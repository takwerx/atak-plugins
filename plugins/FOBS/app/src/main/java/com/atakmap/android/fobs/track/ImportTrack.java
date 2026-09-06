package com.atakmap.android.fobs.track;

import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.text.InputType;
import android.util.SparseArray;
import android.widget.EditText;
import android.widget.Toast;

import com.atakmap.android.drawing.mapItems.DrawingShape;
import com.atakmap.android.fobs.plugin.R;
import com.atakmap.android.ipc.AtakBroadcast;
import com.atakmap.android.maps.MapView;
import com.atakmap.android.maps.PointMapItem;
import com.atakmap.android.track.crumb.CrumbDatabase;
import com.atakmap.android.track.crumb.CrumbPoint;
import com.atakmap.android.track.maps.TrackPolyline;
import com.atakmap.coremap.log.Log;
import com.atakmap.coremap.maps.coords.GeoPoint;
import com.atakmap.coremap.maps.coords.GeoPointMetaData;

import java.util.ArrayList;
import java.util.List;

/**
 * Import a track from ATAK's own track history: this device's breadcrumb log, or the
 * TAK Server's record of any user's track (the beacon's reports land there too).
 *
 * <p>Finding the track is ATAK's job. Import opens ATAK's Track History screen, with
 * its list, its on/off toggles and its server search; the operator turns the track on
 * and then picks the FOBS button on the line's radial menu (or FOBS -> Select element
 * and taps the line). Every track that screen can show, local or fetched from a
 * server, ends up in the same {@link CrumbDatabase}, so one import path covers both.
 *
 * <p>Crumbs are run through the same {@link FixFilter} gate and cleanup as a live GPS
 * track, using each crumb's own accuracy, speed and time, so an imported walk is
 * cleaned to the same standard as a walked one. Nothing in the log is modified.
 *
 * <p>Only classes common to ATAK 5.6 through 5.8 are used here. A FOBS-side server
 * query (QueryUserTracksRequest2 and friends) was tried and dropped: those classes
 * exist only in the 5.8 SDK, and Track History already does the same search.
 */
public class ImportTrack {

    private static final String TAG = "FOBS.ImportTrack";
    private static final String TRACK_HISTORY = "com.atakmap.android.track.TRACK_HISTORY";

    private final MapView mapView;
    private final Context plugin;
    private final Context host;
    private final TrackFinisher finisher;

    public ImportTrack(MapView mapView, Context pluginContext) {
        this.mapView = mapView;
        this.plugin = pluginContext;
        this.host = mapView.getContext();
        this.finisher = new TrackFinisher(mapView, pluginContext);
    }

    public void dispose() {
    }

    // ---- entry -------------------------------------------------------------------

    /** ATAK's Track History, with a reminder of how the track gets back to FOBS. */
    public void begin() {
        AtakBroadcast.getInstance().sendBroadcast(new Intent(TRACK_HISTORY));
        Toast.makeText(host, plugin.getString(R.string.import_track_history_hint),
                Toast.LENGTH_LONG).show();
    }

    /** A track line the operator turned on in Track History and picked on the map. */
    public void importVisible(TrackPolyline track) {
        nameThenImport(track);
    }

