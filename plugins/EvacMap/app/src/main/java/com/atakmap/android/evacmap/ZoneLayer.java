package com.atakmap.android.evacmap;

import android.content.Context;

import com.atakmap.android.features.FeatureDataStoreDeepMapItemQuery;
import com.atakmap.android.features.FeatureDataStoreMapOverlay;
import com.atakmap.android.maps.MapItem;
import com.atakmap.android.maps.MapView;
import com.atakmap.android.menu.PluginMenuParser;
import com.atakmap.android.util.ATAKUtilities;
import com.atakmap.coremap.log.Log;
import com.atakmap.coremap.maps.coords.GeoPoint;
import com.atakmap.map.layer.feature.AttributeSet;
import com.atakmap.map.layer.feature.Feature;
import com.atakmap.map.layer.feature.FeatureDataStore2;
import com.atakmap.map.layer.feature.FeatureLayer3;
import com.atakmap.map.layer.feature.FeatureSet;
import com.atakmap.map.layer.feature.FeatureSetCursor;
import com.atakmap.map.layer.feature.datastore.FeatureSetDatabase2;
import com.atakmap.map.layer.feature.geometry.Envelope;
import com.atakmap.map.layer.feature.geometry.Geometry;
import com.atakmap.map.layer.feature.geometry.Point;
import com.atakmap.map.layer.feature.style.Style;

