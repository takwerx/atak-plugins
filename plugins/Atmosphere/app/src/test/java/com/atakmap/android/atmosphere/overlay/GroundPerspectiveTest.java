package com.atakmap.android.atmosphere.overlay;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** The 3D wind's ground-to-screen perspective: fitted, applied, inverted, horizon. */
public class GroundPerspectiveTest {

    private static boolean solve(double[] gx, double[] gy, double[] sx, double[] sy, double[] h) {
        return GroundPerspective.solve(gx, gy, sx, sy, h);
    }

    private static boolean invert(double[] h, double[] out) {
        return GroundPerspective.invert(h, out);
    }

    private static boolean apply(double[] h, double x, double y, double[] out) {
        return GroundPerspective.apply(h, x, y, out);
    }

    /** A tilted camera's plane-to-screen map, foreshortened toward +y (north, up screen). */
    private static final double[] TRUE_H = { 900, 40, 1200, 15, -700, 540, 0.02, 1.6, 1 };

    private static double[] project(double x, double y) {
        final double w = TRUE_H[6] * x + TRUE_H[7] * y + TRUE_H[8];
        return new double[] { (TRUE_H[0] * x + TRUE_H[1] * y + TRUE_H[2]) / w,
                (TRUE_H[3] * x + TRUE_H[4] * y + TRUE_H[5]) / w };
    }

    @Test
    public void fitsFourPointsAndPlacesEveryOther() {
        final double[] gx = { -0.01, 0.01, 0.01, -0.01 }, gy = { 0.01, 0.01, -0.01, -0.01 };
        final double[] sx = new double[4], sy = new double[4];
        for (int i = 0; i < 4; i++) {
            final double[] s = project(gx[i], gy[i]);
            sx[i] = s[0];
            sy[i] = s[1];
        }
        final double[] h = new double[9];
        assertTrue(solve(gx, gy, sx, sy, h));
        // A point well outside the four it was fitted to, as far ground is.
        final double[] out = new double[2];
        assertTrue(apply(h, 0.07, 0.25, out));
        final double[] want = project(0.07, 0.25);
        assertEquals(want[0], out[0], 1e-6);
        assertEquals(want[1], out[1], 1e-6);
    }

    @Test
    public void invertsScreenBackToGround() {
        final double[] inv = new double[9], g = new double[2];
        assertTrue(invert(TRUE_H, inv));
        final double[] s = project(-0.03, 0.12);
        assertTrue(apply(inv, s[0], s[1], g));
        assertEquals(-0.03, g[0], 1e-9);
        assertEquals(0.12, g[1], 1e-9);
    }

    @Test
    public void groundBeyondTheHorizonIsRefused() {
        // w = 0.02x + 1.6y + 1 is zero at y = -0.625: everything past it is behind.
        final double[] out = new double[2];
        assertFalse(apply(TRUE_H, 0, -0.7, out));
        assertTrue(apply(TRUE_H, 0, -0.5, out));
    }

    @Test
    public void threePointsInALineAreRefused() {
        final double[] gx = { 0, 0.01, 0.02, 0.0 }, gy = { 0, 0.01, 0.02, 0.01 };
        final double[] sx = { 0, 10, 20, 30 }, sy = { 0, 10, 20, 0 };
        assertFalse(solve(gx, gy, sx, sy, new double[9]));
    }
}
