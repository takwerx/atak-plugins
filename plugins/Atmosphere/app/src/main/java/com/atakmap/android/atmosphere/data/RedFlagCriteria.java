package com.atakmap.android.atmosphere.data;

import com.atakmap.android.atmosphere.data.RedFlag.Criteria;
import com.atakmap.android.atmosphere.data.RedFlag.Leg;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Each office's Red Flag Warning criteria, by fire weather zone.
 *
 * <p>Transcribed from the fire weather annual operating plans, keyed by the zone id
 * NWS uses ({@code CA548}) and, where a plan states one rule for a whole office, by
 * the office ({@code SEW}) for the states that plan covers. A zone with an entry of
 * its own wins over its office's; a zone with neither gets {@link Criteria#NATIONAL},
 * and the record says so.
 *
 * <p>What is kept is what one observation can be held against: the humidity ceiling
 * and the wind floors, sustained and gust. Every plan also asks for a duration (two
 * to eight hours), for fuels to be receptive, and often for a fire danger rating; the
 * record names the source so those are read there. Where a plan writes "less than"
 * it is taken as one below: the readings are whole numbers, so "RH less than 15%" is
 * 14% or less.
 *
 * <p>The sources, and what each leaves out:
 * <ul>
 *   <li><b>California</b>, Fire Weather AOP 2026, Appendix B, including both
 *       decision matrices (weather.gov/media/rev/Fire/CA_AOP.pdf). It skips CA514
 *       and CA515 (East Bay Hills), so those fall to the common pair.</li>
 *   <li><b>Great Basin</b>, AOP 2026, the "RFW Criteria for Wind and RH" map
 *       (weather.gov/media/wrh/fire_AOP/GB_AOP.pdf): RH 15% or less everywhere,
 *       gusts 30 for the green zones, 25 for the purple, and sustained 20 or gusts
 *       35 for Las Vegas and Flagstaff. Each office's own text agrees with its
 *       colors; Riverton's says sustained or gusts, so WY carries both floors.</li>
 *   <li><b>Southwest</b>, AOP 2026-2029 (gacc.nifc.gov/swcc): one standard for
 *       every office, sustained 20 or gusts 35 with RH 15% or less, plus a fire
 *       danger rating; the New Mexico offices also use RFTI, which no observation
 *       carries. Midland's New Mexico zones follow the Texas AOP, not loaded.</li>
 *   <li><b>Northwest</b>, AOP 2023, the latest published (gacc.nifc.gov/nwcc):
 *       zone by zone for Medford, Pendleton and Spokane, an office rule for
 *       Portland (day and night legs), Seattle (west of the crest) and Boise's
 *       Oregon zones (the wind/RH matrix). Medford's nighttime "poor recovery" legs
 *       are left out: they are about the overnight maximum, not a reading.</li>
 * </ul>
 */
public final class RedFlagCriteria {

    private static final String AOP = "California Fire Weather AOP 2026, Appendix B";
    private static final String GB = "Great Basin Fire Weather AOP 2026, RFW criteria map";
    private static final String SW = "Southwest Area Fire Weather AOP 2026-2029";
    private static final String NW = "Northwest Area Fire Weather AOP 2023";

    private static final Map<String, Criteria> BY_ZONE = new HashMap<>();
    /** By office, then by the state a zone's id starts with. */
    private static final Map<String, Map<String, Criteria>> BY_OFFICE = new HashMap<>();

