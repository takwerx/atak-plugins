
package com.atakmap.android.atmosphere.overlay;

import android.content.Context;

import com.atakmap.android.features.FeatureDataStoreMapOverlay;
import com.atakmap.android.menu.PluginMenuParser;
import com.atakmap.android.maps.MapItem;
import com.atakmap.android.maps.MapView;
import com.atakmap.coremap.filesystem.FileSystemUtils;
import com.atakmap.coremap.log.Log;
import com.atakmap.coremap.maps.coords.GeoPoint;
import com.atakmap.map.layer.feature.AttributeSet;
import com.atakmap.map.layer.feature.Feature;
import com.atakmap.map.layer.feature.FeatureDataStore2;
import com.atakmap.map.layer.feature.FeatureLayer3;
import com.atakmap.map.layer.feature.FeatureSet;
import com.atakmap.map.layer.feature.datastore.FeatureSetDatabase2;
import com.atakmap.map.layer.feature.geometry.LineString;
import com.atakmap.map.layer.feature.geometry.Point;
import com.atakmap.map.layer.feature.geometry.Polygon;
import com.atakmap.map.layer.feature.style.BasicFillStyle;
import com.atakmap.map.layer.feature.style.BasicStrokeStyle;
import com.atakmap.map.layer.feature.style.CompositeStyle;
import com.atakmap.map.layer.feature.style.IconPointStyle;
import com.atakmap.map.layer.feature.style.LabelPointStyle;
import com.atakmap.map.layer.feature.style.Style;
import com.atakmap.android.features.FeatureDataStoreDeepMapItemQuery;

import java.io.File;
import java.util.HashMap;
import java.util.Map;

/**
 * Somebody else's GIS data as a read-only map layer, not as drawings: the storms,
 * the air quality contours, and whatever joins them. One instance per layer, each
 * with its own store and its own entry in Overlay Manager.
 *
 * <p>Written for the storms and parameterized when air quality became the second
 * layer to need it, rather than copied: the ordering rules below cost a day to find
 * and belong in one place.
 *
 * <p>They were {@code DrawingShape}s and {@code Marker}s, which is how a plugin makes
 * things the operator <em>owns</em>: a radial menu offering color, rename, delete and
 * send-to, and a row in the object list. That is wrong for somebody else's advisory
 * (operator, 2026-09-23: "i cant change shape color etc, it just gives me details ...
 * like the feature layer map thing"). An NHC cone is reference data drawn on your map,
 * not a shape you drew, and the difference is not cosmetic: it decides whether you can
 * accidentally recolor the forecast, or send "Polo's cone" to a crew as a drawing.
 *
 * <p>So this is the Feature Layer shape, carried forward: a {@link FeatureSetDatabase2}
 * of styled features, a {@link FeatureLayer3} over it on the vector stack, and a
 * {@link FeatureDataStoreDeepMapItemQuery} so a tap produces a transient item carrying
 * the advisory's own attributes for the details pane. Nothing in the store is editable,
 * because nothing in it is an object.
 *
 * <p>One feature set per storm and product ("Hurricane Polo - Cone"), so Overlay
 * Manager lists them the way it lists any other GIS layer and ATAK's own visibility
 * works on each.
 */
final class AtmosphereFeatures {

    /** The provider column, and what Overlay Manager groups these under. */
    private static final String PROVIDER = "Atmosphere";

    /**
     * Drawn at every zoom. minResolution is the COARSEST meters-per-pixel a set draws
     * at, so the permissive value is the large one; 0 there means it never draws at
     * all, which is the opposite of what it reads like.
     */
    /**
     * Coarse enough for any view of a storm, but a REAL number.
     *
     * <p>It was Double.MAX_VALUE, which draws at every zoom and looks right. ATAK
     * converts a feature set's resolution into a level of detail internally, though,
     * and MAX_VALUE has no sane level of detail to become: the features drew and the
     * hit test never reached the layer at all, at any zoom, dead on a line (XCover,
     * 2026-09-23). Feature Layer gates its sets at real resolutions -- 120 m/px for
     * points, 400 for lines -- and its features are tappable. 100 km/px is coarser
     * than the whole globe on this screen, so nothing is gated out, and it is a
     * number the conversion can carry.
     */
    private static final double MIN_GSD = 100_000d;
    private static final double MAX_GSD = 0d;

