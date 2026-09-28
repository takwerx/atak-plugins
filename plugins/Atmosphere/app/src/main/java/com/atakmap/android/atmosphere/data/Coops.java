package com.atakmap.android.atmosphere.data;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;

/**
 * Tides and currents from NOAA CO-OPS (tidesandcurrents.noaa.gov), keyless.
 *
 * <p>Three things it answers, all verified live 2026-09-26: the station lists
 * ({@code mdapi} -- 3,499 tide-prediction stations, 4,430 current-prediction
 * station/bin rows), the predicted highs and lows at a tide station
 * ({@code product=predictions&interval=hilo}), the latest observed water level
 * where the station measures one ({@code product=water_level&date=latest}; a
 * subordinate station has none), and the predicted ebb, flood and slack at a
 * current station ({@code product=currents_predictions&interval=MAX_SLACK}). Times
 * come back in the station's local time ({@code time_zone=lst_ldt}) with no zone
 * written, so they are shown as given and never converted.
 *
 * <p>The station lists are two megabytes each, so they are fetched once a session
 * and cut to a box here. No Android types; tested.
 */
public final class Coops {

    public static final String HOST = "api.tidesandcurrents.noaa.gov";
    private static final String MD = "https://" + HOST + "/mdapi/prod/webapi/stations.json";
    private static final String DATA = "https://" + HOST + "/api/prod/datagetter";

    public static final String TIDE_STATIONS_URL = MD + "?type=tidepredictions";
    public static final String CURRENT_STATIONS_URL = MD + "?type=currentpredictions";

    /**
     * Today's and tomorrow's highs and lows, station-local time. {@code range} on its
     * own counts back a day; {@code begin_date} must be a date, not "today" (the API
     * rejects the word), so the caller passes the device's local date as YYYYMMDD --
     * a day off only at midnight for a station in another zone.
     */
    public static String hiloUrl(String id, String beginYyyymmdd) {
        return DATA + "?station=" + id + "&product=predictions&datum=MLLW&units=english"
                + "&time_zone=lst_ldt&interval=hilo&begin_date=" + beginYyyymmdd
                + "&range=48&format=json";
    }

    /** The device's local date as the API wants it. */
    public static String today() {
        return new java.text.SimpleDateFormat("yyyyMMdd", Locale.US).format(new java.util.Date());
    }

    /** The latest observed water level, where the station measures one. */
    public static String waterLevelUrl(String id) {
        return DATA + "?station=" + id + "&product=water_level&datum=MLLW&units=english"
                + "&time_zone=lst_ldt&date=latest&format=json";
    }

    /** The next 24 hours of ebb, flood and slack, station-local time. */
    public static String currentsUrl(String id, int bin) {
        return DATA + "?station=" + id + "&product=currents_predictions&units=english"
                + "&time_zone=lst_ldt&range=24&interval=MAX_SLACK&bin=" + bin + "&format=json";
    }

    /** One station of either kind. */
    public static final class Station {
        public final String id, name;
        public final double latitude, longitude;
        /**
         * R reference / S subordinate for a tide station; H harmonic, S subordinate or
         * W weak-and-variable for a current station.
         */
        public final String type;
        /** Current stations only: which depth bin, and its depth in feet. */
        public final int bin;
        public final double depthFt;

        Station(String id, String name, double latitude, double longitude, String type,
                int bin, double depthFt) {
            this.id = id;
            this.name = name;
            this.latitude = latitude;
            this.longitude = longitude;
            this.type = type;
            this.bin = bin;
            this.depthFt = depthFt;
        }

        /**
         * The list's types are R (reference) and S (subordinate) for tides, H/S/W for
         * currents. Neither says whether a water level is measured -- Mission Bay is R
         * with none -- so a level is asked for and shown when it answers.
         */
        public boolean measures() {
            return "R".equals(type) || "H".equals(type);
        }

        /**
         * False for a "weak and variable" current station: CO-OPS lists 279 of them
         * (2026-09-26) and predicts none. B St. Pier in San Diego, 0.2 mi from the
         * SDBC1 buoy, answered "Currents predictions are not available from the
         * requested station" -- with HTTP 200 and an error body.
         */
        public boolean predicts() {
            return !"W".equals(type);
        }
    }

    /** Every station in an mdapi answer. */
    public static List<Station> parseStations(String body) {
        final List<Station> out = new ArrayList<>();
        if (body == null || body.isEmpty())
            return out;
        try {
            final JSONArray arr = new JSONObject(body).optJSONArray("stations");
            if (arr == null)
                return out;
            for (int i = 0; i < arr.length(); i++) {
                final JSONObject s = arr.optJSONObject(i);
                if (s == null)
                    continue;
                final double lat = s.optDouble("lat", Double.NaN), lon = s.optDouble("lng", Double.NaN);
                final String id = s.optString("id", "");
                if (id.isEmpty() || Double.isNaN(lat) || Double.isNaN(lon))
                    continue;
                out.add(new Station(id, s.optString("name", "").trim(), lat, lon,
                        s.optString("type", ""), s.optInt("currbin", 1),
                        s.isNull("depth") ? Double.NaN : s.optDouble("depth", Double.NaN)));
            }
        } catch (Exception e) {
            return out;
        }
        return out;
    }

