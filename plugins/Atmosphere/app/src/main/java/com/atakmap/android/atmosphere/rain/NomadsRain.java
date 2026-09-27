package com.atakmap.android.atmosphere.rain;

import com.atakmap.android.atmosphere.wind.Grib2;
import com.atakmap.android.atmosphere.wind.NomadsWind;

import java.io.IOException;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

/**
 * NOAA's global forecast model's rain from the NOMADS grib filter: the rate rain is
 * falling, for a lon/lat box, one small GRIB2 per forecast hour -- the same filter
 * and reader the wind, smoke and waves come through. A forecast of rain, not radar:
 * the operator saw a hurricane's bands drawn this way in another app and asked for
 * the same (2026-09-27, "yeah why not build in the rain layer"), and the two are
 * kept apart on purpose -- the radar layer is what is falling where a radar sees,
 * this is what the model expects everywhere, including 300 miles out to sea where
 * no radar reaches. Pure request building, response reading and coloring; the
 * overlay fetches and draws, so this is unit-tested against a saved response.
 *
 * <h3>Measured on NOMADS, 2026-09-27</h3>
 *
 * <ul>
 *   <li>{@code filter_gfs_0p25.pl}, files under {@code /gfs.YYYYMMDD/HH/atmos/},
 *       runs at 00/06/12/18Z, hourly to f120 then three-hourly. A quarter degree,
 *       the whole globe; a 20 by 15 degree box is 9 KB.</li>
 *   <li>{@code var_PRATE=on&lev_surface=on} answers discipline 0 category 1 number
 *       7, grid template 3.0, simple packing, no bitmap. Every hour carries the
 *       instantaneous rate (product template 4.0); from f001 on a second message is
 *       the average over the bucket so far (template 4.8, its forecast time the
 *       bucket's start). The instantaneous one is drawn.</li>
 *   <li>Units are kilograms per square meter per second, which is millimeters of
 *       water a second; {@link RainGrid#field} makes it millimeters an hour.</li>
 * </ul>
 */
public final class NomadsRain {

    public static final String HOST = NomadsWind.HOST;
    private static final String FILTER = "https://" + HOST + "/cgi-bin/filter_gfs_0p25.pl";
    private static final String FIELDS = "var_PRATE=on&lev_surface=on";
    /** Hours between runs, and how far back the newest run that is certainly published sits. */
    private static final int RUN_STEP = 6, LAG = 5;
    /** Hourly files stop here; three-hourly ones follow and the picker counts in hours. */
    public static final int HOURS = 120;
    /** The widest box asked for, degrees; a wider view is drawn for its middle. */
    public static final double MAX_SPAN_LON = 40, MAX_SPAN_LAT = 30;

    private NomadsRain() {
    }

    /** The newest run certainly published, as UTC millis on a run boundary. */
    public static long latestRun(long nowUtc) {
        final Calendar c = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
        c.setTimeInMillis(nowUtc - LAG * 3_600_000L);
        c.set(Calendar.HOUR_OF_DAY, (c.get(Calendar.HOUR_OF_DAY) / RUN_STEP) * RUN_STEP);
        c.set(Calendar.MINUTE, 0);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        return c.getTimeInMillis();
    }

    public static long previousRun(long runUtc) {
        return runUtc - RUN_STEP * 3_600_000L;
    }

