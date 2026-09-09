package com.atakmap.android.evaczone;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.preference.PreferenceManager;

import com.atakmap.android.ipc.AtakBroadcast;
import com.atakmap.android.ipc.AtakBroadcast.DocumentedIntentFilter;
import com.atakmap.android.maps.MapView;
import com.atakmap.coremap.filesystem.FileSystemUtils;
import com.atakmap.coremap.log.Log;
import com.atakmap.coremap.maps.coords.GeoPoint;
import com.atakmap.map.layer.feature.FeatureDataStore2;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The catalog, every source that is on, and the timer that keeps live ones current.
 *
 * <p>This lives for the plugin's life, never inside the pane or a tool: ATAK ends the
 * active tool whenever another starts, and a zone feed that stopped when the base map
 * changed would read as a zone that was lifted. Refreshes run one at a time on a worker.
 */
public class ZoneManager {

    private static final String TAG = "EvacZone";
    static final String ACTION_DETAILS = "com.atakmap.android.evaczone.FEATURE_DETAILS";
    /** The catalog host is a preference, not a constant: the host can move without a release. */
    public static final String PREF_BASE_URL = "evaczone_base_url";
    public static final String DEFAULT_BASE_URL = "https://mapdepot.takwerx.org/evaczone";
    private static final long TICK_MS = 60 * 1000L;

    public interface Listener {
        /** A layer changed: counts, status, on/off. Main thread. */
        void onChanged();

        /** The catalog itself changed (the depot copy arrived). Main thread. */
        void onCatalog();

        /** The map stopped moving: the zoom readout wants refreshing. Main thread. */
        void onMapMoved();
    }

    private final MapView mapView;
    private final Context pluginContext;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final File root, iconDir, layersDir;
    private String lineGlyph, polygonGlyph;
    private final List<ZoneLayer> layers = new ArrayList<>();
    private FeatureDetailsReceiver details;
    private Listener listener;
    private boolean started;
    private volatile Catalog catalog;
    /** Where the catalog came from, for the pane's status line. */
    public volatile String catalogStatus = "loading catalog";
    /** Radius and zoom threshold, one setting for every source. */
    public final Visibility visibility;
    /** The pane's all-ON/OFF: off draws nothing, keeps every feed's own ON for when it comes back. */
    private boolean mapOn = true;

    public boolean isMapOn() {
        return mapOn;
    }

    public void setMapOn(final boolean on) {
        if (mapOn == on)
            return;
        mapOn = on;
        uiPrefs().edit().putBoolean("mapOn", on).apply();
        for (ZoneLayer l : snapshot())
            l.busy = true;
        changed();
        worker.execute(new Runnable() {
            @Override
            public void run() {
                for (ZoneLayer l : snapshot()) {
                    try {
                        if (l.setMasterVisible(on) && l.isVisible())
                            refreshNow(l);
                    } finally {
                        l.busy = false;
                    }
                }
                changed();
            }
        });
    }

    /**
     * <strong>Runs on the GL render thread.</strong> ATAK dispatches map-moved from
     * {@code GLMapView.dispatchCameraChanged} over JNI; touching a View or a map item
     * here is a native SIGSEGV with no Java stack trace. Post to the main looper and
     * coalesce: during a pinch this fires every frame.
     */
    private final com.atakmap.map.AtakMapView.OnMapMovedListener moved =
            new com.atakmap.map.AtakMapView.OnMapMovedListener() {
                @Override
                public void onMapMoved(com.atakmap.map.AtakMapView v, boolean animate) {
                    main.removeCallbacks(moveTick);
                    main.postDelayed(moveTick, 400);
                }
            };

    private final Runnable moveTick = new Runnable() {
        @Override
        public void run() {
            if (!started)
                return;
            if (listener != null)
                listener.onMapMoved();
            // "Map Center" means where the map is NOW: the radius follows the pan,
            // but only once it has moved far enough to change the answer.
            if (visibility.fromMap && visibility.radiusBig > 0)
                maybeReapply(mapView.getPoint().get());
        }
    };

    private GeoPoint lastApplied;
    private com.atakmap.android.maps.PointMapItem selfWatched;
    private boolean selfPending;

