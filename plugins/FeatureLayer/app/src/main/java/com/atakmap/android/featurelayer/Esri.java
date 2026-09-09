package com.atakmap.android.featurelayer;

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

/** ArcGIS REST plumbing shared by every source: HTTP, JSON geometry, attributes. */
public final class Esri {
    private Esri() {
    }

    /** What a query page hands back: one feature at a time. */
    public interface FeatureSink {
        void feature(JSONObject attributes, Geometry geometry) throws Exception;
    }

    /** An item from a portal search: a feature service the user can see. */
    public static class Item {
        public String id, title, type, owner, url;
    }

    /** Feature services in the signed-in user's reach whose title matches. */
    public static java.util.List<Item> searchItems(String portal, String token, String text) throws Exception {
        final String q = "(" + text.trim() + ") type:\"Feature Service\"";
        final String url = portal + "/sharing/rest/search?q=" + enc(q)
                + "&sortField=modified&sortOrder=desc&num=25&f=json";
        final JSONObject d = new JSONObject(get(url, token));
        if (d.has("error"))
            throw new IllegalStateException(d.getJSONObject("error").optString("message"));
        final java.util.List<Item> out = new java.util.ArrayList<>();
        final JSONArray results = d.optJSONArray("results");
        for (int i = 0; results != null && i < results.length(); i++) {
            final JSONObject r = results.getJSONObject(i);
            if (r.isNull("url"))
                continue;
            final Item it = new Item();
            it.id = r.optString("id");
            it.title = r.optString("title", it.id);
            it.type = r.optString("type", "");
            it.owner = r.optString("owner", "");
            it.url = r.optString("url");
            out.add(it);
        }
        return out;
    }

    /** The layer ids a feature service exposes, in service order. */
    /**
     * A cheap fingerprint of what a query would return: the row count and the newest
     * value of a date field, in one statistics request of a few hundred bytes. Polling
     * this every minute costs nothing; the full fetch runs only when it changes.
     */
    public static String stamp(String base, int layerId, String where, String token, String timeField) throws Exception {
        final String stats = "[{\"statisticType\":\"count\",\"onStatisticField\":\"" + timeField
                + "\",\"outStatisticFieldName\":\"n\"},{\"statisticType\":\"max\",\"onStatisticField\":\"" + timeField
                + "\",\"outStatisticFieldName\":\"mx\"}]";
        final String url = base + "/" + layerId + "/query?where=" + enc(where) + "&outStatistics=" + enc(stats)
                + "&returnGeometry=false&f=json";
        final JSONObject d = new JSONObject(get(url, token));
        if (d.has("error"))
            throw new IllegalStateException(d.getJSONObject("error").optString("message"));
        final JSONArray feats = d.optJSONArray("features");
        if (feats == null || feats.length() == 0)
            return "0|0";
        final JSONObject a = feats.getJSONObject(0).optJSONObject("attributes");
        return a == null ? "0|0" : a.optLong("n") + "|" + a.optLong("mx");
    }

    /**
     * The organization's own name ("City of Corona"), from the portal's self description.
     * Anonymous for a public org; a private one answers with the generic "ArcGIS Online"
     * until a token is sent, so callers ask again after a sign-in. Null when unreadable.
     */
    public static String portalName(String portal, String token) {
        try {
            final JSONObject d = new JSONObject(get(portal + "/sharing/rest/portals/self?f=json", token));
            // A private org answers anonymously with no name (portalName is just
            // "ArcGIS Online"), so that is "unknown", not a name.
            final String n = d.optString("name", null);
            return n == null || n.trim().isEmpty() || "ArcGIS Online".equalsIgnoreCase(n.trim()) ? null : n.trim();
        } catch (Exception e) {
            return null;
        }
    }

    public static int[] serviceLayerIds(String base, String token) throws Exception {
        final JSONObject d = new JSONObject(get(base + "?f=json", token));
        if (d.has("error"))
            throw new IllegalStateException(d.getJSONObject("error").optString("message"));
        final JSONArray layers = d.optJSONArray("layers");
        final int[] ids = new int[layers == null ? 0 : layers.length()];
        for (int i = 0; i < ids.length; i++)
            ids[i] = layers.getJSONObject(i).getInt("id");
        return ids;
    }

    /** A layer's metadata, the parts a renderer and a loader need. */
    public static class LayerInfo {
        public String name, geometryType, displayField, objectIdField;
        public JSONObject drawingInfo;
        public final Set<String> fields = new HashSet<>();
        public final Set<String> dateFields = new HashSet<>();
        public int maxRecordCount = 1000;
    }

