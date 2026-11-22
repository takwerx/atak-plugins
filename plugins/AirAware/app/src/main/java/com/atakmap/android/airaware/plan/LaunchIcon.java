package com.atakmap.android.airaware.plan;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;

import com.atakmap.android.maps.MapTextFormat;
import com.atakmap.android.maps.MapView;
import com.atakmap.android.airaware.plugin.R;
import com.atakmap.coremap.filesystem.FileSystemUtils;
import com.atakmap.coremap.log.Log;

import java.io.File;
import java.io.FileOutputStream;

/**
 * The launch marker's icon: the word in a black pill with white text, above the
 * quadcopter glyph, as one bitmap.
 *
 * <p>ATAK's own marker label is clipped by its label engine (on the XCover it drew
 * "Launch" as "ch"), and nothing a plugin sets stops that. So the label is pixels
 * in the icon, Feature Layer's way: composed at device pixels, handed back with
 * its size in dp and the anchor on the glyph's center, and the marker's own label
 * switched off. Nothing can trim a pixel.
 */
final class LaunchIcon {

    private static final String TAG = "UASLaunch";
    /** Bump when the drawing changes; a stale file would be served under the same name. */
    private static final int VERSION = 1;
    private static final int FILL = 0xE6000000;
    private static final int EDGE = 0x66FFFFFF;
    private static final int TEXT = 0xFFFFFFFF;
    /** Pixels between the pill and the glyph. */
    private static final int GAP = 6;

    /** The composed icon: its file, its size in dp, and the glyph's center in dp. */
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

    private LaunchIcon() {
    }

    /** Composes once per text and text size; null when it cannot be written. */
    static Composed compose(Context plugin, String text) {
        final File dir = FileSystemUtils.getItem("tools/uasflightplan/icons");
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

        Bitmap glyph = null;
        Bitmap bmp = null;
        try {
            glyph = BitmapFactory.decodeResource(plugin.getResources(), R.drawable.ic_launch);
            if (glyph == null)
                return null;
            final int gw = glyph.getWidth(), gh = glyph.getHeight();

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
            // The glyph sits at the bottom, centered; the pill above it. Padding
            // below the glyph equal to the pill and gap keeps the glyph's center at
            // the bitmap's vertical middle, so an anchor at the middle lands on it
            // whatever ATAK does with the size.
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

            c.drawBitmap(glyph, (w - gw) / 2f, ph + GAP, null);

            final String key = text + "_v" + VERSION + "_s" + Math.round(scale * 100)
                    + "_f" + Math.round(textPx * 10);
            final File out = new File(dir, "launch_" + key.replaceAll("[^A-Za-z0-9_]", "")
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
            Log.w(TAG, "launch icon", e);
            return null;
        } finally {
            if (bmp != null)
                bmp.recycle();
            if (glyph != null)
                glyph.recycle();
        }
    }
}
