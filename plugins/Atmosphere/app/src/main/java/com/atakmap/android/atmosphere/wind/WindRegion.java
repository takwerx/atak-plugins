package com.atakmap.android.atmosphere.wind;

import java.util.Locale;

/**
 * Which patch of ground the wind layer has asked NOMADS for, and what the grid that came
 * back actually covers. Pure arithmetic, no map or Android types, so it is tested on the JVM.
 *
 * <p>Two boxes, kept apart on purpose:
 * <ul>
 * <li>{@link #asked()} is the region the layer chose and requested. It is part of the cache
 * key, so the key for a given hour must not change once chosen.</li>
 * <li>{@link #reach()} is the extent of the grid that came back (a projected model's cut is
 * not the box asked for). It answers "is the view still covered", nothing else.</li>
 * </ul>
 */
public final class WindRegion {

    /** A lat/lon box. */
    public static final class Box {
        public final double west, south, east, north;

        public Box(double west, double south, double east, double north) {
            this.west = west;
            this.south = south;
            this.east = east;
            this.north = north;
        }

        public boolean contains(Box inner) {
            return inner.west >= west && inner.east <= east
                    && inner.south >= south && inner.north <= north;
        }

        public double spanLon() {
            return east - west;
        }

        public double spanLat() {
            return north - south;
        }
    }

    private Box asked, reach;

    public boolean isSet() {
        return asked != null;
    }

    /** The region that was requested; the cache key is made from this. */
    public Box asked() {
        return asked;
    }

    /** What is covered: the grid's own extent once one is shown, else the region asked. */
    public Box reach() {
        return reach != null ? reach : asked;
    }

    public void clear() {
        asked = null;
        reach = null;
    }

    /** A new region was chosen and is about to be requested. */
    public void choose(Box region) {
        asked = region;
        reach = null;
    }

    /**
     * A grid for the region is on screen; {@code extent} is the extent of that grid. Only what
     * is covered changes. The region asked for stays, because the grid is cached under it: when
     * this replaced the region too, the next look at the same hour built a different key, missed
     * the grid it had just fetched, and fetched again, once a settled map move after another
     * (ops #1051).
     */
    public void shown(Box extent) {
        reach = extent;
    }

    /**
     * Whether the view needs a new region. {@code clamped} is the view cut to the model's cover.
     *
     * <p>Region and cache rules are the radar's: a map move outside the region or a zoom past
     * a third of it asks again. A view wider than the box the model is asked for asks again
     * only when its center leaves the region, so a pan across a continent is not a request a
     * second.
     */
    public boolean needsRegion(boolean modelChanged, Box clamped, double maxSpanLon) {
        final Box r = reach();
        boolean refetch = modelChanged || r == null || !r.contains(clamped);
        if (refetch && r != null && !modelChanged && clamped.spanLon() > maxSpanLon) {
            final double cx = (clamped.east + clamped.west) / 2;
            final double cy = (clamped.north + clamped.south) / 2;
            refetch = cx < r.west || cx > r.east || cy < r.south || cy > r.north;
        }
        if (!refetch) {
            final double viewSpan = clamped.spanLon();
            final double regionSpan = r.spanLon();
            refetch = viewSpan < regionSpan / 3.5 || viewSpan > regionSpan * 1.6;
        }
        return refetch;
    }

    /** The cache key of one forecast hour of one run over one region. */
    public static String key(String model, String level, long run, int hour, Box r) {
        return String.format(Locale.US, "%s|%s|%d|%d|%.3f,%.3f,%.3f,%.3f", model, level, run, hour,
                r.west, r.south, r.east, r.north);
    }
}
