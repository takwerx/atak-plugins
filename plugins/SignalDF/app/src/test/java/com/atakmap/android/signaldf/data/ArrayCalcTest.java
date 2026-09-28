package com.atakmap.android.signaldf.data;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.atakmap.android.signaldf.data.ArrayCalc.Geometry;
import com.atakmap.android.signaldf.data.ArrayCalc.Result;

import org.junit.Test;

/**
 * Pinned against KrakenRF's own <i>Antenna Array Size Calculator</i> workbook.
 *
 * <p>Every expected value here was read out of that spreadsheet's cells, not
 * derived independently. That is the point: the operator is told what to build
 * by the vendor's tool, and if this plugin disagrees with it by even a
 * centimetre the two are giving contradictory instructions about a physical
 * thing somebody is about to bolt to a truck.
 */
public class ArrayCalcTest {

    /** The workbook prints six figures; match it well inside that. */
    private static final double EPS = 1e-6;

    // ---- circular array, sheet 1 -------------------------------------------

    /**
     * Sheet 1 at 416.0 MHz, 5 elements, spacing multiplier 0.5. The cells:
     * lambda .721154 m, spacing 36.0577 cm, unit radius .425325, radius
     * 30.6725 cm, resolution 8.21734 degrees.
     */
    @Test
    public void circularMatchesTheWorkbook() {
        double freq = 300.0 / 0.72115384615384615;
        Result r = ArrayCalc.sizeFor(Geometry.CIRCULAR, freq, 5, 0.5);
        assertEquals(0.72115384615384615, r.wavelengthM, EPS);
        assertEquals(36.057692307692307, r.spacingCm, 1e-5);
        assertEquals(0.42532540417601994, r.sizeWavelengths, EPS);
        assertEquals(30.672505108847592, r.sizeCm, 1e-5);
        assertEquals(8.2173378687994063, r.resolutionDeg, 1e-5);
    }

    /** The chord factor itself: sqrt(2(1-cos 72)) for a five-element circle. */
    @Test
    public void chordFactorForFiveElements() {
        assertEquals(1.1755705045849463, ArrayCalc.chordFactor(5), EPS);
    }

    /**
     * Sheet 1's reverse cell: a 25 cm radius with a 0.5 multiplier and 5
     * elements is 510.39049 MHz. Reached here by asking what multiplier that
     * radius gives at that frequency, which must come back to 0.5.
     */
    @Test
    public void circularReverseMatchesTheWorkbook() {
        Result r = ArrayCalc.atSize(Geometry.CIRCULAR, 510.39048501122397, 5, 25.0);
        assertEquals(0.5, r.multiplier, 1e-9);
        assertEquals(8.2173378687994063, r.resolutionDeg, 1e-5);
    }

    /** Sheet 1's coordinate block for a 10 cm radius, 5 elements. */
    @Test
    public void circularPositionsMatchTheWorkbook() {
        double[][] p = ArrayCalc.positions(Geometry.CIRCULAR, 5, 10.0);
        double[] x = { 10.0, 3.0901699437494745, -8.0901699437494727,
                -8.0901699437494763, 3.0901699437494723 };
        double[] y = { 0.0, -9.5105651629515346, -5.8778525229247327,
                5.87785252292473, 9.5105651629515364 };
        for (int i = 0; i < 5; i++) {
            assertEquals("x" + i, x[i], p[i][0], 1e-9);
            assertEquals("y" + i, y[i], p[i][1], 1e-9);
        }
    }

    /** Element 0 is forward, and the rest run clockwise, not counterclockwise. */
    @Test
    public void elementZeroIsForwardAndTheRestGoClockwise() {
        double[][] p = ArrayCalc.positions(Geometry.CIRCULAR, 5, 10.0);
        assertEquals(10.0, p[0][0], 1e-9);
        assertEquals(0.0, p[0][1], 1e-9);
        assertTrue("element 1 must be clockwise, i.e. negative y", p[1][1] < 0);
    }

    // ---- linear array, sheet 2 ---------------------------------------------

    /**
     * Sheet 2 at 440 MHz, 5 elements, multiplier 0.5: lambda .681818 m,
     * spacing 34.0909 cm, length 2 wavelengths, 136.3636 cm, resolution
     * 3.49504 degrees.
     */
    @Test
    public void linearMatchesTheWorkbook() {
        Result r = ArrayCalc.sizeFor(Geometry.LINEAR, 440.0, 5, 0.5);
        assertEquals(0.68181818181818177, r.wavelengthM, EPS);
        assertEquals(34.090909090909086, r.spacingCm, 1e-5);
        assertEquals(2.0, r.sizeWavelengths, EPS);
        assertEquals(136.36363636363635, r.sizeCm, 1e-5);
        assertEquals(3.4950425502980216, r.resolutionDeg, 1e-5);
    }

