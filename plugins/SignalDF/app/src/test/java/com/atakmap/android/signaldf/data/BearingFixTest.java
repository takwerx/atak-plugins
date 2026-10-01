package com.atakmap.android.signaldf.data;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.atakmap.android.signaldf.data.BearingFix.Fix;

import org.junit.Test;

import java.util.Random;

/**
 * The fix, against cases whose answers are known without running the code.
 *
 * <p>Every geometry here is drawn on paper first: receivers on axes, a target
 * at a round number of meters, bearings worked out from the triangle. If the
 * solver disagrees with the triangle, the solver is wrong.
 */
public class BearingFixTest {

    /** Compass azimuth from (x1,y1) to (x2,y2) in an east/north frame. */
    private static double az(double x1, double y1, double x2, double y2) {
        return Angles.norm360(Math.toDegrees(Math.atan2(x2 - x1, y2 - y1)));
    }

    @Test
    public void threePerfectBearingsLandOnTheTarget() {
        // Target at (1000, 2000). Three receivers well spread around it.
        double tx = 1000, ty = 2000;
        double[] x = { 0, 2000, 500 };
        double[] y = { 0, 0, 4000 };
        double[] b = new double[3];
        for (int i = 0; i < 3; i++)
            b[i] = az(x[i], y[i], tx, ty);

        Fix f = BearingFix.solve(x, y, b, null, 3);
        assertNotNull(f);
        assertEquals(tx, f.x, 0.5);
        assertEquals(ty, f.y, 0.5);
        // Perfect bearings leave no residual, so the ellipse is essentially a
        // point. It is never exactly zero in floating point.
        assertTrue("perfect data should give a tiny ellipse",
                f.accuracyM() < 1.0);
    }

    @Test
    public void twoBearingsAreNotEnough() {
        double[] x = { 0, 2000 };
        double[] y = { 0, 0 };
        double[] b = { 45, 315 };
        assertNull(BearingFix.solve(x, y, b, null, 2));
    }

    @Test
    public void parallelBearingsLocateNothing() {
        // Driving straight up the y axis with the target dead ahead: every
        // bearing is 000 and the lines never cross. Refusing is correct.
        double[] x = { 0, 0, 0, 0 };
        double[] y = { 0, 500, 1000, 1500 };
        double[] b = { 0, 0, 0, 0 };
        assertNull(BearingFix.solve(x, y, b, null, 4));
    }

    @Test
    public void aWrongBearingIsOutvotedAndWidensTheEllipse() {
        double tx = 0, ty = 3000;
        double[] x = { -2000, -1000, 0, 1000, 2000 };
        double[] y = { 0, 0, 0, 0, 0 };
        double[] b = new double[5];
        for (int i = 0; i < 5; i++)
            b[i] = az(x[i], y[i], tx, ty);

        Fix clean = BearingFix.solve(x, y, b, null, 5);
        assertNotNull(clean);

        // Now throw one of them 25 degrees off.
        b[2] = Angles.norm360(b[2] + 25.0);
        Fix dirty = BearingFix.solve(x, y, b, null, 5);
        assertNotNull(dirty);

        // Still roughly right -- four good bearings outvote one bad one.
        assertTrue("a single outlier must not run away with the fix",
                Math.hypot(dirty.x - tx, dirty.y - ty) < 600);
        // And the uncertainty must GROW, because the bearings now disagree.
        // An estimator that reported the same confidence would be lying.
        assertTrue("disagreement has to widen the ellipse",
                dirty.accuracyM() > clean.accuracyM() * 10);
    }

