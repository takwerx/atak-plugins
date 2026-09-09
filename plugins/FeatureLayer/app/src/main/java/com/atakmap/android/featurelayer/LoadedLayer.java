package com.atakmap.android.featurelayer;

import android.content.Context;

import com.atakmap.android.features.FeatureDataStoreDeepMapItemQuery;
import com.atakmap.android.features.FeatureDataStoreMapOverlay;
import com.atakmap.android.maps.MapItem;
import com.atakmap.android.maps.MapView;
import com.atakmap.android.menu.PluginMenuParser;
import com.atakmap.coremap.log.Log;
import com.atakmap.map.layer.feature.AttributeSet;
import com.atakmap.map.layer.feature.Feature;
import com.atakmap.map.layer.feature.FeatureDataStore2;
import com.atakmap.map.layer.feature.FeatureLayer3;
import com.atakmap.map.layer.feature.FeatureSet;
import com.atakmap.map.layer.feature.FeatureSetCursor;
import com.atakmap.map.layer.feature.datastore.FeatureSetDatabase2;
import com.atakmap.map.layer.feature.geometry.Geometry;
import com.atakmap.map.layer.feature.geometry.GeometryCollection;
import com.atakmap.map.layer.feature.geometry.LineString;
import com.atakmap.map.layer.feature.style.Style;

import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * One loaded layer on the map: a file-backed feature store (so it is there again after a
 * restart, with its age shown, and stays up without service), an ATAK feature layer on
 * the vector overlays stack, an Overlay Manager entry, and a refresh that swaps the
 * contents set by set.
 */
public class LoadedLayer {

    private static final String TAG = "FeatureLayer";

    /** NWCG point categories that are repair bookkeeping; drawn only when zoomed well in. */
    private static final Set<String> REPAIR = new HashSet<>(Arrays.asList(
            "Slash Pile", "Dozer Push", "Culvert", "Hazard Tree", "Road Repair",
            "Fence - Cut/Damaged", "Stream Crossing", "Structure Wrap", "Water Bar"));

    private static final double GSD_ALWAYS = Double.MAX_VALUE, GSD_LINES = 400d, GSD_POINTS = 120d, GSD_REPAIR = 12d;
    /** Line marks: coarse ones between these resolutions, fine ones closer in. */
    private static final double GSD_MARKS_COARSE_MIN = 400d, GSD_MARKS_SPLIT = 25d;

    public final LayerSpec spec;
    private final MapView mapView;
    private final Context pluginContext;
    private final File storeFile;
    private final File iconDir;
    private final Map<String, String> nwcgIcons;
    private final Map<String, String> sarcopIcons;
    private final String lineGlyph, polygonGlyph;
    private final ChooserGlyphs glyphs;
    private final PointIcons pointIcons;

    private FeatureSetDatabase2 store;
    private FeatureLayer3 layer;
    private FeatureDataStoreMapOverlay overlay;

    public volatile String status = "";
    public volatile long lastRefresh;
    public volatile int count;
    public volatile boolean refreshing;
    /** A store rewrite (ON/OFF from memory) is in progress. */
    public volatile boolean busy;
    /** Features fetched so far during a refresh, for the pane's loading line. */
    public volatile int progress;
    public volatile boolean stale;
    private volatile boolean closed;
    /** The last "anything new?" answer, and the where clause shape it was for. */
    private String lastStamp = "", lastStampWhere = "";
    /** Every store access, on any thread, holds this: the store is not thread-safe. */
    private final Object lock = new Object();

    public LoadedLayer(LayerSpec spec, MapView mapView, Context pluginContext, File storeFile,
            File iconDir, Map<String, String> nwcgIcons, Map<String, String> sarcopIcons,
            String lineGlyph, String polygonGlyph, long lastRefresh) {
        this.spec = spec;
        this.mapView = mapView;
        this.pluginContext = pluginContext;
        this.storeFile = storeFile;
        this.iconDir = iconDir;
        this.glyphs = new ChooserGlyphs(iconDir);
        this.pointIcons = new PointIcons(iconDir);
        this.nwcgIcons = nwcgIcons;
        this.sarcopIcons = sarcopIcons;
        this.lineGlyph = lineGlyph;
        this.polygonGlyph = polygonGlyph;
        this.lastRefresh = lastRefresh;
    }

    public String displayName() {
        return spec.title + " (" + spec.subtitle + ")";
    }

    /** One feature's attributes by id, without its geometry or style; null when absent. */
    private AttributeSet attributesOf(long fid) {
        final FeatureDataStore2 s = store;
        if (s == null)
            return null;
        com.atakmap.map.layer.feature.FeatureCursor c = null;
        try {
            final FeatureDataStore2.FeatureQueryParameters p = new FeatureDataStore2.FeatureQueryParameters();
            p.ids = java.util.Collections.singleton(fid);
            p.ignoredFeatureProperties = FeatureDataStore2.PROPERTY_FEATURE_GEOMETRY
                    | FeatureDataStore2.PROPERTY_FEATURE_STYLE;
            p.limit = 1;
            c = s.queryFeatures(p);
            if (c.moveToNext())
                return c.get().getAttributes();
        } catch (Exception e) {
            Log.w(TAG, spec.id + ": attributes of " + fid + " failed", e);
        } finally {
            if (c != null)
                try { c.close(); } catch (Exception ignored) { }
        }
        return null;
    }

    /** Opens the store (existing file = cached contents) and puts the layer on the map. */
    public void attach() throws Exception {
        synchronized (lock) {
            attachLocked();
        }
    }

