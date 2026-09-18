package com.atakmap.android.featurelayer;

import android.content.Context;

import com.atakmap.coremap.maps.assets.Icon;
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
    private final Map<String, Icon> icons = new HashMap<>();
    /** The disc a row falls back to when its own glyph could not be composed. */
    private final String fallbackUri;
    private MapGroup group;
    private boolean visible = true;

    DartMarkers(MapView mapView, Context pluginContext, String layerId, String fallback) {
        this.mapView = mapView;
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
    void update(final List<Row> rows) {
        final List<Row> copy = new ArrayList<>(rows);
        mapView.post(new Runnable() {
            @Override
            public void run() {
                applyOnMain(copy);
            }
        });
    }

    private void applyOnMain(List<Row> rows) {
        try {
            final MapGroup g = group();
            final Set<String> keep = new HashSet<>();
            for (Row r : rows) {
                // An empty callsign is the layer's Labels switch being off, not a reason to
                // drop the vehicle: the marker still has to draw the disc.
                keep.add(r.uid);
                final GeoPoint p = new GeoPoint(r.lat, r.lon);
                Marker m = live.get(r.uid);
                if (m == null) {
                    m = create(r, p);
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

    private Marker create(Row r, GeoPoint p) {
        final Marker m = new Marker(p, r.uid);
        m.setTitle(r.callsign == null ? "" : r.callsign);
        m.setMetaString("callsign", r.callsign);
        m.setMetaString("title", r.callsign);
        // High, not Standard: Standard is what an ordinary marker gets and is still
        // shortened when labels crowd. A callsign that has lost its state and unit is not
        // the resource anyone asked for.
        m.setLabelPriority(Marker.LabelPriority.High);
        // Without this the label obeys maxLabelRenderResolution, which defaults to
        // 10 m/px, so callsigns simply vanished when zoomed out past it.
        m.setTextRenderFlag(Marker.TEXT_STATE_ALWAYS_SHOW);
        final Icon icon = icon(r.iconUri);
        if (icon != null) {
            m.setIcon(icon);
            m.setIconVisibility(Marker.ICON_VISIBLE);
        }
        // The tap target: the feature behind this marker is written with a gate that never
        // draws, so this is what a finger finds. The metadata is the same set
        // FeatureDataStoreDeepMapItemQuery puts on a feature's map item, so the radial and
        // the details pane work off it unchanged.
        m.setMetaString("menu", PluginMenuParser.getMenu(pluginContext, "menu/feature.xml"));
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

    /** The disc the feature would have drawn, as a marker icon, built once per glyph. */
    private Icon icon(String uri) {
        // Never leave a DART point without a disc: with no icon ATAK draws its own default
        // green dot, which says nothing and does not read as one of ours.
        if (uri == null || uri.isEmpty())
            uri = fallbackUri;
        if (uri == null || uri.isEmpty())
            return null;
        Icon i = icons.get(uri);
        if (i != null)
            return i;
        try {
            // setSize, but never setAnchor. The composite is 96x96; with an anchor set
            // ATAK dropped the icon and substituted its own green default, and with no
            // size at all it drew the image at 96 px, three times the size of every other
            // marker on the map. Both were seen on the XCover on 2026-09-17.
            i = new Icon.Builder()
                    .setImageUri(Icon.STATE_DEFAULT, uri)
                    .setSize((int) DartStyles.markerPx(), (int) DartStyles.markerPx())
                    .build();
            icons.put(uri, i);
            return i;
        } catch (Exception e) {
            Log.w(TAG, "DART icon " + uri, e);
            return null;
        }
    }

    private MapGroup group() {
        if (group == null) {
            final MapGroup root = mapView.getRootGroup();
            MapGroup g = root.findMapGroup(groupName);
            if (g == null)
                g = root.addGroup(groupName);
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
