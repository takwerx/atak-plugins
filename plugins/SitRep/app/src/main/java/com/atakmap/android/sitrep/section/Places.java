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
 * homes, dialysis, shelters, airports and heliports, and the Critical Infrastructure
 * layers: fire and police stations, power plants, battery storage, nuclear plants,
 * dams and chemical facilities. Each category is a switch on the report page that puts
 * its places on the map as a read-only feature layer. Sources per
 * NOTES-sitrep-poi-sources.md. Hospitals and shelters count within Nearby too: the
 * nearest ER or shelter is often outside the area.
 */
public final class Places extends Section {

    /** Category key, name on the switch, letter on the map icon, icon color. */
    public static final String[][] CATEGORIES = {
            { "hospital", "Hospitals", "H", "#D32F2F" },
            { "urgent", "Urgent care", "U", "#D32F2F" },
            { "school", "Schools", "S", "#F9A825" },
            { "care", "Nursing and care homes", "N", "#7B1FA2" },
            { "dialysis", "Dialysis", "D", "#1565C0" },
            { "shelter", "Shelters", "+", "#2E7D32" },
            { "animal", "Animal shelters", "A", "#6D4C41" },
            { "fair", "Fairgrounds and rodeo", "F", "#AD1457" },
            { "equestrian", "Equestrian centers", "E", "#5D4037" },
            { "pharmacy", "Pharmacies", "R", "#2E7D32" },
            { "grocery", "Groceries", "G", "#558B2F" },
            { "fuel", "Fuel", "F", "#37474F" },
            { "airport", "Airports", "A", "#263238" },
            { "heliport", "Heliports", "H", "#000000" },
            { "fire", "Fire stations", "F", "#C62828" },
            { "police", "Police", "P", "#1A237E" },
            { "military", "Military installations", "M", "#556B2F" },
            { "power", "Power plants", "E", "#EF6C00" },
            { "battery", "Battery storage (BESS)", "B", "#00897B" },
            { "nuclear", "Nuclear plants", "N", "#FBC02D" },
            { "dam", "Dams", "W", "#0277BD" },
            { "bridge", "Bridges", "B", "#546E7A" },
            { "crossing", "Railroad crossings", "X", "#212121" },
            { "rail", "Rail lines", "-", "#00E676" },
            { "transmission", "Transmission lines", "-", "#FFAB00" },
            { "hazmat", "Chemical facilities (RMP)", "!", "#F57C00" },
            // Filled by the 911 calls section, not by Places; drawn by the same layer.
            { "calls", "911 calls", "9", "#C62828" },
            // Filled by the History section: firefighter fatality and entrapment reviews.
            { "lessons", "Fatalities and entrapments", "!", "#B71C1C" },
    };

    static final String HOSPITALS = "https://mapdepot.takwerx.org/iwi/hospitals/hospitals.json.gz";
    static final String SCHOOLS = "https://carto.nationalmap.gov/arcgis/rest/services/structures/MapServer/23";
    static final String CDPH = "https://services5.arcgis.com/FlidZxdI0LGC9vAw/arcgis/rest/services/CDPH_LC_Facilities_Data/FeatureServer/0";
    static final String NSS = "https://gis.fema.gov/arcgis/rest/services/NSS/FEMA_NSS/FeatureServer/5";
    static final String AVIATION = "https://services.arcgis.com/xOi1kZaI0eWDREZv/arcgis/rest/services/NTAD_Aviation_Facilities/FeatureServer/0";
    static final String USGS = "https://carto.nationalmap.gov/arcgis/rest/services/structures/MapServer/";
    static final String CEC_PLANTS = "https://services3.arcgis.com/bWPjFyq029ChCGur/arcgis/rest/services/Power_Plant/FeatureServer/0";
    static final String EPA_PLANTS = "https://geodata.epa.gov/arcgis/rest/services/OEI/FRS_PowerPlants/MapServer/12";
    static final String NUCLEAR = "https://gis.fema.gov/arcgis/rest/services/Partner/Nuclear_Plant_Power_Reactor_Status/FeatureServer/0";
    static final String DAMS = "https://geospatial.sec.usace.army.mil/dls/rest/services/NID/National_Inventory_of_Dams_Public_Service/FeatureServer/0";
    static final String RMP = "https://services.arcgis.com/cJ9YHowT8TU7DUyn/arcgis/rest/services/FRS_INTERESTS_RMP/FeatureServer/0";
    static final String PACK = "https://mapdepot.takwerx.org/sitrep/places/";
    /** Most places kept per category, nearest first, so fuel cannot crowd out the rest. */
    static final int PER_CATEGORY = 500;
    static final String MILITARY = "https://services.arcgis.com/xOi1kZaI0eWDREZv/arcgis/rest/services/NTAD_Military_Bases/FeatureServer/0";
    static final String NTAD = "https://services.arcgis.com/xOi1kZaI0eWDREZv/arcgis/rest/services/";
    static final String CARE = "https://mapdepot.takwerx.org/sitrep/care/";
    static final String POWER = "https://mapdepot.takwerx.org/sitrep/power/";
    private static final int MAX_POWER_PATHS = 3_000;
    /** Categories drawn as lines, not icons. */
    public static boolean isLine(String category) {
        return "rail".equals(category) || "transmission".equals(category);
    }
    static final String NSS_OPEN = "https://gis.fema.gov/arcgis/rest/services/NSS/OpenShelters/FeatureServer/0";

