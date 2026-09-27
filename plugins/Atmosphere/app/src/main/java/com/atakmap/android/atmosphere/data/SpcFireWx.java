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

    /** The renderer's categories: code, label, color, per kind. */
    public static String label(Kind kind, int dn) {
        if (kind == Kind.OUTLOOK) {
            if (dn == 5)
                return "Elevated";
            if (dn == 8)
                return "Critical";
            if (dn == 10)
                return "Extreme";
        } else {
            if (dn == 5)
                return "Isolated dry thunderstorms";
            if (dn == 8)
                return "Scattered dry thunderstorms";
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
        } else {
            if (dn == 5)
                return 0xFF732600;
            if (dn == 8)
                return 0xFFFF0000;
        }
        return 0xFFB0B0B0;
    }

    /** The legend, in the order SPC prints it: outlook categories, then dry thunderstorms. */
    public static final int[][] LEGEND_CODES = { { 0, 5 }, { 0, 8 }, { 0, 10 }, { 1, 5 }, { 1, 8 } };

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
