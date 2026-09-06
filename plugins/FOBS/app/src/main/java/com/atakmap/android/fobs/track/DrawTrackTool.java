package com.atakmap.android.fobs.track;

import android.content.Context;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;

import com.atak.plugins.impl.PluginLayoutInflater;
import com.atakmap.android.drawing.mapItems.DrawingShape;
import com.atakmap.android.fobs.plugin.R;
import com.atakmap.android.maps.MapEvent;
import com.atakmap.android.maps.MapEventDispatcher;
import com.atakmap.android.maps.MapGroup;
import com.atakmap.android.maps.MapView;
import com.atakmap.android.maps.Marker;
import com.atakmap.android.toolbar.Tool;
import com.atakmap.android.toolbar.widgets.TextContainer;
import com.atakmap.android.tools.ActionBarReceiver;
import com.atakmap.android.tools.ActionBarView;
import com.atakmap.android.util.EditAction;
import com.atakmap.android.util.Undoable;
import com.atakmap.coremap.maps.assets.Icon;
import com.atakmap.coremap.maps.coords.GeoPointMetaData;

import java.util.Stack;
import java.util.UUID;

/**
 * Draw a track: tap along a road or ridge you can see and do not need to walk. Each tap
 * adds a vertex, Undo takes one back, tapping the first vertex once there are three or
 * more closes the shape into an area on the spot, and End asks Single line or Make
 * polygon like the GPS tool.
 *
 * <p>Design follows Fire Area Survey's map-click leg mode. Points come from ATAK's own
 * {@code findPoint}, so they carry DTED altitude where ATAK has it. Point source is
 * {@code USER}: this is a trace, not a walk.
 */
