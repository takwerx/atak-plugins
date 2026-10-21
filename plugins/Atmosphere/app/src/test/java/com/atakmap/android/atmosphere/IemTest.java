package com.atakmap.android.atmosphere;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.atakmap.android.atmosphere.data.Iem;
import com.atakmap.android.atmosphere.data.UsgsRain;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** The mesonet's gauge feed: daily totals, readings over hours, airports, and the neighbor check. */
public class IemTest {

    private static long utc(String s) {
        final java.util.Calendar c = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"));
        c.clear();
        c.set(Integer.parseInt(s.substring(0, 4)), Integer.parseInt(s.substring(5, 7)) - 1,
                Integer.parseInt(s.substring(8, 10)), Integer.parseInt(s.substring(11, 13)),
                Integer.parseInt(s.substring(14, 16)));
        return c.getTimeInMillis();
    }

    @Test
    public void networkKeepsOnlinePlacedGauges() {
        final String g = "{\"features\":["
                + "{\"properties\":{\"id\":\"LAAC1\",\"name\":\"EATON WASH AT LOFTUS ALERT #382\",\"online\":true},"
                + "\"geometry\":{\"coordinates\":[-118.1,34.2]}},"
                + "{\"properties\":{\"id\":\"OLDC1\",\"name\":\"GONE\",\"online\":false},"
                + "\"geometry\":{\"coordinates\":[-118.1,34.2]}},"
                + "{\"properties\":{\"id\":\"bad id\",\"name\":\"X\",\"online\":true},"
                + "\"geometry\":{\"coordinates\":[-118.1,34.2]}}]}";
        final List<Iem.Station> l = Iem.parseNetwork(g);
        assertEquals(1, l.size());
        assertEquals("LAAC1", l.get(0).id);
        assertEquals("Eaton Wash At Loftus Alert #382", l.get(0).name);
        final List<Iem.Station> back = Iem.fromLines(Iem.toLines(l));
        assertEquals(1, back.size());
        assertEquals(34.2, back.get(0).lat, 1e-5);
    }

    @Test
    public void dailySkipsMissingDays() {
        final String csv = "station,day,precip_in,network\n"
                + "HCPC1,2026-10-07,0.0,CA_DCP\nLAAC1,2026-10-07,8.04,CA_DCP\nAAAC1,2026-10-07,M,CA_DCP\n"
                + "HCPC1,2026-10-08,0.04,CA_DCP\n";
        final Map<String, TreeMap<String, Double>> d = Iem.parseDaily(csv);
        assertEquals(2, d.size());
        assertEquals(0.04, d.get("HCPC1").get("2026-10-08"), 1e-9);
        assertTrue(Iem.parseDaily("ERROR: station: X not found").isEmpty());
    }

    @Test
    public void hadsAccumulatorStepsOverDropoutsResetsAndFaults() {
        final String csv = "station,utc_valid,HGIRGZZ,PCIRGZZ,PPHRGZZ\n"
                + "AAAC1,2026-10-11 00:00:00,,10.00,\n"
                + "AAAC1,2026-10-11 01:00:00,,10.30,\n"
                + "AAAC1,2026-10-11 02:00:00,,0.00,\n" // dropped reading
                + "AAAC1,2026-10-11 03:00:00,,10.50,\n"
                + "AAAC1,2026-10-11 04:00:00,,25.50,\n" // fault: 15 in in an hour, once
                + "AAAC1,2026-10-11 05:00:00,,10.60,\n"
                + "AAAC1,2026-10-11 06:00:00,,0.10,\n" // reset, stays down
                + "AAAC1,2026-10-11 07:00:00,,0.20,\n"
                + "AAAC1,2026-10-11 08:00:00,,0.25,\n"
                + "BBBC1,2026-10-11 01:00:00,,,0.10\n" // hourly amounts only
                + "BBBC1,2026-10-11 02:00:00,,,0.20\n";
        final Map<String, Iem.Total> t = Iem.parseHads(csv, utc("2026-10-11 00:00"), utc("2026-10-11 08:00"));
        // 0.30 + 0.20 + 0.10 + (reset) 0.10 + 0.05
        assertEquals(0.75, t.get("AAAC1").inches, 1e-9);
        assertTrue(t.get("AAAC1").complete);
        assertEquals(0.30, t.get("BBBC1").inches, 1e-9);
        // Its readings stop at 02:00: six hours with none.
        assertFalse(t.get("BBBC1").complete);
    }

