package com.atakmap.android.featurelayer;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

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
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Every loaded layer, the sign-ins per portal, and the state file that brings the layers
 * back after a restart. Refreshes run one at a time on a worker; live layers refresh on
 * a timer.
 */
public class LayerManager {

    private static final String TAG = "FeatureLayer";
    static final String ACTION_DETAILS = "com.atakmap.android.featurelayer.FEATURE_DETAILS";
    private static final long TICK_MS = 60 * 1000L;

    public interface Listener {
        void onChanged();
    }

    private final MapView mapView;
    private final Context pluginContext;
    private final String clientId;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final File root, iconDir, layersDir, stateFile;
    private final Map<String, String> nwcgIcons = new HashMap<>();
    private final Map<String, String> sarcopIcons = new HashMap<>();
    private String lineGlyph, polygonGlyph;
    private final List<LoadedLayer> layers = new ArrayList<>();
    private final Map<String, ArcGisAuth> auths = new HashMap<>();
    private FeatureDetailsReceiver details;
    private MarkerHereReceiver markerHere;
    private Listener listener;
    private boolean started;

    /** Once a minute: refresh every layer whose own interval has elapsed. */
    private final Runnable timer = new Runnable() {
        @Override
        public void run() {
            if (!started)
                return;
            final long now = System.currentTimeMillis();
            for (LoadedLayer l : snapshot()) {
                final int min = l.spec.refreshMinutes;
                if (min > 0 && !l.refreshing && now - l.lastRefresh >= min * 60_000L)
                    refresh(l);
            }
            main.postDelayed(this, TICK_MS);
        }
    };

    public LayerManager(MapView mapView, Context pluginContext, String clientId) {
        this.mapView = mapView;
        this.pluginContext = pluginContext;
        this.clientId = clientId;
        root = FileSystemUtils.getItem("tools/featurelayer");
        iconDir = new File(root, "icons");
        layersDir = new File(root, "layers");
        stateFile = new File(root, "layers.json");
    }

    public void setListener(Listener l) {
        listener = l;
    }

    public List<LoadedLayer> snapshot() {
        synchronized (layers) {
            return new ArrayList<>(layers);
        }
    }

    public ArcGisAuth auth(String portal) {
        synchronized (auths) {
            ArcGisAuth a = auths.get(portal);
            if (a == null) {
                a = new ArcGisAuth(mapView, portal, clientId);
                auths.put(portal, a);
            }
            return a;
        }
    }

    public FeatureDataStore2 storeFor(String layerId) {
        for (LoadedLayer l : snapshot())
            if (l.spec.id.equals(layerId))
                return l.getStore();
        return null;
    }

    public LoadedLayer find(String id) {
        for (LoadedLayer l : snapshot())
            if (l.spec.id.equals(id))
                return l;
        return null;
    }

    // ---- lifecycle ----------------------------------------------------------------

    public void start() {
        started = true;
        iconDir.mkdirs();
        layersDir.mkdirs();
        try {
            unpackIcons();
        } catch (Exception e) {
            Log.w(TAG, "icon unpack failed", e);
        }
        details = new FeatureDetailsReceiver(mapView, pluginContext, this);
        final DocumentedIntentFilter filter = new DocumentedIntentFilter();
        filter.addAction(ACTION_DETAILS, "show the attributes of a loaded feature");
        AtakBroadcast.getInstance().registerReceiver(details, filter);
        markerHere = new MarkerHereReceiver(mapView, this);
        final DocumentedIntentFilter mh = new DocumentedIntentFilter();
        mh.addAction(MarkerHereReceiver.ACTION, "drop a marker at a loaded feature");
        AtakBroadcast.getInstance().registerReceiver(markerHere, mh);
        restore();
        for (LoadedLayer l : snapshot())
            refresh(l);
        main.postDelayed(timer, TICK_MS);
    }

