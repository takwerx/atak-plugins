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
    /** Saved entries that failed to open at start, carried through save() untouched until they open. */
    private final List<JSONObject> unrestored = new ArrayList<>();
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

    /** A line in tools/featurelayer/start-log.txt: the start sequence, which no log on this phone shows. */
    void startLog(String line) {
        try {
            final File f = new File(root, "start-log.txt");
            if (f.length() > 200_000)
                //noinspection ResultOfMethodCallIgnored
                f.delete();
            final java.io.FileWriter w = new java.io.FileWriter(f, true);
            try {
                w.write(new java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(new java.util.Date())
                        + " " + Thread.currentThread().getName() + " " + line + "\n");
            } finally {
                w.close();
            }
        } catch (Exception ignored) {
        }
    }

    public void setListener(Listener l) {
        listener = l;
    }

    /**
     * The loaded layers in the order the pane shows them: DART first, then everything
     * else in the order it was added. Live positions are what an operator opens the pane
     * for, and they were sinking under whichever fire layers happened to be added earlier
     * (operator, 2026-09-18: "even if you did a fire first then chose dart, dart is on
     * top"). The sort is stable, so nothing else changes place.
     */
    private static int rank(LoadedLayer l) {
        return DartStyles.handles(l.spec) ? 0 : FireGuardStyles.handles(l.spec) ? 1 : 2;
    }

    public List<LoadedLayer> snapshot() {
        final List<LoadedLayer> copy;
        synchronized (layers) {
            copy = new ArrayList<>(layers);
        }
        java.util.Collections.sort(copy, new java.util.Comparator<LoadedLayer>() {
            @Override
            public int compare(LoadedLayer a, LoadedLayer b) {
                // DART, then FireGuard, then whatever incidents were added, in their order
                // (operator, 2026-09-18: "dart, fireguard, any incidents you have marked").
                return Integer.compare(rank(a), rank(b));
            }
        });
        return copy;
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
        startLog("start: begin");
        attachFollow();
        iconDir.mkdirs();
        layersDir.mkdirs();
        // Thousands of composed PNGs and a few sqlite stores are not media: without this
        // Android's media scanner indexed every one and fought the plugin for the disk
        // at load (75% of a core in the 2026-09-18 14:26 ANR dump).
        for (File d : new File[] { iconDir, layersDir }) {
            try {
                //noinspection ResultOfMethodCallIgnored
                new File(d, ".nomedia").createNewFile();
            } catch (Exception ignored) {
            }
        }
        worker.execute(new Runnable() {
            @Override
            public void run() {
                startLog("purge: begin");
                DartStyles.purgeStale(iconDir);
                startLog("purge: done");
            }
        });
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
        startLog("start: restoring");
        restore();
        startLog("start: restored " + snapshot().size() + " layers, queuing refreshes");
        for (LoadedLayer l : snapshot())
            refresh(l);
        main.postDelayed(timer, TICK_MS);
        startLog("start: done");
    }

    public void stop() {
        started = false;
        detachFollow();
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
        final long t0 = System.currentTimeMillis();
        LoadedLayer existing = find(spec.id);
        if (existing == null) {
            existing = new LoadedLayer(spec, mapView, pluginContext, new File(layersDir, spec.fileKey() + ".sqlite"),
                    iconDir, nwcgIcons, sarcopIcons, lineGlyph, polygonGlyph, 0);
            try {
                existing.attach();
            } catch (Exception e) {
                Log.e(TAG, "attach failed for " + spec.id, e);
                // Say so: a layer that silently fails to appear reads as "I tapped it and
                // nothing happened" (FireGuard, 2026-09-18). The trace goes to a file too,
                // because this phone's logcat never carries ATAK's log.
                try {
                    // The reason goes to the file, not the screen (Fortify: no exception text in UI).
                    android.widget.Toast.makeText(mapView.getContext(),
                            "Could not add " + spec.title + "; see add-failed.txt", android.widget.Toast.LENGTH_LONG).show();
                    // The file says which layer failed and when, and the kind of failure;
                    // the stack is in ATAK's own log (Log.e above). Fortify flagged a stack
                    // trace written to a file as a system information leak (0.8 scan).
                    final File af = new File(layersDir.getParentFile(), "add-failed.txt");
                    if (af.length() > 200_000)
                        //noinspection ResultOfMethodCallIgnored
                        af.delete();
                    final java.io.FileWriter w = new java.io.FileWriter(af, true);
                    try {
                        w.write(new java.util.Date() + " " + spec.id + " " + e.getClass().getSimpleName() + "\n");
                    } finally {
                        w.close();
                    }
                } catch (Exception ignored) {
                }
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
        // add() runs on the main thread and opens a store, so how long it takes decides
        // whether ATAK stutters or ANRs. Measured, not assumed.
        Log.d(TAG, "add " + spec.id + " took " + (System.currentTimeMillis() - t0) + " ms on the main thread");
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

    /** Where a scoped layer looks: kind "me", "center" or "view", and the radius; then fetch. */
    public void setScope(final LoadedLayer l, String kind, double radiusM) {
        l.spec.scopeKind = kind;
        if (radiusM > 0)
            l.spec.scopeRadiusM = radiusM;
        save();
        changed();
        refresh(l);
    }

    /**
     * Follow the map and the operator. Runs on the GL render thread -- ATAK dispatches it
     * from GLMapView.dispatchCameraChanged over JNI, and touching a View or a map item
     * here is a native SIGSEGV with no Java stack -- so it only posts, and it coalesces:
     * during a pinch it fires every frame.
     */
    private final com.atakmap.map.AtakMapView.OnMapMovedListener moveWatch =
            new com.atakmap.map.AtakMapView.OnMapMovedListener() {
                @Override
                public void onMapMoved(com.atakmap.map.AtakMapView v, boolean animate) {
                    main.removeCallbacks(moveTick);
                    main.postDelayed(moveTick, MOVE_SETTLE_MS);
                    // The label check is cheap and the wait for it was the whole delay
                    // an operator saw crossing the level: its own, much shorter settle.
                    main.removeCallbacks(labelTick);
                    main.postDelayed(labelTick, LABEL_SETTLE_MS);
                }
            };

    private static final long LABEL_SETTLE_MS = 150;

    private final Runnable labelTick = new Runnable() {
        @Override
        public void run() {
            if (!started)
                return;
            final double res = mapView.getMapResolution();
            for (LoadedLayer l : snapshot())
                l.onMapResolution(res);
        }
    };
    private final com.atakmap.android.maps.PointMapItem.OnPointChangedListener selfWatch =
            new com.atakmap.android.maps.PointMapItem.OnPointChangedListener() {
                @Override
                public void onPointChanged(com.atakmap.android.maps.PointMapItem item) {
                    main.removeCallbacks(moveTick);
                    main.postDelayed(moveTick, MOVE_SETTLE_MS);
                }
            };
    private com.atakmap.android.maps.Marker selfWatched;
    /** About a second after the last movement, so a pan asks once, not per frame. */
    private static final long MOVE_SETTLE_MS = 1000;
    /** No layer is re-fetched for movement more often than this. */
    private static final long MOVE_MIN_GAP_MS = 20_000;
    private final java.util.Map<String, Long> lastMoveFetch = new java.util.HashMap<>();
    private final Runnable moveTick = new Runnable() {
        @Override
        public void run() {
            if (!started)
                return;
            final long now = System.currentTimeMillis();
            final double res = mapView.getMapResolution();
            boolean followsView = false;
            for (LoadedLayer l : snapshot()) {
                l.onMapResolution(res);
                followsView |= "view".equals(l.spec.scopeKind);
                if (!l.movedOutOfScope())
                    continue;
                final Long last = lastMoveFetch.get(l.spec.id);
                if (last != null && now - last < MOVE_MIN_GAP_MS)
                    continue;
                lastMoveFetch.put(l.spec.id, now);
                refresh(l);
            }
            // A list scoped to the view is drawn from what is in view now, so the pane
            // redraws in place after every settled move, fetch or no fetch.
            if (followsView)
                changed();
        }
    };

    private void attachFollow() {
        try {
            mapView.addOnMapMovedListener(moveWatch);
            final com.atakmap.android.maps.Marker self = mapView.getSelfMarker();
            if (self != null) {
                selfWatched = self;
                self.addOnPointChangedListener(selfWatch);
            }
        } catch (LinkageError | RuntimeException e) {
            Log.w(TAG, "could not follow the map", e);
        }
    }

    private void detachFollow() {
        try {
            mapView.removeOnMapMovedListener(moveWatch);
            if (selfWatched != null)
                selfWatched.removeOnPointChangedListener(selfWatch);
        } catch (LinkageError | RuntimeException e) {
            Log.w(TAG, "could not stop following the map", e);
        } finally {
            selfWatched = null;
            main.removeCallbacks(moveTick);
            main.removeCallbacks(labelTick);
        }
    }

    /** Point labels for one layer; a store rewrite from memory, no network. */
    public void setLabelLevel(final LoadedLayer l, final double metersPerPixel) {
        l.spec.labelGsd = metersPerPixel;
        save();
        l.busy = true;
        changed();
        worker.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    l.setLabelLevel(metersPerPixel);
                } finally {
                    l.busy = false;
                    changed();
                }
            }
        });
    }

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
        startLog("refreshNow " + l.spec.id + " begin");
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
        startLog("refreshNow " + l.spec.id + " token ok, fetching");
        l.refresh(token, new Runnable() {
            @Override
            public void run() {
                changed();
            }
        });
        startLog("refreshNow " + l.spec.id + " done: " + l.status);
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

    /** The feed's own answer to a typed name, for a scoped layer, on the worker; the callback runs on main. */
    public void searchFeed(final LoadedLayer l, final String text, final SearchCallback<List<LoadedLayer.Hit>> cb) {
        search(new java.util.concurrent.Callable<List<LoadedLayer.Hit>>() {
            @Override
            public List<LoadedLayer.Hit> call() throws Exception {
                String token = null;
                if (l.spec.portal != null && trusted(l.spec)) {
                    token = auth(l.spec.portal).getValidToken();
                    if (token == null)
                        throw new IllegalStateException("sign in to " + (l.spec.orgName != null ? l.spec.orgName : "the portal"));
                }
                return l.searchFeed(text, token, 60);
            }
        }, cb);
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
        final File bak = new File(stateFile.getPath() + ".bak");
        if (!stateFile.isFile() && !bak.isFile())
            return;
        try {
            JSONArray arr = stateFile.isFile() ? new JSONArray(readFile(stateFile)) : new JSONArray();
            if (arr.length() == 0 && bak.isFile()) {
                // An empty list where there was one is a lost list, not a choice.
                final JSONArray prev = new JSONArray(readFile(bak));
                if (prev.length() > 0) {
                    startLog("restore: main list empty, using the backup (" + prev.length() + " layers)");
                    arr = prev;
                }
            }
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
                // A built-in source's own definition wins over what was saved with it.
                Sources.migrate(spec);
                // Live NIFS layers saved with the four Event layers: give them all eight.
                if (spec.id.startsWith("nifs-live:") && spec.layerIds.length < Sources.NIFS_LIVE_LAYERS.length)
                    spec.layerIds = Sources.NIFS_LIVE_LAYERS;
                final LoadedLayer l = new LoadedLayer(spec, mapView, pluginContext,
                        new File(layersDir, spec.fileKey() + ".sqlite"), iconDir, nwcgIcons, sarcopIcons,
                        lineGlyph, polygonGlyph, o.optLong("lastRefresh", 0));
                try {
                    startLog("restore: attaching " + spec.id);
                    l.attach();
                    startLog("restore: attached " + spec.id);
                    l.setVisible(o.optBoolean("visible", true));
                    synchronized (layers) {
                        layers.add(l);
                    }
                } catch (Exception e) {
                    Log.w(TAG, "could not restore " + spec.id, e);
                    // Keep it: the next save() writes it back and the next start tries
                    // again. Dropping it turned one bad build into a deleted layer list
                    // (every incident layer, 2026-09-18).
                    synchronized (unrestored) {
                        unrestored.add(o);
                    }
                    try {
                        android.widget.Toast.makeText(mapView.getContext(), "Could not open " + spec.title
                                + "; kept for next start", android.widget.Toast.LENGTH_LONG).show();
                    } catch (Exception ignored) {
                    }
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "state restore failed", e);
        }
        changed();
    }

    private synchronized void save() {
        // A save after stop() writes the cleared list: on 2026-09-18 a refresh that had
        // waited two minutes for its markers came back after the plugin was unloaded and
        // wrote "[]" over five layers. Stopped means nothing more is written.
        if (!started) {
            startLog("save skipped: stopped");
            return;
        }
        try {
            final JSONArray arr = new JSONArray();
            for (LoadedLayer l : snapshot()) {
                final JSONObject o = new JSONObject();
                o.put("spec", l.spec.toJson());
                o.put("visible", l.isVisible());
                o.put("lastRefresh", l.lastRefresh);
                arr.put(o);
            }
            synchronized (unrestored) {
                for (JSONObject o : unrestored)
                    arr.put(o);
            }
            // The previous list survives one save as a backup, and restore() falls back to
            // it when the main file has nothing in it.
            final File bak = new File(stateFile.getPath() + ".bak");
            if (stateFile.isFile() && stateFile.length() > 2)
                //noinspection ResultOfMethodCallIgnored
                stateFile.renameTo(bak);
            try (OutputStream out = new FileOutputStream(stateFile)) {
                out.write(arr.toString(1).getBytes("UTF-8"));
            }
        } catch (Exception e) {
            Log.w(TAG, "state save failed", e);
        }
    }

    private void changed() {
        if (!started)
            return;
        main.post(new Runnable() {
            @Override
            public void run() {
                // A notification queued before stop() and delivered after it reached a
                // pane that no longer existed: NPE on the main thread, ATAK down
                // (2026-09-18 16:03). Stopped means nothing more is delivered.
                if (started && listener != null)
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
        try {
            // EGP's own DART glyphs; DartStyles reads them out of the icon directory by
            // name, so there is no map to carry around.
            final JSONObject didx = new JSONObject(readAsset("dart/index.json"));
            final Iterator<String> dk = didx.keys();
            while (dk.hasNext()) {
                final String file = didx.getString(dk.next());
                copyAsset("dart/" + file, new File(iconDir, "dart_" + file));
            }
        } catch (Exception e) {
            Log.w(TAG, "DART icons unavailable", e);
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
