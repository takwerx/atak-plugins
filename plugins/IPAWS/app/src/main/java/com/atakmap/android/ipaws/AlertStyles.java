package com.atakmap.android.ipaws;

import com.atakmap.map.layer.feature.style.BasicFillStyle;
import com.atakmap.map.layer.feature.style.BasicPointStyle;
import com.atakmap.map.layer.feature.style.BasicStrokeStyle;
import com.atakmap.map.layer.feature.style.CompositeStyle;
import com.atakmap.map.layer.feature.style.LabelPointStyle;
import com.atakmap.map.layer.feature.style.Style;

/**
 * Severity drawn as color, which is the one thing about an alert that has to read
 * before it is tapped.
 *
 * <p>Fills are kept faint on purpose. Alert areas overlap constantly -- a county can
 * be inside a Red Flag Warning, a Heat Advisory and an Air Quality Alert at once --
 * and three solid fills stacked on a basemap hide the ground the operator is actually
 * looking at. The edge carries the signal, the fill only says which side of it you
 * are on.
 */
public final class AlertStyles {

    private AlertStyles() {
    }

    private static final int EXTREME = 0xFFE01020;
    private static final int SEVERE = 0xFFF07000;
    private static final int MODERATE = 0xFFF0C000;
    private static final int MINOR = 0xFF40B0F0;
    private static final int UNKNOWN = 0xFFB0B0B0;

    private static final int LABEL_TEXT = 0xFFFFFFFF;
    private static final int LABEL_BACKING = 0xA0000000;

    /** Edge at full alpha over a fill at this much of it. */
    private static final int FILL_ALPHA = 0x38;

    public static int color(String severity) {
        if (severity == null)
            return UNKNOWN;
        if ("Extreme".equalsIgnoreCase(severity))
            return EXTREME;
        if ("Severe".equalsIgnoreCase(severity))
            return SEVERE;
        if ("Moderate".equalsIgnoreCase(severity))
            return MODERATE;
        if ("Minor".equalsIgnoreCase(severity))
            return MINOR;
        return UNKNOWN;
    }

    /**
     * Fill under edge. <b>No label</b>: ATAK renders a label on a polygon along its
     * boundary, rotated with the edge, which reads as a name for a line rather than
     * for the area. The label is a separate point feature at the middle of the shape
     * -- see {@link #label} and {@code Geo.labelPoint}.
     */
    public static Style area(String severity) {
        final int c = color(severity);
        final int fill = (FILL_ALPHA << 24) | (c & 0x00FFFFFF);
        final float width = "Extreme".equalsIgnoreCase(severity)
                || "Severe".equalsIgnoreCase(severity) ? 4f : 3f;
        return new CompositeStyle(new Style[] {
                new BasicFillStyle(fill),
                new BasicStrokeStyle(c, width) });
    }

    /**
     * For an alert with no polygon at all -- no geometry of its own and no zone that
     * resolved. It is still an alert, and dropping it silently would be the worst
     * kind of wrong.
     */
    public static Style point(String severity, String label) {
        return new CompositeStyle(new Style[] {
                new BasicPointStyle(color(severity), 12f),
                label(label) });
    }

    /** The label alone, for the point at an area's middle. No dot: just the words. */
    public static Style label(String text) {
        // Scroll OFF: an alert name scrolling along a county border is unreadable on
        // a vehicle mount. 0f rotation, no rotate-with-map.
        return new LabelPointStyle(text == null ? "" : text, LABEL_TEXT, LABEL_BACKING,
                LabelPointStyle.ScrollMode.OFF, 0f, 0, 0, 0f, false);
    }
}
