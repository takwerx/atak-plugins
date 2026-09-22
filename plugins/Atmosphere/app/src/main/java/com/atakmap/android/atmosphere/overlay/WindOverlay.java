package com.atakmap.android.atmosphere.overlay;

import android.content.SharedPreferences;
import android.graphics.Bitmap;

import com.atakmap.android.atmosphere.compat.MapCompat;
import com.atakmap.android.atmosphere.net.EgressPolicy;
import com.atakmap.android.atmosphere.net.Http;
import com.atakmap.android.atmosphere.wind.NomadsWind;
import com.atakmap.android.atmosphere.wind.WindGrid;
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

/**
 * Animated surface wind on the map: HRRR 10 m wind from the NOMADS grib filter for
 * the map view, one forecast hour at a time picked on the scrubber, drawn as moving
 * particles by {@link WindAnimator} on the same RasterLayer path the radar uses.
 * Lives for the plugin's life; the pane drives and reads it.
 *
 * <p>Region and cache rules are the radar's: the view padded by half its span,
 * clamped to HRRR's cover; grids cached per (run, hour, region); a map move outside
 * the region or a zoom past a third of it fetches again. The run is the newest one
 * likely complete; a response that is not GRIB (the filter answers HTML for a file
 * it does not have yet) steps back one run, up to four times.
 */
public final class WindOverlay {

    private static final String TAG = "AtmosphereWind";
    public static final String LAYER_ID = "wind";
    public static final String HOST = NomadsWind.HOST;

    private static final String PREF_ON = "weather.layer.wind.on";
    private static final int GRID_NX = 96;
    private static final int FRAME_W = 512;
    private static final int PARTICLES = 1400;
    private static final long TICK_MS = 50L;
    private static final long MOVE_SETTLE_MS = 600L;
    private static final long SCRUB_SETTLE_MS = 180L;
    private static final int CACHE_GRIDS = 24;
    private static final int RUN_STEPS_BACK = 4;

    public interface Listener {
        void onFrames(List<String> labels, int shown);
        void onFrameShown(int index, long validTime);
        void onStatus(String status);
    }

