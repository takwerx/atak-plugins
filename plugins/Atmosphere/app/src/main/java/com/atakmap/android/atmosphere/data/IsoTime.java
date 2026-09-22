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
