package com.atakmap.android.firecast.model;

import com.atakmap.android.firecast.plugin.PluginNativeLoader;
import com.atakmap.coremap.log.Log;

/**
 * The Fire Lab's behave library, surface and crown fire modules, over JNI.
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
     * @param windMph    wind, miles per hour, at 20 ft or at 10 m
     * @param tenMeter   true when the wind was measured at 10 m (NWS networks), false for 20 ft (RAWS)
     * @param windFromDeg degrees the wind comes from, clockwise from north
     * @return the run, or null when the library is missing or refused the inputs
     */
    public static Result surface(int fuelModel, double m1, double m10, double m100,
            double mHerb, double mWoody, double windMph, boolean tenMeter, double windFromDeg,
            double slopeDeg, double aspectDeg, double coverPct, double heightFt, double crownRatio) {
        if (!load() || !validFuelModel(fuelModel))
            return null;
        final double[] v = surfaceRun(fuelModel, m1, m10, m100, mHerb, mWoody,
                windMph, tenMeter ? 1 : 0, windFromDeg, slopeDeg, aspectDeg, coverPct, heightFt, crownRatio);
        return v == null || v.length < 11 ? null : new Result(v);
    }

    /** What kind of fire a crown run found. */
    public static final int SURFACE = 0, TORCHING = 1, CONDITIONAL = 2, CROWNING = 3;

    /** One crown run's outputs: the final fire, whatever kind it is, in the library's units. */
    public static final class Crown {
        public final double rosFtPerMin, headingDeg, aFtPerMin, bFtPerMin, cFtPerMin, flameLengthFt;
        /** {@link #SURFACE}, {@link #TORCHING}, {@link #CONDITIONAL} or {@link #CROWNING}. */
        public final int fireType;
        public final double crownFraction, firelineIntensity;
        /** Albini's ember reach, meters: crown firebrands when it crowns, the surface model otherwise. */
        public final double spotM;

        Crown(double[] v) {
            rosFtPerMin = v[0];
            headingDeg = v[1];
            aFtPerMin = v[2];
            bFtPerMin = v[3];
            cFtPerMin = v[4];
            flameLengthFt = v[5];
            fireType = (int) Math.round(v[6]);
            crownFraction = v[7];
            firelineIntensity = v[8];
            spotM = v[9];
        }
    }

    /**
     * The surface fire with the canopy over it (Scott and Reinhardt 2001, as FlamMap and
     * Behave7 run it): whether it stays on the ground, torches or crowns, and the fire
     * that results. Only for ground with a canopy; open ground is {@link #surface}.
     *
     * @param mFoliar   foliar moisture, percent (100 is the usual figure)
     * @param heightFt  canopy height; {@code baseFt} canopy base height
     * @param density   canopy bulk density, kg/m3
     * @return the run, or null when the library is missing or refused the inputs
     */
    public static Crown crown(int fuelModel, double m1, double m10, double m100, double mHerb, double mWoody,
            double mFoliar, double windMph, boolean tenMeter, double windFromDeg, double slopeDeg, double aspectDeg,
            double coverPct, double heightFt, double baseFt, double density) {
        if (!load() || !validFuelModel(fuelModel))
            return null;
        final double[] v = crownRun(fuelModel, m1, m10, m100, mHerb, mWoody, mFoliar, windMph, tenMeter ? 1 : 0,
                windFromDeg, slopeDeg, aspectDeg, coverPct, heightFt, baseFt, density);
        return v == null || v.length < 10 ? null : new Crown(v);
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

    /** Albini's maximum spot distance from a surface fire, meters; 0 with no wind or no flame. */
    public static double spotDistanceM(double flameLengthFt, double windMph20ft, double coverHeightFt) {
        if (!load())
            return 0;
        try {
            return spotDistance(flameLengthFt, windMph20ft, coverHeightFt);
        } catch (Throwable t) {
            return 0;
        }
    }

    private static native double spotDistance(double flameLengthFt, double windMph, double coverHeightFt);

    private static native double[] surfaceRun(int fuelModel, double m1, double m10, double m100,
            double mHerb, double mWoody, double windMph, int windHeight, double windFrom, double slopeDeg,
            double aspectDeg, double coverPct, double heightFt, double crownRatio);

    private static native String fuelName(int fuelModel);

    private static native double[] crownRun(int fuelModel, double m1, double m10, double m100, double mHerb,
            double mWoody, double mFoliar, double windMph, int windHeight, double windFrom, double slopeDeg,
            double aspectDeg, double coverPct, double heightFt, double baseFt, double density);
}
