
package com.atakmap.android.atmosphere.data;

/**
 * Whether a station's reading is at Red Flag conditions, close to them, or below.
 *
 * <p><b>Red Flag criteria are set by each NWS office for each fire weather zone</b>, in
 * that office's annual operating plan, and they differ: a humidity that is routine in
 * the Great Basin is a warning on the coast. This class holds the <i>national</i> shape
 * of the rule -- dry and windy together -- with the most cautious thresholds in common
 * use, and is the place the per-zone tables go when they are wired in. Until then it
 * says so, and the layer says so, because a color that claims to be a zone's criteria
 * and is not is worse than one that admits to a rule of thumb.
 *
 * <p>The thresholds here are the widely used pairing of <b>RH at or below 15%</b> with
 * <b>sustained wind at or above 25 mph</b>. Offices that differ mostly differ upward
 * (drier, windier), so a station this calls critical is at or beyond nearly every
 * office's bar, and one it calls near is worth a second look anywhere.
 *
 * <p>The middle state is the operator's: a station "flirting" with its criteria, which
 * is the one a crew wants to see before it trips. It is meeting one of the two, or
 * within reach of both.
 *
 * <p>No Android types, so the rule can be tested.
 */
public final class RedFlag {

    /** Relative humidity at or below this, with the wind, is critical. Percent. */
    public static final double RH_CRITICAL = 15.0;
    /** Sustained wind at or above this, with the humidity, is critical. Miles per hour. */
    public static final double WIND_CRITICAL = 25.0;

    /** Within reach: not there, but close enough to watch. */
    public static final double RH_NEAR = 20.0;
    public static final double WIND_NEAR = 20.0;

    /** Below anything worth coloring. */
    public static final int BELOW = 0;
    /** Meeting one of the two, or close to both. */
    public static final int NEAR = 1;
    /** Dry and windy together. */
    public static final int CRITICAL = 2;

    /**
     * The state of one reading.
     *
     * <p>A missing value never makes a station critical: a station that did not send a
     * humidity is not a dry station, and coloring it red because its wind alone is up
     * would put a red diamond on the map with nothing behind it. It can still be
     * {@link #NEAR} on the value it did send, which is what a reading of 31 mph and no
     * humidity deserves.
     */
    public static int state(double relativeHumidity, double windMph) {
        final boolean dry = !Double.isNaN(relativeHumidity)
                && relativeHumidity <= RH_CRITICAL;
        final boolean windy = !Double.isNaN(windMph) && windMph >= WIND_CRITICAL;
        if (dry && windy)
            return CRITICAL;

        // Flirting means approaching on BOTH axes, because the criteria are a pair.
        // Either one alone used to be enough here, which lit a station up yellow at
        // 87% humidity because it was gusting 30 -- soaking wet and nowhere near Red
        // Flag, and the symbol said otherwise (found on the map, 2026-09-25). Wind
        // without dryness is a windy day; dryness without wind is a dry one. Neither
        // is this.
        final boolean nearlyDry = !Double.isNaN(relativeHumidity)
                && relativeHumidity <= RH_NEAR;
        final boolean nearlyWindy = !Double.isNaN(windMph) && windMph >= WIND_NEAR;
        if (nearlyDry && nearlyWindy)
            return NEAR;
        return BELOW;
    }

    /** What the coloring is based on, said plainly under the layer. */
    public static String basis() {
        return "Humidity " + (int) RH_CRITICAL + "% or less with wind "
                + (int) WIND_CRITICAL + " mph or more. Your local office sets its own "
                + "criteria by zone; these are the common thresholds.";
    }

    private RedFlag() {
    }
}
