package com.atakmap.android.atmosphere.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.View;

import com.atakmap.android.atmosphere.data.Nwps;
import com.atakmap.android.atmosphere.overlay.GaugeOverlay;
import com.atakmap.android.atmosphere.units.UnitSystem;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * A gauge's stage over the last days and the forecast ahead, the way water.noaa.gov
 * draws it: the observed line solid, the forecast dashed past a "now" line, and
 * the flood stages as bands across the chart in the legend's own colors, so a
 * crest against a band says what it means without a number being read.
 *
 * <p>Days along the bottom in site-local time; the stage scale up the left in the
 * operator's unit. Sized to its parent's width.
 */
public class HydrographView extends View {

    private static final long DAY = 86_400_000L;

    private final float density;
    private final Paint obs = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fc = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint now = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint band = new Paint();
    private final Paint grid = new Paint();
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dim = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();

    private List<Nwps.Point> observed = new ArrayList<>();
    private List<Nwps.Point> forecast = new ArrayList<>();
    private Nwps.Stages stages = Nwps.Stages.NONE;
    private UnitSystem units = UnitSystem.IMPERIAL;
    private long from, to;
    private int daysBack = 7;

    public HydrographView(Context context) {
        super(context);
        density = context.getResources().getDisplayMetrics().density;
        obs.setColor(0xFF4FA3FF);
        obs.setStyle(Paint.Style.STROKE);
        obs.setStrokeWidth(dp(2));
        obs.setStrokeJoin(Paint.Join.ROUND);
        fc.setColor(0xFF4FA3FF);
        fc.setStyle(Paint.Style.STROKE);
        fc.setStrokeWidth(dp(2));
        fc.setPathEffect(new DashPathEffect(new float[] { dp(6), dp(4) }, 0));
        now.setColor(Color.WHITE);
        now.setAlpha(160);
        now.setStyle(Paint.Style.STROKE);
        now.setStrokeWidth(dp(1));
        now.setPathEffect(new DashPathEffect(new float[] { dp(3), dp(3) }, 0));
        grid.setColor(Color.WHITE);
        grid.setAlpha(40);
        grid.setStrokeWidth(dp(1));
        text.setColor(Color.WHITE);
        text.setTextSize(dp(11));
        dim.setColor(Color.WHITE);
        dim.setAlpha(150);
        dim.setTextSize(dp(11));
    }

    public void set(Nwps.Hydrograph h, Nwps.Stages s, UnitSystem u, int days) {
        observed = h == null ? new ArrayList<Nwps.Point>() : h.observed;
        forecast = h == null ? new ArrayList<Nwps.Point>() : h.forecast;
        stages = s == null ? Nwps.Stages.NONE : s;
        units = u;
        daysBack = Math.max(1, days);
        final long nowMs = System.currentTimeMillis();
        from = nowMs - daysBack * DAY;
        long end = nowMs + DAY;
        for (Nwps.Point p : forecast)
            end = Math.max(end, p.at);
        to = end;
        invalidate();
    }

    private int dp(float v) {
        return Math.round(v * density);
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        setMeasuredDimension(MeasureSpec.getSize(widthSpec), dp(190));
    }

    private static double toUnit(double feet, UnitSystem u) {
        return u == UnitSystem.METRIC ? feet * 0.3048 : feet;
    }

