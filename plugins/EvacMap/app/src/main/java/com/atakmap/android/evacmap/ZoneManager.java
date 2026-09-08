package com.atakmap.android.evacmap;

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

    private static final String TAG = "EvacMap";
    static final String ACTION_DETAILS = "com.atakmap.android.evacmap.FEATURE_DETAILS";
    /** The catalog host is a preference, not a constant: the host can move without a release. */
    public static final String PREF_BASE_URL = "evacmap_base_url";
    public static final String DEFAULT_BASE_URL = "https://mapdepot.takwerx.org/evacmap";
    private static final long TICK_MS = 60 * 1000L;

    public interface Listener {
        /** A layer changed: counts, status, on/off. Main thread. */
        void onChanged();

        /** The catalog itself changed (the depot copy arrived). Main thread. */
        void onCatalog();
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
        root = FileSystemUtils.getItem("tools/evacmap");
        iconDir = new File(root, "icons");
        layersDir = new File(root, "layers");
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
    }

    public void stop() {
        started = false;
        main.removeCallbacks(timer);
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

    private String baseUrl() {
        final SharedPreferences p = PreferenceManager.getDefaultSharedPreferences(mapView.getContext());
        return p.getString(PREF_BASE_URL, DEFAULT_BASE_URL).replaceAll("/+$", "");
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
        ZoneLayer l = find(s.id);
        if (on) {
            if (l == null) {
                l = new ZoneLayer(s, mapView, pluginContext, new File(layersDir, s.fileKey() + ".sqlite"),
                        iconDir, lineGlyph, polygonGlyph, 0, 0);
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
        return mapView.getContext().getSharedPreferences("evacmap.ui", Context.MODE_PRIVATE);
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
