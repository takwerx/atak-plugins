package com.atakmap.android.ipaws.data;

import com.atakmap.map.layer.feature.geometry.Envelope;
import com.atakmap.map.layer.feature.geometry.Geometry;
import com.atakmap.map.layer.feature.geometry.GeometryCollection;
import com.atakmap.map.layer.feature.geometry.LineString;
import com.atakmap.map.layer.feature.geometry.Point;
import com.atakmap.map.layer.feature.geometry.Polygon;

import java.util.ArrayList;
import java.util.List;

/**
 * Does one alert area touch one county.
 *
 * <p>This exists because NWS's own county attribution is not the whole answer. An
 * alert names counties in {@code geocode.SAME}, but a <b>marine</b> zone names none at
 * all, and a forecast zone that laps over a county line is attributed by NWS however
 * NWS chose. The operator's rule is simpler and is what a person means by picking a
 * county: if the alert's area touches the county, they want it.
 *
 * <p>Written out rather than handed to GDAL, which ATAK does ship
 * ({@code org.gdal.ogr.Geometry.CreateFromJson} then {@code Intersects}) and which
 * would be exact. The reason is that this runs inside the filter, on every alert
 * against every selected county, and a native library that has to be initialised in
 * the plugin's classloader is a failure mode in a path whose whole job is deciding
 * what a user is allowed to see. Ninety lines that can be tested on a laptop against
 * real NWS polygons is the better trade; GDAL is there if an exact overlay operation
 * is ever needed.
 *
 * <p>Where it is approximate it is approximate <b>towards showing the alert</b>: a
 * hole in a polygon is not treated as a hole, so an alert falling entirely inside one
 * would be called a touch. A spare alert on the map is a far better failure than a
 * missing one.
 */
public final class Geo {

    private Geo() {
    }

    /**
     * Where to put an area's label: inside it, and near the middle.
     *
     * <p>ATAK labels a polygon along its <b>boundary</b> -- a LabelPointStyle on a
     * polygon renders down the edge, rotated with it, which reads as a label on a line
     * rather than a name for the area. There is no placement flag on the style to
     * change that ({@code LabelPointStyle$Style} is only bold and italic), so the label
     * goes on a point of its own and this works out where that point should be.
     *
     * <p>The centroid of the largest ring is the answer for almost every zone. It is
     * not for a crescent or a C -- a coastal marine zone wrapping a headland has its
     * centroid on dry land outside itself -- so a centroid that falls outside is
     * replaced by the middle of the widest run of interior along that latitude, which
     * is cheap and lands inside by construction.
     *
     * @return {@code {lon, lat}}, or null when there is nothing to label
     */
    public static double[] labelPoint(Geometry g) {
        if (g == null)
            return null;
        final List<double[][]> all = rings(g);
        double[][] biggest = null;
        double biggestArea = -1;
        for (double[][] r : all) {
            final double a = Math.abs(signedArea(r));
            if (a > biggestArea) {
                biggestArea = a;
                biggest = r;
            }
        }
        if (biggest == null || biggest.length < 3)
            return envelopeCenter(g);

        final double[] c = centroid(biggest);
        if (c != null && contains(biggest, c))
            return c;
        final double[] scan = widestSpanAt(biggest, c == null ? midLat(biggest) : c[1]);
        if (scan != null)
            return scan;
        return c != null ? c : envelopeCenter(g);
    }

    private static double[] envelopeCenter(Geometry g) {
        final Envelope e = g.getEnvelope();
        return e == null ? null : new double[] { (e.minX + e.maxX) / 2d, (e.minY + e.maxY) / 2d };
    }

    private static double midLat(double[][] ring) {
        double lo = Double.MAX_VALUE, hi = -Double.MAX_VALUE;
        for (double[] p : ring) {
            lo = Math.min(lo, p[1]);
            hi = Math.max(hi, p[1]);
        }
        return (lo + hi) / 2d;
    }

    /** Shoelace. Sign tells winding; the magnitude is twice the area. */
    private static double signedArea(double[][] r) {
        double a = 0;
        for (int i = 0, j = r.length - 1; i < r.length; j = i++)
            a += (r[j][0] * r[i][1]) - (r[i][0] * r[j][1]);
        return a / 2d;
    }

