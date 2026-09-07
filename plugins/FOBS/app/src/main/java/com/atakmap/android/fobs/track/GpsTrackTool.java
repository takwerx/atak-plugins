package com.atakmap.android.fobs.track;

import android.content.Context;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import com.atak.plugins.impl.PluginLayoutInflater;
import com.atakmap.android.drawing.mapItems.DrawingShape;
import com.atakmap.android.fobs.plugin.R;
import com.atakmap.android.maps.MapView;
import com.atakmap.android.toolbar.Tool;
import com.atakmap.android.toolbar.widgets.TextContainer;
import com.atakmap.android.tools.ActionBarReceiver;
import com.atakmap.android.tools.ActionBarView;

/**
 * The bar for a GPS track: the feed it is going to, a count of rejected fixes,
 * Pause / Resume, End. Nothing more. The recording itself is {@link TrackRecorder},
 * which outlives this tool: ATAK ends the active tool whenever another one starts,
 * and the walk must not end with it (field report, 2026-09-06).
 *
 * <p>Started with a title (and optionally a feed) it begins a new recording. Started
 * with no extras it re-attaches to the one running, which is how the bar comes back
 * after another tool, or from the pane's tile.
 */
public class GpsTrackTool extends Tool implements View.OnClickListener, TrackRecorder.Listener {

    public static final String ID = "com.atakmap.android.fobs.GpsTrackTool";
    public static final String EXTRA_TITLE = "title";
    public static final String EXTRA_FEED = "feed";
    public static final String EXTRA_FEED_SERVER = "feedServer";

    private static final String TAG = "FOBS.GpsTrackTool";

    private final MapView mapView;
    private final Context plugin;
    private final Context host;
    private final ActionBarView toolbar;
    private final Button pauseBtn;
    private final TextView droppedView;
    private final TextView feedView;
    private final TrackFinisher finisher;

    /** Set when End was answered on the bar, so the tool's end is not a dismissal. */
    private boolean endedHere;

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
        TrackRecorder rec = TrackRecorder.get();
        if (rec == null)
            return false;
        String title = extras == null ? null : extras.getString(EXTRA_TITLE);
        if (!rec.isRecording()) {
            if (title == null)
                return false; // nothing to attach to and nothing asked for
            if (!rec.start(title, extras.getString(EXTRA_FEED), extras.getString(EXTRA_FEED_SERVER))) {
                toast(R.string.toast_nothing_recorded);
                return false;
            }
        }
        endedHere = false;
        rec.setListener(this);
        refresh();
        ActionBarReceiver.getInstance().setToolView(toolbar);
        return true;
    }

    @Override
    protected void onToolEnd() {
        TrackRecorder rec = TrackRecorder.get();
        if (rec != null)
            rec.setListener(null);
        ActionBarReceiver.getInstance().setToolView(null);
        TextContainer.getInstance().closePrompt();
        // The bar went away but the walk did not. Say so once when it was not End:
        // another tool took the bar, or ATAK's back dismissed it.
        if (!endedHere && rec != null && rec.isRecording())
            toast(plugin.getString(R.string.toast_still_recording, rec.title()));
    }

    @Override
    public void dispose() {
    }

    // ---- the bar reflects the recorder --------------------------------------------

    @Override
    public void onRecordingChanged() {
        refresh();
    }

    private void refresh() {
        TrackRecorder rec = TrackRecorder.get();
        if (rec == null || !rec.isRecording())
            return;
        String feed = rec.feed();
        if (feed != null) {
            feedView.setText(plugin.getString(R.string.feed_label, feed));
            feedView.setVisibility(View.VISIBLE);
        } else {
            feedView.setVisibility(View.GONE);
        }
        boolean paused = rec.isPaused();
        pauseBtn.setText(paused ? R.string.resume : R.string.pause);
        pauseBtn.setSelected(paused);
        int n = rec.badFixes();
        if (n > 0) {
            droppedView.setText(plugin.getString(R.string.dropped_count, n));
            droppedView.setVisibility(View.VISIBLE);
        } else {
            droppedView.setVisibility(View.GONE);
        }
        TextContainer.getInstance().displayPrompt(plugin.getString(
                paused ? R.string.prompt_gps_paused : R.string.prompt_gps));
    }

    // ---- buttons -----------------------------------------------------------------

    @Override
    public void onClick(View v) {
        final TrackRecorder rec = TrackRecorder.get();
        if (rec == null)
            return;
        if (v == pauseBtn) {
            boolean paused = !rec.isPaused();
            rec.setPaused(paused);
            toast(paused ? R.string.toast_paused : R.string.toast_resumed);
        } else if (v.getId() == R.id.end) {
            DrawingShape shape = rec.shape();
            if (shape == null || rec.points() < 2) {
                endedHere = true;
                rec.end();
                toast(R.string.toast_nothing_recorded);
                requestEndTool();
                return;
            }
            // Make polygon / Single line / Cancel while still recording, so a
            // fat-fingered End costs nothing. Only an answer ends the recording.
            finisher.askThenEnd(shape, new Runnable() {
                @Override
                public void run() {
                    endedHere = true;
                    rec.end();
                    requestEndTool();
                }
            });
        }
    }

    private void toast(int res) {
        toast(plugin.getString(res));
    }

    private void toast(String s) {
        Toast.makeText(host, s, Toast.LENGTH_SHORT).show();
    }
}
