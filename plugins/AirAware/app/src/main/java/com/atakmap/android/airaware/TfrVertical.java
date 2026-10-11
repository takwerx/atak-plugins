package com.atakmap.android.airaware;

import com.atakmap.coremap.maps.conversion.EGM96;
import com.atakmap.coremap.maps.coords.GeoPoint;
import com.atakmap.map.elevation.ElevationManager;

import java.util.List;
import java.util.Locale;

/**
 * How much room is above the operator's head.
 *
 * <p>The whole reason a TFR's numbers are hard to use on the ground: the notice publishes
 * a ceiling in feet above mean sea level, and the person reading it is standing on a
 * ridge. "7,500 ft MSL" and "4,300 ft above you" are the same restriction, and only one of
 * them answers the question being asked. Doing that arithmetic is what this plugin is for.
 */
public final class TfrVertical {

    private static final double FT_PER_M = 1d / 0.3048d;

    private TfrVertical() {
    }

    /**
     * Where the operator is, vertically, in feet above mean sea level.
     *
     * <p>Their own position first; the terrain under them when the fix carries no usable
     * altitude. Returns NaN when neither is known, and every caller treats that as "say
     * nothing" rather than as zero -- sea level is a confident wrong answer in the
     * mountains, which is exactly where this matters.
     */
    public static double myFeetMsl(GeoPoint me) {
        if (me == null)
            return Double.NaN;
        if (me.isAltitudeValid()) {
            final double msl = EGM96.getMSL(me);
            if (!Double.isNaN(msl))
                return msl * FT_PER_M;
        }
        try {
            final double hae = ElevationManager.getElevation(me.getLatitude(),
                    me.getLongitude(), null);
            if (!Double.isNaN(hae)) {
                final double msl = EGM96.getMSL(new GeoPoint(me.getLatitude(),
                        me.getLongitude(), hae));
                if (!Double.isNaN(msl))
                    return msl * FT_PER_M;
            }
        } catch (Exception ignored) {
            // No terrain loaded for here. Saying nothing is the right answer.
        }
        return Double.NaN;
    }

    /**
     * The ceiling expressed from where the operator is, or null when it cannot be said.
     *
     * <p>A ceiling published above the surface is already the answer: it is measured from
     * the ground, and the ground under a restriction is near enough the ground under
     * someone standing in it. A ceiling published above sea level has to have the
     * operator's own altitude taken off it.
     */
    public static String headroom(TfrArea a, double myFeetMsl) {
        if (a == null || !a.ceiling.present || a.ceiling.surface)
            return null;
        if (a.ceiling.agl && !a.ceiling.flightLevel)
            return comma((int) Math.round(a.ceiling.feet)) + " ft above the ground";
        if (Double.isNaN(myFeetMsl))
            return null;
        final long above = Math.round(a.ceiling.feet - myFeetMsl);
        if (above <= 0)
            return "you are above its ceiling";
        return comma((int) above) + " ft above you";
    }

    /** The floor the same way, for a restriction that does not start at the surface. */
    public static String floorFromHere(TfrArea a, double myFeetMsl) {
        if (a == null || !a.floor.present || a.floor.surface || a.floor.feet <= 0)
            return null;
        if (a.floor.agl && !a.floor.flightLevel)
            return "its floor is " + comma(a.floor.feet) + " ft above the ground";
        if (Double.isNaN(myFeetMsl))
            return null;
        final long above = Math.round(a.floor.feet - myFeetMsl);
        if (above <= 0)
            return "you are above its floor";
        return "its floor is " + comma((int) above) + " ft above you";
    }

    /**
     * Whether a point is inside an area's ring, by ray casting.
     *
     * <p>Flat arithmetic on latitude and longitude. A TFR is tens of miles across and this
     * only decides a sentence in a list, so the error from not doing it on a sphere is far
     * below anything that would change what the sentence says.
     */
    public static boolean inside(TfrArea a, GeoPoint p) {
        if (a == null || p == null)
            return false;
        return inside(a.ring, p.getLatitude(), p.getLongitude());
    }

    /**
     * The same test against a bare ring, which is what airspace carries: a shelf is a
     * list of outer rings with holes, not a {@link TfrArea}. One implementation, so a
     * restriction and a shelf can never disagree about whether a point is inside.
     */
    public static boolean inside(List<double[]> r, double y, double x) {
        if (r == null || r.size() < 3)
            return false;
        boolean in = false;
        for (int i = 0, j = r.size() - 1; i < r.size(); j = i++) {
            final double yi = r.get(i)[0], xi = r.get(i)[1];
            final double yj = r.get(j)[0], xj = r.get(j)[1];
            if ((yi > y) != (yj > y)
                    && x < (xj - xi) * (y - yi) / (yj - yi) + xi)
                in = !in;
        }
        return in;
    }

    /** The area of a restriction the operator is standing in, or null. */
    public static TfrArea areaContaining(Tfr t, GeoPoint p) {
        for (TfrArea a : t.drawable())
            if (inside(a, p))
                return a;
        return null;
    }

    public static String comma(int n) {
        final String s = Integer.toString(Math.abs(n));
        final StringBuilder b = new StringBuilder(n < 0 ? "-" : "");
        for (int i = 0; i < s.length(); i++) {
            if (i > 0 && (s.length() - i) % 3 == 0)
                b.append(',');
            b.append(s.charAt(i));
        }
        return b.toString();
    }

    public static String feet(double ft) {
        return String.format(Locale.US, "%s ft", comma((int) Math.round(ft)));
    }
}
