package com.atakmap.android.lces.ui;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewParent;

/**
 * Slide to confirm: a decision that cannot be taken back is a drag of the thumb all the
 * way across the track, never a tap, so a pocket or a bump does not declare or clear
 * anything (operator, 2026-11-06). Let go short of the end and the thumb springs back.
 *
 * <p>Big enough for a gloved hand: 56dp tall, the thumb a square of that. While the
 * thumb is held, the scroller around it is told to keep its hands off the drag.
 */
public final class SlideConfirm extends View {

    public interface OnConfirmed {
        void onConfirmed();
    }

    /** How far across counts as all the way. */
    private static final float DONE = 0.92f;

    private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint thumb = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint edge = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint arrow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF r = new RectF();
    private final Path chevrons = new Path();

    private String label = "";
    private int color = 0xFFFF5252;
    private OnConfirmed listener;

    /** 0 at the left, 1 at the right. */
    private float progress;
    private boolean dragging;
    private float grabOffset;
    private ValueAnimator back;

    public SlideConfirm(Context context) {
        super(context);
        track.setColor(0xFF1E1E1E);
        edge.setStyle(Paint.Style.STROKE);
        edge.setStrokeWidth(dp(2));
        thumb.setColor(Color.WHITE);
        text.setColor(Color.WHITE);
        text.setTextSize(sp(17));
        text.setTypeface(Typeface.DEFAULT_BOLD);
        text.setTextAlign(Paint.Align.CENTER);
        arrow.setStyle(Paint.Style.STROKE);
        arrow.setStrokeWidth(dp(3));
        arrow.setStrokeCap(Paint.Cap.ROUND);
        arrow.setStrokeJoin(Paint.Join.ROUND);
        setColor(color);
        setMinimumHeight(dp(56));
    }

    public void setLabel(String label) {
        this.label = label == null ? "" : label;
        setContentDescription(this.label);
        invalidate();
    }

    public void setColor(int color) {
        this.color = color;
        edge.setColor(color);
        fill.setColor((color & 0x00FFFFFF) | 0x99000000);
        arrow.setColor(color);
        invalidate();
    }

    public void setOnConfirmed(OnConfirmed l) {
        this.listener = l;
    }

    /** Back to the start, at once: the block is shown again for the next decision. */
    public void reset() {
        if (back != null)
            back.cancel();
        dragging = false;
        progress = 0f;
        invalidate();
    }

    @Override
    protected void onMeasure(int w, int h) {
        setMeasuredDimension(MeasureSpec.getSize(w),
                resolveSize(dp(56), h));
    }

    private float thumbSize() {
        return getHeight();
    }

    private float travel() {
        return Math.max(1f, getWidth() - thumbSize());
    }

    @Override
    protected void onDraw(Canvas c) {
        final float w = getWidth();
        final float h = getHeight();
        final float rad = dp(6);
        final float inset = dp(1);
        r.set(inset, inset, w - inset, h - inset);
        c.drawRoundRect(r, rad, rad, track);
        final float x = progress * travel();
        // What has been slid over fills in the color.
        if (x > 0) {
            r.set(inset, inset, x + thumbSize() - inset, h - inset);
            c.drawRoundRect(r, rad, rad, fill);
        }
        r.set(inset, inset, w - inset, h - inset);
        c.drawRoundRect(r, rad, rad, edge);

        // The words, in the room right of the thumb, fading as the thumb covers them.
        final float textX = thumbSize() + (w - thumbSize()) / 2f;
        text.setAlpha((int) (255 * Math.max(0f, 1f - progress * 1.6f)));
        final Paint.FontMetrics fm = text.getFontMetrics();
        c.drawText(label, textX, h / 2f - (fm.ascent + fm.descent) / 2f, text);

        // The thumb: a white square with two chevrons in the color, pointing the way.
        final float pad = dp(5);
        r.set(x + pad, pad, x + thumbSize() - pad, h - pad);
        c.drawRoundRect(r, rad, rad, thumb);
        final float cx = r.centerX();
        final float cy = r.centerY();
        final float a = r.height() * 0.16f;
        chevrons.reset();
        for (int i = 0; i < 2; i++) {
            final float ox = cx - a * 0.9f + i * a * 1.1f;
            chevrons.moveTo(ox - a / 2f, cy - a);
            chevrons.lineTo(ox + a / 2f, cy);
            chevrons.lineTo(ox - a / 2f, cy + a);
        }
        c.drawPath(chevrons, arrow);
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (!isEnabled())
            return false;
        final float x = e.getX();
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: {
                final float thumbX = progress * travel();
                // Only the thumb starts a slide; a tap on the track does nothing.
                if (x < thumbX - dp(8) || x > thumbX + thumbSize() + dp(8))
                    return false;
                if (back != null)
                    back.cancel();
                dragging = true;
                grabOffset = x - thumbX;
                hold(true);
                return true;
            }
            case MotionEvent.ACTION_MOVE:
                if (!dragging)
                    return false;
                progress = clamp((x - grabOffset) / travel());
                invalidate();
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (!dragging)
                    return false;
                dragging = false;
                hold(false);
                if (e.getActionMasked() == MotionEvent.ACTION_UP && progress >= DONE) {
                    progress = 1f;
                    invalidate();
                    performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS);
                    if (listener != null)
                        listener.onConfirmed();
                } else {
                    springBack();
                }
                return true;
            default:
                return false;
        }
    }

    private void springBack() {
        back = ValueAnimator.ofFloat(progress, 0f);
        back.setDuration(220);
        back.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator a) {
                progress = (Float) a.getAnimatedValue();
                invalidate();
            }
        });
        back.start();
    }

    private void hold(boolean on) {
        final ViewParent p = getParent();
        if (p != null)
            p.requestDisallowInterceptTouchEvent(on);
    }

    private static float clamp(float v) {
        return v < 0f ? 0f : (v > 1f ? 1f : v);
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    private float sp(int v) {
        return v * getResources().getDisplayMetrics().scaledDensity;
    }
}
