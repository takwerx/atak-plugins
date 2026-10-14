package com.atakmap.android.atmosphere.data;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * USGS rain gauges: the National Water Information System's instantaneous values
 * for precipitation (parameter 00045), each reading an increment in inches at the
 * gauge's own step, usually 15 minutes. Keyless, nationwide, sparse in places (ten
 * gauges around Southern California, four hundred in Texas, counted 2026-10-11).
 * The last day's increments are summed here into the total a crew wants.
 *
 * <p>No Android types.
 */
public final class UsgsRain {

    public static final String HOST = "waterservices.usgs.gov";

    /** The last day of increments for every active rain gauge in a box. */
    public static String url(double west, double south, double east, double north) {
        return "https://" + HOST + "/nwis/iv/?format=json"
                + String.format(Locale.US, "&bBox=%.3f,%.3f,%.3f,%.3f", west, south, east, north)
                + "&parameterCd=00045&period=P1D&siteStatus=active";
    }

    /**
     * The USGS's own daily totals, {@code start} to {@code end} (yyyy-MM-dd), for every
     * rain gauge in a box that keeps them. Many keep only readings: in a box around
     * central Texas 27 of 210 gauges kept daily totals (2026-10-13).
     */
    public static String dailyUrl(double west, double south, double east, double north, String start,
            String end) {
        return "https://" + HOST + "/nwis/dv/?format=json"
                + String.format(Locale.US, "&bBox=%.3f,%.3f,%.3f,%.3f", west, south, east, north)
                + "&parameterCd=00045&statCd=00006&startDT=" + start + "&endDT=" + end + "&siteStatus=active";
    }

    /**
     * Two days of readings for the gauges named: the days the USGS has not added up
     * yet. Named rather than boxed, so the gauges with no daily totals are not fetched.
     */
    public static String recentUrl(List<String> sites) {
        final StringBuilder b = new StringBuilder();
        for (String s : sites)
            if (s.matches("\\d{8,15}"))
                b.append(b.length() > 0 ? "," : "").append(s);
        return "https://" + HOST + "/nwis/iv/?format=json&sites=" + b + "&parameterCd=00045&period=P2D";
    }

    /** Every active rain gauge in a box that sends readings, with its name and place. */
    public static String sitesUrl(double west, double south, double east, double north) {
        return "https://" + HOST + "/nwis/site/?format=rdb"
                + String.format(Locale.US, "&bBox=%.3f,%.3f,%.3f,%.3f", west, south, east, north)
                + "&parameterCd=00045&siteStatus=active&hasDataTypeCd=iv";
    }

    /**
     * The readings of every rain gauge in a box from {@code startDT} to {@code endDT}
     * (ISO with an offset; empty endDT for now), as tab-separated text: a week of a
     * box over central Texas is 9 MB of it against 17 MB of JSON, and read a line at a
     * time it never becomes a tree (2026-10-13).
     */
    public static String readingsUrl(double west, double south, double east, double north, String startDT,
            String endDT) {
        return "https://" + HOST + "/nwis/iv/?format=rdb"
                + String.format(Locale.US, "&bBox=%.3f,%.3f,%.3f,%.3f", west, south, east, north)
                + "&parameterCd=00045&siteStatus=active&startDT=" + startDT
                + (endDT == null || endDT.isEmpty() ? "" : "&endDT=" + endDT);
    }

    /** A gauge's name and place, from the site list. */
    public static final class Site {
        public final String site, name;
        public final double lat, lon;

        Site(String site, String name, double lat, double lon) {
            this.site = site;
            this.name = name;
            this.lat = lat;
            this.lon = lon;
        }

        public String url() {
            return "https://waterdata.usgs.gov/monitoring-location/" + site + "/";
        }
    }

