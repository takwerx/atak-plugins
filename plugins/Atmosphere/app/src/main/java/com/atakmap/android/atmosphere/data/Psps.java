package com.atakmap.android.atmosphere.data;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;

/**
 * Public Safety Power Shutoffs in California, from Cal OES: the counties a utility
 * has told Cal OES may have a shutoff, and the areas where the power has been cut
 * (or cut because a circuit upstream was, or turned back on). PG&E, SCE and SDG&E
 * report to it; PacifiCorp, Liberty and Bear Valley do not reach the public layers
 * (survey 2026-10-01, NOTES-atmosphere-psps-sources-west). Public ArcGIS items,
 * keyless, rewritten every 15 minutes even when nothing changed.
 *
 * <p>The areas view hides "Monitoring", so it stays empty until power is off; the
 * county flags are the warning before that. Freshness is the latest EditDate on the
 * county rows, which the publisher stamps every cycle: a fresh "No" can be trusted,
 * a stale one cannot.
 *
 * <p>No Android types; tested.
 */
public final class Psps {

    public static final String HOST = "services.arcgis.com";
    private static final String BASE = "https://" + HOST + "/BLN4oKB0N1YSgvY8/ArcGIS/rest/services/";
    private static final String COUNTIES = BASE + "Counties_Potentially_Impacted_by_PSPS_Events/FeatureServer/2/query";
    private static final String AREAS = BASE + "Statewide_PSPS_Current_Active_Outage_Areas_(Public)/FeatureServer/0/query";

    /** The newest stamp on the county rows and how many rows there are (58 when whole), about 700 bytes. */
    public static final String FRESHNESS_URL = COUNTIES + "?where=1%3D1&f=json&outStatistics="
            + encode("[{\"statisticType\":\"max\",\"onStatisticField\":\"EditDate\",\"outStatisticFieldName\":\"latest\"},"
                    + "{\"statisticType\":\"count\",\"onStatisticField\":\"CountyName\",\"outStatisticFieldName\":\"counties\"}]");
    /** The flagged counties' outlines, simplified to about 300 m. */
    public static final String COUNTIES_URL = COUNTIES + "?where=Impacted%3D%27Yes%27"
            + "&outFields=CountyName&returnGeometry=true&outSR=4326&geometryPrecision=4"
            + "&maxAllowableOffset=0.003&f=geojson";
    public static final String AREAS_URL = AREAS + "?where=1%3D1"
            + "&outFields=County,Status,UtilityCompany,EventName&returnGeometry=true&outSR=4326"
            + "&geometryPrecision=5&f=geojson";

    /** Past this the publisher has missed three cycles: status unknown. */
    public static final long STALE_MS = 45L * 60 * 1000;

    /** What each kind of area is called on the map and in the key, and its color. */
    public static final String POSSIBLE = "Shutoff Possible", OFF = "Power Off",
            DOWNSTREAM = "Off, Upstream Shutoff", RESTORED = "Power Restored";
    public static final String[] KINDS = { POSSIBLE, OFF, DOWNSTREAM, RESTORED };
    public static final int[] COLORS = { 0xFFFFB300, 0xFFD50000, 0xFFFF6D00, 0xFF43A047 };

    public static int color(String kind) {
        for (int i = 0; i < KINDS.length; i++)
            if (KINDS[i].equals(kind))
                return COLORS[i];
        return COLORS[1];
    }

    /** Text on the kind's tile: black on amber and orange, white on red and green. */
    public static int textColor(String kind) {
        return OFF.equals(kind) || RESTORED.equals(kind) ? 0xFFFFFFFF : 0xFF000000;
    }

    /** Cal OES's Status word in ours; null for one the public view should not carry. */
    public static String kind(String status) {
        if (status == null)
            return null;
        switch (status.trim().toLowerCase(java.util.Locale.US)) {
            case "de-energized":
                return OFF;
            case "downstream":
                return DOWNSTREAM;
            case "re-energized":
                return RESTORED;
            default:
                return null;
        }
    }

    /** The newest county stamp, UTC ms, and the number of county rows; 0 and 0 when unreadable. */
    public static long[] freshness(String body) {
        try {
            final JSONArray fs = new JSONObject(body).optJSONArray("features");
            final JSONObject a = fs == null || fs.length() == 0 ? null
                    : fs.getJSONObject(0).optJSONObject("attributes");
            if (a == null)
                return new long[] { 0, 0 };
            return new long[] { a.optLong("latest", 0), a.optLong("counties", 0) };
        } catch (Exception e) {
            return new long[] { 0, 0 };
        }
    }

    /** A flagged county or a shutoff area. */
    public static final class Area {
        public final String kind, county, utility, event, status;
        public final JSONObject geometry;

        Area(String kind, String county, String utility, String event, String status,
                JSONObject geometry) {
            this.kind = kind;
            this.county = county;
            this.utility = utility;
            this.event = event;
            this.status = status;
            this.geometry = geometry;
        }
    }

    public static List<Area> parseCounties(String body) {
        final List<Area> out = new ArrayList<>();
        for (JSONObject f : features(body)) {
            final JSONObject p = f.optJSONObject("properties");
            final JSONObject g = f.optJSONObject("geometry");
            if (p == null || g == null)
                continue;
            out.add(new Area(POSSIBLE, p.optString("CountyName", ""), "", "", "Potentially impacted", g));
        }
        return out;
    }

    public static List<Area> parseAreas(String body) {
        final List<Area> out = new ArrayList<>();
        for (JSONObject f : features(body)) {
            final JSONObject p = f.optJSONObject("properties");
            final JSONObject g = f.optJSONObject("geometry");
            if (p == null || g == null)
                continue;
            final String status = p.optString("Status", "");
            final String kind = kind(status);
            if (kind == null)
                continue;
            out.add(new Area(kind, p.optString("County", ""), p.optString("UtilityCompany", ""),
                    p.isNull("EventName") ? "" : p.optString("EventName", ""), status, g));
        }
        return out;
    }

    private static List<JSONObject> features(String body) {
        final List<JSONObject> out = new ArrayList<>();
        if (body == null || body.isEmpty())
            return out;
        try {
            final JSONArray fs = new JSONObject(body).optJSONArray("features");
            if (fs != null)
                for (int i = 0; i < fs.length(); i++) {
                    final JSONObject f = fs.optJSONObject(i);
                    if (f != null)
                        out.add(f);
                }
        } catch (Exception e) {
            return out;
        }
        return out;
    }

    private static String encode(String s) {
        try {
            return URLEncoder.encode(s, "UTF-8");
        } catch (java.io.UnsupportedEncodingException e) {
            throw new IllegalStateException(e);
        }
    }

    private Psps() {
    }
}
