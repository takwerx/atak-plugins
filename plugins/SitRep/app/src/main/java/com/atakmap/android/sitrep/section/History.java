package com.atakmap.android.sitrep.section;

import com.atakmap.android.sitrep.Area;
import com.atakmap.android.sitrep.Units;
import com.atakmap.android.sitrep.net.Esri;
import com.atakmap.android.sitrep.net.Http;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * What has burned here before, and where firefighters were hurt or killed nearby
 * (PLAN 4.13). Two NIFC services:
 *
 * <ul>
 * <li>InterAgency Fire Perimeter History: every wildfire perimeter that reaches into
 * the area. One fire is often in it twice (a final perimeter and an older copy), so
 * fires are merged by name and year and keep the largest acreage. Prescribed burns are
 * left out. The acres are the whole fire, not the part inside the area.</li>
 * <li>The Wildland Fire Lessons Learned Center's incident review database: fatality
 * and entrapment reports in the area or within Nearby, each with its report on
 * lessons.wildfire.gov.</li>
 * </ul>
 */
public final class History extends Section {

    static final String PERIMETERS = "https://services3.arcgis.com/T4QMspbfLg3qTGWY/arcgis/rest/services/InterAgencyFirePerimeterHistory_All_Years_View/FeatureServer/0";
    static final String REVIEWS = "https://services3.arcgis.com/T4QMspbfLg3qTGWY/arcgis/rest/services/HistomapPublicView/FeatureServer/0";
    /** Only report links on the Lessons Learned Center's own site are offered as links. */
    public static final String LESSONS = "https://lessons.wildfire.gov/";

    /** How many fires the list shows, newest first; the largest is always named too. */
    private static final int LIST = 15;

    public History() {
        super("history", "History", false,
                "NIFC InterAgency Fire Perimeter History (wildfires, all years); Wildland Fire Lessons Learned Center incident reviews (fatalities and entrapments).");
        // The burn areas themselves are Feature Layer's Fire History layer (operator,
        // 2026-11-14). Feature Layer answers SHOW only in its debug build, so no
        // action: loaded, this is a line of text; not installed, a Get button.
        seeIn = "See earlier burn areas on the map in Feature Layer: switch on Fire History.";
        openName = "Feature Layer";
        openPackage = "com.atakmap.android.featurelayer.plugin";
        openAction = null;
    }

    @Override
    public Result fetch(Area area) throws Exception {
        return fetch(area, 0);
    }

