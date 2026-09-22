package com.atakmap.android.atmosphere.overlay;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

import java.util.Locale;

/**
 * What the particle colors mean: the speed bands as a bar, with the boundary speed
 * under each join and the unit at the end. The colors and the boundaries come from
 * {@link WindView}, so the legend cannot drift from what is on the map.
 *
 * <p>The bands are categorical, not a gradient, so the segments are equal width and
 * the numbers sit under the joins. A segment drawn proportional to its speed range
 * would give the calm end a sliver and say nothing useful.
 */
public final class WindScaleView extends View {

    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF box = new RectF();
    private final float density;

    /** Band boundaries in the unit being shown, and that unit's name. */
    private float[] breaks = WindView.BAND_MAX_MS;
    private String unit = "m/s";

    public WindScaleView(Context context) {
        this(context, null);
    }

    public WindScaleView(Context context, AttributeSet attrs) {
        super(context, attrs);
        density = context.getResources().getDisplayMetrics().density;
        text.setColor(0xFFFFFFFF);
        text.setAlpha(190);
        text.setTextSize(dp(11));
        text.setTextAlign(Paint.Align.CENTER);
    }

    /**
     * @param breaks the band boundaries already converted to the unit shown, one fewer
     *               than there are bands
     * @param unit   what to print after the last number
     */
    public void setScale(float[] breaks, String unit) {
        this.breaks = breaks;
        this.unit = unit;
        invalidate();
    }

    private float dp(float v) {
        return v * density;
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        setMeasuredDimension(MeasureSpec.getSize(widthSpec), Math.round(dp(30)));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        final int bands = WindView.BAND_COLORS.length;
        final float w = getWidth(), barH = dp(11);
        if (w <= 0)
            return;
        final float seg = w / bands;
        for (int i = 0; i < bands; i++) {
            fill.setColor(WindView.BAND_COLORS[i]);
            box.set(i * seg, 0, (i + 1) * seg, barH);
            // Round only the outer ends, so the bar reads as one scale.
            if (i == 0 || i == bands - 1) {
                final float r = barH / 2;
                canvas.drawRoundRect(box, r, r, fill);
                box.set(i == 0 ? r : i * seg, 0, i == 0 ? (i + 1) * seg : (i + 1) * seg - r, barH);
            }
            canvas.drawRect(box, fill);
        }
        // A number under every join, and the unit after the last one.
        final float baseline = barH + dp(13);
        for (int i = 0; i < breaks.length && i < bands - 1; i++) {
            final String label = format(breaks[i]) + (i == breaks.length - 1 ? " " + unit : "");
            canvas.drawText(label, (i + 1) * seg, baseline, text);
        }
    }

    /** How many bands the particles use. */
    public static int bandCount() {
        return WindView.BAND_COLORS.length;
    }

    /** The upper edge of band {@code i} in m/s, for a caller converting units. */
    public static float bandEdgeMs(int i) {
        return WindView.BAND_MAX_MS[i];
    }

    private static String format(float v) {
        return v >= 10 || v == Math.round(v)
                ? String.valueOf(Math.round(v))
                : String.format(Locale.US, "%.1f", v);
    }
}