    /** The site list, by site number. */
    public static Map<String, Site> parseSites(String rdb) {
        final Map<String, Site> out = new LinkedHashMap<>();
        if (rdb == null)
            return out;
        int iSite = -1, iName = -1, iLat = -1, iLon = -1;
        int from = 0;
        while (from < rdb.length()) {
            int to = rdb.indexOf('\n', from);
            if (to < 0)
                to = rdb.length();
            final String line = rdb.substring(from, to).replace("\r", "");
            from = to + 1;
            if (line.isEmpty() || line.charAt(0) == '#')
                continue;
            final String[] f = line.split("\t", -1);
            if (f[0].equals("agency_cd")) {
                for (int i = 0; i < f.length; i++) {
                    if (f[i].equals("site_no"))
                        iSite = i;
                    else if (f[i].equals("station_nm"))
                        iName = i;
                    else if (f[i].equals("dec_lat_va"))
                        iLat = i;
                    else if (f[i].equals("dec_long_va"))
                        iLon = i;
                }
                continue;
            }
            if (iSite < 0 || iLat < 0 || iLon < 0 || f.length <= Math.max(Math.max(iSite, iName), Math.max(iLat, iLon)))
                continue;
            try {
                final double lat = Double.parseDouble(f[iLat]), lon = Double.parseDouble(f[iLon]);
                if (Double.isNaN(lat) || Double.isNaN(lon) || Math.abs(lat) > 90 || Math.abs(lon) > 180)
                    continue;
                final String site = f[iSite].trim();
                if (!site.matches("\\d{8,15}"))
                    continue;
                out.put(site, new Site(site, iName < 0 ? site : f[iName].trim(), lat, lon));
            } catch (NumberFormatException e) {
                // The format line ("5s 15s ...") and anything else that is not a site.
            }
        }
        return out;
    }

    /**
     * Readings summed into each gauge's standard-time day, the way the USGS adds up its
     * own daily totals: the time as written, an hour back when the zone is daylight
     * time, and a reading stamped midnight closing the day before. Site to day to
     * inches; a day with readings of nothing is there with 0, so a day that is absent
     * had no readings at all.
     */
    public static Map<String, Map<String, Double>> parseRdbByDay(String rdb) {
        final Map<String, Map<String, Double>> out = new LinkedHashMap<>();
        if (rdb == null)
            return out;
        int iValue = -1;
        final java.util.Calendar c = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"));
        int from = 0;
        while (from < rdb.length()) {
            int to = rdb.indexOf('\n', from);
            if (to < 0)
                to = rdb.length();
            final String line = rdb.substring(from, to).replace("\r", "");
            from = to + 1;
            if (line.isEmpty() || line.charAt(0) == '#')
                continue;
            final String[] f = line.split("\t", -1);
            if (f[0].equals("agency_cd")) {
                // Each site has its own header; the value column ends in _00045.
                iValue = -1;
                for (int i = 0; i < f.length; i++)
                    if (f[i].endsWith("_00045")) {
                        iValue = i;
                        break;
                    }
                continue;
            }
            if (iValue < 0 || f.length <= iValue || f.length < 4 || !f[0].equals("USGS"))
                continue;
            final String site = f[1].trim(), when = f[2].trim(), zone = f[3].trim();
            final double x;
            try {
                x = Double.parseDouble(f[iValue]);
            } catch (NumberFormatException e) {
                continue;
            }
            if (Double.isNaN(x) || x < 0 || x > 100 || when.length() < 16)
                continue;
            try {
                c.clear();
                c.set(Integer.parseInt(when.substring(0, 4)), Integer.parseInt(when.substring(5, 7)) - 1,
                        Integer.parseInt(when.substring(8, 10)), Integer.parseInt(when.substring(11, 13)),
                        Integer.parseInt(when.substring(14, 16)));
            } catch (NumberFormatException e) {
                continue;
            }
            c.add(java.util.Calendar.MINUTE, zone.length() >= 3 && zone.endsWith("DT") ? -61 : -1);
            final String day = String.format(Locale.US, "%04d-%02d-%02d", c.get(java.util.Calendar.YEAR),
                    c.get(java.util.Calendar.MONTH) + 1, c.get(java.util.Calendar.DAY_OF_MONTH));
            Map<String, Double> days = out.get(site);
            if (days == null) {
                days = new java.util.TreeMap<>();
                out.put(site, days);
            }
            final Double have = days.get(day);
            days.put(day, (have == null ? 0 : have) + x);
        }
        return out;
    }

    /** Hours from UTC of the zones the USGS writes; a zone it does not name is skipped. */
    private static int zoneHours(String zone) {
        switch (zone) {
            case "UTC":
            case "GMT":
                return 0;
            case "ADT":
                return -3;
            case "AST":
            case "EDT":
                return -4;
            case "EST":
            case "CDT":
                return -5;
            case "CST":
            case "MDT":
                return -6;
            case "MST":
            case "PDT":
                return -7;
            case "PST":
            case "AKDT":
                return -8;
            case "AKST":
            case "HDT":
                return -9;
            case "HST":
                return -10;
            case "SST":
                return -11;
            case "ChST":
                return 10;
            default:
                return Integer.MIN_VALUE;
        }
    }

