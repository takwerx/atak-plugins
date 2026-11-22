package com.atakmap.android.atmosphere.data;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * The Iowa Environmental Mesonet as takwerx's relay keeps it on mapdepot: the gauge and
 * airport lists, the gauges' daily totals, and for 45 days the gauges' raw readings and
 * the airports' hourly reports, cut by state, day and 2-degree tile (notes repo,
 * tools/iem_relay.py). Through 0.17 every phone asked the mesonet itself, which turns
 * away an address that asks fast; one harvester asks at its pace and phones read files
 * (operator, 2026-10-17: "lets do like psps and the other plugins where we harvest the
 * data and people hit us for the data").
 *
 * <p>The readings stay raw, so {@link Iem}'s own reading of them -- counters that reset,
 * drop out or jump -- is what adds them up, as it did with the mesonet's answers.
 *
 * <p>No Android types; tested.
 */
public final class IemRelay {

    public static final String HOST = "mapdepot.takwerx.org";
    static final String BASE = "https://" + HOST + "/atmosphere/iem/";
    private static final long HOUR = 3_600_000L, DAY = 24 * HOUR;
    /** The readings, reports and radar hours are kept this long. */
    public static final long KEEP_MS = 45 * DAY;
    /** The relay harvests forward: its first gauge readings are of 2026-10-17 15:00 UTC. */
    static final long HOURS_SINCE_MS = 1_792_249_200_000L;
    /** Its daily totals begin on January 1 of the year before it started. */
    public static final String DAILY_SINCE = "2025-01-01";
    private static final int TILE_DEG = 2;
    private static final String STATE = "[A-Z]{2}", DATE = "\\d{4}-\\d{2}-\\d{2}", MONTH = "\\d{4}-\\d{2}";

    /** The oldest moment whose gauge readings and airport reports the relay holds. */
    public static long hoursSince(long now) {
        return Math.max(HOURS_SINCE_MS, utcDayStart(now) - KEEP_MS);
    }

    // ---- where -----------------------------------------------------------------------------

    /** A state's gauges ("DCP") or airports ("ASOS"). */
    public static String networkUrl(String state, String kind) {
        if (!state.matches(STATE) || !(kind.equals("DCP") || kind.equals("ASOS")))
            throw new IllegalArgumentException("network " + state + "_" + kind);
        return BASE + "networks/" + state + "_" + kind + ".json";
    }

    /** A state's gauges' daily totals for one month, "2026-10". */
    public static String dailyUrl(String state, String month) {
        if (!state.matches(STATE) || !month.matches(MONTH))
            throw new IllegalArgumentException("daily " + state + " " + month);
        return BASE + "daily/" + state + "/" + month + ".json";
    }

    /** The gauges' readings of one UTC day in one 2-degree tile, named by {@link #tile}. */
    public static String hadsUrl(String day, String tile) {
        if (!day.matches(DATE) || !tile.matches("-?\\d{1,2}_-?\\d{1,3}"))
            throw new IllegalArgumentException("hads " + day + " " + tile);
        return BASE + "hads/" + day + "/" + tile + ".json";
    }

    /** A state's airports' reports of one UTC day. */
    public static String asosUrl(String day, String state) {
        if (!day.matches(DATE) || !state.matches(STATE))
            throw new IllegalArgumentException("asos " + day + " " + state);
        return BASE + "asos/" + day + "/" + state + ".json";
    }

    /** The 2-degree tile a place is in, by its south-west corner: "34_-118". */
    public static String tile(double lat, double lon) {
        return (int) (Math.floor(lat / TILE_DEG) * TILE_DEG) + "_" + (int) (Math.floor(lon / TILE_DEG) * TILE_DEG);
    }

    /** The tiles the gauges are in, in order. */
    public static List<String> tiles(Collection<Iem.Station> gauges) {
        final TreeSet<String> out = new TreeSet<>();
        for (Iem.Station s : gauges)
            out.add(tile(s.lat, s.lon));
        return new ArrayList<>(out);
    }

    /** The months a run of days touches, "2026-09" to "2026-10". */
    public static List<String> months(String startDay, String endDay) {
        final List<String> out = new ArrayList<>();
        if (!startDay.matches(DATE) || !endDay.matches(DATE))
            return out;
        int y = Integer.parseInt(startDay.substring(0, 4)), m = Integer.parseInt(startDay.substring(5, 7));
        final String last = endDay.substring(0, 7);
        for (String at = month(y, m); at.compareTo(last) <= 0 && out.size() < 400; at = month(y, m)) {
            out.add(at);
            if (++m > 12) {
                m = 1;
                y++;
            }
        }
        return out;
    }

    /** The UTC days a stretch touches, "2026-10-16", "2026-10-17". */
    public static List<String> utcDays(long startMs, long endMs) {
        final List<String> out = new ArrayList<>();
        for (long d = utcDayStart(startMs); d <= endMs && out.size() < 60; d += DAY)
            out.add(utcDay(d));
        return out;
    }

    // ---- what -------------------------------------------------------------------------------

    /** A network list: {"stations": [[id, lat, lon, name], ...]}. */
    public static List<Iem.Station> parseNetwork(String json) {
        final List<Iem.Station> out = new ArrayList<>();
        if (json == null)
            return out;
        try {
            final JSONArray st = new JSONObject(json).optJSONArray("stations");
            if (st == null)
                return out;
            for (int i = 0; i < st.length(); i++) {
                final JSONArray s = st.optJSONArray(i);
                if (s == null || s.length() < 4)
                    continue;
                final String id = s.optString(0, "");
                final double lat = s.optDouble(1, Double.NaN), lon = s.optDouble(2, Double.NaN);
                if (!id.matches(Iem.ID) || !Iem.placeable(lat, lon))
                    continue;
                out.add(new Iem.Station(id, Acis.clean(s.optString(3, id)), lat, lon));
            }
        } catch (Exception e) {
            return out;
        }
        return out;
    }

    /**
     * Daily totals, month files added one at a time: {"month": "2026-10", "gauges":
     * {id: [inches or null, one a day]}}. Kept for the gauges asked and the days of
     * the run; a day with no total is absent, as the mesonet's own answer left it.
     */
    public static final class Days {
        private final Set<String> ids;
        private final String start, end;
        private final Map<String, TreeMap<String, Double>> out = new LinkedHashMap<>();

        public Days(Set<String> ids, String start, String end) {
            this.ids = ids;
            this.start = start;
            this.end = end;
        }

        /** False when the file is not one: nothing of it is kept. */
        public boolean add(String json) {
            try {
                final JSONObject o = new JSONObject(json);
                final String month = o.optString("month", "");
                final JSONObject g = o.optJSONObject("gauges");
                if (!month.matches(MONTH) || g == null)
                    return false;
                final JSONArray names = g.names();
                for (int i = 0; names != null && i < names.length(); i++) {
                    final String id = names.optString(i, "");
                    final JSONArray days = g.optJSONArray(id);
                    if (!id.matches(Iem.ID) || days == null || (ids != null && !ids.contains(id)))
                        continue;
                    for (int d = 0; d < days.length() && d < 31; d++) {
                        if (days.isNull(d))
                            continue;
                        final double v = days.optDouble(d, Double.NaN);
                        final String day = month + String.format(Locale.US, "-%02d", d + 1);
                        if (Double.isNaN(v) || v < 0 || v > 60 || day.compareTo(start) < 0 || day.compareTo(end) > 0)
                            continue;
                        TreeMap<String, Double> by = out.get(id);
                        if (by == null) {
                            by = new TreeMap<>();
                            out.put(id, by);
                        }
                        by.put(day, v);
                    }
                }
                return true;
            } catch (Exception e) {
                return false;
            }
        }

        /** Gauge to day to inches. */
        public Map<String, TreeMap<String, Double>> byGauge() {
            return out;
        }
    }

    /**
     * The gauges' raw readings, tile-day files added one at a time: {"day":
     * "2026-10-17", "gauges": {id: {"pc": [[minute, inches], ...], "pp": [...]}}}, a
     * minute counted from midnight UTC of the file's day. "pc" is the accumulator,
     * "pp" the hourly amount. Kept for the gauges asked, inside the stretch, exactly
     * as the mesonet answered a request for it.
     */
    public static final class Readings {
        private final Set<String> ids;
        private final long startMs, endMs;
        private final Map<String, List<double[]>> acc = new HashMap<>(), hourly = new HashMap<>();

        public Readings(Set<String> ids, long startMs, long endMs) {
            this.ids = ids;
            this.startMs = startMs;
            this.endMs = endMs;
        }

        public boolean add(String json) {
            try {
                final JSONObject o = new JSONObject(json);
                final long day = dayStart(o.optString("day", ""));
                final JSONObject g = o.optJSONObject("gauges");
                if (day < 0 || g == null)
                    return false;
                final JSONArray names = g.names();
                for (int i = 0; names != null && i < names.length(); i++) {
                    final String id = names.optString(i, "");
                    final JSONObject s = g.optJSONObject(id);
                    if (!id.matches(Iem.ID) || s == null || (ids != null && !ids.contains(id)))
                        continue;
                    series(acc, id, day, s.optJSONArray("pc"));
                    series(hourly, id, day, s.optJSONArray("pp"));
                }
                return true;
            } catch (Exception e) {
                return false;
            }
        }

        private void series(Map<String, List<double[]>> into, String id, long day, JSONArray pts) {
            for (int k = 0; pts != null && k < pts.length(); k++) {
                final JSONArray p = pts.optJSONArray(k);
                if (p == null || p.length() < 2)
                    continue;
                final int minute = p.optInt(0, -1);
                final double v = p.optDouble(1, Double.NaN);
                final long t = day + minute * 60_000L;
                if (minute < 0 || minute >= 1440 || Double.isNaN(v) || Double.isInfinite(v) || v < 0 || v >= 1000
                        || t < startMs || t > endMs)
                    continue;
                Iem.add(into, id, t, v);
            }
        }

        public Map<String, Iem.Total> totals() {
            return Iem.hadsTotals(acc, hourly, startMs, endMs);
        }
    }

    /**
     * The airports' reports, state-day files added one at a time: {"day", "stations":
     * {id: {"lat", "lon", "r": [[minute, inches or "T"], ...]}}}.
     */
    public static final class Reports {
        private final Iem.AsosHours hours;

        public Reports(long startMs, long endMs) {
            hours = new Iem.AsosHours(startMs, endMs);
        }

        public boolean add(String json) {
            try {
                final JSONObject o = new JSONObject(json);
                final long day = dayStart(o.optString("day", ""));
                final JSONObject st = o.optJSONObject("stations");
                if (day < 0 || st == null)
                    return false;
                final JSONArray names = st.names();
                for (int i = 0; names != null && i < names.length(); i++) {
                    final String id = names.optString(i, "");
                    final JSONObject s = st.optJSONObject(id);
                    final JSONArray r = s == null ? null : s.optJSONArray("r");
                    if (r == null)
                        continue;
                    final double lat = s.optDouble("lat", Double.NaN), lon = s.optDouble("lon", Double.NaN);
                    for (int k = 0; k < r.length(); k++) {
                        final JSONArray p = r.optJSONArray(k);
                        if (p == null || p.length() < 2)
                            continue;
                        final int minute = p.optInt(0, -1);
                        if (minute < 0 || minute >= 1440)
                            continue;
                        final double v = "T".equals(p.opt(1)) ? 0 : p.optDouble(1, Double.NaN);
                        hours.add(id, day + minute * 60_000L, v, lat, lon);
                    }
                }
                return true;
            } catch (Exception e) {
                return false;
            }
        }

        public Map<String, Iem.Total> totals() {
            return hours.totals();
        }
    }

    // ---- helpers ---------------------------------------------------------------------------

    private static String month(int y, int m) {
        return String.format(Locale.US, "%04d-%02d", y, m);
    }

    static long utcDayStart(long millis) {
        return millis < 0 ? 0 : (millis / DAY) * DAY;
    }

    static String utcDay(long millis) {
        final Calendar c = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
        c.setTimeInMillis(millis);
        return String.format(Locale.US, "%04d-%02d-%02d", c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1,
                c.get(Calendar.DAY_OF_MONTH));
    }

    /** Midnight UTC of "2026-10-17", or -1. */
    private static long dayStart(String day) {
        if (!day.matches(DATE))
            return -1;
        final Calendar c = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
        c.clear();
        c.set(Integer.parseInt(day.substring(0, 4)), Integer.parseInt(day.substring(5, 7)) - 1,
                Integer.parseInt(day.substring(8, 10)));
        return c.getTimeInMillis();
    }

    private IemRelay() {
    }
}
