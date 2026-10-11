package com.atakmap.android.atmosphere.data;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * Which fire weather zones are under a Red Flag Warning or a Fire Weather Watch
 * right now, for shading the zones and filtering the zone list.
 *
 * <p>Alerts are IPAWS's, and this is the one exception, the operator's own
 * (2026-09-28): only these two fire products, only to mark fire zones. One
 * request for the country, {@code /alerts/active} with both events; each alert
 * names its zones in {@code geocode.UGC} as the fire zone numbers themselves
 * ({@code NVZ420}), so no shapes come down with it. Measured 2026-09-28: 208
 * bytes with none in effect.
 *
 * <p>The colors are the ones NWS publishes for the two products
 * (weather.gov/help-map), never a color chosen by severity.
 *
 * <p>No Android types; tested.
 */
public final class FireAlerts {

    public static final String HOST = Cwf.HOST;
    public static final String URL = "https://" + HOST
            + "/alerts/active?event=Red%20Flag%20Warning,Fire%20Weather%20Watch";

    public static final String RED_FLAG = "Red Flag Warning";
    public static final String WATCH = "Fire Weather Watch";
    /** NWS's Red Flag Warning color, deep pink. */
    public static final int RED_FLAG_COLOR = 0xFFFF1493;
    /** NWS's Fire Weather Watch color, navajo white. */
    public static final int WATCH_COLOR = 0xFFFFDEAD;

    /** What a zone is under. */
    public static final class Status {
        /** {@link #RED_FLAG} or {@link #WATCH}. */
        public final String event;
        /** When it ends, epoch ms; 0 when the alert did not say. */
        public final long ends;
        /** "NWS Reno NV". */
        public final String sender;

        Status(String event, long ends, String sender) {
            this.event = event;
            this.ends = ends;
            this.sender = sender;
        }

        public boolean isRedFlag() {
            return RED_FLAG.equals(event);
        }

        public int color() {
            return isRedFlag() ? RED_FLAG_COLOR : WATCH_COLOR;
        }
    }

    /**
     * The zones in the answer, by {@code NVZ420}. A zone under both keeps the
     * warning; a cancelled or already-ended alert marks nothing.
     */
    public static Map<String, Status> parse(String body, long now) {
        final Map<String, Status> out = new HashMap<>();
        if (body == null || body.isEmpty())
            return out;
        try {
            final JSONArray feats = new JSONObject(body).optJSONArray("features");
            if (feats == null)
                return out;
            for (int i = 0; i < feats.length(); i++) {
                final JSONObject f = feats.optJSONObject(i);
                final JSONObject p = f == null ? null : f.optJSONObject("properties");
                if (p == null)
                    continue;
                final String event = p.optString("event", "");
                if (!RED_FLAG.equals(event) && !WATCH.equals(event))
                    continue;
                if ("Cancel".equalsIgnoreCase(p.optString("messageType", "")))
                    continue;
                long ends = IsoTime.parse(p.optString("ends", ""));
                if (ends <= 0)
                    ends = IsoTime.parse(p.optString("expires", ""));
                if (ends > 0 && ends < now)
                    continue;
                final JSONObject geo = p.optJSONObject("geocode");
                final JSONArray ugc = geo == null ? null : geo.optJSONArray("UGC");
                if (ugc == null)
                    continue;
                final Status s = new Status(event, ends, p.optString("senderName", ""));
                for (int k = 0; k < ugc.length(); k++) {
                    final String z = ugc.optString(k, "");
                    if (!z.matches("[A-Z]{2}Z[0-9]{3}"))
                        continue;
                    final Status had = out.get(z);
                    if (had == null || (!had.isRedFlag() && s.isRedFlag())
                            || (had.event.equals(s.event) && s.ends > had.ends))
                        out.put(z, s);
                }
            }
        } catch (Exception e) {
            return out;
        }
        return out;
    }

    // ---- one answer shared by the page and the map layer ------------------------------

    private static Map<String, Status> latest = Collections.emptyMap();
    private static long fetchedAt;

    /** The last answer, if it is younger than {@code maxAgeMs}; null otherwise. */
    public static synchronized Map<String, Status> cached(long now, long maxAgeMs) {
        return fetchedAt > 0 && now - fetchedAt < maxAgeMs ? latest : null;
    }

    /** The last answer whatever its age, empty before the first. */
    public static synchronized Map<String, Status> last() {
        return latest;
    }

    public static synchronized void remember(Map<String, Status> zones, long now) {
        latest = Collections.unmodifiableMap(new HashMap<>(zones));
        fetchedAt = now;
    }

    private FireAlerts() {
    }
}
