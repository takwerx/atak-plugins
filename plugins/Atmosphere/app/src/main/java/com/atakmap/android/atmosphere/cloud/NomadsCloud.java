package com.atakmap.android.atmosphere.cloud;

import com.atakmap.android.atmosphere.smoke.SmokeGrid;
import com.atakmap.android.atmosphere.wind.Grib2;
import com.atakmap.android.atmosphere.wind.NomadsWind;
import com.atakmap.android.atmosphere.wind.NomadsWind.Model;

import java.io.IOException;
import java.util.List;

/**
 * The model's cloud forecast from the NOMADS grib filter: the GFS files, runs and
 * forecast hours the wind comes from, asking for the cloud-cover fields. Pure request
 * building, response reading and coloring; the overlay does the fetching and the
 * drawing, so this is unit-tested against a saved response.
 *
 * <h3>What the filter answers, measured on NOMADS 2026-11-21 (18Z run)</h3>
 *
 * <ul>
 *   <li>{@code var_LCDC var_MCDC var_HCDC var_TCDC} with {@code lev_low_cloud_layer},
 *       {@code lev_middle_cloud_layer}, {@code lev_high_cloud_layer} and
 *       {@code lev_entire_atmosphere} return GRIB2 discipline 0, category 6 (cloud),
 *       numbers 3 (low), 4 (middle), 5 (high) and 1 (total), in percent, simple
 *       packing on a lat/lon lattice, so the wind's GRIB2 reader takes them as they
 *       stand. A 11 by 10 degree box with all four fields was 20 KB.</li>
 *   <li>From forecast hour 1 on every field comes twice: product template 4.0 (the
 *       value at that hour) and template 4.8 (the average since hour 0). Hour 0 is
 *       the analysis, template 4.0 only. {@link #read} takes template 4.0 and never
 *       the average, which would draw a different hour than the scrubber says.</li>
 * </ul>
 */
public final class NomadsCloud {

    public static final String HOST = NomadsWind.HOST;

    /** The one model: GFS is global, so Alaska, Hawaii and the borders are covered too. */
    public static final Model MODEL = Model.GFS;

    /**
     * Which clouds are drawn: the three decks a pilot or a crew reads separately, or the
     * whole sky. Colored on one white-to-gray scale in percent of the sky covered:
     * under 10 is left clear so the map shows through, then each band is a little more
     * white and then gray as the sky closes.
     */
    public enum Layer {
        LOW("var_LCDC=on&lev_low_cloud_layer=on", 3),
        MID("var_MCDC=on&lev_middle_cloud_layer=on", 4),
        HIGH("var_HCDC=on&lev_high_cloud_layer=on", 5),
        TOTAL("var_TCDC=on&lev_entire_atmosphere=on", 1);

        /** The filter's variable and level switches. */
        public final String fields;
        /** GRIB2 parameter number within category 6. */
        final int number;

        Layer(String fields, int number) {
            this.fields = fields;
            this.number = number;
        }

        /** The unit the grid is read in. */
        public String unit() {
            return "%";
        }

        /** The band a percent falls in, or -1 below the first (drawn clear) or NaN. */
        public int band(float value) {
            if (Float.isNaN(value) || value < EDGES[0])
                return -1;
            for (int i = EDGES.length - 1; i > 0; i--)
                if (value >= EDGES[i])
                    return i;
            return 0;
        }

        /** The color a value is drawn in, fully transparent when it is not drawn. */
        public int color(float value) {
            final int b = band(value);
            return b < 0 ? 0 : COLORS[b];
        }

        /** "mostly cloudy", or the clear phrase. */
        public String words(float value) {
            final int b = band(value);
            return b < 0 ? CLEAR : WORDS[b];
        }

        /** The legend's colors, one per band, fully opaque so the bar reads on a dark pane. */
        public int[] legendColors() {
            final int[] out = new int[COLORS.length];
            for (int i = 0; i < out.length; i++)
                out[i] = COLORS[i] | 0xFF000000;
            return out;
        }

