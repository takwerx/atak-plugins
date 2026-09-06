package com.atakmap.android.fobs.track;

import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.graphics.drawable.Drawable;
import android.util.SparseArray;
import android.widget.Toast;

import com.atakmap.android.data.URIHelper;
import com.atakmap.android.drawing.mapItems.DrawingShape;
import com.atakmap.android.fobs.plugin.R;
import com.atakmap.android.ipc.AtakBroadcast;
import com.atakmap.android.maps.MapItem;
import com.atakmap.android.maps.MapView;
import com.atakmap.android.maps.PointMapItem;
import com.atakmap.android.maps.Shape;
import com.atakmap.android.missionpackage.lasso.LassoSelectionReceiver;
import com.atakmap.coremap.log.Log;
import com.atakmap.coremap.maps.coords.GeoCalculations;
import com.atakmap.coremap.maps.coords.GeoPoint;
import com.atakmap.coremap.maps.coords.GeoPointMetaData;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Join by lasso: circle several tracks with ATAK's own lasso and they are joined,
 * nearest end to nearest end, into one closed area. No tapping.
 *
 * <p>Two ways in, one routine. From the FOBS pane, Join tracks, Lasso: ATAK's lasso is
 * started with a callback and the circled items come back by broadcast. From ATAK's
 * lasso menu: FOBS registers a "Join tracks" button there and ATAK hands over the
 * circled items directly.
 *
 * <p>Greedy nearest-end chaining: start with the longest track, then keep appending
 * the track whose nearest end is closest to the current tail, reversed if it was
 * entered from its far end; close tail to head. The tracks were walked around one
 * perimeter, so the nearest one is the right one, and a wrong guess is visible at
 * once and fixed with Split and Join. If the largest gap bridged is over the limit,
 * ask before drawing a line across it.
 */
