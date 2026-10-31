package com.atakmap.android.firecast.model;

import com.atakmap.android.firecast.Esri;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Locale;

/**
 * The nearest RAWS reading: wind at 20 ft, humidity, air temperature and the 10-hour
 * fuel stick, from NIFC's public RAWS layer, the same service Atmosphere reads
 * (copied forward from its {@code Raws}; no shared module between plugins).
 *
 * <p>Every value arrives as a string with its unit written into it, "5 mph",
 * "225 degrees", "7.3 (unk)", so each is read as the leading number and nothing else.
 */
public final class Weather {

    private static final String LAYER = "https://services3.arcgis.com"
            + "/T4QMspbfLg3qTGWY/arcgis/rest/services/PublicView_RAWS/FeatureServer/1";
    private static final String FIELDS = "StationName,WXID,ObservedDate,Elevation,"
            + "RelativeHumidity,AirTempStandPlace,WindSpeedMPH,WindDirDegrees,"
            + "WindSpeedPeak,FuelMoisture,Latitude,Longitude";

    public static final class Reading {
        public final String station;
        public final String wxId;
        /** "RAWS", "NWS" (an observation station) or "NWS forecast" (the grid at the point). */
        public final String source;
        /** True when the wind is a 10 m reading (NWS networks), false for a RAWS 20 ft wind. */
        public final boolean tenMeterWind;
        /** Feet above sea level, as the layer carries it. */
        public final int elevationFt;
        public final double latitude, longitude;
        public final long observedAt;
        public final double windMph, gustMph, windFromDeg;
        public final double relativeHumidity, airTempF, fuelMoisture;
        /** Meters from the point asked about. */
        public double distanceM;

        Reading(JSONObject a) {
            source = "RAWS";
            tenMeterWind = false;
            station = str(a, "StationName");
            wxId = str(a, "WXID");
            elevationFt = a.optInt("Elevation", 0);
            latitude = a.optDouble("Latitude", Double.NaN);
            longitude = a.optDouble("Longitude", Double.NaN);
            observedAt = a.optLong("ObservedDate", 0L);
            windMph = number(a.optString("WindSpeedMPH", ""));
            gustMph = number(a.optString("WindSpeedPeak", ""));
            windFromDeg = number(a.optString("WindDirDegrees", ""));
            relativeHumidity = number(a.optString("RelativeHumidity", ""));
            airTempF = number(a.optString("AirTempStandPlace", ""));
            fuelMoisture = number(a.optString("FuelMoisture", ""));
        }

        /** An NWS observation station with its latest reading, or the forecast grid at the point. */
        Reading(String source, String id, String name, double lat, double lon, int elevationFt, long observedAt,
                double windMph, double gustMph, double windFromDeg, double rh, double tempF) {
            this(source, id, name, lat, lon, elevationFt, observedAt, windMph, gustMph, windFromDeg, rh, tempF, true);
        }

        /** The operator's own observation: at the fire, never 10 m. */
        static Reading observation(Observation ob, double lat, double lon, int elevationFt) {
            return new Reading("Observation", "OBS", ob.source, lat, lon, elevationFt, ob.at,
                    ob.windMph, ob.gustMph, ob.windFromDeg, ob.rh, ob.tempF, false);
        }

        Reading(String source, String id, String name, double lat, double lon, int elevationFt, long observedAt,
                double windMph, double gustMph, double windFromDeg, double rh, double tempF, boolean tenMeter) {
            this.source = source;
            tenMeterWind = tenMeter;
            station = name;
            wxId = id;
            latitude = lat;
            longitude = lon;
            this.elevationFt = elevationFt;
            this.observedAt = observedAt;
            this.windMph = windMph;
            this.gustMph = gustMph;
            this.windFromDeg = windFromDeg;
            relativeHumidity = rh;
            airTempF = tempF;
            fuelMoisture = Double.NaN;
        }

        public boolean hasWind() {
            return !Double.isNaN(windMph) && !Double.isNaN(windFromDeg);
        }
    }

