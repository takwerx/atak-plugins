package com.atakmap.android.atmosphere.overlay;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;

import com.atakmap.android.maps.MapTextFormat;
import com.atakmap.android.maps.MapView;

import com.atakmap.android.atmosphere.data.Nwps;
import com.atakmap.coremap.filesystem.FileSystemUtils;
import com.atakmap.coremap.log.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * The river gauge symbol, drawn here: a filled disc in the color water.noaa.gov
 * gives that flood category, with a dark ring so the pale ones read on a pale
 * basemap.
 *
 * <p>The colors are the service's own, read off the riv_gauges map service legend
 * ({@code mapservices.weather.noaa.gov/eventdriven/rest/services/water/riv_gauges},
 * 2026-09-26) rather than chosen: a crew that has used water.noaa.gov already reads
 * them. Purple is major flooding, red moderate, orange minor, yellow action stage,
 * green no flooding; light blue a gauge with no flood stages defined, brown a
 * low-water threshold, pale grey one whose observation is not current, dark grey
 * one out of service.
 *
 * <p>Composed on the worker that rebuilds the layer, never on the thread that
 * draws the map or loads the plugin.
 */
final class GaugeIcons {

    private static final String TAG = "AtmosphereGauges";

    /** Bump when the drawing changes, or a stale file is served under the same name. */
    private static final int VERSION = 3;

    static final int MAJOR = 0xFFCC33FF;
    static final int MODERATE = 0xFFFF0000;
    static final int MINOR = 0xFFFF9900;
    static final int ACTION = 0xFFFFFF00;
    static final int NO_FLOODING = 0xFF00FF00;
    static final int NOT_DEFINED = 0xFF72AFE9;
    static final int LOW_WATER = 0xFF906320;
    static final int NOT_CURRENT = 0xFFBDC2BB;
    static final int OUT_OF_SERVICE = 0xFF666666;

    /** Across, in density-independent pixels; the map is asked for the same size back. */
    static final int SIZE_DP = 22;

    private final File dir;
    private final float density;
    private final Map<String, String> cache = new HashMap<>();

    GaugeIcons(float density) {
        this.density = density <= 0 ? 1f : density;
        dir = FileSystemUtils.getItem("tools/atmosphere/gauge-icons");
        if (dir != null && !dir.isDirectory())
            //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
    }

    /** The legend's color for a category; a category the legend does not name is grey. */
    static int color(String category) {
        final String c = category == null ? "" : category;
        switch (c) {
            case Nwps.MAJOR: return MAJOR;
            case Nwps.MODERATE: return MODERATE;
            case Nwps.MINOR: return MINOR;
            case Nwps.ACTION: return ACTION;
            case Nwps.NO_FLOODING: return NO_FLOODING;
            case Nwps.NOT_DEFINED: return NOT_DEFINED;
            case Nwps.LOW_THRESHOLD: return LOW_WATER;
            case Nwps.OUT_OF_SERVICE: return OUT_OF_SERVICE;
            default: return NOT_CURRENT;
        }
    }

    /** One composed icon: where it is and how it is placed. */
    static final class Composed {
        final String uri;
        /** dp, as the feature renderer wants them. */
        final int width, height;
        /** dp, the shift that puts the disc on the point; zero for a bare disc. */
        final float offsetX, offsetY;

        Composed(String uri, int width, int height, float offsetX, float offsetY) {
            this.uri = uri;
            this.width = width;
            this.height = height;
            this.offsetX = offsetX;
            this.offsetY = offsetY;
        }
    }

    private final Map<String, Composed> labeled = new HashMap<>();

