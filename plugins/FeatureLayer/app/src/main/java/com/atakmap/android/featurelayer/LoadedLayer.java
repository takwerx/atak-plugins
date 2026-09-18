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
    /**
     * Bumped whenever this plugin changes how it draws anything. A layer whose store was
     * written under an older number is fully rewritten on its next refresh, because the
     * style travels with the feature into the store.
     */
    private static final int STYLE_VERSION = 51;

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
    /**
     * DART callsigns, drawn as ATAK marker labels. A feature label has no priority to set
     * and gets shortened from the front when labels crowd, which ate the state and the
     * unit off every crowded callsign; see {@link DartMarkers}.
     */
    private DartMarkers dartLabels;

    public volatile String status = "";
    public volatile long lastRefresh;
    public volatile int count;
    public volatile boolean refreshing;
    /** A store rewrite (ON/OFF from memory) is in progress. */
    public volatile boolean busy;
    /** Features fetched so far during a refresh, for the pane's loading line. */
    public volatile int progress;
    public volatile boolean stale;
    /** The last fetch returned the layer's cap, so there is more than is drawn. */
    public volatile boolean capped;
    /**
     * A word about how the scope was resolved this time, for the pane: "no GPS fix --
     * measured from Map Center", or null when there is nothing to say.
     */
    public volatile String scopeNote;
    /** Where the last fetch looked, so a move can be judged against it. */
    private volatile double fetchedLat = Double.NaN, fetchedLon = Double.NaN, fetchedRadiusM;
    private volatile double[] fetchedBox; // south, west, north, east, with the margin
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
        if (DartStyles.handles(spec))
            dartLabels = new DartMarkers(mapView, pluginContext, spec.id,
                    DartStyles.genericMarkerUri(iconDir), iconDir);
            dartLabels.setLabelGsd(spec.labelGsd);
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
                // A point whose icon is a labelled composite shows the chooser the bare
                // symbol, not the pill: the chooser draws into a small box and the whole
                // composite there made every symbol a speck (2026-09-18).
                if (EsriRenderer.isPoint(feature.getGeometry())) {
                    final String sym = LabelledIcons.symbolUriOf(iconUriOf(feature.getStyle()), iconDir);
                    if (sym != null) {
                        item.setMetaString("iconUri", sym);
                        item.setMetaInteger("iconColor", 0xFFFFFFFF);
                    }
                }
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
        // A DART layer is drawn by its markers (see DartMarkers): neither its feature layer
        // nor its Overlay Manager entry is registered, because either one renders the
        // store's own discs under the markers. Gating the sets to 0 was supposed to do
        // this and did not: the features still drew, disc and trimmed name label, on top
        // of the markers -- with the marker icon hidden, the feature's disc still bit a
        // circle out of the callsign (2026-09-17). The store stays for details, search
        // and counts.
        if (dartLabels == null) {
            mapView.getMapOverlayManager().addFilesOverlay(overlay);
            mapView.addLayer(MapView.RenderStack.VECTOR_OVERLAYS, layer);
        }
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
        final List<SetInfo> all = rawSetsLocked();
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
            final boolean drawnByMarkers = dartLabels != null;
            if (dartLabels != null)
                dartLabels.dispose();
            if (layer != null && !drawnByMarkers)
                mapView.removeLayer(MapView.RenderStack.VECTOR_OVERLAYS, layer);
            if (overlay != null && !drawnByMarkers)
                mapView.getMapOverlayManager().removeOverlay(overlay);
            if (store != null)
                store.dispose();
        } catch (Exception e) {
            Log.w(TAG, "detach " + spec.id, e);
        }
        layer = null;
        overlay = null;
        store = null;
        dartLabels = null;
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
                    if (setName == null || isTwin(setName))
                        continue; // the named twin is the same feature again
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

    /** Labels from this resolution and closer. DART markers swap themselves; the store is rewritten for the rest. */
    public void setLabelLevel(double metersPerPixel) {
        spec.labelGsd = metersPerPixel;
        if (dartLabels != null) {
            dartLabels.setLabelGsd(metersPerPixel);
            return;
        }
        synchronized (lock) {
            if (store == null || closed)
                return;
            loadCacheLocked();
            if (!cache.isEmpty())
                rewriteStore();
        }
    }

    /** The map's resolution after a move settled; DART markers show or hide their callsigns by it. Main thread. */
    public void onMapResolution(double metersPerPixel) {
        if (dartLabels != null)
            dartLabels.onMapResolution(metersPerPixel);
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
            for (SetInfo si : rawSetsLocked()) {
                if (layerOn && spec.isOn(twinBase(si.name)))
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
        rewriteStore(false);
    }

    /**
     * @param allowEmpty true only when a fetch has just succeeded and genuinely returned
     *        nothing. Refusing an empty write protects the map from a failed fetch, but on
     *        a scoped layer it also kept the last place's features drawn forever: DART
     *        showed 27 vehicles from a previous scope, with the styles of an older build,
     *        while every fetch came back with nothing in range (2026-09-17). An empty
     *        answer from a server that answered is an answer.
     */
    private void rewriteStore(boolean allowEmpty) {
        if (cache.isEmpty() && !allowEmpty) {
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
            // Collected while writing so the labels and the discs can never disagree;
            // null for every layer that is not DART.
            final List<DartMarkers.Row> labels = dartLabels == null ? null
                    : new ArrayList<DartMarkers.Row>();
            final Map<String, Long> twins = new HashMap<>();
            int written = 0;
            for (Pending pf : cache) {
                if (!layerOn || !spec.isOn(pf.setName))
                    continue;
                // A named point with a label level is written twice: the bare symbol in the
                // type's own set, which stops drawing at that level, and the named icon in a
                // twin set that starts there. ATAK switches between them by resolution, so
                // a zoom costs nothing, and the pane never lists the twin.
                final boolean named = dartLabels == null && pf.name != null && !pf.name.isEmpty()
                        && pf.geometry instanceof com.atakmap.map.layer.feature.geometry.Point;
                final boolean split = named && spec.labels && spec.labelGsd != Double.MAX_VALUE;
                // The kind's own gate (points 120 m/px, lines 400) capped by the layer's.
                final double gate = Math.min(pf.minGsd, spec.gateGsd);
                Long fsid = sets.get(pf.setName);
                if (fsid == null) {
                    fsid = newSet(store, pf.setName, gate, split ? Math.min(spec.labelGsd, gate) : 0d);
                    sets.put(pf.setName, fsid);
                }
                final long fid = store.insertFeature(new Feature(fsid, pf.name, pf.geometry,
                        drawnForm(pf, named, spec.labels && !split), pf.attrs, Feature.AltitudeMode.ClampToGround, 0d));
                written++;
                if (split) {
                    Long tid = twins.get(pf.setName);
                    if (tid == null) {
                        tid = newSet(store, pf.setName + LABEL_TWIN, Math.min(spec.labelGsd, gate), 0d);
                        twins.put(pf.setName, tid);
                    }
                    store.insertFeature(new Feature(tid, pf.name, pf.geometry, drawnForm(pf, named, true),
                            pf.attrs, Feature.AltitudeMode.ClampToGround, 0d));
                }
                if (labels != null && pf.name != null && !pf.name.isEmpty()
                        && pf.geometry instanceof com.atakmap.map.layer.feature.geometry.Point) {
                    final com.atakmap.map.layer.feature.geometry.Point pt =
                            (com.atakmap.map.layer.feature.geometry.Point) pf.geometry;
                    labels.add(new DartMarkers.Row(spec.id + "." + pf.setName + "." + pf.name,
                            spec.labels ? pf.name : "", iconUriOf(pf.style), fid,
                            pt.getY(), pt.getX()));
                }
            }
            int dropped = 0;
            count = written;
            for (Long id : old) {
                try {
                    store.deleteFeatureSet(id);
                    dropped++;
                } catch (Exception e) {
                    Log.w(TAG, "old set " + id, e);
                }
            }
            count = countFeatures();
            if (dartLabels != null && labels != null)
                dartLabels.update(labels);
            Log.d(TAG, spec.id + ": store rewritten, " + count + " shown of " + cache.size()
                    + " (" + dropped + " old sets dropped)"
                    + (labels == null ? "" : ", " + labels.size() + " callsigns"));
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
    /**
     * The spec's scope as a query filter, resolved now. "me" reads the self marker every
     * time, so a layer left on while the operator drives keeps showing what is around
     * them; a spec with no scope returns null and the query is unfiltered, as every
     * source before DART.
     *
     * <p>Throws with words the operator can act on when the scope cannot be resolved:
     * there is no own position yet, or the drawn shape the layer was scoped to is gone.
     */
    /** Where this layer is looking, in the operator's words, for the pane and the log. */
    public String scopeLabel() {
        if (spec.scopeKind == null)
            return "Everything";
        if ("view".equals(spec.scopeKind))
            return "What is in view";
        if ("box".equals(spec.scopeKind))
            return "An area";
        if ("shape".equals(spec.scopeKind))
            return "A drawn shape";
        final String from = "center".equals(spec.scopeKind) ? "Map Center" : "My Location";
        final String note = scopeNote;
        return "Within " + Units.formatBig(spec.scopeRadiusM) + " of " + from + (note == null ? "" : " (" + note + ")");
    }

    /** Whether this layer's scope is one the pane offers a control for. */
    public boolean hasScopeControl() {
        return "me".equals(spec.scopeKind) || "center".equals(spec.scopeKind) || "view".equals(spec.scopeKind);
    }

    /** A usable own position, or null: the self marker before a fix reads 0,0 and calls itself valid. */
    private com.atakmap.coremap.maps.coords.GeoPoint ownPosition() {
        final com.atakmap.android.maps.Marker self = mapView.getSelfMarker();
        final com.atakmap.coremap.maps.coords.GeoPoint p = self == null ? null : self.getPoint();
        if (p == null || !p.isValid() || (Math.abs(p.getLatitude()) < 0.01 && Math.abs(p.getLongitude()) < 0.01))
            return null;
        return p;
    }

    /**
     * Whether the map or the operator has moved far enough since the last fetch that
     * what is drawn no longer answers the scope. Judged on the main thread by
     * {@link LayerManager} after a debounced map move.
     */
    boolean movedOutOfScope() {
        if (!hasScopeControl() || refreshing || busy)
            return false;
        try {
            if ("view".equals(spec.scopeKind)) {
                final double[] fb = fetchedBox;
                final com.atakmap.coremap.maps.coords.GeoBounds b = mapView.getBounds();
                if (fb == null || b == null)
                    return false;
                // Still inside the margin the last fetch added: nothing new to ask for.
                return b.getSouth() < fb[0] || b.getWest() < fb[1] || b.getNorth() > fb[2] || b.getEast() > fb[3];
            }
            if (Double.isNaN(fetchedLat))
                return false;
            final com.atakmap.coremap.maps.coords.GeoPoint now = "center".equals(spec.scopeKind)
                    ? mapView.getPoint().get() : ownPosition();
            if (now == null)
                return false;
            // A fifth of the radius, and never less than 250 m: "Map Center" is where the
            // map is now, and the operator panning half a screen expects the circle to
            // have come along. Half the radius was 800 m on a 1 mi radius -- most of the
            // screen at that zoom -- and read as "panning does nothing" (2026-09-18). The
            // 20 s minimum gap in LayerManager is what keeps a slow drive from fetching
            // every second.
            return distanceM(now.getLatitude(), now.getLongitude(), fetchedLat, fetchedLon)
                    > Math.max(250d, fetchedRadiusM * 0.2);
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static double distanceM(double lat1, double lon1, double lat2, double lon2) {
        final double r = 6371000d, dLat = Math.toRadians(lat2 - lat1), dLon = Math.toRadians(lon2 - lon1);
        final double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return 2 * r * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }

    private Esri.Scope scope() {
        if (spec.scopeKind == null)
            return null;
        if ("view".equals(spec.scopeKind)) {
            // What the operator is looking at, which is what they are asking about. The map
            // always has an extent; the self marker does not, and a phone with no fix
            // refused to fetch anything at all while the map sat over a fire.
            final com.atakmap.coremap.maps.coords.GeoBounds b = mapView.getBounds();
            if (b == null)
                throw new IllegalStateException("the map has no extent yet");
            // A margin, so a small pan still has features under it before the next fetch.
            final double padLat = Math.max(0.01, (b.getNorth() - b.getSouth()) * 0.2);
            final double padLon = Math.max(0.01, (b.getEast() - b.getWest()) * 0.2);
            fetchedBox = new double[] { b.getSouth() - padLat, b.getWest() - padLon,
                    b.getNorth() + padLat, b.getEast() + padLon };
            scopeNote = null;
            return Esri.Scope.box(fetchedBox[0], fetchedBox[1], fetchedBox[2], fetchedBox[3]);
        }
        if ("center".equals(spec.scopeKind)) {
            // Where the map is NOW, not where it was when the control was set: a control
            // named for the map center that ignored the map moving is Cam Depot's old bug.
            final com.atakmap.coremap.maps.coords.GeoPoint c = mapView.getPoint().get();
            if (c == null)
                throw new IllegalStateException("the map has no center yet");
            fetchedLat = c.getLatitude();
            fetchedLon = c.getLongitude();
            fetchedRadiusM = spec.scopeRadiusM;
            scopeNote = null;
            return Esri.Scope.circle(c.getLatitude(), c.getLongitude(), spec.scopeRadiusM);
        }
        if ("box".equals(spec.scopeKind)) {
            if (spec.scopeBox == null)
                throw new IllegalStateException("no area set for " + spec.title);
            return Esri.Scope.box(spec.scopeBox[0], spec.scopeBox[1], spec.scopeBox[2], spec.scopeBox[3]);
        }
        if ("shape".equals(spec.scopeKind)) {
            if (spec.scopeRings == null || spec.scopeRings.isEmpty())
                throw new IllegalStateException("the shape " + spec.title + " was scoped to is gone; pick another");
            return Esri.Scope.polygon(spec.scopeRings);
        }
        // "me". GeoPoint.isValid() is true at 0,0, which is not a position -- it is what
        // the self marker reads before a fix. Scoping to it put a 25 mile circle in the
        // Gulf of Guinea, so every fetch came back empty while the map still showed the
        // last place's features (2026-09-17). With no fix the map center stands in, and
        // the pane says so, rather than refusing to fetch anything.
        com.atakmap.coremap.maps.coords.GeoPoint p = ownPosition();
        if (p == null) {
            p = mapView.getPoint().get();
            if (p == null)
                throw new IllegalStateException("no own position yet; wait for GPS or set your location in ATAK");
            scopeNote = "no GPS fix, measured from Map Center";
        } else {
            scopeNote = null;
        }
        fetchedLat = p.getLatitude();
        fetchedLon = p.getLongitude();
        fetchedRadiusM = spec.scopeRadiusM;
        return Esri.Scope.circle(p.getLatitude(), p.getLongitude(), spec.scopeRadiusM);
    }

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
            // Styles are written into the store with the features, so a build that changes
            // symbology never reaches rows already cached: DART's new EGP glyphs did not
            // show up because the stamp said "no change" and the old tiny dots stayed
            // (2026-09-17). A version bump forces one full rewrite.
            final boolean restyle = spec.styleVersion != STYLE_VERSION;
            if (restyle)
                Log.d(TAG, spec.id + ": symbology changed since this store was written, rewriting");
            if (!restyle && spec.timeField != null && spec.live && !cache.isEmpty()) {
                final StringBuilder now = new StringBuilder();
                for (int layerId : spec.layerIds)
                    now.append(Esri.stamp(spec.base, layerId, spec.whereNow(), scope(), token, spec.timeField))
                            .append(';');
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
            if (spec.scopeKind != null)
                Log.d(TAG, spec.id + ": fetching " + scopeLabel());
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
                rewriteStore(true);
                spec.styleVersion = STYLE_VERSION;
                Log.d(TAG, spec.id + ": refresh done, " + pending.size() + " fetched, store holds " + count);
            }
            lastRefresh = System.currentTimeMillis();
            stale = false;
            status = problems.isEmpty() ? "ok" : "partial: " + problems.get(0);
            capped = spec.maxFeatures > 0 && pending.size() >= spec.maxFeatures * spec.layerIds.length;
            if (capped)
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
        // Only when the source layer is itself the type. A layer that splits by a field
        // (CA Air Intel by source) names its types from the data, and the layer's own name
        // listed a seventh type nothing was ever in.
        if (spec.setField == null)
            spec.setKind.put(info.name, isPointLayer ? "point" : isLineLayer ? "line" : "polygon");
        else
            spec.setKind.remove(info.name);
        final int fill = spec.fillFor(info.name);
        final EsriRenderer generic = nwcg ? null : new EsriRenderer(info.drawingInfo, info.geometryType, iconDir, fill);
        final double gsd = isPointLayer ? GSD_POINTS : isLineLayer ? GSD_LINES : GSD_ALWAYS;
        final String setName = info.name;
        final String repairName = (nwcg && isPointLayer) ? info.name + " (repair)" : null;
        final Set<String> dates = info.dateFields;
        final String displayField = info.displayField != null && info.fields.contains(info.displayField)
                ? info.displayField : null;
        // The service's own layer name is a table name to the operator ("DART_AVLs"), so a
        // source that knows better says so. One layer per service, so one title is enough.
        final String layerName = spec.layerTitle != null && !spec.layerTitle.isEmpty()
                ? spec.layerTitle : info.name;

        Log.d(TAG, spec.id + ": layer " + layerId + " iconSet=" + spec.iconSet + " dart=" + DartStyles.handles(spec)
                + " point=" + isPointLayer + " profile=" + spec.profile + " nwcg=" + nwcg);
        final int firstOfLayer = out.size();
        Esri.query(spec.base, layerId, spec.whereNow(), scope(), token, spec.geojson,
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
                            String disp = labelKey == null || props.isNull(labelKey)
                                    ? null : Esri.firstNonEmpty(props.optString(labelKey, null));
                            // A chosen label field is empty on some rows - a FIRIS flight carries a
                            // mission and no incident_name - and the fallback below is the renderer's
                            // class, which on CA Air Intel is the displayStatus: every perimeter read
                            // "Active". The service's own display field is the name to fall back to.
                            if (disp == null && displayField != null && !displayField.equals(labelKey)
                                    && !props.isNull(displayField))
                                disp = Esri.firstNonEmpty(props.optString(displayField, null));
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
                            if (DartStyles.handles(spec) && isPointLayer) {
                                // EGP's symbology, not the services' own: personnel declare a
                                // 22.5 pt marker and vehicles an esriSMS dot of size 4, which
                                // is a speck nobody can see over imagery.
                                final boolean person = spec.id.contains("personnel");
                                final long reported = spec.timeField != null && props.opt(spec.timeField) instanceof Number
                                        ? ((Number) props.opt(spec.timeField)).longValue() : 0L;
                                final int age = DartStyles.ageBucket(reported, System.currentTimeMillis(),
                                        DartStyles.ageCutoffs(props, person));
                                final Style d = DartStyles.style(props, person, iconDir, age);
                                if (d != null)
                                    style = d;
                            }
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
                        String bare = null, bareAlt = null;
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
                        } else if (isPointLayer && DartStyles.handles(spec)) {
                            // The callsign is a marker label now; a feature label here would
                            // be a second, trimmed copy of it under the disc.
                            style = NwcgStyles.withoutLabel(style);
                            if (alt != null)
                                alt = NwcgStyles.withoutLabel(alt);
                        } else if (isPointLayer && name != null && !name.isEmpty()) {
                            // A point's name is pixels in its icon (LabelledIcons), not a label.
                            // ATAK's label engine drew NWCG point names in white with no backing
                            // and trimmed them -- "Value at Risk" as "Va", "Hazard" as "ard" over
                            // red terrain on the Timber fire (2026-09-17) -- and trimmed the
                            // pill on every other layer's points the same way. A transparent
                            // empty label then keeps the engine from drawing the name itself.
                            // A Label Point is text only: its pill is composed with no symbol.
                            // The bare style rides in the feature's attributes, because the
                            // labels toggle has to rebuild either form later: after a restart
                            // the cache is reloaded from the store, where the icon already
                            // carries its name, and turning labels off changed nothing (Timber,
                            // 2026-09-18).
                            bare = packStyle(style);
                            bareAlt = alt == null ? null : packStyle(alt);
                            if (spec.labels) {
                                final Style ls = labelledPoint(style, name);
                                if (ls != null) {
                                    style = ls;
                                    final Style la = alt == null ? null : labelledPoint(alt, name);
                                    alt = la != null ? la : alt;
                                } else {
                                    style = NwcgStyles.withNameLabel(style, true, name);
                                    if (alt != null)
                                        alt = NwcgStyles.withNameLabel(alt, true, name);
                                }
                            }
                        }
                        final AttributeSet attrs = Esri.toAttributes(props, dates);
                        if (bare != null)
                            attrs.setAttribute(ATTR_BARE, bare);
                        if (bareAlt != null)
                            attrs.setAttribute(ATTR_BARE_ALT, bareAlt);
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
                            // The renderer bakes in the fill it was built with, which is the
                            // source layer's. A layer that splits its types by a field has a
                            // fill per type, and without this it came back at the layer's
                            // default on every refresh.
                            final int want = spec.fillFor(target);
                            if (want != fill && hue != 0) {
                                style = refilled(style, hue & 0x00FFFFFF, want);
                                if (alt != null)
                                    alt = refilled(alt, hue & 0x00FFFFFF, want);
                            }
                        }
                        // Only the fire perimeter says what is burned; an IR flight area or cloud
                        // cover polygon covers everything and put every tick on the default side.
                        if (nwcg && !isPointLayer && !isLineLayer && NwcgStyles.area(cat) == NwcgStyles.Area.WILDFIRE)
                            collectRings(g);
                        Geometry shown = g;
                        if (at != null) {
                            // Flat, not nested: an Esri multipolygon is already a collection.
                            final GeometryCollection withCenter = new GeometryCollection(2);
                            if (g instanceof GeometryCollection)
                                for (Geometry c : ((GeometryCollection) g).getGeometries())
                                    withCenter.addGeometry(c);
                            else
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

    /**
     * A point style with its name composed into the icon and its label made transparent,
     * or null when the style has no file-backed icon to build from. The symbol keeps the
     * size it has on screen today: a scale-form icon draws at its PNG's own pixels times
     * the scale, a size-form one at its dp times ATAK's display scaling.
     */
    /** A point's style before its name was drawn into the icon, packed as ATAK's OGR style text. */
    static final String ATTR_BARE = "_bare", ATTR_BARE_ALT = "_bare_alt";

    private static String packStyle(Style s) {
        try {
            return s == null ? null : com.atakmap.map.layer.feature.ogr.style.FeatureStyleParser.pack(s);
        } catch (Throwable t) {
            return null;
        }
    }

    /** The bare style a point was fetched with, or null when the feature has none recorded. */
    private Style bareStyle(Pending pf, boolean repair) {
        if (pf.attrs == null)
            return null;
        try {
            final String key = repair && pf.attrs.containsAttribute(ATTR_BARE_ALT) ? ATTR_BARE_ALT : ATTR_BARE;
            if (!pf.attrs.containsAttribute(key))
                return null;
            return com.atakmap.map.layer.feature.ogr.style.FeatureStyleParser.parse2(pf.attrs.getStringAttribute(key));
        } catch (Throwable t) {
            return null;
        }
    }

    /** Whether the style's icon is a LabelledIcons composite, the name already pixels in it. */
    private boolean isComposed(Style s) {
        final String uri = iconUriOf(s);
        return uri != null && uri.substring(uri.lastIndexOf('/') + 1).startsWith("lbl_");
    }

    /**
     * The style a cached feature is written with: its name drawn into the icon or not.
     * When the icon disagrees with what is wanted, it is rebuilt from the bare style the
     * fetch recorded; a feature that is not a named point keeps its style, minus the
     * engine's label when labels are off.
     */
    private Style drawnForm(Pending pf, boolean named, boolean labelled) {
        Style drawn = spec.repairStatus && pf.alt != null ? pf.alt : pf.style;
        if (named && labelled != isComposed(drawn)) {
            final Style base = bareStyle(pf, spec.repairStatus);
            if (base != null) {
                if (labelled) {
                    final Style ls = labelledPoint(base, pf.name);
                    drawn = ls != null ? ls : NwcgStyles.withNameLabel(base, true, pf.name);
                } else {
                    drawn = base;
                }
            }
        }
        if (!labelled && !(pf.geometry instanceof LineString))
            drawn = NwcgStyles.withoutLabel(drawn); // a transparent label beats the name
        return drawn;
    }

    private Style labelledPoint(Style s, String text) {
        final com.atakmap.map.layer.feature.style.IconPointStyle ip;
        if (s instanceof com.atakmap.map.layer.feature.style.LabelPointStyle) {
            // A Label Point: text and nothing else. "Boy Scout Camp" drew as "ut Camp"
            // through the engine (2026-09-17); as pixels it is whole.
            try {
                final float scale = gov.tak.api.commons.graphics.DisplaySettings.getRelativeScaling();
                final com.atakmap.android.maps.MapTextFormat tf = MapView.getDefaultTextFormat();
                final android.graphics.Typeface face = tf == null || tf.getTypeface() == null
                        ? android.graphics.Typeface.DEFAULT : tf.getTypeface();
                float textPx = tf == null ? 0f : tf.getDensityAdjustedFontSize();
                if (textPx <= 0f)
                    textPx = (tf == null ? 14f : tf.getFontSize()) * scale;
                final int[] dim = new int[2];
                final File f = LabelledIcons.compose(null, 0, 0, text, face, textPx, iconDir, dim);
                if (f == null)
                    return null;
                final Style icon = new com.atakmap.map.layer.feature.style.IconPointStyle(0xFFFFFFFF,
                        "file://" + f.getAbsolutePath(), dim[0] / scale, dim[1] / scale, 0, 0, 0f, true);
                return NwcgStyles.withoutLabel(icon);
            } catch (Exception e) {
                Log.w(TAG, "labelled text \"" + text + "\"", e);
                return null;
            }
        }
        if (s instanceof com.atakmap.map.layer.feature.style.IconPointStyle)
            ip = (com.atakmap.map.layer.feature.style.IconPointStyle) s;
        else if (s instanceof com.atakmap.map.layer.feature.style.CompositeStyle)
            ip = (com.atakmap.map.layer.feature.style.IconPointStyle) com.atakmap.map.layer.feature.style.CompositeStyle
                    .find((com.atakmap.map.layer.feature.style.CompositeStyle) s,
                            com.atakmap.map.layer.feature.style.IconPointStyle.class);
        else
            ip = null;
        if (ip == null || ip.getIconUri() == null || !ip.getIconUri().startsWith("file://"))
            return null;
        try {
            final File symbol = new File(ip.getIconUri().substring("file://".length()));
            final float scale = gov.tak.api.commons.graphics.DisplaySettings.getRelativeScaling();
            int symW, symH;
            final float sc = ip.getIconScaling();
            if (sc != 0f) {
                final android.graphics.BitmapFactory.Options o = new android.graphics.BitmapFactory.Options();
                o.inJustDecodeBounds = true;
                android.graphics.BitmapFactory.decodeFile(symbol.getAbsolutePath(), o);
                if (o.outWidth <= 0)
                    return null;
                symW = Math.round(o.outWidth * sc);
                symH = Math.round(o.outHeight * sc);
            } else {
                symW = Math.round(ip.getIconWidth() * scale);
                symH = Math.round(ip.getIconHeight() * scale);
            }
            final com.atakmap.android.maps.MapTextFormat tf = MapView.getDefaultTextFormat();
            final android.graphics.Typeface face = tf == null || tf.getTypeface() == null
                    ? android.graphics.Typeface.DEFAULT : tf.getTypeface();
            float textPx = tf == null ? 0f : tf.getDensityAdjustedFontSize();
            if (textPx <= 0f)
                textPx = (tf == null ? 14f : tf.getFontSize()) * scale;
            final int[] dim = new int[2];
            final File f = LabelledIcons.compose(symbol, symW, symH, text, face, textPx, iconDir, dim);
            if (f == null)
                return null;
            final Style icon = new com.atakmap.map.layer.feature.style.IconPointStyle(0xFFFFFFFF,
                    "file://" + f.getAbsolutePath(), dim[0] / scale, dim[1] / scale, 0, 0, 0f, true);
            return NwcgStyles.withoutLabel(icon);
        } catch (Exception e) {
            Log.w(TAG, "labelled point \"" + text + "\"", e);
            return null;
        }
    }

    /** The icon a point style draws, so a marker can draw the same one. */
    private static String iconUriOf(Style s) {
        if (s instanceof com.atakmap.map.layer.feature.style.IconPointStyle)
            return ((com.atakmap.map.layer.feature.style.IconPointStyle) s).getIconUri();
        if (s instanceof com.atakmap.map.layer.feature.style.CompositeStyle) {
            final com.atakmap.map.layer.feature.style.IconPointStyle i =
                    (com.atakmap.map.layer.feature.style.IconPointStyle) com.atakmap.map.layer.feature.style.CompositeStyle
                            .find((com.atakmap.map.layer.feature.style.CompositeStyle) s,
                                    com.atakmap.map.layer.feature.style.IconPointStyle.class);
            if (i != null)
                return i.getIconUri();
        }
        return null;
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

    /** Suffix of the set that holds the named form of a type's points when a label level is set. */
    static final String LABEL_TWIN = " (labels)";

    static boolean isTwin(String setName) {
        return setName != null && setName.endsWith(LABEL_TWIN);
    }

    static String twinBase(String setName) {
        return isTwin(setName) ? setName.substring(0, setName.length() - LABEL_TWIN.length()) : setName;
    }

    /** The sets the pane and the counts see: the twins left out. */
    private List<SetInfo> setsLocked() {
        final List<SetInfo> out = new ArrayList<>();
        for (SetInfo si : rawSetsLocked())
            if (!isTwin(si.name))
                out.add(si);
        return out;
    }

    /** Every set in the store, twins included, visibility judged by the base name. */
    private List<SetInfo> rawSetsLocked() {
        final List<SetInfo> out = new ArrayList<>();
        if (store == null)
            return out;
        try {
            final FeatureSetCursor c = store.queryFeatureSets(new FeatureDataStore2.FeatureSetQueryParameters());
            try {
                while (c.moveToNext())
                    out.add(new SetInfo(c.getId(), c.getName(), spec.isOn(twinBase(c.getName()))));
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
                if (store != null) {
                    store.setFeatureSetVisible(si.id, layerOn && v);
                    for (SetInfo t : rawSetsLocked())
                        if (isTwin(t.name) && twinBase(t.name).equals(si.name))
                            store.setFeatureSetVisible(t.id, layerOn && v);
                }
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
                        final Style s = refilled(f.getStyle(), rgb, alpha);
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
                    updated.add(new Pending(pf.setName, pf.minGsd, pf.name, pf.geometry,
                            refilled(pf.style, rgb, alpha), pf.attrs));
                }
                cache = updated;
                Log.d(TAG, spec.id + ": restyled " + ids.size() + " " + setName + " at alpha " + alpha);
            } catch (Exception e) {
                Log.w(TAG, "restyle failed", e);
            }
        }
    }

    /**
     * An area's style at a new fill opacity: the hue the service served, the stroke it
     * already carries, and its label kept. Rebuilt from the stroke alone, a fill change
     * quietly stripped the name pill off every area until the layer was reloaded.
     */
    private static Style refilled(Style base, int rgb, int alpha) {
        Style stroke = strokeOf(base);
        if (stroke == null)
            stroke = NwcgStyles.solid(0xFF000000 | rgb, 2f);
        final List<Style> parts = new ArrayList<>();
        if (alpha > 0)
            parts.add(new com.atakmap.map.layer.feature.style.BasicFillStyle((Math.min(255, alpha) << 24) | rgb));
        parts.add(stroke);
        final Style label = labelOf(base);
        if (label != null)
            parts.add(label);
        return parts.size() == 1 ? parts.get(0)
                : new com.atakmap.map.layer.feature.style.CompositeStyle(parts.toArray(new Style[0]));
    }

    /** The label style inside a style, or null: what a rebuild has to carry over. */
    private static Style labelOf(Style s) {
        if (s instanceof com.atakmap.map.layer.feature.style.LabelPointStyle)
            return s;
        if (s instanceof com.atakmap.map.layer.feature.style.CompositeStyle) {
            final com.atakmap.map.layer.feature.style.CompositeStyle cs = (com.atakmap.map.layer.feature.style.CompositeStyle) s;
            for (int i = 0; i < cs.getNumStyles(); i++) {
                final Style child = labelOf(cs.getStyle(i));
                if (child != null)
                    return child;
            }
        }
        return null;
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

    /** Features in the store, each counted once: the named twin of a point is not a second feature. */
    private int countFeatures() {
        try {
            int n = 0;
            for (SetInfo si : setsLocked()) {
                final FeatureDataStore2.FeatureQueryParameters p = new FeatureDataStore2.FeatureQueryParameters();
                p.featureSetFilter = new FeatureDataStore2.FeatureSetQueryParameters();
                p.featureSetFilter.ids = java.util.Collections.singleton(si.id);
                n += store.queryFeaturesCount(p);
            }
            return n;
        } catch (Exception e) {
            return 0;
        }
    }
}
