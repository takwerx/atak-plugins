package com.atakmap.android.atmosphere.rain;

import com.atakmap.android.atmosphere.wind.Grib2;
import com.atakmap.android.atmosphere.wind.NomadsWind;

import java.io.IOException;
import java.util.List;

public final class NomadsTemperature {
    /**
     * The broadcast weather map's scale: purple through blue, cyan, green, yellow,
     * orange and red to magenta, saturated and nearly opaque, so the bands read from
     * across a room (operator, 2026-11-12: "the 90's weather report look"). Below
     * the purple the scale lightens every five degrees, violet, lavender, lilac to
     * white at -80: at 34,000 ft the whole sky is colder than -40 and was one
     * purple (operator, the same day: "we lose differentiation in color at higher
     * altitudes"), and the cold stays its own family rather than borrowing the
     * temperate hues, so cold reads as cold (operator: "it made it distinct from the
     * temperate colors"). Stops in °C; the gradient between them is read at each
     * band's middle.
     */
    private static final float[] STOPS = { -80, -75, -70, -65, -60, -55, -50, -45, -40, -30, -20,
            -10, 0, 5, 10, 15, 20, 25, 30, 35, 40, 45 };
    private static final int[] COLORS = {
            0xC8FFFFFF, 0xC8E6E6FA, 0xC8D2CDF5, 0xC8BEB4F0, 0xC8AA96EC, 0xC89678E6, 0xC88C5AE0,
            0xC89A3CD2,
            0xC8B000C8, 0xC87B1FE0, 0xC81E3CFF, 0xC80A78FF, 0xC800C8FF, 0xC800D28C, 0xC828C832,
            0xC8A0E600, 0xC8FFE600, 0xC8FFB400, 0xC8FF7800, 0xC8FF3C00, 0xC8E00000, 0xC8B40064 };

    /**
     * The widest box asked for, degrees: the whole globe. Wider than the rain's: a
     * temperature map is read a continent at a time, Alaska beside the lower 48 is
     * 105 degrees across, and a view down on a pole holds every longitude (operator,
     * 2026-11-12). The model is a quarter degree, so even the whole globe is 1441 by
     * 721 values, a few megabytes.
     */
    public static final double MAX_SPAN_LON = 360, MAX_SPAN_LAT = 180;

    private NomadsTemperature() {
    }

    public static String url(long run, int hour, double west, double south, double east, double north) {
        return url(run, hour, west, south, east, north, NomadsWind.Level.AGL_10);
    }

    /**
     * The heights the temperature is offered at: the wind's ladder less its 80 m
     * rung, which the model does not carry a temperature for. The ground rung is
     * the 2 m temperature, the one a thermometer reads; the rest are the standard
     * pressure surfaces a pilot is briefed on, the freezing level among them.
     */
    public static final NomadsWind.Level[] LEVELS = {
            NomadsWind.Level.AGL_10, NomadsWind.Level.MB_925, NomadsWind.Level.MB_850,
            NomadsWind.Level.MB_700, NomadsWind.Level.MB_500, NomadsWind.Level.MB_300,
            NomadsWind.Level.MB_250 };

    /** The filter's name for a rung: the ground is the 2 m temperature. */
    public static String levelParam(NomadsWind.Level level) {
        return level.surface ? "lev_2_m_above_ground" : level.param;
    }

    public static String url(long run, int hour, double west, double south, double east, double north,
            NomadsWind.Level level) {
        return NomadsRain.url(run, hour, west, south, east, north,
            "var_TMP=on&" + levelParam(level) + "=on");
    }

    public static RainGrid read(byte[] body) throws IOException {
        final List<Grib2.Message> messages = Grib2.read(body);
        for (Grib2.Message message : messages) {
            if (message.discipline != 0 || message.category != 0 || message.number != 0
                    || message.latLon == null)
                continue;
            final float[] values = new float[message.latLon.ni * message.latLon.nj];
            for (int y = 0; y < message.latLon.nj; y++) {
                for (int x = 0; x < message.latLon.ni; x++) {
                    final float value = message.at(x, y);
                    values[y * message.latLon.ni + x] = Float.isNaN(value) || value < 100 || value > 400
                            ? Float.NaN : value - 273.15f;
                }
            }
            return new RainGrid(message.latLon, values,
                    message.referenceTime + message.forecastHours * 3_600_000L);
        }
        throw new IOException("response contains no temperature grid");
    }