    private final MapView mapView;
    private final Context pluginContext;
    private final String tag;
    /** The layer's name in Overlay Manager and on the vector stack: "Hurricanes". */
    private final String layerName;
    /** The store's file under tools/atmosphere/. */
    private final String storeName;
    /** The feature sets' type column. */
    private final String type;
    /** True for the storms only: an older build of that layer left drawings behind. */
    private final boolean sweepOldStormDrawings;

    private File storeFile;
    private FeatureSetDatabase2 store;
    private FeatureLayer3 layer;
    private FeatureDataStoreMapOverlay overlay;
    private final Map<String, Long> sets = new java.util.concurrent.ConcurrentHashMap<>();
    /** Held by a rewrite on the worker and by detach, so a store is never closed mid-write. */
    private final Object lock = new Object();

    /** One thing to draw in a {@link #rewrite}: a shape, its set, its style, its fields. */
    static final class Drawn {
        final String setName, name;
        final com.atakmap.map.layer.feature.geometry.Geometry geometry;
        final Style style;
        final AttributeSet attrs;

        Drawn(String setName, String name, com.atakmap.map.layer.feature.geometry.Geometry geometry,
                Style style, AttributeSet attrs) {
            this.setName = setName;
            this.name = name;
            this.geometry = geometry;
            this.style = style;
            this.attrs = attrs;
        }
    }

    /** An area's style: a faint fill under a full-color edge. */
    static Style area(int stroke, float weight, int fill) {
        return new CompositeStyle(new Style[] {
                new BasicFillStyle(fill), new BasicStrokeStyle(stroke, weight) });
    }

    AtmosphereFeatures(MapView mapView, Context pluginContext, String logTag, String layerName,
            String storeName, String type, boolean sweepOldStormDrawings) {
        this.mapView = mapView;
        this.pluginContext = pluginContext;
        this.tag = logTag;
        this.layerName = layerName;
        this.storeName = storeName;
        this.type = type;
        this.sweepOldStormDrawings = sweepOldStormDrawings;
    }

