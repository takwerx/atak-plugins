package com.atakmap.android.atmosphere.astro;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.TimeZone;

/**
 * A day's light for one place, computed on the device: the sun's twilight bands,
 * sunrise and sunset with their azimuths, moonrise and moonset, when the moon is up,
 * and the moon's next principal phases.
 *
 * <p>Asked for by a user who plans incidents around light and pastes a light bar into
 * worksheets and IAPs (2026-11-15). Nothing is fetched: the sun from the NOAA solar
 * equations, the moon from the main terms of Meeus' lunar theory (Astronomical
 * Algorithms ch. 47, the largest terms of tables 47.A and 47.B), good to about a
 * minute for rise and set at mid latitudes.
 *
 * <p>Events are found by sampling the altitude across the local day every
 * {@link #STEP_MS} and bisecting each crossing, so a day with no sunset (polar day),
 * two moonrises or none is answered as it is, never forced into one rise and one set.
 */
public final class Light {

    private Light() {
    }

    private static final double DAY_MS = 86_400_000d;
    private static final double JD_UNIX = 2_440_587.5;
    private static final double JD_2000 = 2_451_545.0;
    private static final long STEP_MS = 10L * 60_000L;

    /** Sun altitudes, degrees, for the edges of the bands. */
    public static final double SUN_HORIZON = -0.833;
    public static final double CIVIL = -6;
    public static final double NAUTICAL = -12;
    public static final double ASTRONOMICAL = -18;

    /** Bands, darkest first. */
    public static final int NIGHT = 0, ASTRO = 1, NAUT = 2, CIVIL_BAND = 3, DAY = 4;

    /** One event: a time and, for rise and set, the azimuth in degrees true. */
    public static final class Event {
        public final long time;
        public final double azimuth;

        Event(long time, double azimuth) {
            this.time = time;
            this.azimuth = azimuth;
        }
    }

    /** A day's light. Times are UTC milliseconds; a list is empty when it does not happen. */
    public static final class Day {
        public long start, end;
        /** Band per {@link #SLOT_MS} slot from {@link #start}. */
        public int[] bands;
        public final List<Event> astroDawn = new ArrayList<>(), nauticalDawn = new ArrayList<>(),
                civilDawn = new ArrayList<>(), sunrise = new ArrayList<>(),
                sunset = new ArrayList<>(), civilDusk = new ArrayList<>(),
                nauticalDusk = new ArrayList<>(), astroDusk = new ArrayList<>(),
                moonrise = new ArrayList<>(), moonset = new ArrayList<>();
        /** Pairs {from, to}: the moon above the horizon within the day. */
        public final List<long[]> moonUp = new ArrayList<>();
        /** Minutes the sun is up; the band rule, so polar day is 1440 and night 0. */
        public int daylightMinutes;
        /** True when the sun stays up, or down, the whole day. */
        public boolean sunAlwaysUp, sunAlwaysDown;
    }

    /** Width of one band slot. */
    public static final long SLOT_MS = 5L * 60_000L;

