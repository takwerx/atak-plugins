package com.atakmap.android.featurelayer;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;

import com.atakmap.coremap.log.Log;
import com.atakmap.map.layer.feature.style.IconPointStyle;
import com.atakmap.map.layer.feature.style.Style;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.util.Locale;

/**
 * New Fire Starts, as NIFC draws them on its own "New Starts" page: wildfires red,
 * prescribed fires orange, circles sized by acres. The colors and the size rule are the
 * service's renderer (read 2026-10-05): uniqueValue on IncidentTypeCategory, WF
 * 255,0,0 and RX 255,170,0, sizeInfo 6 to 18 pt over 1 to 10,000 acres.
 *
 * <p>One thing NIFC does not do: a wildfire with no size reported is its own type, drawn
 * as a red ring. On 2026-10-05, 18 of 105 starts were LA County Fire dispatch records,
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

    private static final int RED = 0xFFFF0000, ORANGE = 0xFFFFAA00;
    /** Bumped when the drawing changes, so an older composite is never reused. */
    private static final int V = 1;

    private NewStartsStyles() {
    }

    public static boolean handles(LayerSpec spec) {
        return spec != null && "newstarts".equals(spec.iconSet);
    }

    /** The three types, in the order the Features list shows them, each with what it is. */
    static void notes(LayerSpec s) {
        s.setNotes.put(WILDFIRE, "Wildfires with a size reported");
        s.setNotes.put(NO_SIZE, "Wildfires reported with no size and nothing added since");
        s.setNotes.put(PRESCRIBED, "Planned burns");
        for (String n : s.setNotes.keySet())
            s.setKind.put(n, "point");
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
        return a > 0 ? name + " · " + formatAcres(a) : name;
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

    /**
     * NIFC's size rule in points (6 to 18 over 1 to 10,000 acres, linear, clamped), drawn
     * at 2.5 times that in dp: the service's 6 pt is a speck over imagery on a phone,
     * which is what DART's own 4 pt dots were. Snapped to even dp so a day of starts
     * makes a handful of files, not one per acreage.
     */
    static int sizeDp(double acres) {
        final double a = acres > 0 ? acres : 1;
        final double pt = 6 + Math.max(0, Math.min(1, (a - 1) / (10000.1 - 1))) * 12;
        return 2 * (int) Math.round(pt * 2.5 / 2);
    }

    /** The start's circle, composed once per color, kind and size; null when it cannot be drawn. */
    static Style style(JSONObject props, File iconDir) {
        final boolean ring = NO_SIZE.equals(type(props));
        final int color = prescribed(props) ? ORANGE : RED;
        final int dp = sizeDp(acres(props));
        final float scale = gov.tak.api.commons.graphics.DisplaySettings.getRelativeScaling();
        final File f = disc(color, ring, dp, scale, iconDir);
        if (f == null)
            return null;
        // Level when the map turns, like every icon this plugin draws (see NwcgStyles.point).
        return new IconPointStyle(0xFFFFFFFF, "file://" + f.getAbsolutePath(), dp, dp, 0, 0, 0f, false);
    }

    private static File disc(int color, boolean ring, int dp, float scale, File iconDir) {
        if (iconDir == null)
            return null;
        final File out = new File(iconDir, String.format(Locale.US, "ns%d_%08x_%s_%d_s%d.png", V, color,
                ring ? "ring" : "disc", dp, Math.round(scale * 100)));
        if (out.isFile())
            return out;
        try {
            final int px = Math.max(8, Math.round(dp * scale));
            final Bitmap bmp = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888);
            final Canvas c = new Canvas(bmp);
            final float r = px / 2f;
            final float edge = Math.max(1.5f, 1.5f * scale);
            final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            // A thin light edge, NIFC's own white outline made strong enough to read over imagery.
            p.setStyle(Paint.Style.FILL);
            p.setColor(0x99FFFFFF);
            c.drawCircle(r, r, r, p);
            if (ring) {
                // Nothing reported but the call: a dark center inside a red ring.
                p.setColor(color);
                c.drawCircle(r, r, r - edge, p);
                p.setColor(0xB0000000);
                c.drawCircle(r, r, (r - edge) * 0.55f, p);
            } else {
                p.setColor(color);
                c.drawCircle(r, r, r - edge, p);
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
            Log.w(TAG, "new start symbol " + out.getName(), e);
            return null;
        }
    }
}
