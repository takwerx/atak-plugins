package com.atakmap.android.sitrep;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;

import com.atakmap.android.maps.MapTextFormat;
import com.atakmap.android.maps.MapView;
import com.atakmap.coremap.log.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.util.HashMap;
import java.util.Map;

/**
 * A place's map icon: a road-sign style glyph on its point, its name in a black pill
 * with white text above it (the takwerx label standard: the icon and the label are
 * separate things, the label readable on any map). One PNG per category and name,
 * cached on disk; the icon is placed on the point by an offset (Atmosphere's
 * GaugeIcons method).
 */
final class PlaceIcons {

    private static final String TAG = "SitRep.PlaceIcons";
    /** Bump when the drawing changes: a cached file never redraws. */
    private static final int VERSION = 2;
    private static final float SIZE_DP = 30f;

    /** What the style needs: the file, its size and the offset that puts the glyph on the point. */
    static final class Composed {
        final String uri;
        final int width, height;
        final float offsetX, offsetY;

        Composed(String uri, int width, int height, float offsetX, float offsetY) {
            this.uri = uri;
            this.width = width;
            this.height = height;
            this.offsetX = offsetX;
            this.offsetY = offsetY;
        }
    }

    private final File dir;
    private final Map<String, Composed> cache = new HashMap<>();

    PlaceIcons(File dir) {
        this.dir = dir;
    }

    Composed get(String category, String name) {
        final String label = name == null ? "" : (name.length() > 40 ? name.substring(0, 39) + "..." : name);
        final String key = category + "|" + label;
        final Composed hit = cache.get(key);
        if (hit != null)
            return hit;
        final float scale = Math.max(1f, gov.tak.api.commons.graphics.DisplaySettings.getRelativeScaling());
        final MapTextFormat tf = MapView.getDefaultTextFormat();
        float textPx = tf == null ? 0f : tf.getDensityAdjustedFontSize();
        if (textPx <= 0f)
            textPx = 14f * scale;
        final Paint tp = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
        tp.setTypeface(tf == null ? null : tf.getTypeface());
        tp.setTextSize(textPx);
        tp.setColor(0xFFFFFFFF);
        final Paint.FontMetricsInt fm = tp.getFontMetricsInt();
        final int padX = Math.round(textPx * 0.45f), padY = Math.round(textPx * 0.2f);
        final int gap = Math.round(textPx * 0.2f);
        final int textW = Math.round(tp.measureText(label));
        final int pillW = label.isEmpty() ? 0 : textW + 2 * padX;
        final int pillH = label.isEmpty() ? 0 : fm.descent - fm.ascent + 2 * padY;
        final int glyph = Math.round(SIZE_DP * scale * 1.2f);
        final int pad = 2;
        final int w = Math.max(glyph, pillW) + 2 * pad;
        final int h = pillH + (label.isEmpty() ? 0 : gap) + glyph + 2 * pad;
        final int cx = w / 2, cy = h - pad - glyph / 2;
        final File out = new File(dir, "place_" + category + "_v" + VERSION + "_"
                + Integer.toHexString(key.hashCode()) + "_" + Math.round(textPx) + ".png");
        if (!out.isFile()) {
            Bitmap bmp = null;
            try {
                bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
                final Canvas c = new Canvas(bmp);
                if (!label.isEmpty()) {
                    final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
                    p.setColor(0xE6000000);
                    final float left = cx - pillW / 2f, top = pad;
                    c.drawRoundRect(new RectF(left, top, left + pillW, top + pillH), pillH / 2f, pillH / 2f, p);
                    c.drawText(label, cx - textW / 2f, top + padY - fm.ascent, tp);
                }
                glyph(c, category, cx, cy, glyph);
                final File tmp = new File(out.getPath() + ".tmp");
                final FileOutputStream o = new FileOutputStream(tmp);
                try {
                    bmp.compress(Bitmap.CompressFormat.PNG, 100, o);
                } finally {
                    o.close();
                }
                //noinspection ResultOfMethodCallIgnored
                tmp.renameTo(out);
            } catch (Exception e) {
                Log.w(TAG, "could not draw a place icon: " + e);
                return null;
            } finally {
                if (bmp != null)
                    bmp.recycle();
            }
        }
        final Composed made = new Composed("file://" + out.getAbsolutePath(),
                Math.round(w / scale), Math.round(h / scale),
                (w / 2f - cx) / scale, (cy - h / 2f) / scale);
        cache.put(key, made);
        return made;
    }

