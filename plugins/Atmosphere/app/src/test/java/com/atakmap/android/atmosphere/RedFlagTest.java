
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
    public void oneOfThemAloneIsNotFlirtingWithAnything() {
        // The criteria are a pair, so approaching one of them is not approaching
        // them. A station at 87% humidity gusting 30 was drawn yellow by a rule that
        // accepted either on its own -- soaking wet, and the symbol said it was close
        // to Red Flag (found on the map, 2026-09-25).
        assertEquals(RedFlag.BELOW, RedFlag.state(87, 30));
        assertEquals(RedFlag.BELOW, RedFlag.state(10, 5));
        assertEquals(RedFlag.BELOW, RedFlag.state(60, 35));
        // Even one of them fully met, with the other nowhere.
        assertEquals(RedFlag.BELOW, RedFlag.state(12, 3));
        assertEquals(RedFlag.BELOW, RedFlag.state(70, 40));
    }

    @Test
    public void approachingBothIsFlirting() {
        assertEquals(RedFlag.NEAR, RedFlag.state(18, 22));
        assertEquals(RedFlag.NEAR, RedFlag.state(20, 20));
        // One fully met and the other within reach is still flirting.
        assertEquals(RedFlag.NEAR, RedFlag.state(12, 21));
        assertEquals(RedFlag.NEAR, RedFlag.state(19, 30));
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
        // And a missing value cannot be "approaching" either, so it is not yellow.
        assertEquals(RedFlag.BELOW, RedFlag.state(Double.NaN, 40));
        assertEquals(RedFlag.BELOW, RedFlag.state(8, Double.NaN));
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
