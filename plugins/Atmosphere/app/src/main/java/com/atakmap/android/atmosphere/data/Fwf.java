package com.atakmap.android.atmosphere.data;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * The NWS Fire Weather Planning Forecast (product FWF) for one fire weather zone.
 *
 * <p>Each forecast office issues one product for every fire zone it covers, once or
 * twice a day, in sections headed by a UGC line naming a group of zones
 * ({@code CAZ356>358-369>375-548-291130-}) and closed by {@code $$} -- the same
 * layout as the marine forecast, so {@link Cwf#section} cuts the zone's part out.
 * Offices write it two ways: the West in period blocks with dotted labels
 * ({@code Min humidity........15-25 percent}), the East as a table of four periods.
 * Both are shown as written, never parsed into fields.
 *
 * <p>Ahead of the first section is the office's own part: a title, the office, the
 * issue time, then headlines ({@code ...ELEVATED FIRE WEATHER CONDITIONS...}) and a
 * discussion that covers every zone. That is {@link #discussion}.
 *
 * <p>No Android types; tested on the LOX, TAE and MSO issuances of 2026-09-28.
 */
public final class Fwf {

    public static final String HOST = Cwf.HOST;

    /** The office's issuances, newest first. */
    public static String listUrl(String office) {
        return Cwf.latestUrl("FWF", office);
    }

    public static String productUrl(String id) {
        return Cwf.productUrl(id);
    }

    /** One issuance in the office's list. */
    public static final class Issuance {
        public final String id;
        /** Epoch ms, or 0 when the list did not say. */
        public final long issuedAt;

        Issuance(String id, long issuedAt) {
            this.id = id;
            this.issuedAt = issuedAt;
        }
    }

    /** The newest {@code max} issuances, newest first; empty when the office lists none. */
    public static List<Issuance> parseList(String body, int max) {
        final List<Issuance> out = new ArrayList<>();
        if (body == null || body.isEmpty())
            return out;
        try {
            final JSONArray g = new JSONObject(body).optJSONArray("@graph");
            if (g == null)
                return out;
            for (int i = 0; i < g.length() && out.size() < max; i++) {
                final JSONObject o = g.optJSONObject(i);
                if (o == null)
                    continue;
                final String id = o.optString("id", "");
                if (id.isEmpty() || !id.matches("[0-9a-fA-F-]{8,64}"))
                    continue;
                out.add(new Issuance(id, IsoTime.parse(o.optString("issuanceTime", ""))));
            }
        } catch (Exception e) {
            return out;
        }
        return out;
    }

    /** The product text of a {@code /products/{id}} answer, or null. */
    public static String text(String body) {
        return Cwf.parseText(body);
    }

    /** The zone's section, as written; null when this issuance does not cover it. */
    public static String section(String text, String ugc) {
        return Cwf.section(text, ugc);
    }

    private static final Pattern OFFICE = Pattern.compile("^National Weather Service (.+)$");

    /** "Los Angeles/Oxnard CA" from the product's office line, or "". */
    public static String office(String text) {
        if (text == null)
            return "";
        for (String l : text.split("\n")) {
            final java.util.regex.Matcher m = OFFICE.matcher(l.trim());
            if (m.matches())
                return m.group(1).trim();
        }
        return "";
    }

    private static final Pattern ISSUED = Pattern.compile(
            "^\\d{3,4} (AM|PM) [A-Z]{3,4} [A-Z][a-z]{2} [A-Z][a-z]{2} \\d{1,2} \\d{4}$");
    private static final Pattern UGC_START = Pattern.compile("^[A-Z]{3}[0-9]{3}[0-9A-Z>-]*-$");

    /**
     * The office's headlines and discussion: what follows the issue time, up to the
     * first zone section. Empty when the product has none.
     */
    public static String discussion(String text) {
        if (text == null)
            return "";
        final String[] lines = text.split("\n");
        int from = -1;
        for (int i = 0; i < lines.length; i++) {
            final String t = lines[i].trim();
            if (from < 0) {
                if (ISSUED.matcher(t).matches())
                    from = i + 1;
                continue;
            }
            if (UGC_START.matcher(t).matches())
                return join(lines, from, i);
        }
        return "";
    }

    private static String join(String[] lines, int from, int to) {
        final StringBuilder b = new StringBuilder();
        for (int i = from; i < to; i++)
            b.append(lines[i].replaceAll("\\s+$", "")).append('\n');
        return b.toString().trim();
    }

    private Fwf() {
    }
}
