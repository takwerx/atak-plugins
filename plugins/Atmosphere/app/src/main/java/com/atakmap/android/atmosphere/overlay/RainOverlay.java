package com.atakmap.android.atmosphere.overlay;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;

import com.atakmap.android.atmosphere.compat.MapCompat;
import com.atakmap.android.atmosphere.net.EgressPolicy;
import com.atakmap.android.atmosphere.net.Http;
import com.atakmap.android.atmosphere.rain.BandLabels;
import com.atakmap.android.atmosphere.rain.NomadsRain;
import com.atakmap.android.atmosphere.rain.NomadsTemperature;
import com.atakmap.android.atmosphere.rain.RainGrid;
import com.atakmap.android.atmosphere.units.UnitSystem;
import com.atakmap.android.atmosphere.wind.NomadsWind;
import com.atakmap.android.maps.MapView;
import com.atakmap.coremap.log.Log;
import com.atakmap.coremap.maps.coords.GeoBounds;
import com.atakmap.map.AtakMapView;
import com.atakmap.map.layer.feature.AttributeSet;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

/**
 * The rain forecast on the map: the rate the model expects rain to fall, as a
 * colored field light to violent, one forecast hour at a time on the shared time
 * strip, drawn as a raster on the radar's {@link RasterLayer}. NOAA's global model,
 * from the same NOMADS filter the wind, smoke and waves come through
 * ({@link NomadsRain}). Lives for the plugin's life; the pane drives and reads it.
 *
 * <p>The wave layer's shape without the grid ladder: the view padded by half its
 * span, frames cached per (run, hour, region), a move outside the region or a zoom
 * past a third of it fetching again, a non-GRIB answer stepping back a run, and the
 * picture built off the main thread. The model is global, so the box is only
 * capped in width, not in latitude.
 */
public final class RainOverlay {

    private static final String TAG = "AtmosphereRain";
    public static final String LAYER_ID = "rain";
    public static final String TEMPERATURE_LAYER_ID = "temperature";
    public static final String HOST = NomadsRain.HOST;
    private static final String PREF_UNITS = "weather.units";
    private static final String PREF_TRANSPARENCY = "weather.layer.temperature.transparency";
    private static final String PREF_LEVEL = "weather.layer.temperature.level";
    /** Percent see-through the operator can ask for, and what the layer starts at. */
    public static final int MAX_TRANSPARENCY = 90;
    private static final int DEFAULT_TRANSPARENCY = 0;

    /** Eight pixels a quarter-degree cell: a 30-degree box is 960 across. */
    private static final int MAX_PX = 1024;
    /** The temperature's box is wider, so its picture is too, or a continent goes blocky. */
    private static final int MAX_PX_TEMPERATURE = 2048;
    private static final int PX_PER_CELL = 8;
    private static final long MOVE_SETTLE_MS = 600L;
    private static final long SCRUB_SETTLE_MS = 180L;
    private static final int CACHE_FRAMES = 12;
    private static final int RUN_STEPS_BACK = 4;
    /** A label cell on the lattice {@link BandLabels} works on, about a pill wide. */
    private static final float LABEL_CELL_DP = 80f;
    /** The band labels' layer in Overlay Manager, a row beside the picture's. */
    private static final String LABELS_NAME = "Air Temperature Labels";

    public interface Listener {
        void onFrames(List<String> labels, int shown);
        void onFrameShown(int index, long validTime);
        void onStatus(String status);
    }

    private static final class Frame {
        final RainGrid grid;
        final Bitmap bitmap;
        final GeoBounds bounds;

        Frame(RainGrid grid, Bitmap bitmap) {
            this.grid = grid;
            this.bitmap = bitmap;
            this.bounds = new GeoBounds(grid.north, grid.west, grid.south, grid.east);
        }
    }

