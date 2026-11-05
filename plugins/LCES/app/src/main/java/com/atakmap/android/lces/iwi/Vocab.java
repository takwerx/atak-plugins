package com.atakmap.android.lces.iwi;


import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Every pick list in the report: what a tile says, and what is said on the radio.
 *
 * <p>The operator, 2026-10-14: "we get the iwi, we need to get the ball rolling, just
 * the initial data" and "i need to check box, pick etc as much of this as possible".
 * So the report is tiles, not boxes. The lists come from the IRPG (PMS 461, January
 * 2025): the MIR's own example resources (item 5), the Patient Assessment on p. 108,
 * and the Specific Treatments on pp. 110-114.
 *
 * <p>A one-of list stores its code; a pick-many list stores codes separated by commas.
 * No Android types, so the radio script runs on a desktop JVM.
 */
public final class Vocab {

    /** One list: codes, tile labels and spoken words, in the same order. */
    public static final class Choices {
        public final String[] codes;
        public final String[] labels;
        public final String[] spoken;
        /** In a pick-many list, the code that clears all the others ("None needed"). */
        public final String exclusive;

        Choices(String[] codes, String[] labels, String[] spoken, String exclusive) {
            this.codes = codes;
            this.labels = labels;
            this.spoken = spoken;
            this.exclusive = exclusive;
        }

        public int index(String code) {
            for (int i = 0; i < codes.length; i++)
                if (codes[i].equals(code))
                    return i;
            return -1;
        }

        public String label(String code) {
            final int i = index(code);
            return i < 0 ? "" : labels[i];
        }

        public String spoken(String code) {
            final int i = index(code);
            return i < 0 ? "" : spoken[i];
        }
    }

    private Vocab() {
    }

    private static Choices same(String... words) {
        final String[] lower = new String[words.length];
        for (int i = 0; i < words.length; i++)
            lower[i] = words[i].toLowerCase(java.util.Locale.US);
        return new Choices(words, words, lower, null);
    }

    // ---- the initial report ----

    public static final Choices CONTACT = new Choices(
            new String[] {"Communications", "Dispatch"},
            new String[] {"Communications", "Dispatch"},
            new String[] {"Communications", "Dispatch"}, null);

    public static final Choices MEDICAL = new Choices(
            new String[] {"cardiac_arrest", "chest_pain", "allergic", "respiratory", "heat",
                    "unconscious", "seizure", "diabetic", "other_medical"},
            new String[] {"Cardiac Arrest", "Chest Pain", "Allergic Reaction", "Respiratory",
                    "Heat Illness", "Unconscious", "Seizure", "Diabetic", "Other Medical"},
            new String[] {"cardiac arrest", "chest pain", "allergic reaction",
                    "difficulty breathing", "heat-related illness", "unconscious", "seizure",
                    "diabetic emergency", "other medical"}, null);

    public static final Choices TRAUMA = new Choices(
            new String[] {"tree_strike", "vehicle", "fall", "rolling_rock", "chainsaw", "burns",
                    "electrical", "other_trauma"},
            new String[] {"Tree Strike", "Vehicle Accident", "Fall", "Rolling Rock",
                    "Chainsaw / Tool", "Burns", "Electrical", "Other Trauma"},
            new String[] {"struck by a falling tree", "vehicle accident", "fall",
                    "struck by a rolling rock", "cut by a chainsaw or tool", "burns",
                    "electrical contact", "other injury"}, null);

    /** Stored as the label, which is what earlier builds stored. */
    public static final Choices EVAC = new Choices(
            new String[] {"Air Ambulance", "Short-Haul", "Hoist", "Ground Ambulance", "Other"},
            new String[] {"Air Ambulance", "Short-Haul", "Hoist", "Ground Ambulance", "Other"},
            new String[] {"air ambulance", "short-haul", "hoist", "ground ambulance",
                    "other transport"}, null);

    /** The highest level of medical training on scene. No names on the initial call. */
    public static final Choices CARE = new Choices(
            new String[] {"none", "EMT", "Paramedic"},
            new String[] {"None", "EMT", "Paramedic"},
            new String[] {"No EMT or Paramedic is on scene",
                    "An EMT is providing medical care",
                    "A Paramedic is providing medical care"}, null);

    /** The MIR's own item 5 examples, plus None. */
    public static final Choices RESOURCES = new Choices(
            new String[] {"paramedic", "crew", "litter", "immobilization", "aed", "oxygen",
                    "trauma_bag", "iv", "splints", "rope", "extrication", "hazmat", "none"},
            new String[] {"Paramedic/EMT", "Crew", "Wheeled Litter", "Immobilization", "AED",
                    "Oxygen", "Trauma Bag", "IV Fluids", "Splints", "Rope Rescue",
                    "Extrication", "HAZMAT", "None Needed"},
            new String[] {"a paramedic or EMT", "a crew", "a wheeled litter",
                    "immobilization devices", "an AED", "oxygen", "a trauma bag", "IV fluids",
                    "splints", "rope rescue", "extrication", "HAZMAT", "none"}, "none");

    // ---- a patient: IRPG p. 108, Patient Assessment ----

    public static final Choices LOC = new Choices(
            new String[] {"alert", "verbal", "pain", "unresponsive"},
            new String[] {"Alert", "Voice", "Pain", "Unresponsive"},
            new String[] {"alert and oriented", "responds to voice", "responds to pain",
                    "unresponsive"}, null);