    /** The filter request for the rain rate over a box at a forecast hour of a run. */
    public static String url(long runUtc, int forecastHour, double west, double south,
            double east, double north) {
        final Calendar c = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
        c.setTimeInMillis(runUtc);
        final String day = String.format(Locale.US, "%04d%02d%02d", c.get(Calendar.YEAR),
                c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH));
        final String hh = String.format(Locale.US, "%02d", c.get(Calendar.HOUR_OF_DAY));
        return FILTER + "?dir=%2Fgfs." + day + "%2F" + hh + "%2Fatmos"
                + "&file=gfs.t" + hh + "z.pgrb2.0p25.f"
                + String.format(Locale.US, "%03d", forecastHour)
                + "&" + FIELDS + "&subregion="
                + String.format(Locale.US, "&toplat=%.3f&leftlon=%.3f&rightlon=%.3f&bottomlat=%.3f",
                        Math.min(90, north), west, east, Math.max(-90, south));
    }

    /**
     * Read a filter response into a lattice: the instantaneous rate, or the bucket
     * average when a file carries only that.
     */
    public static RainGrid read(byte[] body) throws IOException {
        final List<Grib2.Message> msgs = Grib2.read(body);
        Grib2.Message instant = null, average = null;
        for (Grib2.Message m : msgs) {
            if (m.discipline != 0 || m.category != 1 || m.number != 7 || m.latLon == null)
                continue;
            if (m.productTemplate == 0) {
                if (instant == null)
                    instant = m;
            } else if (average == null) {
                average = m;
            }
        }
        final Grib2.Message use = instant != null ? instant : average;
        if (use == null)
            throw new IOException("response holds " + msgs.size() + " message(s) and no rain rate");
        final long valid = use.referenceTime + use.forecastHours * 3_600_000L;
        return new RainGrid(use.latLon, RainGrid.field(use), valid);
    }

    // ---- how hard it is raining ------------------------------------------------------

    /**
     * The lower edge of each band in inches an hour, the words a forecaster uses for
     * them (the AMS glossary's light, moderate and heavy, the NWS's very heavy past
     * an inch an hour, the WMO's violent past two). Below the first edge nothing is
     * drawn: a hundredth of an inch an hour is the drizzle a model puts everywhere.
     */
    public static final float[] EDGES_IN = { 0.01f, 0.1f, 0.3f, 1f, 2f };
    public static final String[] WORDS = { "light rain", "moderate rain", "heavy rain",
            "very heavy rain", "violent rain" };
    /** One color per band, light to violent, drawn at these alphas over the map. */
    private static final int[] COLORS = { 0x8858B8F0, 0x9938C060, 0xAAF0E030, 0xBBF08020,
            0xCCE02020 };
    static final float MM_PER_IN = 25.4f;

    /** The band a rate falls in, 0 to 4; -1 for no rain or no value. */
    public static int band(float mmPerHr) {
        if (Float.isNaN(mmPerHr))
            return -1;
        final float in = mmPerHr / MM_PER_IN;
        for (int i = EDGES_IN.length - 1; i >= 0; i--)
            if (in >= EDGES_IN[i])
                return i;
        return -1;
    }

    public static int color(float mmPerHr) {
        final int b = band(mmPerHr);
        return b < 0 ? 0 : COLORS[b];
    }

    public static String words(float mmPerHr) {
        final int b = band(mmPerHr);
        return b < 0 ? "" : WORDS[b];
    }

    /** The legend's colors, opaque, one per band. */
    public static int[] legendColors() {
        final int[] out = new int[COLORS.length];
        for (int i = 0; i < out.length; i++)
            out[i] = COLORS[i] | 0xFF000000;
        return out;
    }

    /** The numbers under the legend's joins: inches or millimeters an hour, rounded the way a forecast says them. */
    public static float[] legendBreaks(boolean metric) {
        if (!metric)
            return new float[] { 0.1f, 0.3f, 1f, 2f };
        return new float[] { 2.5f, 7.5f, 25f, 50f };
    }

    /** "0.25 in/hr" or "6.4 mm/hr". */
    public static String rateText(float mmPerHr, boolean metric) {
        if (Float.isNaN(mmPerHr))
            return "";
        if (metric)
            return String.format(Locale.US, mmPerHr < 10 ? "%.1f mm/hr" : "%.0f mm/hr", mmPerHr);
        final double in = mmPerHr / MM_PER_IN;
        return String.format(Locale.US, in < 1 ? "%.2f in/hr" : "%.1f in/hr", in);
    }

    // ---- the picture -----------------------------------------------------------------

    /**
     * The picture: the rain rate as a colored field, {@code width} by {@code height}
     * ARGB pixels over the grid's extent, row 0 the north edge, clear where it is
     * not raining. Sampled between cells so a quarter-degree model draws as weather
     * rather than as tiles.
     */
    public static int[] render(RainGrid g, int width, int height) {
        final int[] px = new int[width * height];
        final double dLon = (g.east - g.west) / width, dLat = (g.north - g.south) / height;
        for (int r = 0; r < height; r++) {
            final double lat = g.north - (r + 0.5) * dLat;
            final int row = r * width;
            for (int c = 0; c < width; c++)
                px[row + c] = color(g.sample(lat, g.west + (c + 0.5) * dLon));
        }
        return px;
    }

    /** Share of a picture's pixels that carry rain, for the log line. */
    public static double rainPercent(int[] px) {
        if (px.length == 0)
            return 0;
        int n = 0;
        for (int p : px)
            if (p != 0)
                n++;
        return 100.0 * n / px.length;
    }
}
