
package com.atakmap.android.iwi.plugin;

import com.atakmap.android.cot.CotMapComponent;
import com.atakmap.android.importexport.CotEventFactory;
import com.atakmap.android.maps.MapItem;
import com.atakmap.android.maps.MapView;
import com.atakmap.coremap.cot.event.CotEvent;
import com.atakmap.coremap.log.Log;
import com.atakmap.coremap.maps.coords.GeoPointMetaData;

import java.util.UUID;

/**
 * The round-trip confirmation described in PLAN-IWI-v0.1.
 *
 * <p>Injects a CASEVAC marker carrying both <code>_medevac_</code> and
 * <code>_iwi_</code> the way a report from another phone would arrive, then
 * dumps what ATAK regenerates for that marker. Between the two, the operator
 * edits the med line in ATAK's own UI. If <code>_iwi_</code> comes back
 * unchanged, the sidecar survives ATAK's own editor.
 */
public final class RoundTrip {

    public static final String TAG = "IWI-RoundTrip";

    /** UID is fixed so inject and dump find each other across an ATAK restart. */
    private static final String UID = "IWI-ROUNDTRIP-SPIKE";

    private RoundTrip() {
    }

    /** Build the CoT a real IWI report would send, and feed it to ATAK. */
    public static String inject(MapView mapView) {
        GeoPointMetaData p = mapView.getCenterPoint();
        String now = com.atakmap.coremap.maps.time.CoordinatedTime
                .currentDate().toString();

        String xml = "<event version='2.0'"
                + " uid='" + UID + "'"
                + " type='b-r-f-h-c'"
                + " time='" + now + "' start='" + now + "' stale='" + now + "'"
                + " how='h-g-i-g-o'>"
                + "<point lat='" + p.get().getLatitude() + "'"
                + " lon='" + p.get().getLongitude() + "'"
                + " hae='9999999.0' ce='9999999.0' le='9999999.0'/>"
                + "<detail>"
                + "<contact callsign='Trout Meadow Medical'/>"
                + "<status readiness='true'/>"
                + "<archive/>"
                + "<remarks>IWI round-trip spike</remarks>"
                // ATAK's own CASEVAC payload
                + "<_medevac_ casevac='true' title='Trout Meadow Medical'"
                + " freq='168.0250' priority='urgent'"
                + " litter='1' ambulatory='0' us_civilian='1'"
                + " obstacles='snags, powerline crossing the road'"
                + " hlz_remarks='meadow 200m south, sloped'"
                + " medline_remarks='2. Struck by falling tree, unconscious.'/>"
                // ours, which a plain ATAK has never heard of
                + "<_iwi_ iwi_type='medical'"
                + " triage_red='1' triage_yellow='0' triage_green='0'"
                + " triage_black='0'"
                + " nature='Unconscious'"
                + " mechanism='Struck by falling tree'"
                + " evac_request='short-haul'"
                + " location_description='Forest Road 1, 1.2 mi east of the gate'"
                + " incident_name='Trout Meadow Medical'"
                + " onscene_ic='TFLD Jones'"
                + " patient_care='EMT Smith'"
                + " declared='" + now + "'/>"
                + "</detail>"
                + "</event>";

        CotEvent e = CotEvent.parse(xml);
        if (e == null || !e.isValid()) {
            Log.e(TAG, "INJECT FAILED: CoT did not parse");
            return null;
        }
        CotMapComponent.getInternalDispatcher().dispatch(e);
        Log.i(TAG, "INJECTED uid=" + UID);
        return UID;
    }

    /**
     * Regenerate the marker's CoT through ATAK's own factory and log it, which
     * is the same path ATAK uses to persist and to send.
     */
    public static boolean dump(MapView mapView) {
        MapItem item = mapView.getRootGroup().deepFindUID(UID);
        if (item == null) {
            Log.e(TAG, "DUMP: no marker with uid=" + UID + " — inject first");
            return false;
        }

        Log.i(TAG, "DUMP opaque-details metadata = "
                + item.getMetaString("opaque-details", "(absent)"));
        for (String a : IwiDetailHandler.ATTRS) {
            String v = item.getMetaString("iwi." + a, null);
            if (v != null)
                Log.i(TAG, "DUMP meta iwi." + a + " = " + v);
        }

        CotEvent e = CotEventFactory.createCotEvent(item);
        if (e == null) {
            Log.e(TAG, "DUMP: createCotEvent returned null");
            return false;
        }
        String xml = e.toString();
        Log.i(TAG, "DUMP has _iwi_ = " + xml.contains("<_iwi_")
                + " ; has _medevac_ = " + xml.contains("<_medevac_"));
        // logcat truncates a long line, so chunk it
        final int n = 2000;
        for (int i = 0; i < xml.length(); i += n)
            Log.i(TAG, "DUMP[" + (i / n) + "] "
                    + xml.substring(i, Math.min(xml.length(), i + n)));
        return true;
    }
}
