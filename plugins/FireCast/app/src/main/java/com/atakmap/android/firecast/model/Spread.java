package com.atakmap.android.firecast.model;

import com.atakmap.map.layer.feature.geometry.LineString;
import com.atakmap.map.layer.feature.geometry.Polygon;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;

/**
 * Fire across the ground: when the fire reaches every cell around the start.
 *
 * <p>Minimum travel time, the way FlamMap does it. Each 30 m cell gets its own head
 * rate, heading and ellipse shape from the engine, for its fuel, slope and aspect
 * under the run's one wind. From the start cell, the earliest arrival at every cell
 * is found by Dijkstra over sixteen neighbors, each move costing its length over the
 * rate in that direction, which is the ellipse's: head rate at the heading, less and
 * less off it, backing rate behind. Ground that does not burn (urban, water, bare,
 * agriculture, snow) is never entered. The rings on the map are the cells reached by
 * 20, 40 and 60 minutes, their edges traced.
 */
public final class Spread {

    /** One cell's fire behavior. */
    private static final class Cell {
        /** Head rate, meters per minute; 0 means the cell does not burn. */
        final double rate;
        /** Where the head goes, degrees clockwise from north. */
        final double headingDeg;
        /** The ellipse's eccentricity, 0 round to just under 1 a needle. */
        final double ecc;
        final double flameFt;
        /** How far embers from this cell's fire can carry, meters (Albini); 0 for none. */
        final double spotM;

        Cell(double rate, double headingDeg, double ecc, double flameFt, double spotM) {
            this.rate = rate;
            this.headingDeg = headingDeg;
            this.ecc = ecc;
            this.flameFt = flameFt;
            this.spotM = spotM;
        }
    }

    private static final Cell BARRIER = new Cell(0, 0, 0, 0, 0);
    private static final int[][] MOVES = {
            { 1, 0 }, { -1, 0 }, { 0, 1 }, { 0, -1 }, { 1, 1 }, { 1, -1 }, { -1, 1 }, { -1, -1 },
            { 2, 1 }, { 2, -1 }, { -2, 1 }, { -2, -1 }, { 1, 2 }, { 1, -2 }, { -1, 2 }, { -1, -2 } };

    /** The result: arrival minutes per cell on a local grid of meters around the start. */
    public static final class Result {
        public final int nx, ny;
        /** Cell size, meters. */
        public final double cell;
        /** Grid origin: the south-west corner, degrees. */
        public final double lat0, lon0, dLat, dLon;
        /** Arrival time per cell, minutes; +infinity when never reached. */
        public final float[] arrival;
        /** Peak flame length among cells reached, feet, by step. */
        public final double[] peakFlameFt;
        public final int cellsSeen, cellsBurnable;
        /** The longest ember carry any reached cell had, meters, and how many times the fire crossed a wall that way. */
        public double maxSpotM;
        public int spotCrossings;
        /** How many different fuel models the fire crossed. */
        public final int fuelsCrossed;
        /** Where the run began when the start itself did not burn: meters and bearing from the start; 0 when it began there. */
        public double startOffsetM, startOffsetDeg;

        Result(int nx, int ny, double cell, double lat0, double lon0, double dLat, double dLon, float[] arrival,
                double[] peakFlameFt, int cellsSeen, int cellsBurnable, int fuelsCrossed) {
            this.nx = nx;
            this.ny = ny;
            this.cell = cell;
            this.lat0 = lat0;
            this.lon0 = lon0;
            this.dLat = dLat;
            this.dLon = dLon;
            this.arrival = arrival;
            this.peakFlameFt = peakFlameFt;
            this.cellsSeen = cellsSeen;
            this.cellsBurnable = cellsBurnable;
            this.fuelsCrossed = fuelsCrossed;
        }

        /** Acres reached by a time. */
        public double acres(double minutes) {
            int n = 0;
            for (float t : arrival)
                if (t <= minutes)
                    n++;
            return n * cell * cell / 4046.856d;
        }

        /** The farthest reach by a time: meters and the bearing to it from the start. */
        public double[] reach(double minutes) {
            final int cx = nx / 2, cy = ny / 2;
            double best = 0, bearing = 0;
            for (int y = 0; y < ny; y++)
                for (int x = 0; x < nx; x++) {
                    if (arrival[y * nx + x] > minutes)
                        continue;
                    final double dx = (x - cx) * cell, dy = (y - cy) * cell;
                    final double d = Math.sqrt(dx * dx + dy * dy);
                    if (d > best) {
                        best = d;
                        bearing = (Math.toDegrees(Math.atan2(dx, dy)) + 360d) % 360d;
                    }
                }
            return new double[] { best, bearing };
        }

