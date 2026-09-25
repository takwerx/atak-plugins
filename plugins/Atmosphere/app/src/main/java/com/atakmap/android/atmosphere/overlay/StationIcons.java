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
import java.util.Locale;
import java.util.Map;

/**
 * A weather station as it is drawn on the map: a round symbol the wind barb turns on,
 * the readings and the station's name in a pill beneath it.
 *
 * <p><b>The symbol's white marks carry the state, not the shape around them.</b> The
 * anemometer inside the disc is white below criteria, amber close to them and red at
 * them. That is the Comms convention -- there a repeater's own marks light up when a
 * handheld can likely open it -- and it is what the operator asked for here
 * (2026-09-25: "the actual white part the weather station should turn yellow or red").
 * A ring or a halo reads as a selection; a symbol whose marks change color reads as a
 * condition.
 *
 * <p><b>Round, because the barb rotates.</b> The symbol was a diamond, which is the
 * NWCG station shape, but a barb sweeping 360 degrees around a diamond meets a corner
 * at some angles and a flat edge at others, and looks hinged rather than pivoted. A
 * disc gives it one thing to turn on.
 *
 * <p><b>The barb is blue under a white outline</b>, so it survives snow, water, burn
 * scar and shaded relief alike -- a single-color line vanishes on something.
 *
 * <p><b>The readings sit in the label, not beside the symbol</b>, for the same reason
 * the symbol is round: the barb needs the whole circle. Anything placed next to the
 * disc is under the barb for part of the compass. So the pill carries the numbers on
 * top and the station's name underneath, in the map's own typeface, drawn into the
 * bitmap the way {@link StormIcons} does it -- ATAK's label engine trims titles freely
 * and pixels cannot be trimmed.
 *
 * <p>The bitmap is <b>symmetric about the disc</b>: the pill hangs below, and as much
 * empty space is left above. A feature's icon is centered on its point, so that is
 * what keeps the disc itself on the station rather than the whole drawing's middle.
 */
final class StationIcons {

    private static final String TAG = "AtmosphereStations";

    /** Bump when the drawing changes, or stale files are served under the same names. */
    private static final int VERSION = 2;

    /** Below criteria: the symbol as it normally reads. */
    static final int NORMAL = 0xFFFFFFFF;
    /** Close to its criteria. Comms' warn amber. */
    static final int NEAR = 0xFFFFB74D;
    /** Meeting its Red Flag criteria. */
    static final int CRITICAL = 0xFFE53935;

    /** The barb, and the outline that keeps it visible on anything. */
    private static final int BARB = 0xFF2E7BD6;
    private static final int BARB_EDGE = 0xFFFFFFFF;

    /** The disc the barb turns on: dark, like the label, with a white edge. */
    private static final int DISC = 0xE6141414;
    private static final int DISC_EDGE = 0xFFFFFFFF;

    private static final int LABEL_BG = 0x99000000;
    private static final int PAD_X = 6, PAD_Y = 3, GAP = 3, RADIUS = 4;

    /** Device-independent sizes, scaled by ATAK's own display scaling. */
    private static final float DISC_R = 13f, BARB_LEN = 30f;

    private final File dir;
    private final Map<String, Composed> cache = new HashMap<>();

    /** A composed icon: where it lives, and how big to ask the renderer for it. */
    static final class Composed {
        final String uri;
        final int width, height;

        Composed(String uri, int width, int height) {
            this.uri = uri;
            this.width = width;
            this.height = height;
        }
    }

    StationIcons() {
        dir = FileSystemUtils.getItem("tools/atmosphere/station-icons");
        if (dir != null && !dir.isDirectory())
            //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
        clearOldFiles();
    }

    /**
     * Empty the folder at start-up.
     *
     * <p>Every reading is its own file -- a station's numbers and its wind direction
     * are both in the name -- so a folder kept across sessions accumulates one icon
     * per station per hour and never reads any of them again. They cost nothing to
     * compose and are written on a worker.
     */
    private void clearOldFiles() {
        if (dir == null)
            return;
        final File[] old = dir.listFiles();
        if (old == null)
            return;
        int gone = 0;
        for (File f : old)
            if (f.isFile() && f.delete())
                gone++;
        if (gone > 0)
            Log.d(TAG, "cleared " + gone + " station icon(s) from a past session");
    }

