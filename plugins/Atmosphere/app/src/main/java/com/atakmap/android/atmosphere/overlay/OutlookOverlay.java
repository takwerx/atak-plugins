package com.atakmap.android.atmosphere.overlay;

import android.content.Context;
import android.content.SharedPreferences;

import com.atakmap.android.atmosphere.compat.MapCompat;
import com.atakmap.android.atmosphere.data.GeoJson;
import com.atakmap.android.atmosphere.net.EgressPolicy;
import com.atakmap.android.atmosphere.net.Http;
import com.atakmap.android.maps.MapView;
import com.atakmap.coremap.log.Log;
import com.atakmap.map.layer.feature.AttributeSet;
import com.atakmap.map.layer.feature.geometry.Geometry;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * An outlook drawn as colored areas: a national center's polygons for the next
 * few days, one request per service layer, the areas filled in the center's own
 * colors, the record behind a tap written by the subclass. SPC's fire weather
 * outlook and WPC's excessive rainfall outlook are the same shape of thing and
 * share everything but their layers, categories and words.
 *
 * <p>The subclass names the requests, parses each answer into {@link Area}s,
 * gives the legend and sums the areas up in a status line. This class owns the
 * on/off preference, the half-hourly poll, the sequential fetch, the rewrite on
 * the worker and the status.
 */
public abstract class OutlookOverlay {

    private static final long POLL_MS = 30 * 60 * 1000L;
    private static final long REFRESH_MS = 10 * 60 * 1000L;
    private static final int FILL_ALPHA = 0x48;
    private static final float WEIGHT = 2.5f;

    /** One colored area of the outlook and what its record says. */
    public static final class Area {
        public final int day;
        /** The record's heading and the chooser row. */
        public final String title;
        /** The category, as the status line and legend name it. */
        public final String label;
        public final int color;
        public final JSONObject geometry;
        /** The record's text, lines in reading order. */
        public final String details;

        public Area(int day, String title, String label, int color, JSONObject geometry,
                String details) {
            this.day = day;
            this.title = title;
            this.label = label;
            this.color = color;
            this.geometry = geometry;
            this.details = details;
        }
    }

    public interface Listener {
        void onStatus(String status);
    }

    protected final MapView mapView;
    protected final EgressPolicy egress;
    private final AtmosphereFeatures features;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final String tag, layerId, prefOn, prefDay;
    /** 0 draws every day; 1-3 one day. Three days stacked hid each other (2026-09-26). */
    private int day = 1;
    private Listener listener;
    private boolean started, on, inFlight;
    private long lastPoll;
    private int generation;
    private List<Area> areas = new ArrayList<>();

    private final Runnable autoPoll = new Runnable() {
        @Override
        public void run() {
            if (!on || !started)
                return;
            refresh(true);
            mapView.postDelayed(this, POLL_MS);
        }
    };

    protected OutlookOverlay(MapView mapView, Context pluginContext, EgressPolicy egress,
            String tag, String layerId, String name) {
        this.mapView = mapView;
        this.egress = egress;
        this.tag = tag;
        this.layerId = layerId;
        this.prefOn = "weather.layer." + layerId + ".on";
        this.prefDay = "weather.layer." + layerId + ".day";
        final SharedPreferences p0 = MapCompat.prefs();
        day = p0 == null ? 1 : Math.max(0, Math.min(3, p0.getInt(prefDay, 1)));
        this.features = new AtmosphereFeatures(mapView, pluginContext, tag, name,
                layerId + ".sqlite", layerId, false);
    }

    /** The requests, in order; each answer goes to {@link #parse(int, String)} with its index. */
    protected abstract String[] urls();

    protected abstract List<Area> parse(int index, String body);

    /** "Day 1: none. Day 2: Elevated." */
    protected abstract String summary(List<Area> areas);

    /** What the status says while the first answer is on its way, and on failure. */
    protected abstract String noun();

    public void setListener(Listener l) {
        listener = l;
    }

