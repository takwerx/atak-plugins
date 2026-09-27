package com.atakmap.android.atmosphere.overlay;

import android.content.Context;
import android.content.SharedPreferences;

import com.atakmap.android.atmosphere.data.Avalanche;
import com.atakmap.android.atmosphere.data.GeoJson;
import com.atakmap.android.atmosphere.net.EgressPolicy;
import com.atakmap.android.atmosphere.net.Http;
import com.atakmap.android.atmosphere.compat.MapCompat;
import com.atakmap.android.maps.MapView;
import com.atakmap.coremap.log.Log;
import com.atakmap.map.layer.feature.AttributeSet;
import com.atakmap.map.layer.feature.geometry.Geometry;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Avalanche forecast zones on the map, filled in the danger color the center
 * publishes, with the center's rating and travel advice behind a tap. One
 * request for the country, every half hour while on; the ratings change once a
 * day. Zones are drawn off season too, gray, so the map says where a forecast
 * would come from rather than showing nothing.
 */
public final class AvalancheOverlay {

    private static final String TAG = "AtmosphereAvalanche";
    public static final String LAYER_ID = "avalanche";
    public static final String HOST = Avalanche.HOST;
    public static final String NAME = "Avalanche zones";
    private static final String PREF_ON = "weather.layer.avalanche.on";
    private static final long POLL_MS = 30 * 60 * 1000L;
    private static final long REFRESH_MS = 10 * 60 * 1000L;
    private static final int FILL_ALPHA = 0x50;
    private static final float WEIGHT = 2f;

    /** The danger scale for the legend, from the same table the record uses. */
    public static final String[][] LEGEND;
    static {
        LEGEND = new String[6][];
        for (int i = 1; i <= 5; i++)
            LEGEND[i - 1] = new String[] { Avalanche.LEVEL_LABELS[i] + " (" + i + ")",
                    String.valueOf(Avalanche.LEVEL_COLORS[i]) };
        LEGEND[5] = new String[] { "No rating or off season", String.valueOf(Avalanche.LEVEL_COLORS[0]) };
    }

    public interface Listener {
        void onStatus(String status);
    }

    private final MapView mapView;
    private final EgressPolicy egress;
    private final AtmosphereFeatures features;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private Listener listener;
    private boolean started, on, inFlight;
    private long lastPoll;
    private int generation;
    private List<Avalanche.Zone> zones = new ArrayList<>();

    private final Runnable autoPoll = new Runnable() {
        @Override
        public void run() {
            if (!on || !started)
                return;
            refresh(true);
            mapView.postDelayed(this, POLL_MS);
        }
    };

    public AvalancheOverlay(MapView mapView, Context pluginContext, EgressPolicy egress) {
        this.mapView = mapView;
        this.egress = egress;
        this.features = new AtmosphereFeatures(mapView, pluginContext, TAG, NAME,
                "avalanche.sqlite", "avalanche", false);
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
            zones = new ArrayList<>();
            drawNothing();
            status("");
        }
    }

    public List<Avalanche.Zone> zones() {
        return zones;
    }

    /** Ask for every center's zones, unless that was done very recently. */
    public void refresh(boolean force) {
        if (!on || !started || inFlight)
            return;
        final long now = System.currentTimeMillis();
        if (!force && now - lastPoll < REFRESH_MS)
            return;
        lastPoll = now;
        inFlight = true;
        final int mine = generation;
        status(zones.isEmpty() ? "Getting the avalanche zones…" : "");
        // A quarter megabyte: the large-request thread, not the shared pool.
        Http.getLarge(Avalanche.URL, egress.userAgent(), new HashMap<String, String>(),
                new Http.Callback() {
                    @Override
                    public void onSuccess(String body) {
                        inFlight = false;
                        if (mine != generation || !on)
                            return;
                        final List<Avalanche.Zone> got = Avalanche.parse(body);
                        if (got.isEmpty()) {
                            status("Avalanche zones: nothing usable in the answer");
                            return;
                        }
                        zones = got;
                        rebuild(mine);
                    }

                    @Override
                    public void onFailure(String error) {
                        inFlight = false;
                        if (mine != generation || !on)
                            return;
                        status("Avalanche zones: " + error);
                    }
                });
    }

    private void rebuild(final int mine) {
        final List<Avalanche.Zone> snapshot = new ArrayList<>(zones);
        worker.execute(new Runnable() {
            @Override
            public void run() {
                if (mine != generation)
                    return;
                final List<AtmosphereFeatures.Drawn> drawn = new ArrayList<>();
                int inSeason = 0, rated = 0;
                for (Avalanche.Zone z : snapshot) {
                    final Geometry g;
                    try {
                        g = GeoJson.parse(z.geometry);
                    } catch (Exception e) {
                        Log.w(TAG, "unusable zone shape: " + z.name, e);
                        continue;
                    }
                    if (g == null)
                        continue;
                    if (!z.offSeason)
                        inSeason++;
                    if (z.dangerLevel >= 1)
                        rated++;
                    final int c = z.color & 0x00FFFFFF;
                    drawn.add(new AtmosphereFeatures.Drawn(z.center, z.name, g,
                            AtmosphereFeatures.area(0xFF000000 | c, WEIGHT, (FILL_ALPHA << 24) | c),
                            attributes(z)));
                }
                features.rewrite(drawn);
                final int nDrawn = drawn.size(), nSeason = inSeason, nRated = rated;
                mapView.post(new Runnable() {
                    @Override
                    public void run() {
                        if (mine != generation || !on)
                            return;
                        Log.d(TAG, "drew " + nDrawn + " avalanche zones, " + nSeason
                                + " in season, " + nRated + " rated");
                        status(line(nDrawn, nSeason, nRated));
                    }
                });
            }
        });
    }

    /** The record, in the order a person reads it, and the fields the chooser wants. */
    private static AttributeSet attributes(Avalanche.Zone z) {
        final AttributeSet s = new AttributeSet();
        final StringBuilder d = new StringBuilder();
        d.append("Danger: ").append(z.dangerLine());
        if (z.warning != null)
            d.append("\nAvalanche warning: ").append(z.warning);
        if (z.travelAdvice != null && !z.travelAdvice.isEmpty())
            d.append("\nTravel advice: ").append(z.travelAdvice);
        if (!z.startDate.isEmpty())
            d.append("\nValid: ").append(Avalanche.when(z.startDate))
                    .append(z.endDate.isEmpty() ? "" : " to " + Avalanche.when(z.endDate));
        d.append("\nForecast center: ").append(z.center);
        if (!z.link.isEmpty())
            d.append("\nForecast: ").append(z.link);
        if (!z.state.isEmpty())
            d.append("\nState: ").append(z.state);
        s.setAttribute("_details", d.toString());
        s.setAttribute("Zone", z.name);
        s.setAttribute("Danger", z.dangerLine());
        return s;
    }

    private static String line(int drawn, int inSeason, int rated) {
        if (drawn == 0)
            return "No avalanche zones";
        if (inSeason == 0)
            return drawn + " zones on the map, all off season";
        return drawn + " zones on the map, " + inSeason + " in season, "
                + (rated == 0 ? "none rated yet" : rated + " rated");
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
