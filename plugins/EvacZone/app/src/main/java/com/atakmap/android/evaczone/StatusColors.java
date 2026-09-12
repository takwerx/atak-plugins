package com.atakmap.android.evaczone;

import com.atakmap.map.layer.feature.style.BasicFillStyle;
import com.atakmap.map.layer.feature.style.BasicStrokeStyle;
import com.atakmap.map.layer.feature.style.CompositeStyle;
import com.atakmap.map.layer.feature.style.Style;

import java.util.Locale;

/**
 * One color language for every source. Counties do not agree on words ("Evacuation
 * Order", "Mandatory_Evac", "Level 3: Go Now", "Order") and some services draw every
 * status the same color (CalOES ships a simple renderer). When the catalog names the
 * status field, the zone is colored by what its status means, so an operator moving
 * between states reads one legend. Colors follow Genasys Protect's, which most fleet
 * users already know from their phones.
 */
public final class StatusColors {
    private StatusColors() {
    }

    enum Level {
        ORDER("Order", 0xFFD9534F),
        WARNING("Warning", 0xFFE5C447),
        ADVISORY("Advisory", 0xFF6A95CB),
        SHELTER("Shelter in Place", 0xFFBF6ADC),
        LIFTED("Lifted", 0xFF90D260),
        NORMAL("Normal", 0xFFF2F2F2),
        OTHER("Other", 0xFFFFFFFF);

        final String label;
        final int color;

        Level(String label, int color) {
            this.label = label;
            this.color = color;
        }
    }

    /** What a status string means, whatever its dialect. Unknown words are OTHER, never a guess. */
    static Level classify(String status) {
        if (status == null)
            return Level.OTHER;
        final String s = status.trim().toLowerCase(Locale.US);
        if (s.isEmpty())
            return Level.OTHER;
        // "Evacuation Order Lifted" contains "order": the ending is checked first.
        if (s.contains("lifted") || s.contains("clear") || s.contains("cancel") || s.contains("repopulat")
                || s.contains("rescind") || s.equals("open") || s.contains("/ open"))
            return Level.LIFTED;
        if (s.contains("shelter"))
            return Level.SHELTER;
        if (s.contains("order") || s.contains("mandatory") || s.contains("go now") || s.contains("level 3")
                || s.equals("3") || s.contains("tactical") || s.contains("immediate"))
            return Level.ORDER;
        if (s.contains("warning") || s.contains("vol") || s.contains("be set") || s.contains("level 2")
                || s.equals("2") || s.contains("alert"))
            return Level.WARNING;
        if (s.contains("advis") || s.contains("be ready") || s.contains("level 1") || s.equals("1")
                || s.contains("watch"))
            return Level.ADVISORY;
        if (s.contains("normal") || s.contains("none") || s.contains("no evac") || s.contains("pre-planned")
                || s.contains("preplanned"))
            return Level.NORMAL;
        return Level.OTHER;
    }

    /**
     * A zone's style: translucent fill and a solid edge in the level's color. Normal
     * zones are drawn faint, an outline and a whisper of fill, so a county that
     * publishes all of its zones does not paint itself over the base map.
     */
    static Style polygon(Level level, int fillAlpha) {
        final int rgb = level.color & 0x00FFFFFF;
        if (level == Level.NORMAL)
            return new CompositeStyle(new Style[] {
                    new BasicFillStyle((0x14 << 24) | rgb),
                    new BasicStrokeStyle((0x90 << 24) | rgb, 1.5f) });
        final Style edge = new BasicStrokeStyle(0xFF000000 | rgb, 2.5f);
        if (fillAlpha <= 0)
            return edge;
        return new CompositeStyle(new Style[] { new BasicFillStyle((Math.min(255, fillAlpha) << 24) | rgb), edge });
    }

    static Style line(Level level) {
        return new BasicStrokeStyle(level.color, 3f);
    }

    /** Where a legend label sits in severity order: Order first, Lifted after, unknown last. */
    public static int rank(String label) {
        for (Level l : Level.values())
            if (l.label.equalsIgnoreCase(label))
                return l.ordinal();
        return Level.values().length;
    }
}
