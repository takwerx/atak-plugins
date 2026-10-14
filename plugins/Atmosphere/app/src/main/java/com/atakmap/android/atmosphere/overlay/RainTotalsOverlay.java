package com.atakmap.android.atmosphere.overlay;

import android.content.SharedPreferences;

import com.atakmap.android.atmosphere.compat.MapCompat;
import com.atakmap.android.atmosphere.net.EgressPolicy;
import com.atakmap.android.maps.MapView;

import java.util.Locale;

/**
 * Rain that fell, as a picture, from two NWS map services in their own colors (read
 * from their legends, 2026-10-11). The hour to three-day windows are the MRMS
 * radar estimate, 1 km, updated every hour, so a storm that just ended is there
 * at once. The seven-day window is the River Forecast Centers' analysis, radar
 * corrected by the rain gauges, the better total once the RFC has produced it:
 * on 2026-10-11 the California RFC's 6 and 24 hour pictures were grey ("missing")
 * the evening after a storm while MRMS showed the rain (operator: "it poored here
 * and its grey for 6 and 24 hours"). The heat map the operator asked for
 * ("rain accumulation ... that would be great").
 *
 * <p>Lower 48 only; the services draw nothing elsewhere. Asked for again every 15
 * minutes while on.
 */
public final class RainTotalsOverlay extends ImageOverlay {

    public static final String LAYER_ID = "raintotals";
    public static final String HOST = "mapservices.weather.noaa.gov";
    private static final String RFC_EXPORT = "https://" + HOST
            + "/raster/rest/services/obs/rfc_qpe/MapServer/export";
    private static final String MRMS_EXPORT = "https://" + HOST
            + "/raster/rest/services/obs/mrms_qpe/ImageServer/exportImage";
    private static final String PREF_WINDOW = "weather.layer.raintotals.window";
    private static final String PREF_TRANSPARENCY = "weather.layer.raintotals.transparency";

    /** How see-through the picture is, 0 (solid) to {@link #MAX_TRANSPARENCY}; a slider, the one continuous control. */
    public static final int MAX_TRANSPARENCY = 90;
    private static final int DEFAULT_TRANSPARENCY = 30;

    /**
     * The windows offered: the first four MRMS raster functions, the rest the RFC
     * analysis's image sublayers. The longer three match the Rain Gauges periods, so
     * both layers can be set alike (operator, 2026-10-14: the gauges were on 30 days
     * and this could not be).
     */
    public static final String[] WINDOWS = { "Last hour", "Last 6 h", "Last 24 h", "Last 3 days", "Last 7 days",
            "Last 30 days", "Since Oct 1", "Since Jan 1", "Before a date and time" };
    /** The radar estimate of the hours before a moment picked, from the mesonet's archive. */
    public static final int WINDOW_BEFORE = 8;
    private static final String PREF_END = "weather.layer.raintotals.end";
    private static final String PREF_HOURS = "weather.layer.raintotals.hours";
    private static final String[] MRMS_FUNCTIONS = { "rft_1hr", "rft_6hr", "rft_24hr", "rft_72hr" };
    /** 7 days, 30 days, water year to date, year to date: the "Image" sublayers. */
    private static final int[] RFC_SUBLAYERS = { 56, 68, 100, 96 };
    private static final int DEFAULT_WINDOW = 2;

    /** MRMS's classes, inches, with its colors. */
    public static final String[][] LEGEND_MRMS = {
            { "0.01 to 0.05 in", "#FF00ECEC" }, { "0.05 to 0.1 in", "#FF00C8F0" },
            { "0.1 to 0.25 in", "#FF00A0FF" }, { "0.25 to 0.5 in", "#FF003CFF" },
            { "0.5 to 0.75 in", "#FF00FF00" }, { "0.75 to 1 in", "#FF00DC00" },
            { "1 to 1.5 in", "#FF00BE00" }, { "1.5 to 2 in", "#FF008D00" },
            { "2 to 2.5 in", "#FFFFFF00" }, { "2.5 to 3 in", "#FFF0D200" },
            { "3 to 4 in", "#FFE7B400" }, { "4 to 5 in", "#FFC87800" },
            { "5 to 6 in", "#FFFFA0A0" }, { "6 to 7 in", "#FFFF3C3C" },
            { "7 to 8 in", "#FFE60000" }, { "8 to 9 in", "#FFB40000" },
            { "9 to 10 in", "#FFFA00FF" }, { "10 to 12 in", "#FFD900D9" },
            { "12 to 14 in", "#FFA400A4" }, { "14 to 16 in", "#FF780078" },
            { "16 to 18 in", "#FFFFFFFF" }, { "18 to 20 in", "#FFC0C0FF" },
            { "20 to 24 in", "#FFC0FFFF" }, { "Over 24 in", "#FFFFFFC0" } };

