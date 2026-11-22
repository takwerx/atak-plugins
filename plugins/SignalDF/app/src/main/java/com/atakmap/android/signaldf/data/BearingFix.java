package com.atakmap.android.signaldf.data;

import java.util.Locale;

/**
 * Where the transmitter is, from a pile of bearings taken while driving.
 *
 * <p>This is the arithmetic only: plain doubles in a local east/north frame in
 * meters, no Android, no map, no ATAK. That is deliberate. It is the piece
 * that decides where a marker goes and how big the uncertainty around it is,
 * and it has to be checkable on a laptop against cases whose answers are known
 * by hand rather than only on a truck against a signal nobody can see.
 *
 * <h2>The method</h2>
 *
 * <p>Each bearing is a line, not a point: the receiver was at
 * {@code (xi, yi)} and the signal came from azimuth {@code ti}. A candidate
 * target {@code (x, y)} sits on that line exactly when its offset from the
 * receiver has no component across the line. Writing the across-line normal of
 * a compass azimuth as {@code (cos t, -sin t)} in east/north, that is:
 *
 * <pre>cos(ti) * (x - xi) - sin(ti) * (y - yi) = 0</pre>
 *
 * <p>which is <em>linear</em> in x and y. So the best fit over N bearings is an
 * ordinary weighted least squares solve of a 2x2 system, in closed form, in
 * microseconds. No search, no grid, no iteration to converge.
 *
 * <p>That estimator -- the pseudo-linear or "Least Squares" DF fix -- has a
 * known bias: it minimizes across-line <em>distance</em>, so a receiver ten
 * kilometers away is allowed to pull the answer as hard as one that is two
 * hundred meters away, even though the same angular error is fifty times
 * bigger on the ground out there. The fix is Stansfield's: once there is an
 * estimate, reweight each bearing by {@code 1/range^2} to the estimate and
 * solve again. Two passes of that is enough at these scales, and it is what
 * {@link #solve} does.
 *
 * <h2>The uncertainty is the point</h2>
 *
 * <p>A position with no error on it is worse than no position, because it
 * looks like knowledge. The solve returns the 2x2 covariance of the estimate,
 * scaled by the residuals it actually got, and {@link Fix#ellipse()} turns
 * that into the 95% error ellipse -- semi-axes and the orientation of the long
 * one. Chi-square with two degrees of freedom puts 95% at 5.991, which is
 * where that constant comes from.
 *
 * <p>The ellipse is also the honest report on geometry. Bearings taken from a
 * short stretch of road all point the same way, they cross at a shallow angle,
 * and the result is a cigar tens of kilometers long pointing down the line of
 * sight -- from bearings that were each individually excellent. That is not a
 * failure of the math and it cannot be fixed with more samples from the same
 * place. It is fixed by driving across the bearing, which is why
 * {@link Fix#advice()} exists and says so in words.
 */
public final class BearingFix {

    /** Chi-square, two degrees of freedom, 95%: the ellipse scale factor. */
    public static final double CHI2_95_2DOF = 5.991;

    /**
     * Ranges shorter than this are treated as this in the Stansfield
     * reweighting, in meters.
     *
     * <p>The weight is {@code 1/range^2}, which runs away as the range goes to
     * zero: a bearing whose receiver happens to sit almost on top of the
     * current estimate would otherwise be given effectively infinite weight
     * and decide the answer by itself. A hundred meters is below any range at
     * which a DF bearing means much anyway.
     */
    private static final double MIN_RANGE_M = 100.0;

    /** The least number of bearings that can produce a fix at all. */
    public static final int MIN_BEARINGS = 3;

    private BearingFix() {
    }

    /** The answer, with everything needed to draw and to judge it. */
    public static final class Fix {

        /** Estimate, meters east and north of the frame's origin. */
        public final double x;
        public final double y;

        /** Covariance of the estimate: [xx, xy, yx, yy], square meters. */
        public final double cxx;
        public final double cxy;
        public final double cyy;

        /** How many bearings went in, and how widely they were spread. */
        public final int count;
        public final double spreadDeg;

        Fix(double x, double y, double cxx, double cxy, double cyy,
                int count, double spreadDeg) {
            this.x = x;
            this.y = y;
            this.cxx = cxx;
            this.cxy = cxy;
            this.cyy = cyy;
            this.count = count;
            this.spreadDeg = spreadDeg;
        }

