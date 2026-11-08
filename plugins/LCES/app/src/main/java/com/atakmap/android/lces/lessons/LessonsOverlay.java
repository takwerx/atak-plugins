package com.atakmap.android.lces.lessons;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import com.atakmap.android.features.FeatureDataStoreDeepMapItemQuery;
import com.atakmap.android.features.FeatureDataStoreMapOverlay;
import com.atakmap.android.maps.MapItem;
import com.atakmap.android.overlay.MapOverlay;
import com.atakmap.android.maps.MapOverlayManager;
import com.atakmap.android.maps.MapView;
import com.atakmap.coremap.filesystem.FileSystemUtils;
import com.atakmap.coremap.log.Log;
import com.atakmap.map.layer.Layer;
import com.atakmap.map.layer.feature.AttributeSet;
import com.atakmap.map.layer.feature.Feature;
import com.atakmap.map.layer.feature.FeatureCursor;
import com.atakmap.map.layer.feature.FeatureDataStore2;
import com.atakmap.map.layer.feature.FeatureLayer3;
import com.atakmap.map.layer.feature.FeatureSet;
import com.atakmap.map.layer.feature.datastore.FeatureSetDatabase2;
import com.atakmap.map.layer.feature.geometry.Point;
import com.atakmap.map.layer.feature.style.CompositeStyle;
import com.atakmap.map.layer.feature.style.IconPointStyle;
import com.atakmap.map.layer.feature.style.LabelPointStyle;
import com.atakmap.map.layer.feature.style.Style;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * The incidents on the map: one feature store, one layer, one row in Overlay Manager
 * with a child per kind.
 *
 * <p>IPAWS's {@code AlertOverlay} and Atmosphere's {@code AtmosphereFeatures}, cut to
 * points. Someone else's GIS data is a feature layer, never markers: ATAK saves
 * markers as the operator's own and brings them back at every start.
 *
 * <ul>
 * <li><b>The store is emptied at attach.</b> It is rebuilt from the catalog (which
 * is on disk, in the APK or downloaded) moments later, and a store that outlives the
 * instance collects sets its own API cannot enumerate (Atmosphere, 2026-09-25).</li>
 * <li><b>Never disposed.</b> Removing the layer does not stop a renderer query in
 * flight; a closed store under it aborts ATAK's whole process.</li>
 * <li><b>Off hides, never deletes.</b> Every set is written whatever the switch says,
 * with the switch's visibility, on a {@code visibleOnly} layer.</li>
 * <li><b>Writes are one batch on a worker</b>, under one modify lock.</li>
 * </ul>
 */
final class LessonsOverlay {

    private static final String TAG = "LCES-Lessons";
    static final String TITLE = "Lessons Learned";
    /** On every map item this layer hands ATAK, so a tap can tell ours from others'. */
    static final String META_ID = "lces_incident";
    /** Labels draw from this map resolution in, meters per pixel (about 6 mi across). */
    private static final double LABEL_MAX_RESOLUTION = 25;

    private final MapView mapView;
    private final Context pluginContext;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Object lock = new Object();
    private final CountDownLatch registered = new CountDownLatch(1);

    private FeatureSetDatabase2 store;
    private FeatureLayer3 layer;
    private FeatureDataStoreMapOverlay overlay;
    private volatile boolean visible;
    private volatile boolean detached;

    LessonsOverlay(MapView mapView, Context pluginContext, boolean visible) {
        this.mapView = mapView;
        this.pluginContext = pluginContext;
        this.visible = visible;
    }

    /** Worker: open the store, then put the layer on the map from main. */
    void attach() {
        try {
            final File file = new File(FileSystemUtils.getItem("tools/lces"), "lessons.sqlite");
            final File dir = file.getParentFile();
            if (dir != null && !dir.isDirectory() && !dir.mkdirs())
                Log.w(TAG, "could not make " + dir);
            for (String suffix : new String[] {"", "-journal", "-wal", "-shm"}) {
                final File f = new File(file.getPath() + suffix);
                if (f.isFile() && !f.delete())
                    Log.w(TAG, "could not empty " + f.getName());
            }
            synchronized (lock) {
                store = new FeatureSetDatabase2(file);
                final FeatureDataStore2.FeatureQueryParameters visibleOnly =
                        new FeatureDataStore2.FeatureQueryParameters();
                visibleOnly.visibleOnly = true;
                visibleOnly.order = Collections.<FeatureDataStore2.FeatureQueryParameters.Order>
                        singletonList(new FeatureDataStore2.FeatureQueryParameters.Order.ID());
                layer = new FeatureLayer3(TITLE, store, visibleOnly);
                overlay = new FeatureDataStoreMapOverlay(mapView.getContext(), store, null,
                        TITLE, "android.resource://" + pluginContext.getPackageName() + "/"
                                + com.atakmap.android.lces.plugin.R.drawable.ic_toolbar,
                        new Query(layer), null, null);
            }
        } catch (Exception e) {
            Log.e(TAG, "the lessons store would not open", e);
            registered.countDown();
            return;
        }
        main.post(new Runnable() {
            @Override
            public void run() {
                register();
            }
        });
    }

