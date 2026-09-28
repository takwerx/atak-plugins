package com.atakmap.android.signaldf.data;

import java.util.Locale;

/**
 * Antenna array sizing: what to build for a frequency, and whether what you
 * have built will work at one.
 *
 * <p>This is KrakenRF's own <i>Antenna Array Size Calculator</i> spreadsheet,
 * reimplemented. Every formula below was read out of that workbook and every
 * one is pinned by a test that reproduces the workbook's own printed values, so
 * a change that drifts from it fails rather than quietly disagreeing with what
 * the operator was told to build.
 *
 * <p><b>Why it is in the plugin rather than left as a spreadsheet.</b> The
 * spreadsheet asks for the frequency and the array size; the plugin already
 * knows both -- the radio reports what it is tuned to and how its array is
 * configured. So this can do the thing the spreadsheet cannot: look at the
 * array somebody actually built, look at what the radio is actually listening
 * to, and say whether those two agree. A DF bearing from an array that is the
 * wrong size for the frequency is not obviously wrong; it is confidently wrong.
 *
 * <p><b>The two rules, in KrakenRF's words.</b> "Array sizing must use a
 * spacing multiplier less than 0.5 in order to avoid ambiguities", and "We
 * consider a resolution of 0 - 25 degrees acceptable for direction finding."
 * Both are theirs, not ours, and {@link #ambiguityFree} and
 * {@link #resolutionUsable} are the only places they are expressed.
 *
 * <p><b>The resolution figure is not the textbook Rayleigh limit.</b> The
 * workbook divides it by ten, which is its allowance for the super-resolution
 * the DSP gets from MUSIC. That factor is reproduced exactly rather than
 * "corrected": the number an operator sees here has to be the number the
 * vendor's own tool gives them, or the two disagree about what array to build.
 */
public final class ArrayCalc {

    /** Which shape the elements are in. The radio calls these UCA and ULA. */
    public enum Geometry {
        /** Uniform circular array. Its size is a RADIUS. */
        CIRCULAR("UCA", "circular"),
        /** Uniform linear array. Its size is a TOTAL LENGTH end to end. */
        LINEAR("ULA", "linear");

        private final String radioName;
        private final String plain;

        Geometry(String radioName, String plain) {
            this.radioName = radioName;
            this.plain = plain;
        }

        /** What the radio's own settings call it: {@code UCA} or {@code ULA}. */
        public String radioName() {
            return radioName;
        }

        public String plain() {
            return plain;
        }

        /** Maps the radio's {@code ant_arrangement} onto this, or null. */
        public static Geometry fromRadio(String antArrangement) {
            if (antArrangement == null)
                return null;
            String s = antArrangement.trim().toUpperCase(Locale.US);
            if (s.startsWith("UCA"))
                return CIRCULAR;
            if (s.startsWith("ULA"))
                return LINEAR;
            return null;
        }
    }

    /** Spacing at or above this wavelength fraction aliases. KrakenRF's rule. */
    public static final double AMBIGUITY_LIMIT = 0.5;

    /**
     * Below this the array still works but resolves too poorly to trust.
     * KrakenRF: "keep the spacing multiplier above around 0.2 ... Below 0.2,
     * the resolution becomes too poor", and "for 5-elements the accuracy starts
     * to become unacceptable below around s=0.2". This is the LOWER end of the
     * usable band, and it is a multiplier rule rather than a resolution one --
     * their published per-hole frequency table is exactly s from 0.2 to 0.5.
     */
    public static final double MIN_MULTIPLIER = 0.2;

    /** What KrakenRF say they actually build to: "we typically set our arrays to s=0.33". */
    public static final double TYPICAL_MULTIPLIER = 0.33;

    /**
     * The radii KrakenRF's printed template can actually give you, in
     * centimeters. Their arms have holes at 50 mm intervals, so an array built
     * with the template is one of these four and nothing in between -- which is
     * why this class can answer "which hole" rather than only "what radius".
     */
    public static final double[] TEMPLATE_RADII_CM = { 10.0, 15.0, 20.0, 25.0 };

    /** Resolution worse than this is not useful for DF. KrakenRF's rule. */
    public static final double RESOLUTION_LIMIT_DEG = 25.0;

    private ArrayCalc() {
    }

    /**
     * Wavelength in meters. The workbook uses {@code 300 / f(MHz)} -- the
     * engineering approximation, not {@code c / f} -- and it is kept because
     * every number the operator compares against came from it.
     */
    public static double wavelengthM(double freqMHz) {
        return 300.0 / freqMHz;
    }

    /**
     * The chord factor for a circle of N elements:
     * {@code sqrt(2(1 - cos(360/N)))}, which is the straight-line distance
     * between neighboring elements on a unit-radius circle. Radius and
     * element spacing differ by this, and conflating them is how an array ends
     * up built to the wrong size.
     */
    static double chordFactor(int elements) {
        return Math.sqrt(2.0 * (1.0 - Math.cos(Math.toRadians(360.0 / elements))));
    }

