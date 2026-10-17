package com.atakmap.android.atmosphere.data;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;
import java.util.TreeMap;

/**
 * The Iowa Environmental Mesonet (Iowa State University, {@code mesonet.agron.iastate.edu},
 * keyless, public domain): the archive of the NWS gauge feed (HADS) that the forecast
 * offices' rainfall reports are written from. County flood-control gauges, RAWS, the
 * Forest Service's and the state's: about 960 gauges in a box around Southern
 * California against the ten the USGS keeps there (2026-10-13). And the airports' hourly
 * reports, so a total can end at an hour, not a day: the rain in the 24 hours before a
 * fire started (operator, 2026-10-13: "we had the bike fire on sunday. what was rain in
 * last 24 hours before the fire?").
 *
 * <p>The archive is not checked: one gauge read 8.04 in on a dry day and nothing through
 * the storm after it. {@link #disagrees} holds a gauge against its neighbors.
 *
 * <p>No Android types; tested.
 */
public final class Iem {

    public static final String HOST = "mesonet.agron.iastate.edu";
    static final String ID = "[A-Z0-9]{3,10}";
    private static final long HOUR = 3_600_000L;
    /** A gauge whose readings stop for longer than this inside a run has a gap. */
    private static final long MAX_GAP = 3 * HOUR;
    /**
     * An accumulator rise faster than this is a fault, not rain: 2 in within a quarter
     * hour, 4 in an hour. Heavy storms in Texas and the monsoon reach 2 to 3 in an hour.
     */
    private static final double MAX_STEP = 2.0, MAX_PER_HOUR = 4.0;

    /** One gauge of a network list. */
    public static final class Station {
        public final String id, name;
        public final double lat, lon;

        Station(String id, String name, double lat, double lon) {
            this.id = id;
            this.name = name;
            this.lat = lat;
            this.lon = lon;
        }
    }

    /** A gauge's total over a stretch, and whether the stretch was covered. */
    public static final class Total {
        public final String id;
        public final double inches;
        public final boolean complete;
        /** Inches by the gauge's day, for a run of days; empty for a stretch of hours. */
        public final TreeMap<String, Double> byDay;
        /** Where, for the airports, whose list comes with the readings. */
        public final double lat, lon;

        Total(String id, double inches, boolean complete, TreeMap<String, Double> byDay, double lat, double lon) {
            this.id = id;
            this.inches = inches;
            this.complete = complete;
            this.byDay = byDay;
            this.lat = lat;
            this.lon = lon;
        }
    }

    // ---- network lists ---------------------------------------------------------------------

    /** A state's gauges: "DCP" for the HADS gauges, "ASOS" for the airports. */
    public static String networkUrl(String state, String kind) {
        return "https://" + HOST + "/api/1/network/" + state + "_" + kind + ".geojson";
    }

    /** Every gauge on line with a place; the archive lists retired ones too. */
    public static List<Station> parseNetwork(String geojson) {
        final List<Station> out = new ArrayList<>();
        if (geojson == null)
            return out;
        try {
            final JSONArray fs = new JSONObject(geojson).optJSONArray("features");
            if (fs == null)
                return out;
            for (int i = 0; i < fs.length(); i++) {
                final JSONObject f = fs.optJSONObject(i);
                final JSONObject p = f == null ? null : f.optJSONObject("properties");
                final JSONObject g = f == null ? null : f.optJSONObject("geometry");
                if (p == null || g == null || !p.optBoolean("online", true))
                    continue;
                final String id = p.optString("id", "");
                final JSONArray c = g.optJSONArray("coordinates");
                if (!id.matches(ID) || c == null || c.length() < 2)
                    continue;
                final double lon = c.optDouble(0, Double.NaN), lat = c.optDouble(1, Double.NaN);
                if (!placeable(lat, lon))
                    continue;
                out.add(new Station(id, Acis.clean(p.optString("name", id)), lat, lon));
            }
        } catch (Exception e) {
            return out;
        }
        return out;
    }

