
package com.atakmap.android.atmosphere.overlay;

import android.content.Context;
import android.content.SharedPreferences;

import com.atakmap.android.atmosphere.compat.MapCompat;
import com.atakmap.android.atmosphere.data.Nhc;
import com.atakmap.android.atmosphere.net.EgressPolicy;
import com.atakmap.android.atmosphere.net.Http;
import com.atakmap.android.maps.MapView;
import com.atakmap.android.atmosphere.plugin.R;
import com.atakmap.coremap.maps.assets.Icon;
import com.atakmap.coremap.log.Log;
import com.atakmap.coremap.maps.coords.GeoPoint;
import com.atakmap.map.layer.feature.AttributeSet;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Live tropical cyclones on the map: the forecast cone, the forecast track, and where
 * the storm is now.
 *
 * <p>Two requests per storm and one for the list, all small. {@code CurrentStorms.json}
 * says what is running; NOAA's tropical ArcGIS service hands back the shapes as GeoJSON,
 * so nothing here unpacks a shapefile or a KMZ. See {@link Nhc} for the join between
 * them, which is the storm's slot rather than its name.
 *
 * <p>The shapes are ordinary ATAK map items in the plugin's own group, so they appear in
 * Overlay Manager and ATAK's own visibility controls work on them. They are clamped to
 * the ground: a plugin shape whose points carry an altitude sinks under the terrain the
 * moment somebody tilts or zooms, and a cone that vanishes reads as a broken layer.
 *
 * <p>Nothing is polled. An advisory is issued every six hours and this refreshes when
 * the layer is switched on and when the pane is opened, which is when somebody is
 * actually looking.
 */
public final class TropicalOverlay {

    private static final String TAG = "AtmosphereTropical";

    public static final String LAYER_ID = "tropical";
    public static final String HOST = Nhc.MAP_HOST;

    private static final String PREF_ON = "weather.layer.tropical.on";
    private static final String GROUP = "Hurricanes";

    /** An advisory is six-hourly; asking again inside this is asking for the same file. */
    private static final long REFRESH_MS = 10 * 60 * 1000L;
    /**
     * How often the layer redraws itself while it is on, the way a Feature Layer
     * source does (operator, 2026-09-23: "can it do an update every 15 minutes if
     * layer is on it redraws it"). Fixed rather than pickable: advisories are
     * six-hourly and intermediate ones land on no schedule a person would choose, so
     * a quarter of an hour catches them without a control nobody would touch twice.
     */
    private static final long AUTO_MS = 15 * 60 * 1000L;

    /** The cone is a forecast, not a boundary: outlined, barely filled, never solid. */
    private static final int CONE_STROKE = 0xFFFFFFFF;
    private static final int CONE_FILL = 0x33FFFFFF;
    private static final double CONE_WEIGHT = 2.0;
    private static final double TRACK_WEIGHT = 3.0;
    /** A watch or warning is a coastline, and red is the only color for it. */
    private static final int WATCH_STROKE = 0xFFFF2020;
    /** Arrival contours are a timetable, not a hazard: readable, not alarming. */
    private static final int ARRIVAL_STROKE = 0xFF90E0FF;

    public interface Listener {
        /** What is running. Empty when nothing is. */
        void onStorms(List<Nhc.Storm> storms);

        /** A line for the pane while something is happening, or "". */
        void onStatus(String status);
    }

    private final MapView mapView;
    private final Context pluginContext;
    private final EgressPolicy egress;

    private final AtmosphereFeatures features;
    private Listener listener;
    private boolean started;
    private boolean on;
    private long lastRefresh;
    /** Bumped whenever the layer is cleared, so a late response cannot redraw. */
    private int generation;
    private final StormIcons icons;
    private final List<Nhc.Storm> active = new ArrayList<>();
    private final Runnable autoRefresh = new Runnable() {
        @Override
        public void run() {
            if (!on || !started)
                return;
            refresh(true);
            mapView.postDelayed(this, AUTO_MS);
        }
    };
    /**
     * What each storm covers on the map, by slot: minLat, minLon, maxLat, maxLon.
     * Grown as its shapes arrive, so "go to" frames the cone rather than dropping the
     * operator on a point inside it.
     */
    private final Map<String, double[]> extents = new HashMap<>();
    private final ExecutorService worker = Executors.newSingleThreadExecutor();

