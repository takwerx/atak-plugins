package com.atakmap.android.atmosphere.data;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

/**
 * The Storm Prediction Center's fire weather outlook, days 1 to 3, from NWS's
 * map service: the categorical areas (Elevated, Critical, Extreme) and the dry
 * thunderstorm areas (isolated, scattered), one layer per day and kind. The
 * categories, their codes and their colors are the service's own renderer
 * (read 2026-09-26), so the map matches SPC's. A day with no areas answers one
 * placeholder feature with dn 0 and no geometry, which the query leaves out.
 *
 * <p>No Android types; tested.
 */
public final class SpcFireWx {

    public static final String HOST = "mapservices.weather.noaa.gov";
    private static final String SERVICE = "https://" + HOST
            + "/vector/rest/services/fire_weather/SPC_firewx/MapServer/";

    public enum Kind {
        OUTLOOK("Fire weather outlook"), DRY_THUNDER("Dry thunderstorms");

        public final String label;

        Kind(String label) {
            this.label = label;
        }
    }

    /** One service layer: which day and kind it carries. */
    public static final class Layer {
        public final int id, day;
        public final Kind kind;

        Layer(int id, int day, Kind kind) {
            this.id = id;
            this.day = day;
            this.kind = kind;
        }

        public String url() {
            return SERVICE + id + "/query?where=dn%3E0&outFields=dn,valid,expire&outSR=4326&f=geojson";
        }
    }

    /** Days 1-3; the service's layer ids, read from its layer list. */
    public static final Layer[] LAYERS = {
            new Layer(1, 1, Kind.OUTLOOK), new Layer(2, 1, Kind.DRY_THUNDER),
            new Layer(4, 2, Kind.OUTLOOK), new Layer(5, 2, Kind.DRY_THUNDER),
            new Layer(8, 3, Kind.OUTLOOK), new Layer(7, 3, Kind.DRY_THUNDER) };

    /**
     * The renderer's categories: code, label, color, per kind. Days 1 and 2 are
     * named categories; from Day 3 SPC gives the chance instead -- 40 or 70 percent
     * that wind and low humidity reach critical, 10 or 40 percent of dry
     * thunderstorms -- and the code is the percent. Its own map calls them
     * "Marginal (40%)" and "Critical (70%)"; the percent is the part that says what
     * it means. Before 2026-09-27 these came out as "Category 40" (operator,
     * shooting the manual: "what does category 40 mean?").
     */
    public static String label(Kind kind, int dn) {
        if (kind == Kind.OUTLOOK) {
            if (dn == 5)
                return "Elevated";
            if (dn == 8)
                return "Critical";
            if (dn == 10)
                return "Extreme";
            if (dn == 40 || dn == 70)
                return dn + "% chance of critical";
        } else {
            if (dn == 5)
                return "Isolated dry thunderstorms";
            if (dn == 8)
                return "Scattered dry thunderstorms";
            if (dn == 10 || dn == 40)
                return dn + "% chance of dry thunderstorms";
        }
        return "Category " + dn;
    }

    public static int color(Kind kind, int dn) {
        if (kind == Kind.OUTLOOK) {
            if (dn == 5)
                return 0xFFE69800;
            if (dn == 8)
                return 0xFFFF0000;
            if (dn == 10)
                return 0xFFE600A9;
            // Day 3, SPC's outline colors off the service's renderer (2026-09-27).
            if (dn == 40)
                return 0xFFFFAA00;
            if (dn == 70)
                return 0xFFE60000;
        } else {
            if (dn == 5)
                return 0xFF732600;
            if (dn == 8)
                return 0xFFFF0000;
            if (dn == 10)
                return 0xFF734C00;
            if (dn == 40)
                return 0xFF0070FF;
        }
        return 0xFFB0B0B0;
    }

    /**
     * The legend, in the order SPC prints it: outlook categories, then dry
     * thunderstorms, then Day 3's chances.
     */
    public static final int[][] LEGEND_CODES = { { 0, 5 }, { 0, 8 }, { 0, 10 }, { 1, 5 }, { 1, 8 },
            { 0, 40 }, { 0, 70 }, { 1, 10 }, { 1, 40 } };

    public static final class Area {
        public final int day, dn;
        public final Kind kind;
        /** As the service writes them, yyyyMMddHHmm UTC. */
        public final String valid, expire;
        public final JSONObject geometry;

        Area(int day, Kind kind, int dn, String valid, String expire, JSONObject geometry) {
            this.day = day;
            this.kind = kind;
            this.dn = dn;
            this.valid = valid;
            this.expire = expire;
            this.geometry = geometry;
        }

        public String label() {
            return SpcFireWx.label(kind, dn);
        }

        public int color() {
            return SpcFireWx.color(kind, dn);
        }

        /** "Day 2 fire weather outlook: Elevated". */
        public String title() {
            return "Day " + day + " " + kind.label.toLowerCase(Locale.US) + ": " + label();
        }
    }

    public static List<Area> parse(String body, Layer layer) {
        final List<Area> out = new ArrayList<>();
        if (body == null || body.isEmpty() || layer == null)
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
                out.add(new Area(layer.day, layer.kind, dn, p.optString("valid", ""),
                        p.optString("expire", ""), g));
            }
        } catch (Exception e) {
            return out;
        }
        return out;
    }

    /** "Sat Sep 27, 5:00 AM" in the phone's zone from "202609271200" (UTC), or the text as given. */
    public static String when(String yyyymmddhhmm, TimeZone zone) {
        if (yyyymmddhhmm == null || yyyymmddhhmm.length() != 12)
            return yyyymmddhhmm == null ? "" : yyyymmddhhmm;
        try {
            final SimpleDateFormat in = new SimpleDateFormat("yyyyMMddHHmm", Locale.US);
            in.setTimeZone(TimeZone.getTimeZone("UTC"));
            final Date d = in.parse(yyyymmddhhmm);
            final SimpleDateFormat out = new SimpleDateFormat("EEE MMM d, h:mm a", Locale.US);
            out.setTimeZone(zone);
            return out.format(d);
        } catch (Exception e) {
            return yyyymmddhhmm;
        }
    }

    private SpcFireWx() {
    }
}