    /** The glyph alone, for the Overlay Manager row. */
    String plain(String category) {
        final Composed c = get(category, "");
        return c == null ? null : c.uri;
    }

    /** Road-sign shapes: blue H, yellow-green school pentagon, care bed, dialysis drop, shelter house. */
    private static void glyph(Canvas c, String cat, float cx, float cy, float s) {
        final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        final Paint white = new Paint(Paint.ANTI_ALIAS_FLAG);
        white.setColor(0xFFFFFFFF);
        final Paint black = new Paint(Paint.ANTI_ALIAS_FLAG);
        black.setColor(0xFF111111);
        final float h = s / 2f, r = s * 0.16f;
        final RectF box = new RectF(cx - h, cy - h, cx + h, cy + h);
        switch (cat) {
            case "hospital": {
                c.drawRoundRect(box, r, r, white);
                fill.setColor(0xFF1559B7);
                c.drawRoundRect(inset(box, s * 0.07f), r * 0.8f, r * 0.8f, fill);
                final float bar = s * 0.13f, top = cy - s * 0.27f, bot = cy + s * 0.27f;
                c.drawRect(cx - s * 0.24f, top, cx - s * 0.24f + bar, bot, white);
                c.drawRect(cx + s * 0.24f - bar, top, cx + s * 0.24f, bot, white);
                c.drawRect(cx - s * 0.24f, cy - bar / 2, cx + s * 0.24f, cy + bar / 2, white);
                break;
            }
            case "school": {
                final Path pent = new Path();
                pent.moveTo(cx, cy - h);
                pent.lineTo(cx + h, cy - h * 0.15f);
                pent.lineTo(cx + h, cy + h);
                pent.lineTo(cx - h, cy + h);
                pent.lineTo(cx - h, cy - h * 0.15f);
                pent.close();
                c.drawPath(pent, black);
                final Path in = new Path();
                final float k = s * 0.08f;
                in.moveTo(cx, cy - h + k * 1.3f);
                in.lineTo(cx + h - k, cy - h * 0.15f + k * 0.4f);
                in.lineTo(cx + h - k, cy + h - k);
                in.lineTo(cx - h + k, cy + h - k);
                in.lineTo(cx - h + k, cy - h * 0.15f + k * 0.4f);
                in.close();
                fill.setColor(0xFFC6E01E);
                c.drawPath(in, fill);
                // A schoolhouse: body, roof, bell tower, door.
                final float bw = s * 0.42f, by = cy + s * 0.02f;
                c.drawRect(cx - bw / 2, by, cx + bw / 2, cy + h - k * 1.6f, black);
                final Path roof = new Path();
                roof.moveTo(cx - bw / 2 - s * 0.05f, by + 1);
                roof.lineTo(cx, by - s * 0.16f);
                roof.lineTo(cx + bw / 2 + s * 0.05f, by + 1);
                roof.close();
                c.drawPath(roof, black);
                c.drawRect(cx - s * 0.04f, by - s * 0.26f, cx + s * 0.04f, by - s * 0.12f, black);
                fill.setColor(0xFFC6E01E);
                c.drawRect(cx - s * 0.05f, cy + h - k * 1.6f - s * 0.15f, cx + s * 0.05f, cy + h - k * 1.6f, fill);
                break;
            }
            case "care": {
                c.drawRoundRect(box, r, r, white);
                fill.setColor(0xFF6A1B9A);
                c.drawRoundRect(inset(box, s * 0.07f), r * 0.8f, r * 0.8f, fill);
                // A bed: headboard, mattress, pillow, legs.
                c.drawRect(cx - s * 0.30f, cy - s * 0.20f, cx - s * 0.22f, cy + s * 0.22f, white);
                c.drawRect(cx - s * 0.22f, cy + s * 0.02f, cx + s * 0.30f, cy + s * 0.12f, white);
                c.drawRect(cx + s * 0.24f, cy + s * 0.02f, cx + s * 0.30f, cy + s * 0.22f, white);
                c.drawCircle(cx - s * 0.11f, cy - s * 0.04f, s * 0.07f, white);
                c.drawRect(cx - s * 0.02f, cy - s * 0.06f, cx + s * 0.28f, cy + s * 0.02f, white);
                break;
            }
            case "dialysis": {
                c.drawCircle(cx, cy, h, white);
                fill.setColor(0xFF00838F);
                c.drawCircle(cx, cy, h - s * 0.07f, fill);
                final Path drop = new Path();
                final float dr = s * 0.17f, dy = cy + s * 0.08f;
                drop.moveTo(cx, cy - s * 0.30f);
                drop.lineTo(cx + dr * 0.95f, dy - dr * 0.3f);
                drop.lineTo(cx - dr * 0.95f, dy - dr * 0.3f);
                drop.close();
                c.drawPath(drop, white);
                c.drawCircle(cx, dy, dr, white);
                break;
            }
            case "airport": {
                c.drawRoundRect(box, r, r, white);
                fill.setColor(0xFF263238);
                c.drawRoundRect(inset(box, s * 0.07f), r * 0.8f, r * 0.8f, fill);
                // An airplane from above, nose up: fuselage, wings, tail.
                final Path plane = new Path();
                final float u = s * 0.30f;
                plane.moveTo(cx, cy - u);
                plane.lineTo(cx + u * 0.12f, cy - u * 0.75f);
                plane.lineTo(cx + u * 0.12f, cy - u * 0.15f);
                plane.lineTo(cx + u, cy + u * 0.20f);
                plane.lineTo(cx + u, cy + u * 0.35f);
                plane.lineTo(cx + u * 0.12f, cy + u * 0.15f);
                plane.lineTo(cx + u * 0.10f, cy + u * 0.70f);
                plane.lineTo(cx + u * 0.38f, cy + u * 0.90f);
                plane.lineTo(cx + u * 0.38f, cy + u);
                plane.lineTo(cx, cy + u * 0.90f);
                plane.lineTo(cx - u * 0.38f, cy + u);
                plane.lineTo(cx - u * 0.38f, cy + u * 0.90f);
                plane.lineTo(cx - u * 0.10f, cy + u * 0.70f);
                plane.lineTo(cx - u * 0.12f, cy + u * 0.15f);
                plane.lineTo(cx - u, cy + u * 0.35f);
                plane.lineTo(cx - u, cy + u * 0.20f);
                plane.lineTo(cx - u * 0.12f, cy - u * 0.15f);
                plane.lineTo(cx - u * 0.12f, cy - u * 0.75f);
                plane.close();
                c.drawPath(plane, white);
                break;
            }
            case "heliport": {
                c.drawCircle(cx, cy, h, white);
                fill.setColor(0xFF111111);
                c.drawCircle(cx, cy, h - s * 0.06f, fill);
                final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
                ring.setStyle(Paint.Style.STROKE);
                ring.setStrokeWidth(s * 0.06f);
                ring.setColor(0xFFFFFFFF);
                c.drawCircle(cx, cy, h - s * 0.16f, ring);
                final float bar = s * 0.09f, top = cy - s * 0.20f, bot = cy + s * 0.20f;
                c.drawRect(cx - s * 0.16f, top, cx - s * 0.16f + bar, bot, white);
                c.drawRect(cx + s * 0.16f - bar, top, cx + s * 0.16f, bot, white);
                c.drawRect(cx - s * 0.16f, cy - bar / 2, cx + s * 0.16f, cy + bar / 2, white);
                break;
            }
            default: { // shelter
                c.drawRoundRect(box, r, r, white);
                fill.setColor(0xFF2E7D32);
                c.drawRoundRect(inset(box, s * 0.07f), r * 0.8f, r * 0.8f, fill);
                final Path house = new Path();
                house.moveTo(cx, cy - s * 0.30f);
                house.lineTo(cx + s * 0.30f, cy - s * 0.02f);
                house.lineTo(cx + s * 0.21f, cy - s * 0.02f);
                house.lineTo(cx + s * 0.21f, cy + s * 0.26f);
                house.lineTo(cx - s * 0.21f, cy + s * 0.26f);
                house.lineTo(cx - s * 0.21f, cy - s * 0.02f);
                house.lineTo(cx - s * 0.30f, cy - s * 0.02f);
                house.close();
                c.drawPath(house, white);
                c.drawRect(cx - s * 0.06f, cy + s * 0.08f, cx + s * 0.06f, cy + s * 0.26f, fill);
                break;
            }
        }
    }

    private static RectF inset(RectF r, float d) {
        return new RectF(r.left + d, r.top + d, r.right - d, r.bottom - d);
    }
}