    /** What a color means, for the legend under the layer's toggle. */
    static String stateLabel(int color) {
        if (color == CRITICAL)
            return "Red Flag criteria met";
        if (color == NEAR)
            return "Close to criteria";
        return "Below criteria";
    }

    /**
     * One station's symbol, readings and name, composed once and kept.
     *
     * @param name      the station's name, drawn under the readings
     * @param speed     wind speed in the operator's unit, or NaN
     * @param unit      what that speed is in, e.g. "mph"
     * @param humidity  relative humidity in percent, or NaN
     * @param windFrom  degrees the wind is coming from, or NaN for no barb
     * @param state     {@link #NORMAL}, {@link #NEAR} or {@link #CRITICAL}
     */
    Composed compose(String name, double speed, String unit, double humidity,
            double windFrom, int state) {
        final float scale = Math.max(1f,
                gov.tak.api.commons.graphics.DisplaySettings.getRelativeScaling());
        final MapTextFormat tf = MapView.getDefaultTextFormat();
        float textPx = tf == null ? 0f : tf.getDensityAdjustedFontSize();
        if (textPx <= 0f)
            textPx = 14f * scale;

        final String readings = readings(speed, unit, humidity);
        final String title = name == null ? "" : name.trim();
        final String key = Integer.toHexString((readings + "|" + title).hashCode())
                + "_" + bucket(windFrom) + "_" + Integer.toHexString(state)
                + "_v" + VERSION + "_s" + Math.round(scale * 100)
                + "_f" + Math.round(textPx * 10);
        final Composed hit = cache.get(key);
        if (hit != null)
            return hit;

        final Paint big = text(tf, textPx, true);
        final Paint small = text(tf, textPx * 0.85f, false);
        final Paint.FontMetricsInt bm = big.getFontMetricsInt();
        final Paint.FontMetricsInt sm = small.getFontMetricsInt();
        final int lineOne = bm.descent - bm.ascent;
        final int lineTwo = title.isEmpty() ? 0 : sm.descent - sm.ascent;
        final int textW = (int) Math.ceil(Math.max(big.measureText(readings),
                title.isEmpty() ? 0 : small.measureText(title)));
        final int pillW = textW + 2 * PAD_X;
        final int pillH = lineOne + lineTwo + 2 * PAD_Y;

        // The barb reaches BARB_LEN in any direction, so the disc and its barb occupy
        // a circle of that radius whatever the wind is doing.
        final int reach = Math.round(BARB_LEN * scale) + 4;
        // Below the disc: the barb's reach, then the pill. The same is left above, so
        // the disc ends up at the middle of the bitmap and therefore on the station.
        final int below = reach + GAP + pillH;
        final int h = 2 * below;
        final int w = Math.max(pillW, 2 * reach) + 2;
        final int cx = w / 2, cy = h / 2;

        final File out = new File(dir, "wx_" + key + ".png");
        if (!out.isFile() && !draw(out, w, h, cx, cy, scale, windFrom, state,
                readings, title, big, small, bm, sm, pillW, pillH, reach))
            return null;
        // Composed at device pixels and asked back at the same pixels, so nothing is
        // resampled: ATAK scales an icon by dp, and these are already scaled.
        final Composed made = new Composed("file://" + out.getAbsolutePath(),
                Math.round(w / scale), Math.round(h / scale));
        cache.put(key, made);
        return made;
    }

    /** "6 mph · 37%", with a dash for anything the station did not send. */
    private static String readings(double speed, String unit, double humidity) {
        return value(speed) + (unit == null || unit.isEmpty() ? "" : " " + unit)
                + "  ·  " + value(humidity) + "%";
    }

    private static String value(double v) {
        return Double.isNaN(v) ? "–" : String.format(Locale.US, "%d", Math.round(v));
    }

    /** Ten degree steps, finer than a barb this long can show anyway. */
    private static String bucket(double deg) {
        if (Double.isNaN(deg))
            return "x";
        final long d = Math.round(deg / 10.0) * 10L;
        return String.valueOf(((d % 360) + 360) % 360);
    }

    private static Paint text(MapTextFormat tf, float px, boolean bold) {
        final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
        p.setTypeface(tf == null || tf.getTypeface() == null ? null : tf.getTypeface());
        p.setTextSize(px);
        p.setFakeBoldText(bold);
        p.setColor(0xFFFFFFFF);
        return p;
    }

