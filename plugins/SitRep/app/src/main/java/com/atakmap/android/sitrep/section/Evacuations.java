package com.atakmap.android.sitrep.section;

import com.atakmap.android.sitrep.Area;
import com.atakmap.android.sitrep.net.Esri;
import com.atakmap.android.sitrep.net.Http;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Evacuation orders and warnings touching the area, from the feeds the Evac Zone
 * catalog lists (PLAN 4.1): live agency feeds, plus pre-planned zones where an agency
 * publishes only those. Where no agency publishes, the line says so.
 */
public final class Evacuations extends Section {

    static final String CATALOG = "https://mapdepot.takwerx.org/evaczone/catalog.json";
    /** The takwerx Genasys relay: GET only, and an envelope, never a polygon. */
    static final String RELAY = "https://cams.takwerx.org/evaczone/";

    public Evacuations() {
        super("evacuations", "Evacuations", true,
                "The evacuation feeds in the takwerx Evac Zone catalog: Genasys Protect, Cal OES, CAL FIRE, Oregon, county feeds.");
        seeIn = "See them on the map in Evac Zone.";
        openName = "Evac Zone";
        openPackage = "com.atakmap.android.evaczone.plugin";
        openAction = "com.atakmap.android.evaczone.SHOW";
    }

    @Override
    public Result fetch(Area area) throws Exception {
        final Counties counties = Counties.of(area);
        final JSONArray sources = new JSONObject(Http.get(CATALOG)).optJSONArray("sources");
        if (sources == null)
            throw new java.io.IOException("no sources in the evacuation catalog");
        final Map<String, List<String>> byStatus = new LinkedHashMap<>();
        final Map<String, List<String>> planned = new LinkedHashMap<>();
        final Set<String> seen = new HashSet<>();
        boolean covered = false;
        for (int i = 0; i < sources.length(); i++) {
            final JSONObject s = sources.optJSONObject(i);
            if (s == null)
                continue;
            // "US" is the Genasys feed for every state without its own source; its
            // where clause already leaves those states out.
            final String st = s.optString("st");
            if (!counties.states.contains(st) && !("US".equals(st) && !counties.states.isEmpty()))
                continue;
            if (!s.optString("url", "").startsWith("https://") || !"polygon".equals(s.optString("geometry")))
                continue;
            if (!overlaps(area, s.optJSONArray("bounds")))
                continue;
            covered = true;
            final boolean live = "live".equals(s.optString("kind"));
            // The source's own feed first; then the "also" feeds, which add only the
            // zones the first does not have (Evac Zone draws them the same way).
            final List<JSONObject> feeds = new ArrayList<>();
            feeds.add(s);
            final JSONArray also = s.optJSONArray("also");
            for (int j = 0; also != null && j < also.length(); j++)
                if (also.optJSONObject(j) != null)
                    feeds.add(also.optJSONObject(j));
            for (JSONObject feed : feeds) {
                final String url = feed.optString("url", "");
                if (!url.startsWith("https://"))
                    continue;
                final String status = field(feed.optString("status_field", ""));
                final String name = field(feed.optString("name_field", ""));
                final String fields = status == null && name == null ? "OBJECTID"
                        : (status == null ? "" : status) + (status != null && name != null ? "," : "") + (name == null ? "" : name);
                final String layer = url.replaceAll("/+$", "") + "/" + Math.max(0, feed.optInt("layer", 0));
                final String where = feed.optString("where", "1=1");
                List<JSONObject> rows;
                try {
                    rows = url.startsWith(RELAY)
                            ? Esri.attributesByGet(layer, where, area, fields)
                            : Esri.attributes(layer, where, area, fields);
                } catch (java.io.IOException e) {
                    // The source's own feed failing fails the section; an "also" feed
                    // only adds zones, so the others still answer without it.
                    if (feed == s)
                        throw e;
                    continue;
                }
                for (JSONObject a : rows) {
                    final String id = name == null ? null : clean(a.optString(name, ""));
                    if (live && id != null && !id.isEmpty() && !seen.add(shortId(id)))
                        continue;
                    String zone = id == null ? null : shortId(id);
                    final String known = clean(a.optString("known_as", ""));
                    if (zone != null && known != null && !known.isEmpty() && !known.equals(zone))
                        zone += " (" + known + ")";
                    if (live) {
                        String stat = status == null ? "In effect" : clean(a.optString(status, ""));
                        if (stat == null || stat.isEmpty())
                            stat = "Status not given";
                        add(byStatus, stat, zone);
                    } else {
                        add(planned, clean(s.optString("title", "Pre-planned zones")), zone);
                    }
                }
            }
        }
        if (!covered) {
            final Result r = new Result("Not published for this state", null);
            r.notPublishedHere = true;
            return r.line("No agency publishes evacuation orders for this place in a form we can read.");
        }
        int orders = 0, warnings = 0, other = 0;
        for (Map.Entry<String, List<String>> e : byStatus.entrySet()) {
            final String k = e.getKey().toLowerCase(java.util.Locale.US);
            if (k.contains("order") || k.contains("mandatory") || k.contains("level 3") || k.contains("go now"))
                orders += e.getValue().size();
            else if (k.contains("warning") || k.contains("level 2") || k.contains("set"))
                warnings += e.getValue().size();
            else
                other += e.getValue().size();
        }
        final String value = orders + warnings == 0
                ? (other == 0 ? "No orders or warnings" : "No orders or warnings; " + other + " other zones")
                : orders + (orders == 1 ? " order" : " orders") + ", " + warnings + (warnings == 1 ? " warning" : " warnings");
        final Result r = new Result(value, orders + warnings == 0 ? null : value);
        for (Map.Entry<String, List<String>> e : byStatus.entrySet())
            group(r, e.getKey(), e.getValue());
        for (Map.Entry<String, List<String>> e : planned.entrySet())
            group(r, "Pre-planned zones (" + e.getKey() + ")", e.getValue());
        if (byStatus.isEmpty())
            r.line("No evacuation order or warning touches the area.");
        return r;
    }

