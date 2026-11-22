package com.atakmap.android.sitrep;

import com.atakmap.android.sitrep.net.Esri;
import com.atakmap.coremap.maps.coords.GeoPoint;

import org.json.JSONArray;
import org.json.JSONException;

import java.util.Locale;

/**
 * The area the operator drew: one ring of {lat, lon}, not repeated at the end. The
 * only input Sit Rep has (PLAN decision 5), so every section is asked about exactly
 * this.
 */
public final class Area {

    /** Most vertices a ring is kept at; a free-hand outline is thinned to this. */
    static final int MAX_VERTICES = 500;

    public final double[][] ring;
    public final double south, west, north, east;

    public Area(double[][] ring) {
        if (ring == null || ring.length < 3)
            throw new IllegalArgumentException("an area needs three points");
        this.ring = ring;
        double s = 90, w = 180, n = -90, e = -180;
        for (double[] p : ring) {
            s = Math.min(s, p[0]);
            n = Math.max(n, p[0]);
            w = Math.min(w, p[1]);
            e = Math.max(e, p[1]);
        }
        south = s;
        west = w;
        north = n;
        east = e;
    }

    public static Area of(GeoPoint[] pts) {
        int n = pts.length;
        // ATAK may repeat the first point at the end of a closed shape.
        if (n > 3 && pts[0].getLatitude() == pts[n - 1].getLatitude()
                && pts[0].getLongitude() == pts[n - 1].getLongitude())
            n--;
        final int step = Math.max(1, (int) Math.ceil(n / (double) MAX_VERTICES));
        final int kept = (n + step - 1) / step;
        final double[][] r = new double[kept][];
        for (int i = 0, k = 0; i < n && k < kept; i += step, k++)
            r[k] = new double[] { pts[i].getLatitude(), pts[i].getLongitude() };
        return new Area(r);
    }

    /** The middle of the area's box: where a one-point source (a forecast) is asked. */
    public GeoPoint center() {
        return new GeoPoint((south + north) / 2, (west + east) / 2);
    }

    /** Ground area in square meters (shoelace on a local flat projection; fine at county size). */
    public double squareMeters() {
        final double lat0 = Math.toRadians((south + north) / 2);
        final double mLat = 111_320d, mLon = 111_320d * Math.cos(lat0);
        double sum = 0;
        for (int i = 0; i < ring.length; i++) {
            final double[] a = ring[i], b = ring[(i + 1) % ring.length];
            sum += (a[1] * mLon) * (b[0] * mLat) - (b[1] * mLon) * (a[0] * mLat);
        }
        return Math.abs(sum) / 2;
    }

    /** The widest the area is, in meters (its box's diagonal). */
    public double spanMeters() {
        final double lat0 = Math.toRadians((south + north) / 2);
        final double dy = (north - south) * 111_320d, dx = (east - west) * 111_320d * Math.cos(lat0);
        return Math.sqrt(dx * dx + dy * dy);
    }

    /**
     * The ring as an Esri JSON polygon: closed, clockwise (Esri reads a
     * counter-clockwise ring as a hole), WGS84.
     */
    public String esriPolygon() {
        double signed = 0;
        for (int i = 0; i < ring.length; i++) {
            final double[] a = ring[i], b = ring[(i + 1) % ring.length];
            signed += a[1] * b[0] - b[1] * a[0];
        }
        final boolean ccw = signed > 0;
        final int n = ring.length;
        final StringBuilder s = new StringBuilder("{\"rings\":[[");
        for (int k = 0; k <= n; k++) {
            final int i = k % n;   // k == n repeats the first point to close the ring
            final double[] p = ring[ccw ? (n - i) % n : i];
            if (k > 0)
                s.append(',');
            s.append('[').append(String.format(Locale.US, "%.6f", p[1])).append(',')
                    .append(String.format(Locale.US, "%.6f", p[0])).append(']');
        }
        return s.append("]],\"spatialReference\":{\"wkid\":4326}}").toString();
    }

    /** The form fields that limit an ArcGIS query to this area. */
    public String esriQueryForm() {
        return "&geometry=" + Esri.enc(esriPolygon())
                + "&geometryType=esriGeometryPolygon&inSR=4326&spatialRel=esriSpatialRelIntersects";
    }

