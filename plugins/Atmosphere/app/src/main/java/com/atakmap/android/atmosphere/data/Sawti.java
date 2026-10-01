package com.atakmap.android.atmosphere.data;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.GregorianCalendar;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;
import java.util.TreeSet;

/**
 * The Santa Ana Wildfire Threat Index (SAWTI, which is what everyone calls it): the
 * US Forest Service's daily rating of how
 * bad a fire could get if one starts during an offshore wind event, for four
 * Southern California zones, built with SDG&E and UCLA. One small keyless JSON
 * (about 7.5 KB, 4 zones x 6 days) posted about 09:00Z every day and re-issued by
 * a forecaster on event days. The levels, colors and their meanings are the
 * website's own (fsapps.nwcg.gov/psp/sawti, read 2026-10-01). Zone 4 rates
 * Sundowner winds rather than Santa Ana.
 *
 * <p>The feed carries zone numbers only; the outlines live in the website's page
 * source and ship with the plugin as {@link #ZONES_ASSET}.
 *
 * <p>No Android types; tested.
 */
public final class Sawti {

    public static final String HOST = "fsapps.nwcg.gov";
    private static final String API = "https://" + HOST + "/psp/sawti/api/";
    public static final String FORECAST_URL = API + "forecast";
    /** {@code {"status":false}}; true means the site hides its forecast, and so do we. */
    public static final String TROUBLE_URL = API + "TechnicalDifficulties";
    public static final String ZONES_ASSET = "sawti_zones.json";

    /** The site shows days 1-4 of the six the feed carries. */
    public static final int DAYS = 4;

    public static final String[] ZONE_NAMES = { "LA-Ventura", "Orange-Inland Empire",
            "San Diego", "Santa Barbara" };

    public static final String[] LEVELS = { "No Rating", "Marginal", "Moderate", "High",
            "Extreme" };
    /** The map's and the legend's colors (the gauge uses slightly different ones). */
    public static final int[] COLORS = { 0xFFD9D9D9, 0xFFFFFF00, 0xFFEE6E19, 0xFFC00000,
            0xFF7030A2 };
    /** The text on each level's tile on the site: black on the light three, white on the dark two. */
    public static final int[] TEXT_COLORS = { 0xFF000000, 0xFF000000, 0xFF000000, 0xFFFFFFFF,
            0xFFFFFFFF };
    /** The legend's words, as the site writes them. */
    public static final String[] MEANINGS = {
            "Winds are either not expected, or will not contribute to significant fire activity.",
            "Upon ignition, fires may grow rapidly.",
            "Upon ignition, fires will grow rapidly and will be difficult to control.",
            "Upon ignition, fires will grow very rapidly, will burn intensely, and will be very difficult to control.",
            "Upon ignition, fires will have extreme growth, will burn very intensely, and will be uncontrollable." };

    public static String zoneName(int zone) {
        return zone >= 1 && zone <= ZONE_NAMES.length ? ZONE_NAMES[zone - 1] : "Zone " + zone;
    }

    /** "Zone 3: San Diego", the site's own heading. */
    public static String zoneTitle(int zone) {
        return "Zone " + zone + ": " + zoneName(zone);
    }

    public static String level(int value) {
        return LEVELS[clamp(value)];
    }

    public static int color(int value) {
        return COLORS[clamp(value)];
    }

    public static int textColor(int value) {
        return TEXT_COLORS[clamp(value)];
    }

    public static String meaning(int value) {
        return MEANINGS[clamp(value)];
    }

    private static int clamp(int value) {
        return Math.max(0, Math.min(LEVELS.length - 1, value));
    }

    /** One zone on one day. */
    public static final class Day {
        public final int zone;
        /** The calendar date the rating is for, {@code yyyy-MM-dd}, Pacific. */
        public final String date;
        public final int value;
        /** The forecaster's words with the HTML taken out. */
        public final String description, actions;

        Day(int zone, String date, int value, String description, String actions) {
            this.zone = zone;
            this.date = date;
            this.value = value;
            this.description = description;
            this.actions = actions;
        }
    }

    /** One issue of the forecast. */
    public static final class Forecast {
        /** UTC milliseconds the forecast was published, 0 when the feed left it out. */
        public final long issued;
        public final List<Day> days;

        Forecast(long issued, List<Day> days) {
            this.issued = issued;
            this.days = days;
        }

