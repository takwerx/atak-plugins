package com.atakmap.android.iwi.plugin;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * What happened, picked from a list: item 2's "Nature of Injury or Illness and
 * Mechanism of Injury" with no free text (operator, 2026-10-14).
 *
 * <p>Two groups, the way a crew sorts it in the first minute: Medical (an illness)
 * and Trauma (how they were hurt). The topics follow the IRPG's Emergency Medical
 * Care pages (PMS 461, January 2025, pp. 110-114: allergic reaction, heat-related
 * injury, burns) and the MIR's own example, "unconscious, struck by a falling tree".
 * Each has a short label for its tile and the words said on the radio.
 *
 * <p>Stored as a comma-separated list of codes. No Android types.
 */
public final class Nature {

    public static final String[] MEDICAL = {
            "chest_pain", "allergic", "respiratory", "heat", "unconscious", "seizure",
            "diabetic", "other_medical"
    };
    public static final String[] TRAUMA = {
            "tree_strike", "vehicle", "fall", "rolling_rock", "chainsaw", "burns",
            "electrical", "other_trauma"
    };

    private Nature() {
    }

    /** The tile's label. */
    public static String label(String code) {
        switch (code) {
            case "chest_pain": return "Chest pain";
            case "allergic": return "Allergic reaction";
            case "respiratory": return "Respiratory";
            case "heat": return "Heat illness";
            case "unconscious": return "Unconscious";
            case "seizure": return "Seizure";
            case "diabetic": return "Diabetic";
            case "other_medical": return "Other medical";
            case "tree_strike": return "Tree strike";
            case "vehicle": return "Vehicle accident";
            case "fall": return "Fall";
            case "rolling_rock": return "Rolling rock";
            case "chainsaw": return "Chainsaw / tool";
            case "burns": return "Burns";
            case "electrical": return "Electrical";
            case "other_trauma": return "Other trauma";
            default: return "";
        }
    }

    /** What is said on the radio for it. */
    public static String spoken(String code) {
        switch (code) {
            case "chest_pain": return "chest pain";
            case "allergic": return "allergic reaction";
            case "respiratory": return "difficulty breathing";
            case "heat": return "heat-related illness";
            case "unconscious": return "unconscious";
            case "seizure": return "seizure";
            case "diabetic": return "diabetic emergency";
            case "other_medical": return "other medical";
            case "tree_strike": return "struck by a falling tree";
            case "vehicle": return "vehicle accident";
            case "fall": return "fall";
            case "rolling_rock": return "struck by a rolling rock";
            case "chainsaw": return "cut by a chainsaw or tool";
            case "burns": return "burns";
            case "electrical": return "electrical contact";
            case "other_trauma": return "other injury";
            default: return "";
        }
    }

    /** The codes in a stored value, in list order, unknown ones dropped. */
    public static Set<String> parse(String stored) {
        final Set<String> picked = new LinkedHashSet<>();
        if (stored == null)
            return picked;
        for (String part : stored.split(","))
            picked.add(part.trim());
        final Set<String> out = new LinkedHashSet<>();
        for (String c : MEDICAL)
            if (picked.contains(c))
                out.add(c);
        for (String c : TRAUMA)
            if (picked.contains(c))
                out.add(c);
        return out;
    }

    public static String format(Set<String> codes) {
        final StringBuilder b = new StringBuilder();
        for (String c : codes)
            b.append(b.length() > 0 ? "," : "").append(c);
        return b.toString();
    }

    /** The stored value with one code switched on or off. */
    public static String toggle(String stored, String code) {
        final Set<String> codes = parse(stored);
        if (!codes.remove(code))
            codes.add(code);
        return format(parse(format(codes)));
    }

    /** "unconscious, struck by a falling tree", medical first. "" when none is picked. */
    public static String said(String stored) {
        final List<String> words = new ArrayList<>();
        for (String c : parse(stored))
            words.add(spoken(c));
        final StringBuilder b = new StringBuilder();
        for (String w : words)
            b.append(b.length() > 0 ? ", " : "").append(w);
        return b.toString();
    }
}
