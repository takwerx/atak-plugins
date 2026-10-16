package com.atakmap.android.atmosphere.data;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;

/**
 * ACIS, the NOAA Regional Climate Centers' Applied Climate Information System: every
 * daily rain gauge the climate record keeps, added up on the server for any run of
 * days ({@code data.rcc-acis.org}, keyless). That is the CoCoRaHS volunteers, the NWS
 * cooperative observers and the airports, about 700 gauges in California and 2,700 in
 * Texas, each with the number of days it has no report for.
 *
 * <p>Asked for because the Rain Gauges layer could only say the last 24 hours, and a
 * storm that ended two days ago had gone from it (operator, 2026-10-13: "now all i
 * have is last 24 hours and storm ended two days ago i cant get a look").
 *
 * <p>A day is the gauge's own: a volunteer reads at about 7 am for the 24 hours
 * before, an airport midnight to midnight, a cooperative observer once a day at a set
 * hour. That is how the climate record files them.
 *
 * <p>No Android types; tested.
 */
public final class Acis {

    public static final String HOST = "data.rcc-acis.org";

    /** ACIS's id type for a CoCoRaHS station, the number after the id in "sids". */
    private static final String COCORAHS_TYPE = "10";

    /**
     * One state's gauges, each with its total for {@code start} to {@code end}
     * (yyyy-MM-dd, both days included) and the count of days it has no report for.
     */
    public static String url(String state, String start, String end) {
        final String params = "{\"state\":\"" + state + "\",\"sdate\":\"" + start + "\",\"edate\":\"" + end
                + "\",\"elems\":[{\"name\":\"pcpn\",\"interval\":\"dly\",\"duration\":\"dly\","
                + "\"smry\":{\"reduce\":\"sum\",\"add\":\"mcnt\"},\"smry_only\":1}],"
                + "\"meta\":\"name,ll,sids\"}";
        try {
            return "https://" + HOST + "/MultiStnData?params=" + URLEncoder.encode(params, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            throw new IllegalStateException(e);
        }
    }

    /** One gauge and its total. */
    public static final class Station {
        public final String name, id;
        public final double lat, lon;
        /** Inches over the days; 0 with {@link #trace} for a trace. */
        public final double inches;
        public final boolean trace;
        /** Days of the run the gauge has no report for. */
        public final int missing;
        /** A CoCoRaHS volunteer's gauge; otherwise an NWS observer's or an airport's. */
        public final boolean volunteer;

        Station(String name, String id, double lat, double lon, double inches, boolean trace, int missing,
                boolean volunteer) {
            this.name = name;
            this.id = id;
            this.lat = lat;
            this.lon = lon;
            this.inches = inches;
            this.trace = trace;
            this.missing = missing;
            this.volunteer = volunteer;
        }
    }

    /**
     * Every gauge with a position and a total. A gauge with no report on any day of
     * the run ("M") is left out: it says nothing about the rain.
     */
    public static List<Station> parse(String body) {
        final List<Station> out = new ArrayList<>();
        if (body == null || body.isEmpty())
            return out;
        try {
            final JSONArray data = new JSONObject(body).optJSONArray("data");
            if (data == null)
                return out;
            for (int i = 0; i < data.length(); i++) {
                final JSONObject s = data.optJSONObject(i);
                if (s == null)
                    continue;
                final JSONObject meta = s.optJSONObject("meta");
                final JSONArray smry = s.optJSONArray("smry");
                if (meta == null || smry == null || smry.length() == 0)
                    continue;
                final JSONArray ll = meta.optJSONArray("ll");
                if (ll == null || ll.length() < 2)
                    continue;
                final double lon = ll.optDouble(0, Double.NaN), lat = ll.optDouble(1, Double.NaN);
                if (Double.isNaN(lat) || Double.isNaN(lon) || Double.isInfinite(lat) || Double.isInfinite(lon)
                        || Math.abs(lat) > 90 || Math.abs(lon) > 180 || (lat == 0 && lon == 0))
                    continue;
                // [["1.47", 0]]: the sum as written, then the missing days.
                final JSONArray pair = smry.optJSONArray(0);
                final String v = pair == null ? smry.optString(0, "M") : pair.optString(0, "M");
                final int missing = pair == null ? 0 : Math.max(0, pair.optInt(1, 0));
                double inches;
                boolean trace = false;
                if (v.equalsIgnoreCase("T")) {
                    inches = 0;
                    trace = true;
                } else {
                    try {
                        inches = Double.parseDouble(v);
                    } catch (NumberFormatException e) {
                        continue;
                    }
                    // A season in the wettest place in the country is under 200 in.
                    if (Double.isNaN(inches) || Double.isInfinite(inches) || inches < 0 || inches > 400)
                        continue;
                }
                String id = "";
                boolean volunteer = false;
                final JSONArray sids = meta.optJSONArray("sids");
                if (sids != null)
                    for (int k = 0; k < sids.length(); k++) {
                        final String[] parts = sids.optString(k, "").trim().split("\\s+");
                        if (parts.length != 2)
                            continue;
                        if (id.isEmpty())
                            id = parts[0];
                        if (parts[1].equals(COCORAHS_TYPE)) {
                            volunteer = true;
                            id = parts[0];
                        }
                    }
                out.add(new Station(clean(meta.optString("name", "")), id, lat, lon, inches, trace, missing,
                        volunteer));
            }
        } catch (Exception e) {
            return out;
        }
        return out;
    }

    /**
     * "ESCONDIDO 2.2 NNW" to "Escondido 2.2 NNW", "AVALON CATALINA AP" to "Avalon
     * Catalina AP": title case, with a compass point after a distance and the airport
     * abbreviation kept in capitals. A name already in mixed case is kept as written.
     */
    static String clean(String name) {
        final String s = name == null ? "" : name.trim();
        if (s.isEmpty() || !s.equals(s.toUpperCase(java.util.Locale.US)))
            return s;
        final String[] words = s.split("\\s+");
        final StringBuilder b = new StringBuilder();
        for (int i = 0; i < words.length; i++) {
            final String w = words[i];
            final boolean afterNumber = i > 0 && words[i - 1].matches("\\d+(\\.\\d+)?");
            final boolean keep = w.equals("AP") || w.equals("RAWS") || (afterNumber && w.matches("[NSEW]{1,3}"));
            if (b.length() > 0)
                b.append(' ');
            if (keep) {
                b.append(w);
                continue;
            }
            boolean start = true;
            for (char c : w.toCharArray()) {
                b.append(start ? Character.toUpperCase(c) : Character.toLowerCase(c));
                start = !Character.isLetterOrDigit(c) && c != '\'';
            }
        }
        return b.toString();
    }

    private Acis() {
    }
}
