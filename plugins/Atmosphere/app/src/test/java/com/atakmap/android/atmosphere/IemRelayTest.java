package com.atakmap.android.atmosphere;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.atakmap.android.atmosphere.data.Iem;
import com.atakmap.android.atmosphere.data.IemRelay;

import org.junit.Test;

import java.io.InputStream;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Scanner;
import java.util.TreeMap;

/**
 * takwerx's relay of the mesonet: where each file is, and that its copy of the gauges'
 * readings and the airports' reports adds up exactly as the mesonet's own answer did.
 */
public class IemRelayTest {

    private static final String BASE = "https://mapdepot.takwerx.org/atmosphere/iem/";

    private static long utc(String s) {
        final java.util.Calendar c = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"));
        c.clear();
        c.set(Integer.parseInt(s.substring(0, 4)), Integer.parseInt(s.substring(5, 7)) - 1,
                Integer.parseInt(s.substring(8, 10)), Integer.parseInt(s.substring(11, 13)),
                Integer.parseInt(s.substring(14, 16)));
        return c.getTimeInMillis();
    }

    private static String resource(String name) {
        final InputStream in = IemRelayTest.class.getClassLoader().getResourceAsStream(name);
        try (Scanner s = new Scanner(in, "UTF-8").useDelimiter("\\A")) {
            return s.next();
        }
    }

    @Test
    public void filesAreWhereTheRelayWritesThem() {
        assertEquals(BASE + "networks/CA_DCP.json", IemRelay.networkUrl("CA", "DCP"));
        assertEquals(BASE + "daily/NV/2026-10.json", IemRelay.dailyUrl("NV", "2026-10"));
        assertEquals(BASE + "hads/2026-10-17/34_-118.json", IemRelay.hadsUrl("2026-10-17", "34_-118"));
        assertEquals(BASE + "asos/2026-10-17/CA.json", IemRelay.asosUrl("2026-10-17", "CA"));
        try {
            IemRelay.networkUrl("../x", "DCP");
            throw new AssertionError("a state that is not one was let through");
        } catch (IllegalArgumentException expected) {
            // Refused.
        }
    }

    @Test
    public void tilesAreNamedByTheirSouthWestCorner() {
        // The relay's tile_of: floor to 2 degrees.
        assertEquals("34_-118", IemRelay.tile(34.05, -117.2));
        assertEquals("32_-118", IemRelay.tile(33.99, -116.01));
        assertEquals("18_-68", IemRelay.tile(18.2, -66.5));
        assertEquals("64_-148", IemRelay.tile(64.8, -147.7));
    }

    @Test
    public void monthsAndDaysOfARun() {
        assertEquals(Arrays.asList("2025-12", "2026-01", "2026-02"), IemRelay.months("2025-12-30", "2026-02-02"));
        assertEquals(Arrays.asList("2026-10"), IemRelay.months("2026-10-11", "2026-10-17"));
        assertTrue(IemRelay.months("bad", "2026-10-17").isEmpty());
        assertEquals(Arrays.asList("2026-10-16", "2026-10-17"),
                IemRelay.utcDays(utc("2026-10-16 18:30"), utc("2026-10-17 18:30")));
    }

    @Test
    public void hoursGoBackToTheRelaysStartThen45Days() {
        final long start = utc("2026-10-17 15:00");
        assertEquals(start, IemRelay.hoursSince(start + 3_600_000L));
        // A hundred days on, 45 whole UTC days.
        final long later = utc("2027-01-25 18:00");
        assertEquals(utc("2026-12-11 00:00"), IemRelay.hoursSince(later));
    }

    @Test
    public void networkListKeepsPlacedGauges() {
        final List<Iem.Station> l = IemRelay.parseNetwork("{\"v\":1,\"network\":\"CA_DCP\",\"stations\":["
                + "[\"LAAC1\",34.2,-118.1,\"EATON WASH AT LOFTUS ALERT #382\"],"
                + "[\"bad id\",34.2,-118.1,\"X\"],[\"ZERC1\",0,0,\"Null Island\"]]}");
        assertEquals(1, l.size());
        assertEquals("LAAC1", l.get(0).id);
        assertEquals("Eaton Wash At Loftus Alert #382", l.get(0).name);
        assertTrue(IemRelay.parseNetwork("<html>").isEmpty());
    }

