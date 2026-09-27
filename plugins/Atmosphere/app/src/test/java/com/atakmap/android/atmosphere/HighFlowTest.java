package com.atakmap.android.atmosphere;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.atakmap.android.atmosphere.data.HighFlow;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

public class HighFlowTest {

    /** One analysis reach (the Wilmington NC sample, 2026-09-27) and one 5-day reach. */
    private static final String BODY = "{\"type\":\"FeatureCollection\",\"exceededTransferLimit\":true,\"features\":["
            + "{\"type\":\"Feature\",\"geometry\":{\"type\":\"LineString\",\"coordinates\":[[-77.82862,34.23840],[-77.83058,34.23584],[-77.83146,34.23510]]},"
            + "\"properties\":{\"feature_id\":\"10520174\",\"name\":\"Unnamed Stream\",\"recur_cat\":\"\\u003e50\",\"max_flow\":0.35314699,"
            + "\"high_water_threshold\":0.2,\"flow_2yr\":1.38,\"flow_5yr\":3.49,\"flow_10yr\":5.73,\"flow_25yr\":9.8,\"flow_50yr\":13.92,"
            + "\"strm_order\":1,\"valid_time\":\"2026-09-27 05:00:00\",\"reference_time\":\"2026-09-27 05:00:00 UTC\",\"state\":\"NC\"}},"
            + "{\"type\":\"Feature\",\"geometry\":{\"type\":\"MultiLineString\",\"coordinates\":[[[-106.55,35.40],[-106.52,35.38]]]},"
            + "\"properties\":{\"feature_id\":\"17847619\",\"name\":\"Rio Grande\",\"recur_cat_5day\":\"4\",\"maxflow_5day_cfs\":12345.6,"
            + "\"high_water_threshold\":8000,\"flow_2yr\":9100,\"flow_5yr\":11000,\"flow_10yr\":12000,\"flow_25yr\":12300,\"flow_50yr\":15000,"
            + "\"strm_order\":7,\"reference_time\":\"2026-09-26 18:00:00 UTC\",\"state\":\"NM\"}},"
            + "{\"type\":\"Feature\",\"geometry\":null,\"properties\":{\"feature_id\":\"9\",\"recur_cat\":\"10\"}}]}";

    @Test
    public void parsesBothLayersShapes() {
        final HighFlow.Answer a = HighFlow.parse(BODY);
        assertTrue(a.truncated);
        assertEquals(2, a.reaches.size());

        final HighFlow.Reach small = a.reaches.get(0);
        assertEquals(HighFlow.Category.HIGH_WATER, small.category);
        assertEquals("", small.name);
        assertEquals("Unnamed stream", small.placeName());
        assertEquals("Stream running high: Unnamed stream (over the high-water mark)", small.title());
        assertEquals("", small.label());
        assertEquals(0.353147, small.flowCfs, 1e-6);
        assertEquals("LineString", small.geometry.optString("type"));
        final String d = small.details(HighFlow.HORIZON_NOW);
        assertTrue(d, d.startsWith("How high: Over the high-water mark (under a 1-in-2-year flow)\nFlow now: 0.4 cfs\nHigh-water mark: 0.2 cfs"));
        assertTrue(d, d.contains("\n1-in-50-year flow: 14 cfs\nStream size: order 1 (1 is the smallest headwater)\nValid: "));
        assertTrue(d, d.contains("\nState: NC\nStream ID: 10520174\nSource: "));

        final HighFlow.Reach river = a.reaches.get(1);
        assertEquals(HighFlow.Category.YR25, river.category);
        assertEquals(0xFF9E00FF, river.category.color);
        assertEquals(12345.6, river.flowCfs, 1e-9);
        assertEquals("Rio Grande: 1-in-25-year flow", river.label());
        assertEquals("Stream running high: Rio Grande (1-in-25-year flow)", river.title());
        final String r = river.details(HighFlow.HORIZON_5DAY);
        assertTrue(r, r.contains("\nPeak flow, next 5 days: 12,346 cfs\nHigh-water mark: 8,000 cfs\n"));
        assertFalse(r, r.contains("Valid:"));
        assertTrue(r, r.contains("\nModel run: "));
    }

    @Test
    public void categoriesWorstAndWords() {
        assertEquals(HighFlow.Category.YR50, HighFlow.Category.of("2"));
        assertEquals(HighFlow.Category.YR2, HighFlow.Category.of("50"));
        assertEquals(HighFlow.Category.UNKNOWN, HighFlow.Category.of("Not Availa"));
        assertEquals(HighFlow.Category.UNKNOWN, HighFlow.Category.of(null));
        assertEquals(HighFlow.Category.UNKNOWN, HighFlow.Category.of("weird"));
        final HighFlow.Answer a = HighFlow.parse(BODY);
        assertEquals(HighFlow.Category.YR25, HighFlow.worst(a.reaches));
        assertNull(HighFlow.worst(new ArrayList<HighFlow.Reach>()));
        final List<HighFlow.Reach> none = HighFlow.parse("{\"features\":[]}").reaches;
        assertTrue(none.isEmpty());
        assertTrue(HighFlow.parse("not json").reaches.isEmpty());
        assertTrue(HighFlow.parse("{\"error\":{\"code\":500}}").reaches.isEmpty());
    }

    @Test
    public void urlAndNumbers() {
        final String now = HighFlow.url(HighFlow.HORIZON_NOW, -78.2, 33.9, -77.6, 34.4, 0.0012);
        assertTrue(now, now.startsWith("https://maps.water.noaa.gov/server/rest/services/nwm/ana_high_flow_magnitude/MapServer/0/query?geometry=-78.200,33.900,-77.600,34.400&"));
        assertTrue(now, now.contains("&outFields=*&orderByFields=strm_order%20DESC&resultRecordCount=2000&outSR=4326"));
        assertTrue(now, now.contains("&maxAllowableOffset=0.00120&f=geojson"));
        final String day5 = HighFlow.url(HighFlow.HORIZON_5DAY, -1, 2, 3, 4, 0);
        assertTrue(day5, day5.contains("/mrf_nbm_5day_max_high_flow_magnitude/MapServer/0/query?"));
        assertTrue(day5, day5.contains("&maxAllowableOffset=0.00001&"));
        assertEquals("12,300 cfs", HighFlow.cfs(12300.4));
        assertEquals("350 cfs", HighFlow.cfs(350));
        assertEquals("0.4 cfs", HighFlow.cfs(0.353));
        assertEquals("", HighFlow.cfs(Double.NaN));
        assertEquals("", HighFlow.localTime(""));
        assertEquals("garbage", HighFlow.localTime("garbage"));
        assertFalse(HighFlow.localTime("2026-09-27 05:00:00 UTC").contains("UTC"));
    }
}
