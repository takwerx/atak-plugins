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

import android.graphics.BitmapFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
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
 * a start/end/period that {@link #parseTimes} expands. Wherever none of those
 * radars see -- Mexico past the border radars, the Caribbean beyond Puerto Rico,
 * the oceans, the rest of the world -- the picture is RainViewer's composite of
 * the world's public radars, through {@link WorldRadar} ("like around the
 * world", same day). Which source draws is decided by the view's center, and the
 * credit on the layers page follows it.
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
    /**
     * One radar mosaic: where its WMS is, the layer, and the box its radars cover.
     * The world composite is a Mosaic too, with no WMS; {@link #WORLD} is the one.
     */
    static final class Mosaic {
        final String id, base, wmsLayer, capabilities;
        final double west, east, south, north;
        /**
         * The box within which this mosaic's radars actually see, for choosing it:
         * the extent unless {@link #sees} narrows it. The lower-48 WMS extent runs
         * to 20 N, but the southernmost radars reach about 24 N; a view centered
         * below that is Mexico, and the world composite has Mexico's radars.
         */
        double seesWest, seesEast, seesSouth, seesNorth;

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
            sees(west, east, south, north);
        }

        Mosaic sees(double w, double e, double s, double n) {
            seesWest = w;
            seesEast = e;
            seesSouth = s;
            seesNorth = n;
            return this;
        }

        /** Whether this mosaic's radars see the middle of a view. */
        boolean seesCenter(GeoBounds v) {
            final double lat = (v.getNorth() + v.getSouth()) / 2;
            final double lon = (v.getEast() + v.getWest()) / 2;
            return lat >= seesSouth && lat <= seesNorth && lon >= seesWest && lon <= seesEast;
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
     * 48 first: it wins a tie, and it is what a whole-world view gets. Each one's
     * {@code sees} box is where its radars reach, not its WMS extent: the Caribbean
     * mosaic's extent runs from Central America to the Windward Islands and its one
     * radar is Puerto Rico's, so a view centered on Cuba, Jamaica or Honduras goes
     * to the world composite, which has their radars; likewise Bermuda east of the
     * lower 48 and Mexico south of the border radars.
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
            new Mosaic("conus", -130, -60, 20, 55).sees(-130, -65, 24, 55),
            new Mosaic("carib", -90, -60, 10, 25).sees(-68.5, -63.5, 15.5, 20.5),
            new Mosaic("hawaii", -164, -151, 15, 26).sees(-161.5, -153, 17, 24),
            new Mosaic("alaska", -176, -126, 50, 72),
            new Mosaic("guam", 140, 150, 9, 18).sees(142, 147.5, 11, 16),
            new Mosaic("canada", "https://" + CANADA_HOST + "/geomet", "RADAR_1KM_RRAI",
                    "https://" + CANADA_HOST + "/geomet?service=WMS&version=1.3.0"
                            + "&request=GetCapabilities&layer=RADAR_1KM_RRAI",
                    -141, -52, 49, 62) };

    /**
     * Everywhere the agency mosaics do not see: RainViewer's composite, tiles to
     * Mercator's reach. Its "capabilities" is the JSON frame list.
     */
    static final Mosaic WORLD = new Mosaic("world", null, null, WorldRadar.FRAMES_URL,
            -180, 180, -WorldRadar.MAX_LAT, WorldRadar.MAX_LAT);

    /**
     * The mosaic whose radars see the view's center -- the one covering most of the
     * view when several do -- and the world composite when none does. The center
     * decides, not the overlap alone: a view of Mexico City overlaps the lower-48
     * extent a little and its radars not at all.
     */
    static Mosaic mosaicFor(GeoBounds view) {
        Mosaic best = null;
        double bestOverlap = 0;
        for (Mosaic m : MOSAICS) {
            if (!m.seesCenter(view))
                continue;
            final double o = m.overlap(view);
            if (best == null || o > bestOverlap) {
                best = m;
                bestOverlap = o;
            }
        }
        return best != null ? best : WORLD;
    }

    private static final String PREF_ON = "weather.layer.radar.on";
    private static final String PREF_TRANSPARENCY = "weather.layer.radar.transparency";
    /** How see-through the radar is, 0 (solid, as it always was) to this. */
    public static final int MAX_TRANSPARENCY = 90;
    /**
     * Frames a loop plays: up to the cache's worth, so every loop after the first
     * plays from memory. The NWS gives a frame every two minutes for two hours;
     * twenty spread over them is one every six minutes.
     */
    static final int PLAY_FRAMES = 20;
    private static final long PLAY_STEP_MS = 500L, PLAY_HOLD_MS = 1500L;
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
        /** The loop started or stopped. */
        void onPlaying(boolean playing);
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
    /** The world composite's frame list, with each frame's tile path; null elsewhere. */
    private WorldRadar.Frames worldFrames;
    /**
     * Fetches and assembles a world frame's tiles, one frame at a time, off main.
     * Made at start and shut down at stop, so a reloaded plugin does not leave a
     * thread pinning the old generation.
     */
    private ExecutorService worker;
    /**
     * Fetches a world frame's tiles four at a time for the worker, which paints
     * them in order as they land. One after another, a server that stalled on two
     * tiles held Europe's first frame for 50 s (XCover, 2026-09-27); in parallel a
     * stall costs one wait, not the sum. Four is modest for a service that asks
     * not to be hammered, and a frame is at most 25 tiles.
     */
    private ExecutorService tileFetch;
    private static final int TILE_FETCHERS = 4;
    private int index = -1;
    /** True while the operator has not scrubbed away from the newest frame. */
    private boolean followLatest = true;
    private GeoBounds region;
    private long capsFetchedAt;
    /** A capabilities read is in flight; a second request would only cancel it. */
    private boolean capsInFlight;
    /** The image being fetched; asking for the same one again is a no-op. */
    private String pendingKey;
    /** Bumped on main whenever what is in flight no longer matters; read by the worker as a hint to stop. */
    private volatile int generation;
    private Listener listener;
    private int transparency;
    /** Playing the loop, and where in it: indexes into frames, oldest first. */
    private boolean playing;
    private final List<Integer> playList = new ArrayList<>();
    private int playPos = -1;

    /** The loop's next frame, once the one before it is on the map. */
    private final Runnable playStep = new Runnable() {
        @Override
        public void run() {
            if (!playing || !on || frames.isEmpty())
                return;
            if (playList.isEmpty())
                playList.addAll(playFrames(frames.size()));
            playPos = (playPos + 1) % playList.size();
            moveTo(playList.get(playPos));
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
        final SharedPreferences tp = MapCompat.prefs();
        transparency = tp == null ? 0
                : Math.max(0, Math.min(MAX_TRANSPARENCY, tp.getInt(PREF_TRANSPARENCY, 0)));
        layer.setAlpha((100 - transparency) / 100f);
        mapView.addOnMapMovedListener(moved);
        worker = Executors.newSingleThreadExecutor(new ThreadFactory() {
            @Override
            public Thread newThread(Runnable r) {
                final Thread t = new Thread(r, "wx-radar-tiles");
                t.setDaemon(true);
                return t;
            }
        });
        tileFetch = Executors.newFixedThreadPool(TILE_FETCHERS, new ThreadFactory() {
            @Override
            public Thread newThread(Runnable r) {
                final Thread t = new Thread(r, "wx-radar-fetch");
                t.setDaemon(true);
                return t;
            }
        });
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
        mapView.removeCallbacks(playStep);
        playing = false;
        generation++;
        layer.clear();
        mapView.removeLayer(MapView.RenderStack.MAP_SURFACE_OVERLAYS, layer);
        GLRasterLayer.unregister();
        cache.clear();
        if (worker != null) {
            worker.shutdownNow();
            worker = null;
        }
        if (tileFetch != null) {
            tileFetch.shutdownNow();
            tileFetch = null;
        }
    }

    public boolean isOn() {
        return on;
    }

    /** True while the picture is the world composite's, so the credit can say so. */
    public boolean worldSource() {
        return mosaic == WORLD;
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
            setPlaying(false);
            layer.setVisible(false);
            layer.clear();
            status("");
        }
    }

    public int transparency() {
        return transparency;
    }

    /**
     * Percent see-through, so the map under a storm shows (takwerx/atmosphere#7).
     * The renderer fades what it holds: the slider moves the picture at once.
     */
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

    public boolean isPlaying() {
        return playing;
    }

    /**
     * Play the last two hours as a loop, to see which way the cells move
     * (takwerx/atmosphere#7): up to {@link #PLAY_FRAMES} frames, oldest first, each
     * once the one before it is on the map, the newest held a moment before it
     * starts over. A frame still to fetch is fetched when its turn comes, so the
     * first loop runs at the server's pace and the rest from memory. Stopping
     * leaves the frame showing; a pick on the strip stops it.
     */
    public void setPlaying(boolean value) {
        if (value && (!on || frames.size() < 2))
            return;
        mapView.removeCallbacks(playStep);
        if (playing == value)
            return;
        playing = value;
        playList.clear();
        if (value) {
            playList.addAll(playFrames(frames.size()));
            playPos = -1;
            mapView.post(playStep);
        }
        if (listener != null)
            listener.onPlaying(value);
    }

    /** The frames a loop plays, oldest first, ending at the newest: evenly spread, at most PLAY_FRAMES. */
    public static List<Integer> playFrames(int count) {
        final List<Integer> out = new ArrayList<>();
        final int n = Math.min(count, PLAY_FRAMES);
        if (n <= 0)
            return out;
        if (n == 1) {
            out.add(count - 1);
            return out;
        }
        // From the oldest to the newest, as even as whole frames allow.
        for (int j = 0; j < n; j++)
            out.add((int) Math.round(j * (count - 1) / (double) (n - 1)));
        return out;
    }

    /** A frame of the loop: shown or fetched now, never waiting on a scrub to pause. */
    private void moveTo(int i) {
        if (i < 0 || i >= frames.size())
            return;
        index = i;
        followLatest = i == frames.size() - 1;
        mapView.removeCallbacks(scrubSettled);
        showFrame();
    }

    /** A loop frame is on the map, or could not be had: the next after a beat. */
    private void stepLoop() {
        if (!playing)
            return;
        mapView.removeCallbacks(playStep);
        final boolean last = !playList.isEmpty() && playPos == playList.size() - 1;
        mapView.postDelayed(playStep, last ? PLAY_HOLD_MS : PLAY_STEP_MS);
    }

    public List<String> frames() {
        return Collections.unmodifiableList(new ArrayList<>(frames));
    }

    public int frameIndex() {
        return index;
    }

    /** Scrub to a frame. The last index means "latest", and new frames follow it. A scrub stops the loop. */
    public void setFrameIndex(int i) {
        if (frames.isEmpty())
            return;
        i = Math.max(0, Math.min(i, frames.size() - 1));
        followLatest = i == frames.size() - 1;
        // The loop stops on the frame picked, and says so once that frame is the
        // index, so the strip shows the pick and not where the loop was.
        final boolean wasPlaying = playing;
        if (wasPlaying) {
            mapView.removeCallbacks(playStep);
            playing = false;
            playList.clear();
        }
        if (i != index) {
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
        if (wasPlaying && listener != null)
            listener.onPlaying(false);
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
                        final List<String> times;
                        if (asked == WORLD) {
                            worldFrames = WorldRadar.parseFrames(body);
                            if (worldFrames.note != null)
                                Log.w(TAG, worldFrames.note);
                            times = new ArrayList<>(worldFrames.times);
                        } else {
                            times = parseTimes(body);
                        }
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
                        if (playing) {
                            playList.clear();
                            playList.addAll(playFrames(times.size()));
                            playPos = Math.min(playPos, playList.size() - 1);
                        }
                        if (listener != null)
                            listener.onFrames(frames(), index);
                        ensureRegion();
                        if (playing)
                            stepLoop();
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
            worldFrames = null;
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
            stepLoop();
            return;
        }
        if (key.equals(pendingKey))
            return;
        pendingKey = key;
        final int mine = ++generation;
        status("Getting radar\u2026");
        if (mosaic == WORLD) {
            fetchWorldFrame(key, r, time, mine);
            return;
        }
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
                stepLoop();
            }

            @Override
            public void onFailure(String error) {
                if (key.equals(pendingKey))
                    pendingKey = null;
                if (mine != generation)
                    return;
                status(error);
                stepLoop();
            }
        });
    }

    /**
     * A world frame: the tiles under the region, fetched and resampled onto the
     * lon/lat quad on the worker, delivered on main like a WMS frame. A tile that
     * fails leaves its part clear; a frame with no tile at all is a failure.
     */
    private void fetchWorldFrame(final String key, final GeoBounds r, final String time,
            final int mine) {
        final String path = worldFrames == null ? null : worldFrames.pathOf(time);
        final WorldRadar.Plan plan = path == null ? null
                : WorldRadar.plan(r.getWest(), r.getSouth(), r.getEast(), r.getNorth(), MAX_PX);
        final ExecutorService w = worker, fetch = tileFetch;
        if (plan == null || w == null || fetch == null) {
            pendingKey = null;
            status("No radar frames available");
            return;
        }
        final String ua = egress.userAgent();
        Log.d(TAG, "world frame " + time + " " + plan);
        w.execute(new Runnable() {
            @Override
            public void run() {
                Bitmap bitmap = null;
                String error = null;
                final List<Future<byte[]>> fetches = new ArrayList<>();
                try {
                    // Every tile asked for at once, four in flight; painted in order
                    // as each lands, one tile's pixels in hand at a time.
                    for (int ty = plan.ty0; ty <= plan.ty1; ty++) {
                        for (int tx = plan.tx0; tx <= plan.tx1; tx++) {
                            final String url = plan.tileUrl(path, tx, ty);
                            fetches.add(fetch.submit(new Callable<byte[]>() {
                                @Override
                                public byte[] call() throws IOException {
                                    return mine != generation ? null : Http.fetchBytes(url, ua);
                                }
                            }));
                        }
                    }
                    final int[] out = new int[plan.outW * plan.outH];
                    final int[] gx = WorldRadar.columns(plan), gy = WorldRadar.rows(plan);
                    final int[] px = new int[WorldRadar.TILE_PX * WorldRadar.TILE_PX];
                    int missing = 0, k = 0;
                    for (int ty = plan.ty0; ty <= plan.ty1; ty++) {
                        for (int tx = plan.tx0; tx <= plan.tx1; tx++) {
                            final Future<byte[]> f = fetches.get(k++);
                            if (mine != generation)
                                return;
                            byte[] bytes = null;
                            try {
                                bytes = f.get();
                            } catch (ExecutionException e) {
                                Log.w(TAG, "tile " + plan.zoom + "/" + tx + "/" + ty + ": "
                                        + (e.getCause() == null ? e : e.getCause().getMessage()));
                            }
                            // A tile is TILE_PX square: its header says so before any
                            // pixel is decoded, so a picture of another size is never
                            // expanded into memory at all.
                            final Bitmap t = bytes == null || !isTile(bytes) ? null
                                    : BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
                            if (t == null || t.getWidth() != WorldRadar.TILE_PX
                                    || t.getHeight() != WorldRadar.TILE_PX) {
                                missing++;
                                if (t != null)
                                    t.recycle();
                                continue;
                            }
                            t.getPixels(px, 0, WorldRadar.TILE_PX, 0, 0, WorldRadar.TILE_PX,
                                    WorldRadar.TILE_PX);
                            t.recycle();
                            WorldRadar.paintTile(plan, gx, gy, tx, ty, px, out);
                        }
                    }
                    if (missing == plan.tileCount())
                        error = "the provider did not return an image";
                    else
                        bitmap = Bitmap.createBitmap(out, plan.outW, plan.outH, Bitmap.Config.ARGB_8888);
                } catch (InterruptedException e) {
                    // The plugin is stopping; nothing to deliver.
                    return;
                } catch (RuntimeException e) {
                    Log.e(TAG, "world frame failed hard", e);
                    error = "request failed";
                } finally {
                    for (Future<byte[]> f : fetches)
                        f.cancel(true);
                }
                final Bitmap b = bitmap;
                final String err = error;
                mapView.post(new Runnable() {
                    @Override
                    public void run() {
                        if (key.equals(pendingKey))
                            pendingKey = null;
                        if (mine != generation || !on) {
                            if (b != null)
                                b.recycle();
                            return;
                        }
                        if (err != null) {
                            status(err);
                            stepLoop();
                            return;
                        }
                        cache.put(key, b);
                        Log.d(TAG, String.format(Locale.US,
                                "world frame %s drawn %dx%d over %.2f,%.2f..%.2f,%.2f, echo %.1f%%",
                                time, b.getWidth(), b.getHeight(), r.getWest(), r.getSouth(),
                                r.getEast(), r.getNorth(), echoPercent(b)));
                        layer.setImage(b, r);
                        if (listener != null)
                            listener.onFrameShown(index, time);
                        status("");
                        stepLoop();
                    }
                });
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

    /** Whether these bytes are a picture of exactly one tile, read from the header alone. */
    private static boolean isTile(byte[] bytes) {
        final BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(bytes, 0, bytes.length, o);
        return o.outWidth == WorldRadar.TILE_PX && o.outHeight == WorldRadar.TILE_PX;
    }
}
