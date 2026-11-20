package com.atakmap.android.sitrep.section;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;

import java.util.List;

/** Row shapes copied from the live Chicago datasets msrk-w9ih and h243-v2q5, 2026-11-16. */
public class PlacesCentersTest {

    @Test
    public void bothDatasetsAreListed() {
        assertEquals(2, Places.CHICAGO_CENTERS.length);
        assertTrue(Places.CHICAGO_CENTERS[0][2].endsWith("/msrk-w9ih.json"));
        assertTrue(Places.CHICAGO_CENTERS[1][2].endsWith("/h243-v2q5.json"));
        for (String[] c : Places.CHICAGO_CENTERS)
            assertTrue(c[2].startsWith("https://data.cityofchicago.org/resource/"));
    }

    @Test
    public void coolingCenterLines() throws Exception {
        final JSONObject a = new JSONObject("{\"site_type\":\"Chicago Community College\",\"site_name\":\"Daley College\","
                + "\"hours_of_operation\":\"M-F 8am-8pm; Sat 9am-3pm; Closed Sun\",\"address\":\"7500 S. Pulaski\","
                + "\"city\":\"Chicago\",\"state\":\"IL\",\"zip\":\"60652\",\"phone\":\"7735550142\"}");
        final List<String> info = Places.centerInfo(a, "Cooling center");
        assertEquals("Cooling center, Chicago Community College.", info.get(0));
        assertEquals("Hours: M-F 8am-8pm; Sat 9am-3pm; Closed Sun", info.get(1));
        assertEquals("7500 S. Pulaski, Chicago, IL", info.get(2));
        assertEquals(String.format("(%s) %s-%s", "773", "555", "0142"), info.get(3));
    }

    /** The warming dataset leaves hours blank on some rows. */
    @Test
    public void warmingCenterWithoutHours() throws Exception {
        final JSONObject a = new JSONObject("{\"site_type\":\"Police District\",\"site_name\":\"8th District\","
                + "\"address\":\"3420 W 63rd St.\",\"city\":\"Chicago\",\"state\":\"IL\",\"phone\":\"3125550177\"}");
        final List<String> info = Places.centerInfo(a, "Warming center");
        assertEquals(3, info.size());
        assertEquals("Warming center, Police District.", info.get(0));
        assertEquals("3420 W 63rd St., Chicago, IL", info.get(1));
        assertEquals(String.format("(%s) %s-%s", "312", "555", "0177"), info.get(2));
    }

    /** Hours that list every holiday closure are cut. */
    @Test
    public void longHoursAreCut() throws Exception {
        final StringBuilder h = new StringBuilder("M-F 8am-8pm; Holiday Closures:");
        for (int i = 0; i < 40; i++)
            h.append(" 5/").append(i).append("/26,");
        final JSONObject a = new JSONObject().put("site_name", "X").put("hours_of_operation", h.toString());
        final String hours = Places.centerInfo(a, "Cooling center").get(1);
        assertTrue(hours, hours.length() <= "Hours: ".length() + 143);
        assertTrue(hours, hours.endsWith("..."));
    }
}