    public static LayerInfo layerInfo(String base, int layerId, String token) throws Exception {
        final JSONObject m = new JSONObject(get(base + "/" + layerId + "?f=json", token));
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
     * Esri JSON unless {@code geojson}; returns the count.
     */
    public static int query(String base, int layerId, String where, String token, boolean geojson,
            int pageSize, int maxFeatures, FeatureSink sink) throws Exception {
        int count = 0, offset = 0;
        while (true) {
            if (maxFeatures > 0 && count >= maxFeatures)
                break;
            final String url = base + "/" + layerId + "/query?where=" + enc(where)
                    + "&outFields=*&outSR=4326&f=" + (geojson ? "geojson" : "json")
                    + "&resultRecordCount=" + pageSize + "&resultOffset=" + offset;
            final JSONObject page = new JSONObject(get(url, token));
            if (page.has("error")) {
                final JSONObject err = page.getJSONObject("error");
                throw new IllegalStateException(err.optString("message") + " " + err.optJSONArray("details"));
            }
            final JSONArray feats = page.optJSONArray("features");
            if (feats == null || feats.length() == 0)
                break;
            for (int i = 0; i < feats.length(); i++) {
                final JSONObject f = feats.getJSONObject(i);
                final JSONObject props = f.optJSONObject(geojson ? "properties" : "attributes");
                final JSONObject geom = f.optJSONObject("geometry");
                if (props == null || geom == null)
                    continue;
                final Geometry g = geojson ? fromGeoJson(geom) : fromEsriJson(geom);
                if (g == null)
                    continue;
                sink.feature(props, g);
                count++;
            }
            final boolean more = page.optBoolean("exceededTransferLimit", false)
                    || (page.optJSONObject("properties") != null
                            && page.getJSONObject("properties").optBoolean("exceededTransferLimit", false));
            if (!more && feats.length() < pageSize)
                break;
            offset += feats.length();
        }
        return count;
    }

    // ---- geometry -----------------------------------------------------------------

    public static Geometry fromGeoJson(JSONObject g) throws Exception {
        final String type = g.optString("type", "");
        final JSONArray c = g.optJSONArray("coordinates");
        if (c == null)
            return null;
        switch (type) {
            case "Point":
                return new Point(c.getDouble(0), c.getDouble(1));
            case "MultiPoint": {
                final GeometryCollection gc = new GeometryCollection(2);
                for (int i = 0; i < c.length(); i++)
                    gc.addGeometry(new Point(c.getJSONArray(i).getDouble(0), c.getJSONArray(i).getDouble(1)));
                return gc;
            }
            case "LineString":
                return line(c);
            case "MultiLineString": {
                final GeometryCollection gc = new GeometryCollection(2);
                for (int i = 0; i < c.length(); i++)
                    gc.addGeometry(line(c.getJSONArray(i)));
                return gc;
            }
            case "Polygon":
                return polygon(c);
            case "MultiPolygon": {
                final GeometryCollection gc = new GeometryCollection(2);
                for (int i = 0; i < c.length(); i++)
                    gc.addGeometry(polygon(c.getJSONArray(i)));
                return gc;
            }
            default:
                return null;
        }
    }

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

    public static String sql(String s) {
        return "'" + s.replace("'", "''") + "'";
    }

    public static String enc(String s) throws Exception {
        return URLEncoder.encode(s, "UTF-8");
    }

    /**
     * Whether a portal's token may go to a service. Same host: yes. An ArcGIS Online org's
     * hosted services live on services<n>.arcgis.com: yes. Anything else only if the
     * server's own /rest/info names the portal as its owning system, which is the check
     * Esri's clients make. Never over plain http. A search result can name any URL at all
     * (an item "from URL" is free-form), and without this the org token went to it.
     */
    public static boolean trustsServer(String portal, String base) {
        try {
            if (portal == null || base == null)
                return false;
            final URL p = new URL(portal), b = new URL(base);
            if (!"https".equals(p.getProtocol()) || !"https".equals(b.getProtocol()))
                return false;
            final String ph = p.getHost().toLowerCase(java.util.Locale.US), bh = b.getHost().toLowerCase(java.util.Locale.US);
            if (ph.equals(bh))
                return true;
            final boolean online = ph.endsWith(".maps.arcgis.com") || ph.equals("www.arcgis.com") || ph.equals("arcgis.com");
            if (online)
                return bh.matches("services\\d*\\.arcgis\\.com");
            final int i = base.indexOf("/rest/services");
            if (i < 0)
                return false;
            final JSONObject info = new JSONObject(get(base.substring(0, i) + "/rest/info?f=json"));
            final String owner = info.optString("owningSystemUrl", null);
            if (owner == null || owner.isEmpty())
                return false;
            return new URL(owner).getHost().toLowerCase(java.util.Locale.US).equals(ph);
        } catch (Exception e) {
            return false;
        }
    }

    public static String get(String url) throws Exception {
        return get(url, null);
    }

    /** GET over https only; a token travels in the Esri authorization header, never in the URL. */
    public static String get(String url, String token) throws Exception {
        if (!url.startsWith("https://"))
            throw new IllegalStateException("not https: " + hostOf(url));
        final HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(20000);
        c.setReadTimeout(60000);
        c.setRequestProperty("User-Agent", "FeatureLayer/0.1 (ATAK plugin)");
        if (token != null)
            c.setRequestProperty("X-Esri-Authorization", "Bearer " + token);
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

    public static String hostOf(String url) {
        try {
            return new URL(url).getHost();
        } catch (Exception e) {
            return "?";
        }
    }
}
