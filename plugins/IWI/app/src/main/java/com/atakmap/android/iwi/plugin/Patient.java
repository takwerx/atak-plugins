package com.atakmap.android.iwi.plugin;

import java.util.HashMap;
import java.util.Map;

/**
 * One patient in an IWI: a number, never a name (IRPG p. 107: "Do not use patient
 * name(s) on the radio"), a triage color, and what the follow-ups have picked.
 *
 * <p>Every observation is a pick from {@link Vocab}: the IRPG p. 108 Patient
 * Assessment and the pp. 110-114 treatments. Weight, for short-haul and hoist, is a
 * number in pounds. No Android types.
 */
public final class Patient {

    public static final String LOC = "loc";
    public static final String BREATHING = "breathing";
    public static final String PULSE = "pulse";
    public static final String BLEEDING = "bleeding";
    public static final String SKIN_COLOR = "skin_color";
    public static final String SKIN_MOIST = "skin_moist";
    public static final String SKIN_TEMP = "skin_temp";
    public static final String PUPILS = "pupils";
    public static final String REACTION = "reaction";
    public static final String TREATMENT = "treatment";
    public static final String WEIGHT = "weight";

    /** The fields a card shows, in order, and the list each picks from. */
    public static final String[] ONE_OF = {
            LOC, BREATHING, PULSE, BLEEDING, SKIN_COLOR, SKIN_MOIST, SKIN_TEMP, PUPILS, REACTION
    };

    /** Unique on this IWI, never shown: what a card's open/closed state is keyed by. */
    public int n;
    /** Stored colors are the triage keys: red, yellow, green, black. */
    public String color = IwiState.RED;
    /**
     * The color the patient was counted under and its number in that color: "Red 2".
     * Kept for the patient's life, so the name dispatch wrote down stays right after a
     * re-triage ("Red 2, now Yellow"), never renumbered (operator, 2026-10-14).
     */
    public String tag = IwiState.RED;
    public int tagN = 1;

    /** "Red 2". */
    public String label() {
        return name(tag) + " " + tagN;
    }

    public boolean recolored() {
        return !color.equals(tag);
    }

    /** "Red 2", or "Red 2, now Yellow" after a re-triage. */
    public String labelNow() {
        return recolored() ? label() + ", now " + name(color) : label();
    }
    /** When anything on the card last changed, or 0 when nothing has. */
    public long updatedMs;
    /** Each field's code, or for treatment a comma-separated list, or "" when not picked. */
    public final Map<String, String> v = new HashMap<>();

    public String get(String field) {
        final String s = v.get(field);
        return s == null ? "" : s.trim();
    }

    public boolean hasNotes() {
        for (String s : v.values())
            if (s != null && !s.trim().isEmpty())
                return true;
        return false;
    }

    public static Vocab.Choices choices(String field) {
        switch (field) {
            case LOC: return Vocab.LOC;
            case BREATHING: return Vocab.BREATHING;
            case PULSE: return Vocab.PULSE;
            case BLEEDING: return Vocab.BLEEDING;
            case SKIN_COLOR: return Vocab.SKIN_COLOR;
            case SKIN_MOIST: return Vocab.SKIN_MOIST;
            case SKIN_TEMP: return Vocab.SKIN_TEMP;
            case PUPILS: return Vocab.PUPILS;
            case REACTION: return Vocab.REACTION;
            case TREATMENT: return Vocab.TREATMENT;
            default: return null;
        }
    }

    /** Most severe first: Red, Yellow, Green, then Black. */
    public static int rank(String color) {
        if (IwiState.RED.equals(color))
            return 0;
        if (IwiState.YELLOW.equals(color))
            return 1;
        if (IwiState.GREEN.equals(color))
            return 2;
        return 3;
    }

    public static String name(String color) {
        if (IwiState.RED.equals(color))
            return "Red";
        if (IwiState.YELLOW.equals(color))
            return "Yellow";
        if (IwiState.GREEN.equals(color))
            return "Green";
        return "Black";
    }

    public static final String[] COLORS = {
            IwiState.RED, IwiState.YELLOW, IwiState.GREEN, IwiState.BLACK
    };
}