    /**
     * Everything one refresh will draw, gathered before any of it is written.
     *
     * <p>A storm layer is not one request: it is the storm list, then a cone, a track,
     * the forecast positions, the watches and the arrival times for <b>each</b> storm,
     * arriving separately. Each answer used to be parsed and inserted as it landed, on
     * whatever thread delivered it -- which is the main thread, because that is where
     * {@link Http} posts. Two hurricanes was enough to stop ATAK answering for ten
     * seconds and raise "ATAK isn't responding" (operator, 2026-09-25; the ANR trace
     * is TropicalOverlay.addPoint -> addIcon -> insertFeature -> StatementImpl.execute
     * on main).
     *
     * <p>So each answer is parsed on the worker and appended here, and the last one in
     * writes the lot with a single {@link AtmosphereFeatures#rewrite}: one transaction
     * instead of hundreds, off the thread that draws the map, and the map never blanks
     * between a clear and a redraw because the replacement is atomic.
     *
     * <p>Counted with a latch rather than by working out in advance how many requests
     * a set of storms implies: the orchestrator holds one count of its own until it
     * has issued them all, so a batch can never finish early on a fast first answer.
     */
    private final class Batch {
        private final int mine;
        private final List<AtmosphereFeatures.Drawn> drawn = new ArrayList<>();
        private int outstanding = 1;
        private boolean flushed;

        Batch(int mine) {
            this.mine = mine;
        }

        synchronized void expect() {
            outstanding++;
        }

        synchronized void add(AtmosphereFeatures.Drawn d) {
            if (d != null)
                drawn.add(d);
        }

        /** One answer in, or one request that never arrived. The last one writes. */
        void done() {
            final List<AtmosphereFeatures.Drawn> all;
            synchronized (this) {
                if (--outstanding > 0 || flushed)
                    return;
                flushed = true;
                all = new ArrayList<>(drawn);
            }
            if (mine != generation || !on)
                return;
            run(new Runnable() {
                @Override
                public void run() {
                    if (mine != generation || !on)
                        return;
                    features.rewrite(all);
                    Log.d(TAG, "storms: wrote " + all.size() + " features in one pass");
                }
            });
        }
    }

    /** Off the main thread, and never after stop() has shut the worker down. */
    private void run(Runnable r) {
        try {
            worker.execute(r);
        } catch (RuntimeException shuttingDown) {
            Log.d(TAG, "worker is gone, dropping a storm write");
        }
    }

    public TropicalOverlay(MapView mapView, Context pluginContext, EgressPolicy egress) {
        this.mapView = mapView;
        this.pluginContext = pluginContext;
        this.egress = egress;
        // ATAK's own storage, not the plugin's: the plugin package's cache dir belongs
        // to a different uid than the process this runs in, so mkdirs there fails and
        // every composite lands on ENOENT (XCover, 2026-09-23). Feature Layer keeps its
        // label composites under tools/ for the same reason.
        this.features = new AtmosphereFeatures(mapView, pluginContext, TAG, GROUP,
                "storms.sqlite", "tropical", true);
        this.icons = new StormIcons(pluginContext,
                com.atakmap.coremap.filesystem.FileSystemUtils.getItem(
                        "tools/atmosphere/storm-icons"));
    }

    /**
     * Whether a storm is showing one of its products. Keyed by the storm's own id
     * rather than its slot: a slot is reused by whatever storm is running, so
     * "EP2" means a different hurricane next week, and a toggle would carry over
     * to a storm nobody chose it for.
     */
    public boolean isEnabled(Nhc.Storm storm, Nhc.Product product) {
        final SharedPreferences p = MapCompat.prefs();
        if (p == null || storm == null)
            return product.onByDefault;
        return p.getBoolean(key(storm, product), product.onByDefault);
    }

    public void setEnabled(Nhc.Storm storm, Nhc.Product product, boolean enabled) {
        final SharedPreferences p = MapCompat.prefs();
        if (p != null && storm != null)
            p.edit().putBoolean(key(storm, product), enabled).apply();
        redrawAll();
    }

    private static String key(Nhc.Storm storm, Nhc.Product product) {
        return "weather.tropical." + storm.id + "." + product.name();
    }

