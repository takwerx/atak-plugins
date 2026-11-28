package com.atakmap.android.sitrep;

import com.atakmap.android.sitrep.section.Section;
import com.atakmap.android.sitrep.section.Sections;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;
import java.util.regex.Pattern;

/**
 * What a shared report says, in order, before any page is drawn (takwerx/sit-rep#1,
 * ops#786): the header, then every section the report has, in report order, with
 * the head, lines and lists the report page shows, then its source line.
 *
 * <p>Built only from the saved report, so it works with no network. Two things never
 * go in, whatever the report holds (ops#786 plan): a shelter's contact names and
 * phone numbers, and any row marked CUI. Plain Java with no ATAK or Android class,
 * so the tests run on the desk; what only ATAK knows (MGRS, callsign, units) comes
 * in as text in {@link Facts}.
 */
public final class ReportDoc {

    /** Most rows a list shows, as on the report page. */
    static final int MAX_ROWS = 40;

    /** Place categories whose rows lose contact names and phone numbers. */
    static final String[] SHELTER_KINDS = { "shelter", "cooling", "warming", "animal" };

    private static final Pattern CUI = Pattern.compile(
            "(?i)(\\bCUI\\b|controlled unclassified)");
    private static final Pattern PHONE = Pattern.compile(
            "(\\+?1[-. ]?)?\\(?\\b\\d{3}\\)?[-. ]?\\d{3}[-. ]\\d{4}\\b");
    /** A shelter detail about a person to call: dropped whole. */
    private static final Pattern CONTACT = Pattern.compile(
            "(?i).*\\b(contact|poc|manager|coordinator|director|phone|tel|telephone|cell|e-?mail|@)\\b.*");

    /** One piece of the document. */
    public enum Kind {
        TITLE, META, SECTION, GROUP, LINE, NOTE, TABLE
    }

    public static final class Block {
        public final Kind kind;
        public final String text;
        /** LINE: 0 or 1 (a line inside a group). */
        public final int indent;
        /** TABLE: the head row and the body rows; each row has one cell per column. */
        public final String[] header;
        public final List<String[]> rows;
        /** TABLE: each column's share of the width, adding up to 1. */
        public final float[] widths;

        Block(Kind kind, String text, int indent) {
            this.kind = kind;
            this.text = text;
            this.indent = indent;
            this.header = null;
            this.rows = null;
            this.widths = null;
        }

        Block(String[] header, float[] widths, List<String[]> rows) {
            this.kind = Kind.TABLE;
            this.text = null;
            this.indent = 0;
            this.header = header;
            this.rows = rows;
            this.widths = widths;
        }
    }

    /** What the device knows that the report does not, all as finished text. */
    public static final class Facts {
        /** The time the PDF is made, epoch ms. */
        public long now;
        /** {@code 33.57631, -117.24060}. */
        public String latLon;
        /** {@code 11S MT 77670 15200}, or null. */
        public String mgrs;
        /** {@code 12.4 sq mi}. */
        public String size;
        /** {@code 4.2 mi}: center to the farthest point of the outline. */
        public String radius;
        /** {@code 0.9}. */
        public String version;
        /** The ATAK callsign of the device that made the PDF. */
        public String callsign;
        /** Null for the device's own zone. */
        public TimeZone zone;
    }

    public final List<Block> blocks = new ArrayList<>();
    /** The section heads in order, for the tests. */
    public final List<String> sections = new ArrayList<>();
    /** The snapshot line on every page's footer. */
    public final String snapshot;
    /** Rows left out because they were marked CUI, and shelter contact details removed. */
    public int cuiLeftOut, contactsLeftOut;

    private final Facts facts;

    private ReportDoc(Facts f) {
        this.facts = f;
        this.snapshot = "Snapshot at " + stamp(f.now, f.zone) + ". Data may be out of date.";
    }

    /** The footer's second line, the same on every page. */
    public static final String SOURCES = "Data from public agency sources; each section names its own. "
            + "Read-only report made by the Sit Rep ATAK plugin.";

    /** Page footer: {@code Page 2 of 7}. */
    public static String pageLabel(int page, int pages) {
        return "Page " + page + " of " + pages;
    }

    public static ReportDoc build(Report r, Facts f) {
        final ReportDoc d = new ReportDoc(f);
        d.header(r);
        for (Section s : Sections.all()) {
            final Report.Answer a = r.answer(s.key);
            if (a != null)
                d.section(r, s, a);
        }
        if (d.sections.isEmpty())
            d.add(Kind.NOTE, "This report has no sections.", 0);
        return d;
    }

    // ------------------------------------------------------------------ header

