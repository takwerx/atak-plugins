package com.atakmap.android.iwi.plugin;

import java.util.ArrayList;
import java.util.List;

/**
 * The Medical Incident Report as it is said on the radio, one numbered section at a
 * time.
 *
 * <p>NWCG 6MFS: "When transmitting the report, state the number and title of each
 * section, and say 'break' and pause between the sections." The workflow (PLAN-IWI,
 * "The workflow") is an initial report with the basics, items 1, 2 and 5, all picked
 * rather than typed, then follow-ups as more is known: item 3 for each patient, most
 * severe first, and 4, 6, 7 and 8 once they say something.
 *
 * <p>What has not been picked yet is a part marked missing, shown where it would be
 * said. The report page colors a section's row green exactly when its section here
 * has nothing missing, so the two cannot disagree. Optional things (an incident name,
 * a landmark, a named IC) are said when they are there and never marked missing.
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

        /** This phone's callsign: the reporter, and the IC unless one is named. */
        String callsign();
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

    /** Something said before a section's main words, and what to do after saying it. */
    public static final class Lead {
        public final String said;
        public final String then;

        Lead(String said, String then) {
            this.said = said;
            this.then = then;
        }
    }

    public static final class Section {
        public final int number;
        public final String title;
        /** Said first, each followed by a pause or a wait: item 5's call back. */
        public final List<Lead> leads = new ArrayList<>();
        public final List<Part> parts = new ArrayList<>();
        /**
         * "Break" is a word that is said, and it means "I am going to keep talking"
         * (operator, 2026-10-14). So it ends a section's words, in the same quote, only
         * when more follows after a pause; a section that ends the transmission says no
         * break and waits.
         */
        public boolean saysBreak = true;
        /** What to do after saying it: pause, or wait for an answer. Never said. */
        public String after = "Pause.";

        /** The transmission stops here: no break, and wait for this. */
        void endsWith(String wait) {
            saysBreak = false;
            after = wait;
        }
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
        // No break after the incident status (operator, 2026-10-14): the reporter stops
        // and Communications acknowledges before the resources are called.
        final Section two = status(s, coords);
        two.endsWith(acknowledge(s));
        out.add(two);
        final Section five = resources(s);
        five.endsWith(acknowledge(s));
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
            out.get(out.size() - 1).endsWith(acknowledge(s));
        return out;
    }

    /**
     * How a follow-up call opens: "Communications, BILLO, update on the Trout Meadow
     * Medical." Whatever is not known is left out rather than shown.
     */
    public static String updateLead(Source s) {
        final StringBuilder b = new StringBuilder(calling(s));
        final String from = s.callsign();
        if (!from.isEmpty())
            b.append(", ").append(from);
        b.append(", update");
        final String incident = s.text(IwiState.INCIDENT);
        if (!incident.isEmpty())
            b.append(" on the ").append(incident);
        return b.append('.').toString();
    }

    // ---- the sections ----

    /**
     * 1. The IRPG's example: "Communications, Div. Alpha. Stand-by for Emergency Traffic."
     * No break after it: the reporter waits for the air to be cleared.
     */
    public static Section contact(Source s) {
        final String to = calling(s);
        final Section one = new Section(1, "Contact Communications / Dispatch")
                .say(to + ", ")
                .field(s.callsign(), "your callsign: set it in ATAK")
                .say(". Stand-by for Emergency Traffic.");
        // No break: the reporter stops, and Communications clears the air and comes
        // back (operator, 2026-10-14).
        one.endsWith("Wait for " + to + " to clear the air and come back to you.");
        return one;
    }

    /** The end of a transmission: what the reporter waits for. */
    public static String acknowledge(Source s) {
        return "Wait for " + calling(s) + " to acknowledge.";
    }

    /** After a follow-up's opening call. */
    public static String answer(Source s) {
        return "Wait for " + calling(s) + " to answer.";
    }

    /** Communications unless Dispatch was picked. */
    static String calling(Source s) {
        final String to = s.text(IwiState.CONTACT_TO);
        return Vocab.CONTACT.index(to) >= 0 ? to : "Communications";
    }

    /**
     * 2. "Communications, I have a Red priority patient, unconscious, struck by a falling
     * tree. Requesting air ambulance to Forest Road 1 at (Lat. / Long.). This will be the
     * Trout Meadow Medical, IC is TFLD Jones. EMT Smith is providing medical care."
     *
     * <p>What gets the ball rolling is required: how many and how bad, what happened,
     * the evacuation request, where, and the medical training on scene. The incident
     * name, a landmark and a named IC are said when given; the IC defaults to the
     * reporter.
     *
     * @param coords the patient's location in DDM, or null when no IWI is declared
     */
    public static Section status(Source s, String coords) {
        final Section two = new Section(2, "Incident Status").say("I have ")
                .field(triage(s), "how many patients, and how bad").say(", ")
                .field(Vocab.said(s.text(IwiState.NATURE), Vocab.MEDICAL, Vocab.TRAUMA),
                        "what happened: pick from the list")
                .say(". Requesting ")
                .field(evac(s), "evacuation request").say(" to ");
        final String landmark = s.text(IwiState.LOCATION);
        if (!landmark.isEmpty())
            two.say(landmark + " at ");
        two.field(coords, "patient location: declare the IWI").say(". ");
        final String incident = s.text(IwiState.INCIDENT);
        if (!incident.isEmpty())
            two.say("This will be the " + incident + ", IC");
        else
            two.say("IC");
        two.say(" is " + ic(s) + ". ")
                .field(care(s), "medical on scene: None, EMT or Paramedic").say(".");
        if (needsWeight(s)) {
            final String contact = s.text(IwiState.RADIO_CONTACT);
            two.say(" On-scene radio contact " + (contact.isEmpty() ? s.callsign() : contact)
                    + ".");
        }
        return two;
    }

    /** The named IC, or the reporter when none is named. */
    static String ic(Source s) {
        final String named = s.text(IwiState.IC);
        if (!named.isEmpty())
            return named;
        final String me = s.callsign();
        return me.isEmpty() ? "on scene" : me;
    }

    /**
     * 3, for one patient, IRPG p. 108 in its order: "Patient 3, Red priority. Responds
     * to voice, labored breathing, pulse present, bleeding controlled. Skin pale, cool.
     * Treatment: tourniquet and treated for shock."
     */
    public static Section patient(Source s, Patient p) {
        final Section three = new Section(3, "Initial Patient Assessment")
                .say(p.label() + ", " + Patient.name(p.color)
                        + (IwiState.BLACK.equals(p.color) ? ". " : " priority. "))
                .field(capital(Vocab.LOC.spoken(p.get(Patient.LOC))), "consciousness")
                .say(", ")
                .field(Vocab.BREATHING.spoken(p.get(Patient.BREATHING)), "breathing")
                .say(", ")
                .field(Vocab.PULSE.spoken(p.get(Patient.PULSE)), "pulse").say(", ")
                .field(Vocab.BLEEDING.spoken(p.get(Patient.BLEEDING)), "bleeding").say(".");
        final String skin = skin(p);
        if (!skin.isEmpty())
            three.say(" " + skin);
        final String pupils = pupils(p);
        if (!pupils.isEmpty())
            three.say(" " + pupils);
        three.say(" Treatment: ")
                .field(Vocab.saidAnd(p.get(Patient.TREATMENT), Vocab.TREATMENT),
                        "treatment, or none yet")
                .say(".");
        if (needsWeight(s)) {
            final String w = p.get(Patient.WEIGHT);
            three.say(" Patient weight ")
                    .field(w.isEmpty() ? "" : "about " + w + " pounds", "patient weight")
                    .say(".");
        }
        return three;
    }

    /** "Skin pale, moist and clammy, cool." or "Skin normal." or "" when not looked at. */
    static String skin(Patient p) {
        final String[] fields = {Patient.SKIN_COLOR, Patient.SKIN_MOIST, Patient.SKIN_TEMP};
        final List<String> words = new ArrayList<>();
        boolean any = false;
        for (String f : fields) {
            final String code = p.get(f);
            if (code.isEmpty())
                continue;
            any = true;
            final String w = Patient.choices(f).spoken(code);
            if (!w.isEmpty())
                words.add(w);
        }
        if (!any)
            return "";
        if (words.isEmpty())
            return "Skin normal.";
        final StringBuilder b = new StringBuilder("Skin ");
        for (int i = 0; i < words.size(); i++)
            b.append(i > 0 ? ", " : "").append(words.get(i));
        return b.append('.').toString();
    }

    /** "Pupils unequal, slow to react." or "" when not looked at. */
    static String pupils(Patient p) {
        final String eq = Vocab.PUPILS.spoken(p.get(Patient.PUPILS));
        final String react = Vocab.REACTION.spoken(p.get(Patient.REACTION));
        if (eq.isEmpty() && react.isEmpty())
            return "";
        if (eq.isEmpty() || react.isEmpty())
            return "Pupils " + eq + react + ".";
        return "Pupils " + eq + ", " + react + ".";
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

    /** 5, picked: "Requesting a paramedic or EMT, a wheeled litter and oxygen." */
    /**
     * 5, picked. None needed is one line: "No additional resources needed. Break." A
     * request is called in its own exchange (operator, 2026-10-14): "Stand by for
     * additional resources. Break." then, after a pause, "Communications, BILLO, are you
     * ready to copy resource request?" and, once they answer, the request.
     */
    public static Section resources(Source s) {
        final Section five = new Section(5, "Additional Resources / Equipment Needs");
        final String stored = s.text(IwiState.RESOURCES);
        if (Vocab.parse(stored, Vocab.RESOURCES).contains("none"))
            return five.field("No additional resources needed.", "");
        final String said = Vocab.saidAnd(stored, Vocab.RESOURCES);
        if (!said.isEmpty()) {
            five.leads.add(new Lead("Stand by for additional resources. Break.", "Pause."));
            final String me = s.callsign();
            five.leads.add(new Lead(calling(s) + (me.isEmpty() ? "" : ", " + me)
                    + ", are you ready to copy resource request?", answer(s)));
        }
        return five.field(said.isEmpty() ? "" : "Requesting " + said + ".",
                "resources needed, or None needed");
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
     * their numbers, the order the radio script says them, and nothing not filled in.
     */
    public static String remarks(Source s, String coords) {
        final StringBuilder b = new StringBuilder();
        line(b, "2. Patients", triage(s));
        line(b, "Nature", Vocab.said(s.text(IwiState.NATURE), Vocab.MEDICAL, Vocab.TRAUMA));
        line(b, "Evacuation request", evac(s));
        line(b, "Location", join(s.text(IwiState.LOCATION), coords));
        line(b, "Incident", s.text(IwiState.INCIDENT));
        line(b, "On-scene IC", ic(s));
        line(b, "Medical on scene", Vocab.CARE.label(s.text(IwiState.CARE_ROLE)));
        for (Patient p : bySeverity(s.patients())) {
            if (!p.hasNotes())
                continue;
            line(b, "3. " + p.label() + " " + Patient.name(p.color), said(patient(s, p)));
        }
        line(b, "4. Evacuation location", s.text(IwiState.EVAC_LOCATION));
        line(b, "Patient ETA", s.text(IwiState.ETA));
        line(b, "Helispot", s.text(IwiState.HELISPOT));
        final Section five = resources(s);
        if (five.complete())
            line(b, "5. Resources", said(five));
        for (int i = 0; i < IwiState.COMMS_ROWS.length; i++)
            line(b, (i == 0 ? "6. " : "") + IwiState.COMMS_ROW_NAMES[i],
                    commsRow(s, IwiState.COMMS_ROWS[i]));
        line(b, "7. Contingency", s.text(IwiState.CONTINGENCY));
        line(b, "8. Additional", s.text(IwiState.ADDITIONAL));
        return b.toString().trim();
    }

    /** A section's words without its gaps. */
    static String said(Section x) {
        final StringBuilder b = new StringBuilder();
        for (Part p : x.parts)
            if (!p.missing)
                b.append(p.text);
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
        return Vocab.EVAC.spoken(s.text(IwiState.EVAC_REQUEST));
    }

    static boolean needsWeight(Source s) {
        final String v = s.text(IwiState.EVAC_REQUEST);
        return "Short-Haul".equals(v) || "Hoist".equals(v);
    }

    private static String care(Source s) {
        return Vocab.CARE.spoken(s.text(IwiState.CARE_ROLE));
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

    private static String capital(String v) {
        return v.isEmpty() ? v : Character.toUpperCase(v.charAt(0)) + v.substring(1);
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
