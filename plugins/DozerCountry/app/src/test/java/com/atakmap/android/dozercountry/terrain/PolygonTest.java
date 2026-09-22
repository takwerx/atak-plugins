package com.atakmap.android.dozercountry.terrain;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * The ring test decides which ground gets painted, so it is checked without a device.
 */
public class PolygonTest {

    /** A square, to get the obvious cases nailed down first. */
    private static Polygon square() {
        return new Polygon(new double[] { 0, 10, 10, 0 }, new double[] { 0, 0, 10, 10 });
    }

    @Test
    public void insideAndOutsideASquare() {
        final Polygon p = square();
        assertTrue(p.contains(5, 5));
        assertTrue(p.contains(0.1, 0.1));
        assertFalse(p.contains(-1, 5));
        assertFalse(p.contains(11, 5));
        assertFalse(p.contains(5, -1));
        assertFalse(p.contains(5, 11));
    }

    /**
     * A concave shape is the whole reason for using a ring rather than a box: the
     * notch must not be painted, and a bounding box would paint it.
     */
    @Test
    public void theNotchOfAConcaveShapeIsOutside() {
        // A "C" opening to the right.
        final Polygon c = new Polygon(
                new double[] { 0, 10, 10, 4, 4, 10, 10, 0 },
                new double[] { 0, 0, 3, 3, 7, 7, 10, 10 });
        assertTrue("the solid left side is inside", c.contains(2, 5));
        assertFalse("the notch is outside", c.contains(8, 5));
        assertTrue("the arms are inside", c.contains(8, 1.5));
        assertTrue(c.contains(8, 8.5));
    }

    /**
     * A point level with a vertex must be counted once, not twice. Getting this wrong
     * shows up as single stray cells along a horizontal edge, which looks like noise
     * in the data rather than a bug in the test.
     */
    @Test
    public void aPointLevelWithAVertexIsNotCountedTwice() {
        // A triangle whose apex sits at y = 5.
        final Polygon tri = new Polygon(
                new double[] { 0, 10, 5 }, new double[] { 0, 0, 5 });
        assertFalse(tri.contains(-1, 5));
        assertFalse(tri.contains(11, 5));
        assertTrue(tri.contains(5, 1));
    }

    /** Degenerate rings enclose everything rather than nothing — see the javadoc. */
    @Test
    public void aRingWithFewerThanThreeVerticesEnclosesEverything() {
        final Polygon two = new Polygon(new double[] { 0, 1 }, new double[] { 0, 1 });
        assertTrue(two.contains(-100, -100));
    }

    /** Longitude and latitude, in that order, and not swapped. */
    @Test
    public void takesLonLatNotLatLon() {
        // A tall thin ring: wide in latitude, narrow in longitude.
        final Polygon p = new Polygon(
                new double[] { -120, -119.9, -119.9, -120 },
                new double[] { 34, 34, 38, 38 });
        assertTrue(p.contains(-119.95, 36));
        assertFalse("swapping the arguments would call this inside",
                p.contains(36, -119.95));
    }
}
