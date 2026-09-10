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
        // Angeles forest net at Frazier Mountain: transmit Tone 8, no receive tone.
        assertEquals("Tone 8 (103.5)", Tones.line(NONE, "", 103.5, ""));
    }

    @Test
    public void aCalFireCommandNetShowsBothSides() {
        // Every CDF command net receives on Tone 8 and transmits the site's own tone.
        assertEquals("TX Tone 3 (131.8) · RX Tone 8 (103.5)",
                Tones.line(103.5, "", 131.8, ""));
    }

    @Test
    public void oneToneBothWaysIsPrintedOnce() {
        // VFIRE 21, simplex, Tone 6 each way.
        assertEquals("Tone 6 (156.7)", Tones.line(156.7, "", 156.7, ""));
    }

    @Test
    public void anOperatorSelectedToneSaysSo() {
        // NIFC command nets: carrier squelch in, operator picks the tone out.
        assertEquals("site tone", Tones.line(NONE, "", NONE, "OST"));
        // A CDF command net with no site: tone protected in, operator selects out.
        assertEquals("RX Tone 8 (103.5) · TX site tone", Tones.line(103.5, "", NONE, "OST"));
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
