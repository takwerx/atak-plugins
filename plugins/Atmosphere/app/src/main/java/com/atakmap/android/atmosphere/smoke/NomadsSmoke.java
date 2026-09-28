package com.atakmap.android.atmosphere.smoke;

import com.atakmap.android.atmosphere.wind.Grib2;
import com.atakmap.android.atmosphere.wind.NomadsWind;
import com.atakmap.android.atmosphere.wind.NomadsWind.Model;

import java.io.IOException;
import java.util.List;

/**
 * The model's smoke forecast from the NOMADS grib filter: the same files, runs and
 * forecast hours the wind comes from, asking for a different field. Pure request
 * building, response reading and coloring; the overlay does the fetching and the
 * drawing, so this is unit-tested against a saved response.
 *
 * <h3>What the model carries, measured on NOMADS 2026-09-24</h3>
 *
 * <ul>
 *   <li>HRRR's surface file ({@code wrfsfcf}) holds {@code MASSDEN} at 8 m above
 *       ground, kg/m3, and {@code COLMD} for the whole atmosphere, kg/m2. Both are
 *       product template 4.0 in simple packing, discipline 0 category 20 (chemical
 *       constituents), numbers 0 and 1, so the wind's GRIB2 reader takes them as they
 *       stand. Present at f00, f18 and f48. An 8 by 6 degree box is 150 KB.</li>
 *   <li>RAP's {@code awp130pgrbf} holds {@code MASSDEN} at 8 m, same template, and no
 *       column field. The whole CONUS at 13 km is 264 KB, cheap enough for a
 *       continental view of the near-ground smoke.</li>
 *   <li>GFS carries neither. Outside HRRR and RAP's lower 48 there is no smoke
 *       forecast here, and the layer says so.</li>
 * </ul>
 */
public final class NomadsSmoke {

    public static final String HOST = NomadsWind.HOST;

    /**
     * Where the smoke is read: at the ground, which is what a crew breathes, or through
     * the whole sky, which is what they see and what the sun and the aircraft fly
     * through. The two presets the model offers, the way the wind offers heights.
     */
    public enum Height {
        /**
         * Near-ground concentration, colored on EPA's PM2.5 Air Quality Index bands
         * (the 2024 breakpoints: 9.0, 35.4, 55.4, 125.4, 225.4 ug/m3) in the AQI's own
         * colors, so it reads like the air quality map beside it. Below 5 ug/m3 is
         * left clear: measured over northern California, 60% of the ground sat
         * between 1 and 4 and a floor there hazed the whole map. From 5 to 9 the air
         * is still good and is drawn as a gray haze, not as the AQI's green, because a
         * green wash over a smoke map reads as "all clear" in the wrong place.
         */
        GROUND("var_MASSDEN=on&lev_8_m_above_ground=on", 0, 1e9, "µg/m³",
                new float[] { 5f, 9.05f, 35.45f, 55.45f, 125.45f, 225.45f },
                new int[] { 0x55A0A0A0, 0x99FFFF00, 0x99FF7E00, 0x99FF0000, 0xA88F3F97,
                        0xB87E0023 },
                new String[] { "light smoke, the air is still good", "moderate",
                        "unhealthy for sensitive groups", "unhealthy", "very unhealthy",
                        "hazardous" },
                "little or no smoke"),
        /**
         * Smoke through the whole column, a gray-to-brown ramp by thickness. There is no
         * health scale for smoke overhead, so the bands are where haze becomes visible
         * and then heavy, roughly optical depth 0.07, 0.2, 0.45, 0.9 and 2 at the mass
         * extinction smoke usually has. Measured over northern California: median
         * 3.6 mg/m2, 1% above 58, a plume at 716. HRRR only.
         */
        SKY("var_COLMD=on&lev_entire_atmosphere_%5C%28considered_as_a_single_layer%5C%29=on",
                1, 1e6, "mg/m²",
                new float[] { 15f, 50f, 100f, 200f, 500f },
                new int[] { 0x66C8C8C8, 0x80BFA88A, 0x99A0785A, 0xB07A4E32, 0xC04A2A1A },
                new String[] { "thin haze overhead", "hazy overhead", "thick smoke overhead",
                        "very thick smoke overhead", "dense smoke overhead" },
                "little or no smoke overhead");

        /** The filter's variable and level switches. */
        public final String fields;
        /** GRIB2 parameter number within category 20. */
        final int number;
        /** Multiplies the file's SI value into {@link #unit}. */
        final double scale;
        /** The unit the grid is read in. */
        public final String unit;
        /** Lower edge of each band, in {@link #unit}; below the first is left clear. */
        final float[] edges;
        /** One ARGB color per band. */
        final int[] colors;
        /** What each band means, in words, for the reading at a point. */
        final String[] words;
        /** What below the first band means. */
        final String clear;

