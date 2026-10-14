package com.atakmap.android.iwi.plugin;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The Medical Incident Report as it is said on the radio, one numbered section at a
 * time.
 *
 * <p>NWCG 6MFS: "When transmitting the report, state the number and title of each
 * section, and say 'break' and pause between the sections." The workflow (PLAN-IWI,
 * "The workflow") is an initial report with the basics, items 1, 2 and 5, then
 * follow-ups as more is known: item 3 for each patient, most severe first, and 4, 6,
 * 7 and 8 once they say something.
 *
 * <p>What the reporter has not filled in is a part marked missing, shown where it
 * would be said. The report page colors a section's row green exactly when its
 * section here has nothing missing, so the two cannot disagree.
 *
 * <p>No Android or ATAK types, so it runs against a plain map on a desktop JVM.
 */
public final class RadioScript {

    /** Where the report comes from. */
    public interface Source {
        /** The field, trimmed, or "" when empty. */
        String text(String key);

        /** How many patients are this triage color. */
        int count(String color);

        /** The patient cards, in the order they were added. */
        List<Patient> patients();
    }

    /** A run of words, or a gap the reporter has not filled in. */
    public static final class Part {
        public final String text;
        public final boolean missing;

        Part(String text, boolean missing) {
            this.text = text;
            this.missing = missing;
        }
    }

    public static final class Section {
        public final int number;
        public final String title;
        public final List<Part> parts = new ArrayList<>();
        /** What to say or do after it: "BREAK", or null for the last. */
        public String after = "BREAK";
        /** How many of its fields are filled in. */
        public int filled;
        /** How many are not. */
        public int missing;

        Section(int number, String title) {
            this.number = number;
            this.title = title;
        }

        Section say(String text) {
            parts.add(new Part(text, false));
            return this;
        }

        Section gap(String what) {
            parts.add(new Part("[" + what + "]", true));
            missing++;
            return this;
        }

        /** The value, or a gap naming it. */
        Section field(String value, String what) {
            if (value == null || value.isEmpty())
                return gap(what);
            filled++;
            return say(value);
        }

        public boolean complete() {
            return missing == 0;
        }

        public boolean hasContent() {
            return filled > 0;
        }
    }

    private RadioScript() {
    }

    // ---- the two scripts ----

    /** Items 1, 2 and 5: what is said first. Missing parts included, never blocking. */
    public static List<Section> initial(Source s, String coords) {
        final List<Section> out = new ArrayList<>();
        out.add(contact(s));
        out.add(status(s, coords));
        final Section five = resources(s);
        five.after = null;
        out.add(five);
        return out;
    }

    /**
     * What has been learned since: item 3 for every patient with notes, most severe
     * first, then 4, 6, 7 and 8 when they say anything. Empty when nothing has been
     * filled in yet.
     */
    public static List<Section> followUp(Source s) {
        final List<Section> out = new ArrayList<>();
        for (Patient p : bySeverity(s.patients()))
            if (p.hasNotes())
                out.add(patient(s, p));
        final Section[] rest = {evacPlan(s), comms(s), contingency(s), additional(s)};
        for (Section x : rest)
            if (x.hasContent())
                out.add(x);
        if (!out.isEmpty())
            out.get(out.size() - 1).after = null;
        return out;
    }

    /**
     * How a follow-up call opens: "Communications, Div. Alpha, update on the Trout
     * Meadow Medical." Whatever is not filled in is left out rather than shown.
     */
    public static String updateLead(Source s) {
        final String to = s.text(IwiState.CONTACT_TO);
        final String from = s.text(IwiState.CONTACT_FROM);
        final String incident = s.text(IwiState.INCIDENT);
        final StringBuilder b = new StringBuilder();
        b.append(to.isEmpty() ? "Communications" : to);
        if (!from.isEmpty())
            b.append(", ").append(from);
        b.append(", update");
        if (!incident.isEmpty())
            b.append(" on the ").append(incident);
        return b.append('.').toString();
    }

