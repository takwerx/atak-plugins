package com.atakmap.android.sitrep.section;

import com.atakmap.android.sitrep.Area;
import com.atakmap.android.sitrep.Units;
import com.atakmap.android.sitrep.net.Http;
import com.atakmap.coremap.conversions.Span;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

/**
 * The basic forecast for the area's center, the next 24 hours, from the NWS grid
 * (PLAN 4.12, decision 14: the short answer here, the detail in Atmosphere). The wind
 * is the 20-ft wind, which only the grid carries.
 */
public final class Weather extends Section {

    static final String POINTS = "https://api.weather.gov/points/";

    public Weather() {
        super("weather", "Weather and daylight", true,
                "National Weather Service forecast grid and forecast text for the center of the area; "
                        + "sun and moon worked out on this device (NOAA sunrise equation).");
        seeIn = "See stations, fire weather and radar in Atmosphere.";
        openName = "Atmosphere";
        openPackage = "com.atakmap.android.atmosphere.plugin";
        openAction = "com.atakmap.android.atmosphere.SHOW";
    }

    /**
     * The forecast, then the sun and the moon (operator, 2026-11-13: "combine weather
     * and time and moon"). The sun and moon are worked out on the device, so when the
     * forecast does not answer they are still there, with a line saying so.
     */
    @Override
    public Result fetch(Area area) throws Exception {
        Result r;
        try {
            r = forecast(area);
        } catch (Exception e) {
            final String sun = Daylight.add(area, null).value;
            r = new Result((Http.noNetwork(e) ? "No network" : "Forecast not available") + "; " + sun, null);
            r.line((Http.noNetwork(e) ? "No network: " : "The forecast did not answer: ")
                    + "no forecast. Tap Refresh to try again.");
        }
        return Daylight.add(area, r);
    }

    private Result forecast(Area area) throws Exception {
        final double lat = (area.south + area.north) / 2, lon = (area.west + area.east) / 2;
        final JSONObject point;
        try {
            point = new JSONObject(Http.get(POINTS + String.format(Locale.US, "%.4f,%.4f", lat, lon)))
                    .getJSONObject("properties");
        } catch (Http.StatusException e) {
            if (e.status == 404) {
                final Result r = new Result("Not published here", null);
                r.notPublishedHere = true;
                return r.line("The National Weather Service forecasts the United States and its territories.");
            }
            throw e;
        }
        final String gridUrl = point.optString("forecastGridData", ""), textUrl = point.optString("forecast", "");
        if (!gridUrl.startsWith("https://api.weather.gov/") || !textUrl.startsWith("https://api.weather.gov/"))
            throw new java.io.IOException("unexpected forecast address");
        final JSONObject grid = new JSONObject(Http.get(gridUrl)).getJSONObject("properties");
        final long now = System.currentTimeMillis(), end = now + 24L * 3600_000L;

        final double[] t = range(grid.optJSONObject("temperature"), now, end);
        final double[] rh = range(grid.optJSONObject("relativeHumidity"), now, end);
        final double[] wind = range(grid.optJSONObject("twentyFootWindSpeed"), now, end);
        final double[] gust = range(grid.optJSONObject("windGust"), now, end);
        final double[] rain = range(grid.optJSONObject("probabilityOfPrecipitation"), now, end);
        final double[] thunder = range(grid.optJSONObject("probabilityOfThunder"), now, end);
        final double[] mix = range(grid.optJSONObject("mixingHeight"), now, end);
        final double[] transport = range(grid.optJSONObject("transportWindSpeed"), now, end);
        final double windDir = at(grid.optJSONObject("twentyFootWindDirection"), wind[2]);

        final boolean metric = Units.type() == Span.METRIC;
        final StringBuilder head = new StringBuilder();
        if (!Double.isNaN(t[0]))
            head.append(temp(t[1], metric)).append('/').append(temp(t[0], metric));
        if (!Double.isNaN(rh[0]))
            head.append(", RH ").append(Math.round(rh[0])).append('%');
        if (!Double.isNaN(wind[1]))
            head.append(", wind ").append(speed(wind[1], metric))
                    .append(Double.isNaN(gust[1]) ? "" : " G" + speed(gust[1], metric).replaceAll("[^0-9]", ""));
        if (!Double.isNaN(rain[1]) && rain[1] >= 20)
            head.append(", rain ").append(Math.round(rain[1])).append('%');
        final Result r = new Result(head.length() == 0 ? "No forecast values" : head.toString(), null);

        r.line(GROUP + "Next 24 hours");
        if (!Double.isNaN(t[0]))
            r.line("  Temperature " + temp(t[0], metric) + " to " + temp(t[1], metric) + ".");
        if (!Double.isNaN(rh[0]))
            r.line("  Humidity " + Math.round(rh[0]) + "% to " + Math.round(rh[1]) + "%.");
        if (!Double.isNaN(wind[1]))
            r.line("  20-ft wind up to " + speed(wind[1], metric)
                    + (Double.isNaN(windDir) ? "" : " from the " + compass(windDir))
                    + (Double.isNaN(gust[1]) ? "" : ", gusts to " + speed(gust[1], metric)) + ".");
        if (!Double.isNaN(rain[1]))
            r.line("  Chance of rain up to " + Math.round(rain[1]) + "%.");
        if (!Double.isNaN(thunder[1]))
            r.line("  Chance of thunder up to " + Math.round(thunder[1]) + "%.");
        if (!Double.isNaN(mix[1]))
            r.line("  Mixing height up to " + (metric ? Math.round(mix[1]) + " m" : String.format(Locale.US, "%,d ft",
                    Math.round(mix[1] * 3.28084))) + (Double.isNaN(transport[1]) ? "" : ", transport wind up to "
                    + speed(transport[1], metric)) + ".");

        final JSONArray periods = new JSONObject(Http.get(textUrl)).getJSONObject("properties").optJSONArray("periods");
        for (int i = 0; periods != null && i < periods.length() && i < 3; i++) {
            final JSONObject p = periods.optJSONObject(i);
            if (p == null)
                continue;
            r.line(GROUP + clip(p.optString("name", ""), 30) + ": " + clip(p.optString("shortForecast", ""), 60));
            r.line("  " + clip(p.optString("detailedForecast", ""), 400));
        }
        final String city = point.optJSONObject("relativeLocation") == null ? null
                : point.optJSONObject("relativeLocation").optJSONObject("properties").optString("city", null);
        r.line("Forecast for the center of the area" + (city == null ? "" : ", near " + clip(city, 40))
                + " (NWS office " + clip(point.optString("gridId", ""), 4) + ").");
        return r;
    }