    /**
     * The NWS observation stations near a point with their latest reading: airports,
     * the utility networks (Edison, SDG&amp;E, PG&amp;E), citizen stations, and the RAWS
     * again under their NWS ids. These sit low, along the lines and in the valleys,
     * where the RAWS do not. One request for the list, then one per station for the
     * nearest {@code max} by distance and height. Worker thread.
     */
    public static java.util.List<Reading> nwsNear(double lat, double lon, double pointElevFt, int max) throws Exception {
        final String listUrl = String.format(Locale.US, "https://api.weather.gov/points/%.4f,%.4f/stations", lat, lon);
        final JSONArray feats = new JSONObject(Esri.get(listUrl)).optJSONArray("features");
        final java.util.List<Object[]> near = new java.util.ArrayList<>();
        for (int i = 0; feats != null && i < feats.length(); i++) {
            final JSONObject f = feats.getJSONObject(i);
            final JSONObject p = f.optJSONObject("properties");
            final JSONObject g = f.optJSONObject("geometry");
            if (p == null || g == null)
                continue;
            final JSONArray c = g.optJSONArray("coordinates");
            if (c == null || c.length() < 2)
                continue;
            final double slon = c.getDouble(0), slat = c.getDouble(1);
            final String id = p.optString("stationIdentifier", "");
            // The id goes into a URL path below; only a plain station id is allowed to.
            if (id.isEmpty() || !id.matches("[A-Za-z0-9_-]{1,16}"))
                continue;
            final JSONObject e = p.optJSONObject("elevation");
            final double elevM = e == null ? Double.NaN : e.optDouble("value", Double.NaN);
            final int elevFt = Double.isNaN(elevM) ? 0 : (int) Math.round(elevM / 0.3048d);
            final double distM = com.atakmap.coremap.maps.coords.GeoCalculations.distanceTo(
                    new com.atakmap.coremap.maps.coords.GeoPoint(lat, lon),
                    new com.atakmap.coremap.maps.coords.GeoPoint(slat, slon));
            final double score = distM / 1609.344d
                    + (Double.isNaN(pointElevFt) || elevFt <= 0 ? 0 : Math.abs(elevFt - pointElevFt) / 100d);
            near.add(new Object[] { score, id, p.optString("name", id), slat, slon, elevFt, distM });
        }
        java.util.Collections.sort(near, new java.util.Comparator<Object[]>() {
            @Override
            public int compare(Object[] a, Object[] b) {
                return Double.compare((Double) a[0], (Double) b[0]);
            }
        });
        final java.util.List<Reading> out = new java.util.ArrayList<>();
        for (int i = 0; i < near.size() && out.size() < max; i++) {
            final Object[] s = near.get(i);
            try {
                final JSONObject o = new JSONObject(Esri.get("https://api.weather.gov/stations/"
                        + Esri.enc((String) s[1]) + "/observations/latest")).optJSONObject("properties");
                if (o == null)
                    continue;
                final double windKmh = quantity(o, "windSpeed"), gustKmh = quantity(o, "windGust");
                final double dir = quantity(o, "windDirection"), rh = quantity(o, "relativeHumidity");
                final double tempC = quantity(o, "temperature");
                if (Double.isNaN(windKmh) || Double.isNaN(dir))
                    continue;
                long at = 0;
                try {
                    at = com.atakmap.coremap.maps.time.CoordinatedTime.fromCot(o.optString("timestamp", "")).getMilliseconds();
                } catch (Exception ignored) {
                }
                final Reading r = new Reading("NWS", (String) s[1], (String) s[2], (Double) s[3], (Double) s[4], (Integer) s[5], at,
                        windKmh / 1.609344d, Double.isNaN(gustKmh) ? Double.NaN : gustKmh / 1.609344d, dir, rh,
                        Double.isNaN(tempC) ? Double.NaN : tempC * 9d / 5d + 32d);
                r.distanceM = (Double) s[6];
                out.add(r);
            } catch (Exception e) {
                // One station down is not a reason to lose the rest.
            }
        }
        return out;
    }

    /**
     * The NWS forecast grid's values at the point for a moment: 10 m wind, gust,
     * humidity and temperature from the 2.5 km grid the local office edits. Two
     * requests: the point, then the grid it names. A 20 ft wind sits in the grid
     * for some offices and not others (LOX has none, 2026-10-12), so the 10 m wind
     * is used, which the engine scales. Worker thread; null when the grid has no wind.
     */
    public static Reading forecastAt(double lat, double lon, long whenMillis) throws Exception {
        return forecastAt(lat, lon, new long[] { whenMillis })[0];
    }

    /** The forecast grid at a point at several moments, from one fetch; an entry is null where the grid has no wind. */
    public static Reading[] forecastAt(double lat, double lon, long[] when) throws Exception {
        final Reading[] out = new Reading[when.length];
        final JSONObject pt = new JSONObject(Esri.get(String.format(Locale.US,
                "https://api.weather.gov/points/%.4f,%.4f", lat, lon))).optJSONObject("properties");
        if (pt == null)
            return out;
        final String gridUrl = pt.optString("forecastGridData", "");
        if (!gridUrl.startsWith("https://api.weather.gov/gridpoints/"))
            return out;
        final JSONObject g = new JSONObject(Esri.get(gridUrl)).optJSONObject("properties");
        if (g == null)
            return out;
        for (int i = 0; i < when.length; i++)
            out[i] = gridAt(g, lat, lon, when[i]);
        return out;
    }

