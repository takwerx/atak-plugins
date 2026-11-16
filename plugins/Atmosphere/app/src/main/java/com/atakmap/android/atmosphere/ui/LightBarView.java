package com.atakmap.android.atmosphere.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.View;

import com.atakmap.android.atmosphere.astro.Light;

import java.util.Calendar;
import java.util.TimeZone;

/**
 * A day's light as one bar, local midnight to midnight: night, the three twilights
 * and day in their colors, a line across the hours the moon is up with the moon's
 * phase drawn on it, and a mark under "now" when now is in the day. Read-only.
 *
 * <p>For crews who plan around light and paste this picture into a worksheet or an
 * IAP (2026-11-15), so it reads on its own: hour labels every 3 hours, the bands
 * named in the legend under it.
 */
public final class LightBarView extends View {

    /** Band colors, darkest first: night, astronomical, nautical, civil, day. */
    public static final int[] BAND_COLORS = { 0xFF0A1030, 0xFF1F3F94, 0xFF2D86A8, 0xFFDB8530,
            0xFFF0D466 };
    public static final String[] BAND_NAMES = { "Night", "Astronomical", "Nautical", "Civil",
            "Day" };

    private final float density;
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint tick = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint label = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint moonLine = new Paint(Paint.ANTI_ALIAS_FLAG);
    private Light.Day day;
    private TimeZone tz = TimeZone.getDefault();
    private long now;
    private double phase;   // elongation degrees at the moon glyph's time

    public LightBarView(Context context) {
        super(context);
        density = context.getResources().getDisplayMetrics().density;
        tick.setColor(0xFFB0B8C8);
        tick.setStrokeWidth(1.2f * density);
        label.setColor(0xFFFFFFFF);
        label.setTextSize(12f * density);
        label.setTextAlign(Paint.Align.CENTER);
        moonLine.setColor(0xFFFFFFFF);
        moonLine.setStrokeWidth(2.5f * density);
        moonLine.setStrokeCap(Paint.Cap.ROUND);
    }

    public void setDay(Light.Day day, TimeZone tz, long now, double moonElongationDeg) {
        this.day = day;
        this.tz = tz;
        this.now = now;
        this.phase = moonElongationDeg;
        invalidate();
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        final int w = MeasureSpec.getSize(widthSpec);
        setMeasuredDimension(w, (int) (82 * density));
    }

    @Override
    protected void onDraw(Canvas c) {
        if (day == null || day.bands == null || day.bands.length == 0)
            return;
        final float pad = 6 * density;
        final float left = pad, right = getWidth() - pad;
        final float top = 2 * density, barH = 44 * density, bottom = top + barH;
        final double span = day.end - day.start;

        // Bands, merged into runs so the edges are crisp.
        int i = 0;
        while (i < day.bands.length) {
            int j = i;
            while (j + 1 < day.bands.length && day.bands[j + 1] == day.bands[i])
                j++;
            final float x0 = x(day.start + i * Light.SLOT_MS, left, right, span);
            final float x1 = x(day.start + (j + 1) * Light.SLOT_MS, left, right, span);
            fill.setColor(BAND_COLORS[day.bands[i]]);
            c.drawRect(x0, top, Math.min(x1, right), bottom, fill);
            i = j + 1;
        }

        // The moon: a line across the hours it is up, its phase on the line.
        final float my = top + barH * 0.45f;
        final float r = 9 * density;
        float glyphX = Float.NaN;
        for (long[] up : day.moonUp) {
            final float x0 = x(up[0], left, right, span), x1 = x(up[1], left, right, span);
            c.drawLine(x0, my, x1, my, moonLine);
            if (now >= up[0] && now <= up[1])
                glyphX = x(now, left, right, span);
            else if (Float.isNaN(glyphX))
                glyphX = (x0 + x1) / 2;
        }
        if (!Float.isNaN(glyphX))
            drawMoon(c, Math.max(left + r, Math.min(right - r, glyphX)), my, r, phase);

        // Hour ticks, a label every 3 hours by the local clock.
        final Calendar cal = Calendar.getInstance(tz);
        cal.setTimeInMillis(day.start);
        final float labelY = bottom + 26 * density;
        while (cal.getTimeInMillis() <= day.end) {
            final long t = cal.getTimeInMillis();
            final int hour = cal.get(Calendar.HOUR_OF_DAY);
            final float x = x(t, left, right, span);
            final boolean major = hour % 3 == 0;
            c.drawLine(x, bottom, x, bottom + (major ? 7 : 4) * density, tick);
            if (major && t > day.start && t < day.end)
                c.drawText(hourLabel(hour), x, labelY, label);
            cal.add(Calendar.HOUR_OF_DAY, 1);
        }

        // "Now" under the bar, when now is in this day.
        if (now >= day.start && now < day.end) {
            final float x = x(now, left, right, span);
            final Path p = new Path();
            p.moveTo(x, bottom + 1 * density);
            p.lineTo(x - 6 * density, bottom + 11 * density);
            p.lineTo(x + 6 * density, bottom + 11 * density);
            p.close();
            fill.setColor(0xFFFFFFFF);
            c.drawPath(p, fill);
        }
    }

    private static String hourLabel(int h) {
        if (com.atakmap.android.atmosphere.units.Clock.is24())
            return String.valueOf(h);
        if (h == 0) return "12a";
        if (h == 12) return "12p";
        return h < 12 ? h + "a" : (h - 12) + "p";
    }

    /** The x of a time within the day, clamped to the bar. */
    private float x(long t, float left, float right, double span) {
        final double f = (t - day.start) / span;
        return (float) (left + (right - left) * Math.max(0, Math.min(1, f)));
    }

    /**
     * The moon as it looks: a dark disc with the lit part on the right while waxing
     * and on the left while waning (northern hemisphere), drawn from the elongation.
     */
    public static void drawMoon(Canvas c, float cx, float cy, float r, double elongationDeg) {
        final Paint dark = new Paint(Paint.ANTI_ALIAS_FLAG);
        dark.setColor(0xFF2A2A2E);
        final Paint lit = new Paint(Paint.ANTI_ALIAS_FLAG);
        lit.setColor(0xFFF4F4F0);
        final Paint rim = new Paint(Paint.ANTI_ALIAS_FLAG);
        rim.setStyle(Paint.Style.STROKE);
        rim.setStrokeWidth(Math.max(1f, r / 16f));
        rim.setColor(0xFF7C8494);
        c.drawCircle(cx, cy, r, dark);
        final double e = ((elongationDeg % 360) + 360) % 360;
        final boolean waxing = e < 180;
        // The lit half, on the right while waxing; then the terminator as an ellipse
        // of half-width r|cos e| laid over the middle: dark for a crescent, lit for a
        // gibbous moon. (A Path arc of -180 degrees is the same as +180 on Android, so
        // the terminator drawn as an arc bulged the wrong way and 37% read as a half.)
        final RectF disc = new RectF(cx - r, cy - r, cx + r, cy + r);
        c.drawArc(disc, waxing ? -90 : 90, 180, true, lit);
        final float k = (float) (r * Math.abs(Math.cos(Math.toRadians(e))));
        final boolean gibbous = e > 90 && e < 270;
        c.drawOval(new RectF(cx - k, cy - r, cx + k, cy + r), gibbous ? lit : dark);
        c.drawCircle(cx, cy, r, rim);
    }
}
