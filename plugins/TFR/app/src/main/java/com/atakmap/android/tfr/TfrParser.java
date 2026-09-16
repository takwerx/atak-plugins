package com.atakmap.android.tfr;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import java.io.ByteArrayInputStream;
import java.nio.charset.Charset;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

/**
 * The FAA's XNOTAM-Update detail XML, turned into a {@link Tfr}.
 *
 * <p>Every rule here was measured against all 104 detail documents in the national picture on
 * 2026-09-16, not inherited. infra-TAK's Node-RED engine is the older implementation of the
 * same job and remains the reference for what the fields mean, but three of its assumptions do
 * not survive the whole feed:
 *
 * <ul>
 *   <li>It walks {@code aseCircle} into a 36-point ring. {@code aseCircle} appears in <b>0 of
 *       104</b>: the FAA publishes every shape pre-expanded in {@code abdMergedArea}, whose
 *       vertices are 100% great-circle. There is one geometry path and no arc or radius math.</li>
 *   <li>It reads {@code valDistVerUpper} as feet. {@code uomDistVerUpper} is {@code FL} on 14
 *       areas, where 180 means 18,000 ft — so an 18,000 ft shelf is labeled "180", a number a
 *       UAS operator would read as clear air.</li>
 *   <li>It reads {@code dateEffective} as UTC. {@code codeTimeZone} is a local zone on 37 of
 *       104 (EDT, PDT, CDT, MDT, AKDT, PST, Guam), so those windows are wrong by up to ten
 *       hours — enough to draw a two-hour air show TFR as live when it is over.</li>
 * </ul>
 */
public final class TfrParser {

    private TfrParser() {
    }

    /**
     * Zone abbreviation to offset in hours.
     *
     * <p>Fixed offsets rather than {@code TimeZone.getTimeZone(id)}, for two reasons: that call
     * answers "GMT" for anything it does not recognize, which is a silent ten-hour error on a
     * Guam TFR; and the abbreviation already says whether daylight time is in effect, so EDT is
     * -4 whatever the date is.
     */
    private static final Map<String, Integer> ZONES = new HashMap<>();

    static {
        ZONES.put("UTC", 0);
        ZONES.put("GMT", 0);
        ZONES.put("Z", 0);
        ZONES.put("AST", -4);   // Atlantic: Puerto Rico, USVI
        ZONES.put("EDT", -4);
        ZONES.put("EST", -5);
        ZONES.put("CDT", -5);
        ZONES.put("CST", -6);
        ZONES.put("MDT", -6);
        ZONES.put("MST", -7);
        ZONES.put("PDT", -7);
        ZONES.put("PST", -8);
        ZONES.put("AKDT", -8);
        ZONES.put("AKST", -9);
        ZONES.put("HDT", -9);
        ZONES.put("HST", -10);  // Hawaii does not keep daylight time
        ZONES.put("SST", -11);  // American Samoa
        ZONES.put("CHST", 10);  // Guam and the Northern Marianas
        ZONES.put("GUAM", 10);  // what the feed actually writes
    }

    /** Parse a detail document into {@code t}, which already carries its list-API fields. */
    public static void parseDetail(byte[] xml, Tfr t) throws Exception {
        Document doc = read(xml);
        Element not = first(doc.getDocumentElement(), "Not");
        Element root = not != null ? not : doc.getDocumentElement();

        t.timeZone = text(root, "codeTimeZone");
        t.city = text(root, "txtNameCity");
        t.stateName = text(root, "txtNameUSState");
        t.notamText = squash(text(root, "txtDescrTraditional"));

        boolean[] approx = new boolean[1];
        t.effectiveMs = millis(childText(root, "dateEffective"), t.timeZone, approx);
        t.expireMs = millis(childText(root, "dateExpire"), t.timeZone, approx);
        t.windowApproximate = approx[0];

        t.areas.clear();
        NodeList groups = doc.getElementsByTagName("TFRAreaGroup");
        for (int i = 0; i < groups.getLength(); i++)
            t.areas.add(area((Element) groups.item(i), t.timeZone));
        t.detailLoaded = true;
    }