    public void stop() {
        started = false;
        main.removeCallbacks(timer);
        try {
            AtakBroadcast.getInstance().unregisterReceiver(details);
        } catch (Exception ignored) {
        }
        try {
            AtakBroadcast.getInstance().unregisterReceiver(markerHere);
            markerHere.dispose();
        } catch (Exception ignored) {
        }
        if (details != null)
            details.dispose();
        for (LoadedLayer l : snapshot())
            l.detach();
        synchronized (layers) {
            layers.clear();
        }
        synchronized (auths) {
            for (ArcGisAuth a : auths.values())
                a.dispose();
        }
        worker.shutdownNow();
    }

    // ---- layers -------------------------------------------------------------------

    /** Adds (or, if already loaded, refreshes) a layer, pans to it, and saves the list. */
    public void add(LayerSpec spec) {
        LoadedLayer existing = find(spec.id);
        if (existing == null) {
            existing = new LoadedLayer(spec, mapView, pluginContext, new File(layersDir, spec.fileKey() + ".sqlite"),
                    iconDir, nwcgIcons, sarcopIcons, lineGlyph, polygonGlyph, 0);
            try {
                existing.attach();
            } catch (Exception e) {
                Log.e(TAG, "attach failed for " + spec.id, e);
                return;
            }
            synchronized (layers) {
                layers.add(existing);
            }
            save();
        }
        existing.panTo();
        refresh(existing);
        changed();
    }

    public void remove(LoadedLayer l) {
        synchronized (layers) {
            layers.remove(l);
        }
        l.delete();
        save();
        changed();
    }

    /** Polygon fill opacity for one layer: every area type, in place, no network. */
    public void setFillAlpha(final LoadedLayer l, final int alpha) {
        l.spec.fillAlpha = alpha;
        l.spec.setFill.clear();
        save();
        worker.execute(new Runnable() {
            @Override
            public void run() {
                for (Map.Entry<String, String> e : new HashMap<>(l.spec.setKind).entrySet())
                    if ("polygon".equals(e.getValue()))
                        l.restyleFills(e.getKey(), alpha);
                changed();
            }
        });
    }

    /** Fill opacity for one feature type of one layer, in place, no network. */
    public void setSetFill(final LoadedLayer l, final String setName, final int alpha) {
        l.spec.setFill.put(setName, alpha);
        save();
        worker.execute(new Runnable() {
            @Override
            public void run() {
                l.restyleFills(setName, alpha);
                changed();
            }
        });
    }

    public void setRefreshMinutes(LoadedLayer l, int minutes) {
        l.spec.refreshMinutes = minutes;
        save();
        changed();
    }

