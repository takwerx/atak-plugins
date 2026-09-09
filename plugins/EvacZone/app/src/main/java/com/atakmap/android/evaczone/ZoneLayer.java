package com.atakmap.android.evaczone;

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
import com.atakmap.map.layer.feature.geometry.GeometryCollection;
import com.atakmap.map.layer.feature.geometry.Point;
import com.atakmap.map.layer.feature.style.Style;

import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
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

    private static final String TAG = "EvacZone";

    /** Polygon fill opacity, 0..255. Zones sit over a base map; an opaque fill hides it. */
    static final int FILL_ALPHA = 0x50;
    /*
     * Zone names on the map. ATAK labels a polygon only along an edge long enough to
     * hold the text, which at county scale is never, so each zone's geometry is the
     * polygon plus its center point in one collection, with the name as a label style:
     * the point child draws the name, the polygon child the fill and edge, and there is
     * one feature to tap, list and refresh. A separate label feature was tried first and
     * ATAK's tap chooser listed it beside the zone. Lines carry an empty label.
     */

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
    /** The pane's all-ON/OFF switch, over every layer's own ON. Off draws nothing, forgets nothing. */
    private boolean masterOn = true;
    /** Everything fetched last time; the store holds it only while the layer is on. */
    private List<Pending> cache = new ArrayList<>();

    /** A feature fetched and styled, waiting to be written into the store. */
    private static class Pending {
        final String setName, name;
        final double minGsd;
        final Geometry geometry;
        final Style style;
        final AttributeSet attrs;
        /** The geometry's box, read once, for the radius test on every rewrite. */
        final Envelope env;
        /** For the zone list: the status label, its color, the county, a point to measure from. */
        String title, statusKey, county;
        /** The county of the layer this came from, when that layer is one county's. */
        String layerCounty;
        int color;
        double lat = Double.NaN, lon = Double.NaN;

        Pending(String setName, double minGsd, String name, Geometry geometry, Style style, AttributeSet attrs) {
            this.setName = setName;
            this.minGsd = minGsd;
            this.name = name;
            this.geometry = geometry;
            this.style = style;
            this.attrs = attrs;
            this.env = geometry == null ? null : geometry.getEnvelope();
            if (env != null) {
                lat = (env.minY + env.maxY) / 2;
                lon = (env.minX + env.maxX) / 2;
            }
        }
    }

    /** One zone as the pane lists it: what is on the map right now, nothing more. */
    public static final class ZoneInfo {
        public final String name, title, status, sourceId, sourceTitle;
        /** The county's name as the row shows it, however the zone was placed. */
        public String county;
        public final int color;
        public final double lat, lon;
        public final double[] bounds;
        /** The feature's id in the store, for the details pane. */
        public long featureId = -1;

        ZoneInfo(Pending pf, Catalog.Source source) {
            name = pf.name;
            title = pf.title == null ? pf.name : pf.title;
            status = pf.statusKey == null ? "" : pf.statusKey;
            county = pf.county != null ? pf.county : pf.layerCounty != null && !pf.layerCounty.isEmpty() ? pf.layerCounty : source.county;
            sourceId = source.id;
            sourceTitle = source.title;
            color = pf.color;
            lat = pf.lat;
            lon = pf.lon;
            bounds = pf.env == null ? null : new double[] { pf.env.minY, pf.env.minX, pf.env.maxY, pf.env.maxX };
        }

        public boolean matches(String q) {
            return q.isEmpty() || name.toLowerCase(java.util.Locale.US).contains(q)
                    || title.toLowerCase(java.util.Locale.US).contains(q)
                    || county.toLowerCase(java.util.Locale.US).contains(q);
        }
    }

    /** The zones in the store after the radius filter, in fetch order. */
    public volatile List<ZoneInfo> zones = new ArrayList<>();

    // ---- visibility: Cam Depot's radius and zoom gate, applied to the store --------

    /** Counties to draw, as {@link Catalog#countyKey} keys; null draws every county. */
    private Set<String> filterCounties;
    /** Zones the county filter left out last rewrite. */
    public volatile int outsideCounties;

    /** The county filter, from the pane's picker. Rewrites the store from memory. Worker thread. */
    public void applyCounties(Set<String> keys) {
        synchronized (lock) {
            final boolean same = (keys == null && filterCounties == null)
                    || (keys != null && keys.equals(filterCounties));
            filterCounties = keys == null ? null : new HashSet<>(keys);
            if (store == null || same)
                return;
            if (layerOn && masterOn && !cache.isEmpty())
                rewriteStore();
        }
    }

    public void presetCounties(Set<String> keys) {
        synchronized (lock) {
            filterCounties = keys == null ? null : new HashSet<>(keys);
        }
    }

    /** The state's counties with their boxes, for placing a zone no feed put in a county. */
    private volatile List<Catalog.County> countyList = new ArrayList<>();

    public void setCountyList(List<Catalog.County> counties) {
        countyList = counties == null ? new ArrayList<Catalog.County>() : counties;
    }

    /** The county's name for the row: the placed county, title-cased when the feed shouts it. */
    private String countyNameOf(Pending pf) {
        final String key = countyKeyOf(pf);
        if (key == null)
            return "";
        for (Catalog.County c : countyList)
            if (Catalog.countyKey(c.name).equals(key))
                return c.name;
        if (pf.county != null && !pf.county.trim().isEmpty())
            return titleCase(pf.county.trim());
        if (pf.layerCounty != null && !pf.layerCounty.isEmpty())
            return pf.layerCounty;
        return source.county.isEmpty() ? titleCase(key) : source.county;
    }

    private static String titleCase(String s) {
        final StringBuilder sb = new StringBuilder();
        boolean up = true;
        for (char ch : s.toLowerCase(java.util.Locale.US).toCharArray()) {
            sb.append(up ? Character.toUpperCase(ch) : ch);
            up = ch == ' ' || ch == '-';
        }
        return sb.toString();
    }

    /**
     * A zone's county key: its own county field, else its layer's county, else the
     * source's county, else the smallest county box its center sits in. Null only when
     * nothing places it.
     */
    private String countyKeyOf(Pending pf) {
        if (pf.county != null && !pf.county.trim().isEmpty())
            return Catalog.countyKey(pf.county);
        if (pf.layerCounty != null && !pf.layerCounty.isEmpty())
            return Catalog.countyKey(pf.layerCounty);
        if (!source.county.isEmpty())
            return Catalog.countyKey(source.county);
        if (!Double.isNaN(pf.lat)) {
            Catalog.County best = null;
            double bestArea = Double.MAX_VALUE;
            for (Catalog.County c : countyList) {
                if (!c.contains(pf.lat, pf.lon))
                    continue;
                final double area = (c.bounds[2] - c.bounds[0]) * (c.bounds[3] - c.bounds[1]);
                if (area < bestArea) {
                    bestArea = area;
                    best = c;
                }
            }
            if (best != null)
                return Catalog.countyKey(best.name);
        }
        return null;
    }

    /** Where the radius is measured from; null when the radius is off. */
    private GeoPoint filterFrom;
    private double filterRadius;
    /** The feature set's own resolution gate: zones draw at or below this many m/px. */
    private double zoomGate = Double.MAX_VALUE;
    /** Zones in the store after the radius filter; {@link #count} is the same number. */
    public volatile int shown;
    /** Zones the radius left out last rewrite. */
    public volatile int outsideRadius;

    /** Records the settings without touching the store, for a layer about to attach and refresh. */
    public void presetVisibility(GeoPoint from, double radiusMeters, double maxResolution) {
        synchronized (lock) {
            filterFrom = radiusMeters > 0 ? from : null;
            filterRadius = radiusMeters;
            zoomGate = maxResolution;
        }
    }

    /**
     * Applies the radius and the zoom gate. The gate is a property of the feature set
     * and changes in place; a changed radius rewrites the store from the memory copy,
     * no network. Worker thread.
     */
    public void applyVisibility(GeoPoint from, double radiusMeters, double maxResolution) {
        synchronized (lock) {
            if (store == null)
                return;
            final GeoPoint newFrom = radiusMeters > 0 ? from : null;
            final boolean radiusChanged = radiusMeters != filterRadius || !samePoint(newFrom, filterFrom);
            final boolean gateChanged = maxResolution != zoomGate;
            filterFrom = newFrom;
            filterRadius = radiusMeters;
            zoomGate = maxResolution;
            if (gateChanged)
                applyGateLocked();
            if (radiusChanged && layerOn && !cache.isEmpty())
                rewriteStore();
        }
    }

    private static boolean samePoint(GeoPoint a, GeoPoint b) {
        if (a == null || b == null)
            return a == b;
        return Math.abs(a.getLatitude() - b.getLatitude()) < 1e-7
                && Math.abs(a.getLongitude() - b.getLongitude()) < 1e-7;
    }

    /** Lock held. */
    private void applyGateLocked() {
        for (Long id : existingSets()) {
            try {
                store.updateFeatureSet(id, zoomGate, 0d);
            } catch (Exception e) {
                Log.w(TAG, "zoom gate on set " + id, e);
            }
        }
    }

    /**
     * Any part of the zone within the radius: the distance from the point to the
     * nearest point of the zone's box. A zone whose corner is 20 miles off but whose
     * edge runs past the operator is in.
     */
    private static boolean withinRadius(Pending pf, GeoPoint from, double radiusMeters) {
        final Envelope e = pf.env;
        if (e == null)
            return true;
        final double lat = Math.max(e.minY, Math.min(e.maxY, from.getLatitude()));
        final double lon = Math.max(e.minX, Math.min(e.maxX, from.getLongitude()));
        return com.atakmap.coremap.maps.coords.GeoCalculations.distanceTo(from, new GeoPoint(lat, lon)) <= radiusMeters;
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
                item.setMetaString("evaczone_source", source.id);
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
                if (!point)
                    item.setMetaString("iconUri", hasPolygon(feature.getGeometry()) ? polygonGlyph : lineGlyph);
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
        // The store holds zones and their label points; the pane counts zones. After a
        // restart the split is not known, so the count saved with the ON list is used.
        final int stored = countFeatures();
        count = stored == 0 ? 0 : (zoneCount > 0 ? zoneCount : stored);
        status = count > 0 ? "cached" : "empty";
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

    /** Records the all-switch without touching the store, for a layer about to attach. */
    public void presetMaster(boolean on) {
        masterOn = on;
    }

    /** The all-switch. Returns true when a fetch is needed to show the layer (no memory copy). */
    public boolean setMasterVisible(boolean on) {
        synchronized (lock) {
            masterOn = on;
            if (store == null)
                return false;
            if (on && layerOn && cache.isEmpty())
                return countFeatures() == 0;
            rewriteStore();
        }
        return false;
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
        if (layerOn && masterOn)
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
            final List<ZoneInfo> listed = new ArrayList<>();
            int in = 0, out = 0, outCounty = 0;
            if (layerOn && masterOn) {
                final GeoPoint from = filterFrom;
                final double radius = filterRadius;
                final Set<String> counties = filterCounties;
                for (Pending pf : cache) {
                    if (counties != null) {
                        // Only the picked counties. A zone nothing can place is left out
                        // too: "I want Monterey" means Monterey.
                        final String ck = countyKeyOf(pf);
                        if (ck == null || !counties.contains(ck)) {
                            outCounty++;
                            continue;
                        }
                    }
                    if (from != null && radius > 0 && !withinRadius(pf, from, radius)) {
                        out++;
                        continue;
                    }
                    final ZoneInfo zi = new ZoneInfo(pf, source);
                    zi.county = countyNameOf(pf);
                    listed.add(zi);
                    Long fsid = sets.get(pf.setName);
                    if (fsid == null) {
                        fsid = newSet(pf.setName, Math.min(pf.minGsd, zoomGate));
                        sets.put(pf.setName, fsid);
                    }
                    zi.featureId = store.insertFeature(new Feature(fsid, pf.name, pf.geometry, pf.style, pf.attrs,
                            Feature.AltitudeMode.ClampToGround, 0d));
                    in++;
                }
            }
            shown = in;
            outsideRadius = out;
            outsideCounties = outCounty;
            zones = listed;
            for (Long id : old) {
                try {
                    store.deleteFeatureSet(id);
                } catch (Exception e) {
                    Log.w(TAG, "old set " + id, e);
                }
            }
            count = layerOn && masterOn ? shown : 0;
            Log.d(TAG, source.id + ": store rewritten, " + count + " of " + zoneCount + " zones shown"
                    + (out > 0 ? ", " + out + " outside the radius" : ""));
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

    /**
     * Every layer of the source, merged by zone key. The first layer is the authority:
     * a later layer adds a zone the first does not have, and adds fields the first
     * lacks to a zone it does, but never changes a status. Duplicate rows in any layer
     * collapse to the first seen. The key is the short zone id the labels use, so
     * Cal OES's US-CA-XMY-FHL-G019 and CAL FIRE's FHL-G019 are one zone.
     */
    private void fetch(final List<Pending> out, final Map<String, Integer> counts,
            final Map<String, Integer> colors, final Map<String, CountyTally> tallies,
            final Runnable progressCb) throws Exception {
        final Map<String, Pending> byKey = new LinkedHashMap<>();
        final int[] seen = { 0 };
        final List<String> problems = new ArrayList<>();
        for (int li = 0; li < source.layers.size(); li++) {
            final Catalog.Layer lay = source.layers.get(li);
            final boolean authority = li == 0;
            try {
                fetchLayer(lay, authority, byKey, seen, progressCb);
            } catch (Exception e) {
                if (authority)
                    throw e;
                // An extra layer that fails costs its extras, not the feed.
                Log.w(TAG, source.id + ": extra layer " + lay.publisher + " failed: " + e.getMessage());
                problems.add(lay.publisher);
            }
        }
        for (Pending pf : byKey.values()) {
            final String key = pf.statusKey == null ? "(no status)" : pf.statusKey;
            final Integer n = counts.get(key);
            counts.put(key, n == null ? 1 : n + 1);
            if (pf.color != 0)
                colors.put(key, pf.color);
            if (pf.county != null && !pf.county.trim().isEmpty()) {
                final String ck = Catalog.countyKey(pf.county);
                CountyTally t = tallies.get(ck);
                if (t == null) {
                    t = new CountyTally(pf.county.trim());
                    tallies.put(ck, t);
                }
                final Integer tn = t.counts.get(key);
                t.counts.put(key, tn == null ? 1 : tn + 1);
                t.bounds = union(t.bounds, pf.geometry);
            }
            out.add(pf);
        }
        if (!problems.isEmpty())
            status = "without " + problems.get(0);
    }

    private void fetchLayer(final Catalog.Layer lay, final boolean authority, final Map<String, Pending> byKey,
            final int[] seen, final Runnable progressCb) throws Exception {
        final Esri.LayerInfo info = Esri.layerInfo(lay.url, lay.layer);
        final boolean isPoint = info.geometryType.contains("Point");
        final boolean isLine = info.geometryType.contains("Polyline");
        final EsriRenderer renderer = new EsriRenderer(info.drawingInfo, info.geometryType, iconDir, FILL_ALPHA);
        final String nameField = fieldOf(lay.nameField, info);
        final String statusField = fieldOf(lay.statusField, info);
        final String displayField = fieldOf(info.displayField, info);
        final String countyField = fieldOf(lay.countyField, info);
        // One color language when the catalog says where the status is; the service's
        // own symbols otherwise (and always for points, which are icons, not zones).
        final boolean normalize = statusField != null && !isPoint;
        final Set<String> dates = info.dateFields;
        final String setName = source.layers.get(0) == lay ? info.name : source.layers.get(0).publisher;

        Esri.query(lay.url, lay.layer, lay.where, Math.min(1000, info.maxRecordCount),
                source.maxFeatures, lay.simplify, new Esri.FeatureSink() {
                    @Override
                    public void feature(JSONObject props, Geometry g) throws Exception {
                        final String cls = renderer.labelFor(props);
                        final String name = Esri.firstNonEmpty(str(props, nameField), str(props, displayField), cls, info.name);
                        final String statusText = Esri.firstNonEmpty(str(props, statusField), cls);
                        final String zoneKey = isPoint ? lay.publisher + ":" + name + ":" + g.getEnvelope().minX + "," + g.getEnvelope().minY
                                : shortLabel(name);
                        Pending existing = byKey.get(zoneKey);
                        if (existing == null && !authority && !isPoint && !isLine)
                            existing = sameZone(byKey.values(), labelPoint(g), g.getEnvelope());
                        if (existing != null) {
                            // Already have this zone: add the fields it lacks, keep its status.
                            final AttributeSet a = existing.attrs;
                            final java.util.Iterator<String> keys = props.keys();
                            while (keys.hasNext()) {
                                final String k = keys.next();
                                if (props.isNull(k) || a.containsAttribute(k))
                                    continue;
                                final Object v = props.opt(k);
                                if (v == null)
                                    continue;
                                a.setAttribute(k, dates.contains(k) && v instanceof Number
                                        ? new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US)
                                                .format(new java.util.Date(((Number) v).longValue()))
                                        : String.valueOf(v));
                            }
                            if (!authority && !a.containsAttribute("_also"))
                                a.setAttribute("_also", lay.publisher);
                            return;
                        }
                        Style style;
                        String key;
                        int color = 0;
                        if (normalize) {
                            final StatusColors.Level level = StatusColors.classify(statusText);
                            style = isLine ? StatusColors.line(level) : StatusColors.polygon(level, FILL_ALPHA);
                            key = level == StatusColors.Level.OTHER && statusText != null ? statusText : level.label;
                            color = level.color;
                        } else {
                            style = renderer.styleFor(props);
                            key = statusText == null ? "(no status)" : statusText;
                        }
                        Geometry geometry = g;
                        String featureName = name;
                        Point at = null;
                        if (isLine) {
                            style = Styles.silentLabel(style);
                        } else if (!isPoint) {
                            at = labelPoint(g);
                            if (at != null) {
                                final GeometryCollection gc = new GeometryCollection(2);
                                gc.addGeometry(g);
                                gc.addGeometry(at);
                                geometry = gc;
                                style = Styles.withNameLabel(style);
                                featureName = shortLabel(name);
                            } else {
                                style = Styles.silentLabel(style);
                            }
                        }
                        final String title = statusText != null && !statusText.equals(name)
                                ? statusText + ": " + name : name;
                        final AttributeSet attrs = Esri.toAttributes(props, dates);
                        attrs.setAttribute("_title", title);
                        if (statusText != null)
                            attrs.setAttribute("_status", statusText);
                        if (!authority)
                            attrs.setAttribute("_also", lay.publisher);
                        final Pending pf = new Pending(setName, Double.MAX_VALUE, featureName, geometry, style, attrs);
                        pf.title = title;
                        pf.statusKey = key;
                        pf.color = color;
                        if (countyField != null && !props.isNull(countyField))
                            pf.county = props.optString(countyField, null);
                        pf.layerCounty = lay.county;
                        if (at != null) {
                            pf.lat = at.getY();
                            pf.lon = at.getX();
                        }
                        byKey.put(zoneKey, pf);
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
        // Genasys: US-<state>-<county code>-<city code>-<number>[-<part>]. Drop the
        // country, state and county; keep everything from the city code on, so
        // US-CA-XMY-FHL-G012-A is FHL-G012-A and US-CA-SLC-002 is SLC-002.
        if (p.length >= 4 && "US".equals(p[0]) && p[1].length() == 2 && p[2].length() == 3) {
            final StringBuilder sb = new StringBuilder();
            for (int i = 3; i < p.length; i++)
                sb.append(i > 3 ? "-" : "").append(p[i]);
            return sb.toString();
        }
        if (p.length == 3 && "US".equals(p[0]) && p[1].length() == 2)
            return p[2];
        return name;
    }

    /**
     * The same zone under another agency's id: an authority zone whose center is within
     * 150 m of this one's and whose box overlaps it by more than half. County feeds do
     * not all use the Genasys id, so the key alone would draw a Sonoma zone twice.
     */
    private static Pending sameZone(java.util.Collection<Pending> have, Point at, Envelope env) {
        if (at == null || env == null)
            return null;
        final double area = Math.max(1e-12, (env.maxX - env.minX) * (env.maxY - env.minY));
        for (Pending pf : have) {
            if (pf.env == null || Double.isNaN(pf.lat))
                continue;
            if (Math.abs(pf.lat - at.getY()) > 0.01 || Math.abs(pf.lon - at.getX()) > 0.01)
                continue;
            final double d = com.atakmap.coremap.maps.coords.GeoCalculations.distanceTo(
                    new GeoPoint(pf.lat, pf.lon), new GeoPoint(at.getY(), at.getX()));
            if (d > 150)
                continue;
            final double ix = Math.min(pf.env.maxX, env.maxX) - Math.max(pf.env.minX, env.minX);
            final double iy = Math.min(pf.env.maxY, env.maxY) - Math.max(pf.env.minY, env.minY);
            if (ix <= 0 || iy <= 0)
                continue;
            final double inter = ix * iy;
            final double other = Math.max(1e-12, (pf.env.maxX - pf.env.minX) * (pf.env.maxY - pf.env.minY));
            if (inter / (area + other - inter) > 0.5)
                return pf;
        }
        return null;
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
            for (Geometry c : ((GeometryCollection) g).getGeometries()) {
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

    /** A polygon, or a collection holding one (a zone with its label point). */
    static boolean hasPolygon(Geometry g) {
        if (g instanceof com.atakmap.map.layer.feature.geometry.Polygon)
            return true;
        if (g instanceof GeometryCollection)
            for (Geometry c : ((GeometryCollection) g).getGeometries())
                if (hasPolygon(c))
                    return true;
        return false;
    }

    private static String str(JSONObject p, String k) {
        return k == null || p.isNull(k) ? null : p.optString(k, null);
    }

    /** The field if the layer has it, else null: a catalog typo costs a label, not the layer. */
    private static String fieldOf(String name, Esri.LayerInfo info) {
        return name != null && !name.isEmpty() && info.fields.contains(name) ? name : null;
    }

    private long newSet(String name, double minGsd) throws Exception {
        final long id = store.insertFeatureSet(new FeatureSet("EvacZone", source.id, name, minGsd, 0d));
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
