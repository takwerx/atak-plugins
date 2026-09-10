package com.atakmap.android.featurelayer;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;

import com.atakmap.coremap.log.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * The pictures ATAK's tap chooser ("Select Item") shows for a line. NWCG lines get the
 * standard's own drawing (PMS 936: black completed lines lettered H, R, M, P, a chain of
 * X's for dozer line, red ticks on the fire edge, magenta planned lines, purple access,
 * green escape, tan repair) in its true colors on a light tile, because the rows are
 * dark and half the standard is black. Anything else gets a plain line in its own color.
 * Drawn once per kind and kept as a PNG; the chooser takes a file URI and scales it into
 * one small box, and the standard's strip drawings came out too small to read there.
 */
final class ChooserGlyphs {
    private static final String TAG = "FeatureLayer";
    private static final int SIZE = 96;
    private static final int REV = 3; // bump when a drawing changes, so cached PNGs are redrawn
    private static final String PREFIX = "chooser" + REV + "_";
    private static final int TILE = 0xFFE4E4E4;

    // NWCG colors, sampled from the standard's own symbol images.
    static final int BLACK = 0xFF000000, MAGENTA = 0xFFF000C0, RED = 0xFFE00000,
            PURPLE = 0xFFC000F0, DARK_PURPLE = 0xFF900090, GREEN = 0xFF30A000,
            TAN = 0xFFA08040, FENCE_RED = 0xFFC01020, LIGHT_GRAY = 0xFFB0B0B0,
            YELLOW = 0xFFF0F000, PINK = 0xFFF07070, SKY = 0xFF70B0F0,
            HOSE_BLUE = 0xFF0070F0, ORANGE = 0xFFF0A000, GREY_EDGE = 0xFF808080, DARK_GREY = 0xFF707070;

    /** How the line itself is drawn. */
    private enum Stroke { NONE, SOLID, THICK, THIN, DASHED, SQUARE_DOTS, THIN_DASHED }

    /** What rides on it. */
    private enum Marks { NONE, TICKS, X_CHAIN, X_GROUPS, BOXED_DOTS, ZIGZAG, TRIANGLES, BOXES, CIRCLES, DIAMONDS, DROPS, END_CIRCLES }

    /** How an area's inside is drawn, after the standard's polygon symbols. */
    private enum Fill { NONE, TINT, DIAG, DIAG_TWO, HORIZ, CROSS, DOTS }

    private static final class Spec {
        final int color;
        final Stroke stroke;
        final String letters; // drawn three times along the line, or null
        final Marks marks;
        final int accent;

        Spec(int color, Stroke stroke, String letters, Marks marks, int accent) {
            this.color = color;
            this.stroke = stroke;
            this.letters = letters;
            this.marks = marks;
            this.accent = accent;
        }
    }

    private final File dir;
    private final Map<String, String> uris = new HashMap<>();

    ChooserGlyphs(File dir) {
        this.dir = dir;
        // Drawings from an earlier revision are stale, not cached.
        final File[] old = dir.listFiles();
        if (old != null)
            for (File f : old)
                if (f.getName().startsWith("chooser") && !f.getName().startsWith(PREFIX))
                    //noinspection ResultOfMethodCallIgnored
                    f.delete();
    }

    /** A color that reads on the chooser's dark rows: dark ones become light grey. */
    static int visible(int argb) {
        final int r = (argb >> 16) & 0xFF, g = (argb >> 8) & 0xFF, b = argb & 0xFF;
        return 0.299 * r + 0.587 * g + 0.114 * b < 70 ? 0xFFBBBBBB : (argb | 0xFF000000);
    }

    /** The standard's drawing for an NWCG Event Line or Perimeter Line category, or null. */
    String nwcg(String category) {
        final Spec s = nwcgSpec(category);
        if (s == null)
            return null;
        return glyph("nwcg_" + category.toLowerCase(Locale.US).replaceAll("[^a-z0-9]+", "_"), s);
    }

    /** A plain line in the feature's own color, for layers with no standard behind them. */
    String generic(int color, boolean dashed) {
        return glyph(String.format(Locale.US, "line_%08x_%s", color, dashed ? "dashed" : "solid"),
                new Spec(color, dashed ? Stroke.DASHED : Stroke.SOLID, null, Marks.NONE, 0));
    }

