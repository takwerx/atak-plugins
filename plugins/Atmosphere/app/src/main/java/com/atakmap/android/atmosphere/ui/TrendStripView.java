package com.atakmap.android.atmosphere.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.view.View;

import java.util.ArrayList;
import java.util.List;

/**
 * One value across the coming hours, the way a consumer weather app draws it and a
 * crew already reads it: a column per hour with the time on top, a sky glyph, the
 * number, and a dot on a line whose height follows the value. Sunrise and sunset
 * are their own columns between the hours. No axes, no grid: the numbers are the
 * scale. Lives inside a HorizontalScrollView and sizes itself to its columns.
 */
public class TrendStripView extends View {

    /** One column of the strip. */
    public static final class Column {
        /** "4p", "Now", "Tue 6a"; two lines allowed with a newline. */
        public String header = "";
        public boolean now;
        /** The plotted value, NaN for none; text is what is printed above the dot. */
        public double value = Double.NaN;
        public String text = "";
        /** Wind direction to draw an arrow after the text, degrees the wind blows toward; NaN for none. */
        public double arrowDeg = Double.NaN;
        /** Sky cover in oktas 0..8, -1 for unknown. */
        public int oktas = -1;
        /** Night at this hour: the glyph is a moon instead of a sun. */
        public boolean night;
        /** A sunrise or sunset column: no value, a horizon glyph and {@link #sunLabel}. */
        public boolean sunEvent;
        public boolean sunrise;
        public String sunLabel = "";
    }

    private final List<Column> columns = new ArrayList<>();
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dimPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint linePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dotPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glyphStroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glyphFill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint sunPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final RectF box = new RectF();

    private final float density;
    private final int columnW;
    private final int headerH, glyphH, valueH, plotH, padBottom;
    private final int accent = 0xFFDFB228;   // heading_yellow, the plugin's accent

    public TrendStripView(Context context) {
        super(context);
        density = context.getResources().getDisplayMetrics().density;
        columnW = dp(64);
        headerH = dp(22);
        glyphH = dp(30);
        valueH = dp(24);
        plotH = dp(54);
        padBottom = dp(8);

        textPaint.setColor(Color.WHITE);
        textPaint.setTextSize(dp(15));
        textPaint.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        textPaint.setTextAlign(Paint.Align.CENTER);

        dimPaint.setColor(Color.WHITE);
        dimPaint.setAlpha(150);
        dimPaint.setTextSize(dp(12));
        dimPaint.setTextAlign(Paint.Align.CENTER);

        linePaint.setColor(accent);
        linePaint.setStyle(Paint.Style.STROKE);
        linePaint.setStrokeWidth(dp(2));
        linePaint.setStrokeCap(Paint.Cap.ROUND);

        dotPaint.setColor(accent);
        dotPaint.setStyle(Paint.Style.FILL);

        glyphStroke.setColor(Color.WHITE);
        glyphStroke.setStyle(Paint.Style.STROKE);
        glyphStroke.setStrokeWidth(dp(1.5f));
        glyphFill.setColor(Color.WHITE);
        glyphFill.setStyle(Paint.Style.FILL);

        sunPaint.setColor(accent);
        sunPaint.setStyle(Paint.Style.STROKE);
        sunPaint.setStrokeWidth(dp(1.5f));
    }

    public void setColumns(List<Column> cols) {
        columns.clear();
        if (cols != null)
            columns.addAll(cols);
        requestLayout();
        invalidate();
    }

    public int columnWidth() {
        return columnW;
    }

    private int dp(float v) {
        return Math.round(v * density);
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        final int w = Math.max(columnW, columns.size() * columnW);
        final int h = headerH + glyphH + valueH + plotH + padBottom;
        setMeasuredDimension(w, h);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (columns.isEmpty())
            return;

        // Scale from the plotted values, with headroom so the top dot sits under its number.
        double lo = Double.MAX_VALUE, hi = -Double.MAX_VALUE;
        for (Column c : columns) {
            if (!c.sunEvent && !Double.isNaN(c.value)) {
                lo = Math.min(lo, c.value);
                hi = Math.max(hi, c.value);
            }
        }
        if (hi <= lo) {
            hi = lo + 1;
            lo = lo - 1;
        }
        final float plotTop = headerH + glyphH + valueH;
        final float plotBottom = plotTop + plotH;
        final float dotR = dp(4.5f);

        // The line first, so dots and numbers sit on it. Sun-event columns are skipped
        // but the line still passes under them.
        path.reset();
        boolean started = false;
        for (int i = 0; i < columns.size(); i++) {
            final Column c = columns.get(i);
            if (c.sunEvent || Double.isNaN(c.value))
                continue;
            final float x = i * columnW + columnW / 2f;
            final float y = yFor(c.value, lo, hi, plotTop + dotR + dp(2), plotBottom - dotR - dp(2));
            if (!started) {
                path.moveTo(x, y);
                started = true;
            } else {
                path.lineTo(x, y);
            }
        }
        canvas.drawPath(path, linePaint);

        for (int i = 0; i < columns.size(); i++) {
            final Column c = columns.get(i);
            final float x0 = i * columnW;
            final float cx = x0 + columnW / 2f;

            // header: "Now" bright, the rest dim; two lines allowed
            final Paint hp = c.now ? textPaint : dimPaint;
            final float savedSize = hp.getTextSize();
            hp.setTextSize(dp(13));
            final String[] lines = c.header.split("\n");
            float hy = dp(15);
            for (String l : lines) {
                canvas.drawText(l, cx, hy, hp);
                hy += dp(13);
            }
            hp.setTextSize(savedSize);

            final float gy = headerH + glyphH / 2f;
            if (c.sunEvent) {
                drawHorizonSun(canvas, cx, gy, dp(10));
                dimPaint.setColor(accent);
                dimPaint.setAlpha(230);
                canvas.drawText(c.sunLabel, cx, headerH + glyphH + dp(16), dimPaint);
                dimPaint.setColor(Color.WHITE);
                dimPaint.setAlpha(150);
                continue;
            }

            // sky glyph: sun or moon, covered by the okta fraction
            drawSky(canvas, cx, gy, dp(10), c);

            if (Double.isNaN(c.value)) {
                canvas.drawText("—", cx, headerH + glyphH + dp(17), textPaint);
                continue;
            }
            final float y = yFor(c.value, lo, hi, plotTop + dotR + dp(2), plotBottom - dotR - dp(2));
            // the number rides just above its dot, the arrow after it
            final float textW = textPaint.measureText(c.text);
            final float arrowW = Double.isNaN(c.arrowDeg) ? 0 : dp(14);
            final float tx = cx - arrowW / 2f;
            canvas.drawText(c.text, tx, y - dotR - dp(6), textPaint);
            if (!Double.isNaN(c.arrowDeg))
                drawArrow(canvas, tx + textW / 2f + dp(9), y - dotR - dp(11), dp(5), (float) c.arrowDeg);
            canvas.drawCircle(cx, y, c.now ? dotR * 1.4f : dotR, dotPaint);
        }
    }

