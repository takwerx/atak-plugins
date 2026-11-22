package com.atakmap.android.firecast;

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
import java.util.Iterator;

/**
 * ArcGIS REST plumbing: HTTP, paged queries over a map box, Esri JSON geometry.
 *
 * <p>Copied from the Feature Layer plugin and cut down to what FireCast needs. Fire
 * Cast reads public NIFC services with no key, so none of the portal search, org
 * scoping or token handling came across: every request here is anonymous https.
 */
public final class Esri {

    private Esri() {
    }

    /** What a query page hands back: one feature at a time. */
    public interface FeatureSink {
        /** @param esriGeometry the geometry exactly as the service served it, for saving */
        void feature(JSONObject attributes, Geometry geometry, JSONObject esriGeometry) throws Exception;
    }

    /** South, west, north, east in degrees: the box a query is limited to. */
    public static class Box {
        public final double south, west, north, east;

        public Box(double south, double west, double north, double east) {
            this.south = south;
            this.west = west;
            this.north = north;
            this.east = east;
        }

        public boolean contains(double lat, double lon) {
            return lat >= south && lat <= north && lon >= west && lon <= east;
        }

        /** This box grown by a fraction of its own size on every side. */
        public Box grown(double fraction) {
            final double dLat = (north - south) * fraction, dLon = (east - west) * fraction;
            return new Box(Math.max(-90, south - dLat), Math.max(-180, west - dLon),
                    Math.min(90, north + dLat), Math.min(180, east + dLon));
        }

        public double spanLat() {
            return north - south;
        }

        public double spanLon() {
            return east - west;
        }

        public String esriJson() {
            return "{\"xmin\":" + west + ",\"ymin\":" + south + ",\"xmax\":" + east + ",\"ymax\":" + north
                    + ",\"spatialReference\":{\"wkid\":4326}}";
        }

        @Override
        public String toString() {
            return String.format(java.util.Locale.US, "%.3f,%.3f..%.3f,%.3f", south, west, north, east);
        }
    }

