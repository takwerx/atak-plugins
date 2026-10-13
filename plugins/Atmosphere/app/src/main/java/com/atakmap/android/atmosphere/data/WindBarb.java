
package com.atakmap.android.atmosphere.data;

/**
 * How many feathers a wind barb carries, which is how its speed is read.
 *
 * <p>The convention, unchanged since long before anyone read one on a phone: a short
 * feather is <b>5 knots</b>, a long one <b>10</b>, a solid triangle <b>50</b>, added
 * together, with the speed rounded to the nearest 5. Calm is a bare circle with no
 * staff.
 *
 * <p>Here rather than in the drawing code because it is the part that can be wrong
 * without looking wrong: a barb showing 15 knots when the station reported 25 is a
 * legible, confident, incorrect symbol, and nothing on the map would give it away.
 *
 * <p>No Android types, so it is tested.
 */
public final class WindBarb {

    /** Below this the barb is not drawn at all; the symbol alone means calm. */
    public static final double CALM_KNOTS = 2.5;

    /** What one barb carries: triangles, then full feathers, then a half. */
    public static final class Feathers {
        public final int flags, fulls, halves;

        Feathers(int flags, int fulls, int halves) {
            this.flags = flags;
            this.fulls = fulls;
            this.halves = halves;
        }

        /** The speed this barb reads as, which is the rounded one, not the measured. */
        public int knots() {
            return flags * 50 + fulls * 10 + halves * 5;
        }
    }

    /** True when there is nothing to draw but the station symbol. */
    public static boolean isCalm(double knots) {
        return Double.isNaN(knots) || knots < CALM_KNOTS;
    }

    /**
     * The feathers for a wind, rounded to the nearest 5 knots.
     *
     * <p>A calm wind returns all zeros; the caller draws no staff for it.
     */
    public static Feathers of(double knots) {
        if (isCalm(knots))
            return new Feathers(0, 0, 0);
        final long fives = Math.round(knots / 5.0);
        return new Feathers((int) (fives / 10), (int) ((fives % 10) / 2),
                (int) ((fives % 10) % 2));
    }

    private WindBarb() {
    }
}
