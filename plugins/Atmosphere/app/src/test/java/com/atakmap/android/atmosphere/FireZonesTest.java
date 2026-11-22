package com.atakmap.android.atmosphere;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.atakmap.android.atmosphere.data.FireZones;

import org.junit.Test;

import java.util.List;

/** The zone join: a square, a square with a hole, and the service's own shape. */
public class FireZonesTest {

    /** Two zones side by side, the second with a hole in its middle. */
    private static final String TWO = "{\"features\":[" +
            "{\"attributes\":{\"state_zone\":\"CA001\",\"cwa\":\"LOX\",\"name\":\"West\"," +
            "\"idp_source\":\"fz16ap26\"},\"geometry\":{\"rings\":[[[-118,34],[-117,34]," +
            "[-117,35],[-118,35],[-118,34]]]}}," +
            "{\"attributes\":{\"state_zone\":\"CA002\",\"cwa\":\"SGX\",\"name\":\"East\"}," +
            "\"geometry\":{\"rings\":[[[-117,34],[-116,34],[-116,35],[-117,35],[-117,34]]," +
            "[[-116.6,34.4],[-116.4,34.4],[-116.4,34.6],[-116.6,34.6],[-116.6,34.4]]]}}" +
            "]}";

    @Test
    public void parsesZonesWithTheirOutlines() {
        final List<FireZones.Zone> z = FireZones.parse(TWO);
        assertEquals(2, z.size());
        assertEquals("CA001", z.get(0).id);
        assertEquals("LOX", z.get(0).cwa);
        assertEquals("fz16ap26", z.get(0).source);
        assertEquals("CA001 — West (LOX)", z.get(0).label());
    }

    @Test
    public void findsTheZoneAPointStandsIn() {
        final List<FireZones.Zone> z = FireZones.parse(TWO);
        assertEquals("CA001", FireZones.at(34.5, -117.5, z).id);
        assertEquals("CA002", FireZones.at(34.2, -116.2, z).id);
        assertNull(FireZones.at(36.0, -117.5, z));
    }

    @Test
    public void aHoleCountsBackOut() {
        final List<FireZones.Zone> z = FireZones.parse(TWO);
        assertNull(FireZones.at(34.5, -116.5, z));
        assertEquals("CA002", FireZones.at(34.5, -116.3, z).id);
    }

    @Test
    public void skipsWhatHasNoOutline() {
        assertTrue(FireZones.parse("{\"features\":[{\"attributes\":{\"state_zone\":\"CA9\"}}]}")
                .isEmpty());
        assertTrue(FireZones.parse("").isEmpty());
        assertTrue(FireZones.parse("not json").isEmpty());
    }

    @Test
    public void theUrlAsksForTheRadiusInMiles() {
        final String u = FireZones.nearUrl(33.576, -117.241, 250);
        assertTrue(u.contains("distance=250"));
        assertTrue(u.contains("units=esriSRUnit_StatuteMile"));
        assertTrue(u.contains("geometry=-117.241,33.576"));
        assertTrue(u.contains("outSR=4326"));
    }
}