    /**
     * Whether a ring of GeoJSON positions ({@code [lon, lat]}) touches this area: a
     * corner of either inside the other, or two edges crossing.
     */
    public boolean touches(JSONArray lonLatRing) {
        if (lonLatRing == null || lonLatRing.length() < 3)
            return false;
        final int m = lonLatRing.length();
        final double[][] other = new double[m][];
        double s = 90, w = 180, n = -90, e = -180;
        for (int i = 0; i < m; i++) {
            final JSONArray p = lonLatRing.optJSONArray(i);
            if (p == null || p.length() < 2)
                return false;
            other[i] = new double[] { p.optDouble(1), p.optDouble(0) };
            s = Math.min(s, other[i][0]);
            n = Math.max(n, other[i][0]);
            w = Math.min(w, other[i][1]);
            e = Math.max(e, other[i][1]);
        }
        if (s > north || n < south || w > east || e < west)
            return false;
        for (double[] p : other)
            if (inside(ring, p))
                return true;
        for (double[] p : ring)
            if (inside(other, p))
                return true;
        for (int i = 0; i < ring.length; i++) {
            final double[] a = ring[i], b = ring[(i + 1) % ring.length];
            for (int j = 0; j < m; j++)
                if (cross(a, b, other[j], other[(j + 1) % m]))
                    return true;
        }
        return false;
    }

    /** Whether a point is inside the area. */
    public boolean contains(double lat, double lon) {
        return inside(ring, new double[] { lat, lon });
    }

    /** Meters from a point to the area's edge; 0 inside. Flat at the area's latitude. */
    public double distanceMeters(double lat, double lon) {
        if (contains(lat, lon))
            return 0;
        final double mLat = 111_320d, mLon = 111_320d * Math.cos(Math.toRadians(lat));
        double best = Double.MAX_VALUE;
        for (int i = 0; i < ring.length; i++) {
            final double[] a = ring[i], b = ring[(i + 1) % ring.length];
            final double ax = (a[1] - lon) * mLon, ay = (a[0] - lat) * mLat;
            final double bx = (b[1] - lon) * mLon, by = (b[0] - lat) * mLat;
            final double dx = bx - ax, dy = by - ay, len = dx * dx + dy * dy;
            double t = len == 0 ? 0 : -(ax * dx + ay * dy) / len;
            t = Math.max(0, Math.min(1, t));
            final double x = ax + t * dx, y = ay + t * dy;
            best = Math.min(best, Math.sqrt(x * x + y * y));
        }
        return best;
    }

    /** {@code NE}: which way a point lies from the area's center. */
    public String directionTo(double lat, double lon) {
        final double c = Math.cos(Math.toRadians((south + north) / 2));
        final double deg = (Math.toDegrees(Math.atan2((lon - (west + east) / 2) * c, lat - (south + north) / 2)) + 360) % 360;
        final String[] d = { "N", "NE", "E", "SE", "S", "SW", "W", "NW" };
        return d[(int) Math.round(deg / 45) % 8];
    }

    private static boolean inside(double[][] r, double[] p) {
        boolean in = false;
        for (int i = 0, j = r.length - 1; i < r.length; j = i++) {
            if ((r[i][0] > p[0]) != (r[j][0] > p[0])
                    && p[1] < (r[j][1] - r[i][1]) * (p[0] - r[i][0]) / (r[j][0] - r[i][0]) + r[i][1])
                in = !in;
        }
        return in;
    }

    private static boolean cross(double[] a, double[] b, double[] c, double[] d) {
        final double d1 = turn(c, d, a), d2 = turn(c, d, b), d3 = turn(a, b, c), d4 = turn(a, b, d);
        return ((d1 > 0) != (d2 > 0)) && ((d3 > 0) != (d4 > 0));
    }

    private static double turn(double[] o, double[] a, double[] b) {
        return (a[1] - o[1]) * (b[0] - o[0]) - (a[0] - o[0]) * (b[1] - o[1]);
    }

    public JSONArray toJson() throws JSONException {
        final JSONArray a = new JSONArray();
        for (double[] p : ring)
            a.put(new JSONArray().put(p[0]).put(p[1]));
        return a;
    }

    /** A ring back from a saved report, every coordinate checked; null when it is not usable. */
    public static Area fromJson(JSONArray a) {
        if (a == null || a.length() < 3 || a.length() > MAX_VERTICES + 1)
            return null;
        try {
            final double[][] r = new double[a.length()][];
            for (int i = 0; i < a.length(); i++) {
                final JSONArray p = a.getJSONArray(i);
                final double la = p.getDouble(0), lo = p.getDouble(1);
                if (!(Math.abs(la) <= 90) || !(Math.abs(lo) <= 180))
                    return null;
                r[i] = new double[] { la, lo };
            }
            return new Area(r);
        } catch (JSONException e) {
            return null;
        }
    }
}
