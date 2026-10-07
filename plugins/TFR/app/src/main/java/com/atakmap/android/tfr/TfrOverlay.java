package com.atakmap.android.tfr;

import android.content.Context;

import com.atakmap.android.features.FeatureDataStoreDeepMapItemQuery;
import com.atakmap.android.features.FeatureDataStoreMapOverlay;
import com.atakmap.android.maps.MapItem;
import com.atakmap.android.maps.MapView;
import com.atakmap.android.menu.PluginMenuParser;
import com.atakmap.coremap.log.Log;
import com.atakmap.map.layer.feature.AttributeSet;
import com.atakmap.map.layer.feature.Feature;
import com.atakmap.map.layer.feature.FeatureCursor;
import com.atakmap.map.layer.feature.FeatureDataStore2;
import com.atakmap.map.layer.feature.FeatureLayer3;
import com.atakmap.map.layer.feature.FeatureSet;
import com.atakmap.map.layer.feature.FeatureSetCursor;
import com.atakmap.map.layer.feature.datastore.FeatureSetDatabase2;
import com.atakmap.map.layer.feature.geometry.Geometry;
import com.atakmap.map.layer.feature.style.Style;

import java.io.File;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

/**
 * The TFR areas on the map: one feature store, one layer, one row in Overlay Manager.
 *
 * <p>A feature layer and never {@code DrawingShape}, because a TFR is <b>somebody else's
 * GIS data</b> (operator, 2026-09-23). A map item is a thing the operator owns: a radial
 * with color, rename, delete and send-to, a row in the object list, and ATAK saves it as
 * one of their own drawings and restores it at the next start. None of that is right for
 * a restriction published by the FAA -- you should not be able to recolor an airspace
 * closure, and last week's TFR coming back as a drawing is the Atmosphere storm-cone bug
 * with worse consequences.
 *
 * <p><b>One feature set per TFR type</b>, so Overlay Manager lists Hazards, Security and
 * the rest as separate switches, and so the pane's type filter is set visibility rather
 * than deletion. Switching a type off hides it and keeps it: it has to come back after a
 * restart with no network (CLAUDE.md, "Downloaded data survives a restart with no
 * network"), and TFRs are named in that rule.
 */
public class TfrOverlay {

    private static final String TAG = "TfrOverlay";
    private static final String PROVIDER = "TFR";
    private static final String TYPE = "tfr";

    private final MapView mapView;
    private final Context pluginContext;
    private final File storeFile;
    private final String title;
    private final Object lock = new Object();

    private FeatureSetDatabase2 store;
    private FeatureLayer3 layer;
    private FeatureDataStoreMapOverlay overlay;
    private int count;

    /** The master switch. Read by every set written, so a rewrite cannot undo it. */
    private volatile boolean visible;
    /** Types the operator has switched off. Hidden, never deleted. */
    private volatile Set<String> typesOff = Collections.emptySet();
    /** Raw type to set id, from the last rewrite, so a type can be toggled without one. */
    private final Map<String, Long> setIds = new HashMap<>();
    /** Raw type to set id as last read off disk, before any rewrite has run. */
    private final Map<Long, String> setKeyById = new HashMap<>();

    /**
     * Its own thread: a visibility write waits behind any rewrite in progress, and the
     * switch has to act now rather than after the next sync finishes writing.
     */
    private final ExecutorService settings = Executors.newSingleThreadExecutor(
            new ThreadFactory() {
                @Override
                public Thread newThread(Runnable r) {
                    final Thread t = new Thread(r, "tfr-store-settings");
                    t.setDaemon(true);
                    return t;
                }
            });