    // ---- the sections ----

    /** 1. The IRPG's example: "Communications, Div. Alpha. Stand-by for Emergency Traffic." */
    public static Section contact(Source s) {
        final String to = s.text(IwiState.CONTACT_TO);
        final Section one = new Section(1, "Contact Communications / Dispatch")
                .field(to, "who you are calling, Ex: Communications").say(", ")
                .field(s.text(IwiState.CONTACT_FROM), "your callsign, Ex: Div. Alpha")
                .say(". Stand-by for Emergency Traffic.");
        one.after = "BREAK. Wait for " + (to.isEmpty() ? "Communications" : to)
                + " to answer.";
        return one;
    }

    /**
     * 2. "Communications, I have a Red priority patient, unconscious, struck by a falling
     * tree. Requesting air ambulance to Forest Road 1 at (Lat. / Long.). This will be the
     * Trout Meadow Medical, IC is TFLD Jones. EMT Smith is providing medical care."
     *
     * @param coords the patient's location in DDM, or null when no IWI is declared
     */
    public static Section status(Source s, String coords) {
        final Section two = new Section(2, "Incident Status").say("I have ")
                .field(triage(s), "how many patients, and how bad").say(", ")
                .field(Nature.said(s.text(IwiState.NATURE)), "what happened: pick from the list")
                .say(". Requesting ")
                .field(evac(s), "evacuation request").say(" to ")
                .field(s.text(IwiState.LOCATION), "descriptive location").say(" at ")
                .field(coords, "Lat. / Long.: declare the IWI").say(". This will be the ")
                .field(s.text(IwiState.INCIDENT), "incident name, Ex: Trout Meadow Medical")
                .say(", IC is ")
                .field(s.text(IwiState.IC), "on-scene IC").say(". ")
                .field(care(s), "patient care, Ex: EMT Smith")
                .say(" is providing medical care.");
        if (needsWeight(s)) {
            two.say(" On-scene radio contact ")
                    .field(s.text(IwiState.RADIO_CONTACT), "on-scene radio contact")
                    .say(".");
        }
        return two;
    }

    /** 3, for one patient: "Patient 2, Yellow priority. ... Treatment: ..." */
    public static Section patient(Source s, Patient p) {
        final Section three = new Section(3, "Initial Patient Assessment")
                .say("Patient " + p.n + ", " + Patient.name(p.color)
                        + (IwiState.BLACK.equals(p.color) ? ". " : " priority. "))
                .field(sentence(p.assessment.trim()), "assessment")
                .say(" Treatment: ")
                .field(sentence(p.treatment.trim()), "treatment");
        if (needsWeight(s))
            three.say(" Patient weight ").field(p.weight.trim(), "patient weight").say(".");
        return three;
    }

    public static Section evacPlan(Source s) {
        return new Section(4, "Evacuation Plan")
                .say("Evacuation location ")
                .field(s.text(IwiState.EVAC_LOCATION), "evacuation location, if different")
                .say(". Patient's ETA to the evacuation location ")
                .field(s.text(IwiState.ETA), "ETA").say(". Helispot ")
                .field(s.text(IwiState.HELISPOT), "helispot or extraction site size and hazards")
                .say(".");
    }

    public static Section resources(Source s) {
        return new Section(5, "Additional Resources / Equipment Needs")
                .field(sentence(s.text(IwiState.RESOURCES)),
                        "resources and equipment needed, or say none");
    }

    public static Section comms(Source s) {
        final Section six = new Section(6, "Communications");
        boolean anyRow = false;
        for (int i = 0; i < IwiState.COMMS_ROWS.length; i++) {
            final String row = commsRow(s, IwiState.COMMS_ROWS[i]);
            if (row.isEmpty())
                continue;
            six.say((anyRow ? " " : "") + IwiState.COMMS_ROW_NAMES[i] + ": ")
                    .field(row, "").say(".");
            anyRow = true;
        }
        if (!anyRow)
            six.gap("command, air-to-ground and tactical frequencies");
        return six;
    }