    /** One sizing answer: what to build, and how well it will work. */
    public static final class Result {
        /** Wavelength at the frequency asked about, meters. */
        public final double wavelengthM;
        /** Distance between neighboring elements, centimeters. */
        public final double spacingCm;
        /** Radius for a circular array, total end-to-end length for a linear one. */
        public final double sizeCm;
        /** That same size expressed in wavelengths. */
        public final double sizeWavelengths;
        /** Element spacing as a fraction of a wavelength. Under 0.5 or it aliases. */
        public final double multiplier;
        /** Estimated resolution, degrees, on KrakenRF's super-resolution scale. */
        public final double resolutionDeg;
        public final Geometry geometry;
        public final int elements;
        public final double freqMHz;

        Result(Geometry g, int elements, double freqMHz, double wavelengthM,
                double spacingCm, double sizeCm, double sizeWavelengths,
                double multiplier, double resolutionDeg) {
            this.geometry = g;
            this.elements = elements;
            this.freqMHz = freqMHz;
            this.wavelengthM = wavelengthM;
            this.spacingCm = spacingCm;
            this.sizeCm = sizeCm;
            this.sizeWavelengths = sizeWavelengths;
            this.multiplier = multiplier;
            this.resolutionDeg = resolutionDeg;
        }

        /** Both of KrakenRF's rules met. */
        public boolean usable() {
            return ambiguityFree(multiplier) && multiplier >= MIN_MULTIPLIER;
        }

        /**
         * What is wrong with this array at this frequency, in words an operator
         * can act on, or empty when nothing is. Deliberately says which way to
         * move the array rather than only naming the number.
         */
        public String problem() {
            if (!ambiguityFree(multiplier))
                return String.format(Locale.US,
                        "Elements are %.2f wavelengths apart. Over %.1f the array cannot tell "
                                + "some directions apart -- make it smaller, or tune lower.",
                        multiplier, AMBIGUITY_LIMIT);
            if (multiplier < MIN_MULTIPLIER)
                return String.format(Locale.US,
                        "Elements are only %.2f wavelengths apart. Under %.1f the bearings "
                                + "get too rough to trust -- make the array bigger, or tune higher.",
                        multiplier, MIN_MULTIPLIER);
            return "";
        }
    }

    /**
     * What to build: given a frequency, an element count and a spacing
     * multiplier, the array size that results.
     *
     * @param multiplier element spacing as a fraction of a wavelength. Keep it
     *                   just under {@link #AMBIGUITY_LIMIT} for the best
     *                   resolution that is still unambiguous.
     */
    public static Result sizeFor(Geometry g, double freqMHz, int elements, double multiplier) {
        double lambda = wavelengthM(freqMHz);
        double spacingCm = multiplier * lambda * 100.0;
        if (g == Geometry.CIRCULAR) {
            double radiusWl = multiplier / chordFactor(elements);
            double radiusCm = lambda * radiusWl * 100.0;
            // Aperture of a circle is its DIAMETER, hence the 2.
            double res = Math.toDegrees(1.22 / (radiusWl * 2.0)) / 10.0;
            return new Result(g, elements, freqMHz, lambda, spacingCm, radiusCm,
                    radiusWl, multiplier, res);
        }
        // Linear: N elements make N-1 gaps, so the aperture is (N-1) spacings.
        double lengthWl = (elements - 1) * multiplier;
        double lengthCm = lengthWl * lambda * 100.0;
        double res = Math.toDegrees(1.22 / lengthWl) / 10.0;
        return new Result(g, elements, freqMHz, lambda, spacingCm, lengthCm,
                lengthWl, multiplier, res);
    }

    /**
     * What you have: given an array that is already built, at a frequency, what
     * spacing multiplier and resolution it gives. This is the direction the
     * spreadsheet makes hard and the plugin makes easy, because the radio
     * already reports both numbers.
     *
     * @param sizeCm radius for a circular array, total end-to-end length for a
     *               linear one -- the same thing {@link Result#sizeCm} returns
     */
    public static Result atSize(Geometry g, double freqMHz, int elements, double sizeCm) {
        double lambda = wavelengthM(freqMHz);
        if (g == Geometry.CIRCULAR) {
            double radiusWl = (sizeCm / 100.0) / lambda;
            double multiplier = radiusWl * chordFactor(elements);
            double res = Math.toDegrees(1.22 / (radiusWl * 2.0)) / 10.0;
            return new Result(g, elements, freqMHz, lambda, multiplier * lambda * 100.0,
                    sizeCm, radiusWl, multiplier, res);
        }
        double lengthWl = (sizeCm / 100.0) / lambda;
        double multiplier = lengthWl / (elements - 1);
        double res = Math.toDegrees(1.22 / lengthWl) / 10.0;
        return new Result(g, elements, freqMHz, lambda, multiplier * lambda * 100.0,
                sizeCm, lengthWl, multiplier, res);
    }