    /**
     * Each gauge's rain from {@code startMs} to {@code endMs} (UTC), a reading counted
     * when the interval it closes ends inside: {total, 1 when its readings cover the
     * stretch with no gap over three hours, else 0}.
     */
    public static Map<String, double[]> parseRdbWindow(String rdb, long startMs, long endMs) {
        final Map<String, double[]> out = new LinkedHashMap<>();
        if (rdb == null)
            return out;
        final long gap = 3L * 3_600_000L;
        final Map<String, long[]> last = new java.util.HashMap<>();
        final java.util.Calendar c = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"));
        int iValue = -1;
        int from = 0;
        while (from < rdb.length()) {
            int to = rdb.indexOf('\n', from);
            if (to < 0)
                to = rdb.length();
            final String line = rdb.substring(from, to).replace("\r", "");
            from = to + 1;
            if (line.isEmpty() || line.charAt(0) == '#')
                continue;
            final String[] f = line.split("\t", -1);
            if (f[0].equals("agency_cd")) {
                iValue = -1;
                for (int i = 0; i < f.length; i++)
                    if (f[i].endsWith("_00045")) {
                        iValue = i;
                        break;
                    }
                continue;
            }
            if (iValue < 0 || f.length <= iValue || f.length < 4 || !f[0].equals("USGS"))
                continue;
            final String site = f[1].trim(), when = f[2].trim();
            final int hours = zoneHours(f[3].trim());
            if (hours == Integer.MIN_VALUE || when.length() < 16)
                continue;
            final double x;
            try {
                x = Double.parseDouble(f[iValue]);
                c.clear();
                c.set(Integer.parseInt(when.substring(0, 4)), Integer.parseInt(when.substring(5, 7)) - 1,
                        Integer.parseInt(when.substring(8, 10)), Integer.parseInt(when.substring(11, 13)),
                        Integer.parseInt(when.substring(14, 16)));
            } catch (NumberFormatException e) {
                continue;
            }
            if (Double.isNaN(x) || x < 0 || x > 100)
                continue;
            final long t = c.getTimeInMillis() - hours * 3_600_000L;
            if (t <= startMs || t > endMs)
                continue;
            double[] tot = out.get(site);
            long[] seen = last.get(site);
            if (tot == null) {
                tot = new double[] { 0, 1 };
                out.put(site, tot);
                seen = new long[] { startMs };
                last.put(site, seen);
            }
            tot[0] += x;
            if (t - seen[0] > gap)
                tot[1] = 0;
            seen[0] = Math.max(seen[0], t);
        }
        final long end = Math.min(endMs, System.currentTimeMillis());
        for (Map.Entry<String, double[]> e : out.entrySet())
            if (end - last.get(e.getKey())[0] > gap)
                e.getValue()[1] = 0;
        return out;
    }

    /** One gauge's daily totals, by its day. */
    public static final class Daily {
        public final String site, name;
        public final double lat, lon;
        /** yyyy-MM-dd to inches, the gauge's standard-time day. */
        public final java.util.TreeMap<String, Double> days = new java.util.TreeMap<>();

        Daily(String site, String name, double lat, double lon) {
            this.site = site;
            this.name = name;
            this.lat = lat;
            this.lon = lon;
        }

        public String url() {
            return "https://waterdata.usgs.gov/monitoring-location/" + site + "/";
        }
    }

    /** Every gauge in a daily answer, by site. */
    public static Map<String, Daily> parseDaily(String body) {
        final Map<String, Daily> out = new LinkedHashMap<>();
        for (Series t : series(body)) {
            final Daily d = new Daily(t.site, t.name, t.lat, t.lon);
            for (int k = 0; k < t.readings.size(); k++)
                if (!t.days.get(k).isEmpty())
                    d.days.put(t.days.get(k), t.readings.get(k)[1]);
            out.put(t.site, d);
        }
        return out;
    }

    /**
     * Readings summed into the gauge's standard-time days, the way the USGS adds up
     * its own: matched to the inch against a month of San Bernardino gauges
     * (2026-10-13), a reading stamped midnight closing the day before. Site to day to
     * inches.
     */
    public static Map<String, Map<String, Double>> parseByDay(String body) {
        final Map<String, Map<String, Double>> out = new LinkedHashMap<>();
        for (Series t : series(body)) {
            final Map<String, Double> days = new java.util.TreeMap<>();
            for (double[] r : t.readings) {
                final long when = (long) r[0];
                if (when <= 0)
                    continue;
                final String day = utcDay(when - 60_000L + t.standardOffsetMs);
                final Double have = days.get(day);
                days.put(day, (have == null ? 0 : have) + r[1]);
            }
            out.put(t.site, days);
        }
        return out;
    }

