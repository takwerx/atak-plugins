package com.atakmap.android.ipaws.data;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * What this device wants to see. The whole point of the plugin: the box has one
 * {@code ipaws_config} for everyone, and this is per EUD.
 *
 * <p>Persisted as JSON so a field can be added without a migration.
 */
public class Filter {

    /**
     * The default until the picker exists.
     *
     * <p>The operator's answer for a fresh install is "the state the phone is in, at
     * Extreme + Severe". That needs the self marker and a state lookup, which lands
     * with the picker; until then these three are the hardcoded filter the first
     * build polls with, at every severity so there is something to look at.
     */
    private static final String[] DEFAULT_AREAS = { "CA", "NV", "OR" };

    /**
     * Minutes between polls. The box polls every minute for everyone; a phone doing
     * that is 288 more requests a day each, across a fleet, for a feed whose own
     * cache-control is 5 seconds and whose alerts are issued in minutes, not seconds.
     * Five is the starting point and the field decides, not a probe.
     */
    public static final int DEFAULT_POLL_MINUTES = 5;

    /** Area codes: 59 state/territory + 15 marine, every one verified to serve. */
    public final Set<String> areas = new LinkedHashSet<>();
    /** CAP severities. Empty means the feed's own default, which is all of them. */
    public final Set<String> severities = new LinkedHashSet<>();
    /** Event names. Empty means every event -- the usual case. */
    public final Set<String> events = new LinkedHashSet<>();
    public int pollMinutes = DEFAULT_POLL_MINUTES;
    /**
     * Off by default, and when on it fires only for the severities below: a plugin
     * that pops a notification for a Winter Weather Advisory in the next county is
     * switched off within a day.
     */
    public boolean notify = false;
    public final Set<String> notifySeverities = new LinkedHashSet<>();

    public static Filter defaults() {
        final Filter f = new Filter();
        f.areas.addAll(Arrays.asList(DEFAULT_AREAS));
        f.notifySeverities.add("Extreme");
        f.notifySeverities.add("Severe");
        return f;
    }

    /** Nothing selected means nothing to ask for; the poll does not run. */
    public boolean selectsNothing() {
        return areas.isEmpty();
    }

    /** Whether an alert the feed returned should be shown. */
    public boolean accepts(Alert a) {
        if (!events.isEmpty() && !events.contains(a.event))
            return false;
        // Severity is a server-side filter too, but a cached set outlives a filter
        // change, so it is applied here as well rather than trusted from the query.
        return severities.isEmpty() || severities.contains(a.severity);
    }

    public boolean shouldNotify(Alert a) {
        return notify && notifySeverities.contains(a.severity);
    }

    public JSONObject toJson() throws Exception {
        final JSONObject o = new JSONObject();
        o.put("areas", new JSONArray(areas));
        o.put("severities", new JSONArray(severities));
        o.put("events", new JSONArray(events));
        o.put("pollMinutes", pollMinutes);
        o.put("notify", notify);
        o.put("notifySeverities", new JSONArray(notifySeverities));
        return o;
    }

    /** A stored filter; anything unreadable falls back to the defaults. */
    public static Filter fromJson(JSONObject o) {
        if (o == null)
            return defaults();
        final Filter f = new Filter();
        readInto(o.optJSONArray("areas"), f.areas);
        readInto(o.optJSONArray("severities"), f.severities);
        readInto(o.optJSONArray("events"), f.events);
        readInto(o.optJSONArray("notifySeverities"), f.notifySeverities);
        f.pollMinutes = Math.max(1, o.optInt("pollMinutes", DEFAULT_POLL_MINUTES));
        f.notify = o.optBoolean("notify", false);
        // A stored filter that selects nothing is a stored filter, not a broken one:
        // the user may have turned everything off on purpose. Only a missing key
        // falls back.
        if (!o.has("areas"))
            return defaults();
        return f;
    }

    private static void readInto(JSONArray a, Set<String> out) {
        if (a == null)
            return;
        for (int i = 0; i < a.length(); i++) {
            final String s = a.optString(i, null);
            if (s != null && !s.isEmpty())
                out.add(s);
        }
    }
}