    /** Where each MRMS class starts, inches, in LEGEND_MRMS's order. */
    static final double[] FROM_MRMS = { 0.01, 0.05, 0.1, 0.25, 0.5, 0.75, 1, 1.5, 2, 2.5, 3, 4, 5, 6, 7, 8, 9, 10,
            12, 14, 16, 18, 20, 24 };

    /**
     * The RFC analysis's key for 7 and 30 days, read from the service's legend for
     * those pictures on 2026-10-14. Until then this layer showed the 24-hour picture's
     * key over the 7-day picture, whose steps are wider: its yellow is 2 to 3 in, not
     * 1.5 to 2 (operator, 2026-10-14: "i feel like its not right").
     */
    public static final String[][] LEGEND_RFC = {
            { "0.01 to 0.1 in", "#FF14C8FA" }, { "0.1 to 0.25 in", "#FF3E87C7" },
            { "0.25 to 0.5 in", "#FF001496" }, { "0.5 to 1 in", "#FF00FA15" },
            { "1 to 1.5 in", "#FF00A110" }, { "1.5 to 2 in", "#FF00630A" },
            { "2 to 3 in", "#FFFAFA00" }, { "3 to 4 in", "#FFFFD966" },
            { "4 to 5 in", "#FFFA9600" }, { "5 to 6 in", "#FFFA0000" },
            { "6 to 8 in", "#FFB00000" }, { "8 to 10 in", "#FF7D0000" },
            { "10 to 15 in", "#FFFA00FA" }, { "15 to 20 in", "#FF7C4AE0" },
            { "20 in or more", "#FFDBDBDB" } };
    static final double[] FROM_RFC = { 0.01, 0.1, 0.25, 0.5, 1, 1.5, 2, 3, 4, 5, 6, 8, 10, 15, 20 };

    /** The RFC key for the water year and the year to date: the same colors on a season's steps. */
    public static final String[][] LEGEND_SEASON = {
            { "0.01 to 2.5 in", "#FF14C8FA" }, { "2.5 to 5 in", "#FF3E87C7" },
            { "5 to 10 in", "#FF001496" }, { "10 to 15 in", "#FF00FA15" },
            { "15 to 20 in", "#FF00A110" }, { "20 to 25 in", "#FF00630A" },
            { "25 to 30 in", "#FFFAFA00" }, { "30 to 35 in", "#FFFFD966" },
            { "35 to 40 in", "#FFFA9600" }, { "40 to 50 in", "#FFFA0000" },
            { "50 to 60 in", "#FFB00000" }, { "60 to 70 in", "#FF7D0000" },
            { "70 to 80 in", "#FFFA00FA" }, { "80 to 100 in", "#FF7C4AE0" },
            { "100 in or more", "#FFDBDBDB" } };
    static final double[] FROM_SEASON = { 0.01, 2.5, 5, 10, 15, 20, 25, 30, 35, 40, 50, 60, 70, 80, 100 };

    /** The color a total takes in a key, as the picture draws it; null under 0.01 in, which it leaves clear. */
    public static Integer colorIn(String[][] legend, double[] from, double inches) {
        if (Double.isNaN(inches) || inches < 0.005)
            return null;
        int at = 0;
        for (int i = 0; i < from.length; i++)
            if (inches + 1e-9 >= from[i])
                at = i;
        return (int) Long.parseLong(legend[at][1].substring(1), 16);
    }

    /** A gauge total over a stretch of hours or days in the key the picture of that stretch uses. */
    public static Integer colorFor(int hours, boolean season, double inches) {
        if (season)
            return colorIn(LEGEND_SEASON, FROM_SEASON, inches);
        if (hours <= 72)
            return colorIn(LEGEND_MRMS, FROM_MRMS, inches);
        return colorIn(LEGEND_RFC, FROM_RFC, inches);
    }

