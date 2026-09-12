package com.atakmap.android.featurelayer;

import com.atakmap.map.layer.feature.style.BasicFillStyle;
import com.atakmap.map.layer.feature.style.BasicStrokeStyle;
import com.atakmap.map.layer.feature.style.CompositeStyle;
import com.atakmap.map.layer.feature.style.IconPointStyle;
import com.atakmap.map.layer.feature.style.LabelPointStyle;
import com.atakmap.map.layer.feature.style.PatternStrokeStyle;
import com.atakmap.map.layer.feature.style.Style;

import java.util.Locale;

/**
 * NWCG Event symbology, approximated with ATAK feature styles.
 *
 * <p>The archive's own renderer is a crude web approximation (an 18 pt black bar for
 * a completed dozer line), so lines are authored here from the NWCG standard instead:
 * completed line is black with a white texture that says which kind, planned line is
 * the same but dashed, proposed line is magenta, perimeter is red or black. Points
 * keep the standard ICS icons, which come from the renderer as-is.
 */
public final class NwcgStyles {
    private NwcgStyles() {
    }

    // Sampled from the standard's own symbol images (PMS 936), not chosen.
    static final int BLACK = 0xFF000000, WHITE = 0xFFFFFFFF, RED = 0xFFE00000,
            MAGENTA = 0xFFF000C0, GRAY = 0xFFC0C0C0, GREEN = 0xFF30A000,
            TAN = 0xFFA08040, YELLOW = 0xFFF0F000, ORANGE = 0xFFF0A000,
            PURPLE = 0xFFC000F0, DARK_PURPLE = 0xFF900090, SKY = 0xFF70B0F0,
            PINK = 0xFFF07070, FENCE_RED = 0xFFC01020, HOSE_BLUE = 0xFF0070F0,
            GREY_EDGE = 0xFF808080, DARK_GREY = 0xFF707070;
    /** Strategic Operations lines, drawn under the tactical line they apply to. */
    static final int STRAT_PRIMARY = 0xFF1080C0, STRAT_SECONDARY = 0xFF40D0C0,
            STRAT_PROPOSED = 0xFFB0D010, STRAT_LIMITED = 0xFFA03000;
    /** Repair Status, the secondary symbology on points and lines. */
    static final int REPAIR_NEEDED = 0xFFF00000, REPAIR_PROGRESS = 0xFFF0A000,
            REPAIR_READY = 0xFFF0F000, REPAIR_DONE = 0xFF40E000, REPAIR_ASSESS = 0xFFC000F0,
            REPAIR_DEFERRED = 0xFF70B0D0, REPAIR_IN_USE = 0xFFF000C0, REPAIR_OTHER = 0xFF909090;

    /** 16-bit pixel patterns; {@code factor} is pixels per bit. */
    static final short DASH = (short) 0xF0F0;      // 8 on, 8 off
    static final short DASH_LONG = (short) 0xFFF0; // 12 on, 4 off
    static final short DOT = (short) 0xAAAA;       // 1 on, 1 off
    static final short DASH_DOT = (short) 0xFC30;  // 6 on, 4 off, 2 on, 4 off

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

    static Style over(Style base, Style top) {
        return new CompositeStyle(new Style[] { base, top });
    }

    /**
     * Tick marks across a line: a wide stroke whose pattern is on for {@code onPx} pixels
     * out of every 32, drawn over the thin line. ATAK centers a stroke on the line, so
     * the ticks straddle it, the nearest thing to NWCG's one-sided ticks, X's and H's.
     */
    static Style ticks(int color, float across, int onPx) {
        final short pattern = (short) ((1 << Math.max(1, Math.min(8, onPx / 2))) - 1);
        return dashed(color, pattern, 2, across);
    }

