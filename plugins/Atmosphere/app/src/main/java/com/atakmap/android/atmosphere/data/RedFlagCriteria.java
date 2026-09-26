package com.atakmap.android.atmosphere.data;

import com.atakmap.android.atmosphere.data.RedFlag.Criteria;
import com.atakmap.android.atmosphere.data.RedFlag.Leg;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Each office's Red Flag Warning criteria, by fire weather zone.
 *
 * <p>Transcribed from the California Fire Weather Annual Operating Plan, 2026,
 * Appendix B ("NWS Red Flag Warning Criteria", weather.gov/media/rev/Fire/CA_AOP.pdf),
 * keyed by the zone id NWS uses ({@code CA548}). A zone with no entry gets
 * {@link Criteria#NATIONAL}, and the record says so.
 *
 * <p>What is kept is what one observation can be held against: the humidity ceiling
 * and the wind floors, sustained and gust. Every entry in the appendix also asks for
 * a duration (three to eight hours) and for fuels to be receptive; the record names
 * the source so those are read there. Where the appendix writes "less than" it is
 * taken as "or less": the readings are whole numbers.
 *
 * <p>The appendix's zone lists skip CA514 and CA515 (East Bay Hills is 515), so
 * those two get the common pair until the AOP names them.
 *
 * <p>Zone 281 is listed under both Medford and Reno; Reno names it with a duration
 * of its own, so it is Reno's here. Zones 260-262 are listed under both the desert
 * group and San Diego; San Diego's is the superset (it adds the sustained floor).
 */
public final class RedFlagCriteria {

    private static final String AOP = "California Fire Weather AOP 2026, Appendix B";

    private static final Map<String, Criteria> BY_ZONE = new HashMap<>();

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
    }

    /** The criteria for a zone, or null when the table has none for it. */
    public static Criteria forZone(FireZones.Zone zone) {
        return zone == null ? null : forZoneId(zone.id);
    }

    /** By id, e.g. {@code CA548}. */
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

    private RedFlagCriteria() {
    }
}
