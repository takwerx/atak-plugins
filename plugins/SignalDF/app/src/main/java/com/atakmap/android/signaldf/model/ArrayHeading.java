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
        /**
         * The operator said antenna 0 points down the vehicle, and ATAK's own
         * self marker is supplying the course. The normal case once the array
         * is bolted on the way both templates tell you to bolt it.
         */
        VEHICLE,
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
     * The preference key for "antenna 0 points down the vehicle". One key,
     * read by the pane and by the publisher, so the map and what goes on the
     * wire can never disagree about which way the array is facing.
     */
    public static final String PREF_FORWARD = "signaldf.antenna0_forward";

    /**
     * ATAK's course when the operator has said antenna 0 points forward and
     * the vehicle is moving; null otherwise. The one place that rule lives.
     */
    public static Double vehicleDegrees(boolean antenna0Forward) {
        if (!antenna0Forward)
            return null;
        VehicleHeading v = VehicleHeading.get();
        return v == null ? null : v.degrees();
    }

    public static ArrayHeading vehicle(double degrees) {
        return new ArrayHeading(degrees, Source.VEHICLE);
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
     * <p>{@code vehicleDeg} is ATAK's own course, already speed-gated and
     * hold-limited by {@link VehicleHeading}, and non-null only when the
     * operator has said antenna 0 points down the vehicle. It outranks the
     * radio because it is a statement the operator made about their own
     * install, and the radio's heading field is zero on most units.
     */
    public static ArrayHeading resolve(Double operatorSetting, Double vehicleDeg,
            Bearing newest) {
        if (operatorSetting != null)
            return manual(operatorSetting);
        if (vehicleDeg != null)
            return vehicle(vehicleDeg);
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
        return source == Source.MANUAL || source == Source.VEHICLE
                || source == Source.RADIO;
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
                        "Array heading: %.0f deg, set manually", degrees);
            case VEHICLE:
                return String.format(Locale.US,
                        "Array heading: %.0f deg, from your GPS track",
                        degrees);
            case RADIO:
                return String.format(Locale.US,
                        "Array heading: %.0f deg, from the radio", degrees);
            case RADIO_ZERO:
                return "Array heading: the radio reports 0 deg, which is also "
                        + "what it reports when it has none. Set a heading "
                        + "source to be certain.";
            case NONE:
            default:
                // Actionable, not just honest. The default heading source is
                // the radio, and a KrakenSDR has no GPS of its own -- the
                // vendor's own note is that "a seperate GPS unit is not
                // required if you're using the Kraken App, as Kraken App can
                // make use of the smartphone GPS instead". So on most rigs
                // this line is the first thing the operator sees and it has
                // to name the fix.
                return "Array heading: not set. The radio measures direction "
                        + "from antenna 0, not from north, so a line on the "
                        + "map is turned by however far the array is turned. "
                        + "Set a heading source.";
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
