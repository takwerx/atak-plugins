package com.atakmap.android.atmosphere.smoke;

import com.atakmap.android.atmosphere.wind.Grib2;
import com.atakmap.android.atmosphere.wind.LatLonGrid;
import com.atakmap.android.atmosphere.wind.Lcc;

/**
 * Smoke on a regular lon/lat lattice, in the unit it is read in: micrograms per cubic
 * meter near the ground, milligrams per square meter for the whole sky. Row 0 is the
 * south edge. The wind's {@code WindGrid} with one field instead of two; bilinear
 * inside, NaN outside or over a hole.
 */
public final class SmokeGrid {

    public final double west, south, east, north;
    public final int nx, ny;
    public final float[] values;
    /** When the field is valid for, UTC millis. */
    public final long validTime;

    public SmokeGrid(double west, double south, double east, double north, int nx, int ny,
            float[] values, long validTime) {
        this.west = west;
        this.south = south;
        this.east = east;
        this.north = north;
        this.nx = nx;
        this.ny = ny;
        this.values = values;
        this.validTime = validTime;
    }

    public boolean contains(double lat, double lon) {
        return lat >= south && lat <= north && lon >= west && lon <= east;
    }

    /** The value at a point, or NaN. */
    public float sample(double lat, double lon) {
        final double fx = (lon - west) / (east - west) * (nx - 1);
        final double fy = (lat - south) / (north - south) * (ny - 1);
        if (fx < 0 || fy < 0 || fx > nx - 1 || fy > ny - 1)
            return Float.NaN;
        final int x0 = (int) Math.floor(fx), y0 = (int) Math.floor(fy);
        final int x1 = Math.min(nx - 1, x0 + 1), y1 = Math.min(ny - 1, y0 + 1);
        final double tx = fx - x0, ty = fy - y0;
        final float a00 = values[y0 * nx + x0], a10 = values[y0 * nx + x1];
        final float a01 = values[y1 * nx + x0], a11 = values[y1 * nx + x1];
        return (float) ((a00 * (1 - tx) + a10 * tx) * (1 - ty) + (a01 * (1 - tx) + a11 * tx) * ty);
    }

    /**
     * One message onto a lattice, scaled into the reading unit. A Lambert message is
     * resampled at its own column count, so the lattice is as fine as the model; a
     * lat/lon message is copied south-up as it stands.
     *
     * @param scale multiplies the file's SI value: 1e9 turns kg/m3 into ug/m3
     */
    public static SmokeGrid from(Grib2.Message m, double scale) {
        final long valid = m.referenceTime + m.forecastHours * 3_600_000L;
        if (m.latLon != null) {
            final LatLonGrid g = m.latLon;
            final float[] out = new float[g.ni * g.nj];
            for (int y = 0; y < g.nj; y++)
                for (int x = 0; x < g.ni; x++)
                    out[y * g.ni + x] = (float) (m.at(x, y) * scale);
            return new SmokeGrid(g.west, g.south, g.east, g.north, g.ni, g.nj, out, valid);
        }
        final Lcc g = m.grid;
        double west = 180, east = -180, south = 90, north = -90;
        for (int i = 0; i < m.nx; i++) {
            for (int j : new int[] { 0, m.ny - 1 }) {
                final double[] ll = g.latLonOf(i, j);
                south = Math.min(south, ll[0]); north = Math.max(north, ll[0]);
                west = Math.min(west, ll[1]); east = Math.max(east, ll[1]);
            }
        }
        for (int j = 0; j < m.ny; j++) {
            for (int i : new int[] { 0, m.nx - 1 }) {
                final double[] ll = g.latLonOf(i, j);
                south = Math.min(south, ll[0]); north = Math.max(north, ll[0]);
                west = Math.min(west, ll[1]); east = Math.max(east, ll[1]);
            }
        }
        final int nx = Math.max(2, m.nx);
        final int ny = Math.max(2, (int) Math.round(nx * (north - south) / (east - west)));
        final float[] out = new float[nx * ny];
        for (int y = 0; y < ny; y++) {
            final double lat = south + (north - south) * y / (ny - 1);
            for (int x = 0; x < nx; x++) {
                final double lon = west + (east - west) * x / (nx - 1);
                final double[] ij = g.gridIndex(lat, lon);
                out[y * nx + x] = (float) (bilinear(m, ij[0], ij[1]) * scale);
            }
        }
        return new SmokeGrid(west, south, east, north, nx, ny, out, valid);
    }

    private static float bilinear(Grib2.Message m, double fi, double fj) {
        if (fi < 0 || fj < 0 || fi > m.nx - 1 || fj > m.ny - 1)
            return Float.NaN;
        final int i0 = (int) Math.floor(fi), j0 = (int) Math.floor(fj);
        final int i1 = Math.min(m.nx - 1, i0 + 1), j1 = Math.min(m.ny - 1, j0 + 1);
        final double ti = fi - i0, tj = fj - j0;
        final float a = m.at(i0, j0), b = m.at(i1, j0), c = m.at(i0, j1), d = m.at(i1, j1);
        return (float) ((a * (1 - ti) + b * ti) * (1 - tj) + (c * (1 - ti) + d * ti) * tj);
    }
}
