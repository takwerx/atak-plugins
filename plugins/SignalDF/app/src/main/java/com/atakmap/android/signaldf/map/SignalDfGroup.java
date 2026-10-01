package com.atakmap.android.signaldf.map;

import com.atakmap.android.maps.MapGroup;
import com.atakmap.android.maps.MapView;
import com.atakmap.android.overlay.DefaultMapGroupOverlay;
import com.atakmap.android.overlay.MapOverlay;

/**
 * The one map group everything this plugin draws lives in, and its entry in
 * ATAK's Overlay Manager.
 *
 * <p>Adding a group under the root gets items onto the map and <b>does not</b>
 * get them into Overlay Manager: that needs a {@link MapOverlay} registered
 * separately. Without it a plugin's lines and markers are on the operator's
 * map with no row to switch them off, no way to see how many there are, and
 * nothing in the list to tell them where the magenta thing came from. Signal
 * DF drew a bearing that way through 0.1 and the gap only showed up when
 * somebody went looking for the fix marker in the list and found no Signal DF
 * row at all.
 *
 * <p>Registered once, from whichever layer needs the group first, and taken
 * out on plugin stop so a reload does not stack a second row on the first.
 * The group itself is deliberately <em>not</em> removed on dispose: ATAK's
 * hit-testing walks the root group's children, and pulling a group out from
 * under live map items is how a plugin ends up with markers that cannot be
 * tapped.
 */
public final class SignalDfGroup {

    /** What the operator sees in Overlay Manager. */
    public static final String NAME = "Signal DF";

    private static MapOverlay overlay;

    private SignalDfGroup() {
    }

    /** The group, creating and registering it the first time. */
    public static synchronized MapGroup get(MapView mapView) {
        MapGroup root = mapView.getRootGroup();
        MapGroup group = root.findMapGroup(NAME);
        if (group == null)
            group = root.addGroup(NAME);
        if (overlay == null) {
            // The group has to look like something worth listing before the
            // overlay is built: ATAK's own groups carry these, and a group
            // without them is drawn on the map and left out of the list.
            group.setMetaBoolean("addToObjList", true);
            group.setMetaBoolean("permaGroup", true);
            // The plugin's own glyph on the row. The authority is the
            // PLUGIN's package: the icon lives in the plugin APK and ATAK's
            // own package cannot resolve it, which IPAWS found out by getting
            // a blank row beside every other overlay's icon.
            group.setMetaString("iconUri", "android.resource://"
                    + com.atakmap.android.signaldf.plugin.BuildConfig.APPLICATION_ID
                    + "/" + com.atakmap.android.signaldf.plugin.R.drawable.ic_toolbar);
            overlay = new DefaultMapGroupOverlay(mapView, group);
            // The add reports whether it took, so ask rather than assume --
            // the symptom of getting this wrong is an absence from a list,
            // which looks like nothing at all. IPAWS learned this the hard
            // way with addFilesOverlay.
            boolean added = mapView.getMapOverlayManager().addOverlay(overlay);
            String id = overlay.getIdentifier();
            boolean listed = mapView.getMapOverlayManager()
                    .getOverlay(id) != null;
            com.atakmap.coremap.log.Log.d("SignalDF.Group",
                    "overlay registration: added=" + added + " id='" + id
                            + "' findable=" + listed);
            // Overlay Manager builds its list once and does not notice a row
            // that arrives afterwards. Registering succeeds, the manager can
            // find the overlay by id, and the list still has no Signal DF in
            // it -- which reads as "ATAK does not show plugin overlays" and
            // is really just a stale list. This is the nudge ATAK sends
            // itself when its own overlays change.
            com.atakmap.android.ipc.AtakBroadcast.getInstance().sendBroadcast(
                    new android.content.Intent(
                            "com.atakmap.android.maps.REFRESH_HIERARCHY"));
        }
        return group;
    }

    /** Take the Overlay Manager row out; leave the group where it is. */
    public static synchronized void unregister(MapView mapView) {
        if (overlay == null)
            return;
        try {
            mapView.getMapOverlayManager().removeOverlay(overlay);
        } catch (Exception e) {
            com.atakmap.coremap.log.Log.w("SignalDF.Group",
                    "overlay was already gone", e);
        }
        overlay = null;
    }
}
