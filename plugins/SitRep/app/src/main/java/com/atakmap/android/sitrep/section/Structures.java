package com.atakmap.android.sitrep.section;

import com.atakmap.android.sitrep.Area;
import com.atakmap.android.sitrep.net.Esri;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Structures by occupancy, from FEMA's USA Structures: one grouped count inside the
 * area. Many residential buildings carry no type ("Unclassified"), so the head leads
 * with all structures, then homes (PLAN section 4.3).
 */
public final class Structures extends Section {

    static final String LAYER = "https://services2.arcgis.com/FiaPA4ga0iQKduv3/arcgis/rest/services/"
            + "USA_Structures_View/FeatureServer/0";

    public Structures() {
        super("structures", "Structures", false,
                "FEMA USA Structures: every building footprint that touches the area, with the occupancy FEMA assigned it.");
    }

    @Override
    public Result fetch(Area area) throws Exception {
        final List<JSONObject> rows = Esri.countBy(LAYER, "1=1", area, "OCC_CLS", "PRIM_OCC");
        int total = 0, single = 0, multi = 0, manufactured = 0, untyped = 0;
        final Map<String, Integer> byClass = new LinkedHashMap<>();
        final Map<String, Integer> notable = new LinkedHashMap<>();
        for (String k : NOTABLE.keySet())
            notable.put(k, 0);
        for (JSONObject a : rows) {
            final int n = a.optInt("n", 0);
            if (n <= 0)
                continue;
            final String cls = a.optString("OCC_CLS", "Unclassified");
            final String prim = a.optString("PRIM_OCC", "Unclassified");
            total += n;
            if ("Residential".equals(cls)) {
                if ("Single Family Dwelling".equals(prim))
                    single += n;
                else if (prim.startsWith("Multi"))
                    multi += n;
                else if ("Manufactured Home".equals(prim))
                    manufactured += n;
                else if ("Unclassified".equals(prim))
                    untyped += n;
            } else {
                final String label = CLASS_LABELS.containsKey(cls) ? CLASS_LABELS.get(cls) : cls;
                final Integer was = byClass.get(label);
                byClass.put(label, (was == null ? 0 : was) + n);
            }
            if (notable.containsKey(prim))
                notable.put(prim, notable.get(prim) + n);
        }
        final int homes = single + multi + manufactured + untyped;
        final Result r = new Result(total == 0 ? "None in the area"
                : count(total) + "; " + count(homes) + (homes == 1 ? " home" : " homes"),
                total == 0 ? null : count(homes) + (homes == 1 ? " home" : " homes"));
        if (total == 0)
            return r.line("No buildings are mapped inside the area.");
        if (homes > 0) {
            r.line("Homes " + count(homes) + ":");
            if (single > 0)
                r.line("  " + count(single) + " single-family");
            if (multi > 0)
                r.line("  " + count(multi) + " multi-family buildings");
            if (manufactured > 0)
                r.line("  " + count(manufactured) + " manufactured homes");
            if (untyped > 0)
                r.line("  " + count(untyped) + " type not recorded");
        }
        for (Map.Entry<String, String> e : NOTABLE.entrySet()) {
            final int n = notable.get(e.getKey());
            if (n > 0)
                r.line(e.getValue() + ": " + count(n));
        }
        final List<Map.Entry<String, Integer>> classes = new ArrayList<>(byClass.entrySet());
        Collections.sort(classes, new Comparator<Map.Entry<String, Integer>>() {
            @Override
            public int compare(Map.Entry<String, Integer> a, Map.Entry<String, Integer> b) {
                return b.getValue() - a.getValue();
            }
        });
        if (!classes.isEmpty()) {
            r.line("Other buildings:");
            for (Map.Entry<String, Integer> e : classes)
                r.line("  " + count(e.getValue()) + " " + e.getKey());
        }
        return r;
    }

    /** Occupancies worth their own line, by the PRIM_OCC FEMA uses. */
    private static final Map<String, String> NOTABLE = new LinkedHashMap<>();
    private static final Map<String, String> CLASS_LABELS = new LinkedHashMap<>();

    static {
        NOTABLE.put("Hospital", "Hospitals");
        NOTABLE.put("Nursing Home", "Nursing homes");
        NOTABLE.put("Institutional Dormitory", "Dormitories");
        NOTABLE.put("Pre-K - 12 Schools", "School buildings");
        NOTABLE.put("Emergency Response", "Emergency response buildings");
        CLASS_LABELS.put("Commercial", "commercial");
        CLASS_LABELS.put("Industrial", "industrial");
        CLASS_LABELS.put("Government", "government");
        CLASS_LABELS.put("Education", "education");
        CLASS_LABELS.put("Agriculture", "agriculture");
        CLASS_LABELS.put("Assembly", "assembly (churches, arenas)");
        CLASS_LABELS.put("Utility and Misc", "utility and other");
        CLASS_LABELS.put("Unclassified", "not classified");
    }

    static String count(int n) {
        return String.format(Locale.US, "%,d", n);
    }
}
