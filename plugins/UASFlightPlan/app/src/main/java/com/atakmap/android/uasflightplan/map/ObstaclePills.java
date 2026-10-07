package com.atakmap.android.uasflightplan.map;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;

import com.atakmap.android.maps.MapTextFormat;
import com.atakmap.android.maps.MapView;
import com.atakmap.coremap.filesystem.FileSystemUtils;
import com.atakmap.coremap.log.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.util.HashMap;
import java.util.Map;

/**
 * An obstacle's label as a pill: a tower glyph in the ceiling color, then the
 * words in white, on black, rounded fully at both ends. Atmosphere's zone pills,
 * copied forward: the operator wants every takwerx label drawn this way, not in
 * ATAK's square label box.
 *
 * <p>Drawn once per distinct text and color into a PNG at device pixels, and
 * placed as the icon of the point at the obstacle's top; the feature's own label
 * is suppressed so the name is not drawn twice. A composite of two point styles
 * draws only the first, which is why the glyph and the words are one picture.
 */
final class ObstaclePills {

    private static final String TAG = "UASObstacleLayer";

    /** Bump when the drawing changes, or a stale file is served under the same name. */
    private static final int VERSION = 1;
    private static final int FILL = 0xE6000000;
    private static final int EDGE = 0x66FFFFFF;
    private static final int TEXT = 0xFFFFFFFF;

    /**
     * A composed pill: its file and the size to ask the renderer for. The bitmap is
     * drawn at device pixels and the renderer scales an icon's size by ATAK's own dp
     * scaling, so the size is handed over with that scaling divided out; passed as
     * pixels, a pill draws twice its size.
     */
    static final class Pill {
        final String uri;
        final int width, height;

        Pill(String uri, int width, int height) {
            this.uri = uri;
            this.width = width;
            this.height = height;
        }
    }

    private final File dir;
    private final Map<String, Pill> cache = new HashMap<>();

    ObstaclePills() {
        final File root = FileSystemUtils.getItem("tools/uasflightplan/icons");
        if (root != null && !root.isDirectory() && !root.mkdirs())
            Log.w(TAG, "could not create " + root);
        dir = root;
        if (dir != null) {
            // Not pictures for the gallery.
            final File nomedia = new File(dir, ".nomedia");
            try {
                if (!nomedia.exists())
                    //noinspection ResultOfMethodCallIgnored
                    nomedia.createNewFile();
            } catch (Exception e) {
                Log.w(TAG, "no .nomedia", e);
            }
        }
    }

    /** The pill for this text with the glyph in this color, composed once and kept. */
    synchronized Pill pill(String text, int glyphColor) {
        if (text == null || text.isEmpty() || dir == null)
            return null;
        final float scale = Math.max(1f,
                gov.tak.api.commons.graphics.DisplaySettings.getRelativeScaling());
        final MapTextFormat tf = MapView.getDefaultTextFormat();
        // The map's own text size; before it exists, ATAK's default of 14 scaled.
        float textPx = tf == null ? 0f : tf.getDensityAdjustedFontSize();
        if (textPx <= 0f)
            textPx = 14f * scale;
        final String key = text + "_v" + VERSION + "_f" + Math.round(textPx * 10)
                + "_" + Integer.toHexString(glyphColor);
        final Pill hit = cache.get(key);
        if (hit != null)
            return hit;

        final Paint tp = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
        tp.setTypeface(tf == null ? null : tf.getTypeface());
        tp.setTextSize(textPx);
        tp.setFakeBoldText(true);
        tp.setColor(TEXT);
        final int textW = (int) Math.ceil(tp.measureText(text));
        // Sized on the font's metrics, not the glyph bounds: these labels carry
        // lowercase with descenders ("Building"), and a pill cut to the capitals
        // would clip them.
        final Paint.FontMetricsInt fm = tp.getFontMetricsInt();
        final int textH = fm.descent - fm.ascent;
        final int padY = Math.round(textPx * 0.28f);
        final int h = textH + 2 * padY;
        final int glyphW = Math.round(h * 0.62f);
        final int gap = Math.round(textPx * 0.3f);
        // The ends are half circles, so the content starts half a height in.
        final int w = h / 2 + glyphW + gap + textW + h / 2;
        final float baseline = (h - textH) / 2f - fm.ascent;
        final File out = new File(dir, "ob_" + key.replaceAll("[^A-Za-z0-9_]", "")
                + "_" + Integer.toHexString(key.hashCode()) + ".png");
        if (!out.isFile()
                && !compose(out, text, tp, baseline, w, h, glyphW, gap, glyphColor))
            return null;
        final Pill p = new Pill("file://" + out.getAbsolutePath(), Math.round(w / scale),
                Math.round(h / scale));
        cache.put(key, p);
        return p;
    }

    private boolean compose(File out, String text, Paint tp, float baseline, int w, int h,
            int glyphW, int gap, int glyphColor) {
        Bitmap bmp = null;
        try {
            bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            final Canvas c = new Canvas(bmp);
            final float r = h / 2f;
            final RectF body = new RectF(0.5f, 0.5f, w - 0.5f, h - 0.5f);
            final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
            fill.setColor(FILL);
            c.drawRoundRect(body, r, r, fill);
            final Paint edge = new Paint(Paint.ANTI_ALIAS_FLAG);
            edge.setStyle(Paint.Style.STROKE);
            edge.setStrokeWidth(1f);
            edge.setColor(EDGE);
            c.drawRoundRect(body, r - 0.5f, r - 0.5f, edge);

            // The tower glyph: a triangle with a crossbar and a light on top, in the
            // ceiling color, the same shape as the map key's icon.
            final float gx = r;
            final float gh = h * 0.66f;
            final float gy = (h - gh) / 2f;
            final Paint g = new Paint(Paint.ANTI_ALIAS_FLAG);
            g.setColor(glyphColor);
            g.setStyle(Paint.Style.STROKE);
            g.setStrokeWidth(Math.max(1.5f, h * 0.09f));
            g.setStrokeJoin(Paint.Join.ROUND);
            final Path tri = new Path();
            tri.moveTo(gx + glyphW / 2f, gy + gh * 0.12f);
            tri.lineTo(gx + glyphW, gy + gh);
            tri.lineTo(gx, gy + gh);
            tri.close();
            c.drawPath(tri, g);
            c.drawLine(gx + glyphW * 0.22f, gy + gh * 0.62f,
                    gx + glyphW * 0.78f, gy + gh * 0.62f, g);
            g.setStyle(Paint.Style.FILL);
            c.drawCircle(gx + glyphW / 2f, gy + gh * 0.1f, Math.max(1.5f, h * 0.08f), g);

            c.drawText(text, r + glyphW + gap, baseline, tp);

            final File tmp = new File(out.getPath() + ".tmp");
            final FileOutputStream o = new FileOutputStream(tmp);
            try {
                bmp.compress(Bitmap.CompressFormat.PNG, 100, o);
            } finally {
                o.close();
            }
            //noinspection ResultOfMethodCallIgnored
            tmp.renameTo(out);
            return out.isFile();
        } catch (Exception e) {
            Log.w(TAG, "obstacle pill " + text, e);
            return false;
        } finally {
            if (bmp != null)
                bmp.recycle();
        }
    }
}
