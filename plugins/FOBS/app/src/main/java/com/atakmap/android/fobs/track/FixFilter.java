package com.atakmap.android.fobs.track;

import java.util.ArrayList;
import java.util.List;

/**
 * GPS cleanup, in two stages, with no ATAK dependency so it can be unit tested.
 *
 * <p>Both stages are conservative on purpose. The walk is the operator's evidence, so
 * the filter removes only what cannot be real and counts what it removed; the tool
 * reports that count rather than hiding it.
 *
 * <ul>
 *   <li>{@link LiveGate}: per fix, before it is appended. Rejects a fix whose reported
 *       horizontal error is too large, whose implied speed from the last accepted fix is
 *       impossible for the mode, that has not moved far enough to be more than jitter,
 *       or whose timestamp is not newer than the last one.</li>
 *   <li>{@link #cleanup}: at End, over the accepted points. Removes out-and-back spikes
 *       (the "lost GPS for a section" case: the track leaves and comes straight back)
 *       and then thins the line with Douglas-Peucker so an hour's walk is a few hundred
 *       vertices rather than several thousand.</li>
 * </ul>
 */
public final class FixFilter {

    private FixFilter() {
    }

    /** A position fix reduced to what the filter needs. */
    public static final class Fix {
        public final double lat;
        public final double lon;
        /** Horizontal error in meters, or NaN when the receiver did not say. */
        public final double ce;
        /** Fix time in epoch milliseconds, or 0 when unknown. */
        public final long time;

        public Fix(double lat, double lon, double ce, long time) {
            this.lat = lat;
            this.lon = lon;
            this.ce = ce;
            this.time = time;
        }
    }

    /** Why the live gate rejected a fix. {@code ACCEPTED} means it did not. */
    public enum Verdict {
        ACCEPTED, BAD_ACCURACY, TOO_FAST, TOO_CLOSE, STALE_TIME
    }

    /** Thresholds. Defaults are for walking; a driven survey raises {@code maxSpeed}. */
    public static final class Thresholds {
        /** Reject a fix with a reported horizontal error above this, meters. */
        public double maxCe = 25.0;
        /** Reject a fix implying travel faster than this since the last accepted, m/s. */
        public double maxSpeed = 8.0;
        /** Reject a fix closer than this to the last accepted one, meters (jitter). */
        public double minSpacing = 2.0;
        /** Drop an interior vertex this far out on an out-and-back, meters. */
        public double spikeMin = 10.0;
        /**
         * Thin only a track longer than this many points, and only as much as needed to
         * get under it. Below the cap every accepted fix is kept as walked: on the first
         * field test (2026-09-05) a fixed 1.5 m Douglas-Peucker turned gentle curves
         * into right angles and the operator saw it at once against the breadcrumbs.
         */
        public int maxPoints = 2000;
        /** First Douglas-Peucker tolerance tried when over the cap, meters. */
        public double simplifyStart = 0.5;
    }

    /**
     * The per-fix gate. Not thread safe; the tool feeds it from one thread.
     */
    public static final class LiveGate {
        private final Thresholds t;
        private Fix last;
        private int dropped;
        private final int[] byVerdict = new int[Verdict.values().length];

        public LiveGate(Thresholds t) {
            this.t = t;
        }

        /**
         * Forget the last accepted fix, so the next one is judged on accuracy alone.
         * Called on Resume: the operator may have walked a long way while paused, and
         * that jump is not a bad fix.
         */
        public void resetReference() {
            last = null;
        }

        public int dropped() {
            return dropped;
        }

        /** How many fixes got each verdict, indexed by {@link Verdict#ordinal()}. */
        public int count(Verdict v) {
            return byVerdict[v.ordinal()];
        }

        public Verdict judge(Fix f) {
            Verdict v = judgeInner(f);
            byVerdict[v.ordinal()]++;
            return v;
        }

        private Verdict judgeInner(Fix f) {
            if (!Double.isNaN(f.ce) && f.ce > t.maxCe) {
                dropped++;
                return Verdict.BAD_ACCURACY;
            }
            if (last != null) {
                if (f.time != 0 && last.time != 0 && f.time <= last.time) {
                    dropped++;
                    return Verdict.STALE_TIME;
                }
                double d = distance(last, f);
                if (d < t.minSpacing) {
                    // Not counted as dropped: standing still is not a bad fix.
                    return Verdict.TOO_CLOSE;
                }
                if (f.time != 0 && last.time != 0) {
                    double dt = (f.time - last.time) / 1000.0;
                    if (dt > 0 && d / dt > t.maxSpeed) {
                        dropped++;
                        return Verdict.TOO_FAST;
                    }
                }
            }
            last = f;
            return Verdict.ACCEPTED;
        }
    }

