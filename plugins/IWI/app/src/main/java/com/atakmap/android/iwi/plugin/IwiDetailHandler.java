
package com.atakmap.android.iwi.plugin;


import com.atakmap.android.cot.detail.CotDetailHandler;
import com.atakmap.android.maps.MapItem;
import com.atakmap.comms.CommsMapComponent;
import com.atakmap.coremap.cot.event.CotDetail;
import com.atakmap.coremap.cot.event.CotEvent;
import com.atakmap.coremap.log.Log;

import gov.tak.api.util.AttributeSet;

import java.util.Arrays;
import java.util.List;

/**
 * Reads and writes the <code>_iwi_</code> CoT detail, which carries what the
 * NWCG Medical Incident Report needs and ATAK's <code>_medevac_</code> schema
 * has no honest home for.
 *
 * <p>An ATAK without this plugin does not lose the detail: CotDetailManager
 * hands a detail it has no handler for to OpaqueHandler, which parks it in the
 * marker's <code>opaque-details</code> metadata and re-emits it verbatim. So
 * the same marker round trips through a plain phone. This handler exists so
 * that on an IWI phone the contents are typed and readable rather than opaque.
 *
 * <p>The key list is fixed and explicit, the way ATAK's own
 * MedicalDetailHandler does it, so a stale metadata key can never be resurrected
 * into a report.
 *
 * <p>Lived in LCES from 2026-11-03 to 2026-11-07 with the detail name,
 * the attribute list and the metadata keys unchanged, so a marker either plugin wrote
 * reads the same in the other. ATAK runs every handler registered for a detail name,
 * so on a device that also has the IWI plugin loaded both handlers see each marker:
 * reading twice writes the same keys, but writing twice would put two {@code _iwi_}
 * children in one event. This one adds nothing when the event already has one.
 */
public class IwiDetailHandler extends CotDetailHandler {

    public static final String DETAIL = "_iwi_";

    /** Metadata keys are this prefix plus the attribute name. */
    private static final String META = "iwi.";

    /**
     * Every attribute the detail carries. Spike set for the round-trip
     * confirmation; grows with the report.
     */
    static final List<String> ATTRS = Arrays.asList(
            "iwi_type",
            "triage_red", "triage_yellow", "triage_green", "triage_black",
            "nature", "mechanism",
            "evac_request",
            "location_description",
            "incident_name",
            "onscene_ic",
            "patient_care",
            "declared",
            // The whole IWI as JSON, for a device with IWI to read (2026-11-06).
            "report");

    public IwiDetailHandler() {
        super(DETAIL);
    }

    @Override
    public CommsMapComponent.ImportResult toItemMetadata(MapItem item,
            CotEvent event, CotDetail detail) {
        for (String a : ATTRS) {
            String v = detail.getAttribute(a);
            if (v != null)
                item.setMetaString(META + a, v);
            else
                item.removeMetaData(META + a);
        }
        return CommsMapComponent.ImportResult.SUCCESS;
    }

    @Override
    public boolean toCotDetail(MapItem item, CotEvent event, CotDetail root) {
        if (root.getFirstChildByName(0, DETAIL) != null)
            return false;
        CotDetail d = new CotDetail(DETAIL);
        boolean any = false;
        for (String a : ATTRS) {
            String v = item.getMetaString(META + a, null);
            if (v != null) {
                d.setAttribute(a, v);
                any = true;
            }
        }
        if (!any)
            return false;
        root.addChild(d);
        return true;
    }

    /**
     * A marker that reached this device before IWI was loaded holds its {@code _iwi_}
     * detail only as text in ATAK's {@code opaque-details} (see the class comment),
     * and stays that way until the next update comes in: a tap on another device's
     * IWI found no report and opened ATAK's CASEVAC view (S22, 2026-11-11). Read the
     * parked text into the same metadata the handler writes.
     *
     * @return true when the item now carries IWI metadata from its opaque detail
     */
    static boolean adoptOpaque(MapItem item) {
        if (item == null)
            return false;
        try {
            final AttributeSet opaque = item.getMetaAttributeSet("opaque-details");
            if (opaque == null || !opaque.containsAttribute(DETAIL))
                return false;
            final String xml = opaque.getStringAttribute(DETAIL);
            if (xml == null || xml.isEmpty())
                return false;
            final CotEvent e = CotEvent.parse(OPAQUE_WRAPPER_START + xml
                    + OPAQUE_WRAPPER_END);
            if (e == null || e.getDetail() == null)
                return false;
            final CotDetail d = e.getDetail().getFirstChildByName(0, DETAIL);
            if (d == null)
                return false;
            new IwiDetailHandler().toItemMetadata(item, e, d);
            return true;
        } catch (RuntimeException ex) {
            Log.w("IWI-Detail", "could not read a parked _iwi_ detail", ex);
            return false;
        }
    }

    /** The event ATAK's own OpaqueHandler wraps a parked detail in to parse it. */
    private static final String OPAQUE_WRAPPER_START = "<?xml version='1.0' encoding='UTF-8'?>"
            + "<event version='2.0' uid='X' type='Y' how='h-e'"
            + " time='2011-10-29T02:40:00.677Z' start='2011-10-29T02:40:00.677Z'"
            + " stale='2011-10-30T02:40:00.677Z'>"
            + "<point ce='9999999.0' le='9999999.0' hae='9999999.0' lat='0.0' lon='0.0'/>"
            + "<detail>";
    private static final String OPAQUE_WRAPPER_END = "</detail></event>";
}
