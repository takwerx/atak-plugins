package com.atakmap.android.ipaws.ui;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import com.atakmap.android.ipaws.AlertManager;
import com.atakmap.android.ipaws.data.Alert;
import com.atakmap.android.maps.MapItem;
import com.atakmap.android.maps.MapView;
import com.atakmap.coremap.log.Log;

/**
 * Tapping an alert on the map, into the same page the list opens.
 *
 * <p>The radial menu's details button broadcasts here with the map item's uid. The
 * item carries the alert's own id, put there when the feature was turned into a map
 * item, so the alert is found in the manager's current set rather than rebuilt from
 * the feature's attributes.
 *
 * <p>That is deliberate and it avoids a trap: the hit-test query asks the store for
 * features <b>without</b> their attributes, so reading them off the tapped item gives
 * an empty set. Feature Layer re-fetches the feature by id for exactly this reason;
 * here the alert is already in hand, which is simpler and cannot drift from the list.
 */
public class AlertDetailsReceiver extends BroadcastReceiver {

    private static final String TAG = "IPAWS";
    public static final String ACTION = "com.atakmap.android.ipaws.ALERT_DETAILS";

    private final MapView mapView;
    private final AlertManager manager;
    private final AlertDetails details;

    public AlertDetailsReceiver(MapView mapView, AlertManager manager, AlertDetails details) {
        this.mapView = mapView;
        this.manager = manager;
        this.details = details;
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        final String uid = intent.getStringExtra("targetUID");
        final MapItem item = uid == null ? null
                : mapView.getRootGroup().deepFindItem("uid", uid);
        if (item == null) {
            Log.d(TAG, "details: no map item for " + uid);
            return;
        }
        final String id = item.getMetaString("ipaws_alert_id", null);
        if (id == null) {
            Log.d(TAG, "details: map item carries no alert id");
            return;
        }
        for (Alert a : manager.snapshot())
            if (id.equals(a.id)) {
                details.show(a);
                return;
            }
        // The alert expired between the tap and the poll that dropped it. Saying so
        // beats opening a page about weather that is over.
        Log.d(TAG, "details: alert " + id + " is no longer current");
        android.widget.Toast.makeText(mapView.getContext(),
                "That alert has expired", android.widget.Toast.LENGTH_SHORT).show();
    }
}
