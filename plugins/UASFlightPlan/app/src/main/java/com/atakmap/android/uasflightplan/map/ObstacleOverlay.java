package com.atakmap.android.uasflightplan.map;

import android.content.Context;

import com.atakmap.android.features.FeatureDataStoreDeepMapItemQuery;
import com.atakmap.android.features.FeatureDataStoreMapOverlay;
import com.atakmap.android.maps.MapItem;
import com.atakmap.android.maps.MapView;
import com.atakmap.android.uasflightplan.data.Units;
import com.atakmap.android.uasflightplan.obstacles.Obstacle;
import com.atakmap.android.uasflightplan.plugin.R;
import com.atakmap.coremap.filesystem.FileSystemUtils;
import com.atakmap.coremap.log.Log;
import com.atakmap.map.layer.feature.AttributeSet;
import com.atakmap.map.layer.feature.Feature;
import com.atakmap.map.layer.feature.FeatureDataStore2;
import com.atakmap.map.layer.feature.FeatureLayer3;
import com.atakmap.map.layer.feature.FeatureSet;
import com.atakmap.map.layer.feature.FeatureSetCursor;
import com.atakmap.map.layer.feature.datastore.FeatureSetDatabase2;
import com.atakmap.map.layer.feature.geometry.LineString;
import com.atakmap.map.layer.feature.geometry.Point;
import com.atakmap.map.layer.feature.style.BasicStrokeStyle;
import com.atakmap.map.layer.feature.style.IconPointStyle;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The obstacles on the map: FAA's records drawn as a read-only feature layer,
 * standing up at their heights.
 *
 * <p>Someone else's GIS data is a feature layer, never a {@code Marker}: the pilot
 * gets details on a tap and cannot recolor or move an FAA record, ATAK does not
 * save it as one of their drawings, and Overlay Manager lists it as a layer.
 * IPAWS's {@code AlertOverlay} is the pattern; the Obstacles plugin will copy this
 * forward.
 *
 * <p>Each obstacle is two features: a vertical line from the ground to its top,
 * and an icon with the name at the top, both with {@code AltitudeMode.Relative}
 * so they stand up when the map is tilted and sit on the point when it is not.
 * Red when the top is at or above the ceiling (it sticks up through it like an
 * island), amber under it.
 *
 * <p>Every write is one batched rewrite on a worker under one modify lock. The
 * store is never disposed: the renderer's worker aborts the whole ATAK process.
 * The sets are deleted on attach instead, so a reload starts clean.
 */
public final class ObstacleOverlay {

    private static final String TAG = "UASObstacleLayer";

    /** Top at or above the ceiling. */
    public static final int ABOVE_ARGB = 0xFFE53935;
    /** Top under the ceiling. */
    public static final int BELOW_ARGB = 0xFFFFA000;

    /** The most drawn per circle: two features each, and a rewrite has to stay quick. */
    public static final int MAX_DRAWN = 1500;

    private final MapView mapView;
    private final Context pluginContext;
    private final Object lock = new Object();

    private FeatureSetDatabase2 store;
    private FeatureLayer3 layer;
    private FeatureDataStoreMapOverlay overlay;
    private volatile boolean visible = true;

    public ObstacleOverlay(MapView mapView, Context pluginContext) {
        this.mapView = mapView;
        this.pluginContext = pluginContext;
    }

    /** Opens the store and puts the layer on the map. Worker thread: it touches a file. */
    public void attach() throws Exception {
        synchronized (lock) {
            final File dir = FileSystemUtils.getItem("tools/uasflightplan");
            if (dir != null && !dir.exists() && !dir.mkdirs())
                Log.w(TAG, "could not create " + dir);
            final File file = new File(dir, "obstacles.sqlite");
            store = new FeatureSetDatabase2(file);
            // Whatever a previous session or build left: the circle is recomputed
            // from the launch point, never read back from this file.
            dropAllSets();

            final FeatureDataStore2.FeatureQueryParameters visibleOnly =
                    new FeatureDataStore2.FeatureQueryParameters();
            visibleOnly.visibleOnly = true;
            final String title = pluginContext.getString(R.string.obstacles_layer);
            layer = new FeatureLayer3(title, store, visibleOnly);
            final FeatureDataStoreDeepMapItemQuery query =
                    new FeatureDataStoreDeepMapItemQuery(layer) {
                        @Override
                        protected MapItem featureToMapItem(Feature feature) {
                            final MapItem item = super.featureToMapItem(feature);
                            item.setMetaLong("featureid", feature.getId());
                            item.setMetaString("title", feature.getName());
                            item.setMetaString("callsign", feature.getName());
                            // Tap two overlapping obstacles and ATAK offers a chooser;
                            // without an icon those rows come up blank.
                            item.setMetaString("iconUri", "android.resource://"
                                    + pluginContext.getPackageName() + "/"
                                    + R.drawable.ic_obstacle);
                            item.setMetaInteger("iconColor", 0xFFFFFFFF);
                            return item;
                        }
                    };
            overlay = new FeatureDataStoreMapOverlay(mapView.getContext(), store, null,
                    title, "android.resource://" + pluginContext.getPackageName() + "/"
                            + R.drawable.ic_toolbar,
                    query, null, null);
            final boolean added = mapView.getMapOverlayManager().addOverlay(overlay);
            Log.d(TAG, "overlay registration: added=" + added);
            mapView.addLayer(MapView.RenderStack.VECTOR_OVERLAYS, layer);
            layer.setVisible(visible);
        }
    }

