package com.atakmap.android.atmosphere.overlay;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PointF;
import android.view.MotionEvent;
import android.view.View;

import com.atakmap.android.atmosphere.waves.WaveGrid;
import com.atakmap.android.maps.MapView;
import com.atakmap.coremap.maps.coords.GeoBounds;
import com.atakmap.coremap.maps.coords.GeoPoint;

import java.util.Random;

/**
 * The swell as moving crests: short curved lines across the direction of travel,
 * gliding the way the swell runs, faster when the period is longer, over the
 * sea-state colors the raster underneath draws. The look the operator asked for
 * from FlowX (2026-09-27: "i like the way that flow x does the swell direction";
 * of the arrows this replaced, "these arrows look horrible like 8 bit graphics").
 *
 * <p>{@link WindView}'s frame, without trails: a transparent view inside the map
 * view, particles that live in lon/lat so a pan or zoom carries them with the
 * ground, the view's four corners projected each frame and everything placed
 * bilinearly between them. Each crest is a shallow arc of {@link #ARC_SEGMENTS}
 * pieces, laid out in meters around its particle and projected point by point, so
 * it stays across the swell however the map is rotated. Crests fade in when born
 * and out before they die, so the field breathes instead of popping. Every
 * segment is batched by its fade step into one {@code drawLines} call, with a dark
 * outline under it so it reads on any color of sea; that is what kept the wind
 * view off the UI thread's back, and this one draws a tenth of its geometry.
 *
 * <p>Motion is screen motion, not the swell's real 20 m/s: at a continental zoom
 * the real speed is a pixel a minute and at a harbor zoom it is a blur. A
 * ten-second swell moves about a pixel a frame; a twenty-second swell twice that.
 */
final class SwellView extends View {

    /** Frames a crest lives; it fades in over the first steps and out over the last. */
    private static final int MAX_AGE = 150;
    private static final int FADE_IN = 18;
    private static final int FADE_OUT = 36;
    /** Screen pixels a ten-second swell carries a crest each frame. */
    private static final float PX_PER_FRAME_AT_10S = 1.0f;
    /**
     * Pieces in each crest's arc. Four: the wind view learned that a segment shorter
     * than its own cap is what makes the renderer, not the draw call, the cost
     * (100% janky frames at 105 ms with six round-capped pieces of 7 px, XCover
     * 2026-09-27); a crest of four butt-capped pieces of 12 px is the same curve.
     */
    private static final int ARC_SEGMENTS = 4;
    /** Half the crest's length on screen at the smallest seas, and what each sea state adds. */
    private static final float HALF_LEN_PX = 20f;
    private static final float HALF_LEN_PER_BAND_PX = 2.5f;
    /** How far the middle of the arc bows ahead of its ends, as a share of the half length. */
    private static final float BOW = 0.28f;
    private static final int FADES = 6;

    private final MapView mapView;
    private final int particles;
    private final double[] lat, lon;
    private final int[] age;
    /** Direction each crest travels toward, degrees true, and its length and speed on screen. */
    private final float[] toward, halfLen, speedPx;
    private final Random random = new Random();
    private final Paint[] paints = new Paint[FADES];
    private final Paint[] outline = new Paint[FADES];
    private final float[][] batch = new float[FADES][];
    private final int[] batchCount = new int[FADES];
    private WaveGrid grid;
    private final double[] cornerX = new double[4], cornerY = new double[4];
    private final double[] arcX = new double[ARC_SEGMENTS + 1], arcY = new double[ARC_SEGMENTS + 1];
    private long lastReport;
    private int drawnSegments;
    private long drawNanos;
    private int drawCount;

    SwellView(Context context, MapView mapView, int particles) {
        super(context);
        this.mapView = mapView;
        this.particles = particles;
        lat = new double[particles];
        lon = new double[particles];
        age = new int[particles];
        toward = new float[particles];
        halfLen = new float[particles];
        speedPx = new float[particles];
        for (int k = 0; k < FADES; k++) {
            final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(2.0f);
            // Butt, not round: see ARC_SEGMENTS.
            p.setStrokeCap(Paint.Cap.BUTT);
            p.setColor(0xFFFFFFFF);
            p.setAlpha(Math.round(225f * (k + 1f) / FADES));
            paints[k] = p;
            final Paint o = new Paint(Paint.ANTI_ALIAS_FLAG);
            o.setStyle(Paint.Style.STROKE);
            o.setStrokeWidth(3.4f);
            o.setStrokeCap(Paint.Cap.BUTT);
            o.setColor(0xFF10304A);
            o.setAlpha(Math.round(120f * (k + 1f) / FADES));
            outline[k] = o;
        }
        setClickable(false);
        setFocusable(false);
        setWillNotDraw(false);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        return false;
    }