    public static int color(float celsius) {
        if (Float.isNaN(celsius))
            return 0;
        if (celsius <= STOPS[0])
            return COLORS[0];
        for (int index = 1; index < STOPS.length; index++) {
            if (celsius <= STOPS[index]) {
                final float fraction = (celsius - STOPS[index - 1]) / (STOPS[index] - STOPS[index - 1]);
                int result = 0;
                for (int shift = 0; shift <= 24; shift += 8) {
                    final int low = (COLORS[index - 1] >>> shift) & 255;
                    final int high = (COLORS[index] >>> shift) & 255;
                    result |= Math.round(low + fraction * (high - low)) << shift;
                }
                return result;
            }
        }
        return COLORS[COLORS.length - 1];
    }

    // ---- bands -----------------------------------------------------------------------

    /** Width of a band in the unit shown: five degrees, °F or °C, whichever the operator reads. */
    public static final int BAND_DEGREES = 5;

    public static float toDisplay(float celsius, boolean fahrenheit) {
        return fahrenheit ? celsius * 1.8f + 32 : celsius;
    }

    public static float toCelsius(float display, boolean fahrenheit) {
        return fahrenheit ? (display - 32) / 1.8f : display;
    }

    /** The lower edge of the band a value falls in, in the unit shown; NaN stays NaN. */
    public static float bandFloor(float celsius, boolean fahrenheit) {
        if (Float.isNaN(celsius))
            return Float.NaN;
        return (float) Math.floor(toDisplay(celsius, fahrenheit) / BAND_DEGREES) * BAND_DEGREES;
    }

    /**
     * The color of the band a value falls in: the gradient read at the band's middle,
     * so every point in one band is one color and the bands step rather than blend.
     */
    public static int bandColor(float celsius, boolean fahrenheit) {
        final float floor = bandFloor(celsius, fahrenheit);
        if (Float.isNaN(floor))
            return 0;
        return color(toCelsius(floor + BAND_DEGREES / 2f, fahrenheit));
    }

    /**
     * The lower edges of every band from the grid's coldest to its warmest, in the
     * unit shown, so a legend lists only the bands on the map. At least two, so there
     * is always a join to label. Null when the grid has no values.
     */
    public static float[] bandEdges(RainGrid grid, boolean fahrenheit) {
        float min = Float.POSITIVE_INFINITY, max = Float.NEGATIVE_INFINITY;
        for (float c : grid.rate) {
            if (Float.isNaN(c))
                continue;
            final float floor = bandFloor(c, fahrenheit);
            if (floor < min)
                min = floor;
            if (floor > max)
                max = floor;
        }
        if (min > max)
            return null;
        if (min == max)
            max += BAND_DEGREES;
        final float[] edges = new float[Math.round((max - min) / BAND_DEGREES) + 1];
        for (int i = 0; i < edges.length; i++)
            edges[i] = min + i * BAND_DEGREES;
        return edges;
    }

    /** One opaque color per band in {@code edges}, for the legend bar. */
    public static int[] bandColors(float[] edges, boolean fahrenheit) {
        final int[] out = new int[edges.length];
        for (int i = 0; i < edges.length; i++)
            out[i] = color(toCelsius(edges[i] + BAND_DEGREES / 2f, fahrenheit)) | 0xff000000;
        return out;
    }

    public static int[] render(RainGrid grid, int width, int height) {
        return render(grid, width, height, false);
    }

    /** The picture in five-degree steps of the unit shown; see {@link #bandColor}. */
    public static int[] render(RainGrid grid, int width, int height, boolean fahrenheit) {
        final int[] pixels = new int[width * height];
        final double deltaLon = (grid.east - grid.west) / width;
        final double deltaLat = (grid.north - grid.south) / height;
        for (int row = 0; row < height; row++) {
            final double lat = grid.north - (row + 0.5) * deltaLat;
            for (int column = 0; column < width; column++)
                pixels[row * width + column] = bandColor(
                        grid.sample(lat, grid.west + (column + 0.5) * deltaLon), fahrenheit);
        }
        return pixels;
    }
}