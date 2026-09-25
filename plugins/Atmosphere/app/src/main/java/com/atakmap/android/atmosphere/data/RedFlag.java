
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

    /** Below anything worth coloring. */
    public static final int BELOW = 0;
    /** Meeting one of the two, or close to both. */
    public static final int NEAR = 1;
    /** Dry and windy together. */
    public static final int CRITICAL = 2;

    /**
     * The state of one reading: red when every criterion is met, yellow when one of
     * them already is, and nothing when neither.
     *
     * <p>Yellow is one leg of Red Flag being there on its own -- the wind is up but
     * the air is damp, or the air is dry but nothing is moving. That is the operator's
     * definition and the one that matters on a fire: "flirting, one of the criteria
     * for red flag met; red, all categories met" (2026-09-25). It does mean a
     * saturated station gusting hard draws yellow, which looks odd until you read it
     * as what it is -- half of Red Flag, waiting on the other half.
     *
     * <p>A missing value is not a met criterion: a station that sent no humidity is
     * not a dry station, and coloring it on the wind alone would put a warning on the
     * map with nothing behind it.
     */
    public static int state(double relativeHumidity, double windMph) {
        final boolean dry = !Double.isNaN(relativeHumidity)
                && relativeHumidity <= RH_CRITICAL;
        final boolean windy = !Double.isNaN(windMph) && windMph >= WIND_CRITICAL;
        if (dry && windy)
            return CRITICAL;                    // every criterion met
        if (dry || windy)
            return NEAR;                        // one of them already there
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