    public static Section contingency(Source s) {
        return new Section(7, "Contingency")
                .field(sentence(s.text(IwiState.CONTINGENCY)), "if the primary plan fails");
    }

    public static Section additional(Source s) {
        return new Section(8, "Additional Information")
                .field(sentence(s.text(IwiState.ADDITIONAL)), "updates or changes");
    }

    // ---- the marker's remarks ----

    /**
     * The report as plain text for ATAK's med line remarks: the items in MIR order with
     * their numbers, the order the radio script says them, and nothing the reporter
     * has not filled in.
     */
    public static String remarks(Source s, String coords) {
        final StringBuilder b = new StringBuilder();
        line(b, "2. Patients", triage(s));
        line(b, "Nature", Nature.said(s.text(IwiState.NATURE)));
        line(b, "Evacuation request", evac(s));
        line(b, "Location", join(s.text(IwiState.LOCATION), coords));
        line(b, "Incident", s.text(IwiState.INCIDENT));
        line(b, "On-scene IC", s.text(IwiState.IC));
        line(b, "Patient care", care(s));
        if (needsWeight(s))
            line(b, "On-scene radio contact", s.text(IwiState.RADIO_CONTACT));
        for (Patient p : bySeverity(s.patients())) {
            if (!p.hasNotes())
                continue;
            final StringBuilder one = new StringBuilder();
            if (!p.assessment.trim().isEmpty())
                one.append(p.assessment.trim());
            if (!p.treatment.trim().isEmpty())
                one.append(one.length() > 0 ? "; " : "").append("treatment ")
                        .append(p.treatment.trim());
            if (!p.weight.trim().isEmpty())
                one.append(one.length() > 0 ? "; " : "").append("weight ")
                        .append(p.weight.trim());
            line(b, "3. Patient " + p.n + " " + Patient.name(p.color), one.toString());
        }
        line(b, "4. Evacuation location", s.text(IwiState.EVAC_LOCATION));
        line(b, "Patient ETA", s.text(IwiState.ETA));
        line(b, "Helispot", s.text(IwiState.HELISPOT));
        line(b, "5. Resources", s.text(IwiState.RESOURCES));
        for (int i = 0; i < IwiState.COMMS_ROWS.length; i++)
            line(b, (i == 0 ? "6. " : "") + IwiState.COMMS_ROW_NAMES[i],
                    commsRow(s, IwiState.COMMS_ROWS[i]));
        line(b, "7. Contingency", s.text(IwiState.CONTINGENCY));
        line(b, "8. Additional", s.text(IwiState.ADDITIONAL));
        return b.toString().trim();
    }

    private static void line(StringBuilder b, String label, String value) {
        if (value == null || value.isEmpty())
            return;
        b.append(label).append(": ").append(value).append('\n');
    }

    private static String join(String a, String b) {
        if (a.isEmpty())
            return b == null ? "" : b;
        return b == null || b.isEmpty() ? a : a + ", " + b;
    }

    // ---- phrasing ----

    /**
     * "a Red priority patient", or the NWCG multi-casualty phrasing: "five patients,
     * three Reds, one Yellow, and one Green". "" when there are no patients.
     */
    public static String triage(Source s) {
        final int[] n = new int[Patient.COLORS.length];
        int total = 0;
        for (int i = 0; i < n.length; i++) {
            n[i] = s.count(Patient.COLORS[i]);
            total += n[i];
        }
        if (total == 0)
            return "";
        if (total == 1) {
            for (int i = 0; i < 3; i++)
                if (n[i] == 1)
                    return "a " + Patient.name(Patient.COLORS[i]) + " priority patient";
            return "one patient, Black";
        }
        final List<String> parts = new ArrayList<>();
        for (int i = 0; i < n.length; i++)
            if (n[i] > 0)
                parts.add(words(n[i]) + " " + Patient.name(Patient.COLORS[i])
                        + (n[i] > 1 ? "s" : ""));
        final StringBuilder b = new StringBuilder(words(total)).append(" patients, ");
        for (int i = 0; i < parts.size(); i++) {
            if (i > 0)
                b.append(i == parts.size() - 1 ? (parts.size() > 2 ? ", and " : " and ") : ", ");
            b.append(parts.get(i));
        }
        return b.toString();
    }

