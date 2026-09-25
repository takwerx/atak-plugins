package com.atakmap.android.ipaws.data;

import org.json.JSONArray;
import org.json.JSONObject;

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
    /**
     * Categories switched off entirely. Empty means every category is on, which is
     * the default: "if i dont care about marine i dont want marine".
     */
    public final Set<String> offCategories = new LinkedHashSet<>();
    /**
     * Event names narrowing the categories that are on, exactly the way counties
     * narrow states: a category with none of its events chosen means the whole
     * category, and one with events chosen means only those.
     */
    public final Set<String> events = new LinkedHashSet<>();
    /**
     * Counties, as SAME codes, narrowing the states above.
     *
     * <p>The rule is per state and it is the one that makes this behave the way a
     * person expects: a selected state with <b>no</b> counties chosen means the whole
     * state, and a selected state with counties chosen means only those. So picking
     * three counties in Nevada does not quietly switch California off.
     */
    public final Set<String> counties = new LinkedHashSet<>();
    public int pollMinutes = DEFAULT_POLL_MINUTES;
    /**
     * Off by default, and when on it fires only for the severities below: a plugin
     * that pops a notification for a Winter Weather Advisory in the next county is
     * switched off within a day.
     */
    public boolean notify = false;
    public final Set<String> notifySeverities = new LinkedHashSet<>();
    /**
     * All ON / All OFF, Feature Layer's switch: off takes every alert off the map and
     * leaves the rest running -- the list, the poll, notifications -- and the layer is
     * hidden rather than emptied, so switching it back on is instant.
     */
    public boolean mapOn = true;

    /**
     * A fresh install: the state the phone is in, every severity showing.
     *
     * <p>The state is not known here -- it is resolved once from the self marker when
     * the plugin starts, so this leaves it empty and the pane says "Choose states"
     * until either that lookup lands or the operator picks. A phone with no position
     * therefore shows nothing rather than guessing a state, which is the right way
     * round: an empty map that says so beats a map full of another state's weather.
     *
     * <p>Severity starts wide, on the operator's instruction: "it should start with
     * all severities on then you can toggle off what you dont want". Starting at
     * Extreme and Severe meant a new install often showed an empty map -- California
     * had nothing at either level the day this changed -- and an empty map is a poor
     * way to learn what a tool does. Showing everything and letting the operator trim
     * is the right direction to be wrong in.
     *
     * <p>Empty means every severity and sends no severity parameter at all, which is
     * also the shape that cannot trip the enum's case-sensitivity.
     *
     * <p>Notifications are untouched by this: they stay OFF, and when switched on they
     * still offer Extreme and Severe. Showing an alert on a map and chiming about it
     * are different questions.
     */
    public static Filter defaults() {
        final Filter f = new Filter();
        f.notifySeverities.add("Extreme");
        f.notifySeverities.add("Severe");
        return f;
    }

    /** Nothing selected means nothing to ask for; the poll does not run. */
    public boolean selectsNothing() {
        return areas.isEmpty();
    }

    /**
     * Drops county selections for states that are no longer selected. Without this a
     * state deselected and later reselected would come back silently narrowed to
     * whatever counties were picked a week ago.
     */
    public void dropOrphanedCounties() {
        final Set<String> live = new LinkedHashSet<>();
        for (String a : areas) {
            final String p = Areas.statePrefix(a);
            if (p != null)
                live.add(p);
        }
        final java.util.Iterator<String> it = counties.iterator();
        while (it.hasNext()) {
            final String p = Areas.statePrefixOfSame(it.next());
            if (p == null || !live.contains(p))
                it.remove();
        }
    }

    /** Whether an alert the feed returned should be shown. */
    public boolean accepts(Alert a) {
        if (!acceptsType(a.event))
            return false;
        // Severity is a server-side filter too, but a cached set outlives a filter
        // change, so it is applied here as well rather than trusted from the query.
        return severities.isEmpty() || severities.contains(a.severity);
    }

    /**
     * Category first, then the event names inside it.
     *
     * <p>An event type this build has never heard of lands in Other &amp; Outlooks
     * and is therefore on unless that category was switched off. That is deliberate:
     * a hazard NWS invented last week showing up unasked is a far better failure than
     * one that silently never appears.
     */
    public boolean acceptsType(String event) {
        final String category = Events.categoryOf(event);
        if (offCategories.contains(category))
            return false;
        final Set<String> chosen = eventsIn(category);
        return chosen.isEmpty() || chosen.contains(event);
    }

    /** The events chosen within one category; empty means the whole category. */
    public Set<String> eventsIn(String category) {
        final Set<String> out = new LinkedHashSet<>();
        for (String e : events)
            if (Events.categoryOf(e).equals(category))
                out.add(e);
        return out;
    }

    /** Replaces the event selection for one category, leaving the others alone. */
    public void setEventsIn(String category, Set<String> chosen) {
        final java.util.Iterator<String> it = events.iterator();
        while (it.hasNext())
            if (Events.categoryOf(it.next()).equals(category))
                it.remove();
        events.addAll(chosen);
    }

    /** The categories that are on and narrowed to particular events. */
    public Set<String> narrowedCategories() {
        final Set<String> out = new LinkedHashSet<>();
        for (String e : events) {
            final String c = Events.categoryOf(e);
            if (!offCategories.contains(c))
                out.add(c);
        }
        return out;
    }

    /**
     * Drops event selections inside categories that are now off, for the same reason
     * county selections are dropped with their state: a category switched off and back
     * on must not return silently narrowed to a choice made a week ago.
     */
    public void dropOrphanedEvents() {
        final java.util.Iterator<String> it = events.iterator();
        while (it.hasNext())
            if (offCategories.contains(Events.categoryOf(it.next())))
                it.remove();
    }

    /**
     * The half of the county rule that can be decided from the alert's own text.
     *
     * <p>Returns YES when the alert is in, NO when the alert is out on attribution
     * alone, and MAYBE when only geometry can settle it. The geometric half lives in
     * the manager, because that is where the assembled alert area is.
     *
     * <p>Counties narrow, per state, and never subtract: an alert touching a selected
     * state the user did <b>not</b> narrow is always in, so picking three counties in
     * Nevada cannot switch California off.
     */
    public enum Verdict {
        YES, NO, MAYBE
    }

    public Verdict acceptsCounties(Alert a) {
        if (counties.isEmpty())
            return Verdict.YES;
        final Set<String> narrowed = narrowedStates();
        for (String same : a.counties) {
            final String state = Areas.statePrefixOfSame(same);
            if (state == null || !narrowed.contains(state))
                return Verdict.YES;   // a selected state, taken whole
            if (counties.contains(same))
                return Verdict.YES;   // one of the counties asked for
        }
        // Either the alert names no counties at all -- which is every marine zone --
        // or it names only counties in a narrowed state that were not chosen. Both
        // can still be a touch on the ground, and the operator's rule is that a
        // touch counts, so this is not a refusal yet.
        return Verdict.MAYBE;
    }

    /** The state prefixes ("006") that have at least one county selected. */
    public Set<String> narrowedStates() {
        final Set<String> out = new LinkedHashSet<>();
        for (String same : counties) {
            final String p = Areas.statePrefixOfSame(same);
            if (p != null)
                out.add(p);
        }
        return out;
    }

    /** The counties selected within one state, by its two-letter code. */
    public Set<String> countiesIn(String stateCode) {
        final Set<String> out = new LinkedHashSet<>();
        final String prefix = Areas.statePrefix(stateCode);
        if (prefix == null)
            return out;
        for (String same : counties)
            if (same.startsWith(prefix))
                out.add(same);
        return out;
    }

    /** Replaces the county selection for one state, leaving every other state alone. */
    public void setCountiesIn(String stateCode, Set<String> chosen) {
        final String prefix = Areas.statePrefix(stateCode);
        if (prefix == null)
            return;
        final java.util.Iterator<String> it = counties.iterator();
        while (it.hasNext())
            if (it.next().startsWith(prefix))
                it.remove();
        counties.addAll(chosen);
    }

    public boolean shouldNotify(Alert a) {
        return notify && notifySeverities.contains(a.severity);
    }

    public JSONObject toJson() throws Exception {
        final JSONObject o = new JSONObject();
        o.put("areas", new JSONArray(areas));
        o.put("severities", new JSONArray(severities));
        o.put("events", new JSONArray(events));
        o.put("counties", new JSONArray(counties));
        o.put("offCategories", new JSONArray(offCategories));
        o.put("pollMinutes", pollMinutes);
        o.put("notify", notify);
        o.put("notifySeverities", new JSONArray(notifySeverities));
        o.put("mapOn", mapOn);
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
        readInto(o.optJSONArray("counties"), f.counties);
        readInto(o.optJSONArray("offCategories"), f.offCategories);
        readInto(o.optJSONArray("notifySeverities"), f.notifySeverities);
        f.pollMinutes = Math.max(1, o.optInt("pollMinutes", DEFAULT_POLL_MINUTES));
        f.notify = o.optBoolean("notify", false);
        f.mapOn = o.optBoolean("mapOn", true);
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
