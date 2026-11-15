package com.atakmap.android.sitrep.section;

import com.atakmap.android.sitrep.net.Http;

import org.xmlpull.v1.XmlPullParser;

import java.io.IOException;
import java.io.StringReader;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

/**
 * The DHS National Terrorism Advisory System feed (PLAN 4.1b): the bulletin or alert in
 * force, if any. Nationwide, so it is the same for every area; the Alerts section shows
 * it first, and only when one is in force (the feed is an empty {@code <alerts>} most of
 * the time).
 *
 * <p>Format, read from archived copies (2019, 2023): {@code <alert start="2022/11/30
 * 14:00:00" end="2023/05/24 14:00:00" type="Bulletin Threat" href="...pdf">} holding a
 * {@code <summary>} of HTML elements. The summary's first paragraph is kept.
 */
final class Ntas {

    static final String FEED = "https://www.dhs.gov/ntas/1.1/feed.xml";
    private static final int MAX_TEXT = 700;

    private Ntas() {
    }

    /** What is in force. */
    static final class Advisory {
        /** {@code Terrorism bulletin}, {@code Elevated terrorism alert}, {@code Imminent terrorism alert}. */
        String kind;
        /** The end date, {@code May 24, 2023}, or null when the feed gives none. */
        String until;
        /** The summary's first paragraph, plain text. */
        String summary;
        /** The bulletin on dhs.gov, https only, or null. */
        String href;
    }

    /** The advisory in force now, or null when none is. Throws when the feed cannot be read. */
    static Advisory current() throws IOException {
        // HTML's &nbsp; is not an XML entity; the archived feeds use only &amp;, but one
        // stray &nbsp; would otherwise lose the whole advisory.
        return parse(Http.get(FEED).replace("&nbsp;", " "), System.currentTimeMillis());
    }

    static Advisory parse(String xml, long now) throws IOException {
        try {
            final XmlPullParser p = android.util.Xml.newPullParser();
            p.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false);
            p.setInput(new StringReader(xml));
            Advisory cur = null, found = null;
            boolean inSummary = false, inPara = false, gotPara = false;
            final StringBuilder all = new StringBuilder(), para = new StringBuilder();
            for (int ev = p.getEventType(); ev != XmlPullParser.END_DOCUMENT; ev = p.next()) {
                if (ev == XmlPullParser.START_TAG) {
                    final String tag = p.getName();
                    if ("alert".equals(tag) && found == null) {
                        final long start = time(p.getAttributeValue(null, "start"));
                        final long end = time(p.getAttributeValue(null, "end"));
                        cur = null;
                        if ((start <= 0 || start <= now) && (end <= 0 || now < end)) {
                            cur = new Advisory();
                            cur.kind = kind(p.getAttributeValue(null, "type"));
                            cur.until = end > 0 ? day(end) : null;
                            final String href = p.getAttributeValue(null, "href");
                            cur.href = href != null && href.startsWith("https://www.dhs.gov/") ? href : null;
                        }
                        all.setLength(0);
                        para.setLength(0);
                        gotPara = false;
                    } else if ("summary".equals(tag) && cur != null)
                        inSummary = true;
                    else if ("p".equals(tag) && inSummary && !gotPara)
                        inPara = true;
                } else if (ev == XmlPullParser.TEXT && inSummary) {
                    final String t = p.getText();
                    if (t != null) {
                        if (all.length() < MAX_TEXT * 2)
                            all.append(t).append(' ');
                        if (inPara && para.length() < MAX_TEXT * 2)
                            para.append(t);
                    }
                } else if (ev == XmlPullParser.END_TAG) {
                    final String tag = p.getName();
                    if ("p".equals(tag) && inPara) {
                        inPara = false;
                        gotPara = para.toString().trim().length() > 0;
                    } else if ("summary".equals(tag) && inSummary) {
                        inSummary = false;
                        if (cur != null)
                            cur.summary = clip(gotPara ? para.toString() : all.toString());
                    } else if ("alert".equals(tag) && cur != null) {
                        found = cur;
                        cur = null;
                    }
                }
            }
            return found;
        } catch (org.xmlpull.v1.XmlPullParserException e) {
            throw new IOException("the advisory feed is not valid XML");
        }
    }

    private static String kind(String type) {
        final String t = type == null ? "" : type.toLowerCase(Locale.US);
        if (t.contains("imminent"))
            return "Imminent terrorism alert";
        if (t.contains("elevated"))
            return "Elevated terrorism alert";
        return "Terrorism bulletin";
    }

    /** {@code 2023/05/24 14:00:00}, read as US Eastern (DHS's own clock); 0 when absent or unreadable. */
    private static long time(String s) {
        if (s == null || s.trim().isEmpty())
            return 0;
        try {
            final SimpleDateFormat f = new SimpleDateFormat("yyyy/MM/dd HH:mm:ss", Locale.US);
            f.setTimeZone(TimeZone.getTimeZone("America/New_York"));
            final Date d = f.parse(s.trim());
            return d == null ? 0 : d.getTime();
        } catch (java.text.ParseException e) {
            return 0;
        }
    }

    private static String day(long t) {
        final SimpleDateFormat f = new SimpleDateFormat("MMM d, yyyy", Locale.US);
        f.setTimeZone(TimeZone.getTimeZone("America/New_York"));
        return f.format(new Date(t));
    }

    private static String clip(String s) {
        // No control characters: \u0001 is the report's link marker, and server text
        // must never turn a line into a link.
        final String t = s.replaceAll("\\p{Cntrl}", " ").replace('\u00A0', ' ').replaceAll("\\s+", " ").trim();
        if (t.length() <= MAX_TEXT)
            return t;
        final int cut = t.lastIndexOf(". ", MAX_TEXT);
        return cut > 200 ? t.substring(0, cut + 1) : t.substring(0, MAX_TEXT) + "...";
    }
}
