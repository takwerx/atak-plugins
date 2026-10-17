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

    /**
     * A spatial filter for a query: the Esri JSON geometry, the type name the REST API
     * wants for it, and for a radius the distance parameters. Built, never sniffed from
     * the JSON, so a caller cannot hand over rings and have them read as an envelope.
     */
    public static class Scope {
        private final String geometry, type, extra;

        private Scope(String geometry, String type, String extra) {
            this.geometry = geometry;
            this.type = type;
            this.extra = extra;
        }

        /** Everything within {@code metres} of a point: a true circle, not its bounding box. */
        public static Scope circle(double lat, double lon, double metres) {
            return new Scope("{\"x\":" + lon + ",\"y\":" + lat + ",\"spatialReference\":{\"wkid\":4326}}",
                    "esriGeometryPoint", "&distance=" + (long) metres + "&units=esriSRUnit_Meter");
        }

        /** A lat/lon box: a map extent, or a loaded layer's own bounds. */
        public static Scope box(double south, double west, double north, double east) {
            return new Scope("{\"xmin\":" + west + ",\"ymin\":" + south + ",\"xmax\":" + east
                    + ",\"ymax\":" + north + ",\"spatialReference\":{\"wkid\":4326}}",
                    "esriGeometryEnvelope", "");
        }

        /** A shape the operator drew in ATAK; {@code rings} is the Esri JSON rings array. */
        public static Scope polygon(String rings) {
            return new Scope("{\"rings\":" + rings + ",\"spatialReference\":{\"wkid\":4326}}",
                    "esriGeometryPolygon", "");
        }

        /**
         * The same filter, asking the server to simplify each shape to within
         * {@code degrees}: fire perimeters around Redding were 25 MB for a 100 km view at
         * full detail and 1.3 MB at 0.001 degrees (about 100 m), 2026-10-06.
         */
        public Scope simplified(double degrees) {
            // geometryPrecision: five decimals, about a meter, instead of the
            // fifteen the server writes by default; the coordinates are most of
            // the bytes and a land layer's fetch was 6 to 8 s on the phone (2026-10-16).
            return new Scope(geometry, type, extra + String.format(java.util.Locale.US,
                    "&maxAllowableOffset=%.6f&geometryPrecision=5", degrees));
        }

        String params() throws Exception {
            return "&geometry=" + enc(geometry) + "&geometryType=" + type
                    + "&inSR=4326&spatialRel=esriSpatialRelIntersects" + extra;
        }
    }

    /**
     * Feature services the organization owns whose title matches. The query carries the
     * org's own ID: without it ArcGIS Online answers from every public item on the
     * platform (0.3 listed DC fire hydrants for a California city), and an Enterprise
     * portal does the same with everything it federates.
     */
    public static java.util.List<Item> searchItems(String portal, String token, String text) throws Exception {
        final String orgId = portalId(portal, token);
        if (orgId == null)
            throw new IllegalStateException("could not read the organization's ID; sign in and try again");
        final String words = text.trim();
        final String q = (words.isEmpty() ? "" : "(" + words + ") ") + "type:\"Feature Service\" orgid:" + orgId;
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
        return stamp(base, layerId, where, null, token, timeField);
    }

    /**
     * The same fingerprint for a scoped layer. It has to carry the same filter as the
     * fetch: a stamp of the whole national feed changes every minute whatever is in
     * view, so it would either refresh constantly or describe rows nobody is drawing.
     */
    public static String stamp(String base, int layerId, String where, Scope scope, String token, String timeField)
            throws Exception {
        final String stats = "[{\"statisticType\":\"count\",\"onStatisticField\":\"" + timeField
                + "\",\"outStatisticFieldName\":\"n\"},{\"statisticType\":\"max\",\"onStatisticField\":\"" + timeField
                + "\",\"outStatisticFieldName\":\"mx\"}]";
        final String params = "where=" + enc(where) + "&outStatistics=" + enc(stats)
                + "&returnGeometry=false&f=json" + (scope == null ? "" : scope.params());
        final String url = base + "/" + layerId + "/query";
        final JSONObject d = new JSONObject(scope == null ? get(url + "?" + params, token) : post(url, params, token));
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

    /**
     * The organization's ID from the portal's self description, the value a content
     * search is scoped with. Read fresh every time, so it always belongs to whoever is
     * signed in now; null when the portal does not say (an anonymous read of a private
     * org).
     */
    public static String portalId(String portal, String token) {
        try {
            final JSONObject d = new JSONObject(get(portal + "/sharing/rest/portals/self?f=json", token));
            final String id = d.optString("id", null);
            return id == null || id.trim().isEmpty() ? null : id.trim();
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
        return query(base, layerId, where, null, token, geojson, pageSize, maxFeatures, sink);
    }

    /**
     * The same query with a spatial filter, and every scoped page goes by POST.
     * services*.arcgis.com answers a GET whose URL runs over about 2,000 characters with
     * HTTP 404, not 414: measured 747 characters fine, 2,275 refused, every length above
     * it refused, the identical query by POST fine. A 40-vertex shape traced by hand is
     * already 2,500 characters once URL-encoded, and one real fire perimeter was 237
     * rings and 52,270 vertices -- 2.0 MB. So this is not a limit to approach with care,
     * it is one to stay off entirely, and a 404 would read as a wrong URL or a deleted
     * layer rather than as a long request.
     */
    public static int query(String base, int layerId, String where, Scope scope, String token, boolean geojson,
            int pageSize, int maxFeatures, FeatureSink sink) throws Exception {
        return query(base, layerId, where, scope, token, geojson, pageSize, maxFeatures, null, sink);
    }

    /** The same, asking for only {@code outFields} (comma separated); null is every field. */
    public static int query(String base, int layerId, String where, Scope scope, String token, boolean geojson,
            int pageSize, int maxFeatures, String outFields, FeatureSink sink) throws Exception {
        return query(base, layerId, where, scope, token, geojson, pageSize, maxFeatures, outFields, false, sink);
    }

    /**
     * The same, and with {@code pbf} each page is asked for as Esri's FeatureCollection
     * protobuf instead of JSON: four to nine times fewer bytes for a land layer, which
     * was most of its redraw time on the phone (2026-10-16). A page the server will not
     * answer that way, or one that does not decode, is fetched again as JSON and the
     * rest of the query stays on JSON, so a service that drops PBF costs time, not a
     * layer. Every service a land layer reads lists PBF among its query formats.
     */
    public static int query(String base, int layerId, String where, Scope scope, String token, boolean geojson,
            int pageSize, int maxFeatures, String outFields, boolean pbf, FeatureSink sink) throws Exception {
        int count = 0, offset = 0;
        // The cap is a cap: a 1,000-row page for a 300-feature layer delivered 1,000
        // (a national view of DART on 2026-09-18). Ask for no more than the cap, and
        // stop handing rows to the sink at it.
        if (maxFeatures > 0)
            pageSize = Math.min(pageSize, maxFeatures);
        while (true) {
            if (maxFeatures > 0 && count >= maxFeatures)
                break;
            final String params = "where=" + enc(where)
                    + "&outFields=" + (outFields == null || outFields.isEmpty() ? "*" : enc(outFields))
                    + "&outSR=4326&f=" + (geojson ? "geojson" : "json")
                    + "&resultRecordCount=" + pageSize + "&resultOffset=" + offset
                    + (scope == null ? "" : scope.params());
            final String url = base + "/" + layerId + "/query";
            if (pbf) {
                final int[] got = pbfPage(url, params, token, layerId, offset, maxFeatures - count, sink);
                if (got != null) {
                    count += got[2];
                    if (got[0] == 0 || (got[1] == 0 && got[0] < pageSize))
                        break;
                    offset += got[0];
                    continue;
                }
                pbf = false; // this page and the rest as JSON
            }
            final long t0 = System.currentTimeMillis();
            final String body = scope == null ? get(url + "?" + params, token) : post(url, params, token);
            final long t1 = System.currentTimeMillis();
            final JSONObject page = new JSONObject(body);
            final long t2 = System.currentTimeMillis();
            if (page.has("error")) {
                final JSONObject err = page.getJSONObject("error");
                throw new IllegalStateException(err.optString("message") + " " + err.optJSONArray("details"));
            }
            final JSONArray feats = page.optJSONArray("features");
            if (feats == null || feats.length() == 0)
                break;
            // Where a slow fetch spends its time (2026-10-16): the wire, the parse, or the sink.
            final long[] sinkMs = { 0 };
            final long t3 = System.currentTimeMillis();
            com.atakmap.coremap.log.Log.d("FeatureLayer", "page " + layerId + " offset " + offset + ": " + body.length()
                    + " chars, wire " + (t1 - t0) + " ms, parse " + (t2 - t1) + " ms, " + feats.length() + " rows");
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
                if (maxFeatures > 0 && count >= maxFeatures)
                    break;
            }
            com.atakmap.coremap.log.Log.d("FeatureLayer", "page " + layerId + " offset " + offset + ": sink "
                    + (System.currentTimeMillis() - t3) + " ms");
            final boolean more = page.optBoolean("exceededTransferLimit", false)
                    || (page.optJSONObject("properties") != null
                            && page.getJSONObject("properties").optBoolean("exceededTransferLimit", false));
            if (!more && feats.length() < pageSize)
                break;
            offset += feats.length();
        }
        return count;
    }

    /**
     * One page as PBF: {rows in the page, 1 when the server says more follow, rows handed
     * to the sink}, or null when the page could not be had that way (the JSON path takes
     * over).
     * {@code params} is the JSON query's form body; only its format changes.
     */
    private static int[] pbfPage(String url, String params, String token, int layerId, int offset, int room,
            final FeatureSink sink) throws Exception {
        final String body = params.replace("&f=json", "&f=pbf").replace("&f=geojson", "&f=pbf");
        final long t0 = System.currentTimeMillis();
        final byte[] data;
        try {
            data = postBytes(url, body, token);
        } catch (Exception e) {
            com.atakmap.coremap.log.Log.w("FeatureLayer", "pbf page " + layerId + " offset " + offset
                    + " not served: " + e.getMessage() + "; asking for JSON");
            return null;
        }
        final long t1 = System.currentTimeMillis();
        // A service that cannot answer in PBF answers the error in JSON, with 200.
        if (data.length > 0 && data[0] == '{') {
            String why = "not protobuf";
            try {
                final JSONObject err = new JSONObject(new String(data, "UTF-8")).optJSONObject("error");
                if (err != null)
                    why = err.optString("message");
            } catch (org.json.JSONException ignored) {
            }
            com.atakmap.coremap.log.Log.w("FeatureLayer", "pbf page " + layerId + ": " + why + "; asking for JSON");
            return null;
        }
        // Decoded whole before any row reaches the sink: a page that fails halfway is
        // fetched again as JSON, and its first half must not arrive twice.
        final java.util.List<JSONObject> propsList = new java.util.ArrayList<>();
        final java.util.List<Geometry> geoms = new java.util.ArrayList<>();
        final int cap = room > 0 ? room : Integer.MAX_VALUE;
        final EsriPbf.Page p;
        try {
            p = EsriPbf.read(data, new EsriPbf.Sink() {
                @Override
                public void feature(java.util.Map<String, Object> attrs, int type, java.util.List<double[]> parts)
                        throws Exception {
                    if (geoms.size() >= cap)
                        return;
                    final Geometry g = fromParts(type, parts);
                    if (g == null)
                        return;
                    final JSONObject props = new JSONObject();
                    for (java.util.Map.Entry<String, Object> e : attrs.entrySet()) {
                        final Object v = e.getValue();
                        // org.json refuses NaN and infinity; a server's odd number is
                        // one empty field, never a failed layer.
                        if (v instanceof Double && (((Double) v).isNaN() || ((Double) v).isInfinite()))
                            continue;
                        props.put(e.getKey(), v);
                    }
                    propsList.add(props);
                    geoms.add(g);
                }
            });
        } catch (IllegalStateException | IndexOutOfBoundsException e) {
            // A page that is not the protobuf we expect: the format, not the server.
            com.atakmap.coremap.log.Log.w("FeatureLayer", "pbf page " + layerId + " offset " + offset
                    + " did not decode: " + e.getMessage() + "; asking for JSON");
            return null;
        }
        final long t2 = System.currentTimeMillis();
        for (int i = 0; i < geoms.size(); i++)
            sink.feature(propsList.get(i), geoms.get(i));
        com.atakmap.coremap.log.Log.d("FeatureLayer", "pbf page " + layerId + " offset " + offset + ": " + data.length
                + " bytes, wire " + (t1 - t0) + " ms, decode " + (t2 - t1) + " ms, sink "
                + (System.currentTimeMillis() - t2) + " ms, " + p.count + " rows");
        // The page's own row count drives the offset: it is what the server counts.
        return new int[] { p.count, p.exceededTransferLimit ? 1 : 0, geoms.size() };
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
            return esriRings(g.getJSONArray("rings"));
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

    /**
     * Esri JSON has no multipolygon: every ring of a feature sits in one "rings" list and
     * only the winding order says what it is - clockwise opens a new outer ring, counter-
     * clockwise is a hole in the outer ring before it. GeoJSON says the first ring is the
     * outer one and the rest are holes, which is what this used to assume, so a FIRIS heat
     * perimeter of thirteen separate burn islands became one island with twelve bogus
     * holes: the fill tessellated to nothing and the shape dropped out on zoom-in.
     */
    private static Geometry esriRings(JSONArray rings) throws Exception {
        final java.util.List<double[]> flat = new java.util.ArrayList<>(rings.length());
        for (int i = 0; i < rings.length(); i++) {
            final JSONArray r = rings.getJSONArray(i);
            final double[] d = new double[r.length() * 2];
            for (int j = 0; j < r.length(); j++) {
                final JSONArray p = r.getJSONArray(j);
                d[j * 2] = p.getDouble(0);
                d[j * 2 + 1] = p.getDouble(1);
            }
            flat.add(d);
        }
        return esriRings(flat);
    }

    /** A decoded PBF feature as geometry, by the same rules as the JSON. */
    static Geometry fromParts(int type, java.util.List<double[]> parts) throws Exception {
        switch (type) {
            case EsriPbf.TYPE_POINT: {
                final double[] p = parts.get(0);
                return p.length < 2 ? null : new Point(p[0], p[1]);
            }
            case EsriPbf.TYPE_POLYLINE: {
                if (parts.size() == 1)
                    return line(parts.get(0));
                final GeometryCollection gc = new GeometryCollection(2);
                for (double[] p : parts)
                    gc.addGeometry(line(p));
                return gc;
            }
            case EsriPbf.TYPE_POLYGON:
                return esriRings(parts);
            default:
                return null;
        }
    }

    private static LineString line(double[] xy) {
        final LineString ls = new LineString(2);
        for (int i = 0; i + 1 < xy.length; i += 2)
            ls.addPoint(xy[i], xy[i + 1]);
        return ls;
    }

    /** {@link #esriRings(JSONArray)} over rings of x,y pairs. */
    private static Geometry esriRings(java.util.List<double[]> rings) throws Exception {
        final java.util.List<Polygon> parts = new java.util.ArrayList<>();
        for (int i = 0; i < rings.size(); i++) {
            final double[] r = rings.get(i);
            final double a = ringArea(r);
            // A ring the server's simplification collapsed to a line or a point: CAL
            // FIRE's State Responsibility Area is one polygon of 927 rings, and at a
            // 50 m offset hundreds of its inholdings come back as three or four points
            // of no area. The tessellator refused the whole polygon and nothing of the
            // state's responsibility drew (2026-10-14). Dropped; a polygon whose outer
            // ring is one of them is dropped with it.
            if (r.length < 8 || Math.abs(a) < 1e-12) {
                if (a < 0 || parts.isEmpty())
                    parts.add(null); // an outer ring with nothing in it: its holes go nowhere
                continue;
            }
            if (parts.isEmpty() || a < 0)
                parts.add(new Polygon(2));
            final Polygon p = parts.get(parts.size() - 1);
            if (p != null)
                p.addRing(line(r));
        }
        parts.removeAll(java.util.Collections.singleton(null));
        if (parts.isEmpty())
            return null;
        if (parts.size() == 1)
            return parts.get(0);
        final GeometryCollection gc = new GeometryCollection(2);
        for (Polygon p : parts)
            gc.addGeometry(p);
        return gc;
    }

    /** Shoelace area of a ring of x,y pairs in lon/lat, y up: negative is clockwise, an outer ring. */
    private static double ringArea(double[] xy) {
        final int n = xy.length / 2;
        double a = 0d;
        for (int i = 0; i < n; i++) {
            final int j = (i + 1) % n;
            a += xy[i * 2] * xy[j * 2 + 1] - xy[j * 2] * xy[i * 2 + 1];
        }
        return a / 2d;
    }

    /** The format {@link #toAttributes} writes date fields in. */
    private static final String STORED_DATE = "yyyy-MM-dd HH:mm";

    /**
     * "4 min ago" for a stored date string, or null when the value is not one. Worked out
     * when it is shown, never when it is stored: a last known location is only as good as
     * its age, and an age written into the cache would keep saying "2 min ago" for hours.
     */
    public static String ago(String stored) {
        if (stored == null || stored.length() != STORED_DATE.length())
            return null;
        final long then;
        try {
            final SimpleDateFormat f = new SimpleDateFormat(STORED_DATE, Locale.US);
            final Date d = f.parse(stored);
            if (d == null)
                return null;
            then = d.getTime();
        } catch (Exception e) {
            return null;
        }
        final long secs = (System.currentTimeMillis() - then) / 1000L;
        if (secs < -90)
            return "in the future";
        if (secs < 90)
            return "just now";
        final long mins = (secs + 30) / 60;
        if (mins < 90)
            return mins + " min ago";
        final long hours = (mins + 30) / 60;
        if (hours < 36)
            return hours + (hours == 1 ? " hour ago" : " hours ago");
        return ((hours + 12) / 24) + " days ago";
    }

    /** Every non-null property as a string; dates formatted from epoch ms. */
    public static AttributeSet toAttributes(JSONObject props, Set<String> dateFields) {
        final AttributeSet a = new AttributeSet();
        final SimpleDateFormat fmt = new SimpleDateFormat(STORED_DATE, Locale.US);
        final Iterator<String> keys = props.keys();
        while (keys.hasNext()) {
            final String k = keys.next();
            // Keys starting with "_" are the plugin's own (_title, _time, _bare...): a
            // server must not be able to plant one (security review, 2026-09-18).
            if (k.startsWith("_") || props.isNull(k))
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

    /**
     * POST of a form-encoded body, same rules as {@link #get}: https only, and the token
     * in the Esri authorization header rather than the body, so it never lands in a
     * server access log.
     */
    public static String post(String url, String body, String token) throws Exception {
        if (!url.startsWith("https://"))
            throw new IllegalStateException("not https: " + hostOf(url));
        final HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(20000);
        c.setReadTimeout(60000);
        c.setRequestMethod("POST");
        c.setDoOutput(true);
        c.setRequestProperty("User-Agent", "FeatureLayer/0.1 (ATAK plugin)");
        c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
        if (token != null)
            c.setRequestProperty("X-Esri-Authorization", "Bearer " + token);
        try {
            final byte[] out = body.getBytes("UTF-8");
            c.setFixedLengthStreamingMode(out.length);
            try (java.io.OutputStream os = c.getOutputStream()) {
                os.write(out);
            }
            final int code = c.getResponseCode();
            if (code != 200)
                throw new IllegalStateException("HTTP " + code + " for POST " + url);
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

    /** {@link #post} returning the body's bytes: a PBF answer is not text. */
    public static byte[] postBytes(String url, String body, String token) throws Exception {
        if (!url.startsWith("https://"))
            throw new IllegalStateException("not https: " + hostOf(url));
        final HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(20000);
        c.setReadTimeout(60000);
        c.setRequestMethod("POST");
        c.setDoOutput(true);
        c.setRequestProperty("User-Agent", "FeatureLayer/0.1 (ATAK plugin)");
        c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
        if (token != null)
            c.setRequestProperty("X-Esri-Authorization", "Bearer " + token);
        try {
            final byte[] out = body.getBytes("UTF-8");
            c.setFixedLengthStreamingMode(out.length);
            try (java.io.OutputStream os = c.getOutputStream()) {
                os.write(out);
            }
            final int code = c.getResponseCode();
            if (code != 200)
                throw new IllegalStateException("HTTP " + code + " for POST " + url);
            final java.io.ByteArrayOutputStream sb = new java.io.ByteArrayOutputStream(65536);
            try (java.io.InputStream in = c.getInputStream()) {
                final byte[] buf = new byte[16384];
                int n;
                while ((n = in.read(buf)) > 0)
                    sb.write(buf, 0, n);
            }
            return sb.toByteArray();
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
