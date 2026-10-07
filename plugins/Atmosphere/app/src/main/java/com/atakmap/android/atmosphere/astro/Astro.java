package com.atakmap.android.atmosphere.astro;

import java.util.Calendar;
import java.util.TimeZone;

/**
 * Sun and moon arithmetic on the device: no network, no provider, no source
 * definition. Sunrise and sunset from the NOAA solar equations (good to about a
 * minute at mid latitudes), moon phase from the moon's elongation off a low-precision
 * lunar longitude (good to about an hour). Everything takes and returns UTC
 * milliseconds.
 */
public final class Astro {

    private Astro() {
    }

    /** Milliseconds in a day. */
    private static final double DAY_MS = 86_400_000d;
    /** Julian date of the Unix epoch. */
    private static final double JD_UNIX = 2_440_587.5;
    /** Julian date of J2000.0. */
    private static final double JD_2000 = 2_451_545.0;
    /** Mean synodic month, days; only used to express the phase as an age. */
    private static final double SYNODIC = 29.530588853;

    static double julianDate(long utcMillis) {
        return JD_UNIX + utcMillis / DAY_MS;
    }

    // ------------------------------------------------------------------ moon

    /**
     * Phase as a fraction of the cycle: 0 new, 0.25 first quarter, 0.5 full, 0.75 last
     * quarter. The moon's elongation from the sun, from the Astronomical Almanac's
     * low-precision lunar longitude (six periodic terms, about 0.3 degrees) and the
     * NOAA solar longitude. A mean synodic month would be off by up to half a day at
     * the quarters, because the moon's orbit is not circular; this is off by an hour.
     */
    public static double moonPhase(long utcMillis) {
        final double t = (julianDate(utcMillis) - JD_2000) / 36525d;
        final double moonLon = 218.32 + 481267.881 * t
                + 6.29 * sinDeg(135.0 + 477198.87 * t)
                - 1.27 * sinDeg(259.3 - 413335.36 * t)
                + 0.66 * sinDeg(235.7 + 890534.22 * t)
                + 0.21 * sinDeg(269.9 + 954397.74 * t)
                - 0.19 * sinDeg(357.5 + 35999.05 * t)
                - 0.11 * sinDeg(186.5 + 966404.03 * t);
        final double elongation = norm360(moonLon - sunTrueLongitude(t));
        return elongation / 360d;
    }

    private static double sinDeg(double deg) {
        return Math.sin(Math.toRadians(deg));
    }

    /** The sun's geometric true longitude, degrees, for Julian centuries from J2000. */
    private static double sunTrueLongitude(double t) {
        final double l0 = norm360(280.46646 + t * (36000.76983 + t * 0.0003032));
        final double m = 357.52911 + t * (35999.05029 - 0.0001537 * t);
        final double mr = Math.toRadians(m);
        final double c = Math.sin(mr) * (1.914602 - t * (0.004817 + 0.000014 * t))
                + Math.sin(2 * mr) * (0.019993 - 0.000101 * t)
                + Math.sin(3 * mr) * 0.000289;
        return l0 + c;
    }

    /** Illuminated fraction of the disc, 0 to 1. */
    public static double moonIllumination(long utcMillis) {
        return (1 - Math.cos(2 * Math.PI * moonPhase(utcMillis))) / 2;
    }

    /** Days since the last new moon. */
    public static double moonAgeDays(long utcMillis) {
        return moonPhase(utcMillis) * SYNODIC;
    }

    /** The eight names, by the conventional bands around each principal phase. */
    public static String moonPhaseName(long utcMillis) {
        final double p = moonPhase(utcMillis);
        if (p < 0.0625 || p >= 0.9375) return "New moon";
        if (p < 0.1875) return "Waxing crescent";
        if (p < 0.3125) return "First quarter";
        if (p < 0.4375) return "Waxing gibbous";
        if (p < 0.5625) return "Full moon";
        if (p < 0.6875) return "Waning gibbous";
        if (p < 0.8125) return "Last quarter";
        return "Waning crescent";
    }

    /** True while the lit side grows: new to full. */
    public static boolean moonWaxing(long utcMillis) {
        return moonPhase(utcMillis) < 0.5;
    }

    // ------------------------------------------------------------------- sun

    /** Sunrise and sunset in UTC milliseconds for the civil day containing {@code utcMillis} at the given longitude, or null when the sun does not rise or set there that day. */
    public static long[] sunRiseSet(long utcMillis, double latDeg, double lonDeg) {
        // The observer's civil day, found by shifting the clock by longitude. dayRef is
        // the 00:00 UTC that NOAA's "minutes past midnight" count from for that day; it
        // is fixed here and not re-derived from the event time, or a sunset that falls
        // after UTC midnight (every evening in the western US) moves a day late.
        final double localDays = Math.floor((utcMillis / DAY_MS) + lonDeg / 360d);
        final double dayRef = JD_UNIX + localDays;
        final double jdNoonUtc = dayRef + 0.5 - lonDeg / 360d;
        final double[] rs = noaaRiseSet(jdNoonUtc, dayRef, latDeg, lonDeg);
        if (rs == null)
            return null;
        return new long[] { (long) ((rs[0] - JD_UNIX) * DAY_MS), (long) ((rs[1] - JD_UNIX) * DAY_MS) };
    }

