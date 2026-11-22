package com.atakmap.android.ipaws.data;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The states and territories the picker offers, and the one piece of arithmetic
 * that lets a county be filtered at all.
 *
 * <p>An alert names the counties it covers only as <b>SAME</b> codes -- {@code "0"}
 * then the two-digit state FIPS then the three-digit county FIPS, so Imperial County
 * California is {@code 006025}. The county picker, on the other hand, is built from
 * county <b>zone</b> ids ({@code CAC025}), because that is what the API will list.
 * Nothing in the API returns the pairing between the two, so the state FIPS table is
 * shipped here.
 *
 * <p>It is shipped, and it is also checked: {@code tools/verify_ipaws_feed.py} takes
 * every alert confined to a single state -- which asserts one row, since the alert
 * carries the state letters in its UGC and the state FIPS in its SAME -- and fails if
 * any row disagrees. 23 rows were confirmed that way on 2026-09-16 with none wrong.
 * A wrong row here is not a crash, it is a county that silently matches no alert
 * ever, which is why it is worth checking rather than trusting.
 *
 * <p>Marine areas are deliberately not offered in v0.1's picker. {@code area=} accepts
 * them and the filter would carry them, but naming them correctly needs a source we
 * have not checked, and a row labelled with a guessed name is worse than no row.
 */
public final class Areas {

    private Areas() {
    }

    private static final Map<String, String> NAMES = new LinkedHashMap<>();
    private static final Map<String, String> FIPS = new LinkedHashMap<>();

    private static void put(String code, String name, String fips) {
        NAMES.put(code, name);
        FIPS.put(code, fips);
    }

    static {
        put("AL", "Alabama", "01");
        put("AK", "Alaska", "02");
        put("AZ", "Arizona", "04");
        put("AR", "Arkansas", "05");
        put("CA", "California", "06");
        put("CO", "Colorado", "08");
        put("CT", "Connecticut", "09");
        put("DE", "Delaware", "10");
        put("DC", "District of Columbia", "11");
        put("FL", "Florida", "12");
        put("GA", "Georgia", "13");
        put("HI", "Hawaii", "15");
        put("ID", "Idaho", "16");
        put("IL", "Illinois", "17");
        put("IN", "Indiana", "18");
        put("IA", "Iowa", "19");
        put("KS", "Kansas", "20");
        put("KY", "Kentucky", "21");
        put("LA", "Louisiana", "22");
        put("ME", "Maine", "23");
        put("MD", "Maryland", "24");
        put("MA", "Massachusetts", "25");
        put("MI", "Michigan", "26");
        put("MN", "Minnesota", "27");
        put("MS", "Mississippi", "28");
        put("MO", "Missouri", "29");
        put("MT", "Montana", "30");
        put("NE", "Nebraska", "31");
        put("NV", "Nevada", "32");
        put("NH", "New Hampshire", "33");
        put("NJ", "New Jersey", "34");
        put("NM", "New Mexico", "35");
        put("NY", "New York", "36");
        put("NC", "North Carolina", "37");
        put("ND", "North Dakota", "38");
        put("OH", "Ohio", "39");
        put("OK", "Oklahoma", "40");
        put("OR", "Oregon", "41");
        put("PA", "Pennsylvania", "42");
        put("RI", "Rhode Island", "44");
        put("SC", "South Carolina", "45");
        put("SD", "South Dakota", "46");
        put("TN", "Tennessee", "47");
        put("TX", "Texas", "48");
        put("UT", "Utah", "49");
        put("VT", "Vermont", "50");
        put("VA", "Virginia", "51");
        put("WA", "Washington", "53");
        put("WV", "West Virginia", "54");
        put("WI", "Wisconsin", "55");
        put("WY", "Wyoming", "56");
        put("AS", "American Samoa", "60");
        put("GU", "Guam", "66");
        put("MP", "Northern Mariana Islands", "69");
        put("PR", "Puerto Rico", "72");
        put("VI", "U.S. Virgin Islands", "78");
        put("FM", "Micronesia", "64");
        put("MH", "Marshall Islands", "68");
        put("PW", "Palau", "70");
    }