    /** Open the store and put the layer on the map. Safe to call twice. */
    void attach() {
        if (store != null)
            return;
        try {
            storeFile = FileSystemUtils.getItem("tools/atmosphere/" + storeName);
            final File dir = storeFile.getParentFile();
            if (dir != null && !dir.isDirectory())
                //noinspection ResultOfMethodCallIgnored
                dir.mkdirs();
            // The store is NOT emptied here. It was, on the reasoning that an advisory
            // is replaced whole every six hours so yesterday's storms are only a
            // chance to draw one that has dissipated -- and the refresh clears it
            // anyway, moments later. But that made the layer open over an EMPTY store,
            // which is the one structural difference from IPAWS and Feature Layer,
            // whose stores always carry the last session's features. Whether the
            // renderer's hit-test control survives being created over nothing is the
            // open question (XCover, 2026-09-23).
            // BEFORE the overlay is built, and that ordering is the whole point --
            // see sweepOldDrawings.
            if (sweepOldStormDrawings)
                sweepOldDrawings();

            store = new FeatureSetDatabase2(storeFile);
            final FeatureDataStore2.FeatureQueryParameters visibleOnly =
                    new FeatureDataStore2.FeatureQueryParameters();
            visibleOnly.visibleOnly = true;
            layer = new FeatureLayer3(layerName, store, visibleOnly);

            final FeatureDataStoreDeepMapItemQuery query =
                    new FeatureDataStoreDeepMapItemQuery(layer) {
                        @Override
                        public java.util.SortedSet<MapItem> deepHitTest(MapView view,
                                com.atakmap.map.hittest.HitTestQueryParameters params,
                                java.util.Map<com.atakmap.map.layer.Layer2,
                                        java.util.Collection<com.atakmap.map.hittest.HitTestControl>> controls) {
                            final java.util.SortedSet<MapItem> hits =
                                    super.deepHitTest(view, params, controls);
                            Log.d(tag, "deepHitTest: " + (controls == null ? -1 : controls.size())
                                    + " controls, " + (hits == null ? -1 : hits.size()) + " hits");
                            return hits;
                        }

                        @Override
                        public java.util.SortedSet<MapItem> deepHitTestItems(int x, int y,
                                com.atakmap.coremap.maps.coords.GeoPoint point, MapView view) {
                            final java.util.SortedSet<MapItem> hits =
                                    super.deepHitTestItems(x, y, point, view);
                            Log.d(tag, "deepHitTestItems: "
                                    + (hits == null ? -1 : hits.size()) + " hits");
                            return hits;
                        }

                        @Override
                        protected MapItem featureToMapItem(Feature feature) {
                            final MapItem item = super.featureToMapItem(feature);
                            // A READ-ONLY radial, not no radial. Blanking the menu
                            // removed the radial and with it the only route to the
                            // details pane, so a tap said nothing at all (operator,
                            // 2026-09-23: "i have no metadata about the storm at all
                            // i can read"). These are Feature Layer's own menus:
                            // details, bloodhound, pairing line, polar coords, drop a
                            // reference point. Nothing that recolors, renames, deletes
                            // or sends, which is the whole point.
                            final boolean point = feature.getGeometry()
                                    instanceof com.atakmap.map.layer.feature.geometry.Point;
                            item.setMetaString("menu", PluginMenuParser.getMenu(
                                    pluginContext,
                                    point ? "menu/feature.xml" : "menu/feature_shape.xml"));
                            item.setMetaBoolean("removable", false);
                            item.setMetaBoolean("editable", false);
                            item.setMetaBoolean("addToObjList", false);
                            item.setMetaBoolean("nevercot", true);
                            item.setMetaLong("featureid", feature.getId());
                            final AttributeSet a = attributesOf(feature.getId());
                            if (a != null)
                                item.setMetaString("remarks",
                                        com.atakmap.android.atmosphere.ui
                                                .StormDetailsReceiver.render(a));
                            // What the details pane shows as its heading and subtitle.
                            String title = feature.getName();
                            if ((title == null || title.isEmpty()) && a != null)
                                try {
                                    title = a.getStringAttribute("Position");
                                } catch (Exception ignored) {
                                    // the set name is a good enough heading
                                }
                            if (title == null || title.isEmpty())
                                title = setOf(feature.getId());
                            item.setMetaString("title", title);
                            item.setMetaString("callsign", title);
                            item.setMetaString("storm_set", setOf(feature.getId()));
                            return item;
                        }
                    };

            overlay = new FeatureDataStoreMapOverlay(mapView.getContext(), store, null,
                    layerName, "file://asset/nothing", query, null, null);
            // addOverlay, not addFilesOverlay. With addFilesOverlay this overlay did
            // not appear anywhere in Overlay Manager on the XCover, while its polygons
            // drew on the map perfectly well -- the same thing IPAWS found on the same
            // phone. The add reports whether it took, so ask rather than assume: the
            // symptom of getting it wrong is an absence from a list, which looks like
            // nothing at all.
            final boolean added = mapView.getMapOverlayManager().addOverlay(overlay);
            final String id = overlay.getIdentifier();
            Log.d(tag, "overlay registration: added=" + added + " identifier='" + id
                    + "' findable="
                    + (mapView.getMapOverlayManager().getOverlay(id) != null));
            mapView.addLayer(MapView.RenderStack.VECTOR_OVERLAYS, layer);
            try {
                final java.util.List<com.atakmap.map.layer.Layer> stack =
                        mapView.getLayers(MapView.RenderStack.VECTOR_OVERLAYS);
                Log.d(tag, "vector stack: " + stack.size() + " layers, ours present="
                        + stack.contains(layer) + ", layer='" + layer.getName()
                        + "' visible=" + layer.isVisible());
            } catch (Exception e) {
                Log.w(tag, "could not read the vector stack", e);
            }
        } catch (Exception e) {
            Log.w(tag, layerName + " store would not open", e);
            store = null;
        }
    }