    /** Sheet 2's spacing-from-length cell: 132 cm over 5 elements is 33 cm. */
    @Test
    public void linearSpacingIsLengthOverTheGaps() {
        Result r = ArrayCalc.atSize(Geometry.LINEAR, 300.0, 5, 132.0);
        // 4 gaps, so 33 cm between neighbors whatever the frequency.
        assertEquals(33.0, r.sizeCm / 4, 1e-9);
    }

    /** A linear array spans its whole length; a circle only spans a diameter. */
    @Test
    public void linearResolvesBetterThanCircularOfTheSameSpacing() {
        Result circ = ArrayCalc.sizeFor(Geometry.CIRCULAR, 440.0, 5, 0.5);
        Result lin = ArrayCalc.sizeFor(Geometry.LINEAR, 440.0, 5, 0.5);
        assertTrue(lin.resolutionDeg < circ.resolutionDeg);
    }

    // ---- the two rules, in KrakenRF's words --------------------------------

    @Test
    public void spacingAtOrOverHalfAWavelengthIsAmbiguous() {
        assertTrue(ArrayCalc.ambiguityFree(0.499));
        assertFalse(ArrayCalc.ambiguityFree(0.5));
        assertFalse(ArrayCalc.ambiguityFree(0.6));
    }

    @Test
    public void resolutionIsAcceptableUpToTwentyFiveDegrees() {
        assertTrue(ArrayCalc.resolutionUsable(25.0));
        assertFalse(ArrayCalc.resolutionUsable(25.1));
    }

    /**
     * An array too big for the frequency aliases, and the problem text says
     * which way to move it rather than only naming the number.
     */
    @Test
    public void tooBigForTheFrequencyReportsAmbiguity() {
        // 30 cm radius at 900 MHz: spacing well over half a wavelength.
        Result r = ArrayCalc.atSize(Geometry.CIRCULAR, 900.0, 5, 30.0);
        assertFalse(r.usable());
        assertTrue(r.problem().contains("cannot tell some directions apart"));
        assertTrue(r.problem().contains("smaller"));
    }

    /** And one too small is not ambiguous, just too coarse to act on. */
    @Test
    public void tooSmallForTheFrequencyIsNotAmbiguousJustUseless() {
        // 3 cm radius at 50 MHz: unambiguous, and hopeless.
        Result r = ArrayCalc.atSize(Geometry.CIRCULAR, 50.0, 5, 3.0);
        assertTrue(ArrayCalc.ambiguityFree(r.multiplier));
        assertFalse(r.usable());
        assertTrue(r.problem().contains("bigger"));
    }

    @Test
    public void aWellSizedArrayHasNoProblemToReport() {
        Result r = ArrayCalc.sizeFor(Geometry.CIRCULAR, 416.0, 5, 0.45);
        assertTrue(r.usable());
        assertEquals("", r.problem());
    }

    // ---- KrakenRF's published template table --------------------------------

    /**
     * The strongest oracle available: the wiki states the band each template
     * hole covers, and those numbers must fall out of the formulas.
     *
     * <p>"Each hole is spaced at 50mm radius intervals. So you have radius
     * spacings of 100mm, 150mm, 200mm, and 250mm. These spacings cover the
     * following frequency range: 100mm : 510 - 1275 MHz, 150mm : 340 - 850 MHz,
     * 200mm : 255 - 637 MHz, 250mm : 204 - 510 MHz."
     *
     * <p>Reproducing both ends is what proved the band is bounded by the
     * multiplier at 0.2 and 0.5, not by the 25 degree resolution figure. The
     * first version of this class derived the bottom from resolution and put
     * the 250 mm hole at 152 MHz against their published 204.
     */
    @Test
    public void everyTemplateHoleMatchesTheWikisPublishedBand() {
        double[][] table = {
                // radius cm, low MHz, high MHz
                { 10.0, 510, 1275 },
                { 15.0, 340, 850 },
                { 20.0, 255, 637 },
                { 25.0, 204, 510 },
        };
        for (double[] row : table) {
            double radius = row[0];
            double lo = ArrayCalc.lowestUsableMHz(Geometry.CIRCULAR, 5, radius);
            double hi = ArrayCalc.highestUsableMHz(Geometry.CIRCULAR, 5, radius);
            // The wiki rounds to whole megahertz.
            assertEquals(radius + " cm low", row[1], lo, 1.0);
            assertEquals(radius + " cm high", row[2], hi, 1.0);
        }
    }

    /** The template's holes are 50 mm apart, four of them, starting at 100 mm. */
    @Test
    public void theTemplateOffersFourRadii() {
        assertEquals(4, ArrayCalc.TEMPLATE_RADII_CM.length);
        assertEquals(10.0, ArrayCalc.TEMPLATE_RADII_CM[0], 1e-9);
        for (int i = 1; i < ArrayCalc.TEMPLATE_RADII_CM.length; i++)
            assertEquals(5.0, ArrayCalc.TEMPLATE_RADII_CM[i]
                    - ArrayCalc.TEMPLATE_RADII_CM[i - 1], 1e-9);
    }

