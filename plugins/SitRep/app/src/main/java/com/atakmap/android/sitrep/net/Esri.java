package com.atakmap.android.sitrep.net;

import com.atakmap.android.sitrep.Area;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;

/**
 * ArcGIS REST queries limited to the drawn area. Every query is a POST: a polygon in
 * a GET past about 2,000 characters answers 404, and GETs are cached by the CDN for an
 * hour (PLAN section 3).
 */
public final class Esri {

    private Esri() {
    }

    /**
     * Counts grouped by one or more fields, inside the area. One row per group, each
     * the group's field values and its count under {@code n}.
     */
    public static List<JSONObject> countBy(String layer, String where, Area area, String... fields)
            throws IOException {
        final StringBuilder group = new StringBuilder();
        for (String f : fields)
            group.append(group.length() == 0 ? "" : ",").append(f);
        final String form = "where=" + enc(where == null ? "1=1" : where)
                + "&f=json"
                + area.esriQueryForm()
                + "&groupByFieldsForStatistics=" + enc(group.toString())
                + "&outStatistics=" + enc("[{\"statisticType\":\"count\",\"onStatisticField\":\"OBJECTID\","
                        + "\"outStatisticFieldName\":\"n\"}]");
        final JSONArray feats = features(Http.post(layer + "/query", form));
        final List<JSONObject> out = new ArrayList<>(feats.length());
        for (int i = 0; i < feats.length(); i++) {
            final JSONObject a = feats.optJSONObject(i) == null ? null : feats.optJSONObject(i).optJSONObject("attributes");
            if (a != null)
                out.add(a);
        }
        return out;
    }

    /** How many features inside the area match. */
    public static int count(String layer, String where, Area area) throws IOException {
        final String form = "where=" + enc(where == null ? "1=1" : where)
                + "&f=json&returnCountOnly=true"
                + area.esriQueryForm();
        final JSONObject o = parse(Http.post(layer + "/query", form));
        if (!o.has("count"))
            throw new IOException("no count in the answer");
        return o.optInt("count", 0);
    }

    /** The attributes of every feature inside the area that matches; no geometry. */
    public static List<JSONObject> attributes(String layer, String where, Area area, String outFields)
            throws IOException {
        final String form = "where=" + enc(where == null ? "1=1" : where)
                + "&f=json&returnGeometry=false"
                + "&outFields=" + enc(outFields)
                + area.esriQueryForm();
        final JSONArray feats = features(Http.post(layer + "/query", form));
        final List<JSONObject> out = new ArrayList<>(feats.length());
        for (int i = 0; i < feats.length(); i++) {
            final JSONObject a = feats.optJSONObject(i) == null ? null : feats.optJSONObject(i).optJSONObject("attributes");
            if (a != null)
                out.add(a);
        }
        return out;
    }

    /**
     * Point features inside the area or within {@code nearbyMiles} of it: each the
     * feature's attributes plus {@code _lat} and {@code _lon}.
     */
    public static List<JSONObject> points(String layer, String where, Area area, String outFields,
            int nearbyMiles) throws IOException {
        final String form = "where=" + enc(where == null ? "1=1" : where)
                + "&f=json&returnGeometry=true&outSR=4326"
                + "&outFields=" + enc(outFields)
                + area.esriQueryForm()
                + (nearbyMiles > 0 ? "&distance=" + nearbyMiles + "&units=esriSRUnit_StatuteMile" : "");
        final JSONArray feats = features(Http.post(layer + "/query", form));
        final List<JSONObject> out = new ArrayList<>(feats.length());
        for (int i = 0; i < feats.length(); i++) {
            final JSONObject f = feats.optJSONObject(i);
            final JSONObject a = f == null ? null : f.optJSONObject("attributes");
            final JSONObject g = f == null ? null : f.optJSONObject("geometry");
            if (a == null || g == null || !g.has("x") || !g.has("y"))
                continue;
            final double lon = g.optDouble("x"), lat = g.optDouble("y");
            if (!(Math.abs(lat) <= 90) || !(Math.abs(lon) <= 180))
                continue;
            try {
                a.put("_lat", lat).put("_lon", lon);
            } catch (org.json.JSONException e) {
                continue;
            }
            out.add(a);
        }
        return out;
    }