        /**
         * The ground reached by a time as rings, in degrees: the contour of the arrival
         * field at that time, run between cell centers and smoothed, so it reads as a
         * fire's edge and not as the cells under it. Outer boundaries only.
         */
        public List<Polygon> rings(double minutes) {
            final List<List<double[]>> loops = Isoline.trace(arrival, nx, ny, (float) minutes);
            final List<Polygon> out = new ArrayList<>();
            for (List<double[]> loop : loops) {
                if (loop.size() < 3)
                    continue;
                final LineString ring = new LineString(2);
                for (double[] p : loop)
                    ring.addPoint(lon0 + (p[0] + 0.5) * dLon, lat0 + (p[1] + 0.5) * dLat);
                final double[] f = loop.get(0);
                ring.addPoint(lon0 + (f[0] + 0.5) * dLon, lat0 + (f[1] + 0.5) * dLat);
                final Polygon poly = new Polygon(2);
                poly.addRing(ring);
                out.add(poly);
            }
            return out;
        }
    }

    /** What a cell's behavior is asked with: the run's weather and moistures. */
    public interface Behavior {
        /** @return [head rate ft/min, heading deg, A ft/min, B ft/min, C ft/min, flame ft, spot distance m], or null for no spread */
        double[] run(int fuelModel, double slopeDeg, double aspectDeg);
    }

    private Spread() {
    }

