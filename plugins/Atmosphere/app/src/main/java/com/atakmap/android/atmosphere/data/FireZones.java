package com.atakmap.android.atmosphere.data;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * NWS fire weather zones, and which one a station stands in.
 *
 * <p>Red Flag criteria are set per fire weather zone by the office that forecasts
 * it, so a station's color is only right once the station is joined to its zone.
 * The zones are one live NWS service, layer 9 of {@code nws_reference_map} -- the
 * service, never the dated shapefile ZIPs, which change name at every
 * implementation and leave a hardcoded URL silently fetching nothing (PLAN 10f).
 *
 * <p>One request per origin: every zone within the station radius, generalized to
 * about 200 m, which is 89 zones and a few hundred KB around southern California
 * (measured 2026-09-26). The join is done here, point in polygon, rather than one
 * query per station.
 *
 * <p>No Android types, so the parsing and the join are tested.
 */
public final class FireZones {

    public static final String HOST = "mapservices.weather.noaa.gov";

    private static final String LAYER = "https://" + HOST
            + "/static/rest/services/nws_reference_maps/nws_reference_map/MapServer/9";

    /**
     * Zones within {@code miles} of a point, with their outlines.
     *
     * <p>Generalized to two thousandths of a degree, about 200 m: a station that
     * close to a zone line may be put in the neighbor, which shares an office and
     * usually its criteria. Ungeneralized, the same answer is several megabytes.
     */
    public static String nearUrl(double lat, double lon, double miles) {
        return LAYER + "/query?f=json&returnGeometry=true&outSR=4326"
                + "&maxAllowableOffset=0.002"
                + "&outFields=state_zone,cwa,name,zone,state,idp_source"
                + "&geometryType=esriGeometryPoint&inSR=4326"
                + "&spatialRel=esriSpatialRelIntersects"
                + "&units=esriSRUnit_StatuteMile"
                + "&distance=" + trim(miles)
                + "&geometry=" + trim(lon) + "," + trim(lat);
    }

    /** The side of a lookup cell, degrees. */
    private static final double CELL = 0.5;

    /**
     * Every zone touching the half-degree cell a point is in: which cell, never the
     * point. The zone the point stands in is then found on the phone. Around Los
     * Angeles a cell is 12 zones and 15 KB (2026-09-28).
     */
    public static String cellUrl(double lat, double lon) {
        final double s = Math.floor(lat / CELL) * CELL, w = Math.floor(lon / CELL) * CELL;
        return LAYER + "/query?f=json&returnGeometry=true&outSR=4326"
                + "&maxAllowableOffset=0.002"
                + "&outFields=state_zone,cwa,name"
                + "&geometryType=esriGeometryEnvelope&inSR=4326"
                + "&spatialRel=esriSpatialRelIntersects"
                + "&geometry=" + trim(w) + "," + trim(s) + "," + trim(w + CELL) + ","
                + trim(s + CELL);
    }

    /** Which cell a point is in, so a point moved within it is not asked for again. */
    public static String cellKey(double lat, double lon) {
        return (long) Math.floor(lat / CELL) + "," + (long) Math.floor(lon / CELL);
    }

    /**
     * What was typed as a zone number, the way the service keys it: {@code CAZ548},
     * {@code CA548}, {@code caz 548} and {@code CA 548} are all {@code CA548}. Null
     * when it is not a state and a number.
     */
    public static String normalize(String typed) {
        if (typed == null)
            return null;
        final String t = typed.trim().toUpperCase(Locale.US).replaceAll("[^A-Z0-9]", "");
        if (!t.matches("[A-Z]{2}Z?[0-9]{1,3}"))
            return null;
        final String num = t.substring(t.charAt(2) == 'Z' ? 3 : 2);
        return t.substring(0, 2) + pad3(num);
    }

    /** {@code CA548} as the products write it, {@code CAZ548}. */
    public static String ugc(String stateZone) {
        if (stateZone == null || stateZone.length() != 5)
            return stateZone == null ? "" : stateZone;
        return stateZone.substring(0, 2) + "Z" + stateZone.substring(2);
    }

    /**
     * Every zone touching a box of the map, outlines generalized to {@code offset}
     * degrees, for drawing. The map layer's request: the view, not a position.
     */
    public static String boxUrl(double west, double south, double east, double north,
            double offset) {
        return LAYER + "/query?f=json&returnGeometry=true&outSR=4326"
                + "&maxAllowableOffset=" + trim(offset)
                + "&outFields=state_zone,cwa,name"
                + "&geometryType=esriGeometryEnvelope&inSR=4326"
                + "&spatialRel=esriSpatialRelIntersects"
                + "&geometry=" + trim(west) + "," + trim(south) + "," + trim(east) + ","
                + trim(north);
    }

