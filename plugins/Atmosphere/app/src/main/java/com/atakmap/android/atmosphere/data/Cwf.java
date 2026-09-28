package com.atakmap.android.atmosphere.data;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The NWS coastal waters forecast for the marine zone a buoy sits in.
 *
 * <p>api.weather.gov answers {@code /points} over water with the marine zone and
 * the office (PZZ740 and SGX for San Diego Bay and for 46224 offshore, PZZ840 and
 * ONP for Tanner Bank; verified 2026-09-26), but {@code /zones/forecast/PZZ740/
 * forecast} is 404 "Marine Forecast Not Supported". The text exists as the
 * office's product -- CWF from a forecast office, OFF from the ocean centers for
 * the offshore zones -- one product for every zone the office covers, in sections
 * headed by a UGC line ({@code PZZ740-271015-}, {@code AMZ650-670-270900-},
 * {@code AMZ600-GMZ606-270900-}) and closed by {@code $$}. The zone's section is
 * cut out here and its {@code .TONIGHT...} period lines read. Headline lines
 * ({@code ...SMALL CRAFT ADVISORY...}) are left out: those are IPAWS's.
 *
 * <p>No Android types; tested on the 2026-09-26 SGX issuance.
 */
public final class Cwf {

    public static final String HOST = "api.weather.gov";
    private static final String BASE = "https://" + HOST;

    /** Four decimals: more and the API answers with a redirect to four. */
    public static String pointUrl(double lat, double lon) {
        return BASE + "/points/" + String.format(Locale.US, "%.4f,%.4f", lat, lon);
    }

    public static String latestUrl(String productType, String office) {
        return BASE + "/products/types/" + productType + "/locations/" + office;
    }

    public static String productUrl(String id) {
        return BASE + "/products/" + id;
    }

    /**
     * Marine zone prefixes, so a pier that lands on a land zone is not asked for a
     * coastal waters forecast it cannot have.
     */
    private static final Set<String> MARINE = new HashSet<>(Arrays.asList(
            "AMZ", "ANZ", "GMZ", "LCZ", "LEZ", "LHZ", "LMZ", "LOZ", "LSZ",
            "PHZ", "PKZ", "PMZ", "PSZ", "PZZ", "SLZ"));

    /** The zone a point is in and who forecasts it. */
    public static final class Zone {
        public final String id, cwa;

        Zone(String id, String cwa) {
            this.id = id;
            this.cwa = cwa;
        }

        /**
         * Which product carries the text. A forecast office issues CWF; the offshore
         * zones come back with the ocean center's id (ONP, NH2, HPA) and are in OFF.
         */
        public String productType() {
            return isOffice(cwa) ? "CWF" : "OFF";
        }

        private static boolean isOffice(String cwa) {
            return cwa != null && cwa.length() == 3 && !cwa.equals("ONP")
                    && !cwa.equals("ONA") && !cwa.equals("NH1") && !cwa.equals("NH2")
                    && !cwa.equals("HPA");
        }
    }

