package com.atakmap.android.firecast;

import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

/**
 * Where the detections come from: NIFC's public ArcGIS organization, no key, no
 * sign-in. The same organization the Feature Layer plugin already reads WFIGS and NIFS
 * from, so this is a service address away from what is already proven on hardware.
 *
 * <p>NASA FIRMS' own API is the faster path to the same underlying NASA and NOAA data
 * and is deliberately not used: it requires a per-user MAP_KEY, and a key belongs in a
 * catalog or a proxy, never compiled into an APK that anyone can pull apart.
 */
public final class Sources {

    private Sources() {
    }

    private static final String ORG =
            "https://services3.arcgis.com/T4QMspbfLg3qTGWY/arcgis/rest/services/";

    public static final String VIIRS = ORG + "VIIRS_Heat_Detections/FeatureServer/0";
    public static final String MODIS = ORG + "Modis_Heat_Detections/FeatureServer/0";
    public static final String GOES = ORG + "GOES_Heat_Detections/FeatureServer/0";
    public static final String RADR = ORG + "RADR_Heat_Detection_Last_14_Days_View/FeatureServer/0";
    public static final String INDUSTRIAL_MASK =
            ORG + "StaticIndustrialMaskforHeatDetects_2025/FeatureServer/0";

    /** Every feed the plugin reads, in the order the filters list them. */
    public static List<DetectionSource> all() {
        return Arrays.asList(PolarSource.viirs(), PolarSource.modis(),
                new NgfsSource(), new RadrSource(), new FedsSource());
    }

    /**
     * A moment as an ArcGIS timestamp literal. UTC, always: the services store UTC, and
     * a where clause built in the phone's own zone quietly shifts the window by the
     * offset - eight hours of missing detections on a California phone, which looks
     * like a quiet fire day rather than a bug.
     */
    public static String utc(long epochMillis) {
        final SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US);
        f.setTimeZone(TimeZone.getTimeZone("UTC"));
        return f.format(new Date(epochMillis));
    }

    /** "2026-10-30T12:00:00", UTC with or without a zone after it; 0 when unreadable. */
    public static long parseIso(String t) {
        if (t == null || t.length() < 19)
            return 0;
        try {
            final SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US);
            f.setTimeZone(TimeZone.getTimeZone("UTC"));
            return f.parse(t.substring(0, 19)).getTime();
        } catch (Exception e) {
            return 0;
        }
    }
}