        /** The numbers under the legend's joins: 30, 55, 80, 95 (the first edge is the clear line). */
        public float[] legendBreaks() {
            final float[] out = new float[EDGES.length - 1];
            for (int i = 1; i < EDGES.length; i++)
                out[i - 1] = EDGES[i];
            return out;
        }
    }

    /** Lower edge of each band in percent; below the first is left clear. */
    private static final float[] EDGES = { 10f, 30f, 55f, 80f, 95f };
    /** One ARGB color per band, white thinning in to gray. */
    private static final int[] COLORS = { 0x55FFFFFF, 0x80E8EBF0, 0x99CCD1DA, 0xAAA6ADB9,
            0xBB7F8794 };
    private static final String[] WORDS = { "a few clouds", "partly cloudy", "mostly cloudy",
            "very cloudy", "overcast" };
    private static final String CLEAR = "clear";

    /**
     * The widest box asked for, degrees across and up. The field is one number per
     * quarter degree, so this is a few hundred kilobytes at most; a view wider than this
     * is drawn for its middle, which the pane says.
     */
    public static final double SPAN_LON = 60.0, SPAN_LAT = 40.0;

    /**
     * The narrowest box asked for, degrees across and up: four cells of the quarter
     * degree field, so the answer always holds a lattice with rows and columns on both
     * sides of any point inside it.
     */
    public static final double MIN_SPAN = 1.0;

    private NomadsCloud() {
    }

    /**
     * {@code [low, high]} of an interval widened about its middle to at least
     * {@code min}, then held inside {@code [limitLow, limitHigh]} by sliding, not
     * shrinking, so a view at the edge of the model still gets the full width.
     */
    public static double[] atLeast(double low, double high, double min, double limitLow,
            double limitHigh) {
        if (high - low < min) {
            final double c = (low + high) / 2;
            low = c - min / 2;
            high = c + min / 2;
        }
        if (low < limitLow) {
            high = Math.min(limitHigh, high + (limitLow - low));
            low = limitLow;
        }
        if (high > limitHigh) {
            low = Math.max(limitLow, low - (high - limitHigh));
            high = limitHigh;
        }
        return new double[] { low, high };
    }

    public static String url(Layer layer, long runUtc, int forecastHour, double west,
            double south, double east, double north) {
        return NomadsWind.filterUrl(MODEL, layer.fields, runUtc, forecastHour, west, south,
                east, north);
    }

    /**
     * Read a filter response into a lattice in percent. Takes the message for the layer
     * at product template 4.0, the value at the hour; template 4.8 is the average since
     * hour 0 and is skipped.
     */
    public static SmokeGrid read(byte[] body, Layer layer) throws IOException {
        final List<Grib2.Message> msgs = Grib2.read(body);
        for (Grib2.Message m : msgs)
            if (m.discipline == 0 && m.category == 6 && m.number == layer.number
                    && m.productTemplate == 0)
                return SmokeGrid.from(m, 1.0);
        throw new IOException("response holds " + msgs.size() + " message(s) and no "
                + layer.name().toLowerCase(java.util.Locale.US) + " cloud cover at the hour");
    }

    /**
     * The picture: {@code width} by {@code height} ARGB pixels over the grid's extent,
     * row 0 the north edge, each pixel the band color of the value at its center.
     */
    public static int[] render(SmokeGrid g, Layer layer, int width, int height) {
        final int[] px = new int[width * height];
        final double dLon = (g.east - g.west) / width, dLat = (g.north - g.south) / height;
        for (int r = 0; r < height; r++) {
            final double lat = g.north - (r + 0.5) * dLat;
            final int row = r * width;
            for (int c = 0; c < width; c++)
                px[row + c] = layer.color(g.sample(lat, g.west + (c + 0.5) * dLon));
        }
        return px;
    }

    /** Share of a picture's pixels that carry cloud, for the log line. */
    public static double cloudyPercent(int[] px) {
        if (px.length == 0)
            return 0;
        int n = 0;
        for (int p : px)
            if (p != 0)
                n++;
        return 100.0 * n / px.length;
    }
}
