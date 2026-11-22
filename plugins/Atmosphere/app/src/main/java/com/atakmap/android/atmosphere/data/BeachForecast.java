package com.atakmap.android.atmosphere.data;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The NWS beach and surf zone forecast: rip current risk, surf, water
 * temperature and the rest, per beach area, from the NWS map service's
 * {@code marine_beachforecast}. That service is one layer per forecast office
 * and day (84 layers, 2026-09-26), so a box is asked of every layer at once
 * with one {@code identify} request and the answer is cut to the Day 1 areas;
 * the Day 2 area for the same beach becomes the record's "Tomorrow" line.
 * Geometry comes back as Esri rings, turned into GeoJSON here: an exterior
 * ring is clockwise, a hole counter-clockwise.
 *
 * <p>The rip current risk is the office's own word -- Low, Moderate, High --
 * colored the way the NWS beach forecast pages color it.
 *
 * <p>No Android types; tested.
 */
public final class BeachForecast {

    public static final String HOST = "mapservices.weather.noaa.gov";
    private static final String SERVICE = "https://" + HOST
            + "/vector/rest/services/outlooks/marine_beachforecast/MapServer";

    public static final int LOW = 0xFF3CB043, MODERATE = 0xFFFFD600, HIGH = 0xFFE53935,
            UNKNOWN = 0xFF9E9E9E;

    /** Every layer, one envelope, geometry back, field names not aliases. */
    public static String url(double west, double south, double east, double north) {
        final String env = String.format(Locale.US, "%.3f,%.3f,%.3f,%.3f", west, south, east, north);
        return SERVICE + "/identify?geometry=" + env
                + "&geometryType=esriGeometryEnvelope&sr=4326&layers=all&tolerance=0"
                + "&mapExtent=" + env + "&imageDisplay=800,600,96&returnGeometry=true"
                + "&returnFieldName=true&f=json";
    }

    public static int ripColor(String rip) {
        final String r = rip == null ? "" : rip.trim().toLowerCase(Locale.US);
        if (r.startsWith("low"))
            return LOW;
        if (r.startsWith("mod"))
            return MODERATE;
        if (r.startsWith("high"))
            return HIGH;
        return UNKNOWN;
    }

    public static final class Beach {
        public final String office, name, rip, surf, waterTemp, uv, weather, winds, thunderstorms,
                maxTemp, period, issuedDate, issuedTime, url;
        /** Tomorrow's rip and surf from the Day 2 layer, or empty. */
        public String tomorrowRip = "", tomorrowSurf = "";
        public final JSONObject geometry;

        Beach(String office, JSONObject a, JSONObject geometry) {
            this.office = office;
            name = str(a, "beachname");
            rip = str(a, "rip");
            surf = str(a, "surf");
            waterTemp = str(a, "wtemp");
            uv = str(a, "uv");
            weather = str(a, "weather");
            winds = str(a, "winds");
            thunderstorms = str(a, "tstorm");
            maxTemp = str(a, "maxtemp");
            period = str(a, "period");
            issuedDate = str(a, "productdat");
            issuedTime = str(a, "producttim");
            url = str(a, "srfprod");
            this.geometry = geometry;
        }

        public int color() {
            return ripColor(rip);
        }

        /** "Rip current risk: High" -- what the pill and the chooser say. */
        public String title() {
            return name;
        }

        /** The record, in reading order. */
        public String details() {
            final StringBuilder d = new StringBuilder();
            line(d, "Rip current risk", rip);
            line(d, "Surf", surf);
            line(d, "Water temperature", waterTemp);
            line(d, "UV index", uv);
            line(d, "Weather", weather);
            line(d, "Winds", winds);
            line(d, "Thunderstorms", thunderstorms);
            line(d, "High temperature", maxTemp);
            line(d, "Period", period);
            if (!tomorrowRip.isEmpty() || !tomorrowSurf.isEmpty())
                line(d, "Tomorrow", (tomorrowRip.isEmpty() ? "" : "rip current " + tomorrowRip)
                        + (tomorrowRip.isEmpty() || tomorrowSurf.isEmpty() ? "" : ", ")
                        + (tomorrowSurf.isEmpty() ? "" : "surf " + tomorrowSurf));
            line(d, "Issued", (issuedTime + " " + issuedDate).trim());
            line(d, "Forecast office", office);
            line(d, "Forecast", url);
            return d.toString();
        }
    }

