package com.atakmap.android.ipaws;

import android.content.Context;

import com.atakmap.android.features.FeatureDataStoreDeepMapItemQuery;
import com.atakmap.android.features.FeatureDataStoreMapOverlay;
import com.atakmap.android.maps.MapItem;
import com.atakmap.android.menu.PluginMenuParser;
import com.atakmap.android.maps.MapView;
import com.atakmap.coremap.log.Log;
import com.atakmap.map.layer.feature.AttributeSet;
import com.atakmap.map.layer.feature.Feature;
import com.atakmap.map.layer.feature.FeatureDataStore2;
import com.atakmap.map.layer.feature.FeatureLayer3;
import com.atakmap.map.layer.feature.FeatureSet;
import com.atakmap.map.layer.feature.FeatureSetCursor;
import com.atakmap.map.layer.feature.datastore.FeatureSetDatabase2;
import com.atakmap.map.layer.feature.geometry.Geometry;
import com.atakmap.map.layer.feature.style.Style;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The alert polygons on the map: one feature store, one layer, one entry in ATAK's
 * Overlay Manager.
 *
 * <p>Feature Layer's {@code LoadedLayer}, cut down to what one feed needs. The store
 * is a file, so the last good set is still on the map after ATAK restarts and before
 * the first poll of the new session comes back.
 *
 * <p>One feature set per severity, which is what puts Extreme, Severe and the rest in
 * the Overlay Manager as separate switches -- the plugin can be left running and its
 * quieter half turned off without opening our pane at all.
 */
public class AlertOverlay {

    private static final String TAG = "IPAWS";

    private final MapView mapView;
    private final Context pluginContext;
    private final File storeFile;
    private final String title;
    private final Object lock = new Object();

    private FeatureSetDatabase2 store;
    private FeatureLayer3 layer;
    private FeatureDataStoreMapOverlay overlay;
    private int count;
    /** Alerts ON / OFF. Read by every set this writes, so a rewrite cannot undo it. */
    private volatile boolean visible = true;
    /**
     * Its own thread, not the rebuild worker: a store-settings write waits for a
     * rewrite in progress (eleven seconds at national scale) and must not also wait
     * for the rebuilds queued behind it, or the labels outlive the switch by minutes.
     */
    private final java.util.concurrent.ExecutorService settings =
            java.util.concurrent.Executors.newSingleThreadExecutor(
                    new java.util.concurrent.ThreadFactory() {
                        @Override
                        public Thread newThread(Runnable r) {
                            final Thread t = new Thread(r, "ipaws-store-settings");
                            t.setDaemon(true);
                            return t;
                        }
                    });

    /** One thing to draw: an alert's area, or a point when no area could be resolved. */
    public static class Drawn {
        public final String setName;
        public final String name;
        public final Geometry geometry;
        public final Style style;
        public final AttributeSet attrs;

        public Drawn(String setName, String name, Geometry geometry, Style style,
                AttributeSet attrs) {
            this.setName = setName;
            this.name = name;
            this.geometry = geometry;
            this.style = style;
            this.attrs = attrs;
        }
    }

    public AlertOverlay(MapView mapView, Context pluginContext, File storeFile, String title) {
        this.mapView = mapView;
        this.pluginContext = pluginContext;
        this.storeFile = storeFile;
        this.title = title;
    }

