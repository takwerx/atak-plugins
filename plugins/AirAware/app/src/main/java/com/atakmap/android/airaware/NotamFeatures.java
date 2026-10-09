package com.atakmap.android.airaware;

import android.content.Context;

import com.atakmap.map.layer.feature.AttributeSet;
import com.atakmap.map.layer.feature.Feature;
import com.atakmap.map.layer.feature.geometry.LineString;
import com.atakmap.map.layer.feature.geometry.Point;
import com.atakmap.map.layer.feature.geometry.Polygon;
import com.atakmap.map.layer.feature.style.BasicFillStyle;
import com.atakmap.map.layer.feature.style.BasicStrokeStyle;
import com.atakmap.map.layer.feature.style.CompositeStyle;
import com.atakmap.map.layer.feature.style.IconPointStyle;
import com.atakmap.map.layer.feature.style.LabelPointStyle;
import com.atakmap.map.layer.feature.style.Style;

import java.util.ArrayList;
import java.util.List;

/**
 * NOTAMs on the map: the area where there is one, a glyph at the point, and a pill
 * saying what it is.
 *
 * <p>Flat on the ground. A NOTAM's floor and ceiling are on its details page; standing
 * a thousand of them up would be the thicket the airspace volumes were, and the thing a
 * crew needs from the map is <i>where</i>. Colored by kind, so a UAS area and a crane
 * are told apart before a tap, and each kind is its own set so it switches like an
 * obstacle group. A NOTAM that has not started yet is drawn at half strength.
 *
 * <p>Three features per NOTAM where there is an area, two where there is only a
 * point, and the glyph and the pill are separate things for the reason the obstacles
 * give: a composite of two point styles draws only the first. {@link TfrOverlay}
 * dedupes a tap on {@code nms_id}.
 */
public final class NotamFeatures {

    /** The set keys are per kind, so a kind is switched like any other. */
    public static final String SET_PREFIX = "ntm:";

    private static final int ICON_HALF_PX = 16;
    private static final int FILL_ALPHA = 0x30;
    private static final int STROKE_ALPHA = 0xE6;
    private static final float STROKE = 2.5f;
    private static final int CIRCLE_POINTS = 48;

    private NotamFeatures() {
    }

    public static String setKey(String kind) {
        return SET_PREFIX + kind;
    }

    public static String setName(String key) {
        if (key == null || !key.startsWith(SET_PREFIX))
            return key;
        return "NOTAMs: " + Notam.kindName(key.substring(SET_PREFIX.length()));
    }

    /** The color a kind draws in, and the key shows. */
    public static int color(String kind) {
        switch (kind) {
            case "airspace":
                return 0xFFFF6D00;   // orange: something is happening in the air here
            case "obstacle":
                return 0xFFFFB300;   // the amber the charted obstacles use
            case "airfield":
                return 0xFF4FC3F7;   // light blue: the airfield itself
            case "navigation":
                return 0xFFB0BEC5;   // gray-blue: for pilots flying procedures
            default:
                return 0xFF9E9E9E;
        }
    }

    public static List<TfrOverlay.Drawn> drawn(Notam n, boolean active, ObstaclePills pills,
            Context pluginContext) {
        final List<TfrOverlay.Drawn> out = new ArrayList<>();
        final String key = setKey(n.kind);
        final String name = setName(key);
        final int color = color(n.kind);
        // Not started yet: the same color, half as strong, so it reads as a notice and
        // not as something in force.
        final int strokeA = active ? STROKE_ALPHA : STROKE_ALPHA / 2;
        final int fillA = active ? FILL_ALPHA : FILL_ALPHA / 2;
        final int iconColor = active ? color : (0x80 << 24) | (color & 0x00FFFFFF);
        final AttributeSet attrs = attributes(n);
        final String label = n.label();

        final Style area = new CompositeStyle(new Style[] {
                new BasicFillStyle((fillA << 24) | (color & 0x00FFFFFF)),
                new BasicStrokeStyle((strokeA << 24) | (color & 0x00FFFFFF), STROKE) });
        if (!n.rings.isEmpty()) {
            for (double[][] ring : n.rings) {
                final LineString ls = new LineString(2);
                for (double[] c : ring)
                    ls.addPoint(c[0], c[1]);
                out.add(new TfrOverlay.Drawn(n.id, key, name, label, new Polygon(ls), area,
                        attrs, Feature.AltitudeMode.ClampToGround, 0d));
            }
        } else if (n.radiusM > 0) {
            out.add(new TfrOverlay.Drawn(n.id, key, name, label,
                    new Polygon(circle(n.lat, n.lon, n.radiusM)), area, attrs,
                    Feature.AltitudeMode.ClampToGround, 0d));
        }

        // The glyph at the point, its own label suppressed so ATAK does not draw the
        // name in its square box beside it.
        out.add(new TfrOverlay.Drawn(n.id, key, name, label, new Point(n.lon, n.lat),
                new CompositeStyle(new Style[] {
                        new IconPointStyle(iconColor, "android.resource://"
                                + pluginContext.getPackageName() + "/"
                                + com.atakmap.android.airaware.plugin.R.drawable.ic_notam),
                        new LabelPointStyle("", 0x00FFFFFF, 0x00000000,
                                LabelPointStyle.ScrollMode.DEFAULT) }),
                attrs, Feature.AltitudeMode.ClampToGround, 0d));

        // The pill above it, in a label set of its own so the pills gate with the labels.
        final ObstaclePills.Pill pill = pills == null ? null
                : pills.pill(label, ICON_HALF_PX + 6);
        if (pill != null) {
            final TfrOverlay.Drawn lbl = new TfrOverlay.Drawn(n.id, key, name, label,
                    new Point(n.lon, n.lat),
                    new CompositeStyle(new Style[] {
                            new IconPointStyle(0xFFFFFFFF, pill.uri, pill.width,
                                    pill.height, 0f, 0f, 0, 0, 0f, false),
                            new LabelPointStyle("", 0x00FFFFFF, 0x00000000,
                                    LabelPointStyle.ScrollMode.DEFAULT) }),
                    attrs, Feature.AltitudeMode.ClampToGround, 0d);
            lbl.isLabel = true;
            out.add(lbl);
        }
        return out;
    }

    /** A ring of points around a center, for a NOTAM given as a radius. */
    static LineString circle(double lat, double lon, double radiusM) {
        final LineString ring = new LineString(2);
        final double dLat = radiusM / 111320d;
        final double dLon = dLat / Math.max(0.2d, Math.cos(Math.toRadians(lat)));
        for (int i = 0; i <= CIRCLE_POINTS; i++) {
            final double a = 2d * Math.PI * (i % CIRCLE_POINTS) / CIRCLE_POINTS;
            ring.addPoint(lon + dLon * Math.sin(a), lat + dLat * Math.cos(a));
        }
        return ring;
    }

    private static AttributeSet attributes(Notam n) {
        final AttributeSet s = new AttributeSet();
        s.setAttribute("nms_id", n.id);
        s.setAttribute("kind", n.kind);
        s.setAttribute("number", n.number);
        s.setAttribute("location", n.location);
        return s;
    }
}
