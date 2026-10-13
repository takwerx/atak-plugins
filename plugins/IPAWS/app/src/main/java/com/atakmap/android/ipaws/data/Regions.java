package com.atakmap.android.ipaws.data;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Regions, so nobody ticks Arizona, New Mexico, Utah and Colorado one at a time.
 *
 * <p>Plain geography rather than the fire world's GACCs, on the operator's call. Two
 * reasons it is the better fit here: anyone reads "Pacific Northwest" without knowing
 * the fire world, and every region is <b>whole states</b>. GACC splits California into
 * Northern and Southern, and the feed's area codes are whole states, so a GACC picker
 * would have had California in two regions at once.
 *
 * <p>A region is a <b>shortcut, not a fourth thing to keep track of</b>. Nothing is
 * stored: ticking one adds its areas to the states already selected, unticking removes
 * them, and a region shows as ticked when every one of its areas happens to be
 * selected. So narrowing one state afterwards just leaves its region unticked next
 * time the picker opens, and there is no stored region selection to go stale against
 * the states.
 *
 * <p>Water regions are the reason marine areas are reachable at all. The 15 marine
 * codes were left out of the picker because naming each of them correctly needed a
 * source that had not been checked; grouping them as "Great Lakes" and "Gulf waters"
 * is both safe and what a person would look for.
 *
 * <p>Every one of the 74 area codes is in exactly one region, and
 * {@code tools/verify_ipaws_feed.py} fails if that stops being true -- an area in no
 * region is one the regions can never select, and an area in two would be removed by
 * unticking either.
 */
public final class Regions {

    private Regions() {
    }

    private static final Map<String, String[]> LAND = new LinkedHashMap<>();
    private static final Map<String, String[]> WATER = new LinkedHashMap<>();

    static {
        LAND.put("Pacific Northwest", new String[] { "WA", "OR", "ID" });
        LAND.put("Pacific Southwest", new String[] { "CA", "NV", "HI" });
        LAND.put("Southwest", new String[] { "AZ", "NM", "UT", "CO" });
        LAND.put("Rockies", new String[] { "MT", "WY" });
        LAND.put("Great Plains", new String[] { "ND", "SD", "NE", "KS", "OK", "TX" });
        LAND.put("Midwest", new String[] { "MN", "WI", "MI", "IA", "IL", "IN", "OH", "MO" });
        LAND.put("Northeast", new String[] { "ME", "NH", "VT", "MA", "RI", "CT", "NY", "NJ", "PA" });
        LAND.put("Mid-Atlantic", new String[] { "MD", "DE", "VA", "WV", "DC" });
        LAND.put("Southeast", new String[] { "NC", "SC", "GA", "FL", "AL", "MS", "TN", "KY", "AR", "LA" });
        LAND.put("Alaska", new String[] { "AK" });
        LAND.put("Pacific islands", new String[] { "AS", "GU", "MP", "PW", "FM", "MH" });
        LAND.put("Caribbean", new String[] { "PR", "VI" });

        // Grouped from the zone names the service actually returns: PZ's zones are
        // Grays Harbor and the Strait of Juan de Fuca, AM's are the Yucatan Basin and
        // the Caribbean, GM's are Florida Bay and the Gulf.
        WATER.put("Atlantic waters", new String[] { "AN" });
        WATER.put("Caribbean waters", new String[] { "AM" });
        WATER.put("Gulf waters", new String[] { "GM" });
        WATER.put("Great Lakes", new String[] { "LC", "LE", "LH", "LM", "LO", "LS", "SL" });
        WATER.put("Pacific waters", new String[] { "PZ" });
        WATER.put("Alaska waters", new String[] { "PK" });
        WATER.put("Pacific island waters", new String[] { "PH", "PM", "PS" });
    }

    /** Land regions first, then water, in the order the picker shows them. */
    public static List<String> names() {
        final List<String> out = new ArrayList<>(LAND.keySet());
        out.addAll(WATER.keySet());
        return out;
    }

    /** The area codes a region covers. */
    public static List<String> areasIn(String region) {
        String[] a = LAND.get(region);
        if (a == null)
            a = WATER.get(region);
        return a == null ? new ArrayList<String>() : Arrays.asList(a);
    }

    public static boolean isWater(String region) {
        return WATER.containsKey(region);
    }

    /**
     * Ticked when every area of the region is already selected. Derived rather than
     * stored, so it can never disagree with the states.
     */
    public static boolean isFullySelected(String region, Set<String> selectedAreas) {
        final List<String> areas = areasIn(region);
        if (areas.isEmpty())
            return false;
        for (String a : areas)
            if (!selectedAreas.contains(a))
                return false;
        return true;
    }

    /** Every code any region covers, for the completeness gate. */
    public static List<String> allAreas() {
        final List<String> out = new ArrayList<>();
        for (String[] a : LAND.values())
            out.addAll(Arrays.asList(a));
        for (String[] a : WATER.values())
            out.addAll(Arrays.asList(a));
        return out;
    }
}
