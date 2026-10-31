package com.atakmap.android.firecast.model;

import com.atakmap.android.firecast.Esri;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

/**
 * NASA's Fire Event Data Suite (Chen et al. 2022): a perimeter for every fire VIIRS
 * sees in the lower 48, drawn by an algorithm from the 375 m detections every 12 hours,
 * day pass and night pass. Not the official perimeter, which incident GIS draws; it is
 * there for the fire nobody has mapped yet and between their updates, and it is pixel
 * built, so its edges are 375 m steps.
 *
 * <p>Served open on NASA VEDA as OGC API Features. The snapshot collection holds each
 * fire's latest perimeter.
 */
public final class Feds {

    /** f=geojson, never f=json: this server answers f=json with a flat list and WKT geometry. */
    private static final String ITEMS = "https://openveda.cloud/api/features/collections/"
            + "public.eis_fire_snapshot_perimeter_nrt/items";
    /** How far from the start a perimeter may be and still be this fire's. */
    public static final double REACH_M = 3000;
    /** The most features and vertices read from one answer; a real box holds a few dozen fires. */
    private static final int MAX_FEATURES = 200, MAX_VERTICES = 100_000;
    /**
     * The model tests every cell against the ring, so a pixel-stepped outline with
     * thousands of corners is thinned to this many. At 375 m pixels that loses nothing.
     */
    private static final int RING_VERTICES = 400;

    /** One fire's satellite perimeter. */
    public static final class Perimeter {
        /** The outer ring, [lat, lon] pairs, not closed. */
        public final double[][] ring;
        /** The 12-hour step it was drawn at, epoch ms UTC. */
        public final long atMs;
        /** Hours the fire had been burning at that step. */
        public final double burningHours;
        /** 0 when the start is inside it, otherwise how far to its nearest corner. */
        public final double distanceM;

        Perimeter(double[][] ring, long atMs, double burningHours, double distanceM) {
            this.ring = ring;
            this.atMs = atMs;
            this.burningHours = burningHours;
            this.distanceM = distanceM;
        }
    }

    private Feds() {
    }

    /**
     * The perimeter around this point, or the nearest within {@link #REACH_M}; null when
     * there is none. Worker thread. Throws when the service cannot be read.
     */
    public static Perimeter near(double lat, double lon) throws Exception {
        if (!(Math.abs(lat) <= 90) || !(Math.abs(lon) <= 180))
            throw new IllegalArgumentException("start out of range");
        final double dLat = REACH_M / 111320d;
        final double dLon = dLat / Math.max(0.02d, Math.cos(Math.toRadians(lat)));
        final String url = ITEMS + "?f=geojson&limit=" + MAX_FEATURES + "&bbox="
                + String.format(Locale.US, "%.4f,%.4f,%.4f,%.4f", lon - dLon, lat - dLat, lon + dLon, lat + dLat);
        final JSONObject o = new JSONObject(Esri.get(url));
        final JSONArray features = o.optJSONArray("features");
        if (features == null)
            return null;
        Perimeter best = null;
        int vertices = 0;
        for (int f = 0; f < features.length() && f < MAX_FEATURES; f++) {
            final JSONObject feature = features.optJSONObject(f);
            if (feature == null)
                continue;
            final JSONObject props = feature.optJSONObject("properties");
            final long at = props == null ? 0 : parseTime(props.optString("t", ""));
            final double hours = props == null ? Double.NaN : props.optDouble("duration", Double.NaN);
            for (double[][] ring : outerRings(feature.optJSONObject("geometry"))) {
                vertices += ring.length;
                if (vertices > MAX_VERTICES)
                    throw new IllegalStateException("satellite perimeters too large to read");
                final double d = Spread.inside(ring, lat, lon) ? 0 : nearestM(ring, lat, lon);
                if (d > REACH_M)
                    continue;
                if (best == null || d < best.distanceM || (d == best.distanceM && at > best.atMs))
                    best = new Perimeter(thin(ring), at, hours, d);
            }
        }
        return best;
    }

    /** The outer ring of each polygon in a Polygon or MultiPolygon, [lat, lon]. */
    private static List<double[][]> outerRings(JSONObject g) {
        final List<double[][]> out = new ArrayList<>();
        if (g == null)
            return out;
        final String type = g.optString("type", "");
        final JSONArray c = g.optJSONArray("coordinates");
        if (c == null)
            return out;
        if ("Polygon".equals(type)) {
            final double[][] r = ring(c.optJSONArray(0));
            if (r != null)
                out.add(r);
        } else if ("MultiPolygon".equals(type)) {
            for (int i = 0; i < c.length(); i++) {
                final JSONArray poly = c.optJSONArray(i);
                final double[][] r = poly == null ? null : ring(poly.optJSONArray(0));
                if (r != null)
                    out.add(r);
            }
        }
        return out;
    }

    /** GeoJSON [lon, lat] positions to [lat, lon], dropping the closing repeat; null when not a usable ring. */
    private static double[][] ring(JSONArray a) {
        if (a == null || a.length() < 4 || a.length() > MAX_VERTICES)
            return null;
        final List<double[]> pts = new ArrayList<>(a.length());
        for (int i = 0; i < a.length(); i++) {
            final JSONArray p = a.optJSONArray(i);
            if (p == null || p.length() < 2)
                return null;
            final double lon = p.optDouble(0, Double.NaN), lat = p.optDouble(1, Double.NaN);
            if (!(Math.abs(lat) <= 90) || !(Math.abs(lon) <= 180))
                return null;
            pts.add(new double[] { lat, lon });
        }
        final double[] first = pts.get(0), last = pts.get(pts.size() - 1);
        if (first[0] == last[0] && first[1] == last[1])
            pts.remove(pts.size() - 1);
        return pts.size() < 3 ? null : pts.toArray(new double[0][]);
    }

    private static double[][] thin(double[][] ring) {
        if (ring.length <= RING_VERTICES)
            return ring;
        final double[][] out = new double[RING_VERTICES][];
        for (int i = 0; i < RING_VERTICES; i++)
            out[i] = ring[(int) ((long) i * ring.length / RING_VERTICES)];
        return out;
    }

    private static double nearestM(double[][] ring, double lat, double lon) {
        final double mLon = 111320d * Math.max(0.02d, Math.cos(Math.toRadians(lat)));
        double best = Double.MAX_VALUE;
        for (double[] p : ring) {
            final double dy = (p[0] - lat) * 111320d, dx = (p[1] - lon) * mLon;
            best = Math.min(best, Math.sqrt(dx * dx + dy * dy));
        }
        return best;
    }

    /** "2026-10-30T12:00:00", UTC with or without a zone; 0 when unreadable. */
    static long parseTime(String t) {
        if (t == null || t.length() < 19)
            return 0;
        try {
            final SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US);
            f.setTimeZone(TimeZone.getTimeZone("UTC"));
            return f.parse(t.substring(0, 19)).getTime();
        } catch (Exception e) {
            return 0;
        }
    }

    /** Across the widest way, meters: whether the model's square can hold it. */
    public static double spanM(double[][] ring) {
        double s = 90, n = -90, w = 180, e = -180;
        for (double[] p : ring) {
            s = Math.min(s, p[0]);
            n = Math.max(n, p[0]);
            w = Math.min(w, p[1]);
            e = Math.max(e, p[1]);
        }
        final double mLon = 111320d * Math.max(0.02d, Math.cos(Math.toRadians((s + n) / 2)));
        return Math.max((n - s) * 111320d, (e - w) * mLon);
    }
}