    /**
     * The light of the local calendar day in {@code tz} that holds {@code utcMillis}.
     * A day with a clock change is 23 or 25 hours, and is drawn as it is.
     */
    public static Day day(long utcMillis, TimeZone tz, double lat, double lon) {
        final Calendar c = Calendar.getInstance(tz);
        c.setTimeInMillis(utcMillis);
        c.set(Calendar.HOUR_OF_DAY, 0);
        c.set(Calendar.MINUTE, 0);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        final Day d = new Day();
        d.start = c.getTimeInMillis();
        c.add(Calendar.DAY_OF_MONTH, 1);
        d.end = c.getTimeInMillis();

        final int slots = (int) ((d.end - d.start) / SLOT_MS);
        d.bands = new int[slots];
        int dayMinutes = 0;
        boolean anyDown = false, anyUp = false;
        for (int i = 0; i < slots; i++) {
            final double h = sunAltitude(d.start + i * SLOT_MS + SLOT_MS / 2, lat, lon);
            d.bands[i] = band(h);
            if (h >= SUN_HORIZON) {
                dayMinutes += SLOT_MS / 60_000L;
                anyUp = true;
            } else {
                anyDown = true;
            }
        }
        d.sunAlwaysUp = !anyDown;
        d.sunAlwaysDown = !anyUp;

        crossings(d, lat, lon, false, ASTRONOMICAL, d.astroDawn, d.astroDusk);
        crossings(d, lat, lon, false, NAUTICAL, d.nauticalDawn, d.nauticalDusk);
        crossings(d, lat, lon, false, CIVIL, d.civilDawn, d.civilDusk);
        crossings(d, lat, lon, false, SUN_HORIZON, d.sunrise, d.sunset);
        crossings(d, lat, lon, true, Double.NaN, d.moonrise, d.moonset);

        // Daylight from the events themselves, not the 5-minute slots.
        if (d.sunAlwaysUp)
            d.daylightMinutes = (int) ((d.end - d.start) / 60_000L);
        else if (d.sunAlwaysDown)
            d.daylightMinutes = 0;
        else
            d.daylightMinutes = upMinutes(d.start, d.end, d.sunrise, d.sunset,
                    sunAltitude(d.start, lat, lon) >= SUN_HORIZON);

        // When the moon is up within the day, as {from, to} pieces.
        final List<long[]> ev = new ArrayList<>();
        for (Event e : d.moonrise) ev.add(new long[] { e.time, 1 });
        for (Event e : d.moonset) ev.add(new long[] { e.time, 0 });
        java.util.Collections.sort(ev, new java.util.Comparator<long[]>() {
            @Override
            public int compare(long[] a, long[] b) {
                return Long.compare(a[0], b[0]);
            }
        });
        boolean up = moonAltitude(d.start, lat, lon) >= moonHorizon(d.start);
        long from = d.start;
        for (long[] e : ev) {
            if (e[1] == 1 && !up) {
                from = e[0];
                up = true;
            } else if (e[1] == 0 && up) {
                d.moonUp.add(new long[] { from, e[0] });
                up = false;
            }
        }
        if (up)
            d.moonUp.add(new long[] { from, d.end });
        return d;
    }

    private static int upMinutes(long start, long end, List<Event> rise, List<Event> set,
            boolean upAtStart) {
        final List<long[]> ev = new ArrayList<>();
        for (Event e : rise) ev.add(new long[] { e.time, 1 });
        for (Event e : set) ev.add(new long[] { e.time, 0 });
        java.util.Collections.sort(ev, new java.util.Comparator<long[]>() {
            @Override
            public int compare(long[] a, long[] b) {
                return Long.compare(a[0], b[0]);
            }
        });
        long total = 0, from = start;
        boolean up = upAtStart;
        for (long[] e : ev) {
            if (e[1] == 1 && !up) {
                from = e[0];
                up = true;
            } else if (e[1] == 0 && up) {
                total += e[0] - from;
                up = false;
            }
        }
        if (up)
            total += end - from;
        return (int) Math.round(total / 60_000d);
    }

    public static int band(double sunAltitude) {
        if (sunAltitude >= SUN_HORIZON) return DAY;
        if (sunAltitude >= CIVIL) return CIVIL_BAND;
        if (sunAltitude >= NAUTICAL) return NAUT;
        if (sunAltitude >= ASTRONOMICAL) return ASTRO;
        return NIGHT;
    }

    /**
     * Every time in the day the body's altitude crosses the threshold, upward into
     * {@code rising} and downward into {@code setting}. The moon's threshold moves with
     * its distance, so it is computed per time ({@code threshold} NaN).
     */
    private static void crossings(Day d, double lat, double lon, boolean moon, double threshold,
            List<Event> rising, List<Event> setting) {
        long t0 = d.start;
        double f0 = excess(t0, lat, lon, moon, threshold);
        while (t0 < d.end) {
            final long t1 = Math.min(t0 + STEP_MS, d.end);
            final double f1 = excess(t1, lat, lon, moon, threshold);
            if ((f0 < 0) != (f1 < 0)) {
                long lo = t0, hi = t1;
                double flo = f0;
                while (hi - lo > 5_000L) {
                    final long mid = (lo + hi) / 2;
                    final double fm = excess(mid, lat, lon, moon, threshold);
                    if ((fm < 0) == (flo < 0)) {
                        lo = mid;
                        flo = fm;
                    } else {
                        hi = mid;
                    }
                }
                final long t = (lo + hi) / 2;
                final double az = moon ? moonAzimuth(t, lat, lon) : sunAzimuth(t, lat, lon);
                if (f1 >= 0)
                    rising.add(new Event(t, az));
                else
                    setting.add(new Event(t, az));
            }
            t0 = t1;
            f0 = f1;
        }
    }

