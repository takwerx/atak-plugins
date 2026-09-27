package com.atakmap.android.atmosphere.waves;

import com.atakmap.android.atmosphere.wind.Grib2;
import com.atakmap.android.atmosphere.wind.NomadsWind;

import java.io.IOException;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

/**
 * NOAA's wave model from the NOMADS grib filter: significant wave height, peak period
 * and direction, and the swell trains, for a lon/lat box, one small GRIB2 per forecast
 * hour -- the same filter, files and reader the wind and smoke come through. Pure
 * request building, response reading and coloring; the overlay fetches and draws, so
 * this is unit-tested against a saved response.
 *
 * <h3>Measured on NOMADS, 2026-09-27</h3>
 *
 * <ul>
 *   <li>{@code filter_gfswave.pl}, files under {@code /gfs.YYYYMMDD/HH/wave/gridded/},
 *       runs at 00/06/12/18Z, hourly to f120 then three-hourly.</li>
 *   <li>{@code global.0p16} is a sixth of a degree at every longitude from 15 S to
 *       52.5 N: the lower 48's coasts, Hawaii, the Caribbean, Mexico. A box asked of
 *       it OUTSIDE those latitudes comes back as the whole 3 MB file, so the request
 *       is clamped first. {@code global.0p25} is a quarter degree to 77.5 N and is
 *       what serves Alaska. A 7 by 5 degree box with every swell field is 18 KB.</li>
 *   <li>Fields, all discipline 10 category 0, grid template 3.0, simple packing, land
 *       under the bitmap: HTSGW 3, PERPW 11, DIRPW 10, and the swell trains SWELL 8,
 *       SWPER 9, SWDIR 7 three times each, in order. Directions are where the waves
 *       come FROM, degrees true.</li>
 * </ul>
 */
public final class NomadsWaves {

    public static final String HOST = NomadsWind.HOST;
    private static final String FILTER = "https://" + HOST + "/cgi-bin/filter_gfswave.pl";
    private static final String FIELDS = "var_HTSGW=on&var_DIRPW=on&var_PERPW=on&var_SWELL=on"
            + "&var_SWDIR=on&var_SWPER=on";
    /** Hours between runs, and how far back the newest run that is certainly published sits. */
    private static final int RUN_STEP = 6, LAG = 5;
    /** Hourly files stop here; three-hourly ones follow and the picker counts in hours. */
    public static final int HOURS = 120;
    /** The widest box asked for, degrees; a wider view is drawn for its middle. */
    public static final double MAX_SPAN_LON = 40, MAX_SPAN_LAT = 30;

    /** The two global grids, by the latitudes they cover. */
    public enum Grid {
        /** A sixth of a degree, 15 S to 52.5 N. */
        FINE("global.0p16", -15, 52.5),
        /** A quarter degree, to 77.5 N: Alaska. */
        COARSE("global.0p25", -77.5, 77.5);

        public final String file;
        public final double south, north;

        Grid(String file, double south, double north) {
            this.file = file;
            this.south = south;
            this.north = north;
        }
    }

    private NomadsWaves() {
    }

    /** The finer grid when the view's latitudes fit in it, else the coarse one. */
    public static Grid forView(double south, double north) {
        return north <= Grid.FINE.north && south >= Grid.FINE.south ? Grid.FINE : Grid.COARSE;
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

    /** The filter request for the wave fields over a box at a forecast hour of a run. */
    public static String url(Grid grid, long runUtc, int forecastHour, double west, double south,
            double east, double north) {
        final Calendar c = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
        c.setTimeInMillis(runUtc);
        final String day = String.format(Locale.US, "%04d%02d%02d", c.get(Calendar.YEAR),
                c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH));
        final String hh = String.format(Locale.US, "%02d", c.get(Calendar.HOUR_OF_DAY));
        return FILTER + "?dir=%2Fgfs." + day + "%2F" + hh + "%2Fwave%2Fgridded"
                + "&file=gfswave.t" + hh + "z." + grid.file + ".f"
                + String.format(Locale.US, "%03d", forecastHour) + ".grib2"
                + "&" + FIELDS + "&subregion="
                + String.format(Locale.US, "&toplat=%.3f&leftlon=%.3f&rightlon=%.3f&bottomlat=%.3f",
                        Math.min(grid.north, north), west, east, Math.max(grid.south, south));
    }

