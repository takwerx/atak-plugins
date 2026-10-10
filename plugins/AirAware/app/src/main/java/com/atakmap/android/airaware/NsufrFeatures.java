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

/**
 * National Security UAS restrictions on the map: a solid red ring with a light fill.
 *
 * <p>Red because it is a prohibition, the same red as a prohibited area, and the fill
 * is light because the outline is the fence and the map inside it is still the map.
 * Part-time ones are the same red at a lighter edge, pending ones gray, so the three
 * read apart at a glance and the map key says which is which.
 *
 * <p>Flat on the ground by default: it is a surface-to-400 ft prohibition, and a
 * 400 ft wall says nothing a ring does not. When the operator switches 3D on it stands
 * up to the published ceiling.
 */
public final class NsufrFeatures {

    /** The one feature set, switched by its layer row. */
    public static final String SET_KEY = "nsufr";
    public static final String SET_NAME = "National Security UAS restrictions";

    private static final float STROKE = 2.5f;
    private static final int FILL_ALPHA = 0x30;
    private static final int WALL_ALPHA = 0x40;
    /** Left wide open; the gate the operator sets lives in the plugin. See TfrFeatures. */
    private static final double LABEL_MAX_RES = 100000d;

    private static final int FULL_TIME = 0xFFD32F2F;
    private static final int PART_TIME = 0xFFEF6C00;
    private static final int PENDING = 0xFF9E9E9E;

    private NsufrFeatures() {
    }

    /** The color a status draws in, which is also what the map key shows. */
    public static int color(String status) {
        if (Nsufr.PART_TIME.equals(status))
            return PART_TIME;
        if (Nsufr.PENDING.equals(status))
            return PENDING;
        return FULL_TIME;
    }

    /**
     * Everything to draw for one restriction: its outline, and one label.
     *
     * @param solid whether it stands up to 400 ft above the ground; false draws it flat
     */
    public static List<TfrOverlay.Drawn> drawn(Nsufr n, boolean solid) {
        final List<TfrOverlay.Drawn> out = new ArrayList<>();
        final int color = color(n.status);
        final String name = n.title() + "   " + n.statusName();
        final AttributeSet attrs = attributes(n);
        final String id = "ns" + n.id;

        for (Airspace.Part part : n.parts) {
            if (part.outer.size() < 3)
                continue;
            // To the published ceiling: above the ground for the usual 400' AGL, and
            // from sea level to the number for the MSL ones, so the top lands on the
            // published height and the part below ground is buried.
            if (solid)
                out.add(new TfrOverlay.Drawn(id, SET_KEY, SET_NAME, name,
                        polygon(part, 0d), volumeStyle(color, WALL_ALPHA), attrs,
                        n.ceilingAgl() ? Feature.AltitudeMode.Relative
                                : Feature.AltitudeMode.Absolute,
                        n.ceilingFeet() * 0.3048d));
            out.add(new TfrOverlay.Drawn(id, SET_KEY, SET_NAME, name,
                    polygon(part, Double.NaN), flatStyle(color, FILL_ALPHA), attrs,
                    Feature.AltitudeMode.ClampToGround, 0d));
        }

        final double[] mid = center(n);
        if (mid != null) {
            final TfrOverlay.Drawn lbl = new TfrOverlay.Drawn(id, SET_KEY, SET_NAME, name,
                    new Point(mid[1], mid[0]),
                    new LabelPointStyle(label(n), 0xFFFFFFFF, 0x99000000,
                            LabelPointStyle.ScrollMode.OFF, 0f, 0, 0, 0f, false,
                            LABEL_MAX_RES),
                    attrs, Feature.AltitudeMode.ClampToGround, 0d);
            lbl.isLabel = true;
            out.add(lbl);
        }
        return out;
    }

