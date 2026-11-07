package com.atakmap.android.firecast.model;

import android.content.Intent;
import android.os.Bundle;

import com.atakmap.android.drawing.DrawingPreferences;
import com.atakmap.android.drawing.DrawingToolsMapComponent;
import com.atakmap.android.drawing.DrawingToolsToolbar;
import com.atakmap.android.drawing.mapItems.DrawingShape;
import com.atakmap.android.drawing.tools.ShapeCreationTool;
import com.atakmap.android.ipc.AtakBroadcast;
import com.atakmap.android.maps.MapItem;
import com.atakmap.android.maps.MapView;
import com.atakmap.android.toolbar.Tool;
import com.atakmap.android.toolbar.ToolListener;
import com.atakmap.android.toolbar.ToolManagerBroadcastReceiver;
import com.atakmap.android.toolbar.ToolbarBroadcastReceiver;
import com.atakmap.coremap.log.Log;
import com.atakmap.coremap.maps.coords.GeoPoint;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The fire's perimeter as the operator finds it, drawn with ATAK's own polygon tool:
 * the rubber band, the vertex handles and the tap-the-first-marker-to-close an ATAK
 * user already knows. Copied forward from UAS Flight Plan's area picker, where two
 * details were load-bearing: the shape tool's buttons live on the drawing toolbar,
 * so it is opened first, and SET_TOOLBAR is a broadcast whose handler ends the
 * active tool, so the tool starts after a short delay.
 *
 * <p>The drawn shape stays on the map as the fire's edge at the time it was drawn.
 */
public final class PerimeterPicker implements ToolListener {

    private static final String TAG = "FireCast";
    private static final long TOOLBAR_SETTLE_MS = 400L;
    private static final int DRAW_COLOR = 0xFFFF2A00;
    static final String TITLE = "Fire perimeter";

    public interface Callback {
        void onPerimeter(GeoPoint[] ring);

        void onCancelled(String reason);
    }

    private final MapView mapView;
    private final Callback callback;
    private boolean active;
    private Set<String> before = new HashSet<>();
    private DrawingShape drawn;
    private int savedColor;
    private boolean colorSaved;

    public PerimeterPicker(MapView mapView, Callback callback) {
        this.mapView = mapView;
        this.callback = callback;
        ToolManagerBroadcastReceiver.getInstance().registerListener(this);
    }

    public void dispose() {
        if (active)
            cancel();
        ToolManagerBroadcastReceiver.getInstance().unregisterListener(this);
        restoreColor();
        drawn = null;
    }

    public boolean isActive() {
        return active;
    }

