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
        KINDS.put("fire", "Fires on the road");
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
            final int n = e.getValue().size();
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
            r.line(GROUP + KINDS.get(e.getKey()) + ": " + list.size());
            int shown = 0;
            for (JSONObject p : list) {
                if (++shown > 30) {
                    r.line("  ... and " + (list.size() - 30) + " more.");
                    break;
                }
                r.line("  " + describe(p, area));
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
        s.append(d == 0 ? " (in the area)" : " (" + Units.distance(d) + " "
                + area.directionTo(p.optDouble("_lat"), p.optDouble("_lon")) + ")");
        return s.toString();
    }

    private static String clip(String s, int max) {
        s = s == null ? "" : s.replaceAll("\\s+", " ").trim();
        return s.length() <= max ? s : s.substring(0, max - 3).trim() + "...";
    }

    private static String join(List<String> l) {
        return Arrays.toString(l.toArray()).replaceAll("[\\[\\]]", "");
    }
}
