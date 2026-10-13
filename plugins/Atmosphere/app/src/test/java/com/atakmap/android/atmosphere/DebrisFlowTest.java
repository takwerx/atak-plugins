package com.atakmap.android.atmosphere;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.atakmap.android.atmosphere.data.DebrisFlow;

import org.junit.Test;

import java.util.List;

public class DebrisFlowTest {

    private static final String FIRES = "{\"type\":\"FeatureCollection\",\"features\":["
            + "{\"type\":\"Feature\",\"geometry\":{\"type\":\"Point\",\"coordinates\":[-117.75,34.25]},\"properties\":{"
            + "\"fire\":\"Bridge\",\"location\":\"Angeles National Forest\",\"status\":\"Most Recent Version: 1.0\",\"Fire_ID\":\"Bridge_2024-09-08_v1.0\","
            + "\"Rainfall\":\"24mm\",\"start_date\":1725753600000,\"assessment_date\":1727740800000,\"ScienceBaseURL\":\"https://www.sciencebase.gov/catalog/item/x\"}},"
            + "{\"type\":\"Feature\",\"geometry\":{\"type\":\"Point\",\"coordinates\":[-118.1,34.2]},\"properties\":{"
            + "\"fire\":\"Eaton\",\"start_date\":1736208000000,\"assessment_date\":1737072000000,\"Rainfall\":\"24mm\"}}]}";

    private static final String BASINS = "{\"type\":\"FeatureCollection\",\"exceededTransferLimit\":true,\"features\":["
            + "{\"type\":\"Feature\",\"geometry\":{\"type\":\"Polygon\",\"coordinates\":[[[-117.76,34.26],[-117.74,34.26],[-117.74,34.24],[-117.76,34.26]]]},"
            + "\"properties\":{\"BCH_Legend\":\"High\",\"BP_Legend\":\"60-80%\",\"BV_Legend\":\"10,000-100,000\",\"fire_id\":\"brd2024\","
            + "\"start_date\":1725753600000,\"assessment_date\":1727740800000}},"
            + "{\"type\":\"Feature\",\"geometry\":{\"type\":\"Polygon\",\"coordinates\":[[[-117.7,34.2],[-117.6,34.2],[-117.6,34.1],[-117.7,34.2]]]},"
            + "\"properties\":{\"BCH_Legend\":\"NULL\",\"fire_id\":\"brd2024\",\"start_date\":1725753600000,\"assessment_date\":1727740800000}}]}";

    @Test
    public void firesBasinsAndTheJoin() {
        final List<DebrisFlow.Fire> fires = DebrisFlow.parseFires(FIRES);
        assertEquals(2, fires.size());
        assertEquals("Bridge", fires.get(0).name);
        assertEquals("24mm", fires.get(0).rainfall);
        final List<DebrisFlow.Basin> basins = DebrisFlow.parseBasins(BASINS);
        assertEquals(2, basins.size());
        assertEquals(2, basins.get(0).hazard);
        assertEquals(-1, basins.get(1).hazard);
        assertEquals("60-80%", basins.get(0).likelihood);
        final double[] at = DebrisFlow.firstPoint(basins.get(0).geometry);
        assertNotNull(at);
        assertEquals(34.26, at[0], 1e-9);
        final DebrisFlow.Fire f = DebrisFlow.fireFor(fires, basins.get(0).start, basins.get(0).assessed, at[0], at[1]);
        assertNotNull(f);
        assertEquals("Bridge", f.name);
        assertNull(DebrisFlow.fireFor(fires, 1L, 2L, at[0], at[1]));
        assertEquals("24 mm/h (about 0.9 in/h)", DebrisFlow.rainfallWords("24mm"));
        assertTrue(DebrisFlow.distanceMeters(34.0, -117.0, 34.0, -118.0) > 90_000);
    }

    @Test
    public void urlsNameTheBoxAndTheDate() {
        final String b = DebrisFlow.basinsUrl(-118.123456, 33.5, -116.5, 35.0, "2023-10-11", 2000);
        assertTrue(b.startsWith("https://" + DebrisFlow.HOST + "/arcgis/rest/services/ls/pwfdf/MapServer/4/query"));
        assertTrue(b.contains("&geometry=-118.123,33.500,-116.500,35.000&geometryType=esriGeometryEnvelope"));
        assertTrue(b.contains("DATE%20%272023-10-11%27"));
        assertTrue(b.endsWith("&resultOffset=2000"));
        assertTrue(DebrisFlow.perimetersUrl(-118, 33.5, -116.5, 35, "2023-10-11").contains("/MapServer/9/query"));
        assertTrue(DebrisFlow.FIRES_URL.contains("/MapServer/0/query"));
    }
}
