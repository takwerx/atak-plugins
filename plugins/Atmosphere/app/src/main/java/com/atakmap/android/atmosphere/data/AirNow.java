package com.atakmap.android.atmosphere.data;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * EPA AirNow's latest Air Quality Index contours: the whole country as polygons, one
 * per band, ozone and fine particles combined, whichever is worse. Pure request
 * building and response reading; the overlay fetches and draws.
 *
 * <h3>What the service is, measured 2026-09-24</h3>
 *
 * <ul>
 *   <li>{@code AirNowLatestContoursCombined/FeatureServer/0} in ArcGIS organization
 *       {@code cJ9YHowT8TU7DUyn}, which is the U.S. EPA GeoPlatform (its portal
 *       answers "U.S. EPA", urlKey EPA). No key. The catalog's only search hit was a
 *       county's item pointing at it, so the organization was checked, not assumed.</li>
 *   <li>Latest <b>observed</b> contours, not a forecast. The service description says
 *       "AirNow National AQI Forecast", which is a label left on it; the layer is
 *       {@code AirNowLatest_Combined} and EPA's forecast is a separate service
 *       ({@code AirNow_National_Air_Quality_Index_(AQI)_Forecast}, today and tomorrow
 *       by reporting area).</li>
 *   <li>Fields {@code gridcode} 1-6 (the six AQI categories) and {@code Unixtime}.
 *       91 polygons nationally that night, 1 to 4 present, 182 rings: a band is a
 *       polygon with the worse bands cut out as holes. 192 KB as GeoJSON at full
 *       detail, 92 KB simplified to 0.01 degree, which is finer than the
 *       interpolation it came from.</li>
 * </ul>
 */
public final class AirNow {

    public static final String HOST = "services.arcgis.com";
    static final String LAYER = "https://" + HOST
            + "/cJ9YHowT8TU7DUyn/arcgis/rest/services/AirNowLatestContoursCombined/FeatureServer/0";

    /** The six AQI categories, gridcode 1 to 6, in EPA's own words and colors. */
    public enum Category {
        GOOD("Good", "Good", "0 to 50", 0xFF00E400,
                "Air quality is satisfactory, and air pollution poses little or no risk."),
        MODERATE("Moderate", "Moderate", "51 to 100", 0xFFFFFF00,
                "Air quality is acceptable. However, there may be a risk for some people,"
                        + " particularly those who are unusually sensitive to air pollution."),
        SENSITIVE("Unhealthy for Sensitive Groups", "Sensitive groups", "101 to 150", 0xFFFF7E00,
                "Members of sensitive groups may experience health effects. The general"
                        + " public is less likely to be affected."),
        UNHEALTHY("Unhealthy", "Unhealthy", "151 to 200", 0xFFFF0000,
                "Some members of the general public may experience health effects; members"
                        + " of sensitive groups may experience more serious health effects."),
        VERY_UNHEALTHY("Very Unhealthy", "Very unhealthy", "201 to 300", 0xFF8F3F97,
                "Health alert: The risk of health effects is increased for everyone."),
        HAZARDOUS("Hazardous", "Hazardous", "301 and higher", 0xFF7E0023,
                "Health warning of emergency conditions: everyone is more likely to be"
                        + " affected.");

        /** EPA's name for it. */
        public final String label;
        /** Short enough for a legend cell. */
        public final String shortLabel;
        /** The index values it covers. */
        public final String range;
        /** EPA's color for it, opaque. */
        public final int color;
        /** EPA's description of the air, from AirNow's AQI Basics. */
        public final String meaning;

        Category(String label, String shortLabel, String range, int color, String meaning) {
            this.label = label;
            this.shortLabel = shortLabel;
            this.range = range;
            this.color = color;
            this.meaning = meaning;
        }

        /** Null for anything outside 1 to 6. */
        public static Category of(int gridcode) {
            final Category[] all = values();
            return gridcode >= 1 && gridcode <= all.length ? all[gridcode - 1] : null;
        }
    }

    /** One band's polygon, with its rings kept for asking what a point is in. */
    public static final class Contour {
        public final Category category;
        /** The GeoJSON geometry as it came, for the map. */
        public final JSONObject geometry;
        final GeoRings.Area area;

        Contour(Category category, JSONObject geometry, GeoRings.Area area) {
            this.category = category;
            this.geometry = geometry;
            this.area = area;
        }

        /** Inside an outer ring and outside all of that polygon's holes. */
        public boolean contains(double lat, double lon) {
            return area.contains(lat, lon);
        }
    }

