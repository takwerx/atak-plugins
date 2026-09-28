package com.atakmap.android.signaldf.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.util.AttributeSet;
import android.view.View;

import com.atakmap.android.signaldf.data.ArrayCalc;
import com.atakmap.android.signaldf.data.ArrayCalc.Geometry;

/**
 * A top-down plan of the antenna array: which antenna goes where, and which way
 * the array faces.
 *
 * <p><b>It deliberately carries no measurements.</b> Every element sits the
 * same distance out and the arms are always 360/N apart, so a radius drawn on
 * the picture and a neighbor-to-neighbor distance drawn between two dots add
 * nothing the table underneath does not already say -- and they cost a great
 * deal, because the only places those labels fit are on top of each other and
 * on top of the drawing. Three rounds of this screen went on finding somewhere
 * for them to live before the operator pointed out they did not need to. The
 * numbers are in the table; the picture answers the one question a table
 * cannot, which is which way round the thing goes.
 *
 * <p>Element 0 is filled green at the top and labeled as the forward
 * direction, because the workbook's own note reads "ANT 0 points to the
 * forward direction of the array", and every bearing the radio reports is
 * measured from it. The rest run clockwise, so element n sits at n * (360/N)
 * degrees and the plan reads as a compass rose.
 *
 * <p>Two earlier mistakes worth not repeating. The workbook puts element 0 on
 * the +x axis, so drawing its coordinates straight put forward out to the
 * right of a top-down plan while the caption said forward; the drawing is now
 * turned a quarter turn. And every label was drawn a fixed distance above its
 * element, which on the left of the ring put it inside the ring; labels are
 * now placed radially outward, the one direction always clear of the drawing.
 *
 * <p>Deliberately not a chart library. ATAK bundles achartengine and Signal DF
 * will want it for the DoA spectrum, but this is a handful of circles and it
 * has to stay legible at the size of a pane on a phone.
 */
public class ArrayPlanView extends View {

    private static final int COLOR_ELEMENT = Color.rgb(0x00, 0xE5, 0xFF);
    private static final int COLOR_FORWARD = Color.rgb(0x4C, 0xD9, 0x64);
    private static final int COLOR_RULE = Color.rgb(0x90, 0x90, 0x90);
    private static final int COLOR_BAD = Color.rgb(0xFF, 0x5B, 0x5B);

    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint thin = new Paint(Paint.ANTI_ALIAS_FLAG);
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
        stroke.setStrokeWidth(dp(2.5f));
        thin.setStyle(Paint.Style.STROKE);
        thin.setStrokeWidth(dp(1.5f));
        dash.setStyle(Paint.Style.STROKE);
        dash.setStrokeWidth(dp(1));
        dash.setPathEffect(new android.graphics.DashPathEffect(
                new float[] { dp(6), dp(5) }, 0));
        text.setColor(Color.WHITE);
        text.setTextSize(dp(13));
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
        // Tall enough that the ring is a drawing rather than a postage stamp,
        // short enough that the verdict underneath it stays above the fold on a
        // phone. A square would be prettier and is wrong on both counts: the
        // first build of this screen was 260dp and pushed everything off, the
        // second was 100dp and crushed the labels into each other.
        // Capped, because the pane this lives in is a landscape half-screen
        // whose scrolling viewport is only a few hundred pixels tall: a plan
        // taller than that can never be seen whole, however far it is
        // scrolled. 185dp cut elements 2 and 3 off the bottom on an S10.
        int h = (int) Math.min(w * 0.55f, dp(155));
        setMeasuredDimension(w, Math.max(h, (int) dp(115)));
    }

    /** Draw {@code s} centered on a point rather than sitting on a baseline. */
    private void centered(Canvas c, String s, float x, float y) {
        c.drawText(s, x, y - (text.ascent() + text.descent()) / 2f, text);
    }

    @Override
    protected void onDraw(Canvas c) {
        double[][] p = ArrayCalc.positions(geometry, elements, sizeCm);
        int elemColor = usable ? COLOR_ELEMENT : COLOR_BAD;

        // Room for a label outside the ring on every side, measured rather
        // than guessed: the dot, the gap, and the text's own height. A round
        // number here clipped the top off "0 = forward" against the button
        // above the drawing, which is the one label that reaches furthest.
        float r = dp(7);
        float pad = r + dp(13) + Math.abs(text.ascent()) + dp(2);
        float cx = getWidth() / 2f;
        float cy = getHeight() / 2f;
        float scale;
        float originX, originY;
        float[] sx = new float[p.length];
        float[] sy = new float[p.length];

        if (geometry == Geometry.CIRCULAR) {
            // Draw in the array's OWN frame: the origin is the center of the
            // circle, which is what the radius is measured from. Centering on
            // the elements' bounding box instead put the circle and the
            // elements in two different frames and pushed element 0 off screen.
            float half = Math.min(getWidth(), getHeight()) / 2f - pad;
            scale = (float) (half / Math.max(sizeCm, 0.001));
            originX = cx;
            originY = cy;

            for (int i = 0; i < p.length; i++) {
                // A quarter turn counterclockwise, then y flipped for the canvas.
                // The workbook's frame is y-up with element 0 on +x; a canvas
                // is y-down; and a plan wants forward at the top. Both steps
                // collapse into this: screen x from -y, screen y from -x.
                sx[i] = originX - (float) (p[i][1] * scale);
                sy[i] = originY - (float) (p[i][0] * scale);
            }

            dash.setColor(COLOR_RULE);
            c.drawCircle(originX, originY, (float) sizeCm * scale, dash);

            // An arm to every element, the way the vendor's template is a hub
            // with arms, and the radius called out on the one furthest from
            // the spacing figure so the two measurements never share a corner.
            thin.setColor(COLOR_RULE);
            for (int i = 0; i < p.length; i++)
                c.drawLine(originX, originY, sx[i], sy[i], thin);
            fill.setColor(COLOR_RULE);
            c.drawCircle(originX, originY, dp(2.5f), fill);

        } else {
            // A line: fit its length across the width, and sit it on the middle.
            scale = (float) ((getWidth() - 2 * pad) / Math.max(sizeCm, 0.001));
            originX = cx - (float) sizeCm * scale / 2f;
            originY = cy;
            for (int i = 0; i < p.length; i++) {
                sx[i] = originX + (float) (p[i][0] * scale);
                sy[i] = originY;
            }
            dash.setColor(COLOR_RULE);
            c.drawLine(sx[0], originY, sx[p.length - 1], originY, dash);
        }


        for (int i = 0; i < p.length; i++) {
            boolean forward = i == 0 && geometry == Geometry.CIRCULAR;
            fill.setColor(forward ? COLOR_FORWARD : elemColor);
            c.drawCircle(sx[i], sy[i], r, fill);

            // Radially outward is the one direction that is always clear of
            // the ring, the arms and the chord.
            float ox = sx[i] - originX, oy = sy[i] - originY;
            float len = (float) Math.hypot(ox, oy);
            float lx, ly;
            if (geometry == Geometry.CIRCULAR && len > 1) {
                lx = sx[i] + ox / len * (r + dp(13));
                ly = sy[i] + oy / len * (r + dp(13));
            } else {
                lx = sx[i];
                ly = sy[i] - r - dp(11);
            }
            text.setColor(forward ? COLOR_FORWARD : Color.WHITE);
            centered(c, forward ? "0 = forward" : String.valueOf(i), lx, ly);
        }
    }

}
