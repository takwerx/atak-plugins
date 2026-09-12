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
    private final File root, storeFile;
    private final SharedPreferences state;
    private final List<DetectionSource> sources = Sources.all();
    private final IndustrialMask mask = new IndustrialMask();

    public final Filters filters;

    private FeatureSetDatabase2 store;
    private FeatureLayer3 layer;
    private FeatureDataStoreMapOverlay overlay;
    private DetectionDetailsReceiver details;
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
        this.state = mapView.getContext().getSharedPreferences("firecast", Context.MODE_PRIVATE);
        this.filters = new Filters(state);
        this.lastRefresh = state.getLong("lastRefresh", 0L);
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
        main.postDelayed(timer, TICK_MS);
        refresh();
    }

    public void stop() {
        started = false;
        main.removeCallbacks(timer);
        if (details != null) {
            try {
                AtakBroadcast.getInstance().unregisterReceiver(details);
            } catch (Exception ignored) {
            }
            details.dispose();
            details = null;
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
        final Esri.Box view = viewBox();
        if (view == null)
            return false;
        return !(fetchedFor.south <= view.south && fetchedFor.north >= view.north
                && fetchedFor.west <= view.west && fetchedFor.east >= view.east);
    }

    /** What the operator can see right now, as a box; null when the map cannot say. */
    private Esri.Box viewBox() {
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
        Esri.Box b = viewBox();
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
            }
        }
        if (!started) {
            refreshing = false;
            return;
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
        final Geometry fp = d.footprint.geometry(d.lat, d.lon);
        if (d.footprint.kind == Footprint.Kind.MAPPED)
            return fp;
        final com.atakmap.map.layer.feature.geometry.GeometryCollection gc =
                new com.atakmap.map.layer.feature.geometry.GeometryCollection(2);
        gc.addGeometry(fp);
        gc.addGeometry(new com.atakmap.map.layer.feature.geometry.Point(d.lon, d.lat));
        return gc;
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
        final Esri.Box view = viewBox();
        final long now = System.currentTimeMillis();
        final List<HeatDetection> out = new ArrayList<>();
        for (HeatDetection d : all) {
            if (!filters.shows(d, now))
                continue;
            if (view != null && !view.contains(d.lat, d.lon))
                continue;
            out.add(d);
        }
        sortNewestFirst(out);
        return out.size() > max ? new ArrayList<>(out.subList(0, max)) : out;
    }

    /** How many pass the filters inside the current view, before any list cap. */
    public int inViewCount() {
        final Esri.Box view = viewBox();
        final long now = System.currentTimeMillis();
        int n = 0;
        for (HeatDetection d : all)
            if (filters.shows(d, now) && (view == null || view.contains(d.lat, d.lon)))
                n++;
        return n;
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
