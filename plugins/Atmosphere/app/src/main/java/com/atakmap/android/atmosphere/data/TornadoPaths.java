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
 * NWS Damage Assessment Toolkit: the tracks survey teams walked after a tornado,
 * each a line from where the damage began to where it ended, with the EF rating,
 * the path's length and width, the peak wind the damage implies, the injuries and
 * deaths, and the surveyor's words. Published a day or more after the storm, as
 * each office finishes and checks its survey; preliminary until then.
 *
 * <p>The hosted layer takes a date in SQL form ({@code DATE 'yyyy-MM-dd'}); a bare
 * epoch or a quoted string fails the query (checked 2026-10-11). Colors are the
 * DAT viewer's own for its damage swaths, EF0 aqua to EF5 purple, because its lines
 * are plain black there and a black line on ATAK's dark map is nothing.
 *
 * <p>No Android types.
 */
public final class TornadoPaths {

    public static final String HOST = "services.dat.noaa.gov";
    private static final String QUERY = "https://" + HOST
            + "/arcgis/rest/services/nws_damageassessmenttoolkit/DamageViewer/FeatureServer/1/query"
            + "?outFields=wfo,event_id,stormdate,starttime,endtime,length,width,injuries,fatalities,efscale,efnum,maxwind,comments"
            + "&outSR=4326&f=geojson&where=stormdate%20%3E%3D%20DATE%20%27";

    /** Every surveyed path since a day, {@code yyyy-MM-dd} in UTC, from a row offset. */
    public static String url(String sinceYmd, int offset) {
        return QUERY + sinceYmd + "%27" + (offset > 0 ? "&resultOffset=" + offset : "");
    }

    public static String ymd(long millis) {
        final SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
        f.setTimeZone(TimeZone.getTimeZone("UTC"));
        return f.format(new Date(millis));
    }

    /** The ratings the survey can give, in the DAT viewer's swath colors. */
    public static final String[] RATINGS = { "EF0", "EF1", "EF2", "EF3", "EF4", "EF5", "EFU",
            "TSTM/Wind" };
    public static final int[] COLORS = { 0xFF00FFC5, 0xFF55FF00, 0xFFFFFF00, 0xFFE69800,
            0xFFE60000, 0xFFA80084, 0xFF9C9C9C, 0xFFD3FFBE };
    /** What each rating means on the key. */
    public static final String[] MEANINGS = { "EF0: 65 to 85 mph", "EF1: 86 to 110 mph",
            "EF2: 111 to 135 mph", "EF3: 136 to 165 mph", "EF4: 166 to 200 mph",
            "EF5: over 200 mph", "EFU: a tornado, rating unknown (no damage to rate)",
            "Thunderstorm wind, not a tornado" };

    public static int color(String ef) {
        final String e = ef == null ? "" : ef.trim();
        for (int i = 0; i < RATINGS.length; i++)
            if (RATINGS[i].equalsIgnoreCase(e))
                return COLORS[i];
        if (e.startsWith("EF3"))
            return COLORS[3];
        return 0xFFBEE8FF;
    }

    /** The rating as the pill prints it: "EF2", "EFU", "Wind". */
    public static String rating(String ef) {
        final String e = ef == null ? "" : ef.trim();
        if (e.isEmpty() || e.equalsIgnoreCase("N/A") || e.equalsIgnoreCase("UNKNOWN"))
            return "Unrated";
        if (e.equalsIgnoreCase("TSTM/Wind"))
            return "Wind";
        return e.toUpperCase(Locale.US);
    }

    /** One surveyed track. */
    public static final class Track {
        public final String event, office, rating, comments;
        public final long stormDate, start, end;
        public final double lengthMi, widthYd;
        public final int injuries, fatalities, maxWind, efNum;
        public final JSONObject geometry;

        Track(String event, String office, String rating, String comments, long stormDate,
                long start, long end, double lengthMi, double widthYd, int injuries,
                int fatalities, int maxWind, int efNum, JSONObject geometry) {
            this.event = event;
            this.office = office;
            this.rating = rating;
            this.comments = comments;
            this.stormDate = stormDate;
            this.start = start;
            this.end = end;
            this.lengthMi = lengthMi;
            this.widthYd = widthYd;
            this.injuries = injuries;
            this.fatalities = fatalities;
            this.maxWind = maxWind;
            this.efNum = efNum;
            this.geometry = geometry;
        }
    }

    public static List<Track> parse(String body) {
        final List<Track> out = new ArrayList<>();
        if (body == null || body.isEmpty())
            return out;
        try {
            final JSONArray fs = new JSONObject(body).optJSONArray("features");
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
                // Offices name an event "Pawnee_County_E"; that is a file name, not a place.
                out.add(new Track(StormReports.text(p, "event_id").replace('_', ' ').trim(),
                        StormReports.text(p, "wfo"),
                        StormReports.text(p, "efscale"), StormReports.text(p, "comments"),
                        p.optLong("stormdate", 0), p.optLong("starttime", 0),
                        p.optLong("endtime", 0), p.optDouble("length", Double.NaN),
                        p.optDouble("width", Double.NaN), p.optInt("injuries", 0),
                        p.optInt("fatalities", 0), p.optInt("maxwind", -99),
                        p.optInt("efnum", -1), g));
            }
        } catch (Exception e) {
            return out;
        }
        return out;
    }

    private TornadoPaths() {
    }
}
