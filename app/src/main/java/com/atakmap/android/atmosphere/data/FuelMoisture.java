package com.atakmap.android.atmosphere.data;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

/**
 * The field samples of fuel moisture: the US Forest Service's National Fuel Moisture
 * Database, served per state by San Jose State University's fuel moisture portal
 * ({@code www.nfmdb.org}). Every sampling site in a state with its position, each fuel
 * sampled there, and the last sample's date and value, in one request.
 *
 * <p>Live fuel moisture is water as a percent of the plant's dry weight, so it runs
 * past 100. The thresholds are the ones Southern California's agencies publish for
 * chaparral: 60 percent and under is critical, 61 to 79 high, 80 to 99 moderate, 100
 * and over low. Dead fuels (the 1 to 1000 hour classes and duff) run on another scale
 * and are not colored by them.
 *
 * <p>The Forest Service's own successor (FEMS) says its public API is "details to
 * come" (checked 2026-10-11), so the portal is the way in for now.
 */
public final class FuelMoisture {

    public static final String HOST = "www.nfmdb.org";

    public static String url(String state) {
        return "https://" + HOST + "/api/FMR/site_location_v2?input=" + state;
    }

    /** The live fuel thresholds, percent, and their colors: critical, high, moderate, low. */
    public static final int[] THRESHOLDS = { 60, 79, 99 };
    public static final int[] COLORS = { 0xFFE53935, 0xFFFF9800, 0xFFFFEB3B, 0xFF43A047 };
    public static final String[] LEVELS = { "Critical", "High", "Moderate", "Low" };
    public static final int DEAD_COLOR = 0xFFB0B0B0;

    public static int level(double pct) {
        if (pct <= THRESHOLDS[0])
            return 0;
        if (pct <= THRESHOLDS[1])
            return 1;
        if (pct <= THRESHOLDS[2])
            return 2;
        return 3;
    }

    public static boolean isDead(String fuel) {
        final String f = fuel == null ? "" : fuel.trim();
        return f.endsWith("-Hour") || f.equalsIgnoreCase("Duff");
    }

    /** "Manzanita, Greenleaf" reads "Manzanita" in a pill; the whole name is in the details. */
    public static String shortFuel(String fuel) {
        final int c = fuel.indexOf(',');
        return (c > 0 ? fuel.substring(0, c) : fuel).trim();
    }

    public static final class Sample {
        public final String fuel;
        public final double pct;
        public final long sampledAt;
        public final int firstYear, years;

        Sample(String fuel, double pct, long sampledAt, int firstYear, int years) {
            this.fuel = fuel;
            this.pct = pct;
            this.sampledAt = sampledAt;
            this.firstYear = firstYear;
            this.years = years;
        }

        public boolean dead() {
            return isDead(fuel);
        }
    }

    public static final class Site {
        public final String name;
        public final double lat, lon;
        /** Newest first. */
        public final List<Sample> samples;

        Site(String name, double lat, double lon, List<Sample> samples) {
            this.name = name;
            this.lat = lat;
            this.lon = lon;
            this.samples = samples;
        }

        /** The newest sample of the kind: 0 live, 1 dead, 2 any; null when there is none. */
        public Sample newest(int kind) {
            for (Sample s : samples)
                if (kind == 2 || (kind == 1) == s.dead())
                    return s;
            return null;
        }
    }

    private static final String DATE = "EEE, dd MMM yyyy HH:mm:ss zzz";

    static long date(String s) {
        if (s == null || s.isEmpty())
            return 0;
        final SimpleDateFormat f = new SimpleDateFormat(DATE, Locale.US);
        f.setTimeZone(TimeZone.getTimeZone("GMT"));
        try {
            return f.parse(s).getTime();
        } catch (ParseException e) {
            return 0;
        }
    }

    public static List<Site> parse(String body) {
        final List<Site> out = new ArrayList<>();
        if (body == null || body.isEmpty())
            return out;
        try {
            final JSONArray sites = new JSONObject(body).optJSONArray("stationsData");
            if (sites == null)
                return out;
            for (int i = 0; i < sites.length(); i++) {
                final JSONObject s = sites.optJSONObject(i);
                if (s == null)
                    continue;
                final JSONObject c = s.optJSONObject("coordinates");
                if (c == null)
                    continue;
                final double lat = c.optDouble("lat", Double.NaN);
                final double lon = c.optDouble("lng", Double.NaN);
                if (Double.isNaN(lat) || Double.isNaN(lon) || (lat == 0 && lon == 0))
                    continue;
                final String name = s.optString("stationName", "").trim();
                final List<Sample> samples = new ArrayList<>();
                final JSONArray fd = s.optJSONArray("fuelData");
                for (int j = 0; fd != null && j < fd.length(); j++) {
                    final JSONObject f = fd.optJSONObject(j);
                    if (f == null)
                        continue;
                    final String fuel = f.optString("fuelType", "").trim();
                    final double pct = f.optDouble("lastSampledValue", Double.NaN);
                    final long at = date(f.optString("lastSampledDate", ""));
                    if (fuel.isEmpty() || Double.isNaN(pct) || at == 0)
                        continue;
                    final JSONArray ys = f.optJSONArray("yearsSampled");
                    int first = 0;
                    for (int k = 0; ys != null && k < ys.length(); k++) {
                        final int y = ys.optInt(k, 0);
                        if (y > 0 && (first == 0 || y < first))
                            first = y;
                    }
                    samples.add(new Sample(fuel, pct, at, first, ys == null ? 0 : ys.length()));
                }
                if (samples.isEmpty())
                    continue;
                Collections.sort(samples, new Comparator<Sample>() {
                    @Override
                    public int compare(Sample a, Sample b) {
                        return Long.compare(b.sampledAt, a.sampledAt);
                    }
                });
                out.add(new Site(name, lat, lon, samples));
            }
        } catch (Exception e) {
            // A body that is not the portal's answer draws nothing.
        }
        return out;
    }

    private FuelMoisture() {
    }
}
