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
 * Airspace shelves on the map, standing up at the heights the FAA published.
 *
 * <p><b>The colors are the sectional chart's, because pilots already know them.</b> Class
 * B and D are blue, Class C and E magenta -- that is what is printed on every VFR
 * sectional, so a shelf reads without a legend. Special use airspace does not follow the
 * chart, which draws Restricted, Prohibited and Warning in the same blue hatching: on a
 * moving map that is a hazard being drawn the same color as ordinary controlled airspace,
 * so those take warning colors instead, Prohibited reddest.
 *
 * <p>Fill is kept very light. Los Angeles Class B is twelve shelves stacked over the same
 * ground, and at the alpha a restriction uses they would stack into an opaque block with
 * no map underneath.
 */
public final class AirspaceFeatures {

    private static final float STROKE = 2f;
    /** Twelve shelves over one airport is normal, so each one has to be nearly clear. */
    private static final int FILL_ALPHA = 0x18;
    /** Left wide open; the gate the operator sets lives in the plugin. See TfrFeatures. */
    private static final double LABEL_MAX_RES = 100000d;

    // Sectional chart colors.
    private static final int CLASS_B = 0xFF1565C0;
    private static final int CLASS_C = 0xFFB5126A;
    private static final int CLASS_D = 0xFF42A5F5;
    private static final int CLASS_E = 0xFFE05297;
    /** 18,000 ft and up: drawn grey because nothing below it is affected. */
    private static final int CLASS_A = 0xFF78909C;
    private static final int CLASS_OTHER = 0xFF9E9E9E;

    // Special use, by how much it matters to somebody who might be in it.
    private static final int PROHIBITED = 0xFFD32F2F;
    private static final int RESTRICTED = 0xFFF4511E;
    private static final int WARNING = 0xFFFB8C00;
    private static final int ALERT = 0xFFFFB300;
    private static final int MOA = 0xFF8E24AA;

    private AirspaceFeatures() {
    }

    /** The color a set key draws in, which is also what the map key shows. */
    public static int color(String setKey) {
        if (setKey == null)
            return CLASS_OTHER;
        if (setKey.startsWith("as:")) {
            final String c = setKey.substring(3);
            if ("B".equals(c))
                return CLASS_B;
            if ("C".equals(c))
                return CLASS_C;
            if ("D".equals(c))
                return CLASS_D;
            if ("E".equals(c))
                return CLASS_E;
            if ("A".equals(c))
                return CLASS_A;
            return CLASS_OTHER;
        }
        if (setKey.startsWith("sua:")) {
            final String t = setKey.substring(4);
            if ("P".equals(t))
                return PROHIBITED;
            if ("R".equals(t))
                return RESTRICTED;
            if ("W".equals(t))
                return WARNING;
            if ("A".equals(t))
                return ALERT;
            if ("MOA".equals(t))
                return MOA;
        }
        return CLASS_OTHER;
    }

    /** Everything to draw for one shelf: its volume, its footprint and one label. */
    public static List<TfrOverlay.Drawn> drawn(Airspace a) {
        final List<TfrOverlay.Drawn> out = new ArrayList<>();
        final String setKey = a.setKey();
        if (setKey.isEmpty())
            return out;
        final String setName = Airspace.setName(setKey);
        final int color = color(setKey);
        final String name = featureName(a);
        final AttributeSet attrs = attributes(a);
        final String id = "as" + a.id;

        final double floorM = metersOf(a.floor);
        final double ceilM = metersOf(a.ceiling);
        final double wall = a.ceiling.present ? ceilM - floorM : 0d;
        final Feature.AltitudeMode mode = altitudeMode(a);

        for (Airspace.Part part : a.parts) {
            if (part.outer.size() < 3)
                continue;
            // The volume. This is the whole point of the layer: a shelf whose floor is at
            // 5,000 is airspace you fly under, and a flat ring cannot say that.
            if (wall > 1)
                out.add(new TfrOverlay.Drawn(id, setKey, setName, name,
                        polygon(part, floorM), volumeStyle(color), attrs, mode, wall));
            // The footprint, clamped, so the shelf is there looking straight down.
            out.add(new TfrOverlay.Drawn(id, setKey, setName, name, polygon(part, Double.NaN),
                    flatStyle(color), attrs, Feature.AltitudeMode.ClampToGround, 0d));
        }

        // One label for the shelf, on the biggest part. Airspace is dense -- forty-four
        // shelves over Los Angeles -- so there is one label per shelf and never one per
        // ring, and it says the floor, because that is what separates a shelf from the
        // one above it.
        final double[] mid = center(a);
        if (mid != null) {
            final TfrOverlay.Drawn lbl = new TfrOverlay.Drawn(id, setKey, setName, name,
                    new Point(mid[1], mid[0]),
                    new LabelPointStyle(label(a), 0xFFFFFFFF, 0x99000000,
                            LabelPointStyle.ScrollMode.OFF, 0f, 0, 0, 0f, false,
                            LABEL_MAX_RES),
                    attrs, Feature.AltitudeMode.ClampToGround, 0d);
            lbl.isLabel = true;
            out.add(lbl);
        }
        return out;
    }

