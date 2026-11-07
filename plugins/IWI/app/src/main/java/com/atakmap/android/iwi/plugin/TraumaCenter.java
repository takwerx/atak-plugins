package com.atakmap.android.iwi.plugin;


import com.atakmap.coremap.maps.coords.GeoPoint;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * One trauma center with a helipad, as the pack describes it.
 *
 * <p>Built by {@code tools/build_trauma_pack.py} from HIFLD's hospital list (trauma
 * level, helipad flag) joined to the FAA's charted heliports. When the FAA charts the
 * pad, {@link #target()} is the pad, because that is where the aircraft goes; when only
 * the hospital says it has one, the target is the hospital's own point and the details
 * page says the pad is not charted.
 *
 * <p>No ATAK Views here: this can be run against the pack on a desktop JVM.
 */
public final class TraumaCenter {

    public String id = "";
    public String name = "";
    public String city = "";
    public String state = "";
    /** 1 to 5; 1 is the most capable. */
    public int level = 5;
    /** "Level II", or "Level I, Level II pediatric" where both are designated. */
    public String levelText = "";
    public boolean pediatric;
    /** A children's hospital: the only level designated is pediatric. */
    public boolean pediatricOnly;
    public double lat = Double.NaN;
    public double lon = Double.NaN;

    public boolean padCharted;
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

    /** "Level II", with "pediatric" when that is the only designation. */
    public String levelLabel() {
        if (!levelText.isEmpty())
            return pediatricOnly ? levelText : levelText.split(",")[0].trim();
        return "Level " + roman(level);
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
        final JSONArray centers = root == null ? null : root.optJSONArray("centers");
        if (centers == null)
            return out;
        for (int i = 0; i < centers.length(); i++) {
            final TraumaCenter c = one(centers.optJSONObject(i));
            if (c != null)
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
        c.level = Math.min(5, Math.max(1, o.optInt("level", 5)));
        c.levelText = o.optString("level_text", "").trim();
        c.pediatric = o.optBoolean("pediatric", false);
        c.pediatricOnly = o.optBoolean("pediatric_only", false);
        c.lat = o.optDouble("lat", Double.NaN);
        c.lon = o.optDouble("lon", Double.NaN);
        if (c.name.isEmpty() || !valid(c.lat, c.lon))
            return null;
        final JSONObject pad = o.optJSONObject("pad");
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
        return c;
    }
}
