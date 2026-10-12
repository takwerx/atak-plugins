package com.atakmap.android.atmosphere;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.atakmap.android.atmosphere.data.FuelMoisture;

import org.junit.Test;

import java.util.List;

public class FuelMoistureTest {

    private static final String BODY = "{\"allFuelTypes\":[\"1-Hour\",\"Chamise\"],\"state\":\"CA\",\"stationsData\":["
            + "{\"coordinates\":{\"lat\":33.035,\"lng\":-117.1033},\"stationName\":\"4S Ranch\",\"fuelData\":["
            + "{\"fuelType\":\"Chamise\",\"lastSampledDate\":\"Mon, 05 Oct 2026 00:00:00 GMT\",\"lastSampledValue\":58.0,\"lastSampledYear\":2026,\"yearsSampled\":[2024,2025,2026]},"
            + "{\"fuelType\":\"1000-Hour\",\"lastSampledDate\":\"Wed, 07 Oct 2026 00:00:00 GMT\",\"lastSampledValue\":9.0,\"lastSampledYear\":2026,\"yearsSampled\":[2026]},"
            + "{\"fuelType\":\"Manzanita, Greenleaf\",\"lastSampledDate\":\"Tue, 10 Mar 2015 00:00:00 GMT\",\"lastSampledValue\":104.5,\"lastSampledYear\":2015,\"yearsSampled\":[2010,2015]}]},"
            + "{\"coordinates\":{\"lat\":0,\"lng\":0},\"stationName\":\"Nowhere\",\"fuelData\":[{\"fuelType\":\"Chamise\",\"lastSampledDate\":\"Mon, 05 Oct 2026 00:00:00 GMT\",\"lastSampledValue\":50.0}]},"
            + "{\"coordinates\":{\"lat\":34.1,\"lng\":-118.2},\"stationName\":\"Empty\",\"fuelData\":[]}]}";

    @Test
    public void parsesSitesNewestFirstAndDropsTheUnplaced() {
        final List<FuelMoisture.Site> sites = FuelMoisture.parse(BODY);
        assertEquals(1, sites.size());
        final FuelMoisture.Site s = sites.get(0);
        assertEquals("4S Ranch", s.name);
        assertEquals(3, s.samples.size());
        assertEquals("1000-Hour", s.samples.get(0).fuel);
        assertTrue(s.samples.get(0).dead());
        assertEquals("Chamise", s.newest(0).fuel);
        assertEquals(58.0, s.newest(0).pct, 1e-9);
        assertEquals(2024, s.newest(0).firstYear);
        assertEquals("1000-Hour", s.newest(1).fuel);
        assertEquals("1000-Hour", s.newest(2).fuel);
        assertTrue(FuelMoisture.parse("{}").isEmpty());
        assertTrue(FuelMoisture.parse("not json").isEmpty());
    }

    @Test
    public void levelsAndNames() {
        assertEquals(0, FuelMoisture.level(60));
        assertEquals(1, FuelMoisture.level(61));
        assertEquals(1, FuelMoisture.level(79));
        assertEquals(2, FuelMoisture.level(80));
        assertEquals(3, FuelMoisture.level(100));
        assertEquals("Manzanita", FuelMoisture.shortFuel("Manzanita, Greenleaf"));
        assertEquals("Chamise", FuelMoisture.shortFuel("Chamise"));
        assertTrue(FuelMoisture.isDead("10-Hour"));
        assertTrue(FuelMoisture.isDead("Duff"));
        assertFalse(FuelMoisture.isDead("Sagebrush, Big"));
        assertEquals(0, FuelMoisture.level("10-Hour", 6));
        assertEquals(1, FuelMoisture.level("1-Hour", 7));
        assertEquals(3, FuelMoisture.level("10-Hour", 16));
        assertEquals(0, FuelMoisture.level("100-Hour", 10));
        assertEquals(2, FuelMoisture.level("1000-Hour", 20));
        assertEquals(3, FuelMoisture.level("1000-Hour", 23));
        assertEquals(-1, FuelMoisture.level("Duff", 30));
        assertEquals(FuelMoisture.DEAD_COLOR, FuelMoisture.color("Duff", 30));
        assertEquals(FuelMoisture.COLORS[0], FuelMoisture.color("Chamise", 55));
        assertNull(FuelMoisture.thresholds("Duff"));
    }
}
