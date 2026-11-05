package com.atakmap.android.firecast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.TreeSet;

/**
 * Where it is burning, without downloading a single detection.
 *
 * <p>The rest of the plugin answers "what is in front of me": it fetches for the map
 * extent, because a phone cannot hold 3,735 detections and an operator does not want
 * them. That leaves a hole an operator put a finger in straight away - a plugin that
 * only tells you about fires you already knew to look at has not helped you find one.
 *
 * <p>The fix is to ask a different question. Instead of asking the service for
 * detections, this asks it to <em>summarize</em>: group by fire, give back the peak
 * power, the count, the last time seen and a center point. The whole country comes back
 * in about 7 KB and a third of a second, because no geometry and no records travel -
 * only the totals. Tapping a row takes the map there, and the ordinary extent fetch
 * draws the real footprints on arrival.
 *
 * <p>Two grouping keys, and the difference matters. A named incident groups by
 * {@code known_incident_name}. Everything else groups by {@code feature_tracking_id},
 * GOES's own identifier for a fire it is following through time - <em>not</em> by state,
 * which was the first thing tried and was wrong in a way that would send somebody to
 * the wrong place: twelve separate fires in Washington averaged to one point between
 * them, in open country, with nothing burning there at all.
 *
 * <p>Limit worth saying out loud: this is GOES, and NIFC's GOES feed carries CONUS only.
 * VIIRS and MODIS are global but carry no incident name and no tracking id, so finding
 * fires outside the lower 48 means clustering raw detections - a later version.
 */
public class Activity {

    /** One fire, as the summary sees it. */
    public static class Fire {
        /** The incident name when the feed has one, else null. */
        public String name;
        public String state;
        /** The strongest single reading in the window, megawatts. */
        public double peakMw;
        /** How many detections the window holds for it. */
        public int hits;
        /** Newest detection, epoch ms. */
        public long last;
        /** Longest the feed has been following it in one place, hours. */
        public double durationHours;
        public double lat, lon;

        /** What the row reads: the name, or where it is when nobody has named it. */
        public String title() {
            if (name != null && !name.trim().isEmpty())
                return name.trim();
            return state != null ? "Unnamed, " + state : "Unnamed";
        }

        /** The peak heat, named: "peak 16 MW" rather than a bare number nobody can place. */
        public String powerText() {
            return "peak " + (peakMw >= 100 ? String.format(Locale.US, "%.0f MW", peakMw)
                    : String.format(Locale.US, "%.1f MW", peakMw));
        }

        /**
         * Whether this looks like a fire rather than a moment.
         *
         * <p>A fire the feed has named, or has watched for half an hour, or has caught
         * more than once, is something that is happening. A single hot scan with no name
         * is as likely to be a burn pile, a flare or a harvest fire, and over a three-day
         * window there are a great many of those: the strongest reading in the whole of
         * the lower 48 on the first run of this panel was 866 MW, seen once, in Arkansas
         * in September - almost certainly agricultural, and it was sitting at the top of
         * a list whose entire job is to tell an operator where to go.
         *
         * <p>So it is not dropped - it is real data and it might be the start of
         * something - it just does not outrank the fires somebody is already working.
         */
        public boolean established() {
            return (name != null && !name.trim().isEmpty()) || durationHours >= 0.5d || hits > 1;
        }
    }

    private static final String STATS =
            "[{\"statisticType\":\"max\",\"onStatisticField\":\"total_frp\",\"outStatisticFieldName\":\"peak\"},"
            + "{\"statisticType\":\"count\",\"onStatisticField\":\"OBJECTID\",\"outStatisticFieldName\":\"hits\"},"
            + "{\"statisticType\":\"max\",\"onStatisticField\":\"acq_date_time\",\"outStatisticFieldName\":\"last\"},"
            + "{\"statisticType\":\"max\",\"onStatisticField\":\"feature_detection_duration\",\"outStatisticFieldName\":\"dur\"},"
            + "{\"statisticType\":\"avg\",\"onStatisticField\":\"latitude\",\"outStatisticFieldName\":\"lat\"},"
            + "{\"statisticType\":\"avg\",\"onStatisticField\":\"longitude\",\"outStatisticFieldName\":\"lon\"}]";

