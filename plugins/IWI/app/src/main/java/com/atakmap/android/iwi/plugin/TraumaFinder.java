package com.atakmap.android.iwi.plugin;


import com.atakmap.coremap.maps.coords.GeoCalculations;
import com.atakmap.coremap.maps.coords.GeoPoint;

import java.util.List;

/**
 * The nearest trauma center from a point, straight-line, and the nearest more capable
 * one when the nearest is not already a Level I.
 *
 * <p>Two lines at most (PLAN-IWI, "Nearest trauma center"): the nearest with a pad of
 * any level, and then the nearest Level I; when there is no Level I within reach, the
 * nearest Level II, and so on down to one level better than the first line. The nearest
 * Level I may be farther than the nearest Level II, which is the point of showing both.
 */
public final class TraumaFinder {

    /** Beyond this straight-line distance nothing is listed. 250 statute miles. */
    public static final double CAP_M = 250 * 1609.344;

    private TraumaFinder() {
    }

    /** One center and where it is from the reference point. */
    public static final class Result {
        public final TraumaCenter center;
        public final double meters;
        /** Degrees, true north. */
        public final double bearing;

        Result(TraumaCenter center, double meters, double bearing) {
            this.center = center;
            this.meters = meters;
            this.bearing = bearing;
        }
    }

    /** The nearest center of any level within the cap, or null. */
    public static Result nearest(List<TraumaCenter> centers, GeoPoint from) {
        return nearestAtLevel(centers, from, 0);
    }

    /**
     * The nearest center more capable than {@code level}, best level first, or null when
     * {@code level} is already 1 or nothing better is within the cap.
     */
    public static Result nearestBetter(List<TraumaCenter> centers, GeoPoint from, int level) {
        for (int want = 1; want < level; want++) {
            final Result r = nearestAtLevel(centers, from, want);
            if (r != null)
                return r;
        }
        return null;
    }

    /** @param level 1 to 5, or 0 for any */
    private static Result nearestAtLevel(List<TraumaCenter> centers, GeoPoint from, int level) {
        if (centers == null || from == null)
            return null;
        TraumaCenter best = null;
        double bestM = CAP_M;
        for (TraumaCenter c : centers) {
            if (level != 0 && c.level != level)
                continue;
            final double d = GeoCalculations.distanceTo(from, c.target());
            if (!Double.isNaN(d) && d < bestM) {
                bestM = d;
                best = c;
            }
        }
        if (best == null)
            return null;
        return new Result(best, bestM, GeoCalculations.bearingTo(from, best.target()));
    }

    /** Minutes at a cruise speed in knots, straight line, never below zero. */
    public static int minutes(double meters, int knots) {
        if (knots <= 0)
            return 0;
        final double metersPerMinute = knots * 1852.0 / 60.0;
        return (int) Math.round(meters / metersPerMinute);
    }
}