    /**
     * The highest frequency a built array stays unambiguous at -- the one where
     * the spacing reaches {@link #AMBIGUITY_LIMIT}. Above this the array
     * aliases and its bearings cannot be trusted.
     *
     * @param sizeCm radius (circular) or total length (linear)
     */
    public static double highestUsableMHz(Geometry g, int elements, double sizeCm) {
        double spacingM = g == Geometry.CIRCULAR
                ? (sizeCm / 100.0) * chordFactor(elements)
                : (sizeCm / 100.0) / (elements - 1);
        return 300.0 / (spacingM / AMBIGUITY_LIMIT);
    }

    /**
     * The lowest frequency a built array is still trusted at -- where the
     * spacing falls to {@link #MIN_MULTIPLIER}.
     *
     * <p>This is a multiplier rule, not a resolution one, and getting that
     * wrong was the first version of this class. KrakenRF publish the band each
     * template hole covers -- 250 mm is 204 to 510 MHz -- and those numbers are
     * exactly s = 0.2 and s = 0.5. Deriving the bottom from the 25 degree
     * resolution figure instead gave 152 MHz for an array they say bottoms out
     * at 204.
     */
    public static double lowestUsableMHz(Geometry g, int elements, double sizeCm) {
        return frequencyAt(g, elements, sizeCm, MIN_MULTIPLIER);
    }

    /** The frequency at which a built array has exactly this spacing multiplier. */
    public static double frequencyAt(Geometry g, int elements, double sizeCm,
            double multiplier) {
        double spacingM = g == Geometry.CIRCULAR
                ? (sizeCm / 100.0) * chordFactor(elements)
                : (sizeCm / 100.0) / (elements - 1);
        return 300.0 / (spacingM / multiplier);
    }

    /**
     * The template hole to use at this frequency, or -1 when none of them
     * works. Prefers the hole closest to {@link #TYPICAL_MULTIPLIER}, which is
     * what KrakenRF say they build to, rather than the largest that merely
     * fits.
     *
     * @return a radius from {@link #TEMPLATE_RADII_CM}, or -1
     */
    public static double templateRadiusCm(int elements, double freqMHz) {
        double best = -1, bestDistance = Double.MAX_VALUE;
        for (double radius : TEMPLATE_RADII_CM) {
            Result r = atSize(Geometry.CIRCULAR, freqMHz, elements, radius);
            if (!ambiguityFree(r.multiplier) || r.multiplier < MIN_MULTIPLIER)
                continue;
            double d = Math.abs(r.multiplier - TYPICAL_MULTIPLIER);
            if (d < bestDistance) {
                bestDistance = d;
                best = radius;
            }
        }
        return best;
    }

    public static boolean ambiguityFree(double multiplier) {
        return multiplier < AMBIGUITY_LIMIT;
    }

    public static boolean resolutionUsable(double resolutionDeg) {
        return resolutionDeg <= RESOLUTION_LIMIT_DEG;
    }

    /**
     * Where each element goes, in centimeters, for drawing a top-down plan.
     *
     * <p>Element 0 is the array's forward direction and sits on the +X axis;
     * the rest run clockwise, which is the workbook's own layout ("ANT 0 points
     * to the forward direction of the array") and matches a compass rather than
     * the mathematical convention.
     *
     * @return {@code [n][2]} of x, y in centimeters
     */
    public static double[][] positions(Geometry g, int elements, double sizeCm) {
        double[][] p = new double[elements][2];
        if (g == Geometry.CIRCULAR) {
            for (int i = 0; i < elements; i++) {
                double a = 2.0 * Math.PI / elements * i;
                p[i][0] = sizeCm * Math.cos(a);
                p[i][1] = sizeCm * Math.sin(-a);
            }
            return p;
        }
        double step = sizeCm / (elements - 1);
        for (int i = 0; i < elements; i++) {
            p[i][0] = step * i;
            p[i][1] = 0.0;
        }
        return p;
    }

    /**
     * The length one antenna element should be, in centimeters: a quarter of
     * the wavelength.
     *
     * <p>This is not in the vendor's workbook, which sizes the array and stops.
     * It is in their antenna setup guide, and it is the other measurement
     * somebody standing at a vehicle with a telescopic whip in their hand
     * needs: <i>"Under normal circumstances, you will want the length to be a
     * quarter of the wavelength of the frequency of interest for best
     * reception."</i>
     *
     * <p>Plain lambda/4, with no end-effect shortening factor applied. A
     * telescopic whip is set by eye against a tape to the nearest few
     * millimeters, the guide itself says <i>"using shorter than optimal antenna
     * lengths will be acceptable in most cases"</i>, and quoting 17.1 cm where
     * the vendor's own rule gives 18.0 would be false precision about a number
     * nobody can hit anyway.
     */
    public static double quarterWaveCm(double freqMHz) {
        if (freqMHz <= 0)
            return 0;
        return wavelengthM(freqMHz) * 100.0 / 4.0;
    }

