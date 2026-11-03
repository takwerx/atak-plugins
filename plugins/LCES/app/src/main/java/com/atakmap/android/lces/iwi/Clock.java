package com.atakmap.android.lces.iwi;


import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Times the way the fireline says them: 24-hour, "14:45" (operator, 2026-10-14), on
 * the phone's local time, whatever the phone's own 12-hour setting is.
 */
final class Clock {

    private Clock() {
    }

    static String hhmm(long ms) {
        return new SimpleDateFormat("HH:mm", Locale.US).format(new Date(ms));
    }
}