    /** One area to draw. */
    public static class Drawn {
        /**
         * Which set this belongs to, and what the filter is keyed on: the FAA's own raw
         * type string, never the label. Two raw types can render to one label -- a missing
         * type and a literal "OTHER" both read "Other" -- and if they shared a set,
         * switching one off would take the other off the map while the list still showed
         * it. That is the "missing from the map reads as clear airspace" failure, in the
         * one place the list and the map are supposed to follow a single rule.
         */
        public final String setKey;
        /** What Overlay Manager shows for the set. Display only. */
        public final String setName;
        public final String name;
        public final Geometry geometry;
        public final Style style;
        public final AttributeSet attrs;

        public Drawn(String setKey, String setName, String name, Geometry geometry,
                Style style, AttributeSet attrs) {
            this.setKey = setKey;
            this.setName = setName;
            this.name = name;
            this.geometry = geometry;
            this.style = style;
            this.attrs = attrs;
        }
    }

    public TfrOverlay(MapView mapView, Context pluginContext, File storeFile, String title) {
        this.mapView = mapView;
        this.pluginContext = pluginContext;
        this.storeFile = storeFile;
        this.title = title;
    }

    public void attach(boolean startVisible, Set<String> off) throws Exception {
        synchronized (lock) {
            visible = startVisible;
            typesOff = off == null ? Collections.<String> emptySet() : new HashSet<>(off);
            File parent = storeFile.getParentFile();
            if (parent != null && !parent.isDirectory() && !parent.mkdirs())
                Log.w(TAG, "could not make the store directory");
            store = new FeatureSetDatabase2(storeFile);
            // Visible features only. With the plain constructor the renderer keeps
            // drawing the labels of sets the operator switched off, over an empty map.
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
                            // Our own radial. Blanking the menu would remove the only
                            // route to the details pane; leaving ATAK's shows its own
                            // feature metadata screen instead of ours.
                            item.setMetaString("menu", PluginMenuParser.getMenu(
                                    pluginContext, "menu/tfr_shape.xml"));
                            AttributeSet a = feature.getAttributes();
                            // Null on a tap: the hit-test query asks the store to skip
                            // attributes, so they have to be fetched by id.
                            if (a == null)
                                a = attributesOf(feature.getId());
                            String label = feature.getName();
                            if (a != null) {
                                final String notam = string(a, "notam_id");
                                if (notam != null)
                                    item.setMetaString("tfr_notam_id", notam);
                                final String place = string(a, "place");
                                if (place != null && !place.isEmpty())
                                    label = place;
                            }
                            item.setMetaString("title", label);
                            item.setMetaString("callsign", label);
                            // Two overlapping TFRs give ATAK a chooser, and without an
                            // icon those rows come up blank.
                            item.setMetaString("iconUri", "asset://icons/details.png");
                            item.setMetaInteger("iconColor", 0xFFFFFFFF);
                            return item;
                        }

                        @Override
                        public SortedSet<MapItem> deepHitTest(MapView view,
                                com.atakmap.map.hittest.HitTestQueryParameters params,
                                Map<com.atakmap.map.layer.Layer2, Collection<com.atakmap.map.hittest.HitTestControl>> controls) {
                            return dedupe(super.deepHitTest(view, params, controls));
                        }

                        @Override
                        public SortedSet<MapItem> deepHitTestItems(int xpos, int ypos,
                                com.atakmap.coremap.maps.coords.GeoPoint point, MapView view) {
                            return dedupe(super.deepHitTestItems(xpos, ypos, point, view));
                        }

                        /**
                         * ATAK hands the same feature back once per hit-test control, and a
                         * TFR with an inner and an outer ring is two features of one
                         * restriction. The NOTAM is the key where there is one.
                         */
                        private SortedSet<MapItem> dedupe(SortedSet<MapItem> hits) {
                            if (hits == null || hits.isEmpty())
                                return hits;
                            // The map switched off keeps the features, so a tap must find
                            // nothing even before the store has caught up.
                            if (!visible)
                                return new TreeSet<>(hits.comparator());
                            final Set<String> seen = new HashSet<>();
                            final SortedSet<MapItem> out = new TreeSet<>(hits.comparator());
                            for (MapItem m : hits) {
                                final String notam = m.getMetaString("tfr_notam_id", null);
                                final String key = notam != null ? "n:" + notam
                                        : "f:" + m.getMetaLong("featureid", -1);
                                if (seen.add(key))
                                    out.add(m);
                            }
                            return out;
                        }
                    };

