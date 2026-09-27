package com.atakmap.android.atmosphere.overlay;

import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.view.ViewGroup;

import com.atakmap.android.atmosphere.compat.MapCompat;
import com.atakmap.android.atmosphere.net.EgressPolicy;
import com.atakmap.android.atmosphere.net.Http;
import com.atakmap.android.atmosphere.waves.NomadsWaves;
import com.atakmap.android.atmosphere.waves.NomadsWaves.Grid;
import com.atakmap.android.atmosphere.waves.WaveGrid;
import com.atakmap.android.atmosphere.wind.NomadsWind;
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
 * The wave forecast on the map: significant wave height as a colored field in the
 * WMO sea states, drawn as a raster on the radar's {@link RasterLayer}, with the
 * swell as moving crests over it ({@link SwellView}, the wind view's frame), one
 * forecast hour at a time on the shared time strip.
 * NOAA's wave model, from the same NOMADS filter the wind and smoke come through
 * ({@link NomadsWaves}). Lives for the plugin's life; the pane drives and reads it.
 *
 * <p>The smoke layer's shape: the view padded by half its span, grids cached per
 * (grid, run, hour, region), a move outside the region or a zoom past a third of it
 * fetching again, a non-GRIB answer stepping back a run, and the picture built off
 * the main thread. What differs is the grid ladder -- a sixth of a degree between
 * 15 S and 52.5 N, a quarter degree beyond, which is Alaska -- and that a view can
 * be wide: waves are read across an ocean, so the box goes to 40 degrees.
 */
public final class WaveOverlay {

    private static final String TAG = "AtmosphereWaves";
    public static final String LAYER_ID = "waves";
    public static final String HOST = NomadsWaves.HOST;

    private static final String PREF_ON = "weather.layer.waves.on";
    /**
     * The picture is eight pixels a cell, capped: the arrows need room, and a sixth
     * of a degree at eight pixels is a 20-degree box in 960.
     */
    private static final int MAX_PX = 1024;
    private static final int PX_PER_CELL = 8;
    private static final long MOVE_SETTLE_MS = 600L;
    private static final long SCRUB_SETTLE_MS = 180L;
    /** A frame is a grid and a bitmap of up to 2.3 MB; a dozen is a shift's worth of scrubbing. */
    private static final int CACHE_FRAMES = 12;
    private static final int RUN_STEPS_BACK = 4;
    /** Crests on the sea at once, and how often they move; a tenth of the wind's geometry. */
    private static final int CRESTS = 450;
    private static final long TICK_MS = 50L;

    public interface Listener {
        void onFrames(List<String> labels, int shown);
        void onFrameShown(int index, long validTime);
        void onStatus(String status);
    }

    /** What the cache holds: the grid for readings, the picture for the map. */
    private static final class Frame {
        final WaveGrid grid;
        final Bitmap bitmap;
        final GeoBounds bounds;

        Frame(WaveGrid grid, Bitmap bitmap) {
            this.grid = grid;
            this.bitmap = bitmap;
            this.bounds = new GeoBounds(grid.north, grid.west, grid.south, grid.east);
        }
    }

    private final MapView mapView;
    private final EgressPolicy egress;
    private final RasterLayer layer = new RasterLayer("Atmosphere waves");
    private final Map<String, Frame> cache = new LinkedHashMap<String, Frame>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Frame> eldest) {
            return size() > CACHE_FRAMES;
        }
    };
    private ExecutorService worker;
    /** The moving crests, a transparent view inside the map view like the wind's. */
    private SwellView crests;

    private boolean started, on;
    private Grid grid = Grid.FINE;
    private long run;
    private int hours = NomadsWaves.HOURS;
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

    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            if (!on || crests == null)
                return;
            crests.step();
            crests.invalidate();
            mapView.postDelayed(this, TICK_MS);
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

    public WaveOverlay(MapView mapView, EgressPolicy egress) {
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
                final Thread t = new Thread(r, "AtmosphereWaves");
                t.setDaemon(true);
                return t;
            }
        });
        GLRasterLayer.register();
        mapView.addLayer(MapView.RenderStack.MAP_SURFACE_OVERLAYS, layer);
        layer.setVisible(false);
        // Inside the map view, after its GL surface, the way the wind view is: a
        // sibling in the map's parent is laid out at zero width off the right edge.
        crests = new SwellView(mapView.getContext(), mapView, CRESTS);
        crests.setVisibility(android.view.View.GONE);
        mapView.addView(crests, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        mapView.addOnMapMovedListener(moved);
        final SharedPreferences p = MapCompat.prefs();
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
        mapView.removeCallbacks(ticker);
        generation++;
        layer.clear();
        mapView.removeLayer(MapView.RenderStack.MAP_SURFACE_OVERLAYS, layer);
        GLRasterLayer.unregister();
        if (crests != null) {
            final ViewGroup parent = (ViewGroup) crests.getParent();
            if (parent != null)
                parent.removeView(crests);
            crests = null;
        }
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
            setRun(NomadsWaves.latestRun(System.currentTimeMillis()));
            if (listener != null)
                listener.onFrames(labels(), hourIndex());
            layer.setVisible(true);
            if (crests != null)
                crests.setVisibility(android.view.View.VISIBLE);
            ensureRegion();
        } else {
            generation++;
            mapView.removeCallbacks(ticker);
            layer.setVisible(false);
            layer.clear();
            if (crests != null) {
                crests.setVisibility(android.view.View.GONE);
                crests.setGrid(null);
            }
            shown = null;
            status("");
        }
    }

    /** Adopt a run and work out where now falls in it; see the wind's. */
    private void setRun(long value) {
        run = value;
        hours = NomadsWaves.HOURS;
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

    /** A reading at a point, off the picture already on the map; NaNs where it does not cover. */
    public static final class Reading {
        public final float heightM, periodS, fromDeg, swellHeightM, swellPeriodS, swellFromDeg;

        Reading(float heightM, float periodS, float fromDeg, float swellHeightM,
                float swellPeriodS, float swellFromDeg) {
            this.heightM = heightM;
            this.periodS = periodS;
            this.fromDeg = fromDeg;
            this.swellHeightM = swellHeightM;
            this.swellPeriodS = swellPeriodS;
            this.swellFromDeg = swellFromDeg;
        }
    }

    /**
     * The waves at a point, or null when the picture on the map does not cover it. A
     * lookup in the grid already in memory, never a fetch.
     */
    public Reading readingAt(double lat, double lon) {
        final Frame f = shown;
        if (f == null || !f.grid.contains(lat, lon))
            return null;
        final WaveGrid g = f.grid;
        return new Reading(g.sample(g.height, lat, lon), g.sample(g.period, lat, lon),
                g.nearestAt(g.dir, lat, lon), g.sample(g.swellHeight, lat, lon),
                g.sample(g.swellPeriod, lat, lon), g.nearestAt(g.swellDir, lat, lon));
    }

    // ---- region and frames ---------------------------------------------------------

    private String key(long run, int hour, GeoBounds r) {
        return String.format(Locale.US, "%s|%d|%d|%.3f,%.3f,%.3f,%.3f", grid.name(),
                run, hour, r.getWest(), r.getSouth(), r.getEast(), r.getNorth());
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
            clearMap("Zoom in to see the waves");
            return;
        }
        final double vw = bounds.getWest(), ve = bounds.getEast();
        final double vs = bounds.getSouth(), vn = bounds.getNorth();
        final Grid chosen = NomadsWaves.forView(vs, vn);
        final GeoBounds clamped = new GeoBounds(
                Math.min(chosen.north, vn), vw, Math.max(chosen.south, vs), ve);
        if (clamped.getNorth() <= clamped.getSouth()) {
            clearMap("No wave forecast this far north");
            return;
        }
        final double maxLon = NomadsWaves.MAX_SPAN_LON;
        final double maxLat = NomadsWaves.MAX_SPAN_LAT;

        boolean refetch = chosen != grid || region == null || !contains(region, clamped);
        if (refetch && region != null && chosen == grid
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
            if (chosen != grid) {
                grid = chosen;
                setRun(NomadsWaves.latestRun(System.currentTimeMillis()));
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
            region = new GeoBounds(Math.min(chosen.north, n + padY), Math.max(-180, w - padX),
                    Math.max(chosen.south, s - padY), Math.min(180, e + padX));
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
        final Grid asked = grid;
        status("Getting the waves…");
        Http.getBytes(NomadsWaves.url(asked, tryRun, h, r.getWest() - 0.1,
                r.getSouth() - 0.1, r.getEast() + 0.1, r.getNorth() + 0.1),
                egress.userAgent(), new Http.BytesCallback() {
                    @Override
                    public void onSuccess(byte[] body) {
                        if (mine != generation || !on) {
                            if (k.equals(pendingKey)) pendingKey = null;
                            return;
                        }
                        if (!NomadsWind.looksLikeGrib(body)) {
                            if (stepsBack < RUN_STEPS_BACK) {
                                fetch(NomadsWaves.previousRun(tryRun), stepsBack + 1, h, r, k, mine);
                                return;
                            }
                            if (k.equals(pendingKey)) pendingKey = null;
                            status("No wave forecast published yet");
                            return;
                        }
                        build(body, asked, tryRun, h, r, k, mine);
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
    private void build(final byte[] body, final Grid asked, final long answeredRun, final int h,
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
                    final WaveGrid g = NomadsWaves.read(body);
                    final int pw = Math.max(64, Math.min(MAX_PX, g.nx * PX_PER_CELL));
                    final int ph = Math.max(64, Math.min(MAX_PX, g.ny * PX_PER_CELL));
                    final int[] px = NomadsWaves.render(g, pw, ph);
                    frame = new Frame(g, Bitmap.createBitmap(px, pw, ph,
                            Bitmap.Config.ARGB_8888));
                    Log.d(TAG, String.format(Locale.US,
                            "%s +%d h run %d drawn %dx%d over %.2f,%.2f..%.2f,%.2f, sea on %.1f%%",
                            asked.name(), h, answeredRun, pw, ph, g.west, g.south, g.east,
                            g.north, NomadsWaves.seaPercent(px)));
                } catch (IOException e) {
                    Log.w(TAG, "wave grid unreadable", e);
                    error = e.getMessage();
                } catch (RuntimeException e) {
                    Log.e(TAG, "wave grid failed", e);
                    error = "waves could not be drawn";
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
                    }
                });
            }
        });
    }

    private void show(Frame f) {
        shown = f;
        layer.setImage(f.bitmap, f.bounds);
        if (crests != null)
            crests.setGrid(f.grid);
        mapView.removeCallbacks(ticker);
        mapView.post(ticker);
        status("");
        if (listener != null)
            listener.onFrameShown(hourIndex(), f.grid.validTime);
    }

    /**
     * True when the view is wider than the model is asked for and only its middle is
     * drawn. The pane says so: a hard edge across the map would otherwise read as the
     * edge of the sea.
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
