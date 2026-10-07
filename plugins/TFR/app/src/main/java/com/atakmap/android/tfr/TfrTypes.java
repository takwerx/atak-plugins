package com.atakmap.android.tfr;

import java.util.Locale;

/**
 * The FAA's type strings, in words an operator reads.
 *
 * <p>"AIR SHOWS/SPORTS" and "UAS PUBLIC GATHERING" are how the feed writes them and not
 * how anybody says them. The label is also the feature set's name, so it is what Overlay
 * Manager lists.
 */
public final class TfrTypes {

    private TfrTypes() {
    }

    public static String label(String raw) {
        if (raw == null || raw.trim().isEmpty())
            return "Other";
        final String r = raw.trim().toUpperCase(Locale.US);
        if ("SECURITY".equals(r))
            return "Security";
        if ("HAZARDS".equals(r))
            return "Hazards";
        if ("AIR SHOWS/SPORTS".equals(r))
            return "Air shows and sports";
        if ("UAS PUBLIC GATHERING".equals(r))
            return "Public gatherings";
        if ("SPACE OPERATIONS".equals(r))
            return "Space operations";
        if ("VIP".equals(r))
            return "VIP movement";
        if ("SPECIAL".equals(r))
            return "Special";
        final String s = r.toLowerCase(Locale.US).replace('/', ' ');
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}
