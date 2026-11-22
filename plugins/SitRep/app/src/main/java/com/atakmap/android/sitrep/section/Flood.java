package com.atakmap.android.sitrep.section;

import com.atakmap.android.sitrep.Area;
import com.atakmap.android.sitrep.net.Esri;

import org.json.JSONObject;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;

/**
 * FEMA flood zones in the area, from the National Flood Hazard Layer (PLAN 4.12,
 * decision 16: no takwerx plugin has them; Atmosphere's "flood" is WPC's rainfall
 * outlook, a different thing). One grouped count, no shapes: which zones the
 * effective flood map shows here. No map at all is said as such, never as "none".
 */
public final class Flood extends Section {

    static final String NFHL = "https://hazards.fema.gov/arcgis/rest/services/public/NFHL/MapServer/28";

    public Flood() {
        super("flood", "Flood zones", false,
                "FEMA National Flood Hazard Layer: the effective flood insurance rate maps.");
        // The zones are drawn by Atmosphere (operator, 2026-11-14).
        seeIn = "See the flood zones on the map in Atmosphere.";
        openName = "Atmosphere";
        openPackage = "com.atakmap.android.atmosphere.plugin";
        openAction = "com.atakmap.android.atmosphere.SHOW";
    }

    @Override
    public Result fetch(Area area) throws Exception {
        // hazards.fema.gov resets a TLS connection now and then, from any network
        // (0.4 release record): one more try before the section says it did not answer.
        List<JSONObject> rows;
        try {
            rows = zones(area);
        } catch (java.io.IOException e) {
            Thread.sleep(1_500);
            rows = zones(area);
        }
        if (rows.isEmpty()) {
            final Result r = new Result("No FEMA flood map here", null);
            r.notPublishedHere = true;
            return r.line("FEMA has no digital flood map for this area. That is not the same as no flood risk.");
        }
        final Set<String> onePct = new TreeSet<>();
        boolean floodway = false, coastal = false, pointTwo = false, levee = false, undetermined = false,
                minimal = false;
        for (JSONObject a : rows) {
            final String zone = a.optString("FLD_ZONE", "").trim().toUpperCase(Locale.US);
            // Zone codes are a few letters and digits; anything else is not shown.
            if (!zone.matches("[A-Z0-9]{1,6}"))
                continue;
            final String sub = a.isNull("ZONE_SUBTY") ? "" : a.optString("ZONE_SUBTY", "").toUpperCase(Locale.US);
            if (zone.startsWith("V")) {
                coastal = true;
                onePct.add(zone);
            } else if (zone.startsWith("A")) {
                onePct.add(zone);
            } else if ("D".equals(zone)) {
                undetermined = true;
            } else if (zone.startsWith("X")) {
                if (sub.startsWith("0.2 PCT"))
                    pointTwo = true;
                else if (sub.contains("LEVEE"))
                    levee = true;
                else
                    minimal = true;
            }
            if (sub.contains("FLOODWAY"))
                floodway = true;
        }
        final Result r = new Result(onePct.isEmpty() ? "No 1% flood zone mapped" : "1% flood zone here",
                onePct.isEmpty() ? null : "1% flood zone");
        if (!onePct.isEmpty()) {
            r.line(GROUP + "1% annual chance flood (100-year): zone " + join(onePct));
            if (floodway)
                r.line("  Floodway: the channel and banks that carry the flood flow.");
            if (coastal)
                r.line("  Coastal high hazard (V zones): waves 3 ft or more.");
        } else
            r.line("No 1% annual chance (100-year) flood zone on the map here.");
        if (pointTwo)
            r.line("0.2% annual chance flood (500-year) area here.");
        if (levee)
            r.line("Area behind a levee, with reduced flood risk.");
        if (undetermined)
            r.line("Zone D: flood hazard not studied.");
        if (minimal && onePct.isEmpty() && !pointTwo && !levee && !undetermined)
            r.line("All of the map here is minimal flood hazard (zone X).");
        return r;
    }

    /**
     * The zone fields of every flood area touching the area, a page at a time. A
     * grouped count or a distinct query with a city's outline answers 400 "Unable to
     * complete operation" (Portland, 2026-11-14); the plain list does not.
     */
    private static List<JSONObject> zones(Area area) throws Exception {
        final List<JSONObject> out = new java.util.ArrayList<>();
        final int page = 2000;
        for (int p = 0; p < 20; p++) {
            final String form = "where=1%3D1&f=json&returnGeometry=false"
                    + "&outFields=" + Esri.enc("FLD_ZONE,ZONE_SUBTY")
                    + "&orderByFields=OBJECTID&resultOffset=" + (p * page) + "&resultRecordCount=" + page
                    + area.esriQueryForm();
            final JSONObject o = new JSONObject(com.atakmap.android.sitrep.net.Http.post(NFHL + "/query", form));
            if (o.has("error"))
                throw new java.io.IOException("service error: " + o.optJSONObject("error"));
            final org.json.JSONArray f = o.optJSONArray("features");
            if (f == null)
                throw new java.io.IOException("no features in the answer");
            for (int i = 0; i < f.length(); i++) {
                final JSONObject a = f.optJSONObject(i) == null ? null : f.optJSONObject(i).optJSONObject("attributes");
                if (a != null)
                    out.add(a);
            }
            // The server pages before it filters by the outline: a page comes back
            // short, even empty, with more to come. Only its flag says when to stop
            // (Portland, 2026-11-14: 937, then 57 at offset 2000).
            if (!o.optBoolean("exceededTransferLimit", false))
                break;
        }
        return out;
    }

    private static String join(Set<String> s) {
        final StringBuilder b = new StringBuilder();
        for (String z : s)
            b.append(b.length() == 0 ? "" : ", ").append(z);
        return b.toString();
    }
}