    /**
     * Runs a query inside a box, page by page, and feeds every feature to the sink.
     * Returns the count. {@code maxFeatures} stops the paging; the caller decides what
     * to say about a truncated answer, because silently showing part of a picture is
     * the bug this plugin exists to stop.
     */
    public static int query(String service, String where, Box box, String orderBy, int pageSize,
            int maxFeatures, FeatureSink sink) throws Exception {
        int count = 0, offset = 0;
        while (true) {
            if (maxFeatures > 0 && count >= maxFeatures)
                break;
            final int want = maxFeatures > 0 ? Math.min(pageSize, maxFeatures - count) : pageSize;
            final StringBuilder url = new StringBuilder(service)
                    .append("/query?where=").append(enc(where))
                    .append("&outFields=*&outSR=4326&returnGeometry=true&f=json")
                    .append("&resultRecordCount=").append(want)
                    .append("&resultOffset=").append(offset);
            // Ordering is not decoration: paging without it is explicitly undefined in
            // ArcGIS, so the same feature can arrive on two pages and another on none.
            // It is also what makes a cap honest - newest first means a capped fetch
            // drops the oldest, not an arbitrary slice.
            if (orderBy != null)
                url.append("&orderByFields=").append(enc(orderBy));
            if (box != null)
                url.append("&geometry=").append(enc(box.esriJson()))
                        .append("&geometryType=esriGeometryEnvelope&inSR=4326")
                        .append("&spatialRel=esriSpatialRelIntersects");
            final JSONObject page = new JSONObject(get(url.toString()));
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
                sink.feature(props, g, geom);
                count++;
            }
            final boolean more = page.optBoolean("exceededTransferLimit", false);
            if (!more && feats.length() < want)
                break;
            offset += feats.length();
        }
        return count;
    }

    /** How many features a query would return, without fetching any of them. */
    public static int count(String service, String where, Box box) throws Exception {
        final StringBuilder url = new StringBuilder(service)
                .append("/query?where=").append(enc(where))
                .append("&returnCountOnly=true&f=json");
        if (box != null)
            url.append("&geometry=").append(enc(box.esriJson()))
                    .append("&geometryType=esriGeometryEnvelope&inSR=4326")
                    .append("&spatialRel=esriSpatialRelIntersects");
        final JSONObject d = new JSONObject(get(url.toString()));
        if (d.has("error"))
            throw new IllegalStateException(d.getJSONObject("error").optString("message"));
        return d.optInt("count", 0);
    }

    // ---- geometry -----------------------------------------------------------------

    /** Esri JSON: {x,y}, {points}, {paths}, {rings}. */
    public static Geometry fromEsriJson(JSONObject g) throws Exception {
        if (g.has("x") && g.has("y")) {
            // A null island coordinate is a missing one, not a place.
            final double x = g.optDouble("x", Double.NaN), y = g.optDouble("y", Double.NaN);
            if (Double.isNaN(x) || Double.isNaN(y))
                return null;
            return new Point(x, y);
        }
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

    /**
     * Esri JSON has no multipolygon: every ring of a feature sits in one "rings" list and
     * only the winding order says what it is - clockwise opens a new outer ring,
     * counter-clockwise is a hole in the outer ring before it. Reading the GeoJSON rule
     * here instead (first ring outer, rest holes) turns a RADR burn scar of 35 separate
     * islands into one island with 34 bogus holes, which tessellates to nothing and
     * disappears on zoom.
     */
    private static Geometry esriRings(JSONArray rings) throws Exception {
        final java.util.List<Polygon> parts = new java.util.ArrayList<>();
        for (int i = 0; i < rings.length(); i++) {
            final JSONArray r = rings.getJSONArray(i);
            if (r.length() < 4)
                continue;
            if (parts.isEmpty() || ringArea(r) < 0)
                parts.add(new Polygon(2));
            parts.get(parts.size() - 1).addRing(line(r));
        }
        if (parts.isEmpty())
            return null;
        if (parts.size() == 1)
            return parts.get(0);
        final GeometryCollection gc = new GeometryCollection(2);
        for (Polygon p : parts)
            gc.addGeometry(p);
        return gc;
    }

    /** Shoelace area of a ring in lon/lat, y up: negative is clockwise, an outer ring. */
    private static double ringArea(JSONArray ring) throws Exception {
        final int n = ring.length();
        double a = 0d;
        for (int i = 0; i < n; i++) {
            final JSONArray p0 = ring.getJSONArray(i);
            final JSONArray p1 = ring.getJSONArray((i + 1) % n);
            a += p0.getDouble(0) * p1.getDouble(1) - p1.getDouble(0) * p0.getDouble(1);
        }
        return a / 2d;
    }

    /** Every non-null property as a string, for the detail panel's raw section. */
    public static AttributeSet toAttributes(JSONObject props) {
        final AttributeSet a = new AttributeSet();
        final Iterator<String> keys = props.keys();
        while (keys.hasNext()) {
            final String k = keys.next();
            if (props.isNull(k))
                continue;
            final Object v = props.opt(k);
            if (v != null)
                a.setAttribute(k, String.valueOf(v));
        }
        return a;
    }

    // ---- plumbing -----------------------------------------------------------------

    public static String enc(String s) throws Exception {
        return URLEncoder.encode(s, "UTF-8");
    }

    /**
     * GET over https only. Public services, so no credential ever travels here - but the
     * scheme is checked again after the response, because Android follows redirects by
     * default and will follow an https request onto http without saying so. Checking the
     * URL we asked for only proves where the request started.
     */
    private static final int MAX_CHARS = 32 << 20;

    public static String get(String url) throws Exception {
        return get(url, new String[0]);
    }

    /** The same GET with more request headers, name and value in turn, for a service that asks for them. */
    public static String get(String url, String... headers) throws Exception {
        if (!url.startsWith("https://"))
            throw new IllegalStateException("not https: " + hostOf(url));
        final HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(20000);
        c.setReadTimeout(60000);
        c.setRequestProperty("User-Agent", "FireCast (ATAK plugin)");
        for (int i = 0; i + 1 < headers.length; i += 2)
            c.setRequestProperty(headers[i], headers[i + 1]);
        try {
            final int code = c.getResponseCode();
            final URL landed = c.getURL();
            if (landed != null && !"https".equalsIgnoreCase(landed.getProtocol()))
                throw new IllegalStateException("redirected off https to " + landed.getHost());
            if (code != 200) {
                final int q = url.indexOf('?');
                throw new IllegalStateException("HTTP " + code + " from " + (q > 0 ? url.substring(0, q) : url));
            }
            final StringBuilder sb = new StringBuilder();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(c.getInputStream(), "UTF-8"))) {
                final char[] buf = new char[16384];
                int n;
                while ((n = r.read(buf)) > 0) {
                    sb.append(buf, 0, n);
                    // A service that ignores its page limit would otherwise be held
                    // whole, and running out of memory is an Error no catch here sees.
                    if (sb.length() > MAX_CHARS)
                        throw new java.io.IOException("answer over " + (MAX_CHARS >> 20) + " MB from " + hostOf(url));
                }
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
