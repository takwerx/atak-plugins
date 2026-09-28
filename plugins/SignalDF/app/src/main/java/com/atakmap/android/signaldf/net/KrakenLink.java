package com.atakmap.android.signaldf.net;

import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import com.atakmap.android.signaldf.model.Bearing;
import com.atakmap.android.signaldf.model.FeedFrame;
import com.atakmap.android.signaldf.model.FeedSource;
import com.atakmap.android.toolbar.Tool;
import com.atakmap.android.toolbar.ToolListener;
import com.atakmap.android.toolbar.ToolManagerBroadcastReceiver;
import com.atakmap.coremap.log.Log;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * The connection to the radio, apart from any tool or pane. It picks a feed,
 * polls it, reconnects with backoff, and tells whoever is listening how old the
 * last bearing is. It lives for the life of the plugin.
 *
 * <p><b>Why it is not a {@code Tool}, and not owned by the pane.</b> ATAK runs
 * one tool at a time and ends the active one whenever another starts, a dropdown
 * opens, or Back is pressed. Work that has to outlive a tap therefore cannot
 * live inside a tool. FOBS 0.4 went to the field with a GPS recording inside its
 * tool and switching base maps ended the walk; {@code FOBS/track/TrackRecorder}
 * is the fix and this is the same shape: a plain object built once in
 * {@link com.atakmap.android.signaldf.plugin.SignalDF} at plugin start, reached
 * through {@link #get()}, disposed at plugin stop. The pane is a listener and
 * may come and go; the link does not care.
 *
 * <p>It deliberately holds neither the {@code MapView} nor the plugin context.
 * {@code TrackRecorder} takes both because it draws its own track and toasts;
 * this one only talks to the radio and keeps state, and everything that draws or
 * displays is a {@link Listener} that owns its own context. That keeps the one
 * object which must never die on a tap free of anything that can.
 *
 * <p>It also registers as a {@link ToolListener}. Nothing here needs a tool to
 * end in order to keep polling -- that follows from not being a tool at all --
 * but a tool transition is exactly the moment the operator is most likely to
 * glance at the pane, so the transition nudges listeners to redraw an accurate
 * age. When 0.2 adds a toolbar item, this is also where its bar comes back, the
 * way {@code TrackRecorder} re-shows FOBS's.
 *
 * <p><b>Staleness is the failure this class exists to prevent.</b> A bearing
 * from two minutes ago draws exactly the same line as one from this second. So
 * the age of the last frame is measured on the monotonic clock -- not the Pi's,
 * which is commonly years out, and not the wall clock, which a time sync can
 * move -- it counts through Doze, and everything that reads a bearing from here
 * can ask whether it is still {@link #isLive()}.
 *
 * <p><b>A successful read is not a new frame.</b> Measured on real hardware,
 * 2026-09-27: a Pi running krakensdr_doa 1.8.1 with no receiver attached
 * (`daq_ok: false`, empty `hardware_id`) still serves a perfectly well-formed
 * doa.xml on port 8081 -- a leftover file baked into the image, frozen at one
 * timestamp and one bearing forever. Measuring age from the moment of the fetch
 * called that a healthy 0.0 s old feed and would have drawn a bearing from a
 * radio that does not exist. So a read whose body is byte-identical to the last
 * one does not advance the clock and does not reach the listeners: the age goes
 * on climbing and the pane says the radio is answering but not producing.
 *
 * <p>Threading: everything that mutates state happens on the main thread.
 * {@link Http} does its I/O on a bounded pool and delivers back to main, and the
 * poll loop is a {@link Handler} on the main looper, so listeners are always
 * called somewhere it is safe to touch a view.
 */
public final class KrakenLink implements ToolListener {

    private static final String TAG = "SignalDF.KrakenLink";

    /**
     * Roughly the rate the DSP produces at. Faster would poll the same file
     * repeatedly for the same frame; slower would make the map lag the radio.
     */
    private static final long POLL_INTERVAL_MS = 1_000;

    /** After this long with no frame, what is on the map is not current. */
    private static final long STALE_AFTER_MS = 5_000;

    /** Backoff after a failed read: 1s, 2s, 4s, 8s, then every 15s. */
    private static final long BACKOFF_START_MS = 1_000;
    private static final long BACKOFF_MAX_MS = 15_000;

    /**
     * Consecutive failures on a feed that was working before the link goes back
     * and probes the others. An operator who changes the radio's output mode
     * mid-search should not have to reconnect by hand.
     */
    private static final int FAILURES_BEFORE_REPROBE = 3;

    /**
     * Runtime preference order: best feed first, not the order they were built.
     *
     * <p>The CSV carries every active VFO and the DoA spectrum, so it is what
     * the plugin wants; the XML carries VFO 0 and no spectrum but is written in
     * every output mode, so it is what the plugin can always fall back to. This
     * is the whole reason Signal DF connects to a Kraken that is merely running
     * rather than one that has been configured for it.
     */
    private static final Feed[] FEEDS = { new DoaCsvFeed(), new DoaXmlFeed() };

    /** Where the link is, in words the pane can show without interpreting. */
    public enum State {
        /** Never started, or stopped by the operator. */
        IDLE,
        /** Trying each feed in turn to see which one answers. */
        PROBING,
        /** A feed is answering and bearings are arriving. */
        LIVE,
        /** It was answering and stopped; retrying on a backoff. */
        RETRYING
    }

    /** Anything showing the link -- the pane, the map layer -- hears this. */
    public interface Listener {
        /** State, feed, age or error changed; nothing new to draw necessarily. */
        void onLinkChanged();

        /** A frame arrived with at least one bearing in it. */
        void onBearings(FeedFrame frame);
    }

    private static KrakenLink instance;

    /** The one link, or null before plugin start and after plugin stop. */
    public static KrakenLink get() {
        return instance;
    }

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final List<Listener> listeners = new ArrayList<>(2);

    /**
     * Keeps radio traffic on the WiFi while the phone's default network stays
     * cellular, so the operator does not have to choose between the radio and
     * the TAK server. Null when the plugin was built without a context.
     */
    private final WifiPin wifiPin;

    private KrakenHost host;
    private State state = State.IDLE;
    private Feed active;
    private int probeIndex;
    private boolean running;

    /** Monotonic, counts through Doze, unaffected by the Pi's clock or ours. */
    private long lastFrameRealtime = -1;
    private long backoffMs = BACKOFF_START_MS;
    private int consecutiveFailures;
    private String lastError = "";
    private String lastNote = "";
    private int discardedInLastFrame;
    private long framesRead;

    private List<Bearing> latest = Collections.emptyList();

    /**
     * The last response body, so an unchanged one can be recognized. Compared
     * whole rather than by the frame's own timestamp because not every feed
     * carries one, and because a radio that has stopped producing can leave any
     * single field looking plausible. Identical bytes cannot be a new frame:
     * the timestamp, the confidence and the power all jitter between real ones.
     */
    private String lastBody;

    /** Monotonic time the body last actually changed, or -1. */
    private long lastChangeRealtime = -1;

    /** A poll already in flight; a late answer from an older one is ignored. */
    private int generation;

    public KrakenLink() {
        this(null);
    }

    /**
     * @param context the HOST's context (MapView.getContext()), never the
     *                plugin's -- a plugin context has no system services. Null
     *                falls back to the phone's default network for every
     *                request, which is the behavior before {@link WifiPin}.
     */
    public KrakenLink(Context context) {
        instance = this;
        wifiPin = context == null ? null : new WifiPin(context);
        if (wifiPin != null)
            wifiPin.start();
        ToolManagerBroadcastReceiver.getInstance().registerListener(this);
    }

    /** Whether radio traffic is being kept off the phone's default route. */
    public boolean isPinnedToWifi() {
        return wifiPin != null && wifiPin.isPinned();
    }

    /** Plugin stop. Stops polling; keeps nothing running behind it. */
    public void dispose() {
        ToolManagerBroadcastReceiver.getInstance().unregisterListener(this);
        if (wifiPin != null)
            wifiPin.stop();
        stop();
        listeners.clear();
        if (instance == this)
            instance = null;
    }

    // ---- listeners ---------------------------------------------------------

    public void addListener(Listener l) {
        if (l != null && !listeners.contains(l))
            listeners.add(l);
    }

    public void removeListener(Listener l) {
        listeners.remove(l);
    }

    private void notifyChanged() {
        // Copied because a listener may remove itself while being told.
        for (Listener l : new ArrayList<>(listeners))
            l.onLinkChanged();
    }

    private void notifyBearings(FeedFrame frame) {
        for (Listener l : new ArrayList<>(listeners))
            l.onBearings(frame);
    }

    // ---- state the pane reads ----------------------------------------------

    public State state() {
        return state;
    }

    /** Which feed answered, or null while probing or idle. */
    public FeedSource activeFeed() {
        return active == null ? null : active.source();
    }

    /** What the active feed cannot give, in words, or empty. */
    public String activeFeedLimits() {
        return active == null ? "" : active.limits();
    }

    /** The bearing convention the active feed is being read with. */
    public String activeConvention() {
        return active == null ? "" : active.convention().label();
    }

    public KrakenHost host() {
        return host;
    }

    /**
     * Milliseconds since the feed last produced something new, or -1 if it
     * never has. Deliberately not "since the last successful read": a frozen
     * file reads successfully forever.
     */
    public long ageMs() {
        if (lastFrameRealtime < 0)
            return -1;
        return SystemClock.elapsedRealtime() - lastFrameRealtime;
    }

    /**
     * True when the radio is answering but serving the same bytes over and
     * over. Distinct from a network drop, and the operator needs to be told
     * which one they have: this one usually means the receiver is not attached
     * or the DSP has stopped, and no amount of waiting will fix it.
     */
    public boolean isRepeating() {
        return state == State.LIVE && lastChangeRealtime >= 0
                && SystemClock.elapsedRealtime() - lastChangeRealtime >= STALE_AFTER_MS;
    }

    /**
     * Whether what is on the map is current. Anything drawn from a bearing
     * older than this has to look different, or say so, or come off the map --
     * a stale bearing is indistinguishable from a live one by eye.
     */
    public boolean isLive() {
        long age = ageMs();
        return age >= 0 && age < STALE_AFTER_MS;
    }

    /** The most recent bearing per VFO, newest read wins. Never null. */
    public List<Bearing> latest() {
        return latest;
    }

    public String lastError() {
        return lastError;
    }

    /** Parse notes from the last read -- torn lines and the like. May be empty. */
    public String lastNote() {
        return lastNote;
    }

    public int discardedInLastFrame() {
        return discardedInLastFrame;
    }

    public long framesRead() {
        return framesRead;
    }

    /**
     * One line for the pane's status row. It never says "connected" without
     * saying how old the newest bearing is, because those are different facts
     * and only the second one decides whether the map is worth believing.
     */
    public String status() {
        switch (state) {
            case IDLE:
                return "Not connected";
            case PROBING:
                return "Looking for the radio at " + host;
            case RETRYING:
                return lastError.isEmpty()
                        ? "Reconnecting to " + host
                        : "Reconnecting to " + host + " -- " + lastError;
            case LIVE:
            default:
                long age = ageMs();
                if (age < 0)
                    return active.source().label() + " answering, no bearing yet";
                if (isRepeating())
                    // Not a network problem, and saying "nothing for 40 s"
                    // would send the operator to check the wrong thing.
                    return String.format(Locale.US,
                            "%s answering but not updating for %.0f s -- check the "
                                    + "receiver is attached",
                            active.source().label(), age / 1000.0);
                if (age < STALE_AFTER_MS)
                    return String.format(Locale.US, "%s, %.1f s ago",
                            active.source().label(), age / 1000.0);
                return String.format(Locale.US, "%s, nothing for %.0f s",
                        active.source().label(), age / 1000.0);
        }
    }

    // ---- running -----------------------------------------------------------

    public boolean isRunning() {
        return running;
    }

    /**
     * Connects to {@code host} and starts polling. Safe to call again with a
     * different host; the old poll loop is abandoned rather than stacked.
     */
    public void start(KrakenHost host) {
        if (host == null)
            return;
        stop();
        this.host = host;
        this.running = true;
        this.state = State.PROBING;
        this.probeIndex = 0;
        this.active = null;
        this.lastError = "";
        this.lastNote = "";
        this.backoffMs = BACKOFF_START_MS;
        this.consecutiveFailures = 0;
        this.lastBody = null;
        this.lastChangeRealtime = -1;
        this.lastFrameRealtime = -1;
        Log.d(TAG, "starting on " + host);
        notifyChanged();
        poll();
    }

    /** Stops polling. What is already on the map stays; nothing is retried. */
    public void stop() {
        if (!running && state == State.IDLE)
            return;
        running = false;
        generation++;
        handler.removeCallbacksAndMessages(null);
        state = State.IDLE;
        Log.d(TAG, "stopped");
        notifyChanged();
    }

    private void scheduleNext(long delayMs) {
        if (!running)
            return;
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                poll();
            }
        }, delayMs);
    }

    private void poll() {
        if (!running || host == null)
            return;

        final Feed feed = active != null ? active : FEEDS[probeIndex];
        final int mine = ++generation;
        final String url = host.url(feed);

        Http.get(url, wifiPin == null ? null : wifiPin.network(), new Http.Callback() {
            @Override
            public void onSuccess(String body) {
                if (mine != generation || !running)
                    return;
                onBody(feed, body);
            }

            @Override
            public void onFailure(String error) {
                if (mine != generation || !running)
                    return;
                onError(feed, error);
            }
        });
    }

    private void onBody(Feed feed, String body) {
        FeedFrame frame = feed.parse(body, System.currentTimeMillis());
        lastNote = frame.note;
        discardedInLastFrame = frame.discarded;

        if (frame.isEmpty()) {
            // The feed answered but had nothing usable in it. While probing that
            // means this is the wrong feed for the radio's current mode -- the
            // CSV is absent in Kerberos App mode and the file can be there and
            // empty -- so move on rather than sit on a silent socket.
            if (active == null) {
                nextProbe(frame.note.isEmpty() ? "nothing in it" : frame.note);
                return;
            }
            // On an established feed it is one empty read: keep going, and let
            // the age climb so the pane shows the gap rather than hiding it.
            lastError = frame.note;
            notifyChanged();
            scheduleNext(POLL_INTERVAL_MS);
            return;
        }

        if (active != feed) {
            Log.d(TAG, "feed chosen: " + feed.source().label());
            active = feed;
        }
        state = State.LIVE;
        lastError = "";
        backoffMs = BACKOFF_START_MS;
        consecutiveFailures = 0;

        // A body identical to the last one is the same frame served again, not
        // a new measurement. See the class comment: a radio with no receiver
        // attached serves a frozen doa.xml indefinitely.
        boolean changed = lastBody == null || !lastBody.equals(body);
        lastBody = body;
        if (!changed) {
            notifyChanged();
            scheduleNext(POLL_INTERVAL_MS);
            return;
        }

        framesRead++;
        lastFrameRealtime = SystemClock.elapsedRealtime();
        lastChangeRealtime = lastFrameRealtime;
        latest = frame.bearings;

        notifyBearings(frame);
        notifyChanged();
        scheduleNext(POLL_INTERVAL_MS);
    }

    private void onError(Feed feed, String error) {
        if (active == null) {
            nextProbe(error);
            return;
        }
        consecutiveFailures++;
        lastError = error;
        state = State.RETRYING;
        if (consecutiveFailures >= FAILURES_BEFORE_REPROBE) {
            // The radio may still be there with its output mode changed under
            // us, which looks identical to a network drop from here.
            Log.d(TAG, "re-probing after " + consecutiveFailures + " failures on "
                    + feed.source().label());
            active = null;
            probeIndex = 0;
            state = State.PROBING;
            consecutiveFailures = 0;
        }
        notifyChanged();
        scheduleNext(nextBackoff());
    }

    /** Move to the next feed, or start the whole probe again after a backoff. */
    private void nextProbe(String why) {
        Feed tried = FEEDS[probeIndex];
        Log.d(TAG, "no bearings on " + tried.source().label() + ": " + why);
        probeIndex++;
        if (probeIndex < FEEDS.length) {
            lastError = tried.source().label() + ": " + why;
            state = State.PROBING;
            notifyChanged();
            // Straight on to the next feed; this is one extra request, not a
            // retry, and the operator is watching a Connect button.
            scheduleNext(0);
            return;
        }
        probeIndex = 0;
        state = State.RETRYING;
        lastError = why;
        notifyChanged();
        scheduleNext(nextBackoff());
    }

    private long nextBackoff() {
        long wait = backoffMs;
        backoffMs = Math.min(BACKOFF_MAX_MS, backoffMs * 2);
        return wait;
    }

    // ---- ToolListener ------------------------------------------------------

    @Override
    public void onToolBegin(Tool tool, Bundle extras) {
        // The link is unaffected -- it is not a tool. Listeners are nudged
        // because this is a moment the operator is likely to look at the pane,
        // and an age that has not been redrawn is the kind of stale number this
        // plugin is supposed to be careful about.
        notifyChanged();
    }

    @Override
    public void onToolEnded(Tool tool) {
        notifyChanged();
    }
}
