package com.atakmap.android.firecast;

import com.atakmap.coremap.log.Log;
import com.atakmap.map.layer.feature.geometry.Geometry;
import com.atakmap.map.layer.feature.geometry.GeometryCollection;
import com.atakmap.map.layer.feature.geometry.LineString;
import com.atakmap.map.layer.feature.geometry.Polygon;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * NIFC's static industrial heat source mask: the places that read as fires every day
 * and are not. Refineries, flares, steel, landfill gas, some solar fields.
 *
 * <p>It is a filter, not a verdict, and the UI keeps that distinction. A detection
 * inside a mask polygon can be hidden, or flagged and still drawn, and flagged is the
 * default - a refinery fire is still a fire, and an operator who is never shown the
 * detection cannot make that call. The mask is also a dated snapshot (the 2025
 * edition), so a plant built since is not in it and a plant demolished since still is.
 *
 * <p>The polygons carry no attributes at all - the service serves OBJECTID and the
 * shape and nothing else - so there is nothing to tell the operator about which
 * industrial source they are near, only that NIFC has marked the ground.
 */
public class IndustrialMask {

    private static final String TAG = "FireCast";

    /** One masked area: an outer ring and any holes, with a box around it for a cheap reject. */
    private static class Area {
        final double[] outer;
        final List<double[]> holes = new ArrayList<>();
        double minX = Double.MAX_VALUE, minY = Double.MAX_VALUE;
        double maxX = -Double.MAX_VALUE, maxY = -Double.MAX_VALUE;

        Area(double[] outer) {
            this.outer = outer;
            for (int i = 0; i < outer.length; i += 2) {
                minX = Math.min(minX, outer[i]);
                maxX = Math.max(maxX, outer[i]);
                minY = Math.min(minY, outer[i + 1]);
                maxY = Math.max(maxY, outer[i + 1]);
            }
        }
    }

    private final List<Area> areas = new ArrayList<>();
    /** The same polygons as map geometry, for when the operator asks to see the masked ground. */
    private final List<Geometry> shapes = new ArrayList<>();
    /** The box the loaded areas cover; a query outside it has no answer yet. */
    private Esri.Box loaded;
    private long loadedAt;

    /** Whether the mask has been read for an area that covers this one. */
    public synchronized boolean covers(Esri.Box box) {
        return loaded != null && box != null
                && loaded.south <= box.south && loaded.north >= box.north
                && loaded.west <= box.west && loaded.east >= box.east;
    }

    public synchronized int size() {
        return areas.size();
    }

    /**
     * Reads the mask for a box. Worker thread. The dataset is a yearly snapshot, so a
     * box already covered is not fetched again for the life of the plugin.
     */
    public void load(Esri.Box box) throws Exception {
        if (covers(box))
            return;
        final List<Area> fetched = new ArrayList<>();
        final List<Geometry> fetchedShapes = new ArrayList<>();
        Esri.query(Sources.INDUSTRIAL_MASK, "1=1", box, "OBJECTID ASC", 2000, 0, new Esri.FeatureSink() {
            @Override
            public void feature(JSONObject attributes, Geometry g, JSONObject raw) {
                collect(g, fetched);
                fetchedShapes.add(g);
            }
        });
        synchronized (this) {
            areas.clear();
            areas.addAll(fetched);
            shapes.clear();
            shapes.addAll(fetchedShapes);
            loaded = box;
            loadedAt = System.currentTimeMillis();
        }
        Log.d(TAG, "industrial mask: " + fetched.size() + " areas for " + box);
    }

    /** The masked areas as map geometry; empty until a load has happened. */
    public synchronized List<Geometry> shapes() {
        return new ArrayList<>(shapes);
    }

    /** Nothing loaded, so nothing can be said about any point. */
    public synchronized void clear() {
        areas.clear();
        shapes.clear();
        loaded = null;
        loadedAt = 0;
    }

    public synchronized long loadedAt() {
        return loadedAt;
    }

    private static void collect(Geometry g, List<Area> out) {
        if (g instanceof Polygon) {
            final Polygon p = (Polygon) g;
            final LineString ring = p.getExteriorRing();
            if (ring == null || ring.getNumPoints() < 3)
                return;
            final Area a = new Area(flat(ring));
            for (LineString hole : p.getInteriorRings())
                if (hole != null && hole.getNumPoints() > 2)
                    a.holes.add(flat(hole));
            out.add(a);
        } else if (g instanceof GeometryCollection) {
            for (Geometry c : ((GeometryCollection) g).getGeometries())
                collect(c, out);
        }
    }

    private static double[] flat(LineString ring) {
        final double[] r = new double[ring.getNumPoints() * 2];
        for (int i = 0; i < ring.getNumPoints(); i++) {
            r[2 * i] = ring.getX(i);
            r[2 * i + 1] = ring.getY(i);
        }
        return r;
    }

    /** Whether a point falls inside a known industrial heat source. */
    public synchronized boolean contains(double lat, double lon) {
        for (Area a : areas) {
            if (lon < a.minX || lon > a.maxX || lat < a.minY || lat > a.maxY)
                continue;
            if (!inRing(a.outer, lon, lat))
                continue;
            boolean inHole = false;
            for (double[] h : a.holes)
                if (inRing(h, lon, lat)) {
                    inHole = true;
                    break;
                }
            if (!inHole)
                return true;
        }
        return false;
    }

    private static boolean inRing(double[] r, double x, double y) {
        boolean in = false;
        final int n = r.length / 2;
        for (int i = 0, j = n - 1; i < n; j = i++) {
            final double xi = r[2 * i], yi = r[2 * i + 1], xj = r[2 * j], yj = r[2 * j + 1];
            if ((yi > y) != (yj > y) && x < (xj - xi) * (y - yi) / (yj - yi) + xi)
                in = !in;
        }
        return in;
    }
}