    /**
     * The KrakenTenna telescopic sets the vendor sells come in sections, and
     * their guide publishes the frequencies each number of extensions is
     * usable at. Rows overlap heavily, so this returns the FEWEST extensions
     * listed as usable at {@code freqMHz}, or -1 when no row covers it.
     *
     * <p>Fewest, rather than nearest to a quarter wave, for two reasons the
     * guide gives itself: <i>"if you are running the antennas on a vehicle,
     * you do not want the antennas to be extended too long for safety"</i>,
     * and <i>"using shorter than optimal antenna lengths will be acceptable in
     * most cases"</i>. The shortest whip the vendor calls usable at a
     * frequency is the one to put on a roof.
     *
     * <p>The ranges are SWR measurements taken over a ground plane, which is
     * why the screen that shows this says so; on a wooden bench they mean much
     * less than on a car roof.
     */
    public static int krakenTennaExtensions(double freqMHz) {
        for (int i = 0; i < KRAKENTENNA_MHZ.length; i++) {
            double[] row = KRAKENTENNA_MHZ[i];
            for (int j = 0; j + 1 < row.length; j += 2)
                if (freqMHz >= row[j] && freqMHz <= row[j + 1])
                    return i;
        }
        return -1;
    }

    /**
     * Usable frequency ranges per number of extensions, from the vendor's
     * antenna setup guide, transcribed in order: index is the extension count,
     * each row is pairs of low/high MHz.
     */
    private static final double[][] KRAKENTENNA_MHZ = {
            { 440, 1050 },
            { 366, 950 },
            { 330, 1000 },
            { 145, 158, 290, 420, 612, 1050 },
            { 140, 156, 270, 360, 595, 1030 },
            { 135, 155, 250, 330, 520, 1050 },
            { 130, 150, 240, 290, 460, 1050 },
            { 127, 150, 235, 282, 450, 1050 },
    };


    /**
     * Above this frequency a KrakenRF telescopic whip cannot be made short
     * enough: fully retracted it is already a quarter wave at 1000 MHz.
     *
     * <p>The 3D template's author gives the way round it -- extend to three
     * quarters of a wavelength instead: <i>"the telescope must be extended by
     * one half-wavelength above the quarter-wavelength ... This shifts the
     * signal phase by 180 degrees, but as you do it on all 5 antennas it does
     * not adulterate the DOA measurement."</i> His own cross-check is that the
     * 3/4-wave setting for 1800 MHz is the same length as the 1/4-wave setting
     * for 600 MHz, which {@link #whipLengthCm} reproduces exactly.
     */
    public static final double RETRACTED_QUARTER_WAVE_MHZ = 1000.0;

    /**
     * How long to extend a telescopic whip: a quarter wavelength, or three
     * quarters above {@link #RETRACTED_QUARTER_WAVE_MHZ} where a quarter is
     * shorter than the antenna retracts to.
     */
    public static double whipLengthCm(double freqMHz) {
        double q = quarterWaveCm(freqMHz);
        return freqMHz > RETRACTED_QUARTER_WAVE_MHZ ? q * 3.0 : q;
    }


    /**
     * Which hole out from the center, counting from 1, or -1 when no hole on
     * KrakenRF's printed arms suits {@code freqMHz}.
     *
     * <p>The number is what somebody laying the array out actually uses. They
     * are holding an arm with four holes in it and counting outward; "the 3rd
     * hole" is a thing they can do without reading a ruler, and "20 cm" is a
     * thing they have to check. Both get said, in that order.
     *
     * <p>KrakenRF: "Each hole is spaced at 50mm radius intervals. So you have
     * radius spacings of 100mm, 150mm, 200mm, and 250mm." So hole 1 is the
     * innermost at 100 mm, not a hole at the hub.
     */
    public static int templateHoleNumber(int elements, double freqMHz) {
        double r = templateRadiusCm(elements, freqMHz);
        if (r <= 0)
            return -1;
        for (int i = 0; i < TEMPLATE_RADII_CM.length; i++)
            if (Math.abs(TEMPLATE_RADII_CM[i] - r) < 0.001)
                return i + 1;
        return -1;
    }

    /** "1st", "2nd", "3rd", "4th" -- for counting holes, nothing more. */
    public static String ordinal(int n) {
        switch (n) {
            case 1:
                return "1st";
            case 2:
                return "2nd";
            case 3:
                return "3rd";
            default:
                return n + "th";
        }
    }

}