    private void header(Report r) {
        add(Kind.TITLE, "Sit Rep", 0);
        add(Kind.META, "Area: " + r.name(), 0);
        if (r.place != null && r.label != null)
            add(Kind.META, "Place: " + r.place, 0);
        add(Kind.META, "Center: " + facts.latLon + (facts.mgrs != null ? "  (MGRS " + facts.mgrs + ")" : ""), 0);
        add(Kind.META, "Size: " + facts.size + ", radius " + facts.radius
                + " (center to the farthest point of the area)", 0);
        add(Kind.META, "Report made: " + stamp(r.made, facts.zone), 0);
        add(Kind.META, "PDF made: " + stamp(facts.now, facts.zone) + " by "
                + (facts.callsign == null || facts.callsign.trim().isEmpty() ? "an unnamed device" : facts.callsign.trim())
                + ", Sit Rep " + facts.version, 0);
        add(Kind.NOTE, "A snapshot of what this report showed when the PDF was made. "
                + "Live sections give their age; nothing here updates.", 0);
    }

    // ------------------------------------------------------------------ sections

    private void section(Report r, Section s, Report.Answer a) {
        final String head = head(s, a);
        sections.add(head);
        add(Kind.SECTION, head, 0);
        switch (a.state) {
            case LOADING:
                add(Kind.NOTE, "Still loading when this PDF was made; nothing to show.", 0);
                source(s, a);
                return;
            case OFFLINE:
                add(Kind.NOTE, "Not available: this device had no network when the report asked.", 0);
                source(s, a);
                return;
            case FAILED:
                add(Kind.NOTE, "Not available: the source did not answer when the report asked.", 0);
                source(s, a);
                return;
            default:
                break;
        }
        final int before = blocks.size();
        if ("places".equals(s.key) && a.points != null) {
            places(r, a.points);
            lines(s.key, notesOnly(a.lines));
        } else if ("calls".equals(s.key) && a.points != null) {
            calls(a.points);
            lines(s.key, notesOnly(a.lines));
        } else if ("history".equals(s.key) && a.points != null) {
            history(a);
        } else {
            lines(s.key, a.lines);
        }
        if (blocks.size() == before && (a.value == null || a.value.trim().isEmpty()))
            add(Kind.NOTE, "Nothing found for this area.", 0);
        if (a.state == Report.State.OK && a.lastTryFailed)
            add(Kind.NOTE, (a.lastTryOffline ? "No network" : "The last Refresh did not answer")
                    + ", showing what the device saved " + ago(a.at) + ".", 0);
        source(s, a);
    }

    /** The section head, as the report page writes it. */
    private String head(Section s, Report.Answer a) {
        final String name = s.name + ": ";
        switch (a.state) {
            case LOADING:
                return name + "Loading...";
            case OFFLINE:
                return name + "Not available: no network";
            case FAILED:
                return name + "Not available";
            case NOT_HERE:
                return name + (a.value != null ? a.value : "Not published for this state");
            default:
                String v = (name + (a.value == null ? "" : clean(a.value))).trim();
                if (s.live || a.lastTryFailed)
                    v += " (" + ago(a.at) + ")";
                return v;
        }
    }

    private void source(Section s, Report.Answer a) {
        add(Kind.NOTE, "Source: " + s.source + (a.at > 0 ? " Fetched " + stamp(a.at, facts.zone) + "." : ""), 0);
    }

    /** Lines as the report page shows them: group lines, their indented body, plain lines. */
    private void lines(String key, List<String> in) {
        boolean inGroup = false;
        for (String raw : in) {
            if (cui(raw))
                continue;
            if (raw.startsWith(Section.GROUP)) {
                add(Kind.GROUP, clean(raw.substring(Section.GROUP.length())), 0);
                inGroup = true;
            } else if (inGroup && raw.startsWith("  ")) {
                add(Kind.LINE, clean(raw), 1);
            } else {
                inGroup = false;
                add(Kind.LINE, clean(raw), 0);
            }
        }
    }

    /** A section's notes: the lines that are not a list (California only, a source down). */
    private static List<String> notesOnly(List<String> in) {
        final List<String> out = new ArrayList<>();
        for (String line : in)
            if (!line.startsWith(Section.GROUP) && !line.startsWith("  "))
                out.add(line);
        return out;
    }