    private static Spec nwcgSpec(String category) {
        if (category == null)
            return null;
        switch (category) {
            case "Completed Hand Line":
                return new Spec(BLACK, Stroke.SOLID, "H", Marks.NONE, 0);
            case "Completed Road as Line":
                return new Spec(BLACK, Stroke.SOLID, "R", Marks.NONE, 0);
            case "Completed Mixed Construction Line":
                return new Spec(BLACK, Stroke.SOLID, "M", Marks.NONE, 0);
            case "Completed Plow Line":
                return new Spec(BLACK, Stroke.SOLID, "P", Marks.NONE, 0);
            case "Completed Dozer Line":
                return new Spec(BLACK, Stroke.NONE, null, Marks.X_CHAIN, 0);
            case "Completed Fuel Break":
                return new Spec(BLACK, Stroke.NONE, null, Marks.ZIGZAG, 0);
            case "Completed Burnout":
                return new Spec(BLACK, Stroke.SOLID, null, Marks.BOXED_DOTS, 0);
            case "Planned Hand Line":
                return new Spec(MAGENTA, Stroke.SOLID, "H", Marks.NONE, 0);
            case "Planned Road as Line":
                return new Spec(MAGENTA, Stroke.SOLID, "R", Marks.NONE, 0);
            case "Planned Mixed Construction Line":
                return new Spec(MAGENTA, Stroke.SQUARE_DOTS, "M", Marks.NONE, 0);
            case "Planned Plow Line":
                return new Spec(MAGENTA, Stroke.SQUARE_DOTS, "P", Marks.NONE, 0);
            case "Planned Dozer Line":
                return new Spec(MAGENTA, Stroke.NONE, null, Marks.X_GROUPS, 0);
            case "Planned Fuel Break":
                return new Spec(MAGENTA, Stroke.NONE, "ΛV", Marks.NONE, 0);
            case "Planned Burnout":
                return new Spec(MAGENTA, Stroke.SOLID, null, Marks.BOXED_DOTS, 0);
            case "Proposed Line":
                return new Spec(MAGENTA, Stroke.SQUARE_DOTS, null, Marks.NONE, 0);
            case "Uncontained":
            case "Uncontained Fire Edge":
            case "Fire Edge (Field Collection)":
                return new Spec(RED, Stroke.SOLID, null, Marks.TICKS, 0);
            case "Contained":
            case "Contained Fire Edge":
                return new Spec(BLACK, Stroke.SOLID, null, Marks.NONE, 0);
            case "Break Line":
                return new Spec(BLACK, Stroke.SQUARE_DOTS, null, Marks.DIAMONDS, YELLOW);
            case "Hoselay":
                return new Spec(HOSE_BLUE, Stroke.SOLID, null, Marks.DROPS, HOSE_BLUE);
            case "Management Action Point":
                return new Spec(ORANGE, Stroke.SOLID, null, Marks.END_CIRCLES, ORANGE);
            case "Primary Strategic Line":
                return new Spec(NwcgStyles.STRAT_PRIMARY, Stroke.THICK, null, Marks.NONE, 0);
            case "Secondary Strategic Line":
                return new Spec(NwcgStyles.STRAT_SECONDARY, Stroke.THICK, null, Marks.NONE, 0);
            case "Proposed Strategic Line":
                return new Spec(NwcgStyles.STRAT_PROPOSED, Stroke.THICK, null, Marks.NONE, 0);
            case "Limited Action Line":
                return new Spec(NwcgStyles.STRAT_LIMITED, Stroke.THIN, null, Marks.NONE, 0);
            case "Access Route":
            case "Aviation Route":
                return new Spec(PURPLE, Stroke.DASHED, null, Marks.NONE, 0);
            case "Escape Route":
                return new Spec(GREEN, Stroke.DASHED, null, Marks.NONE, 0);
            case "Fence":
                return new Spec(FENCE_RED, Stroke.THIN_DASHED, null, Marks.NONE, 0);
            case "Other":
                return new Spec(LIGHT_GRAY, Stroke.THIN_DASHED, null, Marks.NONE, 0);
            case "Repair Line":
                return new Spec(TAN, Stroke.SOLID, null, Marks.NONE, 0);
            case "Road Repair":
                return new Spec(TAN, Stroke.DASHED, null, Marks.NONE, 0);
            case "Highlighted Feature":
                return new Spec(BLACK, Stroke.DASHED, null, Marks.CIRCLES, YELLOW);
            case "Aerial Hazard":
                return new Spec(DARK_PURPLE, Stroke.SOLID, null, Marks.TRIANGLES, YELLOW);
            case "Retardant Drop":
                return new Spec(DARK_PURPLE, Stroke.SOLID, null, Marks.BOXES, PINK);
            case "Temporary Flight Restriction":
                return new Spec(SKY, Stroke.DASHED, null, Marks.NONE, 0);
            default:
                return null;
        }
    }

