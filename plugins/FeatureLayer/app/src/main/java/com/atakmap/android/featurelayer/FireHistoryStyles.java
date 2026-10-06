package com.atakmap.android.featurelayer;

import com.atakmap.map.layer.feature.style.Style;

import org.json.JSONObject;

import java.util.Locale;

/**
 * Fire History, the way EGP draws it (operator, 2026-10-06: "anytime we can follow EGP
 * lets do it ... go back as far as we can with data but maybe ability to choose by
 * decade"). Two NIFC services, read from EGP's own maps that day:
 *
 * <ul>
 * <li>The current decade, WFIGS Interagency Fire Perimeters (2016 on), colored by years
 * since the fire was found, EGP's "Wildland Fire History - Current Decade" item
 * cd338a45163744009ee2d91bd9679b9f: under half a year purple, half to one year blue,
 * one to two green, two to three yellow, three to ten orange; nothing older than ten;
 * grey edge; half see-through.
 * <li>Every earlier year, InterAgency Fire Perimeter History (all years, 1900 on), grey,
 * one layer per decade as EGP splits it: 2010s, 2000s, 1990s, 1980s, 1979 and earlier
 * (which takes unknown years too). 2020 on is the current decade's.
 * </ul>
 *
 * Each band and each decade is its own type, so the Features list is the decade picker.
 * Labels are EGP's: "Carr Fire (2018)", only for fires over 20 acres.
 */
public final class FireHistoryStyles {

    public static final String UNDER_HALF = "Under 6 Months", HALF_TO_ONE = "6 Months to 1 Year",
            ONE_TO_TWO = "1 to 2 Years", TWO_TO_THREE = "2 to 3 Years", THREE_TO_TEN = "3 to 10 Years",
            D2010 = "2010s", D2000 = "2000s", D1990 = "1990s", D1980 = "1980s", EARLIER = "1979 and Earlier";

    /** The types in the order the Features list and the map key show them, with EGP's fills. */
    public static final Object[][] KEY = {
            { UNDER_HALF, 0x9E559C, "Burned in the last six months" },
            { HALF_TO_ONE, 0x149ECE, "Burned six months to a year ago" },
            { ONE_TO_TWO, 0xA7C636, "Burned one to two years ago" },
            { TWO_TO_THREE, 0xFFDE3E, "Burned two to three years ago" },
            { THREE_TO_TEN, 0xFC921F, "Burned three to ten years ago" },
            { D2010, 0x767676, "Fires of 2010 to 2019" },
            { D2000, 0x767676, "Fires of 2000 to 2009" },
            { D1990, 0x767676, "Fires of 1990 to 1999" },
            { D1980, 0x767676, "Fires of 1980 to 1989" },
            { EARLIER, 0x767676, "Fires before 1980, and fires of unknown year" },
    };

    private static final int AGE_EDGE = 0xCC999999, GREY_EDGE = 0xFF000000;
    private static final double YEAR_MS = 365.2425 * 24 * 3600_000d;

    private FireHistoryStyles() {
    }

    public static boolean handles(LayerSpec spec) {
        return spec != null && "firehistory".equals(spec.iconSet);
    }

    static void notes(LayerSpec s) {
        for (Object[] k : KEY) {
            s.setNotes.put((String) k[0], (String) k[2]);
            s.setKind.put((String) k[0], "polygon");
        }
    }

    /** Whether a row is from the current-decade service (it carries IRWIN's discovery time). */
    private static boolean currentDecade(JSONObject p) {
        return p.has("attr_FireDiscoveryDateTime") || p.has("poly_IncidentName");
    }

    /** Which band or decade a perimeter is, or null when EGP draws it in neither. */
    public static String type(JSONObject p, long nowMs) {
        if (currentDecade(p)) {
            final Object v = p.opt("attr_FireDiscoveryDateTime");
            if (!(v instanceof Number))
                return null;
            final double years = Math.abs(nowMs - ((Number) v).longValue()) / YEAR_MS;
            if (years < 0.5)
                return UNDER_HALF;
            if (years < 1)
                return HALF_TO_ONE;
            if (years < 2)
                return ONE_TO_TWO;
            if (years < 3)
                return TWO_TO_THREE;
            if (years <= 10)
                return THREE_TO_TEN;
            return null;
        }
        final int y = p.isNull("FIRE_YEAR_INT") ? 0 : p.optInt("FIRE_YEAR_INT", 0);
        if (y >= 2020 && y != 9999)
            return null; // the current decade's, drawn from its own service
        if (y >= 2010 && y <= 2019)
            return D2010;
        if (y >= 2000)
            return y <= 2009 ? D2000 : EARLIER;
        if (y >= 1990)
            return D1990;
        if (y >= 1980)
            return D1980;
        return EARLIER;
    }

