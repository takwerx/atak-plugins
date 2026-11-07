package com.atakmap.android.lces.iwi;


import com.atakmap.android.cot.CotMapComponent;
import com.atakmap.android.maps.MapItem;
import com.atakmap.android.maps.MapView;
import com.atakmap.coremap.cot.event.CotDetail;
import com.atakmap.coremap.cot.event.CotEvent;
import com.atakmap.coremap.cot.event.CotPoint;
import com.atakmap.coremap.log.Log;
import com.atakmap.coremap.maps.coords.GeoPoint;
import com.atakmap.coremap.maps.time.CoordinatedTime;

/**
 * The one CASEVAC marker an IWI puts on the map.
 *
 * <p>Settled in PLAN-IWI: one {@code b-r-f-h-c} marker carrying a genuine
 * {@code _medevac_}, so a phone without the plugin sees an ordinary CASEVAC, and an
 * {@code _iwi_} sidecar for what ATAK's schema cannot say. The triage counts go in
 * {@code urgent} / {@code priority} / {@code routine}, which are counts, not values of
 * a priority field (measured on hardware 2026-09-11). The military half of the schema
 * is never written.
 *
 * <p>Built as a CoT event object and handed to ATAK's internal dispatcher, the way a
 * report from another phone arrives. Nothing is serialized by hand, so whatever the
 * reporter typed cannot break the XML. It goes to this map only: sending is the
 * reporter's act, from the marker's own radial.
 */
public final class IwiMarker {

    private static final String TAG = "LCES-IWI-Marker";
    public static final String TYPE = "b-r-f-h-c";

    /** Long enough that an incident running past a shift does not grey it out. */
    private static final long STALE_MS = 24L * 60L * 60L * 1000L;

    private IwiMarker() {
    }

    /** Put the marker where the IWI is, carrying what the report says so far. */
    public static boolean write(IwiState s) {
        final GeoPoint p = s.point();
        final String uid = s.uid();
        if (p == null || uid.isEmpty())
            return false;
        final String title = s.text(IwiState.INCIDENT).isEmpty() ? "IWI"
                : s.text(IwiState.INCIDENT);
        final String coords = Coords.ddm(p.getLatitude(), p.getLongitude());

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
        contact.setAttribute("callsign", title);
        detail.addChild(contact);
        // Archived, so ATAK keeps the marker across a restart.
        detail.addChild(new CotDetail("archive"));

        final CotDetail med = new CotDetail("_medevac_");
        med.setAttribute("casevac", "true");
        med.setAttribute("title", title);
        final int red = s.count(IwiState.RED);
        final int yellow = s.count(IwiState.YELLOW);
        final int green = s.count(IwiState.GREEN);
        if (red + yellow + green > 0) {
            med.setAttribute("urgent", Integer.toString(red));
            med.setAttribute("priority", Integer.toString(yellow));
            med.setAttribute("routine", Integer.toString(green));
            // Mobility is not severity; until the form asks "can they walk?", Red and
            // Yellow ride a litter and Green walks (PLAN-IWI default).
            med.setAttribute("litter", Integer.toString(red + yellow));
            med.setAttribute("ambulatory", Integer.toString(green));
        }
        if (s.patientCount() > 0)
            med.setAttribute("us_civilian", Integer.toString(s.patientCount()));
        final String remarks = RadioScript.remarks(s, coords);
        if (!remarks.isEmpty())
            med.setAttribute("medline_remarks", remarks);
        if (!s.text(IwiState.HELISPOT).isEmpty())
            med.setAttribute("hlz_remarks", s.text(IwiState.HELISPOT));
        final String evac = RadioScript.evacLabels(s);
        if (!evac.isEmpty()) {
            med.setAttribute("equipment_other", "true");
            med.setAttribute("equipment_detail", evac);
        }
        detail.addChild(med);

        final CotDetail iwi = new CotDetail(IwiDetailHandler.DETAIL);
        iwi.setAttribute("iwi_type", "medical");
        iwi.setAttribute("triage_red", Integer.toString(red));
        iwi.setAttribute("triage_yellow", Integer.toString(yellow));
        iwi.setAttribute("triage_green", Integer.toString(green));
        iwi.setAttribute("triage_black", Integer.toString(s.count(IwiState.BLACK)));
        put(iwi, "nature", Vocab.said(s.text(IwiState.NATURE), Vocab.MEDICAL, Vocab.TRAUMA));
        put(iwi, "evac_request", evac);
        put(iwi, "location_description", s.text(IwiState.LOCATION));
        put(iwi, "incident_name", s.text(IwiState.INCIDENT));
        put(iwi, "onscene_ic", s.text(IwiState.IC));
        put(iwi, "patient_care", Vocab.CARE.label(s.text(IwiState.CARE_ROLE)));
        iwi.setAttribute("declared", new CoordinatedTime(s.declaredMs()).toString());
        iwi.setAttribute("report", s.exportJson());
        detail.addChild(iwi);

        e.setDetail(detail);
        if (!e.isValid()) {
            Log.w(TAG, "the IWI marker event is not valid; not placed");
            return false;
        }
        CotMapComponent.getInternalDispatcher().dispatch(e);
        IwiFeed.offer(e);
        return true;
    }

    private static void put(CotDetail d, String name, String value) {
        if (value != null && !value.isEmpty())
            d.setAttribute(name, value);
    }

    /** Take the marker off this map. Anyone it was sent to keeps theirs. */
    public static void remove(MapView mapView, String uid) {
        if (mapView == null || uid == null || uid.isEmpty())
            return;
        final MapItem item = mapView.getRootGroup().deepFindUID(uid);
        if (item != null)
            item.removeFromGroup();
    }
}