    /** The marine zone of a {@code /points} answer, or null on land or at sea beyond a zone. */
    public static Zone parsePoint(String body) {
        if (body == null || body.isEmpty())
            return null;
        try {
            final JSONObject p = new JSONObject(body).optJSONObject("properties");
            if (p == null)
                return null;
            final String url = p.optString("forecastZone", "");
            final String id = url.substring(url.lastIndexOf('/') + 1);
            if (id.length() != 6 || !MARINE.contains(id.substring(0, 3)))
                return null;
            return new Zone(id, p.optString("cwa", ""));
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Where an offshore zone's OFF product is filed, most likely first. The API
     * files OFF not under the ocean center's id that {@code /points} returns (ONP,
     * NH2, HPA) but under region codes, each one product covering a run of zones
     * (read from the newest issuances, 2026-09-27): PZ5 Washington and Oregon
     * (PZZ800-815, 900-915), PZ6 California (PZZ820-945), PZ7 and PZ8 the central
     * Pacific (PMZ), NT1 New England (ANZ800-815), NT2 the mid-Atlantic
     * (ANZ820-935), NT3 the Caribbean (AMZ001-061), NT5 the SW North Atlantic
     * (AMZ063-101), NT4 the Gulf (GMZ); Hawaii's is under HFO, Alaska's under its
     * offices. The zone's own section is what proves the guess, so callers scan
     * the list in order until {@link #section} finds it.
     */
    public static List<String> offshoreLocations(String zoneId) {
        final List<String> out = new ArrayList<>();
        if (zoneId == null || zoneId.length() != 6)
            return out;
        final String prefix = zoneId.substring(0, 3);
        int n = -1;
        try {
            n = Integer.parseInt(zoneId.substring(3));
        } catch (NumberFormatException ignored) {
        }
        switch (prefix) {
            case "PZZ":
                if (n % 100 <= 15) {
                    out.add("PZ5");
                    out.add("PZ6");
                } else {
                    out.add("PZ6");
                    out.add("PZ5");
                }
                break;
            case "PMZ":
                if (n >= 100) {
                    out.add("PZ8");
                    out.add("PZ7");
                } else {
                    out.add("PZ7");
                    out.add("PZ8");
                }
                break;
            case "ANZ":
                if (n % 100 <= 15) {
                    out.add("NT1");
                    out.add("NT2");
                } else {
                    out.add("NT2");
                    out.add("NT1");
                }
                break;
            case "AMZ":
                if (n <= 61) {
                    out.add("NT3");
                    out.add("NT5");
                } else {
                    out.add("NT5");
                    out.add("NT3");
                }
                break;
            case "GMZ":
                out.add("NT4");
                break;
            case "PHZ":
                out.add("HFO");
                break;
            case "PKZ":
                out.add("AER");
                out.add("ALU");
                out.add("AJK");
                out.add("AFG");
                break;
            default:
                break;
        }
        return out;
    }

    /** The newest issuance's id from a product-type listing, or null. */
    public static String parseLatestId(String body) {
        if (body == null || body.isEmpty())
            return null;
        try {
            final JSONArray g = new JSONObject(body).optJSONArray("@graph");
            if (g == null || g.length() == 0)
                return null;
            final String id = g.getJSONObject(0).optString("id", "");
            return id.isEmpty() ? null : id;
        } catch (Exception e) {
            return null;
        }
    }

    public static String parseText(String body) {
        if (body == null || body.isEmpty())
            return null;
        try {
            final String t = new JSONObject(body).optString("productText", "");
            return t.isEmpty() ? null : t;
        } catch (Exception e) {
            return null;
        }
    }

    private static final Pattern UGC_START = Pattern.compile("^[A-Z]{3}[0-9]{3}[0-9A-Z>-]*-$");
    private static final Pattern UGC_MORE = Pattern.compile("^[0-9A-Z>-]+-$");
    private static final Pattern UGC_END = Pattern.compile("[0-9]{6}-$");

    /**
     * The zone's section of a product: everything after its UGC header up to the
     * {@code $$}, or null when the product has no section naming the zone.
     */
    public static String section(String text, String zoneId) {
        if (text == null || zoneId == null)
            return null;
        for (String block : text.split("\\$\\$")) {
            final String[] lines = block.split("\n");
            for (int i = 0; i < lines.length; i++) {
                final String l = lines[i].trim();
                if (!UGC_START.matcher(l).matches())
                    continue;
                // A long header wraps; it runs until the six-digit expiry.
                final StringBuilder header = new StringBuilder(l);
                int j = i;
                while (!UGC_END.matcher(header).find() && j + 1 < lines.length
                        && UGC_MORE.matcher(lines[j + 1].trim()).matches())
                    header.append(lines[++j].trim());
                if (expandUgc(header.toString()).contains(zoneId)) {
                    final StringBuilder b = new StringBuilder();
                    for (int k = j + 1; k < lines.length; k++)
                        b.append(lines[k]).append('\n');
                    return b.toString().trim();
                }
                break;
            }
        }
        return null;
    }

    /**
     * Every zone a UGC header names. {@code PZZ740-271015-} is one; {@code
     * AMZ650-670-270900-} two under one prefix; {@code AMZ600-GMZ606-} switches
     * prefix; {@code PZZ750>753} is a range.
     */
    public static Set<String> expandUgc(String header) {
        final Set<String> out = new HashSet<>();
        if (header == null)
            return out;
        final String[] tokens = header.trim().replaceAll("-+$", "").split("-");
        String prefix = null;
        for (int i = 0; i < tokens.length; i++) {
            final String t = tokens[i].trim();
            if (t.isEmpty())
                continue;
            if (i == tokens.length - 1 && t.matches("[0-9]{6}"))
                break;   // the expiry, ddhhmm
            final int gt = t.indexOf('>');
            if (gt >= 0) {
                final String from = t.substring(0, gt), to = t.substring(gt + 1);
                if (from.length() == 6)
                    prefix = from.substring(0, 3);
                if (prefix == null)
                    continue;
                try {
                    final int a = Integer.parseInt(from.substring(from.length() - 3));
                    final int b = Integer.parseInt(to.substring(to.length() - 3));
                    for (int n = a; n <= b; n++)
                        out.add(prefix + String.format(Locale.US, "%03d", n));
                } catch (NumberFormatException ignored) {
                }
                continue;
            }
            if (t.length() == 6 && Character.isLetter(t.charAt(0))) {
                prefix = t.substring(0, 3);
                out.add(t);
            } else if (t.length() == 3 && prefix != null && t.matches("[0-9]{3}")) {
                out.add(prefix + t);
            }
        }
        return out;
    }

    private static final Pattern ISSUED = Pattern.compile(
            "^(\\d{1,2})(\\d{2}) (AM|PM) ([A-Z]{3,4}) ([A-Z][a-z]{2}) ([A-Z][a-z]{2}) (\\d{1,2}) \\d{4}$");

    /** "2:43 PM PDT Sat Sep 26" from the section's "243 PM PDT Sat Sep 26 2026" line, or "". */
    public static String issued(String section) {
        if (section == null)
            return "";
        for (String l : section.split("\n")) {
            final Matcher m = ISSUED.matcher(l.trim());
            if (m.matches())
                return m.group(1) + ":" + m.group(2) + " " + m.group(3) + " " + m.group(4)
                        + " " + m.group(5) + " " + m.group(6) + " " + m.group(7);
        }
        return "";
    }

    /** The zone's name: the lines before the issued line, the trailing dash dropped. */
    public static String name(String section) {
        if (section == null)
            return "";
        final StringBuilder b = new StringBuilder();
        for (String l : section.split("\n")) {
            final String t = l.trim();
            if (ISSUED.matcher(t).matches())
                break;
            if (t.isEmpty())
                continue;
            if (b.length() > 0)
                b.append(' ');
            b.append(t);
        }
        return b.toString().replaceAll("-+$", "").trim();
    }

    /** One forecast period: "Tonight", and its sentence or two. */
    public static final class Period {
        public final String name, text;

        Period(String name, String text) {
            this.name = name;
            this.text = text;
        }
    }

    private static final Pattern PERIOD = Pattern.compile("^\\.([A-Z][A-Z ]*?)\\.\\.\\.(.*)$");

    /**
     * The section's periods in order. A period is a {@code .NAME...text} line and
     * the lines under it until the next; a headline ({@code ...GALE WARNING...})
     * starts with three dots and is not one.
     */
    public static List<Period> periods(String section) {
        final List<Period> out = new ArrayList<>();
        if (section == null)
            return out;
        String name = null;
        StringBuilder text = null;
        for (String l : section.split("\n")) {
            final String t = l.trim();
            final Matcher m = PERIOD.matcher(t);
            if (m.matches()) {
                if (name != null)
                    out.add(new Period(name, squeeze(text)));
                name = title(m.group(1));
                text = new StringBuilder(m.group(2));
            } else if (name != null && !t.isEmpty() && !t.startsWith("...")) {
                text.append(' ').append(t);
            }
        }
        if (name != null)
            out.add(new Period(name, squeeze(text)));
        return out;
    }

    private static String squeeze(StringBuilder b) {
        return b.toString().replaceAll("\\s+", " ").trim();
    }

    /** "SUN NIGHT" reads "Sun night"; "TONIGHT" "Tonight". */
    static String title(String upper) {
        final String s = upper.trim().toLowerCase(Locale.US);
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    private Cwf() {
    }
}
