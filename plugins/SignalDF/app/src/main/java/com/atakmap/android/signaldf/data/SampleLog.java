package com.atakmap.android.signaldf.data;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Every bearing worth keeping, per emitter, while the operator drives.
 *
 * <p>This is the collection half of finding a transmitter. One bearing is a
 * line and locates nothing; the vendor's own quickstart puts it plainly --
 * "drive around to collect bearing data from multiple locations in order to
 * triangulate the source". So the plugin gathers, and {@link BearingFix}
 * crosses what it gathered.
 *
 * <p><b>Movement is the whole point, so sitting still must not count.</b> The
 * radio reports about once a second. Idling at a light for two minutes would
 * add a hundred and twenty bearings from one spot, and because they are all
 * from the same place they add no crossing angle at all -- they only outvote
 * the handful taken from everywhere else, dragging the fix toward wherever the
 * operator happened to stop. Hence {@link #MIN_MOVE_M}: a sample is kept only
 * when the receiver has moved that far from the last one kept. KrakenRF's own
 * app solves the same problem with a "Stationary Threshold" and a logging mode
 * that pauses when stopped.
 *
 * <p><b>One log per VFO.</b> Two frequencies are two emitters and never one.
 * Averaging them would produce a fix somewhere between two real transmitters,
 * which is a place nothing is.
 *
 * <p>Pure: latitudes, longitudes and degrees, no map and no Android, so the
 * gate and the ageing are testable on a laptop.
 */
public final class SampleLog {

    /**
     * How far the receiver must move before another bearing is kept, meters.
     *
     * <p>Fifty. Short enough that a slow crawl down a street still yields a
     * useful spread of positions, long enough that GPS noise alone -- a few
     * meters -- can never advance it while parked. Bearings from within fifty
     * meters of each other are very nearly the same measurement anyway: at a
     * kilometer's range they differ by three degrees, which is inside the
     * array's own error.
     */
    public static final double MIN_MOVE_M = 50.0;

    /**
     * How long a bearing stays in the log, milliseconds.
     *
     * <p>Half an hour. Old bearings are not merely stale, they are the good
     * part -- they are the ones taken from somewhere else, and the crossing
     * angle is what produces a fix at all. What goes wrong with old ones is a
     * transmitter that moves, and half an hour is long enough to drive a
     * useful arc and short enough that a mobile emitter does not smear the
     * answer across everywhere it has been.
     */
    public static final long MAX_AGE_MS = 30L * 60_000L;

    /** A bearing as it was taken: where from, which way, how well. */
    public static final class Sample {
        public final double lat;
        public final double lon;
        public final double degTrue;
        public final double weight;
        public final double powerDb;
        public final long timeMs;

        public Sample(double lat, double lon, double degTrue, double weight,
                double powerDb, long timeMs) {
            this.lat = lat;
            this.lon = lon;
            this.degTrue = degTrue;
            this.weight = weight;
            this.powerDb = powerDb;
            this.timeMs = timeMs;
        }
    }

    private final List<Sample> samples = new ArrayList<>();
    private double lastLat = Double.NaN;
    private double lastLon = Double.NaN;

    /**
     * Offer a bearing. Kept only if the receiver has moved far enough since
     * the last one kept.
     *
     * @return true if it was kept
     */
    public boolean offer(double lat, double lon, double degTrue, double weight,
            double powerDb, long timeMs) {
        if (Double.isNaN(lat) || Double.isNaN(lon) || Double.isNaN(degTrue))
            return false;
        if (!Double.isNaN(lastLat)
                && LocalFrame.distanceM(lastLat, lastLon, lat, lon) < MIN_MOVE_M)
            return false;
        samples.add(new Sample(lat, lon, degTrue, weight, powerDb, timeMs));
        lastLat = lat;
        lastLon = lon;
        return true;
    }

    /** Drop everything older than {@link #MAX_AGE_MS} relative to {@code now}. */
    public void age(long now) {
        while (!samples.isEmpty() && now - samples.get(0).timeMs > MAX_AGE_MS)
            samples.remove(0);
        if (samples.isEmpty()) {
            lastLat = Double.NaN;
            lastLon = Double.NaN;
        }
    }

    public void clear() {
        samples.clear();
        lastLat = Double.NaN;
        lastLon = Double.NaN;
    }

    public int size() {
        return samples.size();
    }

    public List<Sample> samples() {
        return Collections.unmodifiableList(samples);
    }

    /**
     * Cross everything in the log, or null when it cannot be done yet.
     *
     * <p>The frame is hung on the centroid of the receiver positions, which
     * keeps every point close to the origin and the flat-earth approximation
     * at its best. The answer comes back as a latitude and longitude with the
     * ellipse still in meters, which is what a map needs.
     */
    public Result solve() {
        int n = samples.size();
        if (n < BearingFix.MIN_BEARINGS)
            return null;

        double sumLat = 0, sumLon = 0;
        for (Sample s : samples) {
            sumLat += s.lat;
            sumLon += s.lon;
        }
        LocalFrame frame = new LocalFrame(sumLat / n, sumLon / n);

        double[] x = new double[n], y = new double[n];
        double[] deg = new double[n], w = new double[n];
        for (int i = 0; i < n; i++) {
            Sample s = samples.get(i);
            x[i] = frame.east(s.lon);
            y[i] = frame.north(s.lat);
            deg[i] = s.degTrue;
            w[i] = s.weight;
        }

        BearingFix.Fix fix = BearingFix.solve(x, y, deg, w, n);
        if (fix == null)
            return null;
        return new Result(fix, frame.lat(fix.y), frame.lon(fix.x));
    }

    /** A fix, placed on the earth. */
    public static final class Result {
        public final BearingFix.Fix fix;
        public final double lat;
        public final double lon;

        Result(BearingFix.Fix fix, double lat, double lon) {
            this.fix = fix;
            this.lat = lat;
            this.lon = lon;
        }
    }

    /**
     * The mean direction of the last few bearings, degrees true, or NaN when
     * there are none.
     *
     * <p>A circular mean, not an arithmetic one: averaging 359 and 001 the
     * naive way gives 180, which points at the opposite side of the world.
     *
     * <p>This is the stable half of a direction-finding picture. Early in a
     * search the estimated <em>range</em> slides by kilometers between
     * updates, because every bearing so far runs almost parallel; the
     * <em>direction</em> those bearings point is pinned down from the first
     * few samples and barely moves. Anything that has to stay put while an
     * operator drives toward it should be built on this and not on the fix.
     */
    public double meanBearingDeg(int lastN) {
        int n = samples.size();
        if (n == 0)
            return Double.NaN;
        int from = Math.max(0, n - Math.max(1, lastN));
        double x = 0, y = 0;
        for (int i = from; i < n; i++) {
            double r = Math.toRadians(samples.get(i).degTrue);
            x += Math.sin(r);
            y += Math.cos(r);
        }
        if (x == 0 && y == 0)
            return Double.NaN;
        return Angles.norm360(Math.toDegrees(Math.atan2(x, y)));
    }

    /**
     * Which way the receiver is travelling, from the last two samples kept,
     * or NaN when there are fewer than two.
     *
     * <p>Used only to choose between two equally good perpendiculars: the one
     * nearer the current heading does not ask for a U-turn.
     */
    public double travelBearingDeg() {
        int n = samples.size();
        if (n < 2)
            return Double.NaN;
        Sample a = samples.get(n - 2), b = samples.get(n - 1);
        LocalFrame f = new LocalFrame(a.lat, a.lon);
        double east = f.east(b.lon), north = f.north(b.lat);
        if (east == 0 && north == 0)
            return Double.NaN;
        return Angles.norm360(Math.toDegrees(Math.atan2(east, north)));
    }

}
