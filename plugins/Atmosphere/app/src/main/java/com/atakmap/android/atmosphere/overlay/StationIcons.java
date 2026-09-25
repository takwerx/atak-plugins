package com.atakmap.android.atmosphere.overlay;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;

import com.atakmap.android.atmosphere.data.WindBarb;
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
 * anemometer inside the disc is white below criteria, yellow when it is flirting with
 * them and red when it is hitting them -- the Fire Weather Snooper's own three. That is the Comms convention -- there a repeater's own marks light up when a
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
    private static final int VERSION = 8;

    /** Below criteria: the symbol as it normally reads. */
    static final int NORMAL = 0xFFFFFFFF;
    /**
     * Flirting with its criteria. Yellow, not amber: the Fire Weather Snooper's own
     * legend is "yellow -- station is flirting with Red Flag Criteria", and a crew
     * that reads the Snooper should not have to learn a second palette here
     * (operator, 2026-09-25: "yellow not amber man follow what fire snooper has").
     */
    static final int NEAR = 0xFFFFEB3B;
    /** Hitting its Red Flag criteria. The Snooper's red. */
    static final int CRITICAL = 0xFFE53935;

    /** The barb, and the outline that keeps it visible on anything. */
    private static final int BARB = 0xFF1B3B8B;
    private static final int BARB_EDGE = 0xFFFFFFFF;

    /** The disc the barb turns on: the NWCG symbol's own blue, with a white edge. */
    private static final int DISC = 0xFF1B3B8B;
    private static final int DISC_EDGE = 0xFFFFFFFF;

    private static final int LABEL_BG = 0x99000000;
    private static final int PAD_X = 6, PAD_Y = 3, GAP = 3, RADIUS = 4;

    /**
     * Device-independent sizes, scaled by ATAK's own display scaling. The disc is the
     * width the NWCG diamond was, so the symbol did not shrink when it became round.
     */
    private static final float DISC_R = 17f;
    /**
     * How much of the disc the anemometer spans, edge to edge inside the ring. Near
     * enough to 1 that the symbol reads as a weather station at a glance rather than
     * as a small mark inside a big circle -- the same reason a toolbar glyph fills its
     * square (operator, 2026-09-25: "the inner weather station icon should fill the
     * circle").
     */
    private static final float GLYPH_FILL = 0.80f;
    /** How far the staff reaches past the disc, and how long a full feather is. */
    private static final float STAFF = 38f, FEATHER = 17f, FEATHER_GAP = 8.5f;
    /**
     * How far a feather is swept back toward the station, in degrees off square.
     * Drawn at a right angle they read as the bit of a key rather than as a barb.
     */
    private static final float SWEEP_DEG = 22f;

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

    /**
     * What a color means, in the Snooper's own words. These are also the feature set
     * names, so Overlay Manager reads the same way the Snooper's legend does.
     */
    static String stateLabel(int color) {
        if (color == CRITICAL)
            return "Hitting Red Flag criteria";
        if (color == NEAR)
            return "Flirting with Red Flag criteria";
        return "Below criteria";
    }

    /**
     * One station's symbol, readings and name, composed once and kept.
     *
     * @param name      the station's name, drawn under the readings
     * @param speed     sustained wind in the operator's unit, or NaN
     * @param gust      the gust in the same unit, or NaN when there is none
     * @param unit      what that speed is in, e.g. "mph"
     * @param humidity  relative humidity in percent, or NaN
     * @param windFrom  degrees the wind is coming from, or NaN for no barb
     * @param knots     the same wind in knots, which is what the feathers count in
     * @param state     {@link #NORMAL}, {@link #NEAR} or {@link #CRITICAL}
     */
    Composed compose(String name, double speed, double gust, String unit,
            double humidity, double windFrom, double knots, int state,
            boolean withLabel) {
        final float scale = Math.max(1f,
                gov.tak.api.commons.graphics.DisplaySettings.getRelativeScaling());
        final MapTextFormat tf = MapView.getDefaultTextFormat();
        float textPx = tf == null ? 0f : tf.getDensityAdjustedFontSize();
        if (textPx <= 0f)
            textPx = 14f * scale;

        final String readings = withLabel ? readings(speed, gust, unit, humidity) : "";
        final String title = !withLabel || name == null ? "" : name.trim();
        // The feathers change only in steps of five knots, so that is what the name
        // needs to carry -- not the raw speed, which would be a new file every hour.
        final long fives = Double.isNaN(knots) ? -1 : Math.round(knots / 5.0);
        final String key = (withLabel ? "L" : "n")
                + Integer.toHexString((readings + "|" + title).hashCode())
                + "_" + bucket(windFrom) + "_k" + fives + "_" + Integer.toHexString(state)
                + "_v" + VERSION + "_s" + Math.round(scale * 100)
                + "_f" + Math.round(textPx * 10);
        final Composed hit = cache.get(key);
        if (hit != null)
            return hit;

        final Paint big = text(tf, textPx, true);
        final Paint small = text(tf, textPx * 0.85f, false);
        final Paint.FontMetricsInt bm = big.getFontMetricsInt();
        final Paint.FontMetricsInt sm = small.getFontMetricsInt();
        final int lineOne = readings.isEmpty() ? 0 : bm.descent - bm.ascent;
        final int lineTwo = title.isEmpty() ? 0 : sm.descent - sm.ascent;
        final int textW = (int) Math.ceil(Math.max(big.measureText(readings),
                title.isEmpty() ? 0 : small.measureText(title)));
        final boolean hasPill = lineOne + lineTwo > 0;
        final int pillW = hasPill ? textW + 2 * PAD_X : 0;
        final int pillH = hasPill ? lineOne + lineTwo + 2 * PAD_Y : 0;

        // The pill goes on the side the barb is not, tucked against the disc.
        //
        // It used to hang below the barb's whole sweep, which is the only place that
        // is clear of a staff that could point anywhere -- and that left the name
        // stranded a barb's length from the station it belongs to (operator,
        // 2026-09-25: "can the label just automatically be on the opposite side of the
        // wind so it can be close to the icon?"). The wind direction is known when
        // this is drawn, so the one place the barb is guaranteed not to be is
        // directly opposite it.
        float ox = 0f, oy = 1f;             // below, when there is no wind to oppose
        if (!Double.isNaN(windFrom) && !WindBarb.isCalm(knots)) {
            final double rad = Math.toRadians(windFrom);
            ox = -(float) Math.sin(rad);
            oy = (float) Math.cos(rad);
        }
        // Far enough out that the pill's own box clears the disc in that direction.
        final float away = !hasPill ? 0f : DISC_R * scale + GAP
                + (Math.abs(ox) * pillW + Math.abs(oy) * pillH) / 2f;
        // The disc plus its staff, so nothing is clipped at any angle.
        final int reach = Math.round((DISC_R + STAFF + FEATHER) * scale) + 4;
        // Symmetric about the disc, so the disc lands on the station rather than the
        // middle of the drawing.
        final int halfW = (int) Math.ceil(Math.max(reach,
                Math.abs(ox) * away + pillW / 2f)) + 2;
        final int halfH = (int) Math.ceil(Math.max(reach,
                Math.abs(oy) * away + pillH / 2f)) + 2;
        final int w = 2 * halfW, h = 2 * halfH;
        final int cx = halfW, cy = halfH;
        final float pillCx = cx + ox * away, pillCy = cy + oy * away;

        final File out = new File(dir, "wx_" + key + ".png");
        if (!out.isFile() && !draw(out, w, h, cx, cy, scale, windFrom, knots, state,
                readings, title, big, small, bm, sm, pillW, pillH, pillCx, pillCy))
            return null;
        // Composed at device pixels and asked back at the same pixels, so nothing is
        // resampled: ATAK scales an icon by dp, and these are already scaled.
        final Composed made = new Composed("file://" + out.getAbsolutePath(),
                Math.round(w / scale), Math.round(h / scale));
        cache.put(key, made);
        return made;
    }

    /**
     * "6 mph · 37%", or "20G28 mph · 42%" when the station is gusting.
     *
     * <p>The gust is shown because it is what decides the color: the state is taken
     * from the strongest wind a station has, gust included. With only the sustained
     * wind printed, an operator reads "20 mph" beside a yellow symbol and has nothing
     * to reconcile it with. The numbers have to be able to explain the color.
     *
     * <p>Written the way a forecast writes it -- sustained, G, gust -- rather than as
     * another field, which is a whole column for something most stations are not
     * doing at any given hour.
     */
    private static String readings(double speed, double gust, String unit,
            double humidity) {
        final StringBuilder b = new StringBuilder(value(speed));
        if (!Double.isNaN(gust) && !Double.isNaN(speed)
                && Math.round(gust) > Math.round(speed))
            b.append('G').append(value(gust));
        if (unit != null && !unit.isEmpty())
            b.append(' ').append(unit);
        return b + "  ·  " + value(humidity) + "%";
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
            double windFrom, double knots, int state, String readings, String title,
            Paint big, Paint small, Paint.FontMetricsInt bm, Paint.FontMetricsInt sm,
            int pillW, int pillH, float pillCx, float pillCy) {
        Bitmap bmp = null;
        try {
            bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            final Canvas c = new Canvas(bmp);
            final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);

            barb(c, p, cx, cy, scale, windFrom, knots);
            disc(c, p, cx, cy, scale);
            anemometer(c, p, cx, cy, scale, state);
            pill(c, p, pillCx, pillCy, pillW, pillH, readings, title, big, small, bm, sm);

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
     * A wind barb, drawn the way every station plot draws one.
     *
     * <p>The staff points toward where the wind is blowing <b>from</b>, so a north
     * wind puts it at the top, and the feathers at its far end give the speed:
     * <b>a short feather is 5 knots, a long one 10, a solid triangle 50</b>, added
     * together, with the speed rounded to the nearest 5. Calm is the bare disc with no
     * staff at all. That is the convention a fire weather forecaster already reads and
     * it is not ours to reinterpret (operator, 2026-09-25, with windy.app's guide).
     *
     * <p><b>The feathers are knots, and the pill is in the operator's own unit.</b>
     * Those disagree by a factor of 1.15 and both are correct: a barb has meant knots
     * since before anyone read one on a phone, and the number beside it is what a
     * crew says out loud. The layer says which is which under its legend.
     *
     * <p>Feathers sit on the counter-clockwise side of the staff, the northern
     * hemisphere convention, and are laid from the far end inward: triangles first,
     * then full feathers, then a half.
     *
     * <p>White underneath and blue on top: one stroke laid over a wider one is an
     * outline, and it is what keeps the whole barb readable over water, snow and
     * shaded relief without putting a halo behind the symbol.
     */
    private void barb(Canvas c, Paint p, int cx, int cy, float scale, double windFrom,
            double knots) {
        if (Double.isNaN(windFrom) || WindBarb.isCalm(knots))
            return;                     // calm, or as good as: the disc says it alone

        final double r = Math.toRadians(windFrom);
        // Along the staff, pointing out of the disc toward where the wind is from.
        final float ux = (float) Math.sin(r), uy = (float) -Math.cos(r);
        // The counter-clockwise perpendicular, which is the side feathers go on,
        // swept back toward the station so the barb reads as one.
        final double sweep = Math.toRadians(SWEEP_DEG);
        final float cos = (float) Math.cos(sweep), sin = (float) Math.sin(sweep);
        final float px = uy * cos - ux * sin, py = -ux * cos - uy * sin;

        final float from = DISC_R * scale;
        final float to = (DISC_R + STAFF) * scale;
        final Path lines = new Path();
        lines.moveTo(cx + ux * from, cy + uy * from);
        lines.lineTo(cx + ux * to, cy + uy * to);

        final WindBarb.Feathers f = WindBarb.of(knots);
        final int flags = f.flags, fulls = f.fulls, halves = f.halves;

        final float gap = FEATHER_GAP * scale;
        final float full = FEATHER * scale;
        float at = to;
        final java.util.List<float[]> triangles = new java.util.ArrayList<>();
        for (int i = 0; i < flags; i++) {
            // A triangle standing on the staff, its base along it.
            triangles.add(new float[] {
                    cx + ux * at, cy + uy * at,
                    cx + ux * (at - gap * 1.6f), cy + uy * (at - gap * 1.6f),
                    cx + ux * at + px * full, cy + uy * at + py * full });
            at -= gap * 1.9f;
        }
        for (int i = 0; i < fulls; i++) {
            lines.moveTo(cx + ux * at, cy + uy * at);
            lines.lineTo(cx + ux * at + px * full, cy + uy * at + py * full);
            at -= gap;
        }
        if (halves > 0) {
            // A half feather never sits at the very tip: at the tip it reads as a
            // full one that was drawn short.
            if (flags == 0 && fulls == 0)
                at -= gap;
            lines.moveTo(cx + ux * at, cy + uy * at);
            lines.lineTo(cx + ux * at + px * full * 0.5f, cy + uy * at + py * full * 0.5f);
        }

        p.setStyle(Paint.Style.STROKE);
        p.setStrokeCap(Paint.Cap.ROUND);
        p.setStrokeJoin(Paint.Join.ROUND);
        for (int pass = 0; pass < 2; pass++) {
            final boolean outline = pass == 0;
            p.setColor(outline ? BARB_EDGE : BARB);
            // Thinner than the staff's own weight would suggest: a feather is short,
            // and a heavy stroke on a short line closes the gap to the next one.
            p.setStrokeWidth((outline ? 5.2f : 2.6f) * scale);
            p.setStyle(Paint.Style.STROKE);
            c.drawPath(lines, p);
            for (float[] t : triangles) {
                final Path tri = new Path();
                tri.moveTo(t[0], t[1]);
                tri.lineTo(t[2], t[3]);
                tri.lineTo(t[4], t[5]);
                tri.close();
                // Stroked first at the outline width, then filled, so the triangle
                // carries the same white edge the staff does.
                c.drawPath(tri, p);
                final Paint fill = new Paint(p);
                fill.setStyle(Paint.Style.FILL);
                c.drawPath(tri, fill);
            }
        }
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
     * The NWCG weather unit's mark -- a mast with a cup on top and two on a crossarm --
     * in the state's color. This is the part that turns yellow and red.
     *
     * <p>Every measurement is a fraction of the disc's radius, so the glyph fills the
     * disc at any size and shrinking the symbol shrinks the mark with it. It was drawn
     * at fixed pixels, which left it adrift in the middle of the circle.
     */
    private void anemometer(Canvas c, Paint p, int cx, int cy, float scale, int state) {
        final float r = DISC_R * scale;
        final float g = r * GLYPH_FILL;     // the glyph's half extent inside the ring
        final float cup = g * 0.26f;
        final float arm = g - cup;          // so a cup's edge lands on the glyph's edge

        p.setColor(state);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(Math.max(1.6f, r * 0.15f));
        p.setStrokeCap(Paint.Cap.ROUND);
        // The mast, from under the top cup down to the foot.
        c.drawLine(cx, cy - g + cup, cx, cy + g * 0.82f, p);
        // The crossarm, sitting a little below center the way the symbol has it.
        c.drawLine(cx - arm, cy + g * 0.10f, cx + arm, cy + g * 0.10f, p);

        p.setStyle(Paint.Style.FILL);
        c.drawCircle(cx, cy - g + cup, cup, p);
        c.drawCircle(cx - arm, cy + g * 0.10f, cup * 0.88f, p);
        c.drawCircle(cx + arm, cy + g * 0.10f, cup * 0.88f, p);
    }

    /** The readings over the station's name, in the pill the rest of the plugin uses. */
    private void pill(Canvas c, Paint p, float pillCx, float pillCy, int pillW, int pillH,
            String readings, String title, Paint big, Paint small,
            Paint.FontMetricsInt bm, Paint.FontMetricsInt sm) {
        if (pillW <= 0)
            return;
        final float left = pillCx - pillW / 2f;
        final float top = pillCy - pillH / 2f;
        p.setStyle(Paint.Style.FILL);
        p.setColor(LABEL_BG);
        c.drawRoundRect(new RectF(left, top, left + pillW, top + pillH),
                RADIUS, RADIUS, p);
        // Both lines centered on the pill: the name is shorter than the readings
        // almost always, and left-aligned under them it reads as a stray caption.
        c.drawText(readings, pillCx - big.measureText(readings) / 2f,
                top + PAD_Y - bm.ascent, big);
        if (!title.isEmpty())
            c.drawText(title, pillCx - small.measureText(title) / 2f,
                    top + PAD_Y + (bm.descent - bm.ascent) - sm.ascent, small);
    }
}