    public void start() {
        if (active)
            return;
        active = true;
        before = shapesNow();
        borrowColor();
        final Intent open = new Intent(ToolbarBroadcastReceiver.SET_TOOLBAR);
        open.putExtra("toolbar", DrawingToolsToolbar.TOOLBAR_IDENTIFIER);
        AtakBroadcast.getInstance().sendBroadcast(open);
        mapView.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (!active)
                    return;
                ToolManagerBroadcastReceiver.getInstance().startTool(ShapeCreationTool.TOOL_IDENTIFIER, new Bundle());
            }
        }, TOOLBAR_SETTLE_MS);
    }

    public void cancel() {
        if (!active)
            return;
        active = false;
        restoreColor();
        final Tool t = ToolManagerBroadcastReceiver.getInstance().getActiveTool();
        if (t != null && ShapeCreationTool.TOOL_IDENTIFIER.equals(t.getIdentifier()))
            ToolManagerBroadcastReceiver.getInstance().endCurrentTool();
        closeToolbar();
        callback.onCancelled(null);
    }

    @Override
    public void onToolBegin(Tool tool, Bundle extras) {
    }

    @Override
    public void onToolEnded(Tool tool) {
        if (!active || tool == null || !ShapeCreationTool.TOOL_IDENTIFIER.equals(tool.getIdentifier()))
            return;
        active = false;
        mapView.post(new Runnable() {
            @Override
            public void run() {
                collect();
            }
        });
    }

    private void collect() {
        restoreColor();
        closeToolbar();
        DrawingShape made = null;
        for (MapItem i : DrawingToolsMapComponent.getGroup().getItems())
            if (i instanceof DrawingShape && !before.contains(i.getUID()))
                made = (DrawingShape) i;
        if (made == null) {
            callback.onCancelled(null);
            return;
        }
        final GeoPoint[] pts = made.getPoints();
        if (!made.isClosed() || pts == null || pts.length < 3) {
            made.removeFromGroup();
            callback.onCancelled(made.isClosed() ? null : "That is a line, not an area. Tap the first marker to close the shape.");
            return;
        }
        clearDrawn();
        drawn = made;
        drawn.setTitle(TITLE);
        drawn.setCenterPointVisible(false);
        drawn.setCenterPointLabelVisible(false);
        drawn.hideLabels(true);
        drawn.setAltitudeMode(com.atakmap.map.layer.feature.Feature.AltitudeMode.ClampToGround);
        drawn.setStrokeColor(DRAW_COLOR);
        drawn.setStrokeWeight(3d);
        drawn.setFillColor(0x00000000);
        Log.d(TAG, "perimeter drawn, " + pts.length + " vertices");
        callback.onPerimeter(pts);
    }

    /** A perimeter with our title already on the map, from an earlier session, or null. */
    public GeoPoint[] adoptExisting() {
        final List<DrawingShape> found = ours();
        if (found.isEmpty())
            return null;
        final DrawingShape keep = found.get(found.size() - 1);
        for (DrawingShape extra : found)
            if (extra != keep && extra.getGroup() != null)
                extra.removeFromGroup();
        final GeoPoint[] pts = keep.getPoints();
        if (!keep.isClosed() || pts == null || pts.length < 3) {
            keep.removeFromGroup();
            return null;
        }
        drawn = keep;
        return pts;
    }

    /**
     * Puts a perimeter that did not come from the drawing tool on the map, a satellite
     * outline, as if it had been traced: same title, same look, so a restart adopts it
     * and Clear takes it off.
     */
    public void show(GeoPoint[] pts) {
        clearDrawn();
        final DrawingShape s = new DrawingShape(mapView, DrawingToolsMapComponent.getGroup(),
                java.util.UUID.randomUUID().toString());
        s.setPoints(com.atakmap.coremap.maps.coords.GeoPointMetaData.wrap(pts));
        s.setClosed(true);
        s.setTitle(TITLE);
        s.setCenterPointVisible(false);
        s.setCenterPointLabelVisible(false);
        s.hideLabels(true);
        s.setAltitudeMode(com.atakmap.map.layer.feature.Feature.AltitudeMode.ClampToGround);
        s.setStrokeColor(DRAW_COLOR);
        s.setStrokeWeight(3d);
        s.setFillColor(0x00000000);
        DrawingToolsMapComponent.getGroup().addItem(s);
        drawn = s;
        Log.d(TAG, "satellite perimeter shown, " + pts.length + " vertices");
    }

    /** Takes the perimeter off the map, and any with our title an earlier instance left. */
    public void clearDrawn() {
        final DrawingShape going = drawn;
        drawn = null;
        if (going != null && going.getGroup() != null)
            going.removeFromGroup();
        for (DrawingShape leftover : ours())
            if (leftover.getGroup() != null)
                leftover.removeFromGroup();
    }

    private List<DrawingShape> ours() {
        final List<DrawingShape> out = new ArrayList<>();
        try {
            for (MapItem i : DrawingToolsMapComponent.getGroup().getItems())
                if (i instanceof DrawingShape && i != drawn && TITLE.equals(i.getTitle()))
                    out.add((DrawingShape) i);
        } catch (RuntimeException e) {
            Log.w(TAG, "could not list the drawing group", e);
        }
        return out;
    }

    private void borrowColor() {
        if (colorSaved)
            return;
        try {
            final DrawingPreferences prefs = new DrawingPreferences(mapView);
            savedColor = prefs.getShapeColor();
            colorSaved = true;
            prefs.setShapeColor(DRAW_COLOR);
        } catch (RuntimeException e) {
            Log.w(TAG, "could not set the drawing color", e);
        }
    }

    private void restoreColor() {
        if (!colorSaved)
            return;
        colorSaved = false;
        try {
            new DrawingPreferences(mapView).setShapeColor(savedColor);
        } catch (RuntimeException e) {
            Log.w(TAG, "could not restore the drawing color", e);
        }
    }

    private void closeToolbar() {
        AtakBroadcast.getInstance().sendBroadcast(new Intent(ToolbarBroadcastReceiver.UNSET_TOOLBAR));
    }

    private Set<String> shapesNow() {
        final Set<String> uids = new HashSet<>();
        for (MapItem i : DrawingToolsMapComponent.getGroup().getItems())
            if (i instanceof DrawingShape)
                uids.add(i.getUID());
        return uids;
    }
}
