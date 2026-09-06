package com.atakmap.android.fobs.track;

import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.os.Bundle;
import android.text.InputType;
import android.util.SparseArray;
import android.widget.EditText;
import android.widget.Toast;

import com.atakmap.android.drawing.mapItems.DrawingCircle;
import com.atakmap.android.drawing.mapItems.DrawingShape;
import com.atakmap.android.editableShapes.EditablePolyline;
import com.atakmap.android.editableShapes.Rectangle;
import com.atakmap.android.fobs.plugin.R;
import com.atakmap.android.ipc.AtakBroadcast;
import com.atakmap.android.maps.MapItem;
import com.atakmap.android.maps.MapView;
import com.atakmap.android.maps.MultiPolyline;
import com.atakmap.android.maps.PointMapItem;
import com.atakmap.android.maps.Shape;
import com.atakmap.android.missionpackage.MapItemSelectTool;
import com.atakmap.android.toolbar.ToolManagerBroadcastReceiver;
import com.atakmap.android.track.maps.TrackPolyline;
import com.atakmap.coremap.log.Log;
import com.atakmap.coremap.maps.coords.GeoPointMetaData;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Select a track: tap a shape already on the map and make it a FOBS track. Handles a
 * telestration (one stroke or many), a freeform line or polygon, a route, a rectangle
 * and a circle. Closed shapes become areas directly.
 *
 * <p>The tapping is ATAK's own {@code MapItemSelectTool}, the same one Data Packages
 * use, which hands the chosen UIDs back by broadcast. This is what turns ATAK's
 * telestration tool into a way of drawing a track: ATAK has no path from a scribble
 * to a polyline, so the plugin is it.
 */
public class SelectTrack extends BroadcastReceiver {

    public static final String ACTION = "com.atakmap.android.fobs.SELECTED";
    private static final String TAG = "FOBS.SelectTrack";

    /** What may be tapped: telestration, freeform, route, rectangle, circle, feature. */
    private static final String[] ALLOW_TYPES = {
            "u-d-f-m", "u-d-f", "b-m-r", "u-d-r", "u-d-c-c", "u-r-b-c-c", "u-rb-a",
            "u-d-feature", TrackPolyline.COT_TYPE
    };

    private final MapView mapView;
    private final Context plugin;
    private final Context host;
    private final TrackFinisher finisher;
    private ImportTrack importTrack;

    /** Track lines from Track History are imported with their crumbs, gate and all. */
    public void setImportTrack(ImportTrack importTrack) {
        this.importTrack = importTrack;
    }

    public SelectTrack(MapView mapView, Context pluginContext) {
        this.mapView = mapView;
        this.plugin = pluginContext;
        this.host = mapView.getContext();
        this.finisher = new TrackFinisher(mapView, pluginContext);
        AtakBroadcast.getInstance().registerReceiver(this,
                new AtakBroadcast.DocumentedIntentFilter(ACTION,
                        "A shape was picked on the map to become a FOBS track"));
    }

    public void dispose() {
        AtakBroadcast.getInstance().unregisterReceiver(this);
    }

    /** Start ATAK's selector. */
    public void begin() {
        Bundle b = new Bundle();
        b.putString("prompt", plugin.getString(R.string.prompt_select));
        b.putStringArray("allowTypes", ALLOW_TYPES);
        b.putBoolean("multiSelect", false);
        b.putParcelable("callback", new Intent(ACTION));
        ToolManagerBroadcastReceiver.getInstance().startTool(MapItemSelectTool.TOOL_NAME, b);
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        // Single select answers with "itemUID"; only multi-select uses "itemUIDs"
        // (read from MapItemSelectTool.onToolEnd, 2026-09-05). Accept both.
        String uid = intent.getStringExtra("itemUID");
        if (uid == null) {
            String[] uids = intent.getStringArrayExtra("itemUIDs");
            if (uids != null && uids.length > 0)
                uid = uids[0];
        }
        Log.d(TAG, "selected uid=" + uid);
        if (uid == null)
            return; // the operator ended the selector without tapping anything
        MapItem item = mapView.getRootGroup().deepFindUID(uid);
        if (item == null)
            return;
        Log.d(TAG, "selected " + item.getClass().getSimpleName() + " type=" + item.getType()
                + " title=" + item.getTitle());
        if (FobsShapes.isOurs(item)) {
            toast(plugin.getString(R.string.toast_already_fobs));
            return;
        }
        if (item instanceof TrackPolyline && importTrack != null) {
            importTrack.importVisible((TrackPolyline) item);
            return;
        }
        final List<Stroke> strokes = strokesOf(item);
        if (strokes.isEmpty()) {
            toast(plugin.getString(R.string.toast_shape_no_points));
            return;
        }
        // Same default as every other track: "<callsign> - Track N", editable.
        String pattern = plugin.getString(R.string.track_default_name);
        String callsign = mapView.getDeviceCallsign();
        String title = String.format(pattern, callsign,
                FobsShapes.nextTrackNumber(mapView, pattern, callsign));
        if (strokes.size() == 1)
            nameThenCreate(title, strokes, false);
        else
            askStrokes(title, strokes);
    }

