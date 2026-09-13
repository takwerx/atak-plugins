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
    /** Cells taken to be water and left unpainted. */
    public final int waterCells;
    /** Per cell, true where {@link #findWater} judged the surface to be water. */
    public final boolean[] water;

    private SlopeField(double[] percent, int width, int height, double cellMeters,
            double windowMeters, int unknownCells, boolean[] water, int waterCells) {
        this.percent = percent;
        this.width = width;
        this.height = height;
        this.cellMeters = cellMeters;
        this.windowMeters = windowMeters;
        this.unknownCells = unknownCells;
        this.water = water;
        this.waterCells = waterCells;
    }

    /** True when not one cell resolved — no elevation data for this ground. */
    public boolean isEmpty() {
        return unknownCells >= width * height;
    }

    /** True when everything in the area is either water or unresolved. */
    public boolean isNothingToClass() {
        return unknownCells + waterCells >= width * height;
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
     * @param waterMinAreaM2 a dead-flat region at least this large is taken to be
     *                       water and left unpainted; zero disables the mask
     */
    public static SlopeField compute(double[] elevations, int width, int height,
            double cellEastM, double cellNorthM, double windowM,
            double waterMinAreaM2) {

        final boolean[] water = findWater(elevations, width, height,
                cellEastM * cellNorthM, waterMinAreaM2);
        int waterCount = 0;
        for (boolean b : water)
            if (b) waterCount++;

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
        final int r = windowRadiusCells(windowM, cellM);
        final double effectiveWindow = (2 * r + 1) * cellM;

        // r == 0 means the cell is already about as wide as the window, so there is
        // nothing to widen and the raw slope is the answer.
        final double[] worst = (r == 0) ? raw : windowMax(raw, width, height, r);

        return new SlopeField(worst, width, height, cellM, effectiveWindow, unknown,
                water, waterCount);
    }

    /**
     * Finds water: connected regions of <em>identical</em> elevation big enough to be
     * a body of water rather than a coincidence.
     *
     * <h3>Why identical, and not merely flat</h3>
     *
     * ATAK ships no hydrography a plugin can query — there is not one water, coastline
     * or bathymetry class in {@code main.jar} — so the only thing available is the
     * elevation itself. "Low slope" would be hopeless: it would swallow the Oxnard
     * plain, which is exactly the good dozer ground the overlay exists to find.
     *
     * <p>Equality is much tighter. Real terrain always varies, even at DTED's one metre
     * quantisation; a water surface is rendered dead flat, because that is what it is.
     * So a run of cells all holding precisely the same value, over an area larger than
     * terrain would plausibly do by accident, is water. The ocean is one enormous such
     * region at zero; a reservoir is a smaller one at its own level, which is why this
     * catches lakes as well as sea and why it does not need to know where sea level is.
     *
     * <h3>What it gets wrong</h3>
     *
     * A dry lake bed is dead flat too, and will be masked. That is a real false
     * positive and it removes genuinely workable ground, so the area masked is counted
     * and reported in the pane rather than quietly disappearing, and the threshold
     * lives in {@code dozer_data.json} where it can be raised. The proper fix is a land
     * cover layer — ESA WorldCover has a permanent-water class and is free — and that
     * is a later version's job.
     *
     * @param cellAreaM2     ground area one cell covers
     * @param minAreaM2      smallest region to call water; zero disables the mask
     */
    static boolean[] findWater(double[] z, int w, int h, double cellAreaM2,
            double minAreaM2) {
        final boolean[] water = new boolean[w * h];
        if (!(minAreaM2 > 0d) || !(cellAreaM2 > 0d))
            return water;

        final int minCells = Math.max(2, (int) Math.ceil(minAreaM2 / cellAreaM2));
        final int[] id = new int[w * h];        // 0 = not yet assigned
        final int[] stack = new int[w * h];
        final int[] region = new int[w * h];
        int nextId = 0;

        for (int start = 0; start < z.length; start++) {
            if (id[start] != 0 || Double.isNaN(z[start]))
                continue;

            final double level = z[start];
            final int mine = ++nextId;
            int sp = 0, n = 0;
            stack[sp++] = start;
            id[start] = mine;

            // Iterative flood fill: a recursive one would blow the stack on an ocean.
            while (sp > 0) {
                final int i = stack[--sp];
                region[n++] = i;
                final int x = i % w, y = i / w;
                sp = maybePush(stack, sp, id, z, level, mine, x > 0 ? i - 1 : -1);
                sp = maybePush(stack, sp, id, z, level, mine, x < w - 1 ? i + 1 : -1);
                sp = maybePush(stack, sp, id, z, level, mine, y > 0 ? i - w : -1);
                sp = maybePush(stack, sp, id, z, level, mine, y < h - 1 ? i + w : -1);
            }

            if (n >= minCells && isWideEnough(id, w, h, region, n, mine)) {
                for (int k = 0; k < n; k++)
                    water[region[k]] = true;
            }
        }
        return water;
    }

    private static int maybePush(int[] stack, int sp, int[] id, double[] z,
            double level, int mine, int i) {
        if (i < 0 || id[i] != 0 || z[i] != level)
            return sp;
        id[i] = mine;
        stack[sp++] = i;
        return sp;
    }

    /**
     * Is the region genuinely two-dimensional, or is it a thin ribbon?
     *
     * <p>This is the check that keeps the water mask honest, and leaving it out is a
     * mistake that looks fine until it is tested. <b>Quantised elevation terraces.</b>
     * DTED is whole metres, so a smooth gentle hillside does not come back smooth — it
     * comes back as bands of constant elevation following the contours, one band per
     * metre of fall. Those bands are connected regions of exactly equal value and they
     * can be enormous in area, so an area threshold alone masks a hillside as a lake.
     * A unit test over ground falling 0.4 m per cell masked all of it.
     *
     * <p>Water bodies are blobs; contour terraces are ribbons. So require the region to
     * be at least five cells across somewhere — that is, to contain a cell whose whole
     * 5 x 5 neighbourhood is also in the region. A terrace a few cells wide fails it
     * however long it runs, and anything narrower than five cells is below the
     * resolution at which calling something a lake means much anyway.
     */
    private static boolean isWideEnough(int[] id, int w, int h, int[] region, int n,
            int mine) {
        for (int k = 0; k < n; k++) {
            final int i = region[k];
            final int x = i % w, y = i / w;
            if (x < 2 || y < 2 || x > w - 3 || y > h - 3)
                continue;
            boolean solid = true;
            for (int dy = -2; dy <= 2 && solid; dy++) {
                for (int dx = -2; dx <= 2; dx++) {
                    if (id[(y + dy) * w + (x + dx)] != mine) {
                        solid = false;
                        break;
                    }
                }
            }
            if (solid)
                return true;
        }
        return false;
    }

    /**
     * The window radius in whole cells: the odd cell count whose span is closest to
     * the requested window.
     *
     * <p>A window is always an odd number of cells so it is centred on the cell it
     * classifies, which means the only spans available are one cell, three cells, five
     * and so on. Picking the nearest of those matters more than it looks. Forcing a
     * minimum of one cell <em>radius</em> — a three cell window — is what the first
     * version did, and over an area large enough to push cells out to 30 m it turned a
     * one chain window into 93 m: every cell within ninety metres of anything steep
     * came out steep, and dissected country painted almost solid red. That is the safe
     * direction to be wrong in, but it is not the standard, and an overlay that
     * over-reports is one an operator learns to discount.
     *
     * <p>So a radius of zero is allowed. When the cells are already as wide as the
     * working window, the cell <em>is</em> the window, and widening it would claim a
     * neighbourhood the elevation data cannot resolve in the first place.
     *
     * <p>Ties go to the larger window. Over-reporting steep ground is the direction to
     * err in for something that says where a machine can work.
     */
    static int windowRadiusCells(double windowM, double cellM) {
        if (!(cellM > 0d) || !(windowM > 0d))
            return 0;

        // span(r) = (2r + 1) * cellM, which only grows with r. Find the first span
        // that reaches the window, then take whichever of it and the one below is
        // closer.
        int hi = 0;
        while ((2 * hi + 1) * cellM < windowM && hi < 4096)
            hi++;
        if (hi == 0)
            return 0;

        final double spanHi = (2 * hi + 1) * cellM;
        final double spanLo = (2 * (hi - 1) + 1) * cellM;
        return (windowM - spanLo) >= (spanHi - windowM) ? hi : hi - 1;
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
     * <p>Unknown cells and water both come out fully transparent so the map shows
     * through. The pane is what tells the operator how much of the area each was, and
     * keeps them apart; a transparent hole on its own reads as "nothing steep here",
     * which is the opposite of the truth.
     */
    public int[] toArgb(DozerStandard standard) {
        final int[] argb = new int[percent.length];
        for (int i = 0; i < percent.length; i++)
            argb[i] = water[i] ? 0 : standard.colorFor(percent[i]);
        return argb;
    }
}
