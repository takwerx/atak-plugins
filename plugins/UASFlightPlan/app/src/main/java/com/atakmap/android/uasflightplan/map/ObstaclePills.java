package com.atakmap.android.uasflightplan.map;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
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
 * An obstacle's label as a pill: white text on black, rounded fully at both ends.
 * Atmosphere's zone pills, copied forward: the operator wants every takwerx label
 * drawn this way, not in ATAK's square label box.
 *
 * <p>The label and the tower icon are two different things on the map (operator,
 * 2026-10-06: "the tower icon and label are separate things"). The icon stays a
 * feature of its own at the obstacle's top; the pill is a label-only feature at
 * the same point, composed once per distinct text into a PNG at device pixels
 * with clear space below the pill so that, centered on the point, it sits above
 * the icon. A composite of two point styles draws only the first, which is why
 * they are two features and not one.
 */
final class ObstaclePills {

    private static final String TAG = "UASObstacleLayer";

    /** Bump when the drawing changes, or a stale file is served under the same name. */
    private static final int VERSION = 2;
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

    /**
     * The pill for this text, composed once and kept.
     *
     * @param clearPx device pixels of empty space the pill's bottom edge keeps above
     *                the point, so the icon drawn at the point is not covered
     */
    synchronized Pill pill(String text, int clearPx) {
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
                + "_c" + clearPx;
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
        // The ends are half circles, so the text starts half a height in.
        final int w = textW + h;
        final float baseline = (h - textH) / 2f - fm.ascent;
        // The pill at the top of a taller transparent bitmap. Centered on the point,
        // the pill's bottom edge then sits clearPx above it: the bitmap is twice
        // (pill height + clearance) tall.
        final int H = 2 * (h + clearPx);
        final File out = new File(dir, "ob_" + key.replaceAll("[^A-Za-z0-9_]", "")
                + "_" + Integer.toHexString(key.hashCode()) + ".png");
        if (!out.isFile() && !compose(out, text, tp, baseline, w, h, H))
            return null;
        final Pill p = new Pill("file://" + out.getAbsolutePath(), Math.round(w / scale),
                Math.round(H / scale));
        cache.put(key, p);
        return p;
    }

    private boolean compose(File out, String text, Paint tp, float baseline, int w, int h,
            int H) {
        Bitmap bmp = null;
        try {
            bmp = Bitmap.createBitmap(w, H, Bitmap.Config.ARGB_8888);
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
            c.drawText(text, r, baseline, tp);

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
