package com.atakmap.android.sitrep.section;

import com.atakmap.android.sitrep.Area;
import com.atakmap.android.sitrep.net.Esri;
import com.atakmap.android.sitrep.net.Http;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * FEMA's National Risk Index for the census tracts of the area (PLAN 4.2): which
 * hazards FEMA rates highest here, overall risk, community resilience. For a crew new
 * to an area: what this place is known for (operator, 2026-11-14).
 *
 * <p>Only tracts whose middle is inside the area count, so a neighbor across a city
 * line is not this place's rating (the "Where and whose" lesson, 2026-11-13). A drawn
 * area smaller than a tract has no tract middle in it; then the tracts touching it
 * stand. Each tract weighs by its population.
 */
public final class Risk extends Section {

    static final String NRI = "https://services.arcgis.com/XG15cJAlne2vxtgt/arcgis/rest/services/National_Risk_Index_Census_Tracts/FeatureServer/0";

    /** Field prefix and the hazard's name, FEMA's own wording. */
    private static final String[][] HAZARDS = {
            { "AVLN", "Avalanche" }, { "CFLD", "Coastal flooding" }, { "CWAV", "Cold wave" },
            { "DRGT", "Drought" }, { "ERQK", "Earthquake" }, { "HAIL", "Hail" },
            { "HWAV", "Heat wave" }, { "HRCN", "Hurricane" }, { "ISTM", "Ice storm" },
            { "LNDS", "Landslide" }, { "LTNG", "Lightning" }, { "IFLD", "Inland flooding" },
            { "SWND", "Strong wind" }, { "TRND", "Tornado" }, { "TSUN", "Tsunami" },
            { "VLCN", "Volcanic activity" }, { "WFIR", "Wildfire" }, { "WNTW", "Winter weather" } };

    /** FEMA's ratings, lowest first; anything else (No Rating, Not Applicable) is skipped. */
    private static final String[] RATINGS = { "Very Low", "Relatively Low", "Relatively Moderate",
            "Relatively High", "Very High" };

    public Risk() {
        super("risk", "Hazard risk", false,
                "FEMA National Risk Index, census tracts: FEMA's rating of each natural hazard, overall risk and community resilience.");
    }

    @Override
    public Result fetch(Area area) throws Exception {
        final StringBuilder fields = new StringBuilder("POPULATION,RISK_RATNG,RESL_RATNG");
        for (String[] h : HAZARDS)
            fields.append(',').append(h[0]).append("_RISKR");
        final String form = "where=1%3D1&f=json&returnGeometry=false&returnCentroid=true&outSR=4326"
                + "&outFields=" + Esri.enc(fields.toString())
                + area.esriQueryForm();
        final JSONObject o = new JSONObject(Http.post(NRI + "/query", form));
        if (o.has("error"))
            throw new java.io.IOException("service error: " + o.optJSONObject("error"));
        final JSONArray feats = o.optJSONArray("features");
        if (feats == null)
            throw new java.io.IOException("no features in the answer");
        final List<JSONObject> inside = new ArrayList<>(), touching = new ArrayList<>();
        for (int i = 0; i < feats.length(); i++) {
            final JSONObject f = feats.optJSONObject(i);
            final JSONObject a = f == null ? null : f.optJSONObject("attributes");
            if (a == null)
                continue;
            touching.add(a);
            final JSONObject c = f.optJSONObject("centroid");
            if (c != null && area.contains(c.optDouble("y"), c.optDouble("x")))
                inside.add(a);
        }
        if (touching.isEmpty()) {
            final Result r = new Result("Not published here", null);
            r.notPublishedHere = true;
            return r.line("FEMA rates census tracts in the United States and its territories.");
        }
        final List<JSONObject> tracts = inside.isEmpty() ? touching : inside;

        final List<Rated> rated = new ArrayList<>();
        for (String[] h : HAZARDS) {
            final Rated x = rate(tracts, h[0] + "_RISKR", h[1]);
            if (x != null)
                rated.add(x);
        }
        Collections.sort(rated, new Comparator<Rated>() {
            @Override
            public int compare(Rated a, Rated b) {
                return Double.compare(b.score, a.score);
            }
        });
        final List<Rated> top = new ArrayList<>();
        for (Rated x : rated)
            if (top.size() < 5 && (x.typical >= 2 || x.worst >= 3))
                top.add(x);

        final StringBuilder head = new StringBuilder();
        for (int i = 0; i < Math.min(3, top.size()); i++)
            head.append(i == 0 ? "" : ", ").append(top.get(i).name);
        final Result r = new Result(top.isEmpty() ? "Nothing rated above relatively low" : head.toString(),
                top.isEmpty() ? null : "highest: " + top.get(0).name.toLowerCase(java.util.Locale.US));
        if (top.isEmpty())
            r.line("FEMA rates no natural hazard above relatively low here.");
        else {
            r.line(GROUP + "Highest rated hazards here");
            for (Rated x : top)
                r.line("  " + x.describe());
        }
        final Rated overall = rate(tracts, "RISK_RATNG", "Overall risk");
        if (overall != null)
            r.line(overall.describe());
        final Rated resilience = rate(tracts, "RESL_RATNG", "Community resilience");
        if (resilience != null)
            r.line(resilience.describe());
        return r;
    }

    /** One hazard over the tracts, weighted by people: its usual rating and its worst. */
    private static Rated rate(List<JSONObject> tracts, String field, String name) {
        final double[] weight = new double[RATINGS.length];
        double total = 0;
        int worst = -1;
        for (JSONObject a : tracts) {
            final int k = index(a.optString(field, ""));
            if (k < 0)
                continue;
            final double w = Math.max(1d, a.optDouble("POPULATION", 0));
            weight[k] += w;
            total += w;
            worst = Math.max(worst, k);
        }
        if (total <= 0)
            return null;
        int typical = 0;
        double score = 0;
        for (int k = 0; k < RATINGS.length; k++) {
            if (weight[k] > weight[typical])
                typical = k;
            score += k * weight[k] / total;
        }
        return new Rated(name, typical, worst, score);
    }

    private static int index(String rating) {
        for (int k = 0; k < RATINGS.length; k++)
            if (RATINGS[k].equalsIgnoreCase(rating.trim()))
                return k;
        return -1;
    }

    private static final class Rated {
        final String name;
        final int typical, worst;
        final double score;

        Rated(String name, int typical, int worst, double score) {
            this.name = name;
            this.typical = typical;
            this.worst = worst;
            this.score = score;
        }

        /** {@code Earthquake: Relatively High (Very High in parts)}. */
        String describe() {
            return name + ": " + RATINGS[typical]
                    + (worst > typical ? " (" + RATINGS[worst] + " in parts)" : "");
        }
    }
}
