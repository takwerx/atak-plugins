package com.atakmap.android.atmosphere.data;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The Area Forecast Discussion: the forecaster's own reasoning, in sections that
 * begin ".SYNOPSIS..." and end "&&". Which sections an office writes is its own
 * choice: San Diego has SYNOPSIS, DISCUSSION, AVIATION, MARINE, BEACHES; Los
 * Angeles SHORT TERM and LONG TERM with a time stamp on each heading; Hanford KEY
 * MESSAGES and CERTAINTY. Nothing here is parsed beyond the headings; the words
 * are the forecaster's and are shown as written, with wrapped lines joined.
 *
 * <p>No Android types.
 */
public final class Afd {

    public static final class Section {
        /** "Synopsis", "Short Term (Sun-Wed)". */
        public final String name;
        /** The office's stamp on the heading, "11/345 PM", or "". */
        public final String stamp;
        public final String text;

        Section(String name, String stamp, String text) {
            this.name = name;
            this.stamp = stamp;
            this.text = text;
        }
    }

    public static final class Discussion {
        public String office = "";
        public String issued = "";
        public final List<Section> sections = new ArrayList<>();

        public Section synopsis() {
            for (Section s : sections)
                if (s.name.toUpperCase(Locale.US).startsWith("SYNOPSIS")
                        || s.name.toUpperCase(Locale.US).startsWith("KEY MESSAGES"))
                    return s;
            return sections.isEmpty() ? null : sections.get(0);
        }
    }

    public static Discussion parse(String text) {
        final Discussion d = new Discussion();
        if (text == null)
            return d;
        final String[] lines = text.split("\\r?\\n");
        String name = null, stamp = "";
        final StringBuilder body = new StringBuilder();
        for (int i = 0; i < lines.length; i++) {
            final String line = lines[i];
            final String t = line.trim();
            if (d.office.isEmpty() && t.toUpperCase(Locale.US).startsWith("NATIONAL WEATHER SERVICE ")) {
                d.office = t;
                if (i + 1 < lines.length)
                    d.issued = lines[i + 1].trim();
                continue;
            }
            if (t.startsWith(".") && t.contains("...") && !t.startsWith("...")) {
                if (name != null)
                    d.sections.add(new Section(name, stamp, paragraphs(body.toString())));
                body.setLength(0);
                final String head = t.substring(1);
                final int dots = head.indexOf("...");
                name = title(head.substring(0, dots));
                stamp = head.substring(dots + 3).trim();
                if (stamp.endsWith("."))
                    stamp = stamp.substring(0, stamp.length() - 1);
                // "...FOR EXTREME SOUTHWESTERN CALIFORNIA..." is part of the heading, not a stamp.
                if (stamp.length() > 20)
                    stamp = "";
                continue;
            }
            if (t.equals("&&") || t.equals("$$")) {
                if (name != null)
                    d.sections.add(new Section(name, stamp, paragraphs(body.toString())));
                name = null;
                stamp = "";
                body.setLength(0);
                continue;
            }
            if (name != null)
                body.append(line).append('\n');
        }
        if (name != null)
            d.sections.add(new Section(name, stamp, paragraphs(body.toString())));
        return d;
    }

    /** Wrapped lines joined into paragraphs; a blank line keeps a paragraph break. */
    static String paragraphs(String raw) {
        final StringBuilder out = new StringBuilder();
        final StringBuilder para = new StringBuilder();
        for (String line : raw.split("\n")) {
            final String t = line.trim();
            if (t.isEmpty()) {
                if (para.length() > 0) {
                    out.append(out.length() > 0 ? "\n\n" : "").append(para);
                    para.setLength(0);
                }
                continue;
            }
            // A sub-heading inside a section stands on its own line.
            if (t.startsWith("...") && t.endsWith("...")) {
                if (para.length() > 0) {
                    out.append(out.length() > 0 ? "\n\n" : "").append(para);
                    para.setLength(0);
                }
                out.append(out.length() > 0 ? "\n\n" : "").append(title(t.replace("...", "")));
                continue;
            }
            para.append(para.length() > 0 ? " " : "").append(t);
        }
        if (para.length() > 0)
            out.append(out.length() > 0 ? "\n\n" : "").append(para);
        return out.toString();
    }

    /** "SHORT TERM (SUN-WED)" as "Short Term (Sun-Wed)"; already mixed case is left alone. */
    static String title(String s) {
        final String t = s.trim();
        if (!t.equals(t.toUpperCase(Locale.US)))
            return t;
        final StringBuilder b = new StringBuilder();
        boolean start = true;
        for (char c : t.toCharArray()) {
            b.append(start ? Character.toUpperCase(c) : Character.toLowerCase(c));
            start = !Character.isLetterOrDigit(c);
        }
        return b.toString();
    }

    private Afd() {
    }
}
