
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
import com.atakmap.map.layer.feature.FeatureSetCursor;
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
    /**
     * Feature sets already in the store when this instance opened it, retired by the
     * first rewrite.
     *
     * <p>The store file outlives the plugin instance and {@link #sets} does not, so a
     * reinstall used to start with nothing to delete: the previous instance's
     * features stayed on the map forever and the new instance drew its own on top.
     * Every reinstall added a layer. The operator found three stale Wheeler Incident
     * discs stacked under the one that was current, all at the same coordinates
     * (2026-09-25: "now its listed 4 times").
     */
    /** Held by a rewrite on the worker and by detach, so a store is never closed mid-write. */
    private final Object lock = new Object();

    /** One thing to draw in a {@link #rewrite}: a shape, its set, its style, its fields. */
    static final class Drawn {
        final String setName, name;
        final com.atakmap.map.layer.feature.geometry.Geometry geometry;
        final Style style;
        final AttributeSet attrs;
        /**
         * The zoom band this feature's set draws in, coarsest and finest meters per
         * pixel. Taken from the first feature of a set. The defaults draw at every
         * zoom; a layer that wants a gate -- stations that would be a wall of icons
         * on a state-wide view, or labels that only earn their space close in --
         * names its own.
         */
        final double minGsd, maxGsd;

        Drawn(String setName, String name, com.atakmap.map.layer.feature.geometry.Geometry geometry,
                Style style, AttributeSet attrs) {
            this(setName, name, geometry, style, attrs, MIN_GSD, MAX_GSD);
        }

        Drawn(String setName, String name, com.atakmap.map.layer.feature.geometry.Geometry geometry,
                Style style, AttributeSet attrs, double minGsd, double maxGsd) {
            this.minGsd = minGsd;
            this.maxGsd = maxGsd;
            this.setName = setName;
            this.name = name;
            this.geometry = geometry;
            this.style = style;
            this.attrs = attrs;
        }
    }

    /**
     * A point drawn as an already-composed icon, as a style for {@link #rewrite}.
     * The label is inside the bitmap, so the composite carries an EMPTY label style
     * beside it: a named feature otherwise draws its name as well, and ATAK's Select
     * Item chooser reads that name, so it cannot simply be left off.
     */
    static Style icon(String iconUri, int width, int height) {
        return icon(iconUri, width, height, 0f, 0f);
    }

    /**
     * An icon placed by a pixel offset rather than by its own middle.
     *
     * <p>{@code alignX}/{@code alignY} are enums by sign -- left, center, right --
     * and cannot place anything; the constructor that takes real {@code offsetX} and
     * {@code offsetY} floats can (read from IconPointStyle, ATAK 5.8.0.3).
     *
     * <p>Which matters because a centered bitmap has to be big enough for everything
     * it might contain in any direction -- a wind barb at any angle, a label on
     * whichever side is free -- so it ends up mostly empty. That is invisible on the
     * map and ruinous anywhere the icon is scaled into a small box, like ATAK's
     * Select Item chooser, where the symbol became a few pixels across. With an
     * offset the bitmap can be trimmed to its own ink and still land on its point.
     */
    static Style icon(String iconUri, int width, int height, float offsetX, float offsetY) {
        return new CompositeStyle(new Style[] {
                new IconPointStyle(0xFFFFFFFF, iconUri, width, height,
                        offsetX, offsetY, 0, 0, 0f, true),
                new LabelPointStyle("", 0x00FFFFFF, 0x00000000,
                        LabelPointStyle.ScrollMode.DEFAULT) });
    }

    /** A point geometry, for building a {@link Drawn} off the worker. */
    static com.atakmap.map.layer.feature.geometry.Geometry point(double lat, double lon) {
        return new Point(lon, lat);
    }

    /** A line's geometry, for building a {@link Drawn} off the worker. */
    static com.atakmap.map.layer.feature.geometry.Geometry path(GeoPoint[] pts) {
        return ring(pts);
    }

    /** A closed shape's geometry, for building a {@link Drawn} off the worker. */
    static com.atakmap.map.layer.feature.geometry.Geometry polygon(GeoPoint[] pts) {
        return new Polygon(ring(pts));
    }

    /**
     * A line's style, with its name drawn along it when {@code labelled}.
     *
     * <p>ATAK does not label a line feature from its name the way it labels a point,
     * so a LabelPointStyle in the composite is what puts text on a line.
     */
    static Style stroke(String name, int color, float weight, boolean labelled) {
        final Style stroked = new BasicStrokeStyle(color, weight);
        if (!labelled || name == null || name.isEmpty())
            return stroked;
        return new CompositeStyle(new Style[] { stroked,
                new LabelPointStyle(name, 0xFFFFFFFF, 0x99000000,
                        LabelPointStyle.ScrollMode.DEFAULT) });
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

    /**
     * Remove the store and its journals, so a session never inherits old features.
     *
     * <p>This is the one structural difference from IPAWS and Feature Layer, whose
     * stores open holding the last session's features. Theirs can: they write sets
     * the store hands back when asked. This one could not -- see {@link #attach}.
     */
    private void deleteStoreFile() {
        if (storeFile == null)
            return;
        int gone = 0;
        for (String suffix : new String[] { "", "-journal", "-wal", "-shm" }) {
            final File f = new File(storeFile.getPath() + suffix);
            if (f.isFile() && f.delete())
                gone++;
        }
        if (gone > 0)
            Log.d(tag, layerName + ": removed " + gone + " old store file(s)");
    }

    /** Every feature set id in the store, as the store reports it. */
    private java.util.List<Long> existingSetIds() {
        final java.util.List<Long> ids = new java.util.ArrayList<>();
        if (store == null)
            return ids;
        FeatureSetCursor c = null;
        try {
            // Named by provider and type, NOT the default parameters: those hand
            // back a handful of the sets the file actually holds -- six of sixty,
            // measured -- so a delete driven by them clears a fraction each pass.
            final FeatureDataStore2.FeatureSetQueryParameters ours =
                    new FeatureDataStore2.FeatureSetQueryParameters();
            ours.providers = java.util.Collections.singleton(PROVIDER);
            ours.types = java.util.Collections.singleton(type);
            c = store.queryFeatureSets(ours);
            while (c.moveToNext())
                ids.add(c.get().getId());
        } catch (Exception e) {
            Log.w(tag, "could not read existing sets", e);
        } finally {
            if (c != null)
                try {
                    c.close();
                } catch (Exception ignored) {
                    // nothing to do
                }
        }
        return ids;
    }

    /**
     * Open the store off the main thread, then put the layer on the map. Safe to call
     * twice.
     *
     * <p>Opening a {@code FeatureSetDatabase2} builds its tables and creates its
     * indices. One is not much; <b>nine layers starting together is an ANR</b>, and
     * that is exactly what plugin load does -- every overlay's start() in a row, on
     * the thread ATAK is loading the plugin on. The operator hit it enabling the
     * plugin from Package Management (2026-09-25); the trace is FeatureSetDatabase2's
     * constructor under AtmosphereFeatures.attach under Atmosphere.onStart on "main".
     * Emptying the file first, which this now does, means every one of those opens
     * builds from nothing every time rather than sometimes.
     *
     * <p>So the database work goes to one shared background thread -- shared, so nine
     * layers queue behind each other instead of nine threads fighting over the same
     * disk -- and only the map registration comes back to main, which is cheap and
     * has to be there. Anything that touches the store afterwards is worker-only by
     * contract and waits on {@link #ready}, so the wait lands on a worker and never
     * on the thread drawing the map.
     */
    void attach() {
        synchronized (lock) {
            if (store != null || attaching)
                return;
            attaching = true;
        }
        ATTACH.execute(new Runnable() {
            @Override
            public void run() {
                attachStore();
            }
        });
    }

    /** The one background thread every layer's store is opened on. */
    private static final java.util.concurrent.ExecutorService ATTACH =
            java.util.concurrent.Executors.newSingleThreadExecutor(
                    new java.util.concurrent.ThreadFactory() {
                        @Override
                        public Thread newThread(Runnable r) {
                            final Thread t = new Thread(r, "atmosphere-attach");
                            t.setDaemon(true);
                            return t;
                        }
                    });

    /** Counts down once the store is usable, whether or not the layer is on the map. */
    private final java.util.concurrent.CountDownLatch ready =
            new java.util.concurrent.CountDownLatch(1);
    private boolean attaching;
    private FeatureDataStoreDeepMapItemQuery query;
    private volatile boolean detached;

    /**
     * Wait for the store, on a worker.
     *
     * <p>Bounded: if the open failed there is nothing to wait for, and a layer that
     * hangs its own worker forever is worse than one that misses a refresh.
     */
    private boolean awaitStore() {
        if (store != null)
            return true;
        try {
            ready.await(20, java.util.concurrent.TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
        return store != null;
    }

    private void attachStore() {
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

            // Start from an empty file.
            //
            // The store accumulates feature sets its own API cannot enumerate: the
            // file held sixty while queryFeatureSets returned six, however the
            // parameters were framed, so fifty-four were unreachable and undeletable
            // and their features kept drawing. That is why the same incident came
            // back twice after every attempt to clear it (operator, 2026-09-25, four
            // rounds of it). There is no API that reaches them, so the file goes.
            //
            // It costs nothing: the layer is replaced whole on the next refresh,
            // which is seconds away, and the alternative is a file that grows
            // garbage forever -- this one reached 7.7 MB and 1,887 features to draw
            // roughly 224.
            deleteStoreFile();
            store = new FeatureSetDatabase2(storeFile);
            // Everything that only needs the database can go now; the rest of this
            // is map registration and has to be on main.
            ready.countDown();
            final FeatureDataStore2.FeatureQueryParameters visibleOnly =
                    new FeatureDataStore2.FeatureQueryParameters();
            visibleOnly.visibleOnly = true;
            layer = new FeatureLayer3(layerName, store, visibleOnly);

            // A field, not a local: it is built here with the store and used when the
            // map registration runs, which is now a separate hop onto main.
            query = new FeatureDataStoreDeepMapItemQuery(layer) {
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
                            // A glyph for ATAK's Select Item chooser.
                            //
                            // Without one the chooser scales the map icon into its
                            // small box, and a station's icon is a large, mostly
                            // transparent canvas -- room for a barb pointing any
                            // direction -- so the symbol itself lands a few pixels
                            // across and is unreadable beside ATAK's own entries
                            // (operator, 2026-09-25). A layer that has something
                            // tighter says so in this attribute; Feature Layer does
                            // the same for its own chooser rows.
                            // The item a tap creates is DRAWN on the map, so an icon
                            // here must be the one the feature already wears -- a
                            // substitute changes the tapped symbol under the
                            // operator's finger, which a bare disc did (2026-09-25).
                            // Given that, it also makes ATAK's Select Item chooser
                            // legible, since the chooser scales this icon into a
                            // small row and the feature's own is now trimmed to its
                            // ink rather than centered in a mostly empty square.
                            if (a != null)
                                try {
                                    final String glyph = a.getStringAttribute("_chooserIcon");
                                    if (glyph != null && !glyph.isEmpty()
                                            && item instanceof com.atakmap.android.maps.Marker) {
                                        ((com.atakmap.android.maps.Marker) item).setIcon(
                                                new com.atakmap.coremap.maps.assets.Icon
                                                        .Builder()
                                                        .setImageUri(com.atakmap.coremap.maps
                                                                .assets.Icon.STATE_DEFAULT, glyph)
                                                        .setSize(a.getIntAttribute("_chooserW"),
                                                                a.getIntAttribute("_chooserH"))
                                                        .setAnchor(
                                                                a.getIntAttribute("_chooserAnchorX"),
                                                                a.getIntAttribute("_chooserAnchorY"))
                                                        .setColor(com.atakmap.coremap.maps.assets
                                                                .Icon.STATE_DEFAULT, 0xFFFFFFFF)
                                                        .build());
                                    }
                                } catch (Exception noGlyph) {
                                    Log.d(tag, "no icon for this item", noGlyph);
                                }
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
                            // A spot request knows how to open something better than
                            // a list of its own fields: the forecast NWS wrote for it.
                            // Carried as plain meta so the details receiver can route
                            // on it without knowing anything about spot forecasts.
                            if (a != null)
                                try {
                                    final String spotId = a.getStringAttribute("spotId");
                                    if (spotId != null && !spotId.isEmpty())
                                        item.setMetaString("spotId", spotId);
                                } catch (Exception ignored) {
                                    // not a spot feature
                                }
                            return item;
                        }
                    };

            mapView.post(new Runnable() {
                @Override
                public void run() {
                    registerOnMap();
                }
            });
        } catch (Exception e) {
            Log.w(tag, layerName + " store would not open", e);
            ready.countDown();
        }
    }

    /** The map half of attaching: cheap, and it has to run on the main thread. */
    private void registerOnMap() {
        if (detached) {
            Log.d(tag, layerName + ": stopped before its store finished opening");
            return;
        }
        try {
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
            // Sweep any layer a previous instance of this plugin left on the map.
            //
            // A reinstall does not reliably run the old instance's detach: disposal
            // is a posted Runnable that may never run once its context is gone. Its
            // FeatureLayer3 keeps drawing from this same store file, so every reload
            // stacked another copy -- the vector stack measured 9 layers, then 15
            // across two reinstalls (2026-09-25). Feature Layer empties a found
            // MapGroup for the same reason; this is that, for layers.
            //
            // It is not what put the same incident on the map twice -- an empty
            // store file is -- so do not read this as that fix.
            int swept = 0;
            try {
                for (com.atakmap.map.layer.Layer other : new java.util.ArrayList<>(
                        mapView.getLayers(MapView.RenderStack.VECTOR_OVERLAYS))) {
                    if (other != layer && layerName.equals(other.getName())) {
                        mapView.removeLayer(MapView.RenderStack.VECTOR_OVERLAYS, other);
                        swept++;
                    }
                }
            } catch (Exception e) {
                Log.w(tag, "could not sweep old layers", e);
            }
            if (swept > 0)
                Log.d(tag, layerName + ": swept " + swept
                        + " layer(s) left by an earlier instance");
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
            // Opening the store is asynchronous now, so a stop can overtake it. Say
            // so here: the map registration is a posted hop and would otherwise put a
            // layer on the map for a plugin that has already gone.
            detached = true;
            detachLocked();
        }
    }

    private void detachLocked() {
        try {
            if (overlay != null)
                mapView.getMapOverlayManager().removeOverlay(overlay);
            if (layer != null)
                mapView.removeLayer(MapView.RenderStack.VECTOR_OVERLAYS, layer);
            // The store is deliberately NOT disposed.
            //
            // Removing the layer does not stop a query already running on the
            // renderer's own worker thread. That thread calls back into Java, the
            // closed store throws DataStoreException, and JNI aborts the PROCESS --
            // signal 6, "CallVoidMethodV called with pending exception", inside
            // GLAsynchronousMapRenderable3::WorkerThread. It took ATAK down on every
            // reinstall (08:38:38 and 08:45:42 on 2026-09-25, and on 2026-09-23),
            // which is what made this layer look unfixable: the crash landed a second
            // or two after each write, so the next build went into a dead process and
            // the same duplicate incident kept coming back however it was chased.
            //
            // Delaying the dispose only moves the window; there is no point at which
            // the renderer is known to be finished. So it is left open. ATAK owns the
            // process and closes the file when it exits; a leaked handle costs
            // nothing next to aborting the host.
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
        // Waits for the store, which is opened on its own thread now. This is a
        // worker-only API by contract, so the wait is never on the map's thread --
        // and a layer whose first refresh beat the open would otherwise draw nothing
        // and say nothing until its next poll, ten minutes later.
        if (!awaitStore())
            return;
        synchronized (lock) {
            if (store == null)
                return;
            boolean bulk = false;
            try {
                store.acquireModifyLock(true);
                bulk = true;
                // Write the new sets first, then delete the ones that were here on
                // the way in, both inside the modify lock, so the layer is never
                // momentarily empty on the map.
                //
                // What to delete is asked of the store, not remembered: an in-memory
                // list of "ours" is empty after a reinstall, and the file outlives
                // the instance. That is only sound because attach now starts from an
                // empty file -- while it did not, this query saw six of the sixty
                // sets the file held and each pass cleared a sixth of the problem
                // (2026-09-25).
                final java.util.List<Long> old = existingSetIds();
                sets.clear();
                final Map<String, Long> fresh = new HashMap<>();
                for (Drawn d : drawn) {
                    Long fsid = fresh.get(d.setName);
                    if (fsid == null) {
                        fsid = store.insertFeatureSet(
                                new FeatureSet(PROVIDER, type, d.setName,
                                        d.minGsd, d.maxGsd));
                        store.setFeatureSetVisible(fsid, true);
                        fresh.put(d.setName, fsid);
                    }
                    store.insertFeature(new Feature(fsid, d.name, d.geometry, d.style,
                            d.attrs, Feature.AltitudeMode.ClampToGround, 0d));
                }
                // One at a time. deleteFeatureSets(params) is a silent no-op on this
                // store -- the file held sixty sets before and sixty after, every
                // pass, while reporting success -- so the operator kept finding the
                // same incident twice (2026-09-25). deleteFeatureSet(id) does work;
                // what was broken was the enumeration feeding it, which with default
                // query parameters returned six of the sixty.
                int cleared = 0;
                for (Long id : old) {
                    try {
                        store.deleteFeatureSet(id);
                        cleared++;
                    } catch (Exception e) {
                        Log.w(tag, "old set " + id, e);
                    }
                }
                Log.d(tag, layerName + ": cleared " + cleared + " of " + old.size()
                        + " old set(s), wrote " + fresh.size());
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
        if (!awaitStore())
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