    /**
     * @param halfKm  the grid's reach each way from the start
     * @param maxMin  arrivals past this are not followed
     */
    public static Result run(Landscape land, double lat, double lon, double halfKm, double maxMin, Behavior behavior) {
        // Half the grid's own 30 m: the fuel is sampled nearest, so the run follows the
        // same ground, but the front moves in finer steps and the rings come out smooth.
        final double cell = 15d;
        final int half = (int) Math.round(halfKm * 1000d / cell);
        final int nx = 2 * half + 1, ny = nx;
        final double mPerDegLat = 111320d, mPerDegLon = 111320d * Math.max(0.02d, Math.cos(Math.toRadians(lat)));
        final double dLat = cell / mPerDegLat, dLon = cell / mPerDegLon;
        final double lat0 = lat - half * dLat - dLat / 2d, lon0 = lon - half * dLon - dLon / 2d;

        // Each cell's behavior, computed once per fuel, slope step and aspect step.
        final Cell[] cells = new Cell[nx * ny];
        final Map<Integer, Cell> byKind = new HashMap<>();
        final java.util.Set<Integer> fuels = new java.util.HashSet<>();
        int burnable = 0;
        for (int y = 0; y < ny; y++) {
            for (int x = 0; x < nx; x++) {
                final double cLat = lat0 + (y + 0.5) * dLat, cLon = lon0 + (x + 0.5) * dLon;
                final int fuel = effectiveFuel(land.fuel.at(cLat, cLon), land.evt.at(cLat, cLon), land.fuel.nodata, land.evt.nodata);
                int slope = land.slope.at(cLat, cLon), aspect = land.aspect.at(cLat, cLon);
                if (slope == land.slope.nodata || slope < 0)
                    slope = 0;
                if (aspect == land.aspect.nodata || aspect < 0)
                    aspect = 0;
                final boolean burns = fuel > 0 && (fuel >= 100 || fuel <= 13);
                if (!burns) {
                    cells[y * nx + x] = BARRIER;
                    continue;
                }
                burnable++;
                final int slopeStep = Math.min(80, (slope / 5) * 5), aspectStep = ((aspect + 11) / 22) * 22 % 360;
                final int key = (fuel << 16) | (slopeStep << 8) | (aspectStep / 22);
                Cell c = byKind.get(key);
                if (c == null) {
                    final double[] r = behavior.run(fuel, slopeStep, aspectStep);
                    if (r == null || r[0] <= 0.01)
                        c = BARRIER;
                    else {
                        final double b = r[3], cc = r[4];
                        final double ecc = b > 0 ? Math.max(0, Math.min(0.98, cc / b)) : 0;
                        c = new Cell(r[0] * 0.3048d, r[1], ecc, r[5], r.length > 6 ? r[6] : 0);
                    }
                    byKind.put(key, c);
                }
                if (c != BARRIER)
                    fuels.add(fuel);
                cells[y * nx + x] = c;
            }
        }

        // Dijkstra from the start cell. A move's time is its length over the mean of
        // the rate leaving the source in that direction and the rate the destination
        // would have in it, so a slow cell is slow to enter and slow to leave.
        final float[] arrival = new float[nx * ny];
        java.util.Arrays.fill(arrival, Float.POSITIVE_INFINITY);
        final int start = half * nx + half;
        double offsetM = 0, offsetDeg = 0;
        if (cells[start] == BARRIER) {
            // Light the nearest burnable cell instead of refusing: the tap was on a road
            // or a roof. Up to 8 cells (120 m) away, and the result says how far.
            int best = -1;
            double bestD = Double.MAX_VALUE;
            for (int i = 0; i < cells.length; i++) {
                if (cells[i] == BARRIER)
                    continue;
                final double dx = i % nx - half, dy = i / nx - half, d = dx * dx + dy * dy;
                if (d < bestD) {
                    bestD = d;
                    best = i;
                }
            }
            if (best < 0 || bestD > 64)
                return new Result(nx, ny, cell, lat0, lon0, dLat, dLon, arrival, new double[0], 0, burnable, 0);
            arrival[best] = 0;
            final double dx = (best % nx - half) * cell, dy = (best / nx - half) * cell;
            offsetM = Math.sqrt(dx * dx + dy * dy);
            offsetDeg = (Math.toDegrees(Math.atan2(dx, dy)) + 360d) % 360d;
        } else {
            arrival[start] = 0;
        }
        final PriorityQueue<long[]> q = new PriorityQueue<>(1024, new java.util.Comparator<long[]>() {
            @Override
            public int compare(long[] a, long[] b) {
                return Double.compare(Double.longBitsToDouble(a[0]), Double.longBitsToDouble(b[0]));
            }
        });
        for (int i = 0; i < arrival.length; i++)
            if (arrival[i] == 0)
                q.add(new long[] { Double.doubleToLongBits(0), i });
        final boolean[] done = new boolean[nx * ny];
        int seen = 0, spotCrossings = 0;
        double maxSpot = 0;
        final double[] moveLen = new double[MOVES.length], moveBearing = new double[MOVES.length];
        for (int m = 0; m < MOVES.length; m++) {
            moveLen[m] = Math.sqrt(MOVES[m][0] * MOVES[m][0] + MOVES[m][1] * MOVES[m][1]) * cell;
            moveBearing[m] = (Math.toDegrees(Math.atan2(MOVES[m][0], MOVES[m][1])) + 360d) % 360d;
        }
        while (!q.isEmpty()) {
            final long[] top = q.poll();
            final double t = Double.longBitsToDouble(top[0]);
            final int i = (int) top[1];
            if (done[i])
                continue;
            done[i] = true;
            seen++;
            if (t > maxMin)
                break;
            if (cells[i].spotM > maxSpot)
                maxSpot = cells[i].spotM;
            final int x = i % nx, y = i / nx;
            final Cell from = cells[i];
            for (int m = 0; m < MOVES.length; m++) {
                final int nx2 = x + MOVES[m][0], ny2 = y + MOVES[m][1];
                if (nx2 < 0 || ny2 < 0 || nx2 >= nx || ny2 >= ny)
                    continue;
                final int j = ny2 * nx + nx2;
                if (done[j])
                    continue;
                final Cell to = cells[j];
                if (to == BARRIER) {
                    // Spotting: within 45 degrees of the head, embers carry across a wall
                    // (a freeway, a commercial block) narrower than the spot distance and
                    // light the first fuel beyond it. One ember flight, at the head's pace.
                    if (from.spotM <= 0 || m >= 8)
                        continue;
                    final double off = Math.abs(((moveBearing[m] - from.headingDeg) % 360 + 540) % 360 - 180);
                    if (off > 45)
                        continue;
                    final int maxSteps = (int) Math.floor(from.spotM / moveLen[m]);
                    int sx = nx2, sy = ny2, steps = 1;
                    int landing = -1;
                    while (steps < maxSteps) {
                        sx += MOVES[m][0];
                        sy += MOVES[m][1];
                        steps++;
                        if (sx < 0 || sy < 0 || sx >= nx || sy >= ny)
                            break;
                        final int k = sy * nx + sx;
                        if (cells[k] != BARRIER) {
                            landing = k;
                            break;
                        }
                    }
                    if (landing < 0 || done[landing])
                        continue;
                    final double rHead = rateToward(from, from.headingDeg);
                    if (rHead <= 0)
                        continue;
                    final double nt = t + steps * moveLen[m] / rHead;
                    if (nt < arrival[landing]) {
                        arrival[landing] = (float) nt;
                        q.add(new long[] { Double.doubleToLongBits(nt), landing });
                        spotCrossings++;
                    }
                    continue;
                }
                final double rFrom = rateToward(from, moveBearing[m]), rTo = rateToward(to, moveBearing[m]);
                if (rFrom <= 0 && rTo <= 0)
                    continue;
                final double rate = rFrom > 0 && rTo > 0 ? 2d / (1d / rFrom + 1d / rTo) : Math.max(rFrom, rTo);
                final double nt = t + moveLen[m] / rate;
                if (nt < arrival[j]) {
                    arrival[j] = (float) nt;
                    q.add(new long[] { Double.doubleToLongBits(nt), j });
                }
            }
        }
        final Result res = new Result(nx, ny, cell, lat0, lon0, dLat, dLon, arrival, flameByStep(cells, arrival), seen, burnable, fuels.size());
        res.startOffsetM = offsetM;
        res.startOffsetDeg = offsetDeg;
        res.maxSpotM = maxSpot;
        res.spotCrossings = spotCrossings;
        return res;
    }

