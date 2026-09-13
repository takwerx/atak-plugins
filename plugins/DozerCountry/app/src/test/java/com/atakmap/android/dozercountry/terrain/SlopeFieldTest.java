package com.atakmap.android.dozercountry.terrain;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * The slope arithmetic, checked without a device.
 *
 * <p>Worth having as unit tests rather than only on hardware: this is the code that
 * decides what colour a piece of ground is, the inputs are plain numbers, and the
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

        final SlopeField f = SlopeField.compute(z, w, h, 10d, 10d, 20d);
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
        final SlopeField f = SlopeField.compute(z, w, h, 10d, 10d, 20d);
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

        final SlopeField f = SlopeField.compute(z, w, h, 10d, 10d, 20d);
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

        final SlopeField f = SlopeField.compute(z, w, h, 10d, 10d, 20d);
        assertTrue("window should span more than one cell here",
                f.windowMeters > f.cellMeters);
        // The flattened cell must still report the steep ground around it.
        assertTrue("flat step inside steep ground read as " + f.percent[1 * w + 4],
                f.percent[1 * w + 4] > 25d);
    }
}
