package com.atakmap.android.atmosphere.overlay;

import android.content.Context;
import android.content.SharedPreferences;

import com.atakmap.android.atmosphere.compat.MapCompat;
import com.atakmap.android.atmosphere.data.GeoJson;
import com.atakmap.android.atmosphere.data.SpcFireWx;
import com.atakmap.android.atmosphere.net.EgressPolicy;
import com.atakmap.android.atmosphere.net.Http;
import com.atakmap.android.maps.MapView;
import com.atakmap.coremap.log.Log;
import com.atakmap.map.layer.feature.AttributeSet;
import com.atakmap.map.layer.feature.geometry.Geometry;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.TimeZone;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * SPC's fire weather outlook on the map, days 1 to 3: the Elevated / Critical /
 * Extreme areas and the dry thunderstorm areas, in SPC's colors, with the day,
 * category and valid window behind a tap. Six small requests every half hour
 * while on; most days most of them answer "no areas".
 */
public final class FireWxOutlookOverlay {

    private static final String TAG = "AtmosphereFireWx";
    public static final String LAYER_ID = "firewx";
    public static final String HOST = SpcFireWx.HOST;
    public static final String NAME = "Fire weather outlook";
    private static final String PREF_ON = "weather.layer.firewx.on";
    private static final long POLL_MS = 30 * 60 * 1000L;
    private static final long REFRESH_MS = 10 * 60 * 1000L;
    private static final int FILL_ALPHA = 0x48;
    private static final float WEIGHT = 2.5f;

    /** SPC's categories, in its order, from the same table the map draws with. */
    public static final String[][] LEGEND;
    static {
        LEGEND = new String[SpcFireWx.LEGEND_CODES.length][];
        for (int i = 0; i < SpcFireWx.LEGEND_CODES.length; i++) {
            final SpcFireWx.Kind k = SpcFireWx.LEGEND_CODES[i][0] == 0
                    ? SpcFireWx.Kind.OUTLOOK : SpcFireWx.Kind.DRY_THUNDER;
            final int dn = SpcFireWx.LEGEND_CODES[i][1];
            LEGEND[i] = new String[] { SpcFireWx.label(k, dn), String.valueOf(SpcFireWx.color(k, dn)) };
        }
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
    private List<SpcFireWx.Area> areas = new ArrayList<>();

    private final Runnable autoPoll = new Runnable() {
        @Override
        public void run() {
            if (!on || !started)
                return;
            refresh(true);
            mapView.postDelayed(this, POLL_MS);
        }
    };

    public FireWxOutlookOverlay(MapView mapView, Context pluginContext, EgressPolicy egress) {
        this.mapView = mapView;
        this.egress = egress;
        this.features = new AtmosphereFeatures(mapView, pluginContext, TAG, NAME,
                "firewx.sqlite", "firewx", false);
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
            areas = new ArrayList<>();
            drawNothing();
            status("");
        }
    }

    /** Ask for all six layers, one after another, unless that was done very recently. */
    public void refresh(boolean force) {
        if (!on || !started || inFlight)
            return;
        final long now = System.currentTimeMillis();
        if (!force && now - lastPoll < REFRESH_MS)
            return;
        lastPoll = now;
        inFlight = true;
        status(areas.isEmpty() ? "Getting the outlook…" : "");
        fetch(0, new ArrayList<SpcFireWx.Area>(), generation);
    }

    private void fetch(final int index, final List<SpcFireWx.Area> got, final int mine) {
        if (mine != generation || !on) {
            inFlight = false;
            return;
        }
        if (index >= SpcFireWx.LAYERS.length) {
            inFlight = false;
            areas = got;
            rebuild(mine);
            return;
        }
        final SpcFireWx.Layer layer = SpcFireWx.LAYERS[index];
        Http.get(layer.url(), egress.userAgent(), new HashMap<String, String>(), new Http.Callback() {
            @Override
            public void onSuccess(String body) {
                got.addAll(SpcFireWx.parse(body, layer));
                fetch(index + 1, got, mine);
            }

            @Override
            public void onFailure(String error) {
                Log.w(TAG, "day " + layer.day + " " + layer.kind + ": " + error);
                inFlight = false;
                if (mine == generation && on)
                    status("Fire weather outlook: " + error);
            }
        });
    }

    private void rebuild(final int mine) {
        final List<SpcFireWx.Area> snapshot = new ArrayList<>(areas);
        final TimeZone zone = TimeZone.getDefault();
        worker.execute(new Runnable() {
            @Override
            public void run() {
                if (mine != generation)
                    return;
                final List<AtmosphereFeatures.Drawn> drawn = new ArrayList<>();
                for (SpcFireWx.Area a : snapshot) {
                    final Geometry g;
                    try {
                        g = GeoJson.parse(a.geometry);
                    } catch (Exception e) {
                        Log.w(TAG, "unusable outlook shape", e);
                        continue;
                    }
                    if (g == null)
                        continue;
                    final int c = a.color() & 0x00FFFFFF;
                    drawn.add(new AtmosphereFeatures.Drawn("Day " + a.day, a.title(), g,
                            AtmosphereFeatures.area(0xFF000000 | c, WEIGHT, (FILL_ALPHA << 24) | c),
                            attributes(a, zone)));
                }
                features.rewrite(drawn);
                final String line = line(snapshot);
                mapView.post(new Runnable() {
                    @Override
                    public void run() {
                        if (mine != generation || !on)
                            return;
                        Log.d(TAG, "drew " + drawn.size() + " outlook areas: " + line);
                        status(line);
                    }
                });
            }
        });
    }

    private static AttributeSet attributes(SpcFireWx.Area a, TimeZone zone) {
        final AttributeSet s = new AttributeSet();
        final StringBuilder d = new StringBuilder();
        d.append(a.title());
        if (!a.valid.isEmpty())
            d.append("\nValid: ").append(SpcFireWx.when(a.valid, zone))
                    .append(a.expire.isEmpty() ? "" : " to " + SpcFireWx.when(a.expire, zone));
        d.append("\nFrom: NOAA Storm Prediction Center");
        s.setAttribute("_details", d.toString());
        s.setAttribute("Outlook", a.title());
        return s;
    }

    /** "Day 1: none. Day 2: Elevated. Day 3: none." */
    private static String line(List<SpcFireWx.Area> all) {
        final StringBuilder b = new StringBuilder();
        for (int day = 1; day <= 3; day++) {
            if (b.length() > 0)
                b.append("  ");
            b.append("Day ").append(day).append(": ");
            final List<String> parts = new ArrayList<>();
            for (SpcFireWx.Area a : all)
                if (a.day == day && !parts.contains(a.label()))
                    parts.add(a.label());
            if (parts.isEmpty())
                b.append("none");
            else
                for (int i = 0; i < parts.size(); i++)
                    b.append(i > 0 ? ", " : "").append(parts.get(i));
            b.append('.');
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
        if (listener != null)
            listener.onStatus(s);
    }
}
