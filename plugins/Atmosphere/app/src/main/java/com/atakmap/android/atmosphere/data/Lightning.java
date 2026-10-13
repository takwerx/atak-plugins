package com.atakmap.android.atmosphere.data;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Lightning strike density from NOAA's nowCOAST: the NWS Ocean Prediction Center's
 * 15-minute product from the ground networks (NLDN and GLD360), on an 8 km grid,
 * 25 S to 80 N including Alaska and Hawaii. A new frame every 15 minutes, posted
 * about 10 minutes after its time, six hours kept. Keyless; Fees and
 * AccessConstraints NONE in the capabilities (read 2026-10-01).
 *
 * <p>The value is strikes per square km per minute times 1000, which comes to
 * about the number of strikes in one 5-mile square in the 15 minutes. NOAA's own
 * ramp ends in green at the densest cores, which would read as safe; the server
 * refuses a restyle (403), so the phone repaints its 17 colors into five bands
 * that never use green ({@link #repaint}).
 *
 * <p>No Android types; tested.
 */
public final class Lightning {

    public static final String HOST = "nowcoast.noaa.gov";
    private static final String WMS = "https://" + HOST
            + "/geoserver/lightning_detection/wms?service=WMS&version=1.3.0";
    public static final String CAPABILITIES_URL = WMS + "&request=GetCapabilities";
    public static final String LAYER = "lightning_detection:ldn_lightning_strike_density";

    /** The five bands drawn, lightest first: about how many strikes in a 5-mile square in 15 minutes. */
    public static final String[] BANDS = { "Under 1 strike", "1 to 10 strikes", "10 to 50 strikes",
            "50 to 150 strikes", "Over 150 strikes" };
    public static final int[] BAND_COLORS = { 0xFFFFE34D, 0xFFFF9900, 0xFFFF1A1A, 0xFFD100D1,
            0xFF5B0A91 };

    /**
     * NOAA's interval colors (GetLegendGraphic, 2026-10-01) and the band each goes
     * to, by the class's lower bound: under 1, 1-10, 10-50, 50-150, 150 and up.
     */
    private static final int[] NOAA = {
            0xFFFFCC, 0xFFEC78, 0xFFFF00, 0xFFD600,           // 0.1 - 1
            0xFFA400, 0xFF7800, 0xFF4500,                     // 1 - 10
            0xFF0000, 0xBC0025, 0xCC0066,                     // 10 - 50
            0xFF00FF, 0x800080, 0x4000C0,                     // 50 - 150
            0x0000FF, 0x00C7FF, 0x00A264, 0x00FF00 };         // 150 and up
    private static final int[] NOAA_BAND = { 0, 0, 0, 0, 1, 1, 1, 2, 2, 2, 3, 3, 3, 4, 4, 4, 4 };

    /** The picture of a box at this many pixels for one frame, or the newest when time is null. */
    public static String mapUrl(double west, double south, double east, double north, int px,
            int py, String time) {
        return WMS + "&request=GetMap&layers=" + LAYER + "&styles=&crs=CRS:84"
                + String.format(Locale.US, "&bbox=%.4f,%.4f,%.4f,%.4f", west, south, east, north)
                + "&width=" + px + "&height=" + py + "&format=image/png&transparent=true"
                + (time == null || time.isEmpty() ? "" : "&time=" + time);
    }

    private static final Pattern LAYER_BLOCK = Pattern.compile(
            "<Name>ldn_lightning_strike_density</Name>(.*?)</Layer>", Pattern.DOTALL);
    private static final Pattern TIME_DIM = Pattern.compile(
            "<Dimension[^>]*name=\"time\"[^>]*>([^<]*)</Dimension>");
    private static final Pattern ISO = Pattern.compile("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(\\.\\d+)?Z");

    /** The newest frame the capabilities list, as written ("2026-10-01T02:30:00.000Z"), or null. */
    public static String newestFrame(String capabilities) {
        if (capabilities == null)
            return null;
        final Matcher layer = LAYER_BLOCK.matcher(capabilities);
        if (!layer.find())
            return null;
        final Matcher dim = TIME_DIM.matcher(layer.group(1));
        if (!dim.find())
            return null;
        final String[] times = dim.group(1).trim().split(",");
        for (int i = times.length - 1; i >= 0; i--) {
            final String t = times[i].trim();
            if (ISO.matcher(t).matches())
                return t;
        }
        return null;
    }

    /**
     * One pixel of NOAA's picture in our bands, alpha kept: its own class's band
     * when it is one of NOAA's colors, the nearest one's otherwise, untouched when
     * transparent.
     */
    public static int repaint(int argb) {
        final int a = argb >>> 24;
        if (a == 0)
            return argb;
        final int rgb = argb & 0xFFFFFF;
        int best = -1;
        long bestD = Long.MAX_VALUE;
        for (int i = 0; i < NOAA.length; i++) {
            if (NOAA[i] == rgb)
                return (a << 24) | (BAND_COLORS[NOAA_BAND[i]] & 0xFFFFFF);
            final long dr = ((NOAA[i] >> 16) & 0xFF) - ((rgb >> 16) & 0xFF);
            final long dg = ((NOAA[i] >> 8) & 0xFF) - ((rgb >> 8) & 0xFF);
            final long db = (NOAA[i] & 0xFF) - (rgb & 0xFF);
            final long d = dr * dr + dg * dg + db * db;
            if (d < bestD) {
                bestD = d;
                best = i;
            }
        }
        return (a << 24) | (BAND_COLORS[NOAA_BAND[best]] & 0xFFFFFF);
    }

    private Lightning() {
    }
}
