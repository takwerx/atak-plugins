
package com.atakmap.android.atmosphere.overlay;

import android.content.Context;
import android.content.SharedPreferences;

import com.atakmap.android.atmosphere.compat.MapCompat;
import com.atakmap.android.atmosphere.data.Spot;
import com.atakmap.android.atmosphere.data.States;
import com.atakmap.android.atmosphere.net.EgressPolicy;
import com.atakmap.android.atmosphere.net.Http;
import com.atakmap.android.maps.MapView;
import com.atakmap.coremap.log.Log;
import com.atakmap.coremap.maps.coords.GeoPoint;
import com.atakmap.map.layer.feature.AttributeSet;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Open spot forecast requests on the map, drawn the way NWS's own Spot Forecast
 * Monitor draws them (operator, 2026-09-25, with the Monitor's screenshots: "for spot
 * weather can we have a map layer please and make a similar design").
 *
 * <p>The same list the spot page already reads -- NWS's published Fire Weather Spot
 * map service, no key, updated every fifteen minutes -- put on the map instead of in
 * a list. A disc per request, one letter for what it is, colored by how far along it
 * is; see {@link SpotIcons} for the legend, which is NWS's, not ours.
 *
 * <p>Each kind is its own feature set, so Overlay Manager lists Wildfire, Prescribed
 * Fire, HAZMAT and the rest separately and ATAK's own visibility switches work on
 * them one at a time. That is what makes this behave like the Feature Layer plugin's
 * layers rather than like a heap of markers.
 */
public final class SpotOverlay {

    private static final String TAG = "AtmosphereSpot";

    public static final String LAYER_ID = "spotforecasts";
    public static final String HOST = Spot.HOST;
    private static final String NAME = "Spot forecasts";

    /** NWS's own three status colors, so the pane's legend and the map agree. */
    public static final int DONE = SpotIcons.DONE;
    public static final int WAITING = SpotIcons.WAITING;
    public static final int PENDING = SpotIcons.PENDING;

    private static final String PREF_ON = "weather.layer.spot.on";
    private static final String PREF_OPEN_ONLY = "weather.layer.spot.openonly";

    /** The service says it republishes every fifteen minutes; match it, no faster. */
    private static final long POLL_MS = 15 * 60 * 1000L;
    private static final long REFRESH_MS = 5 * 60 * 1000L;

    public interface Listener {
        void onStatus(String status);

        /** How many are drawn, and of how many, for the line under the toggle. */
        void onDrawn(int drawn, int total);
    }

    private final MapView mapView;
    private final Context pluginContext;
    private final EgressPolicy egress;
    private final AtmosphereFeatures features;
    private final SpotIcons icons = new SpotIcons();
    private final ExecutorService worker = Executors.newSingleThreadExecutor();

    private Listener listener;
    private boolean started, on, inFlight;
    private boolean openOnly;
    private long lastPoll;
    private int generation;
    private States states;
    private List<Spot.Request> requests = new ArrayList<>();

    private final Runnable autoPoll = new Runnable() {
        @Override
        public void run() {
            if (!on || !started)
                return;
            refresh(true);
            mapView.postDelayed(this, POLL_MS);
        }
    };

    public SpotOverlay(MapView mapView, Context pluginContext, EgressPolicy egress) {
        this.mapView = mapView;
        this.pluginContext = pluginContext;
        this.egress = egress;
        this.features = new AtmosphereFeatures(mapView, pluginContext, TAG, NAME,
                "spot.sqlite", "spot", false);
        final SharedPreferences p = MapCompat.prefs();
        // Most of the list is finished work from the past week. A crew looking at the
        // map wants what is still open, so that is where this starts.
        openOnly = p == null || p.getBoolean(PREF_OPEN_ONLY, true);
    }

    public void setListener(Listener l) {
        listener = l;
    }

    public void start() {
        started = true;
        features.attach();
        final SharedPreferences p = MapCompat.prefs();
        if (p != null && p.getBoolean(PREF_ON, false) && egress.isLayerEnabled(LAYER_ID))
            setOn(true);
        // The store outlives the session: last week's requests would otherwise draw
        // now, hours after they were filled.
        if (!on)
            features.clear();
    }

    public void stop() {
        started = false;
        on = false;
        generation++;
        mapView.removeCallbacks(autoPoll);
        worker.shutdownNow();
        features.detach();
    }

    public boolean isOn() {
        return on;
    }

    public void setOn(boolean value) {
        if (!started || on == value)
            return;
        on = value;
        final SharedPreferences p = MapCompat.prefs();
        if (p != null)
            p.edit().putBoolean(PREF_ON, value).apply();
        mapView.removeCallbacks(autoPoll);
        generation++;
        inFlight = false;
        if (value) {
            refresh(true);
            mapView.postDelayed(autoPoll, POLL_MS);
        } else {
            requests = new ArrayList<>();
            features.clear();
            status("");
            drawn(0, 0);
        }
    }

    /** Whether only requests NWS still has open are drawn. */
    public boolean isOpenOnly() {
        return openOnly;
    }

    /** Switch it; redrawn from what is already held, no new request. */
    public void setOpenOnly(boolean value) {
        openOnly = value;
        final SharedPreferences p = MapCompat.prefs();
        if (p != null)
            p.edit().putBoolean(PREF_OPEN_ONLY, value).apply();
        if (on)
            rebuild(generation);
    }

    /** Ask for the country's spot requests, unless that was done very recently. */
    public void refresh(boolean force) {
        if (!on || !started || inFlight)
            return;
        final long now = System.currentTimeMillis();
        if (!force && now - lastPoll < REFRESH_MS)
            return;
        lastPoll = now;
        inFlight = true;
        final int mine = generation;
        if (requests.isEmpty())
            status("Getting spot forecasts…");
        final Map<String, String> headers = new HashMap<>();
        headers.put("Accept", "application/json");
        // 717 KB as it stands, 79 KB gzipped.
        headers.put("Accept-Encoding", "gzip");
        Http.get(Spot.LIST_URL, egress.userAgent(), headers, new Http.Callback() {
            @Override
            public void onSuccess(final String body) {
                if (mine != generation || !on) {
                    inFlight = false;
                    return;
                }
                // A few hundred milliseconds of JSON for the whole country; not on
                // the thread the map draws on.
                worker.execute(new Runnable() {
                    @Override
                    public void run() {
                        List<Spot.Request> parsed = null;
                        try {
                            if (states == null)
                                states = loadStates();
                            parsed = Spot.parse(body, states);
                        } catch (Exception e) {
                            Log.w(TAG, "spot list unreadable", e);
                        }
                        final List<Spot.Request> got = parsed;
                        mapView.post(new Runnable() {
                            @Override
                            public void run() {
                                inFlight = false;
                                if (mine != generation || !on)
                                    return;
                                if (got == null) {
                                    status("Could not read the spot list");
                                    return;
                                }
                                requests = got;
                                rebuild(mine);
                            }
                        });
                    }
                });
            }

            @Override
            public void onFailure(String error) {
                inFlight = false;
                if (mine != generation || !on)
                    return;
                Log.w(TAG, "spot list failed: " + error);
                status("Could not reach the spot service");
            }
        });
    }

    /** Draw what is held, under the switch that is set. */
    private void rebuild(int mine) {
        if (mine != generation || !on)
            return;
        features.clear();
        int n = 0;
        for (Spot.Request r : requests) {
            if (openOnly && !isOpen(r))
                continue;
            if (Double.isNaN(r.lat) || Double.isNaN(r.lon))
                continue;
            final int color = SpotIcons.color(r);
            final String uri = icons.uri(SpotIcons.letter(r), color);
            if (uri == null)
                continue;
            // The set is the kind, so Overlay Manager lists Wildfire and HAZMAT
            // separately and ATAK's own switches work on one at a time.
            features.addIcon(setName(r), label(r), new GeoPoint(r.lat, r.lon), uri,
                    SpotIcons.size(), SpotIcons.size(), attrs(r, color));
            n++;
        }
        status("");
        drawn(n, requests.size());
        Log.d(TAG, String.format(Locale.US, "drew %d of %d spot requests, openOnly=%b",
                n, requests.size(), openOnly));
    }

    /** Open means NWS still owes a forecast: not filled, or an update asked for. */
    private static boolean isOpen(Spot.Request r) {
        return r.filledAt <= 0 || r.pending;
    }

    private static String setName(Spot.Request r) {
        return r.kind == null || r.kind.isEmpty() ? "Other" : r.kind;
    }

    /** What the chooser and the details pane call it. */
    private static String label(Spot.Request r) {
        final String p = r.project == null ? "" : r.project.trim();
        return p.isEmpty() ? ("Spot request " + r.id) : p;
    }

    /**
     * Everything a tap should be able to read back. The renderer keeps these with
     * the feature, so the details pane does not have to hold a parallel list.
     */
    private static AttributeSet attrs(Spot.Request r, int color) {
        final AttributeSet a = new AttributeSet();
        a.setAttribute("spotId", r.id);
        a.setAttribute("project", label(r));
        a.setAttribute("kind", r.kind == null ? "" : r.kind);
        a.setAttribute("status", SpotIcons.statusLabel(color));
        a.setAttribute("office", r.office == null ? "" : r.office);
        a.setAttribute("officeName", r.officeName == null ? "" : r.officeName);
        a.setAttribute("state", r.state == null ? "" : r.state);
        a.setAttribute("region", r.region == null ? "" : r.region);
        a.setAttribute("requestedAt", r.requestedAt);
        a.setAttribute("filledAt", r.filledAt);
        return a;
    }

    private States loadStates() {
        try (java.io.InputStream in = pluginContext.getAssets().open("us_states.json")) {
            final java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            final byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0)
                out.write(buf, 0, n);
            return States.parse(out.toString("UTF-8"));
        } catch (Exception e) {
            Log.w(TAG, "state outlines unreadable", e);
            return null;
        }
    }

    private void status(String s) {
        if (listener != null)
            listener.onStatus(s);
    }

    private void drawn(int n, int total) {
        if (listener != null)
            listener.onDrawn(n, total);
    }
}
