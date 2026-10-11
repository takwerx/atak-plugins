package com.atakmap.android.atmosphere.overlay;

import android.content.SharedPreferences;
import android.graphics.Bitmap;

import com.atakmap.android.atmosphere.compat.MapCompat;
import com.atakmap.android.atmosphere.net.EgressPolicy;
import com.atakmap.android.atmosphere.net.Http;
import com.atakmap.android.atmosphere.smoke.NomadsSmoke;
import com.atakmap.android.atmosphere.smoke.NomadsSmoke.Height;
import com.atakmap.android.atmosphere.smoke.SmokeGrid;
import com.atakmap.android.atmosphere.wind.NomadsWind;
import com.atakmap.android.atmosphere.wind.NomadsWind.Model;
import com.atakmap.android.maps.MapView;
import com.atakmap.coremap.log.Log;
import com.atakmap.coremap.maps.coords.GeoBounds;
import com.atakmap.map.AtakMapView;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

/**
 * The model's smoke forecast on the map: near the ground or through the whole sky, one
 * forecast hour at a time picked on the shared time strip, drawn as a colored raster
 * on the radar's {@link RasterLayer}. Lives for the plugin's life; the pane drives and
 * reads it.
 *
 * <p>The wind's rules, because it is the wind's data: the same NOMADS files, the same
 * runs and hours ({@link Model#forecastHours}), the view padded by half its span and
 * clamped to the model's cover, grids cached per (model, height, run, hour, region), a
 * move outside the region or a zoom past a third of it fetching again, and a non-GRIB
 * answer stepping back a run. What differs is that there is no model ladder -- HRRR
 * at every zoom, so the plume keeps its shape as the map zooms, and nothing outside
 * the lower 48 ({@link NomadsSmoke#forView}) -- and that the picture is built off the
 * main thread: a resample and a few hundred thousand colored pixels is not work for
 * the thread the pane answers on.
 */
public final class SmokeOverlay {

    private static final String TAG = "AtmosphereSmoke";
    public static final String LAYER_ID = "smoke";
    public static final String HOST = NomadsSmoke.HOST;

    private static final String PREF_ON = "weather.layer.smoke.on";
    private static final String PREF_HEIGHT = "weather.layer.smoke.height";
    /** The picture is twice the grid, capped: band edges follow the field, not the cells. */
    private static final int MAX_PX = 768;
    private static final long MOVE_SETTLE_MS = 600L;
    private static final long SCRUB_SETTLE_MS = 180L;
    /** A dozen frames is a shift's worth of scrubbing. */
    private static final int CACHE_FRAMES = 12;
    /**
     * And no more than this held: a frame is its grid and its picture, and a
     * multi-state frame is about 4 MB of each together where a fire's worth is a
     * fraction of one.
     */
    private static final long CACHE_BYTES = 40L << 20;
    private static final int RUN_STEPS_BACK = 4;

    public interface Listener {
        void onFrames(List<String> labels, int shown);
        void onFrameShown(int index, long validTime);
        void onStatus(String status);
    }

    /** What the cache holds: the grid for readings, the picture for the map. */
    private static final class Frame {
        final SmokeGrid grid;
        final Bitmap bitmap;
        final GeoBounds bounds;

        Frame(SmokeGrid grid, Bitmap bitmap) {
            this.grid = grid;
            this.bitmap = bitmap;
            this.bounds = new GeoBounds(grid.north, grid.west, grid.south, grid.east);
        }

        long bytes() {
            return grid.values.length * 4L + bitmap.getByteCount();
        }
    }

