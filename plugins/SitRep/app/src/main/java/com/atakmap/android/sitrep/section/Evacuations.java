package com.atakmap.android.sitrep.section;

import com.atakmap.android.sitrep.Area;
import com.atakmap.android.sitrep.net.Esri;
import com.atakmap.android.sitrep.net.Http;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Evacuation orders and warnings touching the area, from the feeds the Evac Zone
 * catalog lists (PLAN 4.1): live agency feeds, plus pre-planned zones where an agency
 * publishes only those. Where no agency publishes, the line says so.
 */
public final class Evacuations extends Section {

    static final String CATALOG = "https://mapdepot.takwerx.org/evaczone/catalog.json";

    public Evacuations() {
        super("evacuations", "Evacuations", true,
                "The agency evacuation feeds in the takwerx Evac Zone catalog (Cal OES, Oregon OSFM, county feeds).");
        seeIn = "See them on the map in Evac Zone.";
        openName = "Evac Zone";
        openPackage = "com.atakmap.android.evaczone.plugin";
    }

    @Override
    public Result fetch(Area area) throws Exception {
        final Counties counties = Counties.of(area);
        final JSONArray sources = new JSONObject(Http.get(CATALOG)).optJSONArray("sources");
        if (sources == null)
            throw new java.io.IOException("no sources in the evacuation catalog");
        final Map<String, List<String>> byStatus = new LinkedHashMap<>();
        final Map<String, List<String>> planned = new LinkedHashMap<>();
        boolean covered = false;
        for (int i = 0; i < sources.length(); i++) {
            final JSONObject s = sources.optJSONObject(i);
            if (s == null || !counties.states.contains(s.optString("st")))
                continue;
            final String url = s.optString("url", "");
            if (!url.startsWith("https://") || !"polygon".equals(s.optString("geometry")))
                continue;
            if (!overlaps(area, s.optJSONArray("bounds")))
                continue;
            covered = true;
            final boolean live = "live".equals(s.optString("kind"));
            final String status = field(s.optString("status_field", ""));
            final String name = field(s.optString("name_field", ""));
            final String fields = status == null && name == null ? "OBJECTID"
                    : (status == null ? "" : status) + (status != null && name != null ? "," : "") + (name == null ? "" : name);
            final String layer = url.replaceAll("/+$", "") + "/" + Math.max(0, s.optInt("layer", 0));
            final List<JSONObject> rows = Esri.attributes(layer, s.optString("where", "1=1"), area, fields);
            for (JSONObject a : rows) {
                final String zone = name == null ? null : clean(a.optString(name, ""));
                if (live) {
                    String st = status == null ? "In effect" : clean(a.optString(status, ""));
                    if (st == null || st.isEmpty())
                        st = "Status not given";
                    add(byStatus, st, zone);
                } else {
                    add(planned, clean(s.optString("title", "Pre-planned zones")), zone);
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
