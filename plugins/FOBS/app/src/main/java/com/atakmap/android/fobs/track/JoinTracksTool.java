package com.atakmap.android.fobs.track;

import android.content.Context;
import android.os.Bundle;
import android.util.SparseArray;
import android.view.View;
import android.widget.Button;
import android.widget.Toast;

import com.atak.plugins.impl.PluginLayoutInflater;
import com.atakmap.android.drawing.DrawingToolsMapComponent;
import com.atakmap.android.drawing.mapItems.DrawingShape;
import com.atakmap.android.fobs.plugin.R;
import com.atakmap.android.maps.MapEvent;
import com.atakmap.android.maps.MapEventDispatcher;
import com.atakmap.android.maps.MapGroup;
import com.atakmap.android.maps.MapItem;
import com.atakmap.android.maps.MapTouchController;
import com.atakmap.android.maps.MapView;
import com.atakmap.android.maps.Marker;
import com.atakmap.android.maps.PointMapItem;
import com.atakmap.android.toolbar.Tool;
import com.atakmap.android.toolbar.widgets.TextContainer;
import com.atakmap.android.tools.ActionBarReceiver;
import com.atakmap.android.tools.ActionBarView;
import com.atakmap.coremap.log.Log;
import com.atakmap.coremap.maps.assets.Icon;
import com.atakmap.coremap.maps.coords.GeoPointMetaData;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.SortedSet;
import java.util.Stack;
import java.util.UUID;

/**
 * Join tracks: two taps, two ends, one line. Every track on the map grows a marker at
 * each end. Tap an end on one line, then an end on another: those two ends are
 * connected by a straight segment and the two lines become one. Repeat as often as
 * wanted; End when done. Tap both ends of the same line and it closes into an area.
 * Undo puts the last two lines back.
 *
 * <p>No start, no end, no order. The operator: "i'm just joining two lines."
 */