    /** The fill a type gets, opaque RGB; grey for anything unknown. */
    public static int hue(String type) {
        for (Object[] k : KEY)
            if (k[0].equals(type))
                return (Integer) k[1];
        return 0x767676;
    }

    /** EGP's fill and edge for a type, at the given fill alpha (0-255). */
    static Style style(String type, int alpha) {
        final boolean grey = hue(type) == 0x767676;
        final Style edge = NwcgStyles.solid(grey ? GREY_EDGE : AGE_EDGE, grey ? 1f : 1.5f);
        if (alpha <= 0)
            return edge;
        return NwcgStyles.over(new com.atakmap.map.layer.feature.style.BasicFillStyle(
                (Math.min(255, alpha) << 24) | hue(type)), edge);
    }

    /** The fire's year: from discovery on the current decade, the recorded year before; 0 when unknown. */
    static int year(JSONObject p) {
        if (currentDecade(p)) {
            final Object v = p.opt("attr_FireDiscoveryDateTime");
            if (!(v instanceof Number))
                return 0;
            final java.util.Calendar c = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"));
            c.setTimeInMillis(((Number) v).longValue());
            return c.get(java.util.Calendar.YEAR);
        }
        final int y = p.isNull("FIRE_YEAR_INT") ? 0 : p.optInt("FIRE_YEAR_INT", 0);
        return y > 1800 && y < 2100 ? y : 0;
    }

    static double acres(JSONObject p) {
        for (String k : new String[] { "poly_Acres_AutoCalc", "poly_GISAcres", "GIS_ACRES" }) {
            final double a = p.isNull(k) ? -1 : p.optDouble(k, -1);
            if (a > 0)
                return a;
        }
        return -1;
    }

    static String rawName(JSONObject p) {
        for (String k : new String[] { "attr_IncidentName", "poly_IncidentName", "INCIDENT" }) {
            final String n = p.isNull(k) ? "" : p.optString(k, "").trim();
            if (!n.isEmpty() && !"null".equalsIgnoreCase(n))
                return n;
        }
        return "";
    }

    /**
     * EGP's label: the name in proper case, "Fire" added when it does not end in it, the
     * year in brackets: "Carr Fire (2018)". Null for 20 acres and under, as EGP's.
     */
    public static String label(JSONObject p) {
        final String n = rawName(p);
        if (n.isEmpty() || acres(p) <= 20)
            return null;
        final int y = year(p);
        return named(n) + (y > 0 ? " (" + y + ")" : "");
    }

    /** "Carr Fire (2018) · 229,651 ac": the list's and the chooser's title, whatever the size. */
    public static String title(JSONObject p, String fallback) {
        final String n = rawName(p);
        final int y = year(p);
        final double a = acres(p);
        final String base = (n.isEmpty() ? (fallback == null ? "Fire" : fallback) : named(n)) + (y > 0 ? " (" + y + ")" : "");
        return a > 0 ? base + " · " + NewStartsStyles.formatAcres(a) : base;
    }

    private static String named(String n) {
        final StringBuilder b = new StringBuilder();
        for (String w : n.toLowerCase(Locale.US).split("\\s+")) {
            if (w.isEmpty())
                continue;
            if (b.length() > 0)
                b.append(' ');
            b.append(Character.toUpperCase(w.charAt(0))).append(w.substring(1));
        }
        final String s = b.toString();
        return s.endsWith("Fire") ? s : s + " Fire";
    }

    /**
     * The key that makes two copies of one fire the same: name, year and a 0.2 degree
     * cell of where it is. The all-years history holds one perimeter per agency that
     * mapped a fire (Carr 2018 four times: BLM, CAL FIRE, USFS, NPS, all ~229,650 ac).
     */
    static String sameFire(JSONObject p, double lat, double lon) {
        return rawName(p).toUpperCase(Locale.US) + "|" + year(p) + "|" + Math.round(lat * 5) + "|" + Math.round(lon * 5);
    }
}
