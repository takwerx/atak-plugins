
package com.atakmap.android.atmosphere.data;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The National Hurricane Center's live storms, and where to ask for their shapes.
 *
 * <p>Two sources, and the split matters. {@code CurrentStorms.json} says which storms
 * exist right now, what they are and where; it is a few KB and is the only thing worth
 * polling. The shapes -- the forecast cone, the track, the points along it -- come from
 * NOAA's tropical ArcGIS service as GeoJSON, so nothing here unpacks a shapefile or a
 * KMZ (operator, 2026-09-22: use the ArcGIS machinery, "not KMZ").
 *
 * <p><b>The service keeps fifteen fixed slots</b>, not one layer per storm: AT1-AT5,
 * EP1-EP5, CP1-CP5, whichever storms happen to be running. A storm's slot is its
 * {@code binNumber} in CurrentStorms.json, so the two sources join on that. The layer
 * ids march in a fixed stride, verified against the live service on 2026-09-23:
 * AT1 at 4, AT2 at 30, EP1 at 134, CP5 at 368 -- 26 apart -- with each storm's own
 * layers at a fixed offset inside its block.
 *
 * <p>This class holds no Android types so the joins and the URLs can be tested.
 */
public final class Nhc {

    public static final String HOST = "www.nhc.noaa.gov";
    public static final String MAP_HOST = "mapservices.weather.noaa.gov";

    /** The few KB that say what is running. */
    public static final String CURRENT_STORMS = "https://" + HOST + "/CurrentStorms.json";

    private static final String MAP_BASE = "https://" + MAP_HOST
            + "/tropical/rest/services/tropical/NHC_tropical_weather/MapServer";

    /** The slots the service keeps, in the order its layer ids run. */
    public static final String[] SLOTS = {
            "AT1", "AT2", "AT3", "AT4", "AT5",
            "EP1", "EP2", "EP3", "EP4", "EP5",
            "CP1", "CP2", "CP3", "CP4", "CP5"
    };

    /** The first layer id of a slot's block, and the stride between blocks. */
    private static final int FIRST_BLOCK = 4;
    private static final int BLOCK_STRIDE = 26;

    /** Offsets inside a storm's block. Verified by name against the live service. */
    private static final int OFF_POINTS = 2;
    private static final int OFF_TRACK = 3;
    private static final int OFF_CONE = 4;
    private static final int OFF_WATCH = 5;

    private Nhc() {
    }

    /** Where a slot sits in the run of layer ids, or -1 if it is not a slot. */
    public static int slotIndex(String bin) {
        if (bin == null)
            return -1;
        for (int i = 0; i < SLOTS.length; i++)
            if (SLOTS[i].equalsIgnoreCase(bin))
                return i;
        return -1;
    }

    /** The slot's first layer id, or -1. */
    public static int baseLayer(String bin) {
        final int i = slotIndex(bin);
        return i < 0 ? -1 : FIRST_BLOCK + BLOCK_STRIDE * i;
    }

    /** The five-day forecast cone: one polygon. */
    public static int coneLayer(String bin) {
        return offset(bin, OFF_CONE);
    }

    /** The forecast track: one line through the forecast positions. */
    public static int trackLayer(String bin) {
        return offset(bin, OFF_TRACK);
    }

    /** The forecast positions, carrying wind, gust, pressure and category. */
    public static int pointsLayer(String bin) {
        return offset(bin, OFF_POINTS);
    }

    /** Coastal watches and warnings for this storm. */
    public static int watchLayer(String bin) {
        return offset(bin, OFF_WATCH);
    }

    private static int offset(String bin, int off) {
        final int base = baseLayer(bin);
        return base < 0 ? -1 : base + off;
    }

    /**
     * Everything in a layer, as GeoJSON. A storm's layer holds one advisory, so there
     * is nothing to filter and no geometry to send: the whole layer is the answer, and
     * the request stays short enough to be a GET (an ArcGIS gateway answers 404, not
     * 414, past about 2,000 characters).
     */
    public static String queryUrl(int layer) {
        return MAP_BASE + "/" + layer
                + "/query?where=1%3D1&outFields=*&returnGeometry=true&outSR=4326&f=geojson";
    }

