package com.atakmap.android.sitrep.section;

import com.atakmap.android.sitrep.Area;
import com.atakmap.android.sitrep.Units;
import com.atakmap.android.sitrep.net.Esri;
import com.atakmap.android.sitrep.net.Http;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Road closures, crashes, chain control and the rest from the state 511 systems, by
 * our relay (the Traffic plugin's source, PLAN 4.9). Events inside the area or within
 * Nearby. Message signs are left out: most are standing advice ("watch for deer").
 */
public final class Roads extends Section {

    static final String RELAY = "https://cams.takwerx.org/traffic511/";
    /** NWS reference map counties: which states the area is in. */
    static final String COUNTIES = Alerts.ZONES + "2";

    /** Kinds in report order, with the group name each is listed under. */
    private static final Map<String, String> KINDS = new LinkedHashMap<>();

    static {
        KINDS.put("closure", "Closures");
        KINDS.put("chains", "Chain control");
        KINDS.put("fire", "Vehicle fires");
        KINDS.put("crash", "Crashes");
        KINDS.put("lanes", "Lanes blocked");
        KINDS.put("hazard", "Hazards");
        KINDS.put("weather", "Weather on the road");
        KINDS.put("roadwork", "Road work");
        KINDS.put("info", "Other");
    }

    public Roads() {
        super("roads", "Roads", true,
                "State 511 systems (Caltrans, CHP and the other state DOTs), through the takwerx 511 relay.");
        seeIn = "See them on the map in Traffic.";
        openName = "Traffic";
        openPackage = "com.atakmap.android.traffic.plugin";
        openAction = "com.atakmap.android.traffic.SHOW";
    }

    @Override
    public Result fetch(Area area) throws Exception {
        return fetch(area, 0);
    }

    @Override
    public Result fetch(Area area, int nearbyMiles) throws Exception {
        final Set<String> states = new LinkedHashSet<>();
        for (JSONObject c : Esri.attributes(COUNTIES, "1=1", area, "state")) {
            final String st = c.optString("state", "");
            if (st.matches("[A-Z]{2}"))
                states.add(st);
        }
        if (states.isEmpty()) {
            final Result r = new Result("Not published here", null);
            r.notPublishedHere = true;
            return r.line("511 covers the United States.");
        }
        final double reach = nearbyMiles * 1609.344;
        final Map<String, List<JSONObject>> byKind = new LinkedHashMap<>();
        for (String k : KINDS.keySet())
            byKind.put(k, new ArrayList<JSONObject>());
        final List<String> missing = new ArrayList<>();
        for (String st : states) {
            final String body;
            try {
                body = Http.get(RELAY + st + ".geojson");
            } catch (Http.StatusException e) {
                if (e.status == 404) {
                    missing.add(st);
                    continue;
                }
                throw e;
            }
            final JSONArray feats = new JSONObject(body).optJSONArray("features");
            if (feats == null)
                throw new java.io.IOException("no features in the 511 answer");
            for (int i = 0; i < feats.length(); i++) {
                final JSONObject f = feats.optJSONObject(i);
                final JSONObject p = f == null ? null : f.optJSONObject("properties");
                final JSONObject g = f == null ? null : f.optJSONObject("geometry");
                final JSONArray c = g == null ? null : g.optJSONArray("coordinates");
                if (p == null || c == null || c.length() < 2 || !"Point".equals(g.optString("type")))
                    continue;
                final List<JSONObject> list = byKind.get(p.optString("kind"));
                if (list == null)
                    continue;   // message signs and kinds we do not list
                final double lat = c.optDouble(1), lon = c.optDouble(0);
                if (!(Math.abs(lat) <= 90) || !(Math.abs(lon) <= 180))
                    continue;
                final double d = area.distanceMeters(lat, lon);
                if (d > reach)
                    continue;
                p.put("_d", d).put("_lat", lat).put("_lon", lon);
                list.add(p);
            }
        }

        final StringBuilder head = new StringBuilder();
        int total = 0;
        for (Map.Entry<String, List<JSONObject>> e : byKind.entrySet()) {
            final int n = distinct(e.getValue(), area);
            total += n;
            if (n > 0 && head.length() < 60 && !"info".equals(e.getKey()))
                head.append(head.length() == 0 ? "" : ", ").append(n).append(' ')
                        .append(KINDS.get(e.getKey()).toLowerCase(java.util.Locale.US));
        }
        final String within = nearbyMiles > 0 ? " in the area or within " + nearbyMiles + " mi" : " in the area";
        if (total == 0) {
            final Result r = new Result(missing.size() == states.size() ? "Not published for this state"
                    : "None reported", null);
            r.notPublishedHere = missing.size() == states.size();
            r.line(r.notPublishedHere ? "No 511 feed reaches us for " + join(missing) + "."
                    : "No closure, crash or other 511 event" + within + ".");
            return r;
        }
        final Result r = new Result(head.length() == 0 ? total + " events" : head.toString(),
                total + (total == 1 ? " road event" : " road events"));
        for (Map.Entry<String, List<JSONObject>> e : byKind.entrySet()) {
            final List<JSONObject> list = e.getValue();
            if (list.isEmpty())
                continue;
            r.line(GROUP + KINDS.get(e.getKey()) + ": " + distinct(list, area));
            // One line per event: 511 lists one closure once per direction or segment
            // with the same words, and the same text twice says nothing new.
            final java.util.Set<String> said = new java.util.LinkedHashSet<>();
            for (JSONObject p : list)
                said.add(describe(p, area));
            int shown = 0;
            for (String line : said) {
                if (++shown > 30) {
                    r.line("  ... and " + (said.size() - 30) + " more.");
                    break;
                }
                r.line("  " + line);
            }
        }
        if (!missing.isEmpty())
            r.line("No 511 feed reaches us for " + join(missing) + ".");
        return r;
    }

    /** {@code I880 N / Whipple Rd: Road closure. 22:12 CLOSING LNS 3 / 4 / 5 (in the area)}. */
    private static String describe(JSONObject p, Area area) {
        final StringBuilder s = new StringBuilder();
        final String where = clip(p.optString("where", ""), 90);
        if (!where.isEmpty())
            s.append(where).append(": ");
        s.append(clip(p.optString("headline", ""), 90));
        final String details = p.optString("details", "");
        if (!details.isEmpty()) {
            final String first = clip(details.split("\n", 2)[0], 140);
            if (!first.isEmpty() && !first.equalsIgnoreCase(p.optString("headline", "")))
                s.append(". ").append(first);
        }
        final double d = p.optDouble("_d", 0);
        s.append(d == 0 ? " (in the area" : " (" + Units.distance(d) + " "
                + area.directionTo(p.optDouble("_lat"), p.optDouble("_lon")));
        // When the agency last touched it: CHP keeps an incident listed until it is
        // closed, so a rain advisory from the morning is still there at night.
        final long t = Math.max(time(p.optString("updated", "")), time(p.optString("reported", "")));
        if (t > 0)
            s.append("; ").append(new java.text.SimpleDateFormat("MMM d HH:mm", java.util.Locale.US)
                    .format(new java.util.Date(t))).append(", ").append(ago(t));
        return s.append(')').toString();
    }

    private static int distinct(List<JSONObject> list, Area area) {
        final java.util.Set<String> s = new java.util.HashSet<>();
        for (JSONObject p : list)
            s.add(describe(p, area));
        return s.size();
    }

    private static long time(String iso) {
        if (iso == null || iso.isEmpty() || "null".equals(iso))
            return 0;
        try {
            final java.text.SimpleDateFormat f = new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", java.util.Locale.US);
            final java.util.Date d = f.parse(iso);
            return d == null ? 0 : d.getTime();
        } catch (java.text.ParseException e) {
            return 0;
        }
    }

    private static String ago(long ms) {
        final long min = Math.max(0, (System.currentTimeMillis() - ms) / 60_000L);
        if (min < 60)
            return min + " min ago";
        if (min < 48 * 60)
            return (min / 60) + " h ago";
        return (min / (24 * 60)) + " days ago";
    }

    private static String clip(String s, int max) {
        s = s == null ? "" : s.replaceAll("\\s+", " ").trim();
        return s.length() <= max ? s : s.substring(0, max - 3).trim() + "...";
    }

    private static String join(List<String> l) {
        return Arrays.toString(l.toArray()).replaceAll("[\\[\\]]", "");
    }
}
