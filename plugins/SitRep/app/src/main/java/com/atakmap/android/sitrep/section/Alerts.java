package com.atakmap.android.sitrep.section;

import com.atakmap.android.sitrep.Area;
import com.atakmap.android.sitrep.net.Esri;
import com.atakmap.android.sitrep.net.Http;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Active NWS alerts in the area, weather and the civil alerts NWS relays for local
 * authorities (PLAN 4.1, 4.1b). An alert is in the area when its polygon touches it,
 * or, with no polygon (Red Flag Warnings have none), when it names by UGC a county,
 * public zone or fire weather zone the area touches. Not by SAME: a coastal zone's
 * alert carries the SAME code of its whole county, so an inland area would get the
 * Beach Hazards Statement (measured 2026-11-13).
 */
public final class Alerts extends Section {

    /** NWS reference map: 2 counties, 8 public zones, 9 fire weather zones. */
    static final String ZONES = "https://mapservices.weather.noaa.gov/static/rest/services/"
            + "nws_reference_maps/nws_reference_map/MapServer/";
    static final String NWS = "https://api.weather.gov/alerts/active?area=";

    public Alerts() {
        super("alerts", "Alerts", true,
                "National Weather Service active alerts, including the civil alerts it relays for local authorities.");
        seeIn = "See them on the map in Atmosphere and IPAWS Alerts.";
        openName = "Atmosphere";
        openPackage = "com.atakmap.android.atmosphere.plugin";
        openAction = "com.atakmap.android.atmosphere.SHOW";
    }

    @Override
    public Result fetch(Area area) throws Exception {
        final Set<String> ugc = new HashSet<>();
        final Set<String> states = new LinkedHashSet<>();
        for (JSONObject c : Esri.attributes(ZONES + "2", "1=1", area, "state,fips")) {
            final String st = c.optString("state", ""), fips = c.optString("fips", "");
            if (st.length() == 2 && fips.length() == 5) {
                states.add(st);
                ugc.add(st + "C" + fips.substring(2));
            }
        }
        for (String layer : new String[] { "8", "9" })
            for (JSONObject z : Esri.attributes(ZONES + layer, "1=1", area, "state,zone")) {
                final String st = z.optString("state", ""), zone = z.optString("zone", "");
                if (st.length() == 2 && zone.length() == 3) {
                    states.add(st);
                    ugc.add(st + "Z" + zone);
                }
            }
        if (states.isEmpty()) {
            final Result r = new Result("Not published here", null);
            r.notPublishedHere = true;
            return r.line("NWS alerts cover the United States and its territories.");
        }

        // One entry per event name; several alerts of one event (zones, offices) merge.
        final Map<String, Found> byEvent = new LinkedHashMap<>();
        for (String st : states) {
            final JSONObject body = new JSONObject(Http.get(NWS + st));
            final JSONArray feats = body.optJSONArray("features");
            if (feats == null)
                throw new java.io.IOException("no features in the NWS answer");
            for (int i = 0; i < feats.length(); i++) {
                final JSONObject f = feats.optJSONObject(i);
                final JSONObject p = f == null ? null : f.optJSONObject("properties");
                if (p == null)
                    continue;
                final String event = p.optString("event", "");
                if (event.isEmpty() || SKIP.contains(event) || "Test".equals(p.optString("status")))
                    continue;
                if (!inArea(f, p, area, ugc))
                    continue;
                Found x = byEvent.get(event);
                if (x == null) {
                    x = new Found(event);
                    byEvent.put(event, x);
                }
                x.add(p);
            }
        }

        final List<Found> found = new ArrayList<>(byEvent.values());
        Collections.sort(found, new Comparator<Found>() {
            @Override
            public int compare(Found a, Found b) {
                final int r = rank(a.event) - rank(b.event);
                return r != 0 ? r : a.event.compareTo(b.event);
            }
        });
        if (found.isEmpty())
            return new Result("None in effect", null).line("No active alerts touch the area.");

        final StringBuilder head = new StringBuilder();
        for (Found x : found)
            head.append(head.length() == 0 ? "" : ", ").append(x.event);
        final Result r = new Result(head.toString(), found.get(0).event
                + (found.size() > 1 ? " +" + (found.size() - 1) : ""));
        for (Found x : found) {
            r.line(GROUP + x.event + (x.until > 0 ? ": until " + clock(x.until) : ""));
            if (x.headline != null)
                r.line("  " + x.headline);
            if (x.description != null)
                r.line("  " + x.description);
            if (x.instruction != null)
                r.line("  " + x.instruction);
            if (x.sender != null)
                r.line("  From " + x.sender + (x.count > 1 ? " (" + x.count + " alerts)" : "") + ".");
        }
        return r;
    }

