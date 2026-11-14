package com.atakmap.android.sitrep.section;

import com.atakmap.android.sitrep.Area;
import com.atakmap.android.sitrep.net.Esri;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Where the area is and whose it is (PLAN 4.1): counties and cities, the dispatch
 * center and GACC, the PSA, the land managers (NIFC jurisdictional units, from
 * PAD-US), and in California who protects it from fire (DPA). The land managers are
 * listed, not given as a share of the area: the service cannot cut a unit to the area.
 */
public final class Where extends Section {

    static final String NIFC = "https://services3.arcgis.com/T4QMspbfLg3qTGWY/arcgis/rest/services/";
    static final String UNITS = NIFC + "DMP_JurisdictionalUnits_Public/FeatureServer/0";
    static final String DISPATCH = NIFC + "DMP_National_Dispatch_Boundaries_Public/FeatureServer/0";
    static final String PSA = NIFC + "DMP_Predictive_Service_Area__PSA_Boundaries_Public/FeatureServer/0";
    static final String DPA = NIFC + "CA_WF_Direct_Protection_Areas_Public/FeatureServer/0";
    static final String CITIES = "https://tigerweb.geo.census.gov/arcgis/rest/services/TIGERweb/"
            + "Places_CouSub_ConCity_SubMCD/MapServer/4";

    public Where() {
        super("where", "Where and whose", false,
                "Census TIGERweb; NIFC dispatch, PSA and jurisdictional unit boundaries (PAD-US); CAL FIRE Direct Protection Areas.");
    }

    @Override
    public Result fetch(Area area) throws Exception {
        final Counties counties = Counties.of(area);
        final List<String> countyNames = new ArrayList<>();
        for (String[] c : counties.list)
            countyNames.add(c[2] + " County, " + c[0]);

        final Set<String> cities = new LinkedHashSet<>();
        for (JSONObject a : Esri.attributes(CITIES, "1=1", area, "NAME"))
            cities.add(text(a, "NAME"));
        cities.remove(null);

        final Set<String> dispatch = new LinkedHashSet<>(), gacc = new LinkedHashSet<>();
        for (JSONObject a : Esri.attributes(DISPATCH, "1=1", area, "DispName,DispLocation,ContactPhone,GACCName,GACCAbbreviation")) {
            final String name = text(a, "DispName");
            if (name == null)
                continue;
            final String loc = text(a, "DispLocation"), phone = phone(text(a, "ContactPhone"));
            dispatch.add(name + (loc == null ? "" : ", " + loc) + (phone == null ? "" : ", " + phone));
            final String g = text(a, "GACCName");
            if (g != null)
                gacc.add(g + (text(a, "GACCAbbreviation") == null ? "" : " (" + text(a, "GACCAbbreviation") + ")"));
        }

        final Set<String> psas = new LinkedHashSet<>();
        for (JSONObject a : Esri.attributes(PSA, "1=1", area, "PSANAME,PSANationalCode")) {
            final String n = text(a, "PSANAME");
            if (n != null)
                psas.add(n + (text(a, "PSANationalCode") == null ? "" : " (" + text(a, "PSANationalCode") + ")"));
        }

        final Map<String, Set<String>> owners = new LinkedHashMap<>();
        for (JSONObject a : Esri.attributes(UNITS, "1=1", area, "JurisdictionalUnitName,LandownerKind,LandownerCategory")) {
            final String cat = owner(text(a, "LandownerKind"), text(a, "LandownerCategory"));
            final String n = text(a, "JurisdictionalUnitName");
            Set<String> s = owners.get(cat);
            if (s == null) {
                s = new LinkedHashSet<>();
                owners.put(cat, s);
            }
            if (n != null)
                s.add(n);
        }

        final Map<String, Set<String>> protection = new LinkedHashMap<>();
        if (counties.states.contains("CA"))
            for (JSONObject a : Esri.attributes(DPA, "1=1", area, "DPA_AGENCY,NWCG_UNITID")) {
                final String ag = dpa(text(a, "DPA_AGENCY"));
                Set<String> s = protection.get(ag);
                if (s == null) {
                    s = new LinkedHashSet<>();
                    protection.put(ag, s);
                }
                if (text(a, "NWCG_UNITID") != null)
                    s.add(text(a, "NWCG_UNITID"));
            }

        final StringBuilder head = new StringBuilder();
        head.append(countyNames.isEmpty() ? "Outside US counties" : join(countyNames, 2));
        if (!dispatch.isEmpty())
            head.append("; ").append(first(dispatch).split(",")[0]);
        final Result r = new Result(head.toString(), countyNames.isEmpty() ? null : countyNames.get(0));

        r.line(GROUP + "Counties and cities: " + countyNames.size() + " and " + cities.size());
        for (String c : countyNames)
            r.line("  " + c);
        int shown = 0;
        for (String c : cities) {
            if (++shown > 15) {
                r.line("  ... and " + (cities.size() - 15) + " more cities.");
                break;
            }
            r.line("  " + c);
        }
        if (cities.isEmpty())
            r.line("  No incorporated city; unincorporated county land.");
        if (!dispatch.isEmpty()) {
            r.line(GROUP + "Dispatch: " + first(dispatch).split(",")[0]);
            for (String d : dispatch)
                r.line("  " + d);
            for (String g : gacc)
                r.line("  GACC: " + g);
        }
        if (!psas.isEmpty()) {
            r.line(GROUP + "Predictive service area: " + first(psas));
            for (String p : psas)
                r.line("  " + p);
        }
        if (!protection.isEmpty()) {
            r.line(GROUP + "Fire protection (DPA): " + join(new ArrayList<>(protection.keySet()), 3));
            for (Map.Entry<String, Set<String>> e : protection.entrySet())
                r.line("  " + e.getKey() + (e.getValue().isEmpty() ? "" : ": " + join(new ArrayList<>(e.getValue()), 4)));
        }
        if (!owners.isEmpty()) {
            r.line(GROUP + "Land managers: " + owners.size());
            for (Map.Entry<String, Set<String>> e : owners.entrySet()) {
                final List<String> names = new ArrayList<>(e.getValue());
                r.line("  " + e.getKey() + (names.isEmpty() ? "" : ": " + join(names, 4)
                        + (names.size() > 4 ? " and " + (names.size() - 4) + " more" : "")));
            }
            r.line("  Land not listed here is private.");
        }
        return r;
    }

