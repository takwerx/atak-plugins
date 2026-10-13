package com.atakmap.android.signaldf.data;

/**
 * Angle arithmetic, in degrees, kept in one place so that a bearing is wrapped
 * the same way everywhere.
 *
 * <p>Nothing here knows about the map or the radio; it is deliberately pure so
 * that the bearing math is tested off the phone.
 */
public final class Angles {

    private Angles() {
    }

    /** Wraps any angle into [0, 360). NaN and infinities are passed through. */
    public static double norm360(double deg) {
        if (Double.isNaN(deg) || Double.isInfinite(deg))
            return deg;
        double d = deg % 360.0;
        if (d < 0.0)
            d += 360.0;
        // -0.0 % 360.0 is -0.0, and a bearing of -0.0 formats as "-0".
        return d == 0.0 ? 0.0 : d;
    }

    /**
     * Signed difference b - a, wrapped into (-180, 180]. Used for residuals:
     * how far one bearing is from another, taking the short way round.
     */
    public static double diff180(double a, double b) {
        double d = norm360(b - a);
        return d > 180.0 ? d - 360.0 : d;
    }
}
