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
    /** Zone names on the map. Lines never carry labels: ATAK repeats them along the length. */
    static final boolean LABEL_POLYGONS = true;

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

    private volatile boolean closed;
    private final Object lock = new Object();
    private boolean layerOn = true;
    /** Everything fetched last time; the store holds it only while the layer is on. */
    private List<Pending> cache = new ArrayList<>();

    /** A feature fetched and styled, waiting to be written into the store. */
    private static class Pending {
        final String setName, name;
        final Geometry geometry;
        final Style style;
        final AttributeSet attrs;

        Pending(String setName, String name, Geometry geometry, Style style, AttributeSet attrs) {
            this.setName = setName;
            this.name = name;
            this.geometry = geometry;
            this.style = style;
            this.attrs = attrs;
        }
    }

    public ZoneLayer(Catalog.Source source, MapView mapView, Context pluginContext, File storeFile,
            File iconDir, String lineGlyph, String polygonGlyph, long lastRefresh) {
        this.source = source;
        this.mapView = mapView;
        this.pluginContext = pluginContext;
        this.storeFile = storeFile;
        this.iconDir = iconDir;
        this.lineGlyph = lineGlyph;
        this.polygonGlyph = polygonGlyph;
        this.lastRefresh = lastRefresh;
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
                try {
                    title = a == null ? null : a.getStringAttribute("_title");
                } catch (Exception ignored) {
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
        };
        overlay = new FeatureDataStoreMapOverlay(mapView.getContext(), store, null,
                displayName(), "file://asset/nothing", query, null, null);
        // The renderer keeps labels of anything it has ever seen, hidden or not, so the
        // store only ever holds what is shown: drop what should not be before the map sees it.
        dedupeSets();
        pruneHidden();
        mapView.getMapOverlayManager().addFilesOverlay(overlay);
        mapView.addLayer(MapView.RenderStack.VECTOR_OVERLAYS, layer);
        count = countFeatures();
        status = count > 0 ? "cached" : "empty";
    }

    /** A refresh interrupted by a plugin reload can leave two generations of sets; keep the newest. */
    private void dedupeSets() {
        Long newest = null;
        final List<Long> drop = new ArrayList<>();
        for (Long id : existingSets()) {
            if (newest == null || id > newest) {
                if (newest != null)
                    drop.add(newest);
                newest = id;
            } else {
                drop.add(id);
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
            long fsid = -1;
            if (layerOn) {
                for (Pending pf : cache) {
                    if (fsid < 0)
                        fsid = newSet(pf.setName);
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
            count = countFeatures();
            Log.d(TAG, source.id + ": store rewritten, " + count + " shown of " + cache.size());
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
        try {
            fetch(pending, counts, colors, progress);
            synchronized (lock) {
                if (store == null || closed)
                    throw new IllegalStateException("layer closed");
                cache = pending;
                final double[] ext = extentOf(pending);
                if (ext != null)
                    bounds = ext;
                statusCounts = counts;
                statusColors = colors;
                rewriteStore();
            }
            lastRefresh = System.currentTimeMillis();
            stale = false;
            status = "ok";
            if (source.maxFeatures > 0 && pending.size() >= source.maxFeatures)
                status = "capped at " + source.maxFeatures;
            Log.d(TAG, source.id + ": refresh done, " + pending.size() + " fetched");
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
            final Map<String, Integer> colors, final Runnable progressCb) throws Exception {
        final Esri.LayerInfo info = Esri.layerInfo(source.url, source.layer);
        final boolean isPoint = info.geometryType.contains("Point");
        final boolean isLine = info.geometryType.contains("Polyline");
        final EsriRenderer renderer = new EsriRenderer(info.drawingInfo, info.geometryType, iconDir, FILL_ALPHA);
        final String nameField = fieldOf(source.nameField, info);
        final String statusField = fieldOf(source.statusField, info);
        final String displayField = fieldOf(info.displayField, info);
        // One color language when the catalog says where the status is; the service's
        // own symbols otherwise (and always for points, which are icons, not zones).
        final boolean normalize = statusField != null && !isPoint;
        final Set<String> dates = info.dateFields;
        final String setName = info.name;
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
                        if (isLine || (!isPoint && !LABEL_POLYGONS))
                            style = Styles.silentLabel(style);
                        final String title = statusText != null && !statusText.equals(name)
                                ? statusText + ": " + name : name;
                        final AttributeSet attrs = Esri.toAttributes(props, dates);
                        attrs.setAttribute("_title", title);
                        if (statusText != null)
                            attrs.setAttribute("_status", statusText);
                        final Integer n = counts.get(key);
                        counts.put(key, n == null ? 1 : n + 1);
                        out.add(new Pending(setName, name, g, style, attrs));
                        if (++seen[0] % 100 == 0) {
                            progress = seen[0];
                            status = "refreshing: " + seen[0];
                            if (progressCb != null)
                                progressCb.run();
                        }
                    }
                });
    }

    private static String str(JSONObject p, String k) {
        return k == null || p.isNull(k) ? null : p.optString(k, null);
    }

    /** The field if the layer has it, else null: a catalog typo costs a label, not the layer. */
    private static String fieldOf(String name, Esri.LayerInfo info) {
        return name != null && !name.isEmpty() && info.fields.contains(name) ? name : null;
    }

    private long newSet(String name) throws Exception {
        final long id = store.insertFeatureSet(new FeatureSet("EvacMap", source.id, name, Double.MAX_VALUE, 0d));
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
