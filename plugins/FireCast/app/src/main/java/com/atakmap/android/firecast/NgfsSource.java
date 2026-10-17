package com.atakmap.android.firecast;

import android.util.Log;

import com.atakmap.map.layer.feature.geometry.Geometry;
import com.atakmap.map.layer.feature.geometry.Point;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * GOES: NIFC's list of fires, drawn at NOAA's own pixels.
 *
 * <p>NIFC's GOES layer is NOAA's Next Generation Fire System republished as one record
 * per tracked fire at its latest scan: on 2026-10-17 a box over northern California was
 * 17 records and 3 KB. It carries a center and a view angle, so the footprint had to
 * be derived. NOAA's own portal carries each pixel's four ground corners, a quality
 * flag and the LANDFIRE fuel under it, but it serves every five-minute scan of every
 * pixel: the same box over six hours was 1,053 records and 2.3 MB, uncompressed, for 36
 * places.
 *
 * <p>So NIFC says which fires there are and when each was last seen, and NOAA is asked
 * only for those scans, near those fires: a handful of pixels each. A fire stays one
 * detection and one row, drawn as the pixels it was hot in. A fire whose pixels NOAA
 * did not return keeps NIFC's derived box; NOAA's service is labeled an experimental
 * beta, and NIFC alone is the whole layer when it does not answer.
 */
public class NgfsSource implements DetectionSource {

    private static final String API = "https://fire.data.nesdis.noaa.gov/api/ogc/detections/collections/";
    private static final String WEST = "ngfs_schema.ngfs_detections_scene_west_conus";
    private static final String EAST = "ngfs_schema.ngfs_detections_scene_east_conus";
    /** Distinct scans asked of NOAA per refresh; the newest come first, the rest keep NIFC's box. */
    private static final int MAX_SCANS = 24;
    /** Degrees around a scan's fires: a large fire's pixels sit well off its center. */
    private static final double MARGIN_DEG = 0.3d;
    /** Time the pixel requests may take per refresh, all scans together. */
    private static final long BUDGET_MS = 20000L;
    /** A pixel is four corners and the closing one; anything much longer is not a pixel. */
    private static final int MAX_RING = 12;
    private final GoesSource nifc = new GoesSource();

    @Override
    public String id() {
        return "goes";
    }

    @Override
    public String title() {
        return "GOES";
    }

    @Override
    public String summary() {
        return "GOES-18 and GOES-19, each fire drawn at NOAA's own pixels, a scan every few minutes";
    }

    @Override
    public boolean defaultOn() {
        return true;
    }

    @Override
    public int count(Esri.Box box, long sinceMillis) throws Exception {
        return nifc.count(box, sinceMillis);
    }

    private static final class Fire {
        final JSONObject a;
        final JSONObject raw;
        final Point at;
        final List<JSONObject> pixels = new ArrayList<>();

        Fire(JSONObject a, JSONObject raw, Point at) {
            this.a = a;
            this.raw = raw;
            this.at = at;
        }
    }

    @Override
    public List<HeatDetection> fetch(Esri.Box box, long sinceMillis, int max) throws Exception {
        final List<Fire> fires = new ArrayList<>();
        Esri.query(Sources.GOES, GoesSource.where(sinceMillis), box, "acq_date_time DESC", 1000, max, new Esri.FeatureSink() {
            @Override
            public void feature(JSONObject a, Geometry g, JSONObject raw) {
                if (g instanceof Point)
                    fires.add(new Fire(a, raw, (Point) g));
            }
        });

        // One NOAA request per scan: the fires last seen in it, by satellite and time.
        final Map<String, List<Fire>> scans = new LinkedHashMap<>();
        for (Fire f : fires) {
            final long when = Fields.epochMillis(f.a, "acq_date_time");
            final String id = Fields.text(f.a, "feature_tracking_id");
            if (when <= 0 || id == null)
                continue;
            final String key = Fields.text(f.a, "satellite") + "|" + when;
            List<Fire> l = scans.get(key);
            if (l == null)
                scans.put(key, l = new ArrayList<>());
            l.add(f);
        }
        int asked = 0;
        final long giveUp = System.currentTimeMillis() + BUDGET_MS;
        for (List<Fire> scan : scans.values()) {
            // A slow NOAA does not hold the refresh: what is left keeps NIFC's boxes.
            if (asked++ >= MAX_SCANS || System.currentTimeMillis() > giveUp)
                break;
            try {
                pixelsFor(scan);
            } catch (Exception e) {
                Log.w(TAG, "NOAA pixels for one scan: " + e.getMessage());
            }
        }

        final List<HeatDetection> out = new ArrayList<>();
        for (Fire f : fires) {
            HeatDetection d = null;
            if (!f.pixels.isEmpty()) {
                try {
                    d = withPixels(f);
                } catch (Exception e) {
                    Log.w(TAG, "NOAA pixels unreadable: " + e.getMessage());
                }
            }
            if (d == null)
                d = nifc.read(f.a, f.at, f.raw);
            if (d != null)
                out.add(d);
        }
        return out;
    }

