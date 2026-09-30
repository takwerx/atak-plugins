package com.atakmap.android.atmosphere.overlay;

import android.content.Context;
import android.content.SharedPreferences;

import com.atakmap.android.atmosphere.compat.MapCompat;
import com.atakmap.android.atmosphere.data.BeachForecast;
import com.atakmap.android.atmosphere.data.GeoJson;
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
 * The NWS beach forecast on the map: each beach area filled by its rip current
 * risk, with surf, water temperature and the rest behind a tap. Asked for the
 * map's own neighborhood -- the view padded to three times its span, capped --
 * in one request of every office's layer at once, and asked again when the view
 * leaves that box or the forecast is half an hour old.
 */
public final class BeachOverlay {

    private static final String TAG = "AtmosphereBeach";
    public static final String LAYER_ID = "beach";
    public static final String HOST = BeachForecast.HOST;
    public static final String NAME = "Beach Forecast";
    private static final String PREF_ON = "weather.layer.beach.on";
    private static final long POLL_MS = 30 * 60 * 1000L;
    private static final long MOVE_SETTLE_MS = 700L;
    /** The widest box asked for; wider views are cropped around their center. */
    private static final double MAX_SPAN_LON = 9, MAX_SPAN_LAT = 6;
    /** Past this the view is a region, not a coast, and the layer says zoom in. */
    private static final double ZOOM_IN_SPAN = 24;
    private static final int FILL_ALPHA = 0x50;
    private static final float WEIGHT = 2.5f;

    public static final String[][] LEGEND = {
            { "Low rip current risk", String.valueOf(BeachForecast.LOW) },
            { "Moderate rip current risk", String.valueOf(BeachForecast.MODERATE) },
            { "High rip current risk", String.valueOf(BeachForecast.HIGH) },
            { "Risk not stated", String.valueOf(BeachForecast.UNKNOWN) } };

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
    private GeoBounds region;
    private long fetchedAt;
    private String pendingKey;
    private List<BeachForecast.Beach> beaches = new ArrayList<>();

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

    public BeachOverlay(MapView mapView, Context pluginContext, EgressPolicy egress) {
        this.mapView = mapView;
        this.egress = egress;
        this.features = new AtmosphereFeatures(mapView, pluginContext, TAG, NAME,
                "beach.sqlite", "beach", false);
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
            beaches = new ArrayList<>();
            drawNothing();
            status("");
        }
    }

    /** Called when the pane opens: a stale forecast is asked for again. */
    public void refresh(boolean force) {
        if (on)
            ensureRegion(force);
    }

    /** Ask for the map's neighborhood when the view has left the last one, or on force. */
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
            beaches = new ArrayList<>();
            drawNothing();
            status("Zoom in to see beach forecasts");
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
        final double padX = Math.min(e - w, (MAX_SPAN_LON - (e - w)) / 2);
        final double padY = Math.min(n - s, (MAX_SPAN_LAT - (n - s)) / 2);
        final GeoBounds r = new GeoBounds(Math.min(85, n + padY), Math.max(-180, w - padX),
                Math.max(-85, s - padY), Math.min(180, e + padX));
        final String key = String.format(java.util.Locale.US, "%.2f,%.2f,%.2f,%.2f",
                r.getWest(), r.getSouth(), r.getEast(), r.getNorth());
        if (key.equals(pendingKey))
            return;
        pendingKey = key;
        final int mine = generation;
        if (beaches.isEmpty())
            status("Getting the beach forecast…");
        Http.get(BeachForecast.url(r.getWest(), r.getSouth(), r.getEast(), r.getNorth()),
                egress.userAgent(), new HashMap<String, String>(), new Http.Callback() {
                    @Override
                    public void onSuccess(String body) {
                        if (key.equals(pendingKey))
                            pendingKey = null;
                        if (mine != generation || !on)
                            return;
                        region = r;
                        fetchedAt = System.currentTimeMillis();
                        beaches = BeachForecast.parse(body);
                        rebuild(mine);
                    }

                    @Override
                    public void onFailure(String error) {
                        if (key.equals(pendingKey))
                            pendingKey = null;
                        if (mine == generation && on)
                            status("Beach forecast: " + error);
                    }
                });
    }

    private static boolean contains(GeoBounds outer, GeoBounds inner) {
        return inner.getWest() >= outer.getWest() && inner.getEast() <= outer.getEast()
                && inner.getSouth() >= outer.getSouth() && inner.getNorth() <= outer.getNorth();
    }

    private void rebuild(final int mine) {
        final List<BeachForecast.Beach> snapshot = new ArrayList<>(beaches);
        worker.execute(new Runnable() {
            @Override
            public void run() {
                if (mine != generation)
                    return;
                final List<AtmosphereFeatures.Drawn> drawn = new ArrayList<>();
                int low = 0, moderate = 0, high = 0;
                for (BeachForecast.Beach b : snapshot) {
                    final Geometry g;
                    try {
                        g = GeoJson.parse(b.geometry);
                    } catch (Exception e) {
                        Log.w(TAG, "unusable beach shape: " + b.name, e);
                        continue;
                    }
                    if (g == null)
                        continue;
                    final int color = b.color();
                    if (color == BeachForecast.LOW)
                        low++;
                    else if (color == BeachForecast.MODERATE)
                        moderate++;
                    else if (color == BeachForecast.HIGH)
                        high++;
                    final int c = color & 0x00FFFFFF;
                    final AttributeSet s = new AttributeSet();
                    s.setAttribute("_details", b.details());
                    s.setAttribute("Beach", b.name);
                    final String label = (b.rip.isEmpty() ? "Beach" : "Rip current " + b.rip.toLowerCase(Locale.US))
                            + ": " + b.name;
                    drawn.add(new AtmosphereFeatures.Drawn(b.office, label, g,
                            AtmosphereFeatures.area(0xFF000000 | c, WEIGHT, (FILL_ALPHA << 24) | c, label), s));
                }
                features.rewrite(drawn);
                final String line = line(drawn.size(), low, moderate, high);
                mapView.post(new Runnable() {
                    @Override
                    public void run() {
                        if (mine != generation || !on)
                            return;
                        Log.d(TAG, "drew " + drawn.size() + " beach areas: " + line);
                        status(line);
                    }
                });
            }
        });
    }

    /** "7 beach areas: rip current risk high at 5, moderate at 2". */
    private static String line(int n, int low, int moderate, int high) {
        if (n == 0)
            return "No beach forecast for this stretch of coast";
        final StringBuilder b = new StringBuilder();
        b.append(n).append(n == 1 ? " beach area" : " beach areas").append(": rip current risk");
        final List<String> parts = new ArrayList<>();
        if (high > 0)
            parts.add("high at " + high);
        if (moderate > 0)
            parts.add("moderate at " + moderate);
        if (low > 0)
            parts.add("low at " + low);
        if (parts.isEmpty())
            parts.add("not stated");
        for (int i = 0; i < parts.size(); i++)
            b.append(i == 0 ? " " : ", ").append(parts.get(i));
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
        if (listener != null)
            listener.onStatus(s);
    }
}
