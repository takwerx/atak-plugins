package com.atakmap.android.featurelayer;

import com.atakmap.map.layer.feature.geometry.Geometry;
import com.atakmap.map.layer.feature.geometry.GeometryCollection;
import com.atakmap.map.layer.feature.geometry.LineString;

import java.util.ArrayList;
import java.util.List;

/**
 * NWCG line symbols built as geometry, because ATAK's strokes cannot place marks along a
 * line. PMS 936 draws letters in the line (H hand line, R road, M mixed construction,
 * P plow), a chain of X's for dozer line, chevrons for a planned fuel break, a zigzag for
 * a completed one, boxed dots for burnout and one-sided ticks on the fire edge. Each mark
 * is short lines in ground meters; where a letter sits, the line itself is cut, as on the
 * standard's own drawing. Everything ends up in one collection with the line, so a tap
 * finds one feature and the chooser shows one row.
 */
public final class NwcgDecor {
    private NwcgDecor() {
    }

    public enum Mark { NONE, TICKS, X_CHAIN, X_GROUPS, LETTERS, BOXED_DOTS, ZIGZAG }

    /** How a category's line is drawn: the mark, its size and spacing, and whether the line itself shows. */
    public static final class Recipe {
        public final Mark mark;
        public final String letters;   // for LETTERS: drawn in turn along the line ("H", "R", "ΛV")
        public final double spacingM, sizeM;
        public final boolean base;     // draw the line itself (false: the marks are the line)

        Recipe(Mark mark, String letters, double spacingM, double sizeM, boolean base) {
            this.mark = mark;
            this.letters = letters;
            this.spacingM = spacingM;
            this.sizeM = sizeM;
            this.base = base;
        }
    }

    public static Recipe ticks(double spacingM, double sizeM) {
        return new Recipe(Mark.TICKS, null, spacingM, sizeM, true);
    }

    public static Recipe letters(String letters, double spacingM, double sizeM) {
        return new Recipe(Mark.LETTERS, letters, spacingM, sizeM, true);
    }

    public static Recipe chevrons(double spacingM, double sizeM) {
        return new Recipe(Mark.LETTERS, "ΛV", spacingM, sizeM, false);
    }

    public static Recipe xChain(double sizeM) {
        return new Recipe(Mark.X_CHAIN, null, sizeM, sizeM, false);
    }

    public static Recipe xGroups(double sizeM) {
        return new Recipe(Mark.X_GROUPS, null, sizeM, sizeM, false);
    }

    public static Recipe boxedDots(double spacingM, double sizeM) {
        return new Recipe(Mark.BOXED_DOTS, null, spacingM, sizeM, true);
    }

    public static Recipe zigzag(double sizeM) {
        return new Recipe(Mark.ZIGZAG, null, sizeM, sizeM, false);
    }

    /** Says whether a point is inside the fire, so ticks can point to the burned side. */
    public interface Inside {
        boolean inside(double lon, double lat);
    }

    /** Every LineString inside a geometry (a line or a collection of lines). */
    static List<LineString> lines(Geometry g) {
        final List<LineString> out = new ArrayList<>();
        if (g instanceof LineString)
            out.add((LineString) g);
        else if (g instanceof GeometryCollection)
            for (Geometry c : ((GeometryCollection) g).getGeometries())
                out.addAll(lines(c));
        return out;
    }

    // Letter strokes in a unit box: x along the line 0..0.7, y across it 0..1 (1 = the
    // left-hand side, the top of an upright letter when the line runs left to right).
    private static double[][][] glyph(char c) {
        switch (c) {
            case 'H':
                return new double[][][] { { { 0, 0 }, { 0, 1 } }, { { 0.7, 0 }, { 0.7, 1 } }, { { 0, 0.5 }, { 0.7, 0.5 } } };
            case 'M':
                return new double[][][] { { { 0, 0 }, { 0, 1 }, { 0.35, 0.4 }, { 0.7, 1 }, { 0.7, 0 } } };
            case 'P':
                return new double[][][] { { { 0, 0 }, { 0, 1 }, { 0.55, 1 }, { 0.7, 0.85 }, { 0.7, 0.65 }, { 0.55, 0.5 }, { 0, 0.5 } } };
            case 'R':
                return new double[][][] { { { 0, 0 }, { 0, 1 }, { 0.55, 1 }, { 0.7, 0.85 }, { 0.7, 0.65 }, { 0.55, 0.5 }, { 0, 0.5 } },
                        { { 0.35, 0.5 }, { 0.7, 0 } } };
            case 'Λ': // Λ
                return new double[][][] { { { 0, 0 }, { 0.35, 1 }, { 0.7, 0 } } };
            case 'V':
                return new double[][][] { { { 0, 1 }, { 0.35, 0 }, { 0.7, 1 } } };
            case 'X':
                return new double[][][] { { { 0, 0 }, { 0.7, 1 } }, { { 0, 1 }, { 0.7, 0 } } };
            default:
                return new double[0][][];
        }
    }

