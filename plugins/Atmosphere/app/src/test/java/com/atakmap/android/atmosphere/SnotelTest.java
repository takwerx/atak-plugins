package com.atakmap.android.atmosphere;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.atakmap.android.atmosphere.data.Snotel;

import org.junit.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class SnotelTest {

    private static final String STATIONS = "[{\"stationTriplet\":\"301:CA:SNTL\",\"stationId\":\"301\",\"stateCode\":\"CA\","
            + "\"networkCode\":\"SNTL\",\"name\":\"Adin Mtn\",\"countyName\":\"Modoc\",\"elevation\":6170.0,\"latitude\":41.23583,"
            + "\"longitude\":-120.79192,\"dataTimeZone\":-8.0,\"shefId\":\"ADMC1\"},"
            + "{\"stationTriplet\":\"356:CA:SNTL\",\"stationId\":\"356\",\"stateCode\":\"CA\",\"name\":\"Blue Lakes\",\"elevation\":8060.0,"
            + "\"latitude\":38.608,\"longitude\":-119.924,\"dataTimeZone\":-8.0},"
            + "{\"stationTriplet\":\"bad\",\"name\":\"no position\"}]";

    private static final String DATA = "[{\"stationTriplet\":\"301:CA:SNTL\",\"data\":["
            + "{\"stationElement\":{\"elementCode\":\"PREC\"},\"values\":[{\"date\":\"2026-09-26 18:00\",\"value\":25.4},{\"date\":\"2026-09-26 19:00\",\"value\":25.5}]},"
            + "{\"stationElement\":{\"elementCode\":\"SNWD\"},\"values\":[{\"date\":\"2026-09-26 19:00\",\"value\":1}]},"
            + "{\"stationElement\":{\"elementCode\":\"TOBS\"},\"values\":[{\"date\":\"2026-09-26 19:00\",\"value\":49.1}]},"
            + "{\"stationElement\":{\"elementCode\":\"WTEQ\"},\"values\":[{\"date\":\"2026-09-26 15:00\",\"value\":0.0},{\"date\":\"2026-09-26 19:00\",\"value\":null}]}]},"
            + "{\"stationTriplet\":\"999:CA:SNTL\",\"data\":[{\"stationElement\":{\"elementCode\":\"SNWD\"},\"values\":[]}]}]";

    @Test
    public void stationsAndBoxAndChunks() {
        final List<Snotel.Station> s = Snotel.parseStations(STATIONS);
        assertEquals(2, s.size());
        assertEquals("Adin Mtn", s.get(0).name);
        assertEquals(6170.0, s.get(0).elevationFt, 1e-9);
        assertEquals("https://wcc.sc.egov.usda.gov/nwcc/site?sitenum=301", s.get(0).url());
        assertEquals(1, Snotel.within(s, -121, 41, -120, 42).size());
        assertEquals(2, Snotel.within(s, -125, 32, -114, 42).size());
        assertEquals(2, Snotel.chunks(s, 1).size());
        assertEquals(1, Snotel.chunks(s, 60).size());
        assertTrue(Snotel.dataUrl(Snotel.chunks(s, 60).get(0), "2026-09-26 14:00", "2026-09-26 23:59")
                .contains("stationTriplets=301:CA:SNTL,356:CA:SNTL&elements=WTEQ,SNWD,TOBS,PREC&duration=HOURLY&beginDate=2026-09-26%2014:00"));
    }

    @Test
    public void readingsTakeTheLastRealValue() {
        final Map<String, Double> tz = new HashMap<>();
        tz.put("301:CA:SNTL", -8.0);
        final Map<String, Snotel.Reading> r = Snotel.parseData(DATA, tz);
        assertEquals(1, r.size());                              // the empty station is left out
        final Snotel.Reading a = r.get("301:CA:SNTL");
        assertEquals(1.0, a.snowDepthIn, 1e-9);
        assertEquals(0.0, a.sweIn, 1e-9);                       // the null 19:00 is skipped for the 15:00 value
        assertEquals(49.1, a.tempF, 1e-9);
        assertEquals(25.5, a.precipIn, 1e-9);
        assertEquals("2026-09-26 19:00", a.observed);
        assertEquals(IsoTimeHelper.utc("2026-09-27T03:00:00Z"), a.observedAt);   // 19:00 at UTC-8
    }

    @Test
    public void depthColorsFollowTheAnalysis() {
        assertEquals(Snotel.NO_REPORT, Snotel.depthColor(Double.NaN));
        assertEquals(Snotel.NO_SNOW, Snotel.depthColor(0));
        assertEquals(Snotel.DEPTH_COLORS[0], Snotel.depthColor(1));
        assertEquals(Snotel.DEPTH_COLORS[3], Snotel.depthColor(12));
        assertEquals(Snotel.DEPTH_COLORS[10], Snotel.depthColor(500));
    }

    /** A tiny helper so the expected instant is written as ISO, not as a number. */
    private static final class IsoTimeHelper {
        static long utc(String iso) {
            try {
                final java.text.SimpleDateFormat f = new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.US);
                f.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
                return f.parse(iso).getTime();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
    }
}
