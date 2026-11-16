package com.atakmap.android.atmosphere.data;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TimeZone;
import java.util.regex.Pattern;

/**
 * California's dam failure inundation areas: what DWR's Division of Safety of Dams
 * maps as flooded if a dam, or its spillway, outlet or a saddle dam, failed suddenly.
 *
 * <p>Read from the pack we publish (notes: {@code tools/build_dam_pack.py}): the
 * State Water Board's copy of DSOD's polygons from July 2020, simplified to about
 * 30 m and cut into half-degree tiles under {@code mapdepot.takwerx.org}. The state's
 * own service cannot be asked per view: one Oroville area is 200,000 points and the
 * server will not simplify. The copy is frozen at 2020, so every tap says how old
 * the map is and that newer maps may differ.
 */
public final class DamAreas {
    public static final String HOST = "mapdepot.takwerx.org";
    private static final String BASE = "https://" + HOST + "/atmosphere/dams/";
    public static final double TILE = 0.5;
    /** A tile key from the manifest: two integers, nothing else ever reaches a path. */
    private static final Pattern KEY = Pattern.compile("-?\\d{1,4}_-?\\d{1,4}");

    /** California's box, a little wide: outside it the layer says "California only". */
    private static final double CA_W = -124.6, CA_E = -114.0, CA_S = 32.4, CA_N = 42.1;

    private DamAreas() {
    }

    public static int tileX(double lon) {
        return (int) Math.floor(lon / TILE);
    }

    public static int tileY(double lat) {
        return (int) Math.floor(lat / TILE);
    }

    public static String key(int x, int y) {
        return x + "_" + y;
    }

    public static String manifestUrl() {
        return BASE + "dams.json";
    }

    /** The URL of a tile; the key is checked to be two integers first. */
    public static String tileUrl(String key) {
        if (!KEY.matcher(key).matches())
            throw new IllegalArgumentException("not a tile key");
        return BASE + "tiles/" + key + ".json.gz";
    }

    /** Whether any of a view is in or near California. */
    public static boolean touchesCalifornia(double w, double s, double e, double n) {
        return e >= CA_W && w <= CA_E && n >= CA_S && s <= CA_N;
    }

    /** The manifest: which tiles exist, and which build they belong to. */
    public static final class Manifest {
        public final String built;
        public final Set<String> tiles;

        Manifest(String built, Set<String> tiles) {
            this.built = built;
            this.tiles = tiles;
        }
    }

    public static Manifest manifest(String body) throws JSONException {
        final JSONObject o = new JSONObject(body);
        if (o.optInt("v", 0) != 1)
            throw new JSONException("unknown dam pack version");
        final JSONArray t = o.optJSONArray("tiles");
        final Set<String> tiles = new HashSet<>();
        for (int i = 0; t != null && i < t.length(); i++) {
            final String k = t.optString(i, "");
            if (KEY.matcher(k).matches())
                tiles.add(k);
        }
        final String built = o.optString("built", "");
        return new Manifest(built.length() > 20 ? built.substring(0, 20) : built,
                Collections.unmodifiableSet(tiles));
    }

    /** One area: one dam part, one failure case. */
    public static final class Area {
        public final long id;
        public final String dam, nid, part, kase;
        public final long mapDate;
        /** The dam itself, from the National Inventory of Dams; NaN when it has none. */
        public final double damLat, damLon;
        /** "on Santa Ynez River", "beside Feather River"; empty when NID names none. */
        public final String river;
        /** Polygons; each is rings (outer first, then holes) of lon,lat pairs, flat. */
        public final List<List<double[]>> polygons;

        Area(long id, String dam, String nid, String part, String kase, long mapDate,
                double damLat, double damLon, String river, List<List<double[]>> polygons) {
            this.id = id;
            this.dam = dam;
            this.nid = nid;
            this.part = part;
            this.kase = kase;
            this.mapDate = mapDate;
            this.damLat = damLat;
            this.damLon = damLon;
            this.river = river;
            this.polygons = polygons;
        }

        /** Whether a point is inside: in an outer ring and in none of its holes. */
        public boolean contains(double lat, double lon) {
            for (List<double[]> poly : polygons) {
                if (!FloodZones.inRing(poly.get(0), lon, lat))
                    continue;
                boolean inHole = false;
                for (int i = 1; i < poly.size() && !inHole; i++)
                    inHole = FloodZones.inRing(poly.get(i), lon, lat);
                if (!inHole)
                    return true;
            }
            return false;
        }
    }

