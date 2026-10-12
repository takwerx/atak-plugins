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
        /**
         * What a tap opens on the layer's page, in the layer's own words ("3|2026-09-30"
         * for a SAWTI zone and day), or empty for the details. Carried to the map item
         * as {@code atmosphereRef} beside {@code atmosphereLayer}.
         */
        public final String ref;

        public Area(int day, String title, String label, int color, JSONObject geometry,
                String details) {
            this(day, title, label, color, geometry, details, "");
        }

        public Area(int day, String title, String label, int color, JSONObject geometry,
                String details, String ref) {
            this(day, title, label, color, geometry, details, ref, null);
        }

        /**
         * Whatever the layer needs back when it draws this area: a quake, a report,
         * a contour's intensity. Null for a plain filled area.
         */
        public final Object extra;

        public Area(int day, String title, String label, int color, JSONObject geometry,
                String details, String ref, Object extra) {
            this.day = day;
            this.title = title;
            this.label = label;
            this.color = color;
            this.geometry = geometry;
            this.details = details;
            this.ref = ref == null ? "" : ref;
            this.extra = extra;
        }
    }

    public interface Listener {
        void onStatus(String status);
    }

    protected final MapView mapView;
    protected final EgressPolicy egress;
    private final AtmosphereFeatures features;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final String tag, layerId, prefOn, prefDay, prefChoice;
    /** The second chip row's pick, for a layer that offers one; see {@link #choices()}. */
    private int choice;
    /** 0 draws every day; 1 to {@link #days()} one day. Three days stacked hid each other (2026-09-26). */
    private int day = 1;
    private Listener listener;
    /** Told on main whenever what is drawn changed: a fetch landed, a filter moved, the layer went off. */
    private Runnable dataListener;
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
            mapView.postDelayed(this, pollMs());
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
        this.prefChoice = "weather.layer." + layerId + ".choice";
        final SharedPreferences p0 = MapCompat.prefs();
        day = p0 == null ? defaultDay() : clampDay(p0.getInt(prefDay, defaultDay()));
        final String[] cs = choices();
        final int top = cs == null ? 0 : cs.length - 1;
        choice = Math.max(0, Math.min(top,
                p0 == null ? defaultChoice() : p0.getInt(prefChoice, defaultChoice())));
        this.features = new AtmosphereFeatures(mapView, pluginContext, tag, name,
                layerId + ".sqlite", layerId, false);
        if (tapsAtLabelOnly())
            features.tapAtLabelOnly(labelMaxResolution());
    }

    /**
     * Whether an area answers a tap on its label only, for areas that cover the whole
     * map. Called from the constructor: a constant.
     */
    protected boolean tapsAtLabelOnly() {
        return false;
    }

    /**
     * The requests, in order; each answer goes to {@link #parse(int, String)} with its
     * index. Read again before every request, so a layer may grow the list as answers
     * come in: the next page of an ArcGIS query, or a ShakeMap's detail and then its
     * contours.
     */
    protected abstract String[] urls();

    /**
     * Areas a layer draws beside what it fetched: what another page handed it. Read
     * on the worker at every rebuild; the rain gauges draw the office's report this way.
     */
    protected List<Area> extraAreas() {
        return java.util.Collections.emptyList();
    }

    /** Called on main as a refresh begins, before the first request: reset any grown list here. */
    protected void onRefreshStart() {
    }

    /** Called on main once a refresh has finished, drawn or failed. */
    protected void onRefreshDone() {
    }

    /** A request whose answer is a megabyte or more, read with the long timeout. */
    protected boolean large(int index) {
        return false;
    }

    /**
     * Whether an area is drawn on the chosen day. One day exactly by default; a layer
     * whose row is a window ("24 h", "48 h", "72 h") says {@code a.day <= showDay}.
     */
    protected boolean shown(Area a, int showDay) {
        return showDay == 0 || a.day == showDay;
    }

    /** Whether picking another day means asking the server again rather than redrawing what is held. */
    protected boolean refetchOnDay() {
        return false;
    }

    /**
     * What the area becomes on the map: a filled area in the category's color by
     * default. A layer of points or lines builds its own, and may return null to
     * draw nothing for this one. Worker thread.
     */
    protected AtmosphereFeatures.Drawn drawn(Area a, Geometry g, AttributeSet s) {
        final int c = a.color & 0x00FFFFFF;
        final int fill = (fillAlpha(a) << 24) | c;
        final int[] lc = labelColors(a);
        return new AtmosphereFeatures.Drawn(setName(a), a.title, g,
                lc == null
                        ? AtmosphereFeatures.area(strokeColor(a), WEIGHT, fill, a.title)
                        : AtmosphereFeatures.area(strokeColor(a), WEIGHT, fill, a.title,
                                lc[0], lc[1], labelMaxResolution()), s);
    }

    /**
     * Everything an area becomes on the map, for a layer that draws two things for
     * one record: a path and the pill at its start. {@link #drawn} alone by default.
     * Worker thread.
     */
    protected List<AtmosphereFeatures.Drawn> drawnAll(Area a, Geometry g, AttributeSet s) {
        final AtmosphereFeatures.Drawn d = drawn(a, g, s);
        final List<AtmosphereFeatures.Drawn> out = new ArrayList<>(1);
        if (d != null)
            out.add(d);
        return out;
    }

    /**
     * A second chip row under the day row, or null for none: "All", "M1+", "M2.5+",
     * "M4.5+" for the quakes. Called from the pane; a constant.
     */
    public String[] choices() {
        return null;
    }

    /** The row's heading, small caps over the chips. */
    public String choiceHeading() {
        return "";
    }

    protected int defaultChoice() {
        return 0;
    }

    public int choice() {
        return choice;
    }

    /** Whether another choice means asking the server again rather than redrawing what is held. */
    protected boolean refetchOnChoice() {
        return false;
    }

    /** Draw what is held again, under the current filters; no request. */
    protected void redraw() {
        if (on)
            rebuild(generation);
    }

    public void setChoice(int value) {
        final String[] cs = choices();
        final int v = cs == null ? 0 : Math.max(0, Math.min(cs.length - 1, value));
        if (v == choice)
            return;
        choice = v;
        final SharedPreferences p = MapCompat.prefs();
        if (p != null)
            p.edit().putInt(prefChoice, v).apply();
        if (!on)
            return;
        if (refetchOnChoice())
            refresh(true);
        else
            rebuild(generation);
    }

    protected abstract List<Area> parse(int index, String body);

    /** "Day 1: none. Day 2: Elevated." */
    protected abstract String summary(List<Area> areas);

    /** What the status says while the first answer is on its way, and on failure. */
    protected abstract String noun();

    /** How often every request is made again while on; half an hour unless the source moves faster. */
    protected long pollMs() {
        return POLL_MS;
    }

    /**
     * The feature set an area goes in, which ATAK's details pane prints under the
     * title: "Day 1" for an outlook. A layer without days names its own.
     */
    protected String setName(Area a) {
        return "Day " + a.day;
    }

    /** The day drawn on a new install, 1 to {@link #days()}. Called from the constructor: a constant. */
    protected int defaultDay() {
        return 1;
    }

    /** How many days the day row offers. Called from the constructor: a constant. */
    public int days() {
        return 3;
    }

    /** Whether the day row offers "All days"; not for areas that are the same places every day. */
    public boolean allDays() {
        return true;
    }

    /** The day row's button for a day, 1 to {@link #days()}. */
    public String dayLabel(int day) {
        return "Day " + day;
    }

    /** A request whose failure is logged and passed over, not the end of the refresh. */
    protected boolean optional(int index) {
        return false;
    }

    /** The fill's alpha for an area; 0 draws the outline alone. */
    protected int fillAlpha(Area a) {
        return FILL_ALPHA;
    }

    /**
     * The label's own colors, {text, background}, or null for the default: a tile
     * in the edge's color, drawn only when zoomed well in.
     */
    protected int[] labelColors(Area a) {
        return null;
    }

    /** With {@link #labelColors}: the coarsest map resolution, meters per pixel, the label draws at. */
    protected double labelMaxResolution() {
        return 14d;
    }

    /** The edge's color for an area, opaque; the category's own color by default. */
    protected int strokeColor(Area a) {
        return 0xFF000000 | a.color;
    }

    private int clampDay(int value) {
        return Math.max(allDays() ? 0 : 1, Math.min(days(), value));
    }

    public void setListener(Listener l) {
        listener = l;
    }

    public void setDataListener(Runnable r) {
        dataListener = r;
    }

    /** The areas drawn right now: in the window, under every filter. Main thread. */
    public List<Area> shownAreas() {
        final List<Area> out = new ArrayList<>();
        final int d = day;
        for (Area a : areas)
            if (shown(a, d))
                out.add(a);
        return out;
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
            mapView.postDelayed(autoPoll, pollMs());
        } else {
            areas = new ArrayList<>();
            drawNothing();
            status("");
            if (dataListener != null)
                dataListener.run();
        }
    }

    public List<Area> areas() {
        return areas;
    }

    public int day() {
        return day;
    }

    /** Which day to draw, 0 for all where offered; redrawn from what is held, no new request. */
    public void setDay(int value) {
        final int v = clampDay(value);
        if (v == day)
            return;
        day = v;
        final SharedPreferences p = MapCompat.prefs();
        if (p != null)
            p.edit().putInt(prefDay, v).apply();
        if (!on)
            return;
        if (refetchOnDay())
            refresh(true);
        else
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
        onRefreshStart();
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
            onRefreshDone();
            return;
        }
        final Http.Callback cb = new Http.Callback() {
            @Override
            public void onSuccess(final String body) {
                if (mine != generation || !on) {
                    inFlight = false;
                    return;
                }
                // Parsed on the worker, then back to main for the next request: the
                // answer comes back on main, and the national PSA map is 470 KB of
                // JSON, which is not work for the thread the pane answers on. A stop
                // shuts the worker down, and an answer that lands after it must not
                // take ATAK with it (security review, 2026-10-05).
                try {
                    worker.execute(new Runnable() {
                        @Override
                        public void run() {
                            final List<Area> parsed = parse(index, body);
                            mapView.post(new Runnable() {
                                @Override
                                public void run() {
                                    got.addAll(parsed);
                                    fetch(index + 1, got, mine);
                                }
                            });
                        }
                    });
                } catch (java.util.concurrent.RejectedExecutionException stopped) {
                    inFlight = false;
                }
            }

            @Override
            public void onFailure(String error) {
                Log.w(tag, "request " + index + ": " + error);
                if (optional(index)) {
                    fetch(index + 1, got, mine);
                    return;
                }
                inFlight = false;
                if (mine == generation && on)
                    status(capitalize(noun()) + ": " + error);
                onRefreshDone();
            }
        };
        if (large(index))
            Http.getLarge(urls[index], egress.userAgent(), new HashMap<String, String>(), cb);
        else
            Http.get(urls[index], egress.userAgent(), new HashMap<String, String>(), cb);
    }

    private void rebuild(final int mine) {
        final List<Area> snapshot = new ArrayList<>(areas);
        snapshot.addAll(extraAreas());
        worker.execute(new Runnable() {
            @Override
            public void run() {
                if (mine != generation)
                    return;
                final List<AtmosphereFeatures.Drawn> drawn = new ArrayList<>();
                final int showDay = day;
                for (Area a : snapshot) {
                    if (!shown(a, showDay))
                        continue;
                    final Geometry g;
                    try {
                        g = a.geometry == null ? null : GeoJson.parse(a.geometry);
                    } catch (Exception e) {
                        Log.w(tag, "unusable outlook shape", e);
                        continue;
                    }
                    if (g == null)
                        continue;
                    final AttributeSet s = new AttributeSet();
                    s.setAttribute("_details", a.details);
                    s.setAttribute("Outlook", a.title);
                    if (!a.ref.isEmpty())
                        s.setAttribute("_ref", a.ref);
                    try {
                        drawn.addAll(drawnAll(a, g, s));
                    } catch (RuntimeException e) {
                        Log.w(tag, "could not draw " + a.title, e);
                    }
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
                        if (dataListener != null)
                            dataListener.run();
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