    private final com.atakmap.android.maps.PointMapItem.OnPointChangedListener selfWatch =
            new com.atakmap.android.maps.PointMapItem.OnPointChangedListener() {
                @Override
                public void onPointChanged(com.atakmap.android.maps.PointMapItem item) {
                    if (visibility.fromMap || visibility.radiusBig <= 0)
                        return; // measured from the map, or off
                    if (selfPending)
                        return;
                    selfPending = true;
                    main.postDelayed(selfTick, 1500);
                }
            };

    private final Runnable selfTick = new Runnable() {
        @Override
        public void run() {
            selfPending = false;
            if (started)
                maybeReapply(visibility.from(mapView));
        }
    };

    /** Re-filters only when the point has moved a tenth of the radius since the last time. */
    private void maybeReapply(GeoPoint now) {
        if (now == null)
            return;
        if (lastApplied != null) {
            final double moved = com.atakmap.coremap.maps.coords.GeoCalculations.distanceTo(lastApplied, now);
            if (moved < visibility.radiusMeters() * 0.1)
                return;
        }
        applyVisibility();
    }

    /** The details pane for one zone from the pane's list, by source and feature id. Main thread. */
    public void showDetails(String sourceId, long featureId) {
        if (details != null && featureId >= 0)
            details.show(sourceId, featureId, true);
    }

    /** What Back in the details does when they were opened from the pane. */
    public void setOnDetailsBack(Runnable r) {
        if (details != null)
            details.setOnBack(r);
    }

    /** Pushes the current radius and zoom gate to every layer, on the worker. */
    public void applyVisibility() {
        final GeoPoint from = visibility.from(mapView);
        final double r = visibility.radiusMeters();
        final double z = visibility.maxResolution;
        lastApplied = from;
        worker.execute(new Runnable() {
            @Override
            public void run() {
                for (ZoneLayer l : snapshot())
                    l.applyVisibility(from, r, z);
                changed();
            }
        });
    }

    /**
     * The county filter for one state's layers: null draws every county. Applied to
     * every layer of that state, on the worker, and given to layers that attach later.
     */
    private final java.util.Map<String, java.util.Set<String>> countyFilters = new java.util.HashMap<>();

    public void setCounties(final String st, final java.util.Set<String> keys) {
        synchronized (countyFilters) {
            if (keys == null || keys.isEmpty())
                countyFilters.remove(st);
            else
                countyFilters.put(st, new java.util.HashSet<>(keys));
        }
        worker.execute(new Runnable() {
            @Override
            public void run() {
                for (ZoneLayer l : snapshot())
                    if (l.source.st.equalsIgnoreCase(st))
                        l.applyCounties(keys == null || keys.isEmpty() ? null : keys);
                changed();
            }
        });
    }

    public java.util.Set<String> countiesFor(String st) {
        synchronized (countyFilters) {
            final java.util.Set<String> k = countyFilters.get(st);
            return k == null ? null : new java.util.HashSet<>(k);
        }
    }

    public void setRadiusBig(int big) {
        visibility.radiusBig = Math.max(0, Math.min(Visibility.RADIUS_MAX, big));
        visibility.save();
        applyVisibility();
    }

    public void setFromMap(boolean fromMap) {
        visibility.fromMap = fromMap;
        visibility.save();
        applyVisibility();
    }

    public void setZoomThreshold(double metersPerPixel) {
        visibility.maxResolution = metersPerPixel;
        visibility.save();
        applyVisibility();
    }