    /** Every state the feed has anything in, for the filter. Worker thread. */
    public static List<String> states(long sinceMillis) throws Exception {
        final String url = Sources.GOES + "/query?where=" + Esri.enc(window(sinceMillis) + " AND state IS NOT NULL")
                + "&outFields=state&returnDistinctValues=true&returnGeometry=false&f=json";
        final JSONObject d = new JSONObject(Esri.get(url));
        if (d.has("error"))
            throw new IllegalStateException(d.getJSONObject("error").optString("message"));
        // A sorted set: the service returns them in no order, and a jumbled state list is
        // a control an operator has to read rather than scan.
        final TreeSet<String> out = new TreeSet<>();
        final JSONArray feats = d.optJSONArray("features");
        for (int i = 0; feats != null && i < feats.length(); i++) {
            final String s = Fields.text(feats.getJSONObject(i).optJSONObject("attributes"), "state");
            if (s != null)
                out.add(s);
        }
        return new ArrayList<>(out);
    }

    /**
     * Active fires in the window, strongest first.
     *
     * @param state two-letter state to limit to, or null for everywhere the feed covers
     * @param box   the area to limit to, or null for everywhere; the service does the
     *              clipping, so a box costs nothing more than the whole country
     */
    public static List<Fire> fires(long sinceMillis, String state, Esri.Box box) throws Exception {
        final String scope = window(sinceMillis)
                + (state == null ? "" : " AND state = " + sql(state));
        final List<Fire> out = new ArrayList<>();
        // Named incidents group by their name; a fire the feed has not tied to an incident
        // groups by the id GOES follows it with.
        out.addAll(grouped(scope + " AND known_incident_name IS NOT NULL", "known_incident_name,state", box));
        out.addAll(grouped(scope + " AND known_incident_name IS NULL", "feature_tracking_id,state", box));
        // Established fires first, then by power. Sorting on power alone put a single
        // hot scan above every named incident in the country.
        Collections.sort(out, new Comparator<Fire>() {
            @Override
            public int compare(Fire a, Fire b) {
                if (a.established() != b.established())
                    return a.established() ? -1 : 1;
                return Double.compare(b.peakMw, a.peakMw);
            }
        });
        return out;
    }

    private static List<Fire> grouped(String where, String groupBy, Esri.Box box) throws Exception {
        final String url = Sources.GOES + "/query?where=" + Esri.enc(where)
                + "&outStatistics=" + Esri.enc(STATS)
                + "&groupByFieldsForStatistics=" + Esri.enc(groupBy)
                + "&orderByFields=" + Esri.enc("peak DESC")
                + (box == null ? "" : "&geometry=" + Esri.enc(String.format(java.util.Locale.US,
                        "%.5f,%.5f,%.5f,%.5f", box.west, box.south, box.east, box.north))
                        + "&geometryType=esriGeometryEnvelope&inSR=4326&spatialRel=esriSpatialRelIntersects")
                + "&returnGeometry=false&f=json";
        final JSONObject d = new JSONObject(Esri.get(url));
        if (d.has("error"))
            throw new IllegalStateException(d.getJSONObject("error").optString("message"));
        final List<Fire> out = new ArrayList<>();
        final JSONArray feats = d.optJSONArray("features");
        for (int i = 0; feats != null && i < feats.length(); i++) {
            final JSONObject a = feats.getJSONObject(i).optJSONObject("attributes");
            if (a == null)
                continue;
            final Double lat = Fields.number(a, "lat"), lon = Fields.number(a, "lon");
            // Without a position the row cannot do the one thing it is for.
            if (lat == null || lon == null)
                continue;
            final Fire f = new Fire();
            f.name = Fields.text(a, "known_incident_name");
            f.state = Fields.text(a, "state");
            final Double peak = Fields.number(a, "peak");
            f.peakMw = peak == null ? 0d : peak;
            final Integer hits = Fields.integer(a, "hits");
            f.hits = hits == null ? 0 : hits;
            f.last = Fields.epochMillis(a, "last");
            final Double dur = Fields.number(a, "dur");
            f.durationHours = dur == null ? 0d : dur;
            f.lat = lat;
            f.lon = lon;
            out.add(f);
        }
        return out;
    }

    private static String window(long sinceMillis) {
        return "acq_date_time >= timestamp '" + Sources.utc(sinceMillis) + "'";
    }

    private static String sql(String s) {
        return "'" + s.replace("'", "''") + "'";
    }
}
