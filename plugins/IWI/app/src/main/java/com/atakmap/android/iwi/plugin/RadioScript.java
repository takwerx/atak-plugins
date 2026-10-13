package com.atakmap.android.iwi.plugin;

import java.util.ArrayList;
import java.util.List;

/**
 * The Medical Incident Report as it is said on the radio.
 *
 * <p>NWCG 6MFS: "When transmitting the report, state the number and title of each
 * section, and say 'break' and pause between the sections." So each section starts
 * with its number and title, the sentences follow the IRPG's own examples, and what
 * the reporter has not filled in is a part marked missing, shown where it would be
 * said, so they know what they are about to be asked for. It never blocks: half
 * filled and said beats complete and late.
 *
 * <p>No Android or ATAK types, so it runs against a plain map on a desktop JVM.
 */
public final class RadioScript {

    /** Where the report comes from. */
    public interface Source {
        /** The field, trimmed, or "" when empty. */
        String text(String key);

        int count(String key);
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
            return this;
        }

        /** The value, or a gap naming it. */
        Section field(String value, String what) {
            return value == null || value.isEmpty() ? gap(what) : say(value);
        }

        public boolean anyMissing() {
            for (Part p : parts)
                if (p.missing)
                    return true;
            return false;
        }
    }

    private RadioScript() {
    }

    /**
     * @param coords the patient's location in DDM, or null when no IWI is declared
     */
    public static List<Section> build(Source s, String coords) {
        final List<Section> out = new ArrayList<>();

        // 1. The IRPG's own example: "Communications, Div. Alpha. Stand-by for
        // Emergency Traffic."
        final String to = s.text(IwiState.CONTACT_TO);
        final Section one = new Section(1, "Contact Communications / Dispatch")
                .field(to, "who you are calling, Ex: Communications").say(", ")
                .field(s.text(IwiState.CONTACT_FROM), "your callsign, Ex: Div. Alpha")
                .say(". Stand-by for Emergency Traffic.");
        one.after = "BREAK. Wait for " + (to.isEmpty() ? "Communications" : to)
                + " to answer.";
        out.add(one);

        // 2. "Communications, I have a Red priority patient, unconscious, struck by a
        // falling tree. Requesting air ambulance to Forest Road 1 at (Lat. / Long.).
        // This will be the Trout Meadow Medical, IC is TFLD Jones. EMT Smith is
        // providing medical care."
        final Section two = new Section(2, "Incident Status").say("I have ");
        final String triage = triage(s);
        two.field(triage, "how many patients, and Red, Yellow or Green").say(", ")
                .field(lowerFirst(s.text(IwiState.NATURE)), "nature of injury or illness")
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
            two.say(" Patient weight ").field(s.text(IwiState.WEIGHT), "patient weight")
                    .say(". On-scene radio contact ")
                    .field(s.text(IwiState.RADIO_CONTACT), "on-scene radio contact")
                    .say(".");
        }
        out.add(two);

        out.add(new Section(3, "Initial Patient Assessment")
                .field(sentence(s.text(IwiState.ASSESSMENT)), "assessment")
                .say(" Treatment: ")
                .field(sentence(s.text(IwiState.TREATMENT)), "treatment"));

        out.add(new Section(4, "Evacuation Plan")
                .say("Evacuation location ")
                .field(s.text(IwiState.EVAC_LOCATION), "evacuation location, if different")
                .say(". Patient's ETA to the evacuation location ")
                .field(s.text(IwiState.ETA), "ETA").say(". Helispot ")
                .field(s.text(IwiState.HELISPOT), "helispot or extraction site size and hazards")
                .say("."));

        out.add(new Section(5, "Additional Resources / Equipment Needs")
                .field(sentence(s.text(IwiState.RESOURCES)), "resources and equipment needed"));

        final Section six = new Section(6, "Communications");
        boolean anyRow = false;
        for (int i = 0; i < IwiState.COMMS_ROWS.length; i++) {
            final String row = commsRow(s, IwiState.COMMS_ROWS[i]);
            if (row.isEmpty())
                continue;
            six.say((anyRow ? " " : "") + IwiState.COMMS_ROW_NAMES[i] + ": " + row + ".");
            anyRow = true;
        }
        if (!anyRow)
            six.gap("command, air-to-ground and tactical frequencies");
        out.add(six);

        out.add(new Section(7, "Contingency")
                .field(sentence(s.text(IwiState.CONTINGENCY)), "if the primary plan fails"));

        final Section eight = new Section(8, "Additional Information")
                .field(sentence(s.text(IwiState.ADDITIONAL)), "updates or changes, or say none");
        eight.after = null;
        out.add(eight);
        return out;
    }

    /**
     * The report as plain text for ATAK's med line remarks: the items in MIR order with
     * their numbers, the same order the radio script says them, and nothing the
     * reporter has not filled in.
     */
    public static String remarks(Source s, String coords) {
        final StringBuilder b = new StringBuilder();
        line(b, "2. Severity", triage(s));
        line(b, "Nature", s.text(IwiState.NATURE));
        line(b, "Evacuation request", evac(s));
        line(b, "Location", join(s.text(IwiState.LOCATION), coords));
        line(b, "Incident", s.text(IwiState.INCIDENT));
        line(b, "On-scene IC", s.text(IwiState.IC));
        line(b, "Patient care", care(s));
        if (needsWeight(s)) {
            line(b, "Patient weight", s.text(IwiState.WEIGHT));
            line(b, "On-scene radio contact", s.text(IwiState.RADIO_CONTACT));
        }
        line(b, "3. Assessment", s.text(IwiState.ASSESSMENT));
        line(b, "Treatment", s.text(IwiState.TREATMENT));
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

    /**
     * "a Red priority patient", or the NWCG multi-casualty phrasing: "five patients,
     * three Reds, one Yellow, and one Green". "" when no count is set.
     */
    public static String triage(Source s) {
        final int[] n = {
                s.count(IwiState.RED), s.count(IwiState.YELLOW),
                s.count(IwiState.GREEN), s.count(IwiState.BLACK)
        };
        final String[] name = {"Red", "Yellow", "Green", "Black"};
        int total = 0;
        for (int x : n)
            total += x;
        if (total == 0)
            return "";
        if (total == 1) {
            for (int i = 0; i < 3; i++)
                if (n[i] == 1)
                    return "a " + name[i] + " priority patient";
            return "one patient, Black";
        }
        final List<String> parts = new ArrayList<>();
        for (int i = 0; i < n.length; i++)
            if (n[i] > 0)
                parts.add(words(n[i]) + " " + name[i] + (n[i] > 1 ? "s" : ""));
        final StringBuilder b = new StringBuilder(words(total)).append(" patients, ");
        for (int i = 0; i < parts.size(); i++) {
            if (i > 0)
                b.append(i == parts.size() - 1 ? (parts.size() > 2 ? ", and " : " and ") : ", ");
            b.append(parts.get(i));
        }
        return b.toString();
    }

    private static String evac(Source s) {
        final String v = s.text(IwiState.EVAC_REQUEST);
        if (v.isEmpty())
            return "";
        if ("Other".equals(v))
            return lowerFirst(s.text(IwiState.EVAC_OTHER));
        return v.toLowerCase(java.util.Locale.US);
    }

    private static boolean needsWeight(Source s) {
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