    private static TfrArea area(Element g, String zone) {
        TfrArea a = new TfrArea();
        a.name = text(g, "txtLocalName");
        if (a.name.isEmpty())
            a.name = text(g, "txtName");

        // The ring. abdMergedArea is the FAA's own merged, expanded boundary; Abd is the
        // source description it was built from and is never the shape we draw.
        Element merged = first(g, "abdMergedArea");
        if (merged != null) {
            NodeList vs = merged.getElementsByTagName("Avx");
            for (int i = 0; i < vs.getLength(); i++) {
                Element v = (Element) vs.item(i);
                Double lat = coord(text(v, "geoLat"), true);
                Double lon = coord(text(v, "geoLong"), false);
                if (lat != null && lon != null)
                    a.ring.add(new double[] {
                            lat, lon
                    });
            }
        }

        // A circle in the source description is worth a label — "7 NM ring around ..." says
        // more than a 37-point polygon — but the polygon is still what gets drawn.
        Element abd = first(g, "Abd");
        if (abd != null) {
            NodeList vs = abd.getElementsByTagName("Avx");
            for (int i = 0; i < vs.getLength(); i++) {
                Element v = (Element) vs.item(i);
                if (!"CIR".equals(text(v, "codeType")))
                    continue;
                a.radiusNm = nm(text(v, "valRadiusArc"), text(v, "uomRadiusArc"));
                Double lat = coord(text(v, "geoLat"), true);
                Double lon = coord(text(v, "geoLong"), false);
                if (lat != null && lon != null)
                    a.center = new double[] {
                            lat, lon
                    };
                break;
            }
        }

        vert(a.ceiling, text(g, "valDistVerUpper"), text(g, "uomDistVerUpper"),
                text(g, "codeDistVerUpper"), false);
        vert(a.floor, text(g, "valDistVerLower"), text(g, "uomDistVerLower"),
                text(g, "codeDistVerLower"), true);

        Element sched = first(g, "ScheduleGroup");
        if (sched != null) {
            boolean[] ignored = new boolean[1];
            a.effectiveMs = millis(childText(sched, "dateEffective"), zone, ignored);
            a.expireMs = millis(childText(sched, "dateExpire"), zone, ignored);
        }
        return a;
    }

    /**
     * Normalize one vertical limit to feet, remembering how it was written.
     *
     * <p>{@code isFloor} decides what a zero means, and the two answers are not the same. A
     * floor of 0 is the ground however it is coded — the Wawona fire writes its floor as 0 ALT
     * and its own NOTAM text reads "SFC-11500FT MSL" — so it is spoken as the surface. A
     * ceiling of 0 is not a restriction a foot off the ground; it is a limit the NOTAM never
     * stated, and the standing nationwide notices are where it turns up.
     */
    private static void vert(TfrArea.Vert v, String val, String uom, String code, boolean isFloor) {
        v.raw = (val + " " + uom + " " + code).trim();
        if (val.isEmpty()) {
            if ("SFC".equalsIgnoreCase(code)) {
                v.present = true;
                v.surface = true;
            }
            return;
        }
        double n;
        try {
            n = Double.parseDouble(val.trim());
        } catch (NumberFormatException e) {
            // "SFC" and "GND" turn up in the value as well as the code.
            String s = val.trim().toUpperCase(Locale.US);
            if (s.startsWith("SFC") || s.startsWith("GND")) {
                v.present = true;
                v.surface = true;
            }
            return;
        }
        v.present = true;
        String u = uom.trim().toUpperCase(Locale.US);
        if ("FL".equals(u)) {
            // A flight level is hundreds of feet, pressure altitude: FL180 is 18,000.
            v.flightLevel = true;
            v.feet = (int) Math.round(n * 100);
            v.agl = false;
        } else if ("M".equals(u)) {
            v.feet = (int) Math.round(n / 0.3048);
        } else if ("KM".equals(u)) {
            v.feet = (int) Math.round(n * 1000 / 0.3048);
        } else {
            v.feet = (int) Math.round(n);
        }
        // HEI is height above the surface, ALT is above mean sea level. Which one it is
        // varies area by area inside a single TFR, so it is read per area and never assumed.
        if (!v.flightLevel)
            v.agl = "HEI".equalsIgnoreCase(code.trim());
        if (v.feet == 0 && !v.flightLevel) {
            if (isFloor)
                v.surface = true;
            else
                v.present = false;
        }
    }

    private static double nm(String val, String uom) {
        double n;
        try {
            n = Double.parseDouble(val.trim());
        } catch (Exception e) {
            return 0;
        }
        String u = uom.trim().toUpperCase(Locale.US);
        if ("KM".equals(u))
            return n / 1.852;
        if ("M".equals(u))
            return n / 1852.0;
        return n;
    }