    /** One live storm, as CurrentStorms.json describes it. */
    public static final class Storm {
        /** "ep172026". */
        public final String id;
        /** "EP2": the slot, and the join to the map service. */
        public final String bin;
        /** "Polo". */
        public final String name;
        /** "HU", "TS", "TD", "PT"... */
        public final String classification;
        /** Sustained wind in knots, or -1. */
        public final int intensityKt;
        /** Central pressure in millibars, or -1. */
        public final int pressureMb;
        public final double latitude;
        public final double longitude;

        Storm(String id, String bin, String name, String classification,
                int intensityKt, int pressureMb, double latitude, double longitude) {
            this.id = id;
            this.bin = bin;
            this.name = name;
            this.classification = classification;
            this.intensityKt = intensityKt;
            this.pressureMb = pressureMb;
            this.latitude = latitude;
            this.longitude = longitude;
        }

        /**
         * What to call it on the map: "Hurricane Polo", "Tropical Depression
         * Fifteen-E". The two-letter code is how the feed writes it and is not
         * something to put in front of a crew.
         */
        public String display() {
            final String kind = kind();
            return kind.isEmpty() ? name : kind + " " + name;
        }

        /** The classification in words, or "" when the feed sends something new. */
        public String kind() {
            if (classification == null)
                return "";
            final String c = classification.toUpperCase(Locale.US);
            if (c.equals("HU"))
                return "Hurricane";
            if (c.equals("MH"))
                return "Major Hurricane";
            if (c.equals("TS"))
                return "Tropical Storm";
            if (c.equals("TD"))
                return "Tropical Depression";
            if (c.equals("STS"))
                return "Subtropical Storm";
            if (c.equals("SD"))
                return "Subtropical Depression";
            if (c.equals("PT") || c.equals("PTC"))
                return "Post-Tropical Cyclone";
            if (c.equals("LO"))
                return "Low";
            if (c.equals("DB"))
                return "Disturbance";
            return "";
        }

        /**
         * The Saffir-Simpson category from the sustained wind, or 0 below hurricane
         * force. Derived rather than read: the feed carries the wind, and the category
         * only appears on the map service's own points.
         */
        public int category() {
            if (intensityKt >= 137)
                return 5;
            if (intensityKt >= 113)
                return 4;
            if (intensityKt >= 96)
                return 3;
            if (intensityKt >= 83)
                return 2;
            if (intensityKt >= 64)
                return 1;
            return 0;
        }
    }

    /**
     * The active storms, or an empty list. A feed that cannot be read is no storms
     * rather than an exception: the layer says "nothing running" either way, and a
     * crash on a malformed advisory would take the pane with it.
     */
    public static List<Storm> parseActive(String json) {
        final List<Storm> out = new ArrayList<>();
        if (json == null || json.isEmpty())
            return out;
        try {
            final JSONArray arr = new JSONObject(json).optJSONArray("activeStorms");
            if (arr == null)
                return out;
            for (int i = 0; i < arr.length(); i++) {
                final JSONObject o = arr.optJSONObject(i);
                if (o == null)
                    continue;
                final String bin = o.optString("binNumber", "");
                if (slotIndex(bin) < 0)
                    continue;   // a slot the map service does not carry
                out.add(new Storm(
                        o.optString("id", ""),
                        bin,
                        o.optString("name", "").trim(),
                        o.optString("classification", ""),
                        number(o, "intensity"),
                        number(o, "pressure"),
                        o.optDouble("latitudeNumeric", Double.NaN),
                        o.optDouble("longitudeNumeric", Double.NaN)));
            }
        } catch (JSONException e) {
            return out;
        }
        return out;
    }

    /** The feed writes its numbers as strings; take either, and -1 for neither. */
    private static int number(JSONObject o, String key) {
        final String s = o.optString(key, "").trim();
        if (s.isEmpty())
            return o.optInt(key, -1);
        try {
            return (int) Math.round(Double.parseDouble(s));
        } catch (NumberFormatException e) {
            return -1;
        }
    }
}