    private final MapView mapView;
    private final EgressPolicy egress;
    private final RasterLayer layer;
    private final boolean temperature;
    private final String layerId;
    /** Temperature only: the band written on each patch of the picture; null for rain. */
    private final AtmosphereFeatures labels;
    private final ZonePills pills;
    private final float density;
    private final Map<String, Frame> cache = new LinkedHashMap<String, Frame>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Frame> eldest) {
            return size() > CACHE_FRAMES;
        }
    };
    private ExecutorService worker;

    private boolean started, on;
    /** Temperature only: band the picture in °F rather than °C. */
    private boolean fahrenheit;
    /** Temperature only: percent see-through, and the height drawn. */
    private int transparency = DEFAULT_TRANSPARENCY;
    private NomadsWind.Level level = NomadsWind.Level.AGL_10;
    private long run;
    private int hours = NomadsRain.HOURS;
    private int hour;
    private int firstHour;
    private GeoBounds region;
    private boolean cropped;
    private Frame shown;
    private int generation;
    private String pendingKey;
    private Listener listener;

    private final Runnable moveSettled = new Runnable() {
        @Override
        public void run() {
            if (on)
                ensureRegion();
        }
    };

    private final Runnable scrubSettled = new Runnable() {
        @Override
        public void run() {
            if (on)
                showHour();
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

    public RainOverlay(MapView mapView, EgressPolicy egress) {
        this(mapView, null, egress, false);
    }

    /** The temperature, which labels its bands on the map and so needs the plugin's context. */
    public RainOverlay(MapView mapView, Context pluginContext, EgressPolicy egress, boolean temperature) {
        this.mapView = mapView;
        this.egress = egress;
        this.temperature = temperature;
        this.layerId = temperature ? TEMPERATURE_LAYER_ID : LAYER_ID;
        this.layer = new RasterLayer(temperature ? "Atmosphere air temperature" : "Atmosphere rain");
        this.density = mapView.getContext().getResources().getDisplayMetrics().density;
        if (temperature && pluginContext != null) {
            labels = new AtmosphereFeatures(mapView, pluginContext, TAG, LABELS_NAME,
                    "temperature_labels.sqlite", "temperature", false);
            pills = new ZonePills();
        } else {
            labels = null;
            pills = null;
        }
    }

    public void setListener(Listener l) {
        listener = l;
        if (l != null) {
            l.onFrames(labels(), hourIndex());
            l.onFrameShown(hourIndex(), shown == null ? 0 : shown.grid.validTime);
        }
    }

    public void start() {
        if (started)
            return;
        started = true;
        worker = Executors.newSingleThreadExecutor(new ThreadFactory() {
            @Override
            public Thread newThread(Runnable r) {
                final Thread t = new Thread(r, "AtmosphereRain");
                t.setDaemon(true);
                return t;
            }
        });
        GLRasterLayer.register();
        mapView.addLayer(MapView.RenderStack.MAP_SURFACE_OVERLAYS, layer);
        layer.setVisible(false);
        if (labels != null)
            labels.attach();
        mapView.addOnMapMovedListener(moved);
        final SharedPreferences p = MapCompat.prefs();
        // The pane may not exist yet when a layer left on comes back; the unit the
        // bands step in is the operator's, read here, and the pane keeps it current.
        fahrenheit = UnitSystem.fromName(p == null ? null : p.getString(PREF_UNITS, null),
                UnitSystem.IMPERIAL) != UnitSystem.METRIC;
        if (temperature && p != null) {
            transparency = Math.max(0, Math.min(MAX_TRANSPARENCY,
                    p.getInt(PREF_TRANSPARENCY, DEFAULT_TRANSPARENCY)));
            try {
                level = NomadsWind.Level.valueOf(p.getString(PREF_LEVEL, level.name()));
            } catch (IllegalArgumentException e) {
                level = NomadsWind.Level.AGL_10;
            }
        }
        layer.setAlpha((100 - transparency) / 100f);
        if (p != null && p.getBoolean("weather.layer." + layerId + ".on", false)
            && egress.isLayerEnabled(layerId))
            setOn(true);
    }

    public void stop() {
        if (!started)
            return;
        started = false;
        on = false;
        mapView.removeOnMapMovedListener(moved);
        mapView.removeCallbacks(moveSettled);
        mapView.removeCallbacks(runCheck);
        mapView.removeCallbacks(scrubSettled);
        generation++;
        layer.clear();
        mapView.removeLayer(MapView.RenderStack.MAP_SURFACE_OVERLAYS, layer);
        GLRasterLayer.unregister();
        if (worker != null) {
            worker.shutdownNow();
            worker = null;
        }
        if (labels != null)
            labels.detach();
        cache.clear();
        shown = null;
    }

    public boolean isOn() {
        return on;
    }

    /** The grid behind the picture on the map, or null; for a legend of what is drawn. */
    public RainGrid shownGrid() {
        return shown == null ? null : shown.grid;
    }

    public int transparency() {
        return transparency;
    }

    /** Percent see-through; the renderer fades what it holds, so the slider moves the picture at once. */
    public void setTransparency(int percent) {
        final int v = Math.max(0, Math.min(MAX_TRANSPARENCY, percent));
        if (v == transparency)
            return;
        transparency = v;
        layer.setAlpha((100 - v) / 100f);
        final SharedPreferences p = MapCompat.prefs();
        if (p != null)
            p.edit().putInt(PREF_TRANSPARENCY, v).apply();
    }

    public NomadsWind.Level level() {
        return level;
    }

    public int levelIndex() {
        for (int i = 0; i < NomadsTemperature.LEVELS.length; i++)
            if (NomadsTemperature.LEVELS[i] == level)
                return i;
        return 0;
    }

    /** The height drawn, by its place in {@link NomadsTemperature#LEVELS}; another height is another fetch. */
    public void setLevelIndex(int index) {
        if (!temperature || index < 0 || index >= NomadsTemperature.LEVELS.length)
            return;
        final NomadsWind.Level l = NomadsTemperature.LEVELS[index];
        if (l == level)
            return;
        level = l;
        final SharedPreferences p = MapCompat.prefs();
        if (p != null)
            p.edit().putString(PREF_LEVEL, l.name()).apply();
        if (!on)
            return;
        generation++;
        pendingKey = null;
        region = null;
        ensureRegion();
    }

    /**
     * Which unit the temperature bands step in. A display change, not a forecast
     * change: the frame on the map is redrawn from its grid and the rest of the
     * cache dropped, nothing is fetched.
     */
    public void setFahrenheit(boolean value) {
        if (!temperature || fahrenheit == value)
            return;
        fahrenheit = value;
        final Frame current = shown;
        cache.clear();
        pendingKey = null;
        if (!on || current == null)
            return;
        final ExecutorService w = worker;
        if (w == null)
            return;
        final int mine = ++generation;
        final String k = region == null ? null : key(run, hour, region);
        w.execute(new Runnable() {
            @Override
            public void run() {
                final Frame redrawn = redraw(current.grid);
                mapView.post(new Runnable() {
                    @Override
                    public void run() {
                        if (mine != generation || !on)
                            return;
                        if (k != null)
                            cache.put(k, redrawn);
                        show(redrawn);
                    }
                });
            }
        });
    }

    /** Color a grid into a frame; worker thread. */
    private Frame redraw(RainGrid g) {
        final int maxPx = temperature ? MAX_PX_TEMPERATURE : MAX_PX;
        final int pw = Math.max(64, Math.min(maxPx, g.nx * PX_PER_CELL));
        final int ph = Math.max(64, Math.min(maxPx, g.ny * PX_PER_CELL));
        final int[] px = temperature
                ? NomadsTemperature.render(g, pw, ph, fahrenheit, NomadsTemperature.scaleShift(level))
                : NomadsRain.render(g, pw, ph);
        Log.d(TAG, String.format(Locale.US, "drawn %dx%d over %.2f,%.2f..%.2f,%.2f, %s",
                pw, ph, g.west, g.south, g.east, g.north,
                temperature ? (fahrenheit ? "temperature °F" : "temperature °C")
                        : String.format(Locale.US, "rain on %.1f%%", NomadsRain.rainPercent(px))));
        return new Frame(g, Bitmap.createBitmap(px, pw, ph, Bitmap.Config.ARGB_8888));
    }

    /** How often a layer left on looks for a newer model run. */
    private static final long RUN_CHECK_MS = 10 * 60 * 1000L;

    /**
     * A newer run, once the model has one. The run was chosen only when the layer
     * was switched on, so a device left on kept an old forecast and its "now" never
     * moved (operator, 2026-10-17, on the wind: "if you just leave it on its not
     * updating"). The time shown stays the time shown.
     */
    private final Runnable runCheck = new Runnable() {
        @Override
        public void run() {
            if (!on)
                return;
            final long now = System.currentTimeMillis();
            final long latest = NomadsRain.latestRun(now);
            if (latest != run || (now - latest) / 3_600_000L != firstHour) {
                final long shownTime = run + hour * 3_600_000L;
                setRun(latest);
                hour = temperature ? firstHour
                    : (int) Math.max(firstHour, Math.min(hours, Math.round((shownTime - latest) / 3_600_000.0)));
                cache.clear();
                region = null;
                generation++;
                pendingKey = null;
                Log.d(TAG, "newer run " + latest + ", refetching");
                if (listener != null)
                    listener.onFrames(labels(), hourIndex());
                ensureRegion();
            }
            mapView.postDelayed(this, RUN_CHECK_MS);
        }
    };

    public void setOn(boolean value) {
        if (!started || on == value)
            return;
        on = value;
        final SharedPreferences p = MapCompat.prefs();
        if (p != null)
            p.edit().putBoolean("weather.layer." + layerId + ".on", value).apply();
        if (value) {
            setRun(NomadsRain.latestRun(System.currentTimeMillis()));
            if (temperature)
                hour = firstHour;
            if (listener != null)
                listener.onFrames(labels(), hourIndex());
            layer.setVisible(true);
            ensureRegion();
            mapView.removeCallbacks(runCheck);
            mapView.postDelayed(runCheck, RUN_CHECK_MS);
        } else {
            generation++;
            mapView.removeCallbacks(runCheck);
            layer.setVisible(false);
            layer.clear();
            shown = null;
            clearLabels();
            status("");
        }
    }

    /** Adopt a run and work out where now falls in it; see the wind's. */
    private void setRun(long value) {
        run = value;
        hours = NomadsRain.HOURS;
        final long ahead = System.currentTimeMillis() - run;
        firstHour = (int) Math.max(0, Math.min(hours, ahead / 3_600_000L));
        hour = Math.max(firstHour, Math.min(hour, hours));
    }

    public List<String> labels() {
        final List<String> out = new ArrayList<>();
        for (int h = firstHour; h <= hours; h++)
            out.add("+" + (h - firstHour) + " h");
        return out;
    }

    public int hourIndex() {
        return hour - firstHour;
    }

    public long validTime(int index) {
        return run + (firstHour + index) * 3_600_000L;
    }

    public void setHourIndex(int index) {
        final int h = Math.max(firstHour, Math.min(firstHour + index, hours));
        if (h == hour)
            return;
        hour = h;
        mapView.removeCallbacks(scrubSettled);
        if (region != null && cache.containsKey(key(run, hour, region)))
            showHour();
        else
            mapView.postDelayed(scrubSettled, SCRUB_SETTLE_MS);
    }

    /** A reading at a point, off the picture already on the map. */
    public static final class Reading {
        /** Millimeters an hour; NaN where the model has no value. */
        public final float mmPerHr;

        Reading(float mmPerHr) {
            this.mmPerHr = mmPerHr;
        }
    }

    /** The rain at a point, or null when the picture on the map does not cover it. Never a fetch. */
    public Reading readingAt(double lat, double lon) {
        final Frame f = shown;
        if (f == null || !f.grid.contains(lat, lon))
            return null;
        return new Reading(f.grid.sample(lat, lon));
    }

    // ---- region and frames ---------------------------------------------------------

    private String key(long run, int hour, GeoBounds r) {
        return String.format(Locale.US, "%d|%d|%s|%.3f,%.3f,%.3f,%.3f", run, hour,
                temperature ? level.name() : "",
                r.getWest(), r.getSouth(), r.getEast(), r.getNorth());
    }

    /**
     * What the screen shows, as a box. The map's own bounds, unless a corner of the
     * screen is off the globe: zoomed out to a continent the top corners see past
     * the horizon, the bounds come back NaN, and the layer said "zoom in" at exactly
     * the zoom a temperature map is for. Then the box is worked from the center and
     * the resolution, which is what the middle of the screen covers.
     */
    private GeoBounds viewBounds() {
        final GeoBounds b = mapView.getBounds();
        if (b != null && !Double.isNaN(b.getNorth()) && !Double.isNaN(b.getSouth())
                && !Double.isNaN(b.getEast()) && !Double.isNaN(b.getWest()))
            return b;
        final com.atakmap.coremap.maps.coords.GeoPoint c = mapView.getCenterPoint().get();
        final double metersPerPx = mapView.getMapResolution();
        if (c == null || !c.isValid() || metersPerPx <= 0 || mapView.getWidth() <= 0)
            return b;
        final double degPerM = 1 / 111_320d;
        final double halfLat = mapView.getHeight() * metersPerPx * degPerM / 2;
        final double north = Math.min(90, c.getLatitude() + halfLat);
        final double south = Math.max(-90, c.getLatitude() - halfLat);
        // Reaching a pole, the view holds every longitude: the cap, as a ring.
        if (north >= 85 || south <= -85)
            return new GeoBounds(north >= 85 ? 90 : north, -180, south <= -85 ? -90 : south, 180);
        final double cos = Math.max(0.2, Math.cos(Math.toRadians(c.getLatitude())));
        final double halfLon = mapView.getWidth() * metersPerPx * degPerM / cos / 2;
        if (halfLon >= 180)
            return b;
        // Not clamped: a side past 180 is how the caller learns the view wraps.
        return new GeoBounds(north, c.getLongitude() - halfLon, south, c.getLongitude() + halfLon);
    }

    private static boolean contains(GeoBounds outer, GeoBounds inner) {
        return inner.getWest() >= outer.getWest() && inner.getEast() <= outer.getEast()
                && inner.getSouth() >= outer.getSouth() && inner.getNorth() <= outer.getNorth();
    }

    private void clearMap(String why) {
        if (temperature) {
            generation++;
            pendingKey = null;
        }
        layer.clear();
        shown = null;
        region = null;
        clearLabels();
        status(why);
    }

    private void ensureRegion() {
        if (!on)
            return;
        GeoBounds bounds = viewBounds();
        if (bounds == null)
            return;
        // Every longitude at once: looking down on a pole, or across the
        // antimeridian (the view east of the view's west, the Aleutians and the
        // mid-Pacific). One box cannot name a span across 180, so the whole ring of
        // the latitude band is asked for; the model is a quarter degree and a ring
        // 60 degrees tall is under two megabytes. Drawn only one side at first, the
        // other side of the Pacific was bare to a hard edge (2026-11-12).
        final boolean ring = temperature && !Double.isNaN(bounds.getNorth())
                && !Double.isNaN(bounds.getSouth()) && !Double.isNaN(bounds.getEast())
                && !Double.isNaN(bounds.getWest())
                && (bounds.getNorth() >= 85 || bounds.getSouth() <= -85
                        || bounds.getEast() < bounds.getWest()
                        || bounds.getWest() < -180 || bounds.getEast() > 180);
        if (ring)
            bounds = new GeoBounds(bounds.getNorth(), -180, bounds.getSouth(), 180);
        final boolean wholeWorld = Double.isNaN(bounds.getNorth()) || Double.isNaN(bounds.getSouth())
                || Double.isNaN(bounds.getEast()) || Double.isNaN(bounds.getWest())
                || bounds.getEast() <= bounds.getWest()
                || (!ring && bounds.getEast() - bounds.getWest() >= 355);
        if (wholeWorld) {
            clearMap(temperature ? "Zoom in to see air temperature" : "Zoom in to see the rain");
            return;
        }
        final GeoBounds clamped = new GeoBounds(Math.min(90, bounds.getNorth()), bounds.getWest(),
                Math.max(-90, bounds.getSouth()), bounds.getEast());
        final double maxLon = temperature ? NomadsTemperature.MAX_SPAN_LON : NomadsRain.MAX_SPAN_LON;
        final double maxLat = temperature ? NomadsTemperature.MAX_SPAN_LAT : NomadsRain.MAX_SPAN_LAT;

        boolean refetch = region == null || !contains(region, clamped);
        if (refetch && region != null && clamped.getEast() - clamped.getWest() > maxLon) {
            // Wider than the model is asked for: refetch only when the center leaves
            // the box, so a pan is not a request a second.
            final double cx = (clamped.getEast() + clamped.getWest()) / 2;
            final double cy = (clamped.getNorth() + clamped.getSouth()) / 2;
            refetch = cx < region.getWest() || cx > region.getEast()
                    || cy < region.getSouth() || cy > region.getNorth();
        }
        if (!refetch) {
            final double viewSpan = clamped.getEast() - clamped.getWest();
            final double regionSpan = region.getEast() - region.getWest();
                refetch = (viewSpan < regionSpan / 3.5 && (!temperature || regionSpan > 2.01))
                    || viewSpan > regionSpan * 1.6;
        }
        if (refetch) {
            double w = clamped.getWest(), e = clamped.getEast();
            double s = clamped.getSouth(), n = clamped.getNorth();
            cropped = false;
            if (temperature && e - w < 1) {
                final double center = (w + e) / 2;
                w = Math.max(-180, center - 0.5);
                e = Math.min(180, center + 0.5);
            }
            if (temperature && n - s < 1) {
                final double center = (n + s) / 2;
                s = Math.max(-90, center - 0.5);
                n = Math.min(90, center + 0.5);
            }
            if (e - w > maxLon) {
                final double c = (e + w) / 2;
                w = c - maxLon / 2;
                e = c + maxLon / 2;
                cropped = true;
            }
            if (n - s > maxLat) {
                final double c = (n + s) / 2;
                s = c - maxLat / 2;
                n = c + maxLat / 2;
                cropped = true;
            }
            final double padX = Math.min((e - w) * 0.5, Math.max(0, (maxLon - (e - w)) / 2));
            final double padY = Math.min((n - s) * 0.5, Math.max(0, (maxLat - (n - s)) / 2));
            region = new GeoBounds(Math.min(90, n + padY), Math.max(-180, w - padX),
                    Math.max(-90, s - padY), Math.min(180, e + padX));
            cache.clear();
        }
        showHour();
    }

    private void showHour() {
        if (!on || region == null)
            return;
        final GeoBounds r = region;
        final String k = key(run, hour, r);
        final Frame hit = cache.get(k);
        if (hit != null && !hit.bitmap.isRecycled()) {
            show(hit);
            return;
        }
        if (k.equals(pendingKey))
            return;
        pendingKey = k;
        fetch(run, 0, hour, r, k, ++generation);
    }

    private void fetch(final long tryRun, final int stepsBack, final int h, final GeoBounds r,
            final String k, final int mine) {
        status(temperature ? "Getting air temperature…" : "Getting the rain…");
        // Padded a cell's worth, but never past the antimeridian: -180.1 reads as
        // 179.9 to the filter, which then hands back a grid across the dateline
        // that the reader refuses (Alaska, 2026-11-12).
        final double west = Math.max(-180, r.getWest() - 0.1), east = Math.min(180, r.getEast() + 0.1);
        final double south = r.getSouth() - 0.1, north = r.getNorth() + 0.1;
        final String url = temperature
            ? NomadsTemperature.url(tryRun, h, west, south, east, north, level)
            : NomadsRain.url(tryRun, h, west, south, east, north);
        Http.getBytes(url,
                egress.userAgent(), new Http.BytesCallback() {
                    @Override
                    public void onSuccess(byte[] body) {
                        if (mine != generation || !on) {
                            if (k.equals(pendingKey)) pendingKey = null;
                            return;
                        }
                        if (!NomadsWind.looksLikeGrib(body)) {
                            if (stepsBack < RUN_STEPS_BACK) {
                                fetch(NomadsRain.previousRun(tryRun), stepsBack + 1,
                                    temperature ? h + 6 : h, r, k, mine);
                                return;
                            }
                            if (k.equals(pendingKey)) pendingKey = null;
                            status(temperature ? "No temperature forecast published yet" : "No rain forecast published yet");
                            return;
                        }
                        build(body, tryRun, h, r, k, mine);
                    }

                    @Override
                    public void onFailure(String error) {
                        if (k.equals(pendingKey)) pendingKey = null;
                        if (mine != generation)
                            return;
                        status(error);
                    }
                });
    }

    /** Decode and color on the worker, then come back to main to cache and show. */
    private void build(final byte[] body, final long answeredRun, final int h,
            final GeoBounds r, final String k, final int mine) {
        final ExecutorService w = worker;
        if (w == null)
            return;
        w.execute(new Runnable() {
            @Override
            public void run() {
                Frame frame = null;
                String error = null;
                try {
                    final RainGrid g = temperature ? NomadsTemperature.read(body) : NomadsRain.read(body);
                    frame = redraw(g);
                    Log.d(TAG, String.format(Locale.US, "+%d h run %d", h, answeredRun));
                } catch (IOException e) {
                    Log.w(TAG, temperature ? "temperature grid unreadable" : "rain grid unreadable", e);
                    error = e.getMessage();
                } catch (RuntimeException e) {
                    Log.e(TAG, temperature ? "temperature grid failed" : "rain grid failed", e);
                    error = temperature ? "air temperature could not be drawn" : "rain could not be drawn";
                }
                final Frame f = frame;
                final String err = error;
                mapView.post(new Runnable() {
                    @Override
                    public void run() {
                        if (k.equals(pendingKey))
                            pendingKey = null;
                        if (mine != generation || !on)
                            return;
                        if (f == null) {
                            status(err);
                            return;
                        }
                        if (answeredRun != run) {
                            setRun(answeredRun);
                            if (temperature)
                                hour = h;
                            if (listener != null)
                                listener.onFrames(labels(), hourIndex());
                        }
                        cache.put(key(run, h, r), f);
                        if (h == hour)
                            show(f);
                    }
                });
            }
        });
    }

    private void show(Frame f) {
        shown = f;
        layer.setImage(f.bitmap, f.bounds);
        status("");
        if (listener != null)
            listener.onFrameShown(hourIndex(), f.grid.validTime);
        relabel(f);
    }

    // ---- the bands, written on the map ----------------------------------------------

    /**
     * Write each band in view on its own patch of the picture, as a pill in the band's
     * color (see {@link BandLabels}). Main thread reads the view; the worker places
     * and writes. Called with every frame shown, which includes every settled pan or
     * zoom, so the labels follow the view.
     */
    private void relabel(final Frame f) {
        final ExecutorService w = worker;
        if (labels == null || w == null)
            return;
        GeoBounds view = viewBounds();
        final int width = mapView.getWidth(), height = mapView.getHeight();
        if (view == null || width <= 0 || height <= 0)
            return;
        // Across the antimeridian the view's east is under its west, or a side is
        // past 180; the picture is the whole ring then, so label the ring.
        if (view.getEast() < view.getWest() || view.getWest() < -180 || view.getEast() > 180)
            view = new GeoBounds(view.getNorth(), -180, view.getSouth(), 180);
        // Only the part of the view the picture covers.
        final double west = Math.max(view.getWest(), f.grid.west);
        final double east = Math.min(view.getEast(), f.grid.east);
        final double south = Math.max(view.getSouth(), f.grid.south);
        final double north = Math.min(view.getNorth(), f.grid.north);
        if (east <= west || north <= south) {
            clearLabels();
            return;
        }
        final float cellPx = LABEL_CELL_DP * density;
        final int cols = Math.max(1, Math.round(width * (float) ((east - west)
                / (view.getEast() - view.getWest())) / cellPx));
        final int rows = Math.max(1, Math.round(height * (float) ((north - south)
                / (view.getNorth() - view.getSouth())) / cellPx));
        final boolean fahrenheit = this.fahrenheit;
        final NomadsWind.Level level = this.level;
        final long validTime = f.grid.validTime;
        final int mine = generation;
        w.execute(new Runnable() {
            @Override
            public void run() {
                if (mine != generation || !on)
                    return;
                final List<BandLabels.Label> placed = BandLabels.place(f.grid, west, south,
                        east, north, cols, rows, fahrenheit);
                final List<AtmosphereFeatures.Drawn> drawn = new ArrayList<>();
                for (BandLabels.Label l : placed) {
                    final String text = BandLabels.text(l.floor, fahrenheit);
                    final int fill = NomadsTemperature.bandColor(
                            NomadsTemperature.toCelsius(l.floor + 1f, fahrenheit), fahrenheit,
                            NomadsTemperature.scaleShift(level));
                    final ZonePills.Pill pill = pills.pill(text, textOn(fill), fill);
                    if (pill == null)
                        continue;
                    final AttributeSet attrs = new AttributeSet();
                    attrs.setAttribute("Air temperature", text);
                    attrs.setAttribute("Height", level.surface ? "2 m above ground"
                            : level.label(!fahrenheit, true));
                    attrs.setAttribute("_details", "Air temperature " + text
                            + (level.surface ? " near the ground" : " at " + level.label(!fahrenheit, true))
                            + "\nNOAA GFS estimate for the model hour shown; not a station reading.");
                    drawn.add(new AtmosphereFeatures.Drawn(LABELS_NAME, text,
                            AtmosphereFeatures.point(l.lat, l.lon),
                            AtmosphereFeatures.icon(pill.uri, pill.width, pill.height), attrs));
                }
                if (mine != generation || !on)
                    return;
                labels.rewrite(drawn);
                Log.d(TAG, "labelled " + drawn.size() + " band patch(es) on a " + cols + "x" + rows
                        + " lattice, valid " + validTime);
            }
        });
    }

    /** Dark text on the light bands, white on the rest, so the number reads on its pill. */
    private static int textOn(int fill) {
        final double luminance = (0.299 * ((fill >> 16) & 255) + 0.587 * ((fill >> 8) & 255)
                + 0.114 * (fill & 255)) / 255.0;
        return luminance > 0.6 ? 0xFF1A1A1A : 0xFFFFFFFF;
    }

    private void clearLabels() {
        final ExecutorService w = worker;
        if (labels == null || w == null)
            return;
        w.execute(new Runnable() {
            @Override
            public void run() {
                labels.rewrite(Collections.<AtmosphereFeatures.Drawn>emptyList());
            }
        });
    }

    /** True when the view is wider than the model is asked for and only its middle is drawn. */
    public boolean isCropped() {
        return shown != null && cropped;
    }

    private void status(String s) {
        if (listener != null)
            listener.onStatus(s);
        if (!s.isEmpty())
            Log.d(TAG, s);
    }
}