    /** Event_Line, keyed on FeatureCategory. */
    public static Style line(String category) {
        if (category == null)
            return solid(WHITE, 2f);
        switch (category) {
            // Completed lines: black, with the standard's letters, X chain, zigzag or boxed
            // dots built as geometry by NwcgDecor (see recipe()); the stroke is only weight.
            case "Completed Dozer Line":
            case "Completed Hand Line":
            case "Completed Mixed Construction Line":
            case "Completed Plow Line":
            case "Completed Fuel Break":
            case "Completed Road as Line":
            case "Completed Burnout":
                return solid(BLACK, 3f);
            // Planned lines: the same marks in magenta; mixed, plow and road ride on a
            // square-dotted line, the rest on a solid one or on nothing.
            case "Planned Dozer Line":
            case "Planned Hand Line":
            case "Planned Fuel Break":
            case "Planned Burnout":
                return solid(MAGENTA, 3f);
            case "Planned Mixed Construction Line":
            case "Planned Plow Line":
            case "Planned Road as Line":
                return dashed(MAGENTA, DOT, 4, 3f);
            case "Proposed Line":
                // NWCG: magenta square dots.
                return dashed(MAGENTA, DOT, 4, 3.5f);
            case "Access Route":
            case "Aviation Route":
                return dashed(PURPLE, DASH, 3, 3f);
            case "Escape Route":
                return dashed(GREEN, DASH, 3, 3f);
            case "Fence":
                return dashed(FENCE_RED, DASH_LONG, 2, 2f);
            case "Highlighted Feature":
                // NWCG: yellow circles on a black dashed line.
                return over(solid(YELLOW, 5f), dashed(BLACK, DASH, 2, 2f));
            case "Management Action Point":
                return solid(ORANGE, 3.5f);
            case "Repair Line":
                return solid(TAN, 3.5f);
            case "Road Repair":
                return dashed(TAN, DASH, 3, 3.5f);
            case "Aerial Hazard":
                // NWCG: purple line with hazard triangles.
                return over(solid(DARK_PURPLE, 4f), dashed(YELLOW, DOT, 3, 2f));
            case "Retardant Drop":
                // NWCG: pink boxes on a purple line.
                return over(solid(DARK_PURPLE, 3f), dashed(PINK, DASH, 3, 6f));
            case "Temporary Flight Restriction":
                return dashed(SKY, DASH, 3, 3.5f);
            case "Fire Edge (Field Collection)":
                // NWCG: same red ticked line as the uncontained edge.
                return solid(RED, 3f);
            case "Other":
                return dashed(GRAY, DASH_LONG, 2, 2f);
            case "Break Line":
                // NWCG: black dotted with yellow diamonds; the diamonds become wide yellow dashes.
                return over(dashed(YELLOW, DOT, 6, 7f), dashed(BLACK, DOT, 3, 3f));
            case "Hoselay":
                // NWCG: blue with water drops along it; a fat blue dot every 32 px.
                return over(solid(HOSE_BLUE, 2.5f), dashed(HOSE_BLUE, (short) 0x8000, 2, 8f));
            case "Contained":
            case "Contained Fire Edge":
                return solid(BLACK, 4f);
            case "Uncontained":
            case "Uncontained Fire Edge":
                return solid(RED, 4f);
            case "Primary Strategic Line":
                return solid(STRAT_PRIMARY, 6f);
            case "Secondary Strategic Line":
                return solid(STRAT_SECONDARY, 6f);
            case "Proposed Strategic Line":
                return solid(STRAT_PROPOSED, 6f);
            case "Limited Action Line":
                return solid(STRAT_LIMITED, 3f);
            default:
                return solid(WHITE, 2f);
        }
    }

    /**
     * How a category's line is decorated, in ground meters: letters 18 m tall every 60 m,
     * X's 16 m and touching, ticks 24 m every 50 m. Null for a plain stroke.
     */
    public static NwcgDecor.Recipe recipe(String category) {
        if (category == null)
            return null;
        switch (category) {
            case "Uncontained":
            case "Uncontained Fire Edge":
            case "Fire Edge (Field Collection)":
                return NwcgDecor.ticks(50, 24);
            case "Completed Hand Line":
            case "Planned Hand Line":
                return NwcgDecor.letters("H", 60, 18);
            case "Completed Road as Line":
            case "Planned Road as Line":
                return NwcgDecor.letters("R", 60, 18);
            case "Completed Mixed Construction Line":
            case "Planned Mixed Construction Line":
                return NwcgDecor.letters("M", 60, 18);
            case "Completed Plow Line":
            case "Planned Plow Line":
                return NwcgDecor.letters("P", 60, 18);
            case "Completed Dozer Line":
                return NwcgDecor.xChain(16);
            case "Planned Dozer Line":
                return NwcgDecor.xGroups(16);
            case "Completed Fuel Break":
                return NwcgDecor.zigzag(14);
            case "Planned Fuel Break":
                return NwcgDecor.chevrons(40, 18);
            case "Completed Burnout":
            case "Planned Burnout":
                return NwcgDecor.boxedDots(40, 14);
            default:
                return null;
        }
    }

