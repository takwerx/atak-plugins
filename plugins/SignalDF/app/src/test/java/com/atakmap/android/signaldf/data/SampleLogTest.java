package com.atakmap.android.signaldf.data;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Collection, ageing, and a fix placed back on the earth.
 *
 * <p>The geometry here is a real one: a transmitter somewhere in Riverside
 * County and a receiver driving a straight road past it, which is the case the
 * whole plugin exists for.
 */
public class SampleLogTest {

    private static final double TX_LAT = 33.6000;
    private static final double TX_LON = -117.2000;

    /** True bearing from a point to the transmitter, via the local frame. */
    private static double bearingToTx(double lat, double lon) {
        LocalFrame f = new LocalFrame(lat, lon);
        return Angles.norm360(Math.toDegrees(
                Math.atan2(f.east(TX_LON), f.north(TX_LAT))));
    }

    // ---- the local frame ----------------------------------------------------

    @Test
    public void aDegreeOfLatitudeIsAboutAHundredAndElevenKilometers() {
        LocalFrame f = new LocalFrame(33.6, -117.2);
        assertEquals(111000.0, f.north(34.6), 400.0);
        // And a degree of longitude is shorter by the cosine of the latitude.
        assertEquals(111320.0 * Math.cos(Math.toRadians(33.6)),
                f.east(-116.2), 400.0);
    }

    @Test
    public void theFrameRoundTrips() {
        LocalFrame f = new LocalFrame(33.6, -117.2);
        double lat = 33.6432, lon = -117.1891;
        assertEquals(lat, f.lat(f.north(lat)), 1e-9);
        assertEquals(lon, f.lon(f.east(lon)), 1e-9);
    }

    @Test
    public void distanceAgreesWithAKnownSeparation() {
        // One minute of latitude is a nautical mile, 1852 m, near enough.
        assertEquals(1852.0,
                LocalFrame.distanceM(33.6, -117.2, 33.6 + 1.0 / 60.0, -117.2),
                5.0);
    }

    // ---- the movement gate --------------------------------------------------

    @Test
    public void sittingStillDoesNotFillTheLog() {
        SampleLog log = new SampleLog();
        assertTrue(log.offer(33.6, -117.3, 90, 1, -50, 1000));
        // A hundred more frames from the same place, GPS jitter and all.
        for (int i = 0; i < 100; i++) {
            double jitter = (i % 7 - 3) * 0.00002; // about two meters
            assertFalse("parked frames must not be kept",
                    log.offer(33.6 + jitter, -117.3 + jitter, 90, 1, -50,
                            2000 + i));
        }
        assertEquals(1, log.size());
    }

    @Test
    public void movingFarEnoughKeepsTheNextOne() {
        SampleLog log = new SampleLog();
        log.offer(33.6, -117.3, 90, 1, -50, 1000);
        // 0.001 degrees of latitude is about 111 m, over the 50 m gate.
        assertTrue(log.offer(33.601, -117.3, 90, 1, -50, 2000));
        assertEquals(2, log.size());
    }

    @Test
    public void nonsenseIsRefused() {
        SampleLog log = new SampleLog();
        assertFalse(log.offer(Double.NaN, -117.3, 90, 1, -50, 1000));
        assertFalse(log.offer(33.6, Double.NaN, 90, 1, -50, 1000));
        assertFalse(log.offer(33.6, -117.3, Double.NaN, 1, -50, 1000));
        assertEquals(0, log.size());
    }

    // ---- ageing -------------------------------------------------------------

    @Test
    public void oldSamplesFallOutTheBack() {
        SampleLog log = new SampleLog();
        long t = 1_000_000L;
        for (int i = 0; i < 5; i++)
            log.offer(33.6 + i * 0.002, -117.3, 90, 1, -50, t + i * 60_000L);
        assertEquals(5, log.size());
        // Now stand two minutes past the age limit from the third sample.
        log.age(t + 2 * 60_000L + SampleLog.MAX_AGE_MS + 1);
        assertEquals("the first three should have aged out", 2, log.size());
    }

