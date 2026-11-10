package com.atakmap.android.iwi.plugin;

import com.atakmap.android.maps.MapView;
import com.atakmap.coremap.cot.event.CotEvent;
import com.atakmap.coremap.log.Log;
import com.atakmap.coremap.maps.coords.GeoPoint;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The hospitals that go to the feed with an IWI (operator, 2026-11-09): picked from what
 * the report says, each a labeled marker.
 *
 * <pre>
 *   always             nearest ER, nearest ER with a helipad
 *   trauma             + nearest adult trauma center, + nearest Level I if different
 *   burns              + nearest adult burn center
 *   trauma and burns   both; trauma first ("trauma trumps burns"), burn as a second choice
 * </pre>
 *
 * One hospital that fills two rows is one marker. The set is recomputed whenever the IWI
 * changes; markers that drop out are taken off the map and out of the feed, new ones go
 * in, and Clear takes all of them. The UIDs travel in the IWI's own fields, so any device
 * holding the report knows which markers belong to it.
 */
public final class HospitalSet {

    private static final String TAG = "IWI-Hospitals";
    /** Comma-separated marker UIDs, in the IWI's fields. */
    public static final String UIDS = "hospital_uids";

    private HospitalSet() {
    }

    /** One pick: the hospital and why it is there. */
    public static final class Pick {
        public final TraumaFinder.Result result;
        public final String why;

        Pick(TraumaFinder.Result result, String why) {
            this.result = result;
            this.why = why;
        }
    }

    /** What the report's What Happened says. */
    static boolean trauma(IwiState state) {
        final Set<String> picked = Vocab.parse(state.text(IwiState.NATURE), Vocab.TRAUMA);
        for (String code : picked)
            if (!"burns".equals(code))
                return true;
        return false;
    }

    static boolean burns(IwiState state) {
        return Vocab.parse(state.text(IwiState.NATURE), Vocab.TRAUMA).contains("burns");
    }

    /** The picks for a point and an injury, in the order a reader should see them. */
    public static List<Pick> pick(List<TraumaCenter> centers, GeoPoint from, boolean trauma,
            boolean burns) {
        final Map<String, Pick> out = new LinkedHashMap<>();
        if (trauma) {
            add(out, TraumaFinder.nearest(centers, from, TraumaFinder.TRAUMA),
                    "nearest trauma center");
            add(out, TraumaFinder.nearest(centers, from, TraumaFinder.LEVEL_I),
                    "nearest Level I trauma center");
        }
        if (burns)
            add(out, TraumaFinder.nearest(centers, from, TraumaFinder.BURN),
                    "nearest burn center");
        add(out, TraumaFinder.nearest(centers, from, TraumaFinder.ER), "nearest ER");
        add(out, TraumaFinder.nearest(centers, from, TraumaFinder.ER_PAD),
                "nearest ER with a helipad");
        return new ArrayList<>(out.values());
    }

    private static void add(Map<String, Pick> out, TraumaFinder.Result r, String why) {
        if (r != null && !out.containsKey(r.center.id))
            out.put(r.center.id, new Pick(r, why));
    }

    /**
     * Bring the IWI's hospital markers in line with the report. Main thread. Does
     * nothing until the IWI is declared, or when the pack is not on this device.
     */
    public static void sync(MapView mapView, IwiState state, List<TraumaCenter> centers) {
        if (!state.isLive() || centers == null || centers.isEmpty())
            return;
        final GeoPoint from = state.point();
        if (from == null)
            return;
        final List<Pick> picks = pick(centers, from, trauma(state), burns(state));
        final List<String> was = uids(state);
        final List<String> now = new ArrayList<>();
        for (Pick p : picks) {
            final String uid = state.uid() + "-H-" + p.result.center.id;
            now.add(uid);
            if (was.contains(uid))
                continue;
            final CotEvent e = HospitalMarker.write(p.result.center, uid);
            if (e != null)
                IwiFeed.offer(e);
        }
        for (String uid : was) {
            if (now.contains(uid))
                continue;
            IwiMarker.remove(mapView, uid);
            IwiFeed.withdraw(uid);
        }
        if (!now.equals(was)) {
            state.set(UIDS, android.text.TextUtils.join(",", now));
            Log.d(TAG, "hospitals for the IWI: " + now.size());
        }
    }

    /** Clear: every hospital marker off the map and out of the feed. */
    public static void clear(MapView mapView, IwiState state) {
        for (String uid : uids(state)) {
            IwiMarker.remove(mapView, uid);
            IwiFeed.withdraw(uid);
        }
        state.set(UIDS, "");
    }

    /**
     * Only this IWI's own hospital markers: the list travels in the report, so a uid
     * outside "<IWI uid>-H-" is never removed from the map or withdrawn from the feed.
     */
    static List<String> uids(IwiState state) {
        final List<String> out = new ArrayList<>();
        final String own = state.uid() + "-H-";
        for (String s : state.text(UIDS).split(",")) {
            final String uid = s.trim();
            if (uid.startsWith(own) && uid.length() > own.length())
                out.add(uid);
        }
        return out;
    }
}