    private static double excess(long t, double lat, double lon, boolean moon, double threshold) {
        return moon ? moonAltitude(t, lat, lon) - moonHorizon(t)
                : sunAltitude(t, lat, lon) - threshold;
    }

    // ------------------------------------------------------------------ positions

    static double jd(long utcMillis) {
        return JD_UNIX + utcMillis / DAY_MS;
    }

    /** {right ascension, declination} of the sun, degrees: NOAA's apparent position. */
    static double[] sunRaDec(long t) {
        final double T = (jd(t) - JD_2000) / 36525d;
        final double l0 = norm(280.46646 + T * (36000.76983 + T * 0.0003032));
        final double m = Math.toRadians(357.52911 + T * (35999.05029 - 0.0001537 * T));
        final double c = Math.sin(m) * (1.914602 - T * (0.004817 + 0.000014 * T))
                + Math.sin(2 * m) * (0.019993 - 0.000101 * T) + Math.sin(3 * m) * 0.000289;
        final double omega = Math.toRadians(125.04 - 1934.136 * T);
        final double lambda = Math.toRadians(l0 + c - 0.00569 - 0.00478 * Math.sin(omega));
        final double eps = Math.toRadians(obliquity(T) + 0.00256 * Math.cos(omega));
        return new double[] {
                norm(Math.toDegrees(Math.atan2(Math.cos(eps) * Math.sin(lambda), Math.cos(lambda)))),
                Math.toDegrees(Math.asin(Math.sin(eps) * Math.sin(lambda))) };
    }

    /** The sun's apparent ecliptic longitude, degrees. */
    static double sunLongitude(long t) {
        final double T = (jd(t) - JD_2000) / 36525d;
        final double l0 = norm(280.46646 + T * (36000.76983 + T * 0.0003032));
        final double m = Math.toRadians(357.52911 + T * (35999.05029 - 0.0001537 * T));
        final double c = Math.sin(m) * (1.914602 - T * (0.004817 + 0.000014 * T))
                + Math.sin(2 * m) * (0.019993 - 0.000101 * T) + Math.sin(3 * m) * 0.000289;
        final double omega = Math.toRadians(125.04 - 1934.136 * T);
        return norm(l0 + c - 0.00569 - 0.00478 * Math.sin(omega));
    }

    private static double obliquity(double T) {
        return 23 + (26 + (21.448 - T * (46.815 + T * (0.00059 - T * 0.001813))) / 60) / 60;
    }

    // Meeus table 47.A, largest terms: D, M, M', F multipliers, sum l (1e-6 deg), sum r (1e-3 km).
    private static final int[][] LR_ARGS = {
            { 0, 0, 1, 0 }, { 2, 0, -1, 0 }, { 2, 0, 0, 0 }, { 0, 0, 2, 0 }, { 0, 1, 0, 0 },
            { 0, 0, 0, 2 }, { 2, 0, -2, 0 }, { 2, -1, -1, 0 }, { 2, 0, 1, 0 }, { 2, -1, 0, 0 },
            { 0, 1, -1, 0 }, { 1, 0, 0, 0 }, { 0, 1, 1, 0 }, { 2, 0, 0, -2 }, { 0, 0, 1, 2 },
            { 0, 0, 1, -2 }, { 4, 0, -1, 0 }, { 0, 0, 3, 0 }, { 4, 0, -2, 0 }, { 2, 1, -1, 0 },
            { 2, 1, 0, 0 }, { 1, 0, -1, 0 }, { 1, 1, 0, 0 }, { 2, -1, 1, 0 }, { 2, 0, 2, 0 } };
    private static final double[] L_COEF = {
            6288774, 1274027, 658314, 213618, -185116, -114332, 58793, 57066, 53322, 45758,
            -40923, -34720, -30383, 15327, -12528, 10980, 10675, 10034, 8548, -7888,
            -6766, -5163, 4987, 4036, 3994 };
    private static final double[] R_COEF = {
            -20905355, -3699111, -2955968, -569925, 48888, -3149, 246158, -152138, -170733,
            -204586, -129620, 108743, 104755, 10321, 0, 79661, -34782, -23210, -21636, 24208,
            30824, -8379, -16675, -12831, -10445 };
    // Meeus table 47.B, largest terms: sum b (1e-6 deg).
    private static final int[][] B_ARGS = {
            { 0, 0, 0, 1 }, { 0, 0, 1, 1 }, { 0, 0, 1, -1 }, { 2, 0, 0, -1 }, { 2, 0, -1, 1 },
            { 2, 0, -1, -1 }, { 2, 0, 0, 1 }, { 0, 0, 2, 1 }, { 2, 0, 1, -1 }, { 0, 0, 2, -1 },
            { 2, -1, 0, -1 }, { 2, 0, -2, -1 }, { 2, 0, 1, 1 }, { 2, 1, 0, -1 } };
    private static final double[] B_COEF = {
            5128122, 280602, 277693, 173237, 55413, 46271, 32573, 17198, 9266, 8822, 8216,
            4324, 4200, -3359 };

