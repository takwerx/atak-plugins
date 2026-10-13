package com.atakmap.android.atmosphere.waves;

import com.atakmap.android.atmosphere.wind.Grib2;
import com.atakmap.android.atmosphere.wind.LatLonGrid;

/**
 * One forecast hour of the wave model on a plain lon/lat lattice: significant wave
 * height, the peak period and the direction the waves come from, and the first swell
 * train's height, period and direction. Land is NaN. Pure data, no Android types.
 */
public final class WaveGrid {

    public final double west, south, east, north;
    public final int nx, ny;
    /** Significant wave height, meters; NaN over land. */
    public final float[] height;
    /** Peak period, seconds. */
    public final float[] period;
    /** Direction the peak waves come from, degrees true; NaN where there are none. */
    public final float[] dir;
    /** The primary swell train: height in meters, period in seconds, direction from. */
    public final float[] swellHeight, swellPeriod, swellDir;
    /** UTC millis this hour is valid for. */
    public final long validTime;

    WaveGrid(LatLonGrid g, float[] height, float[] period, float[] dir, float[] swellHeight,
            float[] swellPeriod, float[] swellDir, long validTime) {
        this.west = g.west;
        this.south = g.south;
        this.east = g.east;
        this.north = g.north;
        this.nx = g.ni;
        this.ny = g.nj;
        this.height = height;
        this.period = period;
        this.dir = dir;
        this.swellHeight = swellHeight;
        this.swellPeriod = swellPeriod;
        this.swellDir = swellDir;
        this.validTime = validTime;
    }

    public boolean contains(double lat, double lon) {
        return lat >= south && lat <= north && lon >= west && lon <= east;
    }

    /** Bilinear sample of a field, NaN outside or where any corner is land. */
    public float sample(float[] field, double lat, double lon) {
        if (field == null || !contains(lat, lon) || nx < 2 || ny < 2)
            return Float.NaN;
        final double fx = (lon - west) / (east - west) * (nx - 1);
        final double fy = (lat - south) / (north - south) * (ny - 1);
        final int x0 = (int) Math.floor(fx), y0 = (int) Math.floor(fy);
        final int x1 = Math.min(nx - 1, x0 + 1), y1 = Math.min(ny - 1, y0 + 1);
        final double tx = fx - x0, ty = fy - y0;
        final float a00 = field[y0 * nx + x0], a10 = field[y0 * nx + x1];
        final float a01 = field[y1 * nx + x0], a11 = field[y1 * nx + x1];
        // A coast: take the nearest sea cell rather than smear land into the water.
        if (Float.isNaN(a00) || Float.isNaN(a10) || Float.isNaN(a01) || Float.isNaN(a11))
            return nearest(field, fx, fy);
        return (float) ((a00 * (1 - tx) + a10 * tx) * (1 - ty) + (a01 * (1 - tx) + a11 * tx) * ty);
    }

    /** The nearest cell's value, for directions and for coasts. */
    public float nearest(float[] field, double fx, double fy) {
        final int x = (int) Math.max(0, Math.min(nx - 1, Math.round(fx)));
        final int y = (int) Math.max(0, Math.min(ny - 1, Math.round(fy)));
        return field[y * nx + x];
    }

    /** The nearest cell's value at a position. */
    public float nearestAt(float[] field, double lat, double lon) {
        if (field == null || !contains(lat, lon) || nx < 2 || ny < 2)
            return Float.NaN;
        return nearest(field, (lon - west) / (east - west) * (nx - 1),
                (lat - south) / (north - south) * (ny - 1));
    }

    /** Copy a message's values into row-major, row 0 the south edge, land NaN. */
    static float[] field(Grib2.Message m) {
        if (m == null)
            return null;
        final LatLonGrid g = m.latLon;
        final float[] out = new float[g.ni * g.nj];
        for (int y = 0; y < g.nj; y++)
            for (int x = 0; x < g.ni; x++) {
                final float v = m.at(x, y);
                // The wave files mark land with the bitmap; some carry 9999 instead.
                out[y * g.ni + x] = v > 9000 ? Float.NaN : v;
            }
        return out;
    }
}