    /** Takes the layer off the map. The store is left alone, never disposed. */
    public void detach() {
        synchronized (lock) {
            try {
                if (layer != null)
                    mapView.removeLayer(MapView.RenderStack.VECTOR_OVERLAYS, layer);
                if (overlay != null)
                    mapView.getMapOverlayManager().removeOverlay(overlay);
            } catch (RuntimeException e) {
                Log.w(TAG, "detach failed", e);
            }
            layer = null;
            overlay = null;
            store = null;
        }
    }

    /** Hides or shows the layer at once. */
    public void setVisible(boolean on) {
        visible = on;
        final FeatureLayer3 l = layer;
        if (l != null)
            l.setVisible(on);
    }

    public boolean isVisible() {
        return visible;
    }

    /**
     * Replaces everything on the map with these obstacles, colored for this ceiling.
     * Worker thread only: this writes a database.
     *
     * @return how many obstacles were drawn
     */
    public int rewrite(List<Obstacle> obstacles, double ceilingMslFt) {
        synchronized (lock) {
            if (store == null)
                return 0;
            boolean locked = false;
            int drawn = 0;
            try {
                // One content-changed notification at the end instead of one per
                // insert, each of which has ATAK re-query the store on its main thread.
                store.acquireModifyLock(true);
                locked = true;
                final List<Long> old = existingSets();
                final long fsid = store.insertFeatureSet(new FeatureSet("UASFlightPlan",
                        "obstacles", pluginContext.getString(R.string.obstacles_layer),
                        Double.MAX_VALUE, 0d));
                store.setFeatureSetVisible(fsid, true);
                for (Obstacle o : obstacles) {
                    if (drawn >= MAX_DRAWN)
                        break;
                    insert(fsid, o, ceilingMslFt);
                    drawn++;
                }
                for (Long id : old) {
                    try {
                        store.deleteFeatureSet(id);
                    } catch (Exception e) {
                        Log.w(TAG, "old set " + id, e);
                    }
                }
            } catch (Exception e) {
                Log.w(TAG, "obstacle rewrite failed", e);
            } finally {
                if (locked)
                    store.releaseModifyLock();
            }
            Log.d(TAG, "obstacles drawn: " + drawn + " of " + obstacles.size());
            return drawn;
        }
    }

    /** Clears the map. Worker thread. */
    public void clear() {
        synchronized (lock) {
            if (store == null)
                return;
            boolean locked = false;
            try {
                store.acquireModifyLock(true);
                locked = true;
                dropAllSets();
            } catch (Exception e) {
                Log.w(TAG, "obstacle clear failed", e);
            } finally {
                if (locked)
                    store.releaseModifyLock();
            }
        }
    }

    private void insert(long fsid, Obstacle o, double ceilingMslFt) throws Exception {
        final int color = o.aboveCeiling(ceilingMslFt) ? ABOVE_ARGB : BELOW_ARGB;
        final double topM = Units.feetToMeters(Math.max(0d, o.aglFt));
        final String name = String.format(Locale.US, "%s %s AGL", o.kind(),
                Units.height(topM));

        final AttributeSet attrs = new AttributeSet();
        attrs.setAttribute("Type", o.kind());
        attrs.setAttribute("Height above ground", Units.height(topM));
        if (!Double.isNaN(o.amslFt))
            attrs.setAttribute("Top above sea level",
                    Units.altitudeMsl(Units.feetToMeters(o.amslFt)));
        attrs.setAttribute("Lighting", o.lightingWords());
        attrs.setAttribute("Verified", o.isVerified() ? "yes" : "no");
        if (o.quantity > 1)
            attrs.setAttribute("Quantity", o.quantity);
        if (!o.city.isEmpty())
            attrs.setAttribute("City", o.city + (o.state.isEmpty() ? "" : ", " + o.state));
        attrs.setAttribute("FAA number", o.oas);
        attrs.setAttribute("Source", "FAA Digital Obstacle File");

        // Heights are relative to the ground under the point, so the line stands on
        // the terrain whatever the map's elevation says. Longitude first, as the
        // feature geometry wants it.
        final Feature.Traits standing = new Feature.Traits();
        standing.altitudeMode = Feature.AltitudeMode.Relative;
        standing.extrude = 0d;

        final LineString mast = new LineString(3);
        mast.addPoint(o.lon, o.lat, 0d);
        mast.addPoint(o.lon, o.lat, topM);
        store.insertFeature(new Feature(fsid, FeatureDataStore2.FEATURE_ID_NONE, name, mast,
                new BasicStrokeStyle(color, 3f), attrs, standing,
                FeatureDataStore2.TIMESTAMP_NONE, FeatureDataStore2.FEATURE_VERSION_NONE));

        final Point top = new Point(o.lon, o.lat, topM);
        store.insertFeature(new Feature(fsid, FeatureDataStore2.FEATURE_ID_NONE, name, top,
                new IconPointStyle(color, "android.resource://"
                        + pluginContext.getPackageName() + "/" + R.drawable.ic_obstacle),
                attrs, standing,
                FeatureDataStore2.TIMESTAMP_NONE, FeatureDataStore2.FEATURE_VERSION_NONE));
    }

    private void dropAllSets() {
        for (Long id : existingSets()) {
            try {
                store.deleteFeatureSet(id);
            } catch (Exception e) {
                Log.w(TAG, "old set " + id, e);
            }
        }
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
}