    private static void line(StringBuilder d, String label, String value) {
        if (value == null || value.isEmpty())
            return;
        if (d.length() > 0)
            d.append('\n');
        d.append(label).append(": ").append(value);
    }

    /** The service writes "Null" for a missing value. */
    private static String str(JSONObject a, String key) {
        final String s = a.optString(key, "");
        return s == null || s.equals("null") || s.equals("Null") ? "" : s.trim();
    }

    /** Day 1 beaches in an identify answer, with the Day 2 line joined on office and name. */
    public static List<Beach> parse(String body) {
        final List<Beach> out = new ArrayList<>();
        if (body == null || body.isEmpty())
            return out;
        try {
            final JSONArray rs = new JSONObject(body).optJSONArray("results");
            if (rs == null)
                return out;
            final Map<String, JSONObject> tomorrow = new HashMap<>();
            for (int i = 0; i < rs.length(); i++) {
                final JSONObject r = rs.optJSONObject(i);
                if (r == null)
                    continue;
                final String layer = r.optString("layerName", "");
                final JSONObject a = r.optJSONObject("attributes");
                if (a == null)
                    continue;
                if (layer.endsWith("Day 2"))
                    tomorrow.put(layer.substring(0, layer.length() - 6) + "|" + str(a, "beachname"), a);
            }
            for (int i = 0; i < rs.length(); i++) {
                final JSONObject r = rs.optJSONObject(i);
                if (r == null)
                    continue;
                final String layer = r.optString("layerName", "");
                if (!layer.endsWith("Day 1"))
                    continue;
                final JSONObject a = r.optJSONObject("attributes");
                final JSONObject g = geoJson(r.optJSONObject("geometry"));
                if (a == null || g == null)
                    continue;
                final String office = str(a, "sitename").isEmpty()
                        ? layer.substring(0, layer.length() - 6) : str(a, "sitename");
                final Beach b = new Beach(office, a, g);
                final JSONObject t = tomorrow.get(layer.substring(0, layer.length() - 6) + "|" + b.name);
                if (t != null) {
                    b.tomorrowRip = str(t, "rip");
                    b.tomorrowSurf = str(t, "surf");
                }
                out.add(b);
            }
        } catch (Exception e) {
            return out;
        }
        return out;
    }

    /**
     * Esri rings to a GeoJSON Polygon or MultiPolygon. A clockwise ring starts a
     * polygon, a counter-clockwise one is a hole in the polygon before it; a hole
     * with no polygon before it is drawn as its own polygon rather than dropped.
     */
    public static JSONObject geoJson(JSONObject esri) {
        if (esri == null)
            return null;
        try {
            final JSONArray rings = esri.optJSONArray("rings");
            if (rings == null || rings.length() == 0)
                return null;
            final List<JSONArray> polygons = new ArrayList<>();
            for (int i = 0; i < rings.length(); i++) {
                final JSONArray ring = rings.getJSONArray(i);
                if (ring.length() < 4)
                    continue;
                if (signedArea(ring) < 0 || polygons.isEmpty())
                    polygons.add(new JSONArray().put(ring));
                else
                    polygons.get(polygons.size() - 1).put(ring);
            }
            if (polygons.isEmpty())
                return null;
            final JSONObject g = new JSONObject();
            if (polygons.size() == 1) {
                g.put("type", "Polygon");
                g.put("coordinates", polygons.get(0));
            } else {
                g.put("type", "MultiPolygon");
                g.put("coordinates", new JSONArray(polygons));
            }
            return g;
        } catch (Exception e) {
            return null;
        }
    }

    /** Shoelace; positive is counter-clockwise in lon/lat. */
    static double signedArea(JSONArray ring) throws Exception {
        double a = 0;
        for (int i = 0; i < ring.length(); i++) {
            final JSONArray p = ring.getJSONArray(i), q = ring.getJSONArray((i + 1) % ring.length());
            a += p.getDouble(0) * q.getDouble(1) - q.getDouble(0) * p.getDouble(1);
        }
        return a / 2;
    }

    private BeachForecast() {
    }
}