    /** What a response held. */
    public static final class Contours {
        public final List<Contour> contours;
        /** The stamp as the service wrote it, seconds; see {@link #observedAt}. */
        public final long unixtime;
        /** True when the service stopped short of the whole set. */
        public final boolean truncated;

        Contours(List<Contour> contours, long unixtime, boolean truncated) {
            this.contours = contours;
            this.unixtime = unixtime;
            this.truncated = truncated;
        }

        /** The worst category a point is in, or null outside every contour. */
        public Category at(double lat, double lon) {
            Category worst = null;
            for (Contour c : contours)
                if ((worst == null || c.category.ordinal() > worst.ordinal())
                        && c.contains(lat, lon))
                    worst = c.category;
            return worst;
        }
    }

    private AirNow() {
    }

    /**
     * Every contour, as GeoJSON in lon/lat, simplified to about a kilometer. The whole
     * country in one request: 92 KB, and a pan never has to ask again.
     */
    public static String contoursUrl() {
        return LAYER + "/query?where=1%3D1&outFields=gridcode,Unixtime&outSR=4326"
                + "&maxAllowableOffset=0.01&geometryPrecision=4&f=geojson";
    }

    /**
     * Just the stamp, a few hundred bytes: what the layer asks every quarter hour to
     * learn whether there is a new hour worth 92 KB.
     */
    public static String stampUrl() {
        return LAYER + "/query?where=1%3D1&returnGeometry=false&returnDistinctValues=true"
                + "&outFields=Unixtime&f=json";
    }

    /** The newest stamp in a {@link #stampUrl} answer, seconds, or 0. */
    public static long parseStamp(String body) {
        try {
            final JSONArray fs = new JSONObject(body).optJSONArray("features");
            long newest = 0;
            for (int i = 0; fs != null && i < fs.length(); i++) {
                final JSONObject a = fs.getJSONObject(i).optJSONObject("attributes");
                if (a != null)
                    newest = Math.max(newest, a.optLong("Unixtime", 0));
            }
            return newest;
        } catch (Exception e) {
            return 0;
        }
    }

    /**
     * Read a {@link #contoursUrl} answer. A feature with a gridcode outside 1-6 or a
     * geometry that is not a polygon is skipped rather than guessed at.
     */
    public static Contours parse(String body) throws Exception {
        final JSONObject root = new JSONObject(body);
        final JSONArray fs = root.optJSONArray("features");
        final List<Contour> out = new ArrayList<>();
        long stamp = 0;
        for (int i = 0; fs != null && i < fs.length(); i++) {
            final JSONObject f = fs.optJSONObject(i);
            if (f == null)
                continue;
            final JSONObject props = f.optJSONObject("properties");
            final JSONObject geom = f.optJSONObject("geometry");
            if (props == null || geom == null)
                continue;
            final Category cat = Category.of(props.optInt("gridcode", 0));
            if (cat == null)
                continue;
            // Polygons only: a contour band is an area, and anything else from this
            // service is not a band.
            final String type = geom.optString("type", "");
            if (!"Polygon".equals(type) && !"MultiPolygon".equals(type))
                continue;
            final GeoRings.Area area = GeoRings.of(geom);
            if (area == null)
                continue;
            stamp = Math.max(stamp, props.optLong("Unixtime", 0));
            out.add(new Contour(cat, geom, area));
        }
        final JSONObject meta = root.optJSONObject("properties");
        final boolean truncated = root.optBoolean("exceededTransferLimit", false)
                || (meta != null && meta.optBoolean("exceededTransferLimit", false));
        return new Contours(out, stamp, truncated);
    }

    /**
     * When the contours are for, UTC millis: the stamp is plain UTC.
     *
     * <p>Measured twice on 2026-09-24. At 04:57Z the layer was republished stamped
     * 00:00Z while EPA's monitors were at 04:00Z, which looked like Eastern time
     * written as UTC -- and was read that way for an hour. At 05:53Z it was republished
     * stamped 05:00Z, which Eastern would put four hours in the future. So the stamp is
     * UTC, and the first reading was the contours really being five hours old: EPA
     * re-publishes the last hour it made when a newer one has not been made. That is
     * what {@link #isStale} is for, and why the pane says so rather than hiding it.
     */
    public static long observedAt(long unixtimeSeconds) {
        return unixtimeSeconds <= 0 ? 0 : unixtimeSeconds * 1000L;
    }

    /**
     * True when the newest contours are older than EPA's usual lag. Measured: an hour's
     * contours land about 55 minutes after the hour, so two hours old is late.
     */
    public static boolean isStale(long unixtimeSeconds, long nowMillis) {
        final long t = observedAt(unixtimeSeconds);
        return t > 0 && nowMillis - t > 2 * 3_600_000L;
    }
}
