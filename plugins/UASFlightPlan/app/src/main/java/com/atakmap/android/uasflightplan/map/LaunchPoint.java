package com.atakmap.android.uasflightplan.map;

import android.content.Context;
import android.graphics.PointF;
import android.view.KeyEvent;
import android.view.View;

import com.atakmap.android.maps.DefaultMapGroup;
import com.atakmap.android.maps.MapEvent;
import com.atakmap.android.maps.MapEventDispatcher;
import com.atakmap.android.maps.MapGroup;
import com.atakmap.android.maps.MapView;
import com.atakmap.android.maps.Marker;
import com.atakmap.android.toolbar.widgets.TextContainer;
import com.atakmap.android.uasflightplan.plugin.R;
import com.atakmap.coremap.log.Log;
import com.atakmap.coremap.maps.assets.Icon;
import com.atakmap.coremap.maps.coords.GeoPoint;
import com.atakmap.coremap.maps.coords.GeoPointMetaData;

/**
 * The launch point on the map: one marker in a group of our own, and the one-tap
 * picker that places it.
 *
 * <p>The marker is a plugin-owned map item, not CoT. It is never persisted, so no
 * other EUD sees it and ATAK does not write it to its database; a launch site under
 * consideration is the pilot's business until they choose to share it.
 *
 * <p>Map-tap placement is the pattern MAST measured: push the dispatcher's
 * listeners, take {@code map_click} for one tap, pop them back. Back cancels. The
 * prompt bar is ATAK's own, so the pilot sees the same "tap the map" strip every
 * ATAK tool shows.
 */
public final class LaunchPoint {

    private static final String TAG = "UASLaunch";
    private static final String GROUP = "UAS Flight Plan";
    private static final String UID = "uasflightplan.launch";

    public interface Callback {
        void onPicked(GeoPoint point);

        void onPickCancelled();
    }

    private final MapView mapView;
    private final Context plugin;
    private final Callback callback;

    private MapGroup group;
    private Marker marker;
    private boolean picking;
    private MapEventDispatcher.MapEventDispatchListener tapListener;

    private final View.OnKeyListener backListener = new View.OnKeyListener() {
        @Override
        public boolean onKey(View v, int keyCode, KeyEvent event) {
            if (keyCode != KeyEvent.KEYCODE_BACK)
                return false;
            cancelPick();
            callback.onPickCancelled();
            return true;
        }
    };

    public LaunchPoint(MapView mapView, Context pluginContext, Callback callback) {
        this.mapView = mapView;
        this.plugin = pluginContext;
        this.callback = callback;
    }

    public boolean isPicking() {
        return picking;
    }

    /** Where the marker is, or null when there is none. */
    public GeoPoint getPoint() {
        return marker == null ? null : marker.getPoint();
    }

    /** Waits for one tap on the map and hands its position back. */
    public void startPick() {
        if (picking)
            cancelPick();
        final MapEventDispatcher d = mapView.getMapEventDispatcher();
        d.pushListeners();
        d.clearListeners(MapEvent.MAP_CLICK);
        d.clearListeners(MapEvent.MAP_LONG_PRESS);
        d.clearListeners(MapEvent.ITEM_CLICK);
        tapListener = new MapEventDispatcher.MapEventDispatchListener() {
            @Override
            public void onMapEvent(MapEvent event) {
                final PointF pf = event.getPointF();
                if (pf == null)
                    return;
                final GeoPointMetaData gp = mapView.inverseWithElevation(pf.x, pf.y);
                cancelPick();
                if (gp == null || gp.get() == null || !gp.get().isValid()) {
                    callback.onPickCancelled();
                    return;
                }
                // Latitude and longitude only. The ground under the point comes from
                // the elevation engine, not from whatever altitude the tap carried.
                callback.onPicked(new GeoPoint(gp.get().getLatitude(),
                        gp.get().getLongitude()));
            }
        };
        d.addMapEventListener(MapEvent.MAP_CLICK, tapListener);
        d.addMapEventListener(MapEvent.ITEM_CLICK, tapListener);
        mapView.addOnKeyListener(backListener);
        TextContainer.getInstance().displayPrompt(
                plugin.getString(R.string.prompt_tap_launch));
        picking = true;
    }

    /**
     * Stops waiting for a tap and gives ATAK its listeners back.
     *
     * <p>Not optional: while the pick is active the map is deaf to ATAK's own
     * handlers, and if nothing pops the listeners it stays that way until the plugin
     * is reloaded.
     */
    public void cancelPick() {
        if (!picking)
            return;
        picking = false;
        TextContainer.getInstance().closePrompt();
        final MapEventDispatcher d = mapView.getMapEventDispatcher();
        if (tapListener != null) {
            d.removeMapEventListener(MapEvent.MAP_CLICK, tapListener);
            d.removeMapEventListener(MapEvent.ITEM_CLICK, tapListener);
            tapListener = null;
        }
        d.popListeners();
        mapView.removeOnKeyListener(backListener);
    }

    /** Puts the marker at a point, creating it the first time. */
    public void place(GeoPoint p) {
        if (marker == null) {
            marker = new Marker(p, UID);
            marker.setType("b-m-p-s-m");
            marker.setTitle(plugin.getString(R.string.launch_marker));
            marker.setMetaString("callsign", plugin.getString(R.string.launch_marker));
            marker.setMetaBoolean("readiness", true);
            // Not saved, not sent: this is the pilot's working mark, not a report.
            marker.setMetaBoolean("archive", false);
            marker.setMetaBoolean("removable", false);
            marker.setMetaBoolean("editable", false);
            marker.setMovable(false);
            marker.setClickable(true);
            marker.setMetaBoolean("adapt_marker_icon", false);
            marker.setIcon(new Icon.Builder()
                    .setImageUri(Icon.STATE_DEFAULT, "android.resource://"
                            + plugin.getPackageName() + "/" + R.drawable.ic_launch)
                    .setAnchor(Icon.ANCHOR_CENTER, Icon.ANCHOR_CENTER)
                    .setColor(Icon.STATE_DEFAULT, 0xFFFFFFFF)
                    .build());
            group().addItem(marker);
        } else {
            marker.setPoint(p);
        }
        // No coordinates in the log: with "My position" this is the phone's own fix,
        // and ATAK's log-to-file lands logcat on shared storage.
        Log.d(TAG, "launch point placed");
    }

    /** Takes the marker off the map. */
    public void clear() {
        if (marker == null)
            return;
        if (marker.getGroup() != null)
            marker.removeFromGroup();
        marker = null;
    }

    /** Called when the plugin stops: nothing of ours may stay on the map. */
    public void dispose() {
        cancelPick();
        clear();
        if (group != null) {
            try {
                mapView.getRootGroup().removeGroup(group);
            } catch (RuntimeException e) {
                Log.w(TAG, "could not remove the map group", e);
            }
            group = null;
        }
    }

    private MapGroup group() {
        if (group != null)
            return group;
        MapGroup g = mapView.getRootGroup().findMapGroup(GROUP);
        if (g == null) {
            g = new DefaultMapGroup(GROUP);
            mapView.getRootGroup().addGroup(g);
        }
        group = g;
        return g;
    }
}