    public Places() {
        // Live: Refresh updates it, so a fix to a source or our pack reaches saved
        // reports (the Corona Animal Shelter duplicate, 2026-11-13).
        super("places", "Places", true,
                "Hospitals: takwerx IWI pack (CMS, HHS, HIFLD, state lists, FAA). Schools: USGS National Map. "
                        + "Care homes and dialysis: California CDPH licensed facilities; elsewhere CMS certified nursing homes and dialysis (dialysis located by the Census geocoder or by phone match to Overture; about 1 in 11 could not be placed and is not listed). Shelters: FEMA National Shelter System. Airports and heliports: BTS NTAD Aviation Facilities (FAA data). "
                        + "Fire and police: USGS National Map. Military installations: DoD MIRTA via BTS NTAD. Power plants: CEC (California), EPA (elsewhere). "
                        + "Nuclear: FEMA/NRC. Dams: USACE National Inventory of Dams. Chemical: EPA RMP. "
                        + "Animal shelters, fairgrounds, equestrian centers, pharmacies, groceries, fuel and urgent care: "
                        + "Overture Maps Foundation places (CDLA-Permissive-2.0; Foursquare rows Apache-2.0). "
                        + "Transmission lines: (c) OpenStreetMap contributors (ODbL), via Overture Maps.");
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

        // Hospitals: our pack, filtered here. Within Nearby, plus the nearest trauma
        // center, Level I, pediatric trauma and burn center however far (IWI's rule):
        // a Level I 8.8 mi out of Corona was left off at a 5 mi Nearby (2026-11-13).
        try {
            final JSONArray all = new JSONObject(Http.getGzip(HOSPITALS)).optJSONArray("hospitals");
            // The nearest ER, then a ladder of trauma care, one rung per level: the
            // nearest of any level is often a Level IV, and the next step up was left
            // off (Kootenai Health, Level II, for Bayview, ID; Mercy Regional, Level
            // III in Durango, for Pagosa Springs, CO; operator 2026-11-14). One
            // hospital that is the nearest at several rungs is listed once.
            final String[] needs = { "er", "trauma", "level3", "level2", "level1", "ped", "burn" };
            final String[] needWords = { "Nearest emergency room", "Nearest trauma center",
                    "Nearest Level III or higher trauma center",
                    "Nearest Level II or higher trauma center", "Nearest Level I trauma center",
                    "Nearest pediatric trauma center", "Nearest burn center" };
            final JSONObject[] nearest = new JSONObject[needs.length];
            final double[] nearestD = new double[needs.length];
            java.util.Arrays.fill(nearestD, Double.MAX_VALUE);
            final List<JSONObject> within = new ArrayList<>();
            final List<JSONObject> ers = new ArrayList<>();
            for (int i = 0; all != null && i < all.length(); i++) {
                final JSONObject h = all.optJSONObject(i);
                if (h == null)
                    continue;
                final double lat = h.optDouble("lat", Double.NaN), lon = h.optDouble("lon", Double.NaN);
                if (!(Math.abs(lat) <= 90) || !(Math.abs(lon) <= 180))
                    continue;
                // A coarse box first: the nearest Level I can be a few hundred miles out.
                if (lat < area.south - 5 || lat > area.north + 5 || lon < area.west - 6 || lon > area.east + 6)
                    continue;
                final double d = area.distanceMeters(lat, lon);
                h.put("_d", d);
                if (d <= reach)
                    within.add(h);
                if (hasEr(h))
                    ers.add(h);
                final int level = h.optInt("trauma", 0);
                final boolean[] has = { hasEr(h), level > 0, level >= 1 && level <= 3,
                        level == 1 || level == 2, level == 1,
                        h.optInt("ped", 0) > 0, h.has("burn") };
                for (int k = 0; k < needs.length; k++)
                    if (has[k] && d < nearestD[k]) {
                        nearestD[k] = d;
                        nearest[k] = h;
                    }
            }
            final java.util.Map<String, List<String>> marks = new java.util.HashMap<>();
            for (int k = 0; k < needs.length; k++) {
                if (nearest[k] == null)
                    continue;
                final String id = nearest[k].optString("id", nearest[k].optString("name"));
                List<String> m = marks.get(id);
                if (m == null) {
                    m = new ArrayList<>();
                    marks.put(id, m);
                    if (!within.contains(nearest[k]))
                        within.add(nearest[k]);
                }
                m.add(needWords[k]);
            }
            // And the three nearest ERs, however far: the ladder alone skipped the
            // places between its rungs (operator, 2026-11-14: "you put other stuff 160
            // miles away, why would Durango be left off").
            java.util.Collections.sort(ers, new java.util.Comparator<JSONObject>() {
                @Override
                public int compare(JSONObject a, JSONObject b) {
                    return Double.compare(a.optDouble("_d"), b.optDouble("_d"));
                }
            });
            for (int k = 0; k < Math.min(3, ers.size()); k++)
                if (!within.contains(ers.get(k)))
                    within.add(ers.get(k));
            // Nearest first, as the list is read.
            java.util.Collections.sort(within, new java.util.Comparator<JSONObject>() {
                @Override
                public int compare(JSONObject a, JSONObject b) {
                    return Double.compare(a.optDouble("_d"), b.optDouble("_d"));
                }
            });
            // The highest rung a hospital holds says the rest: the nearest Level I that
            // is also the nearest Level II and III reads "Nearest Level I trauma center".
            for (List<String> m : marks.values()) {
                if (m.contains(needWords[4]) || m.contains(needWords[3]))
                    m.remove(needWords[2]);
                if (m.contains(needWords[4]))
                    m.remove(needWords[3]);
            }
            for (JSONObject h : within) {
                final double d = h.optDouble("_d", 0);
                final List<String> info = new ArrayList<>();
                final List<String> m = marks.get(h.optString("id", h.optString("name")));
                if (m != null)
                    info.add(android.text.TextUtils.join("; ", m) + ".");
                info.add(h.optString("city", "") + ", " + h.optString("state", ""));
                info.add(erWords(h));
                if (h.optInt("trauma", 0) > 0)
                    info.add("Trauma level " + roman(h.optInt("trauma")) + ".");
                if (h.optInt("ped", 0) > 0)
                    info.add("Pediatric trauma level " + roman(h.optInt("ped")) + ".");
                if (h.has("burn"))
                    info.add("Burn center.");
                // A pad the FAA has not charted carries no id: "Helipad ." said nothing.
                final JSONObject pad = h.optJSONObject("pad");
                final String padId = pad == null ? "" : pad.optString("id", "").trim();
                if (pad != null && pad.optBoolean("charted", false) && !padId.isEmpty())
                    info.add("Helipad " + padId + ".");
                found.get("hospital").add(point("hospital", h.optString("name"), h.optDouble("lat"),
                        h.optDouble("lon"), d, area, info));
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

        // Everywhere else: our national pack from CMS (tools/build_care_pack.py),
        // certified nursing homes and dialysis, inside the area. California rows are
        // left to CDPH when it answered, which also lists the small licensed homes.
        try {
            carePack(area, found, caCovered);
        } catch (Exception e) {
            failures++;
            missing.add("nursing homes and dialysis");
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
        // Airports and heliports: BTS NTAD Aviation Facilities (the FAA's current
        // cycle, never the FAA services6 quota). Closed sites are left out.
        try {
            for (JSONObject a : Esri.points(AVIATION, "ARPT_STATUS='O'", area,
                    "ARPT_ID,ARPT_NAME,SITE_TYPE_CODE,CITY,STATE_CODE,ELEV,FACILITY_USE_CODE,OWNERSHIP_TYPE_CODE,"
                            + "TWR_TYPE_CODE,FUEL_TYPES", nearbyMiles)) {
                final String type = a.optString("SITE_TYPE_CODE", "");
                final String cat = "H".equals(type) ? "heliport" : "airport";
                final double d = area.distanceMeters(a.optDouble("_lat"), a.optDouble("_lon"));
                final List<String> info = new ArrayList<>();
                final String id = a.optString("ARPT_ID", "").trim();
                info.add(("A".equals(type) ? "Airport" : "S".equals(type) ? "Seaplane base"
                        : "G".equals(type) ? "Gliderport" : "H".equals(type) ? "Heliport" : "Landing site")
                        + (id.isEmpty() ? "" : " " + id) + ", " + ("PU".equals(a.optString("FACILITY_USE_CODE")) ? "public use" : "private use")
                        + ("MA".equals(a.optString("OWNERSHIP_TYPE_CODE")) || "MN".equals(a.optString("OWNERSHIP_TYPE_CODE"))
                        || "MR".equals(a.optString("OWNERSHIP_TYPE_CODE")) ? ", military" : "") + ".");
                if (a.has("ELEV") && !a.isNull("ELEV"))
                    info.add("Elevation " + Math.round(a.optDouble("ELEV")) + " ft MSL.");
                final String twr = a.optString("TWR_TYPE_CODE", "");
                info.add(twr.startsWith("ATCT") ? "Control tower." : "No control tower.");
                final String fuel = a.optString("FUEL_TYPES", "").trim();
                if (!fuel.isEmpty() && !"null".equals(fuel))
                    info.add("Fuel: " + fuel + ".");
                info.add(join(capital(a.optString("CITY", "")), a.optString("STATE_CODE", "")));
                found.get(cat).add(point(cat, (id.isEmpty() ? "" : id + " ") + capital(a.optString("ARPT_NAME")),
                        a.optDouble("_lat"), a.optDouble("_lon"), d, area, info));
            }
        } catch (Exception e) {
            missing.add("airports");
        }
        infrastructure(area, nearbyMiles, reach, found, missing);
        pack(area, reach, found, missing);
        transport(area, found, missing);
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
            while (l.size() > PER_CATEGORY)
                l.remove(l.size() - 1);
            total += l.size();
            if (!l.isEmpty() && head.length() < 70)
                head.append(head.length() == 0 ? "" : ", ").append(l.size()).append(' ')
                        .append(c[1].toLowerCase(Locale.US));
            for (JSONObject p : l)
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
        if (!missing.isEmpty())
            r.line("Did not answer: " + android.text.TextUtils.join(", ", missing) + ". Tap Refresh to try again.");
        return r;
    }

    /**
     * Fire and police stations, power plants and battery storage, nuclear plants,
     * dams and chemical facilities: the Critical Infrastructure layers (PLAN decision
     * 15). Each source fails alone and is named on the "did not answer" line.
     */
    private static void infrastructure(Area area, int nearbyMiles, double reach,
            Map<String, List<JSONObject>> found, List<String> missing) {
        final String[][] stations = { { "16", "fire", "fire stations" }, { "18", "police", "police" } };
        for (String[] st : stations)
            try {
                for (JSONObject a : Esri.points(USGS + st[0], "1=1", area, "name,address,city,state", nearbyMiles)) {
                    final double d = area.distanceMeters(a.optDouble("_lat"), a.optDouble("_lon"));
                    final List<String> info = new ArrayList<>();
                    info.add(join(a.optString("address", ""), a.optString("city", ""), a.optString("state", "")));
                    found.get(st[1]).add(point(st[1], a.optString("name"), a.optDouble("_lat"), a.optDouble("_lon"), d, area, info));
                }
            } catch (Exception e) {
                missing.add(st[2]);
            }
        // Rural areas: no police station in reach (Bayview, Idaho, 2026-11-13). Who comes
        // matters more than "none": the nearest station and the county sheriff, however far.
        if (found.get("police").isEmpty() && !missing.contains("police"))
            try {
                farPolice(area, found.get("police"));
            } catch (Exception e) {
                missing.add("police");
            }

        // Power plants: California's CEC list (it splits battery storage out and lists
        // retired plants, which are left out); EPA's national list elsewhere.
        final boolean ca = area.south < 42.1 && area.north > 32.4 && area.west < -114 && area.east > -124.5;
        try {
            final Map<String, JSONObject> plants = new LinkedHashMap<>();
            if (ca) {
                for (JSONObject a : Esri.points(CEC_PLANTS, "Retired_Plant=0", area,
                        "CECPlantID,PlantName,Capacity_Latest,PriEnergySource,County", nearbyMiles)) {
                    final String id = a.optString("CECPlantID", "");
                    final JSONObject was = plants.get(id);
                    if (was == null)
                        plants.put(id, a);
                    else
                        was.put("Capacity_Latest", was.optDouble("Capacity_Latest", 0) + a.optDouble("Capacity_Latest", 0));
                }
                for (JSONObject a : plants.values()) {
                    final String src = a.optString("PriEnergySource", "");
                    final String cat = "BAT".equals(src) || "ES".equals(src) ? "battery" : "power";
                    final double d = area.distanceMeters(a.optDouble("_lat"), a.optDouble("_lon"));
                    final List<String> info = new ArrayList<>();
                    info.add(fuel(src) + (a.optDouble("Capacity_Latest", 0) > 0
                            ? String.format(Locale.US, ", %.0f MW", a.optDouble("Capacity_Latest")) : "") + ".");
                    info.add(capital(a.optString("County", "")) + " County, CA");
                    found.get(cat).add(point(cat, a.optString("PlantName"), a.optDouble("_lat"), a.optDouble("_lon"), d, area, info));
                }
            } else {
                for (JSONObject a : Esri.points(EPA_PLANTS, "STATUS='OP'", area,
                        "PLANT_CODE,PLANT_NAME,PRIME_MOVER,ENERGY_SOURCE_1,UTILITY_NAME,CITY_NAME,STATE_CODE", nearbyMiles)) {
                    final String id = a.optString("PLANT_CODE", "");
                    if (plants.containsKey(id))
                        continue;
                    plants.put(id, a);
                    final String src = a.optString("ENERGY_SOURCE_1", "");
                    final String cat = "MWH".equals(src) || "BA".equals(a.optString("PRIME_MOVER")) ? "battery" : "power";
                    final double d = area.distanceMeters(a.optDouble("_lat"), a.optDouble("_lon"));
                    final List<String> info = new ArrayList<>();
                    info.add(fuel(src) + ".");
                    info.add("Operator: " + a.optString("UTILITY_NAME", "") + ".");
                    info.add(join(capital(a.optString("CITY_NAME", "")), a.optString("STATE_CODE", "")));
                    found.get(cat).add(point(cat, a.optString("PLANT_NAME"), a.optDouble("_lat"), a.optDouble("_lon"), d, area, info));
                }
            }
        } catch (Exception e) {
            missing.add("power plants");
        }

        // Nuclear plants within 50 mi, the ingestion planning zone; 10 mi is the
        // plume exposure zone.
        try {
            // FEMA lists each reactor; one entry per plant, a line per reactor
            // (Diablo Canyon 1 and 2 were two map points, 2026-11-13).
            final Map<String, JSONObject> plants = new java.util.LinkedHashMap<>();
            final Map<String, List<String>> reactors = new java.util.HashMap<>();
            for (JSONObject a : Esri.points(NUCLEAR, "1=1", area,
                    "power_plant,reactor_name,status,power,location,owner_operator", 50)) {
                final String plant = a.optString("power_plant", "Nuclear plant");
                if (!plants.containsKey(plant)) {
                    plants.put(plant, a);
                    reactors.put(plant, new ArrayList<String>());
                }
                reactors.get(plant).add(a.optString("reactor_name", "") + ": " + a.optString("status", "") + ", "
                        + a.optInt("power", 0) + "% power.");
            }
            for (Map.Entry<String, JSONObject> e : plants.entrySet()) {
                final JSONObject a = e.getValue();
                final double d = area.distanceMeters(a.optDouble("_lat"), a.optDouble("_lon"));
                final List<String> info = new ArrayList<>(reactors.get(e.getKey()));
                info.add(d <= RING_10_MI ? "The area is inside its 10-mile plume zone."
                        : "The area is inside its 50-mile ingestion zone.");
                info.add(PLUME_ZONE);
                info.add(INGESTION_ZONE);
                info.add("Operator: " + a.optString("owner_operator", "") + ".");
                final JSONObject pt = point("nuclear", e.getKey(), a.optDouble("_lat"),
                        a.optDouble("_lon"), d, area, info);
                // The two emergency planning zones as rings on the map (operator,
                // 2026-11-14), the 10-mile first; PlaceLayer labels and explains them.
                pt.put("g", new JSONArray().put(circle(a.optDouble("_lat"), a.optDouble("_lon"), RING_10_MI))
                        .put(circle(a.optDouble("_lat"), a.optDouble("_lon"), RING_50_MI)));
                found.get("nuclear").add(pt);
            }
        } catch (Exception e) {
            missing.add("nuclear plants");
        }

        // Military installations (DoD MIRTA via BTS NTAD): the boundary and a labeled
        // icon, within Nearby (operator, 2026-11-13).
        try {
            for (JSONObject a : Esri.areas(MILITARY, "1=1", area,
                    "siteName,featureName,siteReportingComponent,siteOperationalStatus,isJointBase,stateNameCode", nearbyMiles)) {
                final double d = area.distanceMeters(a.optDouble("_lat"), a.optDouble("_lon"));
                final List<String> info = new ArrayList<>();
                info.add(branch(a.optString("siteReportingComponent", "")) + ", " + status(a.optString("siteOperationalStatus", ""))
                        + ("yes".equalsIgnoreCase(a.optString("isJointBase", "")) ? ", joint base." : "."));
                if (!a.optString("featureName", "").equals(a.optString("siteName", "")))
                    info.add(a.optString("featureName", ""));
                info.add("Boundary from DoD MIRTA; access is controlled.");
                final String name = a.optString("featureName", a.optString("siteName", "Military installation"));
                final JSONObject pt = point("military", name, a.optDouble("_lat"), a.optDouble("_lon"), d, area, info);
                pt.put("g", a.optJSONArray("_rings"));
                found.get("military").add(pt);
            }
        } catch (Exception e) {
            missing.add("military installations");
        }

        // Dams: the service refuses a distance buffer, so a widened box, measured here.
        try {
            for (JSONObject a : Esri.pointsInBox(DAMS, "1=1", area,
                    "NAME,NIDID,HAZARD_POTENTIAL,CONDITION_ASSESSMENT,PRIMARY_PURPOSE,NID_HEIGHT,NID_STORAGE,OWNER_TYPES,CITY",
                    nearbyMiles)) {
                final double d = area.distanceMeters(a.optDouble("_lat"), a.optDouble("_lon"));
                if (d > reach)
                    continue;
                final List<String> info = new ArrayList<>();
                info.add(a.optString("HAZARD_POTENTIAL", "Unknown") + " hazard potential; condition "
                        + a.optString("CONDITION_ASSESSMENT", "not rated").toLowerCase(Locale.US) + ".");
                if (a.optDouble("NID_HEIGHT", 0) > 0)
                    info.add(Math.round(a.optDouble("NID_HEIGHT")) + " ft high, "
                            + String.format(Locale.US, "%,d", Math.round(a.optDouble("NID_STORAGE", 0))) + " acre-ft stored.");
                info.add(a.optString("PRIMARY_PURPOSE", "") + "; " + a.optString("OWNER_TYPES", "") + " owner.");
                info.add("National Inventory of Dams " + a.optString("NIDID", "") + ".");
                found.get("dam").add(point("dam", capital(a.optString("NAME")), a.optDouble("_lat"), a.optDouble("_lon"), d, area, info));
            }
        } catch (Exception e) {
            missing.add("dams");
        }

        // Chemical facilities: EPA Risk Management Program reporters, inside the area.
        try {
            for (JSONObject a : Esri.points(RMP, "1=1", area,
                    "PRIMARY_NAME,LOCATION_ADDRESS,CITY_NAME,STATE_CODE,ACTIVE_STATUS", 0)) {
                if ("INACTIVE".equalsIgnoreCase(a.optString("ACTIVE_STATUS", "")))
                    continue;
                final List<String> info = new ArrayList<>();
                info.add("Files an EPA Risk Management Plan: it holds a listed toxic or flammable chemical above the threshold.");
                info.add(join(capital(a.optString("LOCATION_ADDRESS", "")), capital(a.optString("CITY_NAME", "")),
                        a.optString("STATE_CODE", "")));
                found.get("hazmat").add(point("hazmat", capital(a.optString("PRIMARY_NAME")), a.optDouble("_lat"),
                        a.optDouble("_lon"), 0, area, info));
            }
        } catch (Exception e) {
            missing.add("chemical facilities");
        }
    }

    /**
     * Animal shelters, fairgrounds and rodeo grounds, equestrian centers, pharmacies,
     * groceries, fuel and urgent care: our pack from Overture Maps Places, in 2-degree
     * tiles (tools/build_places_pack.py), within Nearby.
     */
    private static void pack(Area area, double reach, Map<String, List<JSONObject>> found, List<String> missing) {
        try {
            final JSONObject manifest = new JSONObject(Http.get(PACK + "places.json"));
            final JSONObject tiles = manifest.optJSONObject("tiles");
            final int step = Math.max(1, Math.min(10, manifest.optInt("tile_degrees", 2)));
            if (tiles == null)
                throw new java.io.IOException("no tiles in the places manifest");
            final double padLat = reach / 111_320d, padLon = reach / (111_320d
                    * Math.max(0.2, Math.cos(Math.toRadians((area.south + area.north) / 2))));
            final int s0 = floor(area.south - padLat, step), s1 = floor(area.north + padLat, step);
            final int w0 = floor(area.west - padLon, step), w1 = floor(area.east + padLon, step);
            for (int lat = s0; lat <= s1; lat += step)
                for (int lon = w0; lon <= w1; lon += step) {
                    final String key = lat + "_" + lon;
                    if (!tiles.has(key))
                        continue;
                    final JSONArray rows = new JSONArray(Http.getGzip(PACK + "tiles/" + key + ".json.gz"));
                    for (int i = 0; i < rows.length(); i++) {
                        final JSONArray x = rows.optJSONArray(i);
                        if (x == null || x.length() < 8)
                            continue;
                        final String cat = x.optString(0, "");
                        final List<JSONObject> into = found.get(cat);
                        if (into == null)
                            continue;
                        final double plat = x.optDouble(2, Double.NaN), plon = x.optDouble(3, Double.NaN);
                        if (!(Math.abs(plat) <= 90) || !(Math.abs(plon) <= 180))
                            continue;
                        final double d = area.distanceMeters(plat, plon);
                        if (d > reach)
                            continue;
                        final List<String> info = new ArrayList<>();
                        info.add(join(x.optString(4, ""), x.optString(5, ""), x.optString(6, "")));
                        final String phone = x.optString(7, "").replaceAll("[^0-9]", "");
                        if (phone.length() == 11 && phone.startsWith("1"))
                            info.add(String.format("(%s) %s-%s", phone.substring(1, 4), phone.substring(4, 7), phone.substring(7)));
                        else if (phone.length() == 10)
                            info.add(String.format("(%s) %s-%s", phone.substring(0, 3), phone.substring(3, 6), phone.substring(6)));
                        into.add(point(cat, x.optString(1, ""), plat, plon, d, area, info));
                    }
                }
        } catch (Exception e) {
            missing.add("animal shelters, fairgrounds and stores");
        }
    }

    /**
     * Bridges, railroad crossings and rail lines (BTS NTAD, FRA), and power lines
     * across the US (OpenStreetMap pack): what no other takwerx plugin draws (decision 16).
     * Inside the area only.
     */
    private static void transport(Area area, Map<String, List<JSONObject>> found, List<String> missing) {
        try {
            for (JSONObject a : Esri.points(NTAD + "NTAD_National_Bridge_Inventory/FeatureServer/0", "1=1", area,
                    "FACILITY_CARRIED_007,FEATURES_DESC_006A,YEAR_BUILT_027,ADT_029,BRIDGE_CONDITION,"
                            + "OPEN_CLOSED_POSTED_041,VERT_CLR_OVER_MT_053,OPERATING_RATING_064,STRUCTURE_NUMBER_008", 0)) {
                final List<String> info = new ArrayList<>();
                final String cond = a.optString("BRIDGE_CONDITION", "");
                info.add("Condition: " + ("G".equals(cond) ? "good" : "F".equals(cond) ? "fair" : "P".equals(cond) ? "poor" : "not rated") + ".");
                final String ocp = a.optString("OPEN_CLOSED_POSTED_041", "");
                info.add("K".equals(ocp) ? "CLOSED." : "B".equals(ocp) || "P".equals(ocp) || "R".equals(ocp) ? "Load posted." : "Open, no restriction.");
                final double clr = a.optDouble("VERT_CLR_OVER_MT_053", 0);
                if (clr > 0 && clr < 99)
                    info.add(String.format(Locale.US, "Clearance over the deck %.1f ft.", clr * 3.28084));
                final double tons = a.optDouble("OPERATING_RATING_064", 0);
                if (tons > 0 && tons < 99)
                    info.add(String.format(Locale.US, "Operating rating %.0f metric tons.", tons));
                if (a.optInt("YEAR_BUILT_027", 0) > 1700)
                    info.add("Built " + a.optInt("YEAR_BUILT_027") + ".");
                if (a.optInt("ADT_029", 0) > 0)
                    info.add(String.format(Locale.US, "%,d vehicles a day.", a.optInt("ADT_029")));
                info.add("NBI " + a.optString("STRUCTURE_NUMBER_008", "").trim() + ".");
                final String name = capital(a.optString("FACILITY_CARRIED_007", "").replace("'", "")) + " over "
                        + capital(a.optString("FEATURES_DESC_006A", "").replace("'", ""));
                found.get("bridge").add(point("bridge", name, a.optDouble("_lat"), a.optDouble("_lon"), 0, area, info));
            }
        } catch (Exception e) {
            missing.add("bridges");
        }
        try {
            for (JSONObject a : Esri.points(NTAD + "NTAD_Railroad_Grade_Crossings/FeatureServer/0", "1=1", area,
                    "CrossingID,RailroadCode,STREET,CrossingType,CrossingPosition,SignsOrSignals,EmergencyTelephoneNumber,CITYNAME", 0)) {
                final List<String> info = new ArrayList<>();
                info.add(a.optString("CrossingType", "") + " crossing, " + a.optString("CrossingPosition", "").toLowerCase(Locale.US) + ".");
                info.add("Railroad " + a.optString("RailroadCode", "") + ".");
                final String phone = a.optString("EmergencyTelephoneNumber", "").replaceAll("[^0-9]", "");
                if (phone.length() == 10)
                    info.add("Railroad emergency (" + phone.substring(0, 3) + ") " + phone.substring(3, 6) + "-" + phone.substring(6) + ".");
                info.add("Crossing " + a.optString("CrossingID", "") + ", " + capital(a.optString("CITYNAME", "")) + ".");
                found.get("crossing").add(point("crossing", capital(a.optString("STREET", "Crossing")) + " ("
                        + a.optString("RailroadCode", "") + ")", a.optDouble("_lat"), a.optDouble("_lon"), 0, area, info));
            }
        } catch (Exception e) {
            missing.add("railroad crossings");
        }
        try {
            for (JSONObject a : Esri.lines(NTAD + "NTAD_North_American_Rail_Network_Lines/FeatureServer/0", "1=1", area,
                    "RROWNER1,RROWNER2,TRACKS,NET")) {
                final List<String> info = new ArrayList<>();
                info.add("Railroad " + a.optString("RROWNER1", "") + (a.optString("RROWNER2", "").trim().isEmpty() ? ""
                        : ", " + a.optString("RROWNER2")) + ".");
                if (a.optInt("TRACKS", 0) > 1)
                    info.add(a.optInt("TRACKS") + " tracks.");
                found.get("rail").add(line("rail", "Rail, " + a.optString("RROWNER1", ""), a.optJSONArray("_paths"), info));
            }
        } catch (Exception e) {
            missing.add("rail lines");
        }
        try {
            power(area, found);
        } catch (Exception e) {
            missing.add("transmission lines");
        }
    }

    /**
     * Transmission and sub-transmission lines from our pack of OpenStreetMap power
     * lines (tools/build_power_pack.py, via Overture), inside the area. The CEC layer
     * drew Corona's Ontario Ave 66 kV line on the wrong streets (2026-11-13). Lines of
     * one voltage and operator are one entry with many paths.
     */
    private static void power(Area area, Map<String, List<JSONObject>> found) throws Exception {
        final JSONObject manifest = new JSONObject(Http.get(POWER + "power.json"));
        final JSONObject tiles = manifest.optJSONObject("tiles");
        final int step = Math.max(1, Math.min(10, manifest.optInt("tile_degrees", 1)));
        if (tiles == null)
            throw new java.io.IOException("no tiles in the power manifest");
        final java.util.Set<String> seen = new java.util.HashSet<>();
        final Map<String, JSONArray> groups = new java.util.LinkedHashMap<>();
        final Map<String, String[]> heads = new java.util.HashMap<>();
        int paths = 0;
        for (int lat = floor(area.south, step); lat <= floor(area.north, step); lat += step)
            for (int lon = floor(area.west, step); lon <= floor(area.east, step); lon += step) {
                final String key = lat + "_" + lon;
                if (!tiles.has(key))
                    continue;
                final JSONArray rows = new JSONArray(Http.getGzip(POWER + "tiles/" + key + ".json.gz"));
                for (int i = 0; i < rows.length() && paths < MAX_POWER_PATHS; i++) {
                    final JSONArray x = rows.optJSONArray(i);
                    final JSONArray c = x == null ? null : x.optJSONArray(4);
                    if (c == null || c.length() < 4 || c.length() > 20_000)
                        continue;
                    // A line is in every tile it touches; a path once.
                    if (!seen.add(x.optString(0) + "/" + c.optDouble(0) + "," + c.optDouble(1)))
                        continue;
                    boolean in = false;
                    final JSONArray path = new JSONArray();
                    for (int k = 0; k + 1 < c.length(); k += 2) {
                        final double lo = c.optDouble(k), la = c.optDouble(k + 1);
                        if (!(Math.abs(la) <= 90) || !(Math.abs(lo) <= 180))
                            continue;
                        if (!in && la >= area.south && la <= area.north && lo >= area.west && lo <= area.east
                                && area.contains(la, lo))
                            in = true;
                        path.put(new JSONArray().put(lo).put(la));
                    }
                    if (!in || path.length() < 2)
                        continue;
                    final String kv = x.optString(1, ""), op = x.optString(2, "").trim();
                    final String g = kv + "|" + op;
                    JSONArray list = groups.get(g);
                    if (list == null) {
                        list = new JSONArray();
                        groups.put(g, list);
                        heads.put(g, new String[] { kv, op });
                    }
                    list.put(path);
                    paths++;
                }
            }
        for (Map.Entry<String, JSONArray> e : groups.entrySet()) {
            final String kv = heads.get(e.getKey())[0], op = heads.get(e.getKey())[1];
            final String name = (kv.isEmpty() ? "Power line" : kv + " kV line") + (op.isEmpty() ? "" : ", " + op);
            final List<String> info = new ArrayList<>();
            info.add(kv.isEmpty() ? "Voltage not recorded." : kv + " kV.");
            info.add(op.isEmpty() ? "Operator not recorded." : "Operator " + op + ".");
            info.add("From OpenStreetMap; check the towers on the ground.");
            // Report.cleanPaths keeps 200 paths and 4,000 points per entry when a saved
            // report loads, so a big group goes in parts under both.
            JSONArray part = new JSONArray();
            int pts = 0;
            for (int i = 0; i < e.getValue().length(); i++) {
                final JSONArray path = e.getValue().optJSONArray(i);
                final int n = Math.min(path.length(), 3_500);
                if (part.length() > 0 && (part.length() >= 180 || pts + n > 3_800)) {
                    found.get("transmission").add(line("transmission", name, part, info));
                    part = new JSONArray();
                    pts = 0;
                }
                part.put(path);
                pts += n;
            }
            if (part.length() > 0)
                found.get("transmission").add(line("transmission", name, part, info));
        }
    }

    /** A line place: its first vertex for the list, its paths for the map. */
    private static JSONObject line(String c, String name, JSONArray paths, List<String> info) throws Exception {
        final JSONArray first = paths.optJSONArray(0);
        final JSONArray v = first == null ? null : first.optJSONArray(first.length() / 2);
        final JSONObject p = point(c, name, v == null ? 0 : v.optDouble(1), v == null ? 0 : v.optDouble(0), 0, null, info);
        return p.put("g", paths);
    }

    private static int floor(double v, int step) {
        return (int) (Math.floor(v / step) * step);
    }

    /** The nearest police station within 50 mi, and the sheriff of the county at the area's center. */
    private static void farPolice(Area area, List<JSONObject> into) throws Exception {
        final List<JSONObject> wide = Esri.points(USGS + "18", "1=1", area, "name,address,city,state", 50);
        JSONObject nearest = null;
        double best = Double.MAX_VALUE;
        for (JSONObject a : wide) {
            final double d = area.distanceMeters(a.optDouble("_lat"), a.optDouble("_lon"));
            if (d < best) {
                best = d;
                nearest = a;
            }
        }
        // The county from Census at the area's center: "Kootenai County, ID".
        String county = null;
        for (com.atakmap.android.sitrep.net.Places.Place p : com.atakmap.android.sitrep.net.Places.at(
                new com.atakmap.coremap.maps.coords.GeoPoint((area.south + area.north) / 2, (area.west + area.east) / 2))) {
            final java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("^(.+? (?:County|Parish|Borough)), [A-Z]{2}$").matcher(p.label);
            if (m.matches()) {
                county = m.group(1);
                break;
            }
        }
        JSONObject sheriff = null;
        if (county != null && county.matches("[A-Za-z .'-]+")) {
            final String like = county.toUpperCase(Locale.US).replace("'", "''");
            for (JSONObject a : Esri.points(USGS + "18", "UPPER(name) LIKE '" + like + " SHERIFF%'", area,
                    "name,address,city,state", 150)) {
                if (sheriff == null || area.distanceMeters(a.optDouble("_lat"), a.optDouble("_lon"))
                        < area.distanceMeters(sheriff.optDouble("_lat"), sheriff.optDouble("_lon")))
                    sheriff = a;
            }
        }
        for (JSONObject a : new JSONObject[] { sheriff, nearest }) {
            if (a == null || (a == nearest && sheriff != null && a.optString("name").equals(sheriff.optString("name"))))
                continue;
            final double d = area.distanceMeters(a.optDouble("_lat"), a.optDouble("_lon"));
            final List<String> info = new ArrayList<>();
            info.add(a == sheriff ? "The sheriff of " + county + "; no police station within Nearby."
                    : "The nearest police station; none within Nearby.");
            info.add(join(a.optString("address", ""), a.optString("city", ""), a.optString("state", "")));
            into.add(point("police", a.optString("name"), a.optDouble("_lat"), a.optDouble("_lon"), d, area, info));
        }
    }

    /** CMS nursing homes and dialysis from our pack, inside the area. */
    private static void carePack(Area area, Map<String, List<JSONObject>> found, boolean caCovered) throws Exception {
        final JSONObject manifest = new JSONObject(Http.get(CARE + "care.json"));
        final JSONObject tiles = manifest.optJSONObject("tiles");
        final int step = Math.max(1, Math.min(10, manifest.optInt("tile_degrees", 2)));
        if (tiles == null)
            throw new java.io.IOException("no tiles in the care manifest");
        for (int lat = floor(area.south, step); lat <= floor(area.north, step); lat += step)
            for (int lon = floor(area.west, step); lon <= floor(area.east, step); lon += step) {
                final String key = lat + "_" + lon;
                if (!tiles.has(key))
                    continue;
                final JSONArray rows = new JSONArray(Http.getGzip(CARE + "tiles/" + key + ".json.gz"));
                for (int i = 0; i < rows.length(); i++) {
                    final JSONArray x = rows.optJSONArray(i);
                    if (x == null || x.length() < 9)
                        continue;
                    final String cat = x.optString(0, "");
                    final List<JSONObject> into = found.get(cat);
                    if (into == null || !("care".equals(cat) || "dialysis".equals(cat)))
                        continue;
                    if (caCovered && "CA".equals(x.optString(6)))
                        continue;
                    final double plat = x.optDouble(2, Double.NaN), plon = x.optDouble(3, Double.NaN);
                    if (!(Math.abs(plat) <= 90) || !(Math.abs(plon) <= 180) || !area.contains(plat, plon))
                        continue;
                    final List<String> info = new ArrayList<>();
                    info.add(("care".equals(cat) ? "Certified nursing home" : "Dialysis facility")
                            + (x.optString(8, "").isEmpty() ? "." : ", " + x.optString(8) + "."));
                    info.add(join(capital(x.optString(4, "")), capital(x.optString(5, "")), x.optString(6, "")));
                    if (!x.optString(7, "").isEmpty())
                        info.add(x.optString(7));
                    into.add(point(cat, capital(x.optString(1, "")), plat, plon, 0, area, info));
                }
            }
    }

    /** MIRTA's reporting component in words. */
    private static String branch(String code) {
        switch (code == null ? "" : code) {
            case "usa": return "Army";
            case "usar": return "Army Reserve";
            case "armyNationalGuard": return "Army National Guard";
            case "usaf": return "Air Force";
            case "afr": return "Air Force Reserve";
            case "airNationalGuard": return "Air National Guard";
            case "usn": return "Navy";
            case "usnr": return "Navy Reserve";
            case "usmc": return "Marine Corps";
            case "usmcr": return "Marine Corps Reserve";
            case "whs": return "Washington Headquarters Services";
            default: return "Department of Defense";
        }
    }

    /** MIRTA's operational status in words. */
    private static String status(String code) {
        switch (code == null ? "" : code) {
            case "act": return "active";
            case "semi": return "semi-active";
            case "care": return "caretaker status";
            case "clsd": return "closed";
            case "excs": return "excess";
            default: return "status not given";
        }
    }

    /** CEC and EIA energy source codes in words. */
    private static String fuel(String code) {
        switch (code == null ? "" : code) {
            case "SUN": return "Solar";
            case "WND": return "Wind";
            case "NG": return "Natural gas";
            case "BAT": case "MWH": case "ES": return "Battery storage";
            case "WAT": return "Hydro";
            case "NUC": return "Nuclear";
            case "GEO": return "Geothermal";
            case "DFO": case "OIL": return "Oil";
            case "BIT": case "SUB": case "COL": return "Coal";
            case "LFG": return "Landfill gas";
            case "OBG": case "WDS": case "BIO": return "Biomass";
            default: return code == null || code.isEmpty() ? "Power plant" : "Fuel " + code;
        }
    }

    /** NRC emergency planning zones (10 CFR 50.47(c)(2)): about 10 and 50 miles. */
    public static final double RING_10_MI = 16_093.44, RING_50_MI = 80_467.2;
    /** What the rings mean, in the plant's details and on a tap on a ring. */
    public static final String PLUME_ZONE = "10-mile plume zone (NRC emergency planning zone): after a release, "
            + "people inside may be told to shelter in place or evacuate, and to take potassium iodide. "
            + "Sirens and evacuation routes cover this zone. The state plan sets the exact line.";
    public static final String INGESTION_ZONE = "50-mile ingestion zone (NRC emergency planning zone): after a release, "
            + "food, water, milk and crops inside may be tested, held or stopped. People are not usually moved.";

    /** A circle of {@code meters} around a point, as [[lon, lat], ...], closed, 96 sides. */
    private static JSONArray circle(double lat, double lon, double meters) throws Exception {
        final JSONArray ring = new JSONArray();
        final double r = meters / 6_371_008.8, la = Math.toRadians(lat), lo = Math.toRadians(lon);
        for (int k = 0; k <= 96; k++) {
            final double b = Math.toRadians(k * 360.0 / 96);
            final double la2 = Math.asin(Math.sin(la) * Math.cos(r) + Math.cos(la) * Math.sin(r) * Math.cos(b));
            final double lo2 = lo + Math.atan2(Math.sin(b) * Math.sin(r) * Math.cos(la),
                    Math.cos(r) - Math.sin(la) * Math.sin(la2));
            ring.put(new JSONArray().put(Math.round(Math.toDegrees(lo2) * 1e6) / 1e6)
                    .put(Math.round(Math.toDegrees(la2) * 1e6) / 1e6));
        }
        return ring;
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

    /**
     * The pack's ER field: {@code yes} from CMS and most states; California's HCAI
     * levels {@code basic}, {@code comprehensive} and {@code standby}. Every
     * California hospital read "no emergency room" while only {@code yes} counted.
     */
    static boolean hasEr(JSONObject h) {
        final String er = h.optString("er", "").trim().toLowerCase(java.util.Locale.US);
        return er.equals("yes") || er.equals("basic") || er.equals("comprehensive") || er.equals("standby");
    }

    static String erWords(JSONObject h) {
        switch (h.optString("er", "").trim().toLowerCase(java.util.Locale.US)) {
            case "yes":
                return "Emergency room.";
            case "basic":
                return "Emergency room (basic).";
            case "comprehensive":
                return "Emergency room (comprehensive).";
            case "standby":
                return "Standby emergency room: a doctor on call, not on site.";
            default:
                return "No emergency room listed.";
        }
    }

    private static String roman(int n) {
        final String[] r = { "", "I", "II", "III", "IV", "V" };
        return n >= 1 && n <= 5 ? r[n] : String.valueOf(n);
    }
}
