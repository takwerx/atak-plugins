package com.atakmap.android.dozercountry.terrain;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.atakmap.android.dozercountry.model.DozerStandard;
import com.atakmap.android.dozercountry.model.SlopeBand;

import org.junit.Test;

/**
 * The slope arithmetic, checked without a device.
 *
 * <p>Worth having as unit tests rather than only on hardware: this is the code that
 * decides what color a piece of ground is, the inputs are plain numbers, and the
 * window rounding below was wrong in the first build in a way that painted dissected
 * country almost solid red on the phone and looked plausible while doing it.
 */
public class SlopeFieldTest {

    private static double span(double windowM, double cellM) {
        return (2 * SlopeField.windowRadiusCells(windowM, cellM) + 1) * cellM;
    }

    /**
     * A window is always an odd number of cells, so only certain spans exist. Pick the
     * one nearest what was asked for.
     */
    @Test
    public void windowTakesTheNearestAchievableSpan() {
        // 5 m cells: spans are 5, 15, 25, 35 ... 25 is nearest 20 (tie with 15, and
        // ties go to the larger window).
        assertEquals(25d, span(20d, 5d), 1e-9);
        // 1 m cells: spans are 1, 3, 5 ... 21 is nearest 20.
        assertEquals(21d, span(20d, 1d), 1e-9);
        // 2 m cells: spans are 2, 6, 10, 14, 18, 22 ... 18 and 22 tie; take 22.
        assertEquals(22d, span(20d, 2d), 1e-9);
    }

    /**
     * The regression that mattered. Over an area large enough to push cells out to
     * 30 m, forcing a minimum radius of one cell turned a one chain window into 93 m:
     * every cell within ninety metres of anything steep came out steep. A cell already
     * wider than the window must be its own window.
     */
    @Test
    public void aCellWiderThanTheWindowIsItsOwnWindow() {
        assertEquals(0, SlopeField.windowRadiusCells(20d, 31d));
        assertEquals(31d, span(20d, 31d), 1e-9);

        assertEquals(0, SlopeField.windowRadiusCells(20d, 19d));
        assertEquals(19d, span(20d, 19d), 1e-9);

        assertEquals(0, SlopeField.windowRadiusCells(20d, 60d));
    }

    /** Never a negative or absurd radius, whatever it is handed. */
    @Test
    public void windowIsSaneForDegenerateInput() {
        assertEquals(0, SlopeField.windowRadiusCells(0d, 10d));
        assertEquals(0, SlopeField.windowRadiusCells(20d, 0d));
        assertEquals(0, SlopeField.windowRadiusCells(-5d, 10d));
        assertEquals(0, SlopeField.windowRadiusCells(Double.NaN, 10d));
        assertEquals(0, SlopeField.windowRadiusCells(20d, Double.NaN));
    }

    /** A constant slope comes back as that slope, in percent. */
    @Test
    public void planeOfKnownGradeReadsBackInPercent() {
        // 10 m cells, rising 5 m per cell east: a 50% grade.
        final int w = 8, h = 8;
        final double[] z = new double[w * h];
        for (int y = 0; y < h; y++)
            for (int x = 0; x < w; x++)
                z[y * w + x] = x * 5d;

        final SlopeField f = SlopeField.compute(z, w, h, 10d, 10d, 20d, 0d);
        assertEquals(0, f.unknownCells);
        // Interior cells only; the edges use a one-sided difference.
        for (int y = 1; y < h - 1; y++)
            for (int x = 1; x < w - 1; x++)
                assertEquals(50d, f.percent[y * w + x], 1e-6);
    }

    /** Flat ground is flat, not NaN and not noise. */
    @Test
    public void flatGroundIsZero() {
        final int w = 5, h = 5;
        final double[] z = new double[w * h];
        final SlopeField f = SlopeField.compute(z, w, h, 10d, 10d, 20d, 0d);
        for (double v : f.percent)
            assertEquals(0d, v, 1e-9);
    }

    /**
     * A hole in the elevation must not read as flat ground. Green where there is no
     * data is the one failure this overlay cannot have.
     */
    @Test
    public void missingElevationStaysUnknownAndNeverReadsFlat() {
        final int w = 5, h = 5;
        final double[] z = new double[w * h];
        for (int i = 0; i < z.length; i++)
            z[i] = Double.NaN;

        final SlopeField f = SlopeField.compute(z, w, h, 10d, 10d, 20d, 0d);
        assertEquals(w * h, f.unknownCells);
        assertTrue(f.isEmpty());
        for (double v : f.percent)
            assertTrue("unknown ground must stay NaN, was " + v, Double.isNaN(v));
    }

