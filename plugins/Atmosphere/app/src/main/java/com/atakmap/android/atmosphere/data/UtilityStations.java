package com.atakmap.android.atmosphere.data;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;

/**
 * California's utility weather stations -- SCE, SDG&amp;E, PG&amp;E -- and HPWREN's, with
 * what they are reading now.
 *
 * <p>From Cal OES's public extract of Synoptic Data ({@code
 * Synoptic_WeatherStations_Current_view}, rewritten every five minutes): one ArcGIS
 * distance query, the same shape as {@link Raws#nearUrl}, filtered to the four networks
 * by Synoptic's own network ids. 371 stations within 25 miles of Santa Clarita were
 * 100 KB and half a second (2026-10-05), against 31 RAWS in the same circle, which is
 * why the layer draws these only when zoomed in.
 *
 * <p>Values arrive in mph and degrees Fahrenheit, checked against SDG&amp;E's own feed
 * and NIFC's RAWS for the same hour. {@code STID} is the MesoWest id, the same string
 * as a RAWS's {@link Raws.Station#mesowestId}, which is how a station is never drawn
 * twice. RAWS are not asked for here (network 2): NIFC is their source, because only
 * it carries fuel moisture.
 *
 * <p><b>Not a RAWS.</b> Utilities average sustained wind over a minute or two, where a
 * RAWS averages ten, so the same air reads higher here; and none of these carries
 * fuel moisture. The station's own record says so.
 *
 * <p>No Android types here, so the request and the parsing can be tested.
 */
public final class UtilityStations {

    public static final String HOST = "services.arcgis.com";

    private static final String LAYER = "https://" + HOST
            + "/BLN4oKB0N1YSgvY8/arcgis/rest/services/"
            + "Synoptic_WeatherStations_Current_view/FeatureServer/0";

    /** Synoptic's network ids: SCE, SDG&E, PG&E, HPWREN. */
    static final int SCE = 231, SDGE = 139, PGE = 229, HPWREN = 81;
    private static final String NETWORKS = "MNET_ID IN (" + SCE + "," + SDGE + "," + PGE
            + "," + HPWREN + ")";

    /** Everything read back; asked for by name, never "*". */
    private static final String FIELDS = "STID,NAME,MNET_ID,LATITUDE,LONGITUDE,ELEV_DEM,"
            + "AIR_TEMP,RELATIVE_HUMIDITY,WIND_SPEED,WIND_GUST,WIND_DIRECTION,"
            + "WIND_SPEED_OBSERVED_DATETIME,AIR_TEMP_OBSERVED_DATETIME";

    /**
     * The farthest these are asked for, in miles. Fifty around Los Angeles is well over
     * a thousand stations; the service answers at most 2,000 at a time.
     */
    public static final int MAX_MILES = 50;

    /** At most this many ids in one request, for the same reason as {@link Raws#MAX_IDS_PER_QUERY}. */
    public static final int MAX_IDS_PER_QUERY = 60;

    /** What sets a utility station's id apart from a NIFC one wherever ids are kept. */
    public static final String ID_PREFIX = "U:";

    private UtilityStations() {
    }

    /** California, with a margin: the extract holds nothing outside it. */
    public static boolean covers(double lat, double lon) {
        return lat >= 32.0 && lat <= 42.5 && lon >= -125.0 && lon <= -113.5;
    }

    /** The stations within {@code miles} of a point, by the server's own distance. */
    public static String nearUrl(double lat, double lon, double miles) {
        return LAYER + "/query?f=json&returnGeometry=false&outFields=" + FIELDS
                + "&geometryType=esriGeometryPoint&inSR=4326"
                + "&spatialRel=esriSpatialRelIntersects"
                + "&units=esriSRUnit_StatuteMile"
                + "&distance=" + trim(Math.min(miles, MAX_MILES))
                + "&geometry=" + trim(lon) + "," + trim(lat)
                + "&where=" + encode(NETWORKS);
    }

