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
    private static final int VERSION = 8;
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
        final String[] lines = lines(name);
        final String label = android.text.TextUtils.join("\n", lines);
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
        final int lineH = fm.descent - fm.ascent;
        int textW = 0;
        for (String l : lines)
            textW = Math.max(textW, Math.round(tp.measureText(l)));
        final int pillW = label.isEmpty() ? 0 : textW + 2 * padX;
        final int pillH = label.isEmpty() ? 0 : lineH * lines.length + 2 * padY;
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
                    final float rad = Math.min(pillH / 2f, lineH * 0.75f);
                    c.drawRoundRect(new RectF(left, top, left + pillW, top + pillH), rad, rad, p);
                    for (int i = 0; i < lines.length; i++)
                        c.drawText(lines[i], cx - tp.measureText(lines[i]) / 2f,
                                top + padY - fm.ascent + i * lineH, tp);
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

    /**
     * A name in one or two lines, none cut: "Rialto Fire Department Station 201"
     * breaks before "Station", a police name at its dash or after "Department"
     * (operator, 2026-11-13); any other name over 28
     * characters breaks at the space nearest its middle. Each line at most 40.
     */
    static String[] lines(String name) {
        if (name == null || name.trim().isEmpty())
            return new String[0];
        final String n = name.trim().replaceAll("\\s+", " ");
        // Police and sheriff first: "Riverside County Sheriff's Department - Southwest
        // Station" breaks at the dash. Checked before Station, which otherwise put the
        // whole first part on line one, cut, and the word "Station" alone on line two.
        final int dash = n.indexOf(" - ");
        if (dash > 2)
            return new String[] { clip(n.substring(0, dash)), clip(n.substring(dash + 3)) };
        // Fire: "Rialto Fire Department Station 201" breaks before Station, only when
        // the station has a number or a name after the word.
        final java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("^(.{3,}?)\\s+((?:Fire\\s+)?Station\\s+\\S.*)$", java.util.regex.Pattern.CASE_INSENSITIVE).matcher(n);
        if (m.matches() && m.group(1).length() <= 40)
            return new String[] { clip(m.group(1)), clip(m.group(2)) };
        final java.util.regex.Matcher dept = java.util.regex.Pattern
                .compile("^(.*?\\b(?:Department|Dept\\.?|Office|Agency))\\s+(\\S.*)$").matcher(n);
        if (dept.matches() && n.length() > 28)
            return new String[] { clip(dept.group(1)), clip(dept.group(2)) };
        if (n.length() <= 28)
            return new String[] { n };
        int best = -1;
        for (int i = n.indexOf(' '); i >= 0; i = n.indexOf(' ', i + 1))
            if (best < 0 || Math.abs(i - n.length() / 2) < Math.abs(best - n.length() / 2))
                best = i;
        if (best < 0)
            return new String[] { clip(n) };
        return new String[] { clip(n.substring(0, best)), clip(n.substring(best + 1)) };
    }

    private static String clip(String s) {
        return s.length() <= 40 ? s : s.substring(0, 39) + "...";
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
                // A helicopter from the side: rotor, cabin, tail boom, tail rotor, skids.
                final Paint ln = new Paint(Paint.ANTI_ALIAS_FLAG);
                ln.setStyle(Paint.Style.STROKE);
                ln.setStrokeCap(Paint.Cap.ROUND);
                ln.setColor(0xFFFFFFFF);
                ln.setStrokeWidth(s * 0.05f);
                c.drawLine(cx - s * 0.30f, cy - s * 0.20f, cx + s * 0.22f, cy - s * 0.20f, ln);   // main rotor
                c.drawLine(cx - s * 0.06f, cy - s * 0.20f, cx - s * 0.06f, cy - s * 0.12f, ln);   // mast
                c.drawOval(new RectF(cx - s * 0.24f, cy - s * 0.13f, cx + s * 0.08f, cy + s * 0.11f), white); // cabin
                ln.setStrokeWidth(s * 0.06f);
                c.drawLine(cx + s * 0.04f, cy - s * 0.03f, cx + s * 0.30f, cy - s * 0.05f, ln);   // tail boom
                ln.setStrokeWidth(s * 0.04f);
                c.drawLine(cx + s * 0.30f, cy - s * 0.13f, cx + s * 0.30f, cy + s * 0.03f, ln);   // tail rotor
                c.drawLine(cx - s * 0.16f, cy + s * 0.11f, cx - s * 0.16f, cy + s * 0.18f, ln);   // skid struts
                c.drawLine(cx + s * 0.00f, cy + s * 0.11f, cx + s * 0.00f, cy + s * 0.18f, ln);
                c.drawLine(cx - s * 0.26f, cy + s * 0.18f, cx + s * 0.08f, cy + s * 0.18f, ln);   // skid
                break;
            }
            case "urgent": {
                // A red cross on white: care, not a hospital's blue H.
                c.drawRoundRect(box, r, r, black);
                c.drawRoundRect(inset(box, s * 0.05f), r * 0.8f, r * 0.8f, white);
                fill.setColor(0xFFD32F2F);
                final float a = s * 0.11f, b = s * 0.32f;
                c.drawRect(cx - a, cy - b, cx + a, cy + b, fill);
                c.drawRect(cx - b, cy - a, cx + b, cy + a, fill);
                break;
            }
            case "animal": {
                c.drawRoundRect(box, r, r, white);
                fill.setColor(0xFF6D4C41);
                c.drawRoundRect(inset(box, s * 0.07f), r * 0.8f, r * 0.8f, fill);
                // A paw print: pad and four toes.
                c.drawOval(new RectF(cx - s * 0.17f, cy - s * 0.02f, cx + s * 0.17f, cy + s * 0.27f), white);
                c.drawCircle(cx - s * 0.22f, cy - s * 0.06f, s * 0.07f, white);
                c.drawCircle(cx - s * 0.09f, cy - s * 0.20f, s * 0.075f, white);
                c.drawCircle(cx + s * 0.09f, cy - s * 0.20f, s * 0.075f, white);
                c.drawCircle(cx + s * 0.22f, cy - s * 0.06f, s * 0.07f, white);
                break;
            }
            case "fair": {
                c.drawRoundRect(box, r, r, white);
                fill.setColor(0xFFAD1457);
                c.drawRoundRect(inset(box, s * 0.07f), r * 0.8f, r * 0.8f, fill);
                // A barn: gambrel roof, body, doors.
                final Path barn = new Path();
                barn.moveTo(cx - s * 0.28f, cy + s * 0.28f);
                barn.lineTo(cx - s * 0.28f, cy - s * 0.04f);
                barn.lineTo(cx - s * 0.18f, cy - s * 0.22f);
                barn.lineTo(cx, cy - s * 0.32f);
                barn.lineTo(cx + s * 0.18f, cy - s * 0.22f);
                barn.lineTo(cx + s * 0.28f, cy - s * 0.04f);
                barn.lineTo(cx + s * 0.28f, cy + s * 0.28f);
                barn.close();
                c.drawPath(barn, white);
                c.drawRect(cx - s * 0.10f, cy + s * 0.04f, cx + s * 0.10f, cy + s * 0.28f, fill);
                break;
            }
            case "equestrian": {
                c.drawRoundRect(box, r, r, white);
                fill.setColor(0xFF5D4037);
                c.drawRoundRect(inset(box, s * 0.07f), r * 0.8f, r * 0.8f, fill);
                // A horseshoe, open end down.
                final Paint shoe = new Paint(Paint.ANTI_ALIAS_FLAG);
                shoe.setStyle(Paint.Style.STROKE);
                shoe.setStrokeWidth(s * 0.11f);
                shoe.setStrokeCap(Paint.Cap.ROUND);
                shoe.setColor(0xFFFFFFFF);
                c.drawArc(new RectF(cx - s * 0.22f, cy - s * 0.26f, cx + s * 0.22f, cy + s * 0.20f), 150, 240, false, shoe);
                c.drawLine(cx - s * 0.19f, cy + s * 0.05f, cx - s * 0.15f, cy + s * 0.27f, shoe);
                c.drawLine(cx + s * 0.19f, cy + s * 0.05f, cx + s * 0.15f, cy + s * 0.27f, shoe);
                break;
            }
            case "pharmacy": {
                c.drawRoundRect(box, r, r, white);
                fill.setColor(0xFF2E7D32);
                c.drawRoundRect(inset(box, s * 0.07f), r * 0.8f, r * 0.8f, fill);
                final Paint rx = new Paint(Paint.ANTI_ALIAS_FLAG);
                rx.setColor(0xFFFFFFFF);
                rx.setTextSize(s * 0.50f);
                rx.setFakeBoldText(true);
                rx.setTextAlign(Paint.Align.CENTER);
                c.drawText("Rx", cx, cy - (rx.descent() + rx.ascent()) / 2, rx);
                break;
            }
            case "grocery": {
                c.drawRoundRect(box, r, r, white);
                fill.setColor(0xFF558B2F);
                c.drawRoundRect(inset(box, s * 0.07f), r * 0.8f, r * 0.8f, fill);
                // A shopping cart.
                final Paint ln = new Paint(Paint.ANTI_ALIAS_FLAG);
                ln.setStyle(Paint.Style.STROKE);
                ln.setStrokeWidth(s * 0.06f);
                ln.setColor(0xFFFFFFFF);
                c.drawLine(cx - s * 0.32f, cy - s * 0.22f, cx - s * 0.20f, cy - s * 0.22f, ln);
                final Path basket = new Path();
                basket.moveTo(cx - s * 0.20f, cy - s * 0.22f);
                basket.lineTo(cx - s * 0.12f, cy + s * 0.10f);
                basket.lineTo(cx + s * 0.24f, cy + s * 0.10f);
                basket.lineTo(cx + s * 0.30f, cy - s * 0.12f);
                basket.lineTo(cx - s * 0.17f, cy - s * 0.12f);
                basket.close();
                c.drawPath(basket, white);
                c.drawCircle(cx - s * 0.08f, cy + s * 0.22f, s * 0.06f, white);
                c.drawCircle(cx + s * 0.20f, cy + s * 0.22f, s * 0.06f, white);
                break;
            }
            case "fuel": {
                c.drawRoundRect(box, r, r, white);
                fill.setColor(0xFF37474F);
                c.drawRoundRect(inset(box, s * 0.07f), r * 0.8f, r * 0.8f, fill);
                // A fuel pump with its hose.
                c.drawRoundRect(new RectF(cx - s * 0.24f, cy - s * 0.30f, cx + s * 0.06f, cy + s * 0.30f), s * 0.04f, s * 0.04f, white);
                c.drawRect(cx - s * 0.18f, cy - s * 0.22f, cx, cy - s * 0.06f, fill);
                final Paint hose = new Paint(Paint.ANTI_ALIAS_FLAG);
                hose.setStyle(Paint.Style.STROKE);
                hose.setStrokeWidth(s * 0.05f);
                hose.setColor(0xFFFFFFFF);
                final Path hp = new Path();
                hp.moveTo(cx + s * 0.06f, cy - s * 0.12f);
                hp.lineTo(cx + s * 0.20f, cy - s * 0.02f);
                hp.lineTo(cx + s * 0.20f, cy + s * 0.18f);
                hp.lineTo(cx + s * 0.28f, cy + s * 0.18f);
                hp.lineTo(cx + s * 0.28f, cy - s * 0.20f);
                c.drawPath(hp, hose);
                break;
            }
            case "fire": {
                c.drawRoundRect(box, r, r, white);
                fill.setColor(0xFFC62828);
                c.drawRoundRect(inset(box, s * 0.07f), r * 0.8f, r * 0.8f, fill);
                // A flame.
                final Path f = new Path();
                f.moveTo(cx, cy - s * 0.32f);
                f.cubicTo(cx + s * 0.30f, cy - s * 0.05f, cx + s * 0.26f, cy + s * 0.30f, cx, cy + s * 0.30f);
                f.cubicTo(cx - s * 0.26f, cy + s * 0.30f, cx - s * 0.30f, cy + s * 0.02f, cx - s * 0.10f, cy - s * 0.12f);
                f.cubicTo(cx - s * 0.08f, cy - s * 0.02f, cx - s * 0.02f, cy + s * 0.02f, cx + s * 0.02f, cy - s * 0.04f);
                f.cubicTo(cx + s * 0.06f, cy - s * 0.14f, cx + s * 0.02f, cy - s * 0.24f, cx, cy - s * 0.32f);
                f.close();
                c.drawPath(f, white);
                break;
            }
            case "police": {
                c.drawRoundRect(box, r, r, white);
                fill.setColor(0xFF1A237E);
                c.drawRoundRect(inset(box, s * 0.07f), r * 0.8f, r * 0.8f, fill);
                // A five-pointed star.
                final Path star = new Path();
                for (int i = 0; i < 10; i++) {
                    final double a = Math.toRadians(-90 + i * 36);
                    final float rad = (i % 2 == 0 ? 0.32f : 0.13f) * s;
                    final float x = cx + (float) (Math.cos(a) * rad), y = cy + s * 0.02f + (float) (Math.sin(a) * rad);
                    if (i == 0)
                        star.moveTo(x, y);
                    else
                        star.lineTo(x, y);
                }
                star.close();
                c.drawPath(star, white);
                break;
            }
            case "power": {
                c.drawRoundRect(box, r, r, white);
                fill.setColor(0xFFEF6C00);
                c.drawRoundRect(inset(box, s * 0.07f), r * 0.8f, r * 0.8f, fill);
                // A lightning bolt.
                final Path bolt = new Path();
                bolt.moveTo(cx + s * 0.06f, cy - s * 0.34f);
                bolt.lineTo(cx - s * 0.20f, cy + s * 0.04f);
                bolt.lineTo(cx - s * 0.01f, cy + s * 0.04f);
                bolt.lineTo(cx - s * 0.08f, cy + s * 0.34f);
                bolt.lineTo(cx + s * 0.20f, cy - s * 0.06f);
                bolt.lineTo(cx + s * 0.01f, cy - s * 0.06f);
                bolt.close();
                c.drawPath(bolt, white);
                break;
            }
            case "battery": {
                c.drawRoundRect(box, r, r, white);
                fill.setColor(0xFF00796B);
                c.drawRoundRect(inset(box, s * 0.07f), r * 0.8f, r * 0.8f, fill);
                // A battery with a bolt through it.
                final RectF body = new RectF(cx - s * 0.30f, cy - s * 0.16f, cx + s * 0.24f, cy + s * 0.16f);
                c.drawRoundRect(body, s * 0.04f, s * 0.04f, white);
                c.drawRect(cx + s * 0.24f, cy - s * 0.07f, cx + s * 0.31f, cy + s * 0.07f, white);
                final Path bolt = new Path();
                bolt.moveTo(cx + s * 0.02f, cy - s * 0.13f);
                bolt.lineTo(cx - s * 0.12f, cy + s * 0.02f);
                bolt.lineTo(cx - s * 0.02f, cy + s * 0.02f);
                bolt.lineTo(cx - s * 0.06f, cy + s * 0.13f);
                bolt.lineTo(cx + s * 0.09f, cy - s * 0.03f);
                bolt.lineTo(cx - s * 0.01f, cy - s * 0.03f);
                bolt.close();
                c.drawPath(bolt, fill);
                break;
            }
            case "nuclear": {
                c.drawCircle(cx, cy, h, black);
                fill.setColor(0xFFFBC02D);
                c.drawCircle(cx, cy, h - s * 0.06f, fill);
                // The trefoil: three blades and a center.
                final RectF ring = new RectF(cx - s * 0.36f, cy - s * 0.36f, cx + s * 0.36f, cy + s * 0.36f);
                for (int i = 0; i < 3; i++)
                    c.drawArc(ring, -120 + i * 120 - 30, 60, true, black);
                c.drawCircle(cx, cy, s * 0.13f, fill);
                c.drawCircle(cx, cy, s * 0.08f, black);
                break;
            }
            case "dam": {
                c.drawRoundRect(box, r, r, white);
                fill.setColor(0xFF0277BD);
                c.drawRoundRect(inset(box, s * 0.07f), r * 0.8f, r * 0.8f, fill);
                // A dam wall with water behind it.
                final Path wall = new Path();
                wall.moveTo(cx - s * 0.04f, cy - s * 0.30f);
                wall.lineTo(cx + s * 0.08f, cy - s * 0.30f);
                wall.lineTo(cx + s * 0.30f, cy + s * 0.30f);
                wall.lineTo(cx - s * 0.04f, cy + s * 0.30f);
                wall.close();
                c.drawPath(wall, white);
                final Paint wave = new Paint(Paint.ANTI_ALIAS_FLAG);
                wave.setStyle(Paint.Style.STROKE);
                wave.setStrokeWidth(s * 0.06f);
                wave.setColor(0xFFFFFFFF);
                for (int i = 0; i < 3; i++) {
                    final float y = cy - s * 0.14f + i * s * 0.14f;
                    final Path w = new Path();
                    w.moveTo(cx - s * 0.32f, y);
                    w.quadTo(cx - s * 0.24f, y - s * 0.06f, cx - s * 0.16f, y);
                    w.quadTo(cx - s * 0.11f, y + s * 0.05f, cx - s * 0.08f, y);
                    c.drawPath(w, wave);
                }
                break;
            }
            case "hazmat": {
                // An orange diamond with a black exclamation mark.
                final Path dia = new Path();
                dia.moveTo(cx, cy - h);
                dia.lineTo(cx + h, cy);
                dia.lineTo(cx, cy + h);
                dia.lineTo(cx - h, cy);
                dia.close();
                c.drawPath(dia, black);
                final Path in = new Path();
                final float k = s * 0.09f;
                in.moveTo(cx, cy - h + k);
                in.lineTo(cx + h - k, cy);
                in.lineTo(cx, cy + h - k);
                in.lineTo(cx - h + k, cy);
                in.close();
                fill.setColor(0xFFF57C00);
                c.drawPath(in, fill);
                c.drawRect(cx - s * 0.045f, cy - s * 0.24f, cx + s * 0.045f, cy + s * 0.07f, black);
                c.drawCircle(cx, cy + s * 0.16f, s * 0.055f, black);
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
