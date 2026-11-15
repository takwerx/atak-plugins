package com.atakmap.android.sitrep.section;

import com.atakmap.android.sitrep.Area;
import com.atakmap.android.sitrep.net.Http;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * What airspace is over the area and whether a TFR covers any of it (PLAN 4.9;
 * operator, 2026-11-13: "what class airspace as in here, is there a tfr in here").
 *
 * <p>Airspace from AirAware's 2-degree tiles of the FAA Airspace layer on our own
 * server (never the FAA services6 layer: one shared quota). Class A, over the whole
 * country from 18,000 ft, is left out. TFRs from the FAA TFR WFS (shapes) joined to
 * the FAA list (what is active). A TFR with no shape is either a nationwide notice
 * (state "USA") or an area drawn only in the NOTAM text (the US-Mexico border's
 * National Defense Airspace); the second kind is listed when it is in the area's state.
 */
public final class Airspace extends Section {

    static final String TILES = "https://mapdepot.takwerx.org/airaware/airspace/tiles/";
    static final String TFR_LIST = "https://tfr.faa.gov/tfrapi/exportTfrList";
    static final String TFR_SHAPES = "https://tfr.faa.gov/geoserver/TFR/ows?service=WFS&version=1.1.0"
            + "&request=GetFeature&typeName=TFR:V_TFR_LOC&maxFeatures=1000"
            + "&outputFormat=application/json&srsname=EPSG:4326";
    /** The tile grid, degrees; the pack's manifest says 2. */
    private static final int STEP = 2;

    public Airspace() {
        super("airspace", "Airspace", true,
                "FAA Airspace (classes B to E and special use), through the takwerx AirAware tiles; FAA TFR list and TFR map service.");
        seeIn = "See them on the map in AirAware.";
        openName = "AirAware";
        openPackage = "com.atakmap.android.airaware.plugin";
        openAction = "com.atakmap.android.airaware.SHOW";
    }