    /** Area-weighted centroid of a ring; null for a degenerate one. */
    private static double[] centroid(double[][] r) {
        double a = 0, cx = 0, cy = 0;
        for (int i = 0, j = r.length - 1; i < r.length; j = i++) {
            final double cross = (r[j][0] * r[i][1]) - (r[i][0] * r[j][1]);
            a += cross;
            cx += (r[j][0] + r[i][0]) * cross;
            cy += (r[j][1] + r[i][1]) * cross;
        }
        if (Math.abs(a) < 1e-12)
            return null;
        return new double[] { cx / (3d * a), cy / (3d * a) };
    }

    /**
     * The midpoint of the widest stretch of interior along one latitude. Used when the
     * centroid falls outside the shape, which is what a crescent does.
     */
    private static double[] widestSpanAt(double[][] r, double lat) {
        final List<Double> xs = new ArrayList<>();
        for (int i = 0, j = r.length - 1; i < r.length; j = i++) {
            final double y1 = r[j][1], y2 = r[i][1];
            if ((y1 > lat) == (y2 > lat))
                continue;
            final double t = (lat - y1) / (y2 - y1);
            xs.add(r[j][0] + t * (r[i][0] - r[j][0]));
        }
        if (xs.size() < 2)
            return null;
        java.util.Collections.sort(xs);
        double best = -1, bestMid = 0;
        // Crossings pair up into inside-outside-inside runs; every other gap is interior.
        for (int i = 0; i + 1 < xs.size(); i += 2) {
            final double w = xs.get(i + 1) - xs.get(i);
            if (w > best) {
                best = w;
                bestMid = (xs.get(i) + xs.get(i + 1)) / 2d;
            }
        }
        return best <= 0 ? null : new double[] { bestMid, lat };
    }

    /**
     * One flat collection of shapes, never a collection inside a collection.
     *
     * <p>An alert's area is built from zones, and a zone arrives as a Polygon, a
     * MultiPolygon or a GeometryCollection of both, so assembling them nested
     * collections up to three deep. ATAK's feature store does not survive that: on
     * 2026-09-25, 69 of 465 stored areas had been written as a single point at 0,0,
     * every one of them nested two or three deep, while all 369 at one level or none
     * were intact. The Houston Air Quality Alert was one -- its label drawn over Houston
     * from the geometry in hand, and no area under it, because the area in the store
     * was that point. Mostly coastal alerts, whose zones are MultiPolygons.
     *
     * @return the single shape when there is one, otherwise a collection of shapes
     */
    public static Geometry flatten(Geometry g) {
        if (!(g instanceof GeometryCollection))
            return g;
        final List<Geometry> leaves = new ArrayList<>();
        leaves(g, leaves);
        if (leaves.isEmpty())
            return null;
        if (leaves.size() == 1)
            return leaves.get(0);
        final GeometryCollection flat = new GeometryCollection(2);
        for (Geometry leaf : leaves)
            flat.addGeometry(leaf);
        return flat;
    }

    private static void leaves(Geometry g, List<Geometry> out) {
        if (g instanceof GeometryCollection) {
            for (Geometry child : ((GeometryCollection) g).getGeometries())
                leaves(child, out);
        } else if (g != null) {
            out.add(g);
        }
    }

    /**
     * True when any part of the area is within {@code radiusM} of the point, or the
     * point is inside it. For the distance scope: "within 25 mi of me" means the alert
     * reaches to within 25 mi, not that its middle does -- a statewide Heat Advisory
     * you are standing in is zero miles away.
     *
     * <p>Measured on a local flat projection around the point, which at the radii the
     * scope offers (50 mi at most) is off by well under a percent. Holes are not
     * holes, as everywhere in this class: wrong in that direction shows an alert.
     */
    public static boolean withinDistance(Geometry g, double lat, double lon, double radiusM) {
        if (g == null)
            return false;
        final Envelope e = g.getEnvelope();
        if (e != null && envelopeDistanceM(e.minY, e.minX, e.maxY, e.maxX, lat, lon) > radiusM)
            return false;
        final double kx = Math.cos(Math.toRadians(lat)) * M_PER_DEG_LAT;
        final double r2 = radiusM * radiusM;
        for (double[][] ring : rings(g)) {
            final int n = ring.length;
            if (n == 1) {
                final double x = (ring[0][0] - lon) * kx, y = (ring[0][1] - lat) * M_PER_DEG_LAT;
                if (x * x + y * y <= r2)
                    return true;
                continue;
            }
            if (n >= 3 && contains(ring, lon, lat))
                return true;
            for (int i = 0; i + 1 < n; i++) {
                final double ax = (ring[i][0] - lon) * kx, ay = (ring[i][1] - lat) * M_PER_DEG_LAT;
                final double bx = (ring[i + 1][0] - lon) * kx, by = (ring[i + 1][1] - lat) * M_PER_DEG_LAT;
                if (segmentToOriginSq(ax, ay, bx, by) <= r2)
                    return true;
            }
        }
        return false;
    }

