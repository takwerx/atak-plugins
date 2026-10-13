
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

    /**
     * One way a zone's criteria can be met: a humidity ceiling with a wind floor,
     * sustained or gust. An office writes "RH 15% or less with sustained wind 25 mph
     * or more or gusts 35 mph or more", and that is one leg.
     */
    public static final class Leg {
        /** Relative humidity at or below this, percent. */
        public final double rhMax;
        /** Sustained wind at or above this, mph; NaN when the leg has no sustained floor. */
        public final double sustainedMin;
        /** Gusts at or above this, mph; NaN when the leg has no gust floor. */
        public final double gustMin;

        public Leg(double rhMax, double sustainedMin, double gustMin) {
            this.rhMax = rhMax;
            this.sustainedMin = sustainedMin;
            this.gustMin = gustMin;
        }

        boolean dry(double rh) {
            return !Double.isNaN(rh) && rh <= rhMax;
        }

        boolean windy(double sustained, double gust) {
            return (!Double.isNaN(sustainedMin) && !Double.isNaN(sustained)
                    && sustained >= sustainedMin)
                    || (!Double.isNaN(gustMin) && !Double.isNaN(gust) && gust >= gustMin);
        }

        /** "Humidity 15% or less with wind 25 mph or more or gusts 35 mph or more" */
        String describe() {
            final StringBuilder b = new StringBuilder("Humidity ")
                    .append(Math.round(rhMax)).append("% or less with ");
            if (!Double.isNaN(sustainedMin))
                b.append("wind ").append(Math.round(sustainedMin)).append(" mph or more");
            if (!Double.isNaN(sustainedMin) && !Double.isNaN(gustMin))
                b.append(" or ");
            if (!Double.isNaN(gustMin))
                b.append("gusts ").append(Math.round(gustMin)).append(" mph or more");
            return b.toString();
        }
    }

    /**
     * One zone's criteria: the legs an office lists, any one of which is Red Flag,
     * and where they were transcribed from.
     *
     * <p>What an AOP adds beyond this -- a duration, a fuel dryness level, a
     * lightning forecast -- is not something one observation can judge, so the
     * record names the source and the rest is read there.
     */
    public static final class Criteria {
        public final Leg[] legs;
        /** Where it came from, said in the record: an AOP and its year, or "common". */
        public final String source;

        public Criteria(String source, Leg... legs) {
            this.legs = legs;
            this.source = source;
        }

        /** The legs, joined with "; or". */
        public String describe() {
            final StringBuilder b = new StringBuilder();
            for (Leg l : legs) {
                if (b.length() > 0)
                    b.append("; or ");
                b.append(l.describe());
            }
            return b.toString();
        }

        /**
         * The widely used pair, for a zone whose office's table is not loaded. The
         * gust floor matches the sustained one, which is how the layer read the
         * wind before there were zones: the strongest wind the station had.
         */
        public static final Criteria NATIONAL = new Criteria(
                "common thresholds; this zone's own criteria are not loaded",
                new Leg(RH_CRITICAL, WIND_CRITICAL, WIND_CRITICAL));
    }

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
        return state(relativeHumidity, windMph, Double.NaN, Criteria.NATIONAL);
    }

    /**
     * The same, held against one zone's own criteria: red when any leg is met in
     * full, yellow when any leg has one side met, nothing otherwise.
     */
    public static int state(double relativeHumidity, double sustainedMph, double gustMph,
            Criteria c) {
        if (c == null)
            c = Criteria.NATIONAL;
        boolean anyDry = false, anyWindy = false;
        for (Leg l : c.legs) {
            final boolean dry = l.dry(relativeHumidity);
            final boolean windy = l.windy(sustainedMph, gustMph);
            if (dry && windy)
                return CRITICAL;                // every criterion of this leg met
            anyDry |= dry;
            anyWindy |= windy;
        }
        if (anyDry || anyWindy)
            return NEAR;                        // one side of some leg already there
        return BELOW;
    }

    /** What the coloring is based on, said plainly under the layer. */
    public static String basis() {
        return "Each station is held against its fire weather zone's own criteria "
                + "where its office's plan is loaded (California, Great Basin, Southwest, "
                + "Northwest). Elsewhere it "
                + "is humidity " + (int) RH_CRITICAL + "% or less with wind "
                + (int) WIND_CRITICAL + " mph or more, the common thresholds, not the "
                + "zone's own criteria. A station's record says which.";
    }

    private RedFlag() {
    }
}