    @Override
    public Result fetch(Area area, int nearbyMiles) throws Exception {
        final List<Fire> fires = fires(area);
        final List<JSONObject> reviews = Esri.points(REVIEWS,
                "FatalityCount > 0 OR EntrapmentType IS NOT NULL", area,
                "LLIncidentName,IncidentName,Year,FatalityCount,InjuryCount,EntrapmentType,Link1",
                nearbyMiles);
        for (JSONObject v : reviews)
            v.put("_d", area.distanceMeters(v.optDouble("_lat"), v.optDouble("_lon")));
        Collections.sort(reviews, new Comparator<JSONObject>() {
            @Override
            public int compare(JSONObject a, JSONObject b) {
                return Double.compare(a.optDouble("_d"), b.optDouble("_d"));
            }
        });

        final String within = nearbyMiles > 0 ? " in the area or within " + nearbyMiles + " mi" : " in the area";
        final StringBuilder value = new StringBuilder();
        if (fires.isEmpty())
            value.append("No recorded wildfire");
        else
            value.append(fires.size()).append(fires.size() == 1 ? " fire" : " fires")
                    .append(", last ").append(fires.get(0).label());
        int deaths = 0;
        for (JSONObject v : reviews)
            deaths += Math.max(0, v.optInt("FatalityCount", 0));
        if (!reviews.isEmpty())
            value.append("; ").append(reviews.size()).append(reviews.size() == 1 ? " fatality or entrapment" : " fatalities or entrapments");
        final Result r = new Result(value.toString(),
                fires.isEmpty() ? null : "last fire " + fires.get(0).year);

        if (fires.isEmpty())
            r.line("No wildfire perimeter on record reaches into the area.");
        else {
            Fire largest = fires.get(0);
            for (Fire f : fires)
                if (f.acres > largest.acres)
                    largest = f;
            r.line("Largest: " + largest.label() + ", " + acres(largest.acres) + ".");
            r.line(GROUP + "Wildfires that reached the area: " + fires.size());
            for (int i = 0; i < Math.min(LIST, fires.size()); i++)
                r.line("  " + fires.get(i).year + " " + fires.get(i).name + ", " + acres(fires.get(i).acres));
            if (fires.size() > LIST)
                r.line("  and " + (fires.size() - LIST) + " earlier");
            r.line("Acres are the whole fire, not only the part in the area. Prescribed burns are not counted.");
        }

        if (reviews.isEmpty())
            r.line("No firefighter fatality or entrapment report" + within + ".");
        else {
            r.line(GROUP + "Fatalities and entrapments" + within + ": " + reviews.size()
                    + (deaths > 0 ? " (" + deaths + (deaths == 1 ? " death)" : " deaths)") : ""));
            final JSONArray points = new JSONArray();
            for (JSONObject v : reviews) {
                final String name = v.optString("LLIncidentName", "").trim().isEmpty()
                        ? v.optString("IncidentName", "Unnamed").trim() : v.optString("LLIncidentName").trim();
                final double d = v.optDouble("_d");
                final String where = d == 0 ? "in the area"
                        : Units.distance(d) + " " + area.directionTo(v.optDouble("_lat"), v.optDouble("_lon"));
                final String year = v.optString("Year", "").trim();
                String shown = name.replaceAll("\\s+", " ");
                if (!year.isEmpty() && shown.endsWith(" " + year))   // "Euclid Fire Entrapment 1981"
                    shown = shown.substring(0, shown.length() - year.length() - 1);
                final String title = (year.isEmpty() ? "" : year + " ") + shown;
                r.line("  " + title + ", " + where);
                // On the map as category "lessons", drawn by the places layer; the
                // row in the report goes there and offers the review (operator,
                // 2026-11-14).
                final JSONArray info = new JSONArray().put(where);
                final int dead = v.optInt("FatalityCount", 0), hurt = v.optInt("InjuryCount", 0);
                info.put(dead + (dead == 1 ? " fatality, " : " fatalities, ") + hurt + (hurt == 1 ? " injury" : " injuries"));
                final String trap = v.optString("EntrapmentType", "").trim();
                if (!trap.isEmpty() && !"null".equals(trap))
                    info.put("Entrapment: " + trap.replace('_', ' ').replace(",", ", ").toLowerCase(Locale.US));
                final JSONObject pt = new JSONObject().put("c", "lessons").put("n", title)
                        .put("lat", v.optDouble("_lat")).put("lon", v.optDouble("_lon")).put("i", info);
                final String url = v.optString("Link1", "").trim();
                if (url.startsWith(LESSONS))
                    pt.put("u", url);
                points.put(pt);
            }
            r.points = points;
        }
        return r;
    }

    /** Wildfire perimeters reaching the area, merged by name and year, newest first. */
    private static List<Fire> fires(Area area) throws Exception {
        final int thisYear = Calendar.getInstance().get(Calendar.YEAR);
        final String where = "FIRE_YEAR_INT >= 1850 AND FIRE_YEAR_INT <= " + thisYear
                + " AND (FEATURE_CA IS NULL OR FEATURE_CA <> 'Prescribed Fire')";
        final String form = "where=" + Esri.enc(where)
                + "&f=json&returnGeometry=false&outFields=" + Esri.enc("INCIDENT,GIS_ACRES,FIRE_YEAR_INT")
                + "&orderByFields=" + Esri.enc("FIRE_YEAR_INT DESC") + "&resultRecordCount=2000"
                + area.esriQueryForm();
        final JSONObject o = new JSONObject(Http.post(PERIMETERS + "/query", form));
        if (o.has("error"))
            throw new java.io.IOException("service error: " + o.optJSONObject("error"));
        final JSONArray feats = o.optJSONArray("features");
        if (feats == null)
            throw new java.io.IOException("no features in the answer");
        final Map<String, Fire> byKey = new LinkedHashMap<>();
        for (int i = 0; i < feats.length(); i++) {
            final JSONObject f = feats.optJSONObject(i);
            final JSONObject a = f == null ? null : f.optJSONObject("attributes");
            if (a == null)
                continue;
            final String name = clean(a.optString("INCIDENT", ""));
            final int year = a.optInt("FIRE_YEAR_INT", 0);
            final double acres = a.optDouble("GIS_ACRES", 0);
            final String key = year + "|" + name.toUpperCase(Locale.US);
            final Fire old = byKey.get(key);
            if (old == null)
                byKey.put(key, new Fire(name, year, acres));
            else if (acres > old.acres)
                old.acres = acres;
        }
        // One fire under two names in one year ("Freeway" and "Freeway Complex",
        // 30,305 acres each): same first word, acres within 2 percent. The longer
        // name stays.
        final List<Fire> out = new ArrayList<>();
        for (Fire f : byKey.values()) {
            Fire same = null;
            for (Fire g : out)
                if (g.year == f.year && sameFire(g, f)) {
                    same = g;
                    break;
                }
            if (same == null)
                out.add(f);
            else {
                if (f.name.length() > same.name.length())
                    same.name = f.name;
                same.acres = Math.max(same.acres, f.acres);
            }
        }
        Collections.sort(out, new Comparator<Fire>() {
            @Override
            public int compare(Fire a, Fire b) {
                return a.year != b.year ? b.year - a.year : Double.compare(b.acres, a.acres);
            }
        });
        return out;
    }

