package com.atakmap.android.sitrep.section;

import com.atakmap.android.sitrep.Area;
import com.atakmap.android.sitrep.Units;
import com.atakmap.android.sitrep.net.Esri;
import com.atakmap.android.sitrep.net.Http;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Places that matter for people who need help (PLAN decision 9; operator,
 * 2026-11-13: "a toggle for vulnerable folks"): hospitals, schools, nursing and care
 * homes, dialysis, shelters. Each category is a switch on the report page that puts
 * its places on the map as a read-only feature layer. Sources per
 * NOTES-sitrep-poi-sources.md. Hospitals and shelters count within Nearby too: the
 * nearest ER or shelter is often outside the area.
 */
public final class Places extends Section {

    /** Category key, name on the switch, letter on the map icon, icon color. */
    public static final String[][] CATEGORIES = {
            { "hospital", "Hospitals", "H", "#D32F2F" },
            { "school", "Schools", "S", "#F9A825" },
            { "care", "Nursing and care homes", "N", "#7B1FA2" },
            { "dialysis", "Dialysis", "D", "#1565C0" },
            { "shelter", "Shelters", "+", "#2E7D32" },
    };

    static final String HOSPITALS = "https://mapdepot.takwerx.org/iwi/hospitals/hospitals.json.gz";
    static final String SCHOOLS = "https://carto.nationalmap.gov/arcgis/rest/services/structures/MapServer/23";
    static final String CDPH = "https://services5.arcgis.com/FlidZxdI0LGC9vAw/arcgis/rest/services/CDPH_LC_Facilities_Data/FeatureServer/0";
    static final String NSS = "https://gis.fema.gov/arcgis/rest/services/NSS/FEMA_NSS/FeatureServer/5";
    static final String NSS_OPEN = "https://gis.fema.gov/arcgis/rest/services/NSS/OpenShelters/FeatureServer/0";

    public Places() {
        super("places", "Places", false,
                "Hospitals: takwerx IWI pack (CMS, HHS, HIFLD, state lists, FAA). Schools: USGS National Map. "
                        + "Care homes and dialysis: California CDPH licensed facilities. Shelters: FEMA National Shelter System.");
    }

    @Override
    public Result fetch(Area area) throws Exception {
        return fetch(area, 0);
    }

