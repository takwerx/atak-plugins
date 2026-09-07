package com.atakmap.android.fobs.track;

import android.content.Context;
import android.os.Bundle;
import android.util.SparseArray;
import android.widget.Toast;

import com.atakmap.android.drawing.mapItems.DrawingShape;
import com.atakmap.android.fobs.feed.FeedPublisher;
import com.atakmap.android.fobs.plugin.R;
import com.atakmap.android.maps.MapView;
import com.atakmap.android.maps.Marker;
import com.atakmap.android.maps.PointMapItem;
import com.atakmap.android.toolbar.Tool;
import com.atakmap.android.toolbar.ToolListener;
import com.atakmap.android.toolbar.ToolManagerBroadcastReceiver;
import com.atakmap.coremap.log.Log;
import com.atakmap.coremap.maps.coords.GeoPoint;
import com.atakmap.coremap.maps.coords.GeoPointMetaData;

import java.util.ArrayList;
import java.util.List;

/**
 * The GPS recording itself, apart from any tool. It follows the self marker, appends
 * each fix that passes the live gate to the track, saves and re-sends on a throttle,
 * and runs the cleanup pass at End. It lives for the life of the plugin.
 *
 * <p>Why it is not inside {@link GpsTrackTool}: ATAK runs one tool at a time, and
 * starting any other tool ends the current one. When the recording was the tool's,
 * opening ATAK's drawing tool, or FOBS's own Split, ended the walk and popped the
 * Track finished dialog (field report, 2026-09-06: "if they switch to another tool
 * the track stops sending"). Now the tool is only the bar with Pause and End; the
 * recording keeps going until End is answered, through tool changes, the back
 * button, the screen locking and ATAK going to the background, with or without Data
 * Sync on the device. The bar comes back on its own when the other tool ends, and
 * the pane's tile reads Recording while it runs.
 *
 * <p>Threading: the self marker's point-changed callback is not guaranteed to arrive
 * on the main thread, and the shape is a View-like map item. Every fix is posted to
 * the main thread before it touches the shape.
 */
public class TrackRecorder implements PointMapItem.OnPointChangedListener, ToolListener {

    private static final String TAG = "FOBS.TrackRecorder";

    /** Re-send the growing track over the mesh at most this often, milliseconds. */
    private static final long PERSIST_INTERVAL_MS = 15_000;
    /** ... or after this many accepted fixes, whichever first. */
    private static final int PERSIST_EVERY_FIXES = 25;
    /** How long after another tool ends before the bar comes back. */
    private static final long RESHOW_DELAY_MS = 400;

    /** Whoever is showing the recording (the bar) hears about every change. */
    public interface Listener {
        void onRecordingChanged();
    }

    private static TrackRecorder instance;

    public static TrackRecorder get() {
        return instance;
    }

    private final MapView mapView;
    private final Context plugin;
    private final Context host;
    private final FixFilter.Thresholds thresholds = new FixFilter.Thresholds();

    private DrawingShape shape;
    private Marker self;
    private FixFilter.LiveGate gate;
    private final List<FixFilter.Fix> accepted = new ArrayList<>();
    private final List<GeoPointMetaData> acceptedPoints = new ArrayList<>();
    private int raw;
    private boolean paused;
    private long lastPersist;
    private int fixesSincePersist;
    private String feed;
    private Listener listener;

    public TrackRecorder(MapView mapView, Context pluginContext) {
        this.mapView = mapView;
        this.plugin = pluginContext;
        this.host = mapView.getContext();
        instance = this;
        ToolManagerBroadcastReceiver.getInstance().registerListener(this);
    }

    /** Plugin stop. Whatever was walked is kept, not lost. */
    public void dispose() {
        ToolManagerBroadcastReceiver.getInstance().unregisterListener(this);
        if (isRecording()) {
            Log.d(TAG, "plugin stopping while recording; ending the track");
            end();
        }
        if (instance == this)
            instance = null;
    }

