
package com.atakmap.android.atmosphere.data;

/**
 * The Fire Weather Snooper's own emphasis: which single reading is the concerning one.
 *
 * <p>A station's color says whether it is at Red Flag. It does not say <i>why</i>, and
 * on the Snooper that is the whole point of the page -- the wind goes bold when the
 * wind is what is climbing, the humidity when it is the humidity, the fuel stick when
 * it is the fuels. The operator asked for the same here (2026-09-25: "when it bold
 * like the wind or where the fuel stick is, sort of lets you know").
 *
 * <p>The bands are the Snooper's, from its own legend:
 *
 * <pre>
 *   notable        wind/gust 15-24    RH 16-20%    fuels 8-9
 *   near critical  wind/gust 25-34    RH 11-15%    fuels 5-7
 *   extreme        wind/gust 35+      RH 0-10%     fuels 0-4
 * </pre>
 *
 * <p>Note the humidity and the fuels run the other way from the wind: lower is worse.
 * Writing all three as one comparison is how a legend ends up saying that damp air is
 * dangerous.
 *
 * <p>No Android types, so the bands are tested.
 */
public final class Snooper {

    /** Nothing to say about this reading. */
    public static final int PLAIN = 0;
    /** Worth noticing. The Snooper prints it in bold. */
    public static final int NOTABLE = 1;
    /** Likely near critical. The Snooper prints it in purple. */
    public static final int NEAR_CRITICAL = 2;
    /** Extreme. The Snooper prints it in red. */
    public static final int EXTREME = 3;

    /** Wind or gust, whichever is being shown, in miles per hour. */
    public static int windBand(double mph) {
        if (Double.isNaN(mph))
            return PLAIN;
        if (mph >= 35)
            return EXTREME;
        if (mph >= 25)
            return NEAR_CRITICAL;
        return mph >= 15 ? NOTABLE : PLAIN;
    }

    /** Relative humidity in percent. Lower is worse. */
    public static int humidityBand(double percent) {
        if (Double.isNaN(percent))
            return PLAIN;
        if (percent <= 10)
            return EXTREME;
        if (percent <= 15)
            return NEAR_CRITICAL;
        return percent <= 20 ? NOTABLE : PLAIN;
    }

    /** Ten hour fuel moisture in percent. Lower is worse. */
    public static int fuelBand(double percent) {
        if (Double.isNaN(percent))
            return PLAIN;
        if (percent <= 4)
            return EXTREME;
        if (percent <= 7)
            return NEAR_CRITICAL;
        return percent <= 9 ? NOTABLE : PLAIN;
    }

    /** What that band means, for the legend. */
    public static String label(int band) {
        switch (band) {
            case EXTREME:
                return "Extreme";
            case NEAR_CRITICAL:
                return "Likely near critical";
            case NOTABLE:
                return "Worth noticing";
            default:
                return "";
        }
    }

    private Snooper() {
    }
}