    /**
     * Take away the drawings an earlier build of this plugin left behind.
     *
     * <p>They were {@code DrawingShape}s, which is to say ATAK thought they were the
     * operator's own drawings -- so it SAVED them, and restored them on every start.
     * With the layer drawing the current advisory on top, the map showed two of
     * everything, slightly apart, because the saved copies were from the previous
     * advisory (XCover, 2026-09-23: "its like drawn twice or some shit"). That is the
     * clearest possible argument for the change this class makes: an object gets
     * persisted, a feature does not.
     *
     * <p>Matched on the titles that build wrote, because it left nothing else to
     * recognize it by. Runs once at start and costs nothing when there is nothing to
     * find; it can go once no phone has a build older than 2026-09-23 on it.
     *
     * <p><b>It must run BEFORE the overlay is created, and that is not a detail.</b>
     * {@link FeatureDataStoreMapOverlay}'s constructor makes its own
     * {@code DefaultMapGroup} named after the overlay, and {@code addOverlay} binds
     * the hit-test query to that group. This sweep removes a root group called
     * "Hurricanes" -- which, once the overlay exists, IS the overlay's own group --
     * and {@code RootMapGroup.removeGroupImpl} deletes the query along with it. The
     * result was an overlay that reported added=true and findable=true, a layer that
     * drew perfectly, and a query ATAK never asked anything, at any zoom, dead on a
     * line. The log said "removed a leftover Hurricanes group" on every single start
     * and was read as the cleanup working rather than as the bug reporting itself
     * (XCover, 2026-09-23).
     */
    private void sweepOldDrawings() {
        if (overlay != null)
            // Never while our own overlay is registered: see below.
            return;
        try {
            final com.atakmap.android.maps.MapGroup root = mapView.getRootGroup();
            final com.atakmap.android.maps.MapGroup mine = root.findMapGroup("Hurricanes");
            if (mine != null) {
                root.removeGroup(mine);
                Log.d(tag, "removed a leftover Hurricanes group");
            }
            int n = 0;
            for (String suffix : new String[] { " cone", " track", " watches",
                    " wind arrival" })
                n += sweepTitled(root, suffix);
            // The positions were titled with the storm or the forecast time, so they
            // are found by their own marker type instead.
            if (n > 0)
                Log.d(tag, "removed " + n + " drawings left by an older build");
        } catch (Exception e) {
            Log.w(tag, "sweep of old drawings failed", e);
        }
    }

    private int sweepTitled(com.atakmap.android.maps.MapGroup group, String suffix) {
        int n = 0;
        for (MapItem it : new java.util.ArrayList<>(group.getItems())) {
            final String t = it.getTitle();
            if (t != null && t.endsWith(suffix)
                    && (t.contains("Hurricane ") || t.contains("Tropical ")
                            || t.contains("Storm "))) {
                it.removeFromGroup();
                n++;
            }
        }
        for (com.atakmap.android.maps.MapGroup child
                : new java.util.ArrayList<>(group.getChildGroups()))
            n += sweepTitled(child, suffix);
        return n;
    }

    void detach() {
        synchronized (lock) {
            detachLocked();
        }
    }

    private void detachLocked() {
        try {
            if (overlay != null)
                mapView.getMapOverlayManager().removeOverlay(overlay);
            if (layer != null)
                mapView.removeLayer(MapView.RenderStack.VECTOR_OVERLAYS, layer);
            if (store != null)
                store.dispose();
        } catch (Exception e) {
            Log.w(tag, layerName + " store would not close", e);
        }
        overlay = null;
        layer = null;
        store = null;
        sets.clear();
    }

