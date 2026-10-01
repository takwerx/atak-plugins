package com.atakmap.android.atmosphere;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.atakmap.android.atmosphere.data.BeachForecast;

import org.json.JSONObject;
import org.junit.Test;

import java.util.List;

public class BeachForecastTest {

    private static final String BODY = "{\"results\":["
            + "{\"layerId\":103,\"layerName\":\"SGX Day 1\",\"attributes\":{\"sitename\":\"WFO San Diego\",\"beachname\":\"San Diego County Area Beaches\","
            + "\"rip\":\"High\",\"uv\":\"Null\",\"surf\":\"2 to 4 feet with sets to 5 feet.\",\"wtemp\":\"70 to 74 degrees\",\"maxtemp\":\"Null\",\"weather\":\"Null\","
            + "\"winds\":\"Null\",\"period\":\"This afternoon through sunday\",\"tstorm\":\"None expected\",\"productdat\":\"9/26/2026\",\"producttim\":\"242 PM PDT\","
            + "\"srfprod\":\"https://forecast.weather.gov/product.php?site=sgx&product=srf\"},"
            + "\"geometry\":{\"rings\":[[[-117.3,32.5],[-117.3,33.2],[-117.1,33.2],[-117.1,32.5],[-117.3,32.5]]],\"spatialReference\":{\"wkid\":4326}}},"
            + "{\"layerId\":104,\"layerName\":\"SGX Day 2\",\"attributes\":{\"sitename\":\"WFO San Diego\",\"beachname\":\"San Diego County Area Beaches\","
            + "\"rip\":\"Moderate\",\"surf\":\"1 to 3 feet.\"},\"geometry\":{\"rings\":[[[-117.3,32.5],[-117.3,33.2],[-117.1,33.2],[-117.1,32.5],[-117.3,32.5]]]}}]}";

    @Test
    public void dayOneBeachesWithTomorrowJoined() {
        final List<BeachForecast.Beach> b = BeachForecast.parse(BODY);
        assertEquals(1, b.size());
        final BeachForecast.Beach sd = b.get(0);
        assertEquals("San Diego County Area Beaches", sd.name);
        assertEquals("High", sd.rip);
        assertEquals(BeachForecast.HIGH, sd.color());
        assertEquals("", sd.uv);                                   // "Null" is nothing
        assertEquals("Moderate", sd.tomorrowRip);
        assertEquals("Polygon", sd.geometry.optString("type"));
        final String d = sd.details();
        assertTrue(d.startsWith("Rip current risk: High\nSurf: 2 to 4 feet"));
        assertTrue(d.contains("Tomorrow: rip current Moderate, surf 1 to 3 feet."));
        assertTrue(d.contains("Issued: 242 PM PDT 9/26/2026"));
        assertTrue(!d.contains("UV index"));
        assertEquals(BeachForecast.MODERATE, BeachForecast.ripColor("Moderate Risk"));
        assertEquals(BeachForecast.UNKNOWN, BeachForecast.ripColor(""));
    }

    @Test
    public void esriRingsBecomeGeoJson() throws Exception {
        // Clockwise outer, counter-clockwise hole, then a second clockwise outer.
        final JSONObject esri = new JSONObject("{\"rings\":["
                + "[[0,0],[0,2],[2,2],[2,0],[0,0]],"
                + "[[0.5,0.5],[1.5,0.5],[1.5,1.5],[0.5,1.5],[0.5,0.5]],"
                + "[[5,5],[5,6],[6,6],[6,5],[5,5]]]}");
        final JSONObject g = BeachForecast.geoJson(esri);
        assertEquals("MultiPolygon", g.getString("type"));
        assertEquals(2, g.getJSONArray("coordinates").length());
        assertEquals(2, g.getJSONArray("coordinates").getJSONArray(0).length());   // outer + hole
        assertEquals(1, g.getJSONArray("coordinates").getJSONArray(1).length());
        assertEquals(null, BeachForecast.geoJson(new JSONObject("{\"rings\":[]}")));
        assertTrue(BeachForecast.url(-121, 32.4, -116.5, 35).contains("identify?geometry=-121.000,32.400,-116.500,35.000"));
    }
}
