package com.atakmap.android.atmosphere.data;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

/**
 * Energy Release Component and Burning Index for every Predictive Service Area in the
 * lower 48: what each GACC charts on its fuels and fire danger page, from one national
 * service.
 *
 * <p>The Forest Service's Risk Management Assistance team rebuilds "PSA ERC and BI
 * Percentiles and Trends" from FEMS every day at 03:00 Mountain: for each of the 213
 * PSAs the mean of its key RAWS, yesterday's observed value with its percentile and
 * trend, and today's forecast. No key; the whole country with simplified shapes is
 * 120 KB compressed (2026-10-05). Its values match the GACC charts to the hundredth
 * (NR01 20.88, SW05 18.98, SC10 43.82).
 *
 * <p><b>The percentile is year-round</b>, 2005-2022, fuel model Y. Some GACCs chart a
 * fire season only (Northern Rockies: June to October), where the same ERC sits at a
 * lower percentile, so the two are never shown as one number. Alaska is not here: it
 * rates fire danger on the Canadian system.
 *
 * <p>No Android types here, so the request, the parsing and the classes can be tested.
 */
public final class Erc {

    public static final String HOST = "services3.arcgis.com";

    private static final String LAYER = "https://" + HOST
            + "/T4QMspbfLg3qTGWY/arcgis/rest/services/"
            + "PSA_ERC_and_BI_Percentiles_and_Trends/FeatureServer/0";

    private static final String FIELDS = "PSANationalCode,PSANAME,GACCUnitID,GACCName,"
            + "avg_erc,avg_erc_percentile,avg_erc_trend,"
            + "avg_erc_fcast,avg_erc_fcast_percentile,avg_erc_fcast_trend,"
            + "avg_bi,avg_bi_percentile,avg_bi_trend,"
            + "avg_bi_fcast,avg_bi_fcast_percentile,avg_bi_fcast_trend,"
            + "update_date,update_time";

    /**
     * The agency's own classes, from the RMA web map: the lower edge of each, in
     * percent, and its color. Below 60 is green; the top class is a record.
     */
    public static final double[] EDGES = { 0, 60, 80, 90, 97, 99.5 };
    public static final int[] COLORS = { 0xFF38A800, 0xFFD1FF73, 0xFFFFFFBE, 0xFFFFAA00,
            0xFFFF0000, 0xFFA80000 };
    public static final String[] CLASS_NAMES = { "Below the 60th percentile",
            "60th to 80th percentile", "80th to 90th percentile", "90th to 97th percentile",
            "97th to 99.5th percentile", "Above the 99.5th percentile" };

    private Erc() {
    }

    /** Every PSA with its shape, simplified to about a kilometer, as GeoJSON. */
    public static String allUrl() {
        return LAYER + "/query?where=1%3D1&outFields=" + FIELDS
                + "&returnGeometry=true&outSR=4326&maxAllowableOffset=0.01"
                + "&geometryPrecision=3&f=geojson";
    }

    /** The PSA a point is in, without its shape. */
    public static String atUrl(double lat, double lon) {
        return LAYER + "/query?f=json&returnGeometry=false&outFields=" + FIELDS
                + "&geometryType=esriGeometryPoint&inSR=4326"
                + "&spatialRel=esriSpatialRelIntersects"
                + "&geometry=" + String.format(Locale.US, "%.3f,%.3f", lon, lat);
    }

    /** One reading: the value, its year-round percentile, and which way it is going. */
    public static final class Reading {
        public final double value, percentile;
        /** "Increase", "Decrease", "No Change", or empty. */
        public final String trend;

        Reading(double value, double percentile, String trend) {
            this.value = value;
            this.percentile = percentile;
            this.trend = trend == null ? "" : trend.trim();
        }

        /** False when the PSA had no station reporting: the service then writes null, or 0. */
        public boolean known() {
            return !Double.isNaN(value) && value > 0 && !Double.isNaN(percentile);
        }

        /** "rising", "falling", "steady", or empty. */
        public String trendWord() {
            if (trend.equalsIgnoreCase("Increase"))
                return "rising";
            if (trend.equalsIgnoreCase("Decrease"))
                return "falling";
            if (trend.equalsIgnoreCase("No Change"))
                return "steady";
            return "";
        }
    }

    /** One Predictive Service Area and its fire danger. */
    public static final class Psa {
        /** "SC08". */
        public final String code;
        /** "South Coast". */
        public final String name;
        /** "USCAOSCC", the GACC's unit id. */
        public final String gacc;
        /** "Southern California Geographic Area Coordination Center". */
        public final String gaccName;
        public final Reading ercObserved, ercForecast, biObserved, biForecast;
        /** The day the service ran, "2026-10-05"; observed is the day before. */
        public final String updated;
        /** "0300", Mountain time. */
        public final String updatedTime;
        /** The GeoJSON geometry, or null from a point query. */
        public final JSONObject geometry;

        Psa(JSONObject p, JSONObject geometry) {
            code = str(p, "PSANationalCode");
            name = str(p, "PSANAME");
            gacc = str(p, "GACCUnitID");
            gaccName = str(p, "GACCName");
            ercObserved = reading(p, "avg_erc");
            ercForecast = reading(p, "avg_erc_fcast");
            biObserved = reading(p, "avg_bi");
            biForecast = reading(p, "avg_bi_fcast");
            updated = str(p, "update_date");
            updatedTime = str(p, "update_time");
            this.geometry = geometry;
        }

