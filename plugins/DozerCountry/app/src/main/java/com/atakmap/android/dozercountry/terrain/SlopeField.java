package com.atakmap.android.dozercountry.terrain;

import com.atakmap.android.dozercountry.model.DozerStandard;

/**
 * The slope raster for one area of interest: elevations in, percent slope out,
 * worst-in-window applied, then colored by the standard.
 *
 * <p>Pure arithmetic on purpose — no ATAK types, no Android types, no threading. The
 * numbers behind a safety overlay should be checkable without a device in the loop.
 * {@link TerrainSampler} is what talks to ATAK and hands the elevations here.
 *
 * <h3>Percent, not degrees</h3>
 *
 * Slope is {@code rise/run x 100}. That is what the NWCG tables are written in and
 * what a dozer boss says out loud. Note this also means the geoid model does not
 * matter: slope is a difference of elevations, so HAE and MSL give the same answer as
 * long as one of them is used throughout.
 *
 * <h3>Worst in window, not per cell</h3>
 *
 * A single 30 m cell at 20% sitting inside a 60% face is not dozer country, and a
 * per-cell raster paints it green. Each cell therefore takes the <b>steepest</b> slope
 * within the working window of it, so a patch of good ground smaller than the window
 * never shows as workable. This is the same idea as the HLZ convolution: ask whether a
 * neighborhood works, not whether a pixel does.
 */
public final class SlopeField {

    /** Row-major, row 0 northmost. Percent slope, NaN where terrain is unknown. */
    public final double[] percent;
    public final int width;
    public final int height;
    /** Ground distance one cell covers, in metres. Reported to the operator. */
    public final double cellMeters;
    /**
     * The window actually used, in metres. Cells are discrete, so the requested
     * window is rounded to an odd number of cells and this is what came out — the
     * pane shows this rather than the request, because it is what was computed.
     */
    public final double windowMeters;
    /** Cells whose elevation could not be resolved. Zero means full coverage. */
    public final int unknownCells;

    private SlopeField(double[] percent, int width, int height, double cellMeters,
            double windowMeters, int unknownCells) {
        this.percent = percent;
        this.width = width;
        this.height = height;
        this.cellMeters = cellMeters;
        this.windowMeters = windowMeters;
        this.unknownCells = unknownCells;
    }

    /** True when not one cell resolved — no elevation data for this ground. */
    public boolean isEmpty() {
        return unknownCells >= width * height;
    }

