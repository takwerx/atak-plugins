package com.atakmap.android.uasflightplan.terrain;

import com.atakmap.coremap.maps.coords.GeoBounds;
import com.atakmap.coremap.maps.coords.GeoCalculations;
import com.atakmap.coremap.maps.coords.GeoPoint;

/**
 * The ground the plan covers: a circle around the launch point, or the ring the
 * pilot drew with ATAK's shape tool. Everything downstream (terrain sample,
 * obstacle fetch, the paint) asks this for its box and whether a point is in it,
 * and never knows which kind it is.
 */
public final class Extent {

    /** The north-up box around it: what gets sampled and what the FAA is asked for. */
    public final GeoBounds bounds;
    /** The circle's center, or the box's center for a ring. */
    public final GeoPoint center;
    /** Meters, for a circle; NaN for a ring. */
    public final double radiusM;
    /** The ring, or null for a circle. */
    public final Polygon ring;

    private Extent(GeoBounds bounds, GeoPoint center, double radiusM, Polygon ring) {
        this.bounds = bounds;
        this.center = center;
        this.radiusM = radiusM;
        this.ring = ring;
    }

    public static Extent circle(GeoPoint center, double radiusM) {
        final GeoPoint n = GeoCalculations.pointAtDistance(center, 0d, radiusM);
        final GeoPoint s = GeoCalculations.pointAtDistance(center, 180d, radiusM);
        final GeoPoint e = GeoCalculations.pointAtDistance(center, 90d, radiusM);
        final GeoPoint w = GeoCalculations.pointAtDistance(center, 270d, radiusM);
        return new Extent(new GeoBounds(new GeoPoint(n.getLatitude(), w.getLongitude()),
                new GeoPoint(s.getLatitude(), e.getLongitude())), center, radiusM, null);
    }

    public static Extent drawn(GeoBounds bounds, Polygon ring) {
        final GeoPoint c = new GeoPoint((bounds.getNorth() + bounds.getSouth()) / 2d,
                (bounds.getEast() + bounds.getWest()) / 2d);
        return new Extent(bounds, c, Double.NaN, ring);
    }

    public boolean isCircle() {
        return ring == null;
    }

    public boolean contains(double lat, double lon) {
        if (ring != null)
            return ring.contains(lon, lat);
        return GeoCalculations.distanceTo(center, new GeoPoint(lat, lon)) <= radiusM;
    }

    /** The box's width and height on the ground, meters. */
    public double[] sizeM() {
        final GeoPoint nw = new GeoPoint(bounds.getNorth(), bounds.getWest());
        final GeoPoint ne = new GeoPoint(bounds.getNorth(), bounds.getEast());
        final GeoPoint sw = new GeoPoint(bounds.getSouth(), bounds.getWest());
        return new double[] { GeoCalculations.distanceTo(nw, ne),
                GeoCalculations.distanceTo(nw, sw) };
    }
}