    @Override
    public Result fetch(Area area) throws Exception {
        // ---- TFRs
        final Map<String, JSONObject> active = new HashMap<>();
        final JSONArray list = new JSONArray(Http.get(TFR_LIST));
        for (int i = 0; i < list.length(); i++) {
            final JSONObject t = list.optJSONObject(i);
            if (t != null && t.optString("notam_id", "").matches("\\d{1,2}/\\d{1,5}"))
                active.put(t.optString("notam_id"), t);
        }
        final JSONArray shapes = new JSONObject(Http.get(TFR_SHAPES)).optJSONArray("features");
        if (shapes == null)
            throw new java.io.IOException("no features in the TFR map answer");
        final Map<String, JSONObject> tfrs = new LinkedHashMap<>();
        final java.util.Set<String> shaped = new java.util.HashSet<>();
        for (int i = 0; i < shapes.length(); i++) {
            final JSONObject f = shapes.optJSONObject(i);
            final JSONObject p = f == null ? null : f.optJSONObject("properties");
            if (p == null)
                continue;
            final String id = p.optString("NOTAM_KEY", "").split("-")[0];
            final JSONObject row = active.get(id);
            if (row == null)
                continue;   // in the map service, no longer in the active list
            shaped.add(id);
            if (!tfrs.containsKey(id) && touches(area, f.optJSONObject("geometry")))
                tfrs.put(id, row);
        }
        // Active TFRs with no shape: nationwide notices (state "USA") apply everywhere;
        // the rest (the US-Mexico border's National Defense Airspace, DC) are drawn only
        // in the NOTAM text, so one in the area's state is listed for the operator to read.
        final java.util.Set<String> states = new java.util.HashSet<>();
        for (JSONObject c : com.atakmap.android.sitrep.net.Esri.attributes(Roads.COUNTIES, "1=1", area, "state"))
            states.add(c.optString("state", ""));
        int nationwide = 0;
        final List<JSONObject> textOnly = new ArrayList<>();
        for (Map.Entry<String, JSONObject> e : active.entrySet()) {
            if (shaped.contains(e.getKey()))
                continue;
            final String st = e.getValue().optString("state", "").trim();
            if ("USA".equals(st))
                nationwide++;
            else if (states.contains(st))
                textOnly.add(e.getValue());
        }

        // ---- airspace
        final Map<String, List<String>> byKind = new TreeMap<>();
        final java.util.Set<String> seen = new java.util.HashSet<>();
        for (int lat = floor(area.south); lat <= floor(area.north); lat += STEP) {
            for (int lon = floor(area.west); lon <= floor(area.east); lon += STEP) {
                final String body;
                try {
                    body = Http.getGzip(TILES + lat + "_" + lon + ".json.gz");
                } catch (Http.StatusException e) {
                    if (e.status == 404 || e.status == 403)
                        continue;   // no airspace shelves in this tile
                    throw e;
                }
                final JSONArray feats = new JSONObject(body).optJSONArray("features");
                if (feats == null)
                    continue;
                for (int i = 0; i < feats.length(); i++) {
                    final JSONObject f = feats.optJSONObject(i);
                    final JSONObject a = f == null ? null : f.optJSONObject("attributes");
                    final JSONObject g = f == null ? null : f.optJSONObject("geometry");
                    if (a == null || g == null)
                        continue;
                    final String kind = kind(a);
                    if (kind == null)
                        continue;
                    final String label = label(a);
                    if (seen.contains(kind + label))
                        continue;
                    final JSONArray rings = g.optJSONArray("rings");
                    boolean hit = false;
                    for (int k = 0; rings != null && k < rings.length() && !hit; k++)
                        hit = area.touches(rings.optJSONArray(k));
                    if (!hit)
                        continue;
                    seen.add(kind + label);
                    List<String> l = byKind.get(kind);
                    if (l == null) {
                        l = new ArrayList<>();
                        byKind.put(kind, l);
                    }
                    l.add(label);
                }
            }
        }

        // ---- the report
        final StringBuilder head = new StringBuilder();
        final StringBuilder classes = new StringBuilder();
        for (String k : byKind.keySet())
            if (k.startsWith("Class "))
                classes.append(classes.length() == 0 ? "Class " : ", ").append(k.substring(6));
        head.append(classes.length() == 0 ? "Class G only" : classes);
        int special = 0;
        for (String k : byKind.keySet())
            if (!k.startsWith("Class "))
                special += byKind.get(k).size();
        if (special > 0)
            head.append("; ").append(special).append(" special use");
        head.append(tfrs.isEmpty() ? "; no TFR" : "; " + tfrs.size() + (tfrs.size() == 1 ? " TFR" : " TFRs"));
        if (!textOnly.isEmpty())
            head.append("; ").append(textOnly.size()).append(" to check");

        final Result r = new Result(head.toString(), tfrs.isEmpty() ? null
                : tfrs.size() + (tfrs.size() == 1 ? " TFR" : " TFRs"));
        if (!tfrs.isEmpty()) {
            r.line(GROUP + "TFRs: " + tfrs.size());
            for (Map.Entry<String, JSONObject> e : tfrs.entrySet()) {
                final JSONObject t = e.getValue();
                r.line("  " + e.getKey() + " " + clip(t.optString("type", ""), 40) + ": "
                        + clip(t.optString("description", ""), 160));
                r.line("  https://tfr.faa.gov/tfr3/?page=detail_" + e.getKey().replace('/', '_'));
            }
        } else {
            r.line("No TFR with a published shape touches the area.");
        }
        if (!textOnly.isEmpty()) {
            r.line(GROUP + "TFRs in this state with no mapped area: " + textOnly.size());
            r.line("  The FAA describes these areas only in the NOTAM text. Read it to know if the area is inside.");
            for (JSONObject t : textOnly) {
                final String id = t.optString("notam_id", "");
                r.line("  " + id + " " + clip(t.optString("type", ""), 40) + ": "
                        + clip(t.optString("description", ""), 160));
                if (id.matches("\\d{1,2}/\\d{1,5}"))
                    r.line("  https://tfr.faa.gov/tfr3/?page=detail_" + id.replace('/', '_'));
            }
        }
        if (nationwide > 0)
            r.line(nationwide + " nationwide security notices apply everywhere, here included (for example drone limits over national security sites).");
        for (Map.Entry<String, List<String>> e : byKind.entrySet()) {
            r.line(GROUP + e.getKey() + ": " + e.getValue().size());
            for (String s : e.getValue())
                r.line("  " + s);
        }
        r.line("Class A (18,000 ft MSL to FL600) covers the whole country and is not listed.");
        return r;
    }

