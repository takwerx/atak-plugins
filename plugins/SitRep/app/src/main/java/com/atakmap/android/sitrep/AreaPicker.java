package com.atakmap.android.sitrep;

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

import java.util.HashSet;
import java.util.Set;

/**
 * The area, drawn with ATAK's own polygon tool: the rubber band, the vertex handles
 * and tap-the-first-point-to-close that an ATAK user already knows. Copied forward
 * from FireCast's {@code PerimeterPicker} ("Draw perimeter"), where two details were
 * load-bearing: the shape tool's buttons live on the drawing toolbar, so it is opened
 * first, and SET_TOOLBAR is a broadcast whose handler ends the active tool, so the
 * tool starts after a short delay.
 *
 * <p>Unlike FireCast, the drawn shape is taken off the map once read: the area is the
 * report's, drawn by {@link AreaOutlines} with the report, and a copy left in ATAK's
 * drawings would come back at every start as one of the operator's own shapes.
 */
public final class AreaPicker implements ToolListener {

    private static final String TAG = "SitRep";
    private static final long TOOLBAR_SETTLE_MS = 400L;

    public interface Callback {
        void onArea(GeoPoint[] ring);

        /** @param reason words for the operator, or null when they simply stopped */
        void onCancelled(String reason);
    }

    private final MapView mapView;
    private final Callback callback;
    private boolean active;
    private Set<String> before = new HashSet<>();
    private int savedColor;
    private boolean colorSaved;

    public AreaPicker(MapView mapView, Callback callback) {
        this.mapView = mapView;
        this.callback = callback;
        ToolManagerBroadcastReceiver.getInstance().registerListener(this);
    }

    public void dispose() {
        if (active)
            cancel();
        ToolManagerBroadcastReceiver.getInstance().unregisterListener(this);
        restoreColor();
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
        final boolean closed = made.isClosed();
        made.removeFromGroup();
        if (!closed || pts == null || pts.length < 3) {
            callback.onCancelled(closed ? "That area has fewer than three points."
                    : "That is a line, not an area. Tap the first point to close the shape.");
            return;
        }
        Log.d(TAG, "area drawn, " + pts.length + " vertices");
        callback.onArea(pts);
    }

    private void borrowColor() {
        if (colorSaved)
            return;
        try {
            final DrawingPreferences prefs = new DrawingPreferences(mapView);
            savedColor = prefs.getShapeColor();
            colorSaved = true;
            prefs.setShapeColor(AreaOutlines.COLOR);
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