    private void register() {
        try {
            synchronized (lock) {
                if (detached || layer == null)
                    return;
                final MapOverlayManager mgr = mapView.getMapOverlayManager();
                final MapOverlay old = mgr.getOverlay(overlay.getIdentifier());
                if (old != null)
                    mgr.removeOverlay(old);
                final boolean added = mgr.addOverlay(overlay);
                // A reinstall does not reliably run the old instance's detach, and its
                // layer keeps drawing: take out any layer of our name before adding ours.
                int swept = 0;
                for (Layer other : new ArrayList<>(
                        mapView.getLayers(MapView.RenderStack.VECTOR_OVERLAYS))) {
                    if (other != layer && TITLE.equals(other.getName())) {
                        mapView.removeLayer(MapView.RenderStack.VECTOR_OVERLAYS, other);
                        swept++;
                    }
                }
                mapView.addLayer(MapView.RenderStack.VECTOR_OVERLAYS, layer);
                Log.d(TAG, "overlay registered: added=" + added + " findable="
                        + (mgr.getOverlay(overlay.getIdentifier()) != null)
                        + " swept=" + swept);
            }
        } catch (Exception e) {
            Log.e(TAG, "could not put the lessons layer on the map", e);
        } finally {
            registered.countDown();
        }
    }

    /** Main thread, from the plugin's stop. The store stays open on purpose. */
    void detach() {
        detached = true;
        synchronized (lock) {
            try {
                if (overlay != null)
                    mapView.getMapOverlayManager().removeOverlay(overlay);
                if (layer != null)
                    mapView.removeLayer(MapView.RenderStack.VECTOR_OVERLAYS, layer);
            } catch (Exception e) {
                Log.w(TAG, "detach failed", e);
            }
            overlay = null;
            layer = null;
            // Not store.dispose(): see the class comment.
        }
    }

