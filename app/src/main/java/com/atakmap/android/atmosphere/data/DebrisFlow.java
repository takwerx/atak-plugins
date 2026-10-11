package com.atakmap.android.atmosphere.data;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * USGS emergency assessments of post-fire debris-flow hazard: for a burned area,
 * each stream basin's likelihood of producing a debris flow, the volume it would
 * carry, and the two combined, for one design storm (a 15-minute burst at a set
 * rain rate, 24 mm/h for most fires). The hazard is the burn's for about two to
 * five years, until the slopes grow back.
 *
 * <p>One ArcGIS map service: the fires assessed as points (national, small), each
 * fire's perimeter, and the basins as polygons (86,000 nationally, so those are
 * asked for the map's extent only). The basins name their fire by a short id
 * ({@code brd2024}) the points do not carry, so a basin is joined to its fire by
 * the start and assessment dates both match and the fire's point is nearest.
 *
 * <p>No Android types.
 */
public final class DebrisFlow {

    public static final String HOST = "earthquake.usgs.gov";
    private static final String BASE = "https://" + HOST + "/arcgis/rest/services/ls/pwfdf/MapServer/";

    /** Every fire assessed, nationally. */
    public static final String FIRES_URL = BASE + "0/query?where=1%3D1"
            + "&outFields=fire,location,status,Fire_ID,Rainfall,start_date,assessment_date,ScienceBaseURL"
            + "&outSR=4326&f=geojson";

    /** How far back a fire's assessment is drawn. */
    public static final long KEEP_MS = 3L * 365 * 24 * 60 * 60 * 1000;

    private static String box(double w, double s, double e, double n) {
        return "&geometry=" + String.format(Locale.US, "%.3f,%.3f,%.3f,%.3f", w, s, e, n)
                + "&geometryType=esriGeometryEnvelope&inSR=4326&spatialRel=esriSpatialRelIntersects";
    }

    /** The perimeters of recent fires crossing a box. */
    public static String perimetersUrl(double w, double s, double e, double n, String sinceYmd) {
        return BASE + "9/query?where=start_date%20%3E%3D%20DATE%20%27" + sinceYmd + "%27"
                + box(w, s, e, n) + "&outFields=fire_id,start_date,assessment_date"
                + "&outSR=4326&geometryPrecision=4&maxAllowableOffset=0.0005&f=geojson";
    }

    /** The basins of recent fires crossing a box, from a row offset (2,000 a page). */
    public static String basinsUrl(double w, double s, double e, double n, String sinceYmd,
            int offset) {
        return BASE + "4/query?where=start_date%20%3E%3D%20DATE%20%27" + sinceYmd + "%27"
                + box(w, s, e, n)
                + "&outFields=BCH_Legend,BP_Legend,BV_Legend,fire_id,start_date,assessment_date"
                + "&outSR=4326&geometryPrecision=4&maxAllowableOffset=0.0003&f=geojson"
                + (offset > 0 ? "&resultOffset=" + offset : "");
    }

    /** USGS's own: Low yellow, Moderate orange, High red. */
    public static final String[] HAZARDS = { "Low", "Moderate", "High" };
    public static final int[] COLORS = { 0xFFFFFF00, 0xFFFFAA00, 0xFFFF0000 };
    public static final int FIRE_COLOR = 0xFFFF7043;

    public static int hazard(String legend) {
        final String l = legend == null ? "" : legend.trim();
        for (int i = 0; i < HAZARDS.length; i++)
            if (HAZARDS[i].equalsIgnoreCase(l))
                return i;
        return -1;
    }

    /** A fire the USGS has assessed. */
    public static final class Fire {
        public final String name, location, status, id, rainfall, url;
        public final long start, assessed;
        public final double lat, lon;

        Fire(String name, String location, String status, String id, String rainfall,
                String url, long start, long assessed, double lat, double lon) {
            this.name = name;
            this.location = location;
            this.status = status;
            this.id = id;
            this.rainfall = rainfall;
            this.url = url;
            this.start = start;
            this.assessed = assessed;
            this.lat = lat;
            this.lon = lon;
        }
    }

    /** One basin's hazard; {@code hazard} indexes {@link #HAZARDS}, or -1 when unrated. */
    public static final class Basin {
        public final int hazard;
        public final String likelihood, volume, fireId;
        public final long start, assessed;
        public final JSONObject geometry;

        Basin(int hazard, String likelihood, String volume, String fireId, long start,
                long assessed, JSONObject geometry) {
            this.hazard = hazard;
            this.likelihood = likelihood;
            this.volume = volume;
            this.fireId = fireId;
            this.start = start;
            this.assessed = assessed;
            this.geometry = geometry;
        }
    }

    public static final class Perimeter {
        public final String fireId;
        public final long start, assessed;
        public final JSONObject geometry;

