package com.atakmap.android.atmosphere.overlay;

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
import java.util.HashMap;
import java.util.Map;

/**
 * A fire zone's number as a pill: white text on black, rounded fully at both ends.
 *
 * <p>ATAK's own label style draws a square-cornered box, and the operator asked for
 * a true pill (2026-09-28: "i prefer the black pill box with white font can the pill
 * boxes look modern like a true pill like rounded on both ends?"). So the label is
 * drawn into a bitmap at device pixels, the way the station and storm labels are,
 * and placed as the icon of a label-only point.
 */
final class ZonePills {

    private static final String TAG = "AtmosphereZoneLayer";

    /** Bump when the drawing changes, or a stale file is served under the same name. */
    private static final int VERSION = 1;
    private static final int FILL = 0xE6000000;
    private static final int EDGE = 0x66FFFFFF;
    private static final int TEXT = 0xFFFFFFFF;

    /**
     * A composed pill: its file and the size to ask the renderer for. The bitmap is
     * drawn at device pixels and the renderer scales an icon's size by ATAK's own dp
     * scaling, so the size is handed over with that scaling divided out, the way the
     * station labels do it; passed as pixels, the pill drew twice its size.
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

    ZonePills() {
        dir = FileSystemUtils.getItem("tools/atmosphere/zone-pills");
        if (dir != null && !dir.isDirectory())
            //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
    }

    /** The pill for this text, composed once and kept; null when it cannot be written. */
    synchronized Pill pill(String text) {
        if (text == null || text.isEmpty() || dir == null)
            return null;
        final float scale = Math.max(1f,
                gov.tak.api.commons.graphics.DisplaySettings.getRelativeScaling());
        final MapTextFormat tf = MapView.getDefaultTextFormat();
        // The map's own text size; before it exists, ATAK's default of 14 scaled.
        float textPx = tf == null ? 0f : tf.getDensityAdjustedFontSize();
        if (textPx <= 0f)
            textPx = 14f * scale;
        final String key = text + "_v" + VERSION + "_f" + Math.round(textPx * 10);
        final Pill hit = cache.get(key);
        if (hit != null)
            return hit;

        final Paint tp = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
        tp.setTypeface(tf == null ? null : tf.getTypeface());
        tp.setTextSize(textPx);
        tp.setFakeBoldText(true);
        tp.setColor(TEXT);
        final int textW = (int) Math.ceil(tp.measureText(text));
        // Sized and centered on the glyphs' own bounds, not the font's ascent and
        // descent: zone numbers are capitals and digits, with no descender, and on
        // the font's metrics they sit high in the pill.
        final android.graphics.Rect b = new android.graphics.Rect();
        tp.getTextBounds(text, 0, text.length(), b);
        final int padY = Math.round(textPx * 0.42f);
        final int h = b.height() + 2 * padY;
        // The ends are half circles, so the text starts half a height in.
        final int w = textW + h;
        final float baseline = h / 2f - (b.top + b.bottom) / 2f;
        final File out = new File(dir, "zone_" + key.replaceAll("[^A-Za-z0-9_]", "") + ".png");
        if (!out.isFile() && !compose(out, text, tp, baseline, w, h))
            return null;
        final Pill p = new Pill("file://" + out.getAbsolutePath(), Math.round(w / scale),
                Math.round(h / scale));
        cache.put(key, p);
        return p;
    }

    private boolean compose(File out, String text, Paint tp, float baseline, int w, int h) {
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
            Log.w(TAG, "zone pill " + text, e);
            return false;
        } finally {
            if (bmp != null)
                bmp.recycle();
        }
    }
}