    /**
     * The named zones, coarse outlines, for a list built from zone numbers (the
     * zones under a warning). Only numbers {@link #normalize} accepts are sent, so
     * the list can hold nothing else; null when none are left.
     */
    public static String idsUrl(Iterable<String> ids) {
        final StringBuilder in = new StringBuilder();
        for (String id : ids) {
            final String n = normalize(id);
            if (n == null)
                continue;
            if (in.length() > 0)
                in.append(',');
            in.append('\'').append(n).append('\'');
        }
        if (in.length() == 0)
            return null;
        return whereUrl("state_zone IN (" + in + ")", "0.01").replace(
                "&resultRecordCount=50", "&resultRecordCount=500");
    }

    /** One zone by its number, with its outline. */
    public static String byIdUrl(String stateZone) {
        return whereUrl("state_zone='" + stateZone + "'", "0.002");
    }

    /**
     * Zones answering to what was typed: a zone number with its state
     * ({@code CAZ548}), a bare number ({@code 548}, every state that has one), or
     * words of a zone's name ({@code san gabriel}). Null when there is nothing to
     * search for.
     *
     * <p>Only letters, digits and spaces reach the service; the where clause is
     * built from nothing else, so there is no quote to close.
     */
    public static String searchUrl(String typed) {
        final String id = normalize(typed);
        if (id != null)
            return whereUrl("state_zone='" + id + "'", "0.01");
        if (typed == null)
            return null;
        final String t = typed.trim().toUpperCase(Locale.US).replaceAll("[^A-Z0-9 ]", " ")
                .replaceAll("\\s+", " ").trim();
        // "Z548" is how a zone is said without its state (operator, 2026-09-30,
        // typed it and got none); it is the bare number.
        final String bare = t.matches("Z ?[0-9]{1,3}") ? t.replaceAll("[^0-9]", "") : t;
        if (bare.matches("[0-9]{1,3}"))
            return whereUrl("zone='" + pad3(bare) + "'", "0.01");
        if (t.replace(" ", "").length() < 3)
            return null;
        return whereUrl("UPPER(name) LIKE '%" + t.replace(' ', '%') + "%'", "0.01");
    }