    /**
     * End-of-track pass. Returns, for each input point, whether to keep it. The first
     * and last points are always kept.
     */
    public static boolean[] cleanup(List<Fix> pts, Thresholds t) {
        int n = pts.size();
        boolean[] keep = new boolean[n];
        for (int i = 0; i < n; i++)
            keep[i] = true;
        if (n < 3)
            return keep;
        removeSpikes(pts, keep, t.spikeMin);
        // Thin only past the cap, and no more than needed: double the tolerance until
        // the track fits. Under the cap the walk is kept exactly as accepted.
        double tol = t.simplifyStart;
        while (kept(keep) > t.maxPoints && tol < 1000) {
            boolean[] trial = keep.clone();
            simplify(pts, trial, tol);
            if (kept(trial) <= t.maxPoints || tol * 2 >= 1000) {
                keep = trial;
                break;
            }
            tol *= 2;
        }
        return keep;
    }

    static int kept(boolean[] keep) {
        return keep.length - removedCount(keep);
    }

    /** Count of {@code false} entries, for the "Removed N bad fixes" toast. */
    public static int removedCount(boolean[] keep) {
        int c = 0;
        for (boolean k : keep)
            if (!k)
                c++;
        return c;
    }

    /** Apply a keep mask to any parallel list. */
    public static <T> List<T> apply(List<T> items, boolean[] keep) {
        List<T> out = new ArrayList<>(items.size());
        for (int i = 0; i < items.size(); i++)
            if (keep[i])
                out.add(items.get(i));
        return out;
    }

    /**
     * An interior vertex is a spike when both legs into and out of it are long while
     * its neighbors are close to each other: the track went out and came straight
     * back. Repeats until nothing changes, so a two-fix excursion also goes.
     */
    static void removeSpikes(List<Fix> pts, boolean[] keep, double spikeMin) {
        boolean changed = true;
        while (changed) {
            changed = false;
            int prev = nextKept(keep, -1);
            int cur = nextKept(keep, prev);
            int next = nextKept(keep, cur);
            while (next >= 0) {
                double a = distance(pts.get(prev), pts.get(cur));
                double b = distance(pts.get(cur), pts.get(next));
                double c = distance(pts.get(prev), pts.get(next));
                if (a > spikeMin && b > spikeMin && a > 3 * c && b > 3 * c) {
                    keep[cur] = false;
                    changed = true;
                    cur = next;
                    next = nextKept(keep, cur);
                } else {
                    prev = cur;
                    cur = next;
                    next = nextKept(keep, cur);
                }
            }
        }
    }

    private static int nextKept(boolean[] keep, int from) {
        for (int i = from + 1; i < keep.length; i++)
            if (keep[i])
                return i;
        return -1;
    }

    /** Douglas-Peucker over the kept points, in a local flat-earth frame. */
    static void simplify(List<Fix> pts, boolean[] keep, double tol) {
        List<Integer> idx = new ArrayList<>();
        for (int i = 0; i < keep.length; i++)
            if (keep[i])
                idx.add(i);
        if (idx.size() < 3)
            return;
        boolean[] keepIdx = new boolean[idx.size()];
        keepIdx[0] = true;
        keepIdx[idx.size() - 1] = true;
        dp(pts, idx, 0, idx.size() - 1, tol, keepIdx);
        for (int k = 0; k < idx.size(); k++)
            if (!keepIdx[k])
                keep[idx.get(k)] = false;
    }

    private static void dp(List<Fix> pts, List<Integer> idx, int s, int e, double tol,
            boolean[] keepIdx) {
        if (e <= s + 1)
            return;
        Fix a = pts.get(idx.get(s));
        Fix b = pts.get(idx.get(e));
        double maxD = -1;
        int maxK = -1;
        for (int k = s + 1; k < e; k++) {
            double d = perpendicular(pts.get(idx.get(k)), a, b);
            if (d > maxD) {
                maxD = d;
                maxK = k;
            }
        }
        if (maxD > tol) {
            keepIdx[maxK] = true;
            dp(pts, idx, s, maxK, tol, keepIdx);
            dp(pts, idx, maxK, e, tol, keepIdx);
        }
    }

    /** Distance from p to segment ab, meters, equirectangular around a. */
    private static double perpendicular(Fix p, Fix a, Fix b) {
        double cos = Math.cos(Math.toRadians(a.lat));
        double ax = 0, ay = 0;
        double bx = Math.toRadians(b.lon - a.lon) * cos * EARTH_R;
        double by = Math.toRadians(b.lat - a.lat) * EARTH_R;
        double px = Math.toRadians(p.lon - a.lon) * cos * EARTH_R;
        double py = Math.toRadians(p.lat - a.lat) * EARTH_R;
        double dx = bx - ax, dy = by - ay;
        double len2 = dx * dx + dy * dy;
        if (len2 == 0)
            return Math.hypot(px - ax, py - ay);
        double u = ((px - ax) * dx + (py - ay) * dy) / len2;
        u = Math.max(0, Math.min(1, u));
        return Math.hypot(px - (ax + u * dx), py - (ay + u * dy));
    }

    private static final double EARTH_R = 6371008.8;

    /** Haversine distance, meters. */
    public static double distance(Fix a, Fix b) {
        double dLat = Math.toRadians(b.lat - a.lat);
        double dLon = Math.toRadians(b.lon - a.lon);
        double s = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(a.lat)) * Math.cos(Math.toRadians(b.lat))
                        * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return 2 * EARTH_R * Math.asin(Math.min(1, Math.sqrt(s)));
    }
}
