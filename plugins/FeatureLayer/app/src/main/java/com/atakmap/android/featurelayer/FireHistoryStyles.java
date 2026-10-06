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
 * <li>Every earlier year, InterAgency Fire Perimeter History (all years, 1900 on), one
 * layer per decade as EGP splits it: 2010s, 2000s, 1990s, 1980s, 1979 and earlier
 * (which takes unknown years too). 2020 on is the current decade's.
 * </ul>
 *
 * One departure from EGP: EGP greys every decade, and the 2010s, 2000s and 1990s have
 * their own colors here, rust, brown and tan, older reading duller (operator,
 * 2026-10-06: "a 20 year old burn scar is not that old and still relevant"). 1980s and
 * earlier stay grey. No red, which on this map means a fire burning now.
 *
 * Each band and each decade is its own type, so the Features list is the decade picker.
 * Labels are EGP's: "Carr Fire (2018)", only for fires over 20 acres.
 */
public final class FireHistoryStyles {

    /** EGP's grey, for the decades too old to tell apart by color. */
    private static final int GREY = 0x767676;

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
            { D2010, 0xB8603E, "Fires of 2010 to 2019" },
            { D2000, 0x8C6A4A, "Fires of 2000 to 2009" },
            { D1990, 0xC2A679, "Fires of 1990 to 1999" },
            { D1980, GREY, "Fires of 1980 to 1989" },
            { EARLIER, GREY, "Fires before 1980, and fires of unknown year" },
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

    /** How far back a type is: 0 for Under 6 Months up to 9 for 1979 and Earlier; past them all when unknown. */
    static int age(String type) {
        for (int i = 0; i < KEY.length; i++)
            if (KEY[i][0].equals(type))
                return i;
        return KEY.length;
    }

    /** The fill a type gets, opaque RGB; grey for anything unknown. */
    public static int hue(String type) {
        for (Object[] k : KEY)
            if (k[0].equals(type))
                return (Integer) k[1];
        return GREY;
    }

    /** EGP's fill and edge for a type, at the given fill alpha (0-255). */
    static Style style(String type, int alpha) {
        final boolean grey = hue(type) == GREY;
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
        // EGP adds "Fire" unless the name ends in it; a name with Fire inside it, "Ridge
        // Fire #98", read as "Ridge Fire #98 Fire".
        return (" " + s + " ").contains(" Fire ") ? s : s + " Fire";
    }

    /**
     * What two copies of one fire share: name and year. The all-years history holds one
     * perimeter per agency that mapped a fire (Carr 2018 four times: BLM, CAL FIRE, USFS,
     * NPS). Copies are then told apart by place: their extents overlap. A grid cell of
     * the center was the first rule, and the two Ranch 2007 copies (USFS 58,410 ac, FWS
     * 57,573 ac) sat 800 m apart either side of a cell line (operator, 2026-10-06).
     */
    static String nameYear(JSONObject p) {
        return String.valueOf(year(p));
    }

    /**
     * A name as copies of one fire share it: upper case, the word Fire (and Fires,
     * Wildfire) dropped, spaces and punctuation gone. The agencies write one fire many
     * ways: "RIDGE FIRE #98" and "RIDGE #98", "GORMAN FIRE" and "GORMAN", "TOWERHOUSE"
     * and "TOWER HOUSE", "CARR " with a trailing space (287 such pairs around Castaic
     * alone, 2026-10-06). A number stays: "BACKBONE" and "BACKBONE #2" are two fires.
     */
    static String normName(JSONObject p) {
        final StringBuilder b = new StringBuilder();
        for (String w : rawName(p).toUpperCase(Locale.US).replaceAll("[^A-Z0-9]+", " ").trim().split(" "))
            if (!w.equals("FIRE") && !w.equals("FIRES") && !w.equals("WILDFIRE"))
                b.append(w);
        return b.toString();
    }

    /**
     * Whether a burn is another copy of one already kept: the same year (the caller's
     * key), overlapping extents, and the same name as normName writes it, or the same
     * acres within half a percent ("SHU LIGHTNING-MOTION FIRE" and "Motion", both 28,330
     * ac). Half a percent keeps neighbors apart: China and Saint Claire 1987 differ by
     * 0.9%, Sugar and Jackass 1999 by 7%.
     */
    static boolean sameFire(String nameA, double acresA, double[] boxA, String nameB, double acresB, double[] boxB) {
        if (!overlap(boxA, boxB))
            return false;
        if (!nameA.isEmpty() && nameA.equals(nameB))
            return true;
        final double big = Math.max(acresA, acresB);
        return big > 0 && Math.abs(acresA - acresB) / big <= 0.005;
    }

    /** Whether two extents (minX, minY, maxX, maxY) overlap: the test for two copies of one fire. */
    static boolean overlap(double[] a, double[] b) {
        return a[0] <= b[2] && b[0] <= a[2] && a[1] <= b[3] && b[1] <= a[3];
    }

    /** The field a source names its fires in: the current decade's, or the all-years history's. */
    static String nameField(boolean currentDecade) {
        return currentDecade ? "poly_IncidentName" : "INCIDENT";
    }

    /** When the fire burned, epoch ms, for sorting by Newest and Oldest: discovery, else 1 July of its year. */
    static long when(JSONObject p) {
        final Object v = p.opt("attr_FireDiscoveryDateTime");
        if (v instanceof Number)
            return ((Number) v).longValue();
        final int y = year(p);
        if (y <= 0)
            return 0;
        final java.util.Calendar c = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"));
        c.clear();
        c.set(y, java.util.Calendar.JULY, 1);
        return c.getTimeInMillis();
    }
}
