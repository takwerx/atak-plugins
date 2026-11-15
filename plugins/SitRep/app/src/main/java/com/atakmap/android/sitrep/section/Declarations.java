package com.atakmap.android.sitrep.section;

import com.atakmap.android.sitrep.Area;
import com.atakmap.android.sitrep.net.Esri;
import com.atakmap.android.sitrep.net.Http;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Federal disaster declarations for the counties the area touches, from OpenFEMA
 * (PLAN 4.1): the last 90 days, then the rest of the last three years.
 */
public final class Declarations extends Section {

    static final String API = "https://www.fema.gov/api/open/v2/DisasterDeclarationsSummaries";

    public Declarations() {
        super("declarations", "Disaster declarations", false,
                "OpenFEMA Disaster Declarations Summaries: major disasters (DR), emergencies (EM) and fire management grants (FM), by county.");
    }

    @Override
    public Result fetch(Area area) throws Exception {
        final Counties counties = Counties.of(area);
        if (counties.list.isEmpty()) {
            final Result r = new Result("Not published here", null);
            r.notPublishedHere = true;
            return r.line("FEMA declarations cover the United States and its territories.");
        }
        final long since = System.currentTimeMillis() - 3L * 365 * 24 * 3600 * 1000;
        final String sinceIso = new java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(new java.util.Date(since));
        // One declaration lists each county once: key by disaster number.
        final Map<Integer, JSONObject> found = new LinkedHashMap<>();
        for (String[] c : counties.list) {
            final String filter = "state eq '" + c[0] + "' and fipsCountyCode eq '" + c[1].substring(2)
                    + "' and declarationDate ge '" + sinceIso + "'";
            final String url = API + "?$filter=" + Esri.enc(filter)
                    + "&$orderby=" + Esri.enc("declarationDate desc") + "&$top=50"
                    + "&$select=" + Esri.enc("disasterNumber,declarationType,declarationTitle,incidentType,"
                            + "declarationDate,incidentBeginDate,incidentEndDate,designatedArea");
            final JSONArray rows = new JSONObject(Http.get(url)).optJSONArray("DisasterDeclarationsSummaries");
            if (rows == null)
                throw new java.io.IOException("no rows in the OpenFEMA answer");
            for (int i = 0; i < rows.length(); i++) {
                final JSONObject d = rows.optJSONObject(i);
                if (d != null && !found.containsKey(d.optInt("disasterNumber")))
                    found.put(d.optInt("disasterNumber"), d);
            }
        }
        if (found.isEmpty())
            return new Result("None in 3 years", null).line("No federal disaster declaration for these counties since " + sinceIso + ".");
        // Recent by declaration date, not "open": FEMA leaves incidentEndDate empty on
        // fire grants (FM) long after the fire is out (Palisades, 2026-11-13).
        final String recentIso = new java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
                .format(new java.util.Date(System.currentTimeMillis() - 90L * 24 * 3600 * 1000));
        final List<JSONObject> recent = new ArrayList<>(), older = new ArrayList<>();
        for (JSONObject d : found.values())
            (d.optString("declarationDate", "").compareTo(recentIso) >= 0 ? recent : older).add(d);
        final Result r = new Result((recent.isEmpty() ? "None in 90 days" : recent.size() + " in the last 90 days")
                + "; " + found.size() + " in 3 years", recent.isEmpty() ? null : recent.size() + " recent declaration");
        if (!recent.isEmpty()) {
            r.line(GROUP + "Last 90 days: " + recent.size());
            for (JSONObject d : recent)
                r.line("  " + describe(d));
        }
        if (!older.isEmpty()) {
            r.line(GROUP + "Earlier, last 3 years: " + older.size());
            for (JSONObject d : older)
                r.line("  " + describe(d));
        }
        return r;
    }

    /** {@code FM-5605 Canyon Fire (Fire), declared 2025-08-08}. */
    private static String describe(JSONObject d) {
        final String title = d.optString("declarationTitle", "").trim();
        return d.optString("declarationType", "") + "-" + d.optInt("disasterNumber") + " "
                + (title.isEmpty() ? "" : capitalize(title)) + " (" + d.optString("incidentType", "") + "), declared "
                + d.optString("declarationDate", "").split("T")[0];
    }

    private static String capitalize(String s) {
        final StringBuilder b = new StringBuilder();
        for (String w : s.toLowerCase(java.util.Locale.US).split(" "))
            if (!w.isEmpty())
                b.append(b.length() == 0 ? "" : " ").append(Character.toUpperCase(w.charAt(0))).append(w.substring(1));
        return b.length() > 80 ? b.substring(0, 80) : b.toString();
    }
}