    /** One table per place category, in the switches' order, each in the report page's order. */
    private void places(Report r, JSONArray points) {
        final Map<String, List<JSONObject>> byCat = new LinkedHashMap<>();
        for (int i = 0; i < points.length(); i++) {
            final JSONObject p = points.optJSONObject(i);
            if (p == null)
                continue;
            List<JSONObject> l = byCat.get(p.optString("c"));
            if (l == null) {
                l = new ArrayList<>();
                byCat.put(p.optString("c"), l);
            }
            l.add(p);
        }
        for (String[] c : com.atakmap.android.sitrep.section.Places.CATEGORIES) {
            final List<JSONObject> l = byCat.get(c[0]);
            if (l == null)
                continue;
            final boolean shelter = isShelter(c[0]);
            final List<String[]> rows = new ArrayList<>();
            int listed = 0, total = 0;
            for (JSONObject p : PlaceOrder.sorted(l, r)) {
                if (cui(p))
                    continue;
                total++;
                if (++listed > MAX_ROWS)
                    continue;
                final JSONArray info = p.optJSONArray("i");
                String where = "";
                final List<String> details = new ArrayList<>();
                for (int k = 0; info != null && k < info.length(); k++) {
                    final String t = info.optString(k, "");
                    if (k == info.length() - 1 && t.endsWith("of the area."))
                        where = t.replace(" of the area.", "");
                    else if (!t.isEmpty())
                        details.add(t);
                }
                String name = clean(p.optString("n"));
                if (shelter) {
                    name = noPhone(name);
                    for (int k = details.size() - 1; k >= 0; k--) {
                        final String t = details.get(k);
                        // Shelter details never need a phone number: a line with one,
                        // or about a person to call, goes whole.
                        if (CONTACT.matcher(t).matches() || PHONE.matcher(t).find()) {
                            details.remove(k);
                            contactsLeftOut++;
                        }
                    }
                }
                rows.add(new String[] { name, where.isEmpty() ? "In the area" : where, clean(join(details)) });
            }
            if (total == 0)
                continue;
            add(Kind.GROUP, c[1] + " (" + total + ")", 0);
            blocks.add(new Block(new String[] { "Name", "Where", "Details" }, new float[] { 0.34f, 0.18f, 0.48f }, rows));
            if (total > MAX_ROWS)
                add(Kind.NOTE, "... and " + (total - MAX_ROWS) + " more, not listed here.", 0);
        }
    }

    /** The 911 calls: one table per agency, newest first, as the report page lists them. */
    private void calls(JSONArray points) {
        final Map<String, List<JSONObject>> byAgency = new LinkedHashMap<>();
        for (int i = 0; i < points.length(); i++) {
            final JSONObject p = points.optJSONObject(i);
            if (p == null || cui(p))
                continue;
            List<JSONObject> l = byAgency.get(p.optString("ag", "Calls"));
            if (l == null) {
                l = new ArrayList<>();
                byAgency.put(p.optString("ag", "Calls"), l);
            }
            l.add(p);
        }
        for (Map.Entry<String, List<JSONObject>> e : byAgency.entrySet()) {
            final List<JSONObject> l = e.getValue();
            Collections.sort(l, new Comparator<JSONObject>() {
                @Override
                public int compare(JSONObject a, JSONObject b) {
                    return Long.compare(b.optLong("t", 0), a.optLong("t", 0));
                }
            });
            add(Kind.GROUP, clean(e.getKey()) + " (" + l.size() + ")", 0);
            final List<String[]> rows = new ArrayList<>();
            for (int k = 0; k < l.size() && k < MAX_ROWS; k++) {
                final JSONObject p = l.get(k);
                final JSONArray info = p.optJSONArray("i");
                final long t = p.optLong("t", 0);
                rows.add(new String[] {
                        t > 0 ? clock(t, facts.zone) + " (" + ago(t) + ")" : "Time not known",
                        clean(p.optString("n")),
                        info != null && info.length() > 2 ? clean(info.optString(2)) : "" });
            }
            blocks.add(new Block(new String[] { "Received", "Call", "Where" }, new float[] { 0.24f, 0.38f, 0.38f }, rows));
            if (l.size() > MAX_ROWS)
                add(Kind.NOTE, "... and " + (l.size() - MAX_ROWS) + " more, not listed here.", 0);
        }
    }

    /** History: its lines, with the fatality and entrapment reviews listed from its places. */
    private void history(Report.Answer a) {
        final List<String> kept = new ArrayList<>();
        boolean inLessons = false;
        int lessonsAt = -1;
        for (String line : a.lines) {
            if (line.startsWith(Section.GROUP)) {
                inLessons = line.startsWith(Section.GROUP + "Fatalities");
                if (inLessons)
                    lessonsAt = kept.size();
            } else if (!line.startsWith("  "))
                inLessons = false;
            else if (inLessons)
                continue;
            kept.add(line);
        }
        if (lessonsAt >= 0) {
            final List<String> rows = new ArrayList<>();
            for (int i = 0; i < a.points.length(); i++) {
                final JSONObject p = a.points.optJSONObject(i);
                if (p == null || !"lessons".equals(p.optString("c")))
                    continue;
                final JSONArray info = p.optJSONArray("i");
                rows.add("  " + p.optString("n")
                        + (info != null && info.length() > 0 ? ", " + info.optString(0) : ""));
            }
            kept.addAll(lessonsAt + 1, rows);
        }
        lines("history", kept);
    }

    // ------------------------------------------------------------------ rules

