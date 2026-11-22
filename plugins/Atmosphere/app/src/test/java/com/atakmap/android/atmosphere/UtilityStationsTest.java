package com.atakmap.android.atmosphere;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.atakmap.android.atmosphere.data.Raws;
import com.atakmap.android.atmosphere.data.UtilityStations;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.util.Arrays;
import java.util.List;

public class UtilityStationsTest {

    private static String resource(String name) throws IOException {
        try (InputStream in = UtilityStationsTest.class.getClassLoader()
                .getResourceAsStream(name)) {
            assertTrue("missing test resource " + name, in != null);
            final ByteArrayOutputStream out = new ByteArrayOutputStream();
            final byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0)
                out.write(buf, 0, n);
            return new String(out.toByteArray(), Charset.forName("UTF-8"));
        }
    }

    @Test
    public void theRequestAsksForTheFourNetworksInMilesAroundThePoint() {
        final String u = UtilityStations.nearUrl(33.0, -116.9, 25);
        assertTrue(u, u.startsWith("https://" + UtilityStations.HOST + "/"));
        assertTrue(u, u.contains("Synoptic_WeatherStations_Current_view"));
        assertTrue(u, u.contains("units=esriSRUnit_StatuteMile"));
        assertTrue(u, u.contains("distance=25"));
        assertTrue(u, u.contains("geometry=-116.9,33"));
        // SCE, SDG&E, PG&E, HPWREN -- and not RAWS, whose source is NIFC.
        assertTrue(u, u.contains("MNET_ID+IN+%28231%2C139%2C229%2C81%29"));
        assertFalse("never ask for every column", u.contains("outFields=*"));
        // Dense networks: the radius is capped whatever the RAWS radius is.
        assertTrue(UtilityStations.nearUrl(33.0, -116.9, 250).contains(
                "distance=" + UtilityStations.MAX_MILES));
    }

    @Test
    public void aStationReadsInMphAndFahrenheitWithItsNetworkInItsName() throws IOException {
        final List<Raws.Station> all = UtilityStations.parse(resource("utility_ramona.json"));
        assertEquals(4, all.size());
        final Raws.Station s = all.get(0);
        assertEquals("U:LOGSD", s.wxId);
        assertEquals("LOGSD", s.mesowestId);
        // SDG&E names a station by its place alone; the pill says whose it is.
        assertEquals("SDG&E Longs Gulch", s.name);
        assertEquals("SDG&E", s.network);
        assertTrue(s.isUtility());
        assertEquals(5.94, s.windMph, 0.001);
        assertEquals(9.79, s.gustMph, 0.001);
        assertEquals(129.2, s.windFromDeg, 0.001);
        assertEquals(21.39, s.relativeHumidity, 0.001);
        assertEquals(96.7, s.airTempF, 0.001);
        assertEquals(1630, s.elevation);
        assertEquals(1791238200000L, s.observedAt);
        // Nothing a utility station does not measure is made up.
        assertTrue(Double.isNaN(s.fuelMoisture));
        assertTrue(Double.isNaN(s.gustFromDeg));
    }

    @Test
    public void idsAreKeptApartFromNifcs() {
        assertEquals("LOGSD", UtilityStations.stidOf("U:LOGSD"));
        assertNull(UtilityStations.stidOf("045447"));
        final String u = UtilityStations.byIdUrl(Arrays.asList("LOGSD", "O'BRIEN"));
        assertTrue(u, u.contains("STID+IN+%28%27LOGSD%27%2C%27O%27%27BRIEN%27%29"));
    }

    @Test
    public void onlyCaliforniaIsAskedAbout() {
        assertTrue(UtilityStations.covers(34.39, -118.54));     // Santa Clarita
        assertTrue(UtilityStations.covers(40.8, -124.1));       // Eureka
        assertFalse(UtilityStations.covers(46.87, -114.0));     // Missoula
        assertFalse(UtilityStations.covers(33.45, -112.07));    // Phoenix
    }

    @Test
    public void aBrokenAnswerIsNoStations() {
        assertTrue(UtilityStations.parse("").isEmpty());
        assertTrue(UtilityStations.parse("<html>").isEmpty());
        assertTrue(UtilityStations.parse("{\"error\":{\"code\":400}}").isEmpty());
    }
}
