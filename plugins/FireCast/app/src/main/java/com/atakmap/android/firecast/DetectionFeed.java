package com.atakmap.android.firecast;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import com.atakmap.android.features.FeatureDataStoreDeepMapItemQuery;
import com.atakmap.android.features.FeatureDataStoreMapOverlay;
import com.atakmap.android.ipc.AtakBroadcast;
import com.atakmap.android.ipc.AtakBroadcast.DocumentedIntentFilter;
import com.atakmap.android.maps.MapItem;
import com.atakmap.android.maps.MapView;
import com.atakmap.android.menu.PluginMenuParser;
import com.atakmap.coremap.filesystem.FileSystemUtils;
import com.atakmap.coremap.log.Log;
import com.atakmap.coremap.maps.coords.GeoBounds;
import com.atakmap.map.layer.feature.AttributeSet;
import com.atakmap.map.layer.feature.Feature;
import com.atakmap.map.layer.feature.FeatureCursor;
import com.atakmap.map.layer.feature.FeatureDataStore2;
import com.atakmap.map.layer.feature.FeatureLayer3;
import com.atakmap.map.layer.feature.FeatureSet;
import com.atakmap.map.layer.feature.FeatureSetCursor;
import com.atakmap.map.layer.feature.datastore.FeatureSetDatabase2;
import com.atakmap.map.layer.feature.geometry.Geometry;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The detections, on the map, for as long as the plugin is loaded.
 *
 * <p>This is a component and not a {@code Tool} on purpose, and the rule is written in
 * blood: ATAK ends the active tool whenever another one starts, a dropdown or pane
 * opens, or Back is pressed. FOBS 0.4 went to the field with a GPS recording inside its
 * tool and switching base maps ended the walk. Everything here that outlives a tap -
 * the poll, the fetch, the mask - lives at plugin scope, so opening another tool,
 * changing base map, closing the pane or backgrounding ATAK changes nothing about what
 * is on the map.
 *
 * <p>Two copies of the data, for two different reasons. Everything fetched is kept in
 * memory ({@link #all}), so a filter change is arithmetic rather than a network round
 * trip. What is currently shown is written to a file-backed store, so the map is
 * already drawn when ATAK restarts and a failed refresh leaves the last good picture up
 * rather than a blank map.
 */
public class DetectionFeed {

    private static final String TAG = "FireCast";
    public static final String ACTION_DETAILS = "com.atakmap.android.firecast.DETECTION_DETAILS";

    /** How often the timer looks at whether anything needs doing. */
    private static final long TICK_MS = 60000L;
    /** Most detections drawn at once, over every source. */
    private static final int MAX_TOTAL = 3000;
    /** Most from any one source, so a busy VIIRS day cannot crowd GOES off the map. */
    private static final int MAX_PER_SOURCE = 1500;
    /** The largest area a refresh will ask for, degrees. Beyond this the map is zoomed out past any use for a 375 m box. */
    private static final double MAX_SPAN_DEG = 12d;
    /** The smallest, so nudging the map does not refetch. */
    private static final double MIN_SPAN_DEG = 0.4d;

    public interface Listener {
        void onChanged();
    }

    private final MapView mapView;
    private final Context pluginContext;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final File root, storeFile, saveFile;
    /** When the store was last written, so ages can be repainted before they go stale. */
    private volatile long lastDraw;
    private static final long REPAINT_MS = 15 * 60000L;
    private final SharedPreferences state;
    private final List<DetectionSource> sources = Sources.all();
    private final IndustrialMask mask = new IndustrialMask();

    public final Filters filters;

    private FeatureSetDatabase2 store;
    private FeatureLayer3 layer;
    private FeatureDataStoreMapOverlay overlay;
    private DetectionDetailsReceiver details;
    private ActivityReceiver activity;
    private com.atakmap.android.menu.MapMenuEventListener tapListener;
    /** True when the area is a radius but there is no GPS fix, so it is measured from the map center. */
    public volatile boolean noFix;
    private long lastMoveFetch;
    private static final long MOVE_FETCH_GAP_MS = 3000L;
    private Listener listener;
    private boolean started;

    /** Every store access, on any thread, holds this: the store is not thread safe. */
    private final Object lock = new Object();

    /** Everything the last refresh fetched, filtered or not. */
    private volatile List<HeatDetection> all = new ArrayList<>();
    private volatile Map<String, HeatDetection> byUid = new HashMap<>();
    /** What each service says it holds for the box and window, whether or not it was fetched. */
    private volatile Map<String, Integer> available = new LinkedHashMap<>();
    private volatile Map<String, Integer> fetched = new LinkedHashMap<>();

    public volatile String status = "";
    public volatile boolean refreshing;
    public volatile long lastRefresh;
    public volatile int shownCount;
    /** The area the last refresh asked for. */
    public volatile Esri.Box box;
    /** The view was wider than {@link #MAX_SPAN_DEG}, so the box is the middle of it. */
    public volatile boolean clamped;
    /** At least one source hit its cap, so what is drawn is not everything there is. */
    public volatile boolean capped;

    public DetectionFeed(MapView mapView, Context pluginContext) {
        this.mapView = mapView;
        this.pluginContext = pluginContext;
        this.root = FileSystemUtils.getItem("tools/firecast");
        this.storeFile = new File(root, "detections.sqlite");
        this.saveFile = new File(root, "detections.json");
        this.state = mapView.getContext().getSharedPreferences("firecast", Context.MODE_PRIVATE);
        this.filters = new Filters(state);
        this.lastRefresh = state.getLong("lastRefresh", 0L);
    }

    private Runnable paneOpener;

    /** How the plugin shows its pane; a page's Back goes back to it. */
    public void setPaneOpener(Runnable r) {
        paneOpener = r;
    }

    /**
     * Back on a page returns to the pane, not to the bare map. The pages are drop-downs
     * and the pane is a Pane, so ATAK closes the pane when a page opens and nothing
     * reopens it by itself (operator, 2026-10-11: "back does not go back to list").
     * Posted, so the drop-down has finished closing before the pane is asked for.
     */
    public void reopenPane() {
        final Runnable r = paneOpener;
        if (r == null)
            return;
        main.postDelayed(r, 150);
    }

    /**
     * Shows or hides the detection layer without touching the store: while the fire
     * model waits for a tap the footprints must not catch it (a tap on a GOES box opened
     * the box instead of starting the run, dev 1, 2026-10-11).
     */
    public void setLayerVisible(boolean visible) {
        try {
            final FeatureLayer3 l = layer;
            if (l != null)
                l.setVisible(visible);
        } catch (Exception e) {
            Log.w(TAG, "layer visibility", e);
        }
    }

    public void setListener(Listener l) {
        listener = l;
    }

    public List<DetectionSource> sources() {
        return sources;
    }

    // ---- lifecycle ----------------------------------------------------------------

    public void start() {
        if (started)
            return;
        started = true;
        if (!root.isDirectory() && !root.mkdirs())
            Log.w(TAG, "could not create " + root);
        try {
            synchronized (lock) {
                attachLocked();
            }
        } catch (Exception e) {
            Log.e(TAG, "could not put the detection layer on the map", e);
            status = "the map layer could not be opened: " + e.getMessage();
        }
        details = new DetectionDetailsReceiver(mapView, pluginContext, this);
        final DocumentedIntentFilter f = new DocumentedIntentFilter();
        f.addAction(ACTION_DETAILS, "show what a heat detection does and does not mean");
        AtakBroadcast.getInstance().registerReceiver(details, f);
        activity = new ActivityReceiver(mapView, pluginContext, this);
        final DocumentedIntentFilter af = new DocumentedIntentFilter();
        af.addAction(ActivityReceiver.ACTION, "list where fires are burning, beyond the map extent");
        AtakBroadcast.getInstance().registerReceiver(activity, af);
        registerTap();
        mapView.addOnMapMovedListener(moved);
        main.postDelayed(timer, TICK_MS);
        worker.execute(new Runnable() {
            @Override
            public void run() {
                restore();
            }
        });
        refresh();
    }

    // ---- what the phone saved: the last fetch, read back at start --------------------

    /**
     * Rebuilds the detections from the file the last fetch wrote, so that after a
     * restart with no network the list, the counts and a tap on a box all work from
     * what the phone holds; the map already came back from the store on its own. Each
     * record goes through the same reader a fetch used (CLAUDE.md, "Downloaded data
     * survives a restart with no network"). Worker thread, queued ahead of the first
     * fetch on the same worker.
     */
    private void restore() {
        if (!saveFile.isFile())
            return;
        try {
            final org.json.JSONObject saved = new org.json.JSONObject(readText(saveFile));
            final List<HeatDetection> read = new ArrayList<>();
            final org.json.JSONObject bySource = saved.optJSONObject("sources");
            for (DetectionSource s : sources) {
                final org.json.JSONArray records = bySource == null ? null : bySource.optJSONArray(s.id());
                for (int i = 0; records != null && i < records.length(); i++) {
                    final org.json.JSONObject r = records.optJSONObject(i);
                    if (r == null)
                        continue;
                    final org.json.JSONObject a = r.optJSONObject("attributes"), geom = r.optJSONObject("geometry");
                    if (a == null || geom == null)
                        continue;
                    final Geometry g = Esri.fromEsriJson(geom);
                    final HeatDetection d = g == null ? null : s.read(a, g, geom);
                    if (d == null)
                        continue;
                    d.inIndustrialMask = r.optBoolean("industrial", false);
                    read.add(d);
                }
            }
            if (!all.isEmpty())
                return;   // a fetch finished first; what it brought is newer
            final Map<String, HeatDetection> index = new HashMap<>();
            for (HeatDetection d : read)
                index.put(d.uid(), d);
            final Map<String, Integer> counts = new LinkedHashMap<>(), got = new LinkedHashMap<>();
            final org.json.JSONObject av = saved.optJSONObject("available"), fe = saved.optJSONObject("fetched");
            for (DetectionSource s : sources) {
                if (av != null && av.has(s.id()))
                    counts.put(s.id(), av.optInt(s.id()));
                if (fe != null && fe.has(s.id()))
                    got.put(s.id(), fe.optInt(s.id()));
            }
            final org.json.JSONArray b = saved.optJSONArray("box");
            all = read;
            byUid = index;
            available = counts;
            fetched = got;
            capped = saved.optBoolean("capped", false);
            if (b != null && b.length() == 4)
                box = new Esri.Box(b.getDouble(0), b.getDouble(1), b.getDouble(2), b.getDouble(3));
            Log.d(TAG, "restored " + read.size() + " detections saved " + (lastRefresh > 0
                    ? ((System.currentTimeMillis() - lastRefresh) / 60000L) + " min ago" : "earlier"));
            changed();
        } catch (Exception e) {
            Log.w(TAG, "could not read the saved detections", e);
        }
    }

    private static String readText(File f) throws java.io.IOException {
        final StringBuilder sb = new StringBuilder();
        final java.io.Reader r = new java.io.InputStreamReader(
                new java.io.FileInputStream(f), java.nio.charset.StandardCharsets.UTF_8);
        try {
            final char[] buf = new char[8192];
            int n;
            while ((n = r.read(buf)) > 0)
                sb.append(buf, 0, n);
        } finally {
            r.close();
        }
        return sb.toString();
    }

    /** Writes what a fetch brought, every source, to a temp file then into place. Worker thread. */
    private void save() {
        try {
            final org.json.JSONObject out = new org.json.JSONObject();
            final org.json.JSONObject bySource = new org.json.JSONObject();
            for (HeatDetection d : all) {
                if (d.record == null)
                    continue;
                org.json.JSONArray arr = bySource.optJSONArray(d.sourceId);
                if (arr == null) {
                    arr = new org.json.JSONArray();
                    bySource.put(d.sourceId, arr);
                }
                final org.json.JSONObject r = new org.json.JSONObject();
                r.put("attributes", d.record.opt("attributes"));
                r.put("geometry", d.record.opt("geometry"));
                if (d.inIndustrialMask)
                    r.put("industrial", true);
                arr.put(r);
            }
            out.put("sources", bySource);
            final org.json.JSONObject av = new org.json.JSONObject(), fe = new org.json.JSONObject();
            for (Map.Entry<String, Integer> e : available.entrySet())
                av.put(e.getKey(), e.getValue());
            for (Map.Entry<String, Integer> e : fetched.entrySet())
                fe.put(e.getKey(), e.getValue());
            out.put("available", av);
            out.put("fetched", fe);
            out.put("capped", capped);
            final Esri.Box b = box;
            if (b != null)
                out.put("box", new org.json.JSONArray().put(b.south).put(b.west).put(b.north).put(b.east));
            final File tmp = new File(root, "detections.json.tmp");
            final java.io.Writer w = new java.io.OutputStreamWriter(
                    new java.io.FileOutputStream(tmp), java.nio.charset.StandardCharsets.UTF_8);
            try {
                w.write(out.toString());
            } finally {
                w.close();
            }
            if (!tmp.renameTo(saveFile)) {
                saveFile.delete();
                if (!tmp.renameTo(saveFile))
                    Log.w(TAG, "could not move the saved detections into place");
            }
        } catch (Exception e) {
            Log.w(TAG, "could not save the detections", e);
        }
    }

    /**
     * The map follows a pan on its own. {@code onMapMoved} runs on ATAK's GL thread,
     * where touching a View or a map item is a native crash with no Java stack, so it
     * only reschedules a settle on main; the work happens 400 ms after the last move.
     */
    private final com.atakmap.map.AtakMapView.OnMapMovedListener moved =
            new com.atakmap.map.AtakMapView.OnMapMovedListener() {
                @Override
                public void onMapMoved(com.atakmap.map.AtakMapView v, boolean animate) {
                    main.removeCallbacks(settled);
                    main.postDelayed(settled, 400);
                }
            };
    private final Runnable settled = new Runnable() {
        @Override
        public void run() {
            if (started)
                followScope();
        }
    };

    /**
     * Re-reads what is in the area and fetches again only when the area left the box
     * the last fetch covered. A move the rate limit drops is picked up by the minute
     * tick, which asks the same question, so the list is never stale past a minute.
     * Main thread. Also what a change of Area calls.
     */
    public void followScope() {
        changed();
        final long now = System.currentTimeMillis();
        if (refreshing || now - lastMoveFetch < MOVE_FETCH_GAP_MS)
            return;
        try {
            if (movedOffTheBox()) {
                lastMoveFetch = now;
                refresh();
            }
        } catch (Exception e) {
            Log.w(TAG, "follow failed", e);
        }
    }

    /**
     * A tap on a detection opens its page, with no radial in between. The radial's
     * only useful button here was Details, so it was a step in the way (operator,
     * 2026-10-11, the same call made on Atmosphere and AirAware two days earlier).
     *
     * <p>ATAK asks this listener before it opens a radial; answering true stops the
     * radial and tells ATAK the tap was handled, so it never pins its own callout to
     * the top of the map. Only items carrying {@code firecast_uid} are claimed; every
     * other plugin's items keep their radials. The detection's own radial stays set on
     * the item as the fallback for an ATAK with no menu receiver.
     */
    private void registerTap() {
        tapListener = new com.atakmap.android.menu.MapMenuEventListener() {
            @Override
            public boolean onShowMenu(final MapItem item) {
                if (item == null || details == null)
                    return false;
                final String uid = item.getMetaString("firecast_uid", null);
                if (uid == null)
                    return false;
                // A moment later, not now: a pick from ATAK's Select Item list closes
                // the list and then posts its own show-details, which would close a
                // page opened inline. A plain tap does not notice the quarter second.
                main.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        final HeatDetection d = byUid(uid);
                        if (d == null || details == null)
                            return;
                        clearAtakSelection();
                        details.show(d);
                    }
                }, 250);
                return true;
            }

            @Override
            public void onHideMenu(MapItem item) {
            }
        };
        final com.atakmap.android.menu.MapMenuReceiver menus =
                com.atakmap.android.menu.MapMenuReceiver.getInstance();
        if (menus != null)
            menus.addEventListener(tapListener);
        else
            Log.w(TAG, "no radial menu receiver; taps keep ATAK's own menu");
    }

    /**
     * Take ATAK's own selection off the map: the focus and the callout it pins to the
     * top. A pick from ATAK's Select Item list selects the item itself before the page
     * opens, and answering the menu question does not reach that path.
     */
    private static void clearAtakSelection() {
        try {
            AtakBroadcast.getInstance().sendBroadcast(
                    new android.content.Intent("com.atakmap.android.maps.UNFOCUS"));
            AtakBroadcast.getInstance().sendBroadcast(
                    new android.content.Intent("com.atakmap.android.maps.HIDE_DETAILS"));
        } catch (RuntimeException e) {
            Log.d(TAG, "could not clear ATAK's selection: " + e);
        }
    }

    public void stop() {
        started = false;
        main.removeCallbacks(timer);
        main.removeCallbacks(settled);
        try {
            mapView.removeOnMapMovedListener(moved);
        } catch (Exception ignored) {
        }
        if (tapListener != null) {
            final com.atakmap.android.menu.MapMenuReceiver menus =
                    com.atakmap.android.menu.MapMenuReceiver.getInstance();
            if (menus != null)
                menus.removeEventListener(tapListener);
            tapListener = null;
        }
        if (details != null) {
            try {
                AtakBroadcast.getInstance().unregisterReceiver(details);
            } catch (Exception ignored) {
            }
            details.dispose();
            details = null;
        }
        if (activity != null) {
            try {
                AtakBroadcast.getInstance().unregisterReceiver(activity);
            } catch (Exception ignored) {
            }
            activity.dispose();
            activity = null;
        }
        synchronized (lock) {
            detachLocked();
        }
        worker.shutdownNow();
    }

    private void attachLocked() throws Exception {
        store = new FeatureSetDatabase2(storeFile);
        final FeatureDataStore2.FeatureQueryParameters visibleOnly = new FeatureDataStore2.FeatureQueryParameters();
        visibleOnly.visibleOnly = true;
        layer = new FeatureLayer3("Fire Cast heat detections", store, visibleOnly);
        final FeatureDataStoreDeepMapItemQuery query = new FeatureDataStoreDeepMapItemQuery(layer) {
            @Override
            protected MapItem featureToMapItem(Feature feature) {
                final MapItem item = super.featureToMapItem(feature);
                item.setMetaString("menu", PluginMenuParser.getMenu(pluginContext, "menu/detection.xml"));
                item.setMetaLong("featureid", feature.getId());
                // The hit-test query asks the store for features with their attributes
                // stripped, so this one's have to be fetched back before they can be read.
                AttributeSet a = feature.getAttributes();
                if (a == null)
                    a = attributesOf(feature.getId());
                String uid = null, title = null;
                if (a != null) {
                    try {
                        uid = a.getStringAttribute("_uid");
                    } catch (Exception ignored) {
                    }
                    try {
                        title = a.getStringAttribute("_title");
                    } catch (Exception ignored) {
                    }
                }
                if (uid != null)
                    item.setMetaString("firecast_uid", uid);
                if (title == null || title.isEmpty())
                    title = feature.getName();
                item.setMetaString("title", title);
                item.setMetaString("callsign", title);
                return item;
            }

            // ATAK hands the same feature back once per hit-test control; keep the first.
            @Override
            public java.util.SortedSet<MapItem> deepHitTest(MapView view,
                    com.atakmap.map.hittest.HitTestQueryParameters params,
                    Map<com.atakmap.map.layer.Layer2, java.util.Collection<com.atakmap.map.hittest.HitTestControl>> controls) {
                return dedupe(super.deepHitTest(view, params, controls));
            }

            @Override
            public java.util.SortedSet<MapItem> deepHitTestItems(int xpos, int ypos,
                    com.atakmap.coremap.maps.coords.GeoPoint point, MapView view) {
                return dedupe(super.deepHitTestItems(xpos, ypos, point, view));
            }

            private java.util.SortedSet<MapItem> dedupe(java.util.SortedSet<MapItem> hits) {
                if (hits == null || hits.isEmpty())
                    return hits;
                final java.util.Set<Long> seen = new java.util.HashSet<>();
                final java.util.SortedSet<MapItem> out = new java.util.TreeSet<>(hits.comparator());
                for (MapItem m : hits) {
                    final long fid = m.getMetaLong("featureid", -1);
                    if (fid < 0 || seen.add(fid))
                        out.add(m);
                }
                return out;
            }
        };
        overlay = new FeatureDataStoreMapOverlay(mapView.getContext(), store, null,
                "Fire Cast heat detections", "file://asset/nothing", query, null, null);
        // A reload interrupted mid-write can leave two generations of a set; keep the newest.
        dedupeSets();
        mapView.getMapOverlayManager().addFilesOverlay(overlay);
        mapView.addLayer(MapView.RenderStack.VECTOR_OVERLAYS, layer);
        shownCount = countFeatures();
        status = shownCount > 0 ? "showing the last set fetched" : "nothing fetched yet";
    }

    private void detachLocked() {
        try {
            if (layer != null)
                mapView.removeLayer(MapView.RenderStack.VECTOR_OVERLAYS, layer);
            if (overlay != null)
                mapView.getMapOverlayManager().removeOverlay(overlay);
            if (store != null)
                store.dispose();
        } catch (Exception e) {
            Log.w(TAG, "detach failed", e);
        }
        layer = null;
        overlay = null;
        store = null;
    }

    /**
     * Once a minute: refresh if the interval has elapsed, or if the map has been moved
     * off the area the last fetch covered.
     *
     * <p>Deliberately a timer and not a map-moved listener. ATAK calls {@code onMapMoved}
     * on the GL thread, where touching a view or a map item is a native crash with no
     * Java stack trace; a tick on the main thread that reads the bounds is the same
     * answer a second later and cannot do that.
     */
    private final Runnable timer = new Runnable() {
        @Override
        public void run() {
            if (!started)
                return;
            try {
                final long now = System.currentTimeMillis();
                final boolean due = filters.refreshMinutes > 0
                        && now - lastRefresh >= filters.refreshMinutes * 60000L;
                if (!refreshing && (due || movedOffTheBox()))
                    refresh();
                else if (!refreshing && !all.isEmpty() && now - lastDraw > REPAINT_MS)
                    redraw();   // colors are age, and age moves while the data does not
            } catch (Exception e) {
                Log.w(TAG, "tick failed", e);
            }
            // Doze stops this firing and does not queue up the missed ones, which is what
            // is wanted: on wake it fetches once instead of backfilling an hour of ticks.
            main.postDelayed(this, TICK_MS);
        }
    };

    /** Whether the map has left the area the drawn detections were fetched for. */
    private boolean movedOffTheBox() {
        final Esri.Box fetchedFor = box;
        if (fetchedFor == null)
            return true;
        final Esri.Box view = scopeBox();
        if (view == null)
            return false;
        return !(fetchedFor.south <= view.south && fetchedFor.north >= view.north
                && fetchedFor.west <= view.west && fetchedFor.east >= view.east);
    }

    // ---- the area: what is in view, or a circle around the operator -------------

    /** The operator's own position, or null without a fix. A point at 0,0 is not a fix. */
    private double[] ownPosition() {
        try {
            final com.atakmap.android.maps.Marker self = mapView.getSelfMarker();
            final com.atakmap.coremap.maps.coords.GeoPoint p = self == null ? null : self.getPoint();
            if (p == null || !p.isValid()
                    || (Math.abs(p.getLatitude()) < 0.01 && Math.abs(p.getLongitude()) < 0.01))
                return null;
            return new double[] { p.getLatitude(), p.getLongitude() };
        } catch (Exception e) {
            return null;
        }
    }

    /** Where a radius is measured from: the operator, or the map center with no fix. */
    public double[] areaPoint() {
        final double[] me = ownPosition();
        noFix = me == null;
        if (me != null)
            return me;
        final com.atakmap.coremap.maps.coords.GeoPoint c = mapView.getPoint().get();
        if (c == null || !c.isValid())
            return null;
        return new double[] { c.getLatitude(), c.getLongitude() };
    }

    /** The box the area occupies: the view, or the square around the radius. */
    private Esri.Box scopeBox() {
        if (!filters.areaIsRadius())
            return viewBox();
        final double[] p = areaPoint();
        return p == null ? null : boxAround(p, filters.areaM);
    }

    /** The square that holds a circle of that radius around a point. */
    public static Esri.Box boxAround(double[] p, double meters) {
        final double dLat = meters / 111320d;
        final double dLon = meters / (111320d * Math.max(0.02d, Math.cos(Math.toRadians(p[0]))));
        return new Esri.Box(Math.max(-90, p[0] - dLat), Math.max(-180, p[1] - dLon),
                Math.min(90, p[0] + dLat), Math.min(180, p[1] + dLon));
    }

    /** Meters between a point and a position. */
    public static double metersBetween(double[] p, double lat, double lon) {
        return com.atakmap.coremap.maps.coords.GeoCalculations.distanceTo(
                new com.atakmap.coremap.maps.coords.GeoPoint(p[0], p[1]),
                new com.atakmap.coremap.maps.coords.GeoPoint(lat, lon));
    }

    /** Distance from the area's point, meters, or -1 when there is no point. */
    public double distanceM(HeatDetection d) {
        final double[] p = areaPoint();
        if (p == null)
            return -1;
        return com.atakmap.coremap.maps.coords.GeoCalculations.distanceTo(
                new com.atakmap.coremap.maps.coords.GeoPoint(p[0], p[1]),
                new com.atakmap.coremap.maps.coords.GeoPoint(d.lat, d.lon));
    }

    /** The area in words for the status line: "the area on screen", "within 25 mi of you". */
    public String areaText() {
        if (!filters.areaIsRadius())
            return "the area on screen";
        return "within " + Units.formatBig(filters.areaM)
                + (noFix ? " of the map center, no GPS fix" : " of you");
    }

    /** What the operator can see right now, as a box; null when the map cannot say. */
    public Esri.Box viewBox() {
        try {
            final GeoBounds b = mapView.getBounds();
            if (b == null)
                return null;
            double south = b.getSouth(), north = b.getNorth(), west = b.getWest(), east = b.getEast();
            if (Double.isNaN(south) || Double.isNaN(north) || Double.isNaN(west) || Double.isNaN(east))
                return null;
            // A view across the antimeridian comes back with west greater than east. Rather
            // than fetch the whole world the long way round, the area around the center is
            // used; the pane says the area is the middle of the view either way.
            if (west > east)
                return null;
            return new Esri.Box(south, west, north, east);
        } catch (Exception e) {
            Log.w(TAG, "could not read the map extent", e);
            return null;
        }
    }

    /** The area a refresh asks for: what is on screen, with a margin, within sane limits. */
    private Esri.Box refreshBox() {
        Esri.Box b = scopeBox();
        if (b == null) {
            // No usable extent: a box around wherever the self marker or the map center is.
            final com.atakmap.coremap.maps.coords.GeoPoint p = mapView.getPoint().get();
            final double lat = p == null ? 0 : p.getLatitude(), lon = p == null ? 0 : p.getLongitude();
            clamped = true;
            return new Esri.Box(lat - 1, lon - 1, lat + 1, lon + 1);
        }
        b = b.grown(0.3d);
        final double cLat = (b.south + b.north) / 2d, cLon = (b.west + b.east) / 2d;
        double halfLat = Math.max(MIN_SPAN_DEG, b.spanLat()) / 2d;
        double halfLon = Math.max(MIN_SPAN_DEG, b.spanLon()) / 2d;
        clamped = false;
        if (halfLat * 2 > MAX_SPAN_DEG || halfLon * 2 > MAX_SPAN_DEG) {
            halfLat = Math.min(halfLat, MAX_SPAN_DEG / 2d);
            halfLon = Math.min(halfLon, MAX_SPAN_DEG / 2d);
            clamped = true;
        }
        return new Esri.Box(Math.max(-90, cLat - halfLat), Math.max(-180, cLon - halfLon),
                Math.min(90, cLat + halfLat), Math.min(180, cLon + halfLon));
    }

    // ---- fetching -----------------------------------------------------------------

    /** Fetches on the worker. Safe to call from anywhere; a refresh already running wins. */
    public void refresh() {
        if (refreshing)
            return;
        final Esri.Box want = refreshBox();
        // Marked now, on the caller's thread, so a pane that redraws before the worker
        // picks this up says "looking" rather than "none here".
        refreshing = true;
        status = "refreshing";
        changed();
        worker.execute(new Runnable() {
            @Override
            public void run() {
                fetchNow(want);
            }
        });
    }

    /** Redraws from what is already in memory: a filter change costs no network. */
    public void redraw() {
        worker.execute(new Runnable() {
            @Override
            public void run() {
                synchronized (lock) {
                    drawLocked();
                }
                changed();
            }
        });
    }

    private void fetchNow(Esri.Box want) {
        refreshing = true;
        status = "refreshing";
        changed();
        final long now = System.currentTimeMillis();
        final long since = filters.since(now);
        final List<HeatDetection> gathered = new ArrayList<>();
        final Map<String, Integer> counts = new LinkedHashMap<>();
        final Map<String, Integer> got = new LinkedHashMap<>();
        final List<String> problems = new ArrayList<>();
        final java.util.Set<String> failed = new java.util.HashSet<>();
        boolean anyWorked = false, anyCapped = false;
        for (DetectionSource s : sources) {
            if (!started)
                break;
            if (!filters.isOn(s.id()))
                continue;
            try {
                // Ask how many there are before asking for them: the filter button can then
                // say what it costs, and a capped fetch can say so rather than looking whole.
                final int n = s.count(want, since);
                counts.put(s.id(), n);
                final int budget = MAX_TOTAL - gathered.size();
                if (budget <= 0) {
                    // No room left. Say so rather than asking for more: a limit of 0 means
                    // "no limit" to the query, which would fetch the whole feed.
                    got.put(s.id(), 0);
                    anyCapped = true;
                    continue;
                }
                final List<HeatDetection> part = s.fetch(want, since, Math.min(MAX_PER_SOURCE, budget));
                got.put(s.id(), part.size());
                if (n > part.size())
                    anyCapped = true;
                gathered.addAll(part);
                anyWorked = true;
                status = "refreshing: " + gathered.size();
                changed();
            } catch (Exception e) {
                Log.w(TAG, s.id() + " fetch failed", e);
                problems.add(s.title() + ": " + message(e));
                failed.add(s.id());
            }
        }
        if (!started) {
            refreshing = false;
            return;
        }
        // A feed that did not answer keeps what it last gave: a partial fetch merges
        // with what is held rather than deleting the feeds that failed.
        if (!failed.isEmpty()) {
            for (HeatDetection d : all)
                if (failed.contains(d.sourceId))
                    gathered.add(d);
            for (String id : failed) {
                if (available.containsKey(id))
                    counts.put(id, available.get(id));
                if (fetched.containsKey(id))
                    got.put(id, fetched.get(id));
            }
        }
        if (!anyWorked && !problems.isEmpty()) {
            // Nothing new arrived. The map keeps the last good set rather than going blank,
            // and the pane says how old that set is instead of implying it is current.
            refreshing = false;
            status = "no update (" + problems.get(0) + ")";
            changed();
            return;
        }
        try {
            mask.load(want);
        } catch (Exception e) {
            Log.w(TAG, "industrial mask fetch failed", e);
            problems.add("industrial mask: " + message(e));
        }
        for (HeatDetection d : gathered)
            d.inIndustrialMask = mask.contains(d.lat, d.lon);

        final Map<String, HeatDetection> index = new HashMap<>();
        for (HeatDetection d : gathered)
            index.put(d.uid(), d);
        all = gathered;
        byUid = index;
        available = counts;
        fetched = got;
        box = want;
        capped = anyCapped;
        lastRefresh = System.currentTimeMillis();
        state.edit().putLong("lastRefresh", lastRefresh).apply();
        synchronized (lock) {
            drawLocked();
        }
        save();
        refreshing = false;
        status = problems.isEmpty() ? "ok" : "partial: " + problems.get(0);
        changed();
    }

    private static String message(Exception e) {
        final String m = e.getMessage();
        return m == null || m.trim().isEmpty() ? e.getClass().getSimpleName() : m.trim();
    }

    // ---- drawing ------------------------------------------------------------------

    /**
     * Puts exactly what passes the filters into the store: new sets in first, the
     * previous ones out after, so the map is never momentarily empty. Lock held.
     */
    private void drawLocked() {
        if (store == null)
            return;
        final long now = System.currentTimeMillis();
        final long window = filters.windowMillis();
        final List<HeatDetection> source = all;
        // Nothing in memory while the store holds the last picture means the file has
        // not been read back yet, not that there is nothing to show. Drawing now would
        // delete every set and leave the map blank until the network returns.
        if (source.isEmpty() && lastRefresh > 0 && countFeatures() > 0) {
            Log.d(TAG, "draw skipped: memory empty, store holds the last picture");
            return;
        }
        boolean bulk = false;
        try {
            // Bulk mode: one content-changed notification at the end rather than one per
            // insert, each of which has ATAK re-query the store on its main thread.
            store.acquireModifyLock(true);
            bulk = true;
            final List<Long> old = existingSets();
            final Map<String, Long> sets = new HashMap<>();
            int drawn = 0;
            for (HeatDetection d : source) {
                if (!filters.shows(d, now))
                    continue;
                Long fsid = sets.get(d.sourceId);
                if (fsid == null) {
                    fsid = newSet(d.sourceTitle);
                    sets.put(d.sourceId, fsid);
                }
                final AttributeSet a = new AttributeSet();
                a.setAttribute("_uid", d.uid());
                a.setAttribute("_title", d.sourceTitle + " heat detection");
                final Geometry g = footprintGeometry(d);
                store.insertFeature(new Feature(fsid, d.sourceTitle, g,
                        DetectionStyles.forDetection(d, now, window), a,
                        Feature.AltitudeMode.ClampToGround, 0d));
                drawn++;
            }
            if (filters.showMaskAreas)
                drawMaskAreas(sets);
            for (Long id : old) {
                try {
                    store.deleteFeatureSet(id);
                } catch (Exception e) {
                    Log.w(TAG, "old set " + id, e);
                }
            }
            shownCount = drawn;
            lastDraw = now;
            Log.d(TAG, "drew " + drawn + " of " + source.size() + " fetched");
        } catch (Exception e) {
            Log.w(TAG, "draw failed", e);
        } finally {
            if (bulk)
                store.releaseModifyLock();
        }
    }

    /**
     * The footprint, plus a point at its center in the same feature. The point is what
     * carries the detection at a zoom where a 375 m box is under a pixel wide; keeping
     * both in one feature means there is still one thing to tap and one row in the
     * chooser. A mapped RADR area is drawn as it came and gets no center point - it is
     * already big enough to see, and a dot in the middle of a burn scar would read as a
     * detection that is not there.
     */
    private static Geometry footprintGeometry(HeatDetection d) {
        // The footprint alone. A center Point used to ride along in a collection so the
        // detection had a mark at any zoom; without a point style the renderer would
        // give that Point its default 32 px icon, so the Point goes with the dot.
        return d.footprint.geometry(d.lat, d.lon);
    }

    /**
     * The mask polygons themselves, when the operator wants to see what is being flagged.
     * Drawn from the shapes the mask already holds in memory: this runs under the store
     * lock, and fetching them here would have put a network round trip inside it, on
     * every filter change rather than every refresh.
     */
    private void drawMaskAreas(Map<String, Long> sets) throws Exception {
        final List<Geometry> areas = mask.shapes();
        if (areas.isEmpty())
            return;
        final long fsid = newSet("Industrial mask");
        sets.put("mask", fsid);
        for (Geometry g : areas) {
            final AttributeSet a = new AttributeSet();
            a.setAttribute("_title", "Known industrial heat source");
            store.insertFeature(new Feature(fsid, "Industrial mask", g,
                    DetectionStyles.maskStyle(), a, Feature.AltitudeMode.ClampToGround, 0d));
        }
    }

    /**
     * A feature set that draws at every zoom. A footprint is the thing being shown, so
     * gating it on resolution would hide the answer at exactly the zoom where an
     * operator is looking for where to go.
     */
    private long newSet(String name) throws Exception {
        final long id = store.insertFeatureSet(
                new FeatureSet("FireCast", "detections", name, Double.MAX_VALUE, 0d));
        store.setFeatureSetVisible(id, true);
        return id;
    }

    private List<Long> existingSets() {
        final List<Long> ids = new ArrayList<>();
        try {
            final FeatureSetCursor c = store.queryFeatureSets(new FeatureDataStore2.FeatureSetQueryParameters());
            try {
                while (c.moveToNext())
                    ids.add(c.get().getId());
            } finally {
                c.close();
            }
        } catch (Exception e) {
            Log.w(TAG, "set listing failed", e);
        }
        return ids;
    }

    private void dedupeSets() {
        final Map<String, Long> newest = new HashMap<>();
        final List<Long> drop = new ArrayList<>();
        try {
            final FeatureSetCursor c = store.queryFeatureSets(new FeatureDataStore2.FeatureSetQueryParameters());
            try {
                while (c.moveToNext()) {
                    final Long prev = newest.get(c.getName());
                    if (prev == null) {
                        newest.put(c.getName(), c.getId());
                    } else if (c.getId() > prev) {
                        drop.add(prev);
                        newest.put(c.getName(), c.getId());
                    } else {
                        drop.add(c.getId());
                    }
                }
            } finally {
                c.close();
            }
            for (Long id : drop)
                store.deleteFeatureSet(id);
        } catch (Exception e) {
            Log.w(TAG, "set dedupe failed", e);
        }
    }

    private int countFeatures() {
        try {
            return store == null ? 0
                    : store.queryFeaturesCount(new FeatureDataStore2.FeatureQueryParameters());
        } catch (Exception e) {
            return 0;
        }
    }

    private AttributeSet attributesOf(long fid) {
        final FeatureDataStore2 s = store;
        if (s == null)
            return null;
        FeatureCursor c = null;
        try {
            final FeatureDataStore2.FeatureQueryParameters p = new FeatureDataStore2.FeatureQueryParameters();
            p.ids = Collections.singleton(fid);
            p.ignoredFeatureProperties = FeatureDataStore2.PROPERTY_FEATURE_GEOMETRY
                    | FeatureDataStore2.PROPERTY_FEATURE_STYLE;
            p.limit = 1;
            c = s.queryFeatures(p);
            if (c.moveToNext())
                return c.get().getAttributes();
        } catch (Exception e) {
            Log.w(TAG, "attributes of " + fid + " failed", e);
        } finally {
            if (c != null)
                try {
                    c.close();
                } catch (Exception ignored) {
                }
        }
        return null;
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

    // ---- what the pane asks ------------------------------------------------------

    public HeatDetection byUid(String uid) {
        return uid == null ? null : byUid.get(uid);
    }

    /** How many the service says are in the box, whether fetched or not. */
    public int availableFrom(String sourceId) {
        final Integer n = available.get(sourceId);
        return n == null ? -1 : n;
    }

    public int fetchedFrom(String sourceId) {
        final Integer n = fetched.get(sourceId);
        return n == null ? -1 : n;
    }

    public int fetchedTotal() {
        return all.size();
    }

    /** How many pass the filters, without building or sorting the list to find out. */
    public int shownNow() {
        final long now = System.currentTimeMillis();
        int n = 0;
        for (HeatDetection d : all)
            if (filters.shows(d, now))
                n++;
        return n;
    }

    /** Everything the filters pass, newest first. */
    public List<HeatDetection> shown() {
        final long now = System.currentTimeMillis();
        final List<HeatDetection> out = new ArrayList<>();
        for (HeatDetection d : all)
            if (filters.shows(d, now))
                out.add(d);
        sortNewestFirst(out);
        return out;
    }

    /**
     * What the operator can see, newest first. The list follows the map extent rather
     * than the fetched box: a list of a thousand detections two counties away is not a
     * list, and the one thing an operator wants from it is what is in front of them.
     */
    public List<HeatDetection> inView(int max) {
        final List<HeatDetection> out = inArea();
        if (filters.areaIsRadius())
            sortNearestFirst(out);
        else
            sortNewestFirst(out);
        return out.size() > max ? new ArrayList<>(out.subList(0, max)) : out;
    }

    /** How many pass the filters inside the area, before any list cap. */
    public int inViewCount() {
        return inArea().size();
    }

    /**
     * One rule for the list: inside the view, or inside the radius. The map draws the
     * fetched box, which is the area grown by a margin, so what the list names is on
     * the map and a little more besides.
     */
    private List<HeatDetection> inArea() {
        final long now = System.currentTimeMillis();
        final List<HeatDetection> out = new ArrayList<>();
        if (filters.areaIsRadius()) {
            final double[] p = areaPoint();
            final com.atakmap.coremap.maps.coords.GeoPoint from = p == null ? null
                    : new com.atakmap.coremap.maps.coords.GeoPoint(p[0], p[1]);
            for (HeatDetection d : all) {
                if (!filters.shows(d, now))
                    continue;
                if (from != null && com.atakmap.coremap.maps.coords.GeoCalculations.distanceTo(from,
                        new com.atakmap.coremap.maps.coords.GeoPoint(d.lat, d.lon)) > filters.areaM)
                    continue;
                out.add(d);
            }
            return out;
        }
        final Esri.Box view = viewBox();
        for (HeatDetection d : all) {
            if (!filters.shows(d, now))
                continue;
            if (view != null && !view.contains(d.lat, d.lon))
                continue;
            out.add(d);
        }
        return out;
    }

    private void sortNearestFirst(List<HeatDetection> list) {
        final double[] p = areaPoint();
        if (p == null) {
            sortNewestFirst(list);
            return;
        }
        final com.atakmap.coremap.maps.coords.GeoPoint from =
                new com.atakmap.coremap.maps.coords.GeoPoint(p[0], p[1]);
        final Map<HeatDetection, Double> dist = new HashMap<>();
        for (HeatDetection d : list)
            dist.put(d, com.atakmap.coremap.maps.coords.GeoCalculations.distanceTo(from,
                    new com.atakmap.coremap.maps.coords.GeoPoint(d.lat, d.lon)));
        Collections.sort(list, new Comparator<HeatDetection>() {
            @Override
            public int compare(HeatDetection a, HeatDetection b) {
                return Double.compare(dist.get(a), dist.get(b));
            }
        });
    }

    private static void sortNewestFirst(List<HeatDetection> list) {
        Collections.sort(list, new Comparator<HeatDetection>() {
            @Override
            public int compare(HeatDetection a, HeatDetection b) {
                return Long.compare(b.acquired, a.acquired);
            }
        });
    }

    /** Frames one detection's footprint on the map. */
    public void goTo(HeatDetection d) {
        try {
            final double halfDeg = Math.max(0.004d,
                    Math.max(d.footprint.alongScanM, d.footprint.alongTrackM) / 111320d * 4d);
            final com.atakmap.coremap.maps.coords.GeoPoint[] corners = {
                    new com.atakmap.coremap.maps.coords.GeoPoint(d.lat - halfDeg, d.lon - halfDeg),
                    new com.atakmap.coremap.maps.coords.GeoPoint(d.lat + halfDeg, d.lon + halfDeg) };
            com.atakmap.android.util.ATAKUtilities.scaleToFit(mapView, corners, 0d,
                    mapView.getWidth(), mapView.getHeight());
        } catch (Exception e) {
            Log.w(TAG, "go to failed", e);
        }
    }

    public IndustrialMask mask() {
        return mask;
    }
}
