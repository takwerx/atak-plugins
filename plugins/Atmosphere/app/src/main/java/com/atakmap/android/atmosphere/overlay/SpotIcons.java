
package com.atakmap.android.atmosphere.overlay;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;

import com.atakmap.android.atmosphere.data.Spot;
import com.atakmap.android.atmosphere.compat.GeneratedFiles;
import com.atakmap.coremap.log.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * The Spot Forecast Monitor's own symbol, drawn here: a filled disc carrying one
 * letter for what the request is, colored by how far along it is.
 *
 * <p>Copied from NWS's published legend rather than invented, because a crew that has
 * used spot.weather.gov already reads it (operator, 2026-09-25, with the Monitor's own
 * screenshots): <b>W</b> wildfire, <b>P</b> prescribed fire, <b>M</b> marine,
 * <b>H</b> HAZMAT, <b>S</b> search and rescue, <b>O</b> other; green complete, amber
 * waiting on an update, grey not yet filled.
 *
 * <p>The letters are drawn with a real typeface at runtime rather than rasterized as
 * art: eighteen combinations of letter and color is too many to keep as files, and
 * text is the one thing a distance field is bad at.
 */
final class SpotIcons {

    private static final String TAG = "AtmosphereSpot";

    /** Bump when the drawing changes, or a stale file is served under the same name. */
    private static final int VERSION = 1;

    /** NWS's own three, read off the Monitor's legend. */
    static final int DONE = 0xFF1E7B3C;
    static final int WAITING = 0xFFF0B429;
    static final int PENDING = 0xFFBFC4C7;

    /** Device pixels across. The Monitor draws these small; so does this. */
    private static final int SIZE = 44;

    private final File dir;
    private final Map<String, String> cache = new HashMap<>();

    SpotIcons() {
        dir = GeneratedFiles.icons("spot");
        if (dir != null && !dir.isDirectory())
            //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
    }

    /**
     * NWS's letter for what the request is about. Their legend has six; the service
     * has eight type ids, three of which are kinds of search and rescue.
     */
    static char letter(Spot.Request r) {
        final String k = r == null || r.kind == null ? "" : r.kind.toLowerCase(Locale.US);
        if (k.startsWith("wildfire"))
            return 'W';
        if (k.startsWith("prescribed"))
            return 'P';
        if (k.startsWith("marine"))
            return 'M';
        if (k.startsWith("hazmat"))
            return 'H';
        if (k.startsWith("search and rescue"))
            return 'S';
        return 'O';
    }

    /**
     * How far along it is, as a color. The service carries only pending and complete;
     * the third state is a filled request with an update asked for, which is what the
     * Monitor draws amber.
     */
    static int color(Spot.Request r) {
        if (r == null || r.filledAt <= 0)
            return PENDING;
        return r.pending ? WAITING : DONE;
    }

    /** What the color means, for the legend under the layer's toggle. */
    static String statusLabel(int color) {
        if (color == DONE)
            return "Forecast issued";
        if (color == WAITING)
            return "Update requested";
        return "Waiting for the forecast";
    }

    /** The disc's pixel width, so the caller can ask the renderer for the same size. */
    static int size() {
        return SIZE;
    }

    /** A {@code file://} uri for this letter in this color, composed once and kept. */
    String uri(char letter, int color) {
        final String key = letter + "_" + Integer.toHexString(color) + "_v" + VERSION;
        final String hit = cache.get(key);
        if (hit != null)
            return hit;
        final File out = new File(dir, "spot_" + key + ".png");
        if (!out.isFile() && !compose(out, letter, color))
            return null;
        final String uri = "file://" + out.getAbsolutePath();
        cache.put(key, uri);
        return uri;
    }

    private boolean compose(File out, char letter, int color) {
        Bitmap bmp = null;
        try {
            bmp = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888);
            final Canvas c = new Canvas(bmp);
            final float r = SIZE / 2f;

            // A thin dark ring so a pale disc still reads against snow, sand or a
            // white basemap, which the Monitor gets for free from its own tiles.
            final Paint disc = new Paint(Paint.ANTI_ALIAS_FLAG);
            disc.setColor(0xB3000000);
            c.drawCircle(r, r, r - 0.5f, disc);
            disc.setColor(color);
            c.drawCircle(r, r, r - 2.5f, disc);

            final Paint tp = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
            // Pale grey wants dark text; the two strong colors want white.
            tp.setColor(color == PENDING ? 0xFF1A1A1A : 0xFFFFFFFF);
            tp.setTextSize(SIZE * 0.62f);
            tp.setFakeBoldText(true);
            tp.setTextAlign(Paint.Align.CENTER);
            final String s = String.valueOf(letter);
            // Centered on the glyph's own bounds, not the font's: a capital has no
            // descender and sits low if the baseline is taken from the metrics.
            final Rect b = new Rect();
            tp.getTextBounds(s, 0, s.length(), b);
            c.drawText(s, r, r + b.height() / 2f, tp);

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
            Log.w(TAG, "spot icon " + letter, e);
            return false;
        } finally {
            if (bmp != null)
                bmp.recycle();
        }
    }
}
