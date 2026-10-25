package com.atakmap.android.atmosphere.overlay;

/**
 * The perspective that carries the ground plane to the screen in a tilted (3D) map:
 * a 3x3 homography fitted to four ground points the map has projected, so every
 * other ground point lands where the map would put it at the cost of a few
 * multiplies, and a screen pixel can be taken back down to the ground.
 *
 * <p>The flat map gets away with stretching between the four projected corners of a
 * lat/lon box. A tilted map does not: the far side of the box is foreshortened and
 * the horizon may be in view, and that stretch put the wind particles nowhere
 * (operator, 2026-10-24: the wind "disappears" in 3D). A homography is the exact
 * map from one plane to another under a pinhole camera, which is what a tilted
 * view of locally flat ground is.
 *
 * <p>Ground coordinates are degrees of longitude (x) and latitude (y) measured from
 * a nearby origin, so the numbers stay small and the fit stays well conditioned.
 * Pure arithmetic, no Android, so it is tested on the JVM.
 */
final class GroundPerspective {
    private GroundPerspective() {
    }

    /**
     * Fits {@code h} (row major, h[8] = 1) so that ground (gx[i], gy[i]) goes to
     * screen (sx[i], sy[i]) for the four points. False when they are degenerate
     * (three in a line, or a projection that failed).
     */
    static boolean solve(double[] gx, double[] gy, double[] sx, double[] sy, double[] h) {
        final double[][] a = new double[8][9];
        for (int i = 0; i < 4; i++) {
            final double x = gx[i], y = gy[i], u = sx[i], v = sy[i];
            if (Double.isNaN(x + y + u + v) || Double.isInfinite(x + y + u + v))
                return false;
            final double[] r1 = a[2 * i], r2 = a[2 * i + 1];
            r1[0] = x; r1[1] = y; r1[2] = 1; r1[6] = -x * u; r1[7] = -y * u; r1[8] = u;
            r2[3] = x; r2[4] = y; r2[5] = 1; r2[6] = -x * v; r2[7] = -y * v; r2[8] = v;
        }
        // Gaussian elimination with partial pivoting on the 8x8 system.
        for (int c = 0; c < 8; c++) {
            int p = c;
            for (int r = c + 1; r < 8; r++)
                if (Math.abs(a[r][c]) > Math.abs(a[p][c]))
                    p = r;
            if (Math.abs(a[p][c]) < 1e-12)
                return false;
            final double[] t = a[c];
            a[c] = a[p];
            a[p] = t;
            for (int r = 0; r < 8; r++) {
                if (r == c)
                    continue;
                final double f = a[r][c] / a[c][c];
                if (f == 0)
                    continue;
                for (int k = c; k < 9; k++)
                    a[r][k] -= f * a[c][k];
            }
        }
        for (int i = 0; i < 8; i++)
            h[i] = a[i][8] / a[i][i];
        h[8] = 1;
        return true;
    }

    /** The exact inverse of {@code h} into {@code out}; false when it is singular. */
    static boolean invert(double[] h, double[] out) {
        final double a = h[0], b = h[1], c = h[2], d = h[3], e = h[4], f = h[5],
                g = h[6], i = h[7], k = h[8];
        final double c00 = e * k - f * i, c01 = -(d * k - f * g), c02 = d * i - e * g;
        final double det = a * c00 + b * c01 + c * c02;
        if (Math.abs(det) < 1e-18 || Double.isNaN(det))
            return false;
        out[0] = c00 / det;
        out[1] = -(b * k - c * i) / det;
        out[2] = (b * f - c * e) / det;
        out[3] = c01 / det;
        out[4] = (a * k - c * g) / det;
        out[5] = -(a * f - c * d) / det;
        out[6] = c02 / det;
        out[7] = -(a * i - b * g) / det;
        out[8] = (a * e - b * d) / det;
        return true;
    }

    /**
     * Applies {@code m} to (x, y) into {@code out} = {x', y'}; false when the point
     * is at or beyond the horizon (on the far side of the camera), where the
     * homogeneous weight is not positive. Fitted with h[8] = 1 at a point in front
     * of the camera, a positive weight is exactly "in front".
     */
    static boolean apply(double[] m, double x, double y, double[] out) {
        final double w = m[6] * x + m[7] * y + m[8];
        if (!(w > 1e-12))
            return false;
        out[0] = (m[0] * x + m[1] * y + m[2]) / w;
        out[1] = (m[3] * x + m[4] * y + m[5]) / w;
        return true;
    }
}
