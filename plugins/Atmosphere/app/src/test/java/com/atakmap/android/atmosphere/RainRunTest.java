package com.atakmap.android.atmosphere;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.atakmap.android.atmosphere.data.Acis;
import com.atakmap.android.atmosphere.data.UsgsRain;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

/** Rain over a run of days: the climate centers' totals and the USGS daily totals. */
public class RainRunTest {

    private static final String ACIS = "{\"data\":["
            + "{\"meta\":{\"ll\":[-118.41456,33.40419],\"sids\":[\"23191 1\",\"047910 2\",\"KAVX 5\"],"
            + "\"name\":\"AVALON CATALINA AP\"},\"smry\":[[\"T\",1]]},"
            + "{\"meta\":{\"ll\":[-118.1416,34.1336],\"sids\":[\"US1CALA0124 6\",\"CALA0124 10\"],"
            + "\"name\":\"PASADENA 1.9 S\"},\"smry\":[[\"1.47\",2]]},"
            + "{\"meta\":{\"ll\":[-118.2,34.1],\"sids\":[\"US1CALA0085 6\",\"CALA0085 10\"],"
            + "\"name\":\"SOUTH PASADENA 3.7 WSW\"},\"smry\":[[\"M\",7]]},"
            + "{\"meta\":{\"sids\":[\"X 2\"],\"name\":\"NO PLACE\"},\"smry\":[[\"0.50\",0]]},"
            + "{\"meta\":{\"ll\":[0,0],\"sids\":[\"Y 2\"],\"name\":\"NULL ISLAND\"},\"smry\":[[\"0.50\",0]]}"
            + "]}";

    @Test
    public void acisReadsTotalsAndDropsTheUnusable() {
        final List<Acis.Station> s = Acis.parse(ACIS);
        assertEquals(2, s.size());
        assertEquals("Avalon Catalina AP", s.get(0).name);
        assertTrue(s.get(0).trace);
        assertFalse(s.get(0).volunteer);
        assertEquals(1, s.get(0).missing);
        assertEquals("Pasadena 1.9 S", s.get(1).name);
        assertEquals(1.47, s.get(1).inches, 1e-9);
        assertTrue(s.get(1).volunteer);
        assertEquals("CALA0124", s.get(1).id);
        assertEquals(2, s.get(1).missing);
        assertTrue(Acis.parse("{\"error\":\"bad\"}").isEmpty());
        assertTrue(Acis.parse("<html>").isEmpty());
    }

    @Test
    public void acisUrlCarriesTheRunEncoded() {
        final String u = Acis.url("CA", "2026-10-11", "2026-10-13");
        assertTrue(u.startsWith("https://data.rcc-acis.org/MultiStnData?params="));
        assertTrue(u.contains("%22state%22%3A%22CA%22"));
        assertTrue(u.contains("2026-10-11") && u.contains("2026-10-13"));
        assertFalse(u.contains("{") || u.contains("\""));
    }

    private static final String SOURCE = "\"sourceInfo\":{\"siteName\":\"THOMAS MTN PRECIP GAGE NR ANZA CA\","
            + "\"siteCode\":[{\"value\":\"333709116405101\"}],\"timeZoneInfo\":{\"defaultTimeZone\":"
            + "{\"zoneOffset\":\"-08:00\"}},\"geoLocation\":{\"geogLocation\":{\"latitude\":33.619,"
            + "\"longitude\":-116.681}}},\"variable\":{\"noDataValue\":-999999.0}";

    private static final String DAILY = "{\"value\":{\"timeSeries\":[{" + SOURCE + ",\"values\":[{\"value\":["
            + "{\"value\":\"0.80\",\"dateTime\":\"2026-10-10T00:00:00.000\"},"
            + "{\"value\":\"1.23\",\"dateTime\":\"2026-10-11T00:00:00.000\"},"
            + "{\"value\":\"-999999\",\"dateTime\":\"2026-10-12T00:00:00.000\"}]}]}]}}";

    /** PDT readings: 00:15 PDT is 23:15 PST the day before; midnight PST closes the day before. */
    private static final String RECENT = "{\"value\":{\"timeSeries\":[{" + SOURCE + ",\"values\":[{\"value\":["
            + "{\"value\":\"0.10\",\"dateTime\":\"2026-10-12T00:15:00.000-07:00\"},"
            + "{\"value\":\"0.20\",\"dateTime\":\"2026-10-12T01:00:00.000-07:00\"},"
            + "{\"value\":\"0.30\",\"dateTime\":\"2026-10-12T01:15:00.000-07:00\"},"
            + "{\"value\":\"0.05\",\"dateTime\":\"2026-10-13T08:00:00.000-07:00\"}]}]}]}}";