    public static final Choices BREATHING = new Choices(
            new String[] {"normal", "labored", "none"},
            new String[] {"Normal", "Labored", "Not Breathing"},
            new String[] {"breathing normal", "labored breathing", "not breathing"}, null);

    public static final Choices PULSE = new Choices(
            new String[] {"present", "absent"},
            new String[] {"Present", "Absent"},
            new String[] {"pulse present", "no pulse"}, null);

    public static final Choices BLEEDING = new Choices(
            new String[] {"none", "controlled", "uncontrolled"},
            new String[] {"None", "Controlled", "Not Controlled"},
            new String[] {"no major bleeding", "bleeding controlled",
                    "bleeding not controlled"}, null);

    public static final Choices SKIN_COLOR = new Choices(
            new String[] {"normal", "pale", "bluish", "flushed"},
            new String[] {"Normal", "Pale", "Bluish", "Flushed"},
            new String[] {"", "pale", "bluish", "flushed"}, null);

    public static final Choices SKIN_MOIST = new Choices(
            new String[] {"normal", "dry", "clammy", "sweating"},
            new String[] {"Normal", "Dry", "Clammy", "Sweating"},
            new String[] {"", "dry", "moist and clammy", "profuse sweating"}, null);

    public static final Choices SKIN_TEMP = new Choices(
            new String[] {"normal", "hot", "cool", "cold"},
            new String[] {"Normal", "Hot", "Cool", "Cold"},
            new String[] {"", "hot", "cool", "cold"}, null);

    public static final Choices PUPILS = new Choices(
            new String[] {"equal", "unequal"},
            new String[] {"Equal", "Unequal"},
            new String[] {"equal", "unequal"}, null);

    public static final Choices REACTION = new Choices(
            new String[] {"reactive", "slow", "fixed"},
            new String[] {"Reactive", "Slow", "Fixed"},
            new String[] {"reactive", "slow to react", "fixed"}, null);

    /** IRPG pp. 110-114, Specific Treatments, CPR and Burn Injuries. */
    public static final Choices TREATMENT = new Choices(
            new String[] {"pressure", "tourniquet", "splint", "spine", "shock", "epi", "burns",
                    "cooling", "cpr", "aed", "oxygen", "eyes", "none_yet"},
            new String[] {"Direct Pressure", "Tourniquet", "Splinted", "Head/Neck",
                    "Shock Care", "Epi Assisted", "Burns Cooled", "Cooling", "CPR", "AED",
                    "Oxygen", "Eyes Washed", "None Yet"},
            new String[] {"direct pressure", "tourniquet", "splinted",
                    "head and neck stabilized", "treated for shock", "epinephrine assisted",
                    "burns cooled and wrapped", "active cooling", "CPR in progress",
                    "AED applied", "oxygen", "eyes washed and bandaged", "none yet"},
            "none_yet");

    // ---- pick-many values ----

    /** The codes in a stored pick-many value that belong to these lists, in list order. */
    public static Set<String> parse(String stored, Choices... lists) {
        final Set<String> raw = tokens(stored);
        final Set<String> out = new LinkedHashSet<>();
        for (Choices list : lists)
            for (String c : list.codes)
                if (raw.contains(c))
                    out.add(c);
        return out;
    }

    /**
     * The stored value with one code switched on or off. Codes from other lists in the
     * same value are kept. Picking the list's exclusive code clears the rest of the
     * list; picking anything else clears the exclusive one.
     */
    public static String toggle(String stored, String code, Choices list) {
        final Set<String> cur = tokens(stored);
        if (!cur.remove(code)) {
            if (list.exclusive != null) {
                if (code.equals(list.exclusive)) {
                    for (String c : list.codes)
                        cur.remove(c);
                } else {
                    cur.remove(list.exclusive);
                }
            }
            cur.add(code);
        }
        final StringBuilder b = new StringBuilder();
        for (String c : cur)
            b.append(b.length() > 0 ? "," : "").append(c);
        return b.toString();
    }

    /** Free text from an earlier build has spaces; codes never do. */
    private static Set<String> tokens(String stored) {
        final Set<String> out = new LinkedHashSet<>();
        if (stored == null)
            return out;
        for (String part : stored.split(",")) {
            final String t = part.trim();
            if (!t.isEmpty() && !t.contains(" "))
                out.add(t);
        }
        return out;
    }

    /** "unconscious, struck by a falling tree": spoken words, commas between. */
    public static String said(String stored, Choices... lists) {
        final StringBuilder b = new StringBuilder();
        for (Choices list : lists)
            for (String c : parse(stored, list))
                b.append(b.length() > 0 ? ", " : "").append(list.spoken(c));
        return b.toString();
    }

    /** "a crew, a wheeled litter and oxygen". */
    public static String saidAnd(String stored, Choices list) {
        final List<String> words = new ArrayList<>();
        for (String c : parse(stored, list))
            words.add(list.spoken(c));
        return andList(words);
    }

    /** "Pale, Clammy": tile labels, for a row's head. */
    public static String labels(String stored, Choices... lists) {
        final StringBuilder b = new StringBuilder();
        for (Choices list : lists)
            for (String c : parse(stored, list))
                b.append(b.length() > 0 ? ", " : "").append(list.label(c));
        return b.toString();
    }

    static String andList(List<String> words) {
        final StringBuilder b = new StringBuilder();
        for (int i = 0; i < words.size(); i++) {
            if (i > 0)
                b.append(i == words.size() - 1 ? " and " : ", ");
            b.append(words.get(i));
        }
        return b.toString();
    }
}
