package com.atakmap.android.sitrep.net;

import com.atakmap.coremap.maps.coords.GeoPoint;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Cities, towns, unincorporated communities and counties by name, and their outlines,
 * from Census TIGERweb (PLAN decision 13).
 */
public final class Places {

    static final String BASE = "https://tigerweb.geo.census.gov/arcgis/rest/services/TIGERweb/";
    /** Incorporated places, census designated places, counties. */
    static final String[] LAYERS = {
            "Places_CouSub_ConCity_SubMCD/MapServer/4",
            "Places_CouSub_ConCity_SubMCD/MapServer/5",
            "State_County/MapServer/1" };

    /** One answer: what to show, and where to fetch the outline. */
    public static final class Place {
        public final String label;
        final String layer, geoid;
        public final String state;
        final double areaLand;
        /** A named town with no Census boundary (GNIS): its point; NaN otherwise. */
        final double lat, lon;

        Place(String label, String layer, String geoid, String state, double areaLand) {
            this(label, layer, geoid, state, areaLand, Double.NaN, Double.NaN);
        }

        Place(String label, String layer, String geoid, String state, double areaLand, double lat, double lon) {
            this.label = label;
            this.layer = layer;
            this.geoid = geoid;
            this.state = state;
            this.areaLand = areaLand;
            this.lat = lat;
            this.lon = lon;
        }
    }

    private Places() {
    }

    /**
     * Places whose name starts with what was typed, the given state first, then the
     * largest. At most 40.
     */
    public static List<Place> search(String typed, final String preferState) throws Exception {
        return search(typed, preferState, null);
    }

    /** As above, nearest to the map's center first when it is given. */
    public static List<Place> search(String typed, final String preferState, final GeoPoint center) throws Exception {
        String t = typed == null ? "" : typed.trim().toUpperCase(Locale.US).replaceAll("[^A-Z0-9 .,'-]", "");
        // "bayview idaho", "Bayview, ID": the state off the end, as a filter (2026-11-13:
        // the whole string was matched as a name and found nothing).
        String fips = null;
        final String[] typedState = stateAtEnd(t);
        if (typedState != null) {
            fips = typedState[0];
            t = typedState[1];
        }
        final String q = t.replace(",", " ").replaceAll("\\s+", " ").trim().replace("'", "''");
        final List<Place> out = new ArrayList<>();
        if (q.length() < 2)
            return out;
        final String inState = fips == null ? "" : " AND STATE='" + fips + "'";
        final java.util.Set<String> named = new java.util.HashSet<>();
        int census = census("UPPER(BASENAME) LIKE '" + q + "%'" + inState, 25, null, named, out);
        // A typo finds nothing by exact start ("Seatle"; the S22's squashed search box,
        // 2026-11-14, left the operator typing blind): the same three-letter fuzzy match
        // the town search uses, so the city's own boundary is offered, not only a point.
        final String bareQ = q.replace(" ", "");
        if (census == 0 && bareQ.length() >= 4 && bareQ.substring(0, 3).matches("[A-Z0-9]{3}"))
            census("UPPER(BASENAME) LIKE '" + bareQ.substring(0, 3) + "%'" + inState, 200, bareQ, named, out);
        // Towns with no Census boundary (Bayview, Idaho): USGS GNIS populated places,
        // a point each; the report's area is a circle around it. GET: the USGS
        // firewall refuses a POST whose LIKE has "+" for spaces (403, 2026-11-13).
        final String stp = fips == null ? null : POSTAL.get(fips);
        try {
            int found = gnis("UPPER(gaz_name) LIKE '" + q + "%'", stp, null, named, out);
            // Fuzzy: few answers, so every town starting with the same three letters,
            // kept when it is close to what was typed with spaces ignored ("bay view",
            // "bayveiw" -> Bayview).
            final String bare = q.replace(" ", "");
            // Three letters only, never a cut-off quote in the where clause.
            if (found < 3 && bare.length() >= 4 && bare.substring(0, 3).matches("[A-Z0-9]{3}"))
                gnis("UPPER(gaz_name) LIKE '" + bare.substring(0, 3) + "%'", stp, bare, named, out);
        } catch (Exception e) {
            // Census answers alone are still a result.
        }
        // Nearest to the map first (2026-11-13: "bayview" listed 40 Bayviews and cut
        // the one in Idaho the operator was looking at); without a center, the map's
        // state first, then the largest.
        Collections.sort(out, new Comparator<Place>() {
            @Override
            public int compare(Place a, Place b) {
                if (center != null) {
                    final double da = away(a, center), db = away(b, center);
                    if (da != db)
                        return Double.compare(da, db);
                }
                final boolean ah = a.state.equals(preferState), bh = b.state.equals(preferState);
                if (ah != bh)
                    return ah ? -1 : 1;
                return Double.compare(b.areaLand, a.areaLand);
            }
        });
        return out.size() > 40 ? new ArrayList<>(out.subList(0, 40)) : out;
    }

