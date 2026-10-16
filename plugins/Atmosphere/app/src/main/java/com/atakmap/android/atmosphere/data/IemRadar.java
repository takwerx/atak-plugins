package com.atakmap.android.atmosphere.data;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;

/**
 * The NWS radar rain estimate (MRMS) as it was at a past hour: the Iowa Environmental
 * Mesonet keeps every hour's 1, 24, 48 and 72 hour totals ({@code mesonet.agron.iastate.edu},
 * public domain) and cuts a box out of them, where the NWS service holds only the
 * current hour (operator, 2026-10-14: "can that overlay map also have a date and time
 * picker?").
 *
 * <p>The picture comes in the mesonet's own color ramp. Each of its 256 colors stands
 * for one amount (its lookup table, rasters.php?rid=6, read 2026-10-14; the three
 * smallest amounts are drawn in blues the table lists as black), so each pixel is read
 * back to inches and painted in the NWS key the rest of Rain Totals uses.
 *
 * <p>No Android types; tested.
 */
public final class IemRadar {

    public static final String HOST = "mesonet.agron.iastate.edu";

    /** The stretches kept, hours, and their product names. */
    public static final int[] HOURS = { 1, 24, 48, 72 };
    private static final String[] PRODUCTS = { "p1h", "p24h", "p48h", "p72h" };

    /** The mesonet's colors, by index. */
    static final int[] PALETTE = {
            0x000000, 0x0006FF, 0x000CFF, 0x0012FF, 0x0018FF, 0x001EFF, 0x0024FF, 0x002AFF,
            0x0030FF, 0x0036FF, 0x003CFF, 0x0042FF, 0x0048FF, 0x004EFF, 0x0054FF, 0x005AFF,
            0x0060FF, 0x0066FF, 0x006CFF, 0x0072FF, 0x0078FF, 0x007EFF, 0x0084FF, 0x008AFF,
            0x0090FF, 0x0096FF, 0x009CFF, 0x00A2FF, 0x00A8FF, 0x00AEFF, 0x00B4FF, 0x00BAFF,
            0x00C0FF, 0x00C6FF, 0x00CCFF, 0x00D2FF, 0x00D8FF, 0x00FEF0, 0x00FEEA, 0x00FEE4,
            0x00FEDE, 0x00FED8, 0x00FED2, 0x00FECC, 0x00FEC6, 0x00FEC0, 0x00FEBA, 0x00FEB4,
            0x00FEAE, 0x00FEA8, 0x00FEA2, 0x00FE9C, 0x00FE96, 0x00FE90, 0x00FE8A, 0x00FE84,
            0x00FE7E, 0x00FE78, 0x00FE72, 0x00FE6C, 0x00FE66, 0x00FE60, 0x00FE5A, 0x00FE54,
            0x00FE4E, 0x00FE48, 0x00FE42, 0x00FE3C, 0x00FE36, 0x00FE30, 0x00FE2A, 0x00FE24,
            0x00FE1E, 0x00FE18, 0x00FE12, 0x00FE0C, 0x00FE06, 0x00FE00, 0x06FE00, 0x0CFE00,
            0x12FE00, 0x18FE00, 0x1EFE00, 0x24FE00, 0x2AFE00, 0x30FE00, 0x36FE00, 0x3CFE00,
            0x42FE00, 0x48FE00, 0x4EFE00, 0x54FE00, 0x5AFE00, 0x60FE00, 0x66FE00, 0x6CFE00,
            0x72FE00, 0x78FE00, 0x7EFE00, 0x84FE00, 0x8AFE00, 0x90FE00, 0x96FE00, 0x9CFE00,
            0xA2FE00, 0xA8FE00, 0xAEFE00, 0xB4FE00, 0xBAFE00, 0xC0FE00, 0xC6FE00, 0xCCFE00,
            0xD2FE00, 0xD8FE00, 0xDEFE00, 0xE4FE00, 0xEAF000, 0xFEEA00, 0xFEE400, 0xFEDE00,
            0xFED800, 0xFED200, 0xFECC00, 0xFEC600, 0xFEC000, 0xFEBA00, 0xFEB400, 0xFEAE00,
            0xFEA800, 0xFEA200, 0xFE9C00, 0xFE9600, 0xFE9000, 0xFE8A00, 0xFE8400, 0xFE7E00,
            0xFE7800, 0xFE7200, 0xFE6C00, 0xFE6600, 0xFE6000, 0xFE5A00, 0xFE5400, 0xFE4E00,
            0xFE4800, 0xFE4200, 0xFE3C00, 0xFE3600, 0xFE3000, 0xFE2A00, 0xFE2400, 0xFE1E00,
            0xFE1800, 0xFE1200, 0xFE0C00, 0xFE0600, 0xFE0000, 0xFE0000, 0xFE0000, 0xFE0000,
            0xFE0000, 0xFE0006, 0xFE000C, 0xFE0012, 0xFE0018, 0xFE001E, 0xFE0024, 0xFE002A,
            0xFE0030, 0xFE0036, 0xFE003C, 0xFE0042, 0xFE0048, 0xFE004E, 0xFE0054, 0xFE005A,
            0xFE0060, 0xFE0066, 0xFE006C, 0xFE0072, 0xFE0078, 0xFE007E, 0xFE0084, 0xFE008A,
            0xFE0090, 0xFE0096, 0xFE009C, 0xFE00A2, 0xFE00A8, 0xFE00AE, 0xFE00B4, 0xFE00BA,
            0xFE00C0, 0xFE00C6, 0xFE00CC, 0xFE00D2, 0xFE00FE, 0xFE04FE, 0xFE08FE, 0xFE0CFE,
            0xFE10FE, 0xFE14FE, 0xFE18FE, 0xFE1CFE, 0xFE20FE, 0xFE24FE, 0xFE28FE, 0xFE2CFE,
            0xFE30FE, 0xFE34FE, 0xFE38FE, 0xFE3CFE, 0xFE40FE, 0xFE44FE, 0xFE48FE, 0xFE4CFE,
            0xFE50FE, 0xFE54FE, 0xFE58FE, 0xFE5CFE, 0xFE60FE, 0xFE64FE, 0xFE68FE, 0xFE6CFE,
            0xFE70FE, 0xFE74FE, 0xFE78FE, 0xFE7CFE, 0xFE80FE, 0xFE84FE, 0xFE88FE, 0xFE8CFE,
            0xFE90FE, 0xFE94FE, 0xFE98FE, 0xFE9CFE, 0xFEA0FE, 0xFEA4FE, 0xFEA8FE, 0xFEACFE,
            0xFEB0FE, 0xFEB4FE, 0xFEB8FE, 0xFEBCFE, 0xFEC0FE, 0xFEC4FE, 0xFEC8FE, 0xFECCFE,
            0xFED0FE, 0xFED4FE, 0xFED8FE, 0xFEDCFE, 0xFEE0FE, 0xFEE4FE, 0xFEE8FE, 0x909090,
    };