        /** The dates the feed covers, oldest first. */
        public List<String> dates() {
            final TreeSet<String> set = new TreeSet<>();
            for (Day d : days)
                set.add(d.date);
            return new ArrayList<>(set);
        }

        /** 1 for the first date, or 0 when the date is not in this issue. */
        public int dayNumber(String date) {
            return dates().indexOf(date) + 1;
        }

        public Day find(int zone, String date) {
            for (Day d : days)
                if (d.zone == zone && d.date.equals(date))
                    return d;
            return null;
        }
    }

    /** The forecast, or one with no days when the body is not the feed. */
    public static Forecast parse(String body) {
        final List<Day> out = new ArrayList<>();
        long issued = 0L;
        if (body == null || body.isEmpty())
            return new Forecast(0L, out);
        try {
            final JSONObject root = new JSONObject(body);
            issued = IsoTime.parse(root.optString("ForecastPublishedTimestamp", ""));
            final JSONArray rows = root.optJSONArray("Data");
            if (rows != null) {
                for (int i = 0; i < rows.length(); i++) {
                    final JSONObject r = rows.optJSONObject(i);
                    if (r == null)
                        continue;
                    final int zone = r.optInt("zone", 0);
                    final String date = r.optString("date", "");
                    if (zone < 1 || zone > ZONE_NAMES.length || date.length() < 10)
                        continue;
                    out.add(new Day(zone, date.substring(0, 10),
                            clamp(r.optInt("forecastValue", 0)),
                            text(r.optString("eventDescription", "")),
                            text(r.optString("recommendedActions", ""))));
                }
            }
        } catch (Exception e) {
            return new Forecast(0L, new ArrayList<Day>());
        }
        return new Forecast(issued, out);
    }

    /** True only for a body that says {@code "status":true}. */
    public static boolean troubled(String body) {
        if (body == null || body.isEmpty())
            return false;
        try {
            return new JSONObject(body).optBoolean("status", false);
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * The forecaster's HTML as plain text: paragraphs and line breaks kept as new
     * lines, other tags dropped, the common entities decoded, runs of spaces
     * collapsed (the actions put two spaces between sentences).
     */
    public static String text(String html) {
        if (html == null)
            return "";
        String s = html.replaceAll("(?i)<br\\s*/?>", "\n")
                .replaceAll("(?i)</p\\s*>", "\n")
                .replaceAll("<[^>]*>", "")
                .replace("&nbsp;", " ")
                .replace("&quot;", "\"")
                .replace("&#39;", "'")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&amp;", "&");
        s = s.replaceAll("[ \\t\\x0B\\f\\r]+", " ")
                .replaceAll(" *\n *", "\n")
                .replaceAll("\n{2,}", "\n");
        return s.trim();
    }

    /** "Wed 9/30" from {@code 2026-09-30}; the date itself when it is not one. */
    public static String shortDate(String date) {
        final Calendar c = calendar(date);
        if (c == null)
            return date == null ? "" : date;
        return WEEKDAYS[c.get(Calendar.DAY_OF_WEEK) - 1] + " " + (c.get(Calendar.MONTH) + 1)
                + "/" + c.get(Calendar.DAY_OF_MONTH);
    }

    /** "Wed Sep 30" from {@code 2026-09-30}. */
    public static String longDate(String date) {
        final Calendar c = calendar(date);
        if (c == null)
            return date == null ? "" : date;
        return WEEKDAYS[c.get(Calendar.DAY_OF_WEEK) - 1] + " " + MONTHS[c.get(Calendar.MONTH)]
                + " " + c.get(Calendar.DAY_OF_MONTH);
    }

    private static final String[] WEEKDAYS = { "Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat" };
    private static final String[] MONTHS = { "Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul",
            "Aug", "Sep", "Oct", "Nov", "Dec" };

    private static Calendar calendar(String date) {
        if (date == null || date.length() < 10)
            return null;
        try {
            final Calendar c = new GregorianCalendar(TimeZone.getTimeZone("UTC"), Locale.US);
            c.clear();
            c.set(Integer.parseInt(date.substring(0, 4)), Integer.parseInt(date.substring(5, 7)) - 1,
                    Integer.parseInt(date.substring(8, 10)));
            return c;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private Sawti() {
    }
}