    void setGrid(WaveGrid g) {
        grid = g;
        for (int i = 0; i < particles; i++) {
            respawn(i);
            // Spread the ages so the field is not born and dying in lockstep.
            age[i] = random.nextInt(MAX_AGE);
        }
        invalidate();
    }

    /** A new crest somewhere in the view over water; on land it waits a frame and tries again. */
    private void respawn(int i) {
        final WaveGrid g = grid;
        final GeoBounds view = mapView.getBounds();
        double w = g == null ? -180 : g.west, e = g == null ? 180 : g.east;
        double s = g == null ? -90 : g.south, n = g == null ? 90 : g.north;
        if (view != null && !Double.isNaN(view.getWest()) && view.getEast() > view.getWest()) {
            w = Math.max(w, view.getWest());
            e = Math.min(e, view.getEast());
            s = Math.max(s, view.getSouth());
            n = Math.min(n, view.getNorth());
        }
        if (e <= w || n <= s) {
            w = g == null ? -180 : g.west; e = g == null ? 180 : g.east;
            s = g == null ? -90 : g.south; n = g == null ? 90 : g.north;
        }
        lon[i] = w + random.nextDouble() * (e - w);
        lat[i] = s + random.nextDouble() * (n - s);
        age[i] = 0;
        toward[i] = Float.NaN;
        if (g != null)
            readSea(i, g);
    }

    /** What the swell is doing under a crest: where it runs, how long it is, how fast it moves. */
    private void readSea(int i, WaveGrid g) {
        final float height = g.sample(g.height, lat[i], lon[i]);
        if (Float.isNaN(height)) {
            toward[i] = Float.NaN;
            return;
        }
        float from = Float.NaN, period = Float.NaN;
        if (g.swellDir != null) {
            from = g.nearestAt(g.swellDir, lat[i], lon[i]);
            period = g.swellPeriod == null ? Float.NaN : g.nearestAt(g.swellPeriod, lat[i], lon[i]);
        }
        if (Float.isNaN(from) && g.dir != null) {
            from = g.nearestAt(g.dir, lat[i], lon[i]);
            period = g.period == null ? Float.NaN : g.nearestAt(g.period, lat[i], lon[i]);
        }
        if (Float.isNaN(from)) {
            toward[i] = Float.NaN;
            return;
        }
        toward[i] = (from + 180f) % 360f;
        final int band = Math.max(0, com.atakmap.android.atmosphere.waves.NomadsWaves.band(height));
        halfLen[i] = HALF_LEN_PX + HALF_LEN_PER_BAND_PX * band;
        final float t = Float.isNaN(period) || period <= 0 ? 10f : Math.min(25f, Math.max(4f, period));
        speedPx[i] = PX_PER_FRAME_AT_10S * t / 10f;
    }

