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
