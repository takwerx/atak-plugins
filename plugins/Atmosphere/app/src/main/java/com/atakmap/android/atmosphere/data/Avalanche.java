package com.atakmap.android.atmosphere.data;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Avalanche forecast zones and today's danger, from avalanche.org's public map
 * layer: every US avalanche center's zones in one GeoJSON (84 zones, 284 KB,
 * 2026-09-26), each carrying the center, its danger rating and level, the travel
 * advice that goes with it, the forecast link and whether the center is off
 * season. Keyless. The zone's own color is used on the map; the North American
 * Public Avalanche Danger Scale gives the legend.
 *
 * <p>No Android types; tested.
 */
public final class Avalanche {

    public static final String HOST = "api.avalanche.org";
    public static final String URL = "https://" + HOST + "/v2/public/products/map-layer";

    /** The danger scale: 1 Low ... 5 Extreme, the colors every center prints. */
    public static final String[] LEVEL_LABELS = { "No rating", "Low", "Moderate", "Considerable",
            "High", "Extreme" };
    public static final int[] LEVEL_COLORS = { 0xFF888888, 0xFF50B848, 0xFFFFF200, 0xFFF7931E,
            0xFFED1C24, 0xFF231F20 };

    public static String levelLabel(int level) {
        return level >= 1 && level <= 5 ? LEVEL_LABELS[level] : LEVEL_LABELS[0];
    }

    public static int levelColor(int level) {
        return level >= 1 && level <= 5 ? LEVEL_COLORS[level] : LEVEL_COLORS[0];
    }

    public static final class Zone {
        public final long id;
        public final String name, center, centerLink, link, state, danger, travelAdvice;
        /** 1-5, or -1 for no rating. */
        public final int dangerLevel;
        /** The feed's own fill color, ARGB. */
        public final int color;
        /** Start and end of the rating's validity as the feed writes them, or empty. */
        public final String startDate, endDate;
        public final boolean offSeason;
        /** An avalanche warning product in effect, or null. */
        public final String warning;
        public final JSONObject geometry;

        Zone(long id, JSONObject p, JSONObject geometry) {
            this.id = id;
            name = p.optString("name", "");
            center = p.optString("center", "");
            centerLink = p.optString("center_link", "");
            link = p.optString("link", "");
            state = p.optString("state", "");
            danger = p.optString("danger", "");
            travelAdvice = p.optString("travel_advice", "");
            dangerLevel = p.optInt("danger_level", -1);
            color = parseColor(p.optString("color", ""), levelColor(dangerLevel));
            startDate = p.isNull("start_date") ? "" : p.optString("start_date", "");
            endDate = p.isNull("end_date") ? "" : p.optString("end_date", "");
            offSeason = p.optBoolean("off_season", false);
            final JSONObject w = p.optJSONObject("warning");
            final JSONObject product = w == null ? null : w.optJSONObject("product");
            warning = product == null ? null
                    : product.optString("title", product.optString("product_type", "In effect"));
            this.geometry = geometry;
        }

        /** "Considerable (3 of 5)", "No rating, off season". */
        public String dangerLine() {
            if (dangerLevel >= 1 && dangerLevel <= 5)
                return levelLabel(dangerLevel) + " (" + dangerLevel + " of 5)";
            return offSeason ? "No rating, off season" : "No rating";
        }
    }

    public static List<Zone> parse(String body) {
        final List<Zone> out = new ArrayList<>();
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
                out.add(new Zone(f.optLong("id", i), p, g));
            }
        } catch (Exception e) {
            return out;
        }
        return out;
    }

    /** "#rrggbb" to opaque ARGB, or the fallback. */
    public static int parseColor(String hex, int fallback) {
        if (hex == null)
            return fallback;
        final String h = hex.trim();
        if (!h.matches("#[0-9A-Fa-f]{6}"))
            return fallback;
        return 0xFF000000 | Integer.parseInt(h.substring(1), 16);
    }

    /** "Apr 13, 2:00 PM" from "2026-04-13T14:00:00", as the center's local time. */
    public static String when(String iso) {
        if (iso == null || iso.length() < 16)
            return iso == null ? "" : iso;
        try {
            final int mo = Integer.parseInt(iso.substring(5, 7));
            final int d = Integer.parseInt(iso.substring(8, 10));
            final int h = Integer.parseInt(iso.substring(11, 13));
            final int m = Integer.parseInt(iso.substring(14, 16));
            final String[] months = { "Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug",
                    "Sep", "Oct", "Nov", "Dec" };
            final int h12 = h % 12 == 0 ? 12 : h % 12;
            return String.format(Locale.US, "%s %d, %d:%02d %s", months[mo - 1], d, h12, m,
                    h < 12 ? "AM" : "PM");
        } catch (Exception e) {
            return iso;
        }
    }

    private Avalanche() {
    }
}