    private static void group(Result r, String title, List<String> zones) {
        r.line(GROUP + clip(title, 60) + ": " + zones.size());
        int shown = 0;
        for (String z : zones) {
            if (++shown > 40) {
                r.line("  ... and " + (zones.size() - 40) + " more.");
                break;
            }
            r.line("  " + (z == null || z.isEmpty() ? "Zone not named" : clip(z, 80)));
        }
    }

    /**
     * "PVL-E044" for "US-CA-XTU-PVL-E044": a Genasys id cut the way CAL FIRE and Evac
     * Zone show it, so one zone in two feeds is counted once. Anything else is itself.
     */
    static String shortId(String id) {
        final String[] p = id.split("-");
        if (p.length >= 4 && "US".equals(p[0]) && p[1].length() == 2 && p[2].length() == 3) {
            final StringBuilder sb = new StringBuilder();
            for (int i = 3; i < p.length; i++)
                sb.append(i > 3 ? "-" : "").append(p[i]);
            return sb.toString();
        }
        if (p.length == 3 && "US".equals(p[0]) && p[1].length() == 2)
            return p[2];
        return id;
    }

    private static void add(Map<String, List<String>> m, String k, String v) {
        List<String> l = m.get(k);
        if (l == null) {
            l = new ArrayList<>();
            m.put(k, l);
        }
        l.add(v);
    }

    /** A field name from the catalog; null when blank or not a plain field name. */
    private static String field(String f) {
        return f != null && f.matches("[A-Za-z_][A-Za-z0-9_]{0,63}") ? f : null;
    }

    private static String clean(String s) {
        if (s == null || "null".equals(s))
            return null;
        return s.replaceAll("\\s+", " ").trim();
    }

    private static boolean overlaps(Area a, JSONArray b) {
        if (b == null || b.length() < 4)
            return true;
        return !(b.optDouble(0) > a.north || b.optDouble(2) < a.south
                || b.optDouble(1) > a.east || b.optDouble(3) < a.west);
    }

    private static String clip(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max - 3).trim() + "...";
    }
}
