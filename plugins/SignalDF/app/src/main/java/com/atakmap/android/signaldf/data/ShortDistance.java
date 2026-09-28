package com.atakmap.android.signaldf.data;

import com.atakmap.coremap.conversions.Span;

import java.util.Locale;

/**
 * Short distances, in whatever unit ATAK is set to.
 *
 * <p>CLAUDE.md's rule is that distances follow {@code rab_rng_units_pref} and
 * are never hardcoded, and the array sizing screen broke it: it said
 * centimeters to everybody. An operator whose ATAK reads feet and miles is
 * being handed a number in a unit they do not have a tape measure for.
 *
 * <p>{@link Units} cannot do this job -- it formats through
 * {@code SpanUtilities} in meters, feet or miles, and an antenna spacing is
 * tens of centimeters, which those render as "0 m" or a long decimal. So this
 * reads the same preference {@link Units} does and picks the small unit a
 * person would actually measure with: centimeters, or inches.
 *
 * <p>{@link Units} is copied byte-for-byte between plugins and must stay that
 * way, which is why this is a separate class rather than a method added to it.
 */
public final class ShortDistance {

    private ShortDistance() {
    }

    private static final double CM_PER_INCH = 2.54;

    /** True when ATAK is set to feet and miles rather than meters. */
    public static boolean imperial() {
        // Span.ENGLISH is 0 and METRIC is 1. Assuming the obvious ordering gets
        // this exactly backwards, which is why it is written out.
        return Units.type() == Span.ENGLISH;
    }

    /** A length given in centimeters, formatted in the operator's own unit. */
    public static String fromCm(double cm) {
        if (imperial())
            return String.format(Locale.US, "%.1f in", cm / CM_PER_INCH);
        return String.format(Locale.US, "%.1f cm", cm);
    }

    /** Just the number, for a label with its own unit word nearby. */
    public static double valueFromCm(double cm) {
        return imperial() ? cm / CM_PER_INCH : cm;
    }

    /** The unit word on its own: {@code cm} or {@code in}. */
    public static String unit() {
        return imperial() ? "in" : "cm";
    }

    /** Turns what the operator typed, in their own unit, back into centimeters. */
    public static double toCm(double entered) {
        return imperial() ? entered * CM_PER_INCH : entered;
    }
}