    /**
     * A tile's areas. Every ring is repaired before it is kept, as the flood zones'
     * are ({@link FloodZones#repair}): a ring that touches itself makes earcut loop
     * forever and the areas queued behind it never draw.
     */
    public static List<Area> parseTile(String body) throws JSONException {
        final JSONObject o = new JSONObject(body);
        if (o.optInt("v", 0) != 1)
            throw new JSONException("unknown dam tile version");
        final JSONArray f = o.optJSONArray("f");
        final List<Area> out = new ArrayList<>();
        for (int i = 0; f != null && i < f.length(); i++) {
            final JSONObject a = f.optJSONObject(i);
            if (a == null)
                continue;
            final List<List<double[]>> polys = new ArrayList<>();
            final JSONArray g = a.optJSONArray("g");
            for (int p = 0; g != null && p < g.length(); p++) {
                final JSONArray rings = g.optJSONArray(p);
                final List<double[]> poly = new ArrayList<>();
                for (int r = 0; rings != null && r < rings.length(); r++) {
                    final double[] ring = ring(rings.optJSONArray(r));
                    final double[] fixed = ring == null ? null : FloodZones.repair(ring);
                    // An outer ring that does not survive takes its holes with it.
                    if (fixed == null && r == 0)
                        break;
                    if (fixed != null && (poly.isEmpty()
                            || (FloodZones.holds(poly.get(0), fixed)
                                    && !FloodZones.touches(poly.get(0), fixed))))
                        poly.add(fixed);
                }
                if (!poly.isEmpty())
                    polys.add(poly);
            }
            if (polys.isEmpty())
                continue;
            double y = a.optDouble("y", Double.NaN), x = a.optDouble("x", Double.NaN);
            if (!FloodZones.sane(x, y)) {
                y = Double.NaN;
                x = Double.NaN;
            }
            out.add(new Area(a.optLong("i", -1), text(a, "d"), text(a, "n"), text(a, "p"),
                    text(a, "c"), a.optLong("t", 0), y, x, text(a, "r"), polys));
        }
        return out;
    }

    private static double[] ring(JSONArray r) {
        if (r == null || r.length() < 8 || r.length() % 2 != 0)
            return null;
        final double[] pts = new double[r.length()];
        for (int k = 0; k < pts.length; k += 2) {
            pts[k] = r.optDouble(k, Double.NaN);
            pts[k + 1] = r.optDouble(k + 1, Double.NaN);
            if (!FloodZones.sane(pts[k], pts[k + 1]))
                return null;
        }
        return pts;
    }

    /** Server text, trimmed and cut short; it is only ever shown as plain text. */
    private static String text(JSONObject o, String k) {
        String s = o.optString(k, "");
        if ("null".equals(s))
            return "";
        s = s.trim();
        return s.length() > 80 ? s.substring(0, 80) : s;
    }

    /** "Oroville Dam" style: the dam's name as the state writes it, "Herman, Lake" turned. */
    public static String damName(Area a) {
        final String d = a.dam;
        final int c = d.indexOf(", ");
        if (c > 0 && d.indexOf(',', c + 1) < 0)
            return d.substring(c + 2) + " " + d.substring(0, c);
        return d.isEmpty() ? "an unnamed dam" : d;
    }

    /** The failed part in words: main dam, spillway, outlet, saddle dam. */
    public static String partWords(String part) {
        final String p = part == null ? "" : part.toLowerCase(Locale.US).replace(" ", "");
        if (p.startsWith("maindam") || p.isEmpty())
            return "main dam";
        if (p.startsWith("spillway"))
            return "spillway";
        if (p.startsWith("saddledam"))
            return "saddle dam";
        if (p.startsWith("outlet"))
            return "outlet";
        return part;
    }

    /** The dam as a noun: "Gibraltar dam", "Oroville Dam" as the state writes it. */
    public static String damNoun(Area a) {
        final String n = damName(a);
        return n.toLowerCase(Locale.US).contains("dam") || n.startsWith("an ") ? n
                : n + " dam";
    }

    /** Whether this area knows where its dam is. */
    public static boolean hasDam(Area a) {
        return !Double.isNaN(a.damLat) && !Double.isNaN(a.damLon);
    }

    private static final String[] DIRECTION = { "north", "northeast", "east", "southeast",
            "south", "southwest", "west", "northwest" };

    /** "east": the eight-point direction of a bearing in degrees true. */
    public static String direction(double bearing) {
        final double b = ((bearing % 360) + 360) % 360;
        return DIRECTION[(int) Math.round(b / 45) % 8];
    }