    /**
     * Meters from a point to the nearest edge of a lat/lon box, 0 when inside it. The
     * cheap first test for the scope: most alerts are nowhere near, and this rejects
     * them without building their geometry.
     */
    public static double envelopeDistanceM(double south, double west, double north,
            double east, double lat, double lon) {
        final double cy = Math.max(south, Math.min(north, lat));
        final double cx = Math.max(west, Math.min(east, lon));
        final double dy = (cy - lat) * M_PER_DEG_LAT;
        final double dx = (cx - lon) * Math.cos(Math.toRadians(lat)) * M_PER_DEG_LAT;
        return Math.sqrt(dx * dx + dy * dy);
    }

    private static final double M_PER_DEG_LAT = 111_320d;

    /**
     * A GeoJSON geometry's extent straight from its numbers, {@code {south, west,
     * north, east}}, or null when it has none -- without building ATAK geometry, which
     * is the expensive part. Out-of-range numbers are skipped, as the parser skips them.
     */
    public static double[] envelopeOf(org.json.JSONObject g) {
        if (g == null)
            return null;
        final double[] e = { Double.MAX_VALUE, Double.MAX_VALUE, -Double.MAX_VALUE, -Double.MAX_VALUE };
        final org.json.JSONArray gs = g.optJSONArray("geometries");
        if (gs != null) {
            for (int i = 0; i < gs.length(); i++)
                grow(e, envelopeOf(gs.optJSONObject(i)));
        } else {
            walk(g.optJSONArray("coordinates"), e);
        }
        return e[0] > e[2] ? null : e;
    }

    /** Widens {@code into} to take in {@code e}; either may be null or empty. */
    public static void grow(double[] into, double[] e) {
        if (into == null || e == null)
            return;
        into[0] = Math.min(into[0], e[0]);
        into[1] = Math.min(into[1], e[1]);
        into[2] = Math.max(into[2], e[2]);
        into[3] = Math.max(into[3], e[3]);
    }

    private static void walk(org.json.JSONArray a, double[] e) {
        if (a == null || a.length() == 0)
            return;
        if (a.opt(0) instanceof org.json.JSONArray) {
            for (int i = 0; i < a.length(); i++)
                walk(a.optJSONArray(i), e);
            return;
        }
        final double lon = a.optDouble(0), lat = a.optDouble(1);
        if (Double.isNaN(lon) || Double.isNaN(lat) || Math.abs(lon) > 180 || Math.abs(lat) > 90)
            return;
        e[0] = Math.min(e[0], lat);
        e[1] = Math.min(e[1], lon);
        e[2] = Math.max(e[2], lat);
        e[3] = Math.max(e[3], lon);
    }

    /** Squared distance from the origin to the segment a-b. */
    private static double segmentToOriginSq(double ax, double ay, double bx, double by) {
        final double dx = bx - ax, dy = by - ay;
        final double len2 = dx * dx + dy * dy;
        double t = len2 == 0 ? 0 : -(ax * dx + ay * dy) / len2;
        t = Math.max(0, Math.min(1, t));
        final double px = ax + t * dx, py = ay + t * dy;
        return px * px + py * py;
    }

    /** Even-odd point in ring, {@code [lon, lat]} pairs. */
    private static boolean contains(double[][] ring, double lon, double lat) {
        boolean in = false;
        for (int i = 0, j = ring.length - 1; i < ring.length; j = i++) {
            final double xi = ring[i][0], yi = ring[i][1], xj = ring[j][0], yj = ring[j][1];
            if ((yi > lat) != (yj > lat)
                    && lon < (xj - xi) * (lat - yi) / (yj - yi) + xi)
                in = !in;
        }
        return in;
    }

    /** True when the two areas touch at all. Null geometry never touches anything. */
    public static boolean intersects(Geometry a, Geometry b) {
        if (a == null || b == null)
            return false;
        // Envelopes first: nearly every pair is a miss, and this rejects those for
        // the price of four comparisons.
        if (!envelopesOverlap(a, b))
            return false;
        final List<double[][]> ringsA = rings(a);
        final List<double[][]> ringsB = rings(b);
        for (double[][] ra : ringsA)
            for (double[][] rb : ringsB)
                if (ringsTouch(ra, rb))
                    return true;
        return false;
    }

