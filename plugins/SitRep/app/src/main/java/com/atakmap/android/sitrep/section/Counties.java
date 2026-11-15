package com.atakmap.android.sitrep.section;

import com.atakmap.android.sitrep.Area;
import com.atakmap.android.sitrep.net.Esri;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** The counties an area touches, from the NWS reference map: state code, FIPS, name. */
final class Counties {

    final Set<String> states = new LinkedHashSet<>();
    /** {state, 5-digit FIPS, name}. */
    final List<String[]> list = new ArrayList<>();

    static Counties of(Area area) throws Exception {
        final Counties c = new Counties();
        for (JSONObject a : Esri.attributes(Roads.COUNTIES, "1=1", area, "state,fips,countyname")) {
            final String st = a.optString("state", ""), fips = a.optString("fips", "");
            if (!st.matches("[A-Z]{2}") || !fips.matches("\\d{5}"))
                continue;
            c.states.add(st);
            c.list.add(new String[] { st, fips, a.optString("countyname", "") });
        }
        return c;
    }
}
