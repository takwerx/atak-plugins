package com.atakmap.android.iwi.plugin;


import com.atakmap.coremap.maps.coords.GeoPoint;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * One hospital in the pack: its ER, trauma level, burn center and helipad.
 *
 * <p>Built by {@code tools/build_hospital_pack.py}. Where a state publishes its own
 * lists (California first: EMSA trauma and burn lists, HCAI's ER permits) those decide;
 * elsewhere the row is a HIFLD trauma center with a pad and its ER is unknown. When the
 * FAA charts the pad, {@link #target()} is the pad, because that is where the aircraft
 * goes; otherwise it is the hospital's own point.
 *
 * <p>No ATAK Views here: this can be run against the pack on a desktop JVM.
 */
public final class TraumaCenter {

    public String id = "";
    public String name = "";
    public String city = "";
    public String state = "";
    /** "comprehensive", "basic", "standby", or "" when the pack does not say. */
    public String er = "";
    /** Adult trauma level 1 to 5, or 0 when not an adult trauma center. */
    public int adult;
    /** Pediatric trauma level 1 or 2, or 0. */
    public int ped;
    /** 1 to 5 for ranking: the adult level, else the pediatric one; 0 when not trauma. */
    public int level;
    /** A children's hospital: the only level designated is pediatric. */
    public boolean pediatricOnly;
    public boolean burn;
    public boolean burnAdult;
    public boolean burnVerified;
    public String burnName = "";
    /** The state publishes its own lists, so a missing level means "not trauma". */
    public boolean listed;
    public double lat = Double.NaN;
    public double lon = Double.NaN;

    public boolean padCharted;
    /** HIFLD says the hospital has a pad the FAA does not chart. */
    public boolean padReported;
    public String padId = "";
    public String padName = "";
    public double padLat = Double.NaN;
    public double padLon = Double.NaN;
    /** Feet, or -1 when the FAA does not give one. */
    public int padElevFt = -1;

    /** Where a helicopter would land: the charted pad, else the hospital. */
    public GeoPoint target() {
        if (padCharted && valid(padLat, padLon))
            return new GeoPoint(padLat, padLon);
        return new GeoPoint(lat, lon);
    }

    public boolean hasPad() {
        return padCharted || padReported;
    }

    public boolean hasEr() {
        return !er.isEmpty() || level > 0;
    }

    /** An adult trauma center: a crew member is never sent to a children's-only one. */
    public boolean adultTrauma() {
        return adult > 0;
    }

    public boolean adultBurn() {
        return burn && burnAdult;
    }

    /** "Level II", or "Pediatric Level I" for a children's hospital. */
    public String levelLabel() {
        if (adult > 0)
            return "Level " + roman(adult);
        if (ped > 0)
            return "Pediatric Level " + roman(ped);
        return "";
    }

    /**
     * What it is, in capitals where it matters (operator, 2026-11-09: "the label needs
     * to clearly state trauma vs regular ER and level of trauma center"): "TRAUMA Level
     * II", "TRAUMA Level I + BURN", "BURN CENTER", "ER, not a trauma center". The last
     * only where a state list is the authority; elsewhere "ER, trauma level not known".
     */
    public String kindLabel() {
        final StringBuilder b = new StringBuilder();
        if (level > 0)
            b.append("TRAUMA ").append(levelLabel());
        if (burn)
            b.append(b.length() == 0 ? "BURN CENTER" : " + BURN");
        if (b.length() == 0) {
            final String erWord = "standby".equals(er) ? "Standby ER" : "ER";
            b.append(erWord).append(listed ? ", not a trauma center"
                    : ", trauma level not known");
        }
        return b.toString();
    }

    public static String roman(int level) {
        switch (level) {
            case 1:
                return "I";
            case 2:
                return "II";
            case 3:
                return "III";
            case 4:
                return "IV";
            default:
                return "V";
        }
    }

    static boolean valid(double lat, double lon) {
        return !Double.isNaN(lat) && !Double.isNaN(lon)
                && lat >= -90 && lat <= 90 && lon >= -180 && lon <= 180
                && !(lat == 0 && lon == 0);
    }

    /** Every center in the pack whose position can be read, in the pack's order. */
    public static List<TraumaCenter> parse(JSONObject root) {
        final List<TraumaCenter> out = new ArrayList<>();
        final JSONArray rows = root == null ? null : root.optJSONArray("hospitals");
        if (rows == null)
            return out;
        final JSONObject states = root.optJSONObject("states");
        for (int i = 0; i < rows.length(); i++) {
            final TraumaCenter c = one(rows.optJSONObject(i));
            if (c == null)
                continue;
            c.listed = states != null && states.has(c.state);
            out.add(c);
        }
        return out;
    }

    private static TraumaCenter one(JSONObject o) {
        if (o == null)
            return null;
        final TraumaCenter c = new TraumaCenter();
        c.id = o.optString("id", "");
        c.name = o.optString("name", "").trim();
        c.city = o.optString("city", "").trim();
        c.state = o.optString("state", "").trim();
        c.er = o.optString("er", "").trim();
        c.adult = clamp(o.optInt("trauma", 0), 5);
        c.ped = clamp(o.optInt("ped", 0), 5);
        c.level = c.adult > 0 ? c.adult : c.ped;
        c.pediatricOnly = c.adult == 0 && c.ped > 0;
        final JSONObject burn = o.optJSONObject("burn");
        if (burn != null) {
            c.burn = true;
            c.burnAdult = burn.optBoolean("adult", true);
            c.burnVerified = burn.optBoolean("verified", false);
            c.burnName = burn.optString("name", "").trim();
        }
        c.lat = o.optDouble("lat", Double.NaN);
        c.lon = o.optDouble("lon", Double.NaN);
        if (c.name.isEmpty() || !valid(c.lat, c.lon))
            return null;
        final JSONObject pad = o.optJSONObject("pad");
        if (pad != null && !pad.optBoolean("charted", false))
            c.padReported = true;
        if (pad != null && pad.optBoolean("charted", false)) {
            c.padLat = pad.optDouble("lat", Double.NaN);
            c.padLon = pad.optDouble("lon", Double.NaN);
            if (valid(c.padLat, c.padLon)) {
                c.padCharted = true;
                c.padId = pad.optString("id", "").trim();
                c.padName = pad.optString("name", "").trim();
                c.padElevFt = pad.optInt("elev_ft", -1);
            }
        }
        if (c.level == 0 && !c.burn && c.er.isEmpty())
            return null;
        return c;
    }

    private static int clamp(int v, int max) {
        return v < 0 || v > max ? 0 : v;
    }
}