    /** Read a filter response into a lattice. */
    public static WaveGrid read(byte[] body) throws IOException {
        final List<Grib2.Message> msgs = Grib2.read(body);
        Grib2.Message height = null, period = null, dir = null, swellH = null, swellP = null,
                swellD = null;
        for (Grib2.Message m : msgs) {
            if (m.discipline != 10 || m.category != 0 || m.latLon == null)
                continue;
            switch (m.number) {
                case 3: if (height == null) height = m; break;
                case 11: if (period == null) period = m; break;
                case 10: if (dir == null) dir = m; break;
                case 8: if (swellH == null) swellH = m; break;   // the first train
                case 9: if (swellP == null) swellP = m; break;
                case 7: if (swellD == null) swellD = m; break;
                default: break;
            }
        }
        if (height == null)
            throw new IOException("response holds " + msgs.size() + " message(s) and no wave height");
        final long valid = height.referenceTime + height.forecastHours * 3_600_000L;
        return new WaveGrid(height.latLon, WaveGrid.field(height), WaveGrid.field(period),
                WaveGrid.field(dir), WaveGrid.field(swellH), WaveGrid.field(swellP),
                WaveGrid.field(swellD), valid);
    }

    // ---- sea state -------------------------------------------------------------------

    /**
     * The WMO sea state scale (code table 3700), which is what the marine forecasts
     * and every mariner's "rough" or "moderate" come from: the lower edge of each
     * state in meters of significant wave height. Below the first is calm to smooth.
     */
    public static final float[] EDGES_M = { 0.5f, 1.25f, 2.5f, 4f, 6f, 9f, 14f };
    public static final String[] WORDS = { "smooth", "slight", "moderate", "rough", "very rough",
            "high", "very high", "phenomenal" };
    /** One color per state, calm to phenomenal, drawn at these alphas over the sea. */
    private static final int[] COLORS = { 0x66A8D8F0, 0x7770C8E8, 0x8848B0D8, 0x8830D070,
            0x99E8E040, 0xA8F09020, 0xB8E02020, 0xC8A020C0 };

    /** The state a height falls in, 0 below the first edge; -1 for NaN (land). */
    public static int band(float meters) {
        if (Float.isNaN(meters))
            return -1;
        for (int i = EDGES_M.length - 1; i >= 0; i--)
            if (meters >= EDGES_M[i])
                return i + 1;
        return 0;
    }

    public static int color(float meters) {
        final int b = band(meters);
        return b < 0 ? 0 : COLORS[b];
    }

    public static String words(float meters) {
        final int b = band(meters);
        return b < 0 ? "" : WORDS[b];
    }

    /** The legend's colors, opaque, one per state. */
    public static int[] legendColors() {
        final int[] out = new int[COLORS.length];
        for (int i = 0; i < out.length; i++)
            out[i] = COLORS[i] | 0xFF000000;
        return out;
    }

    /** The numbers under the legend's joins, in feet or meters. */
    public static float[] legendBreaks(boolean metric) {
        final float[] out = new float[EDGES_M.length];
        for (int i = 0; i < out.length; i++)
            out[i] = metric ? EDGES_M[i] : Math.round(EDGES_M[i] / 0.3048f);
        return out;
    }

    /** "6 ft" or "1.8 m". */
    public static String heightText(float meters, boolean metric) {
        if (Float.isNaN(meters))
            return "";
        if (metric)
            return String.format(Locale.US, meters < 3 ? "%.1f m" : "%.0f m", meters);
        final double ft = meters / 0.3048;
        return String.format(Locale.US, ft < 3 ? "%.1f ft" : "%.0f ft", ft);
    }

    /** Sixteen-point compass name for a direction in degrees. */
    public static String compass(float degrees) {
        if (Float.isNaN(degrees))
            return "";
        final String[] pts = { "N", "NNE", "NE", "ENE", "E", "ESE", "SE", "SSE", "S", "SSW", "SW",
                "WSW", "W", "WNW", "NW", "NNW" };
        return pts[(int) Math.floor((((degrees % 360) + 360) % 360 + 11.25) / 22.5) % 16];
    }

    // ---- the picture -----------------------------------------------------------------

    /** Arrows this many pixels apart. */
    private static final int ARROW_STEP = 44;
    private static final int ARROW_LEN = 16;