    /** A gauge's total over a run of days, from its daily totals and the readings after them. */
    public static final class Span {
        public final Daily gauge;
        public final double total;
        /** Days of the run with a total or readings. */
        public final int daysOnRecord, daysInRun;
        /** Inches by day, the run's days with any, oldest first. */
        public final java.util.TreeMap<String, Double> byDay;

        Span(Daily gauge, double total, int daysOnRecord, int daysInRun, java.util.TreeMap<String, Double> byDay) {
            this.gauge = gauge;
            this.total = total;
            this.daysOnRecord = daysOnRecord;
            this.daysInRun = daysInRun;
            this.byDay = byDay;
        }
    }

    /**
     * {@code start} to {@code end}: the daily totals inside the run, then any day of
     * readings after the last daily total, which is today and, early in the morning,
     * yesterday. Null when the gauge has nothing in the run.
     */
    public static Span span(Daily d, Map<String, Double> recent, String start, String end, int daysInRun) {
        final java.util.TreeMap<String, Double> byDay = new java.util.TreeMap<>();
        double total = 0;
        for (Map.Entry<String, Double> e : d.days.entrySet())
            if (e.getKey().compareTo(start) >= 0 && e.getKey().compareTo(end) <= 0) {
                byDay.put(e.getKey(), e.getValue());
                total += e.getValue();
            }
        final String last = d.days.isEmpty() ? "" : d.days.lastKey();
        if (recent != null)
            for (Map.Entry<String, Double> e : recent.entrySet())
                if (e.getKey().compareTo(last) > 0 && e.getKey().compareTo(start) >= 0
                        && e.getKey().compareTo(end) <= 0) {
                    byDay.put(e.getKey(), e.getValue());
                    total += e.getValue();
                }
        if (byDay.isEmpty())
            return null;
        return new Span(d, total, byDay.size(), daysInRun, byDay);
    }

    private static String utcDay(long millis) {
        final java.text.SimpleDateFormat f = new java.text.SimpleDateFormat("yyyy-MM-dd", Locale.US);
        f.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
        return f.format(new java.util.Date(millis));
    }

    /** One time series as read: readings are {UTC millis, inches}, with each one's date as written beside it. */
    private static final class Series {
        String site, name;
        double lat, lon;
        long standardOffsetMs;
        final List<double[]> readings = new ArrayList<>();
        final List<String> days = new ArrayList<>();
    }

    private static List<Series> series(String body) {
        final List<Series> out = new ArrayList<>();
        if (body == null || body.isEmpty())
            return out;
        try {
            final JSONArray all = new JSONObject(body).getJSONObject("value").optJSONArray("timeSeries");
            if (all == null)
                return out;
            for (int i = 0; i < all.length(); i++) {
                final JSONObject t = all.optJSONObject(i);
                final JSONObject info = t == null ? null : t.optJSONObject("sourceInfo");
                if (info == null)
                    continue;
                final JSONArray codes = info.optJSONArray("siteCode");
                final String site = codes == null || codes.length() == 0 ? ""
                        : codes.getJSONObject(0).optString("value", "");
                final JSONObject geo = info.optJSONObject("geoLocation");
                final JSONObject loc = geo == null ? null : geo.optJSONObject("geogLocation");
                if (loc == null || site.isEmpty())
                    continue;
                final Series s = new Series();
                s.site = site;
                s.name = StormReports.text(info, "siteName");
                s.lat = loc.optDouble("latitude", Double.NaN);
                s.lon = loc.optDouble("longitude", Double.NaN);
                if (Double.isNaN(s.lat) || Double.isNaN(s.lon))
                    continue;
                final JSONObject tz = info.optJSONObject("timeZoneInfo");
                final JSONObject std = tz == null ? null : tz.optJSONObject("defaultTimeZone");
                s.standardOffsetMs = offsetMs(std == null ? "" : std.optString("zoneOffset", ""));
                final double noData = t.optJSONObject("variable") == null ? -999999d
                        : t.getJSONObject("variable").optDouble("noDataValue", -999999d);
                final JSONArray sets = t.optJSONArray("values");
                final JSONArray values = sets == null || sets.length() == 0 ? null
                        : sets.getJSONObject(0).optJSONArray("value");
                if (values != null)
                    for (int k = 0; k < values.length(); k++) {
                        final JSONObject v = values.getJSONObject(k);
                        final double x = v.optDouble("value", Double.NaN);
                        if (Double.isNaN(x) || x == noData || x < 0 || x > 100)
                            continue;
                        final String when = v.optString("dateTime", "");
                        if (when.length() >= 10 && when.charAt(4) == '-')
                            s.days.add(when.substring(0, 10));
                        else
                            s.days.add("");
                        s.readings.add(new double[] { IsoTime.parse(when), x });
                    }
                out.add(s);
            }
        } catch (Exception e) {
            return out;
        }
        return out;
    }