        Height(String fields, int number, double scale, String unit, float[] edges,
                int[] colors, String[] words, String clear) {
            this.fields = fields;
            this.number = number;
            this.scale = scale;
            this.unit = unit;
            this.edges = edges;
            this.colors = colors;
            this.words = words;
            this.clear = clear;
        }

        /** The band a value falls in, or -1 below the first (drawn clear) or NaN. */
        public int band(float value) {
            if (Float.isNaN(value) || value < edges[0])
                return -1;
            for (int i = edges.length - 1; i > 0; i--)
                if (value >= edges[i])
                    return i;
            return 0;
        }

        /** The color a value is drawn in, fully transparent when it is not drawn. */
        public int color(float value) {
            final int b = band(value);
            return b < 0 ? 0 : colors[b];
        }

        /** "unhealthy for sensitive groups", "thick smoke overhead", or the clear phrase. */
        public String words(float value) {
            final int b = band(value);
            return b < 0 ? clear : words[b];
        }

        /** The legend's colors, one per band, fully opaque so the bar reads on a dark pane. */
        public int[] legendColors() {
            final int[] out = new int[colors.length];
            for (int i = 0; i < out.length; i++)
                out[i] = colors[i] | 0xFF000000;
            return out;
        }

        /**
         * The numbers under the legend's joins, as the scale publishes them: 9, 35, 55,
         * 125, 225 near the ground. Empty for the sky, whose ends are words instead.
         */
        public float[] legendBreaks() {
            if (this != GROUND)
                return new float[0];
            final float[] out = new float[edges.length - 1];
            for (int i = 1; i < edges.length; i++)
                out[i - 1] = (float) Math.floor(edges[i]);
            return out;
        }

        /** True when a model carries this field. Only HRRR has the column. */
        public boolean carriedBy(Model m) {
            return m == Model.HRRR || (m == Model.RAP && this == GROUND);
        }
    }

    private NomadsSmoke() {
    }

    /**
     * The model for a view: HRRR close in, where 3 km cells follow a plume down a
     * drainage; RAP when the view is wider than HRRR is asked for, near the ground
     * only. Null when the view's center is outside the lower 48, where no model here
     * carries smoke.
     */
    public static Model forView(double west, double south, double east, double north,
            Height height) {
        final double cLat = (north + south) / 2, cLon = (east + west) / 2;
        if (!Model.HRRR.covers(cLat, cLon))
            return null;
        final boolean fitsHrrr = east - west <= Model.HRRR.maxSpanLon
                && north - south <= Model.HRRR.maxSpanLat;
        if (fitsHrrr || !height.carriedBy(Model.RAP))
            return Model.HRRR;
        return Model.RAP;
    }

    /**
     * The widest box worth asking a model for, degrees across and up. HRRR as the wind
     * has it; RAP its whole grid, because a smoke picture with the coasts cut off is a
     * wrong picture and all of CONUS is 264 KB.
     */
    public static double maxSpanLon(Model m) {
        return m == Model.RAP ? m.east - m.west : m.maxSpanLon;
    }

    public static double maxSpanLat(Model m) {
        return m == Model.RAP ? m.north - m.south : m.maxSpanLat;
    }

    public static String url(Model model, Height height, long runUtc, int forecastHour,
            double west, double south, double east, double north) {
        return NomadsWind.filterUrl(model, height.fields, runUtc, forecastHour, west, south,
                east, north);
    }

    /** Read a filter response into a lattice in the height's own unit. */
    public static SmokeGrid read(byte[] body, Height height) throws IOException {
        final List<Grib2.Message> msgs = Grib2.read(body);
        for (Grib2.Message m : msgs)
            if (m.discipline == 0 && m.category == 20 && m.number == height.number)
                return SmokeGrid.from(m, height.scale);
        throw new IOException("response holds " + msgs.size() + " message(s) and no "
                + (height == Height.GROUND ? "MASSDEN" : "COLMD"));
    }

    /**
     * The picture: {@code width} by {@code height} ARGB pixels over the grid's extent,
     * row 0 the north edge, each pixel the band color of the value at its center.
     * Sampled bilinearly, so at twice the grid's size the band edges follow the field
     * rather than the cells.
     */
    public static int[] render(SmokeGrid g, Height h, int width, int height) {
        final int[] px = new int[width * height];
        final double dLon = (g.east - g.west) / width, dLat = (g.north - g.south) / height;
        for (int r = 0; r < height; r++) {
            final double lat = g.north - (r + 0.5) * dLat;
            final int row = r * width;
            for (int c = 0; c < width; c++)
                px[row + c] = h.color(g.sample(lat, g.west + (c + 0.5) * dLon));
        }
        return px;
    }

    /** Share of a picture's pixels that carry smoke, for the log line. */
    public static double smokyPercent(int[] px) {
        if (px.length == 0)
            return 0;
        int n = 0;
        for (int p : px)
            if (p != 0)
                n++;
        return 100.0 * n / px.length;
    }
}
