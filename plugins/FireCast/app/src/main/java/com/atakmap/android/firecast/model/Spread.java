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
 * rate, heading and ellipse shape from the engine, for its fuel, slope, aspect and the
 * canopy over it (surface fire, torching or crown fire), under the wind and the fuel
 * moisture of the hour the fire reaches it. From the start cell, the earliest arrival at every cell
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
        /** Surface, torching or crowning: {@link Behave#SURFACE} and the rest. */
        final byte type;

        Cell(double rate, double headingDeg, double ecc, double flameFt, double spotM, int type) {
            this.rate = rate;
            this.headingDeg = headingDeg;
            this.ecc = ecc;
            this.flameFt = flameFt;
            this.spotM = spotM;
            this.type = (byte) type;
        }
    }

    /**
     * What a cell is, for the engine: its fuel, slope and aspect, and the canopy over it,
     * binned so that like cells share one answer from the library.
     */
    public static final class Ground {
        public final int fuel;
        public final double slopeDeg, aspectDeg;
        /** Canopy cover percent; 0 for open ground, NaN when the canopy grids could not be had. */
        public final double coverPct;
        /** Canopy height and base height, feet; bulk density, kg/m3. */
        public final double heightFt, baseFt, density;

        Ground(int fuel, double slopeDeg, double aspectDeg, double coverPct, double heightFt, double baseFt, double density) {
            this.fuel = fuel;
            this.slopeDeg = slopeDeg;
            this.aspectDeg = aspectDeg;
            this.coverPct = coverPct;
            this.heightFt = heightFt;
            this.baseFt = baseFt;
            this.density = density;
        }

        /** Whether there are trees enough over it for the crown fire question to be asked. */
        public boolean canopy() {
            return coverPct > 0 && heightFt > 0 && density > 0;
        }
    }

    private static final Cell BARRIER = new Cell(0, 0, 0, 0, 0, 0);
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
        /**
         * True when the front ran out of ground it could enter before the run's end: walls
         * or fuel that will not carry all round. False when the run reached its end still
         * moving, however slowly; a litter fire under trees in a light wind can cover two
         * cells in an hour and is a real answer, not a refusal.
         */
        public boolean walled;
        /** How each cell burned, {@link Behave#SURFACE} to {@link Behave#CROWNING}; null for an engine without canopy. */
        public byte[] fireType;

        /** {crowning, torching} acres by a time: crown fire through the canopy, and trees torching under a surface fire. */
        public double[] crownAcres(double minutes) {
            if (fireType == null)
                return new double[] { 0, 0 };
            int c = 0, t = 0;
            for (int i = 0; i < arrival.length; i++) {
                if (arrival[i] > minutes)
                    continue;
                if (fireType[i] == Behave.CROWNING)
                    c++;
                else if (fireType[i] == Behave.TORCHING)
                    t++;
            }
            return new double[] { c * cell * cell / 4046.856d, t * cell * cell / 4046.856d };
        }

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

    /** What a cell's behavior is asked with: the hour's moistures, and this wind. */
    public interface Behavior {
        /**
         * @param windMph  the wind for this cell at this moment, or NaN for the hour's own
         * @param windFrom degrees the wind comes from, with it
         * @param hour     which hour of the run, 0 the first: its moistures and, with NaN wind, its wind
         * @return [head rate ft/min, heading deg, A ft/min, B ft/min, C ft/min, flame ft, spot distance m, fire type],
         *         or null for no spread; the fire type may be left off for a surface fire
         */
        double[] run(Ground g, double windMph, double windFrom, int hour);
    }

    /** A wind that varies over the ground and the hour; null means one wind for the run. */
    public interface Wind {
        /** {mph, from-degrees} at a point and a moment, or null for no reading there. */
        double[] at(double lat, double lon, long whenMillis);
    }

    private Spread() {
    }

    /**
     * @param halfKm  the grid's reach each way from the start
     * @param maxMin  arrivals past this are not followed
     */
    public static Result run(Landscape land, double lat, double lon, double halfKm, double maxMin, Behavior behavior) {
        return run(land, lat, lon, halfKm, maxMin, behavior, null, 1d);
    }

    /**
     * @param seedRing  a perimeter, [lat, lon] pairs: every cell inside it burns at time 0,
     *                  in place of the single start cell; null for a point start
     * @param rateScale a multiplier on every cell's rate, from calibration; 1 for none
     */
    public static Result run(Landscape land, double lat, double lon, double halfKm, double maxMin, Behavior behavior,
            double[][] seedRing, double rateScale) {
        return run(land, lat, lon, halfKm, maxMin, behavior, seedRing, rateScale, null, 0L);
    }

    /**
     * @param wind     a wind field sampled per cell and time, or null for the run's one wind
     * @param startMs  when the run begins, for sampling the field through the hour
     */
    public static Result run(Landscape land, double lat, double lon, double halfKm, double maxMin, Behavior behavior,
            double[][] seedRing, double rateScale, Wind wind, long startMs) {
        return run(land, lat, lon, halfKm, maxMin, behavior, seedRing, rateScale, wind, startMs, 15d);
    }

    /** @param cellM the cell, meters: 15 follows the ground finely for an hour; 30 keeps three hours over 20 km affordable */
    public static Result run(Landscape land, double lat, double lon, double halfKm, double maxMin, Behavior behavior,
            double[][] seedRing, double rateScale, Wind wind, long startMs, double cellM) {
        return run(land, lat, lon, halfKm, maxMin, behavior, seedRing, rateScale, wind, startMs, cellM, 1);
    }

    /**
     * @param hours how many hours of weather the behavior has: a cell the fire reaches in
     *              hour h burns with that hour's moistures and wind (the last one holds past
     *              the end). 1 is one set of conditions for the whole run.
     */
    public static Result run(Landscape land, double lat, double lon, double halfKm, double maxMin, Behavior behavior,
            double[][] seedRing, double rateScale, Wind wind, long startMs, double cellM, final int hours) {
        return run(land, lat, lon, halfKm, maxMin, behavior, seedRing == null ? null : new double[][][] { seedRing },
                rateScale, wind, startMs, cellM, hours);
    }

    /**
     * @param seedRings every part of a perimeter, [lat, lon] pairs each: a fire NASA maps
     *                  as two or more pieces burns in all of them at time 0
     */
    public static Result run(Landscape land, double lat, double lon, double halfKm, double maxMin, Behavior behavior,
            double[][][] seedRings, double rateScale, Wind wind, long startMs, double cellM, final int hours) {
        // 15 m is half the grid's own 30 m: the fuel is sampled nearest, so the run
        // follows the same ground, but the front moves in finer steps and the rings
        // come out smooth. Three hours over 20 km runs at 30 m.
        final double cell = cellM;
        final int half = (int) Math.round(halfKm * 1000d / cell);
        final int nx = 2 * half + 1, ny = nx;
        final double mPerDegLat = 111320d, mPerDegLon = 111320d * Math.max(0.02d, Math.cos(Math.toRadians(lat)));
        final double dLat = cell / mPerDegLat, dLon = cell / mPerDegLon;
        final double lat0 = lat - half * dLat - dLat / 2d, lon0 = lon - half * dLon - dLon / 2d;

        // Each cell's behavior, computed once per fuel, slope step and aspect step, per
        // hour, and with a wind field per wind step as well, looked up as the fire reaches
        // it. Fuel that will not carry fire in the first hour is STILL, not a wall: a drier
        // hour later can burn it, and embers do not fly over it as if it were a road.
        final Cell[] cells = new Cell[nx * ny];
        final int[] kindOf = new int[nx * ny];
        final List<Ground> kinds = new ArrayList<>();
        final Map<Long, Integer> kindIndex = new HashMap<>();
        final List<Cell> kindCell = new ArrayList<>();
        final Map<Long, Cell> byKindAndWind = new HashMap<>();
        final java.util.Set<Integer> fuels = new java.util.HashSet<>();
        final Cell[][] byHour = new Cell[Math.max(1, hours)][];
        final Map<Long, Cell> byKindHour = new HashMap<>();
        final float[] flame = new float[nx * ny];
        final byte[] fireType = new byte[nx * ny];
        final boolean canopyKnown = land.cc != null && land.ch != null && land.cbh != null && land.cbd != null;
        int burnable = 0;
        for (int y = 0; y < ny; y++) {
            for (int x = 0; x < nx; x++) {
                final double cLat = lat0 + (y + 0.5) * dLat, cLon = lon0 + (x + 0.5) * dLon;
                final int ownFuel = land.fuel.at(cLat, cLon);
                final int fuel = effectiveFuel(ownFuel, land.evt.at(cLat, cLon), land.fuel.nodata, land.evt.nodata);
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
                // The canopy in bins: cover 10%, height 3 m, base 0.5 m, density 0.02 kg/m3
                // (LANDFIRE carries height and base in decimeters, density in hundredths).
                // A house, a road or a park inside the urban class is open ground here.
                int ccB = 0, chB = 0, cbhB = 0, cbdB = 0;
                if (canopyKnown && fuel == ownFuel) {
                    final int cc = land.cc.at(cLat, cLon), ch = land.ch.at(cLat, cLon),
                            cbh = land.cbh.at(cLat, cLon), cbd = land.cbd.at(cLat, cLon);
                    if (cc > 0 && cc <= 100 && ch > 0 && ch != land.ch.nodata && cbd > 0 && cbd != land.cbd.nodata
                            && cbh >= 0 && cbh != land.cbh.nodata) {
                        // Held to LANDFIRE's own range: trees to 60 m, crowns from 30 m, 0.5 kg/m3.
                        ccB = Math.min(10, Math.round(cc / 10f));
                        chB = Math.min(20, Math.round(ch / 30f));
                        cbhB = Math.min(60, Math.round(cbh / 5f));
                        cbdB = Math.min(25, Math.round(cbd / 2f));
                        if (ccB == 0 || chB == 0 || cbdB == 0)
                            ccB = chB = cbhB = cbdB = 0;
                    }
                }
                final long key = ((long) fuel << 40) | ((long) slopeStep << 32) | ((long) (aspectStep / 22) << 26)
                        | ((long) ccB << 22) | ((long) chB << 16) | ((long) cbhB << 8) | cbdB;
                Integer k = kindIndex.get(key);
                if (k == null) {
                    k = kinds.size();
                    kindIndex.put(key, k);
                    final double mToFt = 3.28084d;
                    kinds.add(new Ground(fuel, slopeStep, aspectStep, canopyKnown ? ccB * 10d : Double.NaN,
                            chB * 3d * mToFt, cbhB * 0.5d * mToFt, cbdB * 0.02d));
                    kindCell.add(null);
                }
                // Worked out when the fire first comes near it: a forested box holds tens of
                // thousands of kinds, most of them where the fire never goes.
                kindOf[y * nx + x] = k;
                cells[y * nx + x] = null;
            }
        }

        // Dijkstra from the start cell. A move's time is its length over the mean of
        // the rate leaving the source in that direction and the rate the destination
        // would have in it, so a slow cell is slow to enter and slow to leave.
        final float[] arrival = new float[nx * ny];
        java.util.Arrays.fill(arrival, Float.POSITIVE_INFINITY);
        final int start = half * nx + half;
        double offsetM = 0, offsetDeg = 0;
        if (seedRings != null && seedRings.length > 0) {
            // Everything inside the perimeter is burning now. Walls inside it are not
            // (a house already lost is not a source); the edge cells carry the fire out.
            // Each part is tested only over the cells inside its own box.
            int lit = 0;
            for (double[][] ring : seedRings) {
                if (ring == null || ring.length < 3)
                    continue;
                double s = 90, w = 180, n = -90, e = -180;
                for (double[] p : ring) {
                    s = Math.min(s, p[0]);
                    n = Math.max(n, p[0]);
                    w = Math.min(w, p[1]);
                    e = Math.max(e, p[1]);
                }
                final int y0 = Math.max(0, (int) Math.floor((s - lat0) / dLat)), y1 = Math.min(ny - 1, (int) Math.ceil((n - lat0) / dLat));
                final int x0 = Math.max(0, (int) Math.floor((w - lon0) / dLon)), x1 = Math.min(nx - 1, (int) Math.ceil((e - lon0) / dLon));
                for (int y = y0; y <= y1; y++)
                    for (int x = x0; x <= x1; x++) {
                        if (cells[y * nx + x] == BARRIER || arrival[y * nx + x] == 0)
                            continue;
                        if (inside(ring, lat0 + (y + 0.5) * dLat, lon0 + (x + 0.5) * dLon)) {
                            arrival[y * nx + x] = 0;
                            lit++;
                        }
                    }
            }
            if (lit == 0 && cells[start] != BARRIER)
                arrival[start] = 0;
        } else {
            // Light the start when it carries fire in the first hour; otherwise the nearest
            // cell that does, instead of refusing: the tap was on a road or a roof, or on
            // fuel too sparse or wet to carry. Up to 8 cells (120 m) away, and the result
            // says how far. A cell that is not a wall but will not carry is no start: the
            // fire would sit on it.
            int best = -1;
            double bestD = Double.MAX_VALUE;
            if (cells[start] != BARRIER
                    && at(start, 0, cells, kindOf, kinds, kindCell, byHour, byKindHour, behavior, rateScale, hours).rate > 0) {
                best = start;
                bestD = 0;
            } else {
                for (int i = 0; i < cells.length; i++) {
                    if (cells[i] == BARRIER)
                        continue;
                    final double dx = i % nx - half, dy = i / nx - half, d = dx * dx + dy * dy;
                    if (d >= bestD || d > 64)
                        continue;
                    if (at(i, 0, cells, kindOf, kinds, kindCell, byHour, byKindHour, behavior, rateScale, hours).rate <= 0)
                        continue;
                    bestD = d;
                    best = i;
                }
            }
            if (best < 0)
                return new Result(nx, ny, cell, lat0, lon0, dLat, dLon, arrival, new double[0], 0, burnable, 0);
            arrival[best] = 0;
            final double dx = (best % nx - half) * cell, dy = (best / nx - half) * cell;
            offsetM = Math.sqrt(dx * dx + dy * dy);
            offsetDeg = offsetM == 0 ? 0 : (Math.toDegrees(Math.atan2(dx, dy)) + 360d) % 360d;
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
        boolean ranOut = true;
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
            if (t > maxMin) {
                ranOut = false;
                break;
            }
            final int x = i % nx, y = i / nx;
            // The hour the fire is in when it leaves this cell; the last one holds past the end.
            final int hour = Math.min(hours - 1, (int) (t / 60d));
            Cell from = at(i, hour, cells, kindOf, kinds, kindCell, byHour, byKindHour, behavior, rateScale, hours);
            if (wind != null && from != BARRIER) {
                // This cell's behavior under the wind blowing here when the fire arrives.
                final double[] w = wind.at(lat0 + (y + 0.5) * dLat, lon0 + (x + 0.5) * dLon, startMs + (long) (t * 60000d));
                if (w != null) {
                    // 150 mph at most, as the hourly winds are: the crown ember model never
                    // finishes past winds like that.
                    final int ws = Math.max(0, Math.min(75, (int) Math.round(w[0] / 2d))), wd = (int) Math.round(w[1] / 22.5d) % 16;
                    final long wkey = ((long) kindOf[i] << 20) | ((long) hour << 16) | (ws << 4) | wd;
                    Cell cw = byKindAndWind.get(wkey);
                    if (cw == null) {
                        cw = cellOf(behavior.run(kinds.get(kindOf[i]), ws * 2d, wd * 22.5d, hour), rateScale);
                        byKindAndWind.put(wkey, cw);
                    }
                    if (cw.rate > 0)
                        from = cw;
                }
            }
            flame[i] = (float) from.flameFt;
            fireType[i] = from.type;
            if (from.rate > 0)
                fuels.add(kinds.get(kindOf[i]).fuel);
            if (from.spotM > maxSpot)
                maxSpot = from.spotM;
            for (int m = 0; m < MOVES.length; m++) {
                final int nx2 = x + MOVES[m][0], ny2 = y + MOVES[m][1];
                if (nx2 < 0 || ny2 < 0 || nx2 >= nx || ny2 >= ny)
                    continue;
                final int j = ny2 * nx + nx2;
                if (done[j])
                    continue;
                final Cell to = at(j, hour, cells, kindOf, kinds, kindCell, byHour, byKindHour, behavior, rateScale, hours);
                if (to == BARRIER) {
                    // Spotting: within 45 degrees of the head, embers carry across a wall
                    // (a freeway, a commercial block) narrower than the spot distance and
                    // light the first fuel beyond it that burns this hour. One ember
                    // flight, at the head's pace.
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
                            if (at(k, hour, cells, kindOf, kinds, kindCell, byHour, byKindHour, behavior, rateScale, hours).rate > 0)
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
                // Fuel that does not burn this hour is not entered; a later hour may reach it.
                if (rTo <= 0)
                    continue;
                final double rate = rFrom > 0 ? 2d / (1d / rFrom + 1d / rTo) : rTo;
                final double nt = t + moveLen[m] / rate;
                if (nt < arrival[j]) {
                    arrival[j] = (float) nt;
                    q.add(new long[] { Double.doubleToLongBits(nt), j });
                }
            }
        }
        final Result res = new Result(nx, ny, cell, lat0, lon0, dLat, dLon, arrival, flameByStep(flame, arrival), seen, burnable, fuels.size());
        res.startOffsetM = offsetM;
        res.startOffsetDeg = offsetDeg;
        res.walled = ranOut;
        res.maxSpotM = maxSpot;
        res.spotCrossings = spotCrossings;
        res.fireType = fireType;
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

    /** Point in polygon, ring as [lat, lon] pairs, even-odd. */
    static boolean inside(double[][] ring, double lat, double lon) {
        boolean in = false;
        for (int i = 0, j = ring.length - 1; i < ring.length; j = i++) {
            final double yi = ring[i][0], xi = ring[i][1], yj = ring[j][0], xj = ring[j][1];
            if ((yi > lat) != (yj > lat) && lon < (xj - xi) * (lat - yi) / (yj - yi) + xi)
                in = !in;
        }
        return in;
    }

    /** A ring's area in acres, degrees in, by the shoelace on a local flat ground. */
    static double ringAcres(double[][] ring) {
        if (ring == null || ring.length < 3)
            return 0;
        final double lat = ring[0][0];
        final double mLat = 111320d, mLon = 111320d * Math.max(0.02d, Math.cos(Math.toRadians(lat)));
        double a = 0;
        for (int i = 0, j = ring.length - 1; i < ring.length; j = i++)
            a += (ring[j][1] - ring[0][1]) * mLon * (ring[i][0] - ring[0][0]) * mLat
                    - (ring[i][1] - ring[0][1]) * mLon * (ring[j][0] - ring[0][0]) * mLat;
        return Math.abs(a) / 2d / 4046.856d;
    }

    /** The ellipse's rate in a direction: the head rate at the heading, backing behind. */
    private static double rateToward(Cell c, double bearingDeg) {
        if (c == BARRIER || c.rate <= 0)
            return 0;
        final double phi = Math.toRadians(bearingDeg - c.headingDeg);
        return c.rate * (1d - c.ecc) / (1d - c.ecc * Math.cos(phi));
    }

    private static double[] flameByStep(float[] flame, float[] arrival) {
        final int[] steps = { 20, 40, 60, 90, 120, 150, 180 };
        final double[] out = new double[steps.length];
        for (int i = 0; i < flame.length; i++) {
            if (arrival[i] == Float.POSITIVE_INFINITY)
                continue;
            for (int s = 0; s < steps.length; s++)
                if (arrival[i] <= steps[s] && flame[i] > out[s])
                    out[s] = flame[i];
        }
        return out;
    }

    /** Fuel that will not carry fire under these conditions: entered by nothing, a wall to nothing. */
    private static final Cell STILL = new Cell(0, 0, 0, 0, 0, 0);

    /** A cell from the engine's answer: the rate in meters a minute scaled, the ellipse, the flame, the embers. */
    private static Cell cellOf(double[] r, double rateScale) {
        if (r == null || r[0] <= 0.01)
            return STILL;
        final double b = r[3], cc = r[4];
        final double ecc = b > 0 ? Math.max(0, Math.min(0.98, cc / b)) : 0;
        return new Cell(r[0] * 0.3048d * rateScale, r[1], ecc, r[5], r.length > 6 ? r[6] : 0,
                r.length > 7 ? (int) Math.round(r[7]) : 0);
    }

    /** Cell {@code i} in hour {@code h}: the first hour's from the grid, a later one asked once per kind and hour. */
    private static Cell at(int i, int h, Cell[] cells, int[] kindOf, List<Ground> kinds, List<Cell> kindCell,
            Cell[][] byHour, Map<Long, Cell> byKindHour, Behavior behavior, double rateScale, int hours) {
        Cell c0 = cells[i];
        if (c0 == null) {
            c0 = kindCell.get(kindOf[i]);
            if (c0 == null) {
                c0 = cellOf(behavior.run(kinds.get(kindOf[i]), Double.NaN, Double.NaN, 0), rateScale);
                kindCell.set(kindOf[i], c0);
            }
            cells[i] = c0;
        }
        if (h <= 0 || c0 == BARRIER || hours <= 1)
            return c0;
        Cell[] a = byHour[h];
        if (a == null)
            a = byHour[h] = new Cell[cells.length];
        Cell c = a[i];
        if (c == null) {
            final long key = ((long) kindOf[i] << 4) | h;
            c = byKindHour.get(key);
            if (c == null) {
                c = cellOf(behavior.run(kinds.get(kindOf[i]), Double.NaN, Double.NaN, h), rateScale);
                byKindHour.put(key, c);
            }
            a[i] = c;
        }
        return c;
    }

    /**
     * Marching squares over the arrival field: the line where arrival equals a time,
     * crossing each cell edge at the interpolated point, chained into loops and
     * rounded off. Coordinates are cell indices of cell centers.
     */
    public static final class Isoline {
        public static List<List<double[]>> trace(float[] arrival, int nx, int ny, float t) {
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
