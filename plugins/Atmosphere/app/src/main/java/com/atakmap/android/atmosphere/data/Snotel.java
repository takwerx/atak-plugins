package com.atakmap.android.atmosphere.data;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;

/**
 * SNOTEL: the NRCS snow telemetry network, 919 stations in the western mountains
 * (2026-09-26), each reporting snow depth, snow water equivalent, air temperature
 * and the water year's precipitation every hour. Read from the AWDB REST API,
 * keyless: the station list once (408 KB), then the last hours of readings for
 * the stations in view, sixty triplets to a request (a hundred was 1.4 KB of URL
 * and 5 s; the box's stations come in chunks).
 *
 * <p>Values are in the network's own units, inches and Fahrenheit; a station's
 * timestamps are its own local time, and its {@code dataTimeZone} (hours from
 * UTC) is what turns them into instants. No Android types; tested.
 */
public final class Snotel {

    public static final String HOST = "wcc.sc.egov.usda.gov";
    private static final String API = "https://" + HOST + "/awdbRestApi/services/v1";

    /** Every active SNOTEL station in the country. */
    public static final String STATIONS_URL = API + "/stations?stationTriplets=*:*:SNTL&activeOnly=true";

    public static final int MAX_PER_QUERY = 60;

    /**
     * Hourly readings for these stations between two station-local times
     * ({@code yyyy-MM-dd HH:mm}); the last value of each element is the reading.
     */
    public static String dataUrl(Collection<String> triplets, String beginLocal, String endLocal) {
        final StringBuilder t = new StringBuilder();
        for (String s : triplets) {
            if (t.length() > 0)
                t.append(',');
            t.append(s);
        }
        return API + "/data?stationTriplets=" + t + "&elements=WTEQ,SNWD,TOBS,PREC&duration=HOURLY"
                + "&beginDate=" + beginLocal.replace(" ", "%20") + "&endDate=" + endLocal.replace(" ", "%20")
                + "&periodRef=END";
    }

    /** "2026-09-26 14:00" for a UTC instant, in the phone's zone: near enough for a window. */
    public static String localStamp(long utcMillis) {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(new java.util.Date(utcMillis));
    }

    public static final class Station {
        public final String triplet, id, name, state, county, shefId;
        public final double latitude, longitude, elevationFt, tzOffsetHours;

        Station(JSONObject s) {
            triplet = s.optString("stationTriplet", "");
            id = s.optString("stationId", "");
            name = s.optString("name", "").trim();
            state = s.optString("stateCode", "");
            county = s.optString("countyName", "");
            shefId = s.optString("shefId", "");
            latitude = s.optDouble("latitude", Double.NaN);
            longitude = s.optDouble("longitude", Double.NaN);
            elevationFt = s.optDouble("elevation", Double.NaN);
            tzOffsetHours = s.optDouble("dataTimeZone", -7);
        }

        /** NRCS's own page for the station. */
        public String url() {
            return "https://wcc.sc.egov.usda.gov/nwcc/site?sitenum=" + id;
        }
    }

    public static List<Station> parseStations(String body) {
        final List<Station> out = new ArrayList<>();
        if (body == null || body.isEmpty())
            return out;
        try {
            final JSONArray arr = new JSONArray(body);
            for (int i = 0; i < arr.length(); i++) {
                final JSONObject o = arr.optJSONObject(i);
                if (o == null)
                    continue;
                final Station s = new Station(o);
                if (s.triplet.isEmpty() || Double.isNaN(s.latitude) || Double.isNaN(s.longitude))
                    continue;
                out.add(s);
            }
        } catch (Exception e) {
            return out;
        }
        return out;
    }

    /** The latest of each element a station sent in the window; any may be NaN. */
    public static final class Reading {
        public double snowDepthIn = Double.NaN, sweIn = Double.NaN, tempF = Double.NaN,
                precipIn = Double.NaN;
        /** Station-local stamp of the newest value, and that instant. */
        public String observed = "";
        public long observedAt;

        public boolean hasAny() {
            return !Double.isNaN(snowDepthIn) || !Double.isNaN(sweIn) || !Double.isNaN(tempF);
        }
    }

