package com.atakmap.android.atmosphere.data;

import org.json.JSONArray;
import org.json.JSONObject;

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
