package com.atakmap.android.atmosphere.data;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * River gauges and what they read right now, from the National Water Prediction
 * Service -- the API behind water.noaa.gov, open and keyless.
 *
 * <p>One request answers "every gauge in this box, its latest observation and its
 * forecast": 96 gauges and 97 KB for 100 miles around Murrieta, 296 and 301 KB for
 * 250 (measured 2026-09-26). Each carries a stage in feet, a flow in thousands of
 * cubic feet a second, and a flood category.
 *
 * <p>Four things the service does that a reader has to know (PLAN 10c):
 * <ol>
 *   <li>{@code srid=EPSG_4326} is required. Without it the answer is an empty list
 *       and HTTP 200: a region with no rivers, not an error.</li>
 *   <li>{@code -999} is the no-data sentinel on both values, and it is a number that
 *       would draw as one.</li>
 *   <li>{@code floodCategory} is mostly not a flood state. In California on
 *       2026-09-26: 477 of 987 gauges have no flood stages defined at all, 105 are
 *       not current, 59 are out of service, 345 read no flooding, one was at action
 *       stage. A legend of flood colors describes a third of them, and "not current"
 *       must never read as "no flooding".</li>
 *   <li>A time of {@code 0001-01-01} means none.</li>
 * </ol>
 *
 * <p>No Android types here, so the parsing is tested.
 */
public final class Nwps {

    public static final String HOST = "api.water.noaa.gov";
    private static final String GAUGES = "https://" + HOST + "/nwps/v1/gauges";

    /** The categories as the service spells them. */
    public static final String MAJOR = "major";
    public static final String MODERATE = "moderate";
    public static final String MINOR = "minor";
    public static final String ACTION = "action";
    public static final String NO_FLOODING = "no_flooding";
    public static final String NOT_DEFINED = "not_defined";
    public static final String OBS_NOT_CURRENT = "obs_not_current";
    public static final String FCST_NOT_CURRENT = "fcst_not_current";
    public static final String OUT_OF_SERVICE = "out_of_service";
    public static final String LOW_THRESHOLD = "low_threshold";

    /**
     * Every gauge within {@code miles} of a point, as the box the service takes.
     *
     * <p>A box, not a radius: the API has no distance query. The box is the radius
     * each way, so its corners reach 1.4 times as far -- a few more gauges, never
     * fewer.
     */
    public static String boxUrl(double lat, double lon, double miles) {
        final double dLat = miles / 69.0;
        final double cos = Math.max(0.1, Math.cos(Math.toRadians(lat)));
        final double dLon = miles / (69.0 * cos);
        return GAUGES + "?srid=EPSG_4326"
                + "&bbox.xmin=" + trim(lon - dLon) + "&bbox.ymin=" + trim(lat - dLat)
                + "&bbox.xmax=" + trim(lon + dLon) + "&bbox.ymax=" + trim(lat + dLat);
    }

    private static String trim(double d) {
        return String.format(Locale.US, "%.5f", d).replaceAll("0+$", "").replaceAll("\\.$", "");
    }

    /** One observation or one forecast: stage, flow, category and when. */
    public static final class Reading {
        /** Feet, or NaN. */
        public final double stage;
        /** Thousands of cubic feet per second, or NaN. */
        public final double flow;
        /** As the service spells it, e.g. {@code no_flooding}; empty when absent. */
        public final String category;
        /** UTC millis, or 0 when there is none. */
        public final long at;

        Reading(double stage, double flow, String category, long at) {
            this.stage = stage;
            this.flow = flow;
            this.category = category;
            this.at = at;
        }

        /** True when there is a number worth showing. */
        public boolean any() {
            return !Double.isNaN(stage) || !Double.isNaN(flow);
        }

        static final Reading NONE = new Reading(Double.NaN, Double.NaN, "", 0L);
    }

    /** One gauge, where it is, whose it is, and what it reads. */
    public static final class Gauge {
        /** The NWS location id, upper case, e.g. ALWC1. */
        public final String lid;
        public final String name;
        public final String wfo, rfc, state;
        public final double latitude, longitude;
        public final Reading observed, forecast;

        Gauge(String lid, String name, String wfo, String rfc, String state,
                double latitude, double longitude, Reading observed, Reading forecast) {
            this.lid = lid;
            this.name = name;
            this.wfo = wfo;
            this.rfc = rfc;
            this.state = state;
            this.latitude = latitude;
            this.longitude = longitude;
            this.observed = observed;
            this.forecast = forecast;
        }

