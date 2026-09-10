package com.atakmap.android.comms.map;

import com.atakmap.coremap.log.Log;
import com.atakmap.coremap.maps.coords.GeoPoint;
import com.atakmap.map.elevation.ElevationManager;

/**
 * Line of sight from the operator to a site's antenna over the device's elevation
 * data.
 *
 * <p>ATAK draws its viewshed on the GL thread and keeps the result to itself, so
 * "which sites are inside the green" has to be answered here: the terrain along the
 * path is sampled about every DTED post, lowered by the earth's bulge with the usual
 * 4/3 radius for VHF refraction, and compared with the straight line between the two
 * antennas. Both ends use the same elevation source, so the answer agrees with what
 * ATAK paints for the same pair of points.
 *
 * <p>Optical line of sight, not radio coverage: no diffraction, no power, no gain.
 * The pane says so.
 */
public final class LineOfSight {

    private static final String TAG = "CommsLos";

    /** Effective earth radius with standard refraction, meters. */
    private static final double EFFECTIVE_RADIUS = 6371008.8 * 4 / 3;
    /** Sample spacing along the path, roughly a DTED level 1 post. */
    private static final double STEP_M = 80;
    private static final int MAX_SAMPLES = 800;
    /** The ends are not tested: a DEM post under the mast is the mast's own hill. */
    private static final double SKIP_END_M = 120;

    private LineOfSight() {
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
     * Whether the antenna at {@code to}, {@code toAbove} meters up its mast, can be
     * seen from {@code from}, {@code fromAbove} meters above the ground there.
     *
     * @return TRUE or FALSE, or null when there is no elevation data at either end
     */
    public static Boolean clear(GeoPoint from, double fromAbove, GeoPoint to, double toAbove,
            double distanceM) {
        final double g0 = ground(from.getLatitude(), from.getLongitude());
        final double g1 = ground(to.getLatitude(), to.getLongitude());
        if (Double.isNaN(g0) || Double.isNaN(g1))
            return null;
        if (distanceM <= 2 * SKIP_END_M)
            return Boolean.TRUE;
        final double h0 = g0 + fromAbove;
        final double h1 = g1 + toAbove;
        final int n = (int) Math.max(8, Math.min(MAX_SAMPLES, Math.round(distanceM / STEP_M)));
        final double lat0 = from.getLatitude(), lon0 = from.getLongitude();
        final double dLat = to.getLatitude() - lat0, dLon = to.getLongitude() - lon0;
        int missing = 0;
        for (int i = 1; i < n; i++) {
            final double t = (double) i / n;
            final double d = distanceM * t;
            if (d < SKIP_END_M || distanceM - d < SKIP_END_M)
                continue;
            final double e = ground(lat0 + dLat * t, lon0 + dLon * t);
            if (Double.isNaN(e)) {
                missing++;
                continue;
            }
            // The terrain sinks below the chord by the bulge; the sight line is straight.
            final double bulge = d * (distanceM - d) / (2 * EFFECTIVE_RADIUS);
            final double sight = h0 + (h1 - h0) * t;
            if (e - bulge > sight)
                return Boolean.FALSE;
        }
        // A path with most of its samples unknown is not a clear path, it is an unknown one.
        return missing > n / 2 ? null : Boolean.TRUE;
    }
}
