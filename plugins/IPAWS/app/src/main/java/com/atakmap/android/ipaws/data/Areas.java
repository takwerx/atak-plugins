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

    /** Every code the picker offers, in the order it shows them: alphabetical by name. */
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
        final String n = code == null ? null : NAMES.get(code.toUpperCase(java.util.Locale.US));
        return n == null ? String.valueOf(code) : n;
    }

    public static boolean isKnown(String code) {
        return code != null && NAMES.containsKey(code.toUpperCase(java.util.Locale.US));
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

    /** The same prefix, for a state code. */
    public static String statePrefix(String code) {
        final String fips = code == null ? null
                : FIPS.get(code.toUpperCase(java.util.Locale.US));
        return fips == null ? null : "0" + fips;
    }
}
