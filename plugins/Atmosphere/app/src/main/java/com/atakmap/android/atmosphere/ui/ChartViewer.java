package com.atakmap.android.atmosphere.ui;

import android.app.Dialog;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Matrix;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.util.TypedValue;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ImageView;
import android.widget.TextView;

import com.atakmap.android.atmosphere.compat.MapCompat;

/**
 * A chart on the whole screen, to pinch, drag and double-tap: a GACC's ERC chart is
 * 1,200 pixels of small print, and in the pane it was too small to read (operator,
 * 2026-10-05: "i want to be able to pinch to zoom on that or make it fill the screen
 * ... would rather it fill screen").
 *
 * <p>A dialog on ATAK's context, never the plugin's, which would take ATAK down. Back
 * or Close returns to whatever was under it.
 */
final class ChartViewer {

    private ChartViewer() {
    }

    /**
     * @param caption one line over the chart, e.g. "South Coast (SC08): ERC 48, 92nd percentile"
     * @param button  ATAK's dark button face from the plugin's resources, or null
     */
    static void show(Bitmap chart, String caption, Drawable button) {
        final Context ctx = MapCompat.atakContext();
        if (ctx == null || chart == null || chart.isRecycled())
            return;
        final Dialog d = new Dialog(ctx, android.R.style.Theme_Black_NoTitleBar_Fullscreen);
        // A bar over the chart, never on it: the caption laid over the picture hid the
        // chart's own title (operator, 2026-10-05).
        final LinearLayout page = new LinearLayout(ctx);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setBackgroundColor(0xFF000000);

        final int pad = dp(ctx, 8);
        final LinearLayout bar = new LinearLayout(ctx);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(pad, pad / 2, pad, pad / 2);
        bar.setBackgroundColor(0xFF1A1A1A);

        final LinearLayout words = new LinearLayout(ctx);
        words.setOrientation(LinearLayout.VERTICAL);
        final TextView title = new TextView(ctx);
        title.setText(caption);
        title.setTextColor(0xFFFFFFFF);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        words.addView(title);
        final TextView hint = new TextView(ctx);
        hint.setText("Pinch to zoom, drag to move, double-tap to zoom in or back out");
        hint.setTextColor(0xB3FFFFFF);
        hint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        words.addView(hint);
        bar.addView(words, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        final Button close = new Button(ctx);
        close.setText("Close");
        close.setTextColor(0xFFFFFFFF);
        close.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        close.setAllCaps(false);
        if (button != null)
            close.setBackground(button);
        close.setMinHeight(dp(ctx, 44));
        close.setPadding(dp(ctx, 16), 0, dp(ctx, 16), 0);
        bar.addView(close, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        close.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                d.dismiss();
            }
        });
        page.addView(bar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        final ZoomImageView image = new ZoomImageView(ctx);
        image.setImageBitmap(chart);
        page.addView(image, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        d.setContentView(page);
        d.show();
    }

    private static int dp(Context ctx, int v) {
        return Math.round(v * ctx.getResources().getDisplayMetrics().density);
    }

    /**
     * An image that fills its view and can be pinched, dragged and double-tapped. It
     * never shrinks below filling the view, and never drags its edge past the view's.
     */
    static final class ZoomImageView extends ImageView {

        private static final float MAX_ZOOM = 6f;
        private final Matrix matrix = new Matrix();
        private final float[] values = new float[9];
        private final ScaleGestureDetector scaler;
        private final GestureDetector gestures;
        private float fitScale = 1f;

        ZoomImageView(Context ctx) {
            super(ctx);
            setScaleType(ScaleType.MATRIX);
            scaler = new ScaleGestureDetector(ctx,
                    new ScaleGestureDetector.SimpleOnScaleGestureListener() {
                        @Override
                        public boolean onScale(ScaleGestureDetector g) {
                            zoomBy(g.getScaleFactor(), g.getFocusX(), g.getFocusY());
                            return true;
                        }
                    });
            gestures = new GestureDetector(ctx, new GestureDetector.SimpleOnGestureListener() {
                @Override
                public boolean onDown(MotionEvent e) {
                    return true;
                }

                @Override
                public boolean onScroll(MotionEvent e1, MotionEvent e2, float dx, float dy) {
                    matrix.postTranslate(-dx, -dy);
                    settle();
                    return true;
                }

                @Override
                public boolean onDoubleTap(MotionEvent e) {
                    if (scale() > fitScale * 1.05f)
                        fit();
                    else
                        zoomBy(2.5f, e.getX(), e.getY());
                    return true;
                }
            });
        }

        @Override
        public void setImageBitmap(Bitmap bm) {
            super.setImageBitmap(bm);
            fit();
        }

        @Override
        protected void onSizeChanged(int w, int h, int ow, int oh) {
            super.onSizeChanged(w, h, ow, oh);
            fit();
        }

        @Override
        public boolean onTouchEvent(MotionEvent e) {
            scaler.onTouchEvent(e);
            gestures.onTouchEvent(e);
            return true;
        }

        /** The whole image in view, as large as the view allows, centered. */
        private void fit() {
            final Drawable d = getDrawable();
            final int w = getWidth(), h = getHeight();
            if (d == null || w == 0 || h == 0)
                return;
            final float iw = d.getIntrinsicWidth(), ih = d.getIntrinsicHeight();
            fitScale = Math.min(w / iw, h / ih);
            matrix.reset();
            matrix.postScale(fitScale, fitScale);
            matrix.postTranslate((w - iw * fitScale) / 2f, (h - ih * fitScale) / 2f);
            setImageMatrix(matrix);
        }

        private float scale() {
            matrix.getValues(values);
            return values[Matrix.MSCALE_X];
        }

        private void zoomBy(float factor, float x, float y) {
            final float now = scale();
            final float next = Math.max(fitScale, Math.min(fitScale * MAX_ZOOM, now * factor));
            matrix.postScale(next / now, next / now, x, y);
            settle();
        }

        /** Keep the image over the view: centered where it is smaller, edge to edge where larger. */
        private void settle() {
            final Drawable d = getDrawable();
            if (d == null)
                return;
            final RectF r = new RectF(0, 0, d.getIntrinsicWidth(), d.getIntrinsicHeight());
            matrix.mapRect(r);
            final float w = getWidth(), h = getHeight();
            float dx = 0, dy = 0;
            if (r.width() <= w)
                dx = (w - r.width()) / 2f - r.left;
            else if (r.left > 0)
                dx = -r.left;
            else if (r.right < w)
                dx = w - r.right;
            if (r.height() <= h)
                dy = (h - r.height()) / 2f - r.top;
            else if (r.top > 0)
                dy = -r.top;
            else if (r.bottom < h)
                dy = h - r.bottom;
            matrix.postTranslate(dx, dy);
            setImageMatrix(matrix);
        }
    }
}