    /**
     * Sunrise and sunset for the calendar day that holds {@code utcMillis} on a clock
     * in {@code tz}: the day a forecast hour or "now" is filed under on the phone.
     * {@link #sunRiseSet(long, double, double)} picks its day by longitude, mean solar
     * time, which runs behind or ahead of the zone's clock by up to an hour or more.
     * At 117.6 W the clock reads 00:00 PDT while the sun's day is still 23:10 the
     * evening before, so every forecast hour of a day keyed off its first, midnight,
     * entry got the previous day's sunrise and sunset, and the trend strip drew a moon
     * over the whole of the next day. The zone's local noon is on the same day by both
     * reckonings, so it is the anchor.
     */
    public static long[] sunRiseSet(long utcMillis, TimeZone tz, double latDeg, double lonDeg) {
        final Calendar c = Calendar.getInstance(tz);
        c.setTimeInMillis(utcMillis);
        c.set(Calendar.HOUR_OF_DAY, 12);
        c.set(Calendar.MINUTE, 0);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        return sunRiseSet(c.getTimeInMillis(), latDeg, lonDeg);
    }

    /**
     * NOAA's solar position algorithm, iterated once on the event time, with the
     * standard refraction and half-disc correction (zenith 90.833 degrees).
     *
     * @return Julian dates {rise, set}, or null in polar day or night
     */
    private static double[] noaaRiseSet(double jdNoonUtc, double dayRef, double latDeg,
            double lonDeg) {
        double rise = eventJd(jdNoonUtc, dayRef, latDeg, lonDeg, true);
        double set = eventJd(jdNoonUtc, dayRef, latDeg, lonDeg, false);
        if (Double.isNaN(rise) || Double.isNaN(set))
            return null;
        // One refinement pass with the solar position at the event's own time.
        rise = eventJd(rise, dayRef, latDeg, lonDeg, true);
        set = eventJd(set, dayRef, latDeg, lonDeg, false);
        if (Double.isNaN(rise) || Double.isNaN(set))
            return null;
        return new double[] { rise, set };
    }

    private static double eventJd(double jdPos, double dayRef, double latDeg, double lonDeg,
            boolean rise) {
        final double t = (jdPos - JD_2000) / 36525d;
        final double l0 = norm360(280.46646 + t * (36000.76983 + t * 0.0003032));
        final double m = 357.52911 + t * (35999.05029 - 0.0001537 * t);
        final double mr = Math.toRadians(m);
        final double trueLon = sunTrueLongitude(t);
        final double omega = 125.04 - 1934.136 * t;
        final double lambda = trueLon - 0.00569 - 0.00478 * Math.sin(Math.toRadians(omega));
        final double e0 = 23 + (26 + (21.448 - t * (46.815 + t * (0.00059 - t * 0.001813))) / 60) / 60;
        final double eps = e0 + 0.00256 * Math.cos(Math.toRadians(omega));
        final double epsR = Math.toRadians(eps);
        final double decl = Math.asin(Math.sin(epsR) * Math.sin(Math.toRadians(lambda)));
        final double ecc = 0.016708634 - t * (0.000042037 + 0.0000001267 * t);
        final double y = Math.tan(epsR / 2) * Math.tan(epsR / 2);
        final double l0r = Math.toRadians(l0);
        final double eqTime = 4 * Math.toDegrees(y * Math.sin(2 * l0r) - 2 * ecc * Math.sin(mr)
                + 4 * ecc * y * Math.sin(mr) * Math.cos(2 * l0r)
                - 0.5 * y * y * Math.sin(4 * l0r) - 1.25 * ecc * ecc * Math.sin(2 * mr));
        final double latR = Math.toRadians(latDeg);
        final double zenith = Math.toRadians(90.833);
        final double cosHa = Math.cos(zenith) / (Math.cos(latR) * Math.cos(decl))
                - Math.tan(latR) * Math.tan(decl);
        if (cosHa < -1 || cosHa > 1)
            return Double.NaN;
        final double ha = Math.toDegrees(Math.acos(cosHa));
        // minutes past dayRef, the civil day's 00:00 UTC
        final double minutesUtc = 720 - 4 * (lonDeg + (rise ? ha : -ha)) - eqTime;
        return dayRef + minutesUtc / 1440d;
    }

    private static double norm360(double d) {
        d = d % 360;
        return d < 0 ? d + 360 : d;
    }
}