    /** A list as kept on the phone: one gauge a line, id, lat, lon, name. */
    public static String toLines(List<Station> list) {
        final StringBuilder b = new StringBuilder();
        for (Station s : list)
            b.append(s.id).append('\t').append(String.format(Locale.US, "%.5f\t%.5f", s.lat, s.lon)).append('\t')
                    .append(s.name.replace('\t', ' ').replace('\n', ' ')).append('\n');
        return b.toString();
    }

    public static List<Station> fromLines(String text) {
        final List<Station> out = new ArrayList<>();
        if (text == null)
            return out;
        for (String line : text.split("\n")) {
            final String[] f = line.split("\t", -1);
            if (f.length < 4 || !f[0].matches(ID))
                continue;
            try {
                final double lat = Double.parseDouble(f[1]), lon = Double.parseDouble(f[2]);
                if (placeable(lat, lon))
                    out.add(new Station(f[0], f[3], lat, lon));
            } catch (NumberFormatException e) {
                // A damaged line; the rest still count.
            }
        }
        return out;
    }

    // ---- daily totals ----------------------------------------------------------------------

    /** The HADS gauges' daily totals, the gauge's local calendar day, {@code start} to {@code end}. */
    public static String dailyUrl(String state, List<String> ids, String start, String end) {
        return "https://" + HOST + "/cgi-bin/request/daily.py?network=" + state + "_DCP&stations=" + join(ids)
                + "&sts=" + start + "&ets=" + end + "&var=precip_in&format=csv&na=M";
    }

    /** Gauge to day to inches; a day with no total ("M") is absent. */
    public static Map<String, TreeMap<String, Double>> parseDaily(String csv) {
        final Map<String, TreeMap<String, Double>> out = new LinkedHashMap<>();
        if (csv == null || !csv.startsWith("station,day,precip_in"))
            return out;
        for (String line : lines(csv)) {
            final String[] f = line.split(",", -1);
            if (f.length < 3 || !f[0].matches(ID) || f[1].length() != 10)
                continue;
            final double v;
            try {
                v = Double.parseDouble(f[2]);
            } catch (NumberFormatException e) {
                continue;
            }
            if (Double.isNaN(v) || v < 0 || v > 60)
                continue;
            TreeMap<String, Double> days = out.get(f[0]);
            if (days == null) {
                days = new TreeMap<>();
                out.put(f[0], days);
            }
            days.put(f[1], v);
        }
        return out;
    }

    // ---- a stretch of hours: the HADS readings ---------------------------------------------

    /** The HADS gauges' readings from {@code startZ} to {@code endZ}, "2026-10-10T21:00Z". */
    public static String hadsUrl(String state, List<String> ids, String startZ, String endZ) {
        return "https://" + HOST + "/cgi-bin/request/hads.py?network=" + state + "_DCP&stations=" + join(ids)
                + "&sts=" + startZ + "&ets=" + endZ;
    }

    /**
     * Each gauge's rain from {@code startMs} to {@code endMs} (UTC): from its
     * accumulator where it has one, the rise of the count with a reset or a dropped
     * reading stepped over and a jump past {@link #MAX_STEP} refused; else from its
     * hourly amounts.
     */
    public static Map<String, Total> parseHads(String csv, long startMs, long endMs) {
        final Map<String, Total> out = new LinkedHashMap<>();
        if (csv == null || !csv.startsWith("station,utc_valid"))
            return out;
        final List<String> rows = lines(csv);
        if (rows.isEmpty())
            return out;
        final String[] head = csv.substring(0, csv.indexOf('\n') < 0 ? csv.length() : csv.indexOf('\n'))
                .trim().split(",", -1);
        // The accumulator: the primary one first; the hourly amounts as the fallback.
        final List<Integer> pc = new ArrayList<>(), pp = new ArrayList<>();
        for (int i = 2; i < head.length; i++) {
            if (head[i].equals("PCIRGZZ"))
                pc.add(0, i);
            else if (head[i].startsWith("PCIR"))
                pc.add(i);
            if (head[i].equals("PPHRGZZ"))
                pp.add(0, i);
            else if (head[i].startsWith("PPHR"))
                pp.add(i);
        }
        final Map<String, List<double[]>> acc = new HashMap<>(), hourly = new HashMap<>();
        for (String line : rows) {
            final String[] f = line.split(",", -1);
            if (f.length < 2 || !f[0].matches(ID))
                continue;
            final long t = utc(f[1]);
            if (t <= 0)
                continue;
            final double a = first(f, pc), h = first(f, pp);
            if (!Double.isNaN(a))
                add(acc, f[0], t, a);
            if (!Double.isNaN(h))
                add(hourly, f[0], t, h);
        }
        return hadsTotals(acc, hourly, startMs, endMs);
    }