    @Test
    public void dailyTotalsKeepTheGaugesAndDaysAsked() {
        final IemRelay.Days d = new IemRelay.Days(new HashSet<>(Arrays.asList("HCPC1", "LAAC1")), "2026-09-30",
                "2026-10-02");
        assertTrue(d.add("{\"v\":1,\"month\":\"2026-09\",\"first\":\"2026-09-01\",\"gauges\":{\"HCPC1\":["
                + "0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0.5]}}"));
        assertTrue(d.add("{\"v\":1,\"month\":\"2026-10\",\"first\":\"2026-10-01\",\"gauges\":{"
                + "\"HCPC1\":[0.04,null,1.2],\"LAAC1\":[99,0.1],\"OTHC1\":[3,3,3]}}"));
        assertFalse(d.add("not json"));
        final Map<String, TreeMap<String, Double>> by = d.byGauge();
        assertEquals(2, by.size());
        assertEquals(0.5, by.get("HCPC1").get("2026-09-30"), 1e-9);
        assertEquals(0.04, by.get("HCPC1").get("2026-10-01"), 1e-9);
        // No total that day, and one after the run, are absent.
        assertNull(by.get("HCPC1").get("2026-10-02"));
        assertNull(by.get("HCPC1").get("2026-10-03"));
        // 99 in a day is a fault, as the mesonet's own parser drops it.
        assertNull(by.get("LAAC1").get("2026-10-01"));
        assertEquals(0.1, by.get("LAAC1").get("2026-10-02"), 1e-9);
    }

    /** IemTest's accumulator case, as the relay writes it: the same totals. */
    @Test
    public void readingsAddUpAsTheMesonetsAnswerDid() {
        final long start = utc("2026-10-11 00:00"), end = utc("2026-10-11 08:00");
        final String csv = "station,utc_valid,HGIRGZZ,PCIRGZZ,PPHRGZZ\n"
                + "AAAC1,2026-10-11 00:00:00,,10.00,\n"
                + "AAAC1,2026-10-11 01:00:00,,10.30,\n"
                + "AAAC1,2026-10-11 02:00:00,,0.00,\n"
                + "AAAC1,2026-10-11 03:00:00,,10.50,\n"
                + "AAAC1,2026-10-11 04:00:00,,25.50,\n"
                + "AAAC1,2026-10-11 05:00:00,,10.60,\n"
                + "AAAC1,2026-10-11 06:00:00,,0.10,\n"
                + "AAAC1,2026-10-11 07:00:00,,0.20,\n"
                + "AAAC1,2026-10-11 08:00:00,,0.25,\n"
                + "BBBC1,2026-10-11 01:00:00,,,0.10\n"
                + "BBBC1,2026-10-11 02:00:00,,,0.20\n";
        final IemRelay.Readings r = new IemRelay.Readings(null, start, end);
        assertTrue(r.add("{\"v\":1,\"day\":\"2026-10-11\",\"tile\":\"34_-118\",\"gauges\":{"
                + "\"AAAC1\":{\"pc\":[[0,10.0],[60,10.3],[120,0.0],[180,10.5],[240,25.5],[300,10.6],[360,0.1],"
                + "[420,0.2],[480,0.25]],\"pp\":[]},"
                + "\"BBBC1\":{\"pc\":[],\"pp\":[[60,0.1],[120,0.2]]},"
                // Outside the stretch: the next day, read in a file of its own.
                + "\"CCCC1\":{\"pc\":[[600,1.0],[660,1.5]],\"pp\":[]}}}"));
        final Map<String, Iem.Total> mine = r.totals(), theirs = Iem.parseHads(csv, start, end);
        for (String id : new String[] { "AAAC1", "BBBC1" }) {
            assertEquals(id, theirs.get(id).inches, mine.get(id).inches, 1e-9);
            assertEquals(id, theirs.get(id).complete, mine.get(id).complete);
        }
        assertEquals(0.75, mine.get("AAAC1").inches, 1e-9);
        assertFalse(mine.containsKey("CCCC1"));
    }