    /** Readings by triplet. A station with values in the window has an entry. */
    public static Map<String, Reading> parseData(String body, Map<String, Double> tzOffsets) {
        final Map<String, Reading> out = new HashMap<>();
        if (body == null || body.isEmpty())
            return out;
        try {
            final JSONArray arr = new JSONArray(body);
            for (int i = 0; i < arr.length(); i++) {
                final JSONObject st = arr.optJSONObject(i);
                if (st == null)
                    continue;
                final String triplet = st.optString("stationTriplet", "");
                final JSONArray data = st.optJSONArray("data");
                if (triplet.isEmpty() || data == null)
                    continue;
                final Reading r = new Reading();
                for (int j = 0; j < data.length(); j++) {
                    final JSONObject el = data.optJSONObject(j);
                    if (el == null)
                        continue;
                    final String code = el.optJSONObject("stationElement") == null ? ""
                            : el.optJSONObject("stationElement").optString("elementCode", "");
                    final JSONArray values = el.optJSONArray("values");
                    if (values == null)
                        continue;
                    for (int k = values.length() - 1; k >= 0; k--) {
                        final JSONObject v = values.optJSONObject(k);
                        if (v == null || v.isNull("value"))
                            continue;
                        final double x = v.optDouble("value", Double.NaN);
                        if (Double.isNaN(x))
                            continue;
                        final String at = v.optString("date", "");
                        if (at.compareTo(r.observed) > 0) {
                            r.observed = at;
                            final Double tz = tzOffsets == null ? null : tzOffsets.get(triplet);
                            r.observedAt = instant(at, tz == null ? -7 : tz);
                        }
                        switch (code) {
                            case "SNWD":
                                r.snowDepthIn = x;
                                break;
                            case "WTEQ":
                                r.sweIn = x;
                                break;
                            case "TOBS":
                                r.tempF = x;
                                break;
                            case "PREC":
                                r.precipIn = x;
                                break;
                            default:
                                break;
                        }
                        break;
                    }
                }
                if (r.hasAny() || !Double.isNaN(r.precipIn))
                    out.put(triplet, r);
            }
        } catch (Exception e) {
            return out;
        }
        return out;
    }

    /** A station-local "2026-09-26 19:00" at the given hours from UTC, as an instant. */
    static long instant(String local, double tzOffsetHours) {
        try {
            final SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US);
            f.setTimeZone(TimeZone.getTimeZone("UTC"));
            return f.parse(local).getTime() - Math.round(tzOffsetHours * 3_600_000d);
        } catch (Exception e) {
            return 0L;
        }
    }

    /** The stations inside a lon/lat box. */
    public static List<Station> within(List<Station> all, double west, double south, double east,
            double north) {
        final List<Station> out = new ArrayList<>();
        for (Station s : all)
            if (s.latitude >= south && s.latitude <= north && s.longitude >= west && s.longitude <= east)
                out.add(s);
        return out;
    }

    public static List<List<String>> chunks(List<Station> stations, int size) {
        final List<List<String>> out = new ArrayList<>();
        List<String> cur = new ArrayList<>();
        for (Station s : stations) {
            cur.add(s.triplet);
            if (cur.size() >= size) {
                out.add(cur);
                cur = new ArrayList<>();
            }
        }
        if (!cur.isEmpty())
            out.add(cur);
        return out;
    }

    /** NOHRSC's snow depth classes and colors, so the stations match the analysis. */
    public static final double[] DEPTH_STEPS_IN = { 0.39, 2, 3.9, 9.8, 20, 39, 59, 98, 197, 295, 394 };
    public static final int[] DEPTH_COLORS = { 0xFFABC1BF, 0xFF66C1C4, 0xFF63A9CB, 0xFF5079C8,
            0xFF3C3FC2, 0xFF5720C3, 0xFF7D01BB, 0xFFB404B1, 0xFFA91377, 0xFF992B50, 0xFF8B4545 };
    public static final int NO_SNOW = 0xFF9E9E9E, NO_REPORT = 0xFF5A5A5A;

    /** The disc's color for a depth: gray for bare ground, darker gray for no reading. */
    public static int depthColor(double depthIn) {
        if (Double.isNaN(depthIn))
            return NO_REPORT;
        if (depthIn < DEPTH_STEPS_IN[0])
            return NO_SNOW;
        for (int i = 1; i < DEPTH_STEPS_IN.length; i++)
            if (depthIn < DEPTH_STEPS_IN[i])
                return DEPTH_COLORS[i - 1];
        return DEPTH_COLORS[DEPTH_COLORS.length - 1];
    }

    private Snotel() {
    }
}
