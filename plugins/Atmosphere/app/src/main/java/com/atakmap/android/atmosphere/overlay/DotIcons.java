package com.atakmap.android.atmosphere.overlay;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;

import com.atakmap.android.maps.MapTextFormat;
import com.atakmap.android.maps.MapView;
import com.atakmap.coremap.log.Log;
import com.atakmap.coremap.maps.assets.Icon;

import java.io.File;
import java.io.FileOutputStream;
import java.util.HashMap;
import java.util.Map;

/**
 * A colored dot with a pill of text above it: the marker for a storm report, a
 * quake, a fire that has been assessed. {@link StormIcons} with a disc instead of a
 * cyclone, for the same reason it exists: ATAK trims a marker's label freely, and
 * text drawn into the bitmap cannot be trimmed. The pill sits above a separate dot
 * so the dot stays on its point and the label never covers it (the obstacle
 * standard, 2026-10-08).
 *
 * <p>Composed once per distinct text, color and size, as a PNG under ATAK's private
 * storage, and handed back as an {@link Icon} anchored at the dot's center.
 */
final class DotIcons {

    private static final String TAG = "AtmosphereDots";

    /** Bump when the drawing changes, or a stale file is served under the same name. */
    private static final int VERSION = 1;

    private static final int LABEL_BG = 0x99000000;
    private static final int PAD_X = 6, PAD_Y = 3, GAP = 2, RADIUS = 4;
    private static final float RING_DP = 1.5f;

    private final File dir;
    private final Map<String, Icon> cache = new HashMap<>();

    DotIcons(File dir) {
        this.dir = dir;
        if (dir != null && !dir.isDirectory())
            //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
    }

    /**
     * A dot in {@code color}, {@code dotDp} across, with {@code text} in a pill above
     * it; no pill when the text is empty. Null when nothing can be written.
     *
     * @param ring the dot's outline, so a white dot still reads on a pale map
     */
    Icon labelled(String text, int color, int ring, int dotDp) {
        if (dir == null)
            return null;
        final float scale = Math.max(1f,
                gov.tak.api.commons.graphics.DisplaySettings.getRelativeScaling());
        final MapTextFormat tf = MapView.getDefaultTextFormat();
        float textPx = tf == null ? 0f : tf.getDensityAdjustedFontSize();
        if (textPx <= 0f)
            textPx = 14f * scale;
        // A pill is a few words; a server field that runs on is cut, never composed whole.
        final String label = text == null ? "" : text.length() > 40 ? text.substring(0, 39) + "\u2026" : text;
        final String key = Integer.toHexString(label.hashCode()) + "_" + Integer.toHexString(color)
                + "_" + Integer.toHexString(ring) + "_" + dotDp + "_v" + VERSION
                + "_s" + Math.round(scale * 100) + "_f" + Math.round(textPx * 10);
        final Icon hit = cache.get(key);
        if (hit != null)
            return hit;

        final Paint tp = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
        tp.setTypeface(tf == null || tf.getTypeface() == null ? null : tf.getTypeface());
        tp.setTextSize(textPx);
        tp.setColor(0xFFFFFFFF);
        final Paint.FontMetricsInt fm = tp.getFontMetricsInt();
        final int textW = label.isEmpty() ? 0 : (int) Math.ceil(tp.measureText(label));
        final int textH = label.isEmpty() ? 0 : fm.descent - fm.ascent;
        final int pillW = label.isEmpty() ? 0 : textW + 2 * PAD_X;
        final int pillH = label.isEmpty() ? 0 : textH + 2 * PAD_Y;
        final int dotPx = Math.round(dotDp * scale);
        final int ringPx = Math.round(RING_DP * scale);
        final int w = Math.max(pillW, dotPx + 2 * ringPx) + 2;
        final int h = (label.isEmpty() ? 0 : pillH + GAP) + dotPx + 2 * ringPx + 2;
        final int cx = w / 2, cy = (label.isEmpty() ? 0 : pillH + GAP) + ringPx + dotPx / 2 + 1;

        final File out = new File(dir, "dot_" + key + ".png");
        if (!out.isFile() && !compose(out, label, color, ring, tp, fm, w, h, pillW, pillH,
                dotPx, ringPx, cx, cy))
            return null;

        final Icon icon = new Icon.Builder()
                .setImageUri(Icon.STATE_DEFAULT, "file://" + out.getAbsolutePath())
                .setSize(Math.round(w / scale), Math.round(h / scale))
                .setAnchor(Math.round(cx / scale), Math.round(cy / scale))
                .build();
        cache.put(key, icon);
        return icon;
    }

    private boolean compose(File out, String label, int color, int ring, Paint tp,
            Paint.FontMetricsInt fm, int w, int h, int pillW, int pillH, int dotPx,
            int ringPx, int cx, int cy) {
        Bitmap bmp = null;
        try {
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
            final Paint rp = new Paint(Paint.ANTI_ALIAS_FLAG);
            rp.setColor(ring);
            c.drawCircle(cx, cy, dotPx / 2f + ringPx, rp);
            final Paint dp = new Paint(Paint.ANTI_ALIAS_FLAG);
            dp.setColor(color);
            c.drawCircle(cx, cy, dotPx / 2f, dp);

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
            Log.w(TAG, "dot icon " + label, e);
            return false;
        } finally {
            if (bmp != null)
                bmp.recycle();
        }
    }
}
