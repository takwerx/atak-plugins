package com.atakmap.android.ipaws.data;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The 111 NWS event types, in categories an operator thinks in.
 *
 * <p>The operator's words: "if i dont care about marine i dont want marine, i want
 * wildfire etc". So the first control is the category -- switch Marine off and 20
 * event types go with it -- and the second is the event names inside a category you
 * have kept, for when you want to get specific. A category with nothing chosen inside
 * it means the whole category, exactly the way a selected state with no counties
 * chosen means the whole state.
 *
 * <p>Fire is first and Civil &amp; Emergency second because that is the order they
 * matter in to the people this is for, not alphabetical.
 *
 * <p>The mapping was generated from the live list at {@code api.weather.gov/alerts/types}
 * and every one of the 111 lands in exactly one category, which
 * {@code tools/verify_ipaws_feed.py} re-checks: an event type NWS adds that this does
 * not know shows up as a gate failure rather than as an alert nobody can find. Until
 * someone files it, an unknown type falls into Other &amp; Outlooks and is therefore
 * <b>on</b> by default -- a new hazard appearing on the map unasked is the right
 * failure, and an alert silently missing is not.
 *
 * <p>A handful are pinned by name because their words fight the obvious rule: a
 * Tropical Storm Warning is Tropical rather than Marine, and a Brisk Wind Advisory is
 * a marine product despite the name.
 */
public final class Events {

    private Events() {
    }

    /**
     * Never offered and never matched: {@code status=actual} is always sent, so a Test
     * can never arrive, and a control that can do nothing is noise.
     */
    public static final String TEST = "Test";

    private static final Map<String, String[]> BY_CATEGORY = new LinkedHashMap<>();
    private static final Map<String, String> OF_EVENT = new LinkedHashMap<>();

    /** Anything NWS adds that this file has not caught up with. */
    public static final String OTHER = "Other & Outlooks";

    static {
        put("Fire", new String[] {
                "Dense Smoke Advisory", "Extreme Fire Danger", "Fire Warning",
                "Fire Weather Watch", "Red Flag Warning" });
        put("Civil & Emergency", new String[] {
                "911 Telephone Outage", "Blue Alert", "Child Abduction Emergency",
                "Civil Danger Warning", "Civil Emergency Message", "Evacuation Immediate",
                "Hazardous Materials Warning", "Law Enforcement Warning",
                "Local Area Emergency", "Nuclear Power Plant Warning",
                "Radiological Hazard Warning", "Shelter In Place Warning" });
        put("Tornado & Thunderstorm", new String[] {
                "Severe Thunderstorm Warning", "Severe Thunderstorm Watch",
                "Severe Weather Statement", "Tornado Warning", "Tornado Watch" });
        put("Flood", new String[] {
                "Coastal Flood Advisory", "Coastal Flood Statement", "Coastal Flood Warning",
                "Coastal Flood Watch", "Flash Flood Statement", "Flash Flood Warning",
                "Flash Flood Watch", "Flood Advisory", "Flood Statement", "Flood Warning",
                "Flood Watch", "Hydrologic Outlook", "Lakeshore Flood Advisory",
                "Lakeshore Flood Statement", "Lakeshore Flood Warning",
                "Lakeshore Flood Watch" });
        put("Marine", new String[] {
                "Beach Hazards Statement", "Brisk Wind Advisory", "Freezing Spray Advisory",
                "Gale Warning", "Gale Watch", "Hazardous Seas Warning", "Hazardous Seas Watch",
                "Heavy Freezing Spray Warning", "Heavy Freezing Spray Watch",
                "High Surf Advisory", "High Surf Warning", "Hurricane Force Wind Warning",
                "Hurricane Force Wind Watch", "Low Water Advisory", "Marine Weather Statement",
                "Rip Current Statement", "Small Craft Advisory", "Special Marine Warning",
                "Storm Warning", "Storm Watch" });
        put("Tropical", new String[] {
                "Hurricane Warning", "Hurricane Watch", "Storm Surge Warning",
                "Storm Surge Watch", "Tropical Cyclone Local Statement",
                "Tropical Storm Warning", "Tropical Storm Watch", "Typhoon Warning",
                "Typhoon Watch" });
        put("Winter & Cold", new String[] {
                "Blizzard Warning", "Cold Weather Advisory", "Extreme Cold Warning",
                "Extreme Cold Watch", "Freeze Warning", "Freeze Watch", "Frost Advisory",
                "Ice Storm Warning", "Lake Effect Snow Warning", "Snow Squall Warning",
                "Winter Storm Warning", "Winter Storm Watch", "Winter Weather Advisory" });
        put("Wind & Dust", new String[] {
                "Blowing Dust Advisory", "Blowing Dust Warning", "Dust Advisory",
                "Dust Storm Warning", "Extreme Wind Warning", "High Wind Warning",
                "High Wind Watch", "Lake Wind Advisory", "Wind Advisory" });
        put("Heat", new String[] {
                "Extreme Heat Warning", "Extreme Heat Watch", "Heat Advisory" });
        put("Air Quality", new String[] {
                "Air Quality Alert", "Air Stagnation Advisory" });
        put("Fog", new String[] {
                "Dense Fog Advisory", "Freezing Fog Advisory" });
        put("Geologic & Tsunami", new String[] {
                "Ashfall Advisory", "Ashfall Warning", "Avalanche Advisory",
                "Avalanche Warning", "Avalanche Watch", "Earthquake Warning",
                "Tsunami Advisory", "Tsunami Warning", "Tsunami Watch", "Volcano Warning" });
        put("Other & Outlooks", new String[] {
                "Administrative Message", "Hazardous Weather Outlook", "Short Term Forecast",
                "Special Weather Statement", "Test" });
    }

    private static void put(String category, String[] events) {
        BY_CATEGORY.put(category, events);
        for (String e : events)
            OF_EVENT.put(e, category);
    }

    /** Categories in the order the picker shows them. */
    public static List<String> categories() {
        return new ArrayList<>(BY_CATEGORY.keySet());
    }

    /** The event names in one category, without Test. */
    public static List<String> eventsIn(String category) {
        final String[] all = BY_CATEGORY.get(category);
        if (all == null)
            return Collections.emptyList();
        final List<String> out = new ArrayList<>(all.length);
        for (String e : all)
            if (!TEST.equals(e))
                out.add(e);
        return out;
    }

    /** The category an event belongs to; {@link #OTHER} for anything unrecognised. */
    public static String categoryOf(String event) {
        final String c = event == null ? null : OF_EVENT.get(event);
        return c == null ? OTHER : c;
    }

    /** Every event type this build knows, for the gate in the verifier. */
    public static int knownCount() {
        return OF_EVENT.size();
    }
}
