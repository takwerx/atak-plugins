package com.atakmap.android.atmosphere.overlay;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PointF;
import android.view.MotionEvent;
import android.view.View;

import com.atakmap.android.atmosphere.wind.WindGrid;
import com.atakmap.android.maps.MapView;
import com.atakmap.coremap.maps.coords.GeoBounds;
import com.atakmap.coremap.maps.coords.GeoPoint;

import java.util.Random;

/**
 * The Windy look, drawn at screen resolution: a transparent view over the map in
 * which every particle's last positions are drawn as a thin line that fades toward
 * its tail, colored by speed. Particles live in lon/lat, so a pan or zoom moves
 * them with the ground; each frame projects them through the map's own transform.
 *
 * <p>No bitmap and no texture upload: the earlier version painted into a 512 px
 * image draped on the map, and every stroke came out two or three screen pixels
 * wide and a slow particle painted a dot. The operator: "trails look bad". This
 * draws lines one pixel wide on the hardware canvas.
 *
 * <p>Projection: the four corners of the view are projected with the map each
 * frame and everything inside is placed bilinearly between them, which is exact
 * enough for a wind view and costs four allocations a frame instead of twenty
 * thousand. It goes wrong on the globe at continental scale, where the view is
 * not a quadrilateral; wind at that scale is not the use.
 *
 * <p>Touches pass through: the view is not clickable and consumes nothing.
 */
final class WindView extends View {

    /**
     * Speed bands, shared with {@link WindScaleView} so the legend can never drift
     * from what is drawn. {@link #BAND_MAX_MS} holds the upper edge of every band
     * but the last, which runs to whatever the wind is doing.
     */
    static final int[] BAND_COLORS = {
            0xFFB8DCFF, 0xFF58B4FF, 0xFF58E890, 0xFFFFE850, 0xFFFF9A30, 0xFFFF4040
    };
    static final float[] BAND_MAX_MS = { 2, 5, 8, 12, 18 };

    private static final int TRAIL = 36;
    /** Screen pixels a 6 m/s wind moves a particle per frame. */
    private static final float PX_PER_FRAME_AT_6MS = 1.7f;
    private static final int MAX_AGE = 200;

    private final MapView mapView;
    private final int particles;
    private final double[] lat, lon;
    /** Trail ring buffers of lon/lat, per particle. */
    private final double[] tLat, tLon;
    private final int[] head, len, age;
    private final float[] speed;
    private final float[] uv = new float[2];
    private final Random random = new Random();
    private final Paint[][] paints = new Paint[6][TRAIL];
    private WindGrid grid;
    private final double[] cornerX = new double[4], cornerY = new double[4];
    private long lastReport;
    private int drawnSegments;

    WindView(Context context, MapView mapView, int particles) {
        super(context);
        this.mapView = mapView;
        this.particles = particles;
        lat = new double[particles];
        lon = new double[particles];
        tLat = new double[particles * TRAIL];
        tLon = new double[particles * TRAIL];
        head = new int[particles];
        len = new int[particles];
        age = new int[particles];
        speed = new float[particles];
        final int[] colors = BAND_COLORS;
        for (int b = 0; b < colors.length; b++) {
            for (int k = 0; k < TRAIL; k++) {
                final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(2.0f);
                p.setStrokeCap(Paint.Cap.ROUND);
                p.setColor(colors[b]);
                // newest segment brightest; the tail fades to nothing
                // a slow rise so the tail thins out over its length, not in a step
                p.setAlpha(Math.round(235f * (float) Math.pow((k + 1f) / TRAIL, 1.6)));
                paints[b][k] = p;
            }
        }
        setClickable(false);
        setFocusable(false);
        setWillNotDraw(false);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        return false;
    }

    void setGrid(WindGrid g) {
        grid = g;
        for (int i = 0; i < particles; i++)
            respawn(i);
        invalidate();
    }

    private void respawn(int i) {
        final WindGrid g = grid;
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
        head[i] = 0;
        len[i] = 0;
        age[i] = random.nextInt(MAX_AGE / 2);
        speed[i] = 0;
    }

