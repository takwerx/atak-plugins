package com.atakmap.android.dozercountry.map;

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

import java.util.HashSet;
import java.util.Set;

/**
 * Picking an area of interest: run ATAK's own polygon drawing tool, then take the
 * shape it made as the area.
 *
 * <h3>Why not our own two taps</h3>
 *
 * The first version listened for two {@code MAP_CLICK}s and drew nothing in between,
 * so the operator tapped twice into empty space with no sign either tap had landed and
 * no idea what shape was coming. Their words: "im not seeing my taps in the corners it
 * needs to be like using the polygon tool". They are right, and the answer is not to
 * reinvent the feedback — ATAK already has a shape tool with the rubber band, the
 * vertex handles, the prompt, the undo and the tap-the-first-marker-to-close that an
 * ATAK user has already learned.
 *
 * <p>A rectangle came first and the operator replaced it: "i think this is better than
 * square". They are right again — a division or a contingency line is not a box, and
 * a box drawn round an irregular piece of ground claims ground nobody asked about.
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
 * <p>The shape stays on the map once its bounds are taken. It is the boundary of the
 * area, and it is the only thing on screen while the overlay is computing or switched
 * off.
 */
public final class AreaPicker implements ToolListener {

    private static final String TAG = "DozerAreaPicker";

    /** ATAK delivers SET_TOOLBAR as a broadcast; give it time to land. From FOBS. */
    private static final long TOOLBAR_SETTLE_MS = 400L;

    /**
     * The color the area is drawn in while it is being drawn.
     *
     * <p>ATAK's shape tool paints in whatever color the operator last left the
     * drawing tools set to, which means the area could come up in anything — including
     * a color that vanishes into the basemap under it. Orange is the operator's
     * choice and the reason is the right one: it holds up on snow, on timber, on bare
     * desert and on dark relief, which no single bright color manages as reliably.
     */
    private static final int DRAW_COLOR = 0xFFFFA500;

    /** ATAK's setting, borrowed for the duration and put straight back. */
    private int savedColor;
    private boolean colorSaved;

    /**
     * The shape the operator drew, kept on the map as the boundary of the area.
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
        void onAreaPicked(Area area);

        /** The drawing tool started; the operator is drawing. */
        void onPickingStarted();

        void onCancelled(String reason);