    /** The key for a stretch, the same rule as {@link #colorFor}. */
    public static String[][] legendFor(int hours, boolean season) {
        return season ? LEGEND_SEASON : hours <= 72 ? LEGEND_MRMS : LEGEND_RFC;
    }

    private int window = DEFAULT_WINDOW;
    private int transparency = DEFAULT_TRANSPARENCY;
    /** The moment picked for {@link #WINDOW_BEFORE}, UTC millis, and the hours before it. */
    private long endMs;
    private int beforeHours = 24;

    public RainTotalsOverlay(MapView mapView, EgressPolicy egress) {
        super(mapView, egress, "AtmosphereRainTotals", LAYER_ID, "Rain Totals");
        final SharedPreferences p = MapCompat.prefs();
        window = p == null ? DEFAULT_WINDOW
                : Math.max(0, Math.min(WINDOWS.length - 1, p.getInt(PREF_WINDOW, DEFAULT_WINDOW)));
        transparency = p == null ? DEFAULT_TRANSPARENCY
                : Math.max(0, Math.min(MAX_TRANSPARENCY, p.getInt(PREF_TRANSPARENCY, DEFAULT_TRANSPARENCY)));
        if (p != null) {
            endMs = p.getLong(PREF_END, 0L);
            beforeHours = p.getInt(PREF_HOURS, 24);
        }
        if (window == WINDOW_BEFORE && !validMoment(endMs, beforeHours))
            window = DEFAULT_WINDOW;
    }

    /** What the Totals button reads: "Last 7 days", or "24 h before Sun Oct 11, 1:00 pm". */
    public String windowLabel() {
        if (window == WINDOW_BEFORE)
            return RainGaugesOverlay.hoursWords(beforeHours) + " before "
                    + RainGaugesOverlay.momentName(com.atakmap.android.atmosphere.data.IemRadar.hourOf(endMs));
        return WINDOWS[window];
    }

    public long moment() {
        return endMs;
    }

    public int beforeHours() {
        return beforeHours;
    }

    /**
     * The radar estimate of the hours before a moment: kept by the hour, so it is the
     * picture of the hour at or before it, never one that holds rain after it.
     */
    public void setMoment(long millis, int hours) {
        final long m = Math.min(millis, System.currentTimeMillis());
        if (!validMoment(m, hours))
            return;
        if (window == WINDOW_BEFORE && m == endMs && hours == beforeHours)
            return;
        window = WINDOW_BEFORE;
        endMs = m;
        beforeHours = hours;
        final SharedPreferences p = MapCompat.prefs();
        if (p != null)
            p.edit().putInt(PREF_WINDOW, window).putLong(PREF_END, m).putInt(PREF_HOURS, hours).apply();
        reask();
    }

    /** One of the stretches the archive keeps, and a moment it can hold: 2014 on. */
    private static boolean validMoment(long millis, int hours) {
        boolean known = false;
        for (int h : com.atakmap.android.atmosphere.data.IemRadar.HOURS)
            known |= h == hours;
        return known && millis > 1_388_534_400_000L && millis <= System.currentTimeMillis() + 60_000L;
    }

    public int transparency() {
        return transparency;
    }

    /** Percent see-through. The alpha is baked into the picture as it arrives, so a change asks again. */
    public void setTransparency(int percent) {
        final int v = Math.max(0, Math.min(MAX_TRANSPARENCY, percent));
        if (v == transparency)
            return;
        transparency = v;
        final SharedPreferences p = MapCompat.prefs();
        if (p != null)
            p.edit().putInt(PREF_TRANSPARENCY, v).apply();
        reask();
    }

    public int window() {
        return window;
    }

    /** The key for the window drawn: MRMS's, the RFC analysis's, or the RFC's season key. */
    public String[][] legend() {
        if (window == WINDOW_BEFORE)
            return LEGEND_MRMS;
        return window < MRMS_FUNCTIONS.length ? LEGEND_MRMS : window >= 6 ? LEGEND_SEASON : LEGEND_RFC;
    }

