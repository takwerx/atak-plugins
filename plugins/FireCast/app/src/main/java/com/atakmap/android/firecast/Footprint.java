package com.atakmap.android.firecast;

import com.atakmap.map.layer.feature.geometry.Geometry;
import com.atakmap.map.layer.feature.geometry.LineString;
import com.atakmap.map.layer.feature.geometry.Polygon;

import java.util.Locale;

/**
 * The ground a detection actually covers.
 *
 * <p>This is the plugin's reason to exist. A heat detection drawn as a pin says "a fire
 * is here"; almost none of them mean that. A VIIRS pixel means "something in this
 * 375 m square was hot"; a GOES pixel off the edge of its disc means "something in this
 * 4 km by 2 km patch was hot". Those two answers send a crew to different places, and
 * the difference is in the record - so the footprint is read from the record, never
 * from a table of nominal resolutions.
 *
 * <p>Three ways a footprint is known, and the detail panel says which:
 *
 * <ul>
 * <li>{@link Kind#MEASURED} - VIIRS and MODIS serve {@code Scan} and {@code Track},
 * this pixel's own across-scan and along-track size. They grow off nadir: MODIS is
 * 1 km at nadir and was seen at 1.02 km on one record and over 4 km at the edge of
 * the swath.
 * <li>{@link Kind#DERIVED} - GOES has no footprint field, so it is computed from
 * {@code satellite_zenith_angle} and the geostationary geometry (see
 * {@link #geostationary}).
 * <li>{@link Kind#MAPPED} - RADR serves a real polygon of the burn, from Landsat or
 * Sentinel imagery. Nothing is derived; the shape is the answer.
 * </ul>
 */
public class Footprint {

    public enum Kind { MEASURED, DERIVED, MAPPED, NOMINAL }

    public final Kind kind;
    /** The long axis, meters. For a polar scanner this is across-scan (roughly east-west). */
    public final double alongScanM;
    /** The other axis, meters. For a polar scanner this is along-track (roughly north-south). */
    public final double alongTrackM;
    /** Compass heading of the {@link #alongScanM} axis, degrees; 90 is east-west. */
    public final double bearingDeg;
    /** RADR only: the mapped polygon exactly as served. */
    public final Geometry mapped;
    /** One line for the detail panel: how this footprint was arrived at. */
    public final String basis;

    private Footprint(Kind kind, double alongScanM, double alongTrackM, double bearingDeg,
            Geometry mapped, String basis) {
        this.kind = kind;
        this.alongScanM = alongScanM;
        this.alongTrackM = alongTrackM;
        this.bearingDeg = bearingDeg;
        this.mapped = mapped;
        this.basis = basis;
    }

    /** VIIRS and MODIS: the sensor's own numbers for this pixel. */
    public static Footprint measured(double scanM, double trackM) {
        return new Footprint(Kind.MEASURED, scanM, trackM, 90d, null,
                "measured by the sensor for this pixel, across-scan by along-track");
    }

    /** RADR: a burn area mapped from imagery. */
    public static Footprint mapped(Geometry geometry, String sensor) {
        return new Footprint(Kind.MAPPED, 0, 0, 0, geometry,
                "mapped from " + (sensor == null ? "satellite imagery" : sensor) + " imagery, not a sensor pixel");
    }

    /** When a record carries no size at all: the sensor's nominal one, said out loud. */
    public static Footprint nominal(double meters, String why) {
        return new Footprint(Kind.NOMINAL, meters, meters, 90d, null, why);
    }

    /**
     * A GOES pixel's ground footprint, from the view angle the record carries.
     *
     * <p>The instrument's angular field of view is fixed, so the patch of ground one
     * pixel covers grows two ways as the detection moves off the sub-satellite point:
     * the satellite is further away (slant range), and the ground is tilted away from
     * the line of sight (foreshortening, which stretches the across-scan direction
     * only). With {@code R} the Earth's radius, {@code r} the geostationary radius and
     * {@code z} the satellite zenith angle the record gives:
     *
     * <pre>
     *   sin e = (R / r) sin z          the nadir angle at the satellite
     *   g     = z - e                  the Earth-center angle to the sub-satellite point
     *   D     = sqrt(R^2 + r^2 - 2 R r cos g)      slant range to the detection
     *
     *   along-track = nominal * D / (r - R)        purely the extra distance
     *   across-scan = along-track / cos z          distance and tilt together
     * </pre>
     *
     * <p>A measured record bears this out: {@code satellite_zenith_angle 58.5} over
     * Wyoming turns a 2 km nominal pixel into about 4.1 km by 2.1 km. Drawing that as
     * a 2 km square - which is what a nominal-resolution table would give - would put
     * a crew on the wrong side of a drainage.
     *
     * <p>The long axis points at the sub-satellite point, because that is the direction
     * the tilt stretches. The satellite's own longitude comes from its name; when that
     * is not recognized the box is drawn east-west and the basis line says the
     * orientation is approximate, rather than inventing a heading.
     */
    public static Footprint geostationary(double nominalM, double zenithDeg, String satellite,
            double lat, double lon) {
        final double R = 6371000d, r = 42164000d;
        final double z = Math.toRadians(Math.max(0d, Math.min(81d, zenithDeg)));
        final double e = Math.asin(Math.max(-1d, Math.min(1d, (R / r) * Math.sin(z))));
        final double g = Math.max(0d, z - e);
        final double D = Math.sqrt(R * R + r * r - 2 * R * r * Math.cos(g));
        final double alongTrack = nominalM * D / (r - R);
        final double acrossScan = alongTrack / Math.max(0.15d, Math.cos(z));
        final Double satLon = subSatelliteLongitude(satellite);
        final double bearing = satLon == null ? 90d : bearingTo(lat, lon, 0d, satLon);
        final String size = String.format(Locale.US, "%.1f km nominal at a %.1f degree view angle", nominalM / 1000d, zenithDeg);
        return new Footprint(Kind.DERIVED, acrossScan, alongTrack, bearing, null,
                satLon == null
                        ? size + "; the satellite is not one we know the slot of, so the box is drawn east-west"
                        : size + ", long axis toward " + satellite);
    }

