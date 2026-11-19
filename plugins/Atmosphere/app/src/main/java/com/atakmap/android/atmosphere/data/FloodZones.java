package com.atakmap.android.atmosphere.data;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * FEMA's National Flood Hazard Layer: the regulatory flood map, not a forecast.
 * Kept apart from the Flash Flood Outlook (WPC rain, days ahead) and Flooded Ground
 * (the river model, now): this one does not change with the weather.
 *
 * <p>Two layers of one public map service, no key:
 * <ul>
 * <li>28, "Flood Hazard Zones": the zone polygons. Asked for one fixed tile at a
 * time by envelope, with the "minimal flood hazard" X zones left out at the server
 * (they cover most of the country and are not drawn).</li>
 * <li>0, "NFHL Availability": where FEMA has a digital map at all. A tile with no
 * study there is "no digital FEMA map", which is a fact from FEMA; a tile with a
 * study and no zones is "no 1% or 0.2% zone". Neither is "no flood risk".</li>
 * </ul>
 *
 * <p>Measured 2026-11-14 (notes, NOTES-atmosphere-fema-flood-zones): paging can be
 * uneven, so a tile pages until {@code exceededTransferLimit} is gone and never
 * stops on a short or empty page; and an ArcGIS error comes back in a 200 body,
 * which is a failure, never an empty answer.
 */
public final class FloodZones {

    public static final String HOST = "hazards.fema.gov";
    private static final String BASE = "https://" + HOST
            + "/arcgis/rest/services/public/NFHL/MapServer/";
    /** Rows per page; the service allows 2,000. */
    public static final int PAGE = 1000;
    /** A tile that needs more pages than this is not a tile; give up on it. */
    public static final int MAX_PAGES = 12;

    /** Drawn bottom to top in this order; the floodway last, on top. */
    public static final int NOT_STUDIED = 0, LEVEE = 1, MODERATE = 2, ONE_PCT = 3,
            COASTAL = 4, FLOODWAY = 5, CLASSES = 6;

    /** The set names: what Overlay Manager and the map key call each class. */
    public static final String[] CLASS_NAMES = {
            "Not studied (zone D)",
            "Behind a levee",
            "0.2% chance (500-year)",
            "1% chance (100-year)",
            "Coastal 1% with waves",
            "Floodway" };

    private static final String WHERE = "NOT (FLD_ZONE='X' AND ZONE_SUBTY='AREA OF MINIMAL"
            + " FLOOD HAZARD') AND FLD_ZONE NOT IN ('OPEN WATER','AREA NOT INCLUDED')";
    private static final String FIELDS = "OBJECTID,FLD_ZONE,ZONE_SUBTY,STATIC_BFE,DEPTH,"
            + "V_DATUM,LEN_UNIT";

    /** One zone polygon, as the plugin keeps it on disk and draws it. */
    public static final class Zone {
        public final long id;
        public final int cls;
        public final String zone, subtype, datum, unit;
        /** -9999 or lower is FEMA's "no value". */
        public final double bfe, depth;
        /** Polygons; each is rings (outer first, then holes) of lon,lat pairs, flat. */
        public final List<List<double[]>> polygons;

        public Zone(long id, int cls, String zone, String subtype, double bfe, double depth,
                String datum, String unit, List<List<double[]>> polygons) {
            this.id = id;
            this.cls = cls;
            this.zone = zone == null ? "" : zone;
            this.subtype = subtype == null ? "" : subtype;
            this.bfe = bfe;
            this.depth = depth;
            this.datum = datum == null ? "" : datum;
            this.unit = unit == null ? "" : unit;
            this.polygons = polygons;
        }

        /** Whether a point is inside: inside an outer ring and in none of its holes. */
        public boolean contains(double lat, double lon) {
            for (List<double[]> poly : polygons) {
                if (poly.isEmpty() || !inRing(poly.get(0), lon, lat))
                    continue;
                boolean inHole = false;
                for (int i = 1; i < poly.size() && !inHole; i++)
                    inHole = inRing(poly.get(i), lon, lat);
                if (!inHole)
                    return true;
            }
            return false;
        }
    }

    private FloodZones() {
    }

    // ---- tiles ----------------------------------------------------------------------