    /**
     * One feature's attributes by id, without its geometry or style; null when absent.
     *
     * <p>This exists because {@code feature.getAttributes()} is <b>always null</b> in
     * {@code featureToMapItem} on a tap: ATAK's hit-test query asks the store for
     * features with {@code ignoredFeatureProperties = PROPERTY_FEATURE_ATTRIBUTES},
     * so the feature handed to the callback has none. Reading them there returned
     * nothing, the alert id never reached the map item, and the radial's Details
     * button logged "carries no alert id" and did nothing.
     *
     * <p>Feature Layer re-fetches for exactly this reason. Knowing the trap is not the
     * same as avoiding it -- it was written into this plugin's own comments and then
     * walked into anyway.
     */
    private AttributeSet attributesOf(long fid) {
        final FeatureDataStore2 s = store;
        if (s == null)
            return null;
        com.atakmap.map.layer.feature.FeatureCursor c = null;
        try {
            final FeatureDataStore2.FeatureQueryParameters p =
                    new FeatureDataStore2.FeatureQueryParameters();
            p.ids = java.util.Collections.singleton(fid);
            p.ignoredFeatureProperties = FeatureDataStore2.PROPERTY_FEATURE_GEOMETRY
                    | FeatureDataStore2.PROPERTY_FEATURE_STYLE;
            p.limit = 1;
            c = s.queryFeatures(p);
            if (c.moveToNext())
                return c.get().getAttributes();
        } catch (Exception e) {
            Log.w(TAG, "attributes of feature " + fid + " failed", e);
        } finally {
            if (c != null)
                try {
                    c.close();
                } catch (Exception ignored) {
                    // Nothing useful to do with a cursor that will not close.
                }
        }
        return null;
    }

    public void attach() throws Exception {
        synchronized (lock) {
            store = new FeatureSetDatabase2(storeFile);
            // Visible features only: with the plain constructor the renderer keeps
            // drawing the labels of sets the user switched off.
            final FeatureDataStore2.FeatureQueryParameters visibleOnly =
                    new FeatureDataStore2.FeatureQueryParameters();
            visibleOnly.visibleOnly = true;
            layer = new FeatureLayer3(title, store, visibleOnly);
            final FeatureDataStoreDeepMapItemQuery query =
                    new FeatureDataStoreDeepMapItemQuery(layer) {
                        @Override
                        protected MapItem featureToMapItem(Feature feature) {
                            final MapItem item = super.featureToMapItem(feature);
                            item.setMetaLong("featureid", feature.getId());
                            item.setMetaString("ipaws_overlay", "1");
                            // Our own radial, or ATAK shows its built-in feature
                            // metadata instead -- a second details screen with no way
                            // back, and nothing like the one the list opens.
                            item.setMetaString("menu", PluginMenuParser.getMenu(
                                    pluginContext, "menu/alert_shape.xml"));
                            String title = feature.getName();
                            // Null on a tap: the hit-test query drops attributes.
                            AttributeSet a = feature.getAttributes();
                            if (a == null)
                                a = attributesOf(feature.getId());
                            if (a != null) {
                                try {
                                    final String t = a.getStringAttribute("headline");
                                    if (t != null && !t.isEmpty())
                                        title = t;
                                } catch (Exception ignored) {
                                    // The name is already a good title.
                                }
                            }
                            item.setMetaString("title", title);
                            item.setMetaString("callsign", title);
                            if (a != null) {
                                try {
                                    // Which alert this shape is, so the radial can open
                                    // it without re-reading attributes the hit-test
                                    // query does not return.
                                    final String id = a.getStringAttribute("id");
                                    if (id != null)
                                        item.setMetaString("ipaws_alert_id", id);
                                } catch (Exception ignored) {
                                    // Without it the radial says so rather than guessing.
                                }
                            }
                            // Tap two overlapping alerts and ATAK offers a chooser;
                            // without an icon those rows come up blank.
                            item.setMetaString("iconUri", "asset://icons/details.png");
                            item.setMetaInteger("iconColor", 0xFFFFFFFF);
                            return item;
                        }
                        // Two reasons a chooser doubles up. ATAK hands the same
                        // feature back once per hit-test control; and one alert is
                        // drawn as TWO features, its area and the point carrying its
                        // name, which are different feature ids for the same alert.
                        // So the key is the alert where there is one.
                        @Override
                        public java.util.SortedSet<MapItem> deepHitTest(MapView view,
                                com.atakmap.map.hittest.HitTestQueryParameters params,
                                java.util.Map<com.atakmap.map.layer.Layer2, java.util.Collection<com.atakmap.map.hittest.HitTestControl>> controls) {
                            return dedupe(super.deepHitTest(view, params, controls));
                        }

                        @Override
                        public java.util.SortedSet<MapItem> deepHitTestItems(int xpos, int ypos,
                                com.atakmap.coremap.maps.coords.GeoPoint point, MapView view) {
                            return dedupe(super.deepHitTestItems(xpos, ypos, point, view));
                        }

                        private java.util.SortedSet<MapItem> dedupe(java.util.SortedSet<MapItem> hits) {
                            // Alerts OFF keeps the features, so a tap must not find them,
                            // including in the moment before the store has caught up.
                            if (!visible && hits != null)
                                return new java.util.TreeSet<>(hits.comparator());
                            if (hits == null || hits.isEmpty())
                                return hits;
                            final java.util.Set<String> seen = new java.util.HashSet<>();
                            final java.util.SortedSet<MapItem> out =
                                    new java.util.TreeSet<>(hits.comparator());
                            for (MapItem m : hits) {
                                final String alert = m.getMetaString("ipaws_alert_id", null);
                                final String key = alert != null ? "a:" + alert
                                        : "f:" + m.getMetaLong("featureid", -1);
                                if (seen.add(key))
                                    out.add(m);
                            }
                            return out;
                        }
                    };
            overlay = new FeatureDataStoreMapOverlay(mapView.getContext(), store, null,
                    title, "file://asset/nothing", query, null, null);
            // addOverlay, not addFilesOverlay. With addFilesOverlay (which is what
            // samples/hello3d uses) this overlay never appeared anywhere in the
            // Overlay Manager on the XCover -- the whole list was swept twice with
            // Show All on, while the polygons were drawing on the map perfectly well.
            // With addOverlay it is at the root as "IPAWS Alerts", with one child per
            // severity and a visibility toggle on each, which is where someone looking
            // to switch alerts off would actually look. These alerts are the plugin's
            // own overlay, not something a user imported from a file, so the root is
            // also where they belong.
            //
            // The add reports whether it took, so ask rather than assume: the symptom
            // of getting this wrong is an absence from a list, which looks like
            // nothing at all and is easy to read as "ATAK does not show plugin
            // overlays".
            final boolean added = mapView.getMapOverlayManager().addOverlay(overlay);
            final String id = overlay.getIdentifier();
            final boolean listed = mapView.getMapOverlayManager().getOverlay(id) != null;
            Log.d(TAG, "overlay registration: added=" + added + " identifier='" + id
                    + "' findable=" + listed);
            mapView.addLayer(MapView.RenderStack.VECTOR_OVERLAYS, layer);
            count = countFeatures();
            Log.d(TAG, "overlay attached, " + count + " features from the last session");
        }
    }

