package com.atakmap.android.camdepot.map;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;

import com.atakmap.android.maps.MapTextFormat;
import com.atakmap.android.maps.MapView;
import com.atakmap.coremap.filesystem.FileSystemUtils;
import com.atakmap.coremap.log.Log;

import java.io.File;
import java.io.FileOutputStream;

/**
 * The Suggest point's icon: the word in a black pill above a cyan target, as one
 * bitmap, AirAware's launch marker's way.
 *
 * <p>A plain drawable is drawn at its pixel size and ATAK's own label at ATAK's
 * size, both small on a phone, and the operator wanted the point to stand out
 * (2026-10-11). So it is composed at device pixels: the target scales with the
 * phone's display scaling, the word uses ATAK's own label font, and the size goes
 * back in dp with the anchor on the target's center. The marker's own label is
 * switched off so nothing draws a second, trimmed one.
 */
final class SuggestIcon {

    private static final String TAG = "CamDepotSuggest";
    /** Bump when the drawing changes; a stale file would be served under the same name. */
    private static final int VERSION = 1;
    private static final int CYAN = 0xFF00E5FF;
    private static final int FILL = 0xE6000000;
    private static final int EDGE = 0x66FFFFFF;
    private static final int TEXT = 0xFFFFFFFF;
    private static final int GAP = 6;

    static final class Composed {
        final String uri;
        final int widthDp, heightDp;
        final int anchorXDp, anchorYDp;

        Composed(String uri, int widthDp, int heightDp, int anchorXDp, int anchorYDp) {
            this.uri = uri;
            this.widthDp = widthDp;
            this.heightDp = heightDp;
            this.anchorXDp = anchorXDp;
            this.anchorYDp = anchorYDp;
        }
    }

    private SuggestIcon() {
    }

    /** Composes once per text and scale; null when it cannot be written. */
    static Composed compose(Context plugin, String text) {
        final File dir = FileSystemUtils.getItem("tools/camdepot/icons");
        if (dir == null)
            return null;
        if (!dir.isDirectory() && !dir.mkdirs())
            return null;
        final float scale = Math.max(1f,
                gov.tak.api.commons.graphics.DisplaySettings.getRelativeScaling());
        final MapTextFormat tf = MapView.getDefaultTextFormat();
        float textPx = tf == null ? 0f : tf.getDensityAdjustedFontSize();
        if (textPx <= 0f)
            textPx = 14f * scale;
        // The target: 3.5 text heights across, so it reads at the same weight as
        // the word above it on any phone.
        final int gw = Math.round(textPx * 3.5f);
        final int gh = gw;

        Bitmap bmp = null;
        try {
            final Paint tp = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
            tp.setTypeface(tf == null ? null : tf.getTypeface());
            tp.setTextSize(textPx);
            tp.setFakeBoldText(true);
            tp.setColor(TEXT);
            final int textW = (int) Math.ceil(tp.measureText(text));
            final Paint.FontMetricsInt fm = tp.getFontMetricsInt();
            final int textH = fm.descent - fm.ascent;
            final int padY = Math.round(textPx * 0.28f);
            final int ph = textH + 2 * padY;
            final int pw = textW + ph;

            final int w = Math.max(pw, gw);
            final int h = 2 * (ph + GAP) + gh;
            bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            final Canvas c = new Canvas(bmp);

            final float px0 = (w - pw) / 2f;
            final RectF body = new RectF(px0 + 0.5f, 0.5f, px0 + pw - 0.5f, ph - 0.5f);
            final float r = ph / 2f;
            final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
            fill.setColor(FILL);
            c.drawRoundRect(body, r, r, fill);
            final Paint edge = new Paint(Paint.ANTI_ALIAS_FLAG);
            edge.setStyle(Paint.Style.STROKE);
            edge.setStrokeWidth(1f);
            edge.setColor(EDGE);
            c.drawRoundRect(body, r - 0.5f, r - 0.5f, edge);
            c.drawText(text, px0 + r, (ph - textH) / 2f - fm.ascent, tp);

            // The target: a glow, a thick cyan ring, crosshair arms, a white center.
            final float cx = w / 2f, cy = ph + GAP + gh / 2f;
            final float R = gw / 2f;
            final Paint glow = new Paint(Paint.ANTI_ALIAS_FLAG);
            glow.setColor(0x6000E5FF);
            c.drawCircle(cx, cy, R, glow);
            final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
            ring.setStyle(Paint.Style.STROKE);
            ring.setStrokeWidth(R * 0.22f);
            ring.setColor(CYAN);
            c.drawCircle(cx, cy, R * 0.72f, ring);
            final Paint arm = new Paint(Paint.ANTI_ALIAS_FLAG);
            arm.setStrokeWidth(R * 0.12f);
            arm.setColor(CYAN);
            arm.setStrokeCap(Paint.Cap.ROUND);
            c.drawLine(cx, cy - R * 0.98f, cx, cy - R * 0.3f, arm);
            c.drawLine(cx, cy + R * 0.3f, cx, cy + R * 0.98f, arm);
            c.drawLine(cx - R * 0.98f, cy, cx - R * 0.3f, cy, arm);
            c.drawLine(cx + R * 0.3f, cy, cx + R * 0.98f, cy, arm);
            final Paint dot = new Paint(Paint.ANTI_ALIAS_FLAG);
            dot.setColor(TEXT);
            c.drawCircle(cx, cy, R * 0.14f, dot);

            final String key = text + "_v" + VERSION + "_s" + Math.round(scale * 100)
                    + "_f" + Math.round(textPx * 10);
            final File out = new File(dir, "suggest_" + key.replaceAll("[^A-Za-z0-9_]", "")
                    + ".png");
            if (!out.isFile()) {
                final File tmp = new File(out.getPath() + ".tmp");
                final FileOutputStream o = new FileOutputStream(tmp);
                try {
                    bmp.compress(Bitmap.CompressFormat.PNG, 100, o);
                } finally {
                    o.close();
                }
                //noinspection ResultOfMethodCallIgnored
                tmp.renameTo(out);
            }
            if (!out.isFile())
                return null;
            return new Composed("file://" + out.getAbsolutePath(),
                    Math.round(w / scale), Math.round(h / scale),
                    Math.round(w / 2f / scale), Math.round((ph + GAP + gh / 2f) / scale));
        } catch (Exception e) {
            Log.w(TAG, "suggest icon", e);
            return null;
        } finally {
            if (bmp != null)
                bmp.recycle();
        }
    }
}