    private void nameThenImport(final TrackPolyline source) {
        String pattern = plugin.getString(R.string.track_default_name);
        String callsign = mapView.getDeviceCallsign();
        final EditText name = new EditText(host);
        name.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        name.setSingleLine(true);
        name.setText(String.format(pattern, callsign,
                FobsShapes.nextTrackNumber(mapView, pattern, callsign)));
        name.setSelection(name.getText().length());
        new AlertDialog.Builder(host)
                .setTitle(plugin.getString(R.string.name_track))
                .setView(name)
                .setPositiveButton(plugin.getString(R.string.ok),
                        new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface d, int which) {
                                importTrack(name.getText().toString().trim(), source);
                            }
                        })
                .setNegativeButton(plugin.getString(R.string.cancel), null)
                .show();
    }

    // ---- crumbs to track ---------------------------------------------------------

    private void importTrack(final String title, final TrackPolyline source) {
        final int dbId = source.getMetaInteger(CrumbDatabase.META_TRACK_DBID, -1);
        new Thread(new Runnable() {
            @Override
            public void run() {
                final List<CrumbPoint> crumbs = dbId >= 0
                        ? CrumbDatabase.instance().getCrumbPoints(dbId) : null;
                mapView.post(new Runnable() {
                    @Override
                    public void run() {
                        build(title, source, crumbs);
                    }
                });
            }
        }, TAG + "-crumbs").start();
    }

    /** Main thread. Gate and clean the crumbs like a live walk, then make the track. */
    private void build(String title, TrackPolyline source, List<CrumbPoint> crumbs) {
        List<GeoPointMetaData> points = new ArrayList<>();
        List<FixFilter.Fix> fixes = new ArrayList<>();
        FixFilter.Thresholds t = new FixFilter.Thresholds();
        // A record, not a live feed: beacon reports are sparse and their timestamps
        // are the server's, so implied speed means nothing here. Three real fixes on
        // a server track were dropped as "too fast" on 2026-09-05. Accuracy and
        // spacing still apply.
        t.maxSpeed = Double.POSITIVE_INFINITY;
        FixFilter.LiveGate gate = new FixFilter.LiveGate(t);
        int raw = 0;
        if (crumbs != null && !crumbs.isEmpty()) {
            for (CrumbPoint c : crumbs) {
                if (c == null || c.gp == null || !c.gp.isValid())
                    continue;
                raw++;
                double ce = c.gp.getCE();
                if (ce == GeoPoint.UNKNOWN)
                    ce = Double.NaN;
                FixFilter.Fix f = new FixFilter.Fix(c.gp.getLatitude(), c.gp.getLongitude(), ce,
                        c.timestamp);
                if (gate.judge(f) != FixFilter.Verdict.ACCEPTED)
                    continue;
                GeoPointMetaData gpm = c.gpm != null ? new GeoPointMetaData(c.gpm)
                        : GeoPointMetaData.wrap(c.gp);
                if (gpm.getGeopointSource() == null)
                    gpm.setGeoPointSource(FobsShapes.SOURCE_GPS);
                fixes.add(f);
                points.add(gpm);
            }
        } else {
            // No crumb rows (older import): fall back to the polyline's own points.
            GeoPointMetaData[] pts = source.getMetaDataPoints();
            if (pts != null)
                for (GeoPointMetaData p : pts) {
                    if (p == null || p.get() == null || !p.get().isValid())
                        continue;
                    raw++;
                    GeoPoint g = p.get();
                    double ce = g.getCE() == GeoPoint.UNKNOWN ? Double.NaN : g.getCE();
                    FixFilter.Fix f = new FixFilter.Fix(g.getLatitude(), g.getLongitude(), ce, 0);
                    if (gate.judge(f) != FixFilter.Verdict.ACCEPTED)
                        continue;
                    fixes.add(f);
                    points.add(new GeoPointMetaData(p));
                }
        }
        if (points.size() < 2) {
            toast(plugin.getString(R.string.toast_nothing_recorded));
            return;
        }
        boolean[] keep = FixFilter.cleanup(fixes, t);
        int removed = FixFilter.removedCount(keep);
        List<GeoPointMetaData> cleaned = FixFilter.apply(points, keep);
        int dropped = gate.dropped() + removed;
        Log.d(TAG, "import raw=" + raw + " accepted=" + points.size()
                + " tooClose=" + gate.count(FixFilter.Verdict.TOO_CLOSE)
                + " badAccuracy=" + gate.count(FixFilter.Verdict.BAD_ACCURACY)
                + " tooFast=" + gate.count(FixFilter.Verdict.TOO_FAST)
                + " staleTime=" + gate.count(FixFilter.Verdict.STALE_TIME)
                + " endPassRemoved=" + removed + " final=" + cleaned.size());

        DrawingShape track = FobsShapes.newTrack(mapView, title, FobsShapes.SOURCE_GPS);
        track.setPoints(cleaned, new SparseArray<PointMapItem>());
        track.setMetaString(FobsShapes.META_RAW, String.valueOf(raw));
        track.setMetaString(FobsShapes.META_DROPPED, String.valueOf(dropped));
        FobsShapes.addToMap(track);
        if (dropped > 0)
            toast(plugin.getString(R.string.toast_removed_bad_fixes, dropped));
        finisher.ask(track, getClass());
    }

    private void toast(String s) {
        Toast.makeText(host, s, Toast.LENGTH_SHORT).show();
    }
}