    /** The middle of the largest part, for the label and for Go to. {lat, lon} or null. */
    public static double[] center(Airspace a) {
        double bestArea = -1;
        double[] best = null;
        for (Airspace.Part p : a.parts) {
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

    /**
     * Which frame the shelf is drawn in. Surface floors are drawn from sea level so the
     * top lands exactly on the published ceiling and the part below ground is buried,
     * which is what "surface" means on a mountain -- the same rule the restrictions use.
     */
    private static Feature.AltitudeMode altitudeMode(Airspace a) {
        if (a.floor.present && a.floor.surface)
            return Feature.AltitudeMode.Absolute;
        final TfrArea.Vert governing = a.ceiling.present ? a.ceiling : a.floor;
        return governing.agl ? Feature.AltitudeMode.Relative : Feature.AltitudeMode.Absolute;
    }

    private static double metersOf(TfrArea.Vert v) {
        if (!v.present || v.surface)
            return 0d;
        return v.feet * 0.3048d;
    }

    private static Style volumeStyle(int color) {
        return new CompositeStyle(new Style[] {
                new BasicFillStyle((FILL_ALPHA << 24) | (color & 0x00FFFFFF)),
                new BasicStrokeStyle(color, STROKE, BasicStrokeStyle.EXTRUDE_VERTEX) });
    }

    private static Style flatStyle(int color) {
        return new CompositeStyle(new Style[] {
                new BasicFillStyle((FILL_ALPHA << 24) | (color & 0x00FFFFFF)),
                new BasicStrokeStyle(color, STROKE) });
    }

    /**
     * What the map says. The kind, then the floor.
     *
     * <p>Not the ceiling: shelves of one airspace share a ceiling and differ only in how
     * low they come down, so the floor is the number that tells two of them apart and the
     * number that decides whether you are underneath.
     */
    private static String label(Airspace a) {
        final StringBuilder b = new StringBuilder(kind(a));
        if (a.floor.present)
            b.append("  from ").append(a.floor.label());
        return b.toString();
    }

    /** "Class B", "Restricted R-2503", "Military operations area". */
    private static String kind(Airspace a) {
        if (a.isClass()) {
            final String c = a.classCode == null ? "" : a.classCode.trim().toUpperCase(Locale.US);
            return c.isEmpty() ? "Airspace" : "Class " + c;
        }
        final String base = Airspace.setName(a.setKey());
        // Restricted and warning areas carry a number everybody uses -- R-2503, W-289 --
        // and it is in the identifier, not the name.
        final String one = singular(base);
        return a.ident.isEmpty() ? one : one + " " + a.ident;
    }

    private static String singular(String plural) {
        if (plural.endsWith("areas"))
            return plural.substring(0, plural.length() - 1);
        return plural;
    }

    /** Never empty: the Select Item chooser lists an unnamed feature as "[Unnamed]". */
    private static String featureName(Airspace a) {
        final String t = a.title();
        return t.isEmpty() ? kind(a) : t;
    }

    /** The source's own fields, which is what the details pane reads. */
    private static AttributeSet attributes(Airspace a) {
        final AttributeSet s = new AttributeSet();
        s.setAttribute("airspace_id", a.id);
        s.setAttribute("kind", kind(a));
        s.setAttribute("name", a.title());
        s.setAttribute("ident", a.ident);
        s.setAttribute("class", a.classCode);
        s.setAttribute("type", a.typeCode);
        s.setAttribute("local_type", a.localType);
        s.setAttribute("floor", a.floor.present ? a.floor.label() : "unstated");
        s.setAttribute("ceiling", a.ceiling.present ? a.ceiling.label() : "unstated");
        s.setAttribute("hours", hours(a.workHours));
        return s;
    }

    /**
     * When it is on.
     *
     * <p>{@code NOTAM} is the one that matters and the one a plain reading gets wrong: a
     * restricted area published that way is not hot all the time, it is activated by
     * NOTAM, and over half the shelves around Los Angeles carried it.
     */
    public static String hours(String code) {
        final String c = code == null ? "" : code.trim().toUpperCase(Locale.US);
        if (c.isEmpty())
            return "not published";
        if ("H24".equals(c))
            return "continuous";
        if ("NOTAM".equals(c))
            return "by NOTAM - check before you rely on it";
        return c;
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
