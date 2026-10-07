
package com.atakmap.android.atmosphere.overlay;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.Rect;
import android.graphics.RectF;

import com.atakmap.android.atmosphere.plugin.R;
import com.atakmap.android.maps.MapTextFormat;
import com.atakmap.android.maps.MapView;
import com.atakmap.coremap.log.Log;
import com.atakmap.coremap.maps.assets.Icon;

import java.io.File;
import java.io.FileOutputStream;
import java.util.HashMap;
import java.util.Map;

/**
 * Storm markers whose labels cannot be trimmed, because they are pixels.
 *
 * <p>ATAK's label engine trims a marker's title freely and there is no priority a
 * plugin can set; the operator saw it immediately ("labeling is not looking good like
 * cut off", 2026-09-23). Feature Layer hit the same wall on its DART callsigns and the
 * answer there is the answer here: draw the text <em>into</em> the marker bitmap, at
 * the map's own text format and size, and tell the marker never to draw a label of its
 * own. Nothing can then trim it. This is that code carried forward, not re-derived.
 *
 * <p>The one change: Feature Layer tints its symbol through {@link Icon.Builder}, which
 * works because its label is drawn by ATAK. Here the label is inside the same bitmap,
 * so a tint on the Icon would color the text too. The category color is baked onto the
 * cyclone as it is composed, and the Icon asks for no tint at all.
 *
 * <p>What it costs, the same as there: overlapping storms do not deconflict, ATAK's
 * global label switch does not hide these, and the touch target is the pill's width.
 */
final class StormIcons {

    private static final String TAG = "AtmosphereTropical";

    /** Bump when the drawing changes, or a stale file is served under the same name. */
    private static final int VERSION = 1;

    private static final int LABEL_BG = 0x99000000;
    private static final int PAD_X = 6, PAD_Y = 3, GAP = 2, RADIUS = 4;

    private final Context pluginContext;
    private final File dir;
    private final Map<String, Icon> cache = new HashMap<>();
    private Bitmap cyclone;

    StormIcons(Context pluginContext, File dir) {
        this.pluginContext = pluginContext;
        this.dir = dir;
        if (dir != null && !dir.isDirectory())
            //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
    }

    /**
     * A cyclone in {@code tint}, with {@code text} in a pill above it.
     *
     * @param symbolDp how wide the cyclone is, in dp: larger for where the storm is
     *                 now than for where it is forecast to be.
     */
    Icon labelled(String text, int tint, int symbolDp) {
        final float scale = Math.max(1f,
                gov.tak.api.commons.graphics.DisplaySettings.getRelativeScaling());
        final MapTextFormat tf = MapView.getDefaultTextFormat();
        // At start-up the map's text format may not exist yet; ATAK's own default is
        // 14 at the display scaling, and an iconless marker is worse than a guess.
        float textPx = tf == null ? 0f : tf.getDensityAdjustedFontSize();
        if (textPx <= 0f)
            textPx = 14f * scale;
        final String label = text == null ? "" : text;
        final String key = Integer.toHexString(label.hashCode()) + "_" + Integer.toHexString(tint)
                + "_" + symbolDp + "_v" + VERSION
                + "_s" + Math.round(scale * 100) + "_f" + Math.round(textPx * 10);
        final Icon hit = cache.get(key);
        if (hit != null)
            return hit;

        final Paint tp = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
        tp.setTypeface(tf == null || tf.getTypeface() == null ? null : tf.getTypeface());
        tp.setTextSize(textPx);
        tp.setColor(0xFFFFFFFF);
        final Paint.FontMetricsInt fm = tp.getFontMetricsInt();
        final int textW = (int) Math.ceil(tp.measureText(label));
        final int textH = fm.descent - fm.ascent;
        final int pillW = textW + 2 * PAD_X, pillH = textH + 2 * PAD_Y;
        final int symbolPx = Math.round(symbolDp * scale);
        final int w = Math.max(pillW, symbolPx) + 2;
        final int h = pillH + GAP + symbolPx + 2;
        // The point is the storm's position, so the anchor is the cyclone's center and
        // the pill hangs above it rather than over it.
        final int cx = w / 2, cy = pillH + GAP + symbolPx / 2;

        final File out = new File(dir, "storm_" + key + ".png");
        if (!out.isFile() && !compose(out, label, tint, tp, fm, w, h, pillW, pillH,
                symbolPx, cx, cy))
            return null;

        // Composed at device pixels; asked back at the same pixels by dividing out
        // ATAK's dp scaling, so nothing is resampled.
        final Icon icon = new Icon.Builder()
                .setImageUri(Icon.STATE_DEFAULT, "file://" + out.getAbsolutePath())
                .setSize(Math.round(w / scale), Math.round(h / scale))
                .setAnchor(Math.round(cx / scale), Math.round(cy / scale))
                .build();
        cache.put(key, icon);
        return icon;
    }

    private boolean compose(File out, String label, int tint, Paint tp,
            Paint.FontMetricsInt fm, int w, int h, int pillW, int pillH,
            int symbolPx, int cx, int cy) {
        Bitmap bmp = null;
        try {
            final Bitmap glyph = cyclone();
            if (glyph == null)
                return false;
            bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            final Canvas c = new Canvas(bmp);
            if (!label.isEmpty()) {
                final Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG);
                bg.setColor(LABEL_BG);
                final float left = (w - pillW) / 2f;
                c.drawRoundRect(new RectF(left, 1, left + pillW, 1 + pillH),
                        RADIUS, RADIUS, bg);
                c.drawText(label, left + PAD_X, 1 + PAD_Y - fm.ascent, tp);
            }
            // The tint goes on the symbol here, not on the Icon: the Icon would color
            // the label with it.
            final Paint sp = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
            sp.setColorFilter(new PorterDuffColorFilter(tint, PorterDuff.Mode.SRC_IN));
            c.drawBitmap(glyph, new Rect(0, 0, glyph.getWidth(), glyph.getHeight()),
                    new RectF(cx - symbolPx / 2f, cy - symbolPx / 2f,
                            cx + symbolPx / 2f, cy + symbolPx / 2f), sp);

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
            Log.w(TAG, "storm icon " + label, e);
            return false;
        } finally {
            if (bmp != null)
                bmp.recycle();
        }
    }

    private Bitmap cyclone() {
        if (cyclone == null || cyclone.isRecycled())
            cyclone = BitmapFactory.decodeResource(pluginContext.getResources(),
                    R.drawable.ic_cyclone);
        return cyclone;
    }
}