    private static Reading gridAt(JSONObject g, double lat, double lon, long whenMillis) {
        final double windKmh = seriesAt(g, "windSpeed", whenMillis), dir = seriesAt(g, "windDirection", whenMillis);
        final double gustKmh = seriesAt(g, "windGust", whenMillis), rh = seriesAt(g, "relativeHumidity", whenMillis);
        final double tempC = seriesAt(g, "temperature", whenMillis);
        if (Double.isNaN(windKmh) || Double.isNaN(dir))
            return null;
        final JSONObject el = g.optJSONObject("elevation");
        final double elevM = el == null ? Double.NaN : el.optDouble("value", Double.NaN);
        final Reading r = new Reading("NWS forecast", "FORECAST", "NWS forecast for this spot", lat, lon,
                Double.isNaN(elevM) ? 0 : (int) Math.round(elevM / 0.3048d), whenMillis,
                windKmh / 1.609344d, Double.isNaN(gustKmh) ? Double.NaN : gustKmh / 1.609344d, dir, rh,
                Double.isNaN(tempC) ? Double.NaN : tempC * 9d / 5d + 32d);
        r.distanceM = 0;
        return r;
    }

    /** The value of a grid series that covers a moment, else the last one that started before it; NaN for none. */
    static double seriesAt(JSONObject g, String key, long when) {
        final JSONObject s = g.optJSONObject(key);
        final JSONArray vals = s == null ? null : s.optJSONArray("values");
        if (vals == null)
            return Double.NaN;
        double best = Double.NaN;
        long bestStart = Long.MIN_VALUE;
        for (int i = 0; i < vals.length(); i++) {
            final JSONObject v = vals.optJSONObject(i);
            if (v == null || v.isNull("value"))
                continue;
            final String vt = v.optString("validTime", "");
            final int slash = vt.indexOf('/');
            if (slash < 0)
                continue;
            final long start, end;
            try {
                start = com.atakmap.coremap.maps.time.CoordinatedTime.fromCot(vt.substring(0, slash)).getMilliseconds();
                end = start + isoDurationMillis(vt.substring(slash + 1));
            } catch (Exception e) {
                continue;
            }
            if (start <= when && when < end)
                return v.optDouble("value", Double.NaN);
            if (start <= when && start > bestStart) {
                bestStart = start;
                best = v.optDouble("value", Double.NaN);
            }
        }
        return best;
    }

    /** "PT5H", "PT30M", "P1DT6H" to milliseconds. */
    static long isoDurationMillis(String d) {
        long ms = 0;
        final java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("P(?:(\\d+)D)?(?:T(?:(\\d+)H)?(?:(\\d+)M)?(?:(\\d+)S)?)?").matcher(d.trim());
        if (!m.matches())
            return 3600000L;
        if (m.group(1) != null)
            ms += Long.parseLong(m.group(1)) * 86400000L;
        if (m.group(2) != null)
            ms += Long.parseLong(m.group(2)) * 3600000L;
        if (m.group(3) != null)
            ms += Long.parseLong(m.group(3)) * 60000L;
        if (m.group(4) != null)
            ms += Long.parseLong(m.group(4)) * 1000L;
        return ms == 0 ? 3600000L : ms;
    }

    /** An NWS {unitCode, value} quantity's value, NaN when null or missing. */
    private static double quantity(JSONObject o, String key) {
        final JSONObject q = o.optJSONObject(key);
        if (q == null || q.isNull("value"))
            return Double.NaN;
        return q.optDouble("value", Double.NaN);
    }

    /**
     * Fine dead fuel moisture from humidity and temperature, percent: the NFDRS
     * equilibrium moisture content (Simard 1968), for a station with no fuel stick.
     */
    public static double fineFuelMoisture(double rh, double tempF) {
        if (Double.isNaN(rh) || Double.isNaN(tempF))
            return Double.NaN;
        final double h = Math.max(0, Math.min(100, rh)), t = tempF;
        final double emc;
        if (h < 10)
            emc = 0.03229 + 0.281073 * h - 0.000578 * h * t;
        else if (h < 50)
            emc = 2.22749 + 0.160107 * h - 0.01478 * t;
        else
            emc = 21.0606 + 0.005565 * h * h - 0.00035 * h * t - 0.483199 * h;
        return Math.max(1, Math.min(35, emc));
    }

    private Weather() {
    }