    private void attachLocked() throws Exception {
        closed = false;
        store = new FeatureSetDatabase2(storeFile);
        // Visible features only: with the plain constructor the renderer kept drawing the
        // labels of hidden sets on the XCover.
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
                item.setMetaString("nifs_layer", spec.id);
                // The hit-test query asks the store for features without attributes
                // (ignoredFeatureProperties = ATTRIBUTES), so fetch them for this one.
                AttributeSet a = feature.getAttributes();
                if (a == null)
                    a = attributesOf(feature.getId());
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
                // A line gets a drawn glyph in its own color, dashed when it is, with three
                // of its NWCG marks; a polygon the plain outline glyph tinted. Dark colors
                // are lightened so a black dozer line reads on the chooser's dark rows.
                if (!EsriRenderer.isPoint(feature.getGeometry())) {
                    final Geometry g = feature.getGeometry();
                    final boolean poly = isArea(g);
                    final com.atakmap.android.maps.Shape shape = item instanceof com.atakmap.android.maps.Shape
                            ? (com.atakmap.android.maps.Shape) item : null;
                    final int color = ChooserGlyphs.visible(shape == null ? 0xFFFFFFFF : shape.getStrokeColor());
                    String uri = poly ? polygonGlyph : lineGlyph;
                    int tint = color;
                    String cat = null;
                    if (spec.profile == LayerSpec.Profile.NWCG && a != null) {
                        try {
                            cat = a.getStringAttribute("FeatureCategory");
                        } catch (Exception ignored) {
                        }
                    }
                    String drawn = cat == null ? null : (poly ? glyphs.nwcgPolygon(cat) : glyphs.nwcg(cat));
                    if (drawn == null && !poly)
                        drawn = glyphs.generic(color, shape != null
                                && shape.getStrokeStyle() != com.atakmap.android.maps.Shape.BASIC_LINE_STYLE_SOLID);
                    if (drawn != null) {
                        uri = drawn;
                        tint = 0xFFFFFFFF; // the glyph carries its color
                    }
                    item.setMetaString("iconUri", uri);
                    item.setMetaInteger("iconColor", tint);
                    item.setMetaInteger("color", tint);
                }
                return item;
            }

            // ATAK hands the same feature back once per hit-test control (the polygon
            // came up twice in Select Item); keep the first of each feature id.
            @Override
            public java.util.SortedSet<MapItem> deepHitTest(MapView view,
                    com.atakmap.map.hittest.HitTestQueryParameters params,
                    java.util.Map<com.atakmap.map.layer.Layer2, java.util.Collection<com.atakmap.map.hittest.HitTestControl>> controls) {
                return dedupe(super.deepHitTest(view, params, controls));
            }

            @Override
            public java.util.SortedSet<MapItem> deepHitTestItems(int xpos, int ypos, com.atakmap.coremap.maps.coords.GeoPoint point, MapView view) {
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

    /**
     * A refresh interrupted by a plugin reload can leave two generations of sets; keep
     * the newest of each name and drop the rest.
     */
    private void dedupeSets() {
        final Map<String, Long> newest = new HashMap<>();
        final List<Long> drop = new ArrayList<>();
        final List<SetInfo> all = setsLocked();
        Log.d(TAG, spec.id + ": " + all.size() + " sets on open");
        for (SetInfo si : all) {
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
                Log.d(TAG, spec.id + ": dropped duplicate set " + id);
            } catch (Exception e) {
                Log.w(TAG, "dedupe " + id, e);
            }
        }
        Log.d(TAG, spec.id + ": " + setsLocked().size() + " sets after dedupe (" + drop.size() + " dropped)");
    }

    public void detach() {
        closed = true;
        synchronized (lock) {
            detachLocked();
        }
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
            Log.w(TAG, "detach " + spec.id, e);
        }
        layer = null;
        overlay = null;
        store = null;
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

    /**
     * Layer on/off. Returns true when a fetch is needed to show it (no memory copy, e.g.
     * after a restart); the manager then refreshes.
     */
    public boolean setVisible(boolean v) {
        layerOn = v;
        synchronized (lock) {
            if (store == null)
                return false;
            loadCacheLocked();
            if (v && cache.isEmpty())
                return countFeatures() == 0;
            rewriteStore();
        }
        return false;
    }

    /**
     * After a restart the store on disk is full and the memory copy is empty. Every
     * rewrite works from the memory copy, so an empty one must be filled from the store
     * first; without this, the first type toggle after a restart deleted every feature
     * and, offline, nothing could bring them back (Plaskett, 2026-09-09).
     */
    private void loadCacheLocked() {
        if (!cache.isEmpty() || store == null)
            return;
        final List<Pending> loaded = new ArrayList<>();
        try {
            final Map<Long, String> names = new HashMap<>();
            final FeatureSetCursor sc = store.queryFeatureSets(new FeatureDataStore2.FeatureSetQueryParameters());
            try {
                while (sc.moveToNext())
                    names.put(sc.getId(), sc.getName());
            } finally {
                sc.close();
            }
            final com.atakmap.map.layer.feature.FeatureCursor c = store.queryFeatures(new FeatureDataStore2.FeatureQueryParameters());
            try {
                while (c.moveToNext()) {
                    final Feature f = c.get();
                    final String setName = names.get(f.getFeatureSetId());
                    if (setName == null)
                        continue;
                    // The kind's own zoom default, never the store's number: the store holds
                    // the gate-capped value, and rebuilding from it made the cap permanent
                    // (Plaskett gated at level 14 on every type, perimeter included, 2026-09-09).
                    loaded.add(new Pending(setName, defaultGsd(setName, f.getGeometry()), f.getName(), f.getGeometry(),
                            f.getStyle(), f.getAttributes()));
                }
            } finally {
                c.close();
            }
        } catch (Exception e) {
            Log.w(TAG, spec.id + ": could not load the cache from the store", e);
            return;
        }
        if (!loaded.isEmpty()) {
            cache = loaded;
            Log.d(TAG, spec.id + ": memory copy rebuilt from the store, " + loaded.size() + " features");
        }
    }

    private boolean layerOn = true;

    /** Everything fetched last time, shown or not; the store holds only the shown part. */
    private List<Pending> cache = new ArrayList<>();
    /** Rings of the fire perimeter polygons seen in the current fetch, lon/lat pairs. */
    private final List<double[]> perimeterRings = new ArrayList<>();
    /** Holes (unburned islands) in those polygons; a point in a hole is outside the fire. */
    private final List<double[]> perimeterHoles = new ArrayList<>();

    /** Records the intended layer state without touching the store (the worker does that). */
    public void markVisible(boolean v) {
        layerOn = v;
    }

    /** One feature type on/off, by name. Returns true when a fetch is needed to show it. */
    /** The layer's zoom gate, from the memory copy; nothing is fetched. */
    public void setGate(double metersPerPixel) {
        spec.gateGsd = metersPerPixel;
        synchronized (lock) {
            if (store == null || closed)
                return;
            loadCacheLocked();
            if (!cache.isEmpty())
                rewriteStore();
        }
    }

