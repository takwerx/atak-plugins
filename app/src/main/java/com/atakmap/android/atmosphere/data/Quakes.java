package com.atakmap.android.atmosphere.data;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * USGS earthquakes: the summary feeds the Earthquake Hazards Program publishes
 * every minute (every quake of the past day or week, or those of M2.5 and up for
 * the past month), and, for the larger ones, the ShakeMap's intensity contours.
 *
 * <p>Drawn the way USGS's own map draws them: a circle sized by magnitude, colored
 * by age (past hour red, past day orange, past week yellow, older white); the
 * contours in the ShakeMap's intensity colors, with the Roman numeral.
 *
 * <p>No Android types.
 */
public final class Quakes {

    public static final String HOST = "earthquake.usgs.gov";
    private static final String FEEDS = "https://" + HOST + "/earthquakes/feed/v1.0/summary/";

    /** The feed for the day row's pick, 1 to 3: past day, past week, past month (M2.5+). */
    public static String url(int window) {
        switch (window) {
            case 1:
                return FEEDS + "all_day.geojson";
            case 3:
                return FEEDS + "2.5_month.geojson";
            default:
                return FEEDS + "all_week.geojson";
        }
    }

    public static final String[] WINDOWS = { "Past day", "Past week", "Past month" };
    public static final String[] FLOORS = { "All", "M1+", "M2.5+", "M4.5+" };
    public static final double[] FLOOR_MAG = { -10d, 1d, 2.5d, 4.5d };

    /** USGS's age colors: past hour, past day, past week, older. */
    public static final String[] AGES = { "Past hour", "Past day", "Past week", "Older" };
    public static final int[] AGE_COLORS = { 0xFFFF0000, 0xFFFFA500, 0xFFFFFF00, 0xFFFFFFFF };

    public static int age(long time, long now) {
        final long a = now - time;
        if (a <= 60L * 60 * 1000)
            return 0;
        if (a <= 24L * 60 * 60 * 1000)
            return 1;
        if (a <= 7L * 24 * 60 * 60 * 1000)
            return 2;
        return 3;
    }

    /** The ShakeMap intensity colors, I to X+, as USGS prints them. */
    private static final int[] MMI_COLORS = { 0xFFFFFFFF, 0xFFBFCCFF, 0xFFBFCCFF, 0xFFA0E6FF,
            0xFF80FFFF, 0xFF7AFF93, 0xFFFFFF00, 0xFFFFC800, 0xFFFF9100, 0xFFFF0000 };
    private static final String[] ROMAN = { "I", "II", "III", "IV", "V", "VI", "VII", "VIII",
            "IX", "X" };

    public static int mmiColor(double mmi) {
        final int i = Math.max(1, Math.min(10, (int) Math.round(mmi)));
        return MMI_COLORS[i - 1];
    }

    public static String roman(double mmi) {
        final int i = Math.max(1, Math.min(10, (int) Math.round(mmi)));
        return ROMAN[i - 1] + (mmi >= 10.5 ? "+" : "");
    }

    /** What an intensity feels like, USGS's words. */
    public static String shaking(double mmi) {
        final int i = Math.max(1, Math.min(10, (int) Math.round(mmi)));
        switch (i) {
            case 1:
                return "Not felt";
            case 2:
            case 3:
                return "Weak";
            case 4:
                return "Light";
            case 5:
                return "Moderate";
            case 6:
                return "Strong";
            case 7:
                return "Very strong";
            case 8:
                return "Severe";
            case 9:
                return "Violent";
            default:
                return "Extreme";
        }
    }

    public static final class Quake {
        public final String id, place, magType, status, alert, url, detail;
        public final double mag, depthKm, lat, lon, cdi, mmi;
        public final long time;
        public final int felt, tsunami;
        public final boolean shakeMap;

        Quake(String id, String place, String magType, String status, String alert, String url,
                String detail, double mag, double depthKm, double lat, double lon, double cdi,
                double mmi, long time, int felt, int tsunami, boolean shakeMap) {
            this.id = id;
            this.place = place;
            this.magType = magType;
            this.status = status;
            this.alert = alert;
            this.url = url;
            this.detail = detail;
            this.mag = mag;
            this.depthKm = depthKm;
            this.lat = lat;
            this.lon = lon;
            this.cdi = cdi;
            this.mmi = mmi;
            this.time = time;
            this.felt = felt;
            this.tsunami = tsunami;
            this.shakeMap = shakeMap;
        }

        /** "M 4.5" the way USGS writes it. */
        public String magnitude() {
            return String.format(java.util.Locale.US, "M %.1f", mag);
        }
    }

    public static List<Quake> parse(String body) {
        final List<Quake> out = new ArrayList<>();
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
                final JSONArray c = g.optJSONArray("coordinates");
                if (c == null || c.length() < 2)
                    continue;
                final double lon = c.optDouble(0, Double.NaN), lat = c.optDouble(1, Double.NaN);
                final double depth = c.length() > 2 ? c.optDouble(2, Double.NaN) : Double.NaN;
                final double mag = p.optDouble("mag", Double.NaN);
                if (Double.isNaN(lat) || Double.isNaN(lon) || Double.isNaN(mag))
                    continue;
                final String type = StormReports.text(p, "type");
                if (!type.isEmpty() && !"earthquake".equals(type))
                    continue;
                final String types = "," + StormReports.text(p, "types") + ",";
                out.add(new Quake(f.optString("id", ""), StormReports.text(p, "place"),
                        StormReports.text(p, "magType"), StormReports.text(p, "status"),
                        StormReports.text(p, "alert"), StormReports.text(p, "url"),
                        StormReports.text(p, "detail"), mag, depth, lat, lon,
                        p.optDouble("cdi", Double.NaN), p.optDouble("mmi", Double.NaN),
                        p.optLong("time", 0), p.optInt("felt", 0), p.optInt("tsunami", 0),
                        types.contains(",shakemap,")));
            }
        } catch (Exception e) {
            return out;
        }
        return out;
    }

    /**
     * From a quake's detail document, the URL of its ShakeMap's intensity contours,
     * or null when it has none. The first ShakeMap listed is the preferred one.
     */
    public static String contoursUrl(String detailBody) {
        if (detailBody == null || detailBody.isEmpty())
            return null;
        try {
            final JSONObject products = new JSONObject(detailBody).getJSONObject("properties")
                    .getJSONObject("products");
            final JSONArray sm = products.optJSONArray("shakemap");
            if (sm == null || sm.length() == 0)
                return null;
            final JSONObject contents = sm.getJSONObject(0).optJSONObject("contents");
            if (contents == null)
                return null;
            final JSONObject c = contents.optJSONObject("download/cont_mi.json");
            final String url = c == null ? null : c.optString("url", null);
            return url != null && url.startsWith("https://" + HOST + "/") ? url : null;
        } catch (Exception e) {
            return null;
        }
    }

    /** One intensity contour. */
    public static final class Contour {
        public final double mmi;
        public final JSONObject geometry;

        Contour(double mmi, JSONObject geometry) {
            this.mmi = mmi;
            this.geometry = geometry;
        }
    }

    /** The contours of a {@code cont_mi.json}, from intensity II up; I is "not felt". */
    public static List<Contour> parseContours(String body) {
        final List<Contour> out = new ArrayList<>();
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
                final double v = p.optDouble("value", Double.NaN);
                if (Double.isNaN(v) || v < 1.5)
                    continue;
                out.add(new Contour(v, g));
            }
        } catch (Exception e) {
            return out;
        }
        return out;
    }

    private Quakes() {
    }
}