    /**
     * Where the dam is from a point, in words: "Gibraltar dam is 10.2 mi east of this
     * point, on Santa Ynez River." The flood from a dam runs miles downstream, often
     * into another dam's lake, and a tap there read as that lake's dam (operator,
     * Lake Cachuma, 2026-11-15). Distance comes formatted in the operator's units.
     */
    public static String whereIsDam(Area a, String distance, double bearing, boolean here) {
        final StringBuilder b = new StringBuilder();
        final String noun = damNoun(a);
        b.append(Character.toUpperCase(noun.charAt(0))).append(noun.substring(1));
        if (here)
            b.append(" is at this point");
        else
            b.append(" is ").append(distance).append(' ').append(direction(bearing))
                    .append(" of this point");
        if (!a.river.isEmpty())
            b.append(", ").append(a.river);
        return b.append('.').toString();
    }

    /** Areas of one dam share this: the NID number, or the name when it has none. */
    public static String damKey(Area a) {
        return a.nid.isEmpty() ? "name:" + a.dam : a.nid;
    }

    /**
     * The title of a tap that lands in one or more areas of one dam. Areas of one dam
     * are one row in the tap (operator, 2026-11-15: "if gibraltar spillway and if
     * gibraltar failed both pink so im confused").
     */
    public static String tapTitle(List<Area> here) {
        if (here.size() == 1)
            return title(here.get(0));
        return "If " + damNoun(here.get(0)) + " failed";
    }

    /** One line per failure that floods the tap point, the main dam first. */
    public static String tapDetails(List<Area> here, String where) {
        final StringBuilder b = new StringBuilder();
        if (where != null && !where.isEmpty())
            b.append(where).append('\n');
        b.append("The state's maps show this point flooded if:");
        final Set<String> lines = new java.util.LinkedHashSet<>();
        final List<Area> ordered = new ArrayList<>(here);
        Collections.sort(ordered, new java.util.Comparator<Area>() {
            @Override
            public int compare(Area x, Area y) {
                final int px = "main dam".equals(partWords(x.part)) ? 0 : 1;
                final int py = "main dam".equals(partWords(y.part)) ? 0 : 1;
                return px != py ? px - py : Long.compare(x.id, y.id);
            }
        });
        long newest = 0;
        for (Area a : ordered) {
            lines.add("\n\u2022 the " + partWords(a.part) + " fails" + caseWords(a.kase));
            newest = Math.max(newest, a.mapDate);
        }
        for (String l : lines)
            b.append(l);
        b.append('\n').append(mapDateLine(newest));
        final Area first = ordered.get(0);
        if (!first.nid.isEmpty())
            b.append("\nNational Inventory of Dams: ").append(first.nid);
        b.append("\nFrom: California DWR, Division of Safety of Dams");
        return b.toString();
    }

    private static String caseWords(String kase) {
        final String k = kase == null ? "" : kase.toLowerCase(Locale.US);
        if (k.startsWith("sunny"))
            return " on a sunny day (reservoir full, no storm)";
        if (k.startsWith("storm"))
            return " during a large flood";
        return "";
    }

    private static String mapDateLine(long mapDate) {
        if (mapDate <= 0)
            return "From the state's July 2020 copy. Newer maps may differ.";
        final SimpleDateFormat f = new SimpleDateFormat("MMMM yyyy", Locale.US);
        f.setTimeZone(TimeZone.getTimeZone("UTC"));
        return "Map of " + f.format(new Date(mapDate))
                + ", from the state's July 2020 copy. Newer maps may differ.";
    }

    public static String title(Area a) {
        final String part = partWords(a.part);
        return "main dam".equals(part) ? "If " + damName(a) + " failed"
                : "If " + damName(a) + "'s " + part + " failed";
    }

    public static String details(Area a) {
        final StringBuilder b = new StringBuilder();
        b.append("The area California's dam safety office maps as flooded if this ")
                .append(partWords(a.part)).append(" failed suddenly.");
        final String k = a.kase.toLowerCase(Locale.US);
        if (k.startsWith("sunny"))
            b.append("\nSunny-day failure: the reservoir full, no storm.");
        else if (k.startsWith("storm"))
            b.append("\nStorm failure: the dam fails during a large flood.");
        if (a.mapDate > 0) {
            final SimpleDateFormat f = new SimpleDateFormat("MMMM yyyy", Locale.US);
            f.setTimeZone(TimeZone.getTimeZone("UTC"));
            b.append("\nMap of ").append(f.format(new Date(a.mapDate)))
                    .append(", from the state's July 2020 copy. Newer maps may differ.");
        } else {
            b.append("\nFrom the state's July 2020 copy. Newer maps may differ.");
        }
        if (!a.nid.isEmpty())
            b.append("\nNational Inventory of Dams: ").append(a.nid);
        b.append("\nFrom: California DWR, Division of Safety of Dams");
        return b.toString();
    }
}
