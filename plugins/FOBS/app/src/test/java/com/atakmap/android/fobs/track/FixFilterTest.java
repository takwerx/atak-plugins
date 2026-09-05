package com.atakmap.android.fobs.track;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.atakmap.android.fobs.track.FixFilter.Fix;
import com.atakmap.android.fobs.track.FixFilter.LiveGate;
import com.atakmap.android.fobs.track.FixFilter.Thresholds;
import com.atakmap.android.fobs.track.FixFilter.Verdict;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

public class FixFilterTest {

    // Roughly 1 m of latitude, and 1 m of longitude at 40 N.
    private static final double M_LAT = 1.0 / 111_320.0;
    private static final double M_LON = 1.0 / (111_320.0 * Math.cos(Math.toRadians(40)));
    private static final double LAT0 = 40.0;
    private static final double LON0 = -105.0;

    private static Fix at(double eastM, double northM, double ce, long t) {
        return new Fix(LAT0 + northM * M_LAT, LON0 + eastM * M_LON, ce, t);
    }

    @Test
    public void haversineIsAboutRight() {
        double d = FixFilter.distance(at(0, 0, 5, 0), at(100, 0, 5, 0));
        assertEquals(100.0, d, 0.5);
    }

    @Test
    public void gateAcceptsAWalk() {
        LiveGate g = new LiveGate(new Thresholds());
        long t = 1_000_000;
        assertEquals(Verdict.ACCEPTED, g.judge(at(0, 0, 5, t)));
        assertEquals(Verdict.ACCEPTED, g.judge(at(3, 0, 5, t += 2000)));
        assertEquals(Verdict.ACCEPTED, g.judge(at(6, 0, 5, t += 2000)));
        assertEquals(0, g.dropped());
    }

    @Test
    public void gateRejectsBadAccuracy() {
        LiveGate g = new LiveGate(new Thresholds());
        assertEquals(Verdict.ACCEPTED, g.judge(at(0, 0, 5, 1000)));
        assertEquals(Verdict.BAD_ACCURACY, g.judge(at(3, 0, 80, 2000)));
        assertEquals(1, g.dropped());
    }

    @Test
    public void gateAcceptsUnknownAccuracy() {
        LiveGate g = new LiveGate(new Thresholds());
        assertEquals(Verdict.ACCEPTED, g.judge(at(0, 0, Double.NaN, 1000)));
    }

    @Test
    public void gateRejectsImpossibleJump() {
        LiveGate g = new LiveGate(new Thresholds());
        assertEquals(Verdict.ACCEPTED, g.judge(at(0, 0, 5, 1000)));
        // 200 m in one second is a multipath jump, not a walk.
        assertEquals(Verdict.TOO_FAST, g.judge(at(200, 0, 5, 2000)));
        assertEquals(1, g.dropped());
    }

    @Test
    public void gateIgnoresJitterWithoutCountingIt() {
        LiveGate g = new LiveGate(new Thresholds());
        assertEquals(Verdict.ACCEPTED, g.judge(at(0, 0, 5, 1000)));
        assertEquals(Verdict.TOO_CLOSE, g.judge(at(0.5, 0.5, 5, 2000)));
        assertEquals(Verdict.TOO_CLOSE, g.judge(at(-0.5, 0.5, 5, 3000)));
        assertEquals(0, g.dropped());
    }

    @Test
    public void gateRejectsStaleTimestamp() {
        LiveGate g = new LiveGate(new Thresholds());
        assertEquals(Verdict.ACCEPTED, g.judge(at(0, 0, 5, 5000)));
        assertEquals(Verdict.STALE_TIME, g.judge(at(5, 0, 5, 5000)));
    }

    @Test
    public void resumeAfterPauseForgivesTheGap() {
        LiveGate g = new LiveGate(new Thresholds());
        assertEquals(Verdict.ACCEPTED, g.judge(at(0, 0, 5, 1000)));
        g.resetReference();
        // Walked 500 m while paused, in two seconds of recorded time.
        assertEquals(Verdict.ACCEPTED, g.judge(at(500, 0, 5, 3000)));
    }

    @Test
    public void cleanupRemovesAnOutAndBackSpike() {
        List<Fix> pts = new ArrayList<>();
        for (int i = 0; i <= 10; i++)
            pts.add(at(i * 5, 0, 5, i * 1000));
        // Insert a 60 m excursion between points 5 and 6 that comes straight back.
        pts.add(6, at(27, 60, 5, 5500));
        boolean[] keep = FixFilter.cleanup(pts, new Thresholds());
        assertFalse("spike vertex should be dropped", keep[6]);
        assertTrue(keep[0]);
        assertTrue(keep[pts.size() - 1]);
        assertEquals(FixFilter.removedCount(keep) >= 1, true);
    }

    @Test
    public void cleanupKeepsARealCorner() {
        // A right angle: 50 m east then 50 m north. Not a spike, since the neighbors
        // of the corner are far apart.
        List<Fix> pts = new ArrayList<>();
        pts.add(at(0, 0, 5, 1000));
        pts.add(at(50, 0, 5, 2000));
        pts.add(at(50, 50, 5, 3000));
        boolean[] keep = FixFilter.cleanup(pts, new Thresholds());
        assertTrue(keep[1]);
    }

    @Test
    public void underTheCapEveryAcceptedFixIsKept() {
        List<Fix> pts = new ArrayList<>();
        for (int i = 0; i <= 20; i++)
            pts.add(at(i * 3, 0.2 * (i % 2), 5, i * 1000)); // 20 cm wobble
        boolean[] keep = FixFilter.cleanup(pts, new Thresholds());
        assertEquals(0, FixFilter.removedCount(keep));
    }

    @Test
    public void overTheCapThinsJustEnough() {
        Thresholds t = new Thresholds();
        t.maxPoints = 50;
        List<Fix> pts = new ArrayList<>();
        // A big gentle circle, 400 points, 3 m apart.
        for (int i = 0; i < 400; i++) {
            double ang = 2 * Math.PI * i / 400;
            pts.add(at(190 * Math.cos(ang), 190 * Math.sin(ang), 5, i * 1000));
        }
        boolean[] keep = FixFilter.cleanup(pts, t);
        int kept = pts.size() - FixFilter.removedCount(keep);
        assertTrue("thinned under the cap: " + kept, kept <= 50);
        assertTrue("but not to nothing: " + kept, kept >= 12);
        assertTrue(keep[0]);
        assertTrue(keep[399]);
    }

    @Test
    public void simplifyKeepsABend() {
        List<Fix> pts = new ArrayList<>();
        for (int i = 0; i <= 10; i++)
            pts.add(at(i * 3, 0, 5, i * 1000));
        for (int i = 1; i <= 10; i++)
            pts.add(at(30, i * 3, 5, 10_000 + i * 1000));
        Thresholds t = new Thresholds();
        t.maxPoints = 5;
        boolean[] keep = FixFilter.cleanup(pts, t);
        assertTrue("the corner must survive", keep[10]);
        assertTrue(pts.size() - FixFilter.removedCount(keep) <= 5);
    }

    @Test
    public void applyFollowsTheMask() {
        List<String> items = new ArrayList<>();
        items.add("a");
        items.add("b");
        items.add("c");
        List<String> out = FixFilter.apply(items, new boolean[] { true, false, true });
        assertEquals(2, out.size());
        assertEquals("c", out.get(1));
    }
}
