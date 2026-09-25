package com.atakmap.android.ipaws.data;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

/**
 * One CAP alert, as the feed gives it.
 *
 * <p>The field that decides the shape of this plugin is {@link #geometry}: when the
 * feed was measured on 2026-09-16, <b>312 of 337 active alerts nationally carried
 * {@code geometry: null}</b> and described their area only by {@link #zoneUrls}. So
 * resolving zones is the main path, not a fallback, and an alert is not drawable until
 * that has happened.
 */
public class Alert {

    /** Most severe first. This is the list order and the notification gate. */
    public static final String[] SEVERITIES = { "Extreme", "Severe", "Moderate", "Minor", "Unknown" };

    public final String id;
    public final String event;
    public final String severity;
    public final String urgency;
    public final String certainty;
    public final String areaDesc;
    public final String senderName;
    public final String headline;
    public final String description;
    public final String instruction;
    /**
     * CAP's msgType: {@code Alert} for a new one, {@code Update} for a revision of one
     * already out (extended, reworded, a new expiry) and {@code Cancel}. An update has
     * an id of its own, so without this every revision of the same warning read as new
     * weather and notified again.
     */
    public final String messageType;
    /** Millis since epoch, or 0 when the feed did not say. */
    public final long effective;
    public final long expires;
    public final long ends;
    /** Full URLs, used verbatim -- never rebuilt, so the zone type is never guessed. */
    public final List<String> zoneUrls;
    /**
     * The counties this alert covers, as SAME codes ({@code 006025}). Present on every
     * one of 310 active alerts when the feed was measured on 2026-09-16, which is why
     * the county filter is applied to these rather than asked of the server: the
     * alerts are issued against forecast zones, not counties -- 1146 Z zones to 63 C
     * zones, and 280 of the 310 named no county zone at all -- so a county query would
     * be a second request per toggle to learn what the response already says.
     */
    public final List<String> counties;
    /**
     * The zones as UGC codes ({@code CAZ562}, {@code PZZ655}). Only the first two
     * letters are read, to place an alert with no area at its state's middle.
     */
    public final List<String> ugc;
    /** The alert's own geometry when it has one, else null and {@link #zoneUrls} applies. */
    public final JSONObject geometry;

    private Alert(JSONObject feature) {
        final JSONObject p = feature.optJSONObject("properties");
        final JSONObject props = p == null ? new JSONObject() : p;
        id = props.optString("id", "");
        event = props.optString("event", "Alert");
        severity = props.optString("severity", "Unknown");
        urgency = props.optString("urgency", "Unknown");
        certainty = props.optString("certainty", "Unknown");
        areaDesc = props.optString("areaDesc", "");
        senderName = props.optString("senderName", "");
        headline = props.optString("headline", "");
        description = props.optString("description", "");
        instruction = props.optString("instruction", "");
        messageType = props.optString("messageType", "Alert");
        effective = parseTime(props.optString("effective", null));
        expires = parseTime(props.optString("expires", null));
        ends = parseTime(props.optString("ends", null));
        geometry = feature.optJSONObject("geometry");

        final List<String> zones = new ArrayList<>();
        final JSONArray az = props.optJSONArray("affectedZones");
        if (az != null)
            for (int i = 0; i < az.length(); i++) {
                final String u = az.optString(i, null);
                // https only: these go straight into the fetcher, which refuses
                // anything else, and a feed that changed scheme should be ignored
                // rather than argued with.
                if (u != null && u.startsWith("https://"))
                    zones.add(u);
            }
        zoneUrls = zones;

        final List<String> same = new ArrayList<>();
        final JSONObject geocode = props.optJSONObject("geocode");
        final JSONArray sameCodes = geocode == null ? null : geocode.optJSONArray("SAME");
        if (sameCodes != null)
            for (int i = 0; i < sameCodes.length(); i++) {
                final String c = sameCodes.optString(i, null);
                if (c != null && c.length() == 6)
                    same.add(c);
            }
        counties = same;

        final List<String> zonesUgc = new ArrayList<>();
        final JSONArray ugcCodes = geocode == null ? null : geocode.optJSONArray("UGC");
        if (ugcCodes != null)
            for (int i = 0; i < ugcCodes.length(); i++) {
                final String c = ugcCodes.optString(i, null);
                if (c != null && c.length() >= 2)
                    zonesUgc.add(c);
            }
        ugc = zonesUgc;
    }

    /** Every feature of an {@code /alerts/active} FeatureCollection, in feed order. */
    public static List<Alert> parseAll(JSONObject collection) {
        final List<Alert> out = new ArrayList<>();
        final JSONArray features = collection.optJSONArray("features");
        if (features == null)
            return out;
        for (int i = 0; i < features.length(); i++) {
            final JSONObject f = features.optJSONObject(i);
            if (f == null)
                continue;
            final Alert a = new Alert(f);
            // An alert with no id cannot be tracked between polls, so it cannot be
            // told apart from a new one and would notify forever.
            if (!a.id.isEmpty())
                out.add(a);
        }
        return out;
    }

    /** A brand-new alert, not an update or a cancellation of one already out. */
    public boolean isNew() {
        return !"Update".equalsIgnoreCase(messageType) && !"Cancel".equalsIgnoreCase(messageType);
    }

    /** 0 is the most severe, so a plain sort puts the worst first. */
    public int severityRank() {
        for (int i = 0; i < SEVERITIES.length; i++)
            if (SEVERITIES[i].equalsIgnoreCase(severity))
                return i;
        return SEVERITIES.length;
    }

    /** When the alert stops applying: {@code ends} if the feed gave one, else {@code expires}. */
    public long until() {
        return ends > 0 ? ends : expires;
    }

    public boolean hasOwnGeometry() {
        return geometry != null && geometry.optString("type", "").length() > 0;
    }

    /**
     * ISO-8601 with an offset, parsed by hand.
     *
     * <p>The feed sends {@code 2026-09-16T02:47:00-07:00}. SimpleDateFormat's
     * {@code XXX} pattern reads that directly but is API 24+, and this plugin's
     * minSdk is 21, so the colon comes out of the offset and the API 21 {@code Z}
     * pattern reads the rest. A time that will not parse is 0, never "now": a
     * fabricated timestamp would make an expired alert look current.
     */
    static long parseTime(String s) {
        if (s == null || s.isEmpty())
            return 0L;
        String t = s.trim();
        try {
            if (t.endsWith("Z")) {
                t = t.substring(0, t.length() - 1) + "+0000";
            } else if (t.length() > 6 && t.charAt(t.length() - 3) == ':') {
                final char sign = t.charAt(t.length() - 6);
                if (sign == '+' || sign == '-')
                    t = t.substring(0, t.length() - 3) + t.substring(t.length() - 2);
            }
            final SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ", Locale.US);
            f.setTimeZone(TimeZone.getTimeZone("UTC"));
            return f.parse(t).getTime();
        } catch (ParseException | RuntimeException e) {
            return 0L;
        }
    }
}