    /**
     * The station nearest a point within {@code miles} that has predictions, or null.
     * Bin 1 wins a tie.
     */
    public static Station nearest(List<Station> stations, double lat, double lon, double miles) {
        Station best = null;
        double bestD = miles;
        for (Station s : stations) {
            if (!s.predicts())
                continue;
            final double d = milesBetween(lat, lon, s.latitude, s.longitude);
            if (d < bestD || (best != null && d == bestD && s.bin < best.bin)) {
                best = s;
                bestD = d;
            }
        }
        return best;
    }

    public static double milesBetween(double lat1, double lon1, double lat2, double lon2) {
        final double r = 3958.8, p = Math.PI / 180;
        final double a = Math.sin((lat2 - lat1) * p / 2) * Math.sin((lat2 - lat1) * p / 2)
                + Math.cos(lat1 * p) * Math.cos(lat2 * p)
                * Math.sin((lon2 - lon1) * p / 2) * Math.sin((lon2 - lon1) * p / 2);
        return 2 * r * Math.asin(Math.sqrt(a));
    }

    /** One high or low: when (station-local, as given) and the height above MLLW in feet. */
    public static final class Tide {
        public final String at;
        public final double feet;
        public final boolean high;

        Tide(String at, double feet, boolean high) {
            this.at = at;
            this.feet = feet;
            this.high = high;
        }
    }

    public static List<Tide> parseHilo(String body) {
        final List<Tide> out = new ArrayList<>();
        if (body == null || body.isEmpty())
            return out;
        try {
            final JSONArray arr = new JSONObject(body).optJSONArray("predictions");
            if (arr == null)
                return out;
            for (int i = 0; i < arr.length(); i++) {
                final JSONObject t = arr.optJSONObject(i);
                if (t == null)
                    continue;
                final double v = t.optDouble("v", Double.NaN);
                if (Double.isNaN(v))
                    continue;
                out.add(new Tide(t.optString("t", ""), v, "H".equals(t.optString("type", ""))));
            }
        } catch (Exception e) {
            return out;
        }
        return out;
    }

    /** The latest observed level: "1.20 ft above MLLW at 15:48", or null without one. */
    public static Tide parseWaterLevel(String body) {
        if (body == null || body.isEmpty())
            return null;
        try {
            final JSONArray arr = new JSONObject(body).optJSONArray("data");
            if (arr == null || arr.length() == 0)
                return null;
            final JSONObject d = arr.optJSONObject(0);
            final double v = d.optDouble("v", Double.NaN);
            return Double.isNaN(v) ? null : new Tide(d.optString("t", ""), v, false);
        } catch (Exception e) {
            return null;
        }
    }

    /** One ebb, flood or slack: when, the speed in knots (ebb negative), and the direction. */
    public static final class Current {
        public final String at, type;
        public final double knots;
        public final double floodDir, ebbDir;

        Current(String at, String type, double knots, double floodDir, double ebbDir) {
            this.at = at;
            this.type = type;
            this.knots = knots;
            this.floodDir = floodDir;
            this.ebbDir = ebbDir;
        }
    }

    public static List<Current> parseCurrents(String body) {
        final List<Current> out = new ArrayList<>();
        if (body == null || body.isEmpty())
            return out;
        try {
            final JSONObject cp = new JSONObject(body).optJSONObject("current_predictions");
            final JSONArray arr = cp == null ? null : cp.optJSONArray("cp");
            if (arr == null)
                return out;
            for (int i = 0; i < arr.length(); i++) {
                final JSONObject c = arr.optJSONObject(i);
                if (c == null)
                    continue;
                out.add(new Current(c.optString("Time", ""), c.optString("Type", "").toLowerCase(Locale.US),
                        c.optDouble("Velocity_Major", Double.NaN),
                        c.optDouble("meanFloodDir", Double.NaN), c.optDouble("meanEbbDir", Double.NaN)));
            }
        } catch (Exception e) {
            return out;
        }
        return out;
    }

    /** "3:15 AM" from "2026-09-26 03:15", or the string as given. */
    public static String clock(String stationLocal) {
        if (stationLocal == null || stationLocal.length() < 16)
            return stationLocal == null ? "" : stationLocal;
        try {
            final int h = Integer.parseInt(stationLocal.substring(11, 13));
            final int m = Integer.parseInt(stationLocal.substring(14, 16));
            final int h12 = h % 12 == 0 ? 12 : h % 12;
            return String.format(Locale.US, "%d:%02d %s", h12, m, h < 12 ? "AM" : "PM");
        } catch (NumberFormatException e) {
            return stationLocal;
        }
    }

    /** The day part, "Sat 26", of a station-local stamp; empty when unparseable. */
    public static String day(String stationLocal) {
        if (stationLocal == null || stationLocal.length() < 10)
            return "";
        try {
            final Calendar c = Calendar.getInstance();
            c.clear();
            c.set(Integer.parseInt(stationLocal.substring(0, 4)),
                    Integer.parseInt(stationLocal.substring(5, 7)) - 1,
                    Integer.parseInt(stationLocal.substring(8, 10)));
            return new java.text.SimpleDateFormat("EEE d", Locale.US).format(c.getTime());
        } catch (NumberFormatException e) {
            return "";
        }
    }

    private Coops() {
    }
}