    /**
     * Which hole to use is answered from the published bands. 416 MHz sits in
     * the 150 mm and 200 mm bands; the one nearer KrakenRF's typical s=0.33 is
     * chosen rather than simply the biggest that fits.
     */
    @Test
    public void picksTheTemplateHoleNearestTheirTypicalSpacing() {
        double radius = ArrayCalc.templateRadiusCm(5, 416.588);
        assertTrue("a hole must fit at 416 MHz", radius > 0);
        Result r = ArrayCalc.atSize(Geometry.CIRCULAR, 416.588, 5, radius);
        assertTrue(r.usable());
        // Nothing else in the table is closer to 0.33 than the one chosen.
        for (double other : ArrayCalc.TEMPLATE_RADII_CM) {
            Result o = ArrayCalc.atSize(Geometry.CIRCULAR, 416.588, 5, other);
            if (!o.usable())
                continue;
            assertTrue(Math.abs(r.multiplier - ArrayCalc.TYPICAL_MULTIPLIER)
                    <= Math.abs(o.multiplier - ArrayCalc.TYPICAL_MULTIPLIER) + 1e-9);
        }
    }

    /** And outside every published band, it says so rather than guessing. */
    @Test
    public void noTemplateHoleWorksWayOutOfBand() {
        assertEquals(-1.0, ArrayCalc.templateRadiusCm(5, 50.0), 1e-9);
        assertEquals(-1.0, ArrayCalc.templateRadiusCm(5, 3000.0), 1e-9);
    }

    /** Spacing under 0.2 is KrakenRF's "too poor", and is not usable. */
    @Test
    public void tooLittleSpacingIsUnusableEvenThoughItCannotAlias() {
        Result r = ArrayCalc.atSize(Geometry.CIRCULAR, 250.0, 5, 10.0);
        assertTrue("it cannot alias", ArrayCalc.ambiguityFree(r.multiplier));
        assertTrue("but it is under the floor", r.multiplier < ArrayCalc.MIN_MULTIPLIER);
        assertFalse(r.usable());
        assertTrue(r.problem().contains("too rough to trust"));
    }

    // ---- the band a built array covers -------------------------------------

    /**
     * The top of the band is where spacing reaches half a wavelength, so
     * sizing an array for exactly 0.5 at a frequency puts that frequency at
     * the ceiling.
     */
    @Test
    public void highestUsableIsWhereSpacingReachesHalfAWavelength() {
        Result r = ArrayCalc.sizeFor(Geometry.CIRCULAR, 416.0, 5, 0.5);
        assertEquals(416.0, ArrayCalc.highestUsableMHz(Geometry.CIRCULAR, 5, r.sizeCm), 1e-6);
    }

    @Test
    public void highestUsableWorksForLinearToo() {
        Result r = ArrayCalc.sizeFor(Geometry.LINEAR, 440.0, 5, 0.5);
        assertEquals(440.0, ArrayCalc.highestUsableMHz(Geometry.LINEAR, 5, r.sizeCm), 1e-6);
    }

    /** At the bottom of the band the spacing is exactly KrakenRF's 0.2 floor. */
    @Test
    public void lowestUsableIsWhereSpacingReachesTheFloor() {
        double lo = ArrayCalc.lowestUsableMHz(Geometry.CIRCULAR, 5, 30.0);
        Result r = ArrayCalc.atSize(Geometry.CIRCULAR, lo, 5, 30.0);
        assertEquals(ArrayCalc.MIN_MULTIPLIER, r.multiplier, 1e-9);
    }

    /** And the band is a band: the ceiling is above the floor. */
    @Test
    public void theBandIsTheRightWayUp() {
        assertTrue(ArrayCalc.highestUsableMHz(Geometry.CIRCULAR, 5, 30.0)
                > ArrayCalc.lowestUsableMHz(Geometry.CIRCULAR, 5, 30.0));
    }

    // ---- reading the radio's own settings ----------------------------------

    @Test
    public void radioArrangementMapsOntoGeometry() {
        assertEquals(Geometry.CIRCULAR, Geometry.fromRadio("UCA"));
        assertEquals(Geometry.LINEAR, Geometry.fromRadio("ULA"));
        assertEquals(Geometry.CIRCULAR, Geometry.fromRadio("uca"));
        assertEquals(null, Geometry.fromRadio("something else"));
        assertEquals(null, Geometry.fromRadio(null));
    }

