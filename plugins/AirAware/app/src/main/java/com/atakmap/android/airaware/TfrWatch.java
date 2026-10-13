package com.atakmap.android.airaware;

import com.atakmap.android.maps.MapGroup;
import com.atakmap.android.maps.MapItem;
import com.atakmap.android.maps.MapView;

import java.util.ArrayList;
import java.util.List;

/**
 * Whether the geofences the operator made still describe real airspace.
 *
 * <p>A fence is a copy taken at a moment. The restriction behind it can end three ways,
 * and only one of them is predictable:
 *
 * <ul>
 *   <li><b>Expired.</b> The FAA publishes an end on nearly every notice -- eleven of
 *       twelve sampled -- so this needs no network at all and is known the moment it
 *       lapses. The standing nationwide notices are the ones with no published end.</li>
 *   <li><b>Lifted.</b> A fire restriction comes down when the fire is out, well before
 *       its stated end. Nothing announces it; the notice simply leaves the list.</li>
 *   <li><b>Replaced.</b> An amended restriction is reissued under a <b>new NOTAM
 *       number</b> and the old number disappears -- Clear, Alaska went 6/2117 to 6/3002
 *       that way. A fence on the old number is then guarding airspace that no longer
 *       exists while the real one is a different id entirely, which is the worst of the
 *       three because nothing about it looks wrong.</li>
 * </ul>
 *
 * <p>The last two are only visible by comparing against a fresh list, which is the sync
 * that already runs. The first is a clock.
 */
public final class TfrWatch {

    /** Written on a geofence shape when it is made, so staleness can be judged later. */
    public static final String META_NOTAM = "tfr_from_notam";
    public static final String META_CEILING = "tfr_from_ceiling_ft";
    public static final String META_EXPIRE = "tfr_from_expire_ms";
    public static final String META_POINTS = "tfr_from_points";

    public enum State {
        /** Still as it was. */
        CURRENT,
        /** Past the end the FAA published. */
        EXPIRED,
        /** No longer in the national list: lifted, or reissued under a new number. */
        GONE,
        /** Still listed, but not the airspace this fence was cut from. */
        CHANGED
    }

    /** One geofence and what has become of the restriction behind it. */
    public static class Watched {
        public final String uid;
        public final String title;
        public final String notamId;
        public final State state;

        Watched(String uid, String title, String notamId, State state) {
            this.uid = uid;
            this.title = title;
            this.notamId = notamId;
            this.state = state;
        }
    }

    /** Only to keep the check from logging the same count every minute forever. */
    private static int lastSeen = -1;

    private TfrWatch() {
    }

    /** Record what a fence was cut from, so a later sync can tell whether it still holds. */
    public static void stamp(MapItem item, Tfr t, TfrArea a) {
        if (item == null)
            return;
        item.setMetaString(META_NOTAM, t.notamId);
        item.setMetaInteger(META_CEILING, a.ceiling.present ? a.ceiling.feet : -1);
        item.setMetaString(META_EXPIRE, Long.toString(t.expireMs));
        item.setMetaInteger(META_POINTS, a.ring.size());
    }