    /** {ecliptic longitude deg, latitude deg, distance km} of the moon (geocentric). */
    static double[] moonEcliptic(long t) {
        final double T = (jd(t) - JD_2000) / 36525d;
        final double lp = norm(218.3164477 + 481267.88123421 * T);
        final double D = norm(297.8501921 + 445267.1114034 * T);
        final double M = norm(357.5291092 + 35999.0502909 * T);
        final double Mp = norm(134.9633964 + 477198.8675055 * T);
        final double F = norm(93.2720950 + 483202.0175233 * T);
        final double E = 1 - 0.002516 * T - 0.0000074 * T * T;
        double sl = 0, sr = 0, sb = 0;
        for (int i = 0; i < LR_ARGS.length; i++) {
            final int[] a = LR_ARGS[i];
            final double arg = Math.toRadians(a[0] * D + a[1] * M + a[2] * Mp + a[3] * F);
            final double e = Math.abs(a[1]) == 1 ? E : Math.abs(a[1]) == 2 ? E * E : 1;
            sl += L_COEF[i] * e * Math.sin(arg);
            sr += R_COEF[i] * e * Math.cos(arg);
        }
        for (int i = 0; i < B_ARGS.length; i++) {
            final int[] a = B_ARGS[i];
            final double arg = Math.toRadians(a[0] * D + a[1] * M + a[2] * Mp + a[3] * F);
            final double e = Math.abs(a[1]) == 1 ? E : Math.abs(a[1]) == 2 ? E * E : 1;
            sb += B_COEF[i] * e * Math.sin(arg);
        }
        // Meeus' additive terms for Venus, Jupiter and the flattening.
        final double a1 = Math.toRadians(119.75 + 131.849 * T);
        final double a2 = Math.toRadians(53.09 + 479264.290 * T);
        final double a3 = Math.toRadians(313.45 + 481266.484 * T);
        final double lpR = Math.toRadians(lp), FR = Math.toRadians(F), MpR = Math.toRadians(Mp);
        sl += 3958 * Math.sin(a1) + 1962 * Math.sin(lpR - FR) + 318 * Math.sin(a2);
        sb += -2235 * Math.sin(lpR) + 382 * Math.sin(a3) + 175 * Math.sin(a1 - FR)
                + 175 * Math.sin(a1 + FR) + 127 * Math.sin(lpR - MpR) - 115 * Math.sin(lpR + MpR);
        // Nutation in longitude, to the main term: the sun's longitude above carries it too.
        final double omega = Math.toRadians(125.04452 - 1934.136261 * T);
        final double nut = -0.00478 * Math.sin(omega);
        return new double[] { norm(lp + sl / 1e6 + nut), sb / 1e6, 385000.56 + sr / 1000 };
    }

    /** {right ascension, declination, horizontal parallax}, degrees. */
    static double[] moonRaDec(long t) {
        final double T = (jd(t) - JD_2000) / 36525d;
        final double[] ecl = moonEcliptic(t);
        final double lam = Math.toRadians(ecl[0]), bet = Math.toRadians(ecl[1]);
        final double eps = Math.toRadians(obliquity(T));
        final double ra = Math.atan2(Math.sin(lam) * Math.cos(eps) - Math.tan(bet) * Math.sin(eps),
                Math.cos(lam));
        final double dec = Math.asin(Math.sin(bet) * Math.cos(eps)
                + Math.cos(bet) * Math.sin(eps) * Math.sin(lam));
        return new double[] { norm(Math.toDegrees(ra)), Math.toDegrees(dec),
                Math.toDegrees(Math.asin(6378.14 / ecl[2])) };
    }