    /** The engine's number for the burnable urban fuel registered in the JNI. */
    public static final int BU1 = 220;

    /**
     * What a cell burns as. LANDFIRE's fuel grid calls every street and every house
     * NB1 (91), and a run that honors that stops at the first subdivision, which no
     * conflagration ever did. Its vegetation grid tells them apart: houses burn house
     * to house as BU1 (Moran 2025); a road is slow timber litter the fire crosses
     * (the paper's closest fit); open space is sparse grass; commercial blocks,
     * water, bare ground, farmed ground and snow stay walls.
     */
    static int effectiveFuel(int fuel, int evt, int fuelNodata, int evtNodata) {
        if (fuel == fuelNodata)
            return -1;
        if (fuel != 91)
            return fuel;
        if (evt == evtNodata)
            return -1;
        if (evt == 7297 || evt == 7298 || (evt >= 7900 && evt <= 7919))
            return BU1;
        if (evt == 7299)
            return 181;   // TL1, the slow one
        if (evt == 7296)
            return 101;   // GR1
        return -1;        // 7300 and anything else developed: a wall
    }

    /** The ellipse's rate in a direction: the head rate at the heading, backing behind. */
    private static double rateToward(Cell c, double bearingDeg) {
        if (c == BARRIER || c.rate <= 0)
            return 0;
        final double phi = Math.toRadians(bearingDeg - c.headingDeg);
        return c.rate * (1d - c.ecc) / (1d - c.ecc * Math.cos(phi));
    }

    private static double[] flameByStep(Cell[] cells, float[] arrival) {
        final double[] out = new double[FireModel.STEPS_MIN.length];
        for (int i = 0; i < cells.length; i++) {
            if (cells[i] == BARRIER || arrival[i] == Float.POSITIVE_INFINITY)
                continue;
            for (int s = 0; s < FireModel.STEPS_MIN.length; s++)
                if (arrival[i] <= FireModel.STEPS_MIN[s] && cells[i].flameFt > out[s])
                    out[s] = cells[i].flameFt;
        }
        return out;
    }

