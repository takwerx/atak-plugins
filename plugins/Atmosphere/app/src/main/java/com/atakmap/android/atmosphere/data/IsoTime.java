package com.atakmap.android.atmosphere.data;

import java.util.Calendar;
import java.util.GregorianCalendar;
import java.util.TimeZone;

/**
 * ISO-8601 timestamps as weather APIs actually emit them, parsed without
 * {@code java.time} (API 26) and without {@code SimpleDateFormat}'s {@code X} pattern
 * (API 24) — this plugin runs on ATAK's minSdk 21.
 *
 * <p>Handles {@code 2026-08-22T14:00}, {@code ...T14:00:00Z} and
 * {@code ...T14:00:00-06:00}. A bare timestamp is read as UTC, which is what a provider
 * queried with {@code timezone=UTC} returns.
 */
public final class IsoTime {

    private IsoTime() {
    }

    /** @return UTC milliseconds, or 0 when the string is not a timestamp we understand. */
    public static long parse(String iso) {
        if (iso == null)
            return 0L;
        final String s = iso.trim();
        if (s.length() < 16 || s.charAt(4) != '-' || s.charAt(7) != '-')
            return 0L;

        try {
            final int year = Integer.parseInt(s.substring(0, 4));
            final int month = Integer.parseInt(s.substring(5, 7));
            final int day = Integer.parseInt(s.substring(8, 10));
            final int hour = Integer.parseInt(s.substring(11, 13));
            final int minute = Integer.parseInt(s.substring(14, 16));

            int second = 0;
            if (s.length() >= 19 && s.charAt(16) == ':')
                second = Integer.parseInt(s.substring(17, 19));

            final Calendar cal = new GregorianCalendar(TimeZone.getTimeZone("UTC"));
            cal.clear();
            cal.set(year, month - 1, day, hour, minute, second);

            return cal.getTimeInMillis() - offsetMillis(s);
        } catch (NumberFormatException | IndexOutOfBoundsException e) {
            return 0L;
        }
    }

    /**
     * An ISO-8601 duration in whole hours: {@code PT1H}, {@code PT12H}, {@code P1D},
     * {@code P1DT6H}. The NWS grid writes every value's span this way after a slash.
     *
     * @return the hours, at least 1; 1 when the string is not a duration we understand,
     *         because a span the feed did write is worth one hour, never none.
     */
    public static int durationHours(String iso) {
        if (iso == null)
            return 1;
        final String s = iso.trim().toUpperCase(java.util.Locale.US);
        if (!s.startsWith("P"))
            return 1;
        int hours = 0;
        boolean time = false;
        int number = -1;
        for (int i = 1; i < s.length(); i++) {
            final char c = s.charAt(i);
            if (c == 'T') {
                time = true;
            } else if (c >= '0' && c <= '9') {
                number = (number < 0 ? 0 : number * 10) + (c - '0');
            } else {
                if (number < 0)
                    return 1;
                if (c == 'D' && !time)
                    hours += number * 24;
                else if (c == 'H' && time)
                    hours += number;
                else if (c == 'W' && !time)
                    hours += number * 24 * 7;
                // minutes and seconds are below the hour and the grid never uses them
                number = -1;
            }
        }
        return Math.max(1, hours);
    }

    /** {@code 2026-09-26T13:00:00Z} for a UTC instant on the hour. */
    public static String formatHourUtc(long millis) {
        final Calendar cal = new GregorianCalendar(TimeZone.getTimeZone("UTC"));
        cal.setTimeInMillis(millis);
        return String.format(java.util.Locale.US, "%04d-%02d-%02dT%02d:00:00Z",
                cal.get(Calendar.YEAR), cal.get(Calendar.MONTH) + 1,
                cal.get(Calendar.DAY_OF_MONTH), cal.get(Calendar.HOUR_OF_DAY));
    }

    /** The trailing zone offset in milliseconds; 0 for UTC or none. */
    private static long offsetMillis(String s) {
        final int timePart = s.indexOf('T');
        if (timePart < 0)
            return 0L;

        for (int i = s.length() - 1; i > timePart; i--) {
            final char c = s.charAt(i);
            if (c == 'Z' || c == 'z')
                return 0L;
            if (c == '+' || c == '-') {
                final String off = s.substring(i + 1).replace(":", "");
                if (off.length() < 2)
                    return 0L;
                final int hours = Integer.parseInt(off.substring(0, 2));
                final int minutes = off.length() >= 4
                        ? Integer.parseInt(off.substring(2, 4)) : 0;
                final long magnitude = (hours * 60L + minutes) * 60000L;
                return c == '+' ? magnitude : -magnitude;
            }
            if (c == ':' || Character.isDigit(c))
                continue;
            return 0L;
        }
        return 0L;
    }
}