    /**
     * Frames a box, then makes sure the zones can be seen there: if the fit lands
     * further out than the zoom threshold, "Go to" would take the operator to the
     * right place and show nothing, so it zooms comfortably inside the threshold.
     * Never zooms the operator back OUT.
     */
    public void frame(double[] b) {
        if (b == null || b[2] <= b[0] || b[3] <= b[1])
            return;
        final double padLat = Math.max(0.002, (b[2] - b[0]) * 0.15);
        final double padLon = Math.max(0.002, (b[3] - b[1]) * 0.15);
        final GeoPoint sw = new GeoPoint(b[0] - padLat, b[1] - padLon);
        final GeoPoint ne = new GeoPoint(b[2] + padLat, b[3] + padLon);
        try {
            com.atakmap.android.util.ATAKUtilities.scaleToFit(mapView, new GeoPoint[] { sw, ne }, 0d,
                    mapView.getWidth(), mapView.getHeight());
            final double limit = visibility.maxResolution;
            if (limit != Double.MAX_VALUE && mapView.getMapResolution() > limit) {
                final GeoPoint center = new GeoPoint((b[0] + b[2]) / 2, (b[1] + b[3]) / 2);
                mapView.getMapController().panZoomTo(center, mapView.mapResolutionAsMapScale(limit * 0.5), true);
            }
        } catch (LinkageError | RuntimeException e) {
            Log.w(TAG, "frame failed", e);
        }
    }

    /** Once a minute: refresh every layer whose own interval has elapsed. */
    private final Runnable timer = new Runnable() {
        @Override
        public void run() {
            if (!started)
                return;
            final long now = System.currentTimeMillis();
            for (ZoneLayer l : snapshot()) {
                final int min = l.source.refreshMin;
                if (min > 0 && l.isVisible() && !l.refreshing && now - l.lastRefresh >= min * 60_000L)
                    refresh(l);
            }
            main.postDelayed(this, TICK_MS);
        }
    };

    public ZoneManager(MapView mapView, Context pluginContext) {
        this.mapView = mapView;
        this.pluginContext = pluginContext;
        root = FileSystemUtils.getItem("tools/evaczone");
        iconDir = new File(root, "icons");
        layersDir = new File(root, "layers");
        visibility = new Visibility(mapView.getContext());
        mapOn = uiPrefs().getBoolean("mapOn", true);
    }

    public void setListener(Listener l) {
        listener = l;
    }

    public Catalog catalog() {
        return catalog;
    }

    public List<ZoneLayer> snapshot() {
        synchronized (layers) {
            return new ArrayList<>(layers);
        }
    }

    public ZoneLayer find(String sourceId) {
        for (ZoneLayer l : snapshot())
            if (l.source.id.equals(sourceId))
                return l;
        return null;
    }

    public FeatureDataStore2 storeFor(String sourceId) {
        final ZoneLayer l = find(sourceId);
        return l == null ? null : l.getStore();
    }

    /** True when the source is loaded and drawn. */
    public boolean isOn(Catalog.Source s) {
        final ZoneLayer l = find(s.id);
        return l != null && l.isVisible();
    }

    // ---- lifecycle ----------------------------------------------------------------

    public void start() {
        started = true;
        iconDir.mkdirs();
        layersDir.mkdirs();
        try {
            unpackGlyphs();
        } catch (Exception e) {
            Log.w(TAG, "glyph unpack failed", e);
        }
        details = new FeatureDetailsReceiver(mapView, pluginContext, this);
        final DocumentedIntentFilter filter = new DocumentedIntentFilter();
        filter.addAction(ACTION_DETAILS, "show the attributes of an evacuation zone");
        AtakBroadcast.getInstance().registerReceiver(details, filter);
        loadBundledCatalog();
        restore();
        main.postDelayed(timer, TICK_MS);
        fetchRemoteCatalog();
        mapView.addOnMapMovedListener(moved);
        try {
            final com.atakmap.android.maps.Marker self = mapView.getSelfMarker();
            if (self != null) {
                selfWatched = self;
                self.addOnPointChangedListener(selfWatch);
            }
        } catch (LinkageError | RuntimeException e) {
            Log.w(TAG, "could not follow the self marker", e);
        }
    }

    public void stop() {
        started = false;
        main.removeCallbacks(timer);
        main.removeCallbacks(moveTick);
        main.removeCallbacks(selfTick);
        try {
            mapView.removeOnMapMovedListener(moved);
            if (selfWatched != null)
                selfWatched.removeOnPointChangedListener(selfWatch);
        } catch (LinkageError | RuntimeException e) {
            Log.w(TAG, "watch removal", e);
        }
        selfWatched = null;
        try {
            AtakBroadcast.getInstance().unregisterReceiver(details);
        } catch (Exception ignored) {
        }
        if (details != null)
            details.dispose();
        for (ZoneLayer l : snapshot())
            l.detach();
        synchronized (layers) {
            layers.clear();
        }
        worker.shutdownNow();
    }