    /** Tile edge, degrees. 0.1 is about 7 by 5 miles; a dense city tile, a few hundred zones. */
    public static final double TILE = 0.1;

    public static int tileX(double lon) {
        return (int) Math.floor((lon + 180d) / TILE);
    }

    public static int tileY(double lat) {
        return (int) Math.floor((lat + 90d) / TILE);
    }

    /** West, south, east, north of a tile. */
    public static double[] tileBox(int x, int y) {
        return new double[] { x * TILE - 180d, y * TILE - 90d, (x + 1) * TILE - 180d,
                (y + 1) * TILE - 90d };
    }

    // ---- requests -------------------------------------------------------------------

    /** How many FEMA studies touch the tile: 0 means no digital map there. */
    public static String availabilityUrl(double[] box) {
        return BASE + "0/query?" + common(box) + "&where=1%3D1&returnCountOnly=true";
    }

    /** One page of the tile's zones. */
    public static String zonesUrl(double[] box, int offset) {
        return BASE + "28/query?" + common(box) + "&where=" + enc(WHERE)
                + "&outFields=" + enc(FIELDS) + "&returnGeometry=true&outSR=4326"
                + "&maxAllowableOffset=0.0001&geometryPrecision=5"
                + "&orderByFields=OBJECTID&resultOffset=" + offset
                + "&resultRecordCount=" + PAGE;
    }

    private static String common(double[] b) {
        return "f=json&geometryType=esriGeometryEnvelope&inSR=4326"
                + "&spatialRel=esriSpatialRelIntersects&geometry="
                + enc(String.format(Locale.US, "%.4f,%.4f,%.4f,%.4f", b[0], b[1], b[2], b[3]));
    }

