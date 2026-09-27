package com.atakmap.android.atmosphere.overlay;

import android.content.SharedPreferences;
import android.graphics.Bitmap;

import com.atakmap.android.atmosphere.compat.MapCompat;
import com.atakmap.android.atmosphere.net.EgressPolicy;
import com.atakmap.android.atmosphere.net.Http;
import com.atakmap.android.maps.MapView;
import com.atakmap.coremap.log.Log;
import com.atakmap.coremap.maps.coords.GeoBounds;
import com.atakmap.map.AtakMapView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * NWS radar on the map: the base reflectivity composites from the NWS GeoServer, the
 * same tiles radar.weather.gov draws, one frame at a time picked by timestamp. NWS
 * publishes five mosaics -- the lower 48, the Caribbean, Hawaii, Alaska and Guam --
 * each with its own coverage box and its own frame list; the layer draws the one
 * that covers most of the view, and switches when the map moves to another
 * (operator, 2026-09-27: radar "further out into the Caribbean and Mexico, Hawaii").
 * The lower-48 mosaic reaches 20 N, so northern Mexico is in it as far as the
 * border radars see. Canada's radar comes from Environment Canada's GeoMet
 * service under Canada's open-government terms (Fees None, Access Constraints
 * None, read 2026-09-27): six-minute frames for the last three hours, given as
 * a start/end/period that {@link #parseTimes} expands.
 *
 * <p>Lives for the plugin's life, never inside the pane: the pane comes and goes with
 * the toolbar button and the radar has to stay up. The pane only drives and reads it.
 *
 * <h3>What it fetches</h3>
 *
 * One image per frame covering the map view padded by half its span on each side,
 * clamped to the layer's CONUS extent, at most 1024 px on the long side, in CRS:84 so
 * the image maps straight onto a lon/lat quad. The frame list comes from the WMS
 * capabilities (about 60 frames over two hours, every two minutes) and is re-read every
 * few minutes while the radar is on. Frames are cached per region, a bounded LRU, so a
 * scrub back and forth is free once fetched. A map move outside the fetched region,
 * or a zoom past a third of it or beyond it, fetches again; small pans do nothing.
 *
 * <h3>Threads</h3>
 *
 * {@code onMapMoved} arrives on the GL thread and touches nothing: it posts to main and
 * coalesces. Every fetch callback lands on main. GL uploads are the renderer's.
 */
public final class RadarOverlay {

    private static final String TAG = "AtmosphereRadar";

    public static final String LAYER_ID = "radar";
    public static final String HOST = "opengeo.ncep.noaa.gov";
    /** One radar mosaic: where its WMS is, the layer, and the box its radars cover. */
    static final class Mosaic {
        final String id, base, wmsLayer, capabilities;
        final double west, east, south, north;

        /** An NWS mosaic, by its GeoServer workspace. */
        Mosaic(String id, double west, double east, double south, double north) {
            this(id, "https://" + HOST + "/geoserver/" + id + "/" + id + "_bref_qcd/ows",
                    id + "_bref_qcd", null, west, east, south, north);
        }

        /**
         * Any WMS. {@code capabilities} is the full capabilities URL when the server
         * wants a layer named to keep the answer small (GeoMet's whole document is
         * megabytes); null asks {@code base} the ordinary way.
         */
        Mosaic(String id, String base, String wmsLayer, String capabilities, double west,
                double east, double south, double north) {
            this.id = id;
            this.base = base;
            this.wmsLayer = wmsLayer;
            this.capabilities = capabilities != null ? capabilities
                    : base + "?service=WMS&version=1.3.0&request=GetCapabilities";
            this.west = west;
            this.east = east;
            this.south = south;
            this.north = north;
        }

        /** How much of a view this mosaic covers, in square degrees; 0 when none. */
        double overlap(GeoBounds v) {
            final double w = Math.max(west, v.getWest()), e = Math.min(east, v.getEast());
            final double s = Math.max(south, v.getSouth()), n = Math.min(north, v.getNorth());
            return e > w && n > s ? (e - w) * (n - s) : 0;
        }
    }

    /**
     * The mosaics, boxes read from each one's capabilities on 2026-09-27. The lower
     * 48 first: it wins a tie, and it is what a whole-world view gets.
     */
    public static final String CANADA_HOST = "geo.weather.gc.ca";
    /**
     * Canada's box is drawn from the 49th parallel, not from the service's nominal
     * extent (which reaches 17 N and would out-vote the lower 48 everywhere): a
     * view mostly north of the border picks it, one mostly south keeps NWS, which
     * folds the border radars in anyway. Alaska is listed first so the Yukon
     * corner they share goes to Alaska.
     */
    static final Mosaic[] MOSAICS = {
            new Mosaic("conus", -130, -60, 20, 55),
            new Mosaic("carib", -90, -60, 10, 25),
            new Mosaic("hawaii", -164, -151, 15, 26),
            new Mosaic("alaska", -176, -126, 50, 72),
            new Mosaic("guam", 140, 150, 9, 18),
            new Mosaic("canada", "https://" + CANADA_HOST + "/geomet", "RADAR_1KM_RRAI",
                    "https://" + CANADA_HOST + "/geomet?service=WMS&version=1.3.0"
                            + "&request=GetCapabilities&layer=RADAR_1KM_RRAI",
                    -141, -52, 49, 62) };

    /** The mosaic whose box covers most of the view; the lower 48 when none does. */
    static Mosaic mosaicFor(GeoBounds view) {
        Mosaic best = MOSAICS[0];
        double bestOverlap = 0;
        for (Mosaic m : MOSAICS) {
            final double o = m.overlap(view);
            if (o > bestOverlap) {
                best = m;
                bestOverlap = o;
            }
        }
        return best;
    }

    private static final String PREF_ON = "weather.layer.radar.on";
    private static final int MAX_PX = 1024;
    private static final int CACHE_FRAMES = 24;
    private static final long CAPS_REFRESH_MS = 3 * 60 * 1000L;
    private static final long MOVE_SETTLE_MS = 600L;
    /** A scrub across uncached frames fetches once it pauses, not once per frame. */
    private static final long SCRUB_SETTLE_MS = 180L;

    public interface Listener {
        /** The frame list changed; times are ISO 8601 UTC, oldest first. */
        void onFrames(List<String> times, int shown);
        /** A frame is on the map (or none, index -1). */
        void onFrameShown(int index, String time);
        /** Something the operator should read: fetching, out of coverage, an error. */
        void onStatus(String status);
    }

    private final MapView mapView;
    private final EgressPolicy egress;
    private final RasterLayer layer = new RasterLayer("Atmosphere radar");
    private final Map<String, Bitmap> cache = new LinkedHashMap<String, Bitmap>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Bitmap> eldest) {
            return size() > CACHE_FRAMES;
        }
    };

    private boolean started;
    private boolean on;
    /** The mosaic being drawn; frames, region and cache all belong to it. */
    private Mosaic mosaic = MOSAICS[0];
    private List<String> frames = new ArrayList<>();
    private int index = -1;
    /** True while the operator has not scrubbed away from the newest frame. */
    private boolean followLatest = true;
    private GeoBounds region;
    private long capsFetchedAt;
    /** A capabilities read is in flight; a second request would only cancel it. */
    private boolean capsInFlight;
    /** The image being fetched; asking for the same one again is a no-op. */
    private String pendingKey;
    private int generation;
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
                showFrame();
        }
    };

    private final AtakMapView.OnMapMovedListener moved = new AtakMapView.OnMapMovedListener() {
        @Override
        public void onMapMoved(AtakMapView view, boolean animate) {
            // GL thread: post and coalesce, touch nothing here.
            mapView.removeCallbacks(moveSettled);
            mapView.postDelayed(moveSettled, MOVE_SETTLE_MS);
        }
    };

    public RadarOverlay(MapView mapView, EgressPolicy egress) {
        this.mapView = mapView;
        this.egress = egress;
    }

    public void setListener(Listener l) {
        listener = l;
        if (l != null) {
            l.onFrames(frames(), index);
            l.onFrameShown(index, index < 0 ? null : frames.get(index));
        }
    }

    /** Puts the layer on the map and restores the saved state. Once, at plugin start. */
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

    /** Takes everything back off. When the plugin stops or is reloaded. */
    public void stop() {
        if (!started)
            return;
        started = false;
        mapView.removeOnMapMovedListener(moved);
        mapView.removeCallbacks(moveSettled);
        mapView.removeCallbacks(scrubSettled);
        generation++;
        layer.clear();
        mapView.removeLayer(MapView.RenderStack.MAP_SURFACE_OVERLAYS, layer);
        GLRasterLayer.unregister();
        cache.clear();
    }

    public boolean isOn() {
        return on;
    }

    /** Turn the radar on or off. The caller has cleared the egress gate first. */
    public void setOn(boolean value) {
        if (!started || on == value)
            return;
        on = value;
        final SharedPreferences p = MapCompat.prefs();
        if (p != null)
            p.edit().putBoolean(PREF_ON, value).apply();
        if (value) {
            layer.setVisible(true);
            refreshFrames(true);
        } else {
            generation++;
            layer.setVisible(false);
            layer.clear();
            status("");
        }
    }

    public List<String> frames() {
        return Collections.unmodifiableList(new ArrayList<>(frames));
    }

    public int frameIndex() {
        return index;
    }

    /** Scrub to a frame. The last index means "latest", and new frames follow it. */
    public void setFrameIndex(int i) {
        if (frames.isEmpty())
            return;
        i = Math.max(0, Math.min(i, frames.size() - 1));
        followLatest = i == frames.size() - 1;
        if (i == index)
            return;
        index = i;
        // A cached frame shows at once, so a scrub over fetched frames plays live;
        // an uncached one waits for the finger to pause, so a drag across the strip
        // does not fire a request per frame it crosses.
        final String time = frames.get(index);
        mapView.removeCallbacks(scrubSettled);
        if (region != null && cache.containsKey(cacheKey(time)))
            showFrame();
        else
            mapView.postDelayed(scrubSettled, SCRUB_SETTLE_MS);
    }

    // ---- frames ----------------------------------------------------------------

    /** Re-read the frame list from the capabilities if it is stale, then show. */
    public void refreshFrames(boolean force) {
        if (!on)
            return;
        final long now = System.currentTimeMillis();
        if (!force && now - capsFetchedAt < CAPS_REFRESH_MS && !frames.isEmpty()) {
            ensureRegion();
            return;
        }
        if (capsInFlight)
            return;
        capsInFlight = true;
        final int mine = ++generation;
        status("Getting radar\u2026");
        final Mosaic asked = mosaic;
        Http.get(asked.capabilities, egress.userAgent(), null, new Http.Callback() {
                    @Override
                    public void onSuccess(String body) {
                        capsInFlight = false;
                        if (mine != generation || asked != mosaic)
                            return;
                        final List<String> times = parseTimes(body);
                        if (times.isEmpty()) {
                            status("No radar frames available");
                            return;
                        }
                        capsFetchedAt = System.currentTimeMillis();
                        final String shownTime = index >= 0 && index < frames.size()
                                ? frames.get(index) : null;
                        frames = times;
                        if (followLatest || shownTime == null || !times.contains(shownTime))
                            index = times.size() - 1;
                        else
                            index = times.indexOf(shownTime);
                        if (listener != null)
                            listener.onFrames(frames(), index);
                        ensureRegion();
                    }

                    @Override
                    public void onFailure(String error) {
                        capsInFlight = false;
                        if (mine != generation)
                            return;
                        status(error);
                    }
                });
    }

    /** The WMS time dimension: a comma list of ISO times, or a start/end/period. */
    static List<String> parseTimes(String capabilities) {
        final List<String> out = new ArrayList<>();
        if (capabilities == null)
            return out;
        final Matcher m = Pattern.compile("<Dimension[^>]*name=\"time\"[^>]*>([^<]*)</Dimension>")
                .matcher(capabilities);
        if (!m.find())
            return out;
        for (String t : m.group(1).trim().split(",")) {
            t = t.trim();
            if (t.isEmpty())
                continue;
            if (t.contains("/")) {
                // start/end/period, the way GeoMet gives three hours of six-minute
                // frames. Expanded into the frames when the period is minutes and
                // the count is a scrubber's worth; otherwise the two ends.
                final String[] parts = t.split("/");
                if (parts.length >= 2)
                    out.addAll(expand(parts[0].trim(), parts[1].trim(),
                            parts.length >= 3 ? parts[2].trim() : ""));
                continue;
            }
            out.add(t);
        }
        return out;
    }

    /** Every frame from start to end at an ISO period like PT6M; the ends alone otherwise. */
    static List<String> expand(String start, String end, String period) {
        final List<String> out = new ArrayList<>();
        final java.util.regex.Matcher pm = Pattern.compile("PT(?:(\\d+)H)?(?:(\\d+)M)?").matcher(period);
        final java.text.SimpleDateFormat f = new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US);
        f.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
        try {
            final long a = f.parse(start).getTime(), b = f.parse(end).getTime();
            long step = 0;
            if (pm.matches()) {
                if (pm.group(1) != null) step += Long.parseLong(pm.group(1)) * 3_600_000L;
                if (pm.group(2) != null) step += Long.parseLong(pm.group(2)) * 60_000L;
            }
            if (step > 0 && b > a && (b - a) / step <= 120) {
                for (long t = a; t <= b; t += step)
                    out.add(f.format(new java.util.Date(t)));
                return out;
            }
        } catch (java.text.ParseException | NumberFormatException ignored) {
            // not the shape expected; the ends below
        }
        out.add(start);
        out.add(end);
        return out;
    }

    // ---- region and images -------------------------------------------------------

    /** The region the images cover: the view padded by half its span, clamped to the mosaic. */
    private static GeoBounds regionFor(Mosaic m, GeoBounds view) {
        double w = view.getWest(), e = view.getEast(), s = view.getSouth(), n = view.getNorth();
        final double padX = (e - w) * 0.5, padY = (n - s) * 0.5;
        w = Math.max(m.west, w - padX);
        e = Math.min(m.east, e + padX);
        s = Math.max(m.south, s - padY);
        n = Math.min(m.north, n + padY);
        if (e - w <= 0 || n - s <= 0)
            return null;
        return new GeoBounds(n, w, s, e);
    }

    private static boolean contains(GeoBounds outer, GeoBounds inner) {
        return inner.getWest() >= outer.getWest() && inner.getEast() <= outer.getEast()
                && inner.getSouth() >= outer.getSouth() && inner.getNorth() <= outer.getNorth();
    }

    /** Fetch again when the view left the region or its scale changed a lot. */
    private void ensureRegion() {
        if (!on)
            return;
        final GeoBounds view = mapView.getBounds();
        if (view == null)
            return;
        // On the globe the bounds come back NaN, or span the world, or cross the
        // antimeridian; any of those means "everything", which is the lower 48.
        final boolean wholeWorld = Double.isNaN(view.getNorth()) || Double.isNaN(view.getSouth())
                || Double.isNaN(view.getEast()) || Double.isNaN(view.getWest())
                || view.getEast() <= view.getWest()
                || view.getEast() - view.getWest() >= 180;
        // The mosaic follows the map. A change drops the frames, the region and the
        // cache, which are all that mosaic's, and reads the new one's frame list; the
        // capabilities answer calls back here.
        final Mosaic wanted = wholeWorld ? MOSAICS[0] : mosaicFor(view);
        if (wanted != mosaic) {
            Log.d(TAG, "mosaic " + mosaic.id + " -> " + wanted.id);
            mosaic = wanted;
            frames = new ArrayList<>();
            index = -1;
            followLatest = true;
            region = null;
            cache.clear();
            layer.clear();
            capsFetchedAt = 0;
            capsInFlight = false;
            pendingKey = null;
            if (listener != null)
                listener.onFrames(frames(), index);
            refreshFrames(true);
            return;
        }
        if (frames.isEmpty())
            return;
        final Mosaic m = mosaic;
        // Clamp the view itself first, so a coast view does not pad out to sea.
        final GeoBounds clampedView = wholeWorld
                ? new GeoBounds(m.north, m.west, m.south, m.east)
                : new GeoBounds(
                        Math.min(m.north, view.getNorth()), Math.max(m.west, view.getWest()),
                        Math.max(m.south, view.getSouth()), Math.min(m.east, view.getEast()));
        if (clampedView.getEast() <= clampedView.getWest()
                || clampedView.getNorth() <= clampedView.getSouth()) {
            layer.clear();
            status("No radar for this area");
            return;
        }
        boolean refetch = region == null || !contains(region, clampedView);
        if (!refetch) {
            final double viewSpan = clampedView.getEast() - clampedView.getWest();
            final double regionSpan = region.getEast() - region.getWest();
            refetch = viewSpan < regionSpan / 3.5 || viewSpan > regionSpan;
        }
        if (refetch) {
            region = regionFor(m, clampedView);
            cache.clear();
        }
        showFrame();
    }

    private String cacheKey(String time) {
        return String.format(Locale.US, "%s|%s|%.3f,%.3f,%.3f,%.3f", mosaic.id, time,
                region.getWest(), region.getSouth(), region.getEast(), region.getNorth());
    }

    private void showFrame() {
        if (!on || region == null || index < 0 || index >= frames.size())
            return;
        final String time = frames.get(index);
        final GeoBounds r = region;
        final String key = cacheKey(time);
        final Bitmap hit = cache.get(key);
        if (hit != null && !hit.isRecycled()) {
            layer.setImage(hit, r);
            if (listener != null)
                listener.onFrameShown(index, time);
            status("");
            return;
        }
        if (key.equals(pendingKey))
            return;
        pendingKey = key;
        final int mine = ++generation;
        status("Getting radar\u2026");
        Http.getBitmap(imageUrl(mosaic, r, time), egress.userAgent(), new Http.BitmapCallback() {
            @Override
            public void onSuccess(Bitmap bitmap) {
                if (key.equals(pendingKey))
                    pendingKey = null;
                if (mine != generation || !on)
                    return;
                cache.put(key, bitmap);
                Log.d(TAG, String.format(Locale.US,
                        "frame %s drawn %dx%d over %.2f,%.2f..%.2f,%.2f, echo %.1f%%",
                        time, bitmap.getWidth(), bitmap.getHeight(), r.getWest(), r.getSouth(),
                        r.getEast(), r.getNorth(), echoPercent(bitmap)));
                layer.setImage(bitmap, r);
                if (listener != null)
                    listener.onFrameShown(index, time);
                status("");
            }

            @Override
            public void onFailure(String error) {
                if (key.equals(pendingKey))
                    pendingKey = null;
                if (mine != generation)
                    return;
                status(error);
            }
        });
    }

    /** The GetMap request: CRS:84 so lon/lat corners are the image's corners. */
    static String imageUrl(Mosaic m, GeoBounds r, String time) {
        final double lonSpan = r.getEast() - r.getWest();
        final double latSpan = r.getNorth() - r.getSouth();
        int width, height;
        if (lonSpan >= latSpan) {
            width = MAX_PX;
            height = Math.max(64, (int) Math.round(MAX_PX * latSpan / lonSpan));
        } else {
            height = MAX_PX;
            width = Math.max(64, (int) Math.round(MAX_PX * lonSpan / latSpan));
        }
        return m.base + "?service=WMS&version=1.3.0&request=GetMap&layers=" + m.wmsLayer
                + "&styles=&crs=CRS:84&bbox=" + String.format(Locale.US, "%.4f,%.4f,%.4f,%.4f",
                        r.getWest(), r.getSouth(), r.getEast(), r.getNorth())
                + "&width=" + width + "&height=" + height
                + "&format=image/png&transparent=true&time=" + time;
    }

    /** Share of pixels with any alpha, sampled: says whether a frame carries weather. */
    private static double echoPercent(Bitmap b) {
        final int step = Math.max(1, Math.max(b.getWidth(), b.getHeight()) / 128);
        int n = 0, hit = 0;
        for (int y = 0; y < b.getHeight(); y += step) {
            for (int x = 0; x < b.getWidth(); x += step) {
                n++;
                if ((b.getPixel(x, y) >>> 24) != 0)
                    hit++;
            }
        }
        return n == 0 ? 0 : 100.0 * hit / n;
    }

    private void status(String s) {
        if (listener != null)
            listener.onStatus(s);
        if (!s.isEmpty())
            Log.d(TAG, s);
    }
}