    private static float yFor(double v, double lo, double hi, float top, float bottom) {
        final double f = (v - lo) / (hi - lo);
        return (float) (bottom - f * (bottom - top));
    }

    /** A small arrow pointing the way the wind blows, 0 = toward north (up). */
    private void drawArrow(Canvas canvas, float cx, float cy, float r, float towardDeg) {
        canvas.save();
        canvas.rotate(towardDeg, cx, cy);
        canvas.drawLine(cx, cy + r, cx, cy - r, glyphStroke);
        canvas.drawLine(cx, cy - r, cx - r * 0.6f, cy - r * 0.3f, glyphStroke);
        canvas.drawLine(cx, cy - r, cx + r * 0.6f, cy - r * 0.3f, glyphStroke);
        canvas.restore();
    }

    /** Sun with rays or a crescent moon, then a cloud over it sized to the okta count. */
    private void drawSky(Canvas canvas, float cx, float cy, float r, Column c) {
        if (c.night) {
            // crescent: full disc minus an offset disc
            box.set(cx - r * 0.8f, cy - r * 0.8f, cx + r * 0.8f, cy + r * 0.8f);
            canvas.drawOval(box, glyphFill);
            final Paint cut = new Paint(Paint.ANTI_ALIAS_FLAG);
            cut.setColor(0xFF1E1E1E);
            box.set(cx - r * 0.35f, cy - r * 0.95f, cx + r * 0.95f, cy + r * 0.65f);
            canvas.drawOval(box, cut);
        } else {
            canvas.drawCircle(cx, cy, r * 0.5f, sunPaint);
            for (int k = 0; k < 8; k++) {
                final double a = Math.toRadians(k * 45);
                canvas.drawLine((float) (cx + Math.cos(a) * r * 0.7f), (float) (cy + Math.sin(a) * r * 0.7f),
                        (float) (cx + Math.cos(a) * r), (float) (cy + Math.sin(a) * r), sunPaint);
            }
        }
        if (c.oktas >= 3) {
            // a cloud lump, bigger with more cover, over the lower right of the glyph
            final float s = r * (0.5f + 0.08f * c.oktas);
            final float ox = cx + r * 0.25f, oy = cy + r * 0.35f;
            box.set(ox - s, oy - s * 0.55f, ox + s, oy + s * 0.45f);
            canvas.drawRoundRect(box, s * 0.45f, s * 0.45f, glyphFill);
            canvas.drawCircle(ox - s * 0.25f, oy - s * 0.45f, s * 0.5f, glyphFill);
            canvas.drawCircle(ox + s * 0.3f, oy - s * 0.3f, s * 0.38f, glyphFill);
        }
    }

    /** Half a sun on a horizon line, the sunrise and sunset mark. */
    private void drawHorizonSun(Canvas canvas, float cx, float cy, float r) {
        box.set(cx - r * 0.6f, cy - r * 0.3f, cx + r * 0.6f, cy + r * 0.9f);
        canvas.drawArc(box, 180, 180, false, sunPaint);
        canvas.drawLine(cx - r, cy + r * 0.3f, cx + r, cy + r * 0.3f, sunPaint);
        for (int k = 1; k <= 3; k++) {
            final double a = Math.toRadians(180 + k * 45);
            canvas.drawLine((float) (cx + Math.cos(a) * r * 0.8f), (float) (cy + r * 0.3f + Math.sin(a) * r * 0.8f),
                    (float) (cx + Math.cos(a) * r * 1.05f), (float) (cy + r * 0.3f + Math.sin(a) * r * 1.05f), sunPaint);
        }
    }
}
