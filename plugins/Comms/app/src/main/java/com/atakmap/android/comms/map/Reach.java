package com.atakmap.android.comms.map;

import com.atakmap.coremap.log.Log;
import com.atakmap.coremap.maps.coords.GeoBounds;
import com.atakmap.coremap.maps.coords.GeoPoint;
import com.atakmap.map.elevation.ElevationData;
import com.atakmap.map.elevation.ElevationManager;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Whether a handheld here is likely to open a repeater there, over the elevation
 * data on the device.
 *
 * <p>This replaced a straight optical line-of-sight test on 2026-09-11, because that
 * test was wrong in the field and wrong in a way that mattered. The operator worked
 * CDF Command 3 on Sierra Peak, tone 4, with a handheld, from inside a station, at a
 * spot the plugin had filed under "no line of sight". A ridge in the path is not a
 * wall: VHF bends over it, and at five miles to a mountaintop a handheld has tens of
 * decibels to spare. Pass/fail on the sight line threw all of that away and demoted a
 * repeater that works.
 *
 * <p>So the terrain profile is still walked, but what comes out of it is a loss, not
 * a verdict. The dominant obstruction is scored with the Fresnel-Kirchhoff parameter
 * and turned into a diffraction loss by the ITU-R P.526 single knife-edge
 * approximation; that is added to free-space loss and compared against what a
 * handheld into a mountaintop repeater can afford. The answer is {@link State}:
 * likely, marginal or unlikely.
 *
 * <p><b>It is a guide, not a prediction</b> (operator, 2026-09-11: "this is not
 * scientific it is general guideline"). Bare earth only -- it knows nothing about
 * trees, buildings, vehicles or weather; it assumes one obstruction rather than a
 * range of them, and it assumes a handheld rather than the radio actually in hand.
 * Where it says unlikely, the radio is still worth a try.
 */
public final class Reach {

    private static final String TAG = "CommsReach";

    /** How likely a handheld here is to open the repeater there. */
    public enum State {
        LIKELY, MARGINAL, UNLIKELY, UNKNOWN
    }

    /** Effective earth radius with standard refraction, meters. */
    private static final double EFFECTIVE_RADIUS = 6371008.8 * 4 / 3;
    /** Closest sample spacing along the path, roughly a DTED level 1 post. */
    private static final double STEP_M = 80;
    /**
     * The most samples any one path gets. A check runs over every site in range on
     * the phone's own processor, so this is a budget, not a preference: at 300 a
     * fifty-mile path steps about 270 m, which is coarse but is the long path where
     * a single ridge decides nothing anyway.
     */
    private static final int MAX_SAMPLES = 300;
    /** The ends are not tested: a DEM post under the mast is the mast's own hill. */
    private static final double SKIP_END_M = 120;

    /**
     * A nominal VHF wavelength, meters. These nets live between about 150 and 175
     * MHz and the catalog holds no frequency to show or to use; 155 MHz is the middle
     * of the band and the answer moves by under a decibel across it.
     */
    private static final double WAVELENGTH_M = 299792458.0 / 155e6;

    /**
     * What a handheld into a mountaintop repeater can afford, in decibels of path
     * loss. 5 W (37 dBm) into a rubber duck held against the body (-5 dBi), a site
     * antenna with 6 dBi of gain behind 3 dB of feedline, a receiver usable at -116
     * dBm, and 12 dB held back for foliage, buildings, body and fading:
     * 37 - 5 + 6 - 3 + 116 - 12.
     */
    private static final double BUDGET_DB = 139;
    /** Decibels of margin over the budget before a path is called likely rather than marginal. */
    private static final double LIKELY_MARGIN_DB = 15;

    private Reach() {
    }

    /**
     * The observer's own ground, remembered across a run. A check over every site in
     * range asks for the same standing spot once per site otherwise, and only that
     * one point repeats -- no sample along a profile is ever asked for twice.
     */
    private static double obsLat = Double.NaN, obsLon = Double.NaN, obsGround = Double.NaN;

    private static synchronized double observerGround(double lat, double lon) {
        if (lat != obsLat || lon != obsLon) {
            obsLat = lat;
            obsLon = lon;
            obsGround = ground(lat, lon);
        }
        return obsGround;
    }

    /** Ground height above the ellipsoid at a point, or NaN with no elevation data there. */
    public static double ground(double lat, double lon) {
        try {
            final double e = ElevationManager.getElevation(lat, lon, null);
            return GeoPoint.isAltitudeValid(e) ? e : Double.NaN;
        } catch (LinkageError | RuntimeException e) {
            Log.w(TAG, "elevation lookup failed", e);
            return Double.NaN;
        }
    }

    /**
     * The ground along a path, in one call.
     *
     * <p>Asking for a point at a time is what made a check over fifty sites take a
     * hundred seconds on an XCover: each call finds the source, opens the tile and
     * reads one post. ATAK carries a bulk form that takes the whole profile at once,
     * and it is in 5.6, 5.7 and 5.8 alike. The path's bounding box goes with it so the
     * right tile is found once rather than per point.
     *
     * @return one elevation per point, NaN where there is no data, or null if the
     *         bulk call is not usable on this build
     */
    private static double[] profile(List<GeoPoint> points) {
        try {
            final double[] out = new double[points.size()];
            Arrays.fill(out, Double.NaN);
            final ElevationData.Hints hints = new ElevationData.Hints();
            hints.interpolate = true;
            double south = 90, north = -90, west = 180, east = -180;
            for (GeoPoint p : points) {
                south = Math.min(south, p.getLatitude());
                north = Math.max(north, p.getLatitude());
                west = Math.min(west, p.getLongitude());
                east = Math.max(east, p.getLongitude());
            }
            hints.bounds = new GeoBounds(south, west, north, east);
            ElevationManager.getElevation(points.iterator(), out, null, hints);
            for (int i = 0; i < out.length; i++)
                if (!GeoPoint.isAltitudeValid(out[i]))
                    out[i] = Double.NaN;
            return out;
        } catch (LinkageError | RuntimeException e) {
            Log.w(TAG, "bulk elevation read unavailable; falling back to one point at a time", e);
            return null;
        }
    }

    /**
     * How likely the repeater at {@code to}, {@code toAbove} meters up its mast, is to
     * hear a handheld at {@code from}, {@code fromAbove} meters above the ground there.
     *
     * @return the state, {@link State#UNKNOWN} when there is no elevation data to judge on
     */
    public static State to(GeoPoint from, double fromAbove, GeoPoint to, double toAbove,
            double distanceM) {
        final double loss = pathLossDb(from, fromAbove, to, toAbove, distanceM);
        if (Double.isNaN(loss))
            return State.UNKNOWN;
        final double margin = BUDGET_DB - loss;
        if (margin >= LIKELY_MARGIN_DB)
            return State.LIKELY;
        return margin >= 0 ? State.MARGINAL : State.UNLIKELY;
    }

    /**
     * Free-space loss plus the loss from diffracting over the worst obstruction in the
     * path, in decibels, or NaN with nothing to judge on.
     */
    public static double pathLossDb(GeoPoint from, double fromAbove, GeoPoint to,
            double toAbove, double distanceM) {
        final double g0 = observerGround(from.getLatitude(), from.getLongitude());
        final double g1 = ground(to.getLatitude(), to.getLongitude());
        if (Double.isNaN(g0) || Double.isNaN(g1) || distanceM <= 0)
            return Double.NaN;

        final double free = 32.44 + 20 * Math.log10(155.0) + 20 * Math.log10(distanceM / 1000.0);
        if (distanceM <= 2 * SKIP_END_M)
            return free;

        final double h0 = g0 + fromAbove;
        final double h1 = g1 + toAbove;
        final int n = (int) Math.max(8, Math.min(MAX_SAMPLES, Math.round(distanceM / STEP_M)));
        final double lat0 = from.getLatitude(), lon0 = from.getLongitude();
        final double dLat = to.getLatitude() - lat0, dLon = to.getLongitude() - lon0;

        // Anything past this is unreachable however the rest of the profile looks, so
        // the walk can stop: the loss only grows as a bigger obstruction turns up.
        final double hopeless = BUDGET_DB - free;
        // Where along the path to look, worked out first so the ground under all of
        // them can be read in one call.
        final List<GeoPoint> points = new ArrayList<>();
        final List<Double> fractions = new ArrayList<>();
        for (int i = 1; i < n; i++) {
            final double t = (double) i / n;
            final double d1 = distanceM * t;
            if (d1 < SKIP_END_M || distanceM - d1 < SKIP_END_M)
                continue;
            points.add(new GeoPoint(lat0 + dLat * t, lon0 + dLon * t));
            fractions.add(t);
        }
        if (points.isEmpty())
            return free;
        final double[] bulk = profile(points);

        double worstV = Double.NEGATIVE_INFINITY;
        int missing = 0;
        final int tested = points.size();
        for (int i = 0; i < tested; i++) {
            final double t = fractions.get(i);
            final double d1 = distanceM * t;
            final double d2 = distanceM - d1;
            final double e = bulk != null ? bulk[i]
                    : ground(points.get(i).getLatitude(), points.get(i).getLongitude());
            if (Double.isNaN(e)) {
                missing++;
                continue;
            }
            // The terrain sinks below the chord by the earth's bulge; the sight line
            // between the two antennas is straight. What is left is how far the ground
            // stands above that line, which is the knife edge's height.
            final double bulge = d1 * d2 / (2 * EFFECTIVE_RADIUS);
            final double sight = h0 + (h1 - h0) * t;
            final double h = (e - bulge) - sight;
            // Fresnel-Kirchhoff diffraction parameter. Negative means the edge is
            // below the line with the first Fresnel zone still clear.
            final double v = h * Math.sqrt(2 * distanceM / (WAVELENGTH_M * d1 * d2));
            if (v > worstV) {
                worstV = v;
                if (knifeEdgeLossDb(worstV) > hopeless)
                    return free + knifeEdgeLossDb(worstV);
            }
        }
        // A path with most of its samples unknown is not a clear path, it is an unknown one.
        if (missing > tested / 2)
            return Double.NaN;
        return free + knifeEdgeLossDb(worstV);
    }

    /**
     * ITU-R P.526 single knife-edge diffraction loss for a Fresnel-Kirchhoff
     * parameter, in decibels. Nothing is lost until the edge starts to intrude on the
     * first Fresnel zone, which is what v = -0.78 marks.
     */
    static double knifeEdgeLossDb(double v) {
        if (Double.isInfinite(v) || Double.isNaN(v) || v <= -0.78)
            return 0;
        return 6.9 + 20 * Math.log10(Math.sqrt((v - 0.1) * (v - 0.1) + 1) + v - 0.1);
    }
}
