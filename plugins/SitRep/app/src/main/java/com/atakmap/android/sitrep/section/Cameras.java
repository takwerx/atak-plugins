package com.atakmap.android.sitrep.section;

import com.atakmap.android.sitrep.Area;
import com.atakmap.android.sitrep.Units;
import com.atakmap.android.sitrep.net.Http;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Cameras in the area and within Nearby, from the Cam Depot catalog (PLAN 4.9):
 * traffic cameras and wildfire cameras. Watch them in Cam Depot.
 */
public final class Cameras extends Section {

    static final String STATIC = "https://mapdepot.takwerx.org/camdepot/static/";

    public Cameras() {
        super("cameras", "Cameras", false,
                "The Cam Depot catalog: state DOT traffic cameras, ALERTWildfire and other wildfire cameras, FAA weather cameras.");
        seeIn = "Watch them in Cam Depot.";
        openName = "Cam Depot";
        openPackage = "com.atakmap.android.camdepot.plugin";
        openAction = "com.atakmap.android.camdepot.SHOW";
    }

    @Override
    public Result fetch(Area area) throws Exception {
        return fetch(area, 0);
    }

    @Override
    public Result fetch(Area area, int nearbyMiles) throws Exception {
        final Counties counties = Counties.of(area);
        if (counties.states.isEmpty()) {
            final Result r = new Result("Not published here", null);
            r.notPublishedHere = true;
            return r.line("The camera catalog covers the United States.");
        }
        final double reach = nearbyMiles * 1609.344;
        final List<JSONObject> cams = new ArrayList<>();
        for (String st : counties.states) {
            final String body;
            try {
                body = Http.getGzip(STATIC + st + ".json.gz");
            } catch (Http.StatusException e) {
                if (e.status == 404 || e.status == 403)
                    continue;   // no cameras in that state's catalog
                throw e;
            }
            final JSONArray all = new JSONArray(body);
            for (int i = 0; i < all.length(); i++) {
                final JSONObject c = all.optJSONObject(i);
                if (c == null)
                    continue;
                final double lat = c.optDouble("lat", Double.NaN), lon = c.optDouble("lon", Double.NaN);
                if (!(Math.abs(lat) <= 90) || !(Math.abs(lon) <= 180))
                    continue;
                final double d = area.distanceMeters(lat, lon);
                if (d > reach)
                    continue;
                c.put("_d", d);
                cams.add(c);
            }
        }
        if (cams.isEmpty())
            return new Result("None", null).line("No camera in the catalog is in the area"
                    + (nearbyMiles > 0 ? " or within " + nearbyMiles + " mi." : "."));
        Collections.sort(cams, new Comparator<JSONObject>() {
            @Override
            public int compare(JSONObject a, JSONObject b) {
                return Double.compare(a.optDouble("_d"), b.optDouble("_d"));
            }
        });
        int inside = 0, fire = 0;
        final Map<String, List<JSONObject>> byProvider = new TreeMap<>();
        for (JSONObject c : cams) {
            if (c.optDouble("_d") == 0)
                inside++;
            if (c.optInt("fire", 0) == 1)
                fire++;
            final String pr = clip(c.optString("pr", "Other"), 40);
            List<JSONObject> l = byProvider.get(pr);
            if (l == null) {
                l = new ArrayList<>();
                byProvider.put(pr, l);
            }
            l.add(c);
        }
        final String value = inside + " in the area"
                + (nearbyMiles > 0 ? ", " + (cams.size() - inside) + " within " + nearbyMiles + " mi" : "")
                + (fire > 0 ? "; " + fire + " wildfire" : "");
        final Result r = new Result(value, cams.size() + (cams.size() == 1 ? " camera" : " cameras"));
        for (Map.Entry<String, List<JSONObject>> e : byProvider.entrySet()) {
            r.line(GROUP + e.getKey() + ": " + e.getValue().size());
            int shown = 0;
            for (JSONObject c : e.getValue()) {
                if (++shown > 25) {
                    r.line("  ... and " + (e.getValue().size() - 25) + " more, nearest first.");
                    break;
                }
                final double d = c.optDouble("_d");
                r.line("  " + clip(c.optString("n", "Camera").replace('_', ' '), 70)
                        + (c.optInt("ptz", 0) == 1 ? " (turns)" : "")
                        + (d == 0 ? "" : ", " + Units.distance(d) + " " + area.directionTo(c.optDouble("lat"), c.optDouble("lon"))));
            }
        }
        return r;
    }

    private static String clip(String s, int max) {
        s = s == null ? "" : s.replaceAll("\\s+", " ").trim();
        return s.length() <= max ? s : s.substring(0, max - 3).trim() + "...";
    }
}
