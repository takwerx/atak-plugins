package com.atakmap.android.dozercountry.map;

import com.atakmap.android.dozercountry.terrain.Polygon;
import com.atakmap.coremap.maps.coords.GeoBounds;
import com.atakmap.coremap.maps.coords.GeoPoint;

/**
 * The area of interest: the ring the operator drew, and the north-up box around it.
 *
 * <p>Both are needed and they are not interchangeable. Elevation is sampled on a
 * lat/lon grid, which is a rectangle, so the bounding box is what gets sampled; the
 * ring is what gets painted, so the color stops exactly where the line is drawn.
 */
public final class Area {

    public final GeoBounds bounds;
    public final Polygon ring;

    public Area(GeoPoint[] points) {
        this.bounds = GeoBounds.createFromPoints(points);
        final double[] lon = new double[points.length];
        final double[] lat = new double[points.length];
        for (int i = 0; i < points.length; i++) {
            lon[i] = points[i].getLongitude();
            lat[i] = points[i].getLatitude();
        }
        this.ring = new Polygon(lon, lat);
    }

    /** True when the ring is big enough to enclose ground at all. */
    public boolean isUsable() {
        return ring.size() >= 3
                && bounds.getNorth() != bounds.getSouth()
                && bounds.getEast() != bounds.getWest();
    }
}