    /** Rough meters from the center to a place's point; places with none go last. */
    private static double away(Place p, GeoPoint c) {
        if (!(Math.abs(p.lat) <= 90) || !(Math.abs(p.lon) <= 180))
            return Double.MAX_VALUE;
        final double dy = (p.lat - c.getLatitude()) * 111_320d;
        final double dx = (p.lon - c.getLongitude()) * 111_320d * Math.cos(Math.toRadians(c.getLatitude()));
        return Math.sqrt(dx * dx + dy * dy);
    }

    private static double num(String s) {
        try {
            return Double.parseDouble(s.trim());
        } catch (RuntimeException e) {
            return Double.NaN;
        }
    }

    /** The city, community and county at a point, smallest first. */
    public static List<Place> at(GeoPoint p) throws Exception {
        final List<Place> out = new ArrayList<>();
        final String geom = Esri.enc(String.format(Locale.US, "%.6f,%.6f", p.getLongitude(), p.getLatitude()));
        for (String layer : LAYERS) {
            final String form = "f=json&returnGeometry=false&where=1%3D1"
                    + "&outFields=" + Esri.enc("NAME,STATE,GEOID,AREALAND")
                    + "&geometry=" + geom + "&geometryType=esriGeometryPoint&inSR=4326"
                    + "&spatialRel=esriSpatialRelIntersects";
            final JSONArray f = Esri.parse(Http.post(BASE + layer + "/query", form)).optJSONArray("features");
            for (int i = 0; f != null && i < f.length(); i++) {
                final JSONObject a = f.optJSONObject(i) == null ? null : f.optJSONObject(i).optJSONObject("attributes");
                if (a == null)
                    continue;
                final String geoid = a.optString("GEOID", "");
                final String st = POSTAL.get(a.optString("STATE", ""));
                if (!geoid.matches("\\d{5,7}") || st == null)
                    continue;
                out.add(new Place(a.optString("NAME", "").replace(" CDP", " (community)") + ", " + st,
                        layer, geoid, st, a.optDouble("AREALAND", 0)));
            }
        }
        Collections.sort(out, new Comparator<Place>() {
            @Override
            public int compare(Place a, Place b) {
                return Double.compare(a.areaLand, b.areaLand);
            }
        });
        return out;
    }

    /** Postal code of the state a point is in (NWS county layer); empty when not known. */
    public static String stateAt(GeoPoint p) {
        if (p == null || !(Math.abs(p.getLatitude()) <= 90) || !(Math.abs(p.getLongitude()) <= 180))
            return "";
        try {
            final String form = "f=json&returnGeometry=false&outFields=state&where=1%3D1"
                    + "&geometry=" + Esri.enc(String.format(Locale.US, "%.6f,%.6f", p.getLongitude(), p.getLatitude()))
                    + "&geometryType=esriGeometryPoint&inSR=4326&spatialRel=esriSpatialRelIntersects";
            final JSONArray f = Esri.parse(Http.post(NWS_COUNTIES + "/query", form)).optJSONArray("features");
            final JSONObject a = f == null || f.length() == 0 || f.optJSONObject(0) == null ? null
                    : f.optJSONObject(0).optJSONObject("attributes");
            final String st = a == null ? "" : a.optString("state", "");
            return st.matches("[A-Z]{2}") ? st : "";
        } catch (Exception e) {
            return "";   // the order is a convenience; the search still answers
        }
    }

