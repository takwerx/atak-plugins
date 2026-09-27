package com.atakmap.android.atmosphere.data;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The Weather Service's active warnings and watches, for drawing: Red Flag Warnings and
 * the rest. Pure request building and response reading; the overlay fetches.
 *
 * <h3>Measured 2026-09-24</h3>
 *
 * 469 alerts active nationally, 2.27 MB, 151 KB gzipped. 260 were warnings or watches
 * and 31 of those carried a polygon of their own; the rest are zone-based and are drawn
 * from their {@code affectedZones} (323 distinct zones that night), which is what
 * {@link ZoneCache} is for. The one Red Flag Warning (Medford, CAZ285) and three Fire
 * Weather Watches (Reno) all came with {@code geometry: null} -- the shape is the fire
 * zone. 128 of the 260 were Gale Warnings at sea.
 *
 * <p>Atmosphere draws warnings and watches, and the fire-weather products whatever
 * their type. Advisories and statements are IPAWS's job, which filters every alert
 * per phone; this is the picture of where the weather is dangerous.
 */
public final class NwsAlerts {

    public static final String HOST = "api.weather.gov";
    public static final String ACTIVE_URL = "https://" + HOST
            + "/alerts/active?status=actual&message_type=alert,update";

    /** Which toggle an alert answers to. */
    public enum Group { FIRE, LAND, MARINE }

    /** IPAWS's Fire category, carried forward: the fire-weather products by name. */
    static final Set<String> FIRE_EVENTS = new HashSet<>(Arrays.asList(
            "Red Flag Warning", "Fire Weather Watch", "Fire Warning", "Extreme Fire Danger",
            "Dense Smoke Advisory"));

    /**
     * UGC prefixes of marine zones. Measured on the night's alerts (AM, AN, GM, LE, LM,
     * LO, PH, PK, PZ) plus the Great Lakes, St. Lawrence and Pacific-island waters that
     * had nothing active. None is a state's code, so a land zone never matches.
     */
    static final Set<String> MARINE_PREFIXES = new HashSet<>(Arrays.asList(
            "AM", "AN", "GM", "LC", "LE", "LH", "LM", "LO", "LS", "SL", "PH", "PK", "PM",
            "PS", "PZ"));

    /** A zone URL this plugin will follow: api.weather.gov, a zone type, an id. */
    private static final Pattern ZONE_URL = Pattern.compile(
            "https://api\\.weather\\.gov/zones/(forecast|county|fire)/[A-Z]{2}[CZ][0-9]{3}");

    public static final class Alert {
        public final String id, event, headline, areaDesc, severity, sender;
        public final String description, instruction;
        public final long onset, ends, expires;
        /** Its own shape, or null when it is drawn from its zones. */
        public final JSONObject geometry;
        /** The zones it covers, as api.weather.gov URLs, only those this plugin follows. */
        public final List<String> zones;
        public final Group group;

        Alert(JSONObject props, JSONObject geometry, Group group) {
            id = props.optString("id", "");
            event = props.optString("event", "");
            headline = text(props, "headline");
            areaDesc = text(props, "areaDesc");
            severity = text(props, "severity");
            sender = text(props, "senderName");
            description = text(props, "description");
            instruction = text(props, "instruction");
            onset = IsoTime.parse(props.optString("onset", props.optString("effective", "")));
            ends = IsoTime.parse(props.optString("ends", ""));
            expires = IsoTime.parse(props.optString("expires", ""));
            this.geometry = geometry;
            final List<String> z = new ArrayList<>();
            final JSONArray az = props.optJSONArray("affectedZones");
            for (int i = 0; az != null && i < az.length(); i++) {
                final String u = az.optString(i, "");
                if (isZoneUrl(u))
                    z.add(u);
            }
            zones = Collections.unmodifiableList(z);
            this.group = group;
            this.advisory = advisory(event);
        }

        /** True for the lower tier (advisory, statement, alert), drawn only when asked. */
        public final boolean advisory;

        /** When it stops being in effect: ends if stated, else when the message expires. */
        public long until() {
            return ends > 0 ? ends : expires;
        }

        public int priority() {
            return NwsHazards.priority(event);
        }

        public int color() {
            return NwsHazards.color(event);
        }
    }

    private NwsAlerts() {
    }

    /** A zone URL this plugin follows and may name a cache file after. */
    public static boolean isZoneUrl(String url) {
        return url != null && ZONE_URL.matcher(url).matches();
    }

    /** True when an event is one Atmosphere draws. */
    public static boolean drawn(String event) {
        return event != null && (FIRE_EVENTS.contains(event) || event.endsWith(" Warning")
                || event.endsWith(" Watch"));
    }

    /**
     * The lower tier: advisories, statements and alerts that are neither a warning
     * nor a watch. Kept out of the map unless asked for, because they are most of
     * the feed (319 of 406 on 2026-09-26) and IPAWS already shows them; the operator
     * asked for them by name after "nothing within 50 mi" sat beside IPAWS's Beach
     * Hazards Statement.
     */
    public static boolean advisory(String event) {
        return event != null && !drawn(event) && !event.isEmpty();
    }

    /**
     * Every alert Atmosphere draws, the most urgent LAST, so inserting in list order
     * puts it on top. Tests and anything not "Actual" are dropped whatever the URL asked.
     */
    public static List<Alert> parse(String body) throws Exception {
        final JSONArray fs = new JSONObject(body).optJSONArray("features");
        final List<Alert> out = new ArrayList<>();
        for (int i = 0; fs != null && i < fs.length(); i++) {
            final JSONObject f = fs.optJSONObject(i);
            final JSONObject p = f == null ? null : f.optJSONObject("properties");
            if (p == null || !"Actual".equals(p.optString("status", "")))
                continue;
            final String event = p.optString("event", "");
            if (!drawn(event) && !advisory(event))
                continue;
            final JSONObject g = f.optJSONObject("geometry");
            out.add(new Alert(p, g, groupOf(event, ugc(p))));
        }
        Collections.sort(out, new Comparator<Alert>() {
            @Override
            public int compare(Alert a, Alert b) {
                // Descending priority number: least urgent first, drawn underneath.
                return Integer.compare(b.priority(), a.priority());
            }
        });
        return out;
    }

    /** Fire weather by name; marine when every zone it names is at sea; else land. */
    static Group groupOf(String event, List<String> ugc) {
        if (FIRE_EVENTS.contains(event))
            return Group.FIRE;
        if (ugc.isEmpty())
            return Group.LAND;
        for (String code : ugc)
            if (code.length() < 2 || !MARINE_PREFIXES.contains(code.substring(0, 2)))
                return Group.LAND;
        return Group.MARINE;
    }

    private static List<String> ugc(JSONObject props) {
        final List<String> out = new ArrayList<>();
        final JSONObject geo = props.optJSONObject("geocode");
        final JSONArray a = geo == null ? null : geo.optJSONArray("UGC");
        for (int i = 0; a != null && i < a.length(); i++)
            out.add(a.optString(i, ""));
        return out;
    }

    private static String text(JSONObject o, String key) {
        final String s = o.optString(key, "");
        return "null".equals(s) ? "" : s.trim();
    }
}