    /**
     * Each gauge's total from its readings, oldest first or not: {@code acc} the
     * accumulator, {@code hourly} the hourly amounts, gauge to [millis, inches].
     * {@link #parseHads} and the relay's copy of the same readings both end here.
     */
    static Map<String, Total> hadsTotals(Map<String, List<double[]>> acc, Map<String, List<double[]>> hourly,
            long startMs, long endMs) {
        final Map<String, Total> out = new LinkedHashMap<>();
        final java.util.Set<String> ids = new java.util.LinkedHashSet<>(acc.keySet());
        ids.addAll(hourly.keySet());
        for (String id : ids) {
            final List<double[]> a = acc.get(id);
            if (a != null && a.size() >= 2) {
                out.put(id, new Total(id, accumulated(a), covered(a, startMs, endMs), new TreeMap<String, Double>(),
                        Double.NaN, Double.NaN));
                continue;
            }
            final List<double[]> h = hourly.get(id);
            if (h == null)
                continue;
            double sum = 0;
            for (double[] r : h)
                if (r[0] > startMs && r[0] <= endMs)
                    sum += r[1];
            out.put(id, new Total(id, sum, covered(h, startMs, endMs), new TreeMap<String, Double>(), Double.NaN,
                    Double.NaN));
        }
        return out;
    }

    /** The rise of an accumulator, oldest first, stepping over resets, dropouts and faults. */
    static double accumulated(List<double[]> series) {
        final List<double[]> s = new ArrayList<>(series);
        Collections.sort(s, new java.util.Comparator<double[]>() {
            @Override
            public int compare(double[] x, double[] y) {
                return Double.compare(x[0], y[0]);
            }
        });
        double sum = 0, prev = s.get(0)[1], prevT = s.get(0)[0];
        for (int i = 1; i < s.size(); i++) {
            final double v = s.get(i)[1], d = v - prev;
            final double limit = Math.max(MAX_STEP, MAX_PER_HOUR * (s.get(i)[0] - prevT) / HOUR);
            if (d >= 0) {
                if (d <= limit) {
                    sum += d;
                    prev = v;
                    prevT = s.get(i)[0];
                } else if (i + 1 < s.size() && Math.abs(s.get(i + 1)[1] - v) <= MAX_STEP) {
                    // A jump past MAX_STEP is a fault, never rain; when the count stays
                    // up there it was set anew, and it counts on from the new value.
                    prev = v;
                    prevT = s.get(i)[0];
                }
                continue;
            }
            // Lower: a reading that dropped out, if the count comes back within a few
            // reports; else the gauge was reset, and it counts on from the new value.
            boolean back = false;
            for (int k = i + 1; k < s.size() && k <= i + 4; k++)
                if (s.get(k)[1] >= prev - 0.005) {
                    back = true;
                    break;
                }
            if (!back) {
                prev = v;
                prevT = s.get(i)[0];
            }
        }
        return sum;
    }

    // ---- a stretch of hours: the airports --------------------------------------------------