    @Test
    public void readingsAcrossTwoDaysJoinIntoOneSeries() {
        final long start = utc("2026-10-11 22:00"), end = utc("2026-10-12 02:00");
        final IemRelay.Readings r = new IemRelay.Readings(new HashSet<>(Arrays.asList("AAAC1")), start, end);
        r.add("{\"day\":\"2026-10-11\",\"gauges\":{\"AAAC1\":{\"pc\":[[1260,1.0],[1320,1.1],[1380,1.2]]},"
                + "\"ZZZC1\":{\"pc\":[[1320,0],[1380,5]]}}}");
        r.add("{\"day\":\"2026-10-12\",\"gauges\":{\"AAAC1\":{\"pc\":[[0,1.3],[60,1.5],[120,1.6],[180,9]]}}}");
        final Map<String, Iem.Total> t = r.totals();
        // 22:00 to 02:00: 1.1 to 1.6; 21:00 and 03:00 are outside.
        assertEquals(0.5, t.get("AAAC1").inches, 1e-9);
        assertTrue(t.get("AAAC1").complete);
        assertFalse("a gauge not asked for", t.containsKey("ZZZC1"));
    }

    /** IemTest's airport case, as the relay writes it. */
    @Test
    public void reportsAddUpAsTheMesonetsAnswerDid() {
        final long start = utc("2026-10-11 00:00"), end = utc("2026-10-11 03:00");
        final String csv = "station,valid,lon,lat,p01i\n"
                + "KSAN,2026-10-11 00:51,-117.18,32.73,0.10\n"
                + "KSAN,2026-10-11 01:00,-117.18,32.73,0.02\n"
                + "KSAN,2026-10-11 01:51,-117.18,32.73,T\n"
                + "KSAN,2026-10-11 02:51,-117.18,32.73,0.30\n";
        final IemRelay.Reports r = new IemRelay.Reports(start, end);
        assertTrue(r.add("{\"v\":1,\"day\":\"2026-10-11\",\"state\":\"CA\",\"stations\":{\"KSAN\":"
                + "{\"lat\":32.73,\"lon\":-117.18,\"r\":[[51,0.1],[60,0.02],[111,\"T\"],[171,0.3]]}}}"));
        final Iem.Total mine = r.totals().get("KSAN"), theirs = Iem.parseAsos(csv, start, end).get("KSAN");
        assertEquals(theirs.inches, mine.inches, 1e-9);
        assertEquals(theirs.complete, mine.complete);
        assertEquals(32.73, mine.lat, 1e-9);
    }

    /** Files as the relay published them on 2026-10-17, read whole. */
    @Test
    public void readsTheRelaysOwnFiles() {
        final long start = utc("2026-10-17 15:00"), end = utc("2026-10-17 18:00");
        final IemRelay.Readings r = new IemRelay.Readings(null, start, end);
        assertTrue(r.add(resource("iem_relay_hads_2026-10-17_34_-118.json")));
        final Map<String, Iem.Total> t = r.totals();
        assertTrue(t.size() > 100);
        int complete = 0;
        for (Iem.Total x : t.values()) {
            assertTrue(x.id, x.inches >= 0 && x.inches < 10);
            if (x.complete)
                complete++;
        }
        assertTrue(complete > 100);

        final IemRelay.Reports a = new IemRelay.Reports(start, end);
        assertTrue(a.add(resource("iem_relay_asos_2026-10-17_CA.json")));
        final Map<String, Iem.Total> air = a.totals();
        assertTrue(air.size() > 50);
        for (Iem.Total x : air.values())
            assertTrue(x.id, x.lat > 32 && x.lat < 42.1 && x.lon > -125 && x.lon < -114);
    }
}