    /**
     * Marching squares over the arrival field: the line where arrival equals a time,
     * crossing each cell edge at the interpolated point, chained into loops and
     * rounded off. Coordinates are cell indices of cell centers.
     */
    static final class Isoline {
        static List<List<double[]>> trace(float[] arrival, int nx, int ny, float t) {
            // Unreached cells count as far, so the line falls between a reached cell and
            // its unreached neighbor rather than at infinity.
            final float far = t * 3f;
            final float[] f = new float[arrival.length];
            for (int i = 0; i < f.length; i++)
                f[i] = arrival[i] > far ? far : arrival[i];
            final Map<Long, double[]> segStart = new HashMap<>();
            final List<double[][]> segs = new ArrayList<>();
            for (int y = 0; y < ny - 1; y++) {
                for (int x = 0; x < nx - 1; x++) {
                    final float a = f[y * nx + x], b = f[y * nx + x + 1], c = f[(y + 1) * nx + x + 1], d = f[(y + 1) * nx + x];
                    final int code = (a <= t ? 1 : 0) | (b <= t ? 2 : 0) | (c <= t ? 4 : 0) | (d <= t ? 8 : 0);
                    if (code == 0 || code == 15)
                        continue;
                    // Edge crossings: bottom (a-b), right (b-c), top (d-c), left (a-d).
                    final double[] pb = { x + lerp(a, b, t), y }, pr = { x + 1, y + lerp(b, c, t) };
                    final double[] pt = { x + lerp(d, c, t), y + 1 }, pl = { x, y + lerp(a, d, t) };
                    switch (code) {
                        case 1: seg(segs, pl, pb); break;
                        case 2: seg(segs, pb, pr); break;
                        case 3: seg(segs, pl, pr); break;
                        case 4: seg(segs, pr, pt); break;
                        case 5: {
                            final boolean center = (a + b + c + d) / 4f <= t;
                            if (center) { seg(segs, pl, pt); seg(segs, pr, pb); }
                            else { seg(segs, pl, pb); seg(segs, pr, pt); }
                            break;
                        }
                        case 6: seg(segs, pb, pt); break;
                        case 7: seg(segs, pl, pt); break;
                        case 8: seg(segs, pt, pl); break;
                        case 9: seg(segs, pt, pb); break;
                        case 10: {
                            final boolean center = (a + b + c + d) / 4f <= t;
                            if (center) { seg(segs, pb, pl); seg(segs, pt, pr); }
                            else { seg(segs, pb, pr); seg(segs, pt, pl); }
                            break;
                        }
                        case 11: seg(segs, pt, pr); break;
                        case 12: seg(segs, pr, pl); break;
                        case 13: seg(segs, pr, pb); break;
                        case 14: seg(segs, pb, pl); break;
                        default: break;
                    }
                }
            }
            // Chain by shared endpoints, whichever way round each segment was emitted:
            // from a segment's far end, find the unused segment touching it, step to its
            // other end, until the start point comes round again.
            final Map<Long, List<Integer>> atPoint = new HashMap<>();
            for (int i = 0; i < segs.size(); i++) {
                for (int e = 0; e < 2; e++) {
                    final long k = q(segs.get(i)[e]);
                    List<Integer> l = atPoint.get(k);
                    if (l == null)
                        atPoint.put(k, l = new ArrayList<>());
                    l.add(i);
                }
            }
            final boolean[] used = new boolean[segs.size()];
            final List<List<double[]>> loops = new ArrayList<>();
            for (int i0 = 0; i0 < segs.size(); i0++) {
                if (used[i0])
                    continue;
                final List<double[]> loop = new ArrayList<>();
                int i = i0;
                double[] at = segs.get(i)[0];
                double[] to = segs.get(i)[1];
                used[i] = true;
                loop.add(at);
                int guard = 0;
                while (guard++ < 4_000_000) {
                    loop.add(to);
                    final List<Integer> cands = atPoint.get(q(to));
                    int next = -1;
                    if (cands != null)
                        for (int c : cands)
                            if (!used[c]) {
                                next = c;
                                break;
                            }
                    if (next < 0)
                        break;
                    used[next] = true;
                    final double[][] sg = segs.get(next);
                    to = q(sg[0]) == q(to) ? sg[1] : sg[0];
                    if (q(to) == q(loop.get(0)))
                        break;
                }
                if (loop.size() >= 3)
                    loops.add(loop);
            }
            // The outer rings are the ones wound the same way as the largest; the rest
            // are holes, islands of ground that did not burn, left out for now.
            double largest = 0;
            for (List<double[]> l : loops)
                if (Math.abs(signedArea(l)) > Math.abs(largest))
                    largest = signedArea(l);
            final List<List<double[]>> out = new ArrayList<>();
            for (List<double[]> l : loops)
                if (Math.abs(signedArea(l)) >= 0.5 && Math.signum(signedArea(l)) == Math.signum(largest))
                    out.add(smooth(smooth(l)));
            return out;
        }

        private static double lerp(float a, float b, float t) {
            if (a == b)
                return 0.5;
            return Math.max(0, Math.min(1, (t - a) / (b - a)));
        }

        private static void seg(List<double[][]> segs, double[] p, double[] q) {
            segs.add(new double[][] { p, q });
        }

        private static long q(double[] p) {
            return (Math.round(p[0] * 64) << 32) ^ (Math.round(p[1] * 64) & 0xFFFFFFFFL);
        }

        private static double signedArea(List<double[]> loop) {
            double a = 0;
            for (int i = 0; i < loop.size(); i++) {
                final double[] p = loop.get(i), r = loop.get((i + 1) % loop.size());
                a += p[0] * r[1] - r[0] * p[1];
            }
            return a / 2d;
        }