    /** Point labels on or off, from the memory copy; nothing is fetched. */
    public void setLabels(boolean on) {
        spec.labels = on;
        synchronized (lock) {
            if (store == null || closed)
                return;
            loadCacheLocked();
            if (!cache.isEmpty())
                rewriteStore();
        }
    }

    /** The zoom default a set's kind gets at fetch time: repair points close in, points, lines, areas always. */
    private static double defaultGsd(String setName, Geometry g) {
        if (setName != null && setName.endsWith(" (repair)"))
            return GSD_REPAIR;
        if (isArea(g))
            return GSD_ALWAYS;
        if (g instanceof com.atakmap.map.layer.feature.geometry.Point)
            return GSD_POINTS;
        if (g instanceof GeometryCollection) {
            boolean anyLine = false;
            for (Geometry c : ((GeometryCollection) g).getGeometries())
                if (c instanceof LineString)
                    anyLine = true;
            return anyLine ? GSD_LINES : GSD_POINTS;
        }
        return GSD_LINES;
    }

    /** Repair Status halos on or off, from the memory copy; nothing is fetched. */
    public void setRepairStatus(boolean on) {
        spec.repairStatus = on;
        synchronized (lock) {
            if (store == null || closed)
                return;
            loadCacheLocked();
            if (!cache.isEmpty())
                rewriteStore();
        }
    }

    public boolean setSetVisible(String setName, boolean v) {
        spec.setOn.put(setName, v);
        synchronized (lock) {
            if (store == null)
                return false;
            loadCacheLocked();
            if (v) {
                // A type that was off at the last fetch is not in the store, so the memory
                // copy cannot show it either: that one needs a fetch.
                boolean have = false;
                for (Pending pf : cache)
                    if (setName.equals(pf.setName)) {
                        have = true;
                        break;
                    }
                if (!have)
                    return true;
            }
            rewriteStore();
        }
        return false;
    }

