package com.atakmap.android.firecast.model;

import com.atakmap.android.firecast.plugin.PluginNativeLoader;
import com.atakmap.coremap.log.Log;

/**
 * The Fire Lab's behave library, surface fire module, over JNI.
 *
 * <p>This is the engine inside FlamMap and Behave7: Rothermel's surface spread with
 * Albini's wind adjustment and the fire ellipse, public domain, vendored verbatim under
 * {@code src/main/cpp/behave}. Its own 215 tests pass on the phone (2026-10-11). Nothing
 * fire-behavior is computed in Java; this class only carries numbers across.
 */
public final class Behave {

    private static final String TAG = "FireCast";
    private static boolean loaded;

    private Behave() {
    }

    /** True when the native library is in; false means the model is unavailable, not broken. */
    public static synchronized boolean load() {
        if (loaded)
            return true;
        try {
            PluginNativeLoader.loadLibrary("firecast");
            loaded = true;
        } catch (Throwable t) {
            Log.w(TAG, "fire model library did not load: " + t);
        }
        return loaded;
    }

    /** One surface fire run's outputs, in the library's own units. */
    public static final class Result {
        /** Head fire spread rate, feet per minute. */
        public final double rosFtPerMin;
        /** Where the head goes, degrees clockwise from north. */
        public final double headingDeg;
        public final double lengthToWidth;
        public final double flameLengthFt;
        /** BTU per foot per second. */
        public final double firelineIntensity;
        /** Ellipse semi-minor, semi-major and center-to-focus, feet per minute of spread. */
        public final double aFtPerMin, bFtPerMin, cFtPerMin;
        public final double backingFtPerMin, flankingFtPerMin;
        public final double midflameWindMph;

        Result(double[] v) {
            rosFtPerMin = v[0];
            headingDeg = v[1];
            lengthToWidth = v[2];
            flameLengthFt = v[3];
            firelineIntensity = v[4];
            aFtPerMin = v[5];
            bFtPerMin = v[6];
            cFtPerMin = v[7];
            backingFtPerMin = v[8];
            flankingFtPerMin = v[9];
            midflameWindMph = v[10];
        }
    }

    /**
     * @param fuelModel  LANDFIRE's FBFM40 (or FBFM13) code
     * @param windMph    20 ft wind, miles per hour
     * @param windFromDeg degrees the wind comes from, clockwise from north
     * @return the run, or null when the library is missing or refused the inputs
     */
    public static Result surface(int fuelModel, double m1, double m10, double m100,
            double mHerb, double mWoody, double windMph, double windFromDeg,
            double slopeDeg, double aspectDeg, double coverPct, double heightFt, double crownRatio) {
        if (!load() || !validFuelModel(fuelModel))
            return null;
        final double[] v = surfaceRun(fuelModel, m1, m10, m100, mHerb, mWoody,
                windMph, windFromDeg, slopeDeg, aspectDeg, coverPct, heightFt, crownRatio);
        return v == null || v.length < 11 ? null : new Result(v);
    }

    /** "SH2 Moderate load, dry climate shrub (S)", or "" for a code the library has no model for. */
    public static String name(int fuelModel) {
        if (!load() || !validFuelModel(fuelModel))
            return "";
        try {
            final String s = fuelName(fuelModel);
            return s == null ? "" : s;
        } catch (Throwable t) {
            return "";
        }
    }

    /**
     * The library's fuel model table has 256 slots and its own getters index it
     * unchecked, so a code off a LANDFIRE pixel (any int, 2147483647 included) is
     * refused here before it can reach native code (security review, 2026-10-11).
     */
    static boolean validFuelModel(int fuelModel) {
        return fuelModel > 0 && fuelModel < 256;
    }

    private static native double[] surfaceRun(int fuelModel, double m1, double m10, double m100,
            double mHerb, double mWoody, double windMph, double windFrom, double slopeDeg,
            double aspectDeg, double coverPct, double heightFt, double crownRatio);

    private static native String fuelName(int fuelModel);
}
