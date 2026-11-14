package com.atakmap.android.sitrep;

import android.util.SparseArray;

import com.atakmap.android.drawing.mapItems.DrawingShape;
import com.atakmap.android.maps.MapGroup;
import com.atakmap.android.maps.MapItem;
import com.atakmap.android.maps.MapView;
import com.atakmap.android.maps.Marker;
import com.atakmap.android.maps.PointMapItem;
import com.atakmap.android.maps.Polyline;
import com.atakmap.android.maps.Shape;
import com.atakmap.android.overlay.DefaultMapGroupOverlay;
import com.atakmap.android.overlay.MapOverlay;
import com.atakmap.coremap.log.Log;
import com.atakmap.coremap.maps.coords.GeoPoint;
import com.atakmap.coremap.maps.coords.GeoPointMetaData;
import com.atakmap.map.layer.feature.Feature;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The saved reports' areas on the map: one labeled outline per report, in a group of
 * its own under Overlay Manager. The only thing Sit Rep draws besides the points an
 * operator taps through to (PLAN section 3). Built the way Map Depot draws its
 * flight outlines. Main thread.
 */
public final class AreaOutlines {

    private static final String TAG = "SitRep";
    /** What the operator sees in Overlay Manager. */
    static final String NAME = "Sit Rep Areas";
    /** The report id on every outline; a tap on one opens that report. */
    public static final String REPORT_KEY = "sitrep_report";
    /** Cyan, the color takwerx plugins use for their own marks. */
    static final int COLOR = 0xFF00E5FF;

    private final MapView mapView;
    private MapGroup group;
    private MapOverlay overlay;

    AreaOutlines(MapView mapView) {
        this.mapView = mapView;
    }

    private MapGroup group() {
        if (group != null)
            return group;
        final MapGroup root = mapView.getRootGroup();
        MapGroup g = root.findMapGroup(NAME);
        if (g == null)
            g = root.addGroup(NAME);
        g.setMetaBoolean("addToObjList", true);
        g.setMetaBoolean("permaGroup", true);
        g.setMetaString("iconUri", "android.resource://"
                + com.atakmap.android.sitrep.plugin.BuildConfig.APPLICATION_ID
                + "/" + com.atakmap.android.sitrep.plugin.R.drawable.ic_toolbar);
        try {
            overlay = new DefaultMapGroupOverlay(mapView, g);
            final boolean added = mapView.getMapOverlayManager().addOverlay(overlay);
            if (!added)
                Log.w(TAG, "Overlay Manager did not take the areas overlay");
            com.atakmap.android.ipc.AtakBroadcast.getInstance().sendBroadcast(
                    new android.content.Intent("com.atakmap.android.maps.REFRESH_HIERARCHY"));
        } catch (LinkageError | RuntimeException e) {
            Log.w(TAG, "could not register the areas overlay: " + e);
        }
        group = g;
        return g;
    }

    /** Makes the map match the reports: one outline each, shown when Areas and its own switch are ON. */
    void sync(List<Report> reports, boolean visible) {
        try {
            final MapGroup g = group();
            final Map<String, Report> want = new HashMap<>();
            for (Report r : reports)
                want.put(r.id, r);
            for (MapItem it : new ArrayList<>(g.getItems())) {
                final String id = it.getMetaString(REPORT_KEY, null);
                final Report r = id == null ? null : want.remove(id);
                if (r == null) {
                    g.removeItem(it);
                    continue;
                }
                if (!r.name().equals(it.getTitle()))
                    it.setTitle(r.name());
                it.setVisible(visible && r.onMap);
            }
            for (Report r : want.values())
                g.addItem(outline(g, r, visible && r.onMap));
        } catch (LinkageError | RuntimeException e) {
            Log.w(TAG, "could not draw the report areas: " + e);
        }
    }

    private DrawingShape outline(MapGroup g, Report r, boolean visible) {
        final DrawingShape s = new DrawingShape(mapView, g, "sitrep-area-" + r.id);
        final List<GeoPointMetaData> pts = new ArrayList<>(r.area.ring.length + 1);
        for (double[] p : r.area.ring)
            pts.add(GeoPointMetaData.wrap(new GeoPoint(p[0], p[1])));
        pts.add(pts.get(0));
        s.setPoints(pts, new SparseArray<PointMapItem>());
        s.setClosed(true);
        s.setStrokeColor(COLOR);
        s.setFillColor(0x00000000);
        s.setStrokeWeight(3.0d);
        s.addStyleBits(Shape.STYLE_STROKE_MASK | Polyline.STYLE_CLOSED_MASK);
        s.setAltitudeMode(Feature.AltitudeMode.ClampToGround);
        s.setTitle(r.name());
        s.setMetaString(REPORT_KEY, r.id);
        // A report's area, not a drawing: nothing to drag, edit or delete here.
        s.setMetaBoolean("editable", false);
        s.setMetaBoolean("movable", false);
        s.setMetaBoolean("removable", false);
        s.setMetaBoolean("archive", false);
        s.setCenterPointVisible(true);
        s.setCenterPointLabelVisible(true);
        try {
            final Marker m = s.getShapeMarker();
            if (m != null) {
                m.setIconVisibility(Marker.ICON_GONE);
                m.setTextColor(COLOR);
                m.setMetaString(REPORT_KEY, r.id);
            }
        } catch (LinkageError | RuntimeException e) {
            Log.d(TAG, "no center label for a report area: " + e);
        }
        s.setVisible(visible);
        return s;
    }

    /** The report a tapped item belongs to, or null when it is not one of ours. */
    public static String reportOf(MapItem item) {
        if (item == null)
            return null;
        final String id = item.getMetaString(REPORT_KEY, null);
        if (id != null)
            return id;
        final String shape = item.getMetaString("shapeUID", null);
        return shape != null && shape.startsWith("sitrep-area-") ? shape.substring("sitrep-area-".length()) : null;
    }

    /** Takes the outlines and the Overlay Manager row away. */
    void dispose() {
        try {
            if (group != null)
                for (MapItem it : new ArrayList<>(group.getItems()))
                    group.removeItem(it);
            if (overlay != null)
                mapView.getMapOverlayManager().removeOverlay(overlay);
        } catch (RuntimeException e) {
            Log.w(TAG, "could not take the areas off the map: " + e);
        }
        overlay = null;
        group = null;
    }
}
