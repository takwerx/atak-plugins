package com.atakmap.android.firecast.model;

import com.atakmap.coremap.maps.coords.GeoPoint;

import java.util.ArrayList;
import java.util.List;

/**
 * A shape's points as rings. ATAK hands a multi-part polygon from another plugin's
 * feature layer (Feature Layer's FIRIS perimeters) as one shape whose points are every
 * ring one after another (FeatureDataStoreDeepMapItemQuery's collection MetaShape),
 * each ring closed. They are split where a point returns to its ring's first point.
 * A ring that never closes is the whole list: an ATAK drawn polygon does not repeat
 * its first point.
 */
final class ShapeRings {

    private ShapeRings() {
    }

    /** The rings, [lat, lon], closing points dropped; empty when nothing has three corners. */
    static List<double[][]> split(GeoPoint[] pts) {
        final List<double[][]> out = new ArrayList<>();
        int start = 0;
        for (int i = 0; i < pts.length; i++) {
            if (pts[i] == null || !(Math.abs(pts[i].getLatitude()) <= 90) || !(Math.abs(pts[i].getLongitude()) <= 180))
                return new ArrayList<>();
            if (i - start >= 3 && same(pts[i], pts[start])) {
                add(out, pts, start, i);
                start = i + 1;
            }
        }
        if (pts.length - start >= 3)
            add(out, pts, start, pts.length);
        return out;
    }

    /** Whether the points end where they began, so an open line is told from an area. */
    static boolean closes(GeoPoint[] pts) {
        return pts.length >= 4 && same(pts[0], pts[pts.length - 1]);
    }

    private static void add(List<double[][]> out, GeoPoint[] pts, int from, int to) {
        final double[][] ring = new double[to - from][];
        for (int i = from; i < to; i++)
            ring[i - from] = new double[] { pts[i].getLatitude(), pts[i].getLongitude() };
        out.add(ring);
    }

    private static boolean same(GeoPoint a, GeoPoint b) {
        return Math.abs(a.getLatitude() - b.getLatitude()) < 1e-9 && Math.abs(a.getLongitude() - b.getLongitude()) < 1e-9;
    }
}