    /**
     * Wipe and redraw every storm. A toggle refetches rather than hiding what is
     * already there: an advisory is a handful of small files, and a layer that keeps
     * shapes it is not showing is a layer that drifts from what the operator sees.
     */
    private void redrawAll() {
        if (!on || !started)
            return;
        final List<Nhc.Storm> storms = new ArrayList<>(active);
        // No clear first: the batch's own write replaces the layer in one pass, so
        // turning a storm's cone off does not blank every other storm on the way.
        newGeneration();
        if (storms.isEmpty()) {
            clearFeaturesOffMain();
            return;
        }
        final Batch b = new Batch(generation);
        for (Nhc.Storm s : storms)
            draw(s, generation, b);
        b.done();
    }

    public void setListener(Listener l) {
        listener = l;
        if (l != null) {
            l.onStorms(new ArrayList<>(active));
            l.onStatus("");
        }
    }

    public void start() {
        started = true;
        features.attach();
        final SharedPreferences p = MapCompat.prefs();
        if (p != null && p.getBoolean(PREF_ON, false) && egress.isLayerEnabled(LAYER_ID))
            setOn(true);
    }

    public void stop() {
        started = false;
        on = false;
        mapView.removeCallbacks(autoRefresh);
        clear();
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
        if (value) {
            refresh(true);
            mapView.removeCallbacks(autoRefresh);
            mapView.postDelayed(autoRefresh, AUTO_MS);
        } else {
            mapView.removeCallbacks(autoRefresh);
            clear();
            active.clear();
            if (listener != null) {
                listener.onStorms(new ArrayList<Nhc.Storm>());
                listener.onStatus("");
            }
        }
    }

    /** The storms being drawn. */
    public List<Nhc.Storm> storms() {
        return new ArrayList<>(active);
    }

    /**
     * Ask NHC what is running and draw it. Cheap enough to call whenever the pane is
     * opened; {@code force} is for the toggle, which should answer immediately even if
     * the list was read a minute ago.
     */
    public void refresh(boolean force) {
        if (!on || !started)
            return;
        final long now = System.currentTimeMillis();
        if (!force && now - lastRefresh < REFRESH_MS)
            return;
        lastRefresh = now;
        status("Looking for storms…");
        final int mine = generation;
        Http.get(Nhc.CURRENT_STORMS, egress.userAgent(), null, new Http.Callback() {
            @Override
            public void onSuccess(String body) {
                if (mine != generation || !on)
                    return;
                final List<Nhc.Storm> storms = Nhc.parseActive(body);
                newGeneration();
                active.clear();
                if (storms.isEmpty()) {
                    // Saying so is the point: an empty map and a broken layer look
                    // identical, and most of the year this is the true answer.
                    clearFeaturesOffMain();
                    status("No storms right now");
                    if (listener != null)
                        listener.onStorms(new ArrayList<Nhc.Storm>());
                    return;
                }
                active.addAll(storms);
                final Batch b = new Batch(generation);
                for (Nhc.Storm s : storms)
                    draw(s, generation, b);
                // The orchestrator's own count, released now that every request for
                // this refresh has been issued. Without it a batch whose first answer
                // beats the second request out of the gate would write and close.
                b.done();
                status("");
                if (listener != null)
                    listener.onStorms(new ArrayList<>(active));
            }

            @Override
            public void onFailure(String error) {
                if (mine != generation || !on)
                    return;
                Log.w(TAG, "storm list failed: " + error);
                status("Could not reach the hurricane center");
            }
        });
    }

    /** "Hurricane Polo, 150 mph" -- what it is, and how hard, for a map item's title. */
    public static String label(Nhc.Storm s) {
        final StringBuilder b = new StringBuilder(s.display());
        if (s.intensityKt > 0) {
            // Knots are how the advisory is written and how nobody outside aviation
            // reads it; the pane's own unit choice does not reach the map, so this is
            // the number a crew would hear on the news.
            b.append(", ").append(Math.round(s.intensityKt * 1.15078)).append(" mph");
            if (s.category() > 0)
                b.append(" (cat ").append(s.category()).append(')');
        }
        return b.toString();
    }