    /**
     * Polygon features touching the area or within {@code nearbyMiles} of it: each
     * outer ring as "_rings" ([[lon, lat], ...] lists) and the service's centroid as
     * "_lat"/"_lon" (the first ring's middle when it gives none).
     */
    public static List<JSONObject> areas(String layer, String where, Area area, String outFields,
            int nearbyMiles) throws IOException {
        final String form = "where=" + enc(where == null ? "1=1" : where)
                + "&f=json&returnGeometry=true&returnCentroid=true&outSR=4326&maxAllowableOffset=0.0005"
                + "&outFields=" + enc(outFields)
                + area.esriQueryForm()
                + (nearbyMiles > 0 ? "&distance=" + nearbyMiles + "&units=esriSRUnit_StatuteMile" : "");
        final JSONArray feats = features(Http.post(layer + "/query", form));
        final List<JSONObject> out = new ArrayList<>(feats.length());
        for (int i = 0; i < feats.length(); i++) {
            final JSONObject f = feats.optJSONObject(i);
            final JSONObject a = f == null ? null : f.optJSONObject("attributes");
            final JSONObject g = f == null ? null : f.optJSONObject("geometry");
            final JSONArray rings = g == null ? null : g.optJSONArray("rings");
            if (a == null || rings == null || rings.length() == 0)
                continue;
            final JSONObject c = f.optJSONObject("centroid");
            double lat = c == null ? Double.NaN : c.optDouble("y"), lon = c == null ? Double.NaN : c.optDouble("x");
            if (!(Math.abs(lat) <= 90) || !(Math.abs(lon) <= 180)) {
                final JSONArray r0 = rings.optJSONArray(0);
                final JSONArray v = r0 == null ? null : r0.optJSONArray(r0.length() / 2);
                lon = v == null ? Double.NaN : v.optDouble(0);
                lat = v == null ? Double.NaN : v.optDouble(1);
            }
            if (!(Math.abs(lat) <= 90) || !(Math.abs(lon) <= 180))
                continue;
            try {
                a.put("_lat", lat).put("_lon", lon).put("_rings", rings);
            } catch (org.json.JSONException e) {
                continue;
            }
            out.add(a);
        }
        return out;
    }

    /**
     * Point features in the area's box widened by {@code miles}, for a service that
     * refuses a distance buffer (the National Inventory of Dams). The caller measures
     * the real distance.
     */
    public static List<JSONObject> pointsInBox(String layer, String where, Area area, String outFields,
            int miles) throws IOException {
        final double dLat = miles / 69.0, dLon = miles / (69.0 * Math.max(0.2,
                Math.cos(Math.toRadians((area.south + area.north) / 2))));
        final String env = String.format(java.util.Locale.US,
                "{\"xmin\":%.6f,\"ymin\":%.6f,\"xmax\":%.6f,\"ymax\":%.6f,\"spatialReference\":{\"wkid\":4326}}",
                area.west - dLon, area.south - dLat, area.east + dLon, area.north + dLat);
        final String form = "where=" + enc(where == null ? "1=1" : where)
                + "&f=json&returnGeometry=true&outSR=4326"
                + "&outFields=" + enc(outFields)
                + "&geometry=" + enc(env) + "&geometryType=esriGeometryEnvelope&inSR=4326"
                + "&spatialRel=esriSpatialRelIntersects";
        final JSONArray feats = features(Http.post(layer + "/query", form));
        final List<JSONObject> out = new ArrayList<>(feats.length());
        for (int i = 0; i < feats.length(); i++) {
            final JSONObject f = feats.optJSONObject(i);
            final JSONObject a = f == null ? null : f.optJSONObject("attributes");
            final JSONObject g = f == null ? null : f.optJSONObject("geometry");
            if (a == null || g == null)
                continue;
            final double lon = g.optDouble("x"), lat = g.optDouble("y");
            if (!(Math.abs(lat) <= 90) || !(Math.abs(lon) <= 180))
                continue;
            try {
                a.put("_lat", lat).put("_lon", lon);
            } catch (org.json.JSONException e) {
                continue;
            }
            out.add(a);
        }
        return out;
    }

    /**
     * Line features inside the area: each the attributes plus {@code _paths}, a
     * JSONArray of paths of [lon, lat], thinned by the server.
     */
    public static List<JSONObject> lines(String layer, String where, Area area, String outFields)
            throws IOException {
        final String form = "where=" + enc(where == null ? "1=1" : where)
                + "&f=json&returnGeometry=true&outSR=4326&maxAllowableOffset=0.0002"
                + "&outFields=" + enc(outFields)
                + area.esriQueryForm();
        final JSONArray feats = features(Http.post(layer + "/query", form));
        final List<JSONObject> out = new ArrayList<>(feats.length());
        for (int i = 0; i < feats.length(); i++) {
            final JSONObject f = feats.optJSONObject(i);
            final JSONObject a = f == null ? null : f.optJSONObject("attributes");
            final JSONObject g = f == null ? null : f.optJSONObject("geometry");
            final JSONArray paths = g == null ? null : g.optJSONArray("paths");
            if (a == null || paths == null || paths.length() == 0)
                continue;
            try {
                a.put("_paths", paths);
            } catch (org.json.JSONException e) {
                continue;
            }
            out.add(a);
        }
        return out;
    }

    private static JSONArray features(String body) throws IOException {
        final JSONArray a = parse(body).optJSONArray("features");
        if (a == null)
            throw new IOException("no features in the answer");
        return a;
    }

    /** The answer as JSON; an ArcGIS error (which comes back as HTTP 200) as an exception. */
    static JSONObject parse(String body) throws IOException {
        try {
            final JSONObject o = new JSONObject(body);
            final JSONObject err = o.optJSONObject("error");
            if (err != null)
                throw new IOException("service error " + err.optInt("code") + ": " + err.optString("message"));
            return o;
        } catch (org.json.JSONException e) {
            throw new IOException("answer is not JSON");
        }
    }

    /**
     * Form-encodes a value. Every query puts "where" first in its body: the USGS
     * firewall answers 403 to a POST whose where clause carries UPPER(...) or LIKE
     * after another parameter, and passes the same clause first (2026-11-13).
     */
    public static String enc(String s) {
        try {
            return URLEncoder.encode(s, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            throw new IllegalStateException(e);
        }
    }
}
