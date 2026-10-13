package com.atakmap.android.signaldf.data;

/**
 * A flat east/north frame in meters, hung on one point of the earth.
 *
 * <p>{@link BearingFix} works in meters because the arithmetic of crossing
 * lines is unbearable on a sphere and unnecessary at these distances. This is
 * the conversion in and out, and it is deliberately plain arithmetic rather
 * than a call into ATAK's geodesy so that both it and the solver can be tested
 * on a laptop.
 *
 * <p><b>The approximation, and why it is safe here.</b> This is an
 * equirectangular projection about the origin: a degree of latitude and a
 * degree of longitude are each given a fixed length, taken from the WGS84
 * series at the origin's latitude, and everything is treated as flat after
 * that. The error grows with the square of the distance from the origin and is
 * around a tenth of a percent at twenty kilometers -- twenty meters in twenty
 * kilometers. A direction-finding fix is doing extremely well to be within a
 * few hundred meters at that range, so the projection is nowhere near the
 * dominant error and never will be.
 *
 * <p>The origin is chosen as the centroid of the receiver positions, which
 * keeps every point close to it and the approximation at its best.
 */
public final class LocalFrame {

    private final double lat0;
    private final double lon0;
    private final double mPerDegLat;
    private final double mPerDegLon;

    /**
     * @param lat0 origin latitude, degrees
     * @param lon0 origin longitude, degrees
     */
    public LocalFrame(double lat0, double lon0) {
        this.lat0 = lat0;
        this.lon0 = lon0;
        double phi = Math.toRadians(lat0);
        // Standard WGS84 series for the length of a degree. The cosine terms
        // carry the flattening; dropping them would cost about 0.5% at the
        // poles and 0.2% in the mid latitudes, which is worth keeping for the
        // three multiplications it costs.
        this.mPerDegLat = 111132.92 - 559.82 * Math.cos(2 * phi)
                + 1.175 * Math.cos(4 * phi) - 0.0023 * Math.cos(6 * phi);
        this.mPerDegLon = 111412.84 * Math.cos(phi) - 93.5 * Math.cos(3 * phi)
                + 0.118 * Math.cos(5 * phi);
    }

    public double originLat() {
        return lat0;
    }

    public double originLon() {
        return lon0;
    }

    /** Meters east of the origin. */
    public double east(double lon) {
        return (lon - lon0) * mPerDegLon;
    }

    /** Meters north of the origin. */
    public double north(double lat) {
        return (lat - lat0) * mPerDegLat;
    }

    /** Latitude of a point this many meters north of the origin. */
    public double lat(double north) {
        return lat0 + north / mPerDegLat;
    }

    /** Longitude of a point this many meters east of the origin. */
    public double lon(double east) {
        return lon0 + east / mPerDegLon;
    }

    /**
     * Straight-line distance between two lat/lon pairs in meters, good enough
     * for a movement gate.
     *
     * <p>Static, and it builds its own frame on the first point, so callers
     * deciding "have I moved far enough to log another bearing" do not need
     * one of these already in hand.
     */
    public static double distanceM(double lat1, double lon1,
            double lat2, double lon2) {
        LocalFrame f = new LocalFrame(lat1, lon1);
        return Math.hypot(f.east(lon2), f.north(lat2));
    }
}
