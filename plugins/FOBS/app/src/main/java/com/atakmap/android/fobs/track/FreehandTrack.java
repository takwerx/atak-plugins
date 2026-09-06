package com.atakmap.android.fobs.track;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.util.SparseArray;
import android.widget.Toast;

import com.atakmap.android.drawing.DrawingPreferences;
import com.atakmap.android.drawing.DrawingToolsMapComponent;
import com.atakmap.android.drawing.DrawingToolsToolbar;
import com.atakmap.android.drawing.mapItems.DrawingShape;
import com.atakmap.android.drawing.tools.TelestrationTool;
import com.atakmap.android.fobs.plugin.R;
import com.atakmap.android.ipc.AtakBroadcast;
import com.atakmap.android.maps.MapItem;
import com.atakmap.android.maps.MapView;
import com.atakmap.android.maps.MultiPolyline;
import com.atakmap.android.maps.PointMapItem;
import com.atakmap.android.toolbar.Tool;
import com.atakmap.android.toolbar.ToolListener;
import com.atakmap.android.toolbar.ToolManagerBroadcastReceiver;
import com.atakmap.android.toolbar.ToolbarBroadcastReceiver;
import com.atakmap.coremap.log.Log;
import com.atakmap.coremap.maps.coords.GeoPointMetaData;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Draw a track, freehand: run ATAK's own telestration tool in the FOBS default color,
 * and when the operator taps Done, take the scribble it made, join its strokes in
 * drawing order into one FOBS track under the name given at the start, remove the
 * scribble, and ask Make polygon or Single line.
 *
 * <p>ATAK has no path from a telestration to a polyline; this is it. The tool draws
 * with ATAK's drawing color preference, so that preference is set to the FOBS color
 * for the duration and put back afterwards.
 */
public class FreehandTrack implements ToolListener {

    private static final String TAG = "FOBS.FreehandTrack";

    private final MapView mapView;
    private final Context plugin;
    private final Context host;
    private final TrackFinisher finisher;

    private boolean active;
    private String title;
    private Set<String> before = new HashSet<>();
    private int savedColor;
    private boolean colorSaved;

    public FreehandTrack(MapView mapView, Context pluginContext) {
        this.mapView = mapView;
        this.plugin = pluginContext;
        this.host = mapView.getContext();
        this.finisher = new TrackFinisher(mapView, pluginContext);
        ToolManagerBroadcastReceiver.getInstance().registerListener(this);
    }

    public void dispose() {
        ToolManagerBroadcastReceiver.getInstance().unregisterListener(this);
        restoreColor();
    }

    public void begin(String trackTitle) {
        title = trackTitle;
        before = scribblesNow();

        // Telestration reads ATAK's drawing color; lend it ours until Done.
        DrawingPreferences prefs = new DrawingPreferences(mapView);
        savedColor = prefs.getShapeColor();
        colorSaved = true;
        prefs.setShapeColor(new StylePrefs(mapView).color());

        // The tool's Done / Undo buttons belong to ATAK's drawing toolbar, so open
        // it first. SET_TOOLBAR is a broadcast, delivered after this method returns,
        // and its handler ends whatever tool is active: start the tool too early and
        // ATAK ends it on the spot ("Nothing recorded" on the first try, 2026-09-05).
        Intent open = new Intent(ToolbarBroadcastReceiver.SET_TOOLBAR);
        open.putExtra("toolbar", DrawingToolsToolbar.TOOLBAR_IDENTIFIER);
        AtakBroadcast.getInstance().sendBroadcast(open);
        mapView.postDelayed(new Runnable() {
            @Override
            public void run() {
                active = true;
                ToolManagerBroadcastReceiver.getInstance().startTool(
                        TelestrationTool.TOOL_IDENTIFIER, new Bundle());
            }
        }, 400);
    }

    @Override
    public void onToolBegin(Tool tool, Bundle extras) {
    }

    @Override
    public void onToolEnded(Tool tool) {
        if (!active || tool == null
                || !TelestrationTool.TOOL_IDENTIFIER.equals(tool.getIdentifier()))
            return;
        active = false;
        restoreColor();
        // The tool persists its scribble in onToolEnd; take it on the next loop.
        mapView.post(new Runnable() {
            @Override
            public void run() {
                collect();
            }
        });
    }

    private void restoreColor() {
        if (colorSaved) {
            new DrawingPreferences(mapView).setShapeColor(savedColor);
            colorSaved = false;
        }
    }

    private Set<String> scribblesNow() {
        Set<String> uids = new HashSet<>();
        for (MapItem i : DrawingToolsMapComponent.getGroup().getItems())
            if (i instanceof MultiPolyline)
                uids.add(i.getUID());
        return uids;
    }

    private void collect() {
        AtakBroadcast.getInstance().sendBroadcast(
                new Intent(ToolbarBroadcastReceiver.UNSET_TOOLBAR));
        MultiPolyline made = null;
        for (MapItem i : DrawingToolsMapComponent.getGroup().getItems())
            if (i instanceof MultiPolyline && !before.contains(i.getUID()))
                made = (MultiPolyline) i;
        if (made == null) {
            toast(plugin.getString(R.string.toast_nothing_recorded));
            return;
        }
        List<GeoPointMetaData> all = new ArrayList<>();
        for (DrawingShape line : made.getLines()) {
            GeoPointMetaData[] pts = line.getMetaDataPoints();
            if (pts == null)
                continue;
            for (GeoPointMetaData p : pts) {
                if (p == null || p.get() == null || !p.get().isValid())
                    continue;
                GeoPointMetaData copy = new GeoPointMetaData(p);
                copy.setGeoPointSource(FobsShapes.SOURCE_USER);
                all.add(copy);
            }
        }
        Log.d(TAG, "freehand strokes=" + made.getLines().size()
                + " points=" + all.size());
        if (all.size() < 2) {
            made.removeFromGroup();
            toast(plugin.getString(R.string.toast_nothing_recorded));
            return;
        }
        DrawingShape track = FobsShapes.newTrack(mapView, title, FobsShapes.SOURCE_USER);
        track.setPoints(all, new SparseArray<PointMapItem>());
        FobsShapes.addToMap(track);
        // The scribble was drawn as a FOBS track; the track replaces it.
        made.removeFromGroup();
        finisher.ask(track, getClass());
    }

    private void toast(String s) {
        Toast.makeText(host, s, Toast.LENGTH_SHORT).show();
    }
}
