package com.atakmap.android.atmosphere.data;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

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