    private boolean awaitReady() {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            Log.w(TAG, "store write asked for on the main thread, skipped",
                    new IllegalStateException("LessonsOverlay is worker-only"));
            return false;
        }
        try {
            registered.await(20, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
        return store != null && !detached;
    }

    /**
     * Lessons ON / OFF. The layer goes at once; the sets follow on this worker, and
     * the layer comes back so the renderer re-reads it and drops the hidden sets'
     * labels too (IPAWS {@code AlertOverlay.setVisible}).
     */
    void setVisible(boolean on) {
        visible = on;
        final FeatureLayer3 l = layer;
        if (!on && l != null) {
            main.post(new Runnable() {
                @Override
                public void run() {
                    l.setVisible(false);
                }
            });
        }
        if (!awaitReady())
            return;
        synchronized (lock) {
            if (store == null)
                return;
            try {
                store.setFeatureSetsVisible(new FeatureDataStore2.FeatureSetQueryParameters(),
                        visible);
            } catch (Exception e) {
                Log.w(TAG, "set visibility failed", e);
            }
        }
        main.post(new Runnable() {
            @Override
            public void run() {
                final FeatureLayer3 shown = layer;
                if (shown != null)
                    shown.setVisible(true);
            }
        });
    }

    /** Worker: replace what is drawn with exactly these incidents. */
    void rewrite(List<Incident> drawn) {
        if (!awaitReady())
            return;
        final Map<Incident.Type, Style> styles = new EnumMap<>(Incident.Type.class);
        for (Incident.Type t : Incident.Type.values()) {
            styles.put(t, new CompositeStyle(new Style[] {
                    // Icon just below the point (its top edge on it), label centered
                    // above: the pairing that keeps the label clear of the icon. The
                    // renderer reads align by sign only (Feature Layer, 5.8).
                    new IconPointStyle(0xFFFFFFFF, LessonIcons.uri(t), LessonIcons.SIZE_DP,
                            LessonIcons.SIZE_DP, 0, 1, 0f, false),
                    new LabelPointStyle("", 0xFFFFFFFF, 0xB0000000,
                            LabelPointStyle.ScrollMode.OFF, 0f, 0, -1, 0f, false,
                            LABEL_MAX_RESOLUTION)}));
        }
        synchronized (lock) {
            if (store == null)
                return;
            boolean locked = false;
            try {
                store.acquireModifyLock(true);
                locked = true;
                final List<Long> old = existingSets();
                final Map<Incident.Type, Long> sets = new EnumMap<>(Incident.Type.class);
                for (Incident i : drawn) {
                    Long fsid = sets.get(i.type);
                    if (fsid == null) {
                        // Double.MAX_VALUE, 0: no resolution gate (minResolution is the
                        // coarsest the set draws at; 0 would mean never).
                        fsid = store.insertFeatureSet(new FeatureSet("LCES", "lessons",
                                setName(i.type), Double.MAX_VALUE, 0d));
                        store.setFeatureSetVisible(fsid, visible);
                        sets.put(i.type, fsid);
                    }
                    final AttributeSet a = new AttributeSet();
                    a.setAttribute("id", i.id);
                    store.insertFeature(new Feature(fsid, i.name, new Point(i.lon, i.lat),
                            styles.get(i.type), a, Feature.AltitudeMode.ClampToGround, 0d));
                }
                for (Long id : old) {
                    try {
                        store.deleteFeatureSet(id);
                    } catch (Exception e) {
                        Log.w(TAG, "old set " + id, e);
                    }
                }
                Log.d(TAG, "drew " + drawn.size() + " incidents in " + sets.size() + " sets on "
                        + Thread.currentThread().getName());
            } catch (Exception e) {
                Log.w(TAG, "rewrite failed", e);
            } finally {
                if (locked)
                    store.releaseModifyLock();
            }
        }
    }

    /** Overlay Manager's row for a kind: "Fatalities", "Burnovers" ... */
    static String setName(Incident.Type t) {
        switch (t) {
            case FATALITY:
                return "Fatalities";
            case BURNOVER:
                return "Burnovers";
            case ENTRAPMENT:
                return "Entrapments";
            case INJURY:
                return "Injuries";
            default:
                return "Near misses";
        }
    }

    private List<Long> existingSets() {
        final List<Long> out = new ArrayList<>();
        com.atakmap.map.layer.feature.FeatureSetCursor c = null;
        try {
            c = store.queryFeatureSets(new FeatureDataStore2.FeatureSetQueryParameters());
            while (c.moveToNext())
                out.add(c.get().getId());
        } catch (Exception e) {
            Log.w(TAG, "listing sets failed", e);
        } finally {
            if (c != null) {
                try {
                    c.close();
                } catch (Exception ignored) {
                    // Nothing useful to do with a cursor that will not close.
                }
            }
        }
        return out;
    }

    /**
     * One feature's incident id, by feature id. The hit-test hands {@code
     * featureToMapItem} a feature with no attributes, so they are fetched again.
     */
    private String incidentOf(long fid) {
        final FeatureDataStore2 s = store;
        if (s == null)
            return null;
        FeatureCursor c = null;
        try {
            final FeatureDataStore2.FeatureQueryParameters p =
                    new FeatureDataStore2.FeatureQueryParameters();
            p.ids = Collections.singleton(fid);
            p.ignoredFeatureProperties = FeatureDataStore2.PROPERTY_FEATURE_GEOMETRY
                    | FeatureDataStore2.PROPERTY_FEATURE_STYLE;
            p.limit = 1;
            c = s.queryFeatures(p);
            if (c.moveToNext()) {
                final AttributeSet a = c.get().getAttributes();
                if (a != null && a.containsAttribute("id"))
                    return a.getStringAttribute("id");
            }
        } catch (Exception e) {
            Log.w(TAG, "attributes of feature " + fid + " failed", e);
        } finally {
            if (c != null) {
                try {
                    c.close();
                } catch (Exception ignored) {
                    // Nothing useful to do with a cursor that will not close.
                }
            }
        }
        return null;
    }

    /** Marks every item with its incident, and hands back each incident once. */
    private final class Query extends FeatureDataStoreDeepMapItemQuery {
        Query(FeatureLayer3 layer) {
            super(layer);
        }

        @Override
        protected MapItem featureToMapItem(Feature feature) {
            final MapItem item = super.featureToMapItem(feature);
            if (item == null)
                return null;
            String id = null;
            final AttributeSet a = feature.getAttributes();
            try {
                if (a != null && a.containsAttribute("id"))
                    id = a.getStringAttribute("id");
            } catch (Exception ignored) {
                // Fetched by id below.
            }
            if (id == null)
                id = incidentOf(feature.getId());
            if (id != null)
                item.setMetaString(META_ID, id);
            item.setMetaLong("featureid", feature.getId());
            item.setMetaString("title", feature.getName());
            item.setMetaString("callsign", feature.getName());
            return item;
        }

        @Override
        public SortedSet<MapItem> deepHitTest(MapView view,
                com.atakmap.map.hittest.HitTestQueryParameters params,
                Map<com.atakmap.map.layer.Layer2,
                        java.util.Collection<com.atakmap.map.hittest.HitTestControl>> controls) {
            return dedupe(super.deepHitTest(view, params, controls));
        }

        @Override
        public SortedSet<MapItem> deepHitTestItems(int x, int y,
                com.atakmap.coremap.maps.coords.GeoPoint point, MapView view) {
            return dedupe(super.deepHitTestItems(x, y, point, view));
        }

        private SortedSet<MapItem> dedupe(SortedSet<MapItem> hits) {
            // Lessons OFF keeps the features; a tap must not find them.
            if (!visible && hits != null)
                return new TreeSet<>(hits.comparator());
            if (hits == null || hits.isEmpty())
                return hits;
            final Set<String> seen = new HashSet<>();
            final SortedSet<MapItem> out = new TreeSet<>(hits.comparator());
            for (MapItem m : hits) {
                final String key = m.getMetaString(META_ID, "f" + m.getMetaLong("featureid", -1));
                if (seen.add(key))
                    out.add(m);
            }
            return out;
        }
    }
}
