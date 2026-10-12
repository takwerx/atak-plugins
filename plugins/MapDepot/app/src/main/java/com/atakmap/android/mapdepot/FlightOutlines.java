package com.atakmap.android.mapdepot;

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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The footprint of every installed disaster aerial flight, drawn by the plugin.
 *
 * ATAK draws an outline for every GRG it holds, and the Outlines button on
 * the packages page switches those on and off. A streaming tile source gets
 * one too, and it is useless: ATAK registers an XYZ dataset with the extent of
 * its projection ({@code MobacMapSourceLayerInfoSpi}), so the outline of a
 * flight over Hatteras is a box around the world. The catalog knows the real
 * box from NOAA's TileJSON, so the plugin draws that instead, one labeled box
 * per installed flight, following the same button and ATAK's own outlines
 * preference, in a group of its own under Overlay Manager.
 */
public final class FlightOutlines {

    public static final String TAG = "MapDepotOutlines";

    /** What the operator sees in Overlay Manager. */
    public static final String NAME = "Disaster Aerial Outlines";

    private static final String FLIGHT_KEY = "mapdepot_flight";
    private static final int COLOR = 0xFFFF8C00;

    private static MapOverlay overlay;

    private FlightOutlines() {
    }

    /** The group, created and registered the first time. Main thread. */
    private static synchronized MapGroup group(MapView mv) {
        final MapGroup root = mv.getRootGroup();
        MapGroup g = root.findMapGroup(NAME);
        if (g == null)
            g = root.addGroup(NAME);
        if (overlay == null) {
            g.setMetaBoolean("addToObjList", true);
            g.setMetaBoolean("permaGroup", true);
            g.setMetaString("iconUri", "android.resource://"
                    + com.atakmap.android.mapdepot.plugin.BuildConfig.APPLICATION_ID
                    + "/" + com.atakmap.android.mapdepot.plugin.R.drawable.ic_toolbar);
            overlay = new DefaultMapGroupOverlay(mv, g);
            final boolean added = mv.getMapOverlayManager().addOverlay(overlay);
            Log.d(TAG, "overlay registration: added=" + added);
            com.atakmap.android.ipc.AtakBroadcast.getInstance().sendBroadcast(
                    new android.content.Intent(
                            "com.atakmap.android.maps.REFRESH_HIERARCHY"));
        }
        return g;
    }

    /**
     * Makes the map match the catalog and the disk: a box for every flight
     * that is installed, on its own or through its event's all-flights file,
     * nothing for the rest. Boxes already drawn are kept, so a sync does not
     * flicker. Main thread.
     */
    public static void sync(List<Depot.Storm> storms, Set<String> installed,
            boolean visible) {
        final MapView mv = MapView.getMapView();
        if (mv == null)
            return;
        try {
            final MapGroup g = group(mv);
            final Map<String, Depot.Flight> want = new LinkedHashMap<>();
            for (Depot.Storm s : storms) {
                for (Depot.Package p : s.flights) {
                    if (!(p instanceof Depot.Flight))
                        continue;
                    final Depot.Flight f = (Depot.Flight) p;
                    if (f.all || f.bounds() == null)
                        continue;
                    // A streamed flight is boxed by the plugin because ATAK
                    // boxes it as the whole world. An offline copy has a real
                    // box of ATAK's own, so drawing ours too put two boxes on
                    // the map (2026-10-11); ATAK's is left to it.
                    final boolean offline = installed.contains(f.id + "-pack");
                    if (installed.contains(f.id) && !offline && !f.boxed)
                        want.put(f.id, f);
                }
            }
            for (MapItem it : new ArrayList<>(g.getItems())) {
                final String fid = it.getMetaString(FLIGHT_KEY, null);
                if (fid == null)
                    continue;
                if (want.remove(fid) != null)
                    it.setVisible(visible);
                else
                    g.removeItem(it);
            }
            for (Depot.Flight f : want.values())
                g.addItem(box(mv, g, f, visible));
        } catch (LinkageError | RuntimeException notThisBuild) {
            Log.w(TAG, "could not draw flight outlines: " + notThisBuild);
        }
    }

    private static DrawingShape box(MapView mv, MapGroup g, Depot.Flight f,
            boolean visible) {
        final double[] b = f.bounds(); // south, west, north, east
        final DrawingShape s = new DrawingShape(mv, g, "mapdepot-flight-" + f.id);
        final List<GeoPointMetaData> pts = new ArrayList<>(5);
        pts.add(GeoPointMetaData.wrap(new GeoPoint(b[2], b[1])));
        pts.add(GeoPointMetaData.wrap(new GeoPoint(b[2], b[3])));
        pts.add(GeoPointMetaData.wrap(new GeoPoint(b[0], b[3])));
        pts.add(GeoPointMetaData.wrap(new GeoPoint(b[0], b[1])));
        pts.add(GeoPointMetaData.wrap(new GeoPoint(b[2], b[1])));
        s.setPoints(pts, new SparseArray<PointMapItem>());
        s.setClosed(true);
        s.setStrokeColor(COLOR);
        s.setFillColor(0x00000000);
        s.setStrokeWeight(3.0d);
        s.addStyleBits(Shape.STYLE_STROKE_MASK | Polyline.STYLE_CLOSED_MASK);
        s.setAltitudeMode(Feature.AltitudeMode.ClampToGround);
        s.setTitle(f.name);
        s.setMetaString(FLIGHT_KEY, f.id);
        // A footprint, not a drawing: nothing to drag, edit or delete.
        s.setMetaBoolean("editable", false);
        s.setMetaBoolean("movable", false);
        s.setMetaBoolean("removable", false);
        s.setCenterPointVisible(true);
        s.setCenterPointLabelVisible(true);
        try {
            final Marker m = s.getShapeMarker();
            if (m != null) {
                m.setIconVisibility(Marker.ICON_GONE);
                m.setTextColor(COLOR);
            }
        } catch (LinkageError | RuntimeException notThisBuild) {
            Log.d(TAG, "no center marker for " + f.id + ": " + notThisBuild);
        }
        s.setVisible(visible);
        return s;
    }

    /** Take the Overlay Manager row out; leave the group where it is. */
    public static synchronized void unregister(MapView mv) {
        if (overlay == null || mv == null)
            return;
        try {
            mv.getMapOverlayManager().removeOverlay(overlay);
        } catch (RuntimeException gone) {
            Log.w(TAG, "overlay was already gone: " + gone);
        }
        overlay = null;
    }
}
