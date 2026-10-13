package com.atakmap.android.atmosphere.data;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * NWS Local Storm Reports: what spotters, emergency managers and the public told a
 * Weather Forecast Office they saw in the last three days, as the NWS map service
 * publishes them (one row per report, with the office's type, magnitude and
 * remarks). Preliminary by nature; a survey comes later.
 *
 * <p>The service's 50 report types are grouped into eight families with a color each,
 * for a map that reads at a glance; the NWS type itself stays in the pill and in the
 * details, so nothing is lost. The service's own colors are a pastel set for a
 * white web map and were not kept.
 *
 * <p>No Android types.
 */
public final class StormReports {

    public static final String HOST = "mapservices.weather.noaa.gov";
    private static final String QUERY = "https://" + HOST
            + "/vector/rest/services/obs/nws_local_storm_reports/MapServer/2/query"
            + "?where=1%3D1&outFields=wfo,lsr_validtime,descript,loc_desc,state,magnitude,units,remarks"
            + "&outSR=4326&f=geojson";

    /** The 72-hour layer, from a row offset; the service hands back at most 2,000 rows a page. */
    public static String url(int offset) {
        return offset > 0 ? QUERY + "&resultOffset=" + offset : QUERY;
    }

    public static final String[] FAMILIES = { "Tornado", "Wind", "Hail", "Flood", "Rain",
            "Snow and Ice", "Landslide and Debris Flow", "Other" };
    public static final int[] COLORS = { 0xFFE53935, 0xFFFB8C00, 0xFF43A047, 0xFF1E88E5,
            0xFF26C6DA, 0xFFAB47BC, 0xFF8D6E63, 0xFF9E9E9E };

    public static final long DAY_MS = 24L * 60 * 60 * 1000;

    /** Which family a NWS report type belongs to, as an index into {@link #FAMILIES}. */
    public static int family(String descript) {
        final String d = descript == null ? "" : descript.trim().toLowerCase(Locale.US);
        if (d.contains("tornado") || d.contains("funnel") || d.contains("waterspout")
                || d.contains("landspout"))
            return 0;
        if (d.contains("wnd") || d.contains("wind") || d.contains("downburst"))
            return 1;
        if (d.contains("hail"))
            return 2;
        if (d.contains("flood") || d.contains("surge") || d.contains("seiche"))
            return 3;
        if (d.equals("rain"))
            return 4;
        if (d.contains("snow") || d.contains("sleet") || d.contains("freezing")
                || d.contains("blizzard") || d.contains("ice jam") || d.contains("avalanche")
                || d.contains("cold") || d.contains("chill"))
            return 5;
        if (d.contains("landslide") || d.contains("debris"))
            return 6;
        return 7;
    }

    /** The NWS type in plain words for the pill, with the magnitude where there is one. */
    public static String label(String descript, String magnitude, String units) {
        final String d = descript == null ? "" : descript.trim();
        String words;
        switch (d) {
            case "Tstm Wnd Gst":
            case "Non-Tstm Wnd Gst":
                words = "Gust";
                break;
            case "Tstm Wnd Dmg":
            case "Non-Tstm Wnd Dmg":
                words = "Wind damage";
                break;
            case "High Sust Winds":
                words = "Sustained wind";
                break;
            case "Snow/Ice Dmg":
                words = "Snow or ice damage";
                break;
            case "Misc Mrn/Srf Hzd":
                words = "Marine hazard";
                break;
            case "Marine Tstm Wind":
                words = "Marine gust";
                break;
            default:
                words = d.isEmpty() ? "Report" : d;
        }
        final String m = magnitudeText(magnitude, units);
        return m.isEmpty() ? words : words + " " + m;
    }

    /** "1.75 in", "66 mph", or "" when the report carries no magnitude. */
    public static String magnitudeText(String magnitude, String units) {
        if (magnitude == null)
            return "";
        String m = magnitude.trim();
        if (m.isEmpty() || m.equals("0") || m.equals("0.0") || m.equalsIgnoreCase("null"))
            return "";
        if (m.endsWith(".0"))
            m = m.substring(0, m.length() - 2);
        final String u = units == null ? "" : units.trim();
        String uu;
        switch (u.toLowerCase(Locale.US)) {
            case "inch":
            case "inches":
            case "in":
                uu = "in";
                break;
            case "mph":
                uu = "mph";
                break;
            case "kts":
            case "kt":
            case "knots":
                uu = "kt";
                break;
            case "f":
                uu = "°F";
                break;
            default:
                uu = u;
        }
        return uu.isEmpty() ? m : m + " " + uu;
    }

    /** One report. */
    public static final class Report {
        public final String type, place, state, office, magnitude, units, remarks;
        public final long time;
        public final double lat, lon;
        public final int family;

        Report(String type, String place, String state, String office, String magnitude,
                String units, String remarks, long time, double lat, double lon) {
            this.type = type;
            this.place = place;
            this.state = state;
            this.office = office;
            this.magnitude = magnitude;
            this.units = units;
            this.remarks = remarks;
            this.time = time;
            this.lat = lat;
            this.lon = lon;
            this.family = family(type);
        }

        public String label() {
            return StormReports.label(type, magnitude, units);
        }
    }

    public static List<Report> parse(String body) {
        final List<Report> out = new ArrayList<>();
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
                final JSONArray c = g.optJSONArray("coordinates");
                if (c == null || c.length() < 2)
                    continue;
                final double lon = c.optDouble(0, Double.NaN), lat = c.optDouble(1, Double.NaN);
                if (Double.isNaN(lat) || Double.isNaN(lon))
                    continue;
                out.add(new Report(text(p, "descript"), text(p, "loc_desc"), text(p, "state"),
                        text(p, "wfo"), text(p, "magnitude"), text(p, "units"),
                        text(p, "remarks"), p.optLong("lsr_validtime", 0), lat, lon));
            }
        } catch (Exception e) {
            return out;
        }
        return out;
    }

    /** Whether the service stopped at its page size, so the next page is worth asking for. */
    public static boolean exceeded(String body) {
        if (body == null)
            return false;
        try {
            final JSONObject o = new JSONObject(body);
            if (o.optBoolean("exceededTransferLimit", false))
                return true;
            final JSONObject p = o.optJSONObject("properties");
            return p != null && p.optBoolean("exceededTransferLimit", false);
        } catch (Exception e) {
            return false;
        }
    }

    static String text(JSONObject p, String key) {
        if (p == null || p.isNull(key))
            return "";
        final String v = p.optString(key, "");
        return v == null || v.equalsIgnoreCase("null") ? "" : v.trim();
    }

    private StormReports() {
    }
}
