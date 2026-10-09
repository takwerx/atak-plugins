package com.atakmap.android.airaware;

import com.atakmap.map.layer.feature.AttributeSet;
import com.atakmap.map.layer.feature.Feature;
import com.atakmap.map.layer.feature.geometry.LineString;
import com.atakmap.map.layer.feature.geometry.Point;
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
     * The style's own label gate, left wide open on purpose.
     *
     * <p>ATAK's default is 14 meters per pixel, which hides the label on anything bigger
     * than a few miles, and a TFR is routinely a 30 NM ring. This was 400, which put the
     * labels on at about a fifty mile scale bar and could not be changed. The gate the
     * operator actually sets lives in the plugin and is compared against ATAK's own scale
     * bar, so this one is opened up and left out of the way.
     */
    private static final double LABEL_MAX_RES = 100000d;

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
            final AttributeSet attrs = attributes(t, a, active);

            // The volume. A restriction is a block of airspace, not a line on the
            // ground, and the question an operator is asking it -- can I be under this
            // -- is the one a flat ring cannot answer.
            final double floorM = metersOf(a.floor);
            final double ceilM = metersOf(a.ceiling);
            final double wall = ceilM - floorM;
            if (wall > 1) {
                // No label on the wall. A LabelPointStyle in an extruded feature's
                // composite does not draw -- the ring was unlabeled on the phone at a zoom
                // well inside the label's own resolution gate -- and the footprint below
                // carries it instead, which is the clamped case that does work.
                out.add(new TfrOverlay.Drawn(t.notamId, setKey, setName, name,
                        polygon(a, floorM), wallStyle(color, null), attrs,
                        altitudeMode(a), wall));
            }
            // The footprint, clamped. ATAK draws its own extruded shapes this way, as
            // two features: without it the area is invisible looking straight down,
            // which is how the map is read most of the time.
            out.add(new TfrOverlay.Drawn(t.notamId, setKey, setName, name, polygon(a, Double.NaN),
                    style(color, null), attrs,
                    Feature.AltitudeMode.ClampToGround, 0d));

            // The label, on a point of its own at the middle of the ring. ATAK puts a
            // polygon's own label wherever it likes, which came out on the edge; a point
            // feature at the centroid is how Atmosphere centers one, and it is the only
            // way to say where the text goes.
            final double[] mid = centroid(a);
            if (mid != null) {
                final TfrOverlay.Drawn lbl = new TfrOverlay.Drawn(t.notamId, setKey, setName,
                        name, new Point(mid[1], mid[0]),
                        new LabelPointStyle(label(t, a), 0xFFFFFFFF, 0x99000000,
                                LabelPointStyle.ScrollMode.OFF, 0f, 0, 0, 0f, false,
                                LABEL_MAX_RES),
                        attrs, Feature.AltitudeMode.ClampToGround, 0d);
                lbl.isLabel = true;
                out.add(lbl);
            }
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

    /**
     * The ring. <b>Longitude first</b>, unlike {@code GeoPoint}, and nothing complains if
     * it is not.
     *
     * @param baseM the floor in meters for an extruded volume, or NaN for a flat ring
     *            clamped to the ground
     */
    private static Polygon polygon(TfrArea a, double baseM) {
        final boolean flat = Double.isNaN(baseM);
        final LineString ring = new LineString(flat ? 2 : 3);
        for (double[] p : a.ring) {
            if (flat)
                ring.addPoint(p[1], p[0]);
            else
                ring.addPoint(p[1], p[0], baseM);
        }
        // A ring that does not close draws as an open shape with a seam across it.
        if (!a.ring.isEmpty()) {
            final double[] first = a.ring.get(0);
            final double[] last = a.ring.get(a.ring.size() - 1);
            if (first[0] != last[0] || first[1] != last[1]) {
                if (flat)
                    ring.addPoint(first[1], first[0]);
                else
                    ring.addPoint(first[1], first[0], baseM);
            }
        }
        return new Polygon(ring);
    }

    /**
     * The middle of a ring, for the label to sit on.
     *
     * <p>The polygon centroid rather than the average of the vertices: a TFR is often an
     * even ring, where the two agree, but an irregular one with its points bunched along
     * one edge would drag a vertex average off into the side of the shape. Falls back to
     * the average when the ring is degenerate enough to have no area.
     */
    /**
     * How wide the restriction is on the ground, in meters, so Go to can pick a zoom
     * that shows the whole thing. The widest of the two axes of the first drawable
     * area's bounding box; 0 when there is nothing to measure.
     */
    public static double span(Tfr t) {
        for (TfrArea a : t.drawable()) {
            if (a.ring.size() < 2)
                continue;
            double minLat = 90, maxLat = -90, minLon = 180, maxLon = -180;
            for (double[] p : a.ring) {
                if (p[0] < minLat) minLat = p[0];
                if (p[0] > maxLat) maxLat = p[0];
                if (p[1] < minLon) minLon = p[1];
                if (p[1] > maxLon) maxLon = p[1];
            }
            // A ring that wraps the antimeridian would measure as most of the planet.
            // No domestic TFR does, so rather than guess at the wrap, decline to size it.
            if (maxLon - minLon > 180)
                continue;
            final double mid = Math.toRadians((minLat + maxLat) / 2);
            final double tall = (maxLat - minLat) * 111320d;
            final double wide = (maxLon - minLon) * 111320d * Math.cos(mid);
            final double span = Math.max(tall, wide);
            if (span > 0)
                return span;
        }
        return 0;
    }

    /** The middle of a restriction's first drawable area, for Go to. {lat, lon} or null. */
    public static double[] center(Tfr t) {
        for (TfrArea a : t.drawable()) {
            final double[] c = centroid(a);
            if (c != null)
                return c;
        }
        return null;
    }

    private static double[] centroid(TfrArea a) {
        final List<double[]> r = a.ring;
        if (r.size() < 3)
            return null;
        double twiceArea = 0, lat = 0, lon = 0;
        for (int i = 0, j = r.size() - 1; i < r.size(); j = i++) {
            final double yi = r.get(i)[0], xi = r.get(i)[1];
            final double yj = r.get(j)[0], xj = r.get(j)[1];
            final double cross = xj * yi - xi * yj;
            twiceArea += cross;
            lat += (yi + yj) * cross;
            lon += (xi + xj) * cross;
        }
        if (Math.abs(twiceArea) < 1e-12) {
            double sy = 0, sx = 0;
            for (double[] p : r) {
                sy += p[0];
                sx += p[1];
            }
            return new double[] { sy / r.size(), sx / r.size() };
        }
        return new double[] { lat / (3 * twiceArea), lon / (3 * twiceArea) };
    }

    /**
     * Which frame the volume is drawn in, which is the whole reason the parser reads
     * {@code codeDistVer} per area.
     *
     * <p>{@code HEI} is a height above the surface, so the block rides the terrain and the
     * mode is Relative. {@code ALT} is above mean sea level, so it sits at a fixed
     * altitude whatever the ground does, and the mode is Absolute. A restriction whose
     * floor is the surface is drawn Absolute from sea level: the top then lands exactly on
     * the published ceiling and the part below the terrain is simply buried, which is what
     * "surface" means on a mountain.
     */
    private static Feature.AltitudeMode altitudeMode(TfrArea a) {
        final TfrArea.Vert governing = a.ceiling.present ? a.ceiling : a.floor;
        if (a.floor.present && a.floor.surface)
            return Feature.AltitudeMode.Absolute;
        return governing.agl ? Feature.AltitudeMode.Relative : Feature.AltitudeMode.Absolute;
    }

    /** Feet to meters, with a surface or unstated limit reading as zero. */
    private static double metersOf(TfrArea.Vert v) {
        if (!v.present || v.surface)
            return 0d;
        return v.feet * 0.3048d;
    }

    /**
     * The wall. {@code EXTRUDE_VERTEX} is what puts an outline up the sides rather than
     * only around the lid; it is what ATAK's own extruded polylines use.
     */
    private static Style wallStyle(int color, String label) {
        final Style fill = new BasicFillStyle((FILL_ALPHA << 24) | (color & 0x00FFFFFF));
        final Style stroke = new BasicStrokeStyle(color, STROKE,
                BasicStrokeStyle.EXTRUDE_VERTEX);
        if (label == null || label.isEmpty())
            return new CompositeStyle(new Style[] { fill, stroke });
        return new CompositeStyle(new Style[] { fill, stroke,
                new LabelPointStyle(label, 0xFFFFFFFF, 0x99000000,
                        LabelPointStyle.ScrollMode.DEFAULT, 0f, 0, 0, 0f, false,
                        LABEL_MAX_RES) });
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
        if (!a.ceiling.present)
            return b.toString();
        // Name the floor when it is not the ground. "to 5,000 ft MSL" on a shelf that
        // starts at 2,500 reads as airspace you are under when you are in it.
        if (a.floor.present && !a.floor.surface && a.floor.feet > 0)
            b.append("  ").append(a.floor.label()).append(" to ").append(a.ceiling.label());
        else
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
