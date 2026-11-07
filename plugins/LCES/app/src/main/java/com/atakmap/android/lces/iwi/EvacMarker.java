package com.atakmap.android.lces.iwi;

import com.atakmap.android.cot.CotMapComponent;
import com.atakmap.coremap.cot.event.CotDetail;
import com.atakmap.coremap.cot.event.CotEvent;
import com.atakmap.coremap.cot.event.CotPoint;
import com.atakmap.coremap.log.Log;
import com.atakmap.coremap.maps.coords.GeoPoint;
import com.atakmap.coremap.maps.time.CoordinatedTime;

/**
 * The evacuation location picked on the map (item 4): a labeled spot, on this map only,
 * the way the IWI marker is. Clear takes it off with the IWI.
 */
public final class EvacMarker {

    private static final String TAG = "LCES-IWI-Evac";
    private static final String TYPE = "b-m-p-s-m";
    private static final long STALE_MS = 24L * 60L * 60L * 1000L;
    /** Cyan, the section-heading color: not a triage color, seen on imagery. */
    private static final int COLOR = 0xFF00E5FF;

    private EvacMarker() {
    }

    public static boolean write(String uid, GeoPoint p) {
        if (uid == null || uid.isEmpty() || p == null)
            return false;
        final CotEvent e = new CotEvent();
        e.setVersion("2.0");
        e.setUID(uid);
        e.setType(TYPE);
        e.setHow("h-g-i-g-o");
        final CoordinatedTime now = new CoordinatedTime();
        e.setTime(now);
        e.setStart(now);
        e.setStale(new CoordinatedTime(now.getMilliseconds() + STALE_MS));
        e.setPoint(new CotPoint(p.getLatitude(), p.getLongitude(), CotPoint.UNKNOWN,
                CotPoint.UNKNOWN, CotPoint.UNKNOWN));
        final CotDetail detail = new CotDetail("detail");
        final CotDetail contact = new CotDetail("contact");
        contact.setAttribute("callsign", "IWI Evac");
        detail.addChild(contact);
        final CotDetail color = new CotDetail("color");
        color.setAttribute("argb", Integer.toString(COLOR));
        detail.addChild(color);
        detail.addChild(new CotDetail("archive"));
        e.setDetail(detail);
        if (!e.isValid()) {
            Log.w(TAG, "the evac marker event is not valid; not placed");
            return false;
        }
        CotMapComponent.getInternalDispatcher().dispatch(e);
        return true;
    }
}