        Perimeter(String fireId, long start, long assessed, JSONObject geometry) {
            this.fireId = fireId;
            this.start = start;
            this.assessed = assessed;
            this.geometry = geometry;
        }
    }

    public static List<Fire> parseFires(String body) {
        final List<Fire> out = new ArrayList<>();
        for (JSONObject f : features(body)) {
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
            out.add(new Fire(StormReports.text(p, "fire"), StormReports.text(p, "location"),
                    StormReports.text(p, "status"), StormReports.text(p, "Fire_ID"),
                    StormReports.text(p, "Rainfall"), StormReports.text(p, "ScienceBaseURL"),
                    p.optLong("start_date", 0), p.optLong("assessment_date", 0), lat, lon));
        }
        return out;
    }

    public static List<Basin> parseBasins(String body) {
        final List<Basin> out = new ArrayList<>();
        for (JSONObject f : features(body)) {
            final JSONObject p = f.optJSONObject("properties");
            final JSONObject g = f.optJSONObject("geometry");
            if (p == null || g == null)
                continue;
            out.add(new Basin(hazard(StormReports.text(p, "BCH_Legend")),
                    StormReports.text(p, "BP_Legend"), StormReports.text(p, "BV_Legend"),
                    StormReports.text(p, "fire_id"), p.optLong("start_date", 0),
                    p.optLong("assessment_date", 0), g));
        }
        return out;
    }

    public static List<Perimeter> parsePerimeters(String body) {
        final List<Perimeter> out = new ArrayList<>();
        for (JSONObject f : features(body)) {
            final JSONObject p = f.optJSONObject("properties");
            final JSONObject g = f.optJSONObject("geometry");
            if (p == null || g == null)
                continue;
            out.add(new Perimeter(StormReports.text(p, "fire_id"), p.optLong("start_date", 0),
                    p.optLong("assessment_date", 0), g));
        }
        return out;
    }

    /**
     * The fire a basin or perimeter belongs to: the same start and assessment dates,
     * nearest to the shape, within 80 km. Null when none fits.
     */
    public static Fire fireFor(List<Fire> fires, long start, long assessed, double lat,
            double lon) {
        Fire best = null;
        double bestD = 80_000d;
        for (Fire f : fires) {
            if (f.start != start || f.assessed != assessed)
                continue;
            final double d = distanceMeters(lat, lon, f.lat, f.lon);
            if (d < bestD) {
                bestD = d;
                best = f;
            }
        }
        return best;
    }

    /** Great-circle distance, meters. */
    public static double distanceMeters(double lat1, double lon1, double lat2, double lon2) {
        final double r = 6371008.8;
        final double p1 = Math.toRadians(lat1), p2 = Math.toRadians(lat2);
        final double dp = p2 - p1, dl = Math.toRadians(lon2 - lon1);
        final double a = Math.sin(dp / 2) * Math.sin(dp / 2)
                + Math.cos(p1) * Math.cos(p2) * Math.sin(dl / 2) * Math.sin(dl / 2);
        return 2 * r * Math.asin(Math.min(1d, Math.sqrt(a)));
    }

    /**
     * The first coordinate of a GeoJSON shape, {lat, lon}, or null: enough to say which
     * fire a basin or a perimeter is nearest to.
     */
    public static double[] firstPoint(JSONObject geometry) {
        try {
            Object c = geometry.opt("coordinates");
            while (c instanceof JSONArray) {
                final JSONArray a = (JSONArray) c;
                if (a.length() == 0)
                    return null;
                if (!(a.opt(0) instanceof JSONArray))
                    return a.length() >= 2 ? new double[] { a.getDouble(1), a.getDouble(0) } : null;
                c = a.opt(0);
            }
        } catch (Exception e) {
            return null;
        }
        return null;
    }

    /** "24mm" as "24 mm/h (about 1 in/h)". */
    public static String rainfallWords(String rainfall) {
        if (rainfall == null)
            return "";
        final String r = rainfall.trim().toLowerCase(Locale.US).replace("mm", "").trim();
        try {
            final double mm = Double.parseDouble(r);
            final double in = mm / 25.4;
            return String.format(Locale.US, "%.0f mm/h (about %.1f in/h)", mm, in);
        } catch (NumberFormatException e) {
            return rainfall.trim();
        }
    }

    private static List<JSONObject> features(String body) {
        final List<JSONObject> out = new ArrayList<>();
        if (body == null || body.isEmpty())
            return out;
        try {
            final JSONArray fs = new JSONObject(body).optJSONArray("features");
            if (fs != null)
                for (int i = 0; i < fs.length(); i++) {
                    final JSONObject f = fs.optJSONObject(i);
                    if (f != null)
                        out.add(f);
                }
        } catch (Exception e) {
            return out;
        }
        return out;
    }

    private DebrisFlow() {
    }
}
