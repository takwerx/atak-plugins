package com.atakmap.android.signaldf.data;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** Angle wrapping and the lenient scalar parsing the feeds rely on. */
public class ScalarsTest {

    private static final double EPS = 1.0e-9;

    @Test
    public void norm360Wraps() {
        assertEquals(10.0, Angles.norm360(370.0), EPS);
        assertEquals(350.0, Angles.norm360(-10.0), EPS);
        assertEquals(0.0, Angles.norm360(360.0), EPS);
        assertEquals(1.0, Angles.norm360(721.0), EPS);
    }

    /** -0.0 formats as "-0" and a bearing of minus zero reads as a bug. */
    @Test
    public void norm360HasNoNegativeZero() {
        assertEquals("0.0", String.valueOf(Angles.norm360(-0.0)));
        assertEquals("0.0", String.valueOf(Angles.norm360(-360.0)));
    }

    @Test
    public void diff180TakesTheShortWay() {
        assertEquals(20.0, Angles.diff180(350.0, 10.0), EPS);
        assertEquals(-20.0, Angles.diff180(10.0, 350.0), EPS);
        assertEquals(180.0, Angles.diff180(0.0, 180.0), EPS);
        assertEquals(-179.0, Angles.diff180(0.0, 181.0), EPS);
    }

    @Test
    public void doublesFallBackRatherThanThrow() {
        assertEquals(1.5, Numbers.d("1.5", -1), EPS);
        assertEquals(1.5, Numbers.d("  1.5 ", -1), EPS);
        assertEquals(-1.0, Numbers.d("", -1), EPS);
        assertEquals(-1.0, Numbers.d(null, -1), EPS);
        assertEquals(-1.0, Numbers.d("nan-ish", -1), EPS);
    }

    /** The radio writes some integers as "3.0"; the WebSocket str()-formats them. */
    @Test
    public void integersTolerateADecimalPoint() {
        assertEquals(3, Numbers.i("3", -1));
        assertEquals(3, Numbers.i("3.0", -1));
        assertEquals(-1, Numbers.i("", -1));
    }

    @Test
    public void booleansCoverPythonsStrTrue() {
        assertTrue(Numbers.bool("True", false));
        assertTrue(Numbers.bool("1", false));
        assertFalse(Numbers.bool("False", true));
        assertFalse(Numbers.bool("0", true));
        assertTrue("an unreadable flag keeps the caller's default",
                Numbers.bool("?", true));
    }

    /**
     * Frame time is milliseconds and GPS time is seconds, neither labelled.
     * 1e11 ms is 1973 and 1e11 s is the year 5138, so the split is unambiguous
     * for any timestamp this decade.
     */
    @Test
    public void timestampsAreNormalizedToMilliseconds() {
        assertEquals(1_757_894_400_000L, Numbers.epochMs("1757894400000"));
        assertEquals(1_757_894_400_000L, Numbers.epochMs("1757894400"));
        assertEquals(1_757_894_400_500L, Numbers.epochMs("1757894400.5"));
    }

    /** No time is 0, and 0 never means 1970. */
    @Test
    public void anAbsentTimestampIsZero() {
        assertEquals(0L, Numbers.epochMs(null));
        assertEquals(0L, Numbers.epochMs(""));
        assertEquals(0L, Numbers.epochMs("0"));
        assertEquals(0L, Numbers.epochMs("-5"));
    }
}