    private final MapView mapView;
    private final EgressPolicy egress;
    private final RasterLayer layer = new RasterLayer("Atmosphere wind");
    private final Map<String, WindGrid> cache = new LinkedHashMap<String, WindGrid>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, WindGrid> eldest) {
            return size() > CACHE_GRIDS;
        }
    };

    private boolean started, on;
    private long run;
    private int hours;
    private int hour;
    private GeoBounds region;
    private WindAnimator animator;
    private WindGrid shown;
    private int generation;
    private String pendingKey;
    private Listener listener;

    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            if (!on || animator == null)
                return;
            final Bitmap frame = animator.tick();
            if (frame != null && region != null)
                layer.setImage(frame, region);
            mapView.postDelayed(this, TICK_MS);
        }
    };

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
        public void onMapMoved(AtakMapView view, boolean animate) {
            mapView.removeCallbacks(moveSettled);
            mapView.postDelayed(moveSettled, MOVE_SETTLE_MS);
        }
    };

    public WindOverlay(MapView mapView, EgressPolicy egress) {
        this.mapView = mapView;
        this.egress = egress;
    }

    public void setListener(Listener l) {
        listener = l;
        if (l != null) {
            l.onFrames(labels(), hour);
            l.onFrameShown(hour, shown == null ? 0 : shown.validTime);
        }
    }

    public void start() {
        if (started)
            return;
        started = true;
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
        mapView.removeCallbacks(ticker);
        generation++;
        layer.clear();
        mapView.removeLayer(MapView.RenderStack.MAP_SURFACE_OVERLAYS, layer);
        GLRasterLayer.unregister();
        cache.clear();
        animator = null;
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
            run = NomadsWind.latestRun(System.currentTimeMillis());
            hours = NomadsWind.hoursFor(run);
            hour = Math.max(0, Math.min(hour, hours));
            if (listener != null)
                listener.onFrames(labels(), hour);
            layer.setVisible(true);
            ensureRegion();
        } else {
            generation++;
            mapView.removeCallbacks(ticker);
            layer.setVisible(false);
            layer.clear();
            shown = null;
            status("");
        }
    }

    /** Forecast hour labels for the scrubber, one per hour of the run. */
    public List<String> labels() {
        final List<String> out = new ArrayList<>();
        for (int h = 0; h <= hours; h++)
            out.add("+" + h + " h");
        return out;
    }

    public int hourIndex() {
        return hour;
    }

    public long validTime(int h) {
        return run + h * 3_600_000L;
    }

    public void setHourIndex(int h) {
        h = Math.max(0, Math.min(h, hours));
        if (h == hour)
            return;
        hour = h;
        mapView.removeCallbacks(scrubSettled);
        if (region != null && cache.containsKey(key(run, hour, region)))
            showHour();
        else
            mapView.postDelayed(scrubSettled, SCRUB_SETTLE_MS);
    }

    // ---- region and grids ----------------------------------------------------------

    private static String key(long run, int hour, GeoBounds r) {
        return String.format(Locale.US, "%d|%d|%.3f,%.3f,%.3f,%.3f", run, hour,
                r.getWest(), r.getSouth(), r.getEast(), r.getNorth());
    }

    private static boolean contains(GeoBounds outer, GeoBounds inner) {
        return inner.getWest() >= outer.getWest() && inner.getEast() <= outer.getEast()
                && inner.getSouth() >= outer.getSouth() && inner.getNorth() <= outer.getNorth();
    }

    private void ensureRegion() {
        if (!on)
            return;
        final GeoBounds view = mapView.getBounds();
        if (view == null)
            return;
        final boolean wholeWorld = Double.isNaN(view.getNorth()) || Double.isNaN(view.getSouth())
                || Double.isNaN(view.getEast()) || Double.isNaN(view.getWest())
                || view.getEast() <= view.getWest()
                || view.getEast() - view.getWest() >= NomadsWind.EAST - NomadsWind.WEST;
        final GeoBounds clamped = wholeWorld
                ? new GeoBounds(NomadsWind.NORTH, NomadsWind.WEST, NomadsWind.SOUTH, NomadsWind.EAST)
                : new GeoBounds(
                        Math.min(NomadsWind.NORTH, view.getNorth()), Math.max(NomadsWind.WEST, view.getWest()),
                        Math.max(NomadsWind.SOUTH, view.getSouth()), Math.min(NomadsWind.EAST, view.getEast()));
        if (clamped.getEast() <= clamped.getWest() || clamped.getNorth() <= clamped.getSouth()) {
            layer.clear();
            status("Wind: outside HRRR coverage");
            return;
        }
        boolean refetch = region == null || !contains(region, clamped);
        if (!refetch) {
            final double viewSpan = clamped.getEast() - clamped.getWest();
            final double regionSpan = region.getEast() - region.getWest();
            refetch = viewSpan < regionSpan / 3.5 || viewSpan > regionSpan;
        }
        if (refetch) {
            double w = clamped.getWest(), e = clamped.getEast(), s = clamped.getSouth(), n = clamped.getNorth();
            final double padX = (e - w) * 0.5, padY = (n - s) * 0.5;
            region = new GeoBounds(Math.min(NomadsWind.NORTH, n + padY), Math.max(NomadsWind.WEST, w - padX),
                    Math.max(NomadsWind.SOUTH, s - padY), Math.min(NomadsWind.EAST, e + padX));
            cache.clear();
        }
        showHour();
    }

    private void showHour() {
        if (!on || region == null)
            return;
        final GeoBounds r = region;
        final String k = key(run, hour, r);
        final WindGrid hit = cache.get(k);
        if (hit != null) {
            show(hit, r);
            return;
        }
        if (k.equals(pendingKey))
            return;
        pendingKey = k;
        fetch(run, 0, hour, r, k, ++generation);
    }

    /** Ask the filter; a non-GRIB answer means the run is not out yet, so step back. */
    private void fetch(final long tryRun, final int stepsBack, final int h, final GeoBounds r,
            final String k, final int mine) {
        status("Wind: fetching HRRR +" + h + " h…");
        // The filter's box is the region grown by a cell, so the grid covers it fully.
        Http.getBytes(NomadsWind.url(tryRun, h, r.getWest() - 0.05, r.getSouth() - 0.05,
                r.getEast() + 0.05, r.getNorth() + 0.05), egress.userAgent(), new Http.BytesCallback() {
            @Override
            public void onSuccess(byte[] body) {
                if (mine != generation || !on) {
                    if (k.equals(pendingKey)) pendingKey = null;
                    return;
                }
                if (!NomadsWind.looksLikeGrib(body)) {
                    if (stepsBack < RUN_STEPS_BACK) {
                        fetch(tryRun - 3_600_000L, stepsBack + 1, h, r, k, mine);
                        return;
                    }
                    if (k.equals(pendingKey)) pendingKey = null;
                    status("Wind: no HRRR run available yet");
                    return;
                }
                if (k.equals(pendingKey)) pendingKey = null;
                final WindGrid grid;
                try {
                    grid = NomadsWind.read(body, GRID_NX);
                } catch (IOException e) {
                    Log.w(TAG, "wind grid unreadable", e);
                    status("Wind: " + e.getMessage());
                    return;
                }
                if (tryRun != run) {
                    // A newer run was not out: keep the one that answered.
                    run = tryRun;
                    hours = NomadsWind.hoursFor(run);
                    if (listener != null)
                        listener.onFrames(labels(), hour);
                }
                cache.put(key(run, h, r), grid);
                Log.d(TAG, String.format(Locale.US, "grid +%d h run %d drawn %dx%d over %.2f,%.2f..%.2f,%.2f",
                        h, run, grid.nx, grid.ny, grid.west, grid.south, grid.east, grid.north));
                if (h == hour)
                    show(grid, r);
            }

            @Override
            public void onFailure(String error) {
                if (k.equals(pendingKey)) pendingKey = null;
                if (mine != generation)
                    return;
                status("Wind: " + error);
            }
        });
    }

    private void show(WindGrid grid, GeoBounds r) {
        shown = grid;
        // The animator's bitmap covers the grid's own extent, which is the region
        // grown by the filter's cut; drape it over exactly that.
        region = new GeoBounds(grid.north, grid.west, grid.south, grid.east);
        final int h = Math.max(64, (int) Math.round(FRAME_W * (grid.north - grid.south) / (grid.east - grid.west)));
        if (animator == null)
            animator = new WindAnimator(FRAME_W, h, PARTICLES);
        else if (animator.current().getHeight() != h)
            animator = new WindAnimator(FRAME_W, h, PARTICLES);
        animator.setGrid(grid);
        mapView.removeCallbacks(ticker);
        mapView.post(ticker);
        if (listener != null)
            listener.onFrameShown(hour, grid.validTime);
        status("");
    }

    private void status(String s) {
        if (listener != null)
            listener.onStatus(s);
        if (!s.isEmpty())
            Log.d(TAG, s);
    }
}