    /**
     * A past picture comes in the mesonet's color ramp: each pixel is read back to
     * inches and painted in the NWS radar key, so the picture reads like every other
     * window and like the gauges over it. Under 0.01 in is left clear, as NWS leaves it.
     */
    @Override
    protected android.graphics.Bitmap repaint(android.graphics.Bitmap picture) {
        if (window != WINDOW_BEFORE || picture == null)
            return picture;
        final int w = picture.getWidth(), h = picture.getHeight();
        final int[] px = new int[w * h];
        picture.getPixels(px, 0, w, 0, 0, w, h);
        final java.util.Map<Integer, Integer> memo = new java.util.HashMap<>();
        for (int i = 0; i < px.length; i++) {
            final int c = px[i];
            if ((c >>> 24) == 0) {
                px[i] = 0;
                continue;
            }
            Integer to = memo.get(c);
            if (to == null) {
                final double in = com.atakmap.android.atmosphere.data.IemRadar.inches(c);
                final Integer key = Double.isNaN(in) ? null : colorIn(LEGEND_MRMS, FROM_MRMS, in);
                to = key == null ? 0 : key;
                memo.put(c, to);
            }
            px[i] = to;
        }
        final android.graphics.Bitmap out = android.graphics.Bitmap.createBitmap(w, h,
                android.graphics.Bitmap.Config.ARGB_8888);
        out.setPixels(px, 0, w, 0, 0, w, h);
        return out;
    }

    /** Another window: the picture up is the wrong one, so it is asked for again. */
    public void setWindow(int index) {
        int v = Math.max(0, Math.min(WINDOWS.length - 1, index));
        if (v == WINDOW_BEFORE && !validMoment(endMs, beforeHours))
            v = DEFAULT_WINDOW;
        if (v == window)
            return;
        window = v;
        final SharedPreferences p = MapCompat.prefs();
        if (p != null)
            p.edit().putInt(PREF_WINDOW, v).apply();
        reask();
    }

    @Override
    protected String url(double west, double south, double east, double north, int px, int py) {
        final String box = String.format(Locale.US, "?bbox=%.4f,%.4f,%.4f,%.4f", west, south, east, north)
                + "&bboxSR=4326&imageSR=4326&size=" + px + "," + py
                + "&format=png32&transparent=true&f=image";
        if (window == WINDOW_BEFORE)
            return com.atakmap.android.atmosphere.data.IemRadar.url(beforeHours, endMs, west, south, east, north,
                    px, py);
        if (window < MRMS_FUNCTIONS.length)
            return MRMS_EXPORT + box + "&renderingRule=%7B%22rasterFunction%22%3A%22"
                    + MRMS_FUNCTIONS[window] + "%22%7D";
        return RFC_EXPORT + box + "&layers=show:" + RFC_SUBLAYERS[window - MRMS_FUNCTIONS.length];
    }

    @Override
    protected int alpha() {
        return Math.round(255f * (100 - transparency) / 100f);
    }

    @Override
    protected long pollMs() {
        return 15 * 60 * 1000L;
    }

    /** The RFCs cover the lower 48 and a margin; nothing is drawn beyond it. */
    @Override
    protected double coverWest() {
        return -127;
    }

    @Override
    protected double coverEast() {
        return -65;
    }

    @Override
    protected double coverSouth() {
        return 23;
    }

    @Override
    protected double coverNorth() {
        return 51;
    }

    @Override
    protected String noun() {
        return "rain totals";
    }

    @Override
    protected String shown() {
        if (window == WINDOW_BEFORE)
            return "Rain that fell in the " + windowLabel() + ": the NWS radar estimate as it stood at that"
                    + " hour, kept hour by hour by the Iowa Environmental Mesonet, in inches, lower 48 only. Dry"
                    + " ground is left clear.";
        final String when = WINDOWS[window].startsWith("Last ")
                ? "in the last " + WINDOWS[window].substring(5).replace("hour", "hour")
                : WINDOWS[window].toLowerCase(java.util.Locale.US).replace("oct", "Oct").replace("jan", "Jan")
                        + (window == 6 ? ", the water year" : "");
        if (window < MRMS_FUNCTIONS.length)
            return "Rain that fell " + when + ": the NWS radar estimate, 1 km, updated every hour, in"
                    + " inches, lower 48 only. Dry ground is left clear.";
        return "Rain that fell " + when + ": the NWS analysis from radar corrected by the rain gauges,"
                + " in inches, lower 48 only. Grey is where the River Forecast Center has not produced it yet.";
    }
}
