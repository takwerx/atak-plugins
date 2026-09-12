package com.atakmap.android.featurelayer;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.widget.Toast;

import com.atakmap.android.maps.MapItem;
import com.atakmap.android.maps.MapView;
import com.atakmap.android.maps.Marker;
import com.atakmap.android.user.PlacePointTool;
import com.atakmap.coremap.log.Log;
import com.atakmap.coremap.maps.coords.GeoPoint;
import com.atakmap.map.layer.feature.AttributeSet;
import com.atakmap.map.layer.feature.Feature;
import com.atakmap.map.layer.feature.FeatureDataStore2;
import com.atakmap.map.layer.feature.Utils;
import com.atakmap.map.layer.feature.geometry.Envelope;
import com.atakmap.map.layer.feature.geometry.Geometry;
import com.atakmap.map.layer.feature.geometry.Point;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * "Marker here": a real ATAK marker at a tapped feature, named after it, with the
 * attributes as remarks. A feature is a picture; a marker is a thing every other ATAK
 * tool knows how to route to, share, or bloodhound.
 */
public class MarkerHereReceiver extends BroadcastReceiver {

    private static final String TAG = "FeatureLayer";
    static final String ACTION = "com.atakmap.android.featurelayer.MARKER_HERE";

    private final MapView mapView;
    private final LayerManager manager;
    /** Feature (layer + id) to the marker already placed for it, so taps do not pile up markers. */
    private final java.util.Map<String, String> placed = new java.util.HashMap<>();

    /** Markers this receiver planted only so a bloodhound had something to follow. */
    private final java.util.Set<String> forBloodhound = new java.util.HashSet<>();
    /** Of those, the ones the hound has stamped with its ETA: seen running once. */
    private final java.util.Set<String> hounded = new java.util.HashSet<>();
    private final android.os.Handler handler = new android.os.Handler(android.os.Looper.getMainLooper());
    private boolean watching;

    public MarkerHereReceiver(MapView mapView, LayerManager manager) {
        this.mapView = mapView;
        this.manager = manager;
    }

    /**
     * ATAK's bloodhound, started by intent, never passes through its tool manager, so no
     * tool-ended event comes. What it does do is keep a "bloodhoundEta" value on the
     * marker it follows and strip it when it stops. A marker planted for the hound is
     * watched for that: stamped once, then unstamped, means the hound is over and the
     * marker goes.
     */
    private final Runnable watch = new Runnable() {
        @Override
        public void run() {
            for (String uid : new java.util.ArrayList<>(forBloodhound)) {
                final MapItem m = mapView.getRootGroup().deepFindUID(uid);
                if (m == null) {
                    forBloodhound.remove(uid);
                    hounded.remove(uid);
                    placed.values().remove(uid);
                    continue;
                }
                // The hound's own range-and-bearing line uses the marker as an endpoint for
                // as long as it runs; ATAK's ETA stamp is the second sign. Either seen once
                // and then gone means the hound is over.
                // The hound's toolbar button model is selected exactly while it runs
                // (BloodHoundTool.setActive drives NavView.setButtonSelected), whether or
                // not the button is on screen. First and best signal; the others stay.
                boolean running = false;
                try {
                    final com.atakmap.android.navigation.models.NavButtonModel model =
                            com.atakmap.android.navigation.NavButtonManager.getInstance()
                                    .getModelByReference("bloodhound.xml"); // the key BloodHoundTool.setActive uses
                    running = model != null && model.isSelected();
                    if (model == null && !warnedNoModel) {
                        warnedNoModel = true;
                        Log.d(TAG, "no bloodhound button model; falling back to the range line");
                    }
                } catch (Exception e) {
                    Log.w(TAG, "bloodhound button model", e);
                }
                running = running || m.hasMetaValue("bloodhoundEta");
                if (!running && m instanceof com.atakmap.android.maps.PointMapItem) {
                    try {
                        final java.util.List<com.atakmap.android.toolbars.RangeAndBearingMapItem> users =
                                com.atakmap.android.toolbars.RangeAndBearingMapItem.getUsers((com.atakmap.android.maps.PointMapItem) m);
                        running = users != null && !users.isEmpty();
                    } catch (Exception e) {
                        Log.w(TAG, "bloodhound watch", e);
                    }
                }
                if (!running) {
                    // Diagnostics: every range line on the map right now, and its endpoints.
                    try {
                        for (MapItem it : mapView.getRootGroup().deepFindItems("type", "u-rb-a")) {
                            if (it instanceof com.atakmap.android.toolbars.RangeAndBearingMapItem) {
                                final com.atakmap.android.toolbars.RangeAndBearingMapItem rb = (com.atakmap.android.toolbars.RangeAndBearingMapItem) it;
                                final String p1 = rb.getPoint1Item() == null ? null : rb.getPoint1Item().getUID();
                                final String p2 = rb.getPoint2Item() == null ? null : rb.getPoint2Item().getUID();
                                if (uid.equals(p1) || uid.equals(p2))
                                    running = true;
                            }
                        }
                    } catch (Exception e) {
                        Log.w(TAG, "rb scan", e);
                    }
                }
                if (running) {
                    if (hounded.add(uid))
                        Log.d(TAG, "bloodhound running to " + uid);
                } else if (hounded.contains(uid)) {
                    Log.d(TAG, "bloodhound over; removing its marker " + uid);
                    m.removeFromGroup();
                    forBloodhound.remove(uid);
                    hounded.remove(uid);
                    placed.values().remove(uid);
                }
            }
            if (forBloodhound.isEmpty())
                watching = false;
            else
                handler.postDelayed(this, 700);
        }
    };

