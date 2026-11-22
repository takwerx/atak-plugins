package com.atakmap.android.atmosphere;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.atakmap.android.atmosphere.data.WpcEro;

import org.junit.Test;

import java.util.List;

public class WpcEroTest {

    @Test
    public void areasReadWithWpcsWords() {
        final String body = "{\"type\":\"FeatureCollection\",\"features\":["
                + "{\"type\":\"Feature\",\"properties\":{\"dn\":2,\"outlook\":\"Slight (At Least 15%)\","
                + "\"valid_time\":\"12Z 09/27/26 - 12Z 09/28/26\",\"issue_time\":\"2026-09-26 20:27:00\"},"
                + "\"geometry\":{\"type\":\"Polygon\",\"coordinates\":[[[-100,30],[-99,30],[-99,31],[-100,31],[-100,30]]]}},"
                + "{\"type\":\"Feature\",\"properties\":{\"dn\":0},\"geometry\":null}]}";
        final List<WpcEro.Area> a = WpcEro.parse(body, 2);
        assertEquals(1, a.size());
        assertEquals("Slight", a.get(0).label());
        assertEquals(0xFFFFFE00, a.get(0).color());
        assertEquals("Day 2 excessive rainfall: Slight", a.get(0).title());
        assertEquals("12Z 09/27/26 - 12Z 09/28/26", a.get(0).validTime);
        assertEquals("High", WpcEro.label(4));
        assertEquals("at least 70%", WpcEro.meaning(4));
        assertTrue(WpcEro.url(3).contains("MapServer/2/query"));
        assertEquals(0, WpcEro.parse("", 1).size());
    }
}