    @Test
    public void ageingEverythingOutResetsTheMovementGate() {
        SampleLog log = new SampleLog();
        log.offer(33.6, -117.3, 90, 1, -50, 1000);
        log.age(1000 + SampleLog.MAX_AGE_MS + 1);
        assertEquals(0, log.size());
        // The very next frame, from the same spot, must be accepted -- there
        // is nothing left to be too close to.
        assertTrue(log.offer(33.6, -117.3, 90, 1, -50, 99_000_000L));
    }

    @Test
    public void clearingEmptiesItAndTheGate() {
        SampleLog log = new SampleLog();
        log.offer(33.6, -117.3, 90, 1, -50, 1000);
        log.clear();
        assertEquals(0, log.size());
        assertTrue(log.offer(33.6, -117.3, 90, 1, -50, 2000));
    }

    // ---- the fix, back on the earth -----------------------------------------

    @Test
    public void tooFewSamplesIsNotAFix() {
        SampleLog log = new SampleLog();
        log.offer(33.55, -117.30, bearingToTx(33.55, -117.30), 1, -50, 1000);
        log.offer(33.56, -117.30, bearingToTx(33.56, -117.30), 1, -50, 2000);
        assertNull(log.solve());
    }

    @Test
    public void drivingPastATransmitterFindsIt() {
        // West to east along a road about five kilometers south of the
        // transmitter -- the ordinary case, and good geometry because the
        // bearing swings right through as you pass.
        SampleLog log = new SampleLog();
        long t = 1_000_000L;
        for (int i = 0; i < 12; i++) {
            double lat = 33.5550;
            double lon = -117.2600 + i * 0.0060;
            log.offer(lat, lon, bearingToTx(lat, lon), 1, -50, t + i * 10_000L);
        }
        assertEquals(12, log.size());

        SampleLog.Result r = log.solve();
        assertNotNull(r);
        double err = LocalFrame.distanceM(TX_LAT, TX_LON, r.lat, r.lon);
        assertTrue("should land on the transmitter, off by " + err, err < 50);
        // Good geometry does not mean silence: it means the advice flips from
        // "drive across your bearings" to "the fix marker is the place to go".
        assertNotNull(r.fix.advice());
        assertTrue(r.fix.advice(),
                r.fix.advice().toLowerCase().contains("fix marker"));
    }

    @Test
    public void drivingStraightAtATransmitterCannotFindIt() {
        // Straight up the line of sight: every bearing is the same line and
        // they cross nowhere. Refusing is the correct answer, and it is the
        // one case an operator will hit by instinct -- the bearing points
        // that way, so they drive that way.
        SampleLog log = new SampleLog();
        long t = 1_000_000L;
        for (int i = 0; i < 10; i++) {
            double lat = 33.5000 + i * 0.0050;
            log.offer(lat, TX_LON, bearingToTx(lat, TX_LON), 1, -50,
                    t + i * 10_000L);
        }
        SampleLog.Result r = log.solve();
        // Either it refuses outright, or it produces something with an
        // enormous ellipse and advice to drive across. Both are honest; a
        // confident answer would not be.
        if (r != null) {
            assertNotNull("a fix from one line must carry advice",
                    r.fix.advice());
            assertTrue("and an enormous ellipse, got " + r.fix.accuracyM(),
                    r.fix.accuracyM() > 1000);
        }
    }

    @Test
    public void aShortStretchOfRoadSaysDriveAcross() {
        // Five hundred meters of road, transmitter ten kilometers away. Each
        // bearing is perfect; the crossing angle is hopeless.
        SampleLog log = new SampleLog();
        long t = 1_000_000L;
        for (int i = 0; i < 6; i++) {
            double lat = 33.5100;
            double lon = -117.2030 + i * 0.0012;
            log.offer(lat, lon, bearingToTx(lat, lon), 1, -50, t + i * 10_000L);
        }
        SampleLog.Result r = log.solve();
        assertNotNull(r);
        String advice = r.fix.advice();
        assertNotNull("narrow geometry must say so", advice);
        assertTrue(advice, advice.toLowerCase().contains("drive across")
                || advice.toLowerCase().contains("long and thin"));
    }
}
