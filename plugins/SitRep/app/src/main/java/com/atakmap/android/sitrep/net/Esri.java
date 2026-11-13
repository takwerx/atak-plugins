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
        final String form = "f=json"
                + "&where=" + enc(where == null ? "1=1" : where)
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
        final String form = "f=json&returnCountOnly=true"
                + "&where=" + enc(where == null ? "1=1" : where)
                + area.esriQueryForm();
        final JSONObject o = parse(Http.post(layer + "/query", form));
        if (!o.has("count"))
            throw new IOException("no count in the answer");
        return o.optInt("count", 0);
    }

    /** The attributes of every feature inside the area that matches; no geometry. */
    public static List<JSONObject> attributes(String layer, String where, Area area, String outFields)
            throws IOException {
        final String form = "f=json&returnGeometry=false"
                + "&where=" + enc(where == null ? "1=1" : where)
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

    public static String enc(String s) {
        try {
            return URLEncoder.encode(s, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            throw new IllegalStateException(e);
        }
    }
}
