package com.atakmap.android.atmosphere.data;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * The Weather Prediction Center's excessive rainfall outlook, days 1 to 3: the
 * chance of rain exceeding flash flood guidance within 25 miles of a point,
 * drawn as Marginal / Slight / Moderate / High areas. The codes, labels and
 * colors are the NWS map service's own renderer (read 2026-09-26). Each area
 * carries the outlook's words ("Slight (At Least 15%)"), its valid window as
 * WPC writes it ("12Z 09/27/26 - 12Z 09/28/26") and the issue time.
 *
 * <p>No Android types; tested.
 */
public final class WpcEro {

    public static final String HOST = "mapservices.weather.noaa.gov";
    private static final String SERVICE = "https://" + HOST
            + "/vector/rest/services/hazards/wpc_precip_hazards/MapServer/";

    /** Layer ids 0-2 are days 1-3. */
    public static final int DAYS = 3;

    public static String url(int day) {
        return SERVICE + (day - 1)
                + "/query?where=dn%3E0&outFields=dn,outlook,valid_time,issue_time&outSR=4326&f=geojson";
    }

    public static String label(int dn) {
        switch (dn) {
            case 1:
                return "Marginal";
            case 2:
                return "Slight";
            case 3:
                return "Moderate";
            case 4:
                return "High";
            default:
                return "Category " + dn;
        }
    }

    /** The renderer's colors: green, yellow, red, pink. */
    public static int color(int dn) {
        switch (dn) {
            case 1:
                return 0xFF38A800;
            case 2:
                return 0xFFFFFE00;
            case 3:
                return 0xFFF50000;
            case 4:
                return 0xFFFF69C5;
            default:
                return 0xFFB0B0B0;
        }
    }

    /** What each category means, WPC's own words, for the legend. */
    public static String meaning(int dn) {
        switch (dn) {
            case 1:
                return "at least 5% chance";
            case 2:
                return "at least 15%";
            case 3:
                return "at least 40%";
            case 4:
                return "at least 70%";
            default:
                return "";
        }
    }

    public static final class Area {
        public final int day, dn;
        /** WPC's own words: "Slight (At Least 15%)". */
        public final String outlook, validTime, issued;
        public final JSONObject geometry;

        Area(int day, int dn, String outlook, String validTime, String issued, JSONObject geometry) {
            this.day = day;
            this.dn = dn;
            this.outlook = outlook;
            this.validTime = validTime;
            this.issued = issued;
            this.geometry = geometry;
        }

        public String label() {
            return WpcEro.label(dn);
        }

        public int color() {
            return WpcEro.color(dn);
        }

        /** "Day 2 excessive rainfall: Slight". */
        public String title() {
            return "Day " + day + " excessive rainfall: " + label();
        }
    }

    public static List<Area> parse(String body, int day) {
        final List<Area> out = new ArrayList<>();
        if (body == null || body.isEmpty())
            return out;
        try {
            final JSONArray fs = new JSONObject(body).optJSONArray("features");
            if (fs == null)
                return out;
            for (int i = 0; i < fs.length(); i++) {
                final JSONObject f = fs.optJSONObject(i);
                if (f == null)
                    continue;
                final JSONObject p = f.optJSONObject("properties");
                final JSONObject g = f.optJSONObject("geometry");
                if (p == null || g == null)
                    continue;
                final int dn = p.optInt("dn", 0);
                if (dn <= 0)
                    continue;
                out.add(new Area(day, dn, p.optString("outlook", ""), p.optString("valid_time", ""),
                        p.optString("issue_time", ""), g));
            }
        } catch (Exception e) {
            return out;
        }
        return out;
    }

    private WpcEro() {
    }
}
