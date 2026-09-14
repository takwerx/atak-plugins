package com.atakmap.android.dozercountry.map;

import android.content.Intent;
import android.os.Bundle;

import com.atakmap.android.drawing.DrawingToolsMapComponent;
import com.atakmap.android.drawing.DrawingToolsToolbar;
import com.atakmap.android.drawing.mapItems.DrawingRectangle;
import com.atakmap.android.drawing.tools.DrawingRectangleCreationTool;
import com.atakmap.android.ipc.AtakBroadcast;
import com.atakmap.android.maps.MapItem;
import com.atakmap.android.maps.MapView;
import com.atakmap.android.toolbar.Tool;
import com.atakmap.android.toolbar.ToolListener;
import com.atakmap.android.toolbar.ToolManagerBroadcastReceiver;
import com.atakmap.android.toolbar.ToolbarBroadcastReceiver;
import com.atakmap.coremap.log.Log;
import com.atakmap.coremap.maps.coords.GeoBounds;

import java.util.HashSet;
import java.util.Set;

/**
 * Picking an area of interest: run ATAK's own rectangle drawing tool, then take the
 * rectangle it made as the area and remove it.
 *
 * <h3>Why not our own two taps</h3>
 *
 * The first version listened for two {@code MAP_CLICK}s and drew nothing in between,
 * so the operator tapped twice into empty space with no sign either tap had landed and
 * no idea what shape was coming. Their words: "im not seeing my taps in the corners it
 * needs to be like using the polygon tool". They are right, and the answer is not to
 * reinvent the feedback — ATAK already has a rectangle tool with the rubber band, the
 * vertex handles, the prompt and the undo that an ATAK user has already learned.
 *
 * <p>The pattern is FOBS's {@code FreehandTrack}, which drives ATAK's telestration tool
 * the same way: snapshot what exists, start the tool, wait for it to end, take what is
 * new. Two details from FOBS are load-bearing and are why this is not obvious:
 *
 * <ul>
 * <li>The tool's buttons live on ATAK's <b>drawing toolbar</b>, so the toolbar has to
 * be opened first.</li>
 * <li>{@code SET_TOOLBAR} is a <b>broadcast</b>, delivered after the calling method
 * returns, and its handler ends whatever tool is active. Start the tool in the same
 * breath and ATAK ends it immediately — FOBS lost a day to exactly that on
 * 2026-09-05. Hence the delay before starting.</li>
 * </ul>
 *
 * <p>The rectangle is removed once its bounds are taken: the painted overlay is the
 * area, and leaving a duplicate shape behind would clutter the operator's drawing
 * objects and Overlay Manager with something they did not mean to create.
 */
public final class AreaPicker implements ToolListener {

    private static final String TAG = "DozerAreaPicker";

    /** ATAK delivers SET_TOOLBAR as a broadcast; give it time to land. From FOBS. */
    private static final long TOOLBAR_SETTLE_MS = 400L;

    public interface Callback {
        /** An area was drawn. */
        void onAreaPicked(GeoBounds bounds);

        /** The drawing tool started; the operator is drawing. */
        void onPickingStarted();

        void onCancelled();
    }

    private final MapView mapView;
    private final Callback callback;

    private boolean active;
    private Set<String> before = new HashSet<>();

    public AreaPicker(MapView mapView, Callback callback) {
        this.mapView = mapView;
        this.callback = callback;
        ToolManagerBroadcastReceiver.getInstance().registerListener(this);
    }

    /** Must be called when the plugin stops, or the listener outlives the plugin. */
    public void dispose() {
        ToolManagerBroadcastReceiver.getInstance().unregisterListener(this);
    }

    public boolean isActive() {
        return active;
    }

    public void start() {
        if (active)
            return;
        active = true;
        before = rectanglesNow();

        final Intent open = new Intent(ToolbarBroadcastReceiver.SET_TOOLBAR);
        open.putExtra("toolbar", DrawingToolsToolbar.TOOLBAR_IDENTIFIER);
        AtakBroadcast.getInstance().sendBroadcast(open);

        mapView.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (!active)
                    return;
                ToolManagerBroadcastReceiver.getInstance().startTool(
                        DrawingRectangleCreationTool.TOOL_IDENTIFIER, new Bundle());
                callback.onPickingStarted();
            }
        }, TOOLBAR_SETTLE_MS);
    }

    public void cancel() {
        if (!active)
            return;
        active = false;
        final Tool t = ToolManagerBroadcastReceiver.getInstance().getActiveTool();
        if (t != null && DrawingRectangleCreationTool.TOOL_IDENTIFIER
                .equals(t.getIdentifier()))
            ToolManagerBroadcastReceiver.getInstance().endCurrentTool();
        closeToolbar();
        callback.onCancelled();
    }

    @Override
    public void onToolBegin(Tool tool, Bundle extras) {
    }

    @Override
    public void onToolEnded(Tool tool) {
        if (!active || tool == null || !DrawingRectangleCreationTool.TOOL_IDENTIFIER
                .equals(tool.getIdentifier()))
            return;
        active = false;
        // The tool adds its rectangle to the group during onToolEnd; take it on the
        // next loop, once it is actually there.
        mapView.post(new Runnable() {
            @Override
            public void run() {
                collect();
            }
        });
    }

    private void collect() {
        closeToolbar();

        DrawingRectangle made = null;
        for (MapItem i : DrawingToolsMapComponent.getGroup().getItems()) {
            if (i instanceof DrawingRectangle && !before.contains(i.getUID()))
                made = (DrawingRectangle) i;
        }
        if (made == null) {
            // Backed out without completing a rectangle.
            callback.onCancelled();
            return;
        }

        final GeoBounds bounds = GeoBounds.createFromPoints(made.getPoints());
        // The overlay is the area from here on; do not leave a second shape behind.
        made.removeFromGroup();

        if (bounds.getNorth() == bounds.getSouth()
                || bounds.getEast() == bounds.getWest()) {
            callback.onCancelled();
            return;
        }
        Log.d(TAG, "area picked " + bounds);
        callback.onAreaPicked(bounds);
    }

    private void closeToolbar() {
        AtakBroadcast.getInstance().sendBroadcast(
                new Intent(ToolbarBroadcastReceiver.UNSET_TOOLBAR));
    }

    private Set<String> rectanglesNow() {
        final Set<String> uids = new HashSet<>();
        for (MapItem i : DrawingToolsMapComponent.getGroup().getItems())
            if (i instanceof DrawingRectangle)
                uids.add(i.getUID());
        return uids;
    }
}