    // ---- state -------------------------------------------------------------------

    public boolean isRecording() {
        return shape != null;
    }

    public boolean isPaused() {
        return paused;
    }

    public DrawingShape shape() {
        return shape;
    }

    public String title() {
        return shape == null ? null : shape.getTitle();
    }

    public String feed() {
        return feed;
    }

    public int points() {
        return acceptedPoints.size();
    }

    /** Every fix the gate refused, standing still included. The record at End. */
    public int dropped() {
        return gate == null ? 0 : gate.dropped();
    }

    /**
     * Fixes refused because they could not be real: poor accuracy, an impossible
     * jump, a stale time. Not the standing-still ones, which are normal and would
     * climb by the second while the operator waits (XCover indoors, 2026-09-06:
     * "dropped 1" with no GPS). This is what the bar shows.
     */
    public int badFixes() {
        if (gate == null)
            return 0;
        return gate.count(FixFilter.Verdict.BAD_ACCURACY)
                + gate.count(FixFilter.Verdict.TOO_FAST)
                + gate.count(FixFilter.Verdict.STALE_TIME);
    }

    public void setListener(Listener l) {
        listener = l;
    }

    private void changed() {
        if (listener != null)
            listener.onRecordingChanged();
    }

    // ---- start, pause, end -------------------------------------------------------

    /**
     * Begin a track. False if one is already running (End it first) or there is no
     * self marker to follow.
     */
    public boolean start(String title, String feedName, String feedServer) {
        if (isRecording())
            return false;
        if (title == null || title.trim().isEmpty())
            title = mapView.getDeviceCallsign();
        Marker me = mapView.getSelfMarker();
        if (me == null)
            return false;

        DrawingShape s = FobsShapes.newTrack(mapView, title, FobsShapes.SOURCE_GPS);
        feed = null;
        if (feedName != null && feedServer != null) {
            s.setMetaString(FeedPublisher.META_FEED, feedName);
            s.setMetaString(FeedPublisher.META_FEED_SERVER, feedServer);
            FeedPublisher fp = FeedPublisher.get();
            if (fp != null)
                fp.attach(s, new FeedPublisher.Feed(feedServer, feedName));
            feed = feedName;
        }
        gate = new FixFilter.LiveGate(thresholds);
        accepted.clear();
        acceptedPoints.clear();
        raw = 0;
        paused = false;
        lastPersist = 0;
        fixesSincePersist = 0;
        shape = s;
        self = me;
        self.addOnPointChangedListener(this);
        // Seed with the current position rather than waiting for the next fix.
        onPointChanged(self);
        Log.d(TAG, "recording started");
        changed();
        return true;
    }

    public void setPaused(boolean p) {
        if (!isRecording() || paused == p)
            return;
        paused = p;
        if (!paused && gate != null)
            gate.resetReference();
        changed();
    }

    /**
     * Stop following, run the cleanup pass, save. Returns the finished track, or null
     * when fewer than two fixes were ever accepted (the shape never reached the map).
     */
    public DrawingShape end() {
        if (self != null)
            self.removeOnPointChangedListener(this);
        self = null;
        final DrawingShape finished = shape;
        shape = null;
        feed = null;
        if (finished == null)
            return null;
        if (acceptedPoints.size() < 2) {
            Log.d(TAG, "end with " + acceptedPoints.size() + " fixes; nothing kept");
            changed();
            return null;
        }
        boolean[] keep = FixFilter.cleanup(accepted, thresholds);
        int removed = FixFilter.removedCount(keep);
        List<GeoPointMetaData> cleaned = FixFilter.apply(acceptedPoints, keep);
        if (removed > 0) {
            // The SparseArray is required, not optional: ATAK dereferences it
            // (NPE on the phone, 2026-09-05). Empty means no vertex markers.
            finished.setPoints(cleaned, new SparseArray<PointMapItem>());
            Toast.makeText(host, plugin.getString(R.string.toast_removed_bad_fixes, removed),
                    Toast.LENGTH_SHORT).show();
        }
        int dropped = gate.dropped() + removed;
        finished.setMetaString(FobsShapes.META_RAW, String.valueOf(raw));
        finished.setMetaString(FobsShapes.META_DROPPED, String.valueOf(dropped));
        // One line per track so a field test can be read back from logcat.
        Log.d(TAG, "end raw=" + raw
                + " accepted=" + accepted.size()
                + " tooClose=" + gate.count(FixFilter.Verdict.TOO_CLOSE)
                + " badAccuracy=" + gate.count(FixFilter.Verdict.BAD_ACCURACY)
                + " tooFast=" + gate.count(FixFilter.Verdict.TOO_FAST)
                + " staleTime=" + gate.count(FixFilter.Verdict.STALE_TIME)
                + " endPassRemoved=" + removed
                + " final=" + cleaned.size());
        FobsShapes.persist(mapView, finished, getClass());
        changed();
        return finished;
    }