    @Override
    public Result fetch(Area area, int nearbyMiles) throws Exception {
        final double reach = nearbyMiles * 1609.344;
        final Map<String, List<JSONObject>> found = new LinkedHashMap<>();
        for (String[] c : CATEGORIES)
            found.put(c[0], new ArrayList<JSONObject>());
        final List<String> missing = new ArrayList<>();
        int failures = 0;

        // Hospitals: our pack, filtered here.
        try {
            final JSONArray all = new JSONObject(Http.getGzip(HOSPITALS)).optJSONArray("hospitals");
            for (int i = 0; all != null && i < all.length(); i++) {
                final JSONObject h = all.optJSONObject(i);
                if (h == null)
                    continue;
                final double lat = h.optDouble("lat", Double.NaN), lon = h.optDouble("lon", Double.NaN);
                if (!(Math.abs(lat) <= 90) || !(Math.abs(lon) <= 180))
                    continue;
                if (lat < area.south - 1 || lat > area.north + 1 || lon < area.west - 1.2 || lon > area.east + 1.2)
                    continue;
                final double d = area.distanceMeters(lat, lon);
                if (d > reach)
                    continue;
                final List<String> info = new ArrayList<>();
                info.add(h.optString("city", "") + ", " + h.optString("state", ""));
                info.add("yes".equals(h.optString("er")) ? "Emergency room." : "No emergency room listed.");
                if (h.optInt("trauma", 0) > 0)
                    info.add("Trauma level " + roman(h.optInt("trauma")) + ".");
                if (h.optInt("ped", 0) > 0)
                    info.add("Pediatric trauma level " + roman(h.optInt("ped")) + ".");
                if (h.has("burn"))
                    info.add("Burn center.");
                if (h.optJSONObject("pad") != null)
                    info.add("Helipad " + h.optJSONObject("pad").optString("id", "") + ".");
                found.get("hospital").add(point("hospital", h.optString("name"), lat, lon, d, area, info));
            }
        } catch (Exception e) {
            failures++;
            missing.add("hospitals");
        }

        // Schools: USGS National Map structures, public and private.
        try {
            for (JSONObject a : Esri.points(SCHOOLS, "1=1", area, "name,address,city,state", 0)) {
                final List<String> info = new ArrayList<>();
                info.add(join(a.optString("address", ""), a.optString("city", ""), a.optString("state", "")));
                found.get("school").add(point("school", a.optString("name"), a.optDouble("_lat"), a.optDouble("_lon"), 0, area, info));
            }
        } catch (Exception e) {
            failures++;
            missing.add("schools");
        }

        // Care homes and dialysis: California's licensed facilities. Never the
        // administrator's name or email, which the layer also carries.
        boolean caCovered = false;
        if (area.south < 42.1 && area.north > 32.4 && area.west < -114 && area.east > -124.5) {
            try {
                for (JSONObject a : Esri.points(CDPH, "FAC_STATUS_TYPE_CODE='OPEN' AND FAC_TYPE_CODE IN "
                        + "('SNF','ICF','ICFDD','ICFDDH','ICFDDN','ICFDDCN','CLHF','HOFA','CDC','ESRD','ESRD/CDC')",
                        area, "FACNAME,FAC_TYPE_CODE,FAC_FDR,CAPACITY,ADDRESS,CITY,CONTACT_PHONE_NUMBER", 0)) {
                    caCovered = true;
                    final String type = a.optString("FAC_TYPE_CODE", "");
                    final String cat = type.contains("CDC") || type.startsWith("ESRD") ? "dialysis" : "care";
                    final List<String> info = new ArrayList<>();
                    info.add(capital(a.optString("FAC_FDR", "")));
                    if (a.optInt("CAPACITY", 0) > 0)
                        info.add(a.optInt("CAPACITY") + " beds.");
                    info.add(join(capital(a.optString("ADDRESS", "")), capital(a.optString("CITY", "")), "CA"));
                    final String ph = a.optString("CONTACT_PHONE_NUMBER", "");
                    if (ph.matches("\\(\\d{3}\\) \\d{3}-\\d{4}"))
                        info.add(ph);
                    found.get(cat).add(point(cat, capital(a.optString("FACNAME")), a.optDouble("_lat"),
                            a.optDouble("_lon"), 0, area, info));
                }
                caCovered = true;
            } catch (Exception e) {
                failures++;
                missing.add("care homes");
            }
        }

        // Shelters: designated, and open now. Never the organization contacts.
        try {
            final java.util.Set<String> open = new java.util.HashSet<>();
            for (JSONObject a : Esri.points(NSS_OPEN, "1=1", area, "shelter_id,total_population,evacuation_capacity", nearbyMiles))
                open.add(a.optString("shelter_id", ""));
            for (JSONObject a : Esri.points(NSS, "1=1", area, "shelter_id,shelter_name,address_1,city,state,"
                    + "evacuation_capacity,post_impact_capacity,wheelchair_accessible,generator_onsite,pet_accommodations_desc",
                    nearbyMiles)) {
                final double d = area.distanceMeters(a.optDouble("_lat"), a.optDouble("_lon"));
                final boolean isOpen = open.contains(a.optString("shelter_id", "-"));
                final List<String> info = new ArrayList<>();
                info.add(isOpen ? "OPEN NOW." : "Designated shelter, not open now.");
                if (a.optInt("evacuation_capacity", 0) > 0)
                    info.add("Evacuation capacity " + a.optInt("evacuation_capacity") + ".");
                if ("YES".equals(a.optString("wheelchair_accessible")))
                    info.add("Wheelchair accessible.");
                if ("YES".equals(a.optString("generator_onsite")))
                    info.add("Generator on site.");
                final String pets = a.optString("pet_accommodations_desc", "").trim();
                if (!pets.isEmpty())
                    info.add("Pets: " + pets + ".");
                info.add(join(a.optString("address_1", ""), capital(a.optString("city", "")), a.optString("state", "")));
                found.get("shelter").add(point("shelter", (isOpen ? "OPEN: " : "") + a.optString("shelter_name"),
                        a.optDouble("_lat"), a.optDouble("_lon"), d, area, info));
            }
        } catch (Exception e) {
            failures++;
            missing.add("shelters");
        }
        if (failures >= 3 && missing.contains("hospitals") && missing.contains("schools") && missing.contains("shelters"))
            throw new java.io.IOException("no place source answered");

        final JSONArray points = new JSONArray();
        final StringBuilder head = new StringBuilder();
        int total = 0;
        for (String[] c : CATEGORIES) {
            final List<JSONObject> l = found.get(c[0]);
            Collections.sort(l, new Comparator<JSONObject>() {
                @Override
                public int compare(JSONObject a, JSONObject b) {
                    return Double.compare(a.optDouble("_d", 0), b.optDouble("_d", 0));
                }
            });
            total += l.size();
            if (!l.isEmpty() && head.length() < 70)
                head.append(head.length() == 0 ? "" : ", ").append(l.size()).append(' ')
                        .append(c[1].toLowerCase(Locale.US));
            for (JSONObject p : l)
                if (points.length() < 3000)
                    points.put(p);
        }
        final Result r = new Result(total == 0 ? "None found" : head.toString(), null);
        r.points = points;
        for (String[] c : CATEGORIES) {
            final List<JSONObject> l = found.get(c[0]);
            if (l.isEmpty())
                continue;
            r.line(GROUP + c[1] + ": " + l.size());
            int shown = 0;
            for (JSONObject p : l) {
                if (++shown > 25) {
                    r.line("  ... and " + (l.size() - 25) + " more, nearest first. Switch them on to see all on the map.");
                    break;
                }
                final double d = p.optDouble("_d", 0);
                r.line("  " + p.optString("n") + (d == 0 ? "" : ", " + Units.distance(d) + " "
                        + area.directionTo(p.optDouble("lat"), p.optDouble("lon"))));
            }
        }
        if (!caCovered)
            r.line("Nursing homes, care homes and dialysis are listed for California only until the national pack is built.");
        if (!missing.isEmpty())
            r.line("Did not answer: " + android.text.TextUtils.join(", ", missing) + ". Tap Refresh to try again.");
        return r;
    }