    private static boolean inArea(JSONObject f, JSONObject p, Area area, Set<String> ugc) {
        final JSONObject g = f.optJSONObject("geometry");
        if (g != null) {
            final JSONArray c = g.optJSONArray("coordinates");
            if ("Polygon".equals(g.optString("type")))
                return c != null && area.touches(c.optJSONArray(0));
            if ("MultiPolygon".equals(g.optString("type")) && c != null) {
                for (int i = 0; i < c.length(); i++) {
                    final JSONArray poly = c.optJSONArray(i);
                    if (poly != null && area.touches(poly.optJSONArray(0)))
                        return true;
                }
                return false;
            }
        }
        final JSONObject geo = p.optJSONObject("geocode");
        final JSONArray codes = geo == null ? null : geo.optJSONArray("UGC");
        if (codes != null)
            for (int i = 0; i < codes.length(); i++)
                if (ugc.contains(codes.optString(i)))
                    return true;
        return false;
    }

    /** Civil alerts first, then warnings, watches, advisories, statements, the rest. */
    static int rank(String event) {
        if (CIVIL.contains(event))
            return 0;
        if (event.endsWith("Warning") || event.endsWith("Emergency"))
            return 1;
        if (event.endsWith("Watch"))
            return 2;
        if (event.endsWith("Advisory"))
            return 3;
        if (event.endsWith("Statement"))
            return 4;
        return 5;
    }

    /** One event's alerts in the area, merged. */
    private static final class Found {
        final String event;
        int count;
        long until;
        String headline, description, instruction, sender;

        Found(String event) {
            this.event = event;
        }

        void add(JSONObject p) {
            count++;
            long end = time(p.optString("ends", null));
            if (end <= 0)
                end = time(p.optString("expires", null));
            if (end > until)
                until = end;
            if (headline == null)
                headline = clip(p.optString("headline", null), 300);
            if (description == null)
                description = clip(p.optString("description", null), 480);
            if (instruction == null)
                instruction = clip(p.optString("instruction", null), 300);
            if (sender == null)
                sender = clip(p.optString("senderName", null), 80);
        }
    }

    private static long time(String iso) {
        if (iso == null || iso.isEmpty() || "null".equals(iso))
            return 0;
        try {
            final Date d = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US).parse(iso);
            return d == null ? 0 : d.getTime();
        } catch (ParseException e) {
            return 0;
        }
    }

    private static String clock(long ms) {
        return new SimpleDateFormat("EEE MMM d HH:mm", Locale.US).format(new Date(ms));
    }

    /** One paragraph: NWS text breaks lines at 70 columns. */
    private static String clip(String s, int max) {
        if (s == null || s.isEmpty() || "null".equals(s))
            return null;
        s = s.replaceAll("\\s+", " ").trim();
        if (s.isEmpty())
            return null;
        return s.length() <= max ? s : s.substring(0, max - 3).trim() + "...";
    }

    private static final Set<String> SKIP = new HashSet<>(Arrays.asList(
            "Administrative Message", "Test Message"));

    private static final Set<String> CIVIL = new HashSet<>(Arrays.asList(
            "Civil Emergency Message", "Civil Danger Warning", "Law Enforcement Warning",
            "Shelter In Place Warning", "Evacuation Immediate", "Local Area Emergency",
            "911 Telephone Outage Emergency", "Child Abduction Emergency", "Blue Alert",
            "Hazardous Materials Warning", "Nuclear Power Plant Warning",
            "Radiological Hazard Warning", "Fire Warning"));
}