    private static String whereUrl(String where, String offset) {
        try {
            return LAYER + "/query?f=json&returnGeometry=true&outSR=4326"
                    + "&maxAllowableOffset=" + offset
                    + "&outFields=state_zone,cwa,name"
                    + "&orderByFields=state_zone&resultRecordCount=50"
                    + "&where=" + URLEncoder.encode(where, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String pad3(String digits) {
        final StringBuilder b = new StringBuilder(digits);
        while (b.length() < 3)
            b.insert(0, '0');
        return b.toString();
    }

    private static String trim(double d) {
        return String.format(Locale.US, "%.5f", d).replaceAll("0+$", "").replaceAll("\\.$", "");
    }

    /** One zone: its id the way NWS writes it, {@code CA548}, its office, and its outline. */
    public static final class Zone {
        /** {@code state_zone}, e.g. CA548. The key the criteria tables use. */
        public final String id;
        /** The forecast office, e.g. LOX. */
        public final String cwa;
        public final String name;
        /** Which implementation of the zone file this came from, e.g. fz16ap26. */
        public final String source;
        /** Rings of [lon, lat]. The first is usually the outer; a hole is any other. */
        final List<double[][]> rings;

        Zone(String id, String cwa, String name, String source, List<double[][]> rings) {
            this.id = id;
            this.cwa = cwa;
            this.name = name;
            this.source = source;
            this.rings = rings;
        }

        /** {@code CAZ548}, the number a crew reads in a product or a warning. */
        public String ugc() {
            return FireZones.ugc(id);
        }

        /**
         * The outline as polygons, each its outer ring first and its holes after.
         * The service winds an outer ring clockwise and a hole counterclockwise; a
         * hole goes with the outer ring that holds its first point, and one that
         * none holds is drawn as an outer ring of its own rather than dropped.
         */
        public List<List<double[][]>> polygons() {
            final List<List<double[][]>> out = new ArrayList<>();
            final List<double[][]> holes = new ArrayList<>();
            for (double[][] r : rings) {
                if (signedArea(r) <= 0) {
                    final List<double[][]> p = new ArrayList<>();
                    p.add(r);
                    out.add(p);
                } else {
                    holes.add(r);
                }
            }
            for (double[][] h : holes) {
                List<double[][]> home = null;
                for (List<double[][]> p : out)
                    if (inRing(p.get(0), h[0][0], h[0][1])) {
                        home = p;
                        break;
                    }
                if (home != null) {
                    home.add(h);
                } else {
                    final List<double[][]> p = new ArrayList<>();
                    p.add(h);
                    out.add(p);
                }
            }
            return out;
        }

        /** West, south, east, north of the outline. */
        public double[] bbox() {
            double w = 180, s = 90, e = -180, n = -90;
            for (double[][] ring : rings)
                for (double[] p : ring) {
                    if (Double.isNaN(p[0]) || Double.isNaN(p[1]))
                        continue;
                    w = Math.min(w, p[0]);
                    e = Math.max(e, p[0]);
                    s = Math.min(s, p[1]);
                    n = Math.max(n, p[1]);
                }
            return new double[] { w, s, e, n };
        }

        /** "CA548 -- Los Angeles County San Gabriel Valley (LOX)" */
        public String label() {
            final StringBuilder b = new StringBuilder(id);
            if (name != null && !name.isEmpty())
                b.append(" — ").append(name);
            if (cwa != null && !cwa.isEmpty())
                b.append(" (").append(cwa).append(')');
            return b.toString();
        }

        /**
         * Whether the point is inside: in an odd number of rings, so a hole cut from
         * the outer ring counts it back out.
         */
        public boolean contains(double lat, double lon) {
            int inside = 0;
            for (double[][] ring : rings)
                if (inRing(ring, lon, lat))
                    inside++;
            return (inside & 1) == 1;
        }
    }

    /** Shoelace area in lon/lat: negative when the ring runs clockwise. */
    static double signedArea(double[][] ring) {
        double a = 0;
        for (int i = 0, j = ring.length - 1; i < ring.length; j = i++)
            a += ring[j][0] * ring[i][1] - ring[i][0] * ring[j][1];
        return a / 2;
    }

    /** Ray casting, even-odd, on the ring as given in lon/lat. */
    static boolean inRing(double[][] ring, double x, double y) {
        boolean in = false;
        for (int i = 0, j = ring.length - 1; i < ring.length; j = i++) {
            final double xi = ring[i][0], yi = ring[i][1];
            final double xj = ring[j][0], yj = ring[j][1];
            if ((yi > y) != (yj > y)
                    && x < (xj - xi) * (y - yi) / (yj - yi) + xi)
                in = !in;
        }
        return in;
    }

    /** Every zone in a service answer. A zone with no outline is skipped. */
    public static List<Zone> parse(String body) {
        final List<Zone> out = new ArrayList<>();
        if (body == null || body.isEmpty())
            return out;
        try {
            final JSONArray feats = new JSONObject(body).optJSONArray("features");
            if (feats == null)
                return out;
            for (int i = 0; i < feats.length(); i++) {
                final JSONObject f = feats.optJSONObject(i);
                if (f == null)
                    continue;
                final JSONObject a = f.optJSONObject("attributes");
                final JSONObject g = f.optJSONObject("geometry");
                if (a == null || g == null)
                    continue;
                final JSONArray rings = g.optJSONArray("rings");
                if (rings == null || rings.length() == 0)
                    continue;
                final List<double[][]> parsed = new ArrayList<>();
                for (int r = 0; r < rings.length(); r++) {
                    final JSONArray ring = rings.optJSONArray(r);
                    if (ring == null || ring.length() < 3)
                        continue;
                    final double[][] pts = new double[ring.length()][];
                    for (int k = 0; k < ring.length(); k++) {
                        final JSONArray p = ring.optJSONArray(k);
                        pts[k] = new double[] { p.optDouble(0, Double.NaN),
                                p.optDouble(1, Double.NaN) };
                    }
                    parsed.add(pts);
                }
                if (parsed.isEmpty())
                    continue;
                final String id = str(a, "state_zone");
                if (id.isEmpty())
                    continue;
                out.add(new Zone(id, str(a, "cwa"), str(a, "name"), str(a, "idp_source"),
                        parsed));
            }
        } catch (Exception e) {
            return out;
        }
        return out;
    }

    /**
     * Whether the service stopped short: it returns at most 2,000 records and says
     * {@code exceededTransferLimit} when there were more.
     */
    public static boolean truncated(String body) {
        return body != null && body.replace(" ", "").contains("\"exceededTransferLimit\":true");
    }

    /** The zone the point stands in, or null when none of these hold it. */
    public static Zone at(double lat, double lon, List<Zone> zones) {
        if (zones == null || Double.isNaN(lat) || Double.isNaN(lon))
            return null;
        for (Zone z : zones)
            if (z.contains(lat, lon))
                return z;
        return null;
    }

    private static String str(JSONObject a, String key) {
        final String s = a.optString(key, "");
        return s == null || s.equals("null") ? "" : s.trim();
    }

    private FireZones() {
    }
}
