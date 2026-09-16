package com.atakmap.android.tfr;

import android.graphics.Color;
import android.util.SparseArray;

import com.atakmap.android.drawing.mapItems.DrawingShape;
import com.atakmap.android.maps.MapGroup;
import com.atakmap.android.maps.MapItem;
import com.atakmap.android.maps.MapView;
import com.atakmap.android.maps.PointMapItem;
import com.atakmap.coremap.log.Log;
import com.atakmap.coremap.maps.coords.GeoPoint;
import com.atakmap.coremap.maps.coords.GeoPointMetaData;
import com.atakmap.map.layer.feature.Feature;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Every shape the plugin owns, in a group of its own.
 *
 * <p>Drawn natively rather than injected as CoT, which is not only about 3D. A CoT marker made
 * on the device is a map item like any other and ATAK may persist or sync it; a shape the plugin
 * creates in its own group is removed cleanly on {@link #dispose()} and cannot leak to the
 * fleet. Nothing here is archived and nothing is persisted.
 *
 * <p>The UID prefix is deliberate. infra-TAK's Node-RED feed publishes the same TFRs as
 * {@code tfr-<notam>} into a Data Sync mission, and a phone can run both; keeping ours under
 * {@code takwerx-tfr-} means the two never adopt each other's items.
 *
 * <p>All of this runs on the main thread. Map items are not safe to touch from the GL thread
 * that dispatches map-moved, and a plugin that does it dies as a native SIGSEGV with no Java
 * stack trace.
 */
public class TfrShapes {

    private static final String TAG = "TfrShapes";

    /** Ours, and nobody else's. */
    public static final String UID_PREFIX = "takwerx-tfr-";
    private static final String GROUP_NAME = "TFR";

    /** Enough fill to read as an area, little enough to see the map through it. */
    private static final int FILL_ALPHA = 0x33;

    private final MapView mapView;
    private MapGroup group;
    /** UID to the shape on the map, so a redraw is a diff and not a teardown. */
    private final Map<String, DrawingShape> drawn = new HashMap<>();

    public TfrShapes(MapView mapView) {
        this.mapView = mapView;
    }

    public void start() {
        if (group == null)
            group = mapView.getRootGroup().addGroup(GROUP_NAME);
    }

    /**
     * Make the map show exactly these TFRs and nothing else.
     *
     * <p>A diff rather than a clear-and-redraw: a refresh every half hour that removed every
     * shape and put it back would flicker the map, and a TFR whose geometry did not change has
     * no reason to move.
     */
    public void draw(Collection<Tfr> tfrs, long nowMs, int activeColor, int upcomingColor) {
        if (group == null)
            start();

        Set<String> wanted = new HashSet<>();
        for (Tfr t : tfrs) {
            List<TfrArea> areas = t.drawable();
            for (int i = 0; i < areas.size(); i++) {
                TfrArea a = areas.get(i);
                String uid = uid(t, i);
                wanted.add(uid);
                boolean active = activeAt(t, a, nowMs);
                int color = active ? activeColor : upcomingColor;
                DrawingShape shape = drawn.get(uid);
                if (shape == null) {
                    shape = create(uid, t, a, areas.size(), i);
                    if (shape == null)
                        continue;
                    drawn.put(uid, shape);
                    group.addItem(shape);
                }
                style(shape, color);
            }
        }

        // Anything no longer wanted comes off. A TFR that has been canceled leaves the list,
        // and a canceled restriction still drawn reads as current airspace -- which is the one
        // failure mode worse than not having the plugin at all.
        List<String> stale = new ArrayList<>();
        for (String uid : drawn.keySet())
            if (!wanted.contains(uid))
                stale.add(uid);
        for (String uid : stale)
            remove(uid);
    }

    /**
     * Whether this area is in effect now. An area with its own schedule is judged on it: nine
     * of the ten multi-area TFRs in the national picture had per-area windows that differed
     * from the NOTAM's, so a TFR can be half on.
     */
    private static boolean activeAt(Tfr t, TfrArea a, long nowMs) {
        if (a.effectiveMs == 0 && a.expireMs == 0)
            return t.isActive(nowMs);
        long slop = t.windowApproximate ? 12L * 3600L * 1000L : 0L;
        long from = a.effectiveMs == 0 ? Long.MIN_VALUE : a.effectiveMs - slop;
        long to = a.expireMs == 0 ? Long.MAX_VALUE : a.expireMs + slop;
        return nowMs >= from && nowMs <= to;
    }

    private DrawingShape create(String uid, Tfr t, TfrArea a, int areaCount, int index) {
        try {
            DrawingShape shape = new DrawingShape(mapView, group, uid);
            List<GeoPointMetaData> pts = new ArrayList<>(a.ring.size());
            for (double[] p : a.ring)
                pts.add(GeoPointMetaData.wrap(new GeoPoint(p[0], p[1])));
            shape.setPoints(pts, new SparseArray<PointMapItem>());
            shape.setClosed(true);
            // On the ground, always, in v0.1. A ring whose points carry altitude sinks under
            // higher terrain and vanishes as you zoom in. The floor and the ceiling are in the
            // label and the row instead; the extruded prism is 0.2, once a wall has been seen
            // on a phone.
            shape.setAltitudeMode(Feature.AltitudeMode.ClampToGround);
            shape.setTitle(title(t, a, areaCount, index));
            shape.setMovable(false);
            shape.setEditable(false);
            // Not ATAK's to keep. No archive, no persist: these come back from the FAA on the
            // next sync, and a stale one restored from the map database would be a lie.
            shape.setMetaBoolean("archive", false);
            shape.setMetaString("menu_exclude", "true");
            return shape;
        } catch (Exception e) {
            Log.w(TAG, "could not build a shape for " + t.notamId, e);
            return null;
        }
    }

    private void style(DrawingShape shape, int color) {
        shape.setStrokeColor(color);
        shape.setStrokeWeight(3.0d);
        shape.setFillColor(Color.argb(FILL_ALPHA, Color.red(color), Color.green(color),
                Color.blue(color)));
    }

    /**
     * What the map label says. The place first, because that is what an operator is looking for,
     * then the vertical limits, because on a TFR that is the question -- can I be under it.
     */
    private static String title(Tfr t, TfrArea a, int areaCount, int index) {
        StringBuilder b = new StringBuilder(t.place());
        if (areaCount > 1)
            b.append(a.name.isEmpty() ? " (area " + (index + 1) + ")" : " (" + a.name + ")");
        b.append("  ").append(a.floor.label()).append(" to ").append(a.ceiling.label());
        return b.toString();
    }

    static String uid(Tfr t, int areaIndex) {
        return UID_PREFIX + t.notamId.replace('/', '-') + "-" + areaIndex;
    }

    private void remove(String uid) {
        DrawingShape shape = drawn.remove(uid);
        if (shape != null)
            shape.removeFromGroup();
    }

    public void clear() {
        for (DrawingShape shape : drawn.values())
            shape.removeFromGroup();
        drawn.clear();
    }

    public int count() {
        return drawn.size();
    }

    /**
     * Take everything off the map.
     *
     * <p>The group is removed as well as its items, and then any straggler carrying our prefix
     * is swept: a plugin that is reloaded mid-life can leave items behind, and an orphaned TFR
     * with nothing maintaining it is the stale-airspace failure again.
     */
    public void dispose() {
        clear();
        if (group != null) {
            mapView.getRootGroup().removeGroup(group);
            group = null;
        }
        List<MapItem> orphans = new ArrayList<>();
        mapView.getRootGroup().deepForEachItem(new MapGroup.MapItemsCallback() {
            @Override
            public boolean onItemFunction(MapItem item) {
                if (item != null && item.getUID() != null && item.getUID().startsWith(UID_PREFIX))
                    orphans.add(item);
                return false;
            }
        });
        for (MapItem item : orphans)
            item.removeFromGroup();
    }
}