    /**
     * The operator's own radio as it was configured on 2026-09-27: UCA,
     * 0.21 m spacing, tuned to 416.588 MHz. This is the check the spreadsheet
     * cannot do, so it is worth having a test that it comes out sensible.
     */
    @Test
    public void theOperatorsOwnArrayAtItsOwnFrequency() {
        // The radio reports ant_spacing_meters as the RADIUS for a UCA.
        Result r = ArrayCalc.atSize(Geometry.CIRCULAR, 416.588, 5, 21.0);
        assertTrue("0.21 m at 416 MHz should not alias", ArrayCalc.ambiguityFree(r.multiplier));
        assertTrue("and should resolve usefully", ArrayCalc.resolutionUsable(r.resolutionDeg));
        assertTrue(r.usable());
    }

    // ---- antenna element length -------------------------------------------
    // The vendor's rule is a quarter wavelength; these pin the arithmetic at
    // frequencies where lambda is easy to check by hand.

    @Test
    public void quarterWaveIsLambdaOverFour() {
        // 300 MHz is exactly 1 m, so a quarter wave is exactly 25 cm.
        assertEquals(25.0, ArrayCalc.quarterWaveCm(300.0), 0.01);
        // 150 MHz is 2 m; 50 cm.
        assertEquals(50.0, ArrayCalc.quarterWaveCm(150.0), 0.01);
        // The radio's own default tuning.
        assertEquals(18.0, ArrayCalc.quarterWaveCm(416.588), 0.05);
    }

    @Test
    public void quarterWaveRefusesNonsense() {
        assertEquals(0.0, ArrayCalc.quarterWaveCm(0.0), 0.0);
        assertEquals(0.0, ArrayCalc.quarterWaveCm(-5.0), 0.0);
    }

    // ---- KrakenTenna extension count --------------------------------------
    // Straight off the published table. Rows overlap, and the contract is the
    // FEWEST extensions listed at a frequency, so these check the overlaps
    // rather than only the easy cases.

    @Test
    public void collapsedWhipCoversUhf() {
        // 440 - 1050 at 0 extensions, and both edges are inclusive.
        assertEquals(0, ArrayCalc.krakenTennaExtensions(440.0));
        assertEquals(0, ArrayCalc.krakenTennaExtensions(1050.0));
        assertEquals(0, ArrayCalc.krakenTennaExtensions(700.0));
    }

    @Test
    public void overlappingRowsPickTheShortestWhip() {
        // 400 MHz is in row 1 (366-950), row 2 (330-1000) and row 3
        // (290-420). The shortest whip the vendor calls usable wins.
        assertEquals(1, ArrayCalc.krakenTennaExtensions(400.0));
        // 350 is out of row 1 but inside row 2.
        assertEquals(2, ArrayCalc.krakenTennaExtensions(350.0));
        // 300 falls in the second band of rows 3, 4 and 5 only.
        assertEquals(3, ArrayCalc.krakenTennaExtensions(300.0));
    }

    @Test
    public void vhfNeedsThreeExtensions() {
        // 145 - 158 first appears at 3 extensions.
        assertEquals(3, ArrayCalc.krakenTennaExtensions(155.0));
        assertEquals(3, ArrayCalc.krakenTennaExtensions(145.0));
    }

    @Test
    public void lowVhfNeedsTheLongestWhip() {
        // 127 - 150 is row 7 alone at the bottom end.
        assertEquals(7, ArrayCalc.krakenTennaExtensions(128.0));
    }

    @Test
    public void frequenciesOffTheTableSaySo() {
        // Below every row, above every row, and in a gap between the bands.
        assertEquals(-1, ArrayCalc.krakenTennaExtensions(50.0));
        assertEquals(-1, ArrayCalc.krakenTennaExtensions(1500.0));
        assertEquals(-1, ArrayCalc.krakenTennaExtensions(200.0));
    }


    @Test
    public void whipGoesThreeQuarterWaveAboveAGigahertz() {
        // Below the threshold it is a plain quarter wave.
        assertEquals(ArrayCalc.quarterWaveCm(416.588),
                ArrayCalc.whipLengthCm(416.588), 0.001);
        // The template author's own cross-check: the 3/4-wave setting for
        // 1800 MHz is the same length as the 1/4-wave setting for 600 MHz.
        assertEquals(ArrayCalc.quarterWaveCm(600.0),
                ArrayCalc.whipLengthCm(1800.0), 0.001);
        // And that length is 12.5 cm, by hand.
        assertEquals(12.5, ArrayCalc.whipLengthCm(1800.0), 0.01);
    }

    @Test
    public void theThresholdItselfIsStillAQuarterWave() {
        // At exactly 1000 MHz the retracted whip IS a quarter wave, so the
        // rule only applies strictly above it.
        assertEquals(7.5, ArrayCalc.whipLengthCm(1000.0), 0.01);
        assertEquals(22.5, ArrayCalc.whipLengthCm(1000.1), 0.02);
    }

}