    static final String NWS_COUNTIES = "https://mapservices.weather.noaa.gov/static/rest/services/"
            + "nws_reference_maps/nws_reference_map/MapServer/2";

    /** The place's outline: its largest ring, thinned by the server. */
    /**
     * GNIS populated places for a where clause, added to out unless Census already
     * named them; with near, only names within a few letters of it. Returns how many.
     */
    private static int gnis(String like, String postal, String near, java.util.Set<String> named,
            List<Place> out) throws Exception {
        final String where = like + (postal == null ? "" : " AND state_alpha='" + postal + "'");
        final String url = GNIS + "/query?f=json&returnGeometry=true&outSR=4326&resultRecordCount="
                + (near != null ? 400 : postal == null ? 200 : 25) + "&where=" + Esri.enc(where)
                + "&outFields=" + Esri.enc("gaz_id,gaz_name,state_alpha,county_name");
        final JSONArray f = Esri.parse(Http.get(url)).optJSONArray("features");
        final List<Object[]> picked = new ArrayList<>();
        for (int i = 0; f != null && i < f.length(); i++) {
            final JSONObject ft = f.optJSONObject(i);
            final JSONObject a = ft == null ? null : ft.optJSONObject("attributes");
            final JSONObject g = ft == null ? null : ft.optJSONObject("geometry");
            final JSONArray pts = g == null ? null : g.optJSONArray("points");
            final JSONArray xy = pts != null && pts.length() > 0 ? pts.optJSONArray(0) : null;
            final double lon = xy != null ? xy.optDouble(0) : g == null ? Double.NaN : g.optDouble("x"),
                    lat = xy != null ? xy.optDouble(1) : g == null ? Double.NaN : g.optDouble("y");
            if (a == null || !(Math.abs(lat) <= 90) || !(Math.abs(lon) <= 180))
                continue;
            final String st = a.optString("state_alpha", "");
            final String name = a.optString("gaz_name", "").trim();
            final String key = name.toUpperCase(Locale.US) + "|" + st;
            if (name.isEmpty() || !POSTAL.containsValue(st) || !named.add(key))
                continue;
            int d = 0;
            if (near != null) {
                final String n = name.toUpperCase(Locale.US).replace(" ", "");
                d = edits(near, n.length() > near.length() + 3 ? n.substring(0, near.length() + 3) : n);
                if (d > Math.max(1, near.length() / 4)) {
                    named.remove(key);
                    continue;
                }
            }
            final String county = a.optString("county_name", "").trim();
            picked.add(new Object[] { d, new Place(name + ", " + st + " (town, no boundary"
                    + (county.isEmpty() ? "" : ", " + county + " County") + ")",
                    "gnis", String.valueOf(a.optLong("gaz_id")), st, 0, lat, lon) });
        }
        Collections.sort(picked, new Comparator<Object[]>() {
            @Override
            public int compare(Object[] x, Object[] y) {
                return (Integer) x[0] - (Integer) y[0];
            }
        });
        for (int i = 0; i < picked.size(); i++)
            out.add((Place) picked.get(i)[1]);
        return picked.size();
    }

    /**
     * Census incorporated places and communities matching {@code where}; with
     * {@code near}, only names within a few edits of it (spaces ignored), closest first.
     */
    private static int census(String where, int max, String near, java.util.Set<String> named, List<Place> out)
            throws Exception {
        final List<Object[]> picked = new ArrayList<>();
        for (String layer : LAYERS) {
            final String form = "f=json&returnGeometry=false&resultRecordCount=" + max
                    + "&where=" + Esri.enc(where)
                    + "&outFields=" + Esri.enc("NAME,BASENAME,STATE,GEOID,AREALAND,INTPTLAT,INTPTLON");
            final JSONObject o = Esri.parse(Http.post(BASE + layer + "/query", form));
            final JSONArray f = o.optJSONArray("features");
            if (f == null)
                continue;
            for (int i = 0; i < f.length(); i++) {
                final JSONObject a = f.optJSONObject(i) == null ? null : f.optJSONObject(i).optJSONObject("attributes");
                if (a == null)
                    continue;
                final String geoid = a.optString("GEOID", "");
                final String st = POSTAL.get(a.optString("STATE", ""));
                if (!geoid.matches("\\d{5,7}") || st == null)
                    continue;
                final String base = a.optString("BASENAME", "").toUpperCase(Locale.US);
                int d = 0;
                if (near != null) {
                    final String n = base.replace(" ", "");
                    d = edits(near, n.length() > near.length() + 3 ? n.substring(0, near.length() + 3) : n);
                    if (d > Math.max(1, near.length() / 4))
                        continue;
                }
                final String name = a.optString("NAME", "").replace(" CDP", " (community)");
                picked.add(new Object[] { d, new Place(name + ", " + st, layer, geoid, st, a.optDouble("AREALAND", 0),
                        num(a.optString("INTPTLAT", "")), num(a.optString("INTPTLON", ""))) });
                named.add(base + "|" + st);
            }
        }
        Collections.sort(picked, new Comparator<Object[]>() {
            @Override
            public int compare(Object[] x, Object[] y) {
                return (Integer) x[0] - (Integer) y[0];
            }
        });
        for (Object[] x : picked)
            out.add((Place) x[1]);
        return picked.size();
    }