    static boolean isShelter(String category) {
        for (String k : SHELTER_KINDS)
            if (k.equals(category))
                return true;
        return false;
    }

    private boolean cui(String line) {
        if (line != null && CUI.matcher(line).find()) {
            cuiLeftOut++;
            return true;
        }
        return false;
    }

    /** A place or call marked CUI in its name, its details or a {@code cui} flag. */
    private boolean cui(JSONObject p) {
        boolean marked = p.optBoolean("cui", false) || CUI.matcher(p.optString("n", "")).find();
        final JSONArray info = p.optJSONArray("i");
        for (int k = 0; !marked && info != null && k < info.length(); k++)
            marked = CUI.matcher(info.optString(k, "")).find();
        if (marked)
            cuiLeftOut++;
        return marked;
    }

    private static String noPhone(String s) {
        return PHONE.matcher(s).replaceAll("").replaceAll("\\s{2,}", " ")
                .replaceAll("\\s+([.,;])", "$1").replaceAll("^[\\s,;:.-]+|[\\s,;:-]+$", "").trim();
    }

    // ------------------------------------------------------------------ text

    private void add(Kind k, String text, int indent) {
        blocks.add(new Block(k, text, indent));
    }

    /**
     * A line as the page shows it: the web page behind a link dropped (the text
     * stays), control characters gone, spaces folded.
     */
    static String clean(String s) {
        if (s == null)
            return "";
        final int k = s.indexOf(Section.LINK);
        if (k >= 0)
            s = s.substring(0, k);
        return s.replaceAll("[\\p{Cntrl}\\u200E\\u200F]", " ").replaceAll("\\s+", " ").trim();
    }

    private static String join(List<String> parts) {
        final StringBuilder b = new StringBuilder();
        for (String p : parts) {
            if (p.trim().isEmpty())
                continue;
            if (b.length() > 0)
                b.append(' ');
            b.append(p.trim());
        }
        return b.toString();
    }

    /** {@code 12 min ago}, counted from the moment the PDF is made, as the page counts from now. */
    private String ago(long ms) {
        if (ms <= 0)
            return "time not known";
        final long min = Math.max(0, (facts.now - ms) / 60_000L);
        if (min < 1)
            return "just now";
        if (min < 60)
            return min + " min ago";
        if (min < 48 * 60)
            return (min / 60) + " h ago";
        return (min / (24 * 60)) + " days ago";
    }

    /** {@code Nov 28, 2026 14:05 PST}. */
    static String stamp(long ms, TimeZone zone) {
        final SimpleDateFormat f = new SimpleDateFormat("MMM d, yyyy HH:mm z", Locale.US);
        if (zone != null)
            f.setTimeZone(zone);
        return f.format(new Date(ms));
    }

    private static String clock(long ms, TimeZone zone) {
        final SimpleDateFormat f = new SimpleDateFormat("MMM d HH:mm", Locale.US);
        if (zone != null)
            f.setTimeZone(zone);
        return f.format(new Date(ms));
    }

    /**
     * {@code SitRep_Riverside_Co_20261128-1405.pdf}: letters, digits, dash and
     * underscore only, the area at most 40 characters, the time in the given zone.
     */
    /** The farthest an outline point is from the center, in meters (great circle). */
    public static double radiusMeters(double[][] ring, double lat, double lon) {
        double max = 0;
        for (double[] p : ring) {
            final double dLat = Math.toRadians(p[0] - lat), dLon = Math.toRadians(p[1] - lon);
            final double h = Math.sin(dLat / 2) * Math.sin(dLat / 2) + Math.cos(Math.toRadians(lat))
                    * Math.cos(Math.toRadians(p[0])) * Math.sin(dLon / 2) * Math.sin(dLon / 2);
            max = Math.max(max, 2 * 6_371_008.8 * Math.asin(Math.min(1, Math.sqrt(h))));
        }
        return max;
    }

    private static final String SAFE = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";

    public static String fileName(String area, long made, TimeZone zone) {
        // Each kept character is read back out of a constant alphabet, so nothing
        // the report holds reaches a file name except letters and digits.
        final StringBuilder b = new StringBuilder();
        boolean gap = false;
        for (int i = 0; area != null && i < area.length() && b.length() < 40; i++) {
            final int k = SAFE.indexOf(area.charAt(i));
            if (k < 0) {
                gap = b.length() > 0;
                continue;
            }
            if (gap && b.length() < 39)
                b.append('_');
            gap = false;
            b.append(SAFE.charAt(k));
        }
        final String a = b.length() == 0 ? "Area" : b.toString();
        final SimpleDateFormat f = new SimpleDateFormat("yyyyMMdd-HHmm", Locale.US);
        if (zone != null)
            f.setTimeZone(zone);
        return "SitRep_" + a + "_" + f.format(new Date(made)) + ".pdf";
    }
}
