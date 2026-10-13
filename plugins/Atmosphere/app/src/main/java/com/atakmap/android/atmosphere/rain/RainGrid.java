package com.atakmap.android.atmosphere.rain;

import com.atakmap.android.atmosphere.wind.Grib2;
import com.atakmap.android.atmosphere.wind.LatLonGrid;

/**
 * One forecast hour of the model's rain on a plain lon/lat lattice: the rate rain is
 * falling, in millimeters an hour. Pure data, no Android types. The wave lattice's
 * shape with one field; copied rather than shared, the way the helpers here are.
 */
public final class RainGrid {

    public final double west, south, east, north;
    public final int nx, ny;
    /** Rain rate, mm per hour; NaN where the model has no value. */
    public final float[] rate;
    /** UTC millis this hour is valid for. */
    public final long validTime;

    RainGrid(LatLonGrid g, float[] rate, long validTime) {
        this.west = g.west;
        this.south = g.south;
        this.east = g.east;
        this.north = g.north;
        this.nx = g.ni;
        this.ny = g.nj;
        this.rate = rate;
        this.validTime = validTime;
    }

    public boolean contains(double lat, double lon) {
        return lat >= south && lat <= north && lon >= west && lon <= east;
    }

    /** Bilinear sample of the rate, NaN outside; a missing corner falls back to the nearest cell. */
    public float sample(double lat, double lon) {
        if (rate == null || !contains(lat, lon) || nx < 2 || ny < 2)
            return Float.NaN;
        final double fx = (lon - west) / (east - west) * (nx - 1);
        final double fy = (lat - south) / (north - south) * (ny - 1);
        final int x0 = (int) Math.floor(fx), y0 = (int) Math.floor(fy);
        final int x1 = Math.min(nx - 1, x0 + 1), y1 = Math.min(ny - 1, y0 + 1);
        final double tx = fx - x0, ty = fy - y0;
        final float a00 = rate[y0 * nx + x0], a10 = rate[y0 * nx + x1];
        final float a01 = rate[y1 * nx + x0], a11 = rate[y1 * nx + x1];
        if (Float.isNaN(a00) || Float.isNaN(a10) || Float.isNaN(a01) || Float.isNaN(a11))
            return nearest(fx, fy);
        return (float) ((a00 * (1 - tx) + a10 * tx) * (1 - ty) + (a01 * (1 - tx) + a11 * tx) * ty);
    }

    /** The nearest cell's value. */
    public float nearest(double fx, double fy) {
        final int x = (int) Math.max(0, Math.min(nx - 1, Math.round(fx)));
        final int y = (int) Math.max(0, Math.min(ny - 1, Math.round(fy)));
        return rate[y * nx + x];
    }

    /**
     * Copy a message's values into row-major, row 0 the south edge, as mm per hour.
     * The model gives a rate in kilograms per square meter per second, which is
     * millimeters per second of water; an hour of it is 3,600 times that.
     */
    static float[] field(Grib2.Message m) {
        if (m == null)
            return null;
        final LatLonGrid g = m.latLon;
        final float[] out = new float[g.ni * g.nj];
        for (int y = 0; y < g.nj; y++)
            for (int x = 0; x < g.ni; x++) {
                final float v = m.at(x, y);
                out[y * g.ni + x] = Float.isNaN(v) || v > 9000 || v < 0 ? Float.NaN : v * 3600f;
            }
        return out;
    }
}