public class LassoJoin extends BroadcastReceiver implements
        LassoSelectionReceiver.ExternalLassoCapability {

    public static final String ACTION = "com.atakmap.android.fobs.LASSOED";
    private static final String TAG = "FOBS.LassoJoin";
    /** Ask before bridging a gap longer than this, meters. */
    private static final double GAP_LIMIT = 100.0;

    private final MapView mapView;
    private final Context plugin;
    private final Context host;
    private final TrackFinisher finisher;

    public LassoJoin(MapView mapView, Context pluginContext) {
        this.mapView = mapView;
        this.plugin = pluginContext;
        this.host = mapView.getContext();
        this.finisher = new TrackFinisher(mapView, pluginContext);
        AtakBroadcast.getInstance().registerReceiver(this,
                new AtakBroadcast.DocumentedIntentFilter(ACTION,
                        "Tracks were lassoed to be joined into a FOBS area"));
        LassoSelectionReceiver.registerExternalLassoCapability(this);
    }

    public void dispose() {
        LassoSelectionReceiver.unregisterExternalLassoCapability(this);
        AtakBroadcast.getInstance().unregisterReceiver(this);
    }

    // ---- from the FOBS pane: start ATAK's lasso ----------------------------------

    public void begin() {
        Intent i = new Intent("com.atakmap.android.missionpackage.lasso.SELECT");
        i.putExtra("callback", new Intent(ACTION));
        AtakBroadcast.getInstance().sendBroadcast(i);
        Toast.makeText(host, plugin.getString(R.string.toast_lasso_hint), Toast.LENGTH_LONG).show();
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        Object[] uris = (Object[]) intent.getSerializableExtra("uris");
        List<Object> items = new ArrayList<>();
        if (uris != null)
            for (Object u : uris) {
                MapItem mi = URIHelper.getMapItem(mapView, String.valueOf(u));
                if (mi != null)
                    items.add(mi);
            }
        process(items);
    }

    // ---- from ATAK's lasso menu ---------------------------------------------------

    @Override
    public String getUniqueIdentifier() {
        return "com.atakmap.android.fobs.lasso";
    }

    @Override
    public Drawable getIcon() {
        return plugin.getResources().getDrawable(R.drawable.ic_toolbar);
    }

    @Override
    public String getTitle() {
        return plugin.getString(R.string.lasso_menu_title);
    }

    @Override
    public void process(List<Object> list) {
        // Any open line, not only FOBS tracks; what is joined becomes a FOBS area.
        final List<Shape> tracks = new ArrayList<>();
        for (Object o : list)
            if (o instanceof MapItem && JoinTracksTool.joinable((MapItem) o))
                tracks.add((Shape) o);
        if (tracks.size() < 2) {
            toast(plugin.getString(R.string.toast_lasso_need_two));
            return;
        }
        final Chain chain = chain(tracks);
        if (chain.maxGap > GAP_LIMIT) {
            String gap = FobsShapes.formatDistance(host, chain.maxGap);
            new AlertDialog.Builder(host)
                    .setTitle(plugin.getString(R.string.lasso_gap_title))
                    .setMessage(plugin.getString(R.string.lasso_gap_message, gap))
                    .setPositiveButton(plugin.getString(R.string.lasso_gap_join),
                            new DialogInterface.OnClickListener() {
                                @Override
                                public void onClick(DialogInterface d, int which) {
                                    build(tracks, chain);
                                }
                            })
                    .setNegativeButton(plugin.getString(R.string.cancel), null)
                    .show();
            return;
        }
        build(tracks, chain);
    }

    // ---- the chaining ------------------------------------------------------------

    static final class Chain {
        final List<GeoPointMetaData> points = new ArrayList<>();
        double maxGap = 0;
        int joined = 0;
    }

    static Chain chain(List<? extends Shape> tracks) {
        List<List<GeoPointMetaData>> runs = new ArrayList<>();
        for (Shape t : tracks)
            runs.add(points(t));
        // Longest first, by point count.
        int start = 0;
        for (int i = 1; i < runs.size(); i++)
            if (runs.get(i).size() > runs.get(start).size())
                start = i;
        Chain c = new Chain();
        c.points.addAll(runs.remove(start));
        while (!runs.isEmpty()) {
            GeoPoint tail = c.points.get(c.points.size() - 1).get();
            int best = -1;
            boolean reverse = false;
            double bestD = Double.MAX_VALUE;
            for (int i = 0; i < runs.size(); i++) {
                List<GeoPointMetaData> r = runs.get(i);
                double dHead = GeoCalculations.distanceTo(tail, r.get(0).get());
                double dTail = GeoCalculations.distanceTo(tail, r.get(r.size() - 1).get());
                if (dHead < bestD) {
                    bestD = dHead;
                    best = i;
                    reverse = false;
                }
                if (dTail < bestD) {
                    bestD = dTail;
                    best = i;
                    reverse = true;
                }
            }
            List<GeoPointMetaData> next = runs.remove(best);
            if (reverse)
                Collections.reverse(next);
            c.points.addAll(next);
            c.maxGap = Math.max(c.maxGap, bestD);
            c.joined++;
        }
        // Closing gap, tail back to head.
        double close = GeoCalculations.distanceTo(c.points.get(c.points.size() - 1).get(),
                c.points.get(0).get());
        c.maxGap = Math.max(c.maxGap, close);
        return c;
    }

    private static List<GeoPointMetaData> points(Shape s) {
        List<GeoPointMetaData> out = new ArrayList<>();
        GeoPointMetaData[] pts = s.getMetaDataPoints();
        if (pts != null)
            for (GeoPointMetaData p : pts)
                if (p != null && p.get() != null)
                    out.add(new GeoPointMetaData(p));
        return out;
    }

    private void build(List<Shape> lines, Chain chain) {
        // Adopt first: an ATAK-drawn line becomes a FOBS track, then is consumed
        // like any other. Done here, after the gap question, so Cancel changes nothing.
        final List<DrawingShape> tracks = new ArrayList<>();
        for (Shape l : lines)
            tracks.add(FobsShapes.adopt(mapView, l));
        String pattern = plugin.getString(R.string.track_default_name);
        String callsign = mapView.getDeviceCallsign();
        String title = String.format(pattern, callsign,
                FobsShapes.nextTrackNumber(mapView, pattern, callsign));
        DrawingShape first = tracks.get(0);
        DrawingShape area = FobsShapes.newTrack(mapView, title,
                first.getMetaString(FobsShapes.META_SOURCE, FobsShapes.SOURCE_USER));
        area.setStrokeColor(first.getStrokeColor());
        area.setStrokeWeight(first.getStrokeWeight());
        area.setLineStyle(first.getLineStyle());
        area.setPoints(chain.points, new SparseArray<PointMapItem>());
        FobsShapes.addToMap(area);
        com.atakmap.android.fobs.feed.FeedPublisher fp = com.atakmap.android.fobs.feed.FeedPublisher.get();
        if (fp != null) {
            for (DrawingShape t : tracks)
                if (fp.isLive(t)) {
                    fp.inherit(t, area);
                    break;
                }
            for (DrawingShape t : tracks)
                fp.unpublish(t);
        }
        // The tracks were joined, so they come off the map; the area replaces them.
        for (DrawingShape t : tracks)
            t.removeFromGroup();
        finisher.makePolygon(area);
        Log.d(TAG, "lasso joined " + tracks.size() + " tracks, " + chain.points.size()
                + " points, max gap " + chain.maxGap + " m");
    }

    private void toast(String s) {
        Toast.makeText(host, s, Toast.LENGTH_SHORT).show();
    }
}