public class DrawTrackTool extends Tool implements
        MapEventDispatcher.MapEventDispatchListener, View.OnClickListener, Undoable {

    public static final String ID = "com.atakmap.android.fobs.DrawTrackTool";
    public static final String EXTRA_TITLE = "title";

    private final MapView mapView;
    private final Context plugin;
    private final ActionBarView toolbar;
    private final Button undoBtn;
    private final TrackFinisher finisher;
    /** Holds the start marker only; never persisted, never in Overlay Manager. */
    private final MapGroup scratch;
    private final Icon startIcon;

    private DrawingShape shape;
    private Marker startMarker;
    private final Stack<EditAction> undo = new Stack<>();
    private boolean closedByTap;
    /** Set when End was answered in the dialog, so onToolEnd does not ask again. */
    private boolean answered;

    public DrawTrackTool(MapView mapView, Context pluginContext) {
        super(mapView, ID);
        this.mapView = mapView;
        this.plugin = pluginContext;
        this.finisher = new TrackFinisher(mapView, pluginContext);

        toolbar = (ActionBarView) PluginLayoutInflater.inflate(pluginContext,
                R.layout.track_toolbar, mapView, false);
        toolbar.setEmbedState(ActionBarView.FLOATING);
        toolbar.showCloseButton(false);
        toolbar.findViewById(R.id.pause).setVisibility(View.GONE);
        toolbar.findViewById(R.id.dropped).setVisibility(View.GONE);
        undoBtn = toolbar.findViewById(R.id.undo);
        undoBtn.setVisibility(View.VISIBLE);
        undoBtn.setOnClickListener(this);
        toolbar.findViewById(R.id.end).setOnClickListener(this);

        scratch = mapView.getRootGroup().addGroup("FOBS draw");
        scratch.setMetaBoolean("addToObjList", false);
        // ATAK's own outline glyph, green: "tap me to close", same as FAST.
        startIcon = new Icon.Builder()
                .setImageUri(0, "asset:/icons/outline.png")
                .setAnchor(24, 24)
                .setColor(0, 0xFF00FF00)
                .build();
    }

    @Override
    protected boolean onToolBegin(Bundle extras) {
        String title = extras.getString(EXTRA_TITLE);
        if (title == null || title.trim().isEmpty())
            title = mapView.getDeviceCallsign();
        shape = FobsShapes.newTrack(mapView, title, FobsShapes.SOURCE_USER);
        undo.clear();
        closedByTap = false;
        answered = false;
        undoBtn.setEnabled(false);

        pushMapListeners();
        clearExtraListeners();
        MapEventDispatcher d = mapView.getMapEventDispatcher();
        d.addMapEventListener(MapEvent.MAP_CLICK, this);
        d.addMapEventListener(MapEvent.ITEM_CLICK, this);

        ActionBarReceiver.getInstance().setToolView(toolbar);
        TextContainer.getInstance().displayPrompt(plugin.getString(R.string.prompt_draw));
        return true;
    }

    @Override
    protected void onToolEnd() {
        popMapListeners();
        ActionBarReceiver.getInstance().setToolView(null);
        TextContainer.getInstance().closePrompt();
        removeStartMarker();

        final DrawingShape finished = shape;
        shape = null;
        undo.clear();
        if (finished == null)
            return;
        if (finished.getNumPoints() < 2) {
            if (finished.getGroup() != null)
                finished.removeFromGroup();
            finisher.toast(plugin.getString(R.string.toast_nothing_recorded));
            return;
        }
        FobsShapes.persist(mapView, finished, getClass());
        if (closedByTap) {
            finisher.makePolygon(finished);
            return;
        }
        if (!answered)
            finisher.ask(finished, getClass()); // ended by ATAK (back), not by our End
    }

    @Override
    public void dispose() {
        mapView.getRootGroup().removeGroup(scratch);
    }

    // ---- taps --------------------------------------------------------------------

    @Override
    public void onMapEvent(MapEvent event) {
        if (shape == null)
            return;
        if (MapEvent.ITEM_CLICK.equals(event.getType()) && event.getItem() == startMarker) {
            if (shape.getNumPoints() >= 3) {
                closedByTap = true;
                requestEndTool();
            } else {
                finisher.toast(plugin.getString(R.string.toast_need_three));
            }
            return;
        }
        GeoPointMetaData p = findPoint(event);
        if (p == null)
            return;
        run(new AddVertex(p));
    }

    private final class AddVertex extends EditAction {
        private final GeoPointMetaData point;

        AddVertex(GeoPointMetaData point) {
            this.point = point;
        }

        @Override
        public boolean run() {
            GeoPointMetaData copy = new GeoPointMetaData(point);
            copy.setGeoPointSource(FobsShapes.SOURCE_USER);
            shape.addPoint(copy);
            int n = shape.getNumPoints();
            if (n == 1)
                placeStartMarker(copy);
            if (n == 2)
                FobsShapes.addToMap(shape);
            if (n >= 2)
                FobsShapes.persist(mapView, shape, DrawTrackTool.class);
            prompt();
            return true;
        }

        @Override
        public void undo() {
            int n = shape.getNumPoints();
            if (n == 0)
                return;
            shape.removePoint(n - 1);
            n--;
            if (n < 2 && shape.getGroup() != null)
                shape.removeFromGroup();
            if (n == 0)
                removeStartMarker();
            else
                FobsShapes.persist(mapView, shape, DrawTrackTool.class);
            prompt();
        }

        @Override
        public String getDescription() {
            return "FOBS vertex";
        }
    }

    private void prompt() {
        int n = shape == null ? 0 : shape.getNumPoints();
        TextContainer.getInstance().displayPrompt(plugin.getString(
                n >= 3 ? R.string.prompt_draw_close : R.string.prompt_draw));
    }

    private void placeStartMarker(GeoPointMetaData at) {
        removeStartMarker();
        Marker m = new Marker(at, UUID.randomUUID().toString());
        m.setType("shape_marker");
        m.setTitle(plugin.getString(R.string.start_marker));
        m.setIcon(startIcon);
        m.setMetaBoolean("addToObjList", false);
        m.setMetaBoolean("nevercot", true);
        m.setMetaBoolean("ignoreMenu", true);
        m.setMetaBoolean("ignoreOffscreen", true);
        m.setMetaBoolean("removable", false);
        m.setMetaBoolean("movable", false);
        m.setShowLabel(false);
        m.setMarkerHitBounds(-32, -32, 32, 32);
        m.setClickable(true);
        scratch.addItem(m);
        startMarker = m;
    }

    private void removeStartMarker() {
        if (startMarker != null) {
            startMarker.removeFromGroup();
            startMarker = null;
        }
    }

    // ---- toolbar and undo --------------------------------------------------------

    @Override
    public void onClick(View v) {
        if (v == undoBtn) {
            undo();
        } else if (v.getId() == R.id.end) {
            if (shape == null || shape.getNumPoints() < 2) {
                requestEndTool();
                return;
            }
            finisher.askThenEnd(shape, new Runnable() {
                @Override
                public void run() {
                    answered = true;
                    requestEndTool();
                }
            });
        }
    }

    @Override
    public boolean run(EditAction action) {
        if (!action.run())
            return false;
        undo.push(action);
        undoBtn.setEnabled(true);
        return true;
    }

    @Override
    public void undo() {
        if (!undo.isEmpty())
            undo.pop().undo();
        undoBtn.setEnabled(!undo.isEmpty());
    }
}
