package com.atakmap.android.dozercountry.terrain;

/**
 * Is a point inside the area the operator drew?
 *
 * <p>Plain arithmetic on longitude/latitude arrays, with no ATAK or Android types, so
 * the test that decides which ground gets painted can be checked without a device.
 *
 * <p>Ray casting: count how many edges a ray from the point crosses. Odd is inside.
 * The half-open edge rule ({@code (yi > y) != (yj > y)}) is what keeps a point level
 * with a vertex from being counted twice, which is the classic way this goes wrong and
 * would show up as single stray cells along a horizontal edge.
 */
public final class Polygon {

    private final double[] lon;
    private final double[] lat;

    /** Bounding box, so the cheap rejection happens before the expensive test. */
    private final double minLon, maxLon, minLat, maxLat;

    public Polygon(double[] lon, double[] lat) {
        this.lon = lon;
        this.lat = lat;
        double n0 = Double.MAX_VALUE, n1 = -Double.MAX_VALUE;
        double t0 = Double.MAX_VALUE, t1 = -Double.MAX_VALUE;
        for (int i = 0; i < lon.length; i++) {
            if (lon[i] < n0) n0 = lon[i];
            if (lon[i] > n1) n1 = lon[i];
            if (lat[i] < t0) t0 = lat[i];
            if (lat[i] > t1) t1 = lat[i];
        }
        minLon = n0;
        maxLon = n1;
        minLat = t0;
        maxLat = t1;
    }

    public int size() {
        return lon.length;
    }

    /**
     * True when the point is inside the ring.
     *
     * <p>A ring of fewer than three vertices encloses nothing, and is treated as
     * enclosing everything rather than nothing: it means no usable polygon was given,
     * and silently painting an empty area would look like the plugin had failed.
     */
    public boolean contains(double pLon, double pLat) {
        if (lon.length < 3)
            return true;
        if (pLon < minLon || pLon > maxLon || pLat < minLat || pLat > maxLat)
            return false;

        boolean in = false;
        for (int i = 0, j = lon.length - 1; i < lon.length; j = i++) {
            final double xi = lon[i], yi = lat[i];
            final double xj = lon[j], yj = lat[j];
            if (((yi > pLat) != (yj > pLat))
                    && (pLon < (xj - xi) * (pLat - yi) / (yj - yi) + xi))
                in = !in;
        }
        return in;
    }
}
