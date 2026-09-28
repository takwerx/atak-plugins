
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
    public void oneCriterionOnItsOwnIsFlirting() {
        // The operator's rule: yellow is one leg of Red Flag already being there.
        assertEquals(RedFlag.NEAR, RedFlag.state(10, 5));       // dry, no wind
        assertEquals(RedFlag.NEAR, RedFlag.state(60, 35));      // windy, damp
        // Which includes a soaking station that is gusting hard. It reads oddly and
        // it is correct: half of Red Flag, waiting on the other half.
        assertEquals(RedFlag.NEAR, RedFlag.state(87, 30));
    }

    @Test
    public void approachingIsNotMeeting() {
        // Close on both, but neither criterion actually met.
        assertEquals(RedFlag.BELOW, RedFlag.state(18, 22));
        assertEquals(RedFlag.BELOW, RedFlag.state(16, 24));
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
        // A missing value is not a met criterion, but the one that IS sent still counts.
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
