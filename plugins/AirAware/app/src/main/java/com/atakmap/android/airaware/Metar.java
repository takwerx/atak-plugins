package com.atakmap.android.airaware;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * One airfield observation, reduced to what decides whether you can fly.
 *
 * <p>The flight category, the ceiling and the visibility are what it leads with, because
 * those are what gate flight: a restriction says whether you are allowed up, the ceiling
 * says whether you can see, and neither answers the question alone. The rest of the
 * observation is carried too -- it is in the report already, and leaving fields out to
 * avoid resembling the weather plugin would make this one worse to solve a problem nobody
 * has.
 */
public class Metar {

    /** Flight category, as published. Never derived here: see {@link #category}. */
    public enum Cat {
        VFR, MVFR, IFR, LIFR, UNKNOWN
    }

    public String icao = "";
    public String name = "";
    public double lat, lon;
    /** Field elevation in meters. */
    public double elevM;
    /** Observation time, UTC millis. */
    public long obsMs;

    /**
     * What the source says the category is.
     *
     * <p><b>Published, not computed.</b> aviationweather.gov returns {@code fltCat} and it
     * is the authority; deriving it here from ceiling and visibility would eventually
     * disagree with every other aviation tool the operator looks at, over rounding or over
     * which layers count as a ceiling. Where the source gives none -- two of seventy
     * stations across California and Nevada, both reporting visibility but no cloud data
     * at all -- the answer is UNKNOWN and the plugin says so rather than guessing.
     */
    public Cat category = Cat.UNKNOWN;

    /** As reported, e.g. "10+" for ten statute miles or more. Empty when absent. */
    public String visibility = "";
    /** Lowest broken or overcast layer in feet above the field; -1 when there is none. */
    public int ceilingFt = -1;
    /** Every reported layer, for the details. */
    public final List<String> layers = new ArrayList<>();

    public int windDirDeg = -1;
    public int windKt = -1;
    /** Degrees Celsius; NaN when not reported. */
    public double tempC = Double.NaN, dewpointC = Double.NaN;
    /** Altimeter setting in hectopascals; NaN when not reported. */
    public double altimeterHpa = Double.NaN;

    /** The observation exactly as published, which is what a pilot actually reads. */
    public String raw = "";

    /** What the row and the map label say. */
    public String categoryLabel() {
        switch (category) {
            case VFR:
                return "VFR";
            case MVFR:
                return "MVFR";
            case IFR:
                return "IFR";
            case LIFR:
                return "LIFR";
            default:
                return "no ceiling reported";
        }
    }

    /** The ceiling in words, including the honest answer when nothing was reported. */
    public String ceilingLabel() {
        if (ceilingFt < 0)
            return layers.isEmpty() ? "no cloud report" : "no ceiling";
        return comma(ceilingFt) + " ft above the field";
    }

    public String windLabel() {
        if (windKt < 0)
            return "wind not reported";
        if (windKt == 0)
            return "calm";
        if (windDirDeg < 0)
            return windKt + " kt";
        return String.format(Locale.US, "%03d at %d kt", windDirDeg, windKt);
    }

    /** Temperature and dewpoint, in whole degrees both ways round. */
    public String tempLabel() {
        if (Double.isNaN(tempC))
            return "";
        final long f = Math.round(tempC * 9 / 5 + 32);
        final StringBuilder b = new StringBuilder();
        b.append(Math.round(tempC)).append(" C (").append(f).append(" F)");
        if (!Double.isNaN(dewpointC))
            b.append(", dewpoint ").append(Math.round(dewpointC)).append(" C");
        return b.toString();
    }

    /** Altimeter in inches of mercury, which is what an altimeter is set in here. */
    public String altimeterLabel() {
        if (Double.isNaN(altimeterHpa))
            return "";
        return String.format(Locale.US, "%.2f inHg", altimeterHpa / 33.8639);
    }

    static String comma(int n) {
        final String s = Integer.toString(n);
        final StringBuilder b = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            if (i > 0 && (s.length() - i) % 3 == 0)
                b.append(',');
            b.append(s.charAt(i));
        }
        return b.toString();
    }
}