    /** One run of points and whether it closes on itself. */
    static final class Stroke {
        final List<GeoPointMetaData> points;
        final boolean closed;

        Stroke(List<GeoPointMetaData> points, boolean closed) {
            this.points = points;
            this.closed = closed;
        }
    }

    /** Break a map item into strokes. A telestration is one per stroke; anything else is one. */
    static List<Stroke> strokesOf(MapItem item) {
        List<Stroke> out = new ArrayList<>();
        if (item instanceof MultiPolyline) {
            for (DrawingShape line : ((MultiPolyline) item).getLines()) {
                List<GeoPointMetaData> pts = pointsOf(line);
                if (pts.size() >= 2)
                    out.add(new Stroke(pts, line.isClosed()));
            }
            return out;
        }
        if (item instanceof Shape) {
            List<GeoPointMetaData> pts = pointsOf((Shape) item);
            boolean closed = item instanceof Rectangle || item instanceof DrawingCircle
                    || (item instanceof EditablePolyline && ((EditablePolyline) item).isClosed());
            if (item instanceof Rectangle && pts.size() > 4)
                pts = new ArrayList<>(pts.subList(0, 4)); // corners only, not the center
            if (pts.size() >= 2)
                out.add(new Stroke(pts, closed));
        }
        return out;
    }

    private static List<GeoPointMetaData> pointsOf(Shape shape) {
        GeoPointMetaData[] pts = shape.getMetaDataPoints();
        List<GeoPointMetaData> out = new ArrayList<>();
        if (pts == null)
            return out;
        for (GeoPointMetaData p : pts) {
            if (p == null || p.get() == null || !p.get().isValid())
                continue;
            GeoPointMetaData copy = new GeoPointMetaData(p);
            if (copy.getGeopointSource() == null)
                copy.setGeoPointSource(FobsShapes.SOURCE_USER);
            out.add(copy);
        }
        return out;
    }

    private void askStrokes(final String title, final List<Stroke> strokes) {
        new AlertDialog.Builder(host)
                .setTitle(plugin.getString(R.string.strokes_title, strokes.size()))
                .setPositiveButton(plugin.getString(R.string.strokes_join),
                        new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface d, int which) {
                                nameThenCreate(title, strokes, true);
                            }
                        })
                .setNegativeButton(plugin.getString(R.string.strokes_separate),
                        new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface d, int which) {
                                nameThenCreate(title, strokes, false);
                            }
                        })
                .setNeutralButton(plugin.getString(R.string.cancel), null)
                .show();
    }

    private void nameThenCreate(String title, final List<Stroke> strokes, final boolean join) {
        final EditText name = new EditText(host);
        name.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        name.setSingleLine(true);
        name.setText(title);
        name.setSelection(name.getText().length());
        new AlertDialog.Builder(host)
                .setTitle(plugin.getString(R.string.name_track))
                .setView(name)
                .setPositiveButton(plugin.getString(R.string.ok),
                        new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface d, int which) {
                                create(name.getText().toString().trim(), strokes, join);
                            }
                        })
                .setNegativeButton(plugin.getString(R.string.cancel), null)
                .show();
    }

    private void create(String title, List<Stroke> strokes, boolean join) {
        if (join) {
            List<GeoPointMetaData> all = new ArrayList<>();
            for (Stroke s : strokes)
                all.addAll(s.points);
            makeOne(title, all, false);
            return;
        }
        if (strokes.size() == 1) {
            makeOne(title, strokes.get(0).points, strokes.get(0).closed);
            return;
        }
        // One track each: the given name for the first, then the next free numbers.
        String pattern = plugin.getString(R.string.track_default_name);
        String callsign = mapView.getDeviceCallsign();
        boolean first = true;
        for (Stroke s : strokes) {
            String t = first ? title : String.format(pattern, callsign,
                    FobsShapes.nextTrackNumber(mapView, pattern, callsign));
            first = false;
            makeOne(t, s.points, s.closed);
        }
    }

    private void makeOne(String title, List<GeoPointMetaData> pts, boolean closed) {
        DrawingShape track = FobsShapes.newTrack(mapView, title, FobsShapes.SOURCE_USER);
        track.setPoints(pts, new SparseArray<PointMapItem>());
        FobsShapes.addToMap(track);
        if (closed && pts.size() >= 3) {
            finisher.makePolygon(track);
        } else {
            FobsShapes.persist(mapView, track, getClass());
            toast(plugin.getString(R.string.toast_track_made, title, pts.size()));
        }
    }

    private void toast(String s) {
        Toast.makeText(host, s, Toast.LENGTH_SHORT).show();
    }

    static List<String> allowTypes() {
        return Arrays.asList(ALLOW_TYPES);
    }
}