    private void draw(final Nhc.Storm storm, final int mine, final Batch batch) {
        final String name = label(storm);
        for (Nhc.Product product : Nhc.Product.values()) {
            if (!isEnabled(storm, product))
                continue;
            final int layer = product.layer(storm.bin);
            switch (product) {
                case CONE:
                    fetchShape(storm, layer, mine, batch, name + " cone", name + " - Cone",
                            CONE_STROKE, CONE_FILL, CONE_WEIGHT, true);
                    break;
                case TRACK:
                    // Drawn from the forecast positions rather than the service's own
                    // track line, so each leg can carry the category it ends at --
                    // which is how every track map anybody has read is drawn. The
                    // points layer answers both; POINTS asks for it too and the second
                    // ask is a few KB.
                    fetchPoints(storm, Nhc.Product.POINTS.layer(storm.bin), mine, batch,
                            true, isEnabled(storm, Nhc.Product.POINTS));
                    break;
                case POINTS:
                    if (!isEnabled(storm, Nhc.Product.TRACK))
                        fetchPoints(storm, layer, mine, batch, false, true);
                    break;
                case WATCHES:
                    fetchShape(storm, layer, mine, batch, name + " watches",
                            name + " - Watches and warnings", WATCH_STROKE, 0,
                            TRACK_WEIGHT, false);
                    break;
                case ARRIVAL:
                    fetchShape(storm, layer, mine, batch, name + " wind arrival",
                            name + " - Wind arrival times", ARRIVAL_STROKE, 0,
                            CONE_WEIGHT, false);
                    break;
            }
        }
    }

    /**
     * The forecast positions, each as a marker carrying its day and time.
     *
     * <p>This is the part a crew reads off the map: where it is now, and when it is
     * forecast to be somewhere else. The symbol is NHC's own letter -- D depression,
     * S storm, H hurricane, M major -- punched through a disc tinted by category, and
     * the label is always drawn, because these are looked at from hundreds of meters
     * per pixel and ATAK otherwise holds a marker's name back until 10 m/px.
     */
    private void fetchPoints(final Nhc.Storm storm, final int layer, final int mine,
            final Batch batch, final boolean drawTrack, final boolean drawMarkers) {
        if (layer < 0)
            return;
        batch.expect();
        Http.get(Nhc.queryUrl(layer), egress.userAgent(), null, new Http.Callback() {
            @Override
            public void onSuccess(final String body) {
                // The worker, because this composes a labelled bitmap per forecast
                // position -- a PNG encode and a file write each -- on top of the
                // parsing and the inserts.
                run(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            build(body);
                        } finally {
                            batch.done();
                        }
                    }
                });
            }

            private void build(String body) {
                if (mine != generation || !on)
                    return;
                try {
                    final JSONArray feats = new JSONObject(body).optJSONArray("features");
                    if (feats == null)
                        return;
                    final List<GeoPoint> path = new ArrayList<>();
                    final List<Integer> colors = new ArrayList<>();
                    for (int i = 0; i < feats.length(); i++) {
                        final JSONObject f = feats.optJSONObject(i);
                        if (f == null)
                            continue;
                        final JSONObject g = f.optJSONObject("geometry");
                        final JSONObject pr = f.optJSONObject("properties");
                        if (g == null || pr == null
                                || !"Point".equals(g.optString("type", "")))
                            continue;
                        final JSONArray c = g.optJSONArray("coordinates");
                        if (c == null || c.length() < 2)
                            continue;
                        final GeoPoint p = new GeoPoint(c.optDouble(1), c.optDouble(0));
                        path.add(p);
                        colors.add(categoryColor(pr.optInt("ssnum", 0),
                                pr.optInt("maxwind", -1)));
                        if (drawMarkers)
                            addPoint(storm, p, pr, batch);
                    }
                    if (drawTrack)
                        drawLegs(storm, path, colors, batch);
                } catch (Exception e) {
                    Log.w(TAG, "forecast points unreadable", e);
                }
            }

