package com.atakmap.android.atmosphere.data;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

/**
 * Streams the National Weather Service's river model has running high: the
 * National Water Prediction Service's "high flow magnitude" reaches, each
 * stretch of stream whose modeled flow is at or above its high-water threshold,
 * graded by how rare a flow that big is (the annual exceedance probability the
 * service colors them by: a 2% flow is the 1-in-50-year flood). Either the
 * model's analysis (now, {@code ana_high_flow_magnitude}) or the peak of its
 * next five days ({@code mrf_nbm_5day_max_high_flow_magnitude}); the two
 * layers name their fields differently ({@code max_flow}/{@code recur_cat}
 * against {@code maxflow_5day_cfs}/{@code recur_cat_5day}, and only the
 * analysis has a valid time), so the parser reads whichever is there.
 * Confirmed live 2026-09-27. The service calls itself experimental.
 *
 * <p>No Android types; tested.
 */
public final class HighFlow {

    public static final String HOST = "maps.water.noaa.gov";
    private static final String SERVICES = "https://" + HOST + "/server/rest/services/nwm/";
    private static final String NOW = "ana_high_flow_magnitude";
    private static final String DAY5 = "mrf_nbm_5day_max_high_flow_magnitude";

    public static final int HORIZON_NOW = 0, HORIZON_5DAY = 1;
    /** The service's own page size; a fuller answer says so and the biggest streams come first. */
    public static final int MAX_RECORDS = 2000;

    /**
     * The service's grades, worst first, in its own colors (read from the layer's
     * renderer 2026-09-27). The words are for the legend and the record: a field
     * operator knows "1-in-50-year flood"; "2% AEP" is the hydrologist's phrase
     * for the same thing and rides along in the record.
     */
    public enum Category {
        YR50("2", 0xFFFF00E5, "1-in-50-year flow or worse", "a 2% chance in any year"),
        YR25("4", 0xFF9E00FF, "1-in-25-year flow", "a 4% chance in any year"),
        YR10("10", 0xFF1400FF, "1-in-10-year flow", "a 10% chance in any year"),
        YR5("20", 0xFF2EC6FF, "1-in-5-year flow", "a 20% chance in any year"),
        YR2("50", 0xFF39EFFF, "1-in-2-year flow", "a 50% chance in any year"),
        HIGH_WATER(">50", 0xFFB4FFF2, "Over the high-water mark", "under a 1-in-2-year flow"),
        UNKNOWN("Not Availa", 0xFFBDC2BB, "Running high, size not known", "");

        public final String code, words, odds;
        public final int color;

        Category(String code, int color, String words, String odds) {
            this.code = code;
            this.color = color;
            this.words = words;
            this.odds = odds;
        }

        public static Category of(String code) {
            if (code == null)
                return UNKNOWN;
            final String c = code.trim();
            for (Category k : values())
                if (k.code.equals(c))
                    return k;
            return UNKNOWN;
        }
    }

    /**
     * Every reach crossing the box, biggest streams first, generalized to about
     * {@code offsetDeg} so a wide view is not a megabyte of bends. All fields, so
     * one request shape serves both layers.
     */
    public static String url(int horizon, double west, double south, double east, double north,
            double offsetDeg) {
        return SERVICES + (horizon == HORIZON_5DAY ? DAY5 : NOW) + "/MapServer/0/query?geometry="
                + String.format(Locale.US, "%.3f,%.3f,%.3f,%.3f", west, south, east, north)
                + "&geometryType=esriGeometryEnvelope&inSR=4326&spatialRel=esriSpatialRelIntersects"
                + "&outFields=*&orderByFields=strm_order%20DESC&resultRecordCount=" + MAX_RECORDS
                + "&outSR=4326&geometryPrecision=5"
                + String.format(Locale.US, "&maxAllowableOffset=%.5f", Math.max(0.00001, offsetDeg))
                + "&f=geojson";
    }

    public static final class Reach {
        public final String id, name, state, referenceTime, validTime;
        public final int order;
        public final Category category;
        /** The modeled flow the grade is for: now, or the five-day peak. */
        public final double flowCfs, thresholdCfs, flow2, flow5, flow10, flow25, flow50;
        public final JSONObject geometry;

        Reach(JSONObject p, JSONObject geometry) {
            id = str(p, "feature_id");
            final String n = str(p, "name");
            name = n.isEmpty() || n.equalsIgnoreCase("Unnamed Stream") ? "" : n;
            state = str(p, "state");
            order = p.optInt("strm_order", 0);
            category = Category.of(p.has("recur_cat_5day") ? str(p, "recur_cat_5day") : str(p, "recur_cat"));
            flowCfs = p.has("maxflow_5day_cfs") ? p.optDouble("maxflow_5day_cfs", Double.NaN)
                    : p.optDouble("max_flow", Double.NaN);
            thresholdCfs = p.optDouble("high_water_threshold", Double.NaN);
            flow2 = p.optDouble("flow_2yr", Double.NaN);
            flow5 = p.optDouble("flow_5yr", Double.NaN);
            flow10 = p.optDouble("flow_10yr", Double.NaN);
            flow25 = p.optDouble("flow_25yr", Double.NaN);
            flow50 = p.optDouble("flow_50yr", Double.NaN);
            referenceTime = str(p, "reference_time");
            validTime = str(p, "valid_time");
            this.geometry = geometry;
        }