    /** Greenwich mean sidereal time, degrees. */
    static double gmst(long t) {
        final double d = jd(t) - JD_2000;
        final double T = d / 36525d;
        return norm(280.46061837 + 360.98564736629 * d + 0.000387933 * T * T);
    }

    private static double altitude(double raDeg, double decDeg, long t, double lat, double lon) {
        final double H = Math.toRadians(gmst(t) + lon - raDeg);
        final double phi = Math.toRadians(lat), dec = Math.toRadians(decDeg);
        return Math.toDegrees(Math.asin(Math.sin(phi) * Math.sin(dec)
                + Math.cos(phi) * Math.cos(dec) * Math.cos(H)));
    }

    /** Azimuth, degrees true, clockwise from north. */
    private static double azimuth(double raDeg, double decDeg, long t, double lat, double lon) {
        final double H = Math.toRadians(gmst(t) + lon - raDeg);
        final double phi = Math.toRadians(lat), dec = Math.toRadians(decDeg);
        final double az = Math.atan2(Math.sin(H),
                Math.cos(H) * Math.sin(phi) - Math.tan(dec) * Math.cos(phi));
        return norm(Math.toDegrees(az) + 180);
    }

    public static double sunAltitude(long t, double lat, double lon) {
        final double[] p = sunRaDec(t);
        return altitude(p[0], p[1], t, lat, lon);
    }

    static double sunAzimuth(long t, double lat, double lon) {
        final double[] p = sunRaDec(t);
        return azimuth(p[0], p[1], t, lat, lon);
    }

    /** Geocentric altitude of the moon's center, degrees. */
    static double moonAltitude(long t, double lat, double lon) {
        final double[] p = moonRaDec(t);
        return altitude(p[0], p[1], t, lat, lon);
    }

    static double moonAzimuth(long t, double lat, double lon) {
        final double[] p = moonRaDec(t);
        return azimuth(p[0], p[1], t, lat, lon);
    }

    /**
     * The geocentric altitude at which the moon's upper limb meets the horizon:
     * Meeus' h0 = 0.7275 x parallax - 0.5667 degrees (parallax, semidiameter and
     * refraction together), about +0.125 degrees.
     */
    static double moonHorizon(long t) {
        return 0.7275 * moonRaDec(t)[2] - 0.5667;
    }

    // ------------------------------------------------------------------ phases

    /** The moon's elongation from the sun, 0 new, 90 first quarter, 180 full, degrees. */
    public static double elongation(long t) {
        return norm(moonEcliptic(t)[0] - sunLongitude(t));
    }

    /** Illuminated fraction of the disc, 0 to 1. */
    public static double illumination(long t) {
        return (1 - Math.cos(Math.toRadians(elongation(t)))) / 2;
    }

    /** One principal phase: 0 new, 1 first quarter, 2 full, 3 last quarter. */
    public static final class Phase {
        public final int kind;
        public final long time;

        Phase(int kind, long time) {
            this.kind = kind;
            this.time = time;
        }
    }

    public static final String[] PHASE_NAMES = { "New moon", "First quarter", "Full moon",
            "Last quarter" };

    /** The next {@code n} principal phases after {@code from}. */
    public static List<Phase> nextPhases(long from, int n) {
        final List<Phase> out = new ArrayList<>();
        final long step = 6L * 3_600_000L;
        long t0 = from;
        double q0 = Math.floor(elongation(t0) / 90d);
        for (int i = 0; i < 4 * 40 && out.size() < n; i++) {
            final long t1 = t0 + step;
            final double q1 = Math.floor(elongation(t1) / 90d);
            if (q1 != q0) {
                // The quarter boundary crossed between t0 and t1: 0/90/180/270.
                final double target = (q0 + 1) % 4 * 90;
                long lo = t0, hi = t1;
                while (hi - lo > 30_000L) {
                    final long mid = (lo + hi) / 2;
                    double e = elongation(mid) - target;
                    if (e > 180) e -= 360;
                    if (e < -180) e += 360;
                    if (e < 0)
                        lo = mid;
                    else
                        hi = mid;
                }
                out.add(new Phase((int) ((q0 + 1) % 4), (lo + hi) / 2));
            }
            t0 = t1;
            q0 = q1;
        }
        return out;
    }

    private static double norm(double d) {
        d %= 360;
        return d < 0 ? d + 360 : d;
    }
}
