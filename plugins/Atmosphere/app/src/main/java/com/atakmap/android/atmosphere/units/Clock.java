package com.atakmap.android.atmosphere.units;

import android.content.Context;

import java.text.SimpleDateFormat;
import java.util.Locale;

/**
 * Times of day on the device's own 12- or 24-hour clock. Every time the plugin
 * shows goes through here, written in the 12-hour form ("h:mm a", "ha"), and turns
 * into the 24-hour form ("HH:mm", "HH") when the device is set to it (operator,
 * 2026-11-15: "follow device settings"). Read again each time the pane renders, so
 * a change in Android's settings shows up without a restart.
 */
public final class Clock {

    private static volatile boolean twentyFour;

    private Clock() {
    }

    /** Read the device's setting; call when the pane renders. */
    public static void refresh(Context context) {
        if (context == null)
            return;
        try {
            twentyFour = android.text.format.DateFormat.is24HourFormat(context);
        } catch (RuntimeException e) {
            // keep the last answer
        }
    }

    public static boolean is24() {
        return twentyFour;
    }

    /** The 12-hour pattern as written, or its 24-hour form when the device uses one. */
    public static String pattern(String twelveHour) {
        if (!twentyFour)
            return twelveHour;
        return twelveHour.replace("h:mm a", "HH:mm").replace("h:mm", "HH:mm")
                .replace("h a", "HH:00").replace("ha", "HH");
    }

    public static SimpleDateFormat format(String twelveHour) {
        return new SimpleDateFormat(pattern(twelveHour), Locale.US);
    }
}
