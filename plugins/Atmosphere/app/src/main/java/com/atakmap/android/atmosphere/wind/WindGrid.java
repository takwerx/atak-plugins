package com.atakmap.android.atmosphere.wind;

/**
 * Wind on a regular lon/lat lattice: u (east) and v (north) in m/s per cell, row 0
 * the south edge. What the particle engine samples and what every source produces,
 * whatever grid the provider used. Bilinear inside, NaN outside or over a hole.
 */
public final class WindGrid {

    public final double west, south, east, north;
    public final int nx, ny;
    public final float[] u, v;
    /** When the field is valid for, UTC millis. */
    public final long validTime;

    public WindGrid(double west, double south, double east, double north, int nx, int ny,
            float[] u, float[] v, long validTime) {
        this.west = west;
        this.south = south;
        this.east = east;
        this.north = north;
        this.nx = nx;
        this.ny = ny;
        this.u = u;
        this.v = v;
        this.validTime = validTime;
    }

    public boolean contains(double lat, double lon) {
        return lat >= south && lat <= north && lon >= west && lon <= east;
    }

    /** {u, v} at a point, or NaNs. */
    public float[] sample(double lat, double lon, float[] out) {
        final double fx = (lon - west) / (east - west) * (nx - 1);
        final double fy = (lat - south) / (north - south) * (ny - 1);
        if (fx < 0 || fy < 0 || fx > nx - 1 || fy > ny - 1) {
            out[0] = Float.NaN;
            out[1] = Float.NaN;
            return out;
        }
        final int x0 = (int) Math.floor(fx), y0 = (int) Math.floor(fy);
        final int x1 = Math.min(nx - 1, x0 + 1), y1 = Math.min(ny - 1, y0 + 1);
        final double tx = fx - x0, ty = fy - y0;
        out[0] = bilinear(u, x0, y0, x1, y1, tx, ty);
        out[1] = bilinear(v, x0, y0, x1, y1, tx, ty);
        return out;
    }

    private float bilinear(float[] a, int x0, int y0, int x1, int y1, double tx, double ty) {
        final float a00 = a[y0 * nx + x0], a10 = a[y0 * nx + x1];
        final float a01 = a[y1 * nx + x0], a11 = a[y1 * nx + x1];
        return (float) ((a00 * (1 - tx) + a10 * tx) * (1 - ty) + (a01 * (1 - tx) + a11 * tx) * ty);
    }

    /** Speed in m/s at a point, or NaN. */
    public float speed(double lat, double lon) {
        final float[] uv = sample(lat, lon, new float[2]);
        return Float.isNaN(uv[0]) ? Float.NaN : (float) Math.hypot(uv[0], uv[1]);
    }

    /**
     * Resample a Lambert-gridded u and v pair onto a regular lon/lat lattice covering the
     * source grid's own extent, so the engine never needs the projection.
     */
    public static WindGrid fromLambert(Grib2.Message uMsg, Grib2.Message vMsg, int targetNx) {
        final Lcc g = uMsg.grid;
        double west = 180, east = -180, south = 90, north = -90;
        // The extent from the source's edge cells; the lattice covers it fully, and the
        // corners outside the tilted source rectangle come back NaN.
        for (int i = 0; i < uMsg.nx; i++) {
            for (int j : new int[] { 0, uMsg.ny - 1 }) {
                final double[] ll = g.latLonOf(i, j);
                south = Math.min(south, ll[0]); north = Math.max(north, ll[0]);
                west = Math.min(west, ll[1]); east = Math.max(east, ll[1]);
            }
        }
        for (int j = 0; j < uMsg.ny; j++) {
            for (int i : new int[] { 0, uMsg.nx - 1 }) {
                final double[] ll = g.latLonOf(i, j);
                south = Math.min(south, ll[0]); north = Math.max(north, ll[0]);
                west = Math.min(west, ll[1]); east = Math.max(east, ll[1]);
            }
        }
        final int nx = Math.max(2, targetNx);
        final int ny = Math.max(2, (int) Math.round(nx * (north - south) / (east - west)));
        final float[] u = new float[nx * ny], v = new float[nx * ny];
        for (int y = 0; y < ny; y++) {
            final double lat = south + (north - south) * y / (ny - 1);
            for (int x = 0; x < nx; x++) {
                final double lon = west + (east - west) * x / (nx - 1);
                final double[] ij = g.gridIndex(lat, lon);
                u[y * nx + x] = bilinearLcc(uMsg, ij[0], ij[1]);
                v[y * nx + x] = bilinearLcc(vMsg, ij[0], ij[1]);
            }
        }
        final long valid = uMsg.referenceTime + uMsg.forecastHours * 3_600_000L;
        return new WindGrid(west, south, east, north, nx, ny, u, v, valid);
    }

    private static float bilinearLcc(Grib2.Message m, double fi, double fj) {
        if (fi < 0 || fj < 0 || fi > m.nx - 1 || fj > m.ny - 1)
            return Float.NaN;
        final int i0 = (int) Math.floor(fi), j0 = (int) Math.floor(fj);
        final int i1 = Math.min(m.nx - 1, i0 + 1), j1 = Math.min(m.ny - 1, j0 + 1);
        final double ti = fi - i0, tj = fj - j0;
        final float a = m.at(i0, j0), b = m.at(i1, j0), c = m.at(i0, j1), d = m.at(i1, j1);
        return (float) ((a * (1 - ti) + b * ti) * (1 - tj) + (c * (1 - ti) + d * ti) * tj);
    }
}
