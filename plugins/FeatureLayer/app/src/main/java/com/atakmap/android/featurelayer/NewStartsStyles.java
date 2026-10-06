package com.atakmap.android.featurelayer;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;

import com.atakmap.coremap.log.Log;
import com.atakmap.map.layer.feature.style.IconPointStyle;
import com.atakmap.map.layer.feature.style.Style;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.util.Locale;

/**
 * New Fire Starts on DART's disc, at DART's size: a red flame for a wildfire, a green RX
 * for a prescribed fire (operator, 2026-10-05: "a different icon for a fire, same size
 * as the dart icons, like a flame, and RX for a prescribed fire, prescribed green, red
 * for wildfire"). NIFC's own page draws red and orange circles sized by acres; the
 * first build did that, and at a glance a circle said nothing.
 *
 * <p>A wildfire with no size reported is its own type, drawn as the flame's outline. On 2026-10-05, 18 of 105 starts were LA County Fire dispatch records,
 * created by its CAD and never touched again: a dispatch number for a name, 0.01 acres
 * as a placeholder, no size, no containment, no update. They are real IRWIN records and
 * none was older than the service's 24 hours, but nothing was ever reported about them,
 * so the operator can see them for what they are and turn them off.
 */
public final class NewStartsStyles {

    private static final String TAG = "FeatureLayer";

    public static final String WILDFIRE = "Wildfire";
    public static final String NO_SIZE = "Wildfire, No Size Yet";
    public static final String PRESCRIBED = "Prescribed Fire";

    /** Bumped when the drawing changes, so an older composite is never reused. */
    private static final int V = 3;

    private NewStartsStyles() {
    }

    /** New Fire Starts and Ongoing Fires: the same IRWIN incident records, drawn the same way. */
    public static boolean handles(LayerSpec spec) {
        return spec != null && ("newstarts".equals(spec.iconSet) || "ongoing".equals(spec.iconSet));
    }

    /** The three types, in the order the Features list shows them, each with what it is. */
    static void notes(LayerSpec s) {
        s.setNotes.put(WILDFIRE, "Wildfires with a size reported");
        s.setNotes.put(NO_SIZE, "Wildfires reported with no size and nothing added since");
        s.setNotes.put(PRESCRIBED, "Planned burns");
        for (String n : s.setNotes.keySet())
            s.setKind.put(n, "point");
    }

    /**
     * How long a fire with no name stays (operator, 2026-10-05: "anything after 4 hours
     * without a name you dump"). A dispatch system that files every brush-fire call and
     * never comes back leaves a dispatch number where the name goes: LA County's CAD
     * filed 49 of the 62 unnamed fires among 401 current ones that evening (LAC-357251,
     * 0.01 ac, never touched again), and NIFC itself only lets them go after 3 days.
     */
    public static final long UNNAMED_KEEP_MS = 4 * 3600_000L;
    private static final java.util.regex.Pattern NUMBER_ONLY =
            java.util.regex.Pattern.compile("^\\s*[A-Za-z]{0,3}\\s*[-#]?\\s*\\d+\\s*$");

    /**
     * Whether a fire has no name, only a number: blank, "LAC-359920", "0881", "FA #53",
     * "RU 64", "H2". A county and a number ("Milam 9199") or a mile marker ("313 MM 13")
     * is a name someone gave it.
     */
    static boolean unnamed(JSONObject props) {
        final String n = props == null || props.isNull("IncidentName") ? "" : props.optString("IncidentName", "").trim();
        return n.isEmpty() || "null".equalsIgnoreCase(n) || NUMBER_ONLY.matcher(n).matches();
    }

    /** When the fire was found, epoch ms: discovery time, else when its record was made; 0 when neither. */
    static long discoveredMs(JSONObject props) {
        if (props == null)
            return 0;
        for (String k : new String[] { "FireDiscoveryDateTime", "CreatedOnDateTime_dt" }) {
            final Object v = props.opt(k);
            if (v instanceof Number && ((Number) v).longValue() > 0)
                return ((Number) v).longValue();
        }
        return 0;
    }

    /** The acres the start is reported at, or -1 when it has none. */
    static double acres(JSONObject props) {
        if (props == null || props.isNull("IncidentSize"))
            return -1;
        final double a = props.optDouble("IncidentSize", -1);
        return a > 0 ? a : -1;
    }

    static boolean prescribed(JSONObject props) {
        return props != null && "RX".equalsIgnoreCase(props.optString("IncidentTypeCategory", ""));
    }

    /** Which of the three types a start is. */
    public static String type(JSONObject props) {
        if (prescribed(props))
            return PRESCRIBED;
        return acres(props) > 0 ? WILDFIRE : NO_SIZE;
    }

    /** "Ridge · 12 ac", "LAC-358073" when there is no size, the type when there is no name. */
    public static String title(JSONObject props, String fallback) {
        String name = props == null || props.isNull("IncidentName") ? "" : props.optString("IncidentName", "").trim();
        if (name.isEmpty() || "null".equalsIgnoreCase(name))
            name = fallback != null && !fallback.isEmpty() ? fallback : type(props);
        final double a = acres(props);
        final StringBuilder b = new StringBuilder(name);
        if (a > 0)
            b.append(" \u00b7 ").append(formatAcres(a));
        // How much is contained, the number an ongoing fire is followed by. A fire at 100%
        // stays listed until someone declares it contained, so it says 100%.
        if (props != null && !props.isNull("PercentContained")) {
            final double pct = props.optDouble("PercentContained", -1);
            if (pct >= 0 && pct <= 100)
                b.append(" \u00b7 ").append(Math.round(pct)).append("%");
        }
        return b.toString();
    }

