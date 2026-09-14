package com.atakmap.android.dozercountry.map;

import android.content.Intent;
import android.os.Bundle;

import com.atakmap.android.drawing.DrawingPreferences;
import com.atakmap.android.drawing.DrawingToolsMapComponent;
import com.atakmap.android.drawing.DrawingToolsToolbar;
import com.atakmap.android.drawing.mapItems.DrawingRectangle;
import com.atakmap.android.drawing.mapItems.DrawingShape;
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
import com.atakmap.coremap.maps.coords.GeoPoint;
import com.atakmap.coremap.maps.coords.GeoPointMetaData;

import java.util.HashSet;
import java.util.UUID;
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

    /**
     * The colour the area is drawn in while it is being drawn.
     *
     * <p>ATAK's rectangle tool paints in whatever colour the operator last left the
     * drawing tools set to, which means the box could come up in anything — including
     * a colour that vanishes into the basemap under it. Orange is the operator's
     * choice and the reason is the right one: it holds up on snow, on timber, on bare
     * desert and on dark relief, which no single bright colour manages as reliably.
     */
    private static final int DRAW_COLOR = 0xFFFFA500;

    /** ATAK's setting, borrowed for the duration and put straight back. */
    private int savedColor;
    private boolean colorSaved;

    /**
     * The rectangle the operator drew, kept on the map as the boundary of the area.
     *
     * <p>An earlier version removed it the instant its bounds were read, on the
     * reasoning that the painted overlay is the area. That was wrong in two ways: the
     * overlay can be switched off, and with it gone there is nothing at all to show
     * what ground was computed — and for the whole moment it takes to compute, the
     * screen shows no sign that anything was drawn.
     */
    private DrawingShape drawn;

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
        restoreColor();
        clearDrawn();
    }

    /**
     * Lend ATAK's drawing tools our colour. Every exit path puts it back — a plugin
     * that quietly repaints the operator's drawing preference is a plugin they will
     * curse three days later when their own shapes come out the wrong colour.
     */
    private void borrowColor() {
        if (colorSaved)
            return;
        try {
            final DrawingPreferences prefs = new DrawingPreferences(mapView);
            savedColor = prefs.getShapeColor();
            colorSaved = true;
            prefs.setShapeColor(DRAW_COLOR);
        } catch (RuntimeException e) {
            Log.w(TAG, "could not set the drawing colour", e);
        }
    }

    private void restoreColor() {
        if (!colorSaved)
            return;
        colorSaved = false;
        try {
            new DrawingPreferences(mapView).setShapeColor(savedColor);
        } catch (RuntimeException e) {
            Log.w(TAG, "could not restore the drawing colour", e);
        }
    }

    public boolean isActive() {
        return active;
    }

    public void start() {
        if (active)
            return;
        active = true;
        before = rectanglesNow();
        borrowColor();

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
        restoreColor();
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
        restoreColor();
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

        if (bounds.getNorth() == bounds.getSouth()
                || bounds.getEast() == bounds.getWest()) {
            made.removeFromGroup();
            callback.onCancelled();
            return;
        }

        // ATAK's rectangle tool is three point entry, so what the operator drew can be
        // ROTATED, while the overlay samples a north-up lat/lon grid and therefore
        // computes the rotated rectangle's bounding box. Keeping their rectangle as the
        // boundary would draw a line around one area and paint a slightly larger one —
        // visibly so at the corners. The boundary has to be the ground that was
        // actually computed, so the drawn rectangle is replaced by an outline of the
        // bounds themselves.
        made.removeFromGroup();

        // One area at a time: the previous boundary goes when a new one is drawn.
        clearDrawn();
        drawn = new DrawingShape(mapView, DrawingToolsMapComponent.getGroup(),
                UUID.randomUUID().toString());
        drawn.setTitle("Dozer Country area");
        // The boundary is a boundary, not an annotation. ATAK gives a drawn shape a
        // centre dot and a floating name by default, and over an overlay whose whole
        // job is to be read they are two more things in the way.
        drawn.setCenterPointVisible(false);
        drawn.setCenterPointLabelVisible(false);
        drawn.hideLabels(true);
        drawn.setPoints(GeoPointMetaData.wrap(new GeoPoint[] {
                new GeoPoint(bounds.getNorth(), bounds.getWest()),
                new GeoPoint(bounds.getNorth(), bounds.getEast()),
                new GeoPoint(bounds.getSouth(), bounds.getEast()),
                new GeoPoint(bounds.getSouth(), bounds.getWest())
        }));
        drawn.setClosed(true);
        // Orange, and set on the item rather than left to the drawing preference: the
        // preference only governs what the tool paints while it is running, and this
        // outline has to stay readable over snow, timber, bare desert and dark relief
        // for as long as the area is up.
        drawn.setStrokeColor(DRAW_COLOR);
        drawn.setStrokeWeight(3d);
        // Outline only; a fill would sit on top of the very thing it bounds.
        drawn.setFillColor(0x00000000);
        DrawingToolsMapComponent.getGroup().addItem(drawn);

        Log.d(TAG, "area picked " + bounds);
        callback.onAreaPicked(bounds);
    }

    /** Takes the boundary off the map. Called when the overlay is cleared. */
    public void clearDrawn() {
        if (drawn == null)
            return;
        if (drawn.getGroup() != null)
            drawn.removeFromGroup();
        drawn = null;
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
