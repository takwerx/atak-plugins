
package com.atakmap.android.atmosphere;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.atakmap.android.atmosphere.data.Raws;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.util.List;

public class RawsTest {

    private static String resource(String name) throws IOException {
        try (InputStream in = RawsTest.class.getClassLoader().getResourceAsStream(name)) {
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
    public void theRadiusGoesOutInMilesWithoutBeingConverted() {
        final String u = Raws.nearUrl(33.576, -117.241, 40);
        assertTrue(u, u.startsWith("https://" + Raws.HOST + "/"));
        // The service takes statute miles natively. Nothing here turns a distance
        // into kilometers and back, which is where that kind of bug lives.
        assertTrue(u, u.contains("units=esriSRUnit_StatuteMile"));
        assertTrue(u, u.contains("distance=40"));
        // GeoJSON is lon,lat; the call must not have them the other way round.
        assertTrue(u, u.contains("geometry=-117.241,33.576"));
        assertTrue(u, u.contains("f=json"));
        assertFalse("never ask for every column", u.contains("outFields=*"));
    }

    @Test
    public void aValueIsItsLeadingNumberWhateverUnitIsWrittenAfterIt() {
        // Every field arrives with its unit inside the string, written a different
        // way each time, with trailing spaces (measured 2026-09-25).
        assertEquals(62, Raws.number("62 % "), 0.001);
        assertEquals(5, Raws.number("5 mph"), 0.001);
        assertEquals(97, Raws.number("97 degrees "), 0.001);
        assertEquals(7.7, Raws.number("7.7 (unk)"), 0.001);
        assertEquals(38, Raws.number("38 deg. F"), 0.001);
        assertEquals(-4.5, Raws.number("-4.5 deg. F"), 0.001);
        // A station with nothing to say writes this, not null.
        assertTrue(Double.isNaN(Raws.number("NO DATA")));
        assertTrue(Double.isNaN(Raws.number("")));
        assertTrue(Double.isNaN(Raws.number(null)));
        assertTrue(Double.isNaN(Raws.number("null")));
    }

    @Test
    public void theLiveAnswerParsesIntoStations() throws IOException {
        // 53 stations within 40 miles of Murrieta, as the service answered on
        // 2026-09-25; thirteen of them had no reading at all, ten with the field
        // absent and three writing the literal "NO DATA".
        final List<Raws.Station> all = Raws.parse(resource("raws_near_murrieta.json"));
        assertEquals(53, all.size());

        int silent = 0;
        Raws.Station named = null;
        for (Raws.Station s : all) {
            if (s.silent())
                silent++;
            if (s.name.equals("SANTA ROSA PLATEAU"))
                named = s;
            // A station without a position is dropped, so every one kept has one.
            assertFalse(s.name, Double.isNaN(s.latitude) || Double.isNaN(s.longitude));
        }
        assertEquals("stations reporting nothing", 13, silent);

        assertTrue("SANTA ROSA PLATEAU missing", named != null);
        // The join to api.weather.gov and every other feed.
        assertFalse(named.mesowestId.isEmpty());
        assertEquals(named.mesowestId, named.mesowestId.toUpperCase(java.util.Locale.US));
        assertTrue("RH should be a percent: " + named.relativeHumidity,
                named.relativeHumidity >= 0 && named.relativeHumidity <= 100);
        assertTrue("wind should be mph: " + named.windMph,
                named.windMph >= 0 && named.windMph < 200);
        assertTrue("direction is degrees: " + named.windFromDeg,
                named.windFromDeg >= 0 && named.windFromDeg <= 360);
    }

    @Test
    public void aSilentStationIsSilentRatherThanCalm() throws IOException {
        final List<Raws.Station> all = Raws.parse(resource("raws_near_murrieta.json"));
        for (Raws.Station s : all) {
            if (!s.silent())
                continue;
            // The danger is drawing NO DATA as a reading of zero: a station with no
            // wind and no humidity is not a station reporting calm and damp.
            assertTrue(s.name, Double.isNaN(s.windMph));
            assertTrue(s.name, Double.isNaN(s.relativeHumidity));
        }
    }

    @Test
    public void aReadingOlderThanThreeHoursIsStale() {
        final long now = 1_790_000_000_000L;
        assertFalse(station(now - 30 * 60 * 1000L).stale(now));
        assertFalse(station(now - 2 * 3_600_000L).stale(now));
        assertTrue(station(now - 4 * 3_600_000L).stale(now));
        // A portable left in the field read 57 days old on 2026-09-25; it must never
        // draw as though it were current.
        assertTrue(station(now - 57L * 24 * 3_600_000L).stale(now));
        // Never reported at all.
        assertTrue(station(0).stale(now));
        assertEquals(2.0, station(now - 2 * 3_600_000L).ageHours(now), 0.001);
    }

    @Test
    public void theStrongestWindPrefersTheGust() {
        assertEquals(18, one("{\"WindSpeedMPH\":\"9 mph\",\"WindSpeedPeak\":\"18 mph\","
                + "\"Latitude\":33,\"Longitude\":-117}").strongestMph(), 0.001);
        // No gust reported: the sustained wind is all there is.
        assertEquals(9, one("{\"WindSpeedMPH\":\"9 mph\",\"WindSpeedPeak\":\"NO DATA\","
                + "\"Latitude\":33,\"Longitude\":-117}").strongestMph(), 0.001);
    }

    @Test
    public void anAnswerThatCannotBeReadIsNoStationsRatherThanACrash() {
        assertEquals(0, Raws.parse(null).size());
        assertEquals(0, Raws.parse("").size());
        assertEquals(0, Raws.parse("not json").size());
        assertEquals(0, Raws.parse("{}").size());
        assertEquals(0, Raws.parse("{\"features\":[]}").size());
        // A row with no position, and the null island, are both dropped.
        assertEquals(0, Raws.parse("{\"features\":[{\"attributes\":{\"WXID\":\"1\"}}]}").size());
        assertEquals(0, one2("{\"Latitude\":0,\"Longitude\":0}"));
    }

    private static Raws.Station station(long observedAt) {
        return one("{\"ObservedDate\":" + observedAt + ",\"Latitude\":33,\"Longitude\":-117}");
    }

    private static Raws.Station one(String attrs) {
        return Raws.parse("{\"features\":[{\"attributes\":" + attrs + "}]}").get(0);
    }

    private static int one2(String attrs) {
        return Raws.parse("{\"features\":[{\"attributes\":" + attrs + "}]}").size();
    }
}
