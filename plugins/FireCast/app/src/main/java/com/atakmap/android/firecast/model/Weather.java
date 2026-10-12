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
        /** Feet above sea level, as the layer carries it. */
        public final int elevationFt;
        public final double latitude, longitude;
        public final long observedAt;
        public final double windMph, gustMph, windFromDeg;
        public final double relativeHumidity, airTempF, fuelMoisture;
        /** Meters from the point asked about. */
        public double distanceM;

        Reading(JSONObject a) {
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

        public boolean hasWind() {
            return !Double.isNaN(windMph) && !Double.isNaN(windFromDeg);
        }
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
