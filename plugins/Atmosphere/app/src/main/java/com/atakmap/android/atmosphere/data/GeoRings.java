package com.atakmap.android.atmosphere.data;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * GeoJSON areas as plain arrays, for asking whether a point is inside one. The map draws
 * through ATAK's native geometry; this is the pane's side, answering "what is in effect
 * here" without native code and in a unit test.
 *
 * <p>An area is a list of polygons; a polygon is its rings as flat lon, lat pairs,
 * ring 0 the outside and the rest holes. Polygon, MultiPolygon and GeometryCollection
 * (what an alert assembled from several zones becomes) are read; anything else is not
 * an area.
 */
public final class GeoRings {

    /** An area and its bounding box, so most points are refused in four comparisons. */
    public static final class Area {
        final double[][][] polygons;
        final double minLon, minLat, maxLon, maxLat;

        Area(double[][][] polygons) {
            this.polygons = polygons;
            double w = 180, s = 90, e = -180, n = -90;
            for (double[][] poly : polygons) {
                if (poly.length == 0)
                    continue;
                final double[] outer = poly[0];
                for (int i = 0; i + 1 < outer.length; i += 2) {
                    w = Math.min(w, outer[i]);
                    e = Math.max(e, outer[i]);
                    s = Math.min(s, outer[i + 1]);
                    n = Math.max(n, outer[i + 1]);
                }
            }
            minLon = w;
            minLat = s;
            maxLon = e;
            maxLat = n;
        }

        /** Inside an outer ring and outside all of that polygon's holes. */
        public boolean contains(double lat, double lon) {
            if (lon < minLon || lon > maxLon || lat < minLat || lat > maxLat)
                return false;
            for (double[][] poly : polygons) {
                if (poly.length == 0 || !inRing(poly[0], lon, lat))
                    continue;
                boolean inHole = false;
                for (int r = 1; r < poly.length && !inHole; r++)
                    inHole = inRing(poly[r], lon, lat);
                if (!inHole)
                    return true;
            }
            return false;
        }
    }

    private GeoRings() {
    }

    /** The area a geometry covers, or null when it is not an area or will not read. */
    public static Area of(JSONObject g) {
        if (g == null)
            return null;
        final List<double[][]> out = new ArrayList<>();
        try {
            collect(g, out);
        } catch (Exception e) {
            return null;
        }
        return out.isEmpty() ? null : new Area(out.toArray(new double[0][][]));
    }

    /** An area from rings already flat, {@code polygons[p][r] = lon, lat, ...}; null if empty. */
    public static Area fromFlat(double[][][] polygons) {
        return polygons == null || polygons.length == 0 ? null : new Area(polygons);
    }

    /** Several geometries as one area, skipping any that are not areas. */
    public static Area of(List<JSONObject> parts) {
        final List<double[][]> out = new ArrayList<>();
        for (JSONObject g : parts) {
            try {
                collect(g, out);
            } catch (Exception e) {
                // one unreadable zone does not unmake the rest of the alert
            }
        }
        return out.isEmpty() ? null : new Area(out.toArray(new double[0][][]));
    }

    private static void collect(JSONObject g, List<double[][]> out) throws Exception {
        if (g == null)
            return;
        final String type = g.optString("type", "");
        if ("GeometryCollection".equals(type)) {
            final JSONArray gs = g.optJSONArray("geometries");
            for (int i = 0; gs != null && i < gs.length(); i++)
                collect(gs.optJSONObject(i), out);
            return;
        }
        final JSONArray c = g.optJSONArray("coordinates");
        if (c == null)
            return;
        if ("Polygon".equals(type))
            out.add(rings(c));
        else if ("MultiPolygon".equals(type))
            for (int i = 0; i < c.length(); i++)
                out.add(rings(c.getJSONArray(i)));
    }

    private static double[][] rings(JSONArray rings) throws Exception {
        final double[][] out = new double[rings.length()][];
        for (int r = 0; r < rings.length(); r++) {
            final JSONArray ring = rings.getJSONArray(r);
            final double[] flat = new double[ring.length() * 2];
            for (int i = 0; i < ring.length(); i++) {
                final JSONArray p = ring.getJSONArray(i);
                flat[2 * i] = p.getDouble(0);
                flat[2 * i + 1] = p.getDouble(1);
            }
            out[r] = flat;
        }
        return out;
    }

    /** Even-odd crossing test on a flat lon/lat ring. */
    static boolean inRing(double[] ring, double lon, double lat) {
        boolean in = false;
        final int n = ring.length / 2;
        for (int i = 0, j = n - 1; i < n; j = i++) {
            final double xi = ring[2 * i], yi = ring[2 * i + 1];
            final double xj = ring[2 * j], yj = ring[2 * j + 1];
            if ((yi > lat) != (yj > lat) && lon < (xj - xi) * (lat - yi) / (yj - yi) + xi)
                in = !in;
        }
        return in;
    }
}