    private static boolean sameFire(Fire a, Fire b) {
        if ("Unnamed".equals(a.name) || "Unnamed".equals(b.name))
            return false;
        final String fa = a.name.split(" ")[0], fb = b.name.split(" ")[0];
        if (!fa.equalsIgnoreCase(fb))
            return false;
        final double big = Math.max(a.acres, b.acres);
        return big > 0 && Math.abs(a.acres - b.acres) <= 0.02 * big;
    }

    /** Words kept in capitals: agency and jurisdiction tags, Roman numerals. */
    private static final java.util.Set<String> CAPS = new java.util.HashSet<>(java.util.Arrays.asList(
            "CDF", "BLM", "BIA", "NPS", "USFS", "NF", "WUI", "HFT", "II", "III", "IV", "VI", "VII", "VIII", "IX", "XI", "XII"));
    /** Responsibility-area tags (CAL FIRE's local, state, federal) that only split one fire in two. */
    private static final java.util.Set<String> TAGS = new java.util.HashSet<>(java.util.Arrays.asList("LRA", "SRA", "FRA"));

    /**
     * A fire name as a crew would say it: {@code FREEWAY_COMPLEX} becomes
     * {@code Freeway Complex}, {@code SKYLINE LRA} becomes {@code Skyline},
     * {@code MCKINLEY} becomes {@code McKinley}. A mixed-case name is kept as written.
     */
    static String clean(String raw) {
        String s = raw == null ? "" : raw.replace('_', ' ').replaceAll("\\s+", " ").trim();
        if (s.isEmpty() || "null".equals(s) || "unknown".equalsIgnoreCase(s))
            return "Unnamed";
        final String[] words = s.split(" ");
        final StringBuilder b = new StringBuilder();
        final boolean allCaps = s.equals(s.toUpperCase(Locale.US));
        for (int i = 0; i < words.length; i++) {
            final String w = words[i];
            final String up = w.toUpperCase(Locale.US);
            if (TAGS.contains(up) && words.length > 1)
                continue;
            String out = w;
            if (allCaps && !CAPS.contains(up) && w.length() > 0) {
                out = up.charAt(0) + w.substring(1).toLowerCase(Locale.US);
                if (out.length() > 2 && out.startsWith("Mc"))
                    out = "Mc" + Character.toUpperCase(out.charAt(2)) + out.substring(3);
            }
            b.append(b.length() == 0 ? "" : " ").append(out);
        }
        return b.length() == 0 ? "Unnamed" : b.toString();
    }

    private static String acres(double a) {
        return a < 1 ? "under 1 acre" : NumberFormat.getIntegerInstance(Locale.US).format(Math.round(a)) + " acres";
    }

    private static final class Fire {
        String name;
        final int year;
        double acres;

        Fire(String name, int year, double acres) {
            this.name = name;
            this.year = year;
            this.acres = acres;
        }

        String label() {
            return name + " " + year;
        }
    }
}