    private static String enc(String s) {
        try {
            return URLEncoder.encode(s, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            throw new IllegalStateException(e);
        }
    }

    // ---- answers --------------------------------------------------------------------

    /** An ArcGIS answer that is an error, or not JSON at all, as a failure. */
    public static JSONObject answer(String body) throws JSONException {
        final JSONObject o = new JSONObject(body == null ? "" : body);
        if (o.has("error")) {
            final JSONObject e = o.optJSONObject("error");
            throw new JSONException("FEMA answered with an error"
                    + (e == null ? "" : " " + e.optInt("code", 0)));
        }
        return o;
    }

    public static int count(String body) throws JSONException {
        final JSONObject o = answer(body);
        if (!o.has("count"))
            throw new JSONException("FEMA sent no count");
        return Math.max(0, o.optInt("count", 0));
    }

    public static boolean exceeded(JSONObject page) {
        return page.optBoolean("exceededTransferLimit", false);
    }

    /** The zones on one page that are drawn; the rest (open water, unknown) are skipped. */
    public static List<Zone> parsePage(JSONObject page) {
        final List<Zone> out = new ArrayList<>();
        final JSONArray fs = page.optJSONArray("features");
        if (fs == null)
            return out;
        for (int i = 0; i < fs.length(); i++) {
            final JSONObject f = fs.optJSONObject(i);
            if (f == null)
                continue;
            final JSONObject a = f.optJSONObject("attributes");
            final JSONObject g = f.optJSONObject("geometry");
            if (a == null || g == null)
                continue;
            final String zone = clean(a.optString("FLD_ZONE", ""));
            final String sub = clean(a.optString("ZONE_SUBTY", ""));
            final int cls = classOf(zone, sub);
            if (cls < 0)
                continue;
            final List<List<double[]>> polys = polygons(g.optJSONArray("rings"));
            if (polys.isEmpty())
                continue;
            out.add(new Zone(a.optLong("OBJECTID", -1), cls, zone, sub,
                    a.optDouble("STATIC_BFE", -9999), a.optDouble("DEPTH", -9999),
                    clean(a.optString("V_DATUM", "")), clean(a.optString("LEN_UNIT", "")),
                    polys));
        }
        return out;
    }

    /** "null" from optString, and anything long, are not values. */
    private static String clean(String s) {
        if (s == null || "null".equals(s))
            return "";
        s = s.trim();
        return s.length() > 80 ? s.substring(0, 80) : s;
    }

    /** Which class a FEMA zone is drawn as, or -1 for one that is not drawn. */
    public static int classOf(String zone, String subtype) {
        final String z = zone == null ? "" : zone.toUpperCase(Locale.US);
        final String s = subtype == null ? "" : subtype.toUpperCase(Locale.US);
        if (s.contains("FLOODWAY"))
            return FLOODWAY;
        if (z.equals("V") || z.equals("VE") || z.matches("V[0-9]+"))
            return COASTAL;
        if (z.startsWith("A"))
            return ONE_PCT;
        if (z.equals("D"))
            return NOT_STUDIED;
        if (z.equals("X") || z.equals("X500") || z.equals("B")) {
            if (s.contains("MINIMAL"))
                return -1;
            if (s.contains("LEVEE"))
                return LEVEE;
            return MODERATE;
        }
        return -1;
    }

    /**
     * Esri rings as polygons: an outer ring runs clockwise, a hole counter-clockwise.
     * Every ring is cleaned first ({@link #cleanRing}).
     */
    static List<List<double[]>> polygons(JSONArray rings) {
        final List<List<double[]>> out = new ArrayList<>();
        if (rings == null)
            return out;
        final List<double[]> holes = new ArrayList<>();
        for (int r = 0; r < rings.length(); r++) {
            final JSONArray ring = rings.optJSONArray(r);
            if (ring == null || ring.length() < 4)
                continue;
            final double[] flat = new double[ring.length() * 2];
            int n = 0;
            for (int i = 0; i < ring.length(); i++) {
                final JSONArray p = ring.optJSONArray(i);
                if (p == null || p.length() < 2)
                    continue;
                final double lon = p.optDouble(0, Double.NaN), lat = p.optDouble(1, Double.NaN);
                if (!sane(lon, lat))
                    continue;
                flat[n++] = lon;
                flat[n++] = lat;
            }
            final double[] pts = repair(java.util.Arrays.copyOf(flat, n));
            if (pts == null)
                continue;
            if (signedArea(pts) < 0) {
                final List<double[]> poly = new ArrayList<>();
                poly.add(pts);
                out.add(poly);
            } else {
                holes.add(pts);
            }
        }
        // A hole goes to the outer ring that holds every point of it. One that no ring
        // holds -- its outer ring was dropped, or simplification pushed it across the
        // edge -- is dropped: a hole outside its polygon is a ring the fill cannot take.
        for (double[] h : holes) {
            for (List<double[]> poly : out)
                if (holds(poly.get(0), h) && !touches(poly.get(0), h)) {
                    poly.add(h);
                    break;
                }
        }
        return out;
    }

    /**
     * Smaller than this, in square degrees, a ring is dropped: about 1 square meter,
     * far under a pixel at any zoom the layer draws. FEMA's map holds hundreds of
     * slivers this size along zone edges (Carpinteria, 2026-11-14: 990 of 1,190).
     */
    static final double MIN_RING_AREA = 1e-10;

    /**
     * The ring the renderer can take, or null. ATAK fills polygons with earcut, and a
     * ring with a repeated point, a collinear point or a zero-width spike -- what the
     * server's {@code maxAllowableOffset} leaves behind -- makes earcut loop forever
     * on a renderer thread, and the zones queued behind it never draw (s10-dev-2,
     * 2026-11-14). Feature Layer met the same loop ({@code Boundaries.copy}); this is
     * its rule, copied forward. One step of {@link #repair}.
     *
     * <p>Repeated and collinear points go, the ring is closed once, and a ring with
     * under three points left or under {@link #MIN_RING_AREA} goes. Orientation is kept.
     */
    static double[] cleanRing(double[] in) {
        final int n = in.length / 2;
        final double[] xs = new double[n], ys = new double[n];
        int k = 0;
        for (int i = 0; i < n; i++) {
            final double x = in[2 * i], y = in[2 * i + 1];
            if (!sane(x, y))
                continue;
            if (k > 0 && Math.abs(xs[k - 1] - x) < 1e-9 && Math.abs(ys[k - 1] - y) < 1e-9)
                continue;
            if (k > 0 && i + 1 < n) {
                final double nx = in[2 * (i + 1)], ny = in[2 * (i + 1) + 1];
                final double cross = (x - xs[k - 1]) * (ny - ys[k - 1])
                        - (y - ys[k - 1]) * (nx - xs[k - 1]);
                if (Math.abs(cross) < 1e-14)
                    continue;
            }
            xs[k] = x;
            ys[k] = y;
            k++;
        }
        while (k > 1 && Math.abs(xs[k - 1] - xs[0]) < 1e-9 && Math.abs(ys[k - 1] - ys[0]) < 1e-9)
            k--;
        if (k < 3)
            return null;
        final double[] out = new double[(k + 1) * 2];
        for (int i = 0; i < k; i++) {
            out[2 * i] = xs[i];
            out[2 * i + 1] = ys[i];
        }
        out[2 * k] = xs[0];
        out[2 * k + 1] = ys[0];
        return Math.abs(signedArea(out)) < MIN_RING_AREA ? null : out;
    }

    /** Shoelace, lon/lat: negative is clockwise. */
    private static double signedArea(double[] p) {
        double a = 0;
        for (int i = 0; i + 3 < p.length; i += 2)
            a += p[i] * p[i + 3] - p[i + 2] * p[i + 1];
        return a / 2;
    }

    /**
     * The ring with its self-crossings undone, or null when it cannot be. The
     * server's {@code maxAllowableOffset} simplifies each ring on its own and leaves
     * some crossing themselves: 28 of 101 zones in one Carpinteria tile at 0.0001,
     * and still about 25 at 0.00001, against none at full detail, which is 5 MB a
     * tile (2026-11-14). Untangling alone is not enough: see {@link #repair}.
     *
     * <p>Each crossing is undone by a 2-opt move: for edges a-b and c-d that cross,
     * the points from b to c are reversed, so the ring runs a-c and b-d instead. No
     * point moves and the ring only gets shorter, so it ends; the cap is a guard.
     */
    static double[] untangle(double[] closed) {
        final int n = closed.length / 2 - 1;
        if (n < 3)
            return null;
        final double[] x = new double[n], y = new double[n];
        for (int i = 0; i < n; i++) {
            x[i] = closed[2 * i];
            y[i] = closed[2 * i + 1];
        }
        int moves = 0;
        boolean changed = false;
        outer:
        while (true) {
            for (int i = 0; i < n; i++) {
                final int i1 = (i + 1) % n;
                for (int j = i + 2; j < n; j++) {
                    final int j1 = (j + 1) % n;
                    if (j1 == i)
                        continue;
                    if (!cross(x[i], y[i], x[i1], y[i1], x[j], y[j], x[j1], y[j1]))
                        continue;
                    if (++moves > 4 * n)
                        return null;
                    for (int a = i1, b = j; a < b; a++, b--) {
                        final double tx = x[a], ty = y[a];
                        x[a] = x[b];
                        y[a] = y[b];
                        x[b] = tx;
                        y[b] = ty;
                    }
                    changed = true;
                    continue outer;
                }
            }
            break;
        }
        if (!changed)
            return closed;
        final double[] out = new double[(n + 1) * 2];
        for (int i = 0; i < n; i++) {
            out[2 * i] = x[i];
            out[2 * i + 1] = y[i];
        }
        out[2 * n] = x[0];
        out[2 * n + 1] = y[0];
        return Math.abs(signedArea(out)) < MIN_RING_AREA ? null : out;
    }

    /**
     * The ring earcut can take, or null. Clean, then untangle, until neither changes
     * the ring; then refuse one that still touches itself. Untangling reverses runs of
     * points, which can leave a repeated or collinear point, or two edges lying on
     * each other, and on such a ring ATAK's earcut loops forever: two threads at 100%
     * for minutes, and every zone queued behind them never drew, for the rest of that
     * ATAK run (s10-dev-2, 2026-11-14). Feature Layer met the same loop
     * ({@code Boundaries.copy}).
     */
    static double[] repair(double[] ring) {
        double[] r = ring;
        for (int pass = 0; pass < 6; pass++) {
            final double[] c = cleanRing(r);
            if (c == null)
                return null;
            final double[] u = untangle(c);
            if (u == null)
                return null;
            // Untangle hands back the same array when it moved nothing: clean and flat.
            if (u == c)
                return simple(c) ? c : null;
            r = u;
        }
        return null;
    }

    /**
     * Whether a closed ring is simple: no two edges that are not neighbors meet at
     * all, crossing or touching, and no point repeats.
     */
    static boolean simple(double[] p) {
        final int n = p.length / 2 - 1;
        for (int i = 0; i < n; i++) {
            final int i1 = i + 1;
            for (int j = i + 1; j < n; j++) {
                final int j1 = j + 1;
                if (j == i1 || (i == 0 && j1 == n))
                    continue;
                if (meet(p[2 * i], p[2 * i + 1], p[2 * i1], p[2 * i1 + 1],
                        p[2 * j], p[2 * j + 1], p[2 * j1], p[2 * j1 + 1]))
                    return false;
            }
        }
        return true;
    }

    /** Whether two segments share any point, touching included. */
    private static boolean meet(double ax, double ay, double bx, double by, double cx,
            double cy, double dx, double dy) {
        if (Math.max(ax, bx) < Math.min(cx, dx) || Math.max(cx, dx) < Math.min(ax, bx)
                || Math.max(ay, by) < Math.min(cy, dy) || Math.max(cy, dy) < Math.min(ay, by))
            return false;
        final double o1 = orient(ax, ay, bx, by, cx, cy), o2 = orient(ax, ay, bx, by, dx, dy);
        final double o3 = orient(cx, cy, dx, dy, ax, ay), o4 = orient(cx, cy, dx, dy, bx, by);
        if (o1 * o2 < 0 && o3 * o4 < 0)
            return true;
        return (o1 == 0 && on(ax, ay, bx, by, cx, cy)) || (o2 == 0 && on(ax, ay, bx, by, dx, dy))
                || (o3 == 0 && on(cx, cy, dx, dy, ax, ay))
                || (o4 == 0 && on(cx, cy, dx, dy, bx, by));
    }

    /** Cross product sign, with near-zero (under 1e-14 square degrees) as zero. */
    private static double orient(double ax, double ay, double bx, double by, double cx,
            double cy) {
        final double v = (bx - ax) * (cy - ay) - (by - ay) * (cx - ax);
        return Math.abs(v) < 1e-14 ? 0 : v;
    }

    private static boolean on(double ax, double ay, double bx, double by, double px,
            double py) {
        return px >= Math.min(ax, bx) - 1e-12 && px <= Math.max(ax, bx) + 1e-12
                && py >= Math.min(ay, by) - 1e-12 && py <= Math.max(ay, by) + 1e-12;
    }

    /** Whether two segments cross at a point inside both. Touching does not count. */
    private static boolean cross(double ax, double ay, double bx, double by, double cx,
            double cy, double dx, double dy) {
        final double o1 = (bx - ax) * (cy - ay) - (by - ay) * (cx - ax);
        final double o2 = (bx - ax) * (dy - ay) - (by - ay) * (dx - ax);
        final double o3 = (dx - cx) * (ay - cy) - (dy - cy) * (ax - cx);
        final double o4 = (dx - cx) * (by - cy) - (dy - cy) * (bx - cx);
        return o1 * o2 < 0 && o3 * o4 < 0;
    }

    /**
     * Whether any edge of one closed ring meets any edge of the other, touching
     * included: a hole that touches its outer ring is a polygon earcut cannot fill.
     */
    static boolean touches(double[] a, double[] b) {
        for (int i = 0; i + 3 < a.length; i += 2)
            for (int j = 0; j + 3 < b.length; j += 2)
                if (meet(a[i], a[i + 1], a[i + 2], a[i + 3], b[j], b[j + 1], b[j + 2],
                        b[j + 3]))
                    return true;
        return false;
    }

    static boolean holds(double[] outer, double[] ring) {
        for (int i = 0; i + 1 < ring.length; i += 2)
            if (!inRing(outer, ring[i], ring[i + 1]))
                return false;
        return true;
    }

    static boolean inRing(double[] p, double x, double y) {
        boolean in = false;
        for (int i = 0, j = p.length - 2; i < p.length; j = i, i += 2) {
            final double xi = p[i], yi = p[i + 1], xj = p[j], yj = p[j + 1];
            if ((yi > y) != (yj > y) && x < (xj - xi) * (y - yi) / (yj - yi) + xi)
                in = !in;
        }
        return in;
    }

    static boolean sane(double lon, double lat) {
        return !Double.isNaN(lon) && !Double.isNaN(lat) && !Double.isInfinite(lon)
                && !Double.isInfinite(lat) && lon >= -180 && lon <= 180 && lat >= -90
                && lat <= 90;
    }

    // ---- what a tap says ------------------------------------------------------------

    /** The heading of a tap: "1% annual chance flood (100-year), zone AE". */
    public static String title(Zone z) {
        final String zone = z.zone.isEmpty() ? "" : ", zone " + z.zone;
        switch (z.cls) {
            case FLOODWAY:
                return "Floodway" + zone;
            case COASTAL:
                return "Coastal 1% annual chance flood with waves" + zone;
            case ONE_PCT:
                return "1% annual chance flood (100-year)" + zone;
            case MODERATE:
                return z.subtype.contains("0.2 PCT") ? "0.2% annual chance flood (500-year)"
                        + zone : "Moderate flood hazard" + zone;
            case LEVEE:
                return "Lower flood risk behind a levee" + zone;
            default:
                return "Flood risk not studied" + zone;
        }
    }

    /** The details: plain words, FEMA's numbers when it gives them, no field names. */
    public static String details(Zone z) {
        final StringBuilder b = new StringBuilder();
        final String zu = z.zone.toUpperCase(Locale.US);
        final String s = z.subtype.toUpperCase(Locale.US);
        switch (z.cls) {
            case FLOODWAY:
                b.append("\nThe channel and the land beside it that must stay open to carry"
                        + " the 1% (100-year) flood. Water here runs deep and fast.");
                break;
            case COASTAL:
                b.append("\n1% chance each year of a coastal flood with waves 3 ft or higher.");
                break;
            case ONE_PCT:
                b.append("\n1% chance of flooding each year: about 1 in 4 over 30 years.");
                if (zu.equals("AO"))
                    b.append("\nShallow flooding, sheet flow on sloping ground, 1 to 3 ft deep.");
                else if (zu.equals("AH"))
                    b.append("\nShallow flooding, ponding, 1 to 3 ft deep.");
                else if (zu.startsWith("AR"))
                    b.append("\nA flood control system here is being restored.");
                else if (zu.equals("A99"))
                    b.append("\nTo be protected by a flood control system still being built.");
                else if (zu.equals("A"))
                    b.append("\nFEMA has not set a flood elevation here.");
                break;
            case MODERATE:
                if (s.contains("0.2 PCT"))
                    b.append("\n0.2% chance of flooding each year. Outside the 1% flood area.");
                else if (s.contains("DEPTH LESS THAN 1 FOOT"))
                    b.append("\n1% chance each year of a flood less than 1 ft deep.");
                else if (s.contains("DRAINAGE AREA"))
                    b.append("\n1% chance flood from a stream draining less than 1 square mile.");
                else if (s.contains("FUTURE"))
                    b.append("\n1% chance flood with the land use the community expects.");
                else
                    b.append("\nOutside the 1% flood area, still at some risk.");
                break;
            case LEVEE:
                b.append("\nA levee FEMA accredits keeps the 1% flood out. A levee can fail"
                        + " or be overtopped.");
                break;
            default:
                b.append("\nFEMA has not studied the flood risk here. The risk is not known,"
                        + " not low.");
                break;
        }
        final String unit = unitWord(z.unit);
        if (z.bfe > -9000)
            b.append("\nBase flood elevation: ").append(number(z.bfe)).append(' ').append(unit)
                    .append(z.datum.isEmpty() ? "" : " (" + z.datum + ")");
        if (z.depth > -9000)
            b.append("\nFlood depth: ").append(number(z.depth)).append(' ').append(unit);
        b.append("\nFrom: FEMA flood map (National Flood Hazard Layer)");
        // The title is the details page's heading already; the text starts below it.
        return b.charAt(0) == '\n' ? b.substring(1) : b.toString();
    }

    private static String unitWord(String unit) {
        final String u = unit == null ? "" : unit.toLowerCase(Locale.US);
        if (u.startsWith("met"))
            return "m";
        return "ft";
    }

    private static String number(double v) {
        return v == Math.rint(v) ? String.format(Locale.US, "%.0f", v)
                : String.format(Locale.US, "%.1f", v);
    }

    // ---- on disk --------------------------------------------------------------------

    /** A tile as this plugin keeps it: our own format, never the server's body. */
    public static String toJson(long fetched, boolean mapped, List<Zone> zones)
            throws JSONException {
        final JSONObject o = new JSONObject();
        o.put("v", FORMAT);
        o.put("t", fetched);
        o.put("m", mapped);
        final JSONArray arr = new JSONArray();
        for (Zone z : zones) {
            final JSONObject j = new JSONObject();
            j.put("i", z.id);
            j.put("z", z.zone);
            j.put("s", z.subtype);
            j.put("b", z.bfe);
            j.put("d", z.depth);
            j.put("vd", z.datum);
            j.put("u", z.unit);
            final JSONArray ps = new JSONArray();
            for (List<double[]> poly : z.polygons) {
                final JSONArray rs = new JSONArray();
                for (double[] ring : poly) {
                    final JSONArray r = new JSONArray();
                    for (double v : ring)
                        r.put(Math.round(v * 1e5) / 1e5);
                    rs.put(r);
                }
                ps.put(rs);
            }
            j.put("p", ps);
            arr.put(j);
        }
        o.put("z", arr);
        return o.toString();
    }

    /** The tile file format. 3: rings repaired (clean, untangled, simple) before they are kept. */
    private static final int FORMAT = 3;

    /** A kept tile read back; null when the file is not one. */
    public static Tile fromJson(String body) {
        try {
            final JSONObject o = new JSONObject(body);
            // Version 1 tiles hold rings as the server simplified them, some crossing
            // themselves; they are fetched again rather than drawn.
            if (o.optInt("v", 0) != FORMAT)
                return null;
            final List<Zone> zones = new ArrayList<>();
            final JSONArray arr = o.optJSONArray("z");
            for (int i = 0; arr != null && i < arr.length(); i++) {
                final JSONObject j = arr.optJSONObject(i);
                if (j == null)
                    continue;
                final String zone = j.optString("z", ""), sub = j.optString("s", "");
                final int cls = classOf(zone, sub);
                if (cls < 0)
                    continue;
                final List<List<double[]>> polys = new ArrayList<>();
                final JSONArray ps = j.optJSONArray("p");
                for (int p = 0; ps != null && p < ps.length(); p++) {
                    final JSONArray rs = ps.optJSONArray(p);
                    final List<double[]> poly = new ArrayList<>();
                    for (int r = 0; rs != null && r < rs.length(); r++) {
                        final JSONArray ring = rs.optJSONArray(r);
                        if (ring == null || ring.length() < 8 || ring.length() % 2 != 0)
                            continue;
                        final double[] pts = new double[ring.length()];
                        boolean ok = true;
                        for (int k = 0; k < pts.length && ok; k += 2) {
                            pts[k] = ring.optDouble(k, Double.NaN);
                            pts[k + 1] = ring.optDouble(k + 1, Double.NaN);
                            ok = sane(pts[k], pts[k + 1]);
                        }
                        // Repaired again, as on fetch: a file from an older build, or
                        // one changed on the device, must not reach earcut as written.
                        // A ring repair already passed comes back the same.
                        final double[] clean = ok ? repair(pts) : null;
                        // An outer ring that does not survive takes its holes with it.
                        if (clean == null && r == 0)
                            break;
                        if (clean != null && (poly.isEmpty()
                                || (holds(poly.get(0), clean) && !touches(poly.get(0), clean))))
                            poly.add(clean);
                    }
                    if (!poly.isEmpty())
                        polys.add(poly);
                }
                if (!polys.isEmpty())
                    zones.add(new Zone(j.optLong("i", -1), cls, zone, sub,
                            j.optDouble("b", -9999), j.optDouble("d", -9999),
                            j.optString("vd", ""), j.optString("u", ""), polys));
            }
            return new Tile(o.optLong("t", 0), o.optBoolean("m", true), zones);
        } catch (JSONException e) {
            return null;
        }
    }

    /** One tile: when it was fetched, whether FEMA maps it, and its zones. */
    public static final class Tile {
        public final long fetched;
        public final boolean mapped;
        public final List<Zone> zones;

        public Tile(long fetched, boolean mapped, List<Zone> zones) {
            this.fetched = fetched;
            this.mapped = mapped;
            this.zones = zones;
        }
    }
}
