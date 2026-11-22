package com.atakmap.android.atmosphere.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.View;

/**
 * The station-plot sky cover symbol: a circle filled in oktas (eighths), the way
 * every surface chart draws it. Clear is an open circle, overcast a filled one, and
 * the steps between are the WMO code table 2700 glyphs: 1 okta a bar, 2 a quarter,
 * 3 a quarter and a bar, 4 a half, 5 a half and a bar, 6 three quarters, 7 filled
 * with a clear band. A crew that has read a fire weather plot knows it at a glance.
 */
public class SkyCoverView extends View {

    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint clear = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF box = new RectF();
    private final Path clip = new Path();
    private int oktas = -1;

    public SkyCoverView(Context context) {
        super(context);
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setColor(Color.WHITE);
        fill.setStyle(Paint.Style.FILL);
        fill.setColor(Color.WHITE);
        clear.setStyle(Paint.Style.FILL);
        clear.setColor(Color.TRANSPARENT);
        clear.setXfermode(new android.graphics.PorterDuffXfermode(
                android.graphics.PorterDuff.Mode.CLEAR));
        setLayerType(LAYER_TYPE_SOFTWARE, null);
    }

    /** @param percent sky cover 0 to 100; NaN or negative draws the "missing" glyph */
    public void setPercent(double percent) {
        if (Double.isNaN(percent) || percent < 0) {
            oktas = -1;
        } else {
            oktas = (int) Math.round(Math.min(100, percent) / 12.5);
        }
        invalidate();
    }

    /** Oktas from a percentage, 0 to 8. */
    public static int oktas(double percent) {
        if (Double.isNaN(percent) || percent < 0)
            return -1;
        return (int) Math.round(Math.min(100, percent) / 12.5);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        final float w = getWidth(), h = getHeight();
        final float d = Math.min(w, h);
        final float lineW = Math.max(2f, d / 14f);
        stroke.setStrokeWidth(lineW);
        final float r = d / 2f - lineW;
        final float cx = w / 2f, cy = h / 2f;
        box.set(cx - r, cy - r, cx + r, cy + r);

        if (oktas < 0) {
            // missing: a circle with a diagonal, not an empty circle that would read as clear
            canvas.drawOval(box, stroke);
            canvas.drawLine(cx - r * 0.7f, cy + r * 0.7f, cx + r * 0.7f, cy - r * 0.7f, stroke);
            return;
        }
        switch (oktas) {
            case 0:
                break;
            case 1:
                canvas.drawLine(cx, cy - r, cx, cy + r, stroke);
                break;
            case 2:
                canvas.drawArc(box, -90, 90, true, fill);
                break;
            case 3:
                canvas.drawArc(box, -90, 90, true, fill);
                canvas.drawLine(cx, cy - r, cx, cy + r, stroke);
                break;
            case 4:
                canvas.drawArc(box, -90, 180, true, fill);
                break;
            case 5:
                canvas.drawArc(box, -90, 180, true, fill);
                canvas.drawLine(cx - r, cy, cx, cy, stroke);
                break;
            case 6:
                canvas.drawArc(box, -90, 270, true, fill);
                break;
            case 7:
                canvas.drawOval(box, fill);
                clip.reset();
                clip.addOval(box, Path.Direction.CW);
                canvas.save();
                canvas.clipPath(clip);
                canvas.drawRect(cx - lineW * 0.9f, cy - r, cx + lineW * 0.9f, cy + r, clear);
                canvas.restore();
                break;
            default:
                canvas.drawOval(box, fill);
                break;
        }
        canvas.drawOval(box, stroke);
    }
}
