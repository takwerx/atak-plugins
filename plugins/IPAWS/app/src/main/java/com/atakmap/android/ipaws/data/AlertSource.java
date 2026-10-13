package com.atakmap.android.ipaws.data;

import org.json.JSONObject;

import java.util.List;

/**
 * Where alerts come from.
 *
 * <p>v0.1 has exactly one implementation, {@link NwsSource}, and that is deliberate:
 * "IPAWS" here is the name the infra-TAK Node-RED tab already uses for the NWS feed
 * (grepped 2026-09-16 -- that flow calls api.weather.gov and nothing else). Real FEMA
 * IPAWS-OPEN is a different endpoint behind a COG ID and a signed MOA, and the
 * operator's call was to ship NWS and leave this seam so it can drop in behind the
 * same overlay and the same list without a rewrite.
 *
 * <p>Which is why a source owns URL construction and parsing but not fetching,
 * caching or drawing: those are the same whatever answers.
 */
public interface AlertSource {

    /** Stable, used in the feature set name and in logs. */
    String id();

    String displayName();

    /**
     * The request for a filter, or null when the filter asks for nothing. Built the
     * same way the verifier builds it, which is the only reason the verifier proves
     * anything about what users get.
     */
    String requestUrl(Filter filter);

    /** The alerts in a response body, in feed order. */
    List<Alert> parse(JSONObject body);

    /**
     * Whether a zone geometry URL that came out of a response body may be fetched.
     *
     * <p>The alerts themselves are requested from a hardcoded base, but the zone
     * polygons are not: an alert names them in {@code affectedZones} and the plugin
     * follows those links. That is the one place where a response body decides what
     * this plugin connects to, so the source that produced the body says which
     * origins it vouches for. Feature Layer learned the same lesson in
     * {@code Esri.trustsServer} -- "a search result can name any URL at all".
     *
     * <p>It belongs to the source rather than to the fetcher because a second source
     * (FEMA IPAWS-OPEN, if a COG ever exists) would bring its own origin with it.
     */
    boolean trustsZoneUrl(String url);
}