    /**
     * Every station within the radius with a wind reading, closest first. Worker
     * thread. Throws when the service does not answer.
     */
    public static java.util.List<Reading> within(double lat, double lon, double miles) throws Exception {
        final java.util.List<Reading> out = new java.util.ArrayList<>();
        final JSONArray feats = new JSONObject(Esri.get(queryUrl(lat, lon, miles))).optJSONArray("features");
        for (int i = 0; feats != null && i < feats.length(); i++) {
            final JSONObject a = feats.getJSONObject(i).optJSONObject("attributes");
            if (a == null)
                continue;
            final Reading r = new Reading(a);
            if (Double.isNaN(r.latitude) || Double.isNaN(r.longitude) || !r.hasWind())
                continue;
            r.distanceM = com.atakmap.coremap.maps.coords.GeoCalculations.distanceTo(
                    new com.atakmap.coremap.maps.coords.GeoPoint(lat, lon),
                    new com.atakmap.coremap.maps.coords.GeoPoint(r.latitude, r.longitude));
            out.add(r);
        }
        java.util.Collections.sort(out, new java.util.Comparator<Reading>() {
            @Override
            public int compare(Reading a, Reading b) {
                return Double.compare(a.distanceM, b.distanceM);
            }
        });
        return out;
    }

    /**
     * The station that best stands for a point: close, and at about its height. A
     * ridge-top station in the marine layer read 100% humidity while the valley 2,000
     * ft below it burned in wind (operator, 2026-10-11), so every 100 ft of height
     * difference counts like a mile of distance. Null when the list is empty.
     */
    public static Reading representative(java.util.List<Reading> list, double pointElevFt) {
        Reading best = null;
        double bestScore = Double.MAX_VALUE;
        for (Reading r : list) {
            final double score = r.distanceM / 1609.344d
                    + (Double.isNaN(pointElevFt) || r.elevationFt <= 0 ? 0 : Math.abs(r.elevationFt - pointElevFt) / 100d);
            if (score < bestScore) {
                bestScore = score;
                best = r;
            }
        }
        return best;
    }

    private static String queryUrl(double lat, double lon, double miles) {
        return LAYER + "/query?f=json&returnGeometry=false&outFields=" + FIELDS
                + "&geometryType=esriGeometryPoint&inSR=4326&spatialRel=esriSpatialRelIntersects"
                + "&units=esriSRUnit_StatuteMile&distance=" + String.format(Locale.US, "%.1f", miles)
                + "&geometry=" + String.format(Locale.US, "%.5f,%.5f", lon, lat) + "&where=1%3D1";
    }

    /** The nearest station within the radius that has a wind reading, or null. */
    public static Reading nearest(double lat, double lon, double miles) throws Exception {
        final String url = LAYER + "/query?f=json&returnGeometry=false&outFields=" + FIELDS
                + "&geometryType=esriGeometryPoint&inSR=4326&spatialRel=esriSpatialRelIntersects"
                + "&units=esriSRUnit_StatuteMile&distance=" + String.format(Locale.US, "%.1f", miles)
                + "&geometry=" + String.format(Locale.US, "%.5f,%.5f", lon, lat) + "&where=1%3D1";
        final JSONArray feats = new JSONObject(Esri.get(url)).optJSONArray("features");
        Reading best = null;
        for (int i = 0; feats != null && i < feats.length(); i++) {
            final JSONObject a = feats.getJSONObject(i).optJSONObject("attributes");
            if (a == null)
                continue;
            final Reading r = new Reading(a);
            if (Double.isNaN(r.latitude) || Double.isNaN(r.longitude) || !r.hasWind())
                continue;
            r.distanceM = com.atakmap.coremap.maps.coords.GeoCalculations.distanceTo(
                    new com.atakmap.coremap.maps.coords.GeoPoint(lat, lon),
                    new com.atakmap.coremap.maps.coords.GeoPoint(r.latitude, r.longitude));
            if (best == null || r.distanceM < best.distanceM)
                best = r;
        }
        return best;
    }

    static String str(JSONObject a, String key) {
        final String s = a.optString(key, "");
        return s == null || s.equals("null") ? "" : s.trim();
    }

    /** The leading number of "21 %", "93 deg. F" or "7.3 (unk)"; NaN when there is none. */
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
            if (c >= '0' && c <= '9')
                i++;
            else if (c == '.' && !dot) {
                dot = true;
                i++;
            } else
                break;
        }
        if (i == start)
            return Double.NaN;
        try {
            return Double.parseDouble(s.substring(0, i));
        } catch (NumberFormatException e) {
            return Double.NaN;
        }
    }
}