    private static final Map<Integer, Integer> INDEX = new HashMap<>();
    static {
        // The first index wins where two share a color; only black does, below 1 mm.
        for (int i = PALETTE.length - 1; i >= 0; i--)
            INDEX.put(PALETTE[i], i);
    }

    /**
     * The box's picture of the {@code hours} ending at {@code endMs}, which is taken
     * down to its hour: the mesonet keeps one picture an hour, on the hour, UTC.
     */
    public static String url(int hours, long endMs, double west, double south, double east, double north,
            int px, int py) {
        String product = PRODUCTS[1];
        for (int i = 0; i < HOURS.length; i++)
            if (HOURS[i] == hours)
                product = PRODUCTS[i];
        final java.util.Calendar c = java.util.Calendar.getInstance(TimeZone.getTimeZone("UTC"));
        c.setTimeInMillis(hourOf(endMs));
        return "https://" + HOST + "/cgi-bin/wms/us/mrms.cgi?SERVICE=WMS&VERSION=1.1.1&REQUEST=GetMap"
                + "&SRS=EPSG:4326" + String.format(Locale.US, "&BBOX=%.4f,%.4f,%.4f,%.4f", west, south, east, north)
                + "&WIDTH=" + px + "&HEIGHT=" + py + "&FORMAT=image/png&TRANSPARENT=true&LAYERS=mrms-archive"
                + "&prod=" + product
                + String.format(Locale.US, "&year=%04d&month=%02d&day=%02d&time=%02d00", c.get(java.util.Calendar.YEAR),
                        c.get(java.util.Calendar.MONTH) + 1, c.get(java.util.Calendar.DAY_OF_MONTH),
                        c.get(java.util.Calendar.HOUR_OF_DAY));
    }

    /** The picture's hour for a moment: the top of the hour at or before it. */
    public static long hourOf(long millis) {
        return (millis / 3_600_000L) * 3_600_000L;
    }

    /** Millimeters for an index: 0.25 mm steps to 25 mm, 1.25 to 125, then 5; NaN for 255, no data. */
    static double mm(int index) {
        if (index <= 0)
            return 0;
        if (index <= 100)
            return index * 0.25;
        if (index <= 180)
            return 25 + (index - 100) * 1.25;
        if (index <= 254)
            return 125 + (index - 180) * 5;
        return Double.NaN;
    }

    private static final Map<Integer, Double> NEAREST = new HashMap<>();

    /**
     * Inches for a pixel's color (RGB, alpha ignored): its index's amount, or the
     * nearest color's where a server's scaling blended one in. NaN for no data.
     */
    public static synchronized double inches(int rgb) {
        final int c = rgb & 0xFFFFFF;
        final Integer at = INDEX.get(c);
        if (at != null)
            return mm(at) / 25.4;
        final Double hit = NEAREST.get(c);
        if (hit != null)
            return hit;
        int best = 0;
        long bestD = Long.MAX_VALUE;
        for (int i = 0; i < PALETTE.length; i++) {
            final int p = PALETTE[i];
            final long dr = ((p >> 16) & 0xFF) - ((c >> 16) & 0xFF), dg = ((p >> 8) & 0xFF) - ((c >> 8) & 0xFF),
                    db = (p & 0xFF) - (c & 0xFF);
            final long d = dr * dr + dg * dg + db * db;
            if (d < bestD) {
                bestD = d;
                best = i;
            }
        }
        final double v = mm(best) / 25.4;
        if (NEAREST.size() < 4096)
            NEAREST.put(c, v);
        return v;
    }

    private IemRadar() {
    }
}