    private static boolean touches(Area area, JSONObject g) {
        if (g == null)
            return false;
        final JSONArray c = g.optJSONArray("coordinates");
        if ("Polygon".equals(g.optString("type")))
            return c != null && area.touches(c.optJSONArray(0));
        if ("MultiPolygon".equals(g.optString("type")) && c != null)
            for (int i = 0; i < c.length(); i++) {
                final JSONArray poly = c.optJSONArray(i);
                if (poly != null && area.touches(poly.optJSONArray(0)))
                    return true;
            }
        return false;
    }

    /** {@code Class B}, {@code Restricted}, {@code MOA} ...; null for what is not listed. */
    private static String kind(JSONObject a) {
        final String cls = text(a, "CLASS_CODE");
        final String local = text(a, "LOCALTYPE_TXT");
        if (cls != null && !"A".equals(cls))
            return "Class " + cls;
        if (local == null)
            return null;
        switch (local) {
            case "RESTRICTED":
                return "Restricted";
            case "PROHIBITED":
                return "Prohibited";
            case "MOA":
                return "MOA";
            case "WARNING":
                return "Warning area";
            case "ALERT":
                return "Alert area";
            default:
                return null;
        }
    }

    /** {@code R-2503A: SFC to 2,000 ft MSL}. */
    private static String label(JSONObject a) {
        String name = text(a, "NAME_TXT");
        if (name == null)
            name = text(a, "IDENT_TXT");
        if (name == null)
            name = "Unnamed";
        final String lo = vert(a, "DISTVERTLOWER"), hi = vert(a, "DISTVERTUPPER");
        return clip(name, 60) + (lo.isEmpty() && hi.isEmpty() ? "" : ": " + (lo.isEmpty() ? "?" : lo)
                + " to " + (hi.isEmpty() ? "?" : hi));
    }

    private static String vert(JSONObject a, String key) {
        final String code = text(a, key + "_CODE"), uom = text(a, key + "_UOM");
        final double v = a.isNull(key + "_VAL") ? Double.NaN : a.optDouble(key + "_VAL", Double.NaN);
        if ("SFC".equals(code) || (v == 0 && !"FL".equals(uom)))
            return "SFC";
        if (Double.isNaN(v))
            return "";
        if (v < 0 || v >= 99_999)
            return "unlimited";
        if ("FL".equals(uom) || "STD".equals(code))
            return String.format(Locale.US, "FL%03d", (int) v);
        final String ref = "MSL".equals(code) || "ALT".equals(code) ? " MSL" : " AGL";
        return String.format(Locale.US, "%,d ft", (int) v) + ref;
    }

    private static int floor(double v) {
        return (int) (Math.floor(v / STEP) * STEP);
    }

    private static String text(JSONObject a, String k) {
        if (a.isNull(k))
            return null;
        final String s = a.optString(k, "").trim();
        return s.isEmpty() ? null : s;
    }

    private static String clip(String s, int max) {
        s = s == null ? "" : s.replaceAll("\\s+", " ").trim();
        return s.length() <= max ? s : s.substring(0, max - 3).trim() + "...";
    }
}
