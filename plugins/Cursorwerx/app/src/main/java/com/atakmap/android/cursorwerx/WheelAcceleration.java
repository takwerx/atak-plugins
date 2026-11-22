
package com.atakmap.android.cursorwerx;

/**
 * Android's wheel acceleration, run backwards: from the value a wheel {@code MotionEvent}
 * carries, the travel the device actually reported.
 *
 * <p>Android multiplies every wheel event by up to four when events come fast
 * ({@code InputReaderConfiguration.wheelVelocityControlParameters(1, 15, 50, 4)}): the
 * speed is a least-squares fit over the last 100 ms of the summed raw travel, in units a
 * second; at 15 or below an event counts once, at 50 or above four times, linearly in
 * between, and a pause of 40 ms starts the speed again from nothing. A Mac trackpad
 * reaches the emulator's tablet as 60 events a second of 1, so every swipe ran at four
 * times. 0.3 measured its pace with events injected more slowly than that, under the
 * 40 ms pause, and on the operator's MacBook one 37-event swipe still ran ATAK's Tools list
 * 21 rows, past every plugin in its middle (2026-10-15). A quickly spun mouse wheel is
 * multiplied the same way. The Mac has already accelerated the swipe once before the
 * emulator sees it, so taking Android's share back out leaves the Mac's own feel.
 *
 * <p>This replays Android's bookkeeping ({@code SimpleVelocityControl} and the LSQ2
 * {@code VelocityTracker}, frameworks/native android15-release) and, since the tablet
 * reports whole numbers, picks the whole raw value that Android would have turned into
 * the value received. To stay in step it has to see every wheel event the window gets,
 * over the map as well as over UI; events that go to another window (a dialog, ATAK's
 * Settings) are missed, and the next 40 ms pause brings the two back together.
 */
final class WheelAcceleration {

    // Android's own numbers, frameworks/native, android15-release.
    private static final float LOW_SPEED = 15f; // units a second; no acceleration at or below
    private static final float HIGH_SPEED = 50f; // full acceleration at or above
    private static final float ACCELERATION = 4f;
    private static final long STOP_NS = 500_000_000L; // VelocityControl::STOP_TIME
    private static final long STOPPED_NS = 40_000_000L; // ASSUME_POINTER_STOPPED_TIME
    private static final long HORIZON_NS = 100_000_000L; // LSQ2 keeps 100 ms
    private static final int HISTORY = 20; // VelocityTracker::HISTORY_SIZE

    /** The speed tracker's samples, oldest first: event time and summed raw travel. */
    private final long[] times = new long[HISTORY];
    private final float[] positions = new float[HISTORY];
    private int count;
    private float position;
    private long lastMove = Long.MIN_VALUE;

    private long lastEvent = Long.MIN_VALUE;
    private float lastRaw;

    /**
     * The raw travel behind one wheel event. Asked twice for the same event, as the router
     * may be (once over UI, once from the map), it answers the same and counts it once.
     */
    float raw(long eventTimeNanos, float accelerated) {
        if (eventTimeNanos == lastEvent)
            return lastRaw;
        lastEvent = eventTimeNanos;
        if (accelerated == 0f)
            return lastRaw = 0f; // Android leaves a still axis alone
        if (lastMove == Long.MIN_VALUE || eventTimeNanos - lastMove >= STOP_NS) {
            position = 0f;
            count = 0;
        } else if (eventTimeNanos - lastMove > STOPPED_NS) {
            count = 0;
        }
        lastMove = eventTimeNanos;
        dropOld(eventTimeNanos);

        // accelerated = raw * factor, factor between 1 and 4, raw a whole number.
        final float target = Math.abs(accelerated);
        final float sign = Math.signum(accelerated);
        final int lo = Math.max(1, (int) Math.floor(target / ACCELERATION));
        final int hi = Math.max(lo, (int) Math.ceil(target));
        int best = lo;
        float bestError = Float.MAX_VALUE;
        for (int r = lo; r <= hi; r++) {
            final float error = Math.abs(r * factor(eventTimeNanos, position + sign * r) - target);
            if (error < bestError) {
                bestError = error;
                best = r;
            }
        }
        position += sign * best;
        if (count == HISTORY)
            shift(1);
        times[count] = eventTimeNanos;
        positions[count] = position;
        count++;
        return lastRaw = sign * best;
    }

    /** Forgets samples the new one leaves outside the horizon, or past the history's size. */
    private void dropOld(long now) {
        int old = 0;
        while (old < count && now - times[old] > HORIZON_NS)
            old++;
        shift(old);
    }

    private void shift(int n) {
        if (n <= 0)
            return;
        System.arraycopy(times, n, times, 0, count - n);
        System.arraycopy(positions, n, positions, 0, count - n);
        count -= n;
    }

    /** Android's multiplier for a new sample at (t, p) on top of the samples kept. */
    private float factor(long t, float p) {
        final float speed = Math.abs(velocity(t, p));
        if (speed >= HIGH_SPEED)
            return ACCELERATION;
        if (speed > LOW_SPEED)
            return 1f + (speed - LOW_SPEED) / (HIGH_SPEED - LOW_SPEED) * (ACCELERATION - 1f);
        return 1f;
    }

    /**
     * LSQ2's velocity at the newest sample, in units a second, or 0 where Android has none
     * (a single sample, which leaves the event as it was). Two samples fit a line; three or
     * more the quadratic of {@code solveUnweightedLeastSquaresDeg2}.
     */
    private float velocity(long t, float p) {
        final int first = count == HISTORY ? 1 : 0;
        final int n = count - first + 1;
        if (n < 2)
            return 0f;
        if (n == 2) {
            final double dt = (t - times[count - 1]) * 1e-9;
            return dt > 0 ? (float) ((p - positions[count - 1]) / dt) : 0f;
        }
        double sxi = 0, sxiyi = 0, syi = 0, sxi2 = 0, sxi3 = 0, sxi2yi = 0, sxi4 = 0;
        for (int i = first; i <= count; i++) {
            final double xi = i < count ? -(t - times[i]) * 1e-9 : 0d;
            final double yi = i < count ? positions[i] : p;
            final double xi2 = xi * xi;
            sxi += xi;
            sxi2 += xi2;
            sxi3 += xi2 * xi;
            sxi4 += xi2 * xi2;
            sxiyi += xi * yi;
            sxi2yi += xi2 * yi;
            syi += yi;
        }
        final double sxx = sxi2 - sxi * sxi / n;
        final double sxy = sxiyi - sxi * syi / n;
        final double sxx2 = sxi3 - sxi * sxi2 / n;
        final double sx2y = sxi2yi - sxi2 * syi / n;
        final double sx2x2 = sxi4 - sxi2 * sxi2 / n;
        final double denominator = sxx * sx2x2 - sxx2 * sxx2;
        return denominator == 0d ? 0f : (float) ((sxy * sx2x2 - sx2y * sxx2) / denominator);
    }
}
