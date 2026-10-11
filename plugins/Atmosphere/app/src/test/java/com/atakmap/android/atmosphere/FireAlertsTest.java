package com.atakmap.android.atmosphere;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.atakmap.android.atmosphere.data.FireAlerts;
import com.atakmap.android.atmosphere.data.FireZones;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * The zones under a Red Flag Warning or a Fire Weather Watch, on the shape of the
 * Reno and Medford alerts of 2026-09-24/25, and the zone outlines they shade.
 */
public class FireAlertsTest {

    private static String alert(String event, String type, String ends, String... ugc) {
        final StringBuilder z = new StringBuilder();
        for (String u : ugc)
            z.append(z.length() == 0 ? "" : ",").append('"').append(u).append('"');
        return "{\"properties\":{\"event\":\"" + event + "\",\"messageType\":\"" + type
                + "\",\"ends\":\"" + ends + "\",\"senderName\":\"NWS Reno NV\","
                + "\"geocode\":{\"UGC\":[" + z + "]}}}";
    }

    /** 2026-09-25T12:00Z. */
    private static final long NOW = 1790337600000L;

    @Test
    public void marksEachZoneAnAlertNames() {
        final String body = "{\"features\":["
                + alert("Red Flag Warning", "Alert", "2026-09-25T22:00:00-07:00",
                        "NVZ420", "NVZ421") + ","
                + alert("Fire Weather Watch", "Alert", "2026-09-26T22:00:00-07:00",
                        "NVZ421", "NVZ423") + "]}";
        final Map<String, FireAlerts.Status> z = FireAlerts.parse(body, NOW);
        assertEquals(3, z.size());
        assertTrue(z.get("NVZ420").isRedFlag());
        // Under both: the warning is what shows.
        assertTrue(z.get("NVZ421").isRedFlag());
        assertEquals(FireAlerts.WATCH, z.get("NVZ423").event);
        assertEquals(FireAlerts.WATCH_COLOR, z.get("NVZ423").color());
        assertEquals("NWS Reno NV", z.get("NVZ420").sender);
    }

    @Test
    public void aCancelOrAnEndedAlertMarksNothing() {
        final String body = "{\"features\":["
                + alert("Red Flag Warning", "Cancel", "2026-09-25T22:00:00-07:00", "CAZ285")
                + "," + alert("Red Flag Warning", "Alert", "2026-09-24T20:00:00-07:00",
                        "CAZ286") + "]}";
        assertTrue(FireAlerts.parse(body, NOW).isEmpty());
    }

    @Test
    public void otherEventsAndOtherCodesAreIgnored() {
        final String body = "{\"features\":["
                + alert("Heat Advisory", "Alert", "2026-09-25T22:00:00-07:00", "CAZ548")
                + "," + alert("Red Flag Warning", "Alert", "2026-09-25T22:00:00-07:00",
                        "CAC037", "CAZ548") + "]}";
        final Map<String, FireAlerts.Status> z = FireAlerts.parse(body, NOW);
        assertEquals(1, z.size());
        assertTrue(z.containsKey("CAZ548"));
        assertTrue(FireAlerts.parse("not json", NOW).isEmpty());
        assertTrue(FireAlerts.parse("{\"features\":[]}", NOW).isEmpty());
    }

    @Test
    public void theNamesQuerySendsOnlyZoneNumbers() {
        final String u = FireZones.idsUrl(Arrays.asList("NVZ420", "x') OR 1=1", "CA548"));
        assertTrue(u, u.contains("%27NV420%27%2C%27CA548%27"));
        assertTrue(!u.contains("OR+1"));
        assertNull(FireZones.idsUrl(Arrays.asList("nope")));
    }

    @Test
    public void aHoleStaysWithItsZone() {
        // An outer ring clockwise, a hole counterclockwise inside it, and a second
        // outer ring off to the side: two polygons, the first with its hole.
        final String body = "{\"features\":[{\"attributes\":{\"state_zone\":\"CA001\"},"
                + "\"geometry\":{\"rings\":["
                + "[[-118,34],[-118,35],[-117,35],[-117,34],[-118,34]],"
                + "[[-117.6,34.4],[-117.4,34.4],[-117.4,34.6],[-117.6,34.6],[-117.6,34.4]],"
                + "[[-116,34],[-116,35],[-115,35],[-115,34],[-116,34]]]}}]}";
        final List<FireZones.Zone> z = FireZones.parse(body);
        final List<List<double[][]>> polys = z.get(0).polygons();
        assertEquals(2, polys.size());
        assertEquals(2, polys.get(0).size());
        assertEquals(1, polys.get(1).size());
    }
}
