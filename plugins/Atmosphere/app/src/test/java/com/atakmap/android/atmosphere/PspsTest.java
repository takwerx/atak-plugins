package com.atakmap.android.atmosphere;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.atakmap.android.atmosphere.data.Psps;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

public class PspsTest {

    private static String resource(String name) throws IOException {
        try (InputStream in = PspsTest.class.getClassLoader().getResourceAsStream(name)) {
            final ByteArrayOutputStream out = new ByteArrayOutputStream();
            final byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0)
                out.write(buf, 0, n);
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    @Test
    public void theFlaggedCountiesOfTheNightOfTheFirst() throws IOException {
        // 2026-10-01 04:29Z: SCE considering Riverside circuits, SDG&E forecasting San Diego.
        final List<Psps.Area> c = Psps.parseCounties(resource("psps_counties_2026-10-01.json"));
        assertEquals(2, c.size());
        assertEquals("Riverside", c.get(0).county);
        assertEquals(Psps.POSSIBLE, c.get(0).kind);
        assertEquals("MultiPolygon", c.get(1).geometry.optString("type"));
        assertEquals(0, Psps.parseAreas(resource("psps_areas_empty.json")).size());
    }

    @Test
    public void areasKeepTheirStatusAndDropMonitoring() {
        final String ring = "{\"type\":\"Polygon\",\"coordinates\":[[[-117,33.5],[-116.9,33.5],[-116.9,33.6],[-117,33.5]]]}";
        final String body = "{\"type\":\"FeatureCollection\",\"features\":["
                + area("De-Energized", "SCE", ring) + "," + area("Downstream", "SCE", ring) + ","
                + area("Re-Energized", "SDG&E", ring) + "," + area("Monitoring", "PG&E", ring) + ","
                + "{\"type\":\"Feature\",\"properties\":{\"Status\":\"De-Energized\"},\"geometry\":null}]}";
        final List<Psps.Area> a = Psps.parseAreas(body);
        assertEquals(3, a.size());
        assertEquals(Psps.OFF, a.get(0).kind);
        assertEquals(Psps.DOWNSTREAM, a.get(1).kind);
        assertEquals(Psps.RESTORED, a.get(2).kind);
        assertEquals("SDG&E", a.get(2).utility);
        assertEquals("PSPS Incident 10-01-2026", a.get(0).event);
        assertNull(Psps.kind("Monitoring"));
        assertEquals(0, Psps.parseAreas("<html>").size());
    }

    private static String area(String status, String utility, String geometry) {
        return "{\"type\":\"Feature\",\"properties\":{\"County\":\"Riverside\",\"Status\":\"" + status
                + "\",\"UtilityCompany\":\"" + utility + "\",\"EventName\":\"PSPS Incident 10-01-2026\"},"
                + "\"geometry\":" + geometry + "}";
    }

    @Test
    public void freshnessReadsTheNewestStampAndTheRowCount() {
        final String body = "{\"features\":[{\"attributes\":{\"latest\":1790828195610,\"counties\":58}}]}";
        final long[] f = Psps.freshness(body);
        assertEquals(1790828195610L, f[0]);
        assertEquals(58, f[1]);
        assertEquals(0, Psps.freshness("{}")[0]);
        assertEquals(0, Psps.freshness("nope")[1]);
    }

    @Test
    public void urlsAndColors() {
        assertTrue(Psps.FRESHNESS_URL.startsWith("https://services.arcgis.com/BLN4oKB0N1YSgvY8/"));
        assertTrue(Psps.FRESHNESS_URL.contains("outStatistics=%5B%7B"));
        assertTrue(Psps.COUNTIES_URL.contains("where=Impacted%3D%27Yes%27"));
        assertEquals(0xFFD50000, Psps.color(Psps.OFF));
        assertEquals(0xFF000000, Psps.textColor(Psps.POSSIBLE));
        assertEquals(0xFFFFFFFF, Psps.textColor(Psps.OFF));
    }
}
