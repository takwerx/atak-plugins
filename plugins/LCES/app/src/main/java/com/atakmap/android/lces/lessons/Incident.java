package com.atakmap.android.lces.lessons;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * One Lessons Learned incident, as the catalog carries it.
 *
 * <p>Built by {@code tools/lessons_build.py} from the LLC's Spatial Incident Review
 * Database. The text fields are the LLC's own words; nothing here rewrites them.
 */
public final class Incident {

    /** The five kinds LCES draws, in the order the catalog builder decides them. */
    public enum Type {
        FATALITY("fatality", "Fatality", 0xFFFF4D4D),
        BURNOVER("burnover", "Burnover", 0xFFFF8A00),
        ENTRAPMENT("entrapment", "Entrapment", 0xFFFFD43B),
        INJURY("injury", "Injury", 0xFFC77DFF),
        NEAR_MISS("near miss", "Near miss", 0xFF4FC3F7);

        /** As the catalog writes it. */
        public final String key;
        /** As the pane writes it. */
        public final String label;
        /** The one color the map, the list and the key all use. */
        public final int color;

        Type(String key, String label, int color) {
            this.key = key;
            this.label = label;
            this.color = color;
        }

        static Type of(String key) {
            for (Type t : values())
                if (t.key.equals(key))
                    return t;
            return null;
        }
    }

    public final String id;
    public final String name;
    public final String fire;
    /** YYYY-MM-DD, the date the report gives. */
    public final String date;
    /** HHMM local, or empty. */
    public final String time;
    public final String state;
    public final Type type;
    /** The LLC's own category: Entrapment/Burnover, Medical, Hit By ... */
    public final String category;
    public final int fatalities;
    public final int injuries;
    public final List<String> entrapment;
    public final List<String> behavior;
    public final String activity;
    public final String summary;
    public final String quote;
    public final double lat;
    public final double lon;
    /** The full report on lessons.wildfire.gov, https only, or empty. */
    public final String report;

    private Incident(JSONObject o) {
        id = o.optString("id", "");
        name = o.optString("name", "");
        fire = o.optString("fire", "");
        date = o.optString("date", "");
        time = o.optString("time", "");
        state = o.optString("state", "");
        type = Type.of(o.optString("type", ""));
        category = o.optString("category", "");
        fatalities = o.optInt("fatalities", 0);
        injuries = o.optInt("injuries", 0);
        entrapment = strings(o.optJSONArray("entrapment"));
        behavior = strings(o.optJSONArray("behavior"));
        activity = o.optString("activity", "");
        summary = o.optString("summary", "");
        quote = o.optString("quote", "");
        lat = o.optDouble("lat", Double.NaN);
        lon = o.optDouble("lon", Double.NaN);
        report = httpsOnly(o.optString("report", ""));
    }

    /**
     * A row from the catalog, or null when it is not one we can draw: no id, no type
     * we know, or no point on the earth. A catalog is data from a server; a bad row is
     * skipped, never guessed at.
     */
    static Incident parse(JSONObject o) {
        if (o == null)
            return null;
        final Incident i = new Incident(o);
        if (i.id.isEmpty() || i.id.length() > 64 || i.type == null)
            return null;
        if (Double.isNaN(i.lat) || Double.isNaN(i.lon) || Math.abs(i.lat) > 90
                || Math.abs(i.lon) > 180 || (i.lat == 0 && i.lon == 0))
            return null;
        return i;
    }

    /**
     * The report link is opened in the device's browser, so only a web address is
     * passed on. Every link in the 2026-11-03 catalog is already https; an http one
     * is upgraded rather than opened in the clear.
     */
    static String httpsOnly(String url) {
        if (url == null)
            return "";
        final String u = url.trim();
        if (u.regionMatches(true, 0, "https://", 0, 8) && u.length() > 8)
            return u;
        if (u.regionMatches(true, 0, "http://", 0, 7) && u.length() > 7)
            return "https://" + u.substring(7);
        return "";
    }

    private static List<String> strings(JSONArray a) {
        if (a == null || a.length() == 0)
            return Collections.emptyList();
        final List<String> out = new ArrayList<>(a.length());
        for (int k = 0; k < a.length(); k++) {
            final String s = a.optString(k, "");
            if (!s.isEmpty())
                out.add(s);
        }
        return Collections.unmodifiableList(out);
    }

    /** "July 10, 2001" from the catalog's date. */
    public String longDate() {
        final String[] months = {"January", "February", "March", "April", "May", "June",
                "July", "August", "September", "October", "November", "December"};
        try {
            final int y = Integer.parseInt(date.substring(0, 4));
            final int m = Integer.parseInt(date.substring(5, 7));
            final int d = Integer.parseInt(date.substring(8, 10));
            return months[m - 1] + " " + d + ", " + y;
        } catch (RuntimeException e) {
            return date;
        }
    }

    /** "5:24 pm" from HHMM, or empty. */
    public String clock() {
        if (time.length() != 4)
            return "";
        try {
            final int h = Integer.parseInt(time.substring(0, 2));
            final int m = Integer.parseInt(time.substring(2));
            final int h12 = h % 12 == 0 ? 12 : h % 12;
            return h12 + ":" + (m < 10 ? "0" : "") + m + (h < 12 ? " am" : " pm");
        } catch (RuntimeException e) {
            return "";
        }
    }

    public String year() {
        return date.length() >= 4 ? date.substring(0, 4) : "";
    }
}
