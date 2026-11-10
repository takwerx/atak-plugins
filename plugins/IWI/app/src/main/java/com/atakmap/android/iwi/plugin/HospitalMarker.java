package com.atakmap.android.iwi.plugin;

import com.atakmap.android.cot.CotMapComponent;
import com.atakmap.coremap.cot.event.CotDetail;
import com.atakmap.coremap.cot.event.CotEvent;
import com.atakmap.coremap.cot.event.CotPoint;
import com.atakmap.coremap.log.Log;
import com.atakmap.coremap.maps.coords.GeoPoint;
import com.atakmap.coremap.maps.time.CoordinatedTime;

/**
 * A hospital on the map, labeled with what it is (operator, 2026-11-09): "Riverside
 * Community Hospital" over "TRAUMA Level I, helipad". Two kinds: the one picked from the Hospitals
 * page (fixed {@link #UID}, this map only, a new pick moves it), and the ones
 * {@link HospitalSet} puts in the feed with an IWI. Where a patient flies is still the
 * air crew's call; the label is a reference.
 */
public final class HospitalMarker {

    private static final String TAG = "IWI-Hospital";
    /** Fixed, so a new pick replaces the last one. */
    public static final String UID = "IWI-hospital";
    private static final String TYPE = "b-m-p-s-m";
    private static final long STALE_MS = 24L * 60L * 60L * 1000L;
    /** Orange (operator, 2026-11-09): not a triage color, not the cyan evac spot. */
    private static final int COLOR = 0xFFFF8C00;

    private HospitalMarker() {
    }

    /** The Hospitals page's pick: this map only. */
    public static boolean write(TraumaCenter c) {
        return write(c, UID) != null;
    }

    /** @return the event placed on this map, for the feed, or null */
    public static CotEvent write(TraumaCenter c, String uid) {
        if (c == null)
            return null;
        final GeoPoint p = c.target();
        if (!TraumaCenter.valid(p.getLatitude(), p.getLongitude()))
            return null;
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
        contact.setAttribute("callsign", label(c));
        detail.addChild(contact);
        final CotDetail color = new CotDetail("color");
        color.setAttribute("argb", Integer.toString(COLOR));
        detail.addChild(color);
        final CotDetail remarks = new CotDetail("remarks");
        remarks.setInnerText(c.kindLabel()
                + (c.burn && !c.burnName.isEmpty() ? " (" + c.burnName + ")" : "")
                + (c.city.isEmpty() ? "" : ". " + c.city + ", " + c.state)
                + (c.padCharted ? ". Helipad charted by the FAA; the marker is on the pad."
                        : c.padReported ? ". Helipad reported, not charted by the FAA."
                        : ". No helipad on record."));
        detail.addChild(remarks);
        detail.addChild(new CotDetail("archive"));
        e.setDetail(detail);
        if (!e.isValid()) {
            Log.w(TAG, "the hospital marker event is not valid; not placed");
            return null;
        }
        CotMapComponent.getInternalDispatcher().dispatch(e);
        return e;
    }

    /**
     * Two lines (operator, 2026-11-09: "way to long on one line"): the name, then what
     * it is and whether it has a pad. No "H": it read as "helipad".
     */
    static String label(TraumaCenter c) {
        return c.name + "\n" + c.kindLabel() + (c.hasPad() ? ", helipad" : ", no helipad");
    }
}
