package com.atakmap.android.airaware;

import android.content.Context;

import com.atakmap.android.features.FeatureDataStoreDeepMapItemQuery;
import com.atakmap.android.features.FeatureDataStoreMapOverlay;
import com.atakmap.android.maps.MapItem;
import com.atakmap.android.maps.MapView;
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
    /** Appended to a type's key for the set holding that type's labels. */
    /**
     * Public so a caller can hide one kind's labels without hiding its areas: the set
     * key plus this is a set key in its own right, and {@code setTypesOff} now matches
     * the whole key as well as the type it belongs to.
     */
    public static final String LABEL_SUFFIX = " \u0000labels";
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
    /** NOTAM to the features drawn for it, so a place filter can hide without deleting. */
    private final Map<String, List<Long>> fidsByNotam = new HashMap<>();
    /** NOTAMs the place filter is letting through; null means no place filter at all. */
    private volatile Set<String> notamsShown;
    /** The label points, which the operator gates separately from the areas. */
    private final Set<Long> labelFids = new HashSet<>();
    private volatile boolean labelsOn = true;

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
        /** Which restriction this belongs to, so a place filter can hide all of its areas. */
        public final String notamId;
        public final String setKey;
        /** What Overlay Manager shows for the set. Display only. */
        public final String setName;
        public final String name;
        public final Geometry geometry;
        public final Style style;
        public final AttributeSet attrs;
        /** Absolute for an MSL block, Relative for one measured above the surface. */
        public final Feature.AltitudeMode altitudeMode;
        /** Wall height in meters; 0 for a flat ring on the ground. */
        public final double extrude;
        /** True for the point that carries the area's text, which has its own zoom gate. */
        public boolean isLabel;

        public Drawn(String notamId, String setKey, String setName, String name,
                Geometry geometry, Style style, AttributeSet attrs,
                Feature.AltitudeMode altitudeMode, double extrude) {
            this.notamId = notamId;
            this.setKey = setKey;
            this.setName = setName;
            this.name = name;
            this.geometry = geometry;
            this.style = style;
            this.attrs = attrs;
            this.altitudeMode = altitudeMode;
            this.extrude = extrude;
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
                            // No radial at all. The operator, 2026-10-07: "no radial menu
                            // when i click on it it opens up in the side pane like my
                            // other plugins are starting to do". A blank menu is what
                            // stops ATAK opening one -- on its own that makes a tap do
                            // nothing, so the plugin listens for the click itself and
                            // opens the details page.
                            // Not blanked any more. A blank menu stopped the radial but
                            // left ATAK selecting the item and leaving its own callout on
                            // the map; the plugin now answers ATAK's menu question with
                            // "handled" instead, which stops both. See AirAware.registerTap.
                            item.setMetaBoolean("airaware", true);
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
                                final String icao = string(a, "metar_icao");
                                if (icao != null)
                                    item.setMetaString("metar_icao", icao);
                                final String shelf = string(a, "airspace_id");
                                if (shelf != null)
                                    item.setMetaString("airspace_id", shelf);
                                final String oas = string(a, "obstacle_oas");
                                if (oas != null)
                                    item.setMetaString("obstacle_oas", oas);
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
                                final String icao = m.getMetaString("metar_icao", null);
                                // Airspace is three features per shelf -- the volume, the
                                // footprint under it and the label -- so without its own
                                // key the chooser listed Chino Class D three times.
                                final String shelf = m.getMetaString("airspace_id", null);
                                // Obstacles are three features each -- mast, glyph and
                                // pill -- so without this the chooser lists one tower
                                // three times, the same way airspace did.
                                final String oas = m.getMetaString("obstacle_oas", null);
                                final String key = notam != null ? "n:" + notam
                                        : icao != null ? "m:" + icao
                                                : shelf != null ? "a:" + shelf
                                                        : oas != null ? "o:" + oas
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
                            + com.atakmap.android.airaware.plugin.R.drawable.ic_toolbar,
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
    /**
     * Every one of these refuses to do anything when nothing has changed.
     *
     * <p>{@link #applyVisibility()} takes the whole layer off the map and puts it back a
     * moment later, which is the only way the renderer picks up a visibility change in
     * the store. That is one blink. The map settling called the setters again on every
     * pan and every zoom step with exactly the same answer as before, so the blink
     * happened continuously while the operator moved: "as i zoome in and it tries to
     * redraw lots of flashing, lables on off etc". Now a pan that changes nothing costs
     * nothing, and the blink is only paid when something really did change -- crossing a
     * zoom gate, or a switch being moved.
     */
    public void setVisible(final boolean on) {
        if (visible == on)
            return;
        visible = on;
        applyVisibility();
    }

    /** The type filter. A type switched off is hidden, so it survives a restart. */
    public void setTypesOff(Set<String> off) {
        final Set<String> want = off == null ? Collections.<String> emptySet()
                : new HashSet<>(off);
        if (want.equals(typesOff))
            return;
        typesOff = want;
        applyVisibility();
    }

    /**
     * The place filter: which NOTAMs a state, region or center pick is letting through.
     * Null means no place filter.
     *
     * <p>Per feature rather than per set, because the sets are per type and a place cuts
     * across them. Hidden either way -- never deleted -- so the picture is whole again the
     * moment the filter is widened, with no network.
     */
    public void setNotamsShown(Set<String> notams) {
        final Set<String> want = notams == null ? null : new HashSet<>(notams);
        if (want == null ? notamsShown == null : want.equals(notamsShown))
            return;
        notamsShown = want;
        applyVisibility();
    }

    /**
     * The label gate, which is its own thing: an operator wants to see where the
     * restrictions are from much further out than they want to read their names.
     */
    public void setLabelsVisible(boolean on) {
        if (labelsOn == on)
            return;
        labelsOn = on;
        applyVisibility();
    }

    public boolean labelsVisible() {
        return labelsOn;
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
                    pushVisibilityLocked();
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

    /**
     * Push the switches into the store, in two scoped writes.
     *
     * <p><b>Not</b> {@code setFeatureSetVisible(fsid, on)}, which looks like the obvious
     * call and is unusable: ATAK's {@code FDB2.setFeatureSetVisibleImpl} compiles
     * {@code "UPDATE featuresets SET visible = ?"} with <b>no WHERE clause</b> and ignores
     * the fsid it was handed, so every call rewrites every set in the store and the last
     * one in a loop wins. It updates its in-memory copy per set, so the map looks right
     * until ATAK restarts and the store is read back -- which is exactly when a switched
     * off type is supposed to come back switched off.
     *
     * <p>{@code setFeatureSetsVisible(params, on)} builds a real {@code WHERE id IN (...)}.
     * Two calls, one per answer, so a set is never left on the wrong side.
     *
     * <p>Caller holds {@link #lock}.
     */
    private void pushVisibilityLocked() {
        if (store == null)
            return;
        pushPlaceVisibilityLocked();
        final Set<Long> show = new HashSet<>();
        final Set<Long> hide = new HashSet<>();
        for (Map.Entry<String, Long> e : setIds.entrySet()) {
            final String key = e.getKey();
            final boolean isLabelSet = key.endsWith(LABEL_SUFFIX);
            final String type = isLabelSet
                    ? key.substring(0, key.length() - LABEL_SUFFIX.length()) : key;
            // The latest wish, not the one this task was queued with: two quick taps must
            // end where the button says. A label set answers to the label gate as well as
            // to its own type.
            // The whole key as well as the base type: hiding "as:B" hides Class B, and
            // hiding "as:B \u0000labels" hides only its names.
            if (visible && !typesOff.contains(type) && !typesOff.contains(key)
                    && (!isLabelSet || labelsOn))
                show.add(e.getValue());
            else
                hide.add(e.getValue());
        }
        try {
            if (!show.isEmpty()) {
                final FeatureDataStore2.FeatureSetQueryParameters p =
                        new FeatureDataStore2.FeatureSetQueryParameters();
                p.ids = show;
                store.setFeatureSetsVisible(p, true);
            }
            if (!hide.isEmpty()) {
                final FeatureDataStore2.FeatureSetQueryParameters p =
                        new FeatureDataStore2.FeatureSetQueryParameters();
                p.ids = hide;
                store.setFeatureSetsVisible(p, false);
            }
        } catch (Exception e) {
            Log.w(TAG, "applying visibility failed", e);
        }
    }

    /**
     * Hide or show whole restrictions for the place filter.
     *
     * <p>Two scoped writes again, for the same reason the set call needed them. Caller
     * holds {@link #lock}.
     */
    private void pushPlaceVisibilityLocked() {
        final Set<String> allow = notamsShown;
        if (fidsByNotam.isEmpty())
            return;
        final Set<Long> show = new HashSet<>();
        final Set<Long> hide = new HashSet<>();
        for (Map.Entry<String, List<Long>> e : fidsByNotam.entrySet()) {
            final boolean placeAllows = allow == null || allow.contains(e.getKey());
            for (Long fid : e.getValue()) {
                // One answer per feature from both gates, computed together: two passes
                // each writing features.visible would undo one another.
                // The label gate is a set-level thing now; this is the place filter only.
                final boolean on = placeAllows;
                (on ? show : hide).add(fid);
            }
        }
        try {
            if (!show.isEmpty()) {
                final FeatureDataStore2.FeatureQueryParameters p =
                        new FeatureDataStore2.FeatureQueryParameters();
                p.ids = show;
                store.setFeaturesVisible(p, true);
            }
            if (!hide.isEmpty()) {
                final FeatureDataStore2.FeatureQueryParameters p =
                        new FeatureDataStore2.FeatureQueryParameters();
                p.ids = hide;
                store.setFeaturesVisible(p, false);
            }
        } catch (Exception e) {
            Log.w(TAG, "applying the place filter failed", e);
        }
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
                fidsByNotam.clear();
                labelFids.clear();
                for (Drawn d : drawn) {
                    // Labels live in a set of their own, per type. Hiding a feature does
                    // not stop ATAK drawing its label -- measured: the label points read
                    // visible=0 in the store and the names stayed on the map a minute
                    // later, through the hide-layer-and-show-it-again dance that works for
                    // sets. Set visibility is the only lever that takes labels with it.
                    final String key = d.isLabel ? d.setKey + LABEL_SUFFIX : d.setKey;
                    final String name = d.isLabel ? d.setName + " labels" : d.setName;
                    Long fsid = fresh.get(key);
                    if (fsid == null) {
                        fsid = newSet(key, name);
                        fresh.put(key, fsid);
                    }
                    final long fid = store.insertFeature(new Feature(fsid, d.name,
                            d.geometry, d.style, d.attrs, d.altitudeMode, d.extrude));
                    if (d.isLabel)
                        labelFids.add(fid);
                    List<Long> ids = fidsByNotam.get(d.notamId);
                    if (ids == null) {
                        ids = new ArrayList<>(2);
                        fidsByNotam.put(d.notamId, ids);
                    }
                    ids.add(fid);
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
                // Once, after every set exists, rather than per set as they are made.
                pushVisibilityLocked();
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
