package com.atakmap.android.tfr;

import com.atakmap.map.layer.feature.AttributeSet;
import com.atakmap.map.layer.feature.geometry.LineString;
import com.atakmap.map.layer.feature.geometry.Polygon;
import com.atakmap.map.layer.feature.style.BasicFillStyle;
import com.atakmap.map.layer.feature.style.BasicStrokeStyle;
import com.atakmap.map.layer.feature.style.CompositeStyle;
import com.atakmap.map.layer.feature.style.LabelPointStyle;
import com.atakmap.map.layer.feature.style.Style;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * A parsed TFR turned into what the map draws: geometry, style, label and the source's
 * own fields.
 *
 * <p>Separate from {@link TfrOverlay} because the overlay owns the store and knows
 * nothing about airspace, and this knows about airspace and nothing about stores.
 */
public final class TfrFeatures {

    /** Edge weight. Thin enough not to swamp a small ring, thick enough to see on terrain. */
    private static final float STROKE = 3f;
    /** Enough fill to read as an area, little enough to see the map through it. */
    private static final int FILL_ALPHA = 0x33;
    /**
     * The coarsest map resolution, meters per pixel, at which an area's label still
     * draws. ATAK's own default is 14, which hides the label on anything bigger than a
     * few miles -- and a TFR is routinely a 30 NM ring, so its name would only appear
     * once it was far too big to see.
     */
    private static final double LABEL_MAX_RES = 400d;

    private TfrFeatures() {
    }

    /** Everything to draw for one TFR, one entry per area that has a ring. */
    public static List<TfrOverlay.Drawn> drawn(Tfr t, long nowMs, int activeColor,
            int upcomingColor) {
        final List<TfrOverlay.Drawn> out = new ArrayList<>();
        final List<TfrArea> areas = t.drawable();
        // Keyed on the FAA's raw type, labeled for Overlay Manager. See Drawn.setKey.
        final String setKey = t.type == null ? "" : t.type;
        final String setName = TfrTypes.label(t.type);
        for (int i = 0; i < areas.size(); i++) {
            final TfrArea a = areas.get(i);
            final boolean active = activeAt(t, a, nowMs);
            final int color = active ? activeColor : upcomingColor;
            final String name = name(t, a, areas.size(), i);
            out.add(new TfrOverlay.Drawn(setKey, setName, name, polygon(a),
                    style(color, label(t, a)), attributes(t, a, active)));
        }
        return out;
    }

    /**
     * Whether this area is in effect now.
     *
     * <p>An area with its own schedule is judged on it. Nine of the ten multi-area TFRs
     * in the national picture carry per-area windows that differ from the NOTAM's, so a
     * TFR can be half on, and the ring that is on is the ring that should be red.
     */
    public static boolean activeAt(Tfr t, TfrArea a, long nowMs) {
        if (a.effectiveMs == 0 && a.expireMs == 0)
            return t.isActive(nowMs);
        final long slop = t.windowApproximate ? 12L * 3600L * 1000L : 0L;
        final long from = a.effectiveMs == 0 ? Long.MIN_VALUE : a.effectiveMs - slop;
        final long to = a.expireMs == 0 ? Long.MAX_VALUE : a.expireMs + slop;
        return nowMs >= from && nowMs <= to;
    }

    /** <b>Longitude first.</b> Unlike {@code GeoPoint}, and nothing complains if it is not. */
    private static Polygon polygon(TfrArea a) {
        final LineString ring = new LineString(2);
        for (double[] p : a.ring)
            ring.addPoint(p[1], p[0]);
        // A ring that does not close draws as an open shape with a seam across it.
        if (!a.ring.isEmpty()) {
            final double[] first = a.ring.get(0);
            final double[] last = a.ring.get(a.ring.size() - 1);
            if (first[0] != last[0] || first[1] != last[1])
                ring.addPoint(first[1], first[0]);
        }
        return new Polygon(ring);
    }

    /**
     * A faint fill under a full-color edge, with the label inside it.
     *
     * <p>ATAK labels a point feature from its name but never a polygon, so without a
     * {@code LabelPointStyle} in the composite every TFR is an unnamed ring the operator
     * can only identify by tapping it.
     */
    private static Style style(int color, String label) {
        final Style fill = new BasicFillStyle(
                (FILL_ALPHA << 24) | (color & 0x00FFFFFF));
        final Style stroke = new BasicStrokeStyle(color, STROKE);
        if (label == null || label.isEmpty())
            return new CompositeStyle(new Style[] { fill, stroke });
        return new CompositeStyle(new Style[] { fill, stroke,
                new LabelPointStyle(label, 0xFFFFFFFF, 0x99000000,
                        LabelPointStyle.ScrollMode.DEFAULT, 0f, 0, 0, 0f, false,
                        LABEL_MAX_RES) });
    }

    /**
     * What the map says about an area. The place, then the ceiling: on a TFR the ceiling
     * is the question being asked, because it decides whether you can be under it.
     */
    private static String label(Tfr t, TfrArea a) {
        final StringBuilder b = new StringBuilder(t.place());
        if (a.ceiling.present)
            b.append("  to ").append(a.ceiling.label());
        return b.toString();
    }

    /** The feature's own name. Never empty: the Select Item chooser lists one as "[Unnamed]". */
    private static String name(Tfr t, TfrArea a, int areaCount, int index) {
        final StringBuilder b = new StringBuilder(t.place());
        if (areaCount > 1)
            b.append(a.name.isEmpty() ? " (area " + (index + 1) + ")" : " (" + a.name + ")");
        return b.toString();
    }

    /**
     * The source's own fields, which is what the details pane reads.
     *
     * <p>The operator should see what the FAA published, not a string somebody here
     * formatted out of it.
     */
    private static AttributeSet attributes(Tfr t, TfrArea a, boolean active) {
        final AttributeSet s = new AttributeSet();
        s.setAttribute("notam_id", t.notamId);
        s.setAttribute("type", TfrTypes.label(t.type));
        s.setAttribute("place", t.place());
        s.setAttribute("state", t.stateName);
        s.setAttribute("facility", t.facility);
        s.setAttribute("floor", a.floor.label());
        s.setAttribute("ceiling", a.ceiling.label());
        s.setAttribute("status", active ? "In effect now" : "Scheduled");
        s.setAttribute("effective_ms", t.effectiveMs);
        s.setAttribute("expire_ms", t.expireMs);
        s.setAttribute("notam", t.notamText);
        s.setAttribute("faa_page", t.faaPageUrl());
        if (a.radiusNm > 0)
            s.setAttribute("radius_nm", String.format(Locale.US, "%.1f NM", a.radiusNm));
        return s;
    }
}