    private static final String TAG = "FireCast";

    /** The scene a satellite's CONUS scans are filed under; both when it is neither. */
    private static String[] scenes(String satellite) {
        final String s = satellite == null ? "" : satellite;
        if (s.contains("18") || s.contains("17"))
            return new String[] { WEST };
        if (s.contains("19") || s.contains("16"))
            return new String[] { EAST };
        return new String[] { WEST, EAST };
    }

    /** Fills each fire's pixels from NOAA's record of the one scan they share. */
    private static void pixelsFor(List<Fire> scan) throws Exception {
        final Map<String, Fire> byId = new HashMap<>();
        double s = 90, w = 180, n = -90, e = -180;
        for (Fire f : scan) {
            byId.put(Fields.text(f.a, "feature_tracking_id"), f);
            s = Math.min(s, f.at.getY());
            n = Math.max(n, f.at.getY());
            w = Math.min(w, f.at.getX());
            e = Math.max(e, f.at.getX());
        }
        final Fire first = scan.get(0);
        final String when = iso(Fields.epochMillis(first.a, "acq_date_time"));
        final String bbox = String.format(Locale.US, "%.4f,%.4f,%.4f,%.4f",
                w - MARGIN_DEG, s - MARGIN_DEG, e + MARGIN_DEG, n + MARGIN_DEG);
        for (String scene : scenes(Fields.text(first.a, "satellite"))) {
            final String url = API + scene + "/items?f=geojson&limit=500&bbox=" + bbox + "&datetime=" + when;
            final JSONArray feats = new JSONObject(Esri.get(url)).optJSONArray("features");
            for (int i = 0; feats != null && i < feats.length(); i++) {
                final JSONObject feat = feats.getJSONObject(i);
                final JSONObject p = feat.optJSONObject("properties");
                final JSONObject g = feat.optJSONObject("geometry");
                if (p == null || g == null)
                    continue;
                final Fire f = byId.get(p.optString("feature_tracking_id", ""));
                if (f == null)
                    continue;
                p.put("_geometry", g);
                f.pixels.add(p);
            }
        }
    }

    /** The fire as NIFC described it, drawn as the pixels NOAA saw hot. */
    private HeatDetection withPixels(Fire f) throws Exception {
        final JSONArray rings = new JSONArray();
        JSONObject hottest = null;
        for (JSONObject p : f.pixels) {
            final JSONArray ring = outerRing(p.optJSONObject("_geometry"));
            if (ring == null)
                continue;
            rings.put(ring);
            if (hottest == null || p.optDouble("frp", 0) > hottest.optDouble("frp", 0))
                hottest = p;
        }
        if (rings.length() == 0 || hottest == null)
            return null;
        final JSONObject a = new JSONObject(f.a.toString());
        a.put("ngfs_pixels", rings.length());
        for (String k : new String[] { "fuel", "land_cover", "bright_t7", "frp" }) {
            final Object v = hottest.opt(k);
            if (v != null && v != JSONObject.NULL)
                a.put(k.equals("frp") ? "ngfs_pixel_frp_max" : k, v);
        }
        final JSONObject esri = new JSONObject().put("rings", rings);
        return read(a, Esri.fromEsriJson(esri), esri);
    }

