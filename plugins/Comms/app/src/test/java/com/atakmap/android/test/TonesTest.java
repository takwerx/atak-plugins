package com.atakmap.android.test;

import static org.junit.Assert.assertEquals;

import com.atakmap.android.comms.data.Tones;

import org.junit.Test;

/**
 * What a site's nets read like in the pane.
 *
 * <p>The plugin shows a tone and not a frequency on a site: the radio already knows
 * the net's pair, and what an operator needs off the map is which tone opens this
 * mountain. These are the four shapes the call plans actually produce.
 */
public class TonesTest {

    private static final double NONE = Double.NaN;

    @Test
    public void toneNumberComesFirst() {
        assertEquals("Tone 8 (103.5)", Tones.describe(103.5, ""));
        assertEquals("Tone 16 (192.8)", Tones.describe(192.8, ""));
    }

    @Test
    public void aToneOutsideTheStandardTableKeepsItsFrequency() {
        assertEquals("88.0", Tones.describe(88.0, ""));
    }

    @Test
    public void aForestRepeaterShowsTheSiteToneAlone() {
        // Angeles forest net at Frazier Mountain: dial Tone 8 to open it.
        assertEquals("Tone 8 (103.5)", Tones.line(103.5, "", NONE, ""));
    }

    @Test
    public void onlyTheAccessToneIsShown() {
        // A CDF command net site: Tone 3 opens this mountain, and the net's own
        // 103.5 is the same at every site on Command 1, so it is not shown.
        assertEquals("Tone 3 (131.8)", Tones.line(131.8, "", 103.5, ""));
    }

    @Test
    public void theNetsToneStandsInWhenTheSiteHasNone() {
        // VFIRE 21 is simplex on Tone 6; there is no per-site tone to prefer.
        assertEquals("Tone 6 (156.7)", Tones.line(NONE, "", 156.7, ""));
    }

    @Test
    public void anOperatorSelectedToneSaysSo() {
        // The plans' OST: the net fixes no tone, the site does, and we do not know it.
        assertEquals("site tone", Tones.line(NONE, "OST", NONE, ""));
    }

    @Test
    public void noToneAtAllSaysThatToo() {
        assertEquals("no tone", Tones.line(NONE, "", NONE, ""));
    }

    @Test
    public void aSearchMatchesEitherWayOfNamingATone() {
        assertEquals(true, Tones.matches(103.5, "103.5"));
        assertEquals(true, Tones.matches(103.5, "tone 8"));
        assertEquals(true, Tones.matches(103.5, "T8"));
        assertEquals(false, Tones.matches(103.5, "8"));       // a bare number is not a tone query
        assertEquals(false, Tones.matches(103.5, "123.0"));
    }
}
