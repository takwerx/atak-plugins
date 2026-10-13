package com.atakmap.android.atmosphere;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.atakmap.android.atmosphere.data.Avalanche;

import org.junit.Test;

import java.util.List;

public class AvalancheTest {

    private static final String BODY = "{\"type\":\"FeatureCollection\",\"features\":["
            + "{\"type\":\"Feature\",\"id\":3004,\"properties\":{\"name\":\"Bridgeport\",\"center\":\"Bridgeport Avalanche Center\","
            + "\"center_link\":\"https://bridgeportavalanchecenter.org/\",\"link\":\"https://bridgeportavalanchecenter.org/avalanche-forecast/\","
            + "\"state\":\"CA\",\"danger\":\"no rating\",\"danger_level\":-1,\"color\":\"#888888\",\"travel_advice\":\"Watch for signs.\","
            + "\"start_date\":\"2026-04-13T14:00:00\",\"end_date\":\"2026-11-01T14:30:00\",\"off_season\":true,\"warning\":{\"product\":null}},"
            + "\"geometry\":{\"type\":\"Polygon\",\"coordinates\":[[[-119.5,38.2],[-119.0,38.2],[-119.0,38.6],[-119.5,38.6],[-119.5,38.2]]]}},"
            + "{\"type\":\"Feature\",\"id\":2856,\"properties\":{\"name\":\"Central Cascades\",\"center\":\"Central Oregon Avalanche Center\","
            + "\"link\":\"https://coavalanche.org/\",\"state\":\"OR\",\"danger\":\"considerable\",\"danger_level\":3,\"color\":\"#f7931e\","
            + "\"travel_advice\":\"Careful snowpack evaluation.\",\"start_date\":null,\"end_date\":null,\"off_season\":false,"
            + "\"warning\":{\"product\":{\"title\":\"Avalanche Warning\"}}},"
            + "\"geometry\":{\"type\":\"MultiPolygon\",\"coordinates\":[[[[-122,44],[-121,44],[-121,45],[-122,45],[-122,44]]]]}}]}";

    @Test
    public void zonesReadWithTheirDangerAndSeason() {
        final List<Avalanche.Zone> z = Avalanche.parse(BODY);
        assertEquals(2, z.size());
        final Avalanche.Zone off = z.get(0);
        assertEquals(3004L, off.id);
        assertEquals("Bridgeport", off.name);
        assertEquals(-1, off.dangerLevel);
        assertTrue(off.offSeason);
        assertEquals("No rating, off season", off.dangerLine());
        assertEquals(0xFF888888, off.color);
        assertNull(off.warning);
        assertEquals("Apr 13, 2:00 PM", Avalanche.when(off.startDate));
        final Avalanche.Zone on = z.get(1);
        assertEquals("Considerable (3 of 5)", on.dangerLine());
        assertEquals(0xFFF7931E, on.color);
        assertEquals("Avalanche Warning", on.warning);
        assertEquals("", on.startDate);
        assertEquals("MultiPolygon", on.geometry.optString("type"));
    }

    @Test
    public void scaleAndColors() {
        assertEquals("Extreme", Avalanche.levelLabel(5));
        assertEquals("No rating", Avalanche.levelLabel(-1));
        assertEquals(0xFF50B848, Avalanche.levelColor(1));
        assertEquals(0xFF888888, Avalanche.levelColor(0));
        assertEquals(0xFF123456, Avalanche.parseColor("#123456", 0));
        assertEquals(7, Avalanche.parseColor("orange", 7));
        assertEquals(0, Avalanche.parse("").size());
    }
}