    /** Every airport's routine hourly reports in a state, with their places. */
    public static String asosUrl(String state, String startZ, String endZ) {
        return "https://" + HOST + "/cgi-bin/request/asos.py?network=" + state
                + "_ASOS&stations=_ALL&data=p01i&tz=Etc/UTC&format=onlycomma&latlon=yes&report_type=3&missing=M"
                + "&trace=T&sts=" + startZ + "&ets=" + endZ;
    }

    /**
     * Each airport's rain from {@code startMs} to {@code endMs}: one report an hour,
     * the one nearest the top of it, so an automated station that reports the last
     * hour every 20 minutes is not counted three times. Covered when nine hours in ten
     * have a report.
     */
    public static Map<String, Total> parseAsos(String csv, long startMs, long endMs) {
        final AsosHours hours = new AsosHours(startMs, endMs);
        if (csv == null || !csv.startsWith("station,valid"))
            return hours.totals();
        for (String line : lines(csv)) {
            final String[] f = line.split(",", -1);
            if (f.length < 5)
                continue;
            final double v;
            if (f[4].equals("T"))
                v = 0;
            else
                try {
                    v = Double.parseDouble(f[4]);
                } catch (NumberFormatException e) {
                    continue;
                }
            try {
                hours.add(f[0], utc(f[1]), v, Double.parseDouble(f[3]), Double.parseDouble(f[2]));
            } catch (NumberFormatException e) {
                // No place; the report cannot be drawn.
            }
        }
        return hours.totals();
    }

    /**
     * The airports' reports, one an hour: {@link #parseAsos} and the relay's copy of
     * the same reports both add them here.
     */
    static final class AsosHours {
        private final long startMs, endMs;
        private final Map<String, Map<Long, double[]>> byHour = new LinkedHashMap<>();
        private final Map<String, double[]> where = new HashMap<>();

        AsosHours(long startMs, long endMs) {
            this.startMs = startMs;
            this.endMs = endMs;
        }

        /** One report: inches in the hour it closes (a trace is 0). */
        void add(String id, long t, double v, double lat, double lon) {
            if (id == null || !id.matches(ID) || t <= startMs || t > endMs + 15 * 60_000L)
                return;
            if (Double.isNaN(v) || v < 0 || v > 10)
                return;
            if (placeable(lat, lon))
                where.put(id, new double[] { lat, lon });
            // The hour this report closes: the nearest top of the hour.
            final long hour = ((t + HOUR / 2) / HOUR) * HOUR;
            if (hour <= startMs || hour > endMs)
                return;
            Map<Long, double[]> hours = byHour.get(id);
            if (hours == null) {
                hours = new HashMap<>();
                byHour.put(id, hours);
            }
            final double[] have = hours.get(hour);
            final long off = Math.abs(t - hour);
            if (off <= 15 * 60_000L && (have == null || off < have[0]))
                hours.put(hour, new double[] { off, v });
        }

        Map<String, Total> totals() {
            final Map<String, Total> out = new LinkedHashMap<>();
            final long wanted = Math.max(1, (endMs - startMs) / HOUR);
            for (Map.Entry<String, Map<Long, double[]>> e : byHour.entrySet()) {
                final double[] at = where.get(e.getKey());
                if (at == null)
                    continue;
                double sum = 0;
                for (double[] h : e.getValue().values())
                    sum += h[1];
                out.put(e.getKey(), new Total(e.getKey(), sum, e.getValue().size() * 10 >= wanted * 9,
                        new TreeMap<String, Double>(), at[0], at[1]));
            }
            return out;
        }
    }

    // ---- holding a gauge against its neighbors ---------------------------------------------