    /**
     * Where a GOES satellite sits. The operational slots are fixed by NOAA and have not
     * moved with the rename to GOES-East/West: GOES-19 took GOES-East at 75.2 W in 2025
     * and GOES-18 holds GOES-West at 137.0 W. Null for anything not on this list - a new
     * satellite should show up as "orientation approximate", not at the wrong heading.
     */
    private static Double subSatelliteLongitude(String satellite) {
        if (satellite == null)
            return null;
        final String s = satellite.trim().toUpperCase(Locale.US).replace("_", "-");
        if (s.contains("GOES-19") || s.contains("GOES19"))
            return -75.2d;
        if (s.contains("GOES-18") || s.contains("GOES18"))
            return -137.0d;
        if (s.contains("GOES-17") || s.contains("GOES17"))
            return -137.2d;
        if (s.contains("GOES-16") || s.contains("GOES16"))
            return -75.2d;
        return null;
    }

    /** Initial great-circle bearing from one point to another, degrees clockwise from north. */
    static double bearingTo(double lat1, double lon1, double lat2, double lon2) {
        final double p1 = Math.toRadians(lat1), p2 = Math.toRadians(lat2);
        final double dl = Math.toRadians(lon2 - lon1);
        final double y = Math.sin(dl) * Math.cos(p2);
        final double x = Math.cos(p1) * Math.sin(p2) - Math.sin(p1) * Math.cos(p2) * Math.cos(dl);
        return (Math.toDegrees(Math.atan2(y, x)) + 360d) % 360d;
    }

    /** The area covered, square meters; 0 for a mapped footprint, whose area the service gives. */
    public double areaM2() {
        return kind == Kind.MAPPED ? 0d : alongScanM * alongTrackM;
    }

    /**
     * The size an operator reads first, in whatever units their ATAK is set to - a
     * fixed unit here would show kilometers to somebody whose range and bearing readout
     * is in miles, which is how a plugin teaches an operator to distrust it.
     */
    public String sizeText() {
        if (kind == Kind.MAPPED)
            return "mapped area";
        return Units.format(alongScanM) + " x " + Units.format(alongTrackM);
    }

    /** The same size in metric, for prose that quotes the sensor's own specification. */
    public String sizeTextMetric() {
        if (kind == Kind.MAPPED)
            return "mapped area";
        return dim(alongScanM) + " x " + dim(alongTrackM);
    }

    private static String dim(double meters) {
        if (meters >= 1000)
            return String.format(Locale.US, "%.1f km", meters / 1000d);
        return String.format(Locale.US, "%.0f m", meters);
    }

    /**
     * The footprint as map geometry, centerd on the detection.
     *
     * <p>A mapped footprint is its own polygon. Everything else is a rectangle built in
     * a local east-north frame and converted back to degrees - at these sizes (under
     * 5 km) the flat-earth error is well under a meter, and the alternative, a true
     * geodesic rectangle, would be false precision over a pixel whose own position is
     * only known to a fraction of its width.
     */
    public Geometry geometry(double lat, double lon) {
        if (mapped != null)
            return mapped;
        final double halfScan = alongScanM / 2d, halfTrack = alongTrackM / 2d;
        final double th = Math.toRadians(bearingDeg);
        // Unit vectors in (east, north): u along the long axis, v across it.
        final double ue = Math.sin(th), un = Math.cos(th);
        final double ve = Math.cos(th), vn = -Math.sin(th);
        final double mPerDegLat = 111320d;
        final double mPerDegLon = 111320d * Math.max(0.02d, Math.cos(Math.toRadians(lat)));
        final double[][] signs = { { 1, 1 }, { 1, -1 }, { -1, -1 }, { -1, 1 } };
        final LineString ring = new LineString(2);
        double firstX = 0, firstY = 0;
        for (int i = 0; i < signs.length; i++) {
            final double east = signs[i][0] * halfScan * ue + signs[i][1] * halfTrack * ve;
            final double north = signs[i][0] * halfScan * un + signs[i][1] * halfTrack * vn;
            final double x = lon + east / mPerDegLon, y = lat + north / mPerDegLat;
            if (i == 0) {
                firstX = x;
                firstY = y;
            }
            ring.addPoint(x, y);
        }
        ring.addPoint(firstX, firstY);
        final Polygon poly = new Polygon(2);
        poly.addRing(ring);
        return poly;
    }
}