    /** {@code USFS}, {@code State}, {@code County} ... in words a crew uses. */
    private static String owner(String kind, String cat) {
        if (cat == null)
            return kind == null ? "Other" : kind;
        switch (cat) {
            case "USFS":
                return "Forest Service";
            case "BLM":
                return "BLM";
            case "NPS":
                return "Park Service";
            case "FWS":
                return "Fish and Wildlife Service";
            case "BIA":
            case "Tribal":
            case "ANCSA":
                return "Tribal";
            case "DOD":
                return "Military";
            case "OthFed":
                return "Other federal";
            case "State":
                return "State";
            case "County":
                return "County";
            case "City":
                return "City";
            case "OthLocal":
                return "Other local";
            default:
                return cat;
        }
    }

    private static String dpa(String a) {
        if (a == null)
            return "Not given";
        switch (a) {
            case "CDF":
                return "CAL FIRE";
            case "LOCAL":
                return "Local";
            case "USFS":
                return "Forest Service";
            case "CONTRACT COUNTY":
                return "Contract county";
            default:
                return a;
        }
    }

    private static String phone(String p) {
        if (p == null)
            return null;
        final String d = p.replaceAll("\\D", "");
        return d.length() == 10 ? "(" + d.substring(0, 3) + ") " + d.substring(3, 6) + "-" + d.substring(6) : null;
    }

    private static String text(JSONObject a, String k) {
        if (a.isNull(k))
            return null;
        final String s = a.optString(k, "").replaceAll("\\s+", " ").trim();
        return s.isEmpty() ? null : s.length() > 80 ? s.substring(0, 80) : s;
    }

    private static String first(Set<String> s) {
        return s.iterator().next();
    }

    private static String join(List<String> l, int max) {
        final StringBuilder b = new StringBuilder();
        for (int i = 0; i < l.size() && i < max; i++)
            b.append(i == 0 ? "" : ", ").append(l.get(i));
        return b.toString();
    }
}