    private static boolean envelopesOverlap(Geometry a, Geometry b) {
        final Envelope ea = a.getEnvelope();
        final Envelope eb = b.getEnvelope();
        if (ea == null || eb == null)
            return true;  // cannot tell, so do not reject on it
        return ea.minX <= eb.maxX && eb.minX <= ea.maxX
                && ea.minY <= eb.maxY && eb.minY <= ea.maxY;
    }

    /** Every ring of every part, as {@code [[lon, lat], ...]}. */
    private static List<double[][]> rings(Geometry g) {
        final List<double[][]> out = new ArrayList<>();
        collect(g, out);
        return out;
    }

    private static void collect(Geometry g, List<double[][]> out) {
        if (g instanceof GeometryCollection) {
            for (Geometry child : ((GeometryCollection) g).getGeometries())
                collect(child, out);
        } else if (g instanceof Polygon) {
            final Polygon p = (Polygon) g;
            final LineString ext = p.getExteriorRing();
            if (ext != null)
                out.add(points(ext));
            // Interior rings are collected as ordinary rings on purpose: treating them
            // as holes would mean an alert inside one is "no touch", and being wrong
            // in that direction hides an alert.
            for (LineString hole : p.getInteriorRings())
                out.add(points(hole));
        } else if (g instanceof LineString) {
            out.add(points((LineString) g));
        } else if (g instanceof Point) {
            final Point pt = (Point) g;
            out.add(new double[][] { { pt.getX(), pt.getY() } });
        }
    }

    private static double[][] points(LineString ls) {
        final int n = ls.getNumPoints();
        final double[][] out = new double[n][2];
        for (int i = 0; i < n; i++) {
            out[i][0] = ls.getX(i);
            out[i][1] = ls.getY(i);
        }
        return out;
    }

    /**
     * Two rings touch when an edge of one crosses an edge of the other, or when
     * either lies wholly inside the other.
     */
    static boolean ringsTouch(double[][] a, double[][] b) {
        if (a.length == 0 || b.length == 0)
            return false;
        // A crossing edge is the common case for two areas that share a border.
        for (int i = 0; i + 1 < a.length; i++)
            for (int j = 0; j + 1 < b.length; j++)
                if (segmentsCross(a[i], a[i + 1], b[j], b[j + 1]))
                    return true;
        // Containment, either way round: one area entirely inside the other has no
        // crossing edges at all.
        return contains(b, a[0]) || contains(a, b[0]);
    }

    /** Ray casting. A ring of fewer than three points contains nothing. */
    static boolean contains(double[][] ring, double[] p) {
        if (ring.length < 3)
            return false;
        boolean in = false;
        for (int i = 0, j = ring.length - 1; i < ring.length; j = i++) {
            final double xi = ring[i][0], yi = ring[i][1];
            final double xj = ring[j][0], yj = ring[j][1];
            if ((yi > p[1]) != (yj > p[1])
                    && p[0] < (xj - xi) * (p[1] - yi) / (yj - yi) + xi)
                in = !in;
        }
        return in;
    }

    /** Proper or touching intersection of two segments, orientation test. */
    static boolean segmentsCross(double[] p1, double[] p2, double[] p3, double[] p4) {
        final double d1 = cross(p3, p4, p1);
        final double d2 = cross(p3, p4, p2);
        final double d3 = cross(p1, p2, p3);
        final double d4 = cross(p1, p2, p4);
        if (((d1 > 0 && d2 < 0) || (d1 < 0 && d2 > 0))
                && ((d3 > 0 && d4 < 0) || (d3 < 0 && d4 > 0)))
            return true;
        // Collinear and overlapping counts as touching: two zones sharing a border
        // segment exactly is not a miss.
        return (d1 == 0 && onSegment(p3, p4, p1)) || (d2 == 0 && onSegment(p3, p4, p2))
                || (d3 == 0 && onSegment(p1, p2, p3)) || (d4 == 0 && onSegment(p1, p2, p4));
    }

    private static double cross(double[] a, double[] b, double[] c) {
        return (b[0] - a[0]) * (c[1] - a[1]) - (b[1] - a[1]) * (c[0] - a[0]);
    }

    private static boolean onSegment(double[] a, double[] b, double[] p) {
        return Math.min(a[0], b[0]) <= p[0] && p[0] <= Math.max(a[0], b[0])
                && Math.min(a[1], b[1]) <= p[1] && p[1] <= Math.max(a[1], b[1]);
    }
}