        /** Chaikin's corner cutting, one pass: the ring loses its corners, keeps its size. */
        private static List<double[]> smooth(List<double[]> loop) {
            final List<double[]> out = new ArrayList<>(loop.size() * 2);
            for (int i = 0; i < loop.size(); i++) {
                final double[] p = loop.get(i), r = loop.get((i + 1) % loop.size());
                out.add(new double[] { 0.75 * p[0] + 0.25 * r[0], 0.75 * p[1] + 0.25 * r[1] });
                out.add(new double[] { 0.25 * p[0] + 0.75 * r[0], 0.25 * p[1] + 0.75 * r[1] });
            }
            return out;
        }
    }

    /**
     * The outer edges of a set of cells as closed loops, in cell units with (0,0) the
     * grid's south-west corner. Every edge between an inside cell and an outside one
     * (or the grid's edge) is a segment; the segments are chained into loops.
     */
    static final class Contour {
        static List<List<double[]>> trace(boolean[] in, int nx, int ny) {
            // Directed edges around each inside cell, counterclockwise, keyed by start corner.
            final Map<Long, int[]> next = new HashMap<>();
            for (int y = 0; y < ny; y++) {
                for (int x = 0; x < nx; x++) {
                    if (!in[y * nx + x])
                        continue;
                    // south edge: (x,y) -> (x+1,y) when the cell below is outside
                    if (y == 0 || !in[(y - 1) * nx + x])
                        put(next, x, y, x + 1, y);
                    // east edge: (x+1,y) -> (x+1,y+1)
                    if (x == nx - 1 || !in[y * nx + x + 1])
                        put(next, x + 1, y, x + 1, y + 1);
                    // north edge: (x+1,y+1) -> (x,y+1)
                    if (y == ny - 1 || !in[(y + 1) * nx + x])
                        put(next, x + 1, y + 1, x, y + 1);
                    // west edge: (x,y+1) -> (x,y)
                    if (x == 0 || !in[y * nx + x - 1])
                        put(next, x, y + 1, x, y);
                }
            }
            final List<List<double[]>> loops = new ArrayList<>();
            while (!next.isEmpty()) {
                final Long startKey = next.keySet().iterator().next();
                final List<double[]> loop = new ArrayList<>();
                long k = startKey;
                int guard = 0;
                while (guard++ < 4_000_000) {
                    final int[] e = next.remove(k);
                    if (e == null)
                        break;
                    loop.add(new double[] { e[0], e[1] });
                    k = key(e[2], e[3]);
                    if (k == startKey)
                        break;
                }
                if (loop.size() >= 4)
                    loops.add(simplify(loop));
            }
            // Largest loops first; holes (clockwise) are dropped for now.
            final List<List<double[]>> outer = new ArrayList<>();
            for (List<double[]> l : loops)
                if (area(l) > 0)
                    outer.add(l);
            return outer;
        }

        private static void put(Map<Long, int[]> next, int x0, int y0, int x1, int y1) {
            // A corner can start two edges (a diagonal touch); chain on the first, the
            // second is picked up as its own loop later.
            final long k = key(x0, y0);
            if (!next.containsKey(k))
                next.put(k, new int[] { x0, y0, x1, y1 });
            else
                next.put(k ^ (1L << 62), new int[] { x0, y0, x1, y1 });
        }

        private static long key(int x, int y) {
            return ((long) x << 32) | (y & 0xFFFFFFFFL);
        }

        /** Drops the middle of straight runs, which are most of a cell-edge outline. */
        private static List<double[]> simplify(List<double[]> loop) {
            final List<double[]> out = new ArrayList<>();
            for (int i = 0; i < loop.size(); i++) {
                final double[] a = loop.get((i + loop.size() - 1) % loop.size()), b = loop.get(i), c = loop.get((i + 1) % loop.size());
                final boolean straight = (a[0] == b[0] && b[0] == c[0]) || (a[1] == b[1] && b[1] == c[1]);
                if (!straight)
                    out.add(b);
            }
            return out.isEmpty() ? loop : out;
        }

        private static double area(List<double[]> loop) {
            double a = 0;
            for (int i = 0; i < loop.size(); i++) {
                final double[] p = loop.get(i), q = loop.get((i + 1) % loop.size());
                a += p[0] * q[1] - q[0] * p[1];
            }
            return a / 2d;
        }
    }
}
