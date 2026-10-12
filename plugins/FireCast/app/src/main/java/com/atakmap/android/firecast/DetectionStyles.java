package com.atakmap.android.firecast;

import com.atakmap.map.layer.feature.style.BasicFillStyle;
import com.atakmap.map.layer.feature.style.BasicStrokeStyle;
import com.atakmap.map.layer.feature.style.CompositeStyle;
import com.atakmap.map.layer.feature.style.LabelPointStyle;
import com.atakmap.map.layer.feature.style.PatternStrokeStyle;
import com.atakmap.map.layer.feature.style.Style;

import java.util.Locale;

/**
 * What a detection looks like on the map.
 *
 * <p>Two things are encoded and nothing else, because a symbol that says four things
 * says none of them at a glance from a moving vehicle:
 *
 * <ul>
 * <li><b>Age is color</b>, on the wildland convention an operator already reads: red
 * in the last hour, through orange and amber, to a washed gray-brown at the far end of
 * the window. The same information is in the fade, so the map stays readable for
 * someone who does not separate red from orange well.
 * <li><b>Size and shape are the footprint</b>, drawn at the ground it actually covers.
 * That is the plugin's whole claim and it is not traded away for legibility.
 * </ul>
 *
 * <p>A detection the industrial mask or the feed's own class flags is drawn hollow and
 * dashed: present, findable, obviously set apart, and never silently removed.
 */
public final class DetectionStyles {

    private DetectionStyles() {
    }

    private static final int LAST_HOUR = 0xFF3020, HOURS_3 = 0xFF6A00, HOURS_6 = 0xFF9A00,
            HOURS_12 = 0xFFC400, HOURS_24 = 0xE8E040, OLDER = 0x9A8E7A;
    /** A flagged detection keeps its age color on the map but is outlined in this. */
    private static final int SUSPECT = 0xB080D0;
    /** RADR scar: burned ground, drawn as the cold thing it is. */
    private static final int BURN_SCAR = 0x8A6A4A;

    /** Dashes for a flagged detection: 8 pixels on, 8 off. */
    private static final short DASH = (short) 0xF0F0;

    /** The age band's color, no alpha. */
    public static int ageColor(long ageMillis) {
        final long h = ageMillis / 3600000L;
        if (ageMillis == Long.MAX_VALUE)
            return OLDER;
        if (h < 1)
            return LAST_HOUR;
        if (h < 3)
            return HOURS_3;
        if (h < 6)
            return HOURS_6;
        if (h < 12)
            return HOURS_12;
        if (h < 24)
            return HOURS_24;
        return OLDER;
    }

    /** What the color means, for the pane's legend and the detail panel. */
    public static String ageBand(long ageMillis) {
        if (ageMillis == Long.MAX_VALUE)
            return "age unknown";
        final long h = ageMillis / 3600000L;
        if (h < 1)
            return "within the hour";
        if (h < 3)
            return "1 to 3 hours";
        if (h < 6)
            return "3 to 6 hours";
        if (h < 12)
            return "6 to 12 hours";
        if (h < 24)
            return "12 to 24 hours";
        return "over a day";
    }

    /**
     * How far through the window a detection is: 1 the moment it was seen, 0 at the
     * far edge. Both the outline and the fill ramp down with it, so a screen of
     * detections reads as a front with an age gradient rather than a flat field.
     */
    private static double freshness(long ageMillis, long windowMillis) {
        if (ageMillis == Long.MAX_VALUE)
            return 0d;
        if (windowMillis <= 0)
            return 1d;
        return Math.max(0d, Math.min(1d, 1d - (double) ageMillis / (double) windowMillis));
    }

    public static Style forDetection(HeatDetection d, long now, long windowMillis) {
        final long age = d.ageMillis(now);
        final double fresh = freshness(age, windowMillis);
        final boolean scar = d.footprint.kind == Footprint.Kind.MAPPED
                && d.classification != null && !d.classification.toLowerCase(Locale.US).contains("intense");
        final int rgb = scar ? BURN_SCAR : ageColor(age);
        final int strokeAlpha = (int) Math.round(90 + 165 * fresh);
        final int fillAlpha = (int) Math.round(20 + 80 * fresh);

        final Style fill = new BasicFillStyle((fillAlpha << 24) | rgb);
        final Style stroke;
        if (d.suspect())
            stroke = dashed((strokeAlpha << 24) | SUSPECT, 2.5f);
        else
            stroke = new BasicStrokeStyle((strokeAlpha << 24) | rgb, 2.5f);
        // The center dot is what carries the detection at a zoom where the footprint is
        // smaller than a pixel: a 375 m box is invisible over a county. It sits in the
        // same feature, so there is still one thing to tap and one row in the chooser.
        // No center dot on anything. It was there so a 375 m box stayed visible over a
        // county, and on the 5.8 renderer it drew as a disc that hid the ground under
        // the footprint (a GOES one came out hundreds of pixels across). The operator's
        // call, 2026-10-11: the box is the detection, and the ground under it matters
        // more than a mark at a zoom nobody works at.
        // ATAK draws a feature's name along the edge of an area unless a label style says
        // otherwise, and a thousand of those makes the map unusable.
        final Style silent = new LabelPointStyle("", 0, 0, LabelPointStyle.ScrollMode.OFF);
        return new CompositeStyle(new Style[] { fill, stroke, silent });
    }

    private static Style dashed(int color, float width) {
        final float a = ((color >>> 24) & 0xFF) / 255f;
        final float r = ((color >>> 16) & 0xFF) / 255f;
        final float g = ((color >>> 8) & 0xFF) / 255f;
        final float b = (color & 0xFF) / 255f;
        return new PatternStrokeStyle(2, DASH, r, g, b, a, width);
    }

    /** The industrial mask drawn as an area, when the operator asks to see it. */
    public static Style maskStyle() {
        return new CompositeStyle(new Style[] {
                new BasicFillStyle(0x20B080D0),
                dashed(0xA0B080D0, 2f),
                new LabelPointStyle("", 0, 0, LabelPointStyle.ScrollMode.OFF) });
    }
}