        /** The category the symbol is colored by: the observation's. */
        public String category() {
            return observed.category;
        }
    }

    /** {@code /gauges/{lid}/stageflow}: 30 days observed and the forecast. */
    public static String stageflowUrl(String lid) {
        return GAUGES + "/" + lid + "/stageflow";
    }

    /** {@code /gauges/{lid}}: the record with the flood stages. */
    public static String gaugeUrl(String lid) {
        return GAUGES + "/" + lid;
    }

    /** One point on the hydrograph. */
    public static final class Point {
        public final long at;
        /** Feet, or NaN. */
        public final double stage;
        /** Thousands of cubic feet per second, or NaN. */
        public final double flow;

        Point(long at, double stage, double flow) {
            this.at = at;
            this.stage = stage;
            this.flow = flow;
        }
    }

    /** The flood stages a gauge defines, feet; NaN where it defines none. */
    public static final class Stages {
        public final double action, minor, moderate, major;

        public Stages(double action, double minor, double moderate, double major) {
            this.action = action;
            this.minor = minor;
            this.moderate = moderate;
            this.major = major;
        }

        public boolean any() {
            return !Double.isNaN(action) || !Double.isNaN(minor)
                    || !Double.isNaN(moderate) || !Double.isNaN(major);
        }

        public static final Stages NONE = new Stages(Double.NaN, Double.NaN, Double.NaN,
                Double.NaN);
    }

    /** The observed and forecast series, in time order. */
    public static final class Hydrograph {
        public final List<Point> observed, forecast;
        /** When the forecast was issued, UTC millis, or 0. */
        public final long forecastIssued;

        Hydrograph(List<Point> observed, List<Point> forecast, long forecastIssued) {
            this.observed = observed;
            this.forecast = forecast;
            this.forecastIssued = forecastIssued;
        }
    }

    /**
     * A stageflow answer. Primary and secondary are told apart by their units, as in
     * the gauge list; a point with neither is skipped.
     */
    public static Hydrograph parseStageflow(String body) {
        final List<Point> obs = new ArrayList<>(), fc = new ArrayList<>();
        long issued = 0L;
        if (body == null || body.isEmpty())
            return new Hydrograph(obs, fc, 0L);
        try {
            final JSONObject root = new JSONObject(body);
            series(root.optJSONObject("observed"), obs);
            final JSONObject f = root.optJSONObject("forecast");
            series(f, fc);
            if (f != null)
                issued = time(str(f, "issuedTime"));
        } catch (Exception e) {
            // whatever parsed stands
        }
        return new Hydrograph(obs, fc, issued);
    }

    private static void series(JSONObject s, List<Point> out) {
        if (s == null)
            return;
        final String pu = str(s, "primaryUnits").toLowerCase(Locale.US);
        final String su = str(s, "secondaryUnits").toLowerCase(Locale.US);
        final JSONArray data = s.optJSONArray("data");
        if (data == null)
            return;
        for (int i = 0; i < data.length(); i++) {
            final JSONObject p = data.optJSONObject(i);
            if (p == null)
                continue;
            final long at = time(str(p, "validTime"));
            if (at <= 0)
                continue;
            final double primary = value(p, "primary"), secondary = value(p, "secondary");
            double stage = Double.NaN, flow = Double.NaN;
            if (pu.equals("ft"))
                stage = primary;
            else if (pu.equals("kcfs"))
                flow = primary;
            if (su.equals("ft"))
                stage = secondary;
            else if (su.equals("kcfs"))
                flow = secondary;
            if (Double.isNaN(stage) && Double.isNaN(flow))
                continue;
            out.add(new Point(at, stage, flow));
        }
    }

    /** The flood stages out of a gauge record; {@code -9999} is none. */
    public static Stages parseStages(String body) {
        if (body == null || body.isEmpty())
            return Stages.NONE;
        try {
            final JSONObject cats = new JSONObject(body).optJSONObject("flood")
                    .optJSONObject("categories");
            if (cats == null)
                return Stages.NONE;
            return new Stages(stageOf(cats, ACTION), stageOf(cats, MINOR),
                    stageOf(cats, MODERATE), stageOf(cats, MAJOR));
        } catch (Exception e) {
            return Stages.NONE;
        }
    }

