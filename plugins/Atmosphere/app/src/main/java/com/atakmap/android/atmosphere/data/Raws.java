
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
 * Fire weather stations and what they are reading right now.
 *
 * <p>The list is NIFC's {@code PublicView_RAWS}: 3,220 stations, no key, each record
 * carrying its own latest observation. One request answers "every station within N
 * miles of here, and what it says", which is the whole layer in a single round trip
 * (24 KB for the 83 around Murrieta, measured 2026-09-25).
 *
 * <p>It is preferred over api.weather.gov for the fire stations because it carries
 * <b>fuel moisture and fuel temperature</b>, which the NWS observation API does not
 * have at all and which several offices' Red Flag criteria require. api.weather.gov
 * covers the other networks -- airports, utility, citizen -- and joins to this one
 * exactly on {@link Station#mesowestId}, which 99% of NIFC's stations carry and which
 * is api.weather.gov's own {@code stationIdentifier}.
 *
 * <p><b>Every value arrives as a string with its unit written into it</b> -- "21 %",
 * "93 deg. F", "5 mph", "225 degrees", "7.3 (unk)" -- so each one is pulled out with
 * the leading number and nothing else is assumed about the text around it.
 *
 * <p>No Android types here, so the parsing and the joins can be tested.
 */
public final class Raws {

    public static final String HOST = "services3.arcgis.com";

    private static final String LAYER = "https://" + HOST
            + "/T4QMspbfLg3qTGWY/arcgis/rest/services/PublicView_RAWS/FeatureServer/1";

    /** Everything the layer draws or reads back; asked for by name, never "*". */
    private static final String FIELDS = "StationName,WXID,NWSID,MesoWestStationID,"
            + "ObservedDate,Elevation,State,County,Agency,Unit,Status,"
            + "RelativeHumidity,AirTempStandPlace,WindSpeedMPH,WindDirDegrees,"
            + "WindSpeedPeak,WindDirPeak,FuelMoisture,FuelTemp,SolarRadiation,"
            + "RainAccumulation,Latitude,Longitude";

    /**
     * Stations within {@code miles} of a point, with their latest observation.
     *
     * <p>The radius is done by the server in statute miles, which the service takes
     * natively -- nothing here converts a distance and gets it wrong.
     */
    public static String nearUrl(double lat, double lon, double miles) {
        return LAYER + "/query?f=json&returnGeometry=false&outFields=" + FIELDS
                + "&geometryType=esriGeometryPoint&inSR=4326"
                + "&spatialRel=esriSpatialRelIntersects"
                + "&units=esriSRUnit_StatuteMile"
                + "&distance=" + trim(miles)
                + "&geometry=" + trim(lon) + "," + trim(lat)
                + "&where=1%3D1";
    }

    /**
     * At most this many ids in one request. Past about 2,000 characters the REST
     * gateway answers 404, not 414. Encoded, every id costs seventeen characters --
     * the quotes and the comma are three each -- so a hundred was already over the
     * line with the field list; the unit test said so before the gateway could.
     */
    public static final int MAX_IDS_PER_QUERY = 60;

    /**
     * The named stations, wherever they are, with their latest observation.
     *
     * <p>The radius query cannot return a starred station two hundred miles away, so
     * those are asked for by id. WXID is a string field on the service; the ids are
     * quoted, and a quote inside one is doubled the way SQL wants it.
     */
    public static String byIdUrl(Collection<String> wxIds) {
        final StringBuilder in = new StringBuilder();
        for (String id : wxIds) {
            if (id == null || id.isEmpty())
                continue;
            if (in.length() > 0)
                in.append(',');
            in.append('\'').append(id.replace("'", "''")).append('\'');
        }
        final String where = "WXID IN (" + in + ")";
        try {
            return LAYER + "/query?f=json&returnGeometry=false&outFields=" + FIELDS
                    + "&where=" + URLEncoder.encode(where, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String trim(double d) {
        return String.format(Locale.US, "%.5f", d).replaceAll("0+$", "").replaceAll("\\.$", "");
    }

    /** One station and its latest reading. Any value may be missing. */
    public static final class Station {
        /** NIFC's own id for the station. */
        public final String wxId;
        /** The MesoWest id, which is also api.weather.gov's: the join to every other feed. */
        public final String mesowestId;
        public final String name;
        public final String state, county, agency, unit, status;
        public final double latitude, longitude;
        /** Feet. */
        public final int elevation;
        /** When the station reported, UTC millis, or 0. */
        public final long observedAt;

        /** Percent, or NaN. */
        public final double relativeHumidity;
        /** Degrees Fahrenheit, or NaN. */
        public final double airTempF, fuelTempF;
        /** Miles per hour, or NaN. */
        public final double windMph, gustMph;
        /** Degrees the wind is coming FROM, or NaN. */
        public final double windFromDeg, gustFromDeg;
        /** Ten-hour fuel moisture, percent, or NaN. */
        public final double fuelMoisture;
        /**
         * The utility that runs the station -- "SCE", "SDG&E", "PG&E", "HPWREN" -- or
         * empty for a RAWS. See {@link UtilityStations}.
         */
        public final String network;

        Station(JSONObject a) {
            wxId = str(a, "WXID");
            mesowestId = str(a, "MesoWestStationID").toUpperCase(Locale.US);
            name = str(a, "StationName");
            state = str(a, "State");
            county = str(a, "County");
            agency = str(a, "Agency");
            unit = str(a, "Unit");
            status = str(a, "Status");
            latitude = a.optDouble("Latitude", Double.NaN);
            longitude = a.optDouble("Longitude", Double.NaN);
            elevation = a.optInt("Elevation", 0);
            observedAt = a.optLong("ObservedDate", 0L);
            relativeHumidity = number(a, "RelativeHumidity");
            airTempF = number(a, "AirTempStandPlace");
            fuelTempF = number(a, "FuelTemp");
            windMph = number(a, "WindSpeedMPH");
            gustMph = number(a, "WindSpeedPeak");
            windFromDeg = number(a, "WindDirDegrees");
            gustFromDeg = number(a, "WindDirPeak");
            fuelMoisture = number(a, "FuelMoisture");
            network = "";
        }

        /** A utility network's station, which reads less than a RAWS does. */
        Station(String wxId, String mesowestId, String name, String network, double latitude,
                double longitude, int elevation, long observedAt, double relativeHumidity,
                double airTempF, double windMph, double gustMph, double windFromDeg) {
            this.wxId = wxId;
            this.mesowestId = mesowestId;
            this.name = name;
            this.network = network;
            this.state = "CA";
            this.county = "";
            this.agency = network;
            this.unit = "";
            this.status = "";
            this.latitude = latitude;
            this.longitude = longitude;
            this.elevation = elevation;
            this.observedAt = observedAt;
            this.relativeHumidity = relativeHumidity;
            this.airTempF = airTempF;
            this.fuelTempF = Double.NaN;
            this.windMph = windMph;
            this.gustMph = gustMph;
            this.windFromDeg = windFromDeg;
            this.gustFromDeg = Double.NaN;
            this.fuelMoisture = Double.NaN;
        }

        /** True for a utility network's station rather than a RAWS. */
        public boolean isUtility() {
            return !network.isEmpty();
        }

        /** True when the station sent nothing worth drawing: no wind, no humidity. */
        public boolean silent() {
            return Double.isNaN(relativeHumidity) && Double.isNaN(windMph)
                    && Double.isNaN(airTempF);
        }

        /** How old the reading is in hours, or {@code Double.MAX_VALUE} if never. */
        public double ageHours(long now) {
            if (observedAt <= 0)
                return Double.MAX_VALUE;
            return (now - observedAt) / 3_600_000.0;
        }

        /**
         * A reading old enough that drawing it as current would mislead. RAWS report
         * hourly; a portable left in the field reported 57 days old on 2026-09-25 and
         * would otherwise have drawn beside live stations.
         */
        public boolean stale(long now) {
            return ageHours(now) > 3.0;
        }

        /** The strongest wind the station has to offer, gust before sustained. */
        public double strongestMph() {
            if (!Double.isNaN(gustMph))
                return gustMph;
            return windMph;
        }
    }

    /**
     * Every station in a service answer. A row without a position is skipped; a row
     * without readings is kept, because a station that has gone quiet is worth
     * showing as quiet rather than leaving a hole in the map.
     */
    public static List<Station> parse(String body) {
        final List<Station> out = new ArrayList<>();
        if (body == null || body.isEmpty())
            return out;
        try {
            final JSONArray feats = new JSONObject(body).optJSONArray("features");
            if (feats == null)
                return out;
            for (int i = 0; i < feats.length(); i++) {
                final JSONObject f = feats.optJSONObject(i);
                if (f == null)
                    continue;
                final JSONObject a = f.optJSONObject("attributes");
                if (a == null)
                    continue;
                final Station s = new Station(a);
                if (Double.isNaN(s.latitude) || Double.isNaN(s.longitude))
                    continue;
                if (s.latitude == 0 && s.longitude == 0)
                    continue;
                out.add(s);
            }
        } catch (Exception e) {
            return out;
        }
        return out;
    }

    static String str(JSONObject a, String key) {
        final String s = a.optString(key, "");
        return s == null || s.equals("null") ? "" : s.trim();
    }

    /**
     * The leading number of a value like "21 %", "93 deg. F" or "7.3 (unk)".
     *
     * <p>Deliberately does not look at the unit text. The service writes it a
     * different way for nearly every field and a parser that insisted on matching it
     * would drop readings over punctuation.
     */
    private static double number(JSONObject a, String key) {
        return number(a.optString(key, ""));
    }

    public static double number(String raw) {
        if (raw == null)
            return Double.NaN;
        final String s = raw.trim();
        if (s.isEmpty() || s.equalsIgnoreCase("null"))
            return Double.NaN;
        int i = 0;
        final int n = s.length();
        if (i < n && (s.charAt(i) == '-' || s.charAt(i) == '+'))
            i++;
        final int start = i;
        boolean dot = false;
        while (i < n) {
            final char c = s.charAt(i);
            if (c >= '0' && c <= '9') {
                i++;
            } else if (c == '.' && !dot) {
                dot = true;
                i++;
            } else {
                break;
            }
        }
        if (i == start)
            return Double.NaN;
        try {
            return Double.parseDouble(s.substring(0, i));
        } catch (NumberFormatException e) {
            return Double.NaN;
        }
    }

    private Raws() {
    }
}
