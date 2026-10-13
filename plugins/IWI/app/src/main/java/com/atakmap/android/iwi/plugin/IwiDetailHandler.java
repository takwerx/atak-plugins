
package com.atakmap.android.iwi.plugin;

import com.atakmap.android.cot.detail.CotDetailHandler;
import com.atakmap.android.maps.MapItem;
import com.atakmap.comms.CommsMapComponent;
import com.atakmap.coremap.cot.event.CotDetail;
import com.atakmap.coremap.cot.event.CotEvent;

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
            "declared");

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
}