        /**
         * The boundary left the map by some route other than Clear — ATAK's own
         * delete, Overlay Manager, an import wiping the drawing group.
         */
        void onAreaRemoved();
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
     * Lend ATAK's drawing tools our color. Every exit path puts it back — a plugin
     * that quietly repaints the operator's drawing preference is a plugin they will
     * curse three days later when their own shapes come out the wrong color.
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
                ToolManagerBroadcastReceiver.getInstance().startTool(
                        ShapeCreationTool.TOOL_IDENTIFIER, new Bundle());
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
        if (t != null && ShapeCreationTool.TOOL_IDENTIFIER
                .equals(t.getIdentifier()))
            ToolManagerBroadcastReceiver.getInstance().endCurrentTool();
        closeToolbar();
        callback.onCancelled(null);
    }

    @Override
    public void onToolBegin(Tool tool, Bundle extras) {
    }

    @Override
    public void onToolEnded(Tool tool) {
        if (!active || tool == null || !ShapeCreationTool.TOOL_IDENTIFIER
                .equals(tool.getIdentifier()))
            return;
        active = false;
        // The tool adds its shape to the group during onToolEnd; take it on the
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

        DrawingShape made = null;
        for (MapItem i : DrawingToolsMapComponent.getGroup().getItems()) {
            if (i instanceof DrawingShape && !before.contains(i.getUID()))
                made = (DrawingShape) i;
        }
        if (made == null) {
            // Backed out without drawing anything.
            callback.onCancelled(null);
            return;
        }

        // ATAK's shape tool can finish two ways: tap the first marker and get a closed
        // shape, or press End Shape and get an open line. Only the first is an area.
        // An open line left behind became a stray orange streak across the operator's
        // map with nothing to clear it, because the pane's Clear only knows about the
        // boundary the plugin adopted.
        final Area area = new Area(made.getPoints());
        if (!made.isClosed() || !area.isUsable()) {
            made.removeFromGroup();
            callback.onCancelled(made.isClosed()
                    ? null
                    : "That is a line, not an area. Tap the first marker to close the "
                            + "shape.");
            return;
        }

        // One area at a time: the previous boundary goes when a new one is drawn.
        clearDrawn();
        drawn = made;
        drawn.addOnGroupChangedListener(watcher);
        drawn.setTitle("Dozer Country area");
        // The boundary is a boundary, not an annotation. ATAK gives a drawn shape a
        // centre dot and a floating name by default, and over an overlay whose whole
        // job is to be read they are two more things in the way.
        drawn.setCenterPointVisible(false);
        drawn.setCenterPointLabelVisible(false);
        drawn.hideLabels(true);
        // Orange on the item, not just via the drawing preference: the preference only
        // governs what the tool paints while it is running, and this outline has to
        // stay readable over snow, timber, bare desert and dark relief for as long as
        // the area is up.
        // Clamp it to the ground. The overlay is draped on terrain, and a shape left
        // at its own altitude drifts away from the paint the moment the map is tilted
        // -- on a 3D view the outline and the color it bounds visibly disagree, which
        // is the same lie as before wearing a different hat.
        drawn.setAltitudeMode(com.atakmap.map.layer.feature.Feature.AltitudeMode.ClampToGround);
        drawn.setStrokeColor(DRAW_COLOR);
        drawn.setStrokeWeight(3d);
        // Outline only; a fill would sit on top of the very thing it bounds.
        drawn.setFillColor(0x00000000);

        Log.d(TAG, "area picked, " + area.ring.size() + " vertices, " + area.bounds);
        callback.onAreaPicked(area);
    }

    /**
     * Notices the boundary being deleted by anything that is not us.
     *
     * <p>The shape is an ordinary ATAK drawing object once it is on the map, so it can
     * be deleted from its radial menu or from Overlay Manager, and the plugin is not
     * told. It was not told: the operator deleted the area and the painting stayed on
     * the map with no outline around it and nothing to say what ground it covered --
     * "uh bugs i delted area its still there". Painted ground with no boundary is
     * exactly the thing the boundary exists to prevent.
     */
    private final MapItem.OnGroupChangedListener watcher =
            new MapItem.OnGroupChangedListener() {
        @Override
        public void onItemAdded(MapItem item, com.atakmap.android.maps.MapGroup group) {
        }

        @Override
        public void onItemRemoved(MapItem item, com.atakmap.android.maps.MapGroup group) {
            // Only our own current boundary counts, and only when we did not do it.
            if (item != drawn)
                return;
            drawn = null;
            item.removeOnGroupChangedListener(this);
            mapView.post(new Runnable() {
                @Override
                public void run() {
                    callback.onAreaRemoved();
                }
            });
        }
    };

    /** Takes the boundary off the map. Called when the overlay is cleared. */
    public void clearDrawn() {
        if (drawn == null)
            return;
        final DrawingShape going = drawn;
        // Null it first so the watcher knows this removal was ours and stays quiet.
        drawn = null;
        going.removeOnGroupChangedListener(watcher);
        if (going.getGroup() != null)
            going.removeFromGroup();
    }

    private void closeToolbar() {
        AtakBroadcast.getInstance().sendBroadcast(
                new Intent(ToolbarBroadcastReceiver.UNSET_TOOLBAR));
    }

    /** Every shape already in the drawing group, so the new one can be told apart. */
    private Set<String> shapesNow() {
        final Set<String> uids = new HashSet<>();
        for (MapItem i : DrawingToolsMapComponent.getGroup().getItems())
            if (i instanceof DrawingShape)
                uids.add(i.getUID());
        return uids;
    }
}
