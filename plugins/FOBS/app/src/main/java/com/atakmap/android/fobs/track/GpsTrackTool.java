package com.atakmap.android.fobs.track;

import android.content.Context;
import android.os.Bundle;
import android.util.SparseArray;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import com.atak.plugins.impl.PluginLayoutInflater;
import com.atakmap.android.drawing.mapItems.DrawingShape;
import com.atakmap.android.fobs.plugin.R;
import com.atakmap.android.maps.MapView;
import com.atakmap.android.maps.Marker;
import com.atakmap.android.maps.PointMapItem;
import com.atakmap.android.toolbar.Tool;
import com.atakmap.android.toolbar.widgets.TextContainer;
import com.atakmap.android.tools.ActionBarReceiver;
import com.atakmap.android.tools.ActionBarView;
import com.atakmap.coremap.log.Log;
import com.atakmap.coremap.maps.coords.GeoPoint;
import com.atakmap.coremap.maps.coords.GeoPointMetaData;

import java.util.ArrayList;
import java.util.List;

/**
 * Start a GPS track: follow the self marker, append each fix that passes the live gate
 * to a dashed drawing shape, Pause / Resume / End on a floating toolbar. At End, run the
 * cleanup pass and ask Single line or Make polygon.
 *
 * <p>Design follows PAR's Fire Area Survey leg-collection tool, read from its APK, with
 * the GPS filtering it did not have. Same ATAK plumbing as ATAK's own drawing tools:
 * a {@link Tool} registered with the tool manager, an {@link ActionBarView} handed to
 * {@link ActionBarReceiver}, a prompt in {@link TextContainer}.
 *
 * <p>Threading: the self marker's point-changed callback is not guaranteed to arrive on
 * the main thread, and the shape is a View-like map item. Every fix is posted to the
 * main thread before it touches the shape.
 */