    /**
     * Whether a gauge's total is out of line with every gauge around it, which the
     * archive's faults are: a gauge that read inches on a dry day, or nothing through a
     * storm its neighbors measured. Needs three neighbors within 25 km; with fewer it
     * cannot be told and is kept.
     */
    public static boolean disagrees(double inches, double lat, double lon, List<double[]> others) {
        final List<Double> near = new ArrayList<>();
        for (double[] o : others) {
            if (o[0] == lat && o[1] == lon)
                continue;
            if (Spot.distanceMeters(lat, lon, o[0], o[1]) <= 25_000)
                near.add(o[2]);
        }
        if (near.size() < 3)
            return false;
        Collections.sort(near);
        final double median = near.get(near.size() / 2), max = near.get(near.size() - 1);
        if (inches > 2.0 && inches > 4 * max)
            return true;
        return median > 0.5 && inches < median / 5;
    }

    // ---- helpers ---------------------------------------------------------------------------

    private static double first(String[] f, List<Integer> cols) {
        for (int c : cols)
            if (c < f.length && !f[c].isEmpty())
                try {
                    final double v = Double.parseDouble(f[c]);
                    if (!Double.isNaN(v) && !Double.isInfinite(v) && v >= 0 && v < 1000)
                        return v;
                } catch (NumberFormatException e) {
                    // Not a number; try the next column.
                }
        return Double.NaN;
    }

    static void add(Map<String, List<double[]>> m, String id, long t, double v) {
        List<double[]> l = m.get(id);
        if (l == null) {
            l = new ArrayList<>();
            m.put(id, l);
        }
        l.add(new double[] { t, v });
    }

    /** Whether readings cover the stretch: none missing for longer than MAX_GAP, ends included. */
    private static boolean covered(List<double[]> series, long startMs, long endMs) {
        final List<Long> t = new ArrayList<>();
        for (double[] r : series)
            if (r[0] > startMs - MAX_GAP && r[0] <= endMs)
                t.add((long) r[0]);
        if (t.isEmpty())
            return false;
        Collections.sort(t);
        long prev = startMs;
        for (long x : t) {
            if (x - prev > MAX_GAP)
                return false;
            prev = Math.max(prev, x);
        }
        return Math.min(endMs, System.currentTimeMillis()) - prev <= MAX_GAP;
    }

    /** "2026-10-11 00:00:00" or "2026-10-10 21:15", UTC, to millis; 0 when unreadable. */
    static long utc(String s) {
        if (s == null || s.length() < 16)
            return 0;
        try {
            final Calendar c = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
            c.clear();
            c.set(Integer.parseInt(s.substring(0, 4)), Integer.parseInt(s.substring(5, 7)) - 1,
                    Integer.parseInt(s.substring(8, 10)), Integer.parseInt(s.substring(11, 13)),
                    Integer.parseInt(s.substring(14, 16)));
            return c.getTimeInMillis();
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** "2026-10-10T21:00Z" for a request. */
    public static String z(long millis) {
        final java.text.SimpleDateFormat f = new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm'Z'", Locale.US);
        f.setTimeZone(TimeZone.getTimeZone("UTC"));
        return f.format(new java.util.Date(millis));
    }

    static boolean placeable(double lat, double lon) {
        return !Double.isNaN(lat) && !Double.isNaN(lon) && !Double.isInfinite(lat) && !Double.isInfinite(lon)
                && Math.abs(lat) <= 90 && Math.abs(lon) <= 180 && !(lat == 0 && lon == 0);
    }

    private static String join(List<String> ids) {
        final StringBuilder b = new StringBuilder();
        for (String id : ids)
            if (id.matches(ID))
                b.append(b.length() > 0 ? "," : "").append(id);
        return b.toString();
    }

    /** The lines after the header, without building one array of the whole body. */
    private static List<String> lines(String body) {
        final List<String> out = new ArrayList<>();
        int from = body.indexOf('\n');
        if (from < 0)
            return out;
        from++;
        while (from < body.length()) {
            int to = body.indexOf('\n', from);
            if (to < 0)
                to = body.length();
            final String line = body.substring(from, to).trim();
            if (!line.isEmpty())
                out.add(line);
            from = to + 1;
        }
        return out;
    }

    private Iem() {
    }
}
