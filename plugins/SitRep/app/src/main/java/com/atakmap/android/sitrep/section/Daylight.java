package com.atakmap.android.sitrep.section;

import com.atakmap.android.sitrep.Area;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

/**
 * Sunrise, sunset, civil twilight and the moon for the area's center, today, worked
 * out on the device (PLAN 4.12b): no network, so it is there offline too. Times are
 * the device's clock zone. NOAA's sunrise equation, good to about a minute.
 */
public final class Daylight extends Section {

    public Daylight() {
        super("daylight", "Daylight", true,
                "Worked out on this device for the center of the area (NOAA sunrise equation); no network.");
    }

    @Override
    public Result fetch(Area area) {
        final double lat = (area.south + area.north) / 2, lon = (area.west + area.east) / 2;
        final Calendar today = Calendar.getInstance();
        final long civilDawn = event(today, lat, lon, 96, true), rise = event(today, lat, lon, 90.833, true);
        final long set = event(today, lat, lon, 90.833, false), civilDusk = event(today, lat, lon, 96, false);
        final SimpleDateFormat hm = new SimpleDateFormat("HH:mm", Locale.US);
        final String zone = TimeZone.getDefault().getDisplayName(TimeZone.getDefault().inDaylightTime(new Date()),
                TimeZone.SHORT, Locale.US);
        final Result r;
        if (rise < 0 || set < 0)
            r = new Result("No sunrise or sunset today here", null);
        else
            r = new Result("Sunrise " + hm.format(new Date(rise)) + ", sunset " + hm.format(new Date(set)), null);
        r.line(GROUP + "Sun, " + new SimpleDateFormat("EEE MMM d", Locale.US).format(today.getTime()) + " (" + zone + ")");
        if (civilDawn > 0)
            r.line("  First light (civil twilight) " + hm.format(new Date(civilDawn)) + ".");
        if (rise > 0)
            r.line("  Sunrise " + hm.format(new Date(rise)) + ".");
        if (set > 0)
            r.line("  Sunset " + hm.format(new Date(set)) + ".");
        if (civilDusk > 0)
            r.line("  Last light (end of civil twilight) " + hm.format(new Date(civilDusk)) + ".");
        if (rise > 0 && set > rise) {
            final long min = (set - rise) / 60_000;
            r.line("  " + (min / 60) + " h " + (min % 60) + " min of daylight.");
        }
        final double phase = moonPhase(System.currentTimeMillis());
        final long lit = Math.round((1 - Math.cos(2 * Math.PI * phase)) / 2 * 100);
        r.line(GROUP + "Moon: " + phaseName(phase) + ", " + lit + "% lit");
        r.line("  " + phaseName(phase) + ", " + lit + "% of the disk lit tonight.");
        return r;
    }

    /** Epoch ms of sunrise (or sunset) at a zenith angle on the given day; -1 if none. */
    static long event(Calendar day, double lat, double lon, double zenith, boolean rising) {
        final int n = day.get(Calendar.DAY_OF_YEAR);
        final double lngHour = lon / 15;
        final double t = n + ((rising ? 6 : 18) - lngHour) / 24;
        final double m = 0.9856 * t - 3.289;
        double l = m + 1.916 * sin(m) + 0.020 * sin(2 * m) + 282.634;
        l = norm(l, 360);
        double ra = Math.toDegrees(Math.atan(0.91764 * Math.tan(Math.toRadians(l))));
        ra = norm(ra, 360);
        ra += Math.floor(l / 90) * 90 - Math.floor(ra / 90) * 90;
        ra /= 15;
        final double sinDec = 0.39782 * sin(l), cosDec = Math.cos(Math.asin(sinDec));
        final double cosH = (Math.cos(Math.toRadians(zenith)) - sinDec * sin(lat)) / (cosDec * Math.cos(Math.toRadians(lat)));
        if (cosH > 1 || cosH < -1)
            return -1;
        double h = rising ? 360 - Math.toDegrees(Math.acos(cosH)) : Math.toDegrees(Math.acos(cosH));
        h /= 15;
        final double localMean = h + ra - 0.06571 * t - 6.622;
        final double utc = norm(localMean - lngHour, 24);
        final Calendar c = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
        c.clear();
        c.set(day.get(Calendar.YEAR), day.get(Calendar.MONTH), day.get(Calendar.DAY_OF_MONTH));
        long ms = c.getTimeInMillis() + Math.round(utc * 3_600_000);
        // The UTC day can differ from the local one: keep the event on the local date asked for.
        final Calendar local = Calendar.getInstance();
        local.setTimeInMillis(ms);
        if (local.get(Calendar.DAY_OF_YEAR) < day.get(Calendar.DAY_OF_YEAR) && local.get(Calendar.YEAR) == day.get(Calendar.YEAR))
            ms += 86_400_000L;
        else if (local.get(Calendar.DAY_OF_YEAR) > day.get(Calendar.DAY_OF_YEAR) && local.get(Calendar.YEAR) == day.get(Calendar.YEAR))
            ms -= 86_400_000L;
        return ms;
    }

    /** 0 new, 0.25 first quarter, 0.5 full, 0.75 last quarter. */
    static double moonPhase(long ms) {
        final double days = ms / 86_400_000d + 2440587.5 - 2451550.1;
        return norm(days / 29.530588853, 1);
    }

    static String phaseName(double p) {
        if (p < 0.03 || p > 0.97)
            return "New moon";
        if (p < 0.22)
            return "Waxing crescent";
        if (p < 0.28)
            return "First quarter";
        if (p < 0.47)
            return "Waxing gibbous";
        if (p < 0.53)
            return "Full moon";
        if (p < 0.72)
            return "Waning gibbous";
        if (p < 0.78)
            return "Last quarter";
        return "Waning crescent";
    }

    private static double sin(double deg) {
        return Math.sin(Math.toRadians(deg));
    }

    private static double norm(double v, double range) {
        v %= range;
        return v < 0 ? v + range : v;
    }
}