public class GpsTrackTool extends Tool implements PointMapItem.OnPointChangedListener,
        View.OnClickListener {

    public static final String ID = "com.atakmap.android.fobs.GpsTrackTool";
    public static final String EXTRA_TITLE = "title";
    public static final String EXTRA_FEED = "feed";
    public static final String EXTRA_FEED_SERVER = "feedServer";

    private static final String TAG = "FOBS.GpsTrackTool";

    /** Re-send the growing track over the mesh at most this often, milliseconds. */
    private static final long PERSIST_INTERVAL_MS = 15_000;
    /** ... or after this many accepted fixes, whichever first. */
    private static final int PERSIST_EVERY_FIXES = 25;

    private final MapView mapView;
    private final Context plugin;
    private final Context host;
    private final ActionBarView toolbar;
    private final Button pauseBtn;
    private final TextView droppedView;
    private final TextView feedView;

    private final FixFilter.Thresholds thresholds = new FixFilter.Thresholds();
    private final TrackFinisher finisher;

    private DrawingShape shape;
    private Marker self;
    private FixFilter.LiveGate gate;
    private final List<FixFilter.Fix> accepted = new ArrayList<>();
    private final List<GeoPointMetaData> acceptedPoints = new ArrayList<>();
    private int raw;
    private boolean paused;
    /** Set when End was answered in the dialog, so onToolEnd does not ask again. */
    private boolean answered;
    private long lastPersist;
    private int fixesSincePersist;

    public GpsTrackTool(MapView mapView, Context pluginContext) {
        super(mapView, ID);
        this.mapView = mapView;
        this.plugin = pluginContext;
        this.host = mapView.getContext();
        this.finisher = new TrackFinisher(mapView, pluginContext);

        toolbar = (ActionBarView) PluginLayoutInflater.inflate(pluginContext,
                R.layout.track_toolbar, mapView, false);
        toolbar.setEmbedState(ActionBarView.FLOATING);
        toolbar.showCloseButton(false);
        pauseBtn = toolbar.findViewById(R.id.pause);
        pauseBtn.setOnClickListener(this);
        toolbar.findViewById(R.id.end).setOnClickListener(this);
        droppedView = toolbar.findViewById(R.id.dropped);
        feedView = toolbar.findViewById(R.id.feed);
    }

    @Override
    protected boolean onToolBegin(Bundle extras) {
        String title = extras.getString(EXTRA_TITLE);
        if (title == null || title.trim().isEmpty())
            title = mapView.getDeviceCallsign();

        shape = FobsShapes.newTrack(mapView, title, FobsShapes.SOURCE_GPS);
        String feed = extras.getString(EXTRA_FEED);
        String feedServer = extras.getString(EXTRA_FEED_SERVER);
        if (feed != null && feedServer != null) {
            shape.setMetaString(com.atakmap.android.fobs.feed.FeedPublisher.META_FEED, feed);
            shape.setMetaString(com.atakmap.android.fobs.feed.FeedPublisher.META_FEED_SERVER,
                    feedServer);
            com.atakmap.android.fobs.feed.FeedPublisher fp =
                    com.atakmap.android.fobs.feed.FeedPublisher.get();
            if (fp != null)
                fp.attach(shape, new com.atakmap.android.fobs.feed.FeedPublisher.Feed(feedServer, feed));
            feedView.setText(plugin.getString(R.string.feed_label, feed));
            feedView.setVisibility(View.VISIBLE);
        } else {
            feedView.setVisibility(View.GONE);
        }
        gate = new FixFilter.LiveGate(thresholds);
        accepted.clear();
        acceptedPoints.clear();
        raw = 0;
        paused = false;
        answered = false;
        lastPersist = 0;
        fixesSincePersist = 0;
        pauseBtn.setText(R.string.pause);
        pauseBtn.setSelected(false);
        droppedView.setVisibility(View.GONE);

        self = mapView.getSelfMarker();
        if (self == null) {
            toast(R.string.toast_nothing_recorded);
            return false;
        }
        self.addOnPointChangedListener(this);
        // Seed with the current position rather than waiting for the next fix.
        onPointChanged(self);

        ActionBarReceiver.getInstance().setToolView(toolbar);
        TextContainer.getInstance().displayPrompt(plugin.getString(R.string.prompt_gps));
        return true;
    }

    @Override
    protected void onToolEnd() {
        if (self != null)
            self.removeOnPointChangedListener(this);
        self = null;
        ActionBarReceiver.getInstance().setToolView(null);
        TextContainer.getInstance().closePrompt();

        final DrawingShape finished = shape;
        shape = null;
        if (finished == null)
            return;
        if (acceptedPoints.size() < 2) {
            // Never reached the map; nothing to remove.
            toast(R.string.toast_nothing_recorded);
            return;
        }
        finish(finished);
    }

    @Override
    public void dispose() {
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
            showDropped();
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
    }

    private void showDropped() {
        int n = gate.dropped();
        if (n <= 0)
            return;
        droppedView.setText(plugin.getString(R.string.dropped_count, n));
        droppedView.setVisibility(View.VISIBLE);
    }

    // ---- toolbar -----------------------------------------------------------------

    @Override
    public void onClick(View v) {
        if (v == pauseBtn) {
            paused = !paused;
            pauseBtn.setText(paused ? R.string.resume : R.string.pause);
            pauseBtn.setSelected(paused);
            if (!paused && gate != null)
                gate.resetReference();
            TextContainer.getInstance().displayPrompt(plugin.getString(
                    paused ? R.string.prompt_gps_paused : R.string.prompt_gps));
            toast(paused ? R.string.toast_paused : R.string.toast_resumed);
        } else if (v.getId() == R.id.end) {
            if (shape == null || acceptedPoints.size() < 2) {
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

    // ---- end of track ------------------------------------------------------------

    private void finish(final DrawingShape finished) {
        boolean[] keep = FixFilter.cleanup(accepted, thresholds);
        int removed = FixFilter.removedCount(keep);
        List<GeoPointMetaData> cleaned = FixFilter.apply(acceptedPoints, keep);
        if (removed > 0) {
            // The SparseArray is required, not optional: ATAK dereferences it
            // (NPE on the phone, 2026-09-05). Empty means no vertex markers.
            finished.setPoints(cleaned, new SparseArray<PointMapItem>());
            toast(plugin.getString(R.string.toast_removed_bad_fixes, removed));
        }
        int dropped = gate.dropped() + removed;
        finished.setMetaString(FobsShapes.META_RAW, String.valueOf(raw));
        finished.setMetaString(FobsShapes.META_DROPPED, String.valueOf(dropped));
        // One line per track so a field test can be read back from logcat.
        Log.d(TAG, "end '" + finished.getTitle() + "' raw=" + raw
                + " accepted=" + accepted.size()
                + " tooClose=" + gate.count(FixFilter.Verdict.TOO_CLOSE)
                + " badAccuracy=" + gate.count(FixFilter.Verdict.BAD_ACCURACY)
                + " tooFast=" + gate.count(FixFilter.Verdict.TOO_FAST)
                + " staleTime=" + gate.count(FixFilter.Verdict.STALE_TIME)
                + " endPassRemoved=" + removed
                + " final=" + cleaned.size());
        FobsShapes.persist(mapView, finished, getClass());
        if (!answered)
            finisher.ask(finished, getClass()); // ended by ATAK (back), not by our End
    }

    private void toast(int res) {
        toast(plugin.getString(res));
    }

    private void toast(String s) {
        Toast.makeText(host, s, Toast.LENGTH_SHORT).show();
    }
}
