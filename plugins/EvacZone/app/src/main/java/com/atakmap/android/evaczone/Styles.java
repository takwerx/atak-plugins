package com.atakmap.android.evaczone;

import com.atakmap.map.layer.feature.style.BasicStrokeStyle;
import com.atakmap.map.layer.feature.style.CompositeStyle;
import com.atakmap.map.layer.feature.style.LabelPointStyle;
import com.atakmap.map.layer.feature.style.PatternStrokeStyle;
import com.atakmap.map.layer.feature.style.Style;

/** The few ATAK feature styles the renderer translation needs. */
final class Styles {
    private Styles() {
    }

    /** 16-bit pixel patterns; {@code factor} is pixels per bit. */
    static final short DASH = (short) 0xF0F0;      // 8 on, 8 off
    static final short DOT = (short) 0xAAAA;       // 1 on, 1 off
    static final short DASH_DOT = (short) 0xFC30;  // 6 on, 4 off, 2 on, 4 off

    /** A zone's name as a label point: white on a dark pill, no icon. */
    static Style label(String text) {
        return new LabelPointStyle(text, 0xFFFFFFFF, 0xA0000000, LabelPointStyle.ScrollMode.OFF, 0f, 0, 100, 0f, false);
    }

    static Style solid(int color, float width) {
        return new BasicStrokeStyle(color, width);
    }

    static Style dashed(int color, short pattern, int factor, float width) {
        final float a = ((color >>> 24) & 0xFF) / 255f;
        final float r = ((color >>> 16) & 0xFF) / 255f;
        final float g = ((color >>> 8) & 0xFF) / 255f;
        final float b = (color & 0xFF) / 255f;
        return new PatternStrokeStyle(factor, pattern, r, g, b, a, width);
    }

    /**
     * The style plus an empty label: the feature keeps its name for the tap chooser and
     * details, but ATAK draws the style's label text (nothing) instead of the name along
     * every line.
     */
    static Style silentLabel(Style s) {
        return plus(s, new LabelPointStyle("", 0, 0, LabelPointStyle.ScrollMode.OFF));
    }

    /**
     * The style plus a label with no text of its own, white on a dark pill. On a
     * geometry that is a polygon and its center point in one collection, the point
     * child draws the feature's name in it and the polygon child, which labels only
     * from a label style's own text, stays silent. One feature, one label, at the center.
     */
    static Style withNameLabel(Style s) {
        return plus(s, new LabelPointStyle("", 0xFFFFFFFF, 0xA0000000, LabelPointStyle.ScrollMode.OFF, 0f, 0, 100, 0f, false));
    }

    private static Style plus(Style s, Style extra) {
        if (s instanceof CompositeStyle) {
            final CompositeStyle cs = (CompositeStyle) s;
            final Style[] all = new Style[cs.getNumStyles() + 1];
            for (int i = 0; i < cs.getNumStyles(); i++)
                all[i] = cs.getStyle(i);
            all[all.length - 1] = extra;
            return new CompositeStyle(all);
        }
        return new CompositeStyle(new Style[] { s, extra });
    }
}
