package com.atakmap.android.sitrep.section;

import com.atakmap.android.sitrep.Area;
import com.atakmap.android.sitrep.net.Esri;
import com.atakmap.android.sitrep.net.Http;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.List;
import java.util.Locale;

/**
 * Who lives in the area (PLAN 4.2): people and housing units from the 2020 Census
 * blocks the area touches, and who may need help from the CDC/ATSDR Social
 * Vulnerability Index 2022 tracts. Tracts are bigger than most areas, so each share
 * is the tracts' population-weighted percent, applied to the area's own people.
 * Every count is "about": whole blocks at the edge are counted.
 */
public final class People extends Section {

    static final String BLOCKS = "https://tigerweb.geo.census.gov/arcgis/rest/services/TIGERweb/Tracts_Blocks/MapServer/12";
    static final String SVI = "https://onemap.cdc.gov/OneMapServices/rest/services/SVI/"
            + "CDC_ATSDR_Social_Vulnerability_Index_2022_USA/FeatureServer/2";

    /** SVI field, words, and whether it counts people (else housing units). */
    private static final String[][] NEEDS = {
            { "EP_AGE65", "aged 65 or older", "p" },
            { "EP_AGE17", "aged 17 or younger", "p" },
            { "EP_DISABL", "with a disability", "p" },
            { "EP_NOVEH", "households with no vehicle", "h" },
            { "EP_LIMENG", "who speak limited English", "p" },
            { "EP_POV150", "below 150% of the poverty line", "p" },
            { "EP_UNINSUR", "with no health insurance", "p" },
            { "EP_MOBILE", "mobile homes", "h" },
    };

    public People() {
        super("people", "People", false,
                "2020 Census blocks (TIGERweb); CDC/ATSDR Social Vulnerability Index 2022 by tract.");
    }

    @Override
    public Result fetch(Area area) throws Exception {
        final String form = "f=json&where=1%3D1" + area.esriQueryForm() + "&outStatistics=" + Esri.enc(
                "[{\"statisticType\":\"sum\",\"onStatisticField\":\"POP100\",\"outStatisticFieldName\":\"pop\"},"
                        + "{\"statisticType\":\"sum\",\"onStatisticField\":\"HU100\",\"outStatisticFieldName\":\"hu\"}]");
        final JSONArray f = new JSONObject(Http.post(BLOCKS + "/query", form)).optJSONArray("features");
        final JSONObject s = f == null || f.length() == 0 || f.optJSONObject(0) == null ? null
                : f.optJSONObject(0).optJSONObject("attributes");
        if (s == null)
            throw new java.io.IOException("no block totals in the answer");
        final double pop = s.optDouble("pop", 0), hu = s.optDouble("hu", 0);
        if (!(pop > 0) && !(hu > 0))
            return new Result("No one lives here", null).line("The 2020 Census counted no people or homes in the area.");

        final StringBuilder fields = new StringBuilder("E_TOTPOP,RPL_THEMES");
        for (String[] n : NEEDS)
            fields.append(',').append(n[0]);
        final List<JSONObject> tracts = Esri.attributes(SVI, "1=1", area, fields.toString());
        double weight = 0, worst = -1, sviSum = 0;
        final double[] sums = new double[NEEDS.length];
        for (JSONObject t : tracts) {
            final double tp = t.optDouble("E_TOTPOP", 0);
            if (!(tp > 0))
                continue;
            weight += tp;
            final double rpl = t.optDouble("RPL_THEMES", -1);
            if (rpl >= 0 && rpl <= 1) {
                worst = Math.max(worst, rpl);
                sviSum += rpl * tp;
            }
            for (int i = 0; i < NEEDS.length; i++) {
                final double v = t.optDouble(NEEDS[i][0], -1);
                if (v >= 0 && v <= 100)
                    sums[i] += v * tp;
            }
        }

        final Result r = new Result("About " + count(pop) + " people, " + count(hu) + " homes",
                "~" + count(pop) + " people");
        r.line(GROUP + "Population: about " + count(pop));
        r.line("  About " + count(pop) + " people and " + count(hu) + " housing units (2020 Census).");
        r.line("  Whole census blocks at the edge of the area are counted, so the numbers run high for a small area.");
        if (weight > 0) {
            final double avg = sviSum / weight;
            r.line(GROUP + "Vulnerability (CDC SVI): " + level(avg));
            r.line(String.format(Locale.US, "  Average %.2f (%s); the most vulnerable tract %.2f (%s).",
                    avg, level(avg), worst, level(worst)));
            r.line("  0 is the least vulnerable tract in the country, 1 the most.");
            r.line(GROUP + "Who may need help");
            for (int i = 0; i < NEEDS.length; i++) {
                final double pct = sums[i] / weight;
                final double base = "h".equals(NEEDS[i][2]) ? hu : pop;
                final long n = Math.round(base * pct / 100);
                if (n <= 0)
                    continue;
                r.line(String.format(Locale.US, "  About %s %s %s (%.0f%%).", count(n),
                        "h".equals(NEEDS[i][2]) ? "" : "people", NEEDS[i][1], pct).replace("  ", " ").replaceFirst("^ ", "  "));
            }
        }
        return r;
    }

    /** CDC's own four bands. */
    private static String level(double rpl) {
        if (rpl < 0)
            return "not known";
        if (rpl < 0.25)
            return "low";
        if (rpl < 0.5)
            return "low to moderate";
        if (rpl < 0.75)
            return "moderate to high";
        return "high";
    }

    private static String count(double n) {
        if (n >= 10_000)
            return String.format(Locale.US, "%,d", Math.round(n / 100) * 100);
        return String.format(Locale.US, "%,d", Math.round(n));
    }
}
