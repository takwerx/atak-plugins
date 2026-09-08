package com.atakmap.android.evacmap;

import com.atakmap.map.layer.feature.AttributeSet;
import com.atakmap.map.layer.feature.geometry.Geometry;
import com.atakmap.map.layer.feature.geometry.GeometryCollection;
import com.atakmap.map.layer.feature.geometry.LineString;
import com.atakmap.map.layer.feature.geometry.Point;
import com.atakmap.map.layer.feature.geometry.Polygon;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Locale;
import java.util.Set;

/**
 * ArcGIS REST plumbing shared by every source: HTTP, JSON geometry, attributes.
 * Carried over from the NIFS feature-layer work; anonymous services only here.
 */
public final class Esri {
    private Esri() {
    }

    /** What a query page hands back: one feature at a time. */
    public interface FeatureSink {
        void feature(JSONObject attributes, Geometry geometry) throws Exception;
    }

    /** A layer's metadata, the parts a renderer and a loader need. */
    public static class LayerInfo {
        public String name, geometryType, displayField, objectIdField;
        public JSONObject drawingInfo;
        public final Set<String> fields = new HashSet<>();
        public final Set<String> dateFields = new HashSet<>();
        public int maxRecordCount = 1000;
    }

    public static LayerInfo layerInfo(String base, int layerId) throws Exception {
        final JSONObject m = new JSONObject(get(base + "/" + layerId + "?f=json"));
        if (m.has("error"))
            throw new IllegalStateException("layer " + layerId + ": " + m.getJSONObject("error").optString("message"));
        final LayerInfo i = new LayerInfo();
        i.name = m.optString("name", "Layer " + layerId);
        i.geometryType = m.optString("geometryType", "");
        i.displayField = m.optString("displayField", null);
        i.objectIdField = m.optString("objectIdField", "OBJECTID");
        i.drawingInfo = m.optJSONObject("drawingInfo");
        i.maxRecordCount = Math.max(1, Math.min(2000, m.optInt("maxRecordCount", 1000)));
        final JSONArray f = m.optJSONArray("fields");
        for (int k = 0; f != null && k < f.length(); k++) {
            final JSONObject fld = f.getJSONObject(k);
            i.fields.add(fld.optString("name"));
            if ("esriFieldTypeDate".equals(fld.optString("type")))
                i.dateFields.add(fld.optString("name"));
        }
        return i;
    }

    /**
     * Runs a where-clause query page by page and feeds every feature to the sink.
     * Esri JSON in WGS84; returns the count. Coordinates come back at six decimals,
     * which is a tenth of a meter and a third of the bytes.
     */
    public static int query(String base, int layerId, String where, int pageSize, int maxFeatures,
            double simplify, FeatureSink sink) throws Exception {
        int count = 0, offset = 0;
        while (true) {
            if (maxFeatures > 0 && count >= maxFeatures)
                break;
            final String url = base + "/" + layerId + "/query?where=" + enc(where)
                    + "&outFields=*&outSR=4326&geometryPrecision=6&f=json"
                    + "&resultRecordCount=" + pageSize + "&resultOffset=" + offset
                    + (simplify > 0 ? "&maxAllowableOffset=" + simplify : "");
            final JSONObject page = new JSONObject(get(url));
            if (page.has("error")) {
                final JSONObject err = page.getJSONObject("error");
                throw new IllegalStateException(err.optString("message") + " " + err.optJSONArray("details"));
            }
            final JSONArray feats = page.optJSONArray("features");
            if (feats == null || feats.length() == 0)
                break;
            for (int i = 0; i < feats.length(); i++) {
                final JSONObject f = feats.getJSONObject(i);
                final JSONObject props = f.optJSONObject("attributes");
                final JSONObject geom = f.optJSONObject("geometry");
                if (props == null || geom == null)
                    continue;
                final Geometry g = fromEsriJson(geom);
                if (g == null)
                    continue;
                sink.feature(props, g);
                count++;
            }
            final boolean more = page.optBoolean("exceededTransferLimit", false);
            if (!more && feats.length() < pageSize)
                break;
            offset += feats.length();
        }
        return count;
    }

    // ---- geometry -----------------------------------------------------------------

    /** Esri JSON: {x,y}, {points}, {paths}, {rings}. */
    public static Geometry fromEsriJson(JSONObject g) throws Exception {
        if (g.has("x") && g.has("y"))
            return new Point(g.getDouble("x"), g.getDouble("y"));
        if (g.has("points")) {
            final JSONArray pts = g.getJSONArray("points");
            final GeometryCollection gc = new GeometryCollection(2);
            for (int i = 0; i < pts.length(); i++)
                gc.addGeometry(new Point(pts.getJSONArray(i).getDouble(0), pts.getJSONArray(i).getDouble(1)));
            return gc;
        }
        if (g.has("paths")) {
            final JSONArray paths = g.getJSONArray("paths");
            if (paths.length() == 1)
                return line(paths.getJSONArray(0));
            final GeometryCollection gc = new GeometryCollection(2);
            for (int i = 0; i < paths.length(); i++)
                gc.addGeometry(line(paths.getJSONArray(i)));
            return gc;
        }
        if (g.has("rings"))
            return polygon(g.getJSONArray("rings"));
        return null;
    }

    private static LineString line(JSONArray coords) throws Exception {
        final LineString ls = new LineString(2);
        for (int i = 0; i < coords.length(); i++) {
            final JSONArray p = coords.getJSONArray(i);
            ls.addPoint(p.getDouble(0), p.getDouble(1));
        }
        return ls;
    }

    private static Polygon polygon(JSONArray rings) throws Exception {
        final Polygon poly = new Polygon(2);
        for (int i = 0; i < rings.length(); i++)
            poly.addRing(line(rings.getJSONArray(i)));
        return poly;
    }

    /** Every non-null property as a string; dates formatted from epoch ms. */
    public static AttributeSet toAttributes(JSONObject props, Set<String> dateFields) {
        final AttributeSet a = new AttributeSet();
        final SimpleDateFormat fmt = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US);
        final Iterator<String> keys = props.keys();
        while (keys.hasNext()) {
            final String k = keys.next();
            if (props.isNull(k))
                continue;
            final Object v = props.opt(k);
            if (v == null)
                continue;
            if (dateFields.contains(k) && v instanceof Number)
                a.setAttribute(k, fmt.format(new Date(((Number) v).longValue())));
            else
                a.setAttribute(k, String.valueOf(v));
        }
        return a;
    }

    public static String firstNonEmpty(String... values) {
        for (String v : values)
            if (v != null && !v.trim().isEmpty() && !"null".equals(v))
                return v.trim();
        return null;
    }

    // ---- plumbing -----------------------------------------------------------------

    public static String enc(String s) throws Exception {
        return URLEncoder.encode(s, "UTF-8");
    }

    public static String get(String url) throws Exception {
        final HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(20000);
        c.setReadTimeout(60000);
        c.setRequestProperty("User-Agent", "EvacMap/0.1 (ATAK plugin)");
        try {
            final int code = c.getResponseCode();
            if (code != 200) {
                final int q = url.indexOf('?');
                throw new IllegalStateException("HTTP " + code + " for " + (q > 0 ? url.substring(0, q) : url));
            }
            final StringBuilder sb = new StringBuilder();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(c.getInputStream(), "UTF-8"))) {
                final char[] buf = new char[16384];
                int n;
                while ((n = r.read(buf)) > 0)
                    sb.append(buf, 0, n);
            }
            return sb.toString();
        } finally {
            c.disconnect();
        }
    }
}