    /** Perimeter_Line: NWCG's uncontained edge is a heavy red line with ticks; contained is black. */
    public static Style perimeter(String category) {
        if (category != null && category.startsWith("Contained"))
            return solid(BLACK, 4f);
        if (category != null && category.startsWith("Uncontained"))
            return solid(RED, 4f); // ticks are drawn as geometry, one-sided like the standard
        return solid(RED, 3f);
    }

    /**
     * Strategic Operations: an Event Line's StrategicLineType is drawn as a wide colored
     * band under the tactical line (primary blue, secondary teal, proposed yellow-green,
     * limited action brown), the way the standard's Strategic Operations view shows it.
     */
    public static Style strategic(String type) {
        if (type == null)
            return null;
        final String t = type.toLowerCase(Locale.US);
        // Wider than the Repair Status band so both read when a line carries both.
        if (t.contains("primary"))
            return solid(STRAT_PRIMARY, 12f);
        if (t.contains("secondary"))
            return solid(STRAT_SECONDARY, 12f);
        if (t.contains("proposed"))
            return solid(STRAT_PROPOSED, 12f);
        if (t.contains("limited"))
            return solid(STRAT_LIMITED, 10f);
        return null;
    }

    /** The Repair Status color of the standard's secondary symbology, or 0 for none. */
    public static int repairColor(String status) {
        if (status == null)
            return 0;
        final String s = status.toLowerCase(Locale.US);
        if (s.contains("no repair"))
            return 0;
        if (s.contains("ready"))
            return REPAIR_READY;      // Completed - Ready for Inspection
        if (s.contains("inspect"))
            return REPAIR_DONE;       // Completed - Inspected / Inspection
        if (s.contains("needed"))
            return REPAIR_NEEDED;
        if (s.contains("progress"))
            return REPAIR_PROGRESS;
        if (s.contains("assess"))
            return REPAIR_ASSESS;
        if (s.contains("defer"))
            return REPAIR_DEFERRED;
        if (s.contains("in use") || s.contains("fire management"))
            return REPAIR_IN_USE;
        if (s.contains("other"))
            return REPAIR_OTHER;
        return 0;
    }

    /** In Use - Fire Management is the one status drawn as a ring, not a disc. */
    public static boolean repairHollow(String status) {
        return repairColor(status) == REPAIR_IN_USE;
    }

    /**
     * A line with its Repair Status as a colored band under it, the way the standard's
     * Repair Status view draws it: the tactical line stays black with its X's or H's, and
     * the status shows as the halo. On top it painted the whole line green.
     */
    public static Style withLineRepair(Style base, String status) {
        final int c = repairColor(status);
        return c == 0 ? base : over(solid(c, 7f), base);
    }

    /**
     * The style plus an empty label: the feature keeps its name for the tap chooser and
     * details, but ATAK draws the style's label text (nothing) instead of the name along
     * every line and area.
     */
    public static Style silentLabel(Style s) {
        final Style silent = new LabelPointStyle("", 0, 0, LabelPointStyle.ScrollMode.OFF);
        if (s instanceof CompositeStyle) {
            final CompositeStyle cs = (CompositeStyle) s;
            final Style[] all = new Style[cs.getNumStyles() + 1];
            for (int i = 0; i < cs.getNumStyles(); i++)
                all[i] = cs.getStyle(i);
            all[all.length - 1] = silent;
            return new CompositeStyle(all);
        }
        return new CompositeStyle(new Style[] { s, silent });
    }

    /** The standard's area symbols, Event Polygon and IR Polygon. */
    public enum Area { WILDFIRE, PRESCRIBED, HAZARD, IR_INTENSE, IR_SCATTERED, IR_HEAT_PERIMETER, IR_FLIGHT, CLOUD, OTHER, UNKNOWN }

    public static Area area(String category) {
        if (category == null)
            return Area.UNKNOWN;
        final String c = category.toLowerCase(Locale.US);
        if (c.startsWith("wildfire"))
            return Area.WILDFIRE;
        if (c.startsWith("prescribed"))
            return Area.PRESCRIBED;
        if (c.contains("hazard"))
            return Area.HAZARD;
        if (c.contains("intense"))
            return Area.IR_INTENSE;
        if (c.contains("scattered"))
            return Area.IR_SCATTERED;
        if (c.contains("heat perimeter"))
            return Area.IR_HEAT_PERIMETER;
        if (c.contains("covered") || c.contains("ir flight"))
            return Area.IR_FLIGHT;
        if (c.contains("cloud") || c.contains("no data"))
            return Area.CLOUD;
        if (c.contains("other"))
            return Area.OTHER;
        return Area.UNKNOWN;
    }

