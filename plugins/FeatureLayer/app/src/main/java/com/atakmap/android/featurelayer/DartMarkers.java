package com.atakmap.android.featurelayer;

import android.content.Context;

import com.atakmap.coremap.maps.assets.Icon;
import java.io.File;
import com.atakmap.android.maps.MapGroup;
import com.atakmap.android.maps.MapView;
import com.atakmap.android.maps.Marker;
import com.atakmap.android.menu.PluginMenuParser;
import com.atakmap.coremap.log.Log;
import com.atakmap.coremap.maps.coords.GeoPoint;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * DART vehicles and personnel drawn as ATAK markers instead of feature-store rows.
 *
 * <p>The reason is the callsign. A feature label and a marker label are not the same
 * thing and only one of them is reliable: both end up in {@code GLLabelManager}, but
 * {@code GLMarker2} calls {@code setPriority(id, Priority.Standard|High|Low)} on the
 * label it creates, and {@code LabelPointStyle} -- the only way a feature can ask for a
 * label -- has no priority argument at all. Neither does {@code FeatureLayer3.Options},
 * which exposes hints, nor {@code FeaturesLabelControl}, which exposes shape centers,
 * background and level of detail. So a feature label loses the collision pass and the
 * manager shortens it from the front to make room: measured on 2026-09-17, an isolated
 * callsign drew whole at 169 px for eleven characters while the tightest pair came back
 * at 125 px, turning CA-ANF-E327 into ANF-E327. The state and the unit are the front of
 * a callsign, which is exactly the part that was being thrown away.
 *
 * <p>Everything reachable from a feature style was tried first, so it is not tried again:
 * every {@code alignX}/{@code alignY} pair, all three {@code ScrollMode}s, passing the
 * text explicitly instead of letting the renderer substitute the name, clearing
 * {@code HINT_WEIGHTED_FLOAT}, clearing {@code labelHints} outright, {@code labelsEnabled}
 * (which removes every label on the layer, ours included), and a 4x wider transparent
 * icon box. None of them moved it.
 *
 * <p>A marker gets the same treatment as every other callsign on the screen, which is
 * what the operator asked for and is not worth reimplementing: ATAK's own placement,
 * typeface and text size, so DART reads as part of the map rather than as a plugin.
 * {@link Marker.LabelPriority#High} keeps it whole, and
 * {@link Marker#TEXT_STATE_ALWAYS_SHOW} keeps it drawn past
 * {@code maxLabelRenderResolution}, which defaults to 10 m/px and had the callsigns
 * disappearing when zoomed out.
 *
 * <p>The feature row still exists. It is written with a set gate that never draws, so it
 * carries the details pane, the search pane and the counts without putting a second disc
 * under every marker; the marker is what a finger finds, and it carries the same metadata
 * {@code FeatureDataStoreDeepMapItemQuery} puts on a feature's map item so the radial
 * needs no special case.
 *
 * <p>These markers are never CoT. DART is NIFC's own positions of its people, shared
 * inside that org, and a published CoT lands on every phone on the server -- so
 * {@code nevercot} is set on each one and nothing here ever calls a send.
 */
final class DartMarkers {

    private static final String TAG = "FeatureLayer";

    /** One DART vehicle or person: where it is, what it says, and the row behind it. */
    static final class Row {
        final String uid;
        final String callsign;
        final String iconUri;
        final long featureId;
        final double lat, lon;

        Row(String uid, String callsign, String iconUri, long featureId, double lat, double lon) {
            this.uid = uid;
            this.callsign = callsign;
            this.iconUri = iconUri;
            this.featureId = featureId;
            this.lat = lat;
            this.lon = lon;
        }
    }

    private final MapView mapView;
    private final Context pluginContext;
    private final String layerId;
    private final String groupName;
    private final Map<String, Marker> live = new HashMap<>();
    private final Map<String, Icon> icons = java.util.Collections.synchronizedMap(new HashMap<String, Icon>());
    private final File iconDir;
    /** The disc a row falls back to when its own glyph could not be composed. */
    private final String fallbackUri;
    private MapGroup group;
    private boolean visible = true;
    /** Callsigns from this resolution and closer; MAX_VALUE is always. */
    private volatile double labelGsd = Double.MAX_VALUE;
    /** Whether the callsigns are drawn right now, by the map's resolution against the level. */
    private volatile boolean labelsShown = true;
    /** The rows last applied, so a zoom across the level can redraw them the other way. */
    private volatile List<Row> lastRows = new ArrayList<>();
    private int swapGen;

    DartMarkers(MapView mapView, Context pluginContext, String layerId, String fallback, File iconDir) {
        this.mapView = mapView;
        this.iconDir = iconDir;
        this.pluginContext = pluginContext;
        this.layerId = layerId;
        this.groupName = "FeatureLayer DART " + layerId;
        this.fallbackUri = fallback;
    }

    /**
     * Replaces the labels on the map with exactly this set, on the main thread.
     *
     * <p>Markers are matched by uid and moved rather than recreated: a marker that is
     * removed and re-added loses its label's place in the manager and flickers on every
     * one-minute refresh.
     */
    void setLabelGsd(double metersPerPixel) {
        labelGsd = metersPerPixel;
        mapView.post(new Runnable() {
            @Override
            public void run() {
                onMapResolution(mapView.getMapResolution());
            }
        });
    }

    /**
     * Show or hide the callsigns for the map's resolution. Main thread. The other form's
     * bitmaps are composed off the main thread first, the way {@link #update} does it,
     * then the rows are applied again; a newer swap wins over one still composing.
     */
    void onMapResolution(double metersPerPixel) {
        final boolean show = metersPerPixel <= labelGsd;
        if (show == labelsShown)
            return;
        labelsShown = show;
        final List<Row> rows = lastRows;
        if (rows.isEmpty())
            return;
        final int gen = ++swapGen;
        new Thread(new Runnable() {
            @Override
            public void run() {
                for (Row r : rows)
                    icon(r.iconUri, show || DartStyles.sos(r.callsign) ? r.callsign : "");
                mapView.post(new Runnable() {
                    @Override
                    public void run() {
                        if (gen == swapGen)
                            applyOnMain(rows, show);
                    }
                });
            }
        }, "dart-labels-swap").start();
    }

    void update(final List<Row> rows) {
        final List<Row> copy = new ArrayList<>(rows);
        lastRows = copy;
        boolean show = labelsShown;
        try {
            show = mapView.getMapResolution() <= labelGsd;
            labelsShown = show;
        } catch (RuntimeException ignored) {
        }
        final boolean shown = show;
        // The bitmaps are composed here, on the refresh thread that called us, and only
        // attached on the main thread. After an ATAK restart at a wide view this composed
        // several hundred callsigns -- decode, draw, PNG-encode each -- inside the main
        // thread's marker update, and ATAK "struggled to start" (2026-09-17). Cached on
        // disk and in memory, so a callsign already seen costs nothing here.
        for (Row r : copy)
            icon(r.iconUri, shown ? r.callsign : "");
        mapView.post(new Runnable() {
            @Override
            public void run() {
                applyOnMain(copy, shown);
            }
        });
    }

    private void applyOnMain(List<Row> rows, boolean show) {
        try {
            final MapGroup g = group();
            final Set<String> keep = new HashSet<>();
            for (Row r : rows) {
                // An empty callsign is the layer's Labels switch being off, not a reason to
                // drop the vehicle: the marker still has to draw the disc.
                keep.add(r.uid);
                final GeoPoint p = new GeoPoint(r.lat, r.lon);
                Marker m = live.get(r.uid);
                // A marker that exists but never got an icon is rebuilt from scratch rather
                // than patched: a Marker that started iconless kept drawing the reference
                // dot after setIcon on the XCover (2026-09-18).
                if (m != null && m.getMetaString("dart_icon", "").isEmpty() && icon(r.iconUri, show || DartStyles.sos(r.callsign) ? r.callsign : "") != null) {
                    live.remove(r.uid);
                    m.removeFromGroup();
                    m = null;
                }
                if (m == null) {
                    m = create(r, p, show);
                    live.put(r.uid, m);
                    g.addItem(m);
                } else {
                    m.setPoint(p);
                    if (r.callsign != null && !r.callsign.equals(m.getTitle())) {
                        m.setTitle(r.callsign);
                        m.setMetaString("callsign", r.callsign);
                        m.setMetaString("title", r.callsign);
                    }
                    m.setMetaLong("featureid", r.featureId);
                    // A reused marker keeps its Icon unless told otherwise. When the
                    // composite changes -- a new MARK_V, or a vehicle changing kind -- the
                    // uri changes, and a marker left holding the old file drew the previous
                    // build's image for as long as it lived (2026-09-17).
                    final String had = m.getMetaString("dart_icon", "");
                    final String want = iconKey(r, show);
                    if (!had.equals(want)) {
                        final Icon icon = icon(r.iconUri, show || DartStyles.sos(r.callsign) ? r.callsign : "");
                        if (icon != null) {
                            m.setIcon(icon);
                            m.setMetaString("dart_icon", want);
                        }
                    }
                }
                m.setVisible(visible);
            }
            for (String uid : new ArrayList<>(live.keySet())) {
                if (keep.contains(uid))
                    continue;
                final Marker gone = live.remove(uid);
                if (gone != null)
                    gone.removeFromGroup();
            }
        } catch (Exception e) {
            Log.w(TAG, "DART labels update failed", e);
        }
    }

    private Marker create(Row r, GeoPoint p, boolean show) {
        final Marker m = new Marker(p, r.uid);
        m.setTitle(r.callsign == null ? "" : r.callsign);
        m.setMetaString("callsign", r.callsign);
        m.setMetaString("title", r.callsign);
        // High, not Standard: Standard is what an ordinary marker gets and is still
        // shortened when labels crowd. A callsign that has lost its state and unit is not
        // the resource anyone asked for.
        m.setLabelPriority(Marker.LabelPriority.High);
        // The callsign is pixels in the icon (DartStyles.labelled); the engine's own label
        // is off so it cannot draw a second, trimmed copy.
        m.setTextRenderFlag(Marker.TEXT_STATE_NEVER_SHOW);
        final Icon icon = icon(r.iconUri, show || DartStyles.sos(r.callsign) ? r.callsign : "");
        if (icon != null) {
            m.setIcon(icon);
            m.setIconVisibility(Marker.ICON_VISIBLE);
            m.setMetaString("dart_icon", iconKey(r, show));
        }
        // The tap target: the feature behind this marker is never on the render stack, so
        // this is what a finger finds. The metadata is the same set
        // FeatureDataStoreDeepMapItemQuery puts on a feature's map item, so the radial and
        // the details pane work off it unchanged.
        m.setMetaString("menu", PluginMenuParser.getMenu(pluginContext, "menu/feature.xml"));
        // Never set an "iconUri" meta on a Marker. ATAK treats it as an icon source and it
        // replaced the labelled icon with its white reference dot, no callsign, on every
        // vehicle (2026-09-18 07:54). The chooser shows the marker's own icon; the bare
        // symbol for the chooser is a feature-item thing (LoadedLayer.featureToMapItem).
        m.setMetaLong("featureid", r.featureId);
        m.setMetaString("nifs_layer", layerId);
        m.setClickable(true);
        m.setMovable(false);
        // DART is NIFC's own positions of its people. A published CoT lands on every phone
        // on the server, so this never becomes one.
        m.setMetaBoolean("nevercot", true);
        m.setMetaBoolean("addToObjList", false);
        m.setMetaBoolean("removable", false);
        m.setMetaBoolean("editable", false);
        return m;
    }

    /**
     * The disc with its callsign drawn above it, as one marker icon: see
     * {@link DartStyles#labelled}. Built once per glyph and callsign.
     */
    private Icon icon(String uri, String callsign) {
        // Never leave a DART point without a disc: with no icon ATAK draws its own default
        // green dot, which says nothing and does not read as one of ours.
        if (uri == null || uri.isEmpty())
            uri = fallbackUri;
        if (uri == null || uri.isEmpty())
            return null;
        final String cs = callsign == null ? "" : callsign;
        final String key = uri + "|" + cs;
        Icon i = icons.get(key);
        if (i != null)
            return i;
        if (cs.isEmpty()) {
            // No callsign to draw: the disc alone, at the size the composite draws it, so a
            // zoom across the label level changes nothing but the pill. The file carries its
            // padding below the disc; anchor on the disc's center.
            final int px = Math.round(DartStyles.markerPx());
            i = new Icon.Builder().setImageUri(Icon.STATE_DEFAULT, uri)
                    .setSize(px, Math.round(DartStyles.markerPxH()))
                    .setAnchor(px / 2, px / 2)
                    .build();
            icons.put(key, i);
            return i;
        }
        try {
            final float scale = gov.tak.api.commons.graphics.DisplaySettings.getRelativeScaling();
            // At ATAK start-up the plugin can be asked to draw before the map's default
            // text format exists; with no fallback icon() returned null and the markers
            // were created iconless. ATAK's own default is 14 at the display scaling.
            final com.atakmap.android.maps.MapTextFormat tf = MapView.getDefaultTextFormat();
            final android.graphics.Typeface face = tf == null || tf.getTypeface() == null
                    ? android.graphics.Typeface.DEFAULT : tf.getTypeface();
            float textPx = tf == null ? 0f : tf.getDensityAdjustedFontSize();
            if (textPx <= 0f)
                textPx = (tf == null ? 14f : tf.getFontSize()) * scale;
            final int[] anchor = new int[4]; // ax, ay, w, h
            final File f = DartStyles.labelled(uri, cs, face, textPx, scale, iconDir, anchor);
            if (f == null)
                return null;
            // Composed at device px; ask for it back at the same px by dividing by ATAK's
            // dp scaling, so nothing is resampled. Anchor on the disc's center.
            i = new Icon.Builder()
                    .setImageUri(Icon.STATE_DEFAULT, "file://" + f.getAbsolutePath())
                    .setSize(Math.round(anchor[2] / scale), Math.round(anchor[3] / scale))
                    .setAnchor(Math.round(anchor[0] / scale), Math.round(anchor[1] / scale))
                    .build();
            icons.put(key, i);
            return i;
        } catch (Exception e) {
            Log.w(TAG, "DART icon " + uri, e);
            return null;
        }
    }

    private static String iconKey(Row r, boolean show) {
        return (r.iconUri == null ? "" : r.iconUri) + "|"
                + (r.callsign == null || !(show || DartStyles.sos(r.callsign)) ? "" : r.callsign);
    }

    private MapGroup group() {
        if (group == null) {
            // One-off: a diagnostic marker from 2026-09-17 was added straight to the root
            // group by an earlier build and outlives that build's unload. Remove it if seen.
            try {
                final com.atakmap.android.maps.MapItem stale = mapView.getRootGroup().deepFindUID("dart-diag-bare");
                if (stale != null)
                    stale.removeFromGroup();
            } catch (Exception ignored) {
            }
            final MapGroup root = mapView.getRootGroup();
            MapGroup g = root.findMapGroup(groupName);
            if (g == null) {
                g = root.addGroup(groupName);
            } else {
                // The group outlives the plugin instance that made it: a reinstall
                // unloads the old instance, whose dispose() is a posted Runnable that
                // may never run once its context is gone, and the new instance then
                // finds the old group still full. Its markers are stale copies of ours
                // at the same positions, drawn underneath, which is what kept biting a
                // disc-shaped hole out of every callsign even with our own icon gone
                // (2026-09-17). Start from an empty group.
                for (com.atakmap.android.maps.MapItem it : new ArrayList<>(g.getItems()))
                    try {
                        g.removeItem(it);
                    } catch (Exception ignored) {
                    }
            }
            g.setMetaBoolean("addToObjList", false);
            group = g;
        }
        return group;
    }

    void setVisible(final boolean v) {
        visible = v;
        mapView.post(new Runnable() {
            @Override
            public void run() {
                for (Marker m : live.values())
                    m.setVisible(v);
            }
        });
    }

    /** Takes every label off the map; safe to call when nothing was ever added. */
    void dispose() {
        mapView.post(new Runnable() {
            @Override
            public void run() {
                for (Marker m : live.values()) {
                    try {
                        m.removeFromGroup();
                    } catch (Exception ignored) {
                    }
                }
                live.clear();
                if (group != null) {
                    try {
                        mapView.getRootGroup().removeGroup(group);
                    } catch (Exception ignored) {
                    }
                    group = null;
                }
            }
        });
    }
}