        /** "Rio Grande" or "Unnamed stream". */
        public String placeName() {
            return name.isEmpty() ? "Unnamed stream" : name;
        }

        /** The feature's name: what ATAK's Select Item row and the record heading say. */
        public String title() {
            return "Stream running high: " + placeName() + " (" + category.words.toLowerCase(Locale.US) + ")";
        }

        /** Along the line, for a stream big enough to have a name worth reading. */
        public String label() {
            return name.isEmpty() || order < 4 ? "" : name + ": " + category.words.toLowerCase(Locale.US);
        }

        /** The record, in reading order. */
        public String details(int horizon) {
            final StringBuilder d = new StringBuilder();
            line(d, "How high", category.words + (category.odds.isEmpty() ? "" : " (" + category.odds + ")"));
            line(d, horizon == HORIZON_5DAY ? "Peak flow, next 5 days" : "Flow now", cfs(flowCfs));
            line(d, "High-water mark", cfs(thresholdCfs));
            line(d, "1-in-2-year flow", cfs(flow2));
            line(d, "1-in-5-year flow", cfs(flow5));
            line(d, "1-in-10-year flow", cfs(flow10));
            line(d, "1-in-25-year flow", cfs(flow25));
            line(d, "1-in-50-year flow", cfs(flow50));
            if (order > 0)
                line(d, "Stream size", "order " + order + " (1 is the smallest headwater)");
            if (!validTime.isEmpty())
                line(d, "Valid", localTime(validTime));
            line(d, "Model run", localTime(referenceTime));
            line(d, "State", state);
            line(d, "Stream ID", id);
            line(d, "Source", "National Weather Service river model, experimental; a computed flow, not a gauge reading");
            return d.toString();
        }
    }

    public static final class Answer {
        public final List<Reach> reaches = new ArrayList<>();
        /** The service stopped at its page size; the biggest streams are what came. */
        public boolean truncated;
    }

    public static Answer parse(String body) {
        final Answer out = new Answer();
        if (body == null || body.isEmpty())
            return out;
        try {
            final JSONObject root = new JSONObject(body);
            out.truncated = root.optBoolean("exceededTransferLimit", false);
            final JSONObject props = root.optJSONObject("properties");
            if (props != null && props.optBoolean("exceededTransferLimit", false))
                out.truncated = true;
            final JSONArray fs = root.optJSONArray("features");
            if (fs == null)
                return out;
            for (int i = 0; i < fs.length(); i++) {
                final JSONObject f = fs.optJSONObject(i);
                if (f == null)
                    continue;
                final JSONObject p = f.optJSONObject("properties");
                final JSONObject g = f.optJSONObject("geometry");
                if (p == null || g == null)
                    continue;
                out.reaches.add(new Reach(p, g));
            }
        } catch (Exception e) {
            return out;
        }
        return out;
    }

    /** The worst grade among these, or null when there are none. */
    public static Category worst(List<Reach> reaches) {
        Category w = null;
        for (Reach r : reaches)
            if (r.category != Category.UNKNOWN && (w == null || r.category.ordinal() < w.ordinal()))
                w = r.category;
        return w;
    }

    /** "12,300 cfs", "350 cfs", "0.4 cfs"; empty when unknown. */
    public static String cfs(double v) {
        if (Double.isNaN(v))
            return "";
        if (v < 10)
            return String.format(Locale.US, "%.1f cfs", v);
        return String.format(Locale.US, "%,d cfs", Math.round(v));
    }

    /**
     * The service's "2026-09-27 05:00:00" (and "... UTC") in the phone's time zone,
     * "Sat Sep 26, 10:00 PM"; the text itself when it does not parse.
     */
    public static String localTime(String serviceTime) {
        if (serviceTime == null || serviceTime.isEmpty())
            return "";
        final String t = serviceTime.replace(" UTC", "").trim();
        try {
            final SimpleDateFormat in = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US);
            in.setTimeZone(TimeZone.getTimeZone("UTC"));
            final Date d = in.parse(t);
            return new SimpleDateFormat("EEE MMM d, h:mm a", Locale.US).format(d);
        } catch (Exception e) {
            return serviceTime;
        }
    }

    private static void line(StringBuilder d, String label, String value) {
        if (value == null || value.isEmpty())
            return;
        if (d.length() > 0)
            d.append('\n');
        d.append(label).append(": ").append(value);
    }

    private static String str(JSONObject p, String key) {
        final String s = p.optString(key, "");
        return s == null || s.equals("null") ? "" : s.trim();
    }

    private HighFlow() {
    }
}
