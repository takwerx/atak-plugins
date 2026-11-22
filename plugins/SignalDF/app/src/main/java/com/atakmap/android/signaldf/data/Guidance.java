package com.atakmap.android.signaldf.data;

import java.util.Locale;

/**
 * How far along a search is, and the one sentence to say about it.
 *
 * <p>This exists so the map and the pane cannot disagree. The band on the map,
 * the notification and the status line in the pane are three views of one
 * fact -- how much angle the bearings span -- and when each of them carried
 * its own copy of the thresholds they drifted apart within a day: the band
 * removed itself at one number while the pane still read "keep driving".
 *
 * <p><b>The number is not ours.</b> Radio-telemetry practice calls it
 * <i>angle width</i>, the angle between the outermost bearings, which for one
 * receiver driving round an emitter is the arc it has travelled round it.
 * Haskell and Ballard (2007) found a strong quadratic effect of angle width
 * and recommended 90-100 degrees to maximize accuracy; Bauder and Barnhart
 * (2014) report a median of 105 degrees achieved in the field from three or
 * four bearings. {@link BearingFix.Fix#spreadDeg} computes exactly that, so
 * the guidance is the published measure rather than a proxy for it.
 */
public final class Guidance {

    /**
     * The angle the bearings should end up spanning, degrees.
     *
     * <p>Radio-telemetry practice calls this <b>angle width</b>: the angle
     * between the outermost bearings, which for one receiver driving round an
     * emitter is the arc it has travelled round it. Haskell and Ballard (2007)
     * found a strong quadratic effect of angle width and recommended 90-100
     * degrees to maximize accuracy; Bauder and Barnhart (2014) report a median
     * of 105 achieved in the field. Ninety is the floor of that range.
     */
    public static final double TARGET_SPREAD_DEG = 90.0;

    /** Kept for callers that read the optimum by its other name. */
    public static final double GOOD_SPREAD_DEG = TARGET_SPREAD_DEG;

    /**
     * Where a search has got to, in the terms the manuals use.
     *
     * <p>FM 24-18 sets these and they are not ours to reinterpret: one azimuth
     * "gives a general indication of direction"; two is a <b>cut</b> and
     * "gives a general indication of distance"; and "three or more bearings is
     * a <b>fix</b> and gives a general location".
     * {@link BearingFix#MIN_BEARINGS} is that three.
     *
     * <p>An earlier version of this file gated the fix on a crossing angle of
     * thirty degrees. That number is common in positioning and
     * dilution-of-precision work and it is <em>not</em> in Army or Marine DF
     * doctrine -- it was ours, dressed as practice. Doctrine says three
     * bearings make a fix, so three bearings make a fix here, and how good
     * that fix is gets reported rather than used to withhold it.
     */
    public enum Stage {
        /** Not yet three bearings: a direction, or a cut. */
        HUNTING,
        /** Three or more. A fix, per FM 24-18, and still worth tightening. */
        FIRST_FIX,
        /** A fix whose angle width has reached the recommended range. */
        GOOD
    }

    public static Stage stage(double spreadDeg, boolean haveFix) {
        if (!haveFix)
            return Stage.HUNTING;
        return isOptimal(spreadDeg) ? Stage.GOOD : Stage.FIRST_FIX;
    }

    /** True once the angle width has reached the recommended range. */
    public static boolean isOptimal(double spreadDeg) {
        return !Double.isNaN(spreadDeg) && spreadDeg >= TARGET_SPREAD_DEG;
    }

    /** How much more angle width is wanted; zero once there is enough. */
    public static double toGoDeg(double spreadDeg) {
        if (Double.isNaN(spreadDeg))
            return TARGET_SPREAD_DEG;
        return Math.max(0.0, TARGET_SPREAD_DEG - spreadDeg);
    }

    /**
     * What the pane says, one fact to a line, in the manuals' vocabulary.
     *
     * <p>A fix is announced as soon as there is one, because that is what the
     * word means. Its angle width is reported beside it so the operator can
     * see how much of the recommended range they have, and the band says what
     * to do about the rest.
     */
    public static String line(Stage stage, double spreadDeg) {
        switch (stage) {
            case GOOD:
                return String.format(Locale.US,
                        "Fix. Angle width %.0f degrees -- in the 90-100 that "
                                + "gives the best accuracy.", spreadDeg);
            case FIRST_FIX:
                return String.format(Locale.US,
                        "Fix from three or more bearings."
                                + "\nAngle width %.0f of the 90 wanted. Drive "
                                + "into the orange band.", spreadDeg);
            default:
                return "No fix yet. One bearing gives direction and two give a "
                        + "cut; three from three places make a fix.";
        }
    }

    private Guidance() {
    }
}