    /**
     * "0.01 ac", "0.5 ac", "2.2 ac", "1,240 ac". A spot fire is reported at a hundredth
     * of an acre, and one decimal printed it as "0.0 ac" (LION, 2026-10-05).
     */
    static String formatAcres(double a) {
        if (a >= 10)
            return String.format(Locale.US, "%,d ac", Math.round(a));
        String s = String.format(Locale.US, a < 1 ? "%.2f" : "%.1f", a);
        while (s.contains(".") && (s.endsWith("0") || s.endsWith(".")))
            s = s.substring(0, s.length() - 1);
        return s + " ac";
    }

    /** DART's marker edge, so a fire start sits beside a DART engine at the same size (DartStyles.PX). */
    static final float PX = 32f;
    /** DART's canvas and disc: near-black, light edge, the glyph inside. */
    private static final int CANVAS = 96, DISC = 0xD9101010, EDGE = 0xFFE6E6E6;
    /** A wildfire's flame; brighter than NIFC's pure red, which goes muddy on the dark disc. */
    private static final int FLAME = 0xFFFF3B2F;
    /** A prescribed fire's RX: the pane's ON green (operator, 2026-10-05: "prescribed is green, red for wildfire"). */
    private static final int RX_GREEN = 0xFF3DDC61;

    /** The start's marker, composed once per kind; null when it cannot be drawn. */
    static Style style(JSONObject props, File iconDir) {
        final String type = type(props);
        final String kind = PRESCRIBED.equals(type) ? "rx" : NO_SIZE.equals(type) ? "wf_nosize" : "wf";
        final File f = marker(kind, iconDir);
        if (f == null)
            return null;
        // Level when the map turns, like every icon this plugin draws (see NwcgStyles.point).
        return new IconPointStyle(0xFFFFFFFF, "file://" + f.getAbsolutePath(), PX, PX, 0, 0, 0f, false);
    }

    /**
     * A flame, Atmosphere's fire weather glyph (ic_layer_firewx, our own drawing), in its
     * 24-unit box: the body with a round base and an inner tongue cut out of it.
     */
    private static Path flame() {
        final Path p = new Path();
        p.setFillType(Path.FillType.EVEN_ODD);
        p.moveTo(13f, 2f);
        p.cubicTo(13.5f, 5.5f, 11f, 6.5f, 10.5f, 9f);
        p.cubicTo(10f, 7.5f, 9f, 7f, 8f, 6.5f);
        p.cubicTo(9.5f, 9f, 6.5f, 11f, 6.5f, 14.5f);
        p.arcTo(new RectF(6.5f, 9f, 17.5f, 20f), 180f, -180f); // the round base
        p.cubicTo(17.5f, 11.5f, 15.5f, 10f, 15f, 7.5f);
        p.cubicTo(14.8f, 9f, 14f, 9.8f, 13.5f, 10.5f);
        p.cubicTo(14.5f, 7.5f, 12.5f, 4f, 13f, 2f);
        p.close();
        p.moveTo(12f, 12f);
        p.cubicTo(11f, 13.5f, 10.2f, 14.3f, 10.2f, 15.5f);
        p.arcTo(new RectF(10.2f, 13.7f, 13.8f, 17.3f), 180f, -180f);
        p.cubicTo(13.8f, 14.3f, 13f, 13.5f, 12f, 12f);
        p.close();
        return p;
    }

    private static synchronized File marker(String kind, File iconDir) {
        if (iconDir == null)
            return null;
        final File out = new File(iconDir, "ns" + V + "_" + kind + ".png");
        if (out.isFile())
            return out;
        try {
            final Bitmap bmp = Bitmap.createBitmap(CANVAS, CANVAS, Bitmap.Config.ARGB_8888);
            final Canvas c = new Canvas(bmp);
            final float mid = CANVAS / 2f;
            final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            p.setStyle(Paint.Style.FILL);
            p.setColor(DISC);
            c.drawCircle(mid, mid, mid - 3f, p);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(4f);
            p.setColor(EDGE);
            c.drawCircle(mid, mid, mid - 3f, p);
            if ("rx".equals(kind)) {
                p.setStyle(Paint.Style.FILL);
                p.setColor(RX_GREEN);
                p.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
                p.setTextAlign(Paint.Align.CENTER);
                p.setTextSize(CANVAS * 0.42f);
                final android.graphics.Rect b = new android.graphics.Rect();
                p.getTextBounds("RX", 0, 2, b);
                c.drawText("RX", mid, mid + b.height() / 2f, p);
            } else {
                // The flame's 18-unit height at 72% of the canvas, its box centered on the
                // disc: DART fits its glyphs to 74%, and a flame is narrower than a truck.
                final float sc = CANVAS * 0.72f / 18f;
                final Path f = flame();
                final Matrix m = new Matrix();
                m.setTranslate(-12f, -11f);
                m.postScale(sc, sc);
                m.postTranslate(mid, mid);
                f.transform(m);
                p.setColor(FLAME);
                if ("wf_nosize".equals(kind)) {
                    // Reported and nothing added since: the flame's outline only.
                    p.setStyle(Paint.Style.STROKE);
                    p.setStrokeWidth(5f);
                    p.setStrokeJoin(Paint.Join.ROUND);
                } else {
                    p.setStyle(Paint.Style.FILL);
                }
                c.drawPath(f, p);
            }
            final File tmp = new File(out.getPath() + ".tmp");
            final FileOutputStream o = new FileOutputStream(tmp);
            try {
                bmp.compress(Bitmap.CompressFormat.PNG, 100, o);
            } finally {
                o.close();
                bmp.recycle();
            }
            //noinspection ResultOfMethodCallIgnored
            tmp.renameTo(out);
            return out.isFile() ? out : null;
        } catch (Exception e) {
            Log.w(TAG, "new start marker " + kind, e);
            return null;
        }
    }
}
