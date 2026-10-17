package com.atakmap.android.atmosphere;

import static org.junit.Assert.assertTrue;

import com.atakmap.android.atmosphere.overlay.RainGaugesOverlay;

import org.junit.Test;

/**
 * The Rain Gauges key is parsed with Color.parseColor on the pane, which throws on
 * anything but "#RRGGBB" or "#AARRGGBB" and took ATAK down on 2026-10-12 when a
 * text-only line carried "0". Every line's color has to parse.
 */
public class LegendColorsTest {

    @Test
    public void rainGaugesLegendColorsParse() {
        for (String[] row : RainGaugesOverlay.LEGEND)
            assertTrue(row[0] + " -> " + row[1], row[1].matches("#[0-9A-Fa-f]{6}([0-9A-Fa-f]{2})?"));
    }
}
