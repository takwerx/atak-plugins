package com.atakmap.android.atmosphere.overlay;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;

import com.atakmap.android.atmosphere.wind.WindGrid;

import java.util.Random;

/**
 * The Windy look: particles carried by the wind, drawn as fading trails into a
 * bitmap that the RasterLayer drapes over the grid's extent. Pure drawing, no map
 * and no threads: {@link #tick()} advances one step and returns the bitmap to show.
 *
 * <p>Two bitmaps alternate so the one the GL thread is uploading is never the one
 * being drawn. Each tick copies the last frame, fades it by keeping most of its
 * alpha, moves every particle by the wind at its position and draws the segment,
 * colored by speed. A particle that leaves the grid, hits a hole or ages out is
 * reborn somewhere random.
 */
final class WindAnimator {

    /** Pixels per (m/s) per tick: 10 m/s crosses about 1.4 px a tick at 20 ticks a second. */
    private static final float PX_PER_MS = 0.14f;
    private static final float KEEP_ALPHA = 0.94f;
    private static final int MAX_AGE = 90;

    private final int width, height;
    private final Bitmap[] buffers = new Bitmap[2];
    private int front;
    private final Paint fade = new Paint();
    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint clear = new Paint();
    private final float[] px, py;
    private final int[] age;
    private final float[] uv = new float[2];
    private final Random random = new Random();
    private WindGrid grid;

    WindAnimator(int width, int height, int particles) {
        this.width = width;
        this.height = height;
        buffers[0] = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        buffers[1] = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        px = new float[particles];
        py = new float[particles];
        age = new int[particles];
        fade.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.DST_IN));
        fade.setColor(Color.argb(Math.round(255 * KEEP_ALPHA), 0, 0, 0));
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeWidth(1.6f);
        line.setStrokeCap(Paint.Cap.ROUND);
        clear.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.CLEAR));
        for (int i = 0; i < particles; i++)
            respawn(i);
    }

    void setGrid(WindGrid g) {
        grid = g;
        for (int i = 0; i < px.length; i++)
            respawn(i);
        for (Bitmap b : buffers)
            new Canvas(b).drawRect(0, 0, width, height, clear);
    }

    /** The bitmap last returned by {@link #tick()}. */
    Bitmap current() {
        return buffers[front];
    }

    private void respawn(int i) {
        px[i] = random.nextFloat() * width;
        py[i] = random.nextFloat() * height;
        age[i] = random.nextInt(MAX_AGE);
    }

    /** One step; returns the frame to show, or null when there is no grid. */
    Bitmap tick() {
        final WindGrid g = grid;
        if (g == null)
            return null;
        final int back = 1 - front;
        final Canvas c = new Canvas(buffers[back]);
        c.drawRect(0, 0, width, height, clear);
        c.drawBitmap(buffers[front], 0, 0, null);
        c.drawRect(0, 0, width, height, fade);
        final double lonPerPx = (g.east - g.west) / width;
        final double latPerPx = (g.north - g.south) / height;
        for (int i = 0; i < px.length; i++) {
            if (++age[i] > MAX_AGE) {
                respawn(i);
                continue;
            }
            final double lon = g.west + px[i] * lonPerPx;
            final double lat = g.north - py[i] * latPerPx;
            g.sample(lat, lon, uv);
            if (Float.isNaN(uv[0])) {
                respawn(i);
                continue;
            }
            final float speed = (float) Math.hypot(uv[0], uv[1]);
            final float nx = px[i] + uv[0] * PX_PER_MS;
            final float ny = py[i] - uv[1] * PX_PER_MS;
            if (nx < 0 || ny < 0 || nx >= width || ny >= height) {
                respawn(i);
                continue;
            }
            line.setColor(colorFor(speed));
            c.drawLine(px[i], py[i], nx, ny, line);
            px[i] = nx;
            py[i] = ny;
        }
        front = back;
        return buffers[front];
    }

    /** Speed in m/s to a color: calm white-blue through green and yellow to red past 18. */
    static int colorFor(float ms) {
        if (ms < 2) return 0xE0B8D8FF;
        if (ms < 5) return 0xE060B0FF;
        if (ms < 8) return 0xE050E080;
        if (ms < 12) return 0xE0FFE040;
        if (ms < 18) return 0xE0FF9020;
        return 0xE0FF3030;
    }
}