    /**
     * The whole point of the window: a flat cell sitting inside steep ground is not
     * workable ground, and must not come back as class 1.
     */
    @Test
    public void aFlatCellInsideSteepGroundTakesTheSteepValue() {
        // 10 m cells so the window is 3 cells across; a steep east-west ramp with one
        // artificially flat step in the middle of it.
        final int w = 9, h = 3;
        final double[] z = new double[w * h];
        for (int y = 0; y < h; y++)
            for (int x = 0; x < w; x++)
                z[y * w + x] = x * 8d;      // 80% grade
        // flatten one column's neighbourhood so the raw slope there is low
        for (int y = 0; y < h; y++)
            z[y * w + 4] = z[y * w + 3];

        final SlopeField f = SlopeField.compute(z, w, h, 10d, 10d, 20d, 0d);
        assertTrue("window should span more than one cell here",
                f.windowMeters > f.cellMeters);
        // The flattened cell must still report the steep ground around it.
        assertTrue("flat step inside steep ground read as " + f.percent[1 * w + 4],
                f.percent[1 * w + 4] > 25d);
    }

    /* ----- the water mask ----- */

    private static int countWater(boolean[] w) {
        int n = 0;
        for (boolean b : w)
            if (b) n++;
        return n;
    }

    /**
     * A lake: a big patch of exactly one value inside terrain that varies. It is found
     * at its own level, not at sea level, which is the whole reason the test uses 300 m.
     */
    @Test
    public void aFlatPatchAtAnyLevelIsWater() {
        final int w = 40, h = 40;
        final double[] z = new double[w * h];
        for (int y = 0; y < h; y++)
            for (int x = 0; x < w; x++)
                z[y * w + x] = 300 + x * 3 + y * 2;   // varying ground
        for (int y = 10; y < 30; y++)
            for (int x = 10; x < 30; x++)
                z[y * w + x] = 300d;                  // a lake, dead flat, 400 cells

        // 10 m cells = 100 m2 each; ask for anything over 1000 m2.
        final boolean[] water = SlopeField.findWater(z, w, h, 100d, 1000d);
        assertEquals(400, countWater(water));
        assertTrue(water[20 * w + 20]);
        assertTrue("ground must not be masked", !water[2 * w + 2]);
    }

    /**
     * The case that matters most: gently sloping farmland is NOT water. Low slope would
     * swallow it; equality does not, because the values still change.
     */
    @Test
    public void gentlySlopingGroundIsNotWater() {
        final int w = 40, h = 40;
        final double[] z = new double[w * h];
        for (int y = 0; y < h; y++)
            for (int x = 0; x < w; x++)
                z[y * w + x] = x * 0.4d;   // about a 4% grade, very flat farmland

        final boolean[] water = SlopeField.findWater(z, w, h, 100d, 1000d);
        assertEquals(0, countWater(water));
    }

    /** A puddle smaller than the threshold is left alone. */
    @Test
    public void aPatchBelowTheAreaThresholdIsNotWater() {
        final int w = 30, h = 30;
        final double[] z = new double[w * h];
        for (int y = 0; y < h; y++)
            for (int x = 0; x < w; x++)
                z[y * w + x] = 100 + x + y;
        for (int y = 5; y < 7; y++)
            for (int x = 5; x < 7; x++)
                z[y * w + x] = 100d;       // 4 cells = 400 m2

        assertEquals(0, countWater(SlopeField.findWater(z, w, h, 100d, 1000d)));
    }

    /** Threshold of zero turns the mask off entirely, as the asset documents. */
    @Test
    public void zeroThresholdDisablesTheMask() {
        final int w = 20, h = 20;
        final double[] z = new double[w * h];   // entirely flat: maximally water-like
        assertEquals(0, countWater(SlopeField.findWater(z, w, h, 100d, 0d)));
    }

    /** Water is left unpainted, and counted apart from missing data. */
    @Test
    public void waterIsTransparentAndCountedSeparately() {
        final int w = 20, h = 20;
        final double[] z = new double[w * h];   // all one level -> all water
        final SlopeField f = SlopeField.compute(z, w, h, 10d, 10d, 20d, 1000d);
        assertEquals(w * h, f.waterCells);
        assertEquals(0, f.unknownCells);
        assertTrue(f.isNothingToClass());
        assertTrue("all water is not the same as no elevation data", !f.isEmpty());
    }

    /** A hole in the DTED is not water: NaN has no level to be flat at. */
    @Test
    public void missingDataIsNotMistakenForWater() {
        final int w = 20, h = 20;
        final double[] z = new double[w * h];
        for (int i = 0; i < z.length; i++)
            z[i] = Double.NaN;
        assertEquals(0, countWater(SlopeField.findWater(z, w, h, 100d, 1000d)));
    }