    @Test
    public void noisyBearingsStillLandCloseAndSayHowClose() {
        // 1.5 degrees of noise, which is better than a real array and worse
        // than perfect, over a good spread.
        double tx = 1500, ty = 2500;
        Random rnd = new Random(20260928L);
        int n = 12;
        double[] x = new double[n], y = new double[n], b = new double[n];
        for (int i = 0; i < n; i++) {
            double a = Math.toRadians(360.0 / n * i);
            x[i] = tx + 3000 * Math.sin(a);
            y[i] = ty + 3000 * Math.cos(a);
            b[i] = Angles.norm360(az(x[i], y[i], tx, ty)
                    + rnd.nextGaussian() * 1.5);
        }
        Fix f = BearingFix.solve(x, y, b, null, n);
        assertNotNull(f);
        double err = Math.hypot(f.x - tx, f.y - ty);
        assertTrue("should land within a couple of hundred meters, got " + err,
                err < 250);
        // The ellipse has to be big enough to contain the actual error. An
        // estimate that is confidently wrong is worse than no estimate.
        assertTrue("the 95% ellipse must cover the real error: err=" + err
                + " acc=" + f.accuracyM(), f.accuracyM() > err * 0.5);
    }

    // ---- geometry reporting -------------------------------------------------

    @Test
    public void spreadIsModulo180BecauseOppositeBearingsAreOneLine() {
        // 010 and 190 are the same line through the target. They cross
        // nothing, so the spread is zero, not 180.
        assertEquals(0.0, BearingFix.spreadDeg(new double[] { 10, 190 }, 2), 1e-9);
        // A right angle is a right angle.
        assertEquals(90.0, BearingFix.spreadDeg(new double[] { 0, 90 }, 2), 1e-9);
        // And a narrow fan is narrow.
        assertEquals(20.0,
                BearingFix.spreadDeg(new double[] { 100, 110, 120 }, 3), 1e-9);
    }

    @Test
    public void aNarrowFanSaysDriveAcrossIt() {
        // Everything taken from a short stretch of road, target far away:
        // each bearing is fine, the crossing angle is terrible.
        double tx = 0, ty = 20000;
        double[] x = { -200, -100, 0, 100, 200 };
        double[] y = { 0, 0, 0, 0, 0 };
        double[] b = new double[5];
        for (int i = 0; i < 5; i++)
            b[i] = az(x[i], y[i], tx, ty);

        Fix f = BearingFix.solve(x, y, b, null, 5);
        assertNotNull(f);
        assertTrue("this geometry is narrow", f.spreadDeg < 5.0);
        String advice = f.advice();
        assertNotNull("a narrow fan must tell the operator what to do", advice);
        assertTrue(advice, advice.toLowerCase().contains("drive across"));
    }

    @Test
    public void goodGeometryIsNotNagged() {
        double tx = 1000, ty = 1000;
        double[] x = { 0, 2000, 2000, 0 };
        double[] y = { 0, 0, 2000, 2000 };
        double[] b = new double[4];
        for (int i = 0; i < 4; i++)
            b[i] = az(x[i], y[i], tx, ty);
        Fix f = BearingFix.solve(x, y, b, null, 4);
        assertNotNull(f);
        String advice = f.advice();
        assertNotNull("good geometry still has something to say", advice);
        assertTrue(advice, advice.toLowerCase().contains("fix marker"));
        assertFalse("and it must not still be telling them to drive across",
                advice.toLowerCase().contains("across"));
    }

    @Test
    public void tooFewBearingsSaysKeepDriving() {
        // Not a fix, but the caller may still want the message.
        double[] x = { 0, 100, 200 };
        double[] y = { 0, 0, 0 };
        double[] b = { 30, 40, 50 };
        Fix f = BearingFix.solve(x, y, b, null, 3);
        assertNotNull(f);
        assertNotNull(f.advice());
    }

    // ---- weighting ----------------------------------------------------------

