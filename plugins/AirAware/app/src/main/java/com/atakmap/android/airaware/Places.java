package com.atakmap.android.airaware;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The three ways an operator can say where they care about: a state, an FAA region, or an
 * air route traffic control center.
 *
 * <p>All three because all three are how different people talk. The Node-RED TFR feed in
 * infra-TAK filters on states alone ({@code cfg.tfrStates}), which is the vocabulary the
 * server side already uses; a region is how the FAA organizes itself; and a center is what
 * the feed itself carries on every row, so it costs nothing and is what somebody holding a
 * chart would say.
 */
public final class Places {

    private Places() {
    }

    // ---- states ----

    /** Code to name, in the order the Configurator lists them. "USA" is the national one. */
    private static final Map<String, String> STATES = new LinkedHashMap<>();

    static {
        String[][] s = {
                { "AL", "Alabama" }, { "AK", "Alaska" }, { "AS", "American Samoa" },
                { "AZ", "Arizona" }, { "AR", "Arkansas" }, { "CA", "California" },
                { "CO", "Colorado" }, { "MP", "Northern Mariana Islands" },
                { "CT", "Connecticut" }, { "DE", "Delaware" },
                { "DC", "District of Columbia" }, { "FL", "Florida" }, { "GA", "Georgia" },
                { "GU", "Guam" }, { "HI", "Hawaii" }, { "ID", "Idaho" }, { "IL", "Illinois" },
                { "IN", "Indiana" }, { "IA", "Iowa" }, { "KS", "Kansas" }, { "KY", "Kentucky" },
                { "LA", "Louisiana" }, { "ME", "Maine" }, { "MD", "Maryland" },
                { "MA", "Massachusetts" }, { "MI", "Michigan" }, { "MN", "Minnesota" },
                { "MS", "Mississippi" }, { "MO", "Missouri" }, { "MT", "Montana" },
                { "NE", "Nebraska" }, { "NV", "Nevada" }, { "NH", "New Hampshire" },
                { "NJ", "New Jersey" }, { "NM", "New Mexico" }, { "NY", "New York" },
                { "NC", "North Carolina" }, { "ND", "North Dakota" }, { "OH", "Ohio" },
                { "OK", "Oklahoma" }, { "OR", "Oregon" }, { "PA", "Pennsylvania" },
                { "PR", "Puerto Rico" }, { "RI", "Rhode Island" }, { "SC", "South Carolina" },
                { "SD", "South Dakota" }, { "TN", "Tennessee" }, { "TX", "Texas" },
                { "UT", "Utah" }, { "VT", "Vermont" }, { "VI", "U.S. Virgin Islands" },
                { "VA", "Virginia" }, { "WA", "Washington" }, { "WV", "West Virginia" },
                { "WI", "Wisconsin" }, { "WY", "Wyoming" }, { "USA", "Nationwide" },
        };
        for (String[] row : s)
            STATES.put(row[0], row[1]);
    }

    public static String stateName(String code) {
        final String n = STATES.get(code);
        return n != null ? n : code;
    }

    // ---- FAA regions ----

    /**
     * The nine FAA regions and the states each covers.
     *
     * <p>Read off the FAA's own regional pages on 2026-10-07 (the nine names from
     * {@code faa.gov/airports/regions/}, the members from each region's page) rather than
     * typed from memory. The nine partition all 56 states and territories exactly, which is
     * the check that the scrape was complete.
     *
     * <p>One trap in that scrape, noted because it would be repeated: "Washington" matches
     * both the state and <em>Washington, DC</em> in every page's footer, so WA appeared in
     * all nine. It belongs to Northwest Mountain alone.
     */
    private static final Map<String, String[]> REGIONS = new LinkedHashMap<>();

    static {
        REGIONS.put("Alaskan", new String[] { "AK" });
        REGIONS.put("Central", new String[] { "IA", "KS", "MO", "NE" });
        REGIONS.put("Eastern", new String[] { "DC", "DE", "MD", "NJ", "NY", "PA", "VA", "WV" });
        REGIONS.put("Great Lakes", new String[] { "IL", "IN", "MI", "MN", "ND", "OH", "SD", "WI" });
        REGIONS.put("New England", new String[] { "CT", "MA", "ME", "NH", "RI", "VT" });
        REGIONS.put("Northwest Mountain", new String[] { "CO", "ID", "MT", "OR", "UT", "WA", "WY" });
        REGIONS.put("Southern", new String[] { "AL", "FL", "GA", "KY", "MS", "NC", "PR", "SC", "TN", "VI" });
        REGIONS.put("Southwest", new String[] { "AR", "LA", "NM", "OK", "TX" });
        REGIONS.put("Western-Pacific", new String[] { "AS", "AZ", "CA", "GU", "HI", "MP", "NV" });
    }

    public static List<String> regions() {
        return Collections.unmodifiableList(new java.util.ArrayList<>(REGIONS.keySet()));
    }

    /** The states an FAA region covers; empty when the name is not one. */
    public static Set<String> statesIn(String region) {
        final String[] s = REGIONS.get(region);
        return s == null ? Collections.<String> emptySet()
                : new LinkedHashSet<>(Arrays.asList(s));
    }

    /** Which region a state belongs to, or null. */
    public static String regionOf(String state) {
        for (Map.Entry<String, String[]> e : REGIONS.entrySet())
            for (String s : e.getValue())
                if (s.equals(state))
                    return e.getKey();
        return null;
    }

    // ---- centers ----

    /**
     * Air route traffic control centers, by the code the feed puts in {@code facility}.
     *
     * <p>Every one of these was corroborated against the live feed on 2026-10-07 by the
     * states its own TFRs fall in: ZAN only Alaska, ZUA only Guam, ZSU only Puerto Rico,
     * ZOA California and Nevada, and so on for all 24. {@code FDC} is not a center at all --
     * it is the Flight Data Center, which is what the nationwide notices carry.
     */
    private static final Map<String, String> CENTERS = new LinkedHashMap<>();

    static {
        String[][] c = {
                { "ZAB", "Albuquerque Center" }, { "ZAN", "Anchorage Center" },
                { "ZAU", "Chicago Center" }, { "ZBW", "Boston Center" },
                { "ZDC", "Washington Center" }, { "ZDV", "Denver Center" },
                { "ZFW", "Fort Worth Center" }, { "ZHN", "Honolulu Control Facility" },
                { "ZHU", "Houston Center" }, { "ZID", "Indianapolis Center" },
                { "ZJX", "Jacksonville Center" }, { "ZKC", "Kansas City Center" },
                { "ZLA", "Los Angeles Center" }, { "ZLC", "Salt Lake City Center" },
                { "ZMA", "Miami Center" }, { "ZME", "Memphis Center" },
                { "ZMP", "Minneapolis Center" }, { "ZNY", "New York Center" },
                { "ZOA", "Oakland Center" }, { "ZOB", "Cleveland Center" },
                { "ZSE", "Seattle Center" }, { "ZSU", "San Juan Center" },
                { "ZTL", "Atlanta Center" }, { "ZUA", "Guam Center" },
                { "FDC", "Nationwide notices" },
        };
        for (String[] row : c)
            CENTERS.put(row[0], row[1]);
    }

    /** A center's name, falling back to the code: the feed can add one we have not met. */
    public static String centerName(String code) {
        if (code == null || code.trim().isEmpty())
            return "Unknown";
        final String n = CENTERS.get(code.trim());
        return n != null ? n : code.trim();
    }
}