    /**
     * A coordinate as the merged boundary writes it.
     *
     * <p>All 5,055 vertices in the national picture are decimal degrees with a hemisphere
     * suffix ({@code 37.68347062N}). The packed DMS form {@code DDMMSS.ss} that the older
     * parser leads with does not appear anywhere in it, but it is what the NOTAM prose uses and
     * costs four lines to keep as a fallback.
     */
    static Double coord(String s, boolean isLat) {
        if (s == null)
            return null;
        s = s.trim().toUpperCase(Locale.US);
        if (s.isEmpty())
            return null;
        int sign = 1;
        char last = s.charAt(s.length() - 1);
        if (last == 'N' || last == 'S' || last == 'E' || last == 'W') {
            sign = (last == 'S' || last == 'W') ? -1 : 1;
            s = s.substring(0, s.length() - 1).trim();
        }
        if (s.isEmpty())
            return null;
        try {
            int dot = s.indexOf('.');
            int intDigits = dot < 0 ? s.length() : dot;
            // Decimal degrees: at most three digits before the point (180), so anything
            // longer is the packed DDMMSS / DDDMMSS form.
            if (intDigits <= 3)
                return inRange(sign * Double.parseDouble(s), isLat);
            int degLen = isLat ? intDigits - 4 : intDigits - 4;
            if (degLen < 1)
                return null;
            double deg = Double.parseDouble(s.substring(0, degLen));
            double min = Double.parseDouble(s.substring(degLen, degLen + 2));
            double sec = Double.parseDouble(s.substring(degLen + 2));
            return inRange(sign * (deg + min / 60.0 + sec / 3600.0), isLat);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * A vertex off the earth is a vertex we drop. It costs one comparison, and the alternative
     * is a restriction drawn across the planet from a single bad number in a ring of 37.
     */
    private static Double inRange(double v, boolean isLat) {
        if (Double.isNaN(v) || Double.isInfinite(v))
            return null;
        double limit = isLat ? 90.0 : 180.0;
        return (v < -limit || v > limit) ? null : Double.valueOf(v);
    }

    /**
     * An FAA timestamp to UTC millis. {@code approx[0]} is set when the zone was not one we
     * can offset, so the caller can widen the window rather than pretend to know.
     */
    static long millis(String iso, String zone, boolean[] approx) {
        if (iso == null || iso.trim().isEmpty())
            return 0;
        String z = zone == null ? "" : zone.trim().toUpperCase(Locale.US);
        Integer off = ZONES.get(z);
        if (off == null) {
            approx[0] = true;
            off = 0;
        }
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US);
        f.setTimeZone(TimeZone.getTimeZone("UTC"));
        try {
            Date d = f.parse(iso.trim());
            return d.getTime() - off * 3600L * 1000L;
        } catch (ParseException e) {
            approx[0] = true;
            return 0;
        }
    }

    // ---- DOM helpers ----

    private static Document read(byte[] xml) throws Exception {
        DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
        // This document came off the network, so ask for no DTDs, no external entities and no
        // schema fetches. On a desktop JVM, where the parser is Xerces, these take effect. On
        // Android they do not: its DocumentBuilderFactory accepts only the "namespaces" and
        // "validation" feature names and throws for every other, and setExpandEntityReferences
        // is stored and ignored. What protects the plugin on a device is the platform parser
        // itself -- KXmlParser never expands internal entities unless asked, maps external ones
        // to the empty string, and never dereferences a system id. These calls are kept for the
        // harness, where they are real; none of them is load-bearing on the phone.
        harden(f, "http://apache.org/xml/features/disallow-doctype-decl", true);
        harden(f, "http://xml.org/sax/features/external-general-entities", false);
        harden(f, "http://xml.org/sax/features/external-parameter-entities", false);
        harden(f, "http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
        // Both of these throw on Android -- setXIncludeAware unconditionally, whatever is
        // passed -- and an UnsupportedOperationException here would mean no TFR ever parsed on
        // a device, while every desktop test went on passing.
        try {
            f.setXIncludeAware(false);
            f.setExpandEntityReferences(false);
        } catch (Exception ignored) {
            // Android has no XInclude support to turn off in the first place.
        }
        DocumentBuilder b = f.newDocumentBuilder();
        return b.parse(new ByteArrayInputStream(xml));
    }

    private static void harden(DocumentBuilderFactory f, String name, boolean on) {
        try {
            f.setFeature(name, on);
        } catch (Exception ignored) {
            // See read(): unknown on Android, effective on the desktop harness. A parser that
            // will not take the hint is not a reason to refuse to parse.
        }
    }

    /** First descendant with this tag, anywhere below {@code e}. */
    private static Element first(Element e, String tag) {
        NodeList l = e.getElementsByTagName(tag);
        return l.getLength() == 0 ? null : (Element) l.item(0);
    }

    /** Text of the first descendant with this tag; "" when there is none. */
    private static String text(Element e, String tag) {
        Element c = first(e, tag);
        return c == null ? "" : squash(c.getTextContent());
    }

    /**
     * Text of an <em>immediate</em> child. The NOTAM's own dates and the per-area schedule use
     * the same tag names, so a descendant search from the NOTAM would find the area's.
     */
    private static String childText(Element e, String tag) {
        for (Node n = e.getFirstChild(); n != null; n = n.getNextSibling())
            if (n.getNodeType() == Node.ELEMENT_NODE && tag.equals(n.getNodeName()))
                return squash(n.getTextContent());
        return "";
    }

    private static String squash(String s) {
        return s == null ? "" : s.replace('\r', ' ').replace('\n', ' ').replaceAll("\\s+", " ").trim();
    }

    /** UTF-8, and tolerant of a feed that mislabels itself. */
    static byte[] utf8(String s) {
        return s.getBytes(Charset.forName("UTF-8"));
    }
}
