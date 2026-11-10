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

    /** Which hospitals count for a pick. Anonymous classes, never lambdas (proguard). */
    public interface Want {
        boolean ok(TraumaCenter c);
    }

    public static final Want ER = new Want() {
        @Override
        public boolean ok(TraumaCenter c) {
            return c.hasEr();
        }
    };
    public static final Want ER_PAD = new Want() {
        @Override
        public boolean ok(TraumaCenter c) {
            return c.hasEr() && c.hasPad();
        }
    };
    public static final Want TRAUMA = new Want() {
        @Override
        public boolean ok(TraumaCenter c) {
            return c.adultTrauma();
        }
    };
    public static final Want LEVEL_I = new Want() {
        @Override
        public boolean ok(TraumaCenter c) {
            return c.adult == 1;
        }
    };
    public static final Want BURN = new Want() {
        @Override
        public boolean ok(TraumaCenter c) {
            return c.adultBurn();
        }
    };

    /** The nearest hospital the test accepts, within the cap, or null. */
    public static Result nearest(List<TraumaCenter> centers, GeoPoint from, Want want) {
        if (centers == null || from == null)
            return null;
        TraumaCenter best = null;
        double bestM = CAP_M;
        for (TraumaCenter c : centers) {
            if (!want.ok(c))
                continue;
            final double d = GeoCalculations.distanceTo(from, c.target());
            if (!Double.isNaN(d) && d < bestM) {
                bestM = d;
                best = c;
            }
        }
        return best == null ? null
                : new Result(best, bestM, GeoCalculations.bearingTo(from, best.target()));
    }

    /** The nearest adult trauma center of any level within the cap, or null. */
    public static Result nearest(List<TraumaCenter> centers, GeoPoint from) {
        return nearest(centers, from, TRAUMA);
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

    public static final Want ANY = new Want() {
        @Override
        public boolean ok(TraumaCenter c) {
            return true;
        }
    };
    /** Any trauma center, pediatric ones too, for the Hospitals page's filter. */
    public static final Want ANY_TRAUMA = new Want() {
        @Override
        public boolean ok(TraumaCenter c) {
            return c.level > 0;
        }
    };
    public static final Want ANY_BURN = new Want() {
        @Override
        public boolean ok(TraumaCenter c) {
            return c.burn;
        }
    };

    /** How many the test accepts within the cap: the count on a filter. */
    public static int count(List<TraumaCenter> centers, GeoPoint from, Want want) {
        int n = 0;
        if (centers == null || from == null)
            return 0;
        for (TraumaCenter c : centers) {
            if (!want.ok(c))
                continue;
            final double d = GeoCalculations.distanceTo(from, c.target());
            if (!Double.isNaN(d) && d < CAP_M)
                n++;
        }
        return n;
    }

    /** Up to {@code max} centers within the cap, nearest first: the Hospitals list. */
    public static java.util.List<Result> nearestList(List<TraumaCenter> centers,
            GeoPoint from, int max) {
        return nearestList(centers, from, max, ANY);
    }

    public static java.util.List<Result> nearestList(List<TraumaCenter> centers,
            GeoPoint from, int max, Want want) {
        final java.util.List<Result> all = new java.util.ArrayList<>();
        if (centers == null || from == null)
            return all;
        for (TraumaCenter c : centers) {
            if (!want.ok(c))
                continue;
            final double d = GeoCalculations.distanceTo(from, c.target());
            if (!Double.isNaN(d) && d < CAP_M)
                all.add(new Result(c, d, GeoCalculations.bearingTo(from, c.target())));
        }
        java.util.Collections.sort(all, new java.util.Comparator<Result>() {
            @Override
            public int compare(Result a, Result b) {
                return Double.compare(a.meters, b.meters);
            }
        });
        return all.size() > max ? new java.util.ArrayList<>(all.subList(0, max)) : all;
    }

    /** @param level 1 to 5, or 0 for any */
    private static Result nearestAtLevel(List<TraumaCenter> centers, GeoPoint from, int level) {
        if (centers == null || from == null)
            return null;
        TraumaCenter best = null;
        double bestM = CAP_M;
        for (TraumaCenter c : centers) {
            if (!c.adultTrauma() || (level != 0 && c.adult != level))
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