public class JoinTracksTool extends Tool implements MapEventDispatcher.MapEventDispatchListener,
        View.OnClickListener, MapTouchController.DeconflictionListener {

    public static final String ID = "com.atakmap.android.fobs.JoinTracksTool";

    private static final String TAG = "FOBS.JoinTracksTool";
    private static final String META_TRACK = "fobs_join_track";
    private static final String META_END = "fobs_join_end"; // "start" | "end"

    private final MapView mapView;
    private final Context plugin;
    private final Context host;
    private final ActionBarView toolbar;
    private final Button undoBtn;
    private final TrackFinisher finisher;
    private final MapGroup scratch;
    private final Icon endpointIcon;
    private final Icon pickedIcon;

    /** Endpoint markers by their own UID. */
    private final Map<String, Marker> endpoints = new HashMap<>();
    /** The first of the two taps, waiting for its partner. */
    private Marker picked;

    /** One join, for Undo: the two originals that were replaced, and by what. */
    private static final class Join {
        final DrawingShape a, b, merged;

        Join(DrawingShape a, DrawingShape b, DrawingShape merged) {
            this.a = a;
            this.b = b;
            this.merged = merged;
        }
    }

    private final Stack<Join> joins = new Stack<>();

    public JoinTracksTool(MapView mapView, Context pluginContext) {
        super(mapView, ID);
        this.mapView = mapView;
        this.plugin = pluginContext;
        this.host = mapView.getContext();
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

        scratch = mapView.getRootGroup().addGroup("FOBS join");
        scratch.setMetaBoolean("addToObjList", false);
        endpointIcon = new Icon.Builder().setImageUri(0, "asset:/icons/outline.png")
                .setAnchor(24, 24).setColor(0, 0xFFFFFFFF).build();
        pickedIcon = endpointIcon.buildUpon().setColor(0, 0xFF00FF00).build();
    }

    // ---- lifecycle ---------------------------------------------------------------

    @Override
    protected boolean onToolBegin(Bundle extras) {
        reset();
        int tracks = 0;
        for (MapItem m : DrawingToolsMapComponent.getGroup().getItemsRecursive()) {
            if (FobsShapes.isTrack(m) && m instanceof DrawingShape
                    && ((DrawingShape) m).getNumPoints() >= 2) {
                addEndpoints((DrawingShape) m);
                tracks++;
            }
        }
        if (tracks < 1) {
            toast(plugin.getString(R.string.toast_join_need_two));
            return false;
        }
        pushMapListeners();
        clearExtraListeners();
        MapEventDispatcher d = mapView.getMapEventDispatcher();
        d.addMapEventListener(MapEvent.ITEM_CLICK, this);
        d.addMapEventListener(MapEvent.MAP_CLICK, this);
        mapView.getMapTouchController().addDeconflictionListener(this);
        mapView.getMapTouchController().setToolActive(true);
        ActionBarReceiver.getInstance().setToolView(toolbar);
        undoBtn.setEnabled(false);
        TextContainer.getInstance().displayPrompt(plugin.getString(R.string.prompt_join_first));
        return true;
    }

    @Override
    protected void onToolEnd() {
        popMapListeners();
        mapView.getMapTouchController().removeDeconflictionListener(this);
        mapView.getMapTouchController().setToolActive(false);
        ActionBarReceiver.getInstance().setToolView(null);
        TextContainer.getInstance().closePrompt();
        reset();
    }

    @Override
    public void dispose() {
        mapView.getRootGroup().removeGroup(scratch);
    }

    private void reset() {
        for (Marker m : endpoints.values())
            m.removeFromGroup();
        endpoints.clear();
        picked = null;
        joins.clear();
    }

    // ---- endpoint markers --------------------------------------------------------

    private void addEndpoints(DrawingShape track) {
        int n = track.getNumPoints();
        if (n < 2)
            return;
        endpoint(track, track.getPoint(0), "start");
        endpoint(track, track.getPoint(n - 1), "end");
    }

    private void removeEndpoints(DrawingShape track) {
        List<String> gone = new ArrayList<>();
        for (Marker m : endpoints.values())
            if (track.getUID().equals(m.getMetaString(META_TRACK, null))) {
                m.removeFromGroup();
                gone.add(m.getUID());
            }
        for (String uid : gone)
            endpoints.remove(uid);
    }

    private void endpoint(DrawingShape track, GeoPointMetaData at, String which) {
        Marker m = new Marker(at, UUID.randomUUID().toString());
        m.setType("shape_marker");
        m.setTitle(track.getTitle() + " " + which);
        m.setIcon(endpointIcon);
        m.setMetaString(META_TRACK, track.getUID());
        m.setMetaString(META_END, which);
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
        endpoints.put(m.getUID(), m);
    }

    /** With stacked items under the finger, keep only our endpoint markers. */
    @Override
    public void onConflict(SortedSet<MapItem> hits) {
        List<MapItem> keep = new ArrayList<>();
        for (MapItem m : hits)
            if (m instanceof Marker && endpoints.containsKey(m.getUID()))
                keep.add(m);
        if (!keep.isEmpty()) {
            hits.clear();
            hits.add(keep.get(0));
        }
    }

    // ---- taps --------------------------------------------------------------------

    @Override
    public void onMapEvent(MapEvent event) {
        MapItem item = event.getItem();
        if (!(item instanceof Marker) || !endpoints.containsKey(item.getUID())) {
            toast(plugin.getString(R.string.toast_join_tap_endpoint));
            return;
        }
        Marker m = (Marker) item;
        if (picked == null) {
            picked = m;
            m.setIcon(pickedIcon);
            TextContainer.getInstance().displayPrompt(plugin.getString(R.string.prompt_join_second));
            return;
        }
        if (m == picked) {
            picked.setIcon(endpointIcon);
            picked = null;
            TextContainer.getInstance().displayPrompt(plugin.getString(R.string.prompt_join_first));
            return;
        }
        String ua = picked.getMetaString(META_TRACK, null);
        String ub = m.getMetaString(META_TRACK, null);
        MapItem ia = mapView.getRootGroup().deepFindUID(ua);
        MapItem ib = mapView.getRootGroup().deepFindUID(ub);
        Marker a = picked;
        picked = null;
        a.setIcon(endpointIcon);
        if (!(ia instanceof DrawingShape) || !(ib instanceof DrawingShape))
            return;
        if (ua.equals(ub)) {
            closeLine((DrawingShape) ia);
        } else {
            join((DrawingShape) ia, "end".equals(a.getMetaString(META_END, "start")),
                    (DrawingShape) ib, "end".equals(m.getMetaString(META_END, "start")));
        }
        TextContainer.getInstance().displayPrompt(plugin.getString(R.string.prompt_join_first));
    }

    /** Both ends of the same line tapped: it closes into an area. */
    private void closeLine(DrawingShape line) {
        if (line.getNumPoints() < 3) {
            toast(plugin.getString(R.string.toast_area_too_few));
            return;
        }
        removeEndpoints(line);
        finisher.makePolygon(line);
        Log.d(TAG, "closed a line into an area");
    }

    /**
     * Connect the tapped end of a to the tapped end of b. a is oriented to finish at
     * its tapped end, b to start at its tapped end; the segment between them is the
     * join. The merged line takes a's name and style; a and b come off the map.
     */
    private void join(DrawingShape a, boolean aTappedEnd, DrawingShape b, boolean bTappedEnd) {
        List<GeoPointMetaData> pa = points(a);
        List<GeoPointMetaData> pb = points(b);
        if (!aTappedEnd)
            Collections.reverse(pa);
        if (bTappedEnd)
            Collections.reverse(pb);
        List<GeoPointMetaData> all = new ArrayList<>(pa);
        all.addAll(pb);

        DrawingShape merged = FobsShapes.newTrack(mapView, a.getTitle(),
                a.getMetaString(FobsShapes.META_SOURCE, FobsShapes.SOURCE_USER));
        merged.setStrokeColor(a.getStrokeColor());
        merged.setStrokeWeight(a.getStrokeWeight());
        merged.setLineStyle(a.getLineStyle());
        merged.setPoints(all, new SparseArray<PointMapItem>());
        FobsShapes.addToMap(merged);
        FobsShapes.persist(mapView, merged, getClass());

        com.atakmap.android.fobs.feed.FeedPublisher fp = com.atakmap.android.fobs.feed.FeedPublisher.get();
        if (fp != null) {
            fp.inherit(fp.isLive(a) ? a : b, merged);
            fp.unpublish(a);
            fp.unpublish(b);
            FobsShapes.persist(mapView, merged, getClass()); // publishes the merged line
        }
        removeEndpoints(a);
        removeEndpoints(b);
        a.removeFromGroup();
        b.removeFromGroup();
        addEndpoints(merged);
        joins.push(new Join(a, b, merged));
        undoBtn.setEnabled(true);
        Log.d(TAG, "joined two lines -> " + all.size() + " points");
        toast(plugin.getString(R.string.toast_joined, a.getTitle(), b.getTitle()));
    }

    private static List<GeoPointMetaData> points(DrawingShape s) {
        List<GeoPointMetaData> out = new ArrayList<>();
        GeoPointMetaData[] pts = s.getMetaDataPoints();
        if (pts != null)
            for (GeoPointMetaData p : pts)
                if (p != null && p.get() != null)
                    out.add(new GeoPointMetaData(p));
        return out;
    }

    // ---- toolbar -----------------------------------------------------------------

    @Override
    public void onClick(View v) {
        if (v == undoBtn)
            undo();
        else if (v.getId() == R.id.end)
            requestEndTool();
    }

    /** Put the last two lines back and take the merged one away. */
    private void undo() {
        if (joins.isEmpty())
            return;
        Join j = joins.pop();
        removeEndpoints(j.merged);
        j.merged.removeFromGroup();
        MapGroup group = DrawingToolsMapComponent.getGroup();
        if (j.a.getGroup() == null)
            group.addItem(j.a);
        if (j.b.getGroup() == null)
            group.addItem(j.b);
        FobsShapes.persist(mapView, j.a, getClass());
        FobsShapes.persist(mapView, j.b, getClass());
        addEndpoints(j.a);
        addEndpoints(j.b);
        undoBtn.setEnabled(!joins.isEmpty());
    }

    private void toast(String s) {
        Toast.makeText(host, s, Toast.LENGTH_SHORT).show();
    }
}
