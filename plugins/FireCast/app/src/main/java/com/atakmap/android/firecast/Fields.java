package com.atakmap.android.firecast;

import org.json.JSONObject;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reading the NIFC heat detection services' fields without being thrown by them.
 *
 * <p>The numbers that matter most on these services are not numbers. VIIRS and MODIS
 * serve {@code Scan}, {@code Track}, {@code FRP} and {@code Brightness} as
 * {@code String(200)} with the unit baked in, and several of them with a leading
 * space, exactly as measured on 2026-09-11:
 *
 * <pre>
 *   Scan:            "0.4 km"          Track:      "0.4 km"
 *   FRP:             "7.39 MW"         Brightness: " 329.76 K"
 *   DetectionSource: " VIIRS-NOAA-20"  Sensor:     "NOAA-20 VIIRS"
 * </pre>
 *
 * <p>{@code Double.parseDouble} throws on every one of those, and
 * {@code "VIIRS-NOAA-20".equals(source)} is false for a value that only differs by the
 * space in front of it. Both mistakes are silent in the wrong direction: a thrown parse
 * loses the footprint that is the whole point of the plugin, and a failed string compare
 * loses a whole satellite from a filter. Everything read from a service goes through
 * here, and every accessor returns null rather than throwing, so one bad record costs
 * that record and nothing else.
 *
 * <p>The same services also serve genuine JSON numbers (all of GOES), so each accessor
 * takes either without the caller having to know which.
 *
 * <p>The parsing itself is in the {@code parse*} methods, which touch no Android or
 * JSON type at all, so they can be exercised on their own the day this repository has a
 * working unit test harness - the template's {@code ExampleTest} does not compile in any
 * of our plugins, junit having never been on the classpath.
 */
public final class Fields {

    private Fields() {
    }

    /** The first number in a value, whatever sits around it. */
    private static final Pattern NUMBER = Pattern.compile("[-+]?(?:\\d+\\.?\\d*|\\.\\d+)(?:[eE][-+]?\\d+)?");

    // ---- the parsing, free of JSON and of Android --------------------------------

    /** A trimmed value, or null when it is empty or one of the several spellings of absent. */
    public static String clean(String raw) {
        if (raw == null)
            return null;
        final String s = raw.trim();
        if (s.isEmpty() || "null".equalsIgnoreCase(s) || "<null>".equalsIgnoreCase(s)
                || "nan".equalsIgnoreCase(s) || "none".equalsIgnoreCase(s))
            return null;
        return s;
    }

    /** The number in a piece of text, ignoring any unit or padding around it. */
    public static Double parseNumber(String raw) {
        final String s = clean(raw);
        if (s == null)
            return null;
        final Matcher m = NUMBER.matcher(s.replace(",", ""));
        if (!m.find())
            return null;
        try {
            final double d = Double.parseDouble(m.group());
            return Double.isNaN(d) || Double.isInfinite(d) ? null : d;
        } catch (NumberFormatException impossible) {
            return null;
        }
    }

    /**
     * A distance in meters from text that carries its own unit ("0.4 km", "375 m"). A
     * bare number is read in {@code bareUnitMeters} - the caller says what the service
     * means by an unlabeled value rather than this guessing at it.
     */
    public static Double parseDistanceMeters(String raw, double bareUnitMeters) {
        final Double n = parseNumber(raw);
        if (n == null)
            return null;
        final double meters = n * unitFactor(unitOf(clean(raw)), bareUnitMeters);
        // A negative or absurd footprint is a broken record, not a small fire.
        return meters > 0 && meters < 500000 ? meters : null;
    }

    private static double unitFactor(String unit, double bareUnitMeters) {
        if (unit == null)
            return bareUnitMeters;
        if (unit.startsWith("km"))
            return 1000d;
        if (unit.startsWith("nm"))
            return 1852d;
        if (unit.startsWith("mi"))
            return 1609.344d;
        if (unit.startsWith("ft"))
            return 0.3048d;
        if (unit.startsWith("m"))
            return 1d;
        return bareUnitMeters;
    }

    /** What follows the number: "km" out of "0.4 km", null when nothing does. */
    private static String unitOf(String s) {
        if (s == null)
            return null;
        final String flat = s.replace(",", "");
        final Matcher m = NUMBER.matcher(flat);
        if (!m.find())
            return null;
        final String rest = flat.substring(m.end()).trim().toLowerCase(Locale.US);
        return rest.isEmpty() ? null : rest;
    }

    /**
     * An epoch-millisecond value. Zero for anything that is not a usable time, which
     * includes the null dates these feeds carry: VIIRS holds a record stamped 1789, and
     * a negative epoch read as an age makes a detection look 237 years in the future.
     */
    public static long parseEpochMillis(Double n) {
        if (n == null)
            return 0L;
        final long ms = n.longValue();
        if (ms <= 0)
            return 0L;
        // A service has been seen serving seconds where its schema says a date; anything
        // before 1990 expressed in milliseconds is really seconds.
        return ms < 631152000000L ? ms * 1000L : ms;
    }

    /**
     * Two values compared the way these services actually serve them: trimmed and case
     * folded. {@code " VIIRS-NOAA-20"} and {@code "VIIRS-NOAA-20"} are the same value.
     */
    public static boolean is(String value, String expected) {
        return value != null && expected != null && value.trim().equalsIgnoreCase(expected.trim());
    }

    // ---- the JSON accessors, which are only these wrapped ------------------------

    public static String text(JSONObject o, String key) {
        if (o == null || o.isNull(key))
            return null;
        final Object v = o.opt(key);
        return v == null ? null : clean(String.valueOf(v));
    }

    public static Double number(JSONObject o, String key) {
        if (o == null || o.isNull(key))
            return null;
        final Object v = o.opt(key);
        if (v instanceof Number) {
            final double d = ((Number) v).doubleValue();
            return Double.isNaN(d) || Double.isInfinite(d) ? null : d;
        }
        return parseNumber(v == null ? null : String.valueOf(v));
    }

    public static Double distanceMeters(JSONObject o, String key, double bareUnitMeters) {
        if (o == null || o.isNull(key))
            return null;
        final Object v = o.opt(key);
        if (v instanceof Number)
            return parseDistanceMeters(String.valueOf(v), bareUnitMeters);
        return parseDistanceMeters(v == null ? null : String.valueOf(v), bareUnitMeters);
    }

    public static long epochMillis(JSONObject o, String key) {
        return parseEpochMillis(number(o, key));
    }

    public static Integer integer(JSONObject o, String key) {
        final Double n = number(o, key);
        return n == null ? null : (int) Math.round(n);
    }
}