        /** The GACC's own ERC chart for this PSA, or null where it makes none. */
        public String chartUrl() {
            return Erc.chartUrl(code, gacc);
        }
    }

    /** Every PSA in a GeoJSON answer; a feature without a code is skipped. */
    public static List<Psa> parseAll(String body) {
        final List<Psa> out = new ArrayList<>();
        try {
            final JSONArray fs = new JSONObject(body).optJSONArray("features");
            if (fs == null)
                return out;
            for (int i = 0; i < fs.length(); i++) {
                final JSONObject f = fs.optJSONObject(i);
                final JSONObject p = f == null ? null : f.optJSONObject("properties");
                if (p == null || str(p, "PSANationalCode").isEmpty())
                    continue;
                out.add(new Psa(p, f.optJSONObject("geometry")));
            }
        } catch (Exception e) {
            return out;
        }
        return out;
    }

    /** The PSA in a point query's answer, or null when the point is in none. */
    public static Psa parseAt(String body) {
        try {
            final JSONArray fs = new JSONObject(body).optJSONArray("features");
            if (fs == null || fs.length() == 0)
                return null;
            final JSONObject a = fs.getJSONObject(0).optJSONObject("attributes");
            return a == null || str(a, "PSANationalCode").isEmpty() ? null : new Psa(a, null);
        } catch (Exception e) {
            return null;
        }
    }

    /** The class a percentile falls in, 0 to 5. */
    public static int classOf(double percentile) {
        int c = 0;
        for (int i = 1; i < EDGES.length; i++)
            if (percentile >= EDGES[i])
                c = i;
        return c;
    }

    public static int color(double percentile) {
        return COLORS[classOf(percentile)];
    }

    /** "92nd". */
    public static String ordinal(double percentile) {
        final long n = Math.round(Math.floor(percentile));
        final long tens = n % 100;
        final String suffix = tens >= 11 && tens <= 13 ? "th"
                : n % 10 == 1 ? "st" : n % 10 == 2 ? "nd" : n % 10 == 3 ? "rd" : "th";
        return n + suffix;
    }

    /** "Oct 4": the day the observed value is for, the day before the service ran. */
    public static String observedDay(String updated) {
        return day(updated, -1);
    }

    /** "Oct 5": the day the forecast is for. */
    public static String forecastDay(String updated) {
        return day(updated, 0);
    }

    private static String day(String isoDate, int offset) {
        try {
            final String[] p = isoDate.split("-");
            final Calendar c = Calendar.getInstance(TimeZone.getTimeZone("UTC"), Locale.US);
            c.clear();
            c.set(Integer.parseInt(p[0]), Integer.parseInt(p[1]) - 1, Integer.parseInt(p[2]));
            c.add(Calendar.DAY_OF_MONTH, offset);
            final java.text.SimpleDateFormat f = new java.text.SimpleDateFormat("MMM d",
                    Locale.US);
            f.setTimeZone(TimeZone.getTimeZone("UTC"));
            return f.format(c.getTime());
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * The GACC's own ERC chart for a PSA, or null where there is none: the Southern
     * and Eastern Areas stopped making them, and the service's own chart links are a
     * stale copy (every Great Basin one 404s). Built from NICC's index of chart files,
     * per GACC, all 133 answering on 2026-10-05.
     */
    public static String chartUrl(String code, String gacc) {
        // A code is letters and digits ("SC08", "SW06N"); anything else is not put in a path.
        if (code == null || !code.matches("[A-Z0-9]{2,8}") || gacc == null)
            return null;
        switch (gacc) {
            case "USMTNRC":         // NR01 -> ERC-1.jpeg
                return "https://gacc.nifc.gov/nrcc/predictive/fuels_fire-danger/graphs/"
                        + "GraphPlots/ERC-" + code.replaceFirst("^NR0?", "") + ".jpeg";
            case "USORNWC":
                return "https://gacc.nifc.gov/nwcc/content/products/fwx/matrices/"
                        + code + "_ERC.jpg";
            case "USCAONCC":
            case "USCAOSCC":
                return "https://gacc.nifc.gov/oncc/predictive/weather/"
                        + "FuelsCharts_Images_FEMS/current/" + code + "_ERC.png";
            case "USUTGBC":
                return "https://gacc.nifc.gov/gbcc/predictive/new_PSA_ERCmap/ercY/"
                        + "charts_automated/" + code + "_ERC.png";
            case "USCORMC":
                return "https://gacc.nifc.gov/rmcc/images/predictive/nfdrs/" + code
                        + "_ERC.png";
            case "USNMSWC":         // SW06N -> PSA_6N.png
                return "https://gacc.nifc.gov/swcc/predictive/fuels_fire-danger/nfdrs_charts/"
                        + "SW_Charts/images/PSA_" + code.replaceFirst("^SW0?", "") + ".png";
            default:
                return null;
        }
    }

    private static Reading reading(JSONObject p, String key) {
        return new Reading(number(p, key), number(p, key + "_percentile"),
                str(p, key + "_trend"));
    }

    private static double number(JSONObject p, String key) {
        return p.isNull(key) ? Double.NaN : p.optDouble(key, Double.NaN);
    }

    private static String str(JSONObject p, String key) {
        if (p.isNull(key))
            return "";
        final String s = p.optString(key, "");
        return s == null ? "" : s.trim();
    }
}