    /** A position along a line where a mark goes, with the local frame in meters. */
    private static final class Spot {
        double d;          // distance along the whole LineString, meters
        double x, y;       // lon, lat
        double ux, uy;     // unit vector along the line, meters
        double lx, ly;     // unit vector to the left, meters
        double mPerLon, mPerLat;
    }

    private static final double M_PER_LAT = 111320d;

    private static List<Spot> spots(LineString ls, double spacingM, double first) {
        final List<Spot> out = new ArrayList<>();
        final int pts = ls.getNumPoints();
        double carry = first, dAcc = 0;
        for (int i = 0; i < pts - 1; i++) {
            final double x0 = ls.getX(i), y0 = ls.getY(i), x1 = ls.getX(i + 1), y1 = ls.getY(i + 1);
            final double mPerLon = M_PER_LAT * Math.cos(Math.toRadians((y0 + y1) / 2));
            final double dxm = (x1 - x0) * mPerLon, dym = (y1 - y0) * M_PER_LAT;
            final double segLen = Math.hypot(dxm, dym);
            if (segLen < 1e-6)
                continue;
            final double ux = dxm / segLen, uy = dym / segLen;
            double d = carry;
            while (d <= segLen) {
                final Spot s = new Spot();
                s.d = dAcc + d;
                s.x = x0 + (ux * d) / mPerLon;
                s.y = y0 + (uy * d) / M_PER_LAT;
                s.ux = ux;
                s.uy = uy;
                s.lx = -uy;
                s.ly = ux;
                s.mPerLon = mPerLon;
                s.mPerLat = M_PER_LAT;
                out.add(s);
                d += spacingM;
            }
            carry = d - segLen;
            dAcc += segLen;
        }
        return out;
    }

    /** The line with the stretches under each gap removed; gaps are [start, end] in meters along it. */
    private static List<LineString> pieces(LineString ls, List<double[]> gaps) {
        final List<LineString> out = new ArrayList<>();
        final int pts = ls.getNumPoints();
        List<double[]> cur = new ArrayList<>();
        double dAcc = 0;
        int gi = 0;
        for (int i = 0; i < pts - 1; i++) {
            final double x0 = ls.getX(i), y0 = ls.getY(i), x1 = ls.getX(i + 1), y1 = ls.getY(i + 1);
            final double mPerLon = M_PER_LAT * Math.cos(Math.toRadians((y0 + y1) / 2));
            final double segLen = Math.hypot((x1 - x0) * mPerLon, (y1 - y0) * M_PER_LAT);
            if (segLen < 1e-6)
                continue;
            final double segEnd = dAcc + segLen;
            double cursor = dAcc;
            while (gi < gaps.size() && gaps.get(gi)[1] <= cursor)
                gi++;
            int k = gi;
            while (k < gaps.size() && gaps.get(k)[0] < segEnd) {
                final double gs = Math.max(gaps.get(k)[0], dAcc), ge = Math.min(gaps.get(k)[1], segEnd);
                if (gs > cursor) {
                    if (cur.isEmpty())
                        cur.add(at(x0, y0, x1, y1, (cursor - dAcc) / segLen));
                    cur.add(at(x0, y0, x1, y1, (gs - dAcc) / segLen));
                }
                if (cur.size() >= 2)
                    out.add(toLine(cur));
                cur = new ArrayList<>();
                cursor = Math.max(cursor, ge);
                k++;
            }
            if (cursor < segEnd) {
                if (cur.isEmpty())
                    cur.add(at(x0, y0, x1, y1, (cursor - dAcc) / segLen));
                cur.add(new double[] { x1, y1 });
            }
            dAcc = segEnd;
        }
        if (cur.size() >= 2)
            out.add(toLine(cur));
        return out;
    }

    private static double[] at(double x0, double y0, double x1, double y1, double t) {
        return new double[] { x0 + (x1 - x0) * t, y0 + (y1 - y0) * t };
    }

    private static LineString toLine(List<double[]> pts) {
        final LineString l = new LineString(2);
        for (double[] p : pts)
            l.addPoint(p[0], p[1]);
        return l;
    }

    /** A polyline given in a spot's frame (meters along, meters left), as lon/lat. */
    private static LineString local(Spot s, double[][] pts, double scaleX, double scaleY, double offX, double offY) {
        final LineString l = new LineString(2);
        for (double[] p : pts) {
            final double a = p[0] * scaleX + offX, b = p[1] * scaleY + offY;
            l.addPoint(s.x + (s.ux * a + s.lx * b) / s.mPerLon, s.y + (s.uy * a + s.ly * b) / s.mPerLat);
        }
        return l;
    }