    /**
     * The failure this nearly shipped with. DTED is whole metres, so a smooth gentle
     * hillside comes back as terraces: long bands of constant elevation following the
     * contours. They are huge in area and would mask a mountainside as a lake. A band
     * three cells wide is a ribbon, not a body of water, however long it runs.
     */
    @Test
    public void aLongThinContourTerraceIsNotWater() {
        final int w = 60, h = 60;
        final double[] z = new double[w * h];
        for (int y = 0; y < h; y++)
            for (int x = 0; x < w; x++)
                z[y * w + x] = 500 + y;           // one metre per row
        // a terrace: three rows all at exactly 520, running the full width
        for (int y = 20; y < 23; y++)
            for (int x = 0; x < w; x++)
                z[y * w + x] = 520d;

        // 180 cells at 100 m2 = 18,000 m2 - far over the area threshold
        final boolean[] water = SlopeField.findWater(z, w, h, 100d, 1000d);
        assertEquals("a 3-cell-wide terrace is a ribbon, not a lake",
                0, countWater(water));
    }

    /** But widen the same band into a blob and it is water again. */
    @Test
    public void thickeningTheSameRegionMakesItWater() {
        final int w = 60, h = 60;
        final double[] z = new double[w * h];
        for (int y = 0; y < h; y++)
            for (int x = 0; x < w; x++)
                z[y * w + x] = 500 + y;
        for (int y = 20; y < 32; y++)
            for (int x = 10; x < 40; x++)
                z[y * w + x] = 520d;             // 12 x 30, thick enough

        assertTrue(countWater(SlopeField.findWater(z, w, h, 100d, 1000d)) > 300);
    }

    /* ----- masking to the ring the operator drew ----- */

    /**
     * The paint must stop where the line is drawn. The sampler works a north-up
     * rectangle because a lat/lon grid is one, so without this the color would fill
     * the bounding box and claim ground nobody asked about.
     */
    @Test
    public void onlyCellsInsideTheRingArePainted() {
        final int w = 20, h = 20;
        final double[] z = new double[w * h];
        for (int y = 0; y < h; y++)
            for (int x = 0; x < w; x++)
                z[y * w + x] = x * 4d;                // a 40% grade, all of it class 1..2

        // Grid spans lon 0..19, lat 19..0 (row 0 is north), one degree per cell.
        // The ring runs BETWEEN cell centres on purpose: ray casting is half-open, so
        // a centre sitting exactly on an edge is deliberately excluded, and a test
        // that put the edge through the centres would be measuring that tie-break
        // rather than the masking. Encloses lon 6..13 and lat 6..13, so 8 x 8.
        final Polygon ring = new Polygon(
                new double[] { 5.5, 13.5, 13.5, 5.5 },
                new double[] { 5.5, 5.5, 13.5, 13.5 });

        final SlopeField f = SlopeField.compute(z, w, h, 10d, 10d, 20d, 0d,
                ring, /*north*/ 19d, /*west*/ 0d, /*latStep*/ 1d, /*lonStep*/ 1d);

        assertEquals(8 * 8, f.areaCells);
        assertTrue("a cell in the middle is inside", f.inArea[10 * w + 10]);
        assertTrue("a corner cell is outside", !f.inArea[0]);

        final DozerStandard std = standard();
        final int[] argb = f.toArgb(std);
        assertEquals("outside the ring must be fully transparent", 0, argb[0]);
        assertTrue("inside the ring must be painted", (argb[10 * w + 10] >>> 24) != 0);
    }

    /** Counts reported to the operator are out of the ring, not the box around it. */
    @Test
    public void unknownCellsOutsideTheRingAreNotCounted() {
        final int w = 20, h = 20;
        final double[] z = new double[w * h];
        for (int i = 0; i < z.length; i++)
            z[i] = Double.NaN;                        // no elevation anywhere

        final Polygon ring = new Polygon(
                new double[] { 5.5, 13.5, 13.5, 5.5 },
                new double[] { 5.5, 5.5, 13.5, 13.5 });
        final SlopeField f = SlopeField.compute(z, w, h, 10d, 10d, 20d, 0d,
                ring, 19d, 0d, 1d, 1d);

        assertEquals("only the ring's own cells count as missing", 8 * 8, f.unknownCells);
        assertTrue("all of the drawn area is unresolved", f.isEmpty());
    }

    /** A minimal standard, so the paint test does not depend on the shipped asset. */
    private static DozerStandard standard() {
        final java.util.List<SlopeBand> bands = new java.util.ArrayList<>();
        bands.add(new SlopeBand(1, 0, 45, "0-45%", "ok", 0x961E8C28));
        return new DozerStandard("t", "t", "t",
                new java.util.ArrayList<String>(), new java.util.ArrayList<String>(),
                bands, new SlopeBand(0, Double.NaN, Double.POSITIVE_INFINITY,
                        "over", "over", 0x96C81E1E),
                null, new java.util.ArrayList<DozerStandard.Machine>());
    }
}