    private final MapView mapView;
    private final EgressPolicy egress;
    private final RasterLayer layer = new RasterLayer("Atmosphere smoke");
    private final Map<String, Frame> cache = new LinkedHashMap<String, Frame>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Frame> eldest) {
            return size() > CACHE_FRAMES;
        }
    };
    private ExecutorService worker;

    private boolean started, on;
    private Model model = Model.HRRR;
    private Height height = Height.GROUND;
    private long run;
    private int hours = NomadsWind.HOURS;
    private int hour;
    /** The first forecast hour worth offering, the one covering now; see the wind's. */
    private int firstHour;
    private GeoBounds region;
    /** True when the view was wider than the model is asked for, so only its middle is drawn. */
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
            // GL thread: post and coalesce, touch nothing here. The raster is on the
            // map surface, so it moves with the map by itself.
            mapView.removeCallbacks(moveSettled);
            mapView.postDelayed(moveSettled, MOVE_SETTLE_MS);
        }
    };

    public SmokeOverlay(MapView mapView, EgressPolicy egress) {
        this.mapView = mapView;
        this.egress = egress;
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
                final Thread t = new Thread(r, "AtmosphereSmoke");
                t.setDaemon(true);
                return t;
            }
        });
        GLRasterLayer.register();
        mapView.addLayer(MapView.RenderStack.MAP_SURFACE_OVERLAYS, layer);
        layer.setVisible(false);
        mapView.addOnMapMovedListener(moved);
        final SharedPreferences p = MapCompat.prefs();
        if (p != null) {
            final int saved = p.getInt(PREF_HEIGHT, 0);
            if (saved >= 0 && saved < Height.values().length)
                height = Height.values()[saved];
        }
        if (p != null && p.getBoolean(PREF_ON, false) && egress.isLayerEnabled(LAYER_ID))
            setOn(true);
    }

    public void stop() {
        if (!started)
            return;
        started = false;
        on = false;
        mapView.removeOnMapMovedListener(moved);
        mapView.removeCallbacks(moveSettled);
        mapView.removeCallbacks(scrubSettled);
        generation++;
        layer.clear();
        mapView.removeLayer(MapView.RenderStack.MAP_SURFACE_OVERLAYS, layer);
        GLRasterLayer.unregister();
        if (worker != null) {
            worker.shutdownNow();
            worker = null;
        }
        cache.clear();
        shown = null;
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
            setRun(model.latestRun(System.currentTimeMillis()));
            if (listener != null)
                listener.onFrames(labels(), hourIndex());
            layer.setVisible(true);
            ensureRegion();
        } else {
            generation++;
            layer.setVisible(false);
            layer.clear();
            shown = null;
            status("");
        }
    }

    /** Adopt a run and work out where now falls in it; see the wind's. */
    private void setRun(long value) {
        run = value;
        hours = model.forecastHours(run);
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

    public Height height() {
        return height;
    }

    public void setHeight(Height h) {
        if (h == null || h == height)
            return;
        height = h;
        final SharedPreferences p = MapCompat.prefs();
        if (p != null)
            p.edit().putInt(PREF_HEIGHT, h.ordinal()).apply();
        cache.clear();
        region = null;
        generation++;
        pendingKey = null;
        ensureRegion();
    }

    /**
     * The smoke at a point in the height's unit, or NaN when the picture on the map
     * does not cover it. A lookup in the grid already in memory, never a fetch.
     */
    public float readingAt(double lat, double lon) {
        final Frame f = shown;
        if (f == null || !f.grid.contains(lat, lon))
            return Float.NaN;
        return f.grid.sample(lat, lon);
    }

    // ---- region and frames ---------------------------------------------------------

    private String key(long run, int hour, GeoBounds r) {
        return String.format(Locale.US, "%s|%s|%d|%d|%.3f,%.3f,%.3f,%.3f", model.name(),
                height.name(), run, hour, r.getWest(), r.getSouth(), r.getEast(), r.getNorth());
    }

    private static boolean contains(GeoBounds outer, GeoBounds inner) {
        return inner.getWest() >= outer.getWest() && inner.getEast() <= outer.getEast()
                && inner.getSouth() >= outer.getSouth() && inner.getNorth() <= outer.getNorth();
    }

    private void clearMap(String why) {
        layer.clear();
        shown = null;
        region = null;
        status(why);
    }

    private void ensureRegion() {
        if (!on)
            return;
        final GeoBounds bounds = mapView.getBounds();
        if (bounds == null)
            return;
        final boolean wholeWorld = Double.isNaN(bounds.getNorth()) || Double.isNaN(bounds.getSouth())
                || Double.isNaN(bounds.getEast()) || Double.isNaN(bounds.getWest())
                || bounds.getEast() <= bounds.getWest()
                || bounds.getEast() - bounds.getWest() >= 355;
        if (wholeWorld) {
            clearMap("Zoom in to see the smoke");
            return;
        }
        final double vw = bounds.getWest(), ve = bounds.getEast();
        final double vs = bounds.getSouth(), vn = bounds.getNorth();
        final Model chosen = NomadsSmoke.forView(vw, vs, ve, vn, height);
        if (chosen == null) {
            clearMap("No smoke forecast outside the lower 48");
            return;
        }
        final GeoBounds clamped = new GeoBounds(
                Math.min(chosen.north, vn), Math.max(chosen.west, vw),
                Math.max(chosen.south, vs), Math.min(chosen.east, ve));
        if (clamped.getEast() <= clamped.getWest() || clamped.getNorth() <= clamped.getSouth()) {
            clearMap("No smoke forecast outside the lower 48");
            return;
        }
        final double maxLon = NomadsSmoke.maxSpanLon(chosen);
        final double maxLat = NomadsSmoke.maxSpanLat(chosen);

        boolean refetch = chosen != model || region == null || !contains(region, clamped);
        if (refetch && region != null && chosen == model
                && clamped.getEast() - clamped.getWest() > maxLon) {
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
            refetch = viewSpan < regionSpan / 3.5 || viewSpan > regionSpan * 1.6;
        }
        if (refetch) {
            if (chosen != model) {
                model = chosen;
                setRun(chosen.latestRun(System.currentTimeMillis()));
                if (listener != null)
                    listener.onFrames(labels(), hourIndex());
            }
            double w = clamped.getWest(), e = clamped.getEast();
            double s = clamped.getSouth(), n = clamped.getNorth();
            cropped = false;
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
            region = new GeoBounds(Math.min(chosen.north, n + padY), Math.max(chosen.west, w - padX),
                    Math.max(chosen.south, s - padY), Math.min(chosen.east, e + padX));
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
        final Model asked = model;
        final Height askedHeight = height;
        status("Getting the smoke…");
        Http.getBytes(NomadsSmoke.url(asked, askedHeight, tryRun, h, r.getWest() - 0.05,
                r.getSouth() - 0.05, r.getEast() + 0.05, r.getNorth() + 0.05),
                egress.userAgent(), new Http.BytesCallback() {
                    @Override
                    public void onSuccess(byte[] body) {
                        if (mine != generation || !on) {
                            if (k.equals(pendingKey)) pendingKey = null;
                            return;
                        }
                        if (!NomadsWind.looksLikeGrib(body)) {
                            if (stepsBack < RUN_STEPS_BACK) {
                                fetch(asked.previousRun(tryRun), stepsBack + 1, h, r, k, mine);
                                return;
                            }
                            if (k.equals(pendingKey)) pendingKey = null;
                            status("No smoke forecast published yet");
                            return;
                        }
                        build(body, asked, askedHeight, tryRun, h, r, k, mine);
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
    private void build(final byte[] body, final Model asked, final Height askedHeight,
            final long answeredRun, final int h, final GeoBounds r, final String k,
            final int mine) {
        final ExecutorService w = worker;
        if (w == null)
            return;
        w.execute(new Runnable() {
            @Override
            public void run() {
                Frame frame = null;
                String error = null;
                try {
                    final SmokeGrid grid = NomadsSmoke.read(body, askedHeight);
                    final int pw = Math.min(MAX_PX, grid.nx * 2);
                    final int ph = Math.min(MAX_PX, grid.ny * 2);
                    final int[] px = NomadsSmoke.render(grid, askedHeight, pw, ph);
                    frame = new Frame(grid, Bitmap.createBitmap(px, pw, ph,
                            Bitmap.Config.ARGB_8888));
                    Log.d(TAG, String.format(Locale.US,
                            "%s %s +%d h run %d drawn %dx%d over %.2f,%.2f..%.2f,%.2f, smoke on %.1f%%",
                            asked.label, askedHeight.name(), h, answeredRun, pw, ph, grid.west,
                            grid.south, grid.east, grid.north, NomadsSmoke.smokyPercent(px)));
                } catch (IOException e) {
                    Log.w(TAG, "smoke grid unreadable", e);
                    error = e.getMessage();
                } catch (RuntimeException e) {
                    Log.e(TAG, "smoke grid failed", e);
                    error = "smoke could not be drawn";
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
                            if (listener != null)
                                listener.onFrames(labels(), hourIndex());
                        }
                        cache.put(key(run, h, r), f);
                        if (h == hour)
                            show(f);
                        trimCache();
                    }
                });
            }
        });
    }

    /** Drop the least recently shown frames past {@link #CACHE_BYTES}, never the one on the map. */
    private void trimCache() {
        long total = 0;
        for (Frame f : cache.values())
            total += f.bytes();
        final java.util.Iterator<Frame> it = cache.values().iterator();
        while (total > CACHE_BYTES && it.hasNext()) {
            final Frame f = it.next();
            if (f == shown)
                continue;
            total -= f.bytes();
            it.remove();
        }
    }

    private void show(Frame f) {
        shown = f;
        layer.setImage(f.bitmap, f.bounds);
        status("");
        if (listener != null)
            listener.onFrameShown(hourIndex(), f.grid.validTime);
    }

    /**
     * True when the view is wider than the model is asked for and only its middle is
     * drawn. The pane says so: a hard edge across the map would otherwise read as the
     * edge of the smoke.
     */
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