    /**
     * Replace everything with this set. <b>Worker thread only</b>: it writes a database,
     * and a country's warnings with their zone shapes is not work for main.
     *
     * <p>IPAWS's {@code AlertOverlay.rewrite}, carried forward: one modify lock for the
     * whole write, so ATAK re-queries the store once rather than once per insert, and
     * the new sets go in BEFORE the old come out, so the map never blanks between two
     * polls. An empty list is honored -- when everything expires the map goes quiet --
     * so a failed poll must not call this at all.
     */
    void rewrite(java.util.List<Drawn> drawn) {
        synchronized (lock) {
            if (store == null)
                return;
            boolean bulk = false;
            try {
                store.acquireModifyLock(true);
                bulk = true;
                final java.util.List<Long> old = new java.util.ArrayList<>(sets.values());
                final Map<String, Long> fresh = new HashMap<>();
                for (Drawn d : drawn) {
                    Long fsid = fresh.get(d.setName);
                    if (fsid == null) {
                        fsid = store.insertFeatureSet(
                                new FeatureSet(PROVIDER, type, d.setName, MIN_GSD, MAX_GSD));
                        store.setFeatureSetVisible(fsid, true);
                        fresh.put(d.setName, fsid);
                    }
                    store.insertFeature(new Feature(fsid, d.name, d.geometry, d.style,
                            d.attrs, Feature.AltitudeMode.ClampToGround, 0d));
                }
                for (Long id : old) {
                    try {
                        store.deleteFeatureSet(id);
                    } catch (Exception e) {
                        Log.w(tag, "old set " + id, e);
                    }
                }
                sets.clear();
                sets.putAll(fresh);
            } catch (Exception e) {
                Log.w(tag, layerName + " rewrite failed", e);
            } finally {
                if (bulk)
                    store.releaseModifyLock();
            }
        }
    }

    /** Empty the store without taking the layer off the map. */
    void clear() {
        if (store == null)
            return;
        try {
            store.deleteFeatureSets(new FeatureDataStore2.FeatureSetQueryParameters());
        } catch (Exception e) {
            Log.w(tag, layerName + " store would not clear", e);
        }
        sets.clear();
    }

    /** "Hurricane Polo - Cone": one set per storm and product, listed on its own. */
    private long setFor(String name) {
        final Long known = sets.get(name);
        if (known != null)
            return known;
        try {
            final long id = store.insertFeatureSet(
                    new FeatureSet(PROVIDER, type, name, MIN_GSD, MAX_GSD));
            store.setFeatureSetVisible(id, true);
            sets.put(name, id);
            return id;
        } catch (Exception e) {
            Log.w(tag, "feature set " + name, e);
            return -1;
        }
    }

    private static LineString ring(GeoPoint[] pts) {
        final LineString l = new LineString(2);
        for (GeoPoint p : pts)
            l.addPoint(p.getLongitude(), p.getLatitude());
        return l;
    }

    void addPolygon(String setName, String name, GeoPoint[] pts, int stroke, float weight,
            int fill, AttributeSet attrs) {
        insert(setName, name, new Polygon(ring(pts)),
                new CompositeStyle(new Style[] {
                        new BasicFillStyle(fill), new BasicStrokeStyle(stroke, weight) }),
                attrs);
    }

    /**
     * A shape that arrived already built, holes and all: a contour band is a polygon
     * with the worse bands cut out of it, and flattening it to its outer ring stacks
     * every band on top of the ones inside it.
     */
    void addShape(String setName, String name, com.atakmap.map.layer.feature.geometry.Geometry g,
            int stroke, float weight, int fill, AttributeSet attrs) {
        insert(setName, name, g,
                new CompositeStyle(new Style[] {
                        new BasicFillStyle(fill), new BasicStrokeStyle(stroke, weight) }),
                attrs);
    }

    void addLine(String setName, String name, GeoPoint[] pts, int stroke, float weight,
            AttributeSet attrs) {
        addLine(setName, name, pts, stroke, weight, attrs, false);
    }

    /**
     * A line, optionally with its name drawn along it.
     *
     * <p>ATAK does not label a line feature from its name the way it labels a point:
     * the arrival contours were correctly named "Sat 8 pm" and the map showed nothing
     * (operator, 2026-09-23: "no arrival times"). A LabelPointStyle in the composite
     * is what puts text on a line, and the hour is the only reason that line is drawn.
     */
    void addLine(String setName, String name, GeoPoint[] pts, int stroke, float weight,
            AttributeSet attrs, boolean labelled) {
        final Style stroked = new BasicStrokeStyle(stroke, weight);
        Style style = stroked;
        if (labelled && name != null && !name.isEmpty()) {
            final LabelPointStyle label = new LabelPointStyle(name, 0xFFFFFFFF,
                    0x99000000, LabelPointStyle.ScrollMode.DEFAULT);
            style = new CompositeStyle(new Style[] { stroked, label });
        }
        insert(setName, name, ring(pts), style, attrs);
    }

