package com.atakmap.android.featurelayer;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;

import com.atakmap.coremap.log.Log;

import java.io.File;
import java.io.FileOutputStream;

/**
 * A point symbol with its name drawn above it as pixels, at device resolution, so the
 * feature carries no label for ATAK's label engine to draw.
 *
 * <p>Why: the engine trims a feature's label to a fraction of its width under a rule no
 * plugin can read or reach, and draws the default one in white with no backing. On the
 * Timber fire at 319 ft, "Value at Risk" read as "Value" and "Va", "Hazard" as "ard",
 * all in white over red-tinted terrain (2026-09-17). The DART callsigns had the same
 * problem and were fixed the same way as markers; this is the same idea for every point
 * that stays a feature. The text is ATAK's own typeface at its density-adjusted marker
 * label size, on the backing ATAK draws behind its own labels, so it reads as the map.
 *
 * <p>The symbol is kept at the bitmap's <b>center</b> by padding below it as much as the
 * label takes above: a feature icon has no anchor, and a symbol off-center would sit off
 * its point. The cost is a tap target as tall as label and symbol together.
 *
 * <p>Compose on the refresh thread, never on the main thread: several hundred of these at
 * once on the main thread is an ANR (the DART version proved it at 22:50 and 22:52).
 */
final class LabelledIcons {
    private static final String TAG = "FeatureLayer";
    private static final int V = 1;
    private static final int GAP = 4, PAD_X = 8, PAD_Y = 4, RADIUS = 6;
    /** The backing ATAK draws behind its own marker labels: argb(153, 0, 0, 0). */
    private static final int LABEL_BG = 0x99000000;

    private LabelledIcons() {
    }

    /**
     * @param symbol  the symbol's PNG on disk, or null for a text-only pill (a Label Point).
     * @param symW    the width the symbol is drawn at on screen, in device px.
     * @param symH    the height, in device px.
     * @param text    the label; null or empty means no composite (returns null).
     * @param textPx  the paint size ATAK's own labels use, in device px.
     * @param out     receives the bitmap's width and height in device px, so a caller can
     *                ask for it back at exactly that size without decoding it.
     * @return the composed PNG, cached, or null when it could not be made.
     */
    static File compose(File symbol, int symW, int symH, String text, Typeface face, float textPx,
            File iconDir, int[] out) {
        if (text == null || text.isEmpty())
            return null;
        // No symbol: a text-only pill, centered on the point. That is a Label Point, the
        // one kind that had kept an engine label -- and "Boy Scout Camp" drew as "ut Camp".
        final boolean textOnly = symbol == null;
        if (!textOnly && (!symbol.isFile() || symW <= 0 || symH <= 0))
            return null;
        if (textOnly)
            symW = symH = 0;
        final String key = (textOnly ? "text" : symbol.getName().replace(".png", "")) + "_"
                + Integer.toHexString(text.hashCode()) + "_" + symW + "x" + symH + "_f" + Math.round(textPx * 10) + "_v" + V;
        final File outFile = new File(iconDir, "lbl_" + key + ".png");
        final Paint tp = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
        tp.setTypeface(face);
        tp.setTextSize(textPx);
        tp.setColor(0xFFFFFFFF);
        final int textW = (int) Math.ceil(tp.measureText(text));
        final Paint.FontMetricsInt fm = tp.getFontMetricsInt();
        final int textH = fm.descent - fm.ascent;
        final int pillW = textW + 2 * PAD_X, pillH = textH + 2 * PAD_Y;
        final int above = textOnly ? 0 : pillH + GAP;
        final int w = Math.max(pillW, symW) + 2;
        // Symmetric: as much below the symbol as the label takes above it.
        final int h = textOnly ? pillH + 2 : above + symH + above;
        if (out != null && out.length >= 2) {
            out[0] = w;
            out[1] = h;
        }
        if (outFile.isFile())
            return outFile;
        try {
            final Bitmap sym = textOnly ? null : BitmapFactory.decodeFile(symbol.getAbsolutePath());
            if (!textOnly && sym == null)
                return null;
            final Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            final Canvas c = new Canvas(bmp);
            final Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG);
            bg.setColor(LABEL_BG);
            final float pl = (w - pillW) / 2f;
            c.drawRoundRect(new RectF(pl, 1, pl + pillW, 1 + pillH), RADIUS, RADIUS, bg);
            c.drawText(text, pl + PAD_X, 1 + PAD_Y - fm.ascent, tp);
            if (sym != null) {
                final float sx = (w - symW) / 2f, sy = above;
                c.drawBitmap(sym, new Rect(0, 0, sym.getWidth(), sym.getHeight()),
                        new RectF(sx, sy, sx + symW, sy + symH),
                        new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG));
            }
            final File tmp = new File(outFile.getPath() + ".tmp");
            final FileOutputStream o = new FileOutputStream(tmp);
            try {
                bmp.compress(Bitmap.CompressFormat.PNG, 100, o);
            } finally {
                o.close();
                bmp.recycle();
                if (sym != null)
                    sym.recycle();
            }
            //noinspection ResultOfMethodCallIgnored
            tmp.renameTo(outFile);
            return outFile.isFile() ? outFile : null;
        } catch (Exception e) {
            Log.w(TAG, "labelled icon for \"" + text + "\"", e);
            return null;
        }
    }
}