    private boolean draw(File out, int w, int h, int cx, int cy, float scale,
            double windFrom, int state, String readings, String title,
            Paint big, Paint small, Paint.FontMetricsInt bm, Paint.FontMetricsInt sm,
            int pillW, int pillH, int reach) {
        Bitmap bmp = null;
        try {
            bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            final Canvas c = new Canvas(bmp);
            final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);

            barb(c, p, cx, cy, scale, windFrom);
            disc(c, p, cx, cy, scale);
            anemometer(c, p, cx, cy, scale, state);
            pill(c, p, w, cy, reach, pillW, pillH, readings, title, big, small, bm, sm);

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
            Log.w(TAG, "could not compose a station icon", e);
            //noinspection ResultOfMethodCallIgnored
            out.delete();
            return false;
        } finally {
            if (bmp != null)
                bmp.recycle();
        }
    }

    /**
     * The barb, drawn out of the disc toward where the wind is blowing FROM -- the
     * meteorological convention, and the opposite of an arrow showing where it goes.
     * A north wind puts the barb at the top.
     *
     * <p>White underneath and blue on top: one stroke laid over a wider one is an
     * outline, and it is what keeps the line readable over water, snow and shaded
     * relief without a halo behind the whole symbol.
     */
    private void barb(Canvas c, Paint p, int cx, int cy, float scale, double windFrom) {
        if (Double.isNaN(windFrom))
            return;
        final double r = Math.toRadians(windFrom);
        final float ex = cx + (float) (Math.sin(r) * BARB_LEN * scale);
        final float ey = cy - (float) (Math.cos(r) * BARB_LEN * scale);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeCap(Paint.Cap.ROUND);
        p.setColor(BARB_EDGE);
        p.setStrokeWidth(7.5f * scale);
        c.drawLine(cx, cy, ex, ey, p);
        p.setColor(BARB);
        p.setStrokeWidth(4f * scale);
        c.drawLine(cx, cy, ex, ey, p);
        // A head at the far end, so which way it points survives at small sizes.
        p.setStyle(Paint.Style.FILL);
        p.setColor(BARB_EDGE);
        c.drawCircle(ex, ey, 5.5f * scale, p);
        p.setColor(BARB);
        c.drawCircle(ex, ey, 3.6f * scale, p);
    }

    private void disc(Canvas c, Paint p, int cx, int cy, float scale) {
        p.setStyle(Paint.Style.FILL);
        p.setColor(DISC);
        c.drawCircle(cx, cy, DISC_R * scale, p);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(2f * scale);
        p.setColor(DISC_EDGE);
        c.drawCircle(cx, cy, DISC_R * scale, p);
    }

    /**
     * The NWCG weather unit's mark -- a mast with a cup on top and two arms -- in the
     * state's color. This is the part that turns amber and red.
     */
    private void anemometer(Canvas c, Paint p, int cx, int cy, float scale, int state) {
        p.setColor(state);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(2.2f * scale);
        p.setStrokeCap(Paint.Cap.ROUND);
        c.drawLine(cx, cy - 5f * scale, cx, cy + 6f * scale, p);
        c.drawLine(cx - 5.5f * scale, cy + 1f * scale, cx + 5.5f * scale, cy + 1f * scale, p);
        p.setStyle(Paint.Style.FILL);
        c.drawCircle(cx, cy - 6.5f * scale, 2.4f * scale, p);
        c.drawCircle(cx - 6.5f * scale, cy + 1f * scale, 2.1f * scale, p);
        c.drawCircle(cx + 6.5f * scale, cy + 1f * scale, 2.1f * scale, p);
    }

    /** The readings over the station's name, in the pill the rest of the plugin uses. */
    private void pill(Canvas c, Paint p, int w, int cy, int reach, int pillW, int pillH,
            String readings, String title, Paint big, Paint small,
            Paint.FontMetricsInt bm, Paint.FontMetricsInt sm) {
        final float left = (w - pillW) / 2f;
        final float top = cy + reach + GAP;
        p.setStyle(Paint.Style.FILL);
        p.setColor(LABEL_BG);
        c.drawRoundRect(new RectF(left, top, left + pillW, top + pillH),
                RADIUS, RADIUS, p);
        c.drawText(readings, left + PAD_X, top + PAD_Y - bm.ascent, big);
        if (!title.isEmpty())
            c.drawText(title, left + PAD_X,
                    top + PAD_Y + (bm.descent - bm.ascent) - sm.ascent, small);
    }
}