    /**
     * Marine areas. Not offered as individual rows -- naming all 15 correctly needs a
     * source that has not been checked -- but reachable through the water regions,
     * which group them under names a person would look for. The names here are only a
     * fallback for anywhere one is printed; the picker shows the region.
     */
    private static final Map<String, String> MARINE = new LinkedHashMap<>();

    static {
        MARINE.put("AN", "Atlantic coastal waters");
        MARINE.put("AM", "Caribbean waters");
        MARINE.put("GM", "Gulf waters");
        MARINE.put("LC", "Lake St. Clair");
        MARINE.put("LE", "Lake Erie");
        MARINE.put("LH", "Lake Huron");
        MARINE.put("LM", "Lake Michigan");
        MARINE.put("LO", "Lake Ontario");
        MARINE.put("LS", "Lake Superior");
        MARINE.put("SL", "St. Lawrence River");
        MARINE.put("PZ", "Pacific coastal waters");
        MARINE.put("PK", "Alaska coastal waters");
        MARINE.put("PH", "Hawaii waters");
        MARINE.put("PM", "Mariana waters");
        MARINE.put("PS", "Samoa waters");
    }

    public static boolean isMarine(String code) {
        return code != null && MARINE.containsKey(code.toUpperCase(java.util.Locale.US));
    }

    /** Every code the picker offers as a state, in the order it shows them. */
    public static List<String> codes() {
        final List<String> out = new ArrayList<>(NAMES.keySet());
        Collections.sort(out, new java.util.Comparator<String>() {
            @Override
            public int compare(String a, String b) {
                return name(a).compareTo(name(b));
            }
        });
        return out;
    }

    /** The name a person would recognise; the code itself if we do not have one. */
    public static String name(String code) {
        if (code == null)
            return "null";
        final String up = code.toUpperCase(java.util.Locale.US);
        final String n = NAMES.get(up);
        if (n != null)
            return n;
        final String m = MARINE.get(up);
        return m == null ? code : m;
    }

    /** A state or territory: what the county drill-down and the FIPS table apply to. */
    public static boolean isKnown(String code) {
        return code != null && NAMES.containsKey(code.toUpperCase(java.util.Locale.US));
    }

    /** Anything the feed will accept as an area, marine included. */
    public static boolean isArea(String code) {
        return isKnown(code) || isMarine(code);
    }

    /**
     * The SAME code for a county zone id: {@code CAC025} to {@code 006025}. Null when
     * the id is not a county zone or the state is not one we hold.
     */
    public static String sameForCountyZone(String zoneId) {
        if (zoneId == null || zoneId.length() != 6 || zoneId.charAt(2) != 'C')
            return null;
        final String fips = FIPS.get(zoneId.substring(0, 2).toUpperCase(java.util.Locale.US));
        return fips == null ? null : "0" + fips + zoneId.substring(3);
    }

    /** The state a SAME code belongs to, as a two-letter code; null when unknown. */
    public static String stateOfSame(String same) {
        if (same == null || same.length() != 6)
            return null;
        final String fips = same.substring(1, 3);
        for (Map.Entry<String, String> e : FIPS.entrySet())
            if (e.getValue().equals(fips))
                return e.getKey();
        return null;
    }

    /**
     * The state-identifying prefix of a SAME code -- the first three characters, which
     * are {@code "0"} plus the state FIPS. Used to ask "does this alert touch a state
     * the user has narrowed to particular counties".
     */
    public static String statePrefixOfSame(String same) {
        return same == null || same.length() != 6 ? null : same.substring(0, 3);
    }

    /**
     * The county zone URL for a SAME code: {@code 006059} to
     * {@code https://api.weather.gov/zones/county/CAC059}.
     *
     * <p>This is the one place the plugin builds a zone URL rather than using one the
     * feed handed it, because there is no alert to take it from -- the operator picked
     * the county, not the weather. It is built from the shipped FIPS table, which the
     * verifier gates, and it goes through the same origin allowlist as every other
     * zone fetch.
     */
    public static String countyZoneUrl(String same) {
        final String state = stateOfSame(same);
        if (state == null)
            return null;
        return "https://api.weather.gov/zones/county/" + state + "C" + same.substring(3);
    }

    /** The same prefix, for a state code. */
    public static String statePrefix(String code) {
        final String fips = code == null ? null
                : FIPS.get(code.toUpperCase(java.util.Locale.US));
        return fips == null ? null : "0" + fips;
    }
}