    private static double stageOf(JSONObject cats, String name) {
        final JSONObject c = cats.optJSONObject(name);
        return c == null ? Double.NaN : value(c, "stage");
    }

    /** Every gauge in a service answer. One without a position is skipped. */
    public static List<Gauge> parse(String body) {
        final List<Gauge> out = new ArrayList<>();
        if (body == null || body.isEmpty())
            return out;
        try {
            final JSONArray arr = new JSONObject(body).optJSONArray("gauges");
            if (arr == null)
                return out;
            for (int i = 0; i < arr.length(); i++) {
                final JSONObject g = arr.optJSONObject(i);
                if (g == null)
                    continue;
                final double lat = g.optDouble("latitude", Double.NaN);
                final double lon = g.optDouble("longitude", Double.NaN);
                if (Double.isNaN(lat) || Double.isNaN(lon) || (lat == 0 && lon == 0))
                    continue;
                final String lid = str(g, "lid").toUpperCase(Locale.US);
                if (lid.isEmpty())
                    continue;
                final JSONObject status = g.optJSONObject("status");
                out.add(new Gauge(lid, str(g, "name").replaceAll("\\s+", " ").trim(),
                        abbr(g, "wfo"), abbr(g, "rfc"), abbr(g, "state"), lat, lon,
                        reading(status == null ? null : status.optJSONObject("observed")),
                        reading(status == null ? null : status.optJSONObject("forecast"))));
            }
        } catch (Exception e) {
            return out;
        }
        return out;
    }

    /**
     * A reading, with the stage and the flow told apart by the unit the service
     * writes beside each -- most gauges report stage first, a few report flow first
     * ({@code QR} in the pedts), and the units say which.
     */
    private static Reading reading(JSONObject r) {
        if (r == null)
            return Reading.NONE;
        final double primary = value(r, "primary");
        final double secondary = value(r, "secondary");
        final String pu = str(r, "primaryUnit").toLowerCase(Locale.US);
        final String su = str(r, "secondaryUnit").toLowerCase(Locale.US);
        double stage = Double.NaN, flow = Double.NaN;
        if (pu.equals("ft"))
            stage = primary;
        else if (pu.equals("kcfs"))
            flow = primary;
        if (su.equals("ft"))
            stage = secondary;
        else if (su.equals("kcfs"))
            flow = secondary;
        return new Reading(stage, flow, str(r, "floodCategory"), time(str(r, "validTime")));
    }

    /** {@code -999} and {@code -9999} are the service's "none"; so is a missing value. */
    static double value(JSONObject o, String key) {
        final double v = o.optDouble(key, Double.NaN);
        return v <= -999 ? Double.NaN : v;
    }

    /** The service writes year 1 for "no time". */
    static long time(String iso) {
        if (iso == null || iso.startsWith("0001"))
            return 0L;
        return IsoTime.parse(iso);
    }

    private static String abbr(JSONObject g, String key) {
        final JSONObject o = g.optJSONObject(key);
        return o == null ? "" : str(o, "abbreviation");
    }

    private static String str(JSONObject a, String key) {
        final String s = a.optString(key, "");
        return s == null || s.equals("null") ? "" : s.trim();
    }

    /** What a category means, in the words water.noaa.gov uses. */
    public static String label(String category) {
        final String c = category == null ? "" : category;
        switch (c) {
            case MAJOR: return "Major flooding";
            case MODERATE: return "Moderate flooding";
            case MINOR: return "Minor flooding";
            case ACTION: return "Action stage (high water)";
            case NO_FLOODING: return "No flooding";
            case NOT_DEFINED: return "No flood stages defined";
            case OBS_NOT_CURRENT: return "Observation not current";
            case FCST_NOT_CURRENT: return "Forecast not current";
            case OUT_OF_SERVICE: return "Out of service";
            case LOW_THRESHOLD: return "Low water";
            default: return c.replace('_', ' ');
        }
    }

    /** Worse first: major 5, moderate 4, minor 3, action 2, no flooding 1, the rest 0. */
    public static int severity(String category) {
        final String c = category == null ? "" : category;
        switch (c) {
            case MAJOR: return 5;
            case MODERATE: return 4;
            case MINOR: return 3;
            case ACTION: return 2;
            case NO_FLOODING: return 1;
            default: return 0;
        }
    }

    /** A category that says something about the water, rather than about the gauge. */
    public static boolean isWaterState(String category) {
        return severity(category) > 0;
    }

    private Nwps() {
    }
}