    /**
     * A GeoJSON pixel polygon's outer ring, cleaned and wound clockwise. Clockwise
     * because in Esri JSON, which is how a detection is saved, it opens a new part, so
     * several pixels of one fire stay several squares instead of one square with holes.
     * Cleaned because a repeated or collinear corner sends ATAK's tessellator into a
     * loop that pins the renderer: repeats and straight-through points are dropped, and
     * a ring with under three corners or no area is no ring.
     */
    static JSONArray outerRing(JSONObject geojson) throws Exception {
        if (geojson == null || !"Polygon".equals(geojson.optString("type")))
            return null;
        final JSONArray rings = geojson.optJSONArray("coordinates");
        if (rings == null || rings.length() == 0)
            return null;
        final JSONArray r = rings.getJSONArray(0);
        if (r.length() < 4 || r.length() > MAX_RING)
            return null;
        final List<double[]> pts = new ArrayList<>();
        for (int i = 0; i < r.length(); i++) {
            final JSONArray p = r.getJSONArray(i);
            final double x = p.getDouble(0), y = p.getDouble(1);
            if (Double.isNaN(x) || Double.isNaN(y) || Math.abs(x) > 180 || Math.abs(y) > 90)
                return null;
            final double[] q = { x, y };
            if (pts.isEmpty() || !same(pts.get(pts.size() - 1), q))
                pts.add(q);
        }
        while (pts.size() > 1 && same(pts.get(0), pts.get(pts.size() - 1)))
            pts.remove(pts.size() - 1);
        boolean dropped = true;
        while (dropped && pts.size() >= 3) {
            dropped = false;
            for (int i = 0; i < pts.size(); i++) {
                final double[] a = pts.get((i + pts.size() - 1) % pts.size()), b = pts.get(i), c = pts.get((i + 1) % pts.size());
                if (Math.abs((b[0] - a[0]) * (c[1] - a[1]) - (b[1] - a[1]) * (c[0] - a[0])) < 1e-10) {
                    pts.remove(i);
                    dropped = true;
                    break;
                }
            }
        }
        if (pts.size() < 3)
            return null;
        double area = 0;
        for (int i = 0; i < pts.size(); i++) {
            final double[] p0 = pts.get(i), p1 = pts.get((i + 1) % pts.size());
            area += p0[0] * p1[1] - p1[0] * p0[1];
        }
        if (Math.abs(area) < 1e-12)
            return null;
        if (area > 0)
            java.util.Collections.reverse(pts);
        final JSONArray out = new JSONArray();
        for (double[] p : pts)
            out.put(new JSONArray().put(p[0]).put(p[1]));
        out.put(new JSONArray().put(pts.get(0)[0]).put(pts.get(0)[1]));
        return out;
    }

    private static boolean same(double[] a, double[] b) {
        return Math.abs(a[0] - b[0]) < 1e-9 && Math.abs(a[1] - b[1]) < 1e-9;
    }

    private static String iso(long millis) {
        final java.text.SimpleDateFormat f = new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US);
        f.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
        return f.format(new java.util.Date(millis));
    }

    /** A fresh fetch and a saved record both come through here. */
    @Override
    public HeatDetection read(JSONObject a, Geometry g, JSONObject raw) {
        if (g == null)
            return null;
        if (g instanceof Point)
            return nifc.read(a, g, raw);
        final Double lat = Fields.number(a, "latitude"), lon = Fields.number(a, "longitude");
        if (lat == null || lon == null || !a.has("total_frp"))
            return null;
        final Integer n = Fields.integer(a, "ngfs_pixels");
        final Footprint fp = Footprint.pixel(g, lat, lon, Fields.number(a, "satellite_zenith_angle"), n == null ? 1 : n);
        final HeatDetection d = nifc.build(a, lat, lon, fp);
        if (d != null)
            d.record = PolarSource.record(a, raw);
        return d;
    }

    /** "FBFM10:49,FBFM5:17,Urban:3" as "timber litter 49%, brush 17%, urban 3%". */
    static String fuelWords(String fuel) {
        final StringBuilder sb = new StringBuilder();
        for (String part : fuel.split(",")) {
            final String[] kv = part.trim().split(":");
            if (kv.length != 2)
                continue;
            if (sb.length() > 0)
                sb.append(", ");
            sb.append(fuelName(kv[0].trim())).append(' ').append(kv[1].trim()).append('%');
        }
        return sb.toString();
    }

    private static String fuelName(String code) {
        switch (code.toUpperCase(Locale.US)) {
            case "FBFM1": return "short grass";
            case "FBFM2": return "timber grass";
            case "FBFM3": return "tall grass";
            case "FBFM4": return "chaparral";
            case "FBFM5": return "brush";
            case "FBFM6": return "dormant brush";
            case "FBFM7": return "southern rough";
            case "FBFM8": return "closed timber litter";
            case "FBFM9": return "hardwood litter";
            case "FBFM10": return "timber litter";
            case "FBFM11": return "light slash";
            case "FBFM12": return "medium slash";
            case "FBFM13": return "heavy slash";
            case "URBAN": return "urban";
            case "WATER": return "water";
            case "BARREN": return "bare";
            case "AGRICULTURE": return "farmed";
            default: return code.toLowerCase(Locale.US);
        }
    }
}