    /** "-08:00" to milliseconds; 0 when unreadable. */
    static long offsetMs(String zone) {
        if (zone == null || zone.length() < 6 || (zone.charAt(0) != '-' && zone.charAt(0) != '+'))
            return 0;
        try {
            final int h = Integer.parseInt(zone.substring(1, 3)), m = Integer.parseInt(zone.substring(4, 6));
            final long ms = (h * 60L + m) * 60_000L;
            return zone.charAt(0) == '-' ? -ms : ms;
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** One gauge with its day. */
    public static final class Gauge {
        public final String site, name;
        public final double lat, lon;
        /** Inches in the last 24 hours, from the increments. */
        public final double total;
        /** When the newest reading was taken, UTC millis, or 0. */
        public final long latest;
        /** Inches per hour of the day, oldest first, for the details. */
        public final double[] hourly;
        public final long[] hourStarts;

        Gauge(String site, String name, double lat, double lon, double total, long latest,
                double[] hourly, long[] hourStarts) {
            this.site = site;
            this.name = name;
            this.lat = lat;
            this.lon = lon;
            this.total = total;
            this.latest = latest;
            this.hourly = hourly;
            this.hourStarts = hourStarts;
        }

        public String url() {
            return "https://waterdata.usgs.gov/monitoring-location/" + site + "/";
        }
    }

    public static List<Gauge> parse(String body) {
        final List<Gauge> out = new ArrayList<>();
        if (body == null || body.isEmpty())
            return out;
        try {
            final JSONArray series = new JSONObject(body).getJSONObject("value").optJSONArray("timeSeries");
            if (series == null)
                return out;
            for (int i = 0; i < series.length(); i++) {
                final JSONObject t = series.optJSONObject(i);
                if (t == null)
                    continue;
                final JSONObject info = t.optJSONObject("sourceInfo");
                if (info == null)
                    continue;
                final JSONArray codes = info.optJSONArray("siteCode");
                final String site = codes == null || codes.length() == 0 ? ""
                        : codes.getJSONObject(0).optString("value", "");
                final JSONObject geo = info.optJSONObject("geoLocation");
                final JSONObject loc = geo == null ? null : geo.optJSONObject("geogLocation");
                if (loc == null || site.isEmpty())
                    continue;
                final double lat = loc.optDouble("latitude", Double.NaN);
                final double lon = loc.optDouble("longitude", Double.NaN);
                if (Double.isNaN(lat) || Double.isNaN(lon))
                    continue;
                final double noData = t.optJSONObject("variable") == null ? -999999d
                        : t.getJSONObject("variable").optDouble("noDataValue", -999999d);
                final JSONArray valuesSets = t.optJSONArray("values");
                final JSONArray values = valuesSets == null || valuesSets.length() == 0 ? null
                        : valuesSets.getJSONObject(0).optJSONArray("value");
                double total = 0;
                long latest = 0;
                final List<double[]> readings = new ArrayList<>();
                if (values != null)
                    for (int k = 0; k < values.length(); k++) {
                        final JSONObject v = values.getJSONObject(k);
                        final double x = v.optDouble("value", Double.NaN);
                        if (Double.isNaN(x) || x == noData || x < 0)
                            continue;
                        final long when = IsoTime.parse(v.optString("dateTime", ""));
                        total += x;
                        latest = Math.max(latest, when);
                        readings.add(new double[] { when, x });
                    }
                // Into hours, oldest first.
                final List<Long> starts = new ArrayList<>();
                final List<Double> sums = new ArrayList<>();
                for (double[] r : readings) {
                    final long hour = ((long) r[0] / 3_600_000L) * 3_600_000L;
                    final int at = starts.indexOf(hour);
                    if (at < 0) {
                        starts.add(hour);
                        sums.add(r[1]);
                    } else {
                        sums.set(at, sums.get(at) + r[1]);
                    }
                }
                final double[] hourly = new double[sums.size()];
                final long[] hourStarts = new long[sums.size()];
                for (int k = 0; k < sums.size(); k++) {
                    hourly[k] = sums.get(k);
                    hourStarts[k] = starts.get(k);
                }
                out.add(new Gauge(site, StormReports.text(info, "siteName"), lat, lon, total,
                        latest, hourly, hourStarts));
            }
        } catch (Exception e) {
            return out;
        }
        return out;
    }

    private UsgsRain() {
    }
}
