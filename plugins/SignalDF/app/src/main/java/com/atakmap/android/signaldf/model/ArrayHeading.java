package com.atakmap.android.signaldf.model;

import com.atakmap.android.signaldf.data.Angles;

import java.util.Locale;

/**
 * Which way the antenna array is pointing, and how we claim to know.
 *
 * <p>This is the quiet half of the bearing problem, and it is worse than the
 * mirror because it never looks wrong. The KrakenSDR does not fold heading into
 * the bearing on any export path: {@code array_offset} is already inside the
 * DSP's scanning vectors, {@code compass_offset} is applied only in the web
 * GUI's own display code, and {@code heading} rides as its own field that the
 * consumer is expected to add. So a true bearing is
 * {@code theta_0 + array heading}, and if nobody supplies the heading the map
 * shows bearings relative to a piece of aluminium on a truck.
 *
 * <p><b>Zero is ambiguous and the wire cannot disambiguate it.</b> The radio
 * writes {@code 0.0} both when the array genuinely faces true north and when it
 * has no idea -- which is the normal state, because heading only becomes
 * non-zero if the operator set a fixed one, or gpsd is attached <em>and</em> the
 * vehicle has been moving above {@code gps_min_speed_for_valid_heading} for
 * longer than {@code gps_min_duration_for_valid_heading}. A parked vehicle with
 * a working GPS reports heading 0, and applying it silently asserts that the
 * array's zero is north.
 *
 * <p>Hence this type. Nothing in the plugin passes a bare number around: a
 * heading always arrives with the reason it is believed, and the pane prints
 * {@link #provenance()} beside every bearing. "Array heading: 142 deg, you set
 * it" and "Array heading: unknown -- bearings are relative to the antenna, not
 * to north" are different claims and the operator gets to see which one they
 * have.
 */
public final class ArrayHeading {

    /** Where a heading came from, best first. */
    public enum Source {
        /** The operator typed or calibrated it. Trusted above the radio's. */
        MANUAL,
        /** The radio reported a non-zero heading of its own. */
        RADIO,
        /**
         * The radio reported exactly zero. Kept separate from {@link #NONE}
         * because the operator should be told the difference between a radio
         * that is silent and one that is reporting the ambiguous value.
         */
        RADIO_ZERO,
        /** Nothing knows. Bearings are relative to the array. */
        NONE
    }

    private final double degrees;
    private final Source source;

    private ArrayHeading(double degrees, Source source) {
        this.degrees = Angles.norm360(degrees);
        this.source = source;
    }

    public static ArrayHeading manual(double degrees) {
        return new ArrayHeading(degrees, Source.MANUAL);
    }

    public static ArrayHeading none() {
        return new ArrayHeading(0.0, Source.NONE);
    }

    /**
     * Resolves what to use, given the operator's setting and the newest bearing
     * the radio produced.
     *
     * <p>Order: what the operator set, then what the radio reported if it is
     * not the ambiguous zero, then nothing. Note that every branch but the
     * first ends up applying 0 degrees -- the arithmetic is the same and the
     * <em>claim</em> is not, which is the entire point of returning this type
     * rather than a double.
     *
     * <p>Not yet a source: the phone's own track. On a vehicle the self marker
     * already knows which way the truck is going and that is the right answer
     * for a Kraken bolted to it, but it is only honest above a minimum speed --
     * a stationary phone's track heading is as meaningless as the radio's zero,
     * and shipping it without that gate would swap one silent wrong answer for
     * another. It lands with the vehicle work.
     */
    public static ArrayHeading resolve(Double operatorSetting, Bearing newest) {
        if (operatorSetting != null)
            return manual(operatorSetting);
        if (newest != null && newest.headingReported && newest.headingDeg != 0.0)
            return new ArrayHeading(newest.headingDeg, Source.RADIO);
        if (newest != null && newest.headingReported)
            return new ArrayHeading(0.0, Source.RADIO_ZERO);
        return none();
    }

    /** Degrees true to add to an array-relative bearing. */
    public double degrees() {
        return degrees;
    }

    public Source source() {
        return source;
    }

    /**
     * Whether a bearing built on this can be called a bearing to north at all.
     * When false, the map is showing a direction relative to the antenna and
     * everything displaying it has to say so.
     */
    public boolean isKnown() {
        return source == Source.MANUAL || source == Source.RADIO;
    }

    /**
     * One line for the pane. Never a number without the reason for it -- the
     * house rule is that a panel says what it is not showing, and a heading
     * nobody can account for is the most expensive thing on this screen.
     */
    public String provenance() {
        switch (source) {
            case MANUAL:
                return String.format(Locale.US,
                        "Array heading: %.0f deg, you set it", degrees);
            case RADIO:
                return String.format(Locale.US,
                        "Array heading: %.0f deg, from the radio", degrees);
            case RADIO_ZERO:
                return "Array heading: the radio says 0 deg, which is also what "
                        + "it says when it has none -- set one to be sure";
            case NONE:
            default:
                return "Array heading: unknown -- bearings are relative to the "
                        + "antenna, not to north";
        }
    }

    /** Short form for a map label or a cramped row. */
    public String shortLabel() {
        return isKnown()
                ? String.format(Locale.US, "%.0f deg", degrees)
                : "relative";
    }

    @Override
    public String toString() {
        return provenance();
    }
}