        /**
         * The 95% error ellipse: {@code [semiMajorM, semiMinorM, majorDeg]},
         * where {@code majorDeg} is the compass azimuth of the long axis.
         *
         * <p>Straight out of the covariance's eigenvectors. The closed form is
         * used rather than a library because it is a symmetric 2x2 and the
         * closed form is four lines.
         */
        public double[] ellipse() {
            double t = cxx + cyy;
            double d = cxx * cyy - cxy * cxy;
            double disc = Math.sqrt(Math.max(0.0, t * t / 4.0 - d));
            double l1 = t / 2.0 + disc;
            double l2 = t / 2.0 - disc;
            double semiMajor = Math.sqrt(Math.max(0.0, l1) * CHI2_95_2DOF);
            double semiMinor = Math.sqrt(Math.max(0.0, l2) * CHI2_95_2DOF);
            // Eigenvector for the larger eigenvalue, in east/north, converted
            // to a compass azimuth: atan2(east, north).
            double ex, ey;
            if (Math.abs(cxy) > 1e-12) {
                ex = l1 - cyy;
                ey = cxy;
            } else {
                ex = cxx >= cyy ? 1.0 : 0.0;
                ey = cxx >= cyy ? 0.0 : 1.0;
            }
            double deg = Angles.norm360(Math.toDegrees(Math.atan2(ex, ey)));
            return new double[] { semiMajor, semiMinor, deg };
        }

        /**
         * One number for "how far off could this be", in meters: the ellipse's
         * LONG semi-axis.
         *
         * <p>Not the geometric mean of the two axes, which is what this
         * returned first and which is quietly wrong in the case that matters
         * most. A fix from a short stretch of road is a cigar -- a hundred
         * meters across and two kilometers long -- and the geometric mean of
         * those is four hundred and fifty meters. "Fix within 450 m" is a
         * number an operator would act on, and the transmitter can be more
         * than a kilometer away along the cigar. Seen on the first virtual
         * drive: the pane read "within 338 ft" while also saying the fix was
         * long and thin, and the two statements could not both be acted on.
         *
         * <p>The long axis is the honest single number, and the ellipse is
         * still drawn so the shape is visible rather than summarized.
         */
        public double accuracyM() {
            return ellipse()[0];
        }

        /**
         * What to do next to make the fix better, in words an operator can act
         * on from the driver's seat, or null when the geometry is already
         * good.
         *
         * <p>This is the part every other tool leaves out. A fix from a narrow
         * spread of bearings looks exactly as confident as a good one unless
         * something says otherwise, and the answer is never "collect more from
         * here" -- it is "go stand somewhere else".
         */
        public String advice() {
            if (count < MIN_BEARINGS)
                return "Keep driving -- a fix needs bearings from at least "
                        + MIN_BEARINGS + " places.";
            if (spreadDeg < 15.0) {
                double[] e = ellipse();
                return String.format(Locale.US,
                        "Poor crossing angle -- your bearings only span "
                                + "%.0f degrees. Drive across them, toward "
                                + "%03.0f or %03.0f, to tighten this.",
                        spreadDeg, Angles.norm360(e[2] + 90.0),
                        Angles.norm360(e[2] + 270.0));
            }
            double[] e = ellipse();
            if (e[1] > 0 && e[0] / e[1] > 4.0)
                return String.format(Locale.US,
                        "The fix is long and thin along %03.0f. Moving across "
                                + "that line will shorten it fastest.", e[2]);
            // The other half of the advice, and it was missing. There are two
            // regimes and they call for opposite things: while the crossing
            // angle is poor, drive ACROSS the bearings, which is what the
            // waypoint is for. Once it is good, the waypoint disappears and
            // the right move is the opposite -- drive AT the fix. Saying
            // nothing at that moment leaves the operator watching a waypoint
            // vanish with no idea that the answer is now the marker itself.
            return "Geometry is good. The fix marker is the place to go.";
        }
    }