    /** Advance every particle one frame; the draw follows on the next invalidate. */
    void step() {
        final WindGrid g = grid;
        if (g == null)
            return;
        // Metres per screen pixel decides how far a frame carries a particle, so the
        // motion reads the same at every zoom and faster wind still moves faster.
        final double mPerPx = Math.max(0.1, mapView.getMapResolution());
        final double secondsPerFrame = PX_PER_FRAME_AT_6MS * mPerPx / 6.0;
        for (int i = 0; i < particles; i++) {
            if (++age[i] > MAX_AGE) {
                respawn(i);
                continue;
            }
            g.sample(lat[i], lon[i], uv);
            if (Float.isNaN(uv[0])) {
                respawn(i);
                continue;
            }
            speed[i] = (float) Math.hypot(uv[0], uv[1]);
            final int slot = i * TRAIL + head[i];
            tLat[slot] = lat[i];
            tLon[slot] = lon[i];
            head[i] = (head[i] + 1) % TRAIL;
            if (len[i] < TRAIL)
                len[i]++;
            final double dLat = uv[1] * secondsPerFrame / 110540.0;
            final double dLon = uv[0] * secondsPerFrame / (111320.0 * Math.cos(Math.toRadians(lat[i])));
            lat[i] += dLat;
            lon[i] += dLon;
            if (!g.contains(lat[i], lon[i]))
                respawn(i);
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        final WindGrid g = grid;
        if (g == null)
            return;
        final GeoBounds view = mapView.getBounds();
        if (view == null || Double.isNaN(view.getWest()) || view.getEast() <= view.getWest())
            return;
        // Corners of the current view on screen: NW, NE, SE, SW.
        final double vw = view.getWest(), ve = view.getEast(), vs = view.getSouth(), vn = view.getNorth();
        project(vn, vw, 0);
        project(vn, ve, 1);
        project(vs, ve, 2);
        project(vs, vw, 3);
        final float w = getWidth(), h = getHeight();
        drawnSegments = 0;
        for (int i = 0; i < particles; i++) {
            final int n = len[i];
            if (n < 2)
                continue;
            final int band = band(speed[i]);
            // walk the ring from oldest to newest
            int idx = (head[i] - n + TRAIL) % TRAIL;
            double px = Double.NaN, py = Double.NaN;
            for (int k = 0; k < n; k++) {
                final int slot = i * TRAIL + idx;
                final double fx = (tLon[slot] - vw) / (ve - vw);
                final double fy = (vn - tLat[slot]) / (vn - vs);
                final double sx = bilin(cornerX, fx, fy), sy = bilin(cornerY, fx, fy);
                if (!Double.isNaN(px) && sx >= -20 && sx <= w + 20 && sy >= -20 && sy <= h + 20) {
                    canvas.drawLine((float) px, (float) py, (float) sx, (float) sy,
                            paints[band][TRAIL - n + k]);
                    drawnSegments++;
                }
                px = sx;
                py = sy;
                idx = (idx + 1) % TRAIL;
            }
        }
        report(w, h);
    }

    /** Once a second: what the view is doing, for the log. */
    private void report(float w, float h) {
        final long now = System.currentTimeMillis();
        if (now - lastReport < 5000)
            return;
        lastReport = now;
        com.atakmap.coremap.log.Log.d("AtmosphereWind", String.format(java.util.Locale.US,
                "view %dx%d vis %d parent %s corners NW %.0f,%.0f SE %.0f,%.0f segments %d",
                (int) w, (int) h, getVisibility(), getParent() == null ? "none"
                        : getParent().getClass().getSimpleName(),
                cornerX[0], cornerY[0], cornerX[2], cornerY[2], drawnSegments));
    }

    /**
     * A fresh point each time: a GeoPoint from the two-argument constructor is
     * read-only and set() leaves it at 0,0, which put all four corners on one pixel
     * and every particle with them (XCover, 2026-09-21). Four allocations a frame.
     */
    private void project(double la, double lo, int k) {
        final PointF p = mapView.forward(new GeoPoint(la, lo));
        cornerX[k] = p.x;
        cornerY[k] = p.y;
    }

    /** Bilinear between NW, NE, SE, SW for fractions across (fx) and down (fy). */
    private static double bilin(double[] c, double fx, double fy) {
        final double top = c[0] + (c[1] - c[0]) * fx;
        final double bottom = c[3] + (c[2] - c[3]) * fx;
        return top + (bottom - top) * fy;
    }

    static int band(float ms) {
        for (int i = 0; i < BAND_MAX_MS.length; i++) {
            if (ms < BAND_MAX_MS[i])
                return i;
        }
        return BAND_MAX_MS.length;
    }
}