    /**
     * The drawn geometry for a line under a recipe: the line (cut under letters) plus its
     * marks, or the line alone when it is too short to carry a mark. Null when the recipe
     * changes nothing.
     */
    public static Geometry decorate(Geometry g, Recipe r, Inside inside) {
        if (r == null || r.mark == Mark.NONE)
            return null;
        final GeometryCollection gc = new GeometryCollection(2);
        int marks = 0;
        for (LineString ls : lines(g)) {
            if (ls.getNumPoints() < 2)
                continue;
            final List<double[]> gaps = new ArrayList<>();
            if (r.mark == Mark.ZIGZAG) {
                // A continuous zigzag: one point every half period, alternating sides.
                final List<Spot> sp = spots(ls, r.sizeM * 0.7, 0);
                if (sp.size() >= 3) {
                    final LineString z = new LineString(2);
                    boolean up = true;
                    for (Spot s : sp) {
                        final double b = up ? r.sizeM / 2 : -r.sizeM / 2;
                        z.addPoint(s.x + (s.lx * b) / s.mPerLon, s.y + (s.ly * b) / s.mPerLat);
                        up = !up;
                    }
                    gc.addGeometry(z);
                    marks++;
                } else {
                    gc.addGeometry(ls);
                }
                continue;
            }
            final List<Spot> sp = spots(ls, r.spacingM, r.spacingM / 2);
            int n = 0, li = 0;
            boolean lastLeft = false; // the side the last unambiguous tick took
            for (Spot s : sp) {
                switch (r.mark) {
                    case TICKS: {
                        // On the burned side. Tested close in and further out, because a narrow
                        // finger of fire is inside on both sides at one distance and outside on
                        // both at another; when neither distance decides, keep the last side.
                        boolean left = lastLeft;
                        if (inside != null) {
                            for (double dist : new double[] { r.sizeM * 0.4, r.sizeM, r.sizeM * 2.5 }) {
                                final boolean rightIn = inside.inside(s.x - s.lx * dist / s.mPerLon, s.y - s.ly * dist / s.mPerLat);
                                final boolean leftIn = inside.inside(s.x + s.lx * dist / s.mPerLon, s.y + s.ly * dist / s.mPerLat);
                                if (rightIn != leftIn) {
                                    left = leftIn;
                                    break;
                                }
                            }
                            lastLeft = left;
                        }
                        final double bx = left ? s.lx : -s.lx, by = left ? s.ly : -s.ly;
                        final LineString t = new LineString(2);
                        t.addPoint(s.x, s.y);
                        t.addPoint(s.x + bx * r.sizeM / s.mPerLon, s.y + by * r.sizeM / s.mPerLat);
                        gc.addGeometry(t);
                        n++;
                        break;
                    }
                    case X_CHAIN:
                    case X_GROUPS: {
                        if (r.mark == Mark.X_GROUPS && (li++ % 4) >= 2)
                            break; // two X's, then a gap the width of two
                        for (double[][] stroke : glyph('X'))
                            gc.addGeometry(local(s, stroke, r.sizeM / 0.7, r.sizeM, -r.sizeM / 2, -r.sizeM / 2));
                        n++;
                        break;
                    }
                    case LETTERS: {
                        final char c = r.letters.charAt(li++ % r.letters.length());
                        final double w = r.sizeM * 0.7;
                        for (double[][] stroke : glyph(c))
                            gc.addGeometry(local(s, stroke, r.sizeM, r.sizeM, -w / 2, -r.sizeM / 2));
                        if (r.base)
                            gaps.add(new double[] { s.d - w / 2 - r.sizeM * 0.2, s.d + w / 2 + r.sizeM * 0.2 });
                        n++;
                        break;
                    }
                    case BOXED_DOTS: {
                        // A bar across the line at every spot, a dot halfway to the next.
                        final double h = r.sizeM / 2;
                        gc.addGeometry(local(s, new double[][] { { 0, -1 }, { 0, 1 } }, 1, h, 0, 0));
                        final double dd = r.sizeM / 8, mid = r.spacingM / 2;
                        gc.addGeometry(local(s, new double[][] { { -1, -1 }, { 1, -1 }, { 1, 1 }, { -1, 1 }, { -1, -1 } }, dd, dd, mid, 0));
                        n++;
                        break;
                    }
                    default:
                        break;
                }
            }
            marks += n;
            if (r.base) {
                for (LineString piece : gaps.isEmpty() ? java.util.Collections.singletonList(ls) : pieces(ls, gaps))
                    gc.addGeometry(piece);
            } else if (n == 0) {
                gc.addGeometry(ls); // too short for a single mark: show the line rather than nothing
            }
        }
        return marks > 0 ? gc : null;
    }
}
