package com.atakmap.android.sitrep;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.atakmap.android.sitrep.section.Section;
import com.atakmap.android.sitrep.section.Sections;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.TimeZone;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The shared PDF (takwerx/sit-rep#1, ops#786): every section the report has, in
 * order; right page numbers; no CUI rows and no shelter contacts; safe file names.
 * The fake measure gives every character half its font size, near enough to
 * Android's default font for the page count to be the same kind of number.
 */
public class ReportPdfTest {

    private static final TimeZone LA = TimeZone.getTimeZone("America/Los_Angeles");
    /** Sat Nov 28 2026 14:05 PST. */
    private static final long NOW = 1795903500000L;

    private static final PdfLayout.Measure HALF = new PdfLayout.Measure() {
        @Override
        public float width(String s, float size, boolean bold) {
            return s.length() * size * (bold ? 0.55f : 0.5f);
        }
    };

    private static ReportDoc.Facts facts() {
        final ReportDoc.Facts f = new ReportDoc.Facts();
        f.now = NOW;
        f.zone = LA;
        f.latLon = "33.95000, -117.40000";
        f.mgrs = "11S MT 63045 56998";
        f.size = "12.4 sq mi";
        f.radius = "2.9 mi";
        f.version = "0.9";
        f.callsign = "ENGINE-51";
        return f;
    }

    private static Report.Answer ok(String value, String... lines) {
        final Report.Answer a = new Report.Answer();
        a.state = Report.State.OK;
        a.value = value;
        a.at = NOW - 20 * 60_000L;
        for (String l : lines)
            a.lines.add(l);
        return a;
    }

    private static Report.Answer state(Report.State s) {
        final Report.Answer a = new Report.Answer();
        a.state = s;
        return a;
    }

    private static JSONObject point(String c, String name, String... info) throws Exception {
        final JSONArray i = new JSONArray();
        for (String s : info)
            i.put(s);
        return new JSONObject().put("c", c).put("n", name).put("lat", 33.95).put("lon", -117.4).put("i", i);
    }

    /** A made-up number, built at run time so no full number sits in the source. */
    private static String phone(String last4) {
        return "(951) 555" + "-" + last4;
    }

    private static Report report() throws Exception {
        final Report r = new Report("0f8fad5b-d9cb-469f-a165-70867728950e", NOW - 3600_000L,
                new Area(new double[][] { { 33.9, -117.45 }, { 34.0, -117.45 }, { 34.0, -117.35 }, { 33.9, -117.35 } }));
        r.place = "Riverside Co";
        r.answers.put("weather", ok("72 F, wind W 8 mph", "Tonight: clear, low 48.", Section.GROUP + "Next 3 days: dry",
                "  Sunday: sunny, 75.", "  Monday: sunny, 77."));
        r.answers.put("alerts", state(Report.State.FAILED));
        r.answers.put("fires", state(Report.State.OFFLINE));
        r.answers.put("evacuations", state(Report.State.LOADING));
        r.answers.put("flood", ok(null));

        final Report.Answer calls = ok("3 in the last 2 h", "Calls received in the last two hours.");
        calls.points = new JSONArray()
                .put(point("calls", "Medical aid", "Riverside Fire", "Received 13:50", "Main St").put("ag", "Riverside Fire").put("t", NOW - 15 * 60_000L))
                .put(point("calls", "Traffic collision", "Riverside Fire", "Received 13:20", "5th St").put("ag", "Riverside Fire").put("t", NOW - 45 * 60_000L))
                .put(point("calls", "Burglary", "Riverside PD", "x", "Elm Ave").put("ag", "Riverside PD").put("t", NOW - 5 * 60_000L));
        r.answers.put("calls", calls);

        final Report.Answer places = ok("2 shelters, 1 hospital", Section.GROUP + "Shelters: 2", "  Hidden list line",
                "California only: care homes from CDPH.");
        places.points = new JSONArray()
                .put(point("shelter", "OPEN: Lincoln High School " + phone("0142"), "OPEN NOW.", "Contact: Jane Placeholder",
                        "Shelter manager phone " + phone("0199").replace("(951) ", "951-") + ".", "Evacuation capacity 300.", "100 Main St, Riverside, CA"))
                .put(point("shelter", "Arlington Community Center", "Designated shelter, not open now.",
                        "POC Robert Example", "4 mi N of the area."))
                .put(point("hospital", "Riverside Community Hospital", "Level II trauma.", phone("0100")));
        r.answers.put("places", places);

        // A Comms answer with a row a future catalog might mark CUI.
        r.answers.put("comms", ok("4 in the area", "Repeater sites with known nets.",
                "CUI: Fire Net 2 on Box Springs, 159.3000 tone 103.5", "  (CUI) Command 4 at Mt Rubidoux"));
        return r;
    }

    private static String allText(List<PdfLayout.Page> pages) {
        final StringBuilder b = new StringBuilder();
        for (PdfLayout.Page p : pages)
            b.append(p.text());
        // A table cell or a long line wraps; read it back as one line of words.
        return b.toString().replace('\n', ' ');
    }

    @Test
    public void everyTurnedOnSectionIsThereInReportOrder() throws Exception {
        final Report r = report();
        final ReportDoc d = ReportDoc.build(r, facts());
        final List<String> expected = new ArrayList<>();
        for (Section s : Sections.all())
            if (r.answer(s.key) != null)
                expected.add(s.name);
        assertEquals(expected.size(), d.sections.size());
        for (int i = 0; i < expected.size(); i++)
            assertTrue(d.sections.get(i) + " should be " + expected.get(i),
                    d.sections.get(i).startsWith(expected.get(i) + ":"));
        // Sections the report does not have are not in the PDF.
        for (Section s : Sections.all())
            if (r.answer(s.key) == null)
                for (String h : d.sections)
                    assertFalse(h.startsWith(s.name + ": "));

        final String text = allText(PdfLayout.layout(d, HALF));
        for (String name : expected)
            assertTrue(name + " missing from the pages", text.contains(name + ":"));
        // Same heads and numbers as the report page.
        assertTrue(text.contains("Weather and daylight: 72 F, wind W 8 mph (20 min ago)"));
        assertTrue(text.contains("911 calls: 3 in the last 2 h (20 min ago)"));
        assertTrue(text.contains("Radio sites: 4 in the area"));
        // Failed, offline, loading and empty sections each say so.
        assertTrue(text.contains("Not available: the source did not answer when the report asked."));
        assertTrue(text.contains("Not available: this device had no network when the report asked."));
        assertTrue(text.contains("Still loading when this PDF was made; nothing to show."));
        assertTrue(text.contains("Nothing found for this area."));
        // Group bodies are written out, and lists come from the saved places.
        assertTrue(text.contains("Monday: sunny, 77."));
        assertTrue(text.contains("Shelters (2)"));
        assertTrue(text.contains("Riverside Fire (2)"));
        assertTrue(text.contains("Medical aid"));
        assertTrue(text.contains("California only: care homes from CDPH."));
        // Header.
        assertTrue(text.contains("Sit Rep"));
        assertTrue(text.contains("MGRS 11S MT 63045 56998"));
        assertTrue(text.contains("ENGINE-51"));
        assertTrue(text.contains("Nov 28, 2026 13:05 PST"));
    }

    @Test
    public void cuiRowsAndShelterContactsAreLeftOut() throws Exception {
        final ReportDoc d = ReportDoc.build(report(), facts());
        final String text = allText(PdfLayout.layout(d, HALF));
        assertFalse(text.contains("CUI"));
        assertFalse(text.contains("Fire Net 2"));
        assertFalse(text.contains("Command 4"));
        assertFalse(text.contains("Jane Placeholder"));
        assertFalse(text.contains("Robert Example"));
        assertFalse(text.contains("555-0142"));
        assertFalse(text.contains("555-0199"));
        assertFalse(text.contains("manager"));
        assertTrue(d.cuiLeftOut >= 2);
        assertTrue(d.contactsLeftOut >= 3);
        // The rest of those rows stays.
        assertTrue(text.contains("Lincoln High School"));
        assertTrue(text.contains("Evacuation capacity 300."));
        assertTrue(text.contains("Repeater sites with known nets."));
        // A CUI row inside a place list goes too.
        final Report r = report();
        r.answer("places").points.put(point("hospital", "Station 9 annex", "CUI//SP-CRIT staging area"));
        assertFalse(allText(PdfLayout.layout(ReportDoc.build(r, facts()), HALF)).contains("Station 9 annex"));
    }

    @Test
    public void pageNumbersAreRightAndLongTablesRepeatTheirHead() throws Exception {
        final Report r = report();
        final JSONArray many = new JSONArray();
        for (int k = 0; k < 200; k++)
            many.put(point("calls", "Call number " + k + " with a long description of what was reported here",
                    "Riverside Fire", "x", (100 + k) + " Very Long Avenue Name, Riverside").put("ag", "Agency " + (k / 40))
                    .put("t", NOW - k * 60_000L));
        r.answer("calls").points = many;
        final List<PdfLayout.Page> pages = PdfLayout.layout(ReportDoc.build(r, facts()), HALF);
        final int n = pages.size();
        assertTrue("a long report runs to several pages, not " + n, n >= 4);
        final Pattern label = Pattern.compile("Page (\\d+) of (\\d+)");
        int tableContinues = 0;
        for (int i = 0; i < n; i++) {
            final String t = pages.get(i).text();
            final Matcher m = label.matcher(t);
            assertTrue("page " + (i + 1) + " has no number", m.find());
            assertEquals(i + 1, Integer.parseInt(m.group(1)));
            assertEquals(n, Integer.parseInt(m.group(2)));
            assertFalse("page " + (i + 1) + " has two numbers", m.find());
            assertTrue(t.contains("Snapshot at Nov 28, 2026 14:05 PST. Data may be out of date."));
            assertTrue(t.contains("Data from public agency sources"));
            // A page that opens inside a call table starts with its head row.
            final String first = firstBodyText(pages.get(i));
            if (first.equals("Received"))
                tableContinues++;
        }
        assertTrue("a table that runs over a page repeats its head", tableContinues >= 1);
    }

    @Test
    public void noTextRunsOffThePage() throws Exception {
        final Report r = report();
        r.answer("weather").lines.add("Averyveryveryveryveryveryveryveryveryveryveryveryveryveryveryveryveryveryverylongwordwithnospaces");
        for (PdfLayout.Page p : PdfLayout.layout(ReportDoc.build(r, facts()), HALF))
            for (PdfLayout.Op o : p.ops) {
                if (o.type != PdfLayout.Op.TEXT)
                    continue;
                assertTrue("runs off the right: " + o.text,
                        o.x + HALF.width(o.text, o.size, o.bold) <= PdfLayout.PAGE_W - PdfLayout.MARGIN + 0.01f);
                assertTrue("below the page: " + o.text, o.y < PdfLayout.PAGE_H);
                assertTrue("above the page: " + o.text, o.y > 0);
            }
    }

    @Test
    public void oldReportsStillMakeAPdf() throws Exception {
        // A 0.8-era saved report read back from its JSON, with a section key this
        // version does not know: it shares like any other.
        final JSONObject saved = report().toJson();
        saved.getJSONObject("sections").put("retired_section", new JSONObject().put("state", "OK").put("value", "x"));
        saved.remove("label");
        final Report back = Report.fromJson(saved);
        final List<PdfLayout.Page> pages = PdfLayout.layout(ReportDoc.build(back, facts()), HALF);
        assertTrue(allText(pages).contains("Weather and daylight: 72 F"));
        assertFalse(allText(pages).contains("retired_section"));
    }

    @Test
    public void fileNamesAreSafe() {
        final Pattern safe = Pattern.compile("SitRep_[A-Za-z0-9_]{1,40}_\\d{8}-\\d{4}\\.pdf");
        assertEquals("SitRep_Riverside_Co_20261128-1405.pdf", ReportDoc.fileName("Riverside Co", NOW, LA));
        assertEquals("SitRep_Area_20261128-1405.pdf", ReportDoc.fileName(null, NOW, LA));
        assertEquals("SitRep_Area_20261128-1405.pdf", ReportDoc.fileName("../..//", NOW, LA));
        final String[] nasty = { "../../etc/passwd", "Div A / Branch II: \"night\" <ops>", "Café Zürich \u200E",
                "a\u0000b\nc", "Riverside Co, Nov 12 14:05", new String(new char[200]).replace("\0", "x"), "  ", "C:\\Windows\\..",
                "名前" };
        for (String s : nasty) {
            final String f = ReportDoc.fileName(s, NOW, LA);
            assertTrue(f, safe.matcher(f).matches());
            assertFalse(f, f.contains(".."));
        }
        assertEquals("SitRep_Div_A_Branch_II_night_ops_20261128-1405.pdf",
                ReportDoc.fileName("Div A / Branch II: \"night\" <ops>", NOW, LA));
    }

    @Test
    public void radiusIsCenterToFarthestPoint() {
        final double[][] ring = { { 0, -0.01 }, { 0.01, 0 }, { 0, 0.01 }, { -0.01, 0 } };
        assertEquals(1112, ReportDoc.radiusMeters(ring, 0, 0), 2);
    }

    /** The first text drawn below the page top that is not the footer. */
    private static String firstBodyText(PdfLayout.Page p) {
        for (PdfLayout.Op o : p.ops)
            if (o.type == PdfLayout.Op.TEXT && o.y < PdfLayout.PAGE_H - PdfLayout.FOOTER)
                return o.text;
        return "";
    }
}
