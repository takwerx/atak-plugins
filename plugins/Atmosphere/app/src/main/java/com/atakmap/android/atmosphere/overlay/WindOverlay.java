package com.atakmap.android.atmosphere.overlay;

import android.content.SharedPreferences;
import android.view.ViewGroup;

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
 * particles by {@link WindView}, a transparent view over the map drawn at screen resolution.
 * Lives for the plugin's life; the pane drives and reads it.
 *
 * <p>Region and cache rules are the radar's: the view padded by half its span,
 * clamped to the model's cover; grids cached per (model, run, hour, region); a map
 * move outside the region or a zoom past a third of it fetches again. The run is the
 * newest one certainly published; a response that is not GRIB (the filter answers
 * HTML for a file it does not have yet) steps back one run, up to four times.
 *
 * <p>Which model answers depends on how much ground is on screen: HRRR at 3 km close
 * in, RAP at 13 km for a region, GFS at a quarter degree for a continent and for
 * everywhere the other two do not reach ({@link NomadsWind#forView}). The scrubber
 * label names it, because the cells change size with the zoom.
 */
public final class WindOverlay {

    private static final String TAG = "AtmosphereWind";
    public static final String LAYER_ID = "wind";
    public static final String HOST = NomadsWind.HOST;

    private static final String PREF_ON = "weather.layer.wind.on";
    /**
     * Columns a projected grid is resampled onto. 160 is about 5 km across an HRRR box
     * and about 27 km across a RAP region, either side of those models' own cells; a
     * lat/lon model (GFS) is taken at its own resolution and never resampled.
     */
    private static final int GRID_NX = 160;
    private static final int PARTICLES = 2400;
    private static final long TICK_MS = 33L;
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
    private WindView view;
    private final Map<String, WindGrid> cache = new LinkedHashMap<String, WindGrid>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, WindGrid> eldest) {
            return size() > CACHE_GRIDS;
        }
    };

    private boolean started, on;
    /** Which model is answering; set from the view every time the region is chosen. */
    private NomadsWind.Model model = NomadsWind.Model.HRRR;
    private long run;
    private int hours;
    private int hour;
    private GeoBounds region;
    private WindGrid shown;
    private int generation;
    private String pendingKey;
    private Listener listener;

    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            if (!on || view == null)
                return;
            view.step();
            view.invalidate();
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

    private final Runnable redraw = new Runnable() {
        @Override
        public void run() {
            if (on && view != null)
                view.invalidate();
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
            // GL thread: the particles are on the ground, so a move only needs a
            // redraw now and a region check once it settles.
            mapView.post(redraw);
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
        // A transparent view inside the map view, after its GL surface, so it is
        // laid out to the map's full bounds and drawn over the surface's hole. The
        // map's parent is a horizontal LinearLayout: a sibling there was laid out as
        // the next item in the row, at zero width off the right edge (XCover,
        // 2026-09-21, seen in the view hierarchy dump).
        view = new WindView(mapView.getContext(), mapView, PARTICLES);
        view.setVisibility(android.view.View.GONE);
        mapView.addView(view, new ViewGroup.LayoutParams(
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
        if (view != null) {
            final ViewGroup parent = (ViewGroup) view.getParent();
            if (parent != null)
                parent.removeView(view);
            view = null;
        }
        cache.clear();
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
            run = model.latestRun(System.currentTimeMillis());
            hours = NomadsWind.HOURS;
            hour = Math.max(0, Math.min(hour, hours));
            if (listener != null)
                listener.onFrames(labels(), hour);
            if (view != null)
                view.setVisibility(android.view.View.VISIBLE);
            ensureRegion();
        } else {
            generation++;
            mapView.removeCallbacks(ticker);
            if (view != null) {
                view.setVisibility(android.view.View.GONE);
                view.setGrid(null);
            }
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

    private String key(long run, int hour, GeoBounds r) {
        return String.format(Locale.US, "%s|%d|%d|%.3f,%.3f,%.3f,%.3f", model.name(), run, hour,
                r.getWest(), r.getSouth(), r.getEast(), r.getNorth());
    }

    /** The model answering right now, for the scrubber label. */
    public String modelName() {
        return model.label;
    }

    private static boolean contains(GeoBounds outer, GeoBounds inner) {
        return inner.getWest() >= outer.getWest() && inner.getEast() <= outer.getEast()
                && inner.getSouth() >= outer.getSouth() && inner.getNorth() <= outer.getNorth();
    }

    private void ensureRegion() {
        if (!on)
            return;
        final GeoBounds bounds = mapView.getBounds();
        if (bounds == null)
            return;
        // On the globe the bounds come back NaN, or span the world, or cross the
        // antimeridian; any of those means "everything", which is a job for GFS.
        final boolean wholeWorld = Double.isNaN(bounds.getNorth()) || Double.isNaN(bounds.getSouth())
                || Double.isNaN(bounds.getEast()) || Double.isNaN(bounds.getWest())
                || bounds.getEast() <= bounds.getWest()
                || bounds.getEast() - bounds.getWest() >= 355;
        if (wholeWorld) {
            // The view draws each trail between the four projected corners of the map,
            // which is a quadrilateral on a flat map and not one on the globe, so the
            // particles would land nowhere. Say so rather than fetch half a megabyte of
            // wind and draw nothing (XCover, 2026-09-22).
            if (view != null)
                view.setGrid(null);
            status("Wind: zoom in to draw");
            region = null;
            return;
        }
        final double vw = bounds.getWest();
        final double ve = bounds.getEast();
        final double vs = bounds.getSouth();
        final double vn = bounds.getNorth();

        final NomadsWind.Model chosen = NomadsWind.forView(vw, vs, ve, vn);
        final GeoBounds clamped = new GeoBounds(
                Math.min(chosen.north, vn), Math.max(chosen.west, vw),
                Math.max(chosen.south, vs), Math.min(chosen.east, ve));
        if (clamped.getEast() <= clamped.getWest() || clamped.getNorth() <= clamped.getSouth()) {
            if (view != null)
                view.setGrid(null);
            status("Wind: outside " + chosen.label + " coverage");
            return;
        }

        boolean refetch = chosen != model || region == null || !contains(region, clamped);
        if (refetch && region != null && chosen == model
                && clamped.getEast() - clamped.getWest() > chosen.maxSpanLon) {
            // Wider than the box this model is asked for: refetch only when the view's
            // center leaves it, so a pan across a continent is not a request a second.
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
                // Each model runs on its own clock: HRRR and RAP hourly, GFS every six.
                // Carrying an hourly run over to GFS asks for a file that never exists,
                // and stepping back from it walks a chain of misses.
                model = chosen;
                run = chosen.latestRun(System.currentTimeMillis());
            }
            double w = clamped.getWest(), e = clamped.getEast();
            double s = clamped.getSouth(), n = clamped.getNorth();
            // A view wider than the model is worth asking for gets a box around its
            // center: the whole HRRR grid is 7 MB of wind per hour, and particles read
            // at that scale only near where the eye is anyway.
            if (e - w > chosen.maxSpanLon) {
                final double c = (e + w) / 2;
                w = c - chosen.maxSpanLon / 2;
                e = c + chosen.maxSpanLon / 2;
            }
            if (n - s > chosen.maxSpanLat) {
                final double c = (n + s) / 2;
                s = c - chosen.maxSpanLat / 2;
                n = c + chosen.maxSpanLat / 2;
            }
            // Pad by half the view, but never past the cap: padding it on top of the
            // cap is how a globe view asked GFS for 180 degrees of quarter-degree
            // cells, 700 KB a frame, when the cap says 120.
            final double padX = Math.min((e - w) * 0.5, Math.max(0, (chosen.maxSpanLon - (e - w)) / 2));
            final double padY = Math.min((n - s) * 0.5, Math.max(0, (chosen.maxSpanLat - (n - s)) / 2));
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
        final NomadsWind.Model asked = model;
        status("Wind: fetching " + asked.label + " +" + h + " h…");
        // The filter's box is the region grown by a cell, so the grid covers it fully.
        Http.getBytes(NomadsWind.url(asked, tryRun, h, r.getWest() - 0.05, r.getSouth() - 0.05,
                r.getEast() + 0.05, r.getNorth() + 0.05), egress.userAgent(), new Http.BytesCallback() {
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
                    status("Wind: no " + asked.label + " run available yet");
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
                    if (listener != null)
                        listener.onFrames(labels(), hour);
                }
                cache.put(key(run, h, r), grid);
                Log.d(TAG, String.format(Locale.US,
                        "%s grid +%d h run %d drawn %dx%d over %.2f,%.2f..%.2f,%.2f",
                        asked.label, h, run, grid.nx, grid.ny, grid.west, grid.south,
                        grid.east, grid.north));
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
        // The grid's own extent is the region grown by the filter's cut.
        region = new GeoBounds(grid.north, grid.west, grid.south, grid.east);
        if (view != null)
            view.setGrid(grid);
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