    private static JSONObject point(String c, String name, double lat, double lon, double d, Area area,
            List<String> info) throws Exception {
        final JSONArray i = new JSONArray();
        for (String s : info)
            if (s != null && !s.replaceAll("[ ,.]", "").isEmpty())
                i.put(s);
        if (d > 0)
            i.put(Units.distance(d) + " " + area.directionTo(lat, lon) + " of the area.");
        return new JSONObject().put("c", c).put("n", name == null || name.isEmpty() ? "Unnamed" : name.trim())
                .put("lat", lat).put("lon", lon).put("_d", d).put("i", i);
    }

    private static String join(String... parts) {
        final StringBuilder b = new StringBuilder();
        for (String p : parts)
            if (p != null && !p.trim().isEmpty() && !"null".equals(p))
                b.append(b.length() == 0 ? "" : ", ").append(p.trim());
        return b.toString();
    }

    private static String capital(String s) {
        if (s == null || "null".equals(s))
            return "";
        final StringBuilder b = new StringBuilder();
        for (String w : s.toLowerCase(Locale.US).split(" "))
            if (!w.isEmpty())
                b.append(b.length() == 0 ? "" : " ").append(Character.toUpperCase(w.charAt(0))).append(w.substring(1));
        return b.toString();
    }

    private static String roman(int n) {
        final String[] r = { "", "I", "II", "III", "IV", "V" };
        return n >= 1 && n <= 5 ? r[n] : String.valueOf(n);
    }
}