    /** Advance every crest one frame; the draw follows on the next invalidate. */
    void step() {
        final WaveGrid g = grid;
        if (g == null)
            return;
        final double mPerPx = Math.max(0.1, mapView.getMapResolution());
        for (int i = 0; i < particles; i++) {
            if (++age[i] > MAX_AGE || Float.isNaN(toward[i])) {
                respawn(i);
                continue;
            }
            final double rad = Math.toRadians(toward[i]);
            final double meters = speedPx[i] * mPerPx;
            lat[i] += Math.cos(rad) * meters / 110540.0;
            lon[i] += Math.sin(rad) * meters / (111320.0 * Math.cos(Math.toRadians(lat[i])));
            if (!g.contains(lat[i], lon[i]))
                respawn(i);
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        final long t0 = System.nanoTime();
        final WaveGrid g = grid;
        if (g == null)
            return;
        final GeoBounds view = mapView.getBounds();
        if (view == null || Double.isNaN(view.getWest()) || view.getEast() <= view.getWest())
            return;
        final double vw = view.getWest(), ve = view.getEast(), vs = view.getSouth(), vn = view.getNorth();
        project(vn, vw, 0);
        project(vn, ve, 1);
        project(vs, ve, 2);
        project(vs, vw, 3);
        final float w = getWidth(), h = getHeight();
        final double mPerPx = Math.max(0.1, mapView.getMapResolution());
        drawnSegments = 0;
        java.util.Arrays.fill(batchCount, 0);
        for (int i = 0; i < particles; i++) {
            if (Float.isNaN(toward[i]))
                continue;
            // The crest's arc in meters around its particle: across the direction of
            // travel, bowed ahead in the middle; then each point to lon/lat and to screen.
            final double rad = Math.toRadians(toward[i]);
            final double fwdN = Math.cos(rad), fwdE = Math.sin(rad);
            final double acrossN = -fwdE, acrossE = fwdN;
            final double half = halfLen[i] * mPerPx, bow = half * BOW;
            final double cosLat = Math.cos(Math.toRadians(lat[i]));
            boolean onScreen = false;
            for (int k = 0; k <= ARC_SEGMENTS; k++) {
                final double t = -1 + 2.0 * k / ARC_SEGMENTS;
                final double dN = acrossN * half * t + fwdN * bow * (1 - t * t);
                final double dE = acrossE * half * t + fwdE * bow * (1 - t * t);
                final double pLat = lat[i] + dN / 110540.0;
                final double pLon = lon[i] + dE / (111320.0 * cosLat);
                final double fx = (pLon - vw) / (ve - vw), fy = (vn - pLat) / (vn - vs);
                arcX[k] = bilin(cornerX, fx, fy);
                arcY[k] = bilin(cornerY, fx, fy);
                if (arcX[k] >= -30 && arcX[k] <= w + 30 && arcY[k] >= -30 && arcY[k] <= h + 30)
                    onScreen = true;
            }
            if (!onScreen)
                continue;
            final int a = age[i];
            final float life = a < FADE_IN ? a / (float) FADE_IN
                    : a > MAX_AGE - FADE_OUT ? (MAX_AGE - a) / (float) FADE_OUT : 1f;
            final int fade = Math.max(0, Math.min(FADES - 1, Math.round(life * (FADES - 1))));
            for (int k = 0; k < ARC_SEGMENTS; k++) {
                add(fade, (float) arcX[k], (float) arcY[k], (float) arcX[k + 1], (float) arcY[k + 1]);
                drawnSegments++;
            }
        }
        for (int b = 0; b < FADES; b++)
            if (batchCount[b] > 0)
                canvas.drawLines(batch[b], 0, batchCount[b], outline[b]);
        for (int b = 0; b < FADES; b++)
            if (batchCount[b] > 0)
                canvas.drawLines(batch[b], 0, batchCount[b], paints[b]);
        drawNanos += System.nanoTime() - t0;
        drawCount++;
        report(w, h, canvas.isHardwareAccelerated());
    }

    private void add(int bucket, float x1, float y1, float x2, float y2) {
        float[] a = batch[bucket];
        final int at = batchCount[bucket];
        if (a == null)
            a = batch[bucket] = new float[2048];
        else if (at + 4 > a.length) {
            final float[] bigger = new float[a.length * 2];
            System.arraycopy(a, 0, bigger, 0, at);
            a = batch[bucket] = bigger;
        }
        a[at] = x1;
        a[at + 1] = y1;
        a[at + 2] = x2;
        a[at + 3] = y2;
        batchCount[bucket] = at + 4;
    }

    /** Every five seconds: what the view costs, for the log. */
    private void report(float w, float h, boolean hardware) {
        final long now = System.currentTimeMillis();
        if (now - lastReport < 5000)
            return;
        lastReport = now;
        com.atakmap.coremap.log.Log.d("AtmosphereWaves", String.format(java.util.Locale.US,
                "swell view %dx%d hw %b segments %d onDraw avg %.1f ms over %d",
                (int) w, (int) h, hardware, drawnSegments,
                drawCount == 0 ? 0 : drawNanos / 1e6 / drawCount, drawCount));
        drawNanos = 0;
        drawCount = 0;
    }

    /** A fresh GeoPoint each time; see {@link WindView#project}. */
    private void project(double la, double lo, int k) {
        final PointF p = mapView.forward(new GeoPoint(la, lo));
        cornerX[k] = p.x;
        cornerY[k] = p.y;
    }

    private static double bilin(double[] c, double fx, double fy) {
        final double top = c[0] + (c[1] - c[0]) * fx;
        final double bottom = c[3] + (c[2] - c[3]) * fx;
        return top + (bottom - top) * fy;
    }
}
