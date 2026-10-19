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
        assertEquals("Tone 8 (103.5)", Tones.describe(103.5, 8, ""));
        assertEquals("Tone 16 (192.8)", Tones.describe(192.8, 16, ""));
    }

    @Test
    public void aToneTheCatalogDoesNotNumberKeepsItsFrequency() {
        assertEquals("88.0", Tones.describe(88.0, 0, ""));
    }

    @Test
    public void theNumberIsThePlansNotTheFrequencys() {
        // Boise's interagency plan calls 100.0 Hz Tone 11, and its Tone 9 is 179.9.
        // California's table would say Tone 9, a real tone on a real Idaho site.
        assertEquals("Tone 11 (100.0)", Tones.describe(100.0, 11, ""));
        // A plan the catalog holds no numbers for gets the frequency and nothing else.
        assertEquals("100.0", Tones.describe(100.0, 0, ""));
        assertEquals("100.0", Tones.line(100.0, 0, "", NONE, 0, ""));
    }

    @Test
    public void californiaNumbersAreTheStandard32() {
        assertEquals(9, Tones.californiaNumberOf(100.0));
        assertEquals(8, Tones.californiaNumberOf(103.5));
        assertEquals(0, Tones.californiaNumberOf(88.0));
        assertEquals(0, Tones.californiaNumberOf(NONE));
    }

    @Test
    public void aForestRepeaterShowsTheSiteToneAlone() {
        // Angeles forest net at Frazier Mountain: dial Tone 8 to open it.
        assertEquals("Tone 8 (103.5)", Tones.line(103.5, 8, "", NONE, 0, ""));
    }

    @Test
    public void onlyTheAccessToneIsShown() {
        // A CDF command net site: Tone 3 opens this mountain, and the net's own
        // 103.5 is the same at every site on Command 1, so it is not shown.
        assertEquals("Tone 3 (131.8)", Tones.line(131.8, 3, "", 103.5, 8, ""));
    }

    @Test
    public void theNetsToneStandsInWhenTheSiteHasNone() {
        // VFIRE 21 is simplex on Tone 6; there is no per-site tone to prefer.
        assertEquals("Tone 6 (156.7)", Tones.line(NONE, 0, "", 156.7, 6, ""));
    }

    @Test
    public void anOperatorSelectedToneSaysSo() {
        // The plans' OST: the net fixes no tone, the site does, and we do not know it.
        assertEquals("tone not listed", Tones.line(NONE, 0, "OST", NONE, 0, ""));
    }

    @Test
    public void noToneAtAllSaysThatToo() {
        assertEquals("tone not listed", Tones.line(NONE, 0, "", NONE, 0, ""));
    }

    @Test
    public void aSearchMatchesEitherWayOfNamingATone() {
        assertEquals(true, Tones.matches(103.5, 8, "103.5"));
        assertEquals(true, Tones.matches(103.5, 8, "tone 8"));
        assertEquals(true, Tones.matches(103.5, 8, "T8"));
        assertEquals(false, Tones.matches(103.5, 8, "8"));       // a bare number is not a tone query
        assertEquals(false, Tones.matches(103.5, 8, "123.0"));
    }

    @Test
    public void aSearchByNumberUsesThePlansNumber() {
        // 100.0 on a Boise site is Tone 11 there; "tone 9" means 179.9 and must
        // not find it.
        assertEquals(true, Tones.matches(100.0, 11, "tone 11"));
        assertEquals(false, Tones.matches(100.0, 11, "tone 9"));
        assertEquals(false, Tones.matches(100.0, 0, "tone 9"));
        assertEquals(true, Tones.matches(100.0, 0, "100.0"));
    }
}
