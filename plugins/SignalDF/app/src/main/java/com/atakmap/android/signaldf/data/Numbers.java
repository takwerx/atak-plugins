package com.atakmap.android.signaldf.data;

/**
 * Lenient scalar parsing for the KrakenSDR's wire formats.
 *
 * <p>The radio is inconsistent about types on purpose: the WebSocket JSON
 * {@code str()}-formats latitude, longitude, bearing, confidence, power and
 * heading but leaves frequency, latency and timestamps numeric, and the CSV and
 * XML are text throughout. Nothing here is worth throwing over -- a frame with
 * one unreadable field is still a usable bearing -- so every parse takes a
 * fallback and the caller decides what a missing value means.
 */
public final class Numbers {

    private Numbers() {
    }

    /** Parses a double, returning {@code fallback} for null, blank or garbage. */
    public static double d(String s, double fallback) {
        if (s == null)
            return fallback;
        String t = s.trim();
        if (t.isEmpty())
            return fallback;
        try {
            return Double.parseDouble(t);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    /** Parses an int, tolerating a value written as "3" or "3.0". */
    public static int i(String s, int fallback) {
        double v = d(s, Double.NaN);
        if (Double.isNaN(v) || Double.isInfinite(v))
            return fallback;
        return (int) Math.round(v);
    }

    /**
     * Truthiness, across the several ways the radio writes a flag: "1", "true",
     * "True" (Python's {@code str(True)}), "yes".
     */
    public static boolean bool(String s, boolean fallback) {
        if (s == null)
            return fallback;
        String t = s.trim();
        if (t.isEmpty())
            return fallback;
        if (t.equalsIgnoreCase("true") || t.equalsIgnoreCase("yes"))
            return true;
        if (t.equalsIgnoreCase("false") || t.equalsIgnoreCase("no"))
            return false;
        double v = d(t, Double.NaN);
        return Double.isNaN(v) ? fallback : v != 0.0;
    }

    /**
     * Normalizes a timestamp to milliseconds since the epoch.
     *
     * <p>The Kraken writes its frame time in milliseconds and its GPS time in
     * seconds, and neither is labeled. Anything below 1e11 -- which as
     * milliseconds would be March 1973, and as seconds is any time up to the
     * year 5138 -- is read as seconds. Returns 0 for anything unparseable, and
     * 0 means "no time", never 1970.
     */
    public static long epochMs(String s) {
        double v = d(s, Double.NaN);
        if (Double.isNaN(v) || Double.isInfinite(v) || v <= 0.0)
            return 0L;
        return v < 1.0e11 ? Math.round(v * 1000.0) : Math.round(v);
    }
}
