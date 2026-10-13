package com.atakmap.android.atmosphere.data;

import java.util.Locale;

/**
 * Where an observation sits on the NWS marine ladder, so a buoy can be read the
 * way a warning is worded without waiting for the warning.
 *
 * <p>The wind bands are national (NWSI 10-303): small craft 20-33 kt, gale
 * 34-47, storm 48-63, hurricane force 64 and up. The small craft floor is set
 * per office between 20 and 25 kt; 20 is the lowest in use and is what is used
 * here, said so in the record. Seas have no national figure -- a Small Craft
 * Advisory for Hazardous Seas is an office's own threshold -- and 10 ft, the
 * West Coast's, is used and said so. Sustained wind places the band; a single
 * gust reading is not the "frequent gusts" the directive means.
 *
 * <p>The colors are the ones weather.gov draws the matching products in, from
 * {@link NwsHazards}, so the pill and an IPAWS warning for the same water
 * agree. This is an observation classed by the thresholds, not a warning: the
 * warnings themselves are IPAWS's.
 */
public enum MarineBand {
    NONE("Below small craft criteria", 0, null),
    SMALL_CRAFT("Small craft", 20, "Small Craft Advisory"),
    GALE("Gale force", 34, "Gale Warning"),
    STORM("Storm force", 48, "Storm Warning"),
    HURRICANE("Hurricane force", 64, "Hurricane Force Wind Warning");

    /** Seas at or above this, in feet, meet small craft criteria on their own. */
    public static final double SEAS_FT = 10;

    public final String label;
    /** Sustained wind, knots, at which the band starts. */
    public final int fromKt;
    /** The NWS product the band is named for, or null. */
    public final String event;
    /** weather.gov's color for that product; white for none. */
    public final int color;

    MarineBand(String label, int fromKt, String event) {
        this.label = label;
        this.fromKt = fromKt;
        this.event = event;
        this.color = event == null ? 0xFFFFFFFF : NwsHazards.color(event);
    }

    /** The band for a sustained wind in knots and seas in feet; either may be NaN. */
    public static MarineBand of(double windKt, double seasFt) {
        final double w = Double.isNaN(windKt) ? 0 : windKt;
        if (w >= HURRICANE.fromKt)
            return HURRICANE;
        if (w >= STORM.fromKt)
            return STORM;
        if (w >= GALE.fromKt)
            return GALE;
        if (w >= SMALL_CRAFT.fromKt || (!Double.isNaN(seasFt) && seasFt >= SEAS_FT))
            return SMALL_CRAFT;
        return NONE;
    }

    /** "34-47 kt", "64 kt and up". */
    public String range() {
        switch (this) {
            case SMALL_CRAFT:
                return SMALL_CRAFT.fromKt + "-" + (GALE.fromKt - 1) + " kt";
            case GALE:
                return GALE.fromKt + "-" + (STORM.fromKt - 1) + " kt";
            case STORM:
                return STORM.fromKt + "-" + (HURRICANE.fromKt - 1) + " kt";
            case HURRICANE:
                return HURRICANE.fromKt + " kt and up";
            default:
                return "";
        }
    }

    /**
     * The record's line: "Gale force: 38 kt (34-47 kt)", "Small craft: seas 12 ft
     * (10 ft or more)", "Below small craft criteria (20 kt, seas 10 ft)".
     */
    public String describe(double windKt, double seasFt) {
        if (this == NONE)
            return String.format(Locale.US, "%s (%d kt, seas %.0f ft)", label,
                    SMALL_CRAFT.fromKt, SEAS_FT);
        final boolean byWind = !Double.isNaN(windKt) && windKt >= fromKt;
        if (byWind)
            return String.format(Locale.US, "%s: %d kt (%s)", label, Math.round(windKt), range());
        return String.format(Locale.US, "%s: seas %d ft (%.0f ft or more)", label,
                Math.round(seasFt), SEAS_FT);
    }

    /** What the thresholds are and where they come from, for the record. */
    public static String basis() {
        return "NWS wind bands. Small craft from " + SMALL_CRAFT.fromKt
                + " kt, the lowest office threshold in use; seas " + Math.round(SEAS_FT)
                + " ft is the West Coast figure. Warnings in effect are in IPAWS.";
    }
}
