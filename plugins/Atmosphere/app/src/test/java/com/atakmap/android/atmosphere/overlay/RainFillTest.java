package com.atakmap.android.atmosphere.overlay;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/**
 * Where the RFC 7-day picture is grey, the radar's three pictures (72 + 72 + 24 h)
 * are added up and painted in the RFC 7-day key (California, 2026-10-15).
 */
public class RainFillTest {

    private static final int CLEAR = 0;
    /** The mesonet's colors for 10 mm (index 40) and 25 mm (index 100), opaque. */
    private static final int MM10 = 0xFF00FEDE;
    private static final int MM25 = 0xFF8AFE00;
    /** Its "no data", index 255. */
    private static final int NO_DATA = 0xFF909090;

    @Test
    public void greyBecomesTheRadarSumInTheSevenDayKey() {
        final int g = RainTotalsOverlay.RFC_GREY;
        final int[] pixels = { g, g, g, g, 0xFF00A110 };
        final int[][] radar = {
                { MM25, MM10, CLEAR, MM10, MM25 },
                { MM25, CLEAR, CLEAR, NO_DATA, MM25 },
                { MM25, CLEAR, CLEAR, CLEAR, MM25 } };
        RainTotalsOverlay.fillGrey(pixels, radar);
        // 75 mm = 2.95 in: "2 to 3 in"
        assertEquals(0xFFFAFA00, pixels[0]);
        // 10 mm = 0.39 in: "0.25 to 0.5 in"
        assertEquals(0xFF001496, pixels[1]);
        // no rain in any picture: clear, as the RFC leaves dry ground
        assertEquals(CLEAR, pixels[2]);
        // the radar has no reading either: still grey
        assertEquals(g, pixels[3]);
        // the RFC's own color is never touched
        assertEquals(0xFF00A110, pixels[4]);
    }

    @Test
    public void greyIsCountedExactly() {
        final int g = RainTotalsOverlay.RFC_GREY;
        assertEquals(2, RainTotalsOverlay.greyCount(new int[] { g, 0xFF7D7D7E, g, 0 }));
    }
}
