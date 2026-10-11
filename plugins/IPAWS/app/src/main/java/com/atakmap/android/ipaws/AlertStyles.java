package com.atakmap.android.ipaws;

import com.atakmap.android.ipaws.data.EventColors;
import com.atakmap.map.layer.feature.style.BasicFillStyle;
import com.atakmap.map.layer.feature.style.BasicPointStyle;
import com.atakmap.map.layer.feature.style.BasicStrokeStyle;
import com.atakmap.map.layer.feature.style.CompositeStyle;
import com.atakmap.map.layer.feature.style.LabelPointStyle;
import com.atakmap.map.layer.feature.style.Style;

/**
 * What an alert is, drawn as NWS's own color for it -- the one thing about an alert
 * that has to read before it is tapped. The colors are {@link EventColors}, and the
 * pane's map key is built from the same call, so the key and the map cannot disagree.
 *
 * <p>Not severity. CAP severity reads as the warning / watch / advisory tier and does
 * not track it: every Watch is Severe while most Warnings are Moderate. Coloring by it
 * put a Watch above a Warning, and so would drawing it thicker, which is why every
 * edge is the same width.
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

    private static final int LABEL_TEXT = 0xFFFFFFFF;
    private static final int LABEL_BACKING = 0xA0000000;

    /** Edge at full alpha over a fill at this much of it. */
    private static final int FILL_ALPHA = 0x38;

    private static final float EDGE_WIDTH = 3f;

    /** The color an alert of this event type is drawn in, everywhere it is drawn. */
    public static int color(String event) {
        return EventColors.color(event);
    }

    /** The faint fill that goes with {@link #color}. */
    private static int fill(String event) {
        return (FILL_ALPHA << 24) | (color(event) & 0x00FFFFFF);
    }

    /**
     * Fill under edge. <b>No label</b>: ATAK renders a label on a polygon along its
     * boundary, rotated with the edge, which reads as a name for a line rather than
     * for the area. The label is a separate point feature at the middle of the shape
     * -- see {@link #label} and {@code Geo.labelPoint}.
     */
    public static Style area(String event) {
        return new CompositeStyle(new Style[] {
                new BasicFillStyle(fill(event)),
                new BasicStrokeStyle(color(event), EDGE_WIDTH) });
    }

    /**
     * For an alert with no polygon at all -- no geometry of its own and no zone that
     * resolved. It is still an alert, and dropping it silently would be the worst
     * kind of wrong.
     */
    public static Style point(String event, String label) {
        // Label to the right, its left edge on the point: centered, as on an area's
        // middle, it covered the dot entirely and the point read as a floating name
        // (s10-dev-1, 2026-09-25). Alignment is by sign only, so 1 is "right of".
        // A dark ring under the color: a tan or pale dot alone vanished on a desert
        // basemap.
        return new CompositeStyle(new Style[] {
                new BasicPointStyle(0xFF000000, 24f),
                new BasicPointStyle(color(event), 18f),
                new LabelPointStyle(label == null ? "" : label, LABEL_TEXT, LABEL_BACKING,
                        LabelPointStyle.ScrollMode.OFF, 0f, 1, 0, 0f, false) });
    }

    /** The label alone, for the point at an area's middle. No dot: just the words. */
    public static Style label(String text) {
        // Scroll OFF: an alert name scrolling along a county border is unreadable on
        // a vehicle mount. 0f rotation, no rotate-with-map.
        return new LabelPointStyle(text == null ? "" : text, LABEL_TEXT, LABEL_BACKING,
                LabelPointStyle.ScrollMode.OFF, 0f, 0, 0, 0f, false);
    }
}
