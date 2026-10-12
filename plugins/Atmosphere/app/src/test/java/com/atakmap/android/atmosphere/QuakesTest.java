package com.atakmap.android.atmosphere;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.atakmap.android.atmosphere.data.Quakes;

import org.junit.Test;

import java.util.List;

public class QuakesTest {

    private static final String FEED = "{\"type\":\"FeatureCollection\",\"metadata\":{\"count\":3},\"features\":["
            + "{\"type\":\"Feature\",\"id\":\"ci1\",\"geometry\":{\"type\":\"Point\",\"coordinates\":[-117.9,33.27,11.3]},\"properties\":{"
            + "\"mag\":3.5,\"place\":\"31 km SW of Dana Point, CA\",\"time\":1791300000000,\"felt\":126,\"cdi\":3.1,\"mmi\":null,\"alert\":null,"
            + "\"status\":\"reviewed\",\"tsunami\":0,\"magType\":\"ml\",\"type\":\"earthquake\",\"types\":\",dyfi,origin,phase-data,\","
            + "\"url\":\"https://earthquake.usgs.gov/earthquakes/eventpage/ci1\",\"detail\":\"https://earthquake.usgs.gov/earthquakes/feed/v1.0/detail/ci1.geojson\"}},"
            + "{\"type\":\"Feature\",\"id\":\"us2\",\"geometry\":{\"type\":\"Point\",\"coordinates\":[155.5,-6.9,40]},\"properties\":{"
            + "\"mag\":6.2,\"place\":\"79 km SSE of Panguna, PNG\",\"time\":1791290000000,\"alert\":\"green\",\"status\":\"automatic\",\"tsunami\":1,"
            + "\"magType\":\"mww\",\"type\":\"earthquake\",\"types\":\",losspager,origin,shakemap,\",\"detail\":\"https://earthquake.usgs.gov/d/us2.geojson\"}},"
            + "{\"type\":\"Feature\",\"id\":\"q3\",\"geometry\":{\"type\":\"Point\",\"coordinates\":[-120,36,0]},\"properties\":{"
            + "\"mag\":1.1,\"type\":\"quarry blast\",\"time\":1791290000000}}]}";

    @Test
    public void parsesQuakesAndDropsBlasts() {
        final List<Quakes.Quake> q = Quakes.parse(FEED);
        assertEquals(2, q.size());
        assertEquals("M 3.5", q.get(0).magnitude());
        assertEquals(11.3, q.get(0).depthKm, 1e-9);
        assertEquals(126, q.get(0).felt);
        assertFalse(q.get(0).shakeMap);
        assertTrue(q.get(1).shakeMap);
        assertEquals(1, q.get(1).tsunami);
        assertEquals("green", q.get(1).alert);
        assertTrue(Quakes.parse("{}").isEmpty());
    }

    @Test
    public void agesIntensitiesAndContours() {
        final long now = 1_000_000_000_000L;
        assertEquals(0, Quakes.age(now - 30 * 60 * 1000L, now));
        assertEquals(1, Quakes.age(now - 5 * 3600 * 1000L, now));
        assertEquals(2, Quakes.age(now - 3 * 86400 * 1000L, now));
        assertEquals(3, Quakes.age(now - 20 * 86400 * 1000L, now));
        assertEquals("VI", Quakes.roman(6.2));
        assertEquals("Strong", Quakes.shaking(6.2));
        assertEquals(0xFFFF0000, Quakes.mmiColor(10));
        assertEquals(0xFFFFFF00, Quakes.mmiColor(7));
        final String detail = "{\"properties\":{\"products\":{\"shakemap\":[{\"contents\":{"
                + "\"download/cont_mi.json\":{\"url\":\"https://earthquake.usgs.gov/product/shakemap/us2/us/1/download/cont_mi.json\"}}}]}}}";
        assertEquals("https://earthquake.usgs.gov/product/shakemap/us2/us/1/download/cont_mi.json",
                Quakes.contoursUrl(detail));
        assertNull(Quakes.contoursUrl(detail.replace("https://earthquake.usgs.gov/", "https://evil.example/")));
        assertNull(Quakes.contoursUrl("{\"properties\":{\"products\":{}}}"));
        final String contours = "{\"type\":\"FeatureCollection\",\"features\":["
                + "{\"type\":\"Feature\",\"properties\":{\"value\":1.0},\"geometry\":{\"type\":\"MultiLineString\",\"coordinates\":[[[1,1],[2,2]]]}},"
                + "{\"type\":\"Feature\",\"properties\":{\"value\":5.0},\"geometry\":{\"type\":\"MultiLineString\",\"coordinates\":[[[1,1],[2,2]]]}}]}";
        final List<Quakes.Contour> c = Quakes.parseContours(contours);
        assertEquals(1, c.size());
        assertEquals(5.0, c.get(0).mmi, 1e-9);
        assertTrue(Quakes.url(1).endsWith("all_hour.geojson"));
        assertTrue(Quakes.url(2).endsWith("all_day.geojson"));
        assertTrue(Quakes.url(3).endsWith("all_week.geojson"));
        assertTrue(Quakes.url(4).endsWith("2.5_month.geojson"));
    }
}