    /**
     * The disc with a pill under it: the reading on the first line, the gauge's
     * name on the second, the station pill's shape. The bitmap is trimmed to its
     * ink and placed by offset, the way the station icon is, with the same signs
     * (x = middle relative to the disc, y = disc relative to the middle).
     */
    Composed labeled(int color, String reading, String name) {
        final String r = reading == null ? "" : reading.trim();
        final String n = name == null ? "" : name.trim();
        final String key = String.format(Locale.US, "L%08x_%s_v%d_s%d", color,
                Integer.toHexString((r + "|" + n).hashCode()), VERSION,
                Math.round(density * 100));
        final Composed hit = labeled.get(key);
        if (hit != null)
            return hit;
        if (dir == null)
            return null;
        final File out = new File(dir, "gauge_" + key + ".png");

        final MapTextFormat tf = MapView.getDefaultTextFormat();
        float textPx = tf == null ? 0f : tf.getDensityAdjustedFontSize();
        if (textPx <= 0f)
            textPx = 14f * density;
        final Paint big = text(tf, textPx, true);
        final Paint small = text(tf, textPx * 0.85f, false);
        final Paint.FontMetricsInt bm = big.getFontMetricsInt();
        final Paint.FontMetricsInt sm = small.getFontMetricsInt();
        final int padX = Math.round(6 * density), padY = Math.round(3 * density);
        final int gap = Math.round(3 * density);
        final int lineOne = r.isEmpty() ? 0 : bm.descent - bm.ascent;
        final int lineTwo = n.isEmpty() ? 0 : sm.descent - sm.ascent;
        final int textW = Math.round(Math.max(r.isEmpty() ? 0 : big.measureText(r),
                n.isEmpty() ? 0 : small.measureText(n)));
        final int pillW = textW + 2 * padX, pillH = lineOne + lineTwo + 2 * padY;
        final int disc = Math.max(8, Math.round(SIZE_DP * density));
        final int pad = 2;
        final int w = Math.max(disc, pillW) + 2 * pad;
        final int h = disc + gap + pillH + 2 * pad;
        final int cx = w / 2, cy = pad + disc / 2;

        if (!out.isFile()) {
            Bitmap bmp = null;
            try {
                bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
                final Canvas c = new Canvas(bmp);
                final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
                final float rad = disc / 2f;
                p.setColor(0xB3000000);
                c.drawCircle(cx, cy, rad - 0.5f, p);
                p.setColor(color);
                c.drawCircle(cx, cy, rad - 1.5f * density, p);
                final float top = cy + rad + gap, left = cx - pillW / 2f;
                p.setColor(0xE6000000);
                c.drawRoundRect(new RectF(left, top, left + pillW, top + pillH),
                        4 * density, 4 * density, p);
                if (!r.isEmpty())
                    c.drawText(r, cx - big.measureText(r) / 2f, top + padY - bm.ascent, big);
                if (!n.isEmpty())
                    c.drawText(n, cx - small.measureText(n) / 2f,
                            top + padY + lineOne - sm.ascent, small);
                final File tmp = new File(out.getPath() + ".tmp");
                final FileOutputStream o = new FileOutputStream(tmp);
                try {
                    bmp.compress(Bitmap.CompressFormat.PNG, 100, o);
                } finally {
                    o.close();
                }
                //noinspection ResultOfMethodCallIgnored
                tmp.renameTo(out);
                if (!out.isFile())
                    return null;
            } catch (Exception e) {
                Log.w(TAG, "could not compose a gauge label", e);
                return null;
            } finally {
                if (bmp != null)
                    bmp.recycle();
            }
        }
        // Reported the way the station composer reports its bitmap: at the map's
        // relative scaling (1.0), NOT divided by density. The text is already the
        // density-adjusted size ATAK draws labels at, and the renderer scales the
        // feature icon by density again, so dividing here drew the pill at 1/density
        // -- "way too small" beside a station pill (operator, 2026-09-26).
        final float scale = Math.max(1f,
                gov.tak.api.commons.graphics.DisplaySettings.getRelativeScaling());
        final Composed made = new Composed("file://" + out.getAbsolutePath(),
                Math.round(w / scale), Math.round(h / scale),
                (w / 2f - cx) / scale, (cy - h / 2f) / scale);
        labeled.put(key, made);
        return made;
    }

    private static Paint text(MapTextFormat tf, float px, boolean bold) {
        final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
        p.setTypeface(tf == null || tf.getTypeface() == null ? null : tf.getTypeface());
        p.setTextSize(px);
        p.setFakeBoldText(bold);
        p.setColor(0xFFFFFFFF);
        return p;
    }

    /** A {@code file://} uri for a disc in this color, composed once and kept. */
    String uri(int color) {
        final String key = String.format(Locale.US, "%08x_v%d_s%d", color, VERSION,
                Math.round(density * 100));
        final String hit = cache.get(key);
        if (hit != null)
            return hit;
        if (dir == null)
            return null;
        final File out = new File(dir, "gauge_" + key + ".png");
        if (!out.isFile() && !compose(out, color))
            return null;
        final String uri = "file://" + out.getAbsolutePath();
        cache.put(key, uri);
        return uri;
    }

    private boolean compose(File out, int color) {
        final int px = Math.max(8, Math.round(SIZE_DP * density));
        Bitmap bmp = null;
        try {
            bmp = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888);
            final Canvas c = new Canvas(bmp);
            final float r = px / 2f;
            final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            // A dark ring so yellow, green and the greys hold their edge over a
            // pale basemap; water.noaa.gov's tiles give it that for free.
            p.setColor(0xB3000000);
            c.drawCircle(r, r, r - 0.5f, p);
            p.setColor(color);
            c.drawCircle(r, r, r - 1.5f * density, p);
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
            Log.w(TAG, "could not compose a gauge icon", e);
            //noinspection ResultOfMethodCallIgnored
            out.delete();
            return false;
        } finally {
            if (bmp != null)
                bmp.recycle();
        }
    }
}