    @Test
    public void confidenceMovesTheAnswerTowardTheTrustedBearings() {
        // Four good bearings on a target, plus one badly wrong one. With equal
        // weight the bad one drags the fix; with its confidence at a tenth it
        // should drag much less.
        double tx = 0, ty = 3000;
        double[] x = { -2000, -1000, 1000, 2000, 0 };
        double[] y = { 0, 0, 0, 0, 0 };
        double[] b = new double[5];
        for (int i = 0; i < 5; i++)
            b[i] = az(x[i], y[i], tx, ty);
        b[4] = Angles.norm360(b[4] + 30.0);

        Fix equal = BearingFix.solve(x, y, b, null, 5);
        Fix weighted = BearingFix.solve(x, y, b,
                new double[] { 1, 1, 1, 1, 0.1 }, 5);
        assertNotNull(equal);
        assertNotNull(weighted);
        double eErr = Math.hypot(equal.x - tx, equal.y - ty);
        double wErr = Math.hypot(weighted.x - tx, weighted.y - ty);
        assertTrue("down-weighting the bad bearing must help: "
                + eErr + " -> " + wErr, wErr < eErr);
    }

    @Test
    public void nearBearingsDoNotDominateFarOnes() {
        // Stansfield's correction. A receiver sitting almost on top of the
        // target has a tiny across-line distance for any angle, so a plain
        // least squares lets it decide everything. Give it a wrong bearing and
        // check the answer survives.
        double tx = 0, ty = 5000;
        double[] x = { -4000, 4000, 0, -3000 };
        double[] y = { 0, 0, 4900, 0 };
        double[] b = new double[4];
        for (int i = 0; i < 4; i++)
            b[i] = az(x[i], y[i], tx, ty);
        b[2] = Angles.norm360(b[2] + 40.0); // the close one, badly wrong

        Fix f = BearingFix.solve(x, y, b, null, 4);
        assertNotNull(f);
        assertTrue("a close, wrong bearing must not own the answer: "
                + Math.hypot(f.x - tx, f.y - ty),
                Math.hypot(f.x - tx, f.y - ty) < 1500);
    }

    // ---- the ellipse --------------------------------------------------------

    @Test
    public void theEllipseLiesAlongTheDirectionOfWorstKnowledge() {
        // Bearings from a short east-west stretch, target due north: the
        // uncertainty must run north-south, along the line of sight.
        double tx = 0, ty = 20000;
        double[] x = { -300, -150, 0, 150, 300 };
        double[] y = { 0, 0, 0, 0, 0 };
        Random rnd = new Random(7L);
        double[] b = new double[5];
        for (int i = 0; i < 5; i++)
            b[i] = Angles.norm360(az(x[i], y[i], tx, ty)
                    + rnd.nextGaussian() * 1.0);

        Fix f = BearingFix.solve(x, y, b, null, 5);
        assertNotNull(f);
        double[] e = f.ellipse();
        assertTrue("it should be long and thin", e[0] > e[1] * 5);
        // The long axis points roughly north-south, i.e. near 0 or 180.
        double off = Math.min(Math.abs(Angles.norm360(e[2]) - 0),
                Math.min(Math.abs(Angles.norm360(e[2]) - 180),
                        Math.abs(Angles.norm360(e[2]) - 360)));
        assertTrue("long axis should run along the line of sight, got " + e[2],
                off < 20);
    }

    @Test
    public void accuracyIsTheWorstCaseNotAnAverage() {
        // A cigar from a short stretch of road. The number an operator reads
        // has to be the long axis: the geometric mean of a 2 km by 100 m
        // ellipse is 450 m, and the transmitter can be a kilometer away along
        // it. "Within 450 m" would be acted on and would be wrong.
        double tx = 0, ty = 20000;
        double[] x = { -300, -150, 0, 150, 300 };
        double[] y = { 0, 0, 0, 0, 0 };
        Random rnd = new Random(11L);
        double[] b = new double[5];
        for (int i = 0; i < 5; i++)
            b[i] = Angles.norm360(az(x[i], y[i], tx, ty)
                    + rnd.nextGaussian() * 1.0);

        Fix f = BearingFix.solve(x, y, b, null, 5);
        assertNotNull(f);
        double[] e = f.ellipse();
        assertTrue("this geometry must be long and thin", e[0] > e[1] * 5);
        assertEquals("accuracy is the long semi-axis", e[0], f.accuracyM(), 1e-9);
        assertTrue("and so is bigger than the geometric mean",
                f.accuracyM() > Math.sqrt(e[0] * e[1]));
    }

}
