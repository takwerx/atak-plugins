package com.atakmap.android.sitrep.net;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.fail;

import org.json.JSONObject;
import org.junit.Test;

/** Row shapes copied from live data.cityofchicago.org answers, 2026-11-16. */
public class SocrataTest {

    private static final double D = 1e-9;

    /** Chicago crashes, cooling and warming centers, Seattle and Austin: GeoJSON. */
    @Test
    public void geoJsonPoint() throws Exception {
        final JSONObject row = new JSONObject("{\"crash_date\":\"2026-11-16T00:05:00.000\","
                + "\"latitude\":\"41.7892590001\",\"longitude\":\"-87.6292853787\","
                + "\"location\":{\"type\":\"Point\",\"coordinates\":[-87.629285378653,41.789259000096]}}");
        final double[] at = Socrata.latLon(row, "location");
        assertArrayEquals(new double[] { 41.789259000096, -87.629285378653 }, at, D);
    }

    /** Chicago 311: the column is the legacy shape, numbers as text. Read as GeoJSON only, every row was dropped. */
    @Test
    public void legacyLocation() throws Exception {
        final JSONObject row = new JSONObject("{\"sr_type\":\"Sign Repair Request - All Other Signs\","
                + "\"latitude\":\"41.765458721526\",\"longitude\":\"-87.576367083884\","
                + "\"location\":{\"latitude\":\"41.765458721526\",\"longitude\":\"-87.576367083884\","
                + "\"human_address\":\"{\\\"address\\\": \\\"\\\", \\\"city\\\": \\\"\\\"}\"}}");
        final double[] at = Socrata.latLon(row, "location");
        assertArrayEquals(new double[] { 41.765458721526, -87.576367083884 }, at, D);
    }

    /** Some 311 rows (aircraft noise) have no coordinates at all. */
    @Test
    public void noCoordinates() throws Exception {
        assertNull(Socrata.latLon(new JSONObject("{\"sr_type\":\"Aircraft Noise Complaint\"}"), "location"));
        assertNull(Socrata.latLon(new JSONObject("{\"location\":{\"human_address\":\"{}\"}}"), "location"));
    }

    /** The point column empty but the plain columns filled: use the columns. */
    @Test
    public void plainColumnsWhenThePointColumnIsMissing() throws Exception {
        final JSONObject row = new JSONObject("{\"latitude\":\"41.88\",\"longitude\":\"-87.63\"}");
        assertArrayEquals(new double[] { 41.88, -87.63 }, Socrata.latLon(row, "location"), D);
    }

    @Test
    public void unusableCoordinatesAreSkipped() throws Exception {
        assertNull(Socrata.latLon(new JSONObject("{\"location\":{\"type\":\"Point\",\"coordinates\":[0,0]}}"), "location"));
        assertNull(Socrata.latLon(new JSONObject("{\"latitude\":\"95\",\"longitude\":\"-87\"}"), "location"));
        assertNull(Socrata.latLon(new JSONObject("{\"latitude\":\"x\",\"longitude\":\"y\"}"), "location"));
        assertNull(Socrata.latLon(null, "location"));
    }

    /** SoQL wants the north-west corner first. */
    @Test
    public void withinBoxOrder() throws Exception {
        assertEquals("within_box(location, 42.030000, -87.940000, 41.640000, -87.520000)",
                Socrata.withinBox("location", 41.64, -87.94, 42.03, -87.52));
    }

    @Test
    public void badFieldNamesAreRefused() {
        try {
            Socrata.withinBox("location) OR (1=1", 1, 2, 3, 4);
            fail("a field name with SoQL in it was accepted");
        } catch (java.io.IOException expected) {
            // good
        }
    }
}