            @Override
            public void onFailure(String error) {
                batch.done();
                if (mine == generation && on)
                    Log.w(TAG, "points layer " + layer + " failed: " + error);
            }
        });
    }

    /**
     * The track as one short shape per leg, each in the color of the category it
     * arrives at. The service does publish a single track line, but a line has one
     * stroke, and a track whose color never changes throws away the thing the map is
     * for: seeing where it becomes a major hurricane.
     */
    private void drawLegs(Nhc.Storm storm, List<GeoPoint> path, List<Integer> colors,
            Batch batch) {
        if (path.size() < 2)
            return;
        final String set = label(storm) + " - Track";
        final String name = label(storm) + " track";
        for (int i = 0; i + 1 < path.size(); i++) {
            final GeoPoint[] leg = { path.get(i), path.get(i + 1) };
            batch.add(new AtmosphereFeatures.Drawn(set, name,
                    AtmosphereFeatures.path(leg),
                    AtmosphereFeatures.stroke(name, colors.get(i + 1),
                            (float) TRACK_WEIGHT, false),
                    attrs(storm, null)));
            grow(storm.bin, leg);
        }
    }

    private void addPoint(Nhc.Storm storm, GeoPoint p, JSONObject pr, Batch batch) {
        // tau is the forecast hour, so tau 0 is where the storm is right now.
        final int tau = pr.optInt("tau", -1);
        final String when = pr.optString("datelbl", "");
        final String label = tau == 0 ? storm.display()
                : (when.isEmpty() ? storm.name : when);
        // The label is inside the icon; a feature's own label would be trimmed and
        // two on one point is worse than one.
        final Icon icon = icons.labelled(label,
                categoryColor(pr.optInt("ssnum", 0), pr.optInt("maxwind", -1)),
                tau == 0 ? 40 : 26);
        if (icon == null)
            return;
        final AttributeSet a = attrs(storm, pr);
        a.setAttribute("Position", label);
        batch.add(new AtmosphereFeatures.Drawn(label(storm) + " - Positions", label,
                AtmosphereFeatures.point(p.getLatitude(), p.getLongitude()),
                AtmosphereFeatures.icon(icon.getImageUri(Icon.STATE_DEFAULT),
                        icon.getWidth(), icon.getHeight()),
                a));
        grow(storm.bin, new GeoPoint[] { p });
    }

    /**
     * What a tap on this feature shows. The advisory's own fields where there is a
     * position to describe, and the storm's line where there is not (a cone has one
     * polygon and its own advisory header).
     */
    private static AttributeSet attrs(Nhc.Storm storm, JSONObject pr) {
        final AttributeSet a = new AttributeSet();
        a.setAttribute("Storm", storm.display());
        if (storm.intensityKt > 0)
            a.setAttribute("Wind", Math.round(storm.intensityKt * 1.15078) + " mph sustained");
        if (storm.pressureMb > 0)
            a.setAttribute("Pressure", storm.pressureMb + " mb");
        if (pr == null)
            return a;
        // An arrival contour's own hour, and the advisory a cone or a watch is from.
        put(a, "Winds arrive", pr.optString("arrival_time", ""));
        put(a, "Stage", pr.optString("tcdvlp", ""));
        put(a, "Valid", pr.optString("fldatelbl", ""));
        final int wind = pr.optInt("maxwind", -1);
        if (wind > 0)
            a.setAttribute("Wind", Math.round(wind * 1.15078) + " mph sustained");
        final int gust = pr.optInt("gust", -1);
        if (gust > 0)
            a.setAttribute("Gusts", Math.round(gust * 1.15078) + " mph");
        final int mslp = pr.optInt("mslp", -1);
        if (mslp > 0)
            a.setAttribute("Pressure", mslp + " mb");
        final int dir = pr.optInt("tcdir", -1), spd = pr.optInt("tcspd", -1);
        if (dir >= 0 && spd >= 0)
            a.setAttribute("Moving", dir + " deg at " + Math.round(spd * 1.15078) + " mph");
        put(a, "Advisory", pr.optString("advisnum", ""));
        put(a, "Issued", pr.optString("advdate", ""));
        return a;
    }

    private static void put(AttributeSet a, String key, String value) {
        if (value != null && !value.isEmpty() && !value.equals("null"))
            a.setAttribute(key, value);
    }


    /**
     * The Saffir-Simpson track-map palette: the colors NHC's own track maps use, and
     * with them every outlet and every briefing anybody has seen. Worth taking as
     * given rather than inventing, because the whole value of it is that a crew
     * already knows what orange means (operator, 2026-09-23: "if there is any
     * standard symbology for hurricane strength that we can apply to the segments").
     *
     * <p>Depression and storm are below category one and the scale does not number
     * them, so they are told apart by wind rather than by {@code ssnum}.
     */
    private static final int[] SAFFIR_SIMPSON = {
            0xFF5EBAFF,   // tropical depression, under 39 mph
            0xFF00FAF4,   // tropical storm, 39-73
            0xFFFFFFCC,   // category 1, 74-95
            0xFFFFE775,   // category 2, 96-110
            0xFFFFC140,   // category 3, 111-129
            0xFFFF8F20,   // category 4, 130-156
            0xFFFF6060,   // category 5, 157 and up
    };

    /** Where a position sits on the scale: 0 depression, 1 storm, 2-6 categories 1-5. */
    private static int rung(int category, int windKt) {
        if (category >= 1)
            return Math.min(6, category + 1);
        if (windKt >= 34)
            return 1;
        return 0;
    }

    /** The scale's labels, rung for rung with {@link #SAFFIR_SIMPSON}. */
    public static final String[] SAFFIR_SIMPSON_LABELS = {
            "TD", "TS", "1", "2", "3", "4", "5"
    };

    /** The color of a rung, for a legend that cannot drift from the map. */
    public static int rungColor(int rung) {
        return SAFFIR_SIMPSON[Math.max(0, Math.min(rung, SAFFIR_SIMPSON.length - 1))];
    }

    private static int categoryColor(int category, int windKt) {
        return SAFFIR_SIMPSON[rung(category, windKt)];
    }

    private static int trackColor(Nhc.Storm s) {
        return categoryColor(s.category(), s.intensityKt);
    }

    private void fetchShape(final Nhc.Storm storm, final int layer, final int mine,
            final Batch batch, final String title, final String set, final int stroke,
            final int fill, final double weight, final boolean closed) {
        if (layer < 0)
            return;
        batch.expect();
        Http.get(Nhc.queryUrl(layer), egress.userAgent(), null, new Http.Callback() {
            @Override
            public void onSuccess(final String body) {
                // Straight to the worker: a cone is thousands of coordinates to parse
                // and this callback is delivered on the main thread.
                run(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            build(body);
                        } finally {
                            batch.done();
                        }
                    }
                });
            }

            private void build(String body) {
                if (mine != generation || !on)
                    return;
                final List<Shape> rings = geometry(body);
                if (rings.isEmpty()) {
                    Log.d(TAG, "no geometry in layer " + layer + " for " + title);
                    return;
                }
                int n = 0;
                for (Shape s : rings) {
                    final GeoPoint[] ring = s.ring;
                    if (ring.length < 2)
                        continue;
                    // An arrival contour IS its hour, so that is the whole of its
                    // name. ATAK draws a feature's name as a label on the line, and
                    // the hour is the only reason the line is on the map: "Sat 8 pm"
                    // reads, "Hurricane Polo, 161 mph (cat 5) wind arrival, Sat 8 pm"
                    // does not (operator, 2026-09-23: "i have the wind arrival on, it
                    // has no times"). The storm it belongs to is in the attributes and
                    // in the feature set's own name.
                    final String own = s.props == null ? ""
                            : s.props.optString("arrival_time", "");
                    final String name = !own.isEmpty() ? own
                            : rings.size() > 1 ? title + " " + (++n) : title;
                    if (closed)
                        batch.add(new AtmosphereFeatures.Drawn(set, name,
                                AtmosphereFeatures.polygon(ring),
                                AtmosphereFeatures.area(stroke, (float) weight, fill),
                                attrs(storm, s.props)));
                    else
                        // An arrival contour carries its hour as its name, so it is
                        // the one line worth labeling on the map.
                        batch.add(new AtmosphereFeatures.Drawn(set, name,
                                AtmosphereFeatures.path(ring),
                                AtmosphereFeatures.stroke(name, stroke, (float) weight,
                                        !own.isEmpty()),
                                attrs(storm, s.props)));
                    grow(storm.bin, ring);
                    Log.d(TAG, String.format(Locale.US,
                            "drew %s: %d points, %.2f,%.2f..%.2f,%.2f", name,
                            ring.length, bound(ring, true, true), bound(ring, false, true),
                            bound(ring, true, false), bound(ring, false, false)));
                }
            }

            @Override
            public void onFailure(String error) {
                // Released either way, or a batch never writes what did arrive.
                batch.done();
                if (mine != generation || !on)
                    return;
                Log.w(TAG, "layer " + layer + " failed: " + error);
            }
        });
    }

    /**
     * Every ring and line in a GeoJSON body, as ATAK points.
     *
     * <p>Only the outer ring of a polygon is taken. A forecast cone has no holes, and
     * an inner ring drawn as another outline would read as a second cone.
     */
    static List<Shape> geometry(String json) {
        final List<Shape> out = new ArrayList<>();
        if (json == null || json.isEmpty())
            return out;
        try {
            final JSONArray feats = new JSONObject(json).optJSONArray("features");
            if (feats == null)
                return out;
            for (int i = 0; i < feats.length(); i++) {
                final JSONObject f = feats.optJSONObject(i);
                if (f == null)
                    continue;
                final JSONObject g = f.optJSONObject("geometry");
                if (g == null)
                    continue;
                final String type = g.optString("type", "");
                final JSONArray c = g.optJSONArray("coordinates");
                if (c == null)
                    continue;
                final JSONObject props = f.optJSONObject("properties");
                if (type.equals("LineString"))
                    add(out, ring(c), props);
                else if (type.equals("MultiLineString") || type.equals("Polygon"))
                    for (int k = 0; k < c.length(); k++)
                        add(out, ring(c.optJSONArray(k)), props);
                else if (type.equals("MultiPolygon"))
                    for (int k = 0; k < c.length(); k++) {
                        final JSONArray poly = c.optJSONArray(k);
                        if (poly != null && poly.length() > 0)
                            add(out, ring(poly.optJSONArray(0)), props);
                    }
            }
        } catch (Exception e) {
            Log.w(TAG, "geometry unreadable", e);
        }
        return out;
    }

    private static void add(List<Shape> out, GeoPoint[] ring, JSONObject props) {
        if (ring != null && ring.length >= 2)
            out.add(new Shape(ring, props));
    }

    /**
     * One ring, with the fields the service sent alongside it. The properties used to
     * be dropped here, which is why an arrival contour could not say its own
     * "Wed 2 pm" and a cone could not say which advisory it came from.
     */
    static final class Shape {
        final GeoPoint[] ring;
        final JSONObject props;

        Shape(GeoPoint[] ring, JSONObject props) {
            this.ring = ring;
            this.props = props;
        }
    }

    /** One ring of [lon, lat] pairs. GeoJSON is lon first; GeoPoint is lat first. */
    private static GeoPoint[] ring(JSONArray coords) {
        if (coords == null)
            return null;
        final List<GeoPoint> pts = new ArrayList<>(coords.length());
        for (int i = 0; i < coords.length(); i++) {
            final JSONArray p = coords.optJSONArray(i);
            if (p == null || p.length() < 2)
                continue;
            final double lon = p.optDouble(0, Double.NaN);
            final double lat = p.optDouble(1, Double.NaN);
            if (Double.isNaN(lat) || Double.isNaN(lon))
                continue;
            pts.add(new GeoPoint(lat, lon));
        }
        return pts.toArray(new GeoPoint[0]);
    }

    /** A corner of a ring, for the log: min or max of lat or lon. */
    private static double bound(GeoPoint[] ring, boolean lat, boolean min) {
        double v = min ? Double.MAX_VALUE : -Double.MAX_VALUE;
        for (GeoPoint p : ring) {
            final double x = lat ? p.getLatitude() : p.getLongitude();
            v = min ? Math.min(v, x) : Math.max(v, x);
        }
        return v;
    }

    /** Widen what a storm is known to cover, as each of its shapes lands. */
    /**
     * Widen a storm's extent, so "Go to" can frame it.
     *
     * <p>Guarded: this is filled on the worker as each answer is parsed and read on
     * main when the operator presses Go to.
     */
    private void grow(String bin, GeoPoint[] ring) {
        synchronized (extents) {
            double[] e = extents.get(bin);
            if (e == null) {
                // South, west, north, east, and in [4] the longitude the others are
                // measured against: the first point this storm drew.
                e = new double[] { Double.MAX_VALUE, Double.MAX_VALUE,
                        -Double.MAX_VALUE, -Double.MAX_VALUE, Double.NaN };
                extents.put(bin, e);
            }
            for (GeoPoint p : ring) {
                if (Double.isNaN(e[4]))
                    e[4] = p.getLongitude();
                final double lon = unwrap(p.getLongitude(), e[4]);
                e[0] = Math.min(e[0], p.getLatitude());
                e[1] = Math.min(e[1], lon);
                e[2] = Math.max(e[2], p.getLatitude());
                e[3] = Math.max(e[3], lon);
            }
        }
    }

    /**
     * A longitude moved by whole turns to within half a turn of {@code ref}.
     *
     * <p>The service splits a cone that crosses the date line into two polygons,
     * one ending at -180 and one starting at +180. Boxed as they come, Hurricane
     * Nolo's ran from -180 to +180 and Go to centered the map on longitude 0, off
     * Africa (operator, 2026-09-29). Measured from the storm, the piece at +179.5
     * is -180.5, and the box is the storm's.
     */
    static double unwrap(double lon, double ref) {
        double l = lon;
        while (l - ref > 180)
            l -= 360;
        while (l - ref < -180)
            l += 360;
        return l;
    }

    /** Back into -180..180 for the map. */
    static double wrap(double lon) {
        double l = lon;
        while (l > 180)
            l -= 360;
        while (l < -180)
            l += 360;
        return l;
    }

    /**
     * Put a storm on screen, cone and all.
     *
     * <p>Deliberately unlike the "go to" in Cam Depot and Comms, which will not zoom
     * the operator back out to reach a site. A five-day cone is a thousand miles
     * across, so arriving at the storm's own position at a street-level zoom shows a
     * patch of empty ocean inside the cone. This frames what was drawn.
     */
    public void goTo(Nhc.Storm storm) {
        if (storm == null)
            return;
        final double[] e;
        synchronized (extents) {
            final double[] held = extents.get(storm.bin);
            e = held == null ? null : held.clone();
        }
        final GeoPoint here = Double.isNaN(storm.latitude) || Double.isNaN(storm.longitude)
                ? null : new GeoPoint(storm.latitude, storm.longitude);
        if (e == null || e[0] > e[2]) {
            // Shapes have not landed yet: the storm's own position is still an answer.
            if (here != null)
                pan(here, Double.NaN);
            return;
        }
        final GeoPoint center = new GeoPoint((e[0] + e[2]) / 2, wrap((e[1] + e[3]) / 2));
        pan(center, fitResolution(e));
    }

    /** Meters per pixel that fits a box in the map view, with room around it. */
    private double fitResolution(double[] e) {
        final int w = Math.max(1, mapView.getWidth());
        final int h = Math.max(1, mapView.getHeight());
        final double midLat = Math.toRadians((e[0] + e[2]) / 2);
        final double tall = (e[2] - e[0]) * 111_320.0;
        final double wide = (e[3] - e[1]) * 111_320.0 * Math.max(0.1, Math.cos(midLat));
        // A sixth again, so the cone is not drawn against the edges of the screen.
        return Math.max(tall / h, wide / w) * 1.15;
    }

    private void pan(GeoPoint p, double resolution) {
        try {
            if (Double.isNaN(resolution)) {
                mapView.getMapController().panTo(p, true);
                return;
            }
            mapView.getMapController().panZoomTo(p,
                    mapView.mapResolutionAsMapScale(resolution), true);
        } catch (LinkageError | RuntimeException ex) {
            Log.w(TAG, "panZoomTo failed; falling back to a plain pan", ex);
            mapView.getMapController().panTo(p, true);
        }
    }

    /** Drop everything drawn and make any response in flight land nowhere. */
    /** Everything in flight for the last refresh is now stale. Cheap, stays on main. */
    private void newGeneration() {
        generation++;
        synchronized (extents) {
            extents.clear();
        }
    }

    /** Emptying the store is a database write like any other: never on main. */
    private void clearFeaturesOffMain() {
        run(new Runnable() {
            @Override
            public void run() {
                features.clear();
            }
        });
    }

    private void clear() {
        newGeneration();
        clearFeaturesOffMain();
    }

    private void status(String s) {
        if (listener != null)
            listener.onStatus(s);
    }
}
