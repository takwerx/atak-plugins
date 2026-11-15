package com.atakmap.android.sitrep;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import com.atakmap.android.ipc.AtakBroadcast;
import com.atakmap.android.maps.MapItem;
import com.atakmap.android.maps.MapView;
import com.atakmap.android.maps.Marker;
import com.atakmap.android.user.PlacePointTool;
import com.atakmap.coremap.log.Log;
import com.atakmap.coremap.maps.coords.GeoPoint;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * The radial on a place Sit Rep draws (PLAN section 11): Details opens the pane on
 * that place, Bloodhound follows it. Range and Bearing and polar coordinates are
 * ATAK's own actions and need nothing here.
 *
 * <p>A place is a feature, not a map item, so ATAK's bloodhound has nothing to follow.
 * Bloodhound plants a marker at the place for it and takes the marker away when the
 * hound stops (Feature Layer's MarkerHereReceiver, copied forward).
 */
public final class PlaceRadial extends BroadcastReceiver {

    private static final String TAG = "SitRep.Radial";
    public static final String DETAILS = "com.atakmap.android.sitrep.PLACE_DETAILS";
    public static final String BLOODHOUND = "com.atakmap.android.sitrep.PLACE_BLOODHOUND";

    /** Opens the pane on one place of one report. */
    public interface Opener {
        void openPlace(String reportId, String category, String key);
    }

    private final MapView mapView;
    private final Opener opener;
    /** The place the radial was opened on; a hit-test item may not be in any group. */
    private MapItem last;
    /** Place key to the marker planted for the hound, so taps do not pile up markers. */
    private final Map<String, String> placed = new HashMap<>();
    private final Set<String> hounded = new HashSet<>();
    /** Marker uid to when it was planted: one the hound never took goes after 20 s. */
    private final Map<String, Long> plantedAt = new HashMap<>();
    private final android.os.Handler handler = new android.os.Handler(android.os.Looper.getMainLooper());
    private boolean watching;

    public PlaceRadial(MapView mapView, Opener opener) {
        this.mapView = mapView;
        this.opener = opener;
    }

    /** The radial is about to open on this item. */
    public void opening(MapItem item) {
        last = item;
    }

    public void register() {
        final AtakBroadcast.DocumentedIntentFilter f = new AtakBroadcast.DocumentedIntentFilter();
        f.addAction(DETAILS, "open the Sit Rep pane on a place");
        f.addAction(BLOODHOUND, "bloodhound to a Sit Rep place");
        AtakBroadcast.getInstance().registerReceiver(this, f);
    }

    /** Takes the receiver off and the planted markers away. */
    public void unregister() {
        try {
            AtakBroadcast.getInstance().unregisterReceiver(this);
        } catch (Exception ignored) {
            // Never registered.
        }
        handler.removeCallbacks(watch);
        watching = false;
        for (String uid : placed.values()) {
            final MapItem m = mapView.getRootGroup().deepFindUID(uid);
            if (m != null)
                m.removeFromGroup();
        }
        placed.clear();
        hounded.clear();
        plantedAt.clear();
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        final String uid = intent.getStringExtra("targetUID");
        MapItem item = last != null && last.getUID().equals(uid) ? last : null;
        if (item == null && uid != null)
            item = mapView.getRootGroup().deepFindItem("uid", uid);
        if (item == null || !item.hasMetaValue("sitrep_key"))
            return;
        final String report = item.getMetaString("sitrep_report", null);
        final String category = item.getMetaString("sitrep_category", null);
        final String key = item.getMetaString("sitrep_key", null);
        if (DETAILS.equals(intent.getAction())) {
            opener.openPlace(report, category, key);
            return;
        }
        final double lat = item.getMetaDouble("sitrep_lat", Double.NaN);
        final double lon = item.getMetaDouble("sitrep_lon", Double.NaN);
        if (!(Math.abs(lat) <= 90) || !(Math.abs(lon) <= 180))
            return;
        Marker m = null;
        final String existing = placed.get(key);
        if (existing != null) {
            final MapItem prev = mapView.getRootGroup().deepFindUID(existing);
            if (prev instanceof Marker)
                m = (Marker) prev;
        }
        if (m == null) {
            m = new PlacePointTool.MarkerCreator(new GeoPoint(lat, lon))
                    .setType("a-u-G")
                    .setCallsign(item.getMetaString("title", "Place"))
                    .showCotDetails(false)
                    .placePoint();
            if (m == null)
                return;
            m.setMetaString("remarks", item.getMetaString("sitrep_details", ""));
            placed.put(key, m.getUID());
            plantedAt.put(m.getUID(), android.os.SystemClock.elapsedRealtime());
        }
        startWatching();
        AtakBroadcast.getInstance().sendBroadcast(
                new Intent("com.atakmap.android.toolbars.BLOOD_HOUND").putExtra("uid", m.getUID()));
    }

    /**
     * ATAK's bloodhound, started by intent, sends no tool-ended event. Its toolbar
     * button model is selected while it runs, it stamps "bloodhoundEta" on the marker,
     * and its range line uses the marker. Any of those seen, then none, means the hound
     * is over and the marker goes.
     */
    private final Runnable watch = new Runnable() {
        @Override
        public void run() {
            for (Map.Entry<String, String> e : new HashMap<>(placed).entrySet()) {
                final String uid = e.getValue();
                final MapItem m = mapView.getRootGroup().deepFindUID(uid);
                if (m == null) {
                    placed.remove(e.getKey());
                    hounded.remove(uid);
                    plantedAt.remove(uid);
                    continue;
                }
                boolean running = false;
                try {
                    final com.atakmap.android.navigation.models.NavButtonModel model =
                            com.atakmap.android.navigation.NavButtonManager.getInstance()
                                    .getModelByReference("bloodhound.xml");
                    running = model != null && model.isSelected();
                } catch (Exception ex) {
                    Log.w(TAG, "bloodhound button model", ex);
                }
                running = running || m.hasMetaValue("bloodhoundEta");
                if (!running && m instanceof com.atakmap.android.maps.PointMapItem) {
                    try {
                        final java.util.List<com.atakmap.android.toolbars.RangeAndBearingMapItem> users =
                                com.atakmap.android.toolbars.RangeAndBearingMapItem.getUsers(
                                        (com.atakmap.android.maps.PointMapItem) m);
                        running = users != null && !users.isEmpty();
                    } catch (Exception ex) {
                        Log.w(TAG, "bloodhound watch", ex);
                    }
                }
                if (running) {
                    hounded.add(uid);
                } else if (hounded.contains(uid) || android.os.SystemClock.elapsedRealtime()
                        - (plantedAt.containsKey(uid) ? plantedAt.get(uid) : 0L) > 20_000L) {
                    m.removeFromGroup();
                    placed.remove(e.getKey());
                    hounded.remove(uid);
                    plantedAt.remove(uid);
                }
            }
            if (placed.isEmpty())
                watching = false;
            else
                handler.postDelayed(this, 700);
        }
    };

    private void startWatching() {
        if (watching)
            return;
        watching = true;
        handler.postDelayed(watch, 700);
    }
}
