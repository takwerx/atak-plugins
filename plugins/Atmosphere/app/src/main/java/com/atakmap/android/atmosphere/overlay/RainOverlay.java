package com.atakmap.android.atmosphere.overlay;

import android.content.SharedPreferences;
import android.graphics.Bitmap;

import com.atakmap.android.atmosphere.compat.MapCompat;
import com.atakmap.android.atmosphere.net.EgressPolicy;
import com.atakmap.android.atmosphere.net.Http;
import com.atakmap.android.atmosphere.rain.NomadsRain;
import com.atakmap.android.atmosphere.rain.RainGrid;
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
    public static final String HOST = NomadsRain.HOST;

    private static final String PREF_ON = "weather.layer.rain.on";
    /** Eight pixels a quarter-degree cell: a 30-degree box is 960 across. */
    private static final int MAX_PX = 1024;
    private static final int PX_PER_CELL = 8;
    private static final long MOVE_SETTLE_MS = 600L;
    private static final long SCRUB_SETTLE_MS = 180L;
    private static final int CACHE_FRAMES = 12;
    private static final int RUN_STEPS_BACK = 4;

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
    private final RasterLayer layer = new RasterLayer("Atmosphere rain");
    private final Map<String, Frame> cache = new LinkedHashMap<String, Frame>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Frame> eldest) {
            return size() > CACHE_FRAMES;
        }
    };
    private ExecutorService worker;

    private boolean started, on;
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
                final Thread t = new Thread(r, "AtmosphereRain");
                t.setDaemon(true);
                return t;
            }
        });
        GLRasterLayer.register();
        mapView.addLayer(MapView.RenderStack.MAP_SURFACE_OVERLAYS, layer);
        layer.setVisible(false);
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
            setRun(NomadsRain.latestRun(System.currentTimeMillis()));
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
        return String.format(Locale.US, "%d|%d|%.3f,%.3f,%.3f,%.3f", run, hour,
                r.getWest(), r.getSouth(), r.getEast(), r.getNorth());
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
            clearMap("Zoom in to see the rain");
            return;
        }
        final GeoBounds clamped = new GeoBounds(Math.min(90, bounds.getNorth()), bounds.getWest(),
                Math.max(-90, bounds.getSouth()), bounds.getEast());
        final double maxLon = NomadsRain.MAX_SPAN_LON;
        final double maxLat = NomadsRain.MAX_SPAN_LAT;

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
            refetch = viewSpan < regionSpan / 3.5 || viewSpan > regionSpan * 1.6;
        }
        if (refetch) {
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
        status("Getting the rain…");
        Http.getBytes(NomadsRain.url(tryRun, h, r.getWest() - 0.1, r.getSouth() - 0.1,
                r.getEast() + 0.1, r.getNorth() + 0.1),
                egress.userAgent(), new Http.BytesCallback() {
                    @Override
                    public void onSuccess(byte[] body) {
                        if (mine != generation || !on) {
                            if (k.equals(pendingKey)) pendingKey = null;
                            return;
                        }
                        if (!NomadsWind.looksLikeGrib(body)) {
                            if (stepsBack < RUN_STEPS_BACK) {
                                fetch(NomadsRain.previousRun(tryRun), stepsBack + 1, h, r, k, mine);
                                return;
                            }
                            if (k.equals(pendingKey)) pendingKey = null;
                            status("No rain forecast published yet");
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
                    final RainGrid g = NomadsRain.read(body);
                    final int pw = Math.max(64, Math.min(MAX_PX, g.nx * PX_PER_CELL));
                    final int ph = Math.max(64, Math.min(MAX_PX, g.ny * PX_PER_CELL));
                    final int[] px = NomadsRain.render(g, pw, ph);
                    frame = new Frame(g, Bitmap.createBitmap(px, pw, ph,
                            Bitmap.Config.ARGB_8888));
                    Log.d(TAG, String.format(Locale.US,
                            "+%d h run %d drawn %dx%d over %.2f,%.2f..%.2f,%.2f, rain on %.1f%%",
                            h, answeredRun, pw, ph, g.west, g.south, g.east, g.north,
                            NomadsRain.rainPercent(px)));
                } catch (IOException e) {
                    Log.w(TAG, "rain grid unreadable", e);
                    error = e.getMessage();
                } catch (RuntimeException e) {
                    Log.e(TAG, "rain grid failed", e);
                    error = "rain could not be drawn";
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
        status("");
        if (listener != null)
            listener.onFrameShown(hourIndex(), f.grid.validTime);
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