    static {
        // Northern California west of the Cascade/Sierra crest: the Wind/RH decision
        // matrix, read row by row. Each row is a daytime-minimum humidity band and the
        // sustained wind at which the cell first says RFW; a drier row keeps every
        // wetter row's cell, so the legs are OR'd. Sustained wind only -- the matrix
        // has no gust column -- and it assumes 10-hour fuels at 6% or less, cured
        // grass, no wetting rain in 24 hours, and an event of 8 hours or more.
        zones("CA", "006,121-164,401-421,505-513,516-518,528-530",
                new Criteria(AOP + " (northern California west of the crest, "
                        + "Wind/RH decision matrix)",
                        new Leg(42, 30, Double.NaN), new Leg(28, 21, Double.NaN),
                        new Leg(18, 12, Double.NaN), new Leg(8, 6, Double.NaN)));
        // Southern California desert area excluding the Lower Colorado River Valley.
        zones("CA", "226-228,230,232", new Criteria(AOP + " (desert zones)",
                new Leg(15, Double.NaN, 35)));
        // Lower Colorado River Valley.
        zones("CA", "229,231", new Criteria(AOP + " (Lower Colorado River Valley)",
                new Leg(15, 20, 35)));
        // Antelope Valley and SE Kern County deserts (Kern Desert zones).
        zones("CA", "298,299,381-383", new Criteria(AOP + " (Kern desert zones)",
                new Leg(10, 25, Double.NaN)));
        // Central California interior except Kern County (WFO Hanford).
        zones("CA", "579,580,590-594", new Criteria(AOP + " (WFO Hanford)",
                new Leg(15, 25, 35)));
        // Southern California excluding the Antelope Valley (WFO Los Angeles): two
        // tiers, the drier one at a lower wind.
        zones("CA", "238,288,340-353,354-358,362,366-370,372-380,548",
                new Criteria(AOP + " (WFO Los Angeles)",
                        new Leg(10, 15, 25), new Leg(15, 25, 35)));
        // Kern County mountains: the same two tiers.
        zones("CA", "595-597", new Criteria(AOP + " (Kern County mountains)",
                new Leg(15, 25, 35), new Leg(10, 15, 25)));
        // Extreme southern California (WFO San Diego).
        zones("CA", "243,248,250,255-258,260-262,265,552,554",
                new Criteria(AOP + " (WFO San Diego)", new Leg(15, 25, 35)));
        // Northern California west of the crest, WFO Medford's zones: dry cold
        // fronts, and east winds.
        zones("CA", "280,282", new Criteria(AOP + " (WFO Medford)",
                new Leg(15, 10, 20), new Leg(25, 15, 25)));
        // Eastern Sierra and northeast California (WFO Reno): gusts, no sustained
        // floor. The text entry is RH 15% or less with gusts 30 or more; Reno's
        // east-of-crest matrix on the next page keeps that cell and adds two at
        // higher gusts, so the matrix is what is held here.
        zones("CA", "214,270,271,274,278,281,284,285", new Criteria(AOP + " (WFO Reno)",
                new Leg(15, Double.NaN, 30), new Leg(20, Double.NaN, 40),
                new Leg(25, Double.NaN, 50)));
        // Lake Tahoe Basin.
        zones("CA", "272", new Criteria(AOP + " (Lake Tahoe Basin)",
                new Leg(20, Double.NaN, 30)));
        // ---- Great Basin: the map, RH 15% or less for every zone ----
        final Criteria gbGreen = new Criteria(GB + " (gusts 30 mph)",
                new Leg(15, Double.NaN, 30));
        final Criteria gbPurple = new Criteria(GB + " (gusts 25 mph)",
                new Leg(15, Double.NaN, 25));
        // Boise's BLM zones and the Snake River Plain; Elko; Reno's Nevada zones;
        // Salt Lake City's western and southern zones.
        zones("ID", "400,410,420,423,424,425,426", gbGreen);
        zones("NV", "420,421,423,424,425,426,427,429,437,438,458,469,470", gbGreen);
        zones("UT", "478,492,495,497,498", gbGreen);
        // Boise's and Payette's forest zones, the southeast Idaho mountains, the
        // Wasatch and eastern Utah (Grand Junction's 485-487, 490, 491 among them).
        zones("ID", "401,402,403,411,413,421,422,427,475,476", gbPurple);
        zones("UT", "479,480,481,482,483,484,485,486,487,488,489,490,491,493,494,496",
                gbPurple);
        // Riverton: "sustained winds or frequent gusts of 25 mph or higher".
        zones("WY", "414,415,416", new Criteria(GB + " (WFO Riverton)", new Leg(15, 25, 25)));
        // Las Vegas: sustained 20 or gusts 35, the office's own words; the map's pink.
        zones("NV", "460,461,462,463,464,465,466", new Criteria(GB + " (WFO Las Vegas)",
                new Leg(15, 20, 35)));

        // ---- Southwest: one standard, by office ----
        final Criteria sw = new Criteria(SW, new Leg(15, 20, 35));
        office("VEF", "AZ", sw);
        office("FGZ", "AZ", sw);
        office("TWC", "AZ", sw);
        office("PSR", "AZ", sw);
        office("ABQ", "NM", new Criteria(SW + " (WFO Albuquerque; RFTI also used)",
                new Leg(15, 20, 35)));
        office("EPZ", "NM,TX", new Criteria(SW + " (WFO El Paso; RFTI also used)",
                new Leg(15, 20, 35)));

        // ---- Northwest ----
        // Boise's Oregon zones: the wind/RH matrix by row, and gusts 35 at RH 10.
        office("BOI", "OR", new Criteria(NW + " (WFO Boise, wind/RH matrix)",
                new Leg(20, 30, Double.NaN), new Leg(15, 25, Double.NaN),
                new Leg(10, 20, Double.NaN), new Leg(10, Double.NaN, 36)));
        // Medford, zone by zone; "less than" is one below.
        zones("OR", "615,618", new Criteria(NW + " (WFO Medford)", new Leg(29, 15, 30)));
        zones("OR", "616,617,619,620,621,622,623", new Criteria(NW + " (WFO Medford)",
                new Leg(14, 10, 20)));
        zones("OR", "624", new Criteria(NW + " (WFO Medford)", new Leg(14, 15, 25)));
        zones("OR", "625", new Criteria(NW + " (WFO Medford)", new Leg(9, 20, Double.NaN),
                new Leg(14, 25, Double.NaN), new Leg(19, 30, Double.NaN)));
        // Pendleton: named zones, then the two tables.
        zones("OR", "639", new Criteria(NW + " (WFO Pendleton)", new Leg(20, 10, Double.NaN)));
        zones("OR", "610,611", new Criteria(NW + " (WFO Pendleton)",
                new Leg(15, 10, Double.NaN)));
        zones("WA", "690", new Criteria(NW + " (WFO Pendleton)", new Leg(20, 15, Double.NaN)));
        zones("WA", "695", new Criteria(NW + " (WFO Pendleton)", new Leg(25, 15, Double.NaN)));
        final Criteria basin = new Criteria(NW + " (WFO Pendleton, Columbia Basin table)",
                new Leg(35, 30, Double.NaN), new Leg(30, 25, Double.NaN),
                new Leg(25, 20, Double.NaN), new Leg(20, 15, Double.NaN));
        zones("OR", "641", basin);
        zones("WA", "691", basin);
        final Criteria mountains = new Criteria(
                NW + " (WFO Pendleton, Central and Northeast Oregon Mountains table)",
                new Leg(30, 30, Double.NaN), new Leg(25, 25, Double.NaN),
                new Leg(20, 20, Double.NaN), new Leg(15, 15, Double.NaN));
        zones("OR", "640,642,643,644,645", mountains);
        zones("WA", "692,693,694", mountains);
        // Portland: a day leg and a night leg for the office; the valley zones want
        // more wind.
        office("PQR", "OR,WA", new Criteria(NW + " (WFO Portland; day 25%, night 35%)",
                new Leg(25, 10, 20), new Leg(35, 15, 25)));
        zones("OR", "604", new Criteria(NW + " (WFO Portland, valley zones)",
                new Leg(30, 15, 25)));
        zones("WA", "667", new Criteria(NW + " (WFO Portland, valley zones)",
                new Leg(30, 15, 25)));
        // Seattle, west of the crest: day RH 30% or less with wind over 10 (RAWS);
        // night 35% or less with wind 10 or more.
        office("SEW", "WA", new Criteria(NW + " (WFO Seattle; day 30%, night 35%)",
                new Leg(30, 11, Double.NaN), new Leg(35, 10, Double.NaN)));
        // Spokane: sustained over 15 with RH under 15, 20 or 25 by terrain.
        zones("WA", "706,707", new Criteria(NW + " (WFO Spokane, Columbia Basin)",
                new Leg(14, 16, Double.NaN)));
        zones("WA", "703,704,705,708,709", new Criteria(NW + " (WFO Spokane, lower valleys)",
                new Leg(19, 16, Double.NaN)));
        zones("WA", "696,697,698,699,700,701,702", new Criteria(NW + " (WFO Spokane, mountains)",
                new Leg(24, 16, Double.NaN)));
        zones("ID", "101", new Criteria(NW + " (WFO Spokane, mountains)",
                new Leg(24, 16, Double.NaN)));
    }

