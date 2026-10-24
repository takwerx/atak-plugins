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
import com.atakmap.coremap.maps.coords.GeoPointMetaData;
import com.atakmap.map.MapRenderer3;
import com.atakmap.map.MapSceneModel;
import com.atakmap.map.opengl.GLMapView;

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
 * <p>A tilted (3D) map is a perspective, which that stretch is not: the far side is
 * foreshortened and the horizon can be in view (operator, 2026-10-24: the wind
 * "disappears" in 3D). There each frame fits a {@link GroundPerspective} to four
 * ground points around the point the camera looks at, places every trail point
 * through it, and spawns new particles on ground that is actually on screen.
 * Each particle carries the height of the ground under it, read from the
 * renderer's own terrain, and the fit is made at two heights so a point is placed
 * at its own: wind over a valley 1,300 m below the ridge the camera looks at sits
 * on the valley floor, not on a sheet at the ridge's height that slides across the
 * ground as the map pans.
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
    /**
     * Draw every third stored position instead of all of them. A particle moves about
     * 1.7 px a frame, so segment-per-frame meant 35 stubs shorter than their own round
     * caps per particle: 55,000 tiny capped dots a frame, which the renderer took
     * ~400 ms to rasterize while our own draw recorded in 17 ms. The trail is the same
     * length on screen and the same shape; it is built of a dozen longer pieces
     * instead of three dozen stubs.
     */
    private static final int DRAW_STEP = 3;
    /** Screen pixels a 6 m/s wind moves a particle per frame. */
    private static final float PX_PER_FRAME_AT_6MS = 1.7f;
    private static final int MAX_AGE = 200;

    private final MapView mapView;
    private final int particles;
    private final double[] lat, lon;
    /** Trail ring buffers of lon/lat, per particle. */
    private final double[] tLat, tLon;
    /** Ground height under each particle and each trail point (tilted view only), NaN unknown. */
    private final double[] alt, tAlt;
    /** The ground's height under each particle as last read, which its height eases toward. */
    private final double[] groundUnder;
    private int ticks;
    private final int[] head, len, age;
    private final float[] speed;
    private final float[] uv = new float[2];
    private final Random random = new Random();
    /**
     * How many brightness steps the trail fades through. It was one per trail point,
     * which meant a Paint per point and so a canvas call per segment: 69,000 of them
     * a frame at 30 fps, all on the thread the pane's taps queue behind, and the pane
     * stopped answering (XCover, 2026-09-22: "side bar not really reactive"). Eight
     * steps over a 36-point trail of 2 px lines is the same picture, and it lets the
     * segments be batched into one call per color and step.
     */
    private static final int FADES = 8;
    private final Paint[][] paints = new Paint[6][FADES];
    /**
     * A dark stroke laid under the bright half of each trail.
     *
     * <p>The field is drawn in the speed colors and nothing else, which disappears
     * over anything of a similar tone -- pale green terrain, tan desert, a light
     * satellite tile (operator, 2026-09-25: "really hard to see on certain base
     * maps"). An outline is what makes a thin line survive an unknown background;
     * it is what the station barb does for the same reason.
     *
     * <p>Only the brighter steps get one. The tail is faint by design and outlining
     * it would double the cost of the whole field for something nobody is reading --
     * and this view has starved the UI thread before, so its per-frame work is not
     * something to spend without looking.
     */
    private final Paint[] outline = new Paint[FADES];
    /** Below this step a trail is tail, and is left alone. */
    private static final int OUTLINE_FROM = FADES / 2;
    /** Segment endpoints per (color, fade) bucket, grown once and reused every frame. */
    private final float[][] batch = new float[6 * FADES][];
    private final int[] batchCount = new int[6 * FADES];
    private WindGrid grid;
    private final double[] cornerX = new double[4], cornerY = new double[4];

    /** Past this many degrees of tilt the map is a perspective, not a stretch. */
    static final double TILT_MIN_DEG = 0.5;
    /**
     * How far from the point the camera looks at a tilted view draws wind, in spans
     * of the screen measured at that point. The ground runs on to the horizon, but a
     * particle out there is a pixel; these are the ones worth the frame.
     */
    static final double TILTED_REACH_SPANS = 4;
    /** Ground (lon, lat from the focus) to screen, and back, for the tilted view. */
    private final double[] hom = new double[9], homInv = new double[9], homUp = new double[9];
    /**
     * The fit's third column as a function of height: column(z) = a + z * b, so a
     * point at height z goes through {@link #hom} with its third column replaced.
     */
    private double a2, a5, a8, b2, b5, b8, z0;
    /** Meters between the two heights the perspective is fitted at. */
    private static final double FIT_DZ = 1000;
    /** How much of the way to the ground's height a particle moves each tick. */
    private static final double HEIGHT_EASE = 0.35;
    private long lastFitLog;
    private final double[] refX = new double[4], refY = new double[4];
    private final double[] refSx = new double[4], refSy = new double[4];
    private final double[] pt = new double[2];
    private boolean homValid;
    private double fLat, fLon, reachLat, reachLon;
    private long lastReport;
    private int drawnSegments;
    private long drawNanos;
    private int drawCount;
    private boolean hardware;

    WindView(Context context, MapView mapView, int particles) {
        super(context);
        this.mapView = mapView;
        this.particles = particles;
        lat = new double[particles];
        lon = new double[particles];
        tLat = new double[particles * TRAIL];
        tLon = new double[particles * TRAIL];
        alt = new double[particles];
        groundUnder = new double[particles];
        java.util.Arrays.fill(groundUnder, Double.NaN);
        tAlt = new double[particles * TRAIL];
        java.util.Arrays.fill(alt, Double.NaN);
        java.util.Arrays.fill(tAlt, Double.NaN);
        head = new int[particles];
        len = new int[particles];
        age = new int[particles];
        speed = new float[particles];
        final int[] colors = BAND_COLORS;
        for (int b = 0; b < colors.length; b++) {
            for (int k = 0; k < FADES; k++) {
                final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(1.9f);
                // Butt, not round: a round cap puts a filled semicircle on both ends of
                // every segment, and the field is thousands of them blended over each
                // other. The joins are a few pixels apart along a smooth curve, so the
                // trail still reads as a line.
                p.setStrokeCap(Paint.Cap.BUTT);
                p.setColor(colors[b]);
                // newest segment brightest; the tail fades to nothing
                // a slow rise so the tail thins out over its length, not in a step
                p.setAlpha(Math.round(235f * (float) Math.pow((k + 1f) / FADES, 1.6)));
                paints[b][k] = p;
            }
        }
        for (int k = OUTLINE_FROM; k < FADES; k++) {
            final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(3.4f);
            p.setStrokeCap(Paint.Cap.BUTT);
            p.setColor(0xFF000000);
            // Fainter than the color it carries, so it reads as a shadow rather than
            // as a second field of black worms.
            p.setAlpha(Math.round(150f * (float) Math.pow((k + 1f) / FADES, 1.6)));
            outline[k] = p;
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
        if (g != null && homValid && tilted(mapView) && respawnOnScreen(i, g))
            return;
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
        alt[i] = groundUnder[i] = homValid ? groundAt(lat[i], lon[i]) : Double.NaN;
        head[i] = 0;
        len[i] = 0;
        age[i] = random.nextInt(MAX_AGE / 2);
        speed[i] = 0;
    }

    /**
     * Tilted: a random pixel taken down to the ground, so particles are born where the
     * eye is rather than evenly over a box most of which is off screen or out at the
     * horizon. A few tries, then the caller's box.
     */
    private boolean respawnOnScreen(int i, WindGrid g) {
        final int w = getWidth(), h = getHeight();
        if (w <= 0 || h <= 0)
            return false;
        for (int t = 0; t < 8; t++) {
            if (!GroundPerspective.apply(homInv, random.nextDouble() * w, random.nextDouble() * h, pt))
                continue; // sky
            if (Math.abs(pt[0]) > reachLon || Math.abs(pt[1]) > reachLat)
                continue; // ground too far out to be worth a particle
            final double la = fLat + pt[1], lo = fLon + pt[0];
            if (!g.contains(la, lo))
                continue;
            lat[i] = la;
            lon[i] = lo;
            alt[i] = groundUnder[i] = groundAt(la, lo);
            head[i] = 0;
            len[i] = 0;
            age[i] = random.nextInt(MAX_AGE / 2);
            speed[i] = 0;
            return true;
        }
        return false;
    }

    /** Advance every particle one frame; the draw follows on the next invalidate. */
    void step() {
        final WindGrid g = grid;
        if (g == null)
            return;
        // Meters per screen pixel decides how far a frame carries a particle, so the
        // motion reads the same at every zoom and faster wind still moves faster.
        final double mPerPx = Math.max(0.1, mapView.getMapResolution());
        final double secondsPerFrame = PX_PER_FRAME_AT_6MS * mPerPx / 6.0;
        // Tilted: each particle follows the ground's height, eased toward rather than
        // jumped to. Jumping to a height read an eighth of the particles a tick drew
        // every trail as a sawtooth -- flat for eight frames, then a step -- because
        // close in, 100 m of height is 80 px on screen (operator, 2026-10-24: "not sure
        // i love how its squigling"). Reading every particle every tick fixed that and
        // cost 14 ms a tick on the UI thread (about 11 us a read on the Note 20), so a
        // quarter of them are read a tick and the easing carries the rest.
        final boolean heights = homValid;
        ticks++;
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
            if (heights) {
                if (((ticks + i) & 3) == 0 || Double.isNaN(groundUnder[i]))
                    groundUnder[i] = groundAt(lat[i], lon[i]);
                final double ground = groundUnder[i];
                if (!Double.isNaN(ground))
                    alt[i] = Double.isNaN(alt[i]) ? ground : alt[i] + (ground - alt[i]) * HEIGHT_EASE;
            }
            final int slot = i * TRAIL + head[i];
            tLat[slot] = lat[i];
            tLon[slot] = lon[i];
            tAlt[slot] = alt[i];
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
        final long t0 = System.nanoTime();
        final WindGrid g = grid;
        if (g == null)
            return;
        final float w = getWidth(), h = getHeight();
        final boolean persp = tilted(mapView);
        double vw = 0, ve = 1, vs = 0, vn = 1;
        if (persp) {
            homValid = perspective(w, h);
            if (!homValid)
                return;
        } else {
            homValid = false;
            final GeoBounds view = mapView.getBounds();
            if (view == null || Double.isNaN(view.getWest()) || view.getEast() <= view.getWest())
                return;
            // Corners of the current view on screen: NW, NE, SE, SW.
            vw = view.getWest();
            ve = view.getEast();
            vs = view.getSouth();
            vn = view.getNorth();
            project(vn, vw, 0);
            project(vn, ve, 1);
            project(vs, ve, 2);
            project(vs, vw, 3);
        }
        drawnSegments = 0;
        java.util.Arrays.fill(batchCount, 0);
        for (int i = 0; i < particles; i++) {
            final int n = len[i];
            if (n < 2)
                continue;
            final int band = band(speed[i]);
            // walk the ring from oldest to newest
            int idx = (head[i] - n + TRAIL) % TRAIL;
            double px = Double.NaN, py = Double.NaN;
            for (int k = 0; k < n; k++) {
                // Sampled from the newest point back, so the head of the trail always
                // sits exactly where the particle is.
                if ((n - 1 - k) % DRAW_STEP == 0) {
                    final int slot = i * TRAIL + idx;
                    final double sx, sy;
                    if (!persp) {
                        final double fx = (tLon[slot] - vw) / (ve - vw);
                        final double fy = (vn - tLat[slot]) / (vn - vs);
                        sx = bilin(cornerX, fx, fy);
                        sy = bilin(cornerY, fx, fy);
                    } else if (placeAt(tLon[slot] - fLon, tLat[slot] - fLat, tAlt[slot])) {
                        sx = pt[0];
                        sy = pt[1];
                    } else {
                        // Past the horizon: breaks the trail rather than drawing a
                        // segment across the sky.
                        sx = Double.NaN;
                        sy = Double.NaN;
                    }
                    if (!Double.isNaN(px) && !Double.isNaN(sx)
                            && sx >= -20 && sx <= w + 20 && sy >= -20 && sy <= h + 20) {
                        final int fade = ((TRAIL - n + k) * FADES) / TRAIL;
                        add(band * FADES + fade, (float) px, (float) py, (float) sx, (float) sy);
                        drawnSegments++;
                    }
                    px = sx;
                    py = sy;
                }
                idx = (idx + 1) % TRAIL;
            }
        }
        // One canvas call per color and fade step rather than one per segment.
        // Every outline first, so a trail is never drawn over its neighbour's shadow.
        for (int b = 0; b < batch.length; b++) {
            final int fade = b % FADES;
            if (batchCount[b] > 0 && outline[fade] != null)
                canvas.drawLines(batch[b], 0, batchCount[b], outline[fade]);
        }
        for (int b = 0; b < batch.length; b++)
            if (batchCount[b] > 0)
                canvas.drawLines(batch[b], 0, batchCount[b], paints[b / FADES][b % FADES]);
        drawNanos += System.nanoTime() - t0;
        drawCount++;
        hardware = canvas.isHardwareAccelerated();
        report(w, h);
    }

    /** Append one segment to its bucket, growing the bucket the first time it is short. */
    private void add(int bucket, float x1, float y1, float x2, float y2) {
        float[] a = batch[bucket];
        final int at = batchCount[bucket];
        if (a == null)
            a = batch[bucket] = new float[1024];
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

    /** Once a second: what the view is doing, for the log. */
    private void report(float w, float h) {
        final long now = System.currentTimeMillis();
        if (now - lastReport < 5000)
            return;
        lastReport = now;
        com.atakmap.coremap.log.Log.d("AtmosphereWind", String.format(java.util.Locale.US,
                "view %dx%d hw %b layer %d parent %s segments %d onDraw avg %.1f ms over %d"
                        + " tilt %.1f persp %b focus %.4f,%.4f",
                (int) w, (int) h, hardware, getLayerType(), getParent() == null ? "none"
                        : getParent().getClass().getSimpleName(),
                drawnSegments, drawCount == 0 ? 0 : drawNanos / 1e6 / drawCount, drawCount,
                mapView.getMapTilt(), homValid, fLat, fLon));
        drawNanos = 0;
        drawCount = 0;
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

    /** Whether the map is tilted enough to be drawn as a perspective. */
    static boolean tilted(MapView mv) {
        return mv != null && mv.getMapTilt() > TILT_MIN_DEG;
    }

    /**
     * The ground point the camera looks at, with its height: the camera's target in
     * the map's own projection. The map center when that is not to be had.
     */
    static GeoPoint focus(MapView mv) {
        try {
            final MapSceneModel sm = mv.getSceneModel();
            if (sm != null && sm.camera != null && sm.camera.target != null && sm.mapProjection != null) {
                final GeoPoint g = sm.mapProjection.inverse(sm.camera.target, GeoPoint.createMutable());
                if (g != null && Math.abs(g.getLatitude()) <= 90 && Math.abs(g.getLongitude()) <= 360
                        && !Double.isNaN(g.getLatitude() + g.getLongitude()))
                    return g;
            }
        } catch (RuntimeException e) {
            // fall through to the center
        }
        final GeoPointMetaData c = mv.getCenterPoint();
        return c == null ? null : c.get();
    }

    /** Meters of ground a tilted view draws wind out to from its focus. */
    static double tiltedReachMeters(MapView mv) {
        return Math.max(mv.getWidth(), mv.getHeight()) * Math.max(0.1, mv.getMapResolution())
                * TILTED_REACH_SPANS;
    }

    /**
     * Fits the tilted view's perspective for this frame: four ground points a quarter
     * screen around the focus, at the focus's own height so they lie in one plane,
     * projected by the map itself. Four allocations a frame, as the flat path's are.
     */
    private boolean perspective(float w, float h) {
        if (w <= 0 || h <= 0)
            return false;
        final GeoPoint f = focus(mapView);
        if (f == null)
            return false;
        fLat = f.getLatitude();
        fLon = f.getLongitude();
        double alt = groundAt(fLat, fLon);
        if (Double.isNaN(alt))
            alt = f.getAltitude();
        if (Double.isNaN(alt))
            alt = 0;
        final double cos = Math.max(0.05, Math.cos(Math.toRadians(fLat)));
        final double m = Math.max(50, Math.max(0.1, mapView.getMapResolution()) * Math.min(w, h) * 0.25);
        final double dLat = m / 110540.0, dLon = m / (111320.0 * cos);
        refX[0] = -dLon; refY[0] = dLat;
        refX[1] = dLon;  refY[1] = dLat;
        refX[2] = dLon;  refY[2] = -dLat;
        refX[3] = -dLon; refY[3] = -dLat;
        if (!fitAt(alt, hom) || !GroundPerspective.invert(hom, homInv))
            return false;
        z0 = alt;
        // The same four points a kilometer up: the two fits differ only in the
        // column that carries height, once the second is brought to the first's scale.
        if (fitAt(alt + FIT_DZ, homUp)) {
            double num = 0, den = 0;
            for (int k : new int[] { 0, 1, 3, 4, 6, 7 }) {
                num += homUp[k] * hom[k];
                den += hom[k] * hom[k];
            }
            final double r = den > 0 ? num / den : 0;
            if (r != 0 && !Double.isNaN(r)) {
                b2 = (homUp[2] / r - hom[2]) / FIT_DZ;
                b5 = (homUp[5] / r - hom[5]) / FIT_DZ;
                b8 = (homUp[8] / r - hom[8]) / FIT_DZ;
            } else
                b2 = b5 = b8 = 0;
        } else
            b2 = b5 = b8 = 0;
        a2 = hom[2] - z0 * b2;
        a5 = hom[5] - z0 * b5;
        a8 = hom[8] - z0 * b8;
        final long now = System.currentTimeMillis();
        if (now - lastFitLog > 5000) {
            lastFitLog = now;
            com.atakmap.coremap.log.Log.d("AtmosphereWind", String.format(java.util.Locale.US,
                    "3D fit: focus alt %.0f m, ground there %.0f m, height moves the focus %.1f px per 100 m",
                    alt, groundAt(fLat, fLon), 100 * Math.hypot(b2 - hom[2] * b8, b5 - hom[5] * b8)));
        }
        final double reach = tiltedReachMeters(mapView);
        reachLat = reach / 110540.0;
        reachLon = reach / (111320.0 * cos);
        return true;
    }

    /** The four reference points at height {@code z}, projected by the map, fitted into {@code h}. */
    private boolean fitAt(double z, double[] h) {
        for (int k = 0; k < 4; k++) {
            final PointF p = mapView.forward(new GeoPoint(fLat + refY[k], fLon + refX[k], z));
            if (p == null)
                return false;
            refSx[k] = p.x;
            refSy[k] = p.y;
        }
        return GroundPerspective.solve(refX, refY, refSx, refSy, h);
    }

    /** Ground (dx, dy from the focus) at height z onto the screen, into {@link #pt}. */
    private boolean placeAt(double dx, double dy, double z) {
        if (Double.isNaN(z))
            z = z0;
        final double w = hom[6] * dx + hom[7] * dy + a8 + z * b8;
        if (!(w > 1e-12))
            return false;
        pt[0] = (hom[0] * dx + hom[1] * dy + a2 + z * b2) / w;
        pt[1] = (hom[3] * dx + hom[4] * dy + a5 + z * b5) / w;
        return true;
    }

    private GLMapView gl;
    private boolean glLooked;

    /** The ground's height at a point as the renderer has it, or NaN. */
    private double groundAt(double la, double lo) {
        if (!glLooked) {
            glLooked = true;
            try {
                final MapRenderer3 r = mapView.getRenderer3();
                if (r instanceof GLMapView)
                    gl = (GLMapView) r;
            } catch (LinkageError | RuntimeException e) {
                gl = null;
            }
        }
        if (gl == null)
            return Double.NaN;
        try {
            final double e = gl.getTerrainMeshElevation(la, lo);
            return Double.isInfinite(e) ? Double.NaN : e;
        } catch (LinkageError | RuntimeException e) {
            gl = null;
            return Double.NaN;
        }
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
