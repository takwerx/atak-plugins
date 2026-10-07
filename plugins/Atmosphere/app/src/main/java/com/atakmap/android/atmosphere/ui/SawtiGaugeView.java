package com.atakmap.android.atmosphere.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.View;

import com.atakmap.android.atmosphere.data.Sawti;

/**
 * One of the three gauges on the SAWTI website's forecast page, drawn the way the
 * site draws it, on the scales its script sets (read 2026-10-01):
 *
 * <ul>
 *   <li>Threat Level: a half dial 0-5 in the five level colors, the needle at the
 *       level plus a half, so it sits in the middle of its band.</li>
 *   <li>Wind Strength: a half dial 100-1000, one gray band, Weak to Strong.</li>
 *   <li>Fuel Moisture: an upright bar 0-8, green at the bottom (Moist) through
 *       yellow to red at the top (Dry), with a marker at the value.</li>
 * </ul>
 *
 * The pane is dark, so the needle is white where the site's is black. A missing
 * value draws the gauge without a needle and says the site's own words for it.
 */
public class SawtiGaugeView extends View {

    public static final int THREAT = 0, WIND = 1, FUEL = 2;

    private static final int[] FUEL_COLORS = { 0xFF0B6623, 0xFFFFC700, 0xFFC20000 };

    private final int kind;
    private final float density;
    private final Paint band = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint needle = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF box = new RectF();
    private final Path marker = new Path();
    private double value = Double.NaN;

    public SawtiGaugeView(Context context, int kind) {
        super(context);
        this.kind = kind;
        density = context.getResources().getDisplayMetrics().density;
        band.setStyle(Paint.Style.STROKE);
        band.setStrokeCap(Paint.Cap.BUTT);
        needle.setColor(Color.WHITE);
        needle.setStrokeCap(Paint.Cap.ROUND);
        text.setColor(0xFFCCCCCC);
        text.setTextSize(11 * density);
        fill.setStyle(Paint.Style.FILL);
    }

    /**
     * Threat: the level 0-4. Wind: the {@code W^2} value. Fuel: the gauge value 0-8.
     * NaN for none.
     */
    public void setValue(double v) {
        value = v;
        invalidate();
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        final int w = MeasureSpec.getSize(widthSpec);
        setMeasuredDimension(w, Math.round(w * 0.72f));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (kind == FUEL)
            drawFuel(canvas);
        else
            drawDial(canvas);
    }

    private void drawDial(Canvas c) {
        final float w = getWidth(), h = getHeight();
        final float stroke = w * 0.11f;
        final float r = Math.min(w / 2f - stroke / 2f - 2 * density, h - stroke - 16 * density);
        final float cx = w / 2f, cy = stroke / 2f + r + 2 * density;
        box.set(cx - r, cy - r, cx + r, cy + r);
        band.setStrokeWidth(stroke);
        final boolean missing = Double.isNaN(value);
        double fraction;
        if (kind == THREAT) {
            for (int i = 0; i < Sawti.COLORS.length; i++) {
                band.setColor(Sawti.COLORS[i]);
                canvas(c, 180f + 36f * i, 36f);
            }
            fraction = (Math.max(0, Math.min(4, value)) + 0.5) / 5.0;
        } else {
            band.setColor(0xFF8A8A8A);
            canvas(c, 180f, 180f);
            text.setTextAlign(Paint.Align.LEFT);
            c.drawText("Weak", cx - r - stroke / 2f, cy + 13 * density, text);
            text.setTextAlign(Paint.Align.RIGHT);
            c.drawText("Strong", cx + r + stroke / 2f, cy + 13 * density, text);
            fraction = (Math.max(100, Math.min(1000, value)) - 100) / 900.0;
        }
        if (missing) {
            text.setTextAlign(Paint.Align.CENTER);
            c.drawText("Not available", cx, cy - r * 0.25f, text);
            return;
        }
        final double angle = Math.PI * (1 + fraction);
        final float len = r * 0.82f;
        needle.setStrokeWidth(3 * density);
        c.drawLine(cx, cy, cx + (float) (Math.cos(angle) * len),
                cy + (float) (Math.sin(angle) * len), needle);
        c.drawCircle(cx, cy, 5 * density, needle);
    }

    private void canvas(Canvas c, float start, float sweep) {
        c.drawArc(box, start, sweep, false, band);
    }

    private void drawFuel(Canvas c) {
        final float w = getWidth(), h = getHeight();
        final float barW = Math.max(6 * density, w * 0.07f);
        final float top = 8 * density, bottom = h - 8 * density;
        final float x = w * 0.58f;
        final float third = (bottom - top) / 3f;
        for (int i = 0; i < 3; i++) {
            fill.setColor(FUEL_COLORS[i]);
            c.drawRect(x - barW / 2f, bottom - third * (i + 1), x + barW / 2f, bottom - third * i,
                    fill);
        }
        text.setTextAlign(Paint.Align.RIGHT);
        c.drawText("Dry", x - barW - 6 * density, top + 9 * density, text);
        c.drawText("Moist", x - barW - 6 * density, bottom, text);
        if (Double.isNaN(value)) {
            text.setTextAlign(Paint.Align.LEFT);
            c.drawText("Not", x + barW, (top + bottom) / 2f - 2 * density, text);
            c.drawText("available", x + barW, (top + bottom) / 2f + 11 * density, text);
            return;
        }
        final float y = bottom - (float) (Math.max(0, Math.min(8, value)) / 8.0) * (bottom - top);
        final float s = 7 * density;
        marker.reset();
        marker.moveTo(x + barW / 2f + 2 * density, y);
        marker.lineTo(x + barW / 2f + 2 * density + s * 1.6f, y - s);
        marker.lineTo(x + barW / 2f + 2 * density + s * 1.6f, y + s);
        marker.close();
        fill.setColor(Color.WHITE);
        c.drawPath(marker, fill);
    }
}
