package com.atakmap.android.airaware;

import android.content.Context;

import com.atakmap.map.layer.feature.AttributeSet;
import com.atakmap.map.layer.feature.Feature;
import com.atakmap.map.layer.feature.geometry.LineString;
import com.atakmap.map.layer.feature.geometry.Point;
import com.atakmap.map.layer.feature.style.BasicStrokeStyle;
import com.atakmap.map.layer.feature.style.CompositeStyle;
import com.atakmap.map.layer.feature.style.IconPointStyle;
import com.atakmap.map.layer.feature.style.LabelPointStyle;
import com.atakmap.map.layer.feature.style.Style;

import java.util.ArrayList;
import java.util.List;

/**
 * Obstacles on the map: a mast from the ground to the top, the tower glyph at the top,
 * and a pill saying how tall it is.
 *
 * <p>Three features per obstacle, which is UAS Flight Plan's arrangement carried over
 * with the rules the operator set there on 2026-10-06. <b>The icon and the label are
 * separate things</b> -- "the tower icon and label are separate things" -- so the pill
 * is composed as its own image with clear space below it and centered on the same
 * point, which puts it above the glyph. A composite of two point styles draws only the
 * first, which is why they cannot be one feature. Three features per obstacle also
 * means the hit test has to dedupe per obstacle or the chooser lists the same tower
 * three times, and {@link TfrOverlay} does that on {@code obstacle_oas}.
 */
public final class ObstacleFeatures {

    /** The set keys are per group, so a group is switched like any other kind. */
    public static final String SET_PREFIX = "obs:";

    /** Half the glyph, in device pixels, which is where the pill is lifted to. */
    private static final int ICON_HALF_PX = 16;

    private static final float MAST = 2f;
    /**
     * The coarsest map resolution an obstacle draws at. Obstacles are dense -- 12,542 in
     * the two degrees over Los Angeles -- so unlike a restriction, which matters from a
     * long way out, this is a close-in thing and the plugin gates it harder still.
     */
    private static final double MAX_RES = 120d;

    private ObstacleFeatures() {
    }

    public static String setKey(Obstacle o) {
        return SET_PREFIX + o.group();
    }

    public static String setName(String key) {
        if (key == null || !key.startsWith(SET_PREFIX))
            return key;
        return Obstacle.groupName(key.substring(SET_PREFIX.length()));
    }

    /**
     * Everything to draw for one obstacle.
     *
     * @param solid whether the mast and the raised glyph are drawn. Flat, the obstacle
     *            is one glyph on the ground: a stick per tower across a wide view is the
     *            same thicket the airspace volumes were.
     */
    public static List<TfrOverlay.Drawn> drawn(Obstacle o, boolean solid,
            ObstaclePills pills, Context pluginContext, int color) {
        final List<TfrOverlay.Drawn> out = new ArrayList<>();
        final String key = setKey(o);
        final String name = Obstacle.groupName(o.group());
        final AttributeSet attrs = attributes(o);
        final String id = o.oas;
        final double topM = o.amslFt * 0.3048d;

        if (solid) {
            // The mast, from the ground to the top. Absolute so the top lands on the
            // published height whatever the terrain does underneath it.
            final LineString mast = new LineString(3);
            mast.addPoint(o.lon, o.lat, Math.max(0d, topM - o.aglFt * 0.3048d));
            mast.addPoint(o.lon, o.lat, topM);
            out.add(new TfrOverlay.Drawn(id, key, name, o.kind(), mast,
                    new BasicStrokeStyle(color, MAST), attrs,
                    Feature.AltitudeMode.Absolute, 0d));
        }

        // The glyph: at the top in 3D, on the ground when flat. Its own label is
        // suppressed so ATAK does not draw the name in its square box beside it.
        final Point at = solid ? new Point(o.lon, o.lat, topM) : new Point(o.lon, o.lat);
        out.add(new TfrOverlay.Drawn(id, key, name, o.kind(), at,
                new CompositeStyle(new Style[] {
                        new IconPointStyle(color, "android.resource://"
                                + pluginContext.getPackageName() + "/"
                                + com.atakmap.android.airaware.plugin.R.drawable.ic_obstacle),
                        new LabelPointStyle("", 0x00FFFFFF, 0x00000000,
                                LabelPointStyle.ScrollMode.DEFAULT) }),
                attrs, solid ? Feature.AltitudeMode.Absolute
                        : Feature.AltitudeMode.ClampToGround, 0d));

        // The pill, above the glyph. A label set of its own, so the operator can have
        // the towers without the numbers.
        final ObstaclePills.Pill pill = pills == null ? null
                : pills.pill(heightLabel(o), ICON_HALF_PX + 6);
        if (pill != null) {
            final TfrOverlay.Drawn lbl = new TfrOverlay.Drawn(id, key, name, o.kind(),
                    solid ? new Point(o.lon, o.lat, topM) : new Point(o.lon, o.lat),
                    new CompositeStyle(new Style[] {
                            new IconPointStyle(0xFFFFFFFF, pill.uri, pill.width,
                                    pill.height, 0f, 0f, 0, 0, 0f, false),
                            new LabelPointStyle("", 0x00FFFFFF, 0x00000000,
                                    LabelPointStyle.ScrollMode.DEFAULT) }),
                    attrs, solid ? Feature.AltitudeMode.Absolute
                            : Feature.AltitudeMode.ClampToGround, 0d);
            lbl.isLabel = true;
            out.add(lbl);
        }
        return out;
    }

    /**
     * What the pill says.
     *
     * <p>Height above the ground, because that is the number somebody flying low is
     * comparing against their own altitude above the ground. The sea-level top is on the
     * details page for anybody working in MSL.
     */
    static String heightLabel(Obstacle o) {
        return TfrVertical.comma((int) Math.round(o.aglFt)) + "'";
    }

    private static AttributeSet attributes(Obstacle o) {
        final AttributeSet s = new AttributeSet();
        s.setAttribute("obstacle_oas", o.oas);
        s.setAttribute("kind", o.kind());
        s.setAttribute("type", o.typeCode);
        s.setAttribute("agl_ft", (int) Math.round(o.aglFt));
        s.setAttribute("amsl_ft", (int) Math.round(o.amslFt));
        s.setAttribute("lighting", o.lighting);
        s.setAttribute("verified", o.verified);
        s.setAttribute("quantity", o.quantity);
        s.setAttribute("city", o.city);
        s.setAttribute("state", o.state);
        return s;
    }

    /** Unused for now; kept so the gate constant lives beside the features it gates. */
    static double maxResolution() {
        return MAX_RES;
    }
}