    private boolean warnedNoModel;

    private void startWatching() {
        if (watching)
            return;
        watching = true;
        handler.postDelayed(watch, 700);
    }

    public void dispose() {
        handler.removeCallbacks(watch);
        watching = false;
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        final String uid = intent.getStringExtra("targetUID");
        final MapItem item = uid == null ? null : mapView.getRootGroup().deepFindItem("uid", uid);
        if (item == null)
            return;
        final long fid = item.getMetaLong("featureid", -1);
        final String layerId = item.getMetaString("nifs_layer", null);
        final FeatureDataStore2 store = layerId == null ? null : manager.storeFor(layerId);
        Feature f = null;
        try {
            if (fid >= 0 && store != null)
                f = Utils.getFeature(store, fid);
        } catch (Exception e) {
            Log.w(TAG, "marker here: feature lookup failed", e);
        }
        if (f == null || f.getGeometry() == null)
            return;
        final GeoPoint at = center(f.getGeometry());
        final String title = item.getMetaString("title", f.getName());
        final String key = layerId + "#" + fid;
        Marker m = null;
        final String existing = placed.get(key);
        if (existing != null) {
            final MapItem prev = mapView.getRootGroup().deepFindUID(existing);
            if (prev instanceof Marker)
                m = (Marker) prev;
        }
        final boolean bloodhound = "bloodhound".equals(intent.getStringExtra("then"));
        if (m == null) {
            m = new PlacePointTool.MarkerCreator(at)
                    .setType("a-u-G")
                    .setCallsign(title)
                    .showCotDetails(false)
                    .placePoint();
            if (m == null)
                return;
            m.setMetaString("remarks", remarks(f.getAttributes()));
            placed.put(key, m.getUID());
            if (bloodhound)
                forBloodhound.add(m.getUID()); // planted for the hound, gone when it ends
            else
                Toast.makeText(mapView.getContext(), "Marker placed: " + title, Toast.LENGTH_SHORT).show();
        }
        if (bloodhound) {
            // ATAK's bloodhound needs an item in a map group; the marker is one, the feature is not.
            Log.d(TAG, "bloodhound to marker " + m.getUID() + " (planted for it: " + forBloodhound.contains(m.getUID()) + ")");
            startWatching();
            com.atakmap.android.ipc.AtakBroadcast.getInstance().sendBroadcast(
                    new Intent("com.atakmap.android.toolbars.BLOOD_HOUND").putExtra("uid", m.getUID()));
        }
    }

    private static GeoPoint center(Geometry g) {
        if (g instanceof Point)
            return new GeoPoint(((Point) g).getY(), ((Point) g).getX());
        final Envelope e = g.getEnvelope();
        return new GeoPoint((e.minY + e.maxY) / 2, (e.minX + e.maxX) / 2);
    }

    private static String remarks(AttributeSet a) {
        if (a == null)
            return "";
        final List<String> keys = new ArrayList<>(a.getAttributeNames());
        Collections.sort(keys, String.CASE_INSENSITIVE_ORDER);
        final StringBuilder sb = new StringBuilder();
        for (String k : keys) {
            if (k.startsWith("_"))
                continue;
            String v;
            try {
                v = a.getStringAttribute(k);
            } catch (Exception ex) {
                continue;
            }
            if (v == null || v.isEmpty())
                continue;
            sb.append(k).append(": ").append(v).append('\n');
        }
        return sb.toString().trim();
    }
}