    /** The named stations, by MesoWest id, wherever they are. */
    public static String byIdUrl(Collection<String> stids) {
        final StringBuilder in = new StringBuilder();
        for (String id : stids) {
            if (id == null || id.isEmpty())
                continue;
            if (in.length() > 0)
                in.append(',');
            in.append('\'').append(id.replace("'", "''")).append('\'');
        }
        return LAYER + "/query?f=json&returnGeometry=false&outFields=" + FIELDS
                + "&where=" + encode(NETWORKS + " AND STID IN (" + in + ")");
    }

    /** The MesoWest id inside a station id this class made, or null for any other. */
    public static String stidOf(String wxId) {
        return wxId != null && wxId.startsWith(ID_PREFIX)
                ? wxId.substring(ID_PREFIX.length()) : null;
    }

    /** What a crew calls the network, or empty for one not asked for. */
    static String network(int mnetId) {
        switch (mnetId) {
            case SCE:
                return "SCE";
            case SDGE:
                return "SDG&E";
            case PGE:
                return "PG&E";
            case HPWREN:
                return "HPWREN";
            default:
                return "";
        }
    }

    /**
     * The name as it is drawn: led by the network, so a pill says whose station it is.
     * SCE names its own ("SCE Yvette Ln"); SDG&E's are bare places ("Mussey Grade").
     */
    static String displayName(String name, String network) {
        final String n = name == null ? "" : name.trim();
        if (network.isEmpty() || n.toUpperCase(Locale.US)
                .startsWith(network.toUpperCase(Locale.US)))
            return n;
        return n.isEmpty() ? network : network + " " + n;
    }

    /**
     * Every station in an answer. A row without a position or a network asked for is
     * skipped; one without readings is kept, and the layer leaves it off the way it
     * leaves off a silent RAWS.
     */
    public static List<Raws.Station> parse(String body) {
        final List<Raws.Station> out = new ArrayList<>();
        if (body == null || body.isEmpty())
            return out;
        try {
            final JSONArray feats = new JSONObject(body).optJSONArray("features");
            if (feats == null)
                return out;
            for (int i = 0; i < feats.length(); i++) {
                final JSONObject f = feats.optJSONObject(i);
                final JSONObject a = f == null ? null : f.optJSONObject("attributes");
                if (a == null)
                    continue;
                final String stid = Raws.str(a, "STID").toUpperCase(Locale.US);
                final String network = network((int) a.optDouble("MNET_ID", -1));
                final double lat = a.optDouble("LATITUDE", Double.NaN);
                final double lon = a.optDouble("LONGITUDE", Double.NaN);
                if (stid.isEmpty() || network.isEmpty() || Double.isNaN(lat)
                        || Double.isNaN(lon) || (lat == 0 && lon == 0))
                    continue;
                long observed = a.optLong("WIND_SPEED_OBSERVED_DATETIME", 0L);
                if (observed <= 0)
                    observed = a.optLong("AIR_TEMP_OBSERVED_DATETIME", 0L);
                final double elev = Raws.number(a.optString("ELEV_DEM", ""));
                out.add(new Raws.Station(ID_PREFIX + stid, stid,
                        displayName(Raws.str(a, "NAME"), network), network, lat, lon,
                        Double.isNaN(elev) ? 0 : (int) Math.round(elev), observed,
                        number(a, "RELATIVE_HUMIDITY"), number(a, "AIR_TEMP"),
                        number(a, "WIND_SPEED"), number(a, "WIND_GUST"),
                        number(a, "WIND_DIRECTION")));
            }
        } catch (Exception e) {
            return out;
        }
        return out;
    }

    /** A number field, NaN when missing or null. */
    private static double number(JSONObject a, String key) {
        return a.isNull(key) ? Double.NaN : a.optDouble(key, Double.NaN);
    }

    private static String encode(String where) {
        try {
            return URLEncoder.encode(where, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String trim(double d) {
        return String.format(Locale.US, "%.5f", d).replaceAll("0+$", "").replaceAll("\\.$", "");
    }
}