    /**
     * A point drawn as an already-composed icon. The label is inside that bitmap, so
     * the style carries no label of its own: ATAK's own would be trimmed, and two
     * labels on one point is worse than one.
     */
    void addIcon(String setName, String name, GeoPoint p, String iconUri,
            int width, int height, AttributeSet attrs) {
        // Named, but with an EMPTY label style beside the icon.
        //
        // A named feature draws its name as a label, and the label is already inside
        // this icon, so naming it drew every storm twice a few pixels apart. Leaving
        // the name off fixed that and cost something else: ATAK's Select Item chooser
        // reads the feature's NAME, so every forecast position listed as "[Unnamed]"
        // while the cone and track listed properly (XCover, 2026-09-23). An empty
        // LabelPointStyle is what Feature Layer calls withoutLabel(): the engine
        // draws that instead of the default name, which is to say nothing.
        insert(setName, name, new Point(p.getLongitude(), p.getLatitude()),
                new CompositeStyle(new Style[] {
                        new IconPointStyle(0xFFFFFFFF, iconUri, width, height, 0, 0,
                                0f, true),
                        new LabelPointStyle("", 0x00FFFFFF, 0x00000000,
                                LabelPointStyle.ScrollMode.DEFAULT) }),
                attrs);
    }

    private void insert(String setName, String name, com.atakmap.map.layer.feature.geometry.Geometry g,
            Style style, AttributeSet attrs) {
        if (store == null)
            return;
        final long fsid = setFor(setName);
        if (fsid < 0)
            return;
        try {
            store.insertFeature(new Feature(fsid, name, g, style, attrs,
                    Feature.AltitudeMode.ClampToGround, 0d));
        } catch (Exception e) {
            Log.w(tag, "insert " + setName + "/" + name, e);
        }
    }

    /** Which feature set a feature belongs to, by name, for the details subtitle. */
    private String setOf(long fid) {
        if (store == null)
            return "";
        com.atakmap.map.layer.feature.FeatureCursor c = null;
        try {
            final FeatureDataStore2.FeatureQueryParameters p =
                    new FeatureDataStore2.FeatureQueryParameters();
            p.ids = java.util.Collections.singleton(fid);
            p.ignoredFeatureProperties = FeatureDataStore2.PROPERTY_FEATURE_GEOMETRY
                    | FeatureDataStore2.PROPERTY_FEATURE_STYLE
                    | FeatureDataStore2.PROPERTY_FEATURE_ATTRIBUTES;
            p.limit = 1;
            c = store.queryFeatures(p);
            if (c.moveToNext()) {
                final long fsid = c.get().getFeatureSetId();
                for (java.util.Map.Entry<String, Long> e : sets.entrySet())
                    if (e.getValue() == fsid)
                        return e.getKey();
            }
        } catch (Exception e) {
            Log.w(tag, "set of " + fid, e);
        } finally {
            if (c != null)
                try {
                    c.close();
                } catch (Exception ignored) {
                }
        }
        return "";
    }

    /** One feature's attributes by id: the hit-test query drops them, so fetch them. */
    private AttributeSet attributesOf(long fid) {
        if (store == null)
            return null;
        com.atakmap.map.layer.feature.FeatureCursor c = null;
        try {
            final FeatureDataStore2.FeatureQueryParameters p =
                    new FeatureDataStore2.FeatureQueryParameters();
            p.ids = java.util.Collections.singleton(fid);
            p.ignoredFeatureProperties = FeatureDataStore2.PROPERTY_FEATURE_GEOMETRY
                    | FeatureDataStore2.PROPERTY_FEATURE_STYLE;
            p.limit = 1;
            c = store.queryFeatures(p);
            if (c.moveToNext())
                return c.get().getAttributes();
        } catch (Exception e) {
            Log.w(tag, "attributes of " + fid, e);
        } finally {
            if (c != null)
                try {
                    c.close();
                } catch (Exception ignored) {
                }
        }
        return null;
    }


}