    @Override
    protected void onDraw(Canvas c) {
        final int w = getWidth(), h = getHeight();
        final float left = dp(44), right = w - dp(8), top = dp(10), bottom = h - dp(24);
        if (right <= left || bottom <= top)
            return;

        // The stage range: the data, and the bands so a crest is shown against them.
        double lo = Double.MAX_VALUE, hi = -Double.MAX_VALUE;
        for (Nwps.Point p : observed)
            if (p.at >= from && !Double.isNaN(p.stage)) {
                lo = Math.min(lo, p.stage);
                hi = Math.max(hi, p.stage);
            }
        for (Nwps.Point p : forecast)
            if (!Double.isNaN(p.stage)) {
                lo = Math.min(lo, p.stage);
                hi = Math.max(hi, p.stage);
            }
        if (lo == Double.MAX_VALUE) {
            c.drawText("No stage data", left, (top + bottom) / 2, dim);
            return;
        }
        if (!Double.isNaN(stages.minor))
            hi = Math.max(hi, stages.minor);
        else if (!Double.isNaN(stages.action))
            hi = Math.max(hi, stages.action);
        if (hi - lo < 1)
            hi = lo + 1;
        final double pad = (hi - lo) * 0.1;
        lo -= pad;
        hi += pad;
        final double span = hi - lo;

        // Bands, each from its stage up to the next one (or the top).
        band.setStyle(Paint.Style.FILL);
        drawBand(c, left, right, top, bottom, lo, span, stages.action, stages.minor,
                GaugeOverlay.legendColor(Nwps.ACTION));
        drawBand(c, left, right, top, bottom, lo, span, stages.minor, stages.moderate,
                GaugeOverlay.legendColor(Nwps.MINOR));
        drawBand(c, left, right, top, bottom, lo, span, stages.moderate, stages.major,
                GaugeOverlay.legendColor(Nwps.MODERATE));
        drawBand(c, left, right, top, bottom, lo, span, stages.major, Double.NaN,
                GaugeOverlay.legendColor(Nwps.MAJOR));

        // Stage scale: four lines.
        for (int i = 0; i <= 4; i++) {
            final float y = bottom - (bottom - top) * i / 4f;
            c.drawLine(left, y, right, y, grid);
            final double v = toUnit(lo + span * i / 4.0, units);
            c.drawText(String.format(Locale.US, "%.1f", v), dp(4), y + dp(4), dim);
        }
        c.drawText(units == UnitSystem.METRIC ? "m" : "ft", dp(4), top - dp(2), dim);

        // Days along the bottom, in site-local time.
        final SimpleDateFormat day = new SimpleDateFormat("EEE d", Locale.US);
        final long msSpan = to - from;
        final long firstMidnight = from - (from % DAY) + DAY;
        for (long t = firstMidnight; t < to; t += DAY) {
            final float x = left + (right - left) * (t - from) / (float) msSpan;
            c.drawLine(x, top, x, bottom, grid);
            c.drawText(day.format(new Date(t)), x + dp(3), h - dp(8), dim);
        }

        // Now.
        final long nowMs = System.currentTimeMillis();
        final float nx = left + (right - left) * (nowMs - from) / (float) msSpan;
        c.drawLine(nx, top, nx, bottom, now);

        // Observed, then forecast.
        line(c, observed, left, right, top, bottom, lo, span, msSpan, obs);
        line(c, forecast, left, right, top, bottom, lo, span, msSpan, fc);

        // The latest reading, in words, at the now line.
        Nwps.Point last = null;
        for (Nwps.Point p : observed)
            if (!Double.isNaN(p.stage))
                last = p;
        if (last != null) {
            final String s = GaugeOverlay.stage(last.stage, units);
            final float tw = text.measureText(s);
            float tx = nx + dp(4);
            if (tx + tw > right)
                tx = nx - tw - dp(4);
            c.drawText(s, tx, top + dp(12), text);
        }
    }

    private void drawBand(Canvas c, float left, float right, float top, float bottom,
            double lo, double span, double fromStage, double toStage, int color) {
        if (Double.isNaN(fromStage))
            return;
        final float y1 = (float) (bottom - (bottom - top) * (fromStage - lo) / span);
        final float y0 = Double.isNaN(toStage) ? top
                : (float) (bottom - (bottom - top) * (toStage - lo) / span);
        if (y1 <= top)
            return;
        band.setColor(color);
        band.setAlpha(60);
        c.drawRect(left, Math.max(top, y0), right, Math.min(bottom, y1), band);
        band.setAlpha(200);
        c.drawLine(left, Math.min(bottom, y1), right, Math.min(bottom, y1), band);
    }

    private void line(Canvas c, List<Nwps.Point> pts, float left, float right, float top,
            float bottom, double lo, double span, long msSpan, Paint paint) {
        path.reset();
        boolean started = false;
        for (Nwps.Point p : pts) {
            if (p.at < from || p.at > to || Double.isNaN(p.stage))
                continue;
            final float x = left + (right - left) * (p.at - from) / (float) msSpan;
            final float y = (float) (bottom - (bottom - top) * (p.stage - lo) / span);
            if (!started) {
                path.moveTo(x, y);
                started = true;
            } else {
                path.lineTo(x, y);
            }
        }
        if (started)
            c.drawPath(path, paint);
    }
}
