package com.atakmap.android.signaldf.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.View;

import com.atakmap.android.signaldf.data.ArrayCalc;
import com.atakmap.android.signaldf.data.ArrayCalc.Geometry;

import java.util.Locale;

/**
 * A top-down plan of the antenna array: where each element goes, to scale, with
 * the measurement somebody has to take with a tape on it.
 *
 * <p>This is the half of the vendor's spreadsheet that does not survive being
 * read as a table of numbers. "Radius 30.7 cm, spacing 36.1 cm" is two numbers
 * that are easy to mix up, and mixing them up builds the wrong array; a picture
 * with the elements in their places and the spacing drawn between two of them
 * is not.
 *
 * <p>Element 0 is drawn filled and labelled as the forward direction, because
 * the array's zero is what every bearing the radio reports is measured from.
 * The rest run clockwise, matching the workbook and a compass.
 *
 * <p>Deliberately not a chart library. ATAK bundles achartengine and Signal DF
 * will want it for the DoA spectrum, but this is a handful of circles and a
 * line, and it has to stay legible at the size of a pane on a phone.
 */
public class ArrayPlanView extends View {

    private static final int COLOR_ELEMENT = Color.rgb(0x00, 0xE5, 0xFF);
    private static final int COLOR_FORWARD = Color.rgb(0x4C, 0xD9, 0x64);
    private static final int COLOR_RULE = Color.rgb(0xB0, 0xB0, 0xB0);
    private static final int COLOR_BAD = Color.rgb(0xFF, 0x5B, 0x5B);

    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dash = new Paint(Paint.ANTI_ALIAS_FLAG);

    private Geometry geometry = Geometry.CIRCULAR;
    private int elements = 5;
    private double sizeCm = 30.0;
    private double spacingCm = 36.0;
    private boolean usable = true;

    public ArrayPlanView(Context c) {
        super(c);
        init();
    }

    public ArrayPlanView(Context c, AttributeSet a) {
        super(c, a);
        init();
    }

    private void init() {
        fill.setStyle(Paint.Style.FILL);
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeWidth(dp(2));
        dash.setStyle(Paint.Style.STROKE);
        dash.setStrokeWidth(dp(1));
        dash.setPathEffect(new android.graphics.DashPathEffect(
                new float[] { dp(6), dp(5) }, 0));
        text.setColor(Color.WHITE);
        text.setTextSize(dp(12));
        text.setTextAlign(Paint.Align.CENTER);
    }

    private float dp(float v) {
        return v * getResources().getDisplayMetrics().density;
    }

    /** Set what to draw. {@code sizeCm} is a radius, or a total length. */
    public void set(Geometry g, int elements, double sizeCm, double spacingCm, boolean usable) {
        this.geometry = g;
        this.elements = Math.max(2, elements);
        this.sizeCm = sizeCm;
        this.spacingCm = spacingCm;
        this.usable = usable;
        invalidate();
    }

    @Override
    protected void onMeasure(int wSpec, int hSpec) {
        int w = MeasureSpec.getSize(wSpec);
        // Deliberately short. A square would be prettier and is wrong: on a
        // phone pane it pushes the verdict and the numbers below the fold, and
        // the first build of this screen showed only the top of the circle with
        // element 0 off screen. The plan scales itself to whatever height it
        // gets, so a short one is a small drawing, not a cropped one.
        int h = (int) dp(100);
        setMeasuredDimension(w, h);
    }

    @Override
    protected void onDraw(Canvas c) {
        double[][] p = ArrayCalc.positions(geometry, elements, sizeCm);

        float pad = dp(22);
        float cx = getWidth() / 2f;
        float cy = getHeight() / 2f;
        float scale;
        float originX, originY;

        if (geometry == Geometry.CIRCULAR) {
            // Draw in the array's OWN frame: the origin is the center of the
            // circle, which is what the radius is measured from. Centring on
            // the elements' bounding box instead put the circle and the
            // elements in two different frames and pushed element 0 off screen.
            float half = Math.min(getWidth(), getHeight()) / 2f - pad;
            scale = (float) (half / Math.max(sizeCm, 0.001));
            originX = cx;
            originY = cy;
            dash.setColor(COLOR_RULE);
            c.drawCircle(originX, originY, (float) sizeCm * scale, dash);
        } else {
            // A line: fit its length across the width, and sit it on the middle.
            scale = (float) ((getWidth() - 2 * pad) / Math.max(sizeCm, 0.001));
            originX = cx - (float) sizeCm * scale / 2f;
            originY = cy;
            dash.setColor(COLOR_RULE);
            c.drawLine(originX, originY, originX + (float) sizeCm * scale, originY, dash);
        }

        float r = dp(7);
        float[] sx = new float[p.length];
        float[] sy = new float[p.length];
        for (int i = 0; i < p.length; i++) {
            sx[i] = originX + (float) p[i][0] * scale;
            sy[i] = originY + (float) p[i][1] * scale;
        }

        // The spacing first, so the elements sit on top of its line.
        if (p.length >= 2) {
            stroke.setColor(usable ? COLOR_ELEMENT : COLOR_BAD);
            c.drawLine(sx[0], sy[0], sx[1], sy[1], stroke);
            text.setColor(usable ? COLOR_ELEMENT : COLOR_BAD);
            c.drawText(String.format(Locale.US, "%.1f cm", spacingCm),
                    (sx[0] + sx[1]) / 2f, (sy[0] + sy[1]) / 2f - dp(7), text);
        }

        for (int i = 0; i < p.length; i++) {
            fill.setColor(i == 0 ? COLOR_FORWARD : (usable ? COLOR_ELEMENT : COLOR_BAD));
            c.drawCircle(sx[i], sy[i], r, fill);
            text.setColor(Color.WHITE);
            c.drawText(String.valueOf(i), sx[i], sy[i] - r - dp(5), text);
        }

        text.setColor(COLOR_FORWARD);
        c.drawText("0 = forward", cx, getHeight() - dp(5), text);
    }

}