    /**
     * The picture: wave height as a colored field, {@code width} by {@code height}
     * ARGB pixels over the grid's extent, row 0 the north edge, land clear; then an
     * arrow every {@link #ARROW_STEP} pixels pointing the way the primary swell is
     * going (the model gives where it comes from; the arrow flies with it, the way a
     * crew on the water reads it), drawn white over a dark outline so it reads on any
     * band. When the model names no swell the peak wave direction is used.
     */
    public static int[] render(WaveGrid g, int width, int height) {
        final int[] px = new int[width * height];
        final double dLon = (g.east - g.west) / width, dLat = (g.north - g.south) / height;
        for (int r = 0; r < height; r++) {
            final double lat = g.north - (r + 0.5) * dLat;
            final int row = r * width;
            for (int c = 0; c < width; c++)
                px[row + c] = color(g.sample(g.height, lat, g.west + (c + 0.5) * dLon));
        }
        final float[] dirs = g.swellDir != null ? g.swellDir : g.dir;
        if (dirs == null)
            return px;
        for (int r = ARROW_STEP / 2; r < height; r += ARROW_STEP) {
            final double lat = g.north - (r + 0.5) * dLat;
            for (int c = ARROW_STEP / 2; c < width; c += ARROW_STEP) {
                final double lon = g.west + (c + 0.5) * dLon;
                final float from = g.nearestAt(dirs, lat, lon);
                final float h = g.sample(g.height, lat, lon);
                if (Float.isNaN(from) || Float.isNaN(h))
                    continue;
                // Toward: the direction it comes from, turned around. Screen y grows
                // southward, so north is -y.
                final double to = Math.toRadians(from + 180);
                final double dx = Math.sin(to), dy = -Math.cos(to);
                arrow(px, width, height, c, r, dx, dy);
            }
        }
        return px;
    }

    /** An arrow of {@link #ARROW_LEN} pixels from its middle, outline first, then white. */
    private static void arrow(int[] px, int w, int h, int cx, int cy, double dx, double dy) {
        final double half = ARROW_LEN / 2.0;
        final int x0 = (int) Math.round(cx - dx * half), y0 = (int) Math.round(cy - dy * half);
        final int x1 = (int) Math.round(cx + dx * half), y1 = (int) Math.round(cy + dy * half);
        // Head: two strokes back from the tip at 30 degrees.
        final double back = 6, ang = Math.toRadians(30);
        final double ax = dx * Math.cos(ang) - dy * Math.sin(ang), ay = dx * Math.sin(ang) + dy * Math.cos(ang);
        final double bx = dx * Math.cos(-ang) - dy * Math.sin(-ang), by = dx * Math.sin(-ang) + dy * Math.cos(-ang);
        final int hx1 = (int) Math.round(x1 - ax * back), hy1 = (int) Math.round(y1 - ay * back);
        final int hx2 = (int) Math.round(x1 - bx * back), hy2 = (int) Math.round(y1 - by * back);
        for (int pass = 0; pass < 2; pass++) {
            final int color = pass == 0 ? 0xC0202020 : 0xFFFFFFFF;
            final int thick = pass == 0 ? 1 : 0;
            line(px, w, h, x0, y0, x1, y1, color, thick);
            line(px, w, h, x1, y1, hx1, hy1, color, thick);
            line(px, w, h, x1, y1, hx2, hy2, color, thick);
        }
    }

    /** Bresenham, with an optional one-pixel halo for the outline pass. */
    private static void line(int[] px, int w, int h, int x0, int y0, int x1, int y1, int color,
            int halo) {
        int dx = Math.abs(x1 - x0), sx = x0 < x1 ? 1 : -1;
        int dy = -Math.abs(y1 - y0), sy = y0 < y1 ? 1 : -1;
        int err = dx + dy;
        int x = x0, y = y0;
        while (true) {
            for (int oy = -halo; oy <= halo; oy++)
                for (int ox = -halo; ox <= halo; ox++) {
                    final int X = x + ox, Y = y + oy;
                    if (X >= 0 && X < w && Y >= 0 && Y < h)
                        px[Y * w + X] = color;
                }
            if (x == x1 && y == y1)
                break;
            final int e2 = 2 * err;
            if (e2 >= dy) { err += dy; x += sx; }
            if (e2 <= dx) { err += dx; y += sy; }
        }
    }

    /** Share of a picture's pixels that are sea, for the log line. */
    public static double seaPercent(int[] px) {
        if (px.length == 0)
            return 0;
        int n = 0;
        for (int p : px)
            if (p != 0)
                n++;
        return 100.0 * n / px.length;
    }
}