    public void detach() {
        synchronized (lock) {
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
    }

    /**
     * Alerts ON / OFF: shows or hides every alert at once, and keeps the features.
     *
     * <p>The sets are what really switch: the store marks them hidden, and the
     * visibleOnly renderer (see {@link #attach}) re-reads and drops them, labels
     * included. That write waits for any rewrite in progress -- eleven seconds at
     * national scale -- so it runs on a thread of its own.
     *
     * <p>Off also hides the whole layer at once, so the areas go on the next frame
     * however busy the store is. That alone is not enough, measured on s10-dev-1:
     * a hidden layer is not re-read, so ATAK kept drawing its labels over an empty
     * map indefinitely. Once the store has caught up, the layer is shown again; the
     * renderer re-reads it, finds nothing visible, and the labels go with it.
     */
    public void setVisible(final boolean on) {
        visible = on;
        final FeatureLayer3 l = layer;
        if (!on && l != null)
            l.setVisible(false);
        settings.execute(new Runnable() {
            @Override
            public void run() {
                synchronized (lock) {
                    if (store == null)
                        return;
                    try {
                        // The latest wish, not the one this task was queued with: two
                        // quick taps must end where the button says.
                        store.setFeatureSetsVisible(
                                new FeatureDataStore2.FeatureSetQueryParameters(), visible);
                    } catch (Exception e) {
                        Log.w(TAG, "set visibility failed", e);
                    }
                }
                com.atakmap.android.ipaws.data.MainThread.post(new Runnable() {
                    @Override
                    public void run() {
                        final FeatureLayer3 shown = layer;
                        if (shown != null)
                            shown.setVisible(true);
                    }
                });
            }
        });
    }

    public FeatureDataStore2 getStore() {
        return store;
    }

    public int count() {
        return count;
    }

    /**
     * Replaces everything on the map with this set.
     *
     * <p>An empty list is honored, and that is deliberate: when every alert expires
     * the map is supposed to go quiet. The rule that a failed poll must never blank
     * the overlay is kept by the caller simply not calling this -- the difference
     * between "nothing is happening" and "we could not ask" belongs where that is
     * known, not here where both look like an empty list.
     *
     * <p>Worker thread only: this writes a database.
     */
    public void rewrite(List<Drawn> drawn) {
        synchronized (lock) {
            if (store == null)
                return;
            boolean bulk = false;
            try {
                // One content-changed notification at the end instead of one per
                // insert, each of which has ATAK re-query the store on its main thread.
                store.acquireModifyLock(true);
                bulk = true;
                final List<Long> old = existingSets();
                final Map<String, Long> sets = new HashMap<>();
                for (Drawn d : drawn) {
                    Long fsid = sets.get(d.setName);
                    if (fsid == null) {
                        fsid = newSet(d.setName);
                        sets.put(d.setName, fsid);
                    }
                    store.insertFeature(new Feature(fsid, d.name, d.geometry, d.style,
                            d.attrs, Feature.AltitudeMode.ClampToGround, 0d));
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
                Log.d(TAG, "overlay rewritten: " + count + " features in " + sets.size()
                        + " sets (" + dropped + " old sets dropped)");
            } catch (Exception e) {
                Log.w(TAG, "overlay rewrite failed", e);
            } finally {
                if (bulk)
                    store.releaseModifyLock();
            }
        }
    }

    /**
     * ClampToGround is set on every feature above. Without it a shape carrying any
     * altitude sinks under the terrain and vanishes on zoom, which reads as the
     * plugin having lost the alert.
     */
    private long newSet(String name) throws Exception {
        // Double.MAX_VALUE, 0d is the SDK's own idiom for "no resolution gate" -- see
        // samples/customtiles, CustomTilesFeatureExtractor. minResolution is the
        // COARSEST resolution at which the set draws, so a huge value means it is
        // visible however far out you are, and maxResolution 0 means no limit going in.
        //
        // Passing 0d for minResolution, which reads like "no minimum", is how this was
        // written first, and it is exactly wrong: it stores min_lod = max_lod =
        // 2147483647, a level of detail no map ever reaches, so nothing draws at any
        // zoom. The store fills up, the log says the overlay was rewritten, and the
        // map stays empty.
        final long id = store.insertFeatureSet(
                new FeatureSet("IPAWS", "alerts", name, Double.MAX_VALUE, 0d));
        store.setFeatureSetVisible(id, visible);
        return id;
    }

    private List<Long> existingSets() {
        final List<Long> out = new ArrayList<>();
        FeatureSetCursor c = null;
        try {
            c = store.queryFeatureSets(new FeatureDataStore2.FeatureSetQueryParameters());
            while (c.moveToNext())
                out.add(c.get().getId());
        } catch (Exception e) {
            Log.w(TAG, "listing sets failed", e);
        } finally {
            if (c != null)
                try {
                    c.close();
                } catch (Exception ignored) {
                    // Nothing useful to do with a cursor that will not close.
                }
        }
        return out;
    }

    private int countFeatures() {
        try {
            return store.queryFeaturesCount(new FeatureDataStore2.FeatureQueryParameters());
        } catch (Exception e) {
            return 0;
        }
    }
}
