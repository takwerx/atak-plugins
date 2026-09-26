package com.atakmap.android.atmosphere.overlay;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;

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
    private static final int VERSION = 1;

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