    /** Removes from the store what should not be shown right now. Lock held. */
    private void pruneHidden() {
        boolean bulk = false;
        try {
            store.acquireModifyLock(true);
            bulk = true;
            for (SetInfo si : setsLocked()) {
                if (layerOn && spec.isOn(si.name))
                    continue;
                try {
                    store.deleteFeatureSet(si.id);
                } catch (Exception e) {
                    Log.w(TAG, "prune " + si.name, e);
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "prune failed", e);
        } finally {
            if (bulk)
                store.releaseModifyLock();
        }
    }

    /**
     * Makes the store hold exactly the shown part of the memory copy: new sets in, then
     * the previous ones out. Lock held.
     */
    private void rewriteStore() {
        if (cache.isEmpty()) {
            // Never trade a full store for an empty memory copy.
            Log.w(TAG, spec.id + ": rewrite skipped, nothing in memory to write");
            return;
        }
        // Bulk mode: one content-changed notification at the end instead of one per
        // insert, each of which had ATAK re-querying the store and stalling its main thread.
        boolean bulk = false;
        try {
            store.acquireModifyLock(true);
            bulk = true;
            final List<Long> old = existingSets();
            final Map<String, Long> sets = new HashMap<>();
            for (Pending pf : cache) {
                if (!layerOn || !spec.isOn(pf.setName))
                    continue;
                Long fsid = sets.get(pf.setName);
                if (fsid == null) {
                    // The kind's own gate (points 120 m/px, lines 400) capped by the layer's.
                    fsid = newSet(store, pf.setName, Math.min(pf.minGsd, spec.gateGsd));
                    sets.put(pf.setName, fsid);
                }
                Style drawn = spec.repairStatus && pf.alt != null ? pf.alt : pf.style;
                if (!spec.labels && !(pf.geometry instanceof LineString))
                    drawn = NwcgStyles.withoutLabel(drawn); // a transparent label beats the name
                store.insertFeature(new Feature(fsid, pf.name, pf.geometry, drawn, pf.attrs,
                        Feature.AltitudeMode.ClampToGround, 0d));
            }
            int dropped = 0;
            for (Long id : old) {
                try {
                    store.deleteFeatureSet(id);
                    dropped++;
                } catch (Exception e) {
                    Log.w(TAG, "old set " + id, e);
                }
            }
            count = countFeatures();
            Log.d(TAG, spec.id + ": store rewritten, " + count + " shown of " + cache.size()
                    + " (" + dropped + " old sets dropped)");
        } catch (Exception e) {
            Log.w(TAG, "store rewrite failed", e);
        } finally {
            if (bulk)
                store.releaseModifyLock();
        }
    }

    /** Frames the whole incident, with a margin; falls back to a pan when no extent is known. */
    public void panTo() {
        try {
            final double[] b = spec.bounds;
            if (b != null && b[2] > b[0] && b[3] > b[1]) {
                final double padLat = Math.max(0.002, (b[2] - b[0]) * 0.15);
                final double padLon = Math.max(0.002, (b[3] - b[1]) * 0.15);
                final com.atakmap.coremap.maps.coords.GeoPoint[] corners = {
                        new com.atakmap.coremap.maps.coords.GeoPoint(b[0] - padLat, b[1] - padLon),
                        new com.atakmap.coremap.maps.coords.GeoPoint(b[2] + padLat, b[3] + padLon) };
                com.atakmap.android.util.ATAKUtilities.scaleToFit(mapView, corners, 0d,
                        mapView.getWidth(), mapView.getHeight());
                return;
            }
            if (!Double.isNaN(spec.lat) && !Double.isNaN(spec.lon))
                mapView.getMapController().panTo(new com.atakmap.coremap.maps.coords.GeoPoint(spec.lat, spec.lon), true);
        } catch (Exception e) {
            Log.w(TAG, "go to failed", e);
        }
    }

    // ---- refresh ------------------------------------------------------------------

    /**
     * Fetches every source layer into new feature sets, then drops the old ones, so the
     * map never goes blank. Worker thread. {@code token} null for public services.
     */
    /**
     * South, west, north, east of the incident's body: the median center of all fetched
     * geometries, then everything within 1.5 degrees of it. A stray zero coordinate or a
     * mis-digitized point far away must not stretch "Go to" across half the planet.
     */
    private static double[] extentOf(List<Pending> features) {
        final List<double[]> boxes = new ArrayList<>();
        for (Pending pf : features) {
            if (pf.geometry == null)
                continue;
            final com.atakmap.map.layer.feature.geometry.Envelope env = pf.geometry.getEnvelope();
            if (env == null || Double.isNaN(env.minX) || Double.isNaN(env.minY))
                continue;
            if (Math.abs(env.minX) < 1e-6 || Math.abs(env.minY) < 1e-6 || Math.abs(env.maxX) < 1e-6 || Math.abs(env.maxY) < 1e-6)
                continue; // a zero coordinate is a missing one
            boxes.add(new double[] { env.minY, env.minX, env.maxY, env.maxX });
        }
        if (boxes.isEmpty())
            return null;
        final double[] lats = new double[boxes.size()], lons = new double[boxes.size()];
        for (int i = 0; i < boxes.size(); i++) {
            lats[i] = (boxes.get(i)[0] + boxes.get(i)[2]) / 2;
            lons[i] = (boxes.get(i)[1] + boxes.get(i)[3]) / 2;
        }
        java.util.Arrays.sort(lats);
        java.util.Arrays.sort(lons);
        final double midLat = lats[lats.length / 2], midLon = lons[lons.length / 2];
        double s = 90, w = 180, n = -90, e = -180;
        boolean any = false;
        for (double[] b : boxes) {
            if (Math.abs((b[0] + b[2]) / 2 - midLat) > 1.5 || Math.abs((b[1] + b[3]) / 2 - midLon) > 1.5)
                continue;
            s = Math.min(s, b[0]);
            w = Math.min(w, b[1]);
            n = Math.max(n, b[2]);
            e = Math.max(e, b[3]);
            any = true;
        }
        return any ? new double[] { s, w, n, e } : null;
    }

    private static double[] flat(LineString ring) {
        final double[] r = new double[ring.getNumPoints() * 2];
        for (int i = 0; i < ring.getNumPoints(); i++) {
            r[2 * i] = ring.getX(i);
            r[2 * i + 1] = ring.getY(i);
        }
        return r;
    }

    private void collectRings(Geometry g) {
        if (g instanceof com.atakmap.map.layer.feature.geometry.Polygon) {
            final com.atakmap.map.layer.feature.geometry.Polygon poly = (com.atakmap.map.layer.feature.geometry.Polygon) g;
            final LineString ring = poly.getExteriorRing();
            if (ring != null && ring.getNumPoints() > 2)
                perimeterRings.add(flat(ring));
            for (LineString hole : poly.getInteriorRings())
                if (hole != null && hole.getNumPoints() > 2)
                    perimeterHoles.add(flat(hole));
        } else if (g instanceof GeometryCollection) {
            for (Geometry c : ((GeometryCollection) g).getGeometries())
                collectRings(c);
        }
    }

    private static boolean inRing(double[] r, double lon, double lat) {
        boolean in = false;
        final int n = r.length / 2;
        for (int i = 0, j = n - 1; i < n; j = i++) {
            final double xi = r[2 * i], yi = r[2 * i + 1], xj = r[2 * j], yj = r[2 * j + 1];
            if ((yi > lat) != (yj > lat) && lon < (xj - xi) * (lat - yi) / (yj - yi) + xi)
                in = !in;
        }
        return in;
    }

    /** Inside the fire: within an outer ring and not within a hole (an unburned island). */
    private boolean insidePerimeter(double lon, double lat) {
        boolean inOuter = false;
        for (double[] r : perimeterRings)
            if (inRing(r, lon, lat)) {
                inOuter = true;
                break;
            }
        if (!inOuter)
            return false;
        for (double[] h : perimeterHoles)
            if (inRing(h, lon, lat))
                return false;
        return true;
    }

    /** A search hit: what it is, where it is. */
    public static class Hit {
        public final String title, layer, layerId, type;
        public final double lat, lon, spanDeg;
        /** When the feature was collected or last edited, epoch ms; 0 when the data says nothing. */
        public final long time;

        Hit(String title, String layer, String layerId, String type, long time, double lat, double lon, double spanDeg) {
            this.title = title;
            this.layer = layer;
            this.layerId = layerId;
            this.type = type;
            this.time = time;
            this.lat = lat;
            this.lon = lon;
            this.spanDeg = spanDeg;
        }
    }

    /** Features whose title, name or any attribute contains the text, from the memory copy. */
    /** Every loaded feature whose name, title or any attribute contains the text; all of them when blank. */
    public List<Hit> find(String text, int max) {
        final List<Hit> out = new ArrayList<>();
        final String needle = text.trim().toLowerCase(Locale.US);
        final List<Pending> snapshot = cache;
        for (Pending pf : snapshot) {
            if (pf.setName.endsWith(" marks"))
                continue;
            String title = pf.name, type = pf.setName;
            long time = 0;
            boolean match = needle.isEmpty() || (pf.name != null && pf.name.toLowerCase(Locale.US).contains(needle));
            if (pf.attrs != null) {
                try {
                    time = pf.attrs.getLongAttribute("_time");
                } catch (Exception ignored) {
                }
                for (String k : pf.attrs.getAttributeNames()) {
                    String v;
                    try {
                        v = pf.attrs.getStringAttribute(k);
                    } catch (Exception e) {
                        continue;
                    }
                    if (v == null)
                        continue;
                    if ("_title".equals(k))
                        title = v;
                    else if ("_type".equals(k))
                        type = v;
                    else if (!match && !k.startsWith("_") && v.toLowerCase(Locale.US).contains(needle))
                        match = true;
                }
            }
            if (!match && title != null && title.toLowerCase(Locale.US).contains(needle))
                match = true;
            if (!match || pf.geometry == null)
                continue;
            final com.atakmap.map.layer.feature.geometry.Envelope e = pf.geometry.getEnvelope();
            if (e == null || Double.isNaN(e.minX))
                continue;
            out.add(new Hit(title, spec.title, spec.id, type, time, (e.minY + e.maxY) / 2, (e.minX + e.maxX) / 2,
                    Math.max(e.maxX - e.minX, e.maxY - e.minY)));
            if (out.size() >= max)
                break;
        }
        return out;
    }

    /** Zooms onto a hit: the feature's own extent, or a few hundred meters around a point. */
    public void zoomTo(Hit h) {
        try {
            final double half = Math.max(0.0025, h.spanDeg * 0.75);
            final com.atakmap.coremap.maps.coords.GeoPoint[] corners = {
                    new com.atakmap.coremap.maps.coords.GeoPoint(h.lat - half, h.lon - half),
                    new com.atakmap.coremap.maps.coords.GeoPoint(h.lat + half, h.lon + half) };
            com.atakmap.android.util.ATAKUtilities.scaleToFit(mapView, corners, 0d, mapView.getWidth(), mapView.getHeight());
        } catch (Exception e) {
            Log.w(TAG, "zoom to hit failed", e);
        }
    }

    /** A feature fetched and styled, waiting to be written into the store. */
    private static class Pending {
        final String setName;
        final double minGsd;
        double maxGsd = 0d;
        final String name;
        final Geometry geometry;
        final Style style;
        /** The same feature with its Repair Status drawn, when it has one; written instead of {@link #style} on request. */
        Style alt;
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

    /**
     * Two phases. Fetch and style everything with no lock held, so the pane stays live.
     * Then, briefly under the lock, write the new sets into the same store and drop the
     * previous ones. The layer object is never recreated: ATAK keeps labels of layers that
     * are thrown away, and a recreated layer forgets it was hidden.
     */
    public void refresh(String token, Runnable progress) {
        if (store == null || closed || refreshing)
            return;
        refreshing = true;
        this.progress = 0;
        status = "refreshing";
        if (progress != null)
            progress.run();
        final List<Pending> pending = new ArrayList<>();
        final List<String> problems = new ArrayList<>();
        try {
            // A windowed live layer asks "anything new?" first: count and newest time per
            // source layer. Same answer as last time and something already drawn: done.
            if (spec.timeField != null && spec.live && !cache.isEmpty()) {
                final StringBuilder now = new StringBuilder();
                for (int layerId : spec.layerIds)
                    now.append(Esri.stamp(spec.base, layerId, spec.whereNow(), token, spec.timeField)).append(';');
                if (now.toString().equals(lastStamp) && !lastStampWhere.equals(spec.whereNow().replaceAll("'[^']*'", ""))) {
                    // the where changed shape (a new window), so fetch anyway
                } else if (now.toString().equals(lastStamp)) {
                    lastRefresh = System.currentTimeMillis();
                    stale = false;
                    status = "ok, no change";
                    return;
                }
                lastStamp = now.toString();
                lastStampWhere = spec.whereNow().replaceAll("'[^']*'", "");
            }
            perimeterRings.clear();
            perimeterHoles.clear();
            for (int layerId : spec.layerIds) {
                if (closed)
                    throw new IllegalStateException("layer closed");
                try {
                    fetchSourceLayer(layerId, token, pending);
                } catch (Exception e) {
                    Log.w(TAG, spec.id + " layer " + layerId + " failed", e);
                    problems.add("layer " + layerId + ": " + e.getMessage());
                }
                this.progress = pending.size();
                status = "refreshing: " + pending.size();
                if (progress != null)
                    progress.run();
            }
            if (problems.size() == spec.layerIds.length)
                throw new IllegalStateException(problems.get(0));
            synchronized (lock) {
                if (store == null || closed)
                    throw new IllegalStateException("layer closed");
                cache = pending;
                spec.bounds = extentOf(pending);
                rewriteStore();
                Log.d(TAG, spec.id + ": refresh done, " + pending.size() + " fetched, store holds " + count);
            }
            lastRefresh = System.currentTimeMillis();
            stale = false;
            status = problems.isEmpty() ? "ok" : "partial: " + problems.get(0);
            if (spec.maxFeatures > 0 && pending.size() >= spec.maxFeatures * spec.layerIds.length)
                status = "capped at " + spec.maxFeatures + " per layer";
        } catch (Exception e) {
            Log.w(TAG, spec.id + " refresh failed", e);
            stale = true;
            status = "no update: " + e.getMessage();
        } finally {
            refreshing = false;
            if (progress != null)
                progress.run();
        }
    }

    private static final String[] TIME_FIELDS = { "PointDateTime", "LineDateTime", "PolygonDateTime",
            "EditDate", "last_edited_date", "EditDate_1", "CreateDate", "CreationDate", "created_date", "DateCurrent" };

    /** When a feature was collected or last edited, epoch ms from the service's own date fields; 0 when none. */
    private static long collectedAt(JSONObject props) {
        for (String k : TIME_FIELDS) {
            final Object v = props.opt(k);
            if (v instanceof Number && ((Number) v).longValue() > 0)
                return ((Number) v).longValue();
        }
        final java.util.Iterator<String> it = props.keys();
        while (it.hasNext()) {
            final String k = it.next();
            final String lk = k.toLowerCase(Locale.US);
            if (!lk.contains("date") && !lk.contains("time"))
                continue;
            final Object v = props.opt(k);
            if (v instanceof Number && ((Number) v).longValue() > 0)
                return ((Number) v).longValue();
        }
        return 0;
    }

    /** A polygon, or a collection holding one (an area with its center point, a multipolygon). */
    private static boolean isArea(Geometry g) {
        if (g instanceof com.atakmap.map.layer.feature.geometry.Polygon)
            return true;
        if (g instanceof GeometryCollection)
            for (Geometry c : ((GeometryCollection) g).getGeometries())
                if (isArea(c))
                    return true;
        return false;
    }

    /**
     * Where an area's name goes: the centroid of its largest ring (Evac Zone's rule). A
     * degenerate ring falls back to the envelope center; null when there is no ring.
     */
    static com.atakmap.map.layer.feature.geometry.Point labelPoint(Geometry g) {
        com.atakmap.map.layer.feature.geometry.Polygon best = null;
        double bestArea = -1;
        if (g instanceof com.atakmap.map.layer.feature.geometry.Polygon) {
            best = (com.atakmap.map.layer.feature.geometry.Polygon) g;
        } else if (g instanceof GeometryCollection) {
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
        final LineString ring = best.getExteriorRing();
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
            final com.atakmap.map.layer.feature.geometry.Envelope e = best.getEnvelope();
            return e == null ? null : new com.atakmap.map.layer.feature.geometry.Point((e.minX + e.maxX) / 2, (e.minY + e.maxY) / 2);
        }
        a *= 0.5;
        return new com.atakmap.map.layer.feature.geometry.Point(cx / (6 * a), cy / (6 * a));
    }

    private static double ringArea(LineString ring) {
        if (ring == null || ring.getNumPoints() < 3)
            return 0;
        double a = 0;
        final int n = ring.getNumPoints();
        for (int i = 0; i < n; i++) {
            final int j = (i + 1) % n;
            a += ring.getX(i) * ring.getY(j) - ring.getX(j) * ring.getY(i);
        }
        return a / 2;
    }

    /** Of the features added since {@code from}, keeps the newest per {@code spec.latestBy} key. */
    private void keepLatest(List<Pending> out, int from) {
        final Map<String, Pending> newest = new java.util.LinkedHashMap<>();
        final Map<String, Long> when = new HashMap<>();
        for (int i = from; i < out.size(); i++) {
            final Pending pf = out.get(i);
            final StringBuilder k = new StringBuilder();
            for (String choice : spec.latestBy) {
                String v = null;
                for (String f : choice.split("\\|")) {
                    try {
                        v = pf.attrs == null ? null : pf.attrs.getStringAttribute(f);
                    } catch (Exception ignored) {
                    }
                    if (v != null && !v.trim().isEmpty())
                        break;
                }
                k.append(v == null ? "" : v.trim().toUpperCase(Locale.US)).append('|');
            }
            long t = 0;
            try {
                t = pf.attrs.getLongAttribute("_time");
            } catch (Exception ignored) {
            }
            final String key = k.toString();
            final Long cur = when.get(key);
            if (cur == null || t > cur) {
                newest.put(key, pf);
                when.put(key, t);
            }
        }
        final int before = out.size() - from;
        while (out.size() > from)
            out.remove(out.size() - 1);
        out.addAll(newest.values());
        Log.d(TAG, spec.id + ": kept the latest " + newest.size() + " of " + before);
    }

    /** The NWCG point symbol for a category, tolerant of underscores, case and an "IR " prefix. */
    private String nwcgIcon(String cat) {
        if (cat != null) {
            String uri = nwcgIcons.get(cat);
            if (uri == null)
                uri = nwcgIcons.get(cat.replace('_', ' '));
            if (uri == null) {
                final String want = cat.replace('_', ' ').replace("IR ", "").trim().toLowerCase(Locale.US);
                for (Map.Entry<String, String> e : nwcgIcons.entrySet())
                    if (e.getKey().replace("IR ", "").toLowerCase(Locale.US).equals(want)) {
                        uri = e.getValue();
                        break;
                    }
            }
            if (uri != null)
                return uri;
        }
        return nwcgIcons.get("Other");
    }

    private void fetchSourceLayer(final int layerId, String token, final List<Pending> out) throws Exception {
        final Esri.LayerInfo info = Esri.layerInfo(spec.base, layerId, token);
        final boolean nwcg = spec.profile == LayerSpec.Profile.NWCG;
        final boolean isPointLayer = info.geometryType.contains("Point");
        final boolean isLineLayer = info.geometryType.contains("Polyline");
        spec.setKind.put(info.name, isPointLayer ? "point" : isLineLayer ? "line" : "polygon");
        final int fill = spec.fillFor(info.name);
        final EsriRenderer generic = nwcg ? null : new EsriRenderer(info.drawingInfo, info.geometryType, iconDir, fill);
        final double gsd = isPointLayer ? GSD_POINTS : isLineLayer ? GSD_LINES : GSD_ALWAYS;
        final String setName = info.name;
        final String repairName = (nwcg && isPointLayer) ? info.name + " (repair)" : null;
        final Set<String> dates = info.dateFields;
        final String displayField = info.displayField != null && info.fields.contains(info.displayField)
                ? info.displayField : null;
        final String layerName = info.name;

        final int firstOfLayer = out.size();
        Esri.query(spec.base, layerId, spec.whereNow(), token, spec.geojson,
                Math.min(spec.geojson ? 2000 : 1000, info.maxRecordCount), spec.maxFeatures, new Esri.FeatureSink() {
                    @Override
                    public void feature(JSONObject props, Geometry g) throws Exception {
                        final String cat = props.isNull("FeatureCategory") ? null : props.optString("FeatureCategory", null);
                        final String repair = props.isNull("RepairStatus") ? null : props.optString("RepairStatus", null);
                        String name, title;
                        Style style, alt = null;
                        String target = setName;
                        if (spec.setField != null) {
                            // One type per value of the field (FIRIS: USFS, CAL FIRE, NIFC...), so each can be toggled.
                            final String v = props.isNull(spec.setField) ? null : props.optString(spec.setField, null);
                            target = v == null || v.isEmpty() ? "Other" : v;
                            spec.setKind.put(target, isPointLayer ? "point" : isLineLayer ? "line" : "polygon");
                        }
                        double targetGsd = gsd;
                        if (nwcg) {
                            final String label = Esri.firstNonEmpty(props.optString("Label", null), props.optString("PointName", null));
                            // Map labels on points only: their own label when they have one, else
                            // the category ("Hot Spot - Spot Fire"). Lines and areas stay unlabeled,
                            // because ATAK repeats a line's label along its length and 700 of those
                            // made the map crawl.
                            name = label != null ? label : (cat != null ? cat : layerName);
                            title = cat == null ? (label != null ? label : layerName) : (label == null ? cat : cat + " " + label);
                            final String lname = layerName.replace('_', ' ').toLowerCase(Locale.US);
                            if (isPointLayer && lname.contains("label")) {
                                // Label Point: text only, the way the standard labels breaks.
                                final String text = Esri.firstNonEmpty(label, props.optString("LabelText", null),
                                        props.optString("Name", null));
                                if (text == null)
                                    return;
                                name = text;
                                title = "Label " + text;
                                style = NwcgStyles.label(text);
                            } else if (isPointLayer) {
                                // Event Point, IR Point, Accountable Property: the symbol, on its
                                // Repair Status disc when it has one.
                                final String uri = nwcgIcon(cat);
                                style = NwcgStyles.point(uri);
                                final int rc = NwcgStyles.repairColor(repair);
                                if (rc != 0)
                                    alt = NwcgStyles.point(pointIcons.withStatus(uri, rc, NwcgStyles.repairHollow(repair)));
                                if (repairName != null && REPAIR.contains(cat)) {
                                    target = repairName;
                                    targetGsd = GSD_REPAIR;
                                }
                            } else if (isLineLayer && lname.contains("perimeter")) {
                                style = NwcgStyles.perimeter(cat);
                            } else if (isLineLayer) {
                                style = NwcgStyles.line(cat);
                                final Style strat = NwcgStyles.strategic(props.isNull("StrategicLineType")
                                        ? null : props.optString("StrategicLineType", null));
                                if (strat != null)
                                    style = NwcgStyles.over(strat, style);
                                if (NwcgStyles.repairColor(repair) != 0)
                                    alt = NwcgStyles.withLineRepair(style, repair);
                            } else {
                                style = NwcgStyles.polygon(cat, fill);
                            }
                        } else {
                            final String labelKey = spec.labelField != null ? spec.labelField : displayField;
                            final String disp = labelKey == null || props.isNull(labelKey)
                                    ? null : Esri.firstNonEmpty(props.optString(labelKey, null));
                            final String cls = generic.labelFor(props);
                            // Map label: the display field's value, unless that is the very code
                            // the renderer classifies on ("other_haz"): then the class's own name
                            // ("Other Hazard"). Chooser title: the class, then the layer.
                            final String raw = generic.rawValueFor(props);
                            final boolean codeOnly = disp != null && raw != null && disp.equalsIgnoreCase(raw);
                            name = disp != null && !codeOnly ? disp : (cls != null ? cls : (disp != null ? disp : layerName));
                            title = (cls != null ? cls : (disp != null ? disp : layerName))
                                    + (cls != null && disp != null && !codeOnly && !cls.equals(disp) ? " " + disp : "")
                                    + " (" + layerName + ")";
                            style = generic.styleFor(props);
                            if ("sarcop".equals(spec.iconSet)) {
                                // NAPSG's own symbology, not the service's placeholder renderer.
                                final Style s = SarcopStyles.style(layerName, props, isPointLayer, isLineLayer,
                                        sarcopIcons, nwcgIcons);
                                if (s != null)
                                    style = s;
                            }
                        }
                        // ATAK draws an area's own label along its edge. Evac Zone's answer, used
                        // here: the area and its center point in one feature with an empty label
                        // style; the point child draws the feature's name at the center, the area
                        // child stays quiet. NWCG areas would only say "Wildfire Daily Fire
                        // Perimeter", so they get no name; lines never do (ATAK repeats a line's
                        // label along its length).
                        final com.atakmap.map.layer.feature.geometry.Point at = !nwcg && !isPointLayer && !isLineLayer
                                && name != null && !name.isEmpty() ? labelPoint(g) : null;
                        if (at != null) {
                            style = NwcgStyles.withNameLabel(style);
                            if (alt != null)
                                alt = NwcgStyles.withNameLabel(alt);
                        } else if (!isPointLayer) {
                            style = NwcgStyles.silentLabel(style);
                            if (alt != null)
                                alt = NwcgStyles.silentLabel(alt);
                        }
                        final AttributeSet attrs = Esri.toAttributes(props, dates);
                        attrs.setAttribute("_title", title);
                        // For the search pane: what kind of thing it is, and when it was collected.
                        attrs.setAttribute("_type", nwcg ? (cat != null ? cat : layerName)
                                : (generic.labelFor(props) != null ? generic.labelFor(props) : layerName));
                        final long when = spec.timeField != null && props.opt(spec.timeField) instanceof Number
                                ? ((Number) props.opt(spec.timeField)).longValue() : collectedAt(props);
                        if (when > 0)
                            attrs.setAttribute("_time", when);
                        // The fill control restyles areas in place from this hue; without it
                        // the button changed nothing (regressed unnoticed in the marks rewrite).
                        if (!isPointLayer && !isLineLayer) {
                            int hue = nwcg ? NwcgStyles.polygonFill(cat) : generic.rawFillFor(props);
                            if (!nwcg && "sarcop".equals(spec.iconSet)) {
                                final int h = SarcopStyles.fillHue(layerName, props);
                                if (h != 0)
                                    hue = h;
                            }
                            attrs.setAttribute("_fill", String.valueOf(hue));
                        }
                        // Only the fire perimeter says what is burned; an IR flight area or cloud
                        // cover polygon covers everything and put every tick on the default side.
                        if (nwcg && !isPointLayer && !isLineLayer && NwcgStyles.area(cat) == NwcgStyles.Area.WILDFIRE)
                            collectRings(g);
                        Geometry shown = g;
                        if (at != null) {
                            final GeometryCollection withCenter = new GeometryCollection(2);
                            withCenter.addGeometry(g);
                            withCenter.addGeometry(at);
                            shown = withCenter;
                        }
                        if (nwcg && isLineLayer) {
                            final NwcgDecor.Recipe recipe = NwcgStyles.recipe(cat);
                            if (recipe != null) {
                                // The standard's marks (letters, X's, ticks, zigzag) are geometry folded
                                // into the line's own feature: one thing to tap, one row in the chooser,
                                // drawn in the line's stroke, the line cut where a letter sits.
                                final NwcgDecor.Inside inside = new NwcgDecor.Inside() {
                                    @Override
                                    public boolean inside(double lon, double lat) {
                                        return insidePerimeter(lon, lat);
                                    }
                                };
                                final Geometry drawn = NwcgDecor.decorate(g, recipe, inside);
                                if (drawn != null)
                                    shown = drawn;
                            }
                        }
                        final Pending p = new Pending(target, targetGsd, name, shown, style, attrs);
                        p.alt = alt;
                        out.add(p);

                    }
                });
        if (spec.latestBy != null && spec.latestBy.length > 0)
            keepLatest(out, firstOfLayer);
    }

    private long newSet(FeatureSetDatabase2 db, String name, double minGsd) throws Exception {
        return newSet(db, name, minGsd, 0d);
    }

    private long newSet(FeatureSetDatabase2 db, String name, double minGsd, double maxGsd) throws Exception {
        final long id = db.insertFeatureSet(new FeatureSet("FeatureLayer", spec.id, name, minGsd, maxGsd));
        db.setFeatureSetVisible(id, true);
        return id;
    }

    /** A feature set (one source layer, or the NWCG repair points) and whether it draws. */
    public static class SetInfo {
        public final long id;
        public final String name;
        public final boolean visible;

        SetInfo(long id, String name, boolean visible) {
            this.id = id;
            this.name = name;
            this.visible = visible;
        }
    }

    /** The layer's feature types as the pane lists them: every type ever seen, shown or not. */
    public List<SetInfo> types() {
        final List<SetInfo> out = new ArrayList<>();
        // The source's own list first, in its order; then anything else the data brought.
        final List<String> names = new ArrayList<>(spec.setNotes.keySet());
        final List<String> rest = new ArrayList<>();
        for (String n : spec.setKind.keySet())
            if (!spec.setNotes.containsKey(n))
                rest.add(n);
        java.util.Collections.sort(rest);
        names.addAll(rest);
        for (String n : names)
            out.add(new SetInfo(-1, n, spec.isOn(n)));
        return out;
    }

    /** The sets actually in the store right now. */
    public List<SetInfo> sets() {
        synchronized (lock) {
            return setsLocked();
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
                    out.add(new SetInfo(c.getId(), c.getName(), spec.isOn(c.getName())));
            } finally {
                c.close();
            }
        } catch (Exception e) {
            Log.w(TAG, "set listing failed", e);
        }
        return out;
    }

    /** Visibility of one type: remembered in the spec by name, pushed to the store now. */
    public void setSetVisible(SetInfo si, boolean v) {
        spec.setOn.put(si.name, v);
        synchronized (lock) {
            try {
                if (store != null)
                    store.setFeatureSetVisible(si.id, layerOn && v);
            } catch (Exception e) {
                Log.w(TAG, "set visibility failed", e);
            }
        }
    }

    /**
     * Redraws one area type at a new fill opacity from the fill color kept in each feature,
     * without touching the network. Worker thread.
     */
    public void restyleFills(String setName, int alpha) {
        synchronized (lock) {
            if (store == null)
                return;
            final List<long[]> ids = new ArrayList<>();
            final List<Style> styles = new ArrayList<>();
            final Set<Long> wanted = new HashSet<>();
            for (SetInfo si : setsLocked())
                if (si.name.equals(setName))
                    wanted.add(si.id);
            if (wanted.isEmpty())
                return;
            try {
                // The store's set-name filter was not honored on the phone (every polygon
                // changed), so the set id is checked here, feature by feature.
                final com.atakmap.map.layer.feature.FeatureCursor c = store.queryFeatures(new FeatureDataStore2.FeatureQueryParameters());
                try {
                    while (c.moveToNext()) {
                        if (!wanted.contains(c.getFsid()))
                            continue;
                        final Feature f = c.get();
                        final AttributeSet a = f.getAttributes();
                        String raw = null;
                        try {
                            raw = a == null ? null : a.getStringAttribute("_fill");
                        } catch (Exception ignored) {
                        }
                        if (raw == null)
                            continue;
                        final int rgb = (int) Long.parseLong(raw) & 0x00FFFFFF;
                        Style stroke = strokeOf(f.getStyle());
                        if (stroke == null)
                            stroke = NwcgStyles.solid(0xFF000000 | rgb, 2f);
                        final Style s = alpha <= 0 ? stroke : new com.atakmap.map.layer.feature.style.CompositeStyle(
                                new Style[] { new com.atakmap.map.layer.feature.style.BasicFillStyle((Math.min(255, alpha) << 24) | rgb), stroke });
                        ids.add(new long[] { f.getId() });
                        styles.add(s);
                    }
                } finally {
                    c.close();
                }
                store.acquireModifyLock(true);
                try {
                    for (int i = 0; i < ids.size(); i++)
                        store.updateFeature(ids.get(i)[0], FeatureDataStore2.PROPERTY_FEATURE_STYLE, null, null,
                                styles.get(i), null, FeatureDataStore2.FEATURE_VERSION_NONE);
                } finally {
                    store.releaseModifyLock();
                }
                // The memory copy too, or the next ON would bring the old fill back.
                final List<Pending> updated = new ArrayList<>();
                for (Pending pf : cache) {
                    if (!pf.setName.equals(setName)) {
                        updated.add(pf);
                        continue;
                    }
                    String raw = null;
                    try {
                        raw = pf.attrs == null ? null : pf.attrs.getStringAttribute("_fill");
                    } catch (Exception ignored) {
                    }
                    if (raw == null) {
                        updated.add(pf);
                        continue;
                    }
                    final int rgb = (int) Long.parseLong(raw) & 0x00FFFFFF;
                    Style stroke = strokeOf(pf.style);
                    if (stroke == null)
                        stroke = NwcgStyles.solid(0xFF000000 | rgb, 2f);
                    final Style s = alpha <= 0 ? stroke : new com.atakmap.map.layer.feature.style.CompositeStyle(
                            new Style[] { new com.atakmap.map.layer.feature.style.BasicFillStyle((Math.min(255, alpha) << 24) | rgb), stroke });
                    updated.add(new Pending(pf.setName, pf.minGsd, pf.name, pf.geometry, s, pf.attrs));
                }
                cache = updated;
                Log.d(TAG, spec.id + ": restyled " + ids.size() + " " + setName + " at alpha " + alpha);
            } catch (Exception e) {
                Log.w(TAG, "restyle failed", e);
            }
        }
    }

    private static Style strokeOf(Style s) {
        if (s instanceof com.atakmap.map.layer.feature.style.BasicStrokeStyle
                || s instanceof com.atakmap.map.layer.feature.style.PatternStrokeStyle)
            return s;
        if (s instanceof com.atakmap.map.layer.feature.style.CompositeStyle) {
            final com.atakmap.map.layer.feature.style.CompositeStyle cs = (com.atakmap.map.layer.feature.style.CompositeStyle) s;
            for (int i = 0; i < cs.getNumStyles(); i++) {
                final Style child = strokeOf(cs.getStyle(i));
                if (child != null)
                    return child;
            }
        }
        return null;
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