    /** The criteria for a zone, or null when the table has none for it or its office. */
    public static Criteria forZone(FireZones.Zone zone) {
        return zone == null ? null : forZone(zone.id, zone.cwa);
    }

    /**
     * By id ({@code CA548}) and office ({@code LOX}): the zone's own entry, else the
     * office's for the state the id starts with, else null.
     */
    public static Criteria forZone(String id, String cwa) {
        if (id == null)
            return null;
        final Criteria own = BY_ZONE.get(id);
        if (own != null)
            return own;
        if (cwa == null || id.length() < 2)
            return null;
        final Map<String, Criteria> byState = BY_OFFICE.get(cwa);
        return byState == null ? null : byState.get(id.substring(0, 2));
    }

    /** By id alone: the zone's own entry, never an office's. */
    public static Criteria forZoneId(String id) {
        return id == null ? null : BY_ZONE.get(id);
    }

    public static int size() {
        return BY_ZONE.size();
    }

    /**
     * Put one criteria set on every zone in a list the way the appendix writes it:
     * "226-228,230,232", numbers and ranges, on one state.
     */
    static void zones(String state, String list, Criteria c) {
        for (String part : list.split(",")) {
            final String p = part.trim();
            if (p.isEmpty())
                continue;
            final int dash = p.indexOf('-');
            final int from = Integer.parseInt(dash < 0 ? p : p.substring(0, dash));
            final int to = Integer.parseInt(dash < 0 ? p : p.substring(dash + 1));
            for (int z = from; z <= to; z++)
                BY_ZONE.put(String.format(Locale.US, "%s%03d", state, z), c);
        }
    }

    /** One criteria set for every zone of an office in the named states, "AZ" or "OR,WA". */
    static void office(String cwa, String states, Criteria c) {
        Map<String, Criteria> byState = BY_OFFICE.get(cwa);
        if (byState == null) {
            byState = new HashMap<>();
            BY_OFFICE.put(cwa, byState);
        }
        for (String st : states.split(","))
            byState.put(st.trim(), c);
    }

    private RedFlagCriteria() {
    }
}