            // The plugin's own glyph on the Overlay Manager row: the icon is in the
            // plugin APK, so the authority has to be the plugin's package.
            overlay = new FeatureDataStoreMapOverlay(mapView.getContext(), store, null,
                    title, "android.resource://" + pluginContext.getPackageName() + "/"
                            + com.atakmap.android.tfr.plugin.R.drawable.ic_toolbar,
                    query, null, null);
            // addOverlay, not addFilesOverlay: the latter has never listed a plugin's
            // overlay in Overlay Manager. Ask whether it took rather than assume, because
            // the symptom of getting it wrong is an absence from a list.
            final boolean added = mapView.getMapOverlayManager().addOverlay(overlay);
            final boolean listed = mapView.getMapOverlayManager()
                    .getOverlay(overlay.getIdentifier()) != null;
            Log.d(TAG, "overlay registration: added=" + added + " findable=" + listed);
            mapView.addLayer(MapView.RenderStack.VECTOR_OVERLAYS, layer);
            readSetIds();
            count = countFeatures();
            Log.d(TAG, "attached with " + count + " areas from the last session");
        }
    }

    public void detach() {
        synchronized (lock) {
            try {
                if (overlay != null)
                    mapView.getMapOverlayManager().removeOverlay(overlay);
                if (layer != null)
                    mapView.removeLayer(MapView.RenderStack.VECTOR_OVERLAYS, layer);
            } catch (Exception e) {
                Log.w(TAG, "detach failed", e);
            }
            // The store is deliberately NOT disposed. Removing the layer does not stop a
            // query already running on the renderer's own worker thread; that thread calls
            // back into Java, a closed store throws DataStoreException, and JNI aborts the
            // whole ATAK process -- signal 6, no Java stack. ATAK owns the process and
            // closes the file on exit, and a leaked handle costs nothing beside taking the
            // host down on every plugin reload.
            layer = null;
            overlay = null;
            store = null;
            settings.shutdownNow();
        }
    }

    // ---- switches: hide, never delete ----

    /** The map switch. Hides every area at once and keeps all of them. */
    public void setVisible(final boolean on) {
        visible = on;
        applyVisibility();
    }

    /** The type filter. A type switched off is hidden, so it survives a restart. */
    public void setTypesOff(Set<String> off) {
        typesOff = off == null ? Collections.<String> emptySet() : new HashSet<>(off);
        applyVisibility();
    }

    /**
     * Push the switches into the store.
     *
     * <p>The layer is hidden first so the areas go on the next frame however busy the
     * store is, and shown again afterwards: a hidden layer is not re-read, so without
     * that second step ATAK keeps drawing the labels of features it is no longer
     * showing, indefinitely, over an empty map.
     */
    private void applyVisibility() {
        final FeatureLayer3 l = layer;
        if (l != null)
            l.setVisible(false);
        settings.execute(new Runnable() {
            @Override
            public void run() {
                synchronized (lock) {
                    if (store == null)
                        return;
                    try {
                        for (Map.Entry<String, Long> e : setIds.entrySet()) {
                            // The latest wish, not the one this task was queued with.
                            final boolean on = visible && !typesOff.contains(e.getKey());
                            store.setFeatureSetVisible(e.getValue(), on);
                        }
                    } catch (Exception e) {
                        Log.w(TAG, "applying visibility failed", e);
                    }
                }
                mapView.post(new Runnable() {
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

    // ---- writing ----

    /**
     * Replace everything on the map with this set. Worker thread only: it writes a
     * database, and one insert at a time on main has ATAK re-query the store per insert.
     *
     * <p>A failed sync must never call this. The difference between "there are no TFRs"
     * and "we could not ask" is only known by the caller, and an empty list honored here
     * would blank the map in both cases.
     */
    public void rewrite(List<Drawn> drawn) {
        synchronized (lock) {
            if (store == null)
                return;
            boolean locked = false;
            try {
                // One content-changed notification at the end instead of one per insert.
                store.acquireModifyLock(true);
                locked = true;
                final List<Long> old = existingSets();
                final Map<String, Long> fresh = new HashMap<>();
                for (Drawn d : drawn) {
                    Long fsid = fresh.get(d.setKey);
                    if (fsid == null) {
                        fsid = newSet(d.setKey, d.setName);
                        fresh.put(d.setKey, fsid);
                    }
                    store.insertFeature(new Feature(fsid, d.name, d.geometry, d.style,
                            d.attrs, Feature.AltitudeMode.ClampToGround, 0d));
                }
                for (Long id : old) {
                    try {
                        store.deleteFeatureSet(id);
                    } catch (Exception e) {
                        Log.w(TAG, "dropping an old set failed", e);
                    }
                }
                setIds.clear();
                setIds.putAll(fresh);
                count = countFeatures();
                Log.d(TAG, "rewritten: " + count + " areas in " + fresh.size() + " sets");
            } catch (Exception e) {
                Log.w(TAG, "rewrite failed", e);
            } finally {
                if (locked)
                    store.releaseModifyLock();
            }
        }
    }

    private long newSet(String key, String name) throws Exception {
        // Double.MAX_VALUE, 0d is the SDK's own idiom for "no resolution gate":
        // minResolution is the COARSEST resolution at which the set draws, so a huge
        // value means visible however far out you are. Passing 0d, which reads like "no
        // minimum", stores min_lod = max_lod = 2147483647 and the set draws at no zoom at
        // all -- inserts succeed, the log says it was written, and the map stays empty.
        // The raw type goes in the set's own type field, so what the filter is keyed on
        // survives a restart and can be read back off disk before the first rewrite.
        final long id = store.insertFeatureSet(
                new FeatureSet(PROVIDER, key, name, Double.MAX_VALUE, 0d));
        store.setFeatureSetVisible(id, visible && !typesOff.contains(key));
        setKeyById.put(id, key);
        return id;
    }

    // ---- reading ----

    /**
     * One feature's attributes by id. {@code feature.getAttributes()} is always null in
     * {@code featureToMapItem} on a tap, because the hit-test query tells the store to
     * skip them.
     */
    private AttributeSet attributesOf(long fid) {
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
            if (c.moveToNext())
                return c.get().getAttributes();
        } catch (Exception e) {
            Log.w(TAG, "reading attributes of " + fid + " failed", e);
        } finally {
            close(c);
        }
        return null;
    }

    private static String string(AttributeSet a, String key) {
        try {
            return a.getStringAttribute(key);
        } catch (Exception e) {
            return null;
        }
    }

    private void readSetIds() {
        setIds.clear();
        setKeyById.clear();
        FeatureSetCursor c = null;
        try {
            c = store.queryFeatureSets(new FeatureDataStore2.FeatureSetQueryParameters());
            while (c.moveToNext()) {
                // getType() is the raw FAA type this set was written with, which is what
                // the filter keys on. getName() is only what Overlay Manager shows.
                final String key = c.get().getType();
                setIds.put(key, c.get().getId());
                setKeyById.put(c.get().getId(), key);
            }
        } catch (Exception e) {
            Log.w(TAG, "listing sets failed", e);
        } finally {
            close(c);
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
            close(c);
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

    public int count() {
        return count;
    }

    private static void close(com.atakmap.database.RowIterator c) {
        if (c == null)
            return;
        try {
            c.close();
        } catch (Exception ignored) {
            // Nothing useful to do with a cursor that will not close.
        }
    }
}