    // ---- catalog ------------------------------------------------------------------

    private void loadBundledCatalog() {
        try {
            final Catalog c = new Catalog(new JSONObject(readAsset("catalog.json")));
            catalog = c;
            catalogStatus = c.sources.size() + " sources, built-in catalog";
        } catch (Exception e) {
            Log.e(TAG, "bundled catalog unreadable", e);
            catalogStatus = "catalog unreadable";
        }
    }

    /** The depot host from the preference, HTTPS only; anything else is the default. */
    private String baseUrl() {
        final SharedPreferences p = PreferenceManager.getDefaultSharedPreferences(mapView.getContext());
        final String u = p.getString(PREF_BASE_URL, DEFAULT_BASE_URL).trim().replaceAll("/+$", "");
        return u.startsWith("https://") ? u : DEFAULT_BASE_URL;
    }

    /**
     * The depot copy replaces the built-in one when it is readable and no newer in
     * format than this build understands. Layers already on keep the source they were
     * turned on with; the next ON of the same id uses the new entry.
     */
    private void fetchRemoteCatalog() {
        final String url = baseUrl() + "/catalog.json";
        worker.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    final Catalog c = new Catalog(new JSONObject(Esri.get(url)));
                    if (c.format > Catalog.SUPPORTED_FORMAT)
                        throw new IllegalStateException("catalog format " + c.format + " is newer than this plugin");
                    if (c.sources.isEmpty())
                        throw new IllegalStateException("catalog is empty");
                    catalog = c;
                    catalogStatus = c.sources.size() + " sources, catalog of " + localTime(c.generated);
                    Log.d(TAG, "depot catalog: " + c.sources.size() + " sources, generated " + c.generated);
                } catch (Exception e) {
                    // Offline, or nothing published yet: the built-in copy is the catalog,
                    // and the pane says which one it has, not what it could not reach.
                    Log.w(TAG, "depot catalog unavailable, keeping the built-in copy: " + e.getMessage());
                    final Catalog c = catalog;
                    catalogStatus = (c == null ? 0 : c.sources.size()) + " sources, built-in catalog";
                }
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        if (listener != null)
                            listener.onCatalog();
                    }
                });
            }
        });
    }

    /** "2026-09-08 12:56" in the phone's time zone for the catalog's UTC stamp; the stamp itself if unreadable. */
    static String localTime(String iso) {
        try {
            final java.text.SimpleDateFormat in = new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.US);
            in.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
            final java.util.Date d = in.parse(iso);
            final java.text.SimpleDateFormat out = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US);
            return out.format(d);
        } catch (Exception e) {
            return iso;
        }
    }

    // ---- sources on and off -------------------------------------------------------

    /** Turns a source on (load and draw) or off (remove from the map and forget its cache). */
    public void setOn(final Catalog.Source s, final boolean on) {
        final Catalog c = catalog;
        ZoneLayer l = find(s.id);
        if (on) {
            if (l == null) {
                l = new ZoneLayer(s, mapView, pluginContext, new File(layersDir, s.fileKey() + ".sqlite"),
                        iconDir, lineGlyph, polygonGlyph, 0, 0);
                l.presetVisibility(visibility.from(mapView), visibility.radiusMeters(), visibility.maxResolution);
                l.presetMaster(mapOn);
                l.presetCounties(countiesFor(s.st));
                l.setCountyList(c.countiesOf(s.st));
                try {
                    l.attach();
                } catch (Exception e) {
                    Log.e(TAG, "attach failed for " + s.id, e);
                    return;
                }
                synchronized (layers) {
                    layers.add(l);
                }
            }
            final ZoneLayer target = l;
            target.markVisible(true);
            saveOn();
            target.busy = true;
            changed();
            worker.execute(new Runnable() {
                @Override
                public void run() {
                    try {
                        // Fetch here, on this task, not queued behind it: the row must
                        // read "Loading" from the tap until the zones are drawn.
                        if (target.setVisible(true) || target.lastRefresh == 0)
                            refreshNow(target);
                    } finally {
                        target.busy = false;
                        changed();
                    }
                }
            });
        } else {
            if (l == null)
                return;
            synchronized (layers) {
                layers.remove(l);
            }
            l.delete();
            saveOn();
            changed();
        }
    }

    public void refresh(final ZoneLayer l) {
        if (l.refreshing)
            return;
        worker.execute(new Runnable() {
            @Override
            public void run() {
                refreshNow(l);
            }
        });
    }

    /** The fetch itself. Worker thread. */
    private void refreshNow(ZoneLayer l) {
        l.refresh(new Runnable() {
            @Override
            public void run() {
                changed();
            }
        });
        saveOn();
    }

    public void refreshAll() {
        for (ZoneLayer l : snapshot())
            if (l.isVisible())
                refresh(l);
    }

    // ---- state --------------------------------------------------------------------

    private SharedPreferences uiPrefs() {
        return mapView.getContext().getSharedPreferences("evaczone.ui", Context.MODE_PRIVATE);
    }

    /** The sources that were on last time come back on, from their cached stores, then refresh. */
    private void restore() {
        final Catalog c = catalog;
        if (c == null)
            return;
        try {
            final JSONArray arr = new JSONArray(uiPrefs().getString("on", "[]"));
            for (int i = 0; i < arr.length(); i++) {
                final JSONObject o = arr.getJSONObject(i);
                final Catalog.Source s = c.byId(o.getString("id"));
                if (s == null)
                    continue;
                final ZoneLayer l = new ZoneLayer(s, mapView, pluginContext,
                        new File(layersDir, s.fileKey() + ".sqlite"), iconDir, lineGlyph, polygonGlyph,
                        o.optLong("lastRefresh", 0), o.optInt("count", 0));
                l.presetVisibility(visibility.from(mapView), visibility.radiusMeters(), visibility.maxResolution);
                l.presetMaster(mapOn);
                l.presetCounties(countiesFor(s.st));
                l.setCountyList(c.countiesOf(s.st));
                try {
                    l.attach();
                    synchronized (layers) {
                        layers.add(l);
                    }
                    refresh(l);
                } catch (Exception e) {
                    Log.w(TAG, "could not restore " + s.id, e);
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "state restore failed", e);
        }
        changed();
    }

    private synchronized void saveOn() {
        try {
            final JSONArray arr = new JSONArray();
            for (ZoneLayer l : snapshot()) {
                if (!l.isVisible())
                    continue;
                arr.put(new JSONObject().put("id", l.source.id).put("lastRefresh", l.lastRefresh).put("count", l.count));
            }
            uiPrefs().edit().putString("on", arr.toString()).apply();
        } catch (Exception e) {
            Log.w(TAG, "state save failed", e);
        }
    }

    private void changed() {
        main.post(new Runnable() {
            @Override
            public void run() {
                if (listener != null)
                    listener.onChanged();
            }
        });
    }

    // ---- assets -------------------------------------------------------------------

    private void unpackGlyphs() throws Exception {
        for (String g : new String[] { "line", "polygon" }) {
            final File png = new File(iconDir, "glyph_" + g + ".png");
            if (!png.isFile())
                copyAsset("glyphs/" + g + ".png", png);
            if ("line".equals(g))
                lineGlyph = "file://" + png.getAbsolutePath();
            else
                polygonGlyph = "file://" + png.getAbsolutePath();
        }
    }

    private String readAsset(String name) throws Exception {
        try (InputStream in = pluginContext.getAssets().open(name)) {
            return new String(readAll(in), "UTF-8");
        }
    }

    private void copyAsset(String name, File dest) throws Exception {
        try (InputStream in = pluginContext.getAssets().open(name); OutputStream out = new FileOutputStream(dest)) {
            out.write(readAll(in));
        }
    }

    private static byte[] readAll(InputStream in) throws Exception {
        final java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        final byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0)
            bos.write(buf, 0, n);
        return bos.toByteArray();
    }
}