    @Test
    public void airportsCountOneReportAnHour() {
        final String csv = "station,valid,lon,lat,p01i\n"
                + "LAX,2026-10-11 00:53,-118.40,33.94,0.10\n"
                + "LAX,2026-10-11 01:53,-118.40,33.94,0.20\n"
                + "LAX,2026-10-11 02:53,-118.40,33.94,T\n"
                // An automated station reporting the last hour every 20 minutes.
                + "HMT,2026-10-11 00:15,-117.02,33.73,0.30\n"
                + "HMT,2026-10-11 00:35,-117.02,33.73,0.30\n"
                + "HMT,2026-10-11 00:55,-117.02,33.73,0.30\n";
        final Map<String, Iem.Total> t = Iem.parseAsos(csv, utc("2026-10-11 00:00"), utc("2026-10-11 03:00"));
        assertEquals(0.30, t.get("LAX").inches, 1e-9);
        assertTrue(t.get("LAX").complete);
        assertEquals(33.94, t.get("LAX").lat, 1e-9);
        assertEquals(0.30, t.get("HMT").inches, 1e-9);
        assertFalse(t.get("HMT").complete);
    }

    @Test
    public void aGaugeOutOfLineWithItsNeighborsIsCaught() {
        final List<double[]> around = new ArrayList<>(Arrays.asList(
                new double[] { 34.20, -118.10, 0.0 }, new double[] { 34.21, -118.11, 0.01 },
                new double[] { 34.19, -118.09, 0.0 }, new double[] { 34.22, -118.12, 0.02 }));
        // 8 in on a dry day.
        assertTrue(Iem.disagrees(8.04, 34.205, -118.105, around));
        // Nothing through a storm the others measured.
        final List<double[]> wet = new ArrayList<>(Arrays.asList(
                new double[] { 34.20, -118.10, 2.1 }, new double[] { 34.21, -118.11, 1.8 },
                new double[] { 34.19, -118.09, 2.4 }));
        assertTrue(Iem.disagrees(0.0, 34.205, -118.105, wet));
        assertFalse(Iem.disagrees(1.9, 34.205, -118.105, wet));
        // Too few neighbors to tell: kept.
        assertFalse(Iem.disagrees(8.04, 34.205, -118.105, wet.subList(0, 2)));
    }

    @Test
    public void usgsReadingsOverAnExactStretch() {
        final String rdb = "agency_cd\tsite_no\tdatetime\ttz_cd\t1_00045\t1_00045_cd\n"
                + "5s\t15s\t20d\t6s\t14n\t10s\n"
                // 17:00 PDT is 00:00 UTC: the end of an interval at the start does not count.
                + "USGS\t333122116394002\t2026-10-10 17:00\tPDT\t0.50\tP\n"
                + "USGS\t333122116394002\t2026-10-10 17:15\tPDT\t0.10\tP\n"
                + "USGS\t333122116394002\t2026-10-10 18:00\tPDT\t0.20\tP\n"
                + "USGS\t333122116394002\t2026-10-10 19:00\tPDT\t0.30\tP\n";
        final Map<String, double[]> w = UsgsRain.parseRdbWindow(rdb, utc("2026-10-11 00:00"),
                utc("2026-10-11 02:00"));
        assertEquals(0.60, w.get("333122116394002")[0], 1e-9);
        assertEquals(1.0, w.get("333122116394002")[1], 1e-9);
    }

    @Test
    public void requestTimesAreUtcMinutes() {
        assertEquals("2026-10-11T00:00Z", Iem.z(utc("2026-10-11 00:00")));
    }
}
