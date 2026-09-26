package com.atakmap.android.ipaws.data;

import android.net.Uri;

import org.json.JSONObject;

import java.util.List;

/**
 * api.weather.gov -- the National Weather Service CAP feed.
 *
 * <p>Public, no key, and it asks callers to identify themselves (see
 * {@code Http.USER_AGENT}). What it carries is NWS's own products plus the handful of
 * non-weather messages NWS relays: Civil Emergency Message, Local Area Emergency,
 * Child Abduction Emergency, 911 Telephone Outage, Blue Alert, Evacuation Immediate.
 * What it does not carry is a state or local originator that never reaches NWS; that
 * is what a FEMA COG would add.
 *
 * <p>The query mirrors infra-TAK's {@code FN_IPAWS_BUILD_REQ}: {@code status=actual}
 * always, so tests and exercises stay off the map, then the area and severity lists.
 */
public class NwsSource implements AlertSource {

    private static final String BASE = "https://api.weather.gov/alerts/active";

    @Override
    public String id() {
        return "nws";
    }

    @Override
    public String displayName() {
        return "National Weather Service";
    }

    @Override
    public String requestUrl(Filter filter) {
        if (filter == null || filter.selectsNothing())
            return null;
        final Uri.Builder b = Uri.parse(BASE).buildUpon();
        b.appendQueryParameter("status", "actual");
        b.appendQueryParameter("area", joinUpper(filter.areas));
        if (!filter.severities.isEmpty())
            b.appendQueryParameter("severity", joinVerbatim(filter.severities));
        // event= is deliberately not sent. The feed takes it, but the response is
        // cached and reused across filter changes, and a server-side event filter
        // would mean a changed event selection shows nothing until the next poll.
        // Filter.accepts() applies events to what came back instead.
        return b.build().toString();
    }

    @Override
    public List<Alert> parse(JSONObject body) {
        return Alert.parseAll(body);
    }

    /**
     * api.weather.gov on the default port, over https, and nothing else.
     *
     * <p>{@code URL.getHost()} returns the authority's host, not whatever userinfo
     * precedes it, so a URL that puts this host in the userinfo position and a
     * different one in the authority is correctly read as the latter and refused.
     * {@code equalsIgnoreCase} refuses a lookalike such as
     * api.weather.gov.example.invalid for the same reason. Both are covered by the
     * cases in the note recorded with this version's security review.
     */
    @Override
    public boolean trustsZoneUrl(String url) {
        if (url == null)
            return false;
        try {
            final java.net.URL u = new java.net.URL(url);
            if (!"https".equalsIgnoreCase(u.getProtocol()))
                return false;
            if (u.getPort() != -1 && u.getPort() != 443)
                return false;
            return HOST.equalsIgnoreCase(u.getHost());
        } catch (java.net.MalformedURLException e) {
            return false;
        }
    }

    private static final String HOST = "api.weather.gov";

    /**
     * Area codes, upper-cased. The service requires it: {@code area=ca} is a 400 and
     * {@code area=CA} is a 200.
     */
    private static String joinUpper(Iterable<String> values) {
        return join(values, true);
    }

    /**
     * Severities exactly as given. These are an enumeration the service matches
     * case-sensitively -- {@code severity=Extreme,Severe} is a 200 and
     * {@code severity=EXTREME,SEVERE} is a 400 reading "Does not have a value in the
     * enumeration". One join upper-cased both, which was right for areas and wrong
     * here, and it only ever showed on a FRESH install: the default filter is the
     * first thing that actually sends a severity, so every poll on a new phone failed
     * while a phone whose filter had been widened to all severities never sent the
     * parameter at all and looked fine.
     */
    private static String joinVerbatim(Iterable<String> values) {
        return join(values, false);
    }

    private static String join(Iterable<String> values, boolean upper) {
        final StringBuilder sb = new StringBuilder();
        for (String v : values) {
            if (v == null)
                continue;
            String t = v.trim();
            if (upper)
                t = t.toUpperCase(java.util.Locale.US);
            if (t.isEmpty())
                continue;
            if (sb.length() > 0)
                sb.append(',');
            sb.append(t);
        }
        return sb.toString();
    }

    /**
     * Every area code the picker may offer, most severe hazard of getting this wrong
     * being a code nothing serves. All 74 were verified against the live endpoint on
     * 2026-09-16 by {@code tools/verify_ipaws_feed.py}; the list itself is the
     * authoritative enum out of {@code api.weather.gov/openapi.json}
     * ({@code StateTerritoryCode} then {@code MarineAreaCode}).
     */
    public static final String[] STATE_CODES = {
            "AL", "AK", "AS", "AR", "AZ", "CA", "CO", "CT", "DE", "DC", "FL", "GA",
            "GU", "HI", "ID", "IL", "IN", "IA", "KS", "KY", "LA", "ME", "MD", "MA",
            "MI", "MN", "MS", "MO", "MT", "NE", "NV", "NH", "NJ", "NM", "NY", "NC",
            "ND", "OH", "OK", "OR", "PA", "PR", "RI", "SC", "SD", "TN", "TX", "UT",
            "VT", "VI", "VA", "WA", "WV", "WI", "WY", "MP", "PW", "FM", "MH" };

    public static final String[] MARINE_CODES = {
            "AM", "AN", "GM", "LC", "LE", "LH", "LM", "LO", "LS", "PH", "PK", "PM",
            "PS", "PZ", "SL" };
}