    @Test
    public void dailyTotalsSkipNoData() {
        final Map<String, UsgsRain.Daily> d = UsgsRain.parseDaily(DAILY);
        assertEquals(1, d.size());
        final UsgsRain.Daily g = d.get("333709116405101");
        assertEquals(2, g.days.size());
        assertEquals(1.23, g.days.get("2026-10-11"), 1e-9);
    }

    @Test
    public void readingsFallOnTheStandardTimeDay() {
        final Map<String, Double> days = UsgsRain.parseByDay(RECENT).get("333709116405101");
        // 00:15 and 01:00 PDT are 23:15 and midnight PST: both close Oct 11.
        assertEquals(0.30, days.get("2026-10-11"), 1e-9);
        assertEquals(0.30, days.get("2026-10-12"), 1e-9);
        assertEquals(0.05, days.get("2026-10-13"), 1e-9);
    }

    @Test
    public void aSpanAddsReadingsOnlyAfterTheLastDailyTotal() {
        final UsgsRain.Daily g = UsgsRain.parseDaily(DAILY).get("333709116405101");
        final Map<String, Double> recent = UsgsRain.parseByDay(RECENT).get("333709116405101");
        final UsgsRain.Span s = UsgsRain.span(g, recent, "2026-10-11", "2026-10-13", 3);
        // Oct 11 from the daily total (not the readings), Oct 12 and 13 from readings.
        assertEquals(1.23 + 0.30 + 0.05, s.total, 1e-9);
        assertEquals(3, s.daysOnRecord);
        assertEquals(Arrays.asList("2026-10-11", "2026-10-12", "2026-10-13"),
                Arrays.asList(s.byDay.keySet().toArray(new String[0])));
        final UsgsRain.Span one = UsgsRain.span(g, null, "2026-10-10", "2026-10-10", 1);
        assertEquals(0.80, one.total, 1e-9);
        assertNull(UsgsRain.span(g, null, "2026-09-01", "2026-09-02", 2));
    }

    @Test
    public void recentUrlNamesOnlySiteNumbers() {
        final String u = UsgsRain.recentUrl(Arrays.asList("333709116405101", "08167000", "bad&x=1"));
        assertTrue(u.contains("sites=333709116405101,08167000&"));
        assertFalse(u.contains("bad"));
    }

    private static final String SITES = "# comment\n"
            + "agency_cd\tsite_no\tstation_nm\tsite_tp_cd\tdec_lat_va\tdec_long_va\n"
            + "5s\t15s\t50s\t7s\t16s\t16s\n"
            + "USGS\t333122116394002\t007S003E34E001S PRECIP\tAT\t33.52216667\t-116.6623333\n"
            + "USGS\tbad&x\tNOPE\tAT\t33.5\t-116.6\n";

    /** Two site blocks, each with its own header; PDT readings, a PST one, and a missing value. */
    private static final String READINGS = "#\n"
            + "agency_cd\tsite_no\tdatetime\ttz_cd\t217562_00045\t217562_00045_cd\n"
            + "5s\t15s\t20d\t6s\t14n\t10s\n"
            + "USGS\t333122116394002\t2026-10-12 00:15\tPDT\t0.10\tP\n"
            + "USGS\t333122116394002\t2026-10-12 01:00\tPDT\t0.20\tP\n"
            + "USGS\t333122116394002\t2026-10-12 01:15\tPDT\t0.30\tP\n"
            + "USGS\t333122116394002\t2026-10-12 02:00\tPDT\t\tEqp\n"
            + "agency_cd\tsite_no\tdatetime\ttz_cd\t1_00045\t1_00045_cd\n"
            + "5s\t15s\t20d\t6s\t14n\t10s\n"
            + "USGS\t08167000\t2026-10-13 00:00\tPST\t0.40\tP\n";

    @Test
    public void sitesKeepOnlyRealSiteNumbers() {
        final Map<String, UsgsRain.Site> s = UsgsRain.parseSites(SITES);
        assertEquals(1, s.size());
        assertEquals(33.52216667, s.get("333122116394002").lat, 1e-9);
        assertEquals("007S003E34E001S PRECIP", s.get("333122116394002").name);
    }

    @Test
    public void tabReadingsFallOnTheStandardTimeDay() {
        final Map<String, Map<String, Double>> r = UsgsRain.parseRdbByDay(READINGS);
        final Map<String, Double> a = r.get("333122116394002");
        assertEquals(0.30, a.get("2026-10-11"), 1e-9);
        assertEquals(0.30, a.get("2026-10-12"), 1e-9);
        // Midnight standard time closes the day before.
        assertEquals(0.40, r.get("08167000").get("2026-10-12"), 1e-9);
        assertTrue(UsgsRain.parseRdbByDay("<html>").isEmpty());
    }
}
