package com.atakmap.android.atmosphere.rain;

import com.atakmap.android.atmosphere.wind.Grib2;

import java.io.IOException;
import java.util.List;

public final class NomadsTemperature {
    private static final float[] STOPS = { -40, -20, 0, 10, 20, 30, 40, 50 };
    private static final int[] COLORS = { 0x9c663399, 0x9c285ec8, 0x9c32b8d0, 0x9c48b678,
            0x9ce1d84c, 0x9cf59b38, 0x9cd94432, 0x9c9b244a };

    private NomadsTemperature() {
    }

    public static String url(long run, int hour, double west, double south, double east, double north) {
        return NomadsRain.url(run, hour, west, south, east, north,
            "var_TMP=on&lev_2_m_above_ground=on");
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

    public static int[] render(RainGrid grid, int width, int height) {
        final int[] pixels = new int[width * height];
        final double deltaLon = (grid.east - grid.west) / width;
        final double deltaLat = (grid.north - grid.south) / height;
        for (int row = 0; row < height; row++) {
            final double lat = grid.north - (row + 0.5) * deltaLat;
            for (int column = 0; column < width; column++)
                pixels[row * width + column] = color(grid.sample(lat, grid.west + (column + 0.5) * deltaLon));
        }
        return pixels;
    }
}