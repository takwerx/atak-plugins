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

    /** The windows offered: the first four MRMS raster functions, the last the RFC image sublayer. */
    public static final String[] WINDOWS = { "1 h", "6 h", "24 h", "3 days", "7 days" };
    private static final String[] MRMS_FUNCTIONS = { "rft_1hr", "rft_6hr", "rft_24hr", "rft_72hr" };
    private static final int RFC_7DAY_SUBLAYER = 56;
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

    /** The RFC analysis's classes, inches, with its colors; "less than 0.01" is left off the key. */
    public static final String[][] LEGEND_RFC = {
            { "0.01 to 0.1 in", "#FF14C8FA" }, { "0.1 to 0.25 in", "#FF3D85C6" },
            { "0.25 to 0.5 in", "#FF001432" }, { "0.5 to 0.75 in", "#FF00FA14" },
            { "0.75 to 1 in", "#FF00A00F" }, { "1 to 1.5 in", "#FF00640A" },
            { "1.5 to 2 in", "#FFFAFA00" }, { "2 to 2.5 in", "#FFFFD966" },
            { "2.5 to 3 in", "#FFFA9600" }, { "3 to 4 in", "#FFFA0000" },
            { "4 to 5 in", "#FFAF0000" }, { "5 to 6 in", "#FF7D0000" },
            { "6 to 8 in", "#FFFA00FA" }, { "8 to 10 in", "#FF7D4BE1" },
            { "10 in or more", "#FFDCDCDC" } };

    private int window = DEFAULT_WINDOW;
    private int transparency = DEFAULT_TRANSPARENCY;

    public RainTotalsOverlay(MapView mapView, EgressPolicy egress) {
        super(mapView, egress, "AtmosphereRainTotals", LAYER_ID, "Rain Totals");
        final SharedPreferences p = MapCompat.prefs();
        window = p == null ? DEFAULT_WINDOW
                : Math.max(0, Math.min(WINDOWS.length - 1, p.getInt(PREF_WINDOW, DEFAULT_WINDOW)));
        transparency = p == null ? DEFAULT_TRANSPARENCY
                : Math.max(0, Math.min(MAX_TRANSPARENCY, p.getInt(PREF_TRANSPARENCY, DEFAULT_TRANSPARENCY)));
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

    /** The key for the window drawn: MRMS's or the RFC analysis's. */
    public String[][] legend() {
        return window < MRMS_FUNCTIONS.length ? LEGEND_MRMS : LEGEND_RFC;
    }

    /** Another window: the picture up is the wrong one, so it is asked for again. */
    public void setWindow(int index) {
        final int v = Math.max(0, Math.min(WINDOWS.length - 1, index));
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
        if (window < MRMS_FUNCTIONS.length)
            return MRMS_EXPORT + box + "&renderingRule=%7B%22rasterFunction%22%3A%22"
                    + MRMS_FUNCTIONS[window] + "%22%7D";
        return RFC_EXPORT + box + "&layers=show:" + RFC_7DAY_SUBLAYER;
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
        if (window < MRMS_FUNCTIONS.length)
            return "Rain that fell in the last " + WINDOWS[window] + ": the NWS radar estimate,"
                    + " 1 km, updated every hour, in inches, lower 48 only. Dry ground is left clear.";
        return "Rain that fell in the last 7 days: the NWS analysis from radar corrected by the"
                + " rain gauges, in inches, lower 48 only. Grey is where the River Forecast Center"
                + " has not produced it yet.";
    }
}
