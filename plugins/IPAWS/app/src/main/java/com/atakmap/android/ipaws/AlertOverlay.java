package com.atakmap.android.ipaws;

import android.content.Context;

import com.atakmap.android.features.FeatureDataStoreDeepMapItemQuery;
import com.atakmap.android.features.FeatureDataStoreMapOverlay;
import com.atakmap.android.maps.MapItem;
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
                            String title = feature.getName();
                            final AttributeSet a = feature.getAttributes();
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
                            return item;
                        }
                    };
            overlay = new FeatureDataStoreMapOverlay(mapView.getContext(), store, null,
                    title, "file://asset/nothing", query, null, null);
            mapView.getMapOverlayManager().addFilesOverlay(overlay);
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
        // 0, 0: no resolution gate. An alert area is a county or bigger and has to be
        // visible at the scale someone looks at a state from.
        final long id = store.insertFeatureSet(new FeatureSet("IPAWS", "alerts", name, 0d, 0d));
        store.setFeatureSetVisible(id, true);
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
