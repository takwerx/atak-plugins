
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
import com.atakmap.map.layer.feature.style.Style;
import com.atakmap.android.features.FeatureDataStoreDeepMapItemQuery;

import java.io.File;
import java.util.HashMap;
import java.util.Map;

/**
 * The storms as a read-only map layer, not as drawings.
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
final class TropicalFeatures {

    private static final String TAG = "AtmosphereTropical";

    /** The provider column, and what Overlay Manager groups these under. */
    private static final String PROVIDER = "Atmosphere";
    private static final String TYPE = "tropical";

    /**
     * Drawn at every zoom. minResolution is the COARSEST meters-per-pixel a set draws
     * at, so the permissive value is the large one; 0 there means it never draws at
     * all, which is the opposite of what it reads like.
     */
    private static final double MIN_GSD = Double.MAX_VALUE;
    private static final double MAX_GSD = 0d;

    private final MapView mapView;
    private final Context pluginContext;

    private File storeFile;
    private FeatureSetDatabase2 store;
    private FeatureLayer3 layer;
    private FeatureDataStoreMapOverlay overlay;
    private final Map<String, Long> sets = new HashMap<>();

    TropicalFeatures(MapView mapView, Context pluginContext) {
        this.mapView = mapView;
        this.pluginContext = pluginContext;
    }

    /** Open the store and put the layer on the map. Safe to call twice. */
    void attach() {
        if (store != null)
            return;
        try {
            storeFile = FileSystemUtils.getItem("tools/atmosphere/storms.sqlite");
            final File dir = storeFile.getParentFile();
            if (dir != null && !dir.isDirectory())
                //noinspection ResultOfMethodCallIgnored
                dir.mkdirs();
            // An advisory is replaced whole every six hours, so a store carried over
            // from a previous run is only a chance to draw a storm that has since
            // dissipated. Start empty.
            //noinspection ResultOfMethodCallIgnored
            storeFile.delete();

            store = new FeatureSetDatabase2(storeFile);
            final FeatureDataStore2.FeatureQueryParameters visibleOnly =
                    new FeatureDataStore2.FeatureQueryParameters();
            visibleOnly.visibleOnly = true;
            layer = new FeatureLayer3("Hurricanes", store, visibleOnly);

            final FeatureDataStoreDeepMapItemQuery query =
                    new FeatureDataStoreDeepMapItemQuery(layer) {
                        @Override
                        public java.util.SortedSet<MapItem> deepHitTest(MapView view,
                                com.atakmap.map.hittest.HitTestQueryParameters params,
                                java.util.Map<com.atakmap.map.layer.Layer2,
                                        java.util.Collection<com.atakmap.map.hittest.HitTestControl>> controls) {
                            final java.util.SortedSet<MapItem> hits =
                                    super.deepHitTest(view, params, controls);
                            Log.d(TAG, "deepHitTest: " + (controls == null ? -1 : controls.size())
                                    + " controls, " + (hits == null ? -1 : hits.size()) + " hits");
                            return hits;
                        }

                        @Override
                        public java.util.SortedSet<MapItem> deepHitTestItems(int x, int y,
                                com.atakmap.coremap.maps.coords.GeoPoint point, MapView view) {
                            final java.util.SortedSet<MapItem> hits =
                                    super.deepHitTestItems(x, y, point, view);
                            Log.d(TAG, "deepHitTestItems: "
                                    + (hits == null ? -1 : hits.size()) + " hits");
                            return hits;
                        }

                        @Override
                        protected MapItem featureToMapItem(Feature feature) {
                            final MapItem item = super.featureToMapItem(feature);
                            Log.d(TAG, "hit-test reached feature " + feature.getId()
                                    + " " + feature.getName());
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
                                item.setMetaString("remarks", readable(a));
                            return item;
                        }
                    };

            overlay = new FeatureDataStoreMapOverlay(mapView.getContext(), store, null,
                    "Hurricanes", "file://asset/nothing", query, null, null);
            // addOverlay, not addFilesOverlay. With addFilesOverlay this overlay did
            // not appear anywhere in Overlay Manager on the XCover, while its polygons
            // drew on the map perfectly well -- the same thing IPAWS found on the same
            // phone. The add reports whether it took, so ask rather than assume: the
            // symptom of getting it wrong is an absence from a list, which looks like
            // nothing at all.
            final boolean added = mapView.getMapOverlayManager().addOverlay(overlay);
            final String id = overlay.getIdentifier();
            Log.d(TAG, "overlay registration: added=" + added + " identifier='" + id
                    + "' findable="
                    + (mapView.getMapOverlayManager().getOverlay(id) != null));
            mapView.addLayer(MapView.RenderStack.VECTOR_OVERLAYS, layer);
            sweepOldDrawings();
        } catch (Exception e) {
            Log.w(TAG, "storm store would not open", e);
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
     */
    private void sweepOldDrawings() {
        try {
            final com.atakmap.android.maps.MapGroup root = mapView.getRootGroup();
            final com.atakmap.android.maps.MapGroup mine = root.findMapGroup("Hurricanes");
            if (mine != null) {
                root.removeGroup(mine);
                Log.d(TAG, "removed a leftover Hurricanes group");
            }
            int n = 0;
            for (String suffix : new String[] { " cone", " track", " watches",
                    " wind arrival" })
                n += sweepTitled(root, suffix);
            // The positions were titled with the storm or the forecast time, so they
            // are found by their own marker type instead.
            if (n > 0)
                Log.d(TAG, "removed " + n + " drawings left by an older build");
        } catch (Exception e) {
            Log.w(TAG, "sweep of old drawings failed", e);
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
        try {
            if (overlay != null)
                mapView.getMapOverlayManager().removeOverlay(overlay);
            if (layer != null)
                mapView.removeLayer(MapView.RenderStack.VECTOR_OVERLAYS, layer);
            if (store != null)
                store.dispose();
        } catch (Exception e) {
            Log.w(TAG, "storm store would not close", e);
        }
        overlay = null;
        layer = null;
        store = null;
        sets.clear();
    }

    /** Empty the store without taking the layer off the map. */
    void clear() {
        if (store == null)
            return;
        try {
            store.deleteFeatureSets(new FeatureDataStore2.FeatureSetQueryParameters());
        } catch (Exception e) {
            Log.w(TAG, "storm store would not clear", e);
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
                    new FeatureSet(PROVIDER, TYPE, name, MIN_GSD, MAX_GSD));
            store.setFeatureSetVisible(id, true);
            sets.put(name, id);
            return id;
        } catch (Exception e) {
            Log.w(TAG, "feature set " + name, e);
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

    void addLine(String setName, String name, GeoPoint[] pts, int stroke, float weight,
            AttributeSet attrs) {
        insert(setName, name, ring(pts), new BasicStrokeStyle(stroke, weight), attrs);
    }

    /**
     * A point drawn as an already-composed icon. The label is inside that bitmap, so
     * the style carries no label of its own: ATAK's own would be trimmed, and two
     * labels on one point is worse than one.
     */
    void addIcon(String setName, String name, GeoPoint p, String iconUri,
            int width, int height, AttributeSet attrs) {
        // The feature is given NO name. ATAK draws a feature's name as a label of its
        // own, and the label is already inside this icon, so a named feature drew
        // every storm twice: the composite's label plus ATAK's, a few pixels apart
        // (XCover, 2026-09-23). The name it would have had is in the attributes, which
        // is where the details pane reads from anyway.
        insert(setName, "", new Point(p.getLongitude(), p.getLatitude()),
                new IconPointStyle(0xFFFFFFFF, iconUri, width, height, 0, 0, 0f, true),
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
            Log.w(TAG, "insert " + setName + "/" + name, e);
        }
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
            Log.w(TAG, "attributes of " + fid, e);
        } finally {
            if (c != null)
                try {
                    c.close();
                } catch (Exception ignored) {
                }
        }
        return null;
    }

    /** The advisory's own fields, one per line, for the details pane. */
    private static String readable(AttributeSet a) {
        final StringBuilder b = new StringBuilder();
        for (String k : a.getAttributeNames()) {
            // getAttributeType answers with a Class, not a tag.
            if (a.getAttributeType(k) != String.class)
                continue;
            final String v = a.getStringAttribute(k);
            if (v == null || v.isEmpty() || v.equals("null"))
                continue;
            if (b.length() > 0)
                b.append('\n');
            b.append(k).append(": ").append(v);
        }
        return b.toString();
    }
}