    /** The middle of the largest part, for the label and for Go to. {lat, lon} or null. */
    public static double[] center(Nsufr n) {
        double bestArea = -1;
        double[] best = null;
        for (Airspace.Part p : n.parts) {
            if (p.outer.size() < 3)
                continue;
            final double area = Math.abs(twiceArea(p.outer));
            if (area > bestArea) {
                bestArea = area;
                best = centroid(p.outer);
            }
        }
        return best;
    }

    private static String label(Nsufr n) {
        final String t = n.title();
        if (Nsufr.FULL_TIME.equals(n.status))
            return t + "  No UAS";
        return t + "  No UAS (" + n.statusName().toLowerCase(java.util.Locale.US) + ")";
    }

    private static Polygon polygon(Airspace.Part part, double baseM) {
        final boolean flat = Double.isNaN(baseM);
        final LineString outer = ring(part.outer, baseM, flat);
        if (part.holes.isEmpty())
            return new Polygon(outer);
        final List<LineString> inner = new ArrayList<>(part.holes.size());
        for (List<double[]> h : part.holes)
            if (h.size() >= 3)
                inner.add(ring(h, baseM, flat));
        return new Polygon(outer, inner);
    }

    /** <b>Longitude first</b>, unlike {@code GeoPoint}, and nothing complains if it is not. */
    private static LineString ring(List<double[]> pts, double baseM, boolean flat) {
        final LineString ls = new LineString(flat ? 2 : 3);
        for (double[] p : pts) {
            if (flat)
                ls.addPoint(p[1], p[0]);
            else
                ls.addPoint(p[1], p[0], baseM);
        }
        final double[] first = pts.get(0);
        final double[] last = pts.get(pts.size() - 1);
        if (first[0] != last[0] || first[1] != last[1]) {
            if (flat)
                ls.addPoint(first[1], first[0]);
            else
                ls.addPoint(first[1], first[0], baseM);
        }
        return ls;
    }

    private static Style volumeStyle(int color, int alpha) {
        return new CompositeStyle(new Style[] {
                new BasicFillStyle((alpha << 24) | (color & 0x00FFFFFF)),
                new BasicStrokeStyle(color, STROKE, BasicStrokeStyle.EXTRUDE_VERTEX) });
    }

    private static Style flatStyle(int color, int alpha) {
        return new CompositeStyle(new Style[] {
                new BasicFillStyle((alpha << 24) | (color & 0x00FFFFFF)),
                new BasicStrokeStyle(color, STROKE) });
    }

    /** The source's own fields, which is what the details page reads. */
    private static AttributeSet attributes(Nsufr n) {
        final AttributeSet s = new AttributeSet();
        s.setAttribute("nsufr_id", n.id);
        s.setAttribute("name", n.title());
        s.setAttribute("status", n.statusName());
        s.setAttribute("base", n.base);
        s.setAttribute("branch", n.branchName());
        s.setAttribute("heights", n.heights());
        return s;
    }

    private static double twiceArea(List<double[]> r) {
        double twice = 0;
        for (int i = 0, j = r.size() - 1; i < r.size(); j = i++)
            twice += r.get(j)[1] * r.get(i)[0] - r.get(i)[1] * r.get(j)[0];
        return twice;
    }

    private static double[] centroid(List<double[]> r) {
        if (r.size() < 3)
            return null;
        double twice = 0, lat = 0, lon = 0;
        for (int i = 0, j = r.size() - 1; i < r.size(); j = i++) {
            final double yi = r.get(i)[0], xi = r.get(i)[1];
            final double yj = r.get(j)[0], xj = r.get(j)[1];
            final double cross = xj * yi - xi * yj;
            twice += cross;
            lat += (yi + yj) * cross;
            lon += (xi + xj) * cross;
        }
        if (Math.abs(twice) < 1e-12) {
            double sy = 0, sx = 0;
            for (double[] p : r) {
                sy += p[0];
                sx += p[1];
            }
            return new double[] { sy / r.size(), sx / r.size() };
        }
        return new double[] { lat / (3 * twice), lon / (3 * twice) };
    }
}