    public void start() {
        started = true;
        features.attach();
        final SharedPreferences p = MapCompat.prefs();
        if (p != null && p.getBoolean(prefOn, false) && egress.isLayerEnabled(layerId))
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
            p.edit().putBoolean(prefOn, value).apply();
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

    public List<Area> areas() {
        return areas;
    }

    public int day() {
        return day;
    }

    /** Which day to draw, 0 for all; redrawn from what is held, no new request. */
    public void setDay(int value) {
        final int v = Math.max(0, Math.min(3, value));
        if (v == day)
            return;
        day = v;
        final SharedPreferences p = MapCompat.prefs();
        if (p != null)
            p.edit().putInt(prefDay, v).apply();
        if (on)
            rebuild(generation);
    }

    /** Ask for every layer, one after another, unless that was done very recently. */
    public void refresh(boolean force) {
        if (!on || !started || inFlight)
            return;
        final long now = System.currentTimeMillis();
        if (!force && now - lastPoll < REFRESH_MS)
            return;
        lastPoll = now;
        inFlight = true;
        status(areas.isEmpty() ? "Getting the " + noun() + "…" : "");
        fetch(0, new ArrayList<Area>(), generation);
    }

    private void fetch(final int index, final List<Area> got, final int mine) {
        if (mine != generation || !on) {
            inFlight = false;
            return;
        }
        final String[] urls = urls();
        if (index >= urls.length) {
            inFlight = false;
            areas = got;
            rebuild(mine);
            return;
        }
        Http.get(urls[index], egress.userAgent(), new HashMap<String, String>(), new Http.Callback() {
            @Override
            public void onSuccess(String body) {
                got.addAll(parse(index, body));
                fetch(index + 1, got, mine);
            }

            @Override
            public void onFailure(String error) {
                Log.w(tag, "request " + index + ": " + error);
                inFlight = false;
                if (mine == generation && on)
                    status(capitalize(noun()) + ": " + error);
            }
        });
    }

    private void rebuild(final int mine) {
        final List<Area> snapshot = new ArrayList<>(areas);
        worker.execute(new Runnable() {
            @Override
            public void run() {
                if (mine != generation)
                    return;
                final List<AtmosphereFeatures.Drawn> drawn = new ArrayList<>();
                final int showDay = day;
                for (Area a : snapshot) {
                    if (showDay != 0 && a.day != showDay)
                        continue;
                    final Geometry g;
                    try {
                        g = GeoJson.parse(a.geometry);
                    } catch (Exception e) {
                        Log.w(tag, "unusable outlook shape", e);
                        continue;
                    }
                    if (g == null)
                        continue;
                    final int c = a.color & 0x00FFFFFF;
                    final AttributeSet s = new AttributeSet();
                    s.setAttribute("_details", a.details);
                    s.setAttribute("Outlook", a.title);
                    drawn.add(new AtmosphereFeatures.Drawn("Day " + a.day, a.title, g,
                            AtmosphereFeatures.area(0xFF000000 | c, WEIGHT, (FILL_ALPHA << 24) | c,
                                    a.title), s));
                }
                features.rewrite(drawn);
                final String line = summary(snapshot);
                mapView.post(new Runnable() {
                    @Override
                    public void run() {
                        if (mine != generation || !on)
                            return;
                        Log.d(tag, "drew " + drawn.size() + " areas: " + line);
                        status(line);
                    }
                });
            }
        });
    }

    /** "Day 1: none.  Day 2: Elevated, Critical." over the days the outlook covers. */
    protected static String byDay(List<Area> all, int days) {
        final StringBuilder b = new StringBuilder();
        for (int day = 1; day <= days; day++) {
            if (b.length() > 0)
                b.append("  ");
            b.append("Day ").append(day).append(": ");
            final List<String> parts = new ArrayList<>();
            for (Area a : all)
                if (a.day == day && !parts.contains(a.label))
                    parts.add(a.label);
            if (parts.isEmpty())
                b.append("none");
            else
                for (int i = 0; i < parts.size(); i++)
                    b.append(i > 0 ? ", " : "").append(parts.get(i));
            b.append('.');
        }
        return b.toString();
    }

    private static String capitalize(String s) {
        return s == null || s.isEmpty() ? "" : Character.toUpperCase(s.charAt(0)) + s.substring(1);
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