    /** The standard's drawing for an Event Polygon or IR Polygon category, or null. */
    String nwcgPolygon(String category) {
        final NwcgStyles.Area a = NwcgStyles.area(category);
        if (a == NwcgStyles.Area.UNKNOWN)
            return null;
        final String key = "area_" + a.name().toLowerCase(Locale.US);
        synchronized (this) {
            String uri = uris.get(key);
            if (uri != null)
                return uri;
            final File f = new File(dir, PREFIX + key + ".png");
            try {
                if (!f.isFile())
                    drawArea(f, a);
                uri = "file://" + f.getAbsolutePath();
            } catch (Exception e) {
                Log.w(TAG, "chooser area glyph " + key + " failed", e);
                return null;
            }
            uris.put(key, uri);
            return uri;
        }
    }

    private static void drawArea(File f, NwcgStyles.Area a) throws Exception {
        final int edge, fill, accent;
        final Fill kind;
        switch (a) {
            case WILDFIRE:   edge = RED;       fill = RED;       accent = 0;   kind = Fill.TINT; break;
            case PRESCRIBED: edge = ORANGE;    fill = ORANGE;    accent = 0;   kind = Fill.TINT; break;
            case HAZARD:     edge = GREY_EDGE; fill = YELLOW;    accent = RED; kind = Fill.DIAG_TWO; break;
            case IR_INTENSE: edge = RED;       fill = RED;       accent = 0;   kind = Fill.DIAG; break;
            case IR_SCATTERED: edge = RED;     fill = RED;       accent = 0;   kind = Fill.DOTS; break;
            case IR_FLIGHT:  edge = GREEN;     fill = GREEN;     accent = 0;   kind = Fill.HORIZ; break;
            case CLOUD:      edge = DARK_GREY; fill = DARK_GREY; accent = 0;   kind = Fill.CROSS; break;
            case IR_HEAT_PERIMETER: edge = RED; fill = 0;        accent = 0;   kind = Fill.NONE; break;
            default:         edge = GREY_EDGE; fill = 0;         accent = 0;   kind = Fill.NONE; break;
        }
        final Bitmap bmp = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888);
        final Canvas c = new Canvas(bmp);
        final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setStyle(Paint.Style.FILL);
        p.setColor(TILE);
        c.drawRoundRect(new RectF(0, 0, SIZE, SIZE), 12f, 12f, p);
        final RectF r = new RectF(10f, 16f, SIZE - 10f, SIZE - 16f);
        c.save();
        c.clipRect(r);
        p.setStrokeWidth(3f);
        switch (kind) {
            case TINT:
                p.setColor((fill & 0x00FFFFFF) | 0x60000000);
                c.drawRect(r, p);
                break;
            case DIAG:
            case DIAG_TWO: {
                p.setStyle(Paint.Style.STROKE);
                int i = 0;
                for (float x = -SIZE; x < SIZE * 2; x += 11f, i++) {
                    p.setColor(kind == Fill.DIAG_TWO && (i & 1) == 1 ? accent : fill);
                    c.drawLine(x, SIZE, x + SIZE, 0, p);
                }
                break;
            }
            case HORIZ:
                p.setStyle(Paint.Style.STROKE);
                p.setColor(fill);
                for (float y = r.top + 6f; y < r.bottom; y += 9f)
                    c.drawLine(r.left, y, r.right, y, p);
                break;
            case CROSS:
                p.setStyle(Paint.Style.STROKE);
                p.setColor(fill);
                for (float x = -SIZE; x < SIZE * 2; x += 12f) {
                    c.drawLine(x, SIZE, x + SIZE, 0, p);
                    c.drawLine(x, 0, x + SIZE, SIZE, p);
                }
                break;
            case DOTS:
                p.setColor(fill);
                for (float y = r.top + 8f; y < r.bottom; y += 12f)
                    for (float x = r.left + 8f; x < r.right; x += 12f)
                        c.drawCircle(x, y, 2.6f, p);
                break;
            case NONE:
                break;
        }
        c.restore();
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(5f);
        p.setColor(edge);
        c.drawRect(r, p);
        final FileOutputStream out = new FileOutputStream(f);
        try {
            bmp.compress(Bitmap.CompressFormat.PNG, 100, out);
        } finally {
            out.close();
            bmp.recycle();
        }
    }

    private synchronized String glyph(String key, Spec spec) {
        String uri = uris.get(key);
        if (uri != null)
            return uri;
        final File f = new File(dir, PREFIX + key + ".png");
        try {
            if (!f.isFile())
                draw(f, spec);
            uri = "file://" + f.getAbsolutePath();
        } catch (Exception e) {
            Log.w(TAG, "chooser glyph " + key + " failed", e);
            return null;
        }
        uris.put(key, uri);
        return uri;
    }

    private static void draw(File f, Spec s) throws Exception {
        final Bitmap bmp = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888);
        final Canvas c = new Canvas(bmp);
        final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);

        // The tile: light, rounded, so black reads and the row stays dark around it.
        p.setStyle(Paint.Style.FILL);
        p.setColor(TILE);
        c.drawRoundRect(new RectF(0, 0, SIZE, SIZE), 12f, 12f, p);

        final float y = SIZE / 2f, x0 = 6f, x1 = SIZE - 6f;
        final float[] xs = { SIZE * 0.25f, SIZE * 0.5f, SIZE * 0.75f };

        // A very light color (a white generic line) would vanish on the tile: halo it.
        final int r = (s.color >> 16) & 0xFF, g = (s.color >> 8) & 0xFF, b = s.color & 0xFF;
        final boolean halo = 0.299 * r + 0.587 * g + 0.114 * b > 190;

        p.setStyle(Paint.Style.STROKE);
        p.setStrokeCap(Paint.Cap.BUTT);
        switch (s.stroke) {
            case SOLID:
                stroke(c, p, s.color, 8f, null, halo, x0, y, x1, y);
                break;
            case THICK:
                stroke(c, p, s.color, 14f, null, halo, x0, y, x1, y);
                break;
            case THIN:
                stroke(c, p, s.color, 5f, null, halo, x0, y, x1, y);
                break;
            case DASHED:
                stroke(c, p, s.color, 8f, new float[] { 14f, 8f }, halo, x0, y, x1, y);
                break;
            case SQUARE_DOTS:
                stroke(c, p, s.color, 8f, new float[] { 8f, 8f }, halo, x0, y, x1, y);
                break;
            case THIN_DASHED:
                stroke(c, p, s.color, 5f, new float[] { 10f, 6f }, halo, x0, y, x1, y);
                break;
            case NONE:
                break;
        }

        p.setPathEffect(null);
        p.setColor(s.color);
        p.setStrokeWidth(6f);
        p.setStrokeCap(Paint.Cap.ROUND);
        switch (s.marks) {
            case TICKS: // one side, as on the map
                for (float x : xs)
                    c.drawLine(x, y, x, y + 26f, p);
                break;
            case X_CHAIN: { // the X's are the line
                final float w = (x1 - x0) / 4f, h = 13f;
                for (int i = 0; i < 4; i++) {
                    final float a = x0 + i * w, e = a + w;
                    c.drawLine(a, y - h, e, y + h, p);
                    c.drawLine(a, y + h, e, y - h, p);
                }
                break;
            }
            case X_GROUPS: { // pairs of small X's with gaps between the pairs
                final float w = 10f, h = 9f;
                for (float x : xs)
                    for (float a = x - w; a < x + w - 1f; a += w) {
                        c.drawLine(a, y - h, a + w, y + h, p);
                        c.drawLine(a, y + h, a + w, y - h, p);
                    }
                break;
            }
            case BOXED_DOTS: { // bars across the line, a dot in each box
                p.setStrokeWidth(5f);
                for (float x : xs)
                    c.drawLine(x, y - 12f, x, y + 12f, p);
                p.setStyle(Paint.Style.FILL);
                for (float x : new float[] { SIZE * 0.125f, SIZE * 0.375f, SIZE * 0.625f, SIZE * 0.875f })
                    c.drawCircle(x, y, 4.5f, p);
                break;
            }
            case ZIGZAG: {
                final Path z = new Path();
                z.moveTo(x0, y + 11f);
                boolean up = true;
                for (float x = x0 + 7f; x <= x1; x += 7f, up = !up)
                    z.lineTo(x, up ? y - 11f : y + 11f);
                p.setStrokeWidth(5f);
                p.setStrokeJoin(Paint.Join.MITER);
                c.drawPath(z, p);
                break;
            }
            case TRIANGLES: { // hazard triangles, accent inside
                for (float x : new float[] { SIZE * 0.35f, SIZE * 0.65f }) {
                    final Path t = new Path();
                    t.moveTo(x, y - 18f);
                    t.lineTo(x + 14f, y + 8f);
                    t.lineTo(x - 14f, y + 8f);
                    t.close();
                    p.setStyle(Paint.Style.FILL);
                    p.setColor(s.accent);
                    c.drawPath(t, p);
                    p.setStyle(Paint.Style.STROKE);
                    p.setStrokeWidth(4f);
                    p.setColor(s.color);
                    c.drawPath(t, p);
                }
                break;
            }
            case BOXES: { // accent squares along the line
                p.setStyle(Paint.Style.FILL);
                p.setColor(s.accent);
                for (float x : new float[] { SIZE * 0.15f, SIZE * 0.385f, SIZE * 0.615f, SIZE * 0.85f })
                    c.drawRect(x - 7f, y - 7f, x + 7f, y + 7f, p);
                break;
            }
            case DIAMONDS: { // a diamond at each end, accent with a dark center
                for (float x : new float[] { x0 + 12f, x1 - 12f }) {
                    final Path d = new Path();
                    d.moveTo(x, y - 16f);
                    d.lineTo(x + 12f, y);
                    d.lineTo(x, y + 16f);
                    d.lineTo(x - 12f, y);
                    d.close();
                    p.setStyle(Paint.Style.FILL);
                    p.setColor(s.accent);
                    c.drawPath(d, p);
                    p.setColor(s.color);
                    c.drawCircle(x, y, 4f, p);
                }
                break;
            }
            case DROPS: { // water drops hanging on the line
                p.setStyle(Paint.Style.FILL);
                p.setColor(s.accent);
                for (float x : xs) {
                    final Path d = new Path();
                    d.moveTo(x, y - 14f);
                    d.lineTo(x + 9f, y + 4f);
                    d.lineTo(x - 9f, y + 4f);
                    d.close();
                    c.drawPath(d, p);
                    c.drawCircle(x, y + 4f, 9f, p);
                }
                break;
            }
            case END_CIRCLES: { // a disc at each end
                p.setStyle(Paint.Style.FILL);
                p.setColor(s.accent);
                c.drawCircle(x0 + 10f, y, 12f, p);
                c.drawCircle(x1 - 10f, y, 12f, p);
                break;
            }
            case CIRCLES: { // accent circles, outlined in the line color
                for (float x : xs) {
                    p.setStyle(Paint.Style.FILL);
                    p.setColor(s.accent);
                    c.drawCircle(x, y, 9f, p);
                    p.setStyle(Paint.Style.STROKE);
                    p.setStrokeWidth(2.5f);
                    p.setColor(s.color);
                    c.drawCircle(x, y, 9f, p);
                }
                break;
            }
            case NONE:
                break;
        }

        if (s.letters != null) {
            // Letters sit in the line, as in the standard: erase a gap, then draw the letter.
            p.setTypeface(Typeface.DEFAULT_BOLD);
            p.setTextSize(34f);
            p.setTextAlign(Paint.Align.CENTER);
            final float baseline = y - (p.descent() + p.ascent()) / 2f;
            final String[] glyphs = new String[3];
            for (int i = 0; i < 3; i++)
                glyphs[i] = String.valueOf(s.letters.charAt(i % s.letters.length()));
            for (int i = 0; i < 3; i++) {
                final float x = xs[i], half = p.measureText(glyphs[i]) / 2f + 3f;
                p.setStyle(Paint.Style.FILL);
                p.setColor(TILE);
                c.drawRect(x - half, y - 20f, x + half, y + 20f, p);
                p.setColor(s.color);
                c.drawText(glyphs[i], x, baseline, p);
            }
        }

        final FileOutputStream out = new FileOutputStream(f);
        try {
            bmp.compress(Bitmap.CompressFormat.PNG, 100, out);
        } finally {
            out.close();
            bmp.recycle();
        }
    }

    private static void stroke(Canvas c, Paint p, int color, float width, float[] dash, boolean halo,
            float x0, float y0, float x1, float y1) {
        if (halo) {
            p.setColor(0xFF555555);
            p.setStrokeWidth(width + 3f);
            p.setPathEffect(dash == null ? null : new DashPathEffect(dash, 0f));
            c.drawLine(x0, y0, x1, y1, p);
        }
        p.setColor(color);
        p.setStrokeWidth(width);
        p.setPathEffect(dash == null ? null : new DashPathEffect(dash, 0f));
        c.drawLine(x0, y0, x1, y1, p);
    }
}
