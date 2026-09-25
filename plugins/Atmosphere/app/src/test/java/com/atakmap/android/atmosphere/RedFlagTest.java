
package com.atakmap.android.atmosphere;

import static org.junit.Assert.assertEquals;

import com.atakmap.android.atmosphere.data.RedFlag;

import org.junit.Test;

public class RedFlagTest {

    @Test
    public void dryAndWindyTogetherIsCritical() {
        assertEquals(RedFlag.CRITICAL, RedFlag.state(12, 30));
        // Exactly on both thresholds counts: the criteria are "at or beyond".
        assertEquals(RedFlag.CRITICAL, RedFlag.state(15, 25));
    }

    @Test
    public void oneOfThemAloneIsWorthWatchingButIsNotRedFlag() {
        assertEquals(RedFlag.NEAR, RedFlag.state(10, 5));
        assertEquals(RedFlag.NEAR, RedFlag.state(60, 35));
    }

    @Test
    public void bothWithinReachIsWorthWatching() {
        assertEquals(RedFlag.NEAR, RedFlag.state(18, 22));
        // Near on one and nowhere on the other is not.
        assertEquals(RedFlag.BELOW, RedFlag.state(18, 4));
        assertEquals(RedFlag.BELOW, RedFlag.state(55, 21));
    }

    @Test
    public void aCalmDampStationIsBelow() {
        assertEquals(RedFlag.BELOW, RedFlag.state(62, 5));
        assertEquals(RedFlag.BELOW, RedFlag.state(100, 0));
    }

    @Test
    public void aMissingValueNeverMakesAStationCritical() {
        // The danger is a station that sent no humidity reading being colored red on
        // its wind alone -- a red diamond with nothing behind it.
        assertEquals(RedFlag.NEAR, RedFlag.state(Double.NaN, 40));
        assertEquals(RedFlag.NEAR, RedFlag.state(8, Double.NaN));
        assertEquals(RedFlag.BELOW, RedFlag.state(Double.NaN, Double.NaN));
        assertEquals(RedFlag.BELOW, RedFlag.state(Double.NaN, 10));
        assertEquals(RedFlag.BELOW, RedFlag.state(40, Double.NaN));
    }

    @Test
    public void theBasisSaysWhoseCriteriaTheseAreNot() {
        final String s = RedFlag.basis();
        assertEquals("says the humidity threshold", true, s.contains("15%"));
        assertEquals("says the wind threshold", true, s.contains("25 mph"));
        // It must not read as though it were the local office's own numbers.
        assertEquals("admits these are not the zone's", true, s.contains("own criteria"));
    }
}