import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * One catalog source on the map: a file-backed feature store (so it is there again
 * after a restart, with its age shown), an ATAK feature layer on the vector overlays
 * stack, an Overlay Manager entry, and a refresh that swaps the contents in one move
 * so the map never goes blank between fetches.
 *
 * <p>The layer object is never recreated: ATAK keeps the labels of layers that are
 * thrown away, and a recreated layer forgets it was hidden. Every store access, on any
 * thread, holds {@link #lock}; the store is not thread-safe.
 */
public class ZoneLayer {

    private static final String TAG = "EvacMap";

    /** Polygon fill opacity, 0..255. Zones sit over a base map; an opaque fill hides it. */
    static final int FILL_ALPHA = 0x50;
    /**
     * Zone names on the map, as their own label point at each zone's center, drawn from
     * this resolution (meters per pixel) in. ATAK labels a polygon only along an edge
     * long enough to hold the text, which at county scale is never; and every polygon
     * and line carries an empty label so nothing is drawn twice.
     */
    static final double LABEL_GSD = 60d;

    public final Catalog.Source source;
    private final MapView mapView;
    private final Context pluginContext;
    private final File storeFile;
    private final File iconDir;
    private final String lineGlyph, polygonGlyph;

    private FeatureSetDatabase2 store;
    private FeatureLayer3 layer;
    private FeatureDataStoreMapOverlay overlay;

    public volatile String status = "";
    public volatile long lastRefresh;
    public volatile int count;
    /** Features fetched so far during a refresh, for the pane's loading line. */
    public volatile int progress;
    public volatile boolean refreshing;
    /** A store rewrite (ON/OFF from memory) is in progress. */
    public volatile boolean busy;
    public volatile boolean stale;
    /** South, west, north, east of what was fetched last, for "Go to". */
    public volatile double[] bounds;
    /** Zones per status label, from the last refresh, in first-seen order. */
    public volatile Map<String, Integer> statusCounts = new LinkedHashMap<>();
    /** The color the pane shows beside each status label; absent means plain text. */
    public volatile Map<String, Integer> statusColors = new HashMap<>();
    /** One county's share of this layer, keyed by {@link Catalog#countyKey}; empty unless the source names a county field. */
    public volatile Map<String, CountyTally> countyTallies = new HashMap<>();

    public static class CountyTally {
        public final String name;
        public final Map<String, Integer> counts = new LinkedHashMap<>();
        public double[] bounds;

        CountyTally(String name) {
            this.name = name;
        }

        public int total() {
            int n = 0;
            for (Integer v : counts.values())
                n += v;
            return n;
        }
    }

    private volatile boolean closed;
    private final Object lock = new Object();
    private boolean layerOn = true;
    /** Everything fetched last time; the store holds it only while the layer is on. */
    private List<Pending> cache = new ArrayList<>();

    /** A feature fetched and styled, waiting to be written into the store. */
    private static class Pending {
        final String setName, name;
        final double minGsd;
        final Geometry geometry;
        final Style style;
        final AttributeSet attrs;

        Pending(String setName, double minGsd, String name, Geometry geometry, Style style, AttributeSet attrs) {
            this.setName = setName;
            this.minGsd = minGsd;
            this.name = name;
            this.geometry = geometry;
            this.style = style;
            this.attrs = attrs;
        }
    }

    /** Zones fetched last time, labels not counted; what the pane calls "features". */
    private int zoneCount;

    public ZoneLayer(Catalog.Source source, MapView mapView, Context pluginContext, File storeFile,
            File iconDir, String lineGlyph, String polygonGlyph, long lastRefresh, int savedCount) {
        this.source = source;
        this.mapView = mapView;
        this.pluginContext = pluginContext;
        this.storeFile = storeFile;
        this.iconDir = iconDir;
        this.lineGlyph = lineGlyph;
        this.polygonGlyph = polygonGlyph;
        this.lastRefresh = lastRefresh;
        this.zoneCount = savedCount;
        this.bounds = source.bounds;
    }

    /** What Overlay Manager and the details pane call this layer. */
    public String displayName() {
        return source.displayTitle() + " (" + source.st + ")";
    }

    // ---- lifecycle ----------------------------------------------------------------

    /** Opens the store (an existing file is the cached contents) and puts the layer on the map. */
    public void attach() throws Exception {
        synchronized (lock) {
            attachLocked();
        }
    }

    private void attachLocked() throws Exception {
        closed = false;
        store = new FeatureSetDatabase2(storeFile);
        // Visible features only: with the plain constructor the renderer kept drawing the
        // labels of hidden sets.
        final FeatureDataStore2.FeatureQueryParameters visibleOnly = new FeatureDataStore2.FeatureQueryParameters();
        visibleOnly.visibleOnly = true;
        layer = new FeatureLayer3(displayName(), store, visibleOnly);
        final FeatureDataStoreDeepMapItemQuery query = new FeatureDataStoreDeepMapItemQuery(layer) {
            @Override
            protected MapItem featureToMapItem(Feature feature) {
                final MapItem item = super.featureToMapItem(feature);
                final boolean point = EsriRenderer.isPoint(feature.getGeometry());
                item.setMetaString("menu", PluginMenuParser.getMenu(pluginContext,
                        point ? "menu/feature.xml" : "menu/feature_shape.xml"));
                item.setMetaLong("featureid", feature.getId());
                item.setMetaString("evacmap_source", source.id);
                final AttributeSet a = feature.getAttributes();
                String title = null;
                boolean label = false;
                try {
                    title = a == null ? null : a.getStringAttribute("_title");
                    label = a != null && a.containsAttribute("_label");
                } catch (Exception ignored) {
                }
                if (label) {
                    // The zone's label point: drawn, never picked. See the hit tests below.
                    item.setMetaBoolean("evacmap_label", true);
                    item.setClickable(false);
                }
                if (title == null || title.isEmpty())
                    title = feature.getName();
                item.setMetaString("title", title);
                item.setMetaString("callsign", title);
                // Lines and polygons have no icon of their own; give the tap chooser one.
                if (!point) {
                    final Geometry g = feature.getGeometry();
                    final boolean poly = g != null && g.getClass().getSimpleName().contains("Polygon");
                    item.setMetaString("iconUri", poly ? polygonGlyph : lineGlyph);
                }
                return item;
            }

            // A zone and its label are one thing to the finger. The label point sits
            // inside its polygon, so a tap on it always finds the polygon too; the
            // label is dropped from every hit test and the chooser lists the zone once.

            @Override
            public java.util.SortedSet<MapItem> deepHitTest(MapView view,
                    com.atakmap.map.hittest.HitTestQueryParameters params,
                    java.util.Map<com.atakmap.map.layer.Layer2, java.util.Collection<com.atakmap.map.hittest.HitTestControl>> controls) {
                return dropLabels(super.deepHitTest(view, params, controls));
            }

            @Override
            public java.util.SortedSet<MapItem> deepHitTestItems(int x, int y, GeoPoint at, MapView view) {
                return dropLabels(super.deepHitTestItems(x, y, at, view));
            }

            @Override
            public MapItem deepHitTest(int x, int y, GeoPoint at, MapView view) {
                final java.util.SortedSet<MapItem> items = deepHitTestItems(x, y, at, view);
                return items == null || items.isEmpty() ? null : items.first();
            }
        };
        overlay = new FeatureDataStoreMapOverlay(mapView.getContext(), store, null,
                displayName(), "file://asset/nothing", query, null, null);
        // The renderer keeps labels of anything it has ever seen, hidden or not, so the
        // store only ever holds what is shown: drop what should not be before the map sees it.
        dedupeSets();
        pruneHidden();
        mapView.getMapOverlayManager().addFilesOverlay(overlay);
        mapView.addLayer(MapView.RenderStack.VECTOR_OVERLAYS, layer);
        // The store holds zones and their label points; the pane counts zones. After a
        // restart the split is not known, so the count saved with the ON list is used.
        final int stored = countFeatures();
        count = stored == 0 ? 0 : (zoneCount > 0 ? zoneCount : stored);
        status = count > 0 ? "cached" : "empty";
    }

    /** The hit-test result without label points; a copy when the set will not be edited in place. */
    static java.util.SortedSet<MapItem> dropLabels(java.util.SortedSet<MapItem> items) {
        if (items == null || items.isEmpty())
            return items;
        boolean any = false;
        for (MapItem m : items)
            if (m.getMetaBoolean("evacmap_label", false)) {
                any = true;
                break;
            }
        if (!any)
            return items;
        try {
            final java.util.Iterator<MapItem> it = items.iterator();
            while (it.hasNext())
                if (it.next().getMetaBoolean("evacmap_label", false))
                    it.remove();
            return items;
        } catch (UnsupportedOperationException e) {
            final java.util.SortedSet<MapItem> out = new java.util.TreeSet<>(
                    items.comparator() != null ? items.comparator() : MapItem.ZORDER_HITTEST_COMPARATOR);
            for (MapItem m : items)
                if (!m.getMetaBoolean("evacmap_label", false))
                    out.add(m);
            return out;
        }
    }

    /**
     * A refresh interrupted by a plugin reload can leave two generations of sets; keep
     * the newest of each name (zones, labels) and drop the rest.
     */
    private void dedupeSets() {
        final Map<String, Long> newest = new HashMap<>();
        final List<Long> drop = new ArrayList<>();
        for (SetInfo si : setsLocked()) {
            final Long prev = newest.get(si.name);
            if (prev == null) {
                newest.put(si.name, si.id);
            } else if (si.id > prev) {
                drop.add(prev);
                newest.put(si.name, si.id);
            } else {
                drop.add(si.id);
            }
        }
        for (Long id : drop) {
            try {
                store.deleteFeatureSet(id);
            } catch (Exception e) {
                Log.w(TAG, "dedupe " + id, e);
            }
        }
    }

    private static class SetInfo {
        final long id;
        final String name;

        SetInfo(long id, String name) {
            this.id = id;
            this.name = name;
        }
    }

    private List<SetInfo> setsLocked() {
        final List<SetInfo> out = new ArrayList<>();
        if (store == null)
            return out;
        try {
            final FeatureSetCursor c = store.queryFeatureSets(new FeatureDataStore2.FeatureSetQueryParameters());
            try {
                while (c.moveToNext())
                    out.add(new SetInfo(c.getId(), c.getName()));
            } finally {
                c.close();
            }
        } catch (Exception e) {
            Log.w(TAG, "set listing failed", e);
        }
        return out;
    }

    public void detach() {
        closed = true;
        synchronized (lock) {
            try {
                if (layer != null)
                    mapView.removeLayer(MapView.RenderStack.VECTOR_OVERLAYS, layer);
                if (overlay != null)
                    mapView.getMapOverlayManager().removeOverlay(overlay);
                if (store != null)
                    store.dispose();
            } catch (Exception e) {
                Log.w(TAG, "detach " + source.id, e);
            }
            layer = null;
            overlay = null;
            store = null;
        }
    }

    /** Detaches and deletes the store file. */
    public void delete() {
        detach();
        if (storeFile.isFile() && !storeFile.delete())
            Log.w(TAG, "could not delete " + storeFile);
    }

    public FeatureDataStore2 getStore() {
        return store;
    }

    public boolean isVisible() {
        return layerOn;
    }

    /** Records the intended state without touching the store (the worker does that). */
    public void markVisible(boolean v) {
        layerOn = v;
    }

    /**
     * Layer on/off. Returns true when a fetch is needed to show it (no memory copy, e.g.
     * after a restart); the manager then refreshes.
     */
    public boolean setVisible(boolean v) {
        layerOn = v;
        synchronized (lock) {
            if (store == null)
                return false;
            if (v && cache.isEmpty())
                return countFeatures() == 0;
            rewriteStore();
        }
        return false;
    }

    /** Removes from the store what should not be shown right now. Lock held. */
    private void pruneHidden() {
        if (layerOn)
            return;
        boolean bulk = false;
        try {
            store.acquireModifyLock(true);
            bulk = true;
            for (Long id : existingSets())
                store.deleteFeatureSet(id);
        } catch (Exception e) {
            Log.w(TAG, "prune failed", e);
        } finally {
            if (bulk)
                store.releaseModifyLock();
        }
    }

    /**
     * Makes the store hold exactly the shown part of the memory copy: the new set in,
     * then the previous ones out. Lock held. Bulk mode, so ATAK gets one content-changed
     * notification instead of one per insert, each of which had it re-querying the store.
     */
    private void rewriteStore() {
        boolean bulk = false;
        try {
            store.acquireModifyLock(true);
            bulk = true;
            final List<Long> old = existingSets();
            final Map<String, Long> sets = new HashMap<>();
            if (layerOn) {
                for (Pending pf : cache) {
                    Long fsid = sets.get(pf.setName);
                    if (fsid == null) {
                        fsid = newSet(pf.setName, pf.minGsd);
                        sets.put(pf.setName, fsid);
                    }
                    store.insertFeature(new Feature(fsid, pf.name, pf.geometry, pf.style, pf.attrs,
                            Feature.AltitudeMode.ClampToGround, 0d));
                }
            }
            for (Long id : old) {
                try {
                    store.deleteFeatureSet(id);
                } catch (Exception e) {
                    Log.w(TAG, "old set " + id, e);
                }
            }
            count = layerOn ? zoneCount : 0;
            Log.d(TAG, source.id + ": store rewritten, " + countFeatures() + " features in " + sets.size()
                    + " sets, " + count + " zones");
        } catch (Exception e) {
            Log.w(TAG, "store rewrite failed", e);
        } finally {
            if (bulk)
                store.releaseModifyLock();
        }
    }

    /** Frames everything fetched, with a margin; the catalog's extent before the first fetch. */
    public void panTo() {
        try {
            final double[] b = bounds;
            if (b != null && b[2] > b[0] && b[3] > b[1]) {
                final double padLat = Math.max(0.002, (b[2] - b[0]) * 0.15);
                final double padLon = Math.max(0.002, (b[3] - b[1]) * 0.15);
                final GeoPoint[] corners = {
                        new GeoPoint(b[0] - padLat, b[1] - padLon),
                        new GeoPoint(b[2] + padLat, b[3] + padLon) };
                ATAKUtilities.scaleToFit(mapView, corners, 0d, mapView.getWidth(), mapView.getHeight());
            }
        } catch (Exception e) {
            Log.w(TAG, "go to failed", e);
        }
    }

    /**
     * South, west, north, east of everything fetched. A feature at 0,0 is a missing
     * coordinate, not a place, and must not stretch "Go to" to the Gulf of Guinea.
     */
    private static double[] extentOf(List<Pending> features) {
        double s = 90, w = 180, n = -90, e = -180;
        boolean any = false;
        for (Pending pf : features) {
            if (pf.geometry == null)
                continue;
            final Envelope env = pf.geometry.getEnvelope();
            if (env == null || Double.isNaN(env.minX) || Double.isNaN(env.minY))
                continue;
            if (Math.abs(env.minX) < 1e-6 || Math.abs(env.minY) < 1e-6
                    || Math.abs(env.maxX) < 1e-6 || Math.abs(env.maxY) < 1e-6)
                continue;
            s = Math.min(s, env.minY);
            w = Math.min(w, env.minX);
            n = Math.max(n, env.maxY);
            e = Math.max(e, env.maxX);
            any = true;
        }
        return any ? new double[] { s, w, n, e } : null;
    }

    // ---- refresh ------------------------------------------------------------------

    /**
     * Two phases. Fetch and style everything with no lock held, so the pane stays live.
     * Then, briefly under the lock, write the new set into the same store and drop the
     * previous one. Worker thread.
     */
    public void refresh(Runnable progress) {
        if (store == null || closed || refreshing)
            return;
        refreshing = true;
        this.progress = 0;
        status = "refreshing";
        if (progress != null)
            progress.run();
        final List<Pending> pending = new ArrayList<>();
        final Map<String, Integer> counts = new LinkedHashMap<>();
        final Map<String, Integer> colors = new HashMap<>();
        final Map<String, CountyTally> tallies = new HashMap<>();
        try {
            fetch(pending, counts, colors, tallies, progress);
            synchronized (lock) {
                if (store == null || closed)
                    throw new IllegalStateException("layer closed");
                cache = pending;
                int zones = 0;
                for (Pending pf : pending)
                    if (pf.minGsd == Double.MAX_VALUE)
                        zones++;
                zoneCount = zones;
                final double[] ext = extentOf(pending);
                if (ext != null)
                    bounds = ext;
                statusCounts = counts;
                statusColors = colors;
                countyTallies = tallies;
                rewriteStore();
            }
            lastRefresh = System.currentTimeMillis();
            stale = false;
            status = "ok";
            if (source.maxFeatures > 0 && zoneCount >= source.maxFeatures)
                status = "capped at " + source.maxFeatures;
            Log.d(TAG, source.id + ": refresh done, " + zoneCount + " zones fetched");
        } catch (Exception e) {
            Log.w(TAG, source.id + " refresh failed", e);
            stale = true;
            status = "no update: " + e.getMessage();
        } finally {
            refreshing = false;
            if (progress != null)
                progress.run();
        }
    }

    private void fetch(final List<Pending> out, final Map<String, Integer> counts,
            final Map<String, Integer> colors, final Map<String, CountyTally> tallies,
            final Runnable progressCb) throws Exception {
        final Esri.LayerInfo info = Esri.layerInfo(source.url, source.layer);
        final boolean isPoint = info.geometryType.contains("Point");
        final boolean isLine = info.geometryType.contains("Polyline");
        final EsriRenderer renderer = new EsriRenderer(info.drawingInfo, info.geometryType, iconDir, FILL_ALPHA);
        final String nameField = fieldOf(source.nameField, info);
        final String statusField = fieldOf(source.statusField, info);
        final String displayField = fieldOf(info.displayField, info);
        final String countyField = fieldOf(source.countyField, info);
        // One color language when the catalog says where the status is; the service's
        // own symbols otherwise (and always for points, which are icons, not zones).
        final boolean normalize = statusField != null && !isPoint;
        final Set<String> dates = info.dateFields;
        final String setName = info.name;
        final String labelSet = info.name + " labels";
        final int[] seen = { 0 };

        Esri.query(source.url, source.layer, source.where, Math.min(1000, info.maxRecordCount),
                source.maxFeatures, source.simplify, new Esri.FeatureSink() {
                    @Override
                    public void feature(JSONObject props, Geometry g) throws Exception {
                        final String cls = renderer.labelFor(props);
                        final String name = Esri.firstNonEmpty(str(props, nameField), str(props, displayField), cls, setName);
                        final String statusText = Esri.firstNonEmpty(str(props, statusField), cls);
                        Style style;
                        String key;
                        if (normalize) {
                            final StatusColors.Level level = StatusColors.classify(statusText);
                            style = isLine ? StatusColors.line(level) : StatusColors.polygon(level, FILL_ALPHA);
                            key = level == StatusColors.Level.OTHER && statusText != null ? statusText : level.label;
                            colors.put(key, level.color);
                        } else {
                            style = renderer.styleFor(props);
                            key = statusText == null ? "(no status)" : statusText;
                        }
                        if (!isPoint)
                            style = Styles.silentLabel(style);
                        final String title = statusText != null && !statusText.equals(name)
                                ? statusText + ": " + name : name;
                        final AttributeSet attrs = Esri.toAttributes(props, dates);
                        attrs.setAttribute("_title", title);
                        if (statusText != null)
                            attrs.setAttribute("_status", statusText);
                        final Integer n = counts.get(key);
                        counts.put(key, n == null ? 1 : n + 1);
                        if (countyField != null) {
                            final String raw = str(props, countyField);
                            if (raw != null && !raw.trim().isEmpty()) {
                                final String ck = Catalog.countyKey(raw);
                                CountyTally t = tallies.get(ck);
                                if (t == null) {
                                    t = new CountyTally(raw.trim());
                                    tallies.put(ck, t);
                                }
                                final Integer tn = t.counts.get(key);
                                t.counts.put(key, tn == null ? 1 : tn + 1);
                                t.bounds = union(t.bounds, g);
                            }
                        }
                        out.add(new Pending(setName, Double.MAX_VALUE, name, g, style, attrs));
                        // The zone's name at its center, as its own gated point. Same
                        // attributes, so a tap on the label opens the zone's details.
                        if (!isPoint && !isLine) {
                            final Point at = labelPoint(g);
                            if (at != null) {
                                final String text = shortLabel(name);
                                final AttributeSet la = Esri.toAttributes(props, dates);
                                la.setAttribute("_title", title);
                                la.setAttribute("_label", "1");
                                if (statusText != null)
                                    la.setAttribute("_status", statusText);
                                out.add(new Pending(labelSet, LABEL_GSD, text, at, Styles.label(text), la));
                            }
                        }
                        if (++seen[0] % 25 == 0) {
                            progress = seen[0];
                            status = "refreshing: " + seen[0];
                            if (progressCb != null)
                                progressCb.run();
                        }
                    }
                });
    }

    /**
     * "RVC-1001" for "US-CA-XRI-RVC-1001": a Genasys id on the map keeps its last two
     * parts, the way CAL FIRE's statewide feed already shows them. Anything else is
     * itself. The details pane keeps the full id.
     */
    static String shortLabel(String name) {
        if (name == null)
            return "";
        final String[] p = name.split("-");
        if (p.length >= 4 && "US".equals(p[0]) && p[1].length() == 2)
            return p[p.length - 2] + "-" + p[p.length - 1];
        return name;
    }

    /**
     * Where a zone's label goes: the centroid of its largest ring. A zero-area or
     * degenerate ring falls back to the envelope center; nothing is returned for a
     * geometry with no usable ring.
     */
    static Point labelPoint(Geometry g) {
        com.atakmap.map.layer.feature.geometry.Polygon best = null;
        double bestArea = -1;
        if (g instanceof com.atakmap.map.layer.feature.geometry.Polygon) {
            best = (com.atakmap.map.layer.feature.geometry.Polygon) g;
        } else if (g instanceof com.atakmap.map.layer.feature.geometry.GeometryCollection) {
            for (Geometry c : ((com.atakmap.map.layer.feature.geometry.GeometryCollection) g).getGeometries()) {
                if (!(c instanceof com.atakmap.map.layer.feature.geometry.Polygon))
                    continue;
                final double a = Math.abs(ringArea(((com.atakmap.map.layer.feature.geometry.Polygon) c).getExteriorRing()));
                if (a > bestArea) {
                    bestArea = a;
                    best = (com.atakmap.map.layer.feature.geometry.Polygon) c;
                }
            }
        }
        if (best == null)
            return null;
        final com.atakmap.map.layer.feature.geometry.LineString ring = best.getExteriorRing();
        if (ring == null || ring.getNumPoints() < 3)
            return null;
        double a = 0, cx = 0, cy = 0;
        final int n = ring.getNumPoints();
        for (int i = 0; i < n; i++) {
            final int j = (i + 1) % n;
            final double x0 = ring.getX(i), y0 = ring.getY(i), x1 = ring.getX(j), y1 = ring.getY(j);
            final double cross = x0 * y1 - x1 * y0;
            a += cross;
            cx += (x0 + x1) * cross;
            cy += (y0 + y1) * cross;
        }
        if (Math.abs(a) < 1e-12) {
            final Envelope e = best.getEnvelope();
            return e == null ? null : new Point((e.minX + e.maxX) / 2, (e.minY + e.maxY) / 2);
        }
        a *= 0.5;
        return new Point(cx / (6 * a), cy / (6 * a));
    }

    private static double ringArea(com.atakmap.map.layer.feature.geometry.LineString ring) {
        if (ring == null)
            return 0;
        double a = 0;
        final int n = ring.getNumPoints();
        for (int i = 0; i < n; i++) {
            final int j = (i + 1) % n;
            a += ring.getX(i) * ring.getY(j) - ring.getX(j) * ring.getY(i);
        }
        return a / 2;
    }

    /** {@code b} grown to hold {@code g}'s envelope; a zero coordinate is a missing one. */
    private static double[] union(double[] b, Geometry g) {
        final Envelope e = g == null ? null : g.getEnvelope();
        if (e == null || Double.isNaN(e.minX) || Double.isNaN(e.minY)
                || Math.abs(e.minX) < 1e-6 || Math.abs(e.minY) < 1e-6)
            return b;
        if (b == null)
            return new double[] { e.minY, e.minX, e.maxY, e.maxX };
        return new double[] { Math.min(b[0], e.minY), Math.min(b[1], e.minX), Math.max(b[2], e.maxY), Math.max(b[3], e.maxX) };
    }

    private static String str(JSONObject p, String k) {
        return k == null || p.isNull(k) ? null : p.optString(k, null);
    }

    /** The field if the layer has it, else null: a catalog typo costs a label, not the layer. */
    private static String fieldOf(String name, Esri.LayerInfo info) {
        return name != null && !name.isEmpty() && info.fields.contains(name) ? name : null;
    }

    private long newSet(String name, double minGsd) throws Exception {
        final long id = store.insertFeatureSet(new FeatureSet("EvacMap", source.id, name, minGsd, 0d));
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
            Log.w(TAG, "feature set listing failed", e);
        }
        return ids;
    }

    private int countFeatures() {
        try {
            return store.queryFeaturesCount(new FeatureDataStore2.FeatureQueryParameters());
        } catch (Exception e) {
            return 0;
        }
    }
}