    /** How far back a windowed layer reaches; the where clause changes, so it refetches. */
    public void setSinceHours(final LoadedLayer l, final int hours) {
        l.spec.sinceHours = hours;
        save();
        l.busy = true;
        changed();
        worker.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    refreshNow(l); // in this task, so "Loading" lasts until the redraw
                } finally {
                    l.busy = false;
                    changed();
                }
            }
        });
    }

    /** The zoom gate for one layer; a store rewrite from memory, no network. */
    public void setGate(final LoadedLayer l, final double metersPerPixel) {
        l.spec.gateGsd = metersPerPixel;
        save();
        l.busy = true;
        changed();
        worker.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    l.setGate(metersPerPixel);
                } finally {
                    l.busy = false;
                    changed();
                }
            }
        });
    }

    /** Point labels for one layer; a store rewrite from memory, no network. */
    public void setLabels(final LoadedLayer l, final boolean on) {
        l.spec.labels = on;
        save();
        l.busy = true;
        changed();
        worker.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    l.setLabels(on);
                } finally {
                    l.busy = false;
                    changed();
                }
            }
        });
    }

    /** Repair Status halos for one layer; a store rewrite from memory, no network. */
    public void setRepairStatus(final LoadedLayer l, final boolean on) {
        l.spec.repairStatus = on;
        save();
        l.busy = true;
        changed();
        worker.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    l.setRepairStatus(on);
                } finally {
                    l.busy = false;
                    changed();
                }
            }
        });
    }

    public void setSetOn(final LoadedLayer l, final String setName, final boolean v) {
        l.spec.setOn.put(setName, v);
        save();
        l.busy = v; // only turning on loads anything; off just removes
        changed();
        worker.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    if (l.setSetVisible(setName, v))
                        refresh(l);
                } finally {
                    l.busy = false;
                    changed();
                }
            }
        });
    }

    public void setVisible(final LoadedLayer l, final boolean v) {
        l.markVisible(v);
        save();
        l.busy = v; // only turning on loads anything; off just removes
        changed();
        worker.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    if (l.setVisible(v))
                        refresh(l);
                } finally {
                    l.busy = false;
                    changed();
                }
            }
        });
    }

    public void refresh(final LoadedLayer l) {
        if (l.refreshing)
            return;
        worker.execute(new Runnable() {
            @Override
            public void run() {
                refreshNow(l);
            }
        });
    }

    /** Fetches on the calling (worker) thread; waits out a refresh already running rather than skipping. */
    private void refreshNow(LoadedLayer l) {
        for (int i = 0; i < 60 && l.refreshing; i++) {
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                return;
            }
        }
        String token = null;
        if (l.spec.portal != null) {
            if (!trusted(l.spec)) {
                // Not this org's server: the layer is read without the org's token, or not at all.
                Log.w(TAG, l.spec.id + ": " + Esri.hostOf(l.spec.base) + " is not a server of " + hostOf(l.spec.portal) + "; no token sent");
                l.spec.portal = null;
                save();
            }
        }
        if (l.spec.portal != null) {
            final ArcGisAuth a = auth(l.spec.portal);
            token = a.getValidToken();
            if (token == null) {
                l.status = "sign in to " + (l.spec.orgName != null ? l.spec.orgName : hostOf(l.spec.portal));
                l.stale = true;
                changed();
                return;
            }
        }
        l.refresh(token, new Runnable() {
            @Override
            public void run() {
                changed();
            }
        });
        save();
    }

    public void refreshAll() {
        for (LoadedLayer l : snapshot())
            refresh(l);
    }

    /** Runs a search on the worker and hands the result to the main thread. */
    public <T> void search(final java.util.concurrent.Callable<T> job, final SearchCallback<T> cb) {
        worker.execute(new Runnable() {
            @Override
            public void run() {
                T result = null;
                String error = null;
                try {
                    result = job.call();
                } catch (Exception e) {
                    Log.w(TAG, "search failed", e);
                    error = e.getMessage();
                }
                final T r = result;
                final String err = error;
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        cb.onResult(r, err);
                    }
                });
            }
        });
    }

    /** Matches across every loaded layer, with the layer that owns each. */
    public List<Object[]> findFeatures(String text) {
        final List<Object[]> out = new ArrayList<>();
        for (LoadedLayer l : snapshot())
            for (LoadedLayer.Hit h : l.find(text, 5000))
                out.add(new Object[] { l, h });
        return out;
    }

    public interface SearchCallback<T> {
        void onResult(T result, String error);
    }

    // ---- state --------------------------------------------------------------------

    private void restore() {
        if (!stateFile.isFile())
            return;
        try {
            final JSONArray arr = new JSONArray(readFile(stateFile));
            for (int i = 0; i < arr.length(); i++) {
                final JSONObject o = arr.getJSONObject(i);
                final LayerSpec spec = LayerSpec.fromJson(o.getJSONObject("spec"));
                // Layers saved before the icon sets existed: give SARCOP its symbols.
                if (spec.iconSet == null && spec.id.startsWith("sarcop-"))
                    spec.iconSet = "sarcop";
                // Built-in public sources: the fixed parts follow the current definition.
                if ("ca-air-intel".equals(spec.id)) {
                    final LayerSpec cur = Sources.caAirIntel();
                    spec.where = cur.where;
                    spec.labelField = cur.labelField;
                    spec.setField = cur.setField;
                    spec.timeField = cur.timeField;
                    spec.latestBy = cur.latestBy;
                    spec.setNotes.putAll(cur.setNotes);
                    for (String n : cur.setNotes.keySet())
                        if (!spec.setKind.containsKey(n))
                            spec.setKind.put(n, "polygon");
                }
                // Live NIFS layers saved with the four Event layers: give them all eight.
                if (spec.id.startsWith("nifs-live:") && spec.layerIds.length < Sources.NIFS_LIVE_LAYERS.length)
                    spec.layerIds = Sources.NIFS_LIVE_LAYERS;
                final LoadedLayer l = new LoadedLayer(spec, mapView, pluginContext,
                        new File(layersDir, spec.fileKey() + ".sqlite"), iconDir, nwcgIcons, sarcopIcons,
                        lineGlyph, polygonGlyph, o.optLong("lastRefresh", 0));
                try {
                    l.attach();
                    l.setVisible(o.optBoolean("visible", true));
                    synchronized (layers) {
                        layers.add(l);
                    }
                } catch (Exception e) {
                    Log.w(TAG, "could not restore " + spec.id, e);
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "state restore failed", e);
        }
        changed();
    }

    private synchronized void save() {
        try {
            final JSONArray arr = new JSONArray();
            for (LoadedLayer l : snapshot()) {
                final JSONObject o = new JSONObject();
                o.put("spec", l.spec.toJson());
                o.put("visible", l.isVisible());
                o.put("lastRefresh", l.lastRefresh);
                arr.put(o);
            }
            try (OutputStream out = new FileOutputStream(stateFile)) {
                out.write(arr.toString(1).getBytes("UTF-8"));
            }
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

    private void unpackIcons() throws Exception {
        final JSONObject index = new JSONObject(readAsset("nwcg/index.json"));
        final Iterator<String> keys = index.keys();
        while (keys.hasNext()) {
            final String cat = keys.next();
            final String file = index.getString(cat);
            final File png = new File(iconDir, "nwcg_" + file);
            copyAsset("nwcg/" + file, png);
            nwcgIcons.put(cat, "file://" + png.getAbsolutePath());
        }
        try {
            final JSONObject sidx = new JSONObject(readAsset("sarcop/index.json"));
            final Iterator<String> sk = sidx.keys();
            while (sk.hasNext()) {
                final String name = sk.next();
                final String file = sidx.getString(name);
                final File png = new File(iconDir, "sarcop_" + file);
                copyAsset("sarcop/" + file, png);
                sarcopIcons.put(name, "file://" + png.getAbsolutePath());
            }
        } catch (Exception e) {
            Log.w(TAG, "SARCOP icons unavailable", e);
        }
        for (String g : new String[] { "line", "polygon" }) {
            final File png = new File(iconDir, "glyph_" + g + ".png");
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

    private static String readFile(File f) throws Exception {
        try (InputStream in = new java.io.FileInputStream(f)) {
            return new String(readAll(in), "UTF-8");
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

    /** Once per service address per session: may this portal's token go there? */
    private final Map<String, Boolean> trustedBases = new HashMap<>();

    private boolean trusted(LayerSpec spec) {
        final String key = spec.portal + " -> " + spec.base;
        synchronized (trustedBases) {
            final Boolean t = trustedBases.get(key);
            if (t != null)
                return t;
        }
        final boolean ok = Esri.trustsServer(spec.portal, spec.base);
        synchronized (trustedBases) {
            trustedBases.put(key, ok);
        }
        return ok;
    }

    public static String hostOf(String portal) {
        return portal.replaceFirst("^https?://", "").replaceFirst("/.*$", "");
    }
}