    /** Levenshtein distance, for the fuzzy town match. */
    static int edits(String a, String b) {
        final int[] prev = new int[b.length() + 1], cur = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++)
            prev[j] = j;
        for (int i = 1; i <= a.length(); i++) {
            cur[0] = i;
            for (int j = 1; j <= b.length(); j++)
                cur[j] = Math.min(Math.min(cur[j - 1] + 1, prev[j] + 1),
                        prev[j - 1] + (a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1));
            System.arraycopy(cur, 0, prev, 0, cur.length);
        }
        return prev[b.length()];
    }

    /** A town with no boundary is a circle this far around its point (2 mi). */
    static final double TOWN_RADIUS_M = 3_218.69;

    public static GeoPoint[] outline(Place p) throws Exception {
        if ("gnis".equals(p.layer)) {
            if (!(Math.abs(p.lat) <= 90) || !(Math.abs(p.lon) <= 180))
                throw new java.io.IOException("no point for that town");
            final GeoPoint[] ring = new GeoPoint[48];
            final double mLat = 111_320d, mLon = 111_320d * Math.cos(Math.toRadians(p.lat));
            for (int i = 0; i < ring.length; i++) {
                final double a = 2 * Math.PI * i / ring.length;
                ring[i] = new GeoPoint(p.lat + TOWN_RADIUS_M * Math.cos(a) / mLat,
                        p.lon + TOWN_RADIUS_M * Math.sin(a) / mLon);
            }
            return ring;
        }
        final String form = "f=json&returnGeometry=true&outSR=4326&maxAllowableOffset=0.0005"
                + "&where=" + Esri.enc("GEOID='" + p.geoid + "'") + "&outFields=GEOID";
        final JSONObject o = Esri.parse(Http.post(BASE + p.layer + "/query", form));
        final JSONArray f = o.optJSONArray("features");
        final JSONObject g = f == null || f.length() == 0 || f.optJSONObject(0) == null ? null
                : f.optJSONObject(0).optJSONObject("geometry");
        final JSONArray rings = g == null ? null : g.optJSONArray("rings");
        if (rings == null || rings.length() == 0)
            throw new java.io.IOException("no outline for that place");
        JSONArray best = null;
        double bestArea = -1;
        for (int i = 0; i < rings.length(); i++) {
            final JSONArray r = rings.optJSONArray(i);
            final double a = r == null ? 0 : Math.abs(shoelace(r));
            if (a > bestArea) {
                bestArea = a;
                best = r;
            }
        }
        final List<GeoPoint> pts = new ArrayList<>();
        for (int i = 0; best != null && i < best.length(); i++) {
            final JSONArray p2 = best.optJSONArray(i);
            if (p2 == null)
                continue;
            final double lon = p2.optDouble(0), lat = p2.optDouble(1);
            if (Math.abs(lat) <= 90 && Math.abs(lon) <= 180)
                pts.add(new GeoPoint(lat, lon));
        }
        if (pts.size() < 3)
            throw new java.io.IOException("the outline has fewer than three points");
        return pts.toArray(new GeoPoint[0]);
    }

    private static double shoelace(JSONArray r) {
        double s = 0;
        for (int i = 0; i < r.length(); i++) {
            final JSONArray a = r.optJSONArray(i), b = r.optJSONArray((i + 1) % r.length());
            if (a != null && b != null)
                s += a.optDouble(0) * b.optDouble(1) - b.optDouble(0) * a.optDouble(1);
        }
        return s / 2;
    }

    static final String GNIS = "https://carto.nationalmap.gov/arcgis/rest/services/geonames/MapServer/3";

    /**
     * A state name or postal code at the end of what was typed: {fips, the rest}, or
     * null. "BAYVIEW IDAHO", "BAYVIEW, ID", "SALEM NEW YORK".
     */
    static String[] stateAtEnd(String t) {
        final String s = t.trim();
        for (Map.Entry<String, String> e : NAMES.entrySet()) {
            final String name = e.getKey();
            if (s.length() > name.length() + 1 && s.endsWith(name)) {
                final char before = s.charAt(s.length() - name.length() - 1);
                if (before == ' ' || before == ',')
                    return new String[] { e.getValue(), s.substring(0, s.length() - name.length()).trim() };
            }
        }
        final java.util.regex.Matcher m = java.util.regex.Pattern.compile("^(.+?)[ ,]+([A-Z]{2})$").matcher(s);
        if (m.matches())
            for (Map.Entry<String, String> e : POSTAL.entrySet())
                if (e.getValue().equals(m.group(2)))
                    return new String[] { e.getKey(), m.group(1).trim() };
        return null;
    }

    /** State names to FIPS, longest first so "WEST VIRGINIA" wins over "VIRGINIA". */
    private static final Map<String, String> NAMES = new java.util.TreeMap<>(new Comparator<String>() {
        @Override
        public int compare(String a, String b) {
            return a.length() != b.length() ? b.length() - a.length() : a.compareTo(b);
        }
    });

    static {
        final String[] n = { "01ALABAMA", "02ALASKA", "04ARIZONA", "05ARKANSAS", "06CALIFORNIA", "08COLORADO",
                "09CONNECTICUT", "10DELAWARE", "11DISTRICT OF COLUMBIA", "12FLORIDA", "13GEORGIA", "15HAWAII",
                "16IDAHO", "17ILLINOIS", "18INDIANA", "19IOWA", "20KANSAS", "21KENTUCKY", "22LOUISIANA", "23MAINE",
                "24MARYLAND", "25MASSACHUSETTS", "26MICHIGAN", "27MINNESOTA", "28MISSISSIPPI", "29MISSOURI",
                "30MONTANA", "31NEBRASKA", "32NEVADA", "33NEW HAMPSHIRE", "34NEW JERSEY", "35NEW MEXICO",
                "36NEW YORK", "37NORTH CAROLINA", "38NORTH DAKOTA", "39OHIO", "40OKLAHOMA", "41OREGON",
                "42PENNSYLVANIA", "44RHODE ISLAND", "45SOUTH CAROLINA", "46SOUTH DAKOTA", "47TENNESSEE", "48TEXAS",
                "49UTAH", "50VERMONT", "51VIRGINIA", "53WASHINGTON", "54WEST VIRGINIA", "55WISCONSIN", "56WYOMING",
                "72PUERTO RICO" };
        for (String x : n)
            NAMES.put(x.substring(2), x.substring(0, 2));
    }

    /** State FIPS to postal code. */
    private static final Map<String, String> POSTAL = new HashMap<>();

    static {
        final String[] t = { "01AL", "02AK", "04AZ", "05AR", "06CA", "08CO", "09CT", "10DE", "11DC",
                "12FL", "13GA", "15HI", "16ID", "17IL", "18IN", "19IA", "20KS", "21KY", "22LA", "23ME",
                "24MD", "25MA", "26MI", "27MN", "28MS", "29MO", "30MT", "31NE", "32NV", "33NH", "34NJ",
                "35NM", "36NY", "37NC", "38ND", "39OH", "40OK", "41OR", "42PA", "44RI", "45SC", "46SD",
                "47TN", "48TX", "49UT", "50VT", "51VA", "53WA", "54WV", "55WI", "56WY", "60AS", "66GU",
                "69MP", "72PR", "78VI" };
        for (String x : t)
            POSTAL.put(x.substring(0, 2), x.substring(2));
    }
}
