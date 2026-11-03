package com.atakmap.android.lces.iwi;


import java.util.Locale;

/**
 * Coordinates the way the ICS 206 WF asks for them: WGS 84, degrees and decimal
 * minutes, "33° 34.580' N, 117° 14.438' W".
 *
 * <p>Settled in PLAN-IWI: DDM everywhere in IWI, on screen and in the radio script,
 * because the number on the screen has to be the number said on the radio. No ATAK
 * types, so it runs on a desktop JVM.
 */
public final class Coords {

    private Coords() {
    }

    public static String ddm(double lat, double lon) {
        return part(lat, 'N', 'S') + ", " + part(lon, 'E', 'W');
    }

    static String part(double v, char positive, char negative) {
        final char hemi = v >= 0 ? positive : negative;
        final double a = Math.abs(v);
        int deg = (int) Math.floor(a);
        String min = String.format(Locale.US, "%06.3f", (a - deg) * 60.0);
        // 59.9996 minutes rounds to 60.000, which is the next whole degree.
        if (min.startsWith("60")) {
            deg += 1;
            min = "00.000";
        }
        return deg + "° " + min + "' " + hemi;
    }
}
