package com.atakmap.android.atmosphere;

import static org.junit.Assert.assertEquals;

import com.atakmap.android.atmosphere.data.MarineBand;

import org.junit.Test;

public class MarineBandTest {

    @Test
    public void windPlacesTheBand() {
        assertEquals(MarineBand.NONE, MarineBand.of(19.9, 3));
        assertEquals(MarineBand.SMALL_CRAFT, MarineBand.of(20, Double.NaN));
        assertEquals(MarineBand.SMALL_CRAFT, MarineBand.of(33.9, 0));
        assertEquals(MarineBand.GALE, MarineBand.of(34, 0));
        assertEquals(MarineBand.GALE, MarineBand.of(47.9, 0));
        assertEquals(MarineBand.STORM, MarineBand.of(48, 0));
        assertEquals(MarineBand.HURRICANE, MarineBand.of(64, 0));
        assertEquals(MarineBand.NONE, MarineBand.of(Double.NaN, Double.NaN));
    }

    @Test
    public void seasMeetSmallCraftOnTheirOwn() {
        assertEquals(MarineBand.SMALL_CRAFT, MarineBand.of(Double.NaN, 10));
        assertEquals(MarineBand.SMALL_CRAFT, MarineBand.of(5, 12));
        assertEquals(MarineBand.NONE, MarineBand.of(5, 9.9));
        assertEquals(MarineBand.GALE, MarineBand.of(40, 12));   // wind outranks seas
    }

    @Test
    public void describesItself() {
        assertEquals("Gale force: 38 kt (34-47 kt)", MarineBand.GALE.describe(38, 6));
        assertEquals("Small craft: seas 12 ft (10 ft or more)", MarineBand.SMALL_CRAFT.describe(8, 12));
        assertEquals("Small craft: 24 kt (20-33 kt)", MarineBand.SMALL_CRAFT.describe(24, 12));
        assertEquals("Below small craft criteria (20 kt, seas 10 ft)", MarineBand.NONE.describe(8, 3));
        assertEquals("64 kt and up", MarineBand.HURRICANE.range());
    }
}
