package com.atakmap.android.atmosphere.wind;

/**
 * Lambert conformal conic on a sphere, the HRRR grid's projection (GRIB2 grid
 * template 3.30), with the grid's first point as the origin of a Dx by Dy cell lattice.
 * Snyder's formulas; the two standard parallels are equal for HRRR (38.5 N) and the
 * general form covers the case where they are not.
 */
public final class Lcc {

    public final double radius, latin1, latin2, laD, loV, la1, lo1, dx, dy;
    private final double n, f, rho0, lambda0;
    /** Projected coordinates of the first grid point. */
    private final double x1, y1;

    public Lcc(double radius, double latin1, double latin2, double laD, double loV,
            double la1, double lo1, double dx, double dy) {
        this.radius = radius;
        this.latin1 = latin1;
        this.latin2 = latin2;
        this.laD = laD;
        this.loV = loV;
        this.la1 = la1;
        this.lo1 = lo1;
        this.dx = dx;
        this.dy = dy;
        final double p1 = Math.toRadians(latin1), p2 = Math.toRadians(latin2);
        if (Math.abs(latin1 - latin2) < 1e-9)
            n = Math.sin(p1);
        else
            n = Math.log(Math.cos(p1) / Math.cos(p2))
                    / Math.log(Math.tan(Math.PI / 4 + p2 / 2) / Math.tan(Math.PI / 4 + p1 / 2));
        f = Math.cos(p1) * Math.pow(Math.tan(Math.PI / 4 + p1 / 2), n) / n;
        rho0 = radius * f / Math.pow(Math.tan(Math.PI / 4 + Math.toRadians(laD) / 2), n);
        lambda0 = Math.toRadians(normLon(loV));
        final double[] xy = forward(la1, lo1);
        x1 = xy[0];
        y1 = xy[1];
    }

    static double normLon(double lon) {
        while (lon > 180) lon -= 360;
        while (lon < -180) lon += 360;
        return lon;
    }

    /** Lon/lat in degrees to projected meters {x, y}. */
    public double[] forward(double lat, double lon) {
        final double phi = Math.toRadians(lat);
        double dl = Math.toRadians(normLon(lon)) - lambda0;
        if (dl > Math.PI) dl -= 2 * Math.PI;
        if (dl < -Math.PI) dl += 2 * Math.PI;
        final double rho = radius * f / Math.pow(Math.tan(Math.PI / 4 + phi / 2), n);
        final double theta = n * dl;
        return new double[] { rho * Math.sin(theta), rho0 - rho * Math.cos(theta) };
    }

    /** Projected meters to {lat, lon} in degrees. */
    public double[] inverse(double x, double y) {
        final double dy0 = rho0 - y;
        final double rho = Math.signum(n) * Math.sqrt(x * x + dy0 * dy0);
        final double theta = Math.atan2(x, dy0);
        final double phi = 2 * Math.atan(Math.pow(radius * f / rho, 1 / n)) - Math.PI / 2;
        final double lambda = lambda0 + theta / n;
        return new double[] { Math.toDegrees(phi), normLon(Math.toDegrees(lambda)) };
    }

    /** Fractional grid column and row (from the first point, south to north) of a lon/lat. */
    public double[] gridIndex(double lat, double lon) {
        final double[] xy = forward(lat, lon);
        return new double[] { (xy[0] - x1) / dx, (xy[1] - y1) / dy };
    }

    /** Lon/lat of a fractional grid cell. */
    public double[] latLonOf(double i, double j) {
        return inverse(x1 + i * dx, y1 + j * dy);
    }
}