    /** {min, max, time of the max} over the values whose time overlaps [from, to]. */
    private static double[] range(JSONObject layer, long from, long to) {
        double lo = Double.NaN, hi = Double.NaN, when = Double.NaN;
        final JSONArray v = layer == null ? null : layer.optJSONArray("values");
        final boolean f = layer != null && layer.optString("uom", "").contains("degF");
        for (int i = 0; v != null && i < v.length(); i++) {
            final JSONObject e = v.optJSONObject(i);
            if (e == null || e.isNull("value"))
                continue;
            final long[] span = span(e.optString("validTime", ""));
            if (span == null || span[1] <= from || span[0] >= to)
                continue;
            double x = e.optDouble("value", Double.NaN);
            if (Double.isNaN(x))
                continue;
            if (f)
                x = (x - 32) * 5 / 9;
            if (Double.isNaN(lo) || x < lo)
                lo = x;
            if (Double.isNaN(hi) || x > hi) {
                hi = x;
                when = span[0];
            }
        }
        return new double[] { lo, hi, when };
    }

    /** The value in effect at a time, NaN when none. */
    private static double at(JSONObject layer, double time) {
        final JSONArray v = layer == null || Double.isNaN(time) ? null : layer.optJSONArray("values");
        for (int i = 0; v != null && i < v.length(); i++) {
            final JSONObject e = v.optJSONObject(i);
            final long[] span = e == null ? null : span(e.optString("validTime", ""));
            if (span != null && span[0] <= time && time < span[1] && !e.isNull("value"))
                return e.optDouble("value", Double.NaN);
        }
        return Double.NaN;
    }

    /** {@code 2026-11-13T14:00:00+00:00/PT3H} as {start, end} epoch ms. */
    private static long[] span(String s) {
        final int slash = s.indexOf('/');
        if (slash < 0)
            return null;
        try {
            final SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US);
            f.setTimeZone(TimeZone.getTimeZone("UTC"));
            final Date start = f.parse(s.substring(0, slash));
            if (start == null)
                return null;
            final String d = s.substring(slash + 1);
            final java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("P(?:(\\d+)D)?(?:T(?:(\\d+)H)?(?:(\\d+)M)?)?").matcher(d);
            if (!m.matches())
                return null;
            final long ms = (m.group(1) == null ? 0 : Long.parseLong(m.group(1)) * 86_400_000L)
                    + (m.group(2) == null ? 0 : Long.parseLong(m.group(2)) * 3_600_000L)
                    + (m.group(3) == null ? 0 : Long.parseLong(m.group(3)) * 60_000L);
            return new long[] { start.getTime(), start.getTime() + Math.max(ms, 60_000L) };
        } catch (Exception e) {
            return null;
        }
    }

    private static String temp(double c, boolean metric) {
        return metric ? Math.round(c) + "°C" : Math.round(c * 9 / 5 + 32) + "°F";
    }

    /** From km/h. */
    private static String speed(double kmh, boolean metric) {
        return metric ? Math.round(kmh) + " km/h" : Math.round(kmh / 1.609344) + " mph";
    }

    private static String compass(double deg) {
        final String[] d = { "N", "NE", "E", "SE", "S", "SW", "W", "NW" };
        return d[(int) Math.round(((deg % 360) + 360) % 360 / 45) % 8];
    }

    private static String clip(String s, int max) {
        s = s == null ? "" : s.replaceAll("\\s+", " ").trim();
        return s.length() <= max ? s : s.substring(0, max - 3).trim() + "...";
    }
}