    // ---- fixes -------------------------------------------------------------------

    @Override
    public void onPointChanged(final PointMapItem item) {
        final GeoPointMetaData gpm = item.getGeoPointMetaData();
        if (gpm == null)
            return;
        final GeoPoint gp = gpm.get();
        if (gp == null || !gp.isValid())
            return;
        final long time = item.getMetaLong("gpsTimestamp", 0L);
        mapView.post(new Runnable() {
            @Override
            public void run() {
                offer(gpm, gp, time);
            }
        });
    }

    /** Main thread only. */
    private void offer(GeoPointMetaData gpm, GeoPoint gp, long time) {
        if (shape == null || gate == null)
            return;
        raw++;
        if (paused)
            return;
        double ce = gp.getCE();
        if (ce == GeoPoint.UNKNOWN)
            ce = Double.NaN;
        FixFilter.Fix fix = new FixFilter.Fix(gp.getLatitude(), gp.getLongitude(), ce, time);
        FixFilter.Verdict v = gate.judge(fix);
        if (v != FixFilter.Verdict.ACCEPTED) {
            changed();
            return;
        }
        GeoPointMetaData copy = new GeoPointMetaData(gpm);
        if (copy.getGeopointSource() == null)
            copy.setGeoPointSource(FobsShapes.SOURCE_GPS);
        accepted.add(fix);
        acceptedPoints.add(copy);
        shape.addPoint(copy);
        if (acceptedPoints.size() == 1 && copy.getAltitudeSource() != null)
            shape.setMetaString(FobsShapes.META_ALTSRC, copy.getAltitudeSource());
        // On the map only once it is a line. See FobsShapes.newTrack.
        if (acceptedPoints.size() == 2)
            FobsShapes.addToMap(shape);

        fixesSincePersist++;
        long now = System.currentTimeMillis();
        if (acceptedPoints.size() >= 2 && (fixesSincePersist >= PERSIST_EVERY_FIXES
                || now - lastPersist >= PERSIST_INTERVAL_MS)) {
            FobsShapes.persist(mapView, shape, getClass());
            lastPersist = now;
            fixesSincePersist = 0;
        }
        changed();
    }

    // ---- the bar comes back ------------------------------------------------------

    @Override
    public void onToolBegin(Tool tool, Bundle extras) {
    }

    /**
     * Another tool has finished while a track is recording: bring the bar back so
     * Pause and End are in reach again. Not when the bar itself was dismissed (End,
     * or ATAK's back); the pane's tile is the way back from that.
     */
    @Override
    public void onToolEnded(Tool tool) {
        if (!isRecording() || tool instanceof GpsTrackTool)
            return;
        mapView.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (!isRecording())
                    return;
                ToolManagerBroadcastReceiver tm = ToolManagerBroadcastReceiver.getInstance();
                if (tm.getActiveTool() == null)
                    tm.startTool(GpsTrackTool.ID, new Bundle());
            }
        }, RESHOW_DELAY_MS);
    }
}