    /** "3 patients: 2 Red, 1 Green", for the main page. "" when there are none. */
    public static String summary(Source s) {
        final StringBuilder b = new StringBuilder();
        int total = 0;
        for (String c : Patient.COLORS) {
            final int n = s.count(c);
            if (n == 0)
                continue;
            total += n;
            b.append(b.length() > 0 ? ", " : "").append(n).append(' ').append(Patient.name(c));
        }
        if (total == 0)
            return "";
        return total + (total == 1 ? " patient: " : " patients: ") + b;
    }

    private static List<Patient> bySeverity(List<Patient> in) {
        final List<Patient> list = new ArrayList<>(in);
        java.util.Collections.sort(list, new java.util.Comparator<Patient>() {
            @Override
            public int compare(Patient a, Patient b) {
                final int r = Patient.rank(a.color) - Patient.rank(b.color);
                return r != 0 ? r : a.n - b.n;
            }
        });
        return list;
    }

    private static String evac(Source s) {
        final String v = s.text(IwiState.EVAC_REQUEST);
        if (v.isEmpty())
            return "";
        if ("Other".equals(v))
            return lowerFirst(s.text(IwiState.EVAC_OTHER));
        return v.toLowerCase(Locale.US);
    }

    static boolean needsWeight(Source s) {
        final String v = s.text(IwiState.EVAC_REQUEST);
        return "Short-Haul".equals(v) || "Hoist".equals(v);
    }

    private static String care(Source s) {
        final String role = s.text(IwiState.CARE_ROLE);
        final String name = s.text(IwiState.CARE_NAME);
        if (name.isEmpty())
            return "";
        return role.isEmpty() ? name : role + " " + name;
    }

    private static String commsRow(Source s, String row) {
        final StringBuilder b = new StringBuilder();
        final String channel = s.text(IwiState.commsKey(row, "channel"));
        if (!channel.isEmpty())
            b.append(channel);
        freq(b, "receive", s.text(IwiState.commsKey(row, "rx")),
                s.text(IwiState.commsKey(row, "rx_tone")));
        freq(b, "transmit", s.text(IwiState.commsKey(row, "tx")),
                s.text(IwiState.commsKey(row, "tx_tone")));
        return b.toString();
    }

    private static void freq(StringBuilder b, String word, String f, String tone) {
        if (f.isEmpty() && tone.isEmpty())
            return;
        if (b.length() > 0)
            b.append(", ");
        b.append(word);
        if (!f.isEmpty())
            b.append(' ').append(f);
        if (!tone.isEmpty())
            b.append(" tone ").append(tone);
    }

    /** Text as a sentence: ends with a full stop unless it already ends a sentence. */
    private static String sentence(String v) {
        if (v.isEmpty())
            return v;
        final char last = v.charAt(v.length() - 1);
        return last == '.' || last == '!' || last == '?' ? v : v + ".";
    }

    /** "Unconscious" reads "unconscious" mid-sentence; "AED" stays "AED". */
    static String lowerFirst(String v) {
        if (v.length() < 2 || !Character.isUpperCase(v.charAt(0))
                || Character.isUpperCase(v.charAt(1)))
            return v;
        return Character.toLowerCase(v.charAt(0)) + v.substring(1);
    }

    private static final String[] WORDS = {
            "zero", "one", "two", "three", "four", "five", "six", "seven", "eight",
            "nine", "ten", "eleven", "twelve", "thirteen", "fourteen", "fifteen",
            "sixteen", "seventeen", "eighteen", "nineteen", "twenty"
    };

    static String words(int n) {
        return n >= 0 && n < WORDS.length ? WORDS[n] : Integer.toString(n);
    }
}