    /** {edge color, fill rgb (0 = never filled), edge width in tenths, fill alpha scale %}. */
    private static int[] areaSpec(Area a) {
        switch (a) {
            case WILDFIRE:
                return new int[] { RED, 0xF00000, 25, 100 };
            case PRESCRIBED:
                return new int[] { ORANGE, 0xF0A000, 25, 100 };
            case HAZARD: // grey edge, yellow and red hatch; the hatch becomes a yellow fill
                return new int[] { GREY_EDGE, 0xF0F000, 20, 100 };
            case IR_INTENSE:
                return new int[] { RED, 0xF00000, 20, 150 };
            case IR_SCATTERED:
                return new int[] { RED, 0xF00000, 20, 60 };
            case IR_HEAT_PERIMETER:
                return new int[] { RED, 0, 30, 0 };
            case IR_FLIGHT:
                return new int[] { GREEN, 0x30A000, 20, 70 };
            case CLOUD:
                return new int[] { DARK_GREY, 0x707070, 20, 80 };
            case OTHER:
                return new int[] { GREY_EDGE, 0, 15, 0 };
            default:
                return new int[] { WHITE, 0xFFFFFF, 15, 100 };
        }
    }

    /**
     * The style plus a label with no text of its own, white on a dark pill. On a geometry
     * that is an area and its center point in one collection, the point child draws the
     * feature's name in it and the area child, which labels only from a label style's own
     * text, stays silent. One feature, one label, at the center (Evac Zone's rule).
     */
    public static Style withNameLabel(Style s) {
        final Style pill = new LabelPointStyle("", WHITE, 0xA0000000, LabelPointStyle.ScrollMode.OFF, 0f, 0, 100, 0f, false);
        if (s instanceof CompositeStyle) {
            final CompositeStyle cs = (CompositeStyle) s;
            final Style[] all = new Style[cs.getNumStyles() + 1];
            for (int i = 0; i < cs.getNumStyles(); i++)
                all[i] = cs.getStyle(i);
            all[all.length - 1] = pill;
            return new CompositeStyle(all);
        }
        return new CompositeStyle(new Style[] { s, pill });
    }

    /** The style with any label text removed and an empty label put in its place, so ATAK draws no name. */
    public static Style withoutLabel(Style s) {
        if (s instanceof CompositeStyle) {
            final CompositeStyle cs = (CompositeStyle) s;
            final java.util.List<Style> keep = new java.util.ArrayList<>();
            for (int i = 0; i < cs.getNumStyles(); i++)
                if (!(cs.getStyle(i) instanceof LabelPointStyle))
                    keep.add(cs.getStyle(i));
            keep.add(new LabelPointStyle("", 0, 0, LabelPointStyle.ScrollMode.OFF));
            return new CompositeStyle(keep.toArray(new Style[0]));
        }
        if (s instanceof LabelPointStyle)
            return new LabelPointStyle("", 0, 0, LabelPointStyle.ScrollMode.OFF);
        return silentLabel(s);
    }

    /** The full-opacity fill color an area category uses (the fill control's hue). */
    public static int polygonFill(String category) {
        final int[] p = areaSpec(area(category));
        return 0xFF000000 | (p[1] == 0 ? 0xFFFFFF : p[1]);
    }

    /** An area at the layer's fill opacity (0 = outline only) with the standard's edge. */
    public static Style polygon(String category, int fillAlpha) {
        final int[] p = areaSpec(area(category));
        final Style edge = solid(p[0], p[2] / 10f);
        if (fillAlpha <= 0 || p[1] == 0)
            return edge;
        final int a = Math.min(255, fillAlpha * p[3] / 100);
        return over(new BasicFillStyle((a << 24) | p[1]), edge);
    }

    /** Event_Point: the official 60 px NWCG icon at its own size (sharp on a dense screen). */
    public static Style point(String iconUri) {
        return point(iconUri, 1.0f);
    }

    /** The icon at a fraction of its own size, for symbols a standard draws small. */
    public static Style point(String iconUri, float scale) {
        return new IconPointStyle(WHITE, iconUri, scale, 0, 0, 0f, true);
    }

    /**
     * A point's label, as its own feature in a resolution-gated set. The label style's
     * own minimum render resolution did not gate anything on the XCover (labels drew at
     * 74 m/px with it set to 15), so the feature set does the gating instead.
     */
    public static Style label(String text) {
        return new LabelPointStyle(text, WHITE, 0xA0000000,
                LabelPointStyle.ScrollMode.OFF, 0f, 0, 100, 0f, false);
    }
}