    /**
     * Builds the field from a grid of elevations.
     *
     * @param elevations   row-major, row 0 northmost, NaN where unknown
     * @param width        cells east-west
     * @param height       cells north-south
     * @param cellEastM    ground metres between columns
     * @param cellNorthM   ground metres between rows
     * @param windowM      requested working window across, in metres
     */
    public static SlopeField compute(double[] elevations, int width, int height,
            double cellEastM, double cellNorthM, double windowM) {

        final double[] raw = new double[width * height];
        int unknown = 0;

        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                final int i = y * width + x;
                final double s = slopeAt(elevations, width, height, x, y,
                        cellEastM, cellNorthM);
                raw[i] = s;
                if (Double.isNaN(s))
                    unknown++;
            }
        }

        final double cellM = (cellEastM + cellNorthM) / 2d;
        // Window radius in whole cells. At least 1: a window narrower than a cell is
        // not a window, and claiming one would overstate what the data resolves.
        final int r = Math.max(1, (int) Math.round(windowM / (2d * cellM)));
        final double effectiveWindow = (2 * r + 1) * cellM;

        final double[] worst = windowMax(raw, width, height, r);

        return new SlopeField(worst, width, height, cellM, effectiveWindow, unknown);
    }

    /**
     * Percent slope at one cell by central difference over its neighbors, falling back
     * to a one-sided difference at the edges of the grid.
     *
     * <p>Returns NaN if the cell or the neighbors it needs are unknown, rather than
     * treating a hole in the DTED as flat ground — a hole painted green is exactly the
     * failure this overlay must not have.
     */
    private static double slopeAt(double[] z, int w, int h, int x, int y,
            double cellEastM, double cellNorthM) {

        if (Double.isNaN(z[y * w + x]))
            return Double.NaN;

        final double dzdx = derivative(z, w, x, y, 1, 0, cellEastM);
        final double dzdy = derivative(z, w, x, y, 0, 1, cellNorthM);
        if (Double.isNaN(dzdx) || Double.isNaN(dzdy))
            return Double.NaN;

        return Math.sqrt(dzdx * dzdx + dzdy * dzdy) * 100d;
    }

    /**
     * One partial derivative, central where both neighbors exist and one-sided where
     * only one does.
     */
    private static double derivative(double[] z, int w, int x, int y,
            int dx, int dy, double spacingM) {
        final int h = z.length / w;
        final int xLo = x - dx, yLo = y - dy;
        final int xHi = x + dx, yHi = y + dy;

        final boolean loIn = xLo >= 0 && yLo >= 0 && xLo < w && yLo < h;
        final boolean hiIn = xHi >= 0 && yHi >= 0 && xHi < w && yHi < h;

        final double lo = loIn ? z[yLo * w + xLo] : Double.NaN;
        final double hi = hiIn ? z[yHi * w + xHi] : Double.NaN;
        final double here = z[y * w + x];

        if (!Double.isNaN(lo) && !Double.isNaN(hi))
            return (hi - lo) / (2d * spacingM);
        if (!Double.isNaN(hi))
            return (hi - here) / spacingM;
        if (!Double.isNaN(lo))
            return (here - lo) / spacingM;
        return Double.NaN;
    }

    /**
     * Separable maximum over a (2r+1) square, skipping NaN.
     *
     * <p>Deliberately the straightforward O(cells x r) form rather than a monotonic
     * deque. The window is one chain against cells of ten to thirty metres, so r is
     * one to three and the whole pass is a couple of million operations over a typical
     * area — the clever version would save nothing measurable and would make the NaN
     * handling, which is the part that matters, harder to check by eye.
     *
     * <p>NaN is skipped rather than propagated: a cell beside a hole in the DTED still
     * has a real worst-case from the neighbors that are known. A cell whose whole
     * window is unknown stays NaN and is left unpainted.
     */
    private static double[] windowMax(double[] in, int w, int h, int r) {
        final double[] tmp = new double[in.length];
        final double[] out = new double[in.length];

        for (int y = 0; y < h; y++) {
            final int row = y * w;
            for (int x = 0; x < w; x++) {
                double m = Double.NaN;
                final int lo = Math.max(0, x - r), hi = Math.min(w - 1, x + r);
                for (int i = lo; i <= hi; i++) {
                    final double v = in[row + i];
                    if (!Double.isNaN(v) && (Double.isNaN(m) || v > m))
                        m = v;
                }
                tmp[row + x] = m;
            }
        }

        for (int x = 0; x < w; x++) {
            for (int y = 0; y < h; y++) {
                double m = Double.NaN;
                final int lo = Math.max(0, y - r), hi = Math.min(h - 1, y + r);
                for (int j = lo; j <= hi; j++) {
                    final double v = tmp[j * w + x];
                    if (!Double.isNaN(v) && (Double.isNaN(m) || v > m))
                        m = v;
                }
                out[y * w + x] = m;
            }
        }
        return out;
    }

    /**
     * The ARGB raster to hand the layer, one int per cell, row 0 northmost.
     *
     * <p>Unknown cells come out fully transparent so the map shows through. The pane is
     * what tells the operator how much of the area that was; a transparent hole on its
     * own reads as "nothing steep here", which is the opposite of the truth.
     */
    public int[] toArgb(DozerStandard standard) {
        final int[] argb = new int[percent.length];
        for (int i = 0; i < percent.length; i++)
            argb[i] = standard.colorFor(percent[i]);
        return argb;
    }
}