    /**
     * Solve for the transmitter.
     *
     * @param x       receiver easting, meters, one per bearing
     * @param y       receiver northing, meters
     * @param degTrue the bearing measured from that receiver, compass degrees
     * @param weight  per-bearing weight, higher is better trusted; a confidence
     *                works directly
     * @param n       how many entries of each array to use
     * @return the fix, or null when the bearings cannot produce one -- too few,
     *         or all parallel, which is what a straight road with a target
     *         dead ahead produces and is a real thing to refuse
     */
    public static Fix solve(double[] x, double[] y, double[] degTrue,
            double[] weight, int n) {
        if (n < MIN_BEARINGS)
            return null;

        double[] w = new double[n];
        for (int i = 0; i < n; i++)
            w[i] = weight == null || Double.isNaN(weight[i]) || weight[i] <= 0
                    ? 1.0 : weight[i];

        double[] p = weightedSolve(x, y, degTrue, w, n);
        if (p == null)
            return null;

        // Stansfield: the pseudo-linear solve above treats a bearing from ten
        // kilometers away as it treats one from two hundred meters, though the
        // same angular error is fifty times wider on the ground out there.
        // Reweighting by 1/range^2 to the current estimate corrects that. Two
        // passes; it converges fast and a third never moved the answer enough
        // to see at these scales.
        double[] rw = new double[n];
        for (int pass = 0; pass < 2; pass++) {
            for (int i = 0; i < n; i++) {
                double r = Math.max(MIN_RANGE_M,
                        Math.hypot(p[0] - x[i], p[1] - y[i]));
                rw[i] = w[i] / (r * r);
            }
            double[] next = weightedSolve(x, y, degTrue, rw, n);
            if (next == null)
                break;
            p = next;
        }

        // Covariance, scaled by the residuals actually seen rather than by an
        // assumed bearing error: if the bearings disagree, the ellipse grows,
        // which is the behavior an operator can trust without being told what
        // the radio's accuracy is.
        double sxx = 0, sxy = 0, syy = 0, rss = 0;
        for (int i = 0; i < n; i++) {
            double t = Math.toRadians(degTrue[i]);
            double a = Math.cos(t), b = -Math.sin(t);
            double r = a * (p[0] - x[i]) + b * (p[1] - y[i]);
            double wi = rw[i] > 0 ? rw[i] : w[i];
            rss += wi * r * r;
            sxx += wi * a * a;
            sxy += wi * a * b;
            syy += wi * b * b;
        }
        double det = sxx * syy - sxy * sxy;
        if (det == 0 || Double.isNaN(det))
            return null;
        double sigma2 = n > 2 ? rss / (n - 2) : rss;
        double cxx = sigma2 * syy / det;
        double cxy = -sigma2 * sxy / det;
        double cyy = sigma2 * sxx / det;

        return new Fix(p[0], p[1], cxx, cxy, cyy, n, spreadDeg(degTrue, n));
    }

    /** One weighted least squares pass. Returns {x, y} or null if singular. */
    private static double[] weightedSolve(double[] x, double[] y,
            double[] degTrue, double[] w, int n) {
        double sxx = 0, sxy = 0, syy = 0, bx = 0, by = 0;
        for (int i = 0; i < n; i++) {
            double t = Math.toRadians(degTrue[i]);
            // Normal to the bearing line, in east/north.
            double a = Math.cos(t), b = -Math.sin(t);
            double c = a * x[i] + b * y[i];
            sxx += w[i] * a * a;
            sxy += w[i] * a * b;
            syy += w[i] * b * b;
            bx += w[i] * a * c;
            by += w[i] * b * c;
        }
        double det = sxx * syy - sxy * sxy;
        // Parallel bearings make this singular, which is the correct outcome:
        // lines that never cross do not locate anything, however many there
        // are. A straight drive with the target dead ahead does exactly this.
        if (Math.abs(det) < 1e-9 || Double.isNaN(det))
            return null;
        return new double[] {
                (syy * bx - sxy * by) / det,
                (sxx * by - sxy * bx) / det
        };
    }

    /**
     * How widely the bearings are spread, in degrees.
     *
     * <p>Computed modulo 180, not 360: a bearing of 010 and one of 190 are the
     * same line through the target and cross nothing, so they are no spread at
     * all. Treating them as 180 degrees apart would report perfect geometry
     * for a drive straight down the line of sight, which is the worst geometry
     * there is.
     */
    static double spreadDeg(double[] degTrue, int n) {
        if (n < 2)
            return 0;
        double[] a = new double[n];
        for (int i = 0; i < n; i++)
            a[i] = ((degTrue[i] % 180) + 180) % 180;
        java.util.Arrays.sort(a, 0, n);
        // The widest gap on a circle of circumference 180 is the part NOT
        // covered; the spread is what is left.
        double widest = a[0] + 180.0 - a[n - 1];
        for (int i = 1; i < n; i++)
            widest = Math.max(widest, a[i] - a[i - 1]);
        return 180.0 - widest;
    }
}
