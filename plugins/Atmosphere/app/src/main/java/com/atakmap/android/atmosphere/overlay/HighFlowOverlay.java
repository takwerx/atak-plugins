package com.atakmap.android.atmosphere.overlay;

import android.content.Context;
import android.content.SharedPreferences;

import com.atakmap.android.atmosphere.compat.MapCompat;
import com.atakmap.android.atmosphere.data.GeoJson;
import com.atakmap.android.atmosphere.data.HighFlow;
import com.atakmap.android.atmosphere.net.EgressPolicy;
import com.atakmap.android.atmosphere.net.Http;
import com.atakmap.android.maps.MapView;
import com.atakmap.coremap.log.Log;
import com.atakmap.coremap.maps.coords.GeoBounds;
import com.atakmap.map.AtakMapView;
import com.atakmap.map.layer.feature.AttributeSet;
import com.atakmap.map.layer.feature.geometry.Geometry;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Streams running high on the map: every stretch of stream the river model has
 * at or over its high-water threshold, as a line in the service's own color for
 * how rare a flow that big is, with the flow against the 2/5/10/25/50-year flows
 * behind a tap. Asked for the map's neighborhood -- the view padded, capped --
 * and again when the view leaves that box, zooms well into it, or half an hour
 * has passed; the biggest streams come first, so when a wide view has more
 * than the service's page the small ones are what is missing, and the status
 * says so. Draws the same horizon as the flooded ground picture: now, or the
 * peak of the next five days ({@link FloodedGroundOverlay#PREF_HORIZON}).
 */
public final class HighFlowOverlay {

    private static final String TAG = "AtmosphereHighFlow";
    public static final String LAYER_ID = "highflow";
    public static final String HOST = HighFlow.HOST;
    public static final String NAME = "Streams Running High";
    private static final String PREF_ON = "weather.layer.highflow.on";
    private static final long POLL_MS = 30 * 60 * 1000L;
    private static final long MOVE_SETTLE_MS = 700L;
    /** The widest box asked for; a wider view is cropped around its center. */
    private static final double MAX_SPAN_LON = 5, MAX_SPAN_LAT = 3.5;
    /** Past this the view is a region, and the layer says zoom in rather than crop. */
    private static final double ZOOM_IN_SPAN = 8;
    /** Bends finer than the box over this many pixels are not asked for. */
    private static final double OFFSET_DIVISOR = 2500;
    private static final float WEIGHT = 4f;

    public interface Listener {
        void onStatus(String status);
    }

    private final MapView mapView;
    private final EgressPolicy egress;
    private final AtmosphereFeatures features;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private Listener listener;
    private boolean started, on;
    private int generation;
    private int horizon;
    private GeoBounds region;
    private long fetchedAt;
    private String pendingKey;
    private HighFlow.Answer answer = new HighFlow.Answer();
    private String lastStatus = "";

    private final Runnable moveSettled = new Runnable() {
        @Override
        public void run() {
            if (on)
                ensureRegion(false);
        }
    };

    private final AtakMapView.OnMapMovedListener moved = new AtakMapView.OnMapMovedListener() {
        @Override
        public void onMapMoved(AtakMapView v, boolean animate) {
            // GL thread: post and coalesce, touch nothing here.
            mapView.removeCallbacks(moveSettled);
            mapView.postDelayed(moveSettled, MOVE_SETTLE_MS);
        }
    };

    private final Runnable autoPoll = new Runnable() {
        @Override
        public void run() {
            if (!on || !started)
                return;
            ensureRegion(true);
            mapView.postDelayed(this, POLL_MS);
        }
    };

    public HighFlowOverlay(MapView mapView, Context pluginContext, EgressPolicy egress) {
        this.mapView = mapView;
        this.egress = egress;
        this.features = new AtmosphereFeatures(mapView, pluginContext, TAG, NAME,
                "highflow.sqlite", "highflow", false);
        this.horizon = FloodedGroundOverlay.horizonPref();
    }

    /** A listener attached after the layer spoke hears the last line at once. */
    public void setListener(Listener l) {
        listener = l;
        if (l != null && on && !lastStatus.isEmpty())
            l.onStatus(lastStatus);
    }

    public void start() {
        started = true;
        features.attach();
        final SharedPreferences p = MapCompat.prefs();
        if (p != null && p.getBoolean(PREF_ON, false) && egress.isLayerEnabled(LAYER_ID))
            setOn(true);
        if (!on)
            drawNothing();
    }

    public void stop() {
        started = false;
        on = false;
        generation++;
        mapView.removeCallbacks(autoPoll);
        mapView.removeCallbacks(moveSettled);
        mapView.removeOnMapMovedListener(moved);
        worker.shutdownNow();
        features.detach();
    }

    public boolean isOn() {
        return on;
    }

    public int horizon() {
        return horizon;
    }

    /**
     * Now or the next five days: the flood layers' shared choice. Persisted here
     * too, so whichever layer is asked last leaves the preference right.
     */
    public void setHorizon(int value) {
        final int v = value == FloodedGroundOverlay.HORIZON_5DAY
                ? FloodedGroundOverlay.HORIZON_5DAY : FloodedGroundOverlay.HORIZON_NOW;
        final SharedPreferences p = MapCompat.prefs();
        if (p != null)
            p.edit().putInt(FloodedGroundOverlay.PREF_HORIZON, v).apply();
        if (v == horizon)
            return;
        horizon = v;
        if (!on)
            return;
        generation++;
        pendingKey = null;
        region = null;
        answer = new HighFlow.Answer();
        drawNothing();
        ensureRegion(true);
    }

    public void setOn(boolean value) {
        if (!started || on == value)
            return;
        on = value;
        final SharedPreferences p = MapCompat.prefs();
        if (p != null)
            p.edit().putBoolean(PREF_ON, value).apply();
        mapView.removeCallbacks(autoPoll);
        mapView.removeCallbacks(moveSettled);
        generation++;
        pendingKey = null;
        if (value) {
            mapView.addOnMapMovedListener(moved);
            ensureRegion(true);
            mapView.postDelayed(autoPoll, POLL_MS);
        } else {
            mapView.removeOnMapMovedListener(moved);
            region = null;
            answer = new HighFlow.Answer();
            drawNothing();
            status("");
        }
    }

    /** Called when the pane opens: a stale answer is asked for again. */
    public void refresh(boolean force) {
        if (on)
            ensureRegion(force);
    }

    private static int toDataHorizon(int h) {
        return h == FloodedGroundOverlay.HORIZON_5DAY ? HighFlow.HORIZON_5DAY : HighFlow.HORIZON_NOW;
    }

    private void ensureRegion(boolean force) {
        final GeoBounds bounds = mapView.getBounds();
        if (bounds == null)
            return;
        final boolean wholeWorld = Double.isNaN(bounds.getNorth()) || Double.isNaN(bounds.getSouth())
                || Double.isNaN(bounds.getEast()) || Double.isNaN(bounds.getWest())
                || bounds.getEast() <= bounds.getWest()
                || bounds.getEast() - bounds.getWest() >= ZOOM_IN_SPAN;
        if (wholeWorld) {
            region = null;
            answer = new HighFlow.Answer();
            drawNothing();
            status("Zoom in to see streams running high");
            return;
        }
        double w = bounds.getWest(), e = bounds.getEast(), s = bounds.getSouth(), n = bounds.getNorth();
        final boolean stale = System.currentTimeMillis() - fetchedAt > POLL_MS;
        boolean refetch = force || stale || region == null || !contains(region, bounds);
        if (!refetch) {
            final double viewSpan = e - w, regionSpan = region.getEast() - region.getWest();
            refetch = viewSpan < regionSpan / 4;
        }
        if (!refetch)
            return;
        if (e - w > MAX_SPAN_LON) {
            final double c = (e + w) / 2;
            w = c - MAX_SPAN_LON / 2;
            e = c + MAX_SPAN_LON / 2;
        }
        if (n - s > MAX_SPAN_LAT) {
            final double c = (n + s) / 2;
            s = c - MAX_SPAN_LAT / 2;
            n = c + MAX_SPAN_LAT / 2;
        }
        final double padX = Math.min((e - w) * 0.5, Math.max(0, (MAX_SPAN_LON - (e - w)) / 2));
        final double padY = Math.min((n - s) * 0.5, Math.max(0, (MAX_SPAN_LAT - (n - s)) / 2));
        final GeoBounds r = new GeoBounds(Math.min(85, n + padY), Math.max(-180, w - padX),
                Math.max(-85, s - padY), Math.min(180, e + padX));
        final String key = String.format(Locale.US, "%d:%.2f,%.2f,%.2f,%.2f", horizon,
                r.getWest(), r.getSouth(), r.getEast(), r.getNorth());
        if (key.equals(pendingKey))
            return;
        pendingKey = key;
        final int mine = generation;
        final int h = toDataHorizon(horizon);
        if (answer.reaches.isEmpty())
            status("Finding streams running high…");
        final double offset = (r.getEast() - r.getWest()) / OFFSET_DIVISOR;
        Http.get(HighFlow.url(h, r.getWest(), r.getSouth(), r.getEast(), r.getNorth(), offset),
                egress.userAgent(), new HashMap<String, String>(), new Http.Callback() {
                    @Override
                    public void onSuccess(String body) {
                        if (key.equals(pendingKey))
                            pendingKey = null;
                        if (mine != generation || !on)
                            return;
                        final HighFlow.Answer got = HighFlow.parse(body);
                        if (got.reaches.isEmpty() && body != null && body.contains("\"error\"")) {
                            // The server answers about one request in six with an error
                            // body; the last answer stays up and the next move asks again.
                            status("Streams running high: the server did not answer, will try again");
                            return;
                        }
                        region = r;
                        fetchedAt = System.currentTimeMillis();
                        answer = got;
                        rebuild(mine);
                    }

                    @Override
                    public void onFailure(String error) {
                        if (key.equals(pendingKey))
                            pendingKey = null;
                        if (mine == generation && on)
                            status("Streams running high: " + error);
                    }
                });
    }

    private static boolean contains(GeoBounds outer, GeoBounds inner) {
        return inner.getWest() >= outer.getWest() && inner.getEast() <= outer.getEast()
                && inner.getSouth() >= outer.getSouth() && inner.getNorth() <= outer.getNorth();
    }

    private void rebuild(final int mine) {
        final HighFlow.Answer snapshot = answer;
        final int h = toDataHorizon(horizon);
        worker.execute(new Runnable() {
            @Override
            public void run() {
                if (mine != generation)
                    return;
                final List<AtmosphereFeatures.Drawn> drawn = new ArrayList<>();
                for (HighFlow.Reach reach : snapshot.reaches) {
                    final Geometry g;
                    try {
                        g = GeoJson.parse(reach.geometry);
                    } catch (Exception e) {
                        Log.w(TAG, "unusable reach shape: " + reach.id, e);
                        continue;
                    }
                    if (g == null)
                        continue;
                    final AttributeSet a = new AttributeSet();
                    a.setAttribute("_details", reach.details(h));
                    a.setAttribute("Stream", reach.placeName());
                    // A creek is many short reaches; a tap lists the one under the
                    // finger, not every neighbor with the same name and grade.
                    a.setAttribute("_oneRowPerName", 1);
                    final String label = reach.label();
                    drawn.add(new AtmosphereFeatures.Drawn(NAME, reach.title(), g,
                            AtmosphereFeatures.stroke(label, reach.category.color, WEIGHT, !label.isEmpty()),
                            a));
                }
                features.rewrite(drawn);
                final String line = line(snapshot, h);
                mapView.post(new Runnable() {
                    @Override
                    public void run() {
                        if (mine != generation || !on)
                            return;
                        Log.d(TAG, "drew " + drawn.size() + " reaches: " + line);
                        status(line);
                    }
                });
            }
        });
    }

    /** "12 streams running high now; worst: Rio Grande, 1-in-25-year flow". */
    static String line(HighFlow.Answer a, int h) {
        final String when = h == HighFlow.HORIZON_5DAY ? " in the next 5 days" : " now";
        final int n = a.reaches.size();
        if (n == 0)
            return "No streams running high here" + when;
        final StringBuilder b = new StringBuilder();
        if (a.truncated)
            b.append("The ").append(String.format(Locale.US, "%,d", n))
                    .append(" biggest streams running high here").append(when)
                    .append("; zoom in for the smaller ones");
        else
            b.append(String.format(Locale.US, "%,d", n)).append(n == 1 ? " stream" : " streams")
                    .append(" running high").append(when);
        final HighFlow.Category worst = HighFlow.worst(a.reaches);
        if (worst != null) {
            HighFlow.Reach named = null;
            for (HighFlow.Reach r : a.reaches)
                if (r.category == worst && (named == null || (named.name.isEmpty() && !r.name.isEmpty())
                        || (!r.name.isEmpty() && r.order > named.order)))
                    named = r;
            b.append("; worst: ");
            if (named != null && !named.name.isEmpty())
                b.append(named.name).append(", ");
            b.append(worst.words.toLowerCase(Locale.US));
        }
        return b.toString();
    }

    private void drawNothing() {
        worker.execute(new Runnable() {
            @Override
            public void run() {
                features.rewrite(Collections.<AtmosphereFeatures.Drawn>emptyList());
            }
        });
    }

    private void status(String s) {
        lastStatus = s == null ? "" : s;
        if (listener != null)
            listener.onStatus(lastStatus);
    }
}
