package com.atakmap.android.atmosphere;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.atakmap.android.atmosphere.astro.Astro;

import org.junit.Test;

import java.time.Instant;
import java.util.TimeZone;

/** Published almanac values, with the tolerance the method deserves. */
public class AstroTest {

    private static long utc(String iso) {
        return Instant.parse(iso).toEpochMilli();
    }

    // ---- moon: the 2000 January phases from the Astronomical Almanac ----------

    @Test
    public void newMoonIsDark() {
        final long t = utc("2000-01-06T18:14:00Z");
        assertEquals(0.0, Astro.moonIllumination(t), 0.01);
        assertEquals("New moon", Astro.moonPhaseName(t));
    }

    @Test
    public void firstQuarterIsHalfLitAndWaxing() {
        final long t = utc("2000-01-14T13:34:00Z");
        assertEquals(0.5, Astro.moonIllumination(t), 0.02);
        assertTrue(Astro.moonWaxing(t));
        assertEquals("First quarter", Astro.moonPhaseName(t));
    }

    @Test
    public void fullMoonIsLit() {
        final long t = utc("2000-01-21T04:40:00Z");
        assertEquals(1.0, Astro.moonIllumination(t), 0.01);
        assertEquals("Full moon", Astro.moonPhaseName(t));
    }

    @Test
    public void lastQuarterIsHalfLitAndWaning() {
        final long t = utc("2000-01-28T07:57:00Z");
        assertEquals(0.5, Astro.moonIllumination(t), 0.02);
        assertTrue(!Astro.moonWaxing(t));
        assertEquals("Last quarter", Astro.moonPhaseName(t));
    }

    @Test
    public void fullMoonDecadesLater() {
        // Full moon 2024-01-25 17:54 UTC (USNO).
        final long t = utc("2024-01-25T17:54:00Z");
        assertEquals(1.0, Astro.moonIllumination(t), 0.02);
        assertEquals("Full moon", Astro.moonPhaseName(t));
    }

    // ---- sun: NOAA solar calculator values -----------------------------------

    private static void assertWithin(long expected, long actual, long toleranceMs, String what) {
        assertTrue(what + " off by " + Math.abs(expected - actual) / 60000 + " min",
                Math.abs(expected - actual) <= toleranceMs);
    }

    @Test
    public void boulderSolstice() {
        // 40.0 N, 105.0 W on 2010-06-21: NOAA gives sunrise 05:31 and sunset 20:32 MDT (UTC-6).
        final long[] rs = Astro.sunRiseSet(utc("2010-06-21T18:00:00Z"), 40.0, -105.0);
        assertNotNull(rs);
        assertWithin(utc("2010-06-21T11:31:00Z"), rs[0], 3 * 60_000, "sunrise");
        assertWithin(utc("2010-06-22T02:32:00Z"), rs[1], 3 * 60_000, "sunset");
    }

    @Test
    public void southernCaliforniaEquinox() {
        // 33.83 N, 117.57 W on 2026-09-22: sunrise 06:38, sunset 18:47 PDT (UTC-7), NOAA.
        final long[] rs = Astro.sunRiseSet(utc("2026-09-22T19:00:00Z"), 33.83, -117.57);
        assertNotNull(rs);
        assertWithin(utc("2026-09-22T13:38:00Z"), rs[0], 4 * 60_000, "sunrise");
        assertWithin(utc("2026-09-23T01:47:00Z"), rs[1], 4 * 60_000, "sunset");
    }

    @Test
    public void clockDayAnchorsTheWholeDay() {
        // 2026-09-22 00:00 PDT is 07:00Z. By longitude the sun's day at 117.57 W is still
        // the 21st at that instant, so the pane keyed Tuesday's hours to Monday's sunset and
        // drew a moon over all of Tuesday (XCover, 2026-09-21). Anchored on the zone's clock,
        // the first and the last minute of Tuesday both get Tuesday's sunrise and sunset.
        final TimeZone la = TimeZone.getTimeZone("America/Los_Angeles");
        final long[] byLongitude = Astro.sunRiseSet(utc("2026-09-22T07:00:00Z"), 33.83, -117.57);
        assertTrue("longitude day should be the 21st", byLongitude[0] < utc("2026-09-22T00:00:00Z"));

        final long[] first = Astro.sunRiseSet(utc("2026-09-22T07:00:00Z"), la, 33.83, -117.57);
        assertWithin(utc("2026-09-22T13:38:00Z"), first[0], 4 * 60_000, "sunrise");
        assertWithin(utc("2026-09-23T01:47:00Z"), first[1], 4 * 60_000, "sunset");

        final long[] last = Astro.sunRiseSet(utc("2026-09-23T06:59:00Z"), la, 33.83, -117.57);
        assertEquals(first[0], last[0]);
        assertEquals(first[1], last[1]);
    }

    @Test
    public void polarNightHasNoEvents() {
        // Utqiagvik in December: the sun does not rise.
        assertNull(Astro.sunRiseSet(utc("2025-12-21T12:00:00Z"), 71.29, -156.79));
    }
}