    /**
     * Every geofence made from a restriction, and whether it still describes one.
     *
     * @param known what the last sync returned, or the cached list when offline
     * @param synced false when nothing has been downloaded yet, in which case absence from
     *            the list says nothing and GONE is never reported
     */
    public static List<Watched> check(MapView mapView, List<Tfr> known, boolean synced,
            long nowMs) {
        final List<Watched> out = new ArrayList<>();
        if (mapView == null)
            return out;
        final List<MapItem> ours = new ArrayList<>();
        mapView.getRootGroup().deepForEachItem(new MapGroup.MapItemsCallback() {
            @Override
            public boolean onItemFunction(MapItem item) {
                if (item != null && item.hasMetaValue(META_NOTAM))
                    ours.add(item);
                return false;
            }
        });
        if (ours.size() != lastSeen) {
            lastSeen = ours.size();
            com.atakmap.coremap.log.Log.d("TfrWatch",
                    "geofences made from a restriction: " + ours.size());
        }
        for (MapItem item : ours) {
            final String notam = item.getMetaString(META_NOTAM, null);
            if (notam == null)
                continue;
            Tfr live = null;
            for (Tfr t : known)
                if (notam.equals(t.notamId)) {
                    live = t;
                    break;
                }
            final String title = item.getMetaString("callsign",
                    item.getMetaString("title", notam));
            State state = State.CURRENT;
            if (live == null) {
                // Only meaningful once there is a list to be absent from.
                if (synced)
                    state = State.GONE;
            } else if (live.expireMs > 0 && nowMs > live.expireMs) {
                state = State.EXPIRED;
            } else if (changed(item, live)) {
                state = State.CHANGED;
            }
            // Every one, current included. The Settings fold lists what the operator
            // made and says how each stands; it used to be handed only the stale ones,
            // so a fence that was fine did not appear at all and the page read as if
            // nothing had been made. Callers that want only the stale ones filter.
            out.add(new Watched(item.getUID(), title, notam, state));
        }
        return out;
    }

    /**
     * Whether the restriction is no longer the one the fence was cut from.
     *
     * <p>Ceiling, published end and vertex count. Not a deep comparison: the point is to
     * notice that the airspace moved under a fence, and any of the three changing is
     * enough to say "look at this again" -- which is all the plugin should claim.
     */
    private static boolean changed(MapItem item, Tfr live) {
        final int wasCeiling = item.getMetaInteger(META_CEILING, -1);
        final int wasPoints = item.getMetaInteger(META_POINTS, -1);
        long wasExpire = 0L;
        try {
            wasExpire = Long.parseLong(item.getMetaString(META_EXPIRE, "0"));
        } catch (NumberFormatException ignored) {
            // An unreadable stamp is not evidence of a change.
        }
        if (wasExpire != 0 && live.expireMs != 0 && wasExpire != live.expireMs)
            return true;
        for (TfrArea a : live.drawable()) {
            final int ceil = a.ceiling.present ? a.ceiling.feet : -1;
            if (ceil == wasCeiling && a.ring.size() == wasPoints)
                return false;
        }
        // Nothing in the live notice looks like what was fenced.
        return wasCeiling != -1 || wasPoints != -1;
    }

    /**
     * Take a geofence off the map and out of ATAK's drawings.
     *
     * <p>The operator's own item, so this only ever runs on their say-so -- the plugin
     * reports a stale fence and offers the removal, it never quietly deletes something
     * somebody made.
     */
    public static boolean remove(MapView mapView, String uid) {
        if (mapView == null || uid == null)
            return false;
        final MapItem item = mapView.getRootGroup().deepFindUID(uid);
        if (item == null)
            return false;
        try {
            com.atakmap.android.geofence.data.GeoFence.clearGeofenceState(mapView, item);
        } catch (Exception ignored) {
            // No fence attached, or ATAK has already forgotten it. The shape still goes.
        }
        item.removeFromGroup();
        return true;
    }

    /** One line for the status, or null when every fence still holds. */
    public static String line(List<Watched> stale) {
        if (stale.isEmpty())
            return null;
        if (stale.size() == 1) {
            final Watched w = stale.get(0);
            return "Geofence " + w.title + ": " + words(w.state);
        }
        return stale.size() + " geofences are out of date. Check them.";
    }

    public static String words(State s) {
        switch (s) {
            case EXPIRED:
                return "the restriction has expired. Remove it or it guards nothing.";
            case GONE:
                return "the restriction was lifted or reissued under a new number."
                        + " Remove it or it guards nothing.";
            case CHANGED:
                return "the restriction has changed. Make it again from the new one.";
            default:
                // Said, not left blank. The Settings fold lists current fences too now,
                // and an empty string there read as "Geofence: SOMEWHERE - " with a
                // dangling dash and no word on how it stands.
                return "still matches a live restriction.";
        }
    }
}
