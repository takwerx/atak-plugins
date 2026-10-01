package com.atakmap.android.atmosphere.data;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Which US state a point is in, from outlines shipped in the plugin
 * ({@code assets/us_states.json}): the Census Bureau's 1:20,000,000 cartographic
 * boundaries (cb_2023_us_state_20m, public domain), 50 states, DC and Puerto Rico,
 * simplified to about a kilometer -- 7,173 points, 110 KB.
 *
 * <p>For the spot forecasts' State filter: NWS's spot service gives a point and no
 * state. Checked 2026-09-24 against the states the Spot Monitor itself recorded for
 * the same requests: 406 of 407 agreed, the one other a point in Alaska's inside
 * passage the Monitor called ocean. A point in no state is at sea.
 */
public final class States {

    private final Map<String, GeoRings.Area> areas;

    private States(Map<String, GeoRings.Area> areas) {
        this.areas = areas;
    }

    /** Read the asset: {"CA": [[[lon, lat, ...], hole...], polygon...], ...}. */
    public static States parse(String json) throws Exception {
        final JSONObject root = new JSONObject(json);
        final Map<String, GeoRings.Area> out = new LinkedHashMap<>();
        final Iterator<String> keys = root.keys();
        while (keys.hasNext()) {
            final String code = keys.next();
            final JSONArray polys = root.getJSONArray(code);
            final double[][][] flat = new double[polys.length()][][];
            for (int p = 0; p < polys.length(); p++) {
                final JSONArray rings = polys.getJSONArray(p);
                flat[p] = new double[rings.length()][];
                for (int r = 0; r < rings.length(); r++) {
                    final JSONArray ring = rings.getJSONArray(r);
                    final double[] v = new double[ring.length()];
                    for (int i = 0; i < v.length; i++)
                        v[i] = ring.getDouble(i);
                    flat[p][r] = v;
                }
            }
            final GeoRings.Area a = GeoRings.fromFlat(flat);
            if (a != null)
                out.put(code, a);
        }
        return new States(out);
    }

    /** The state's two-letter code, or "" at sea or outside the country. */
    public String at(double lat, double lon) {
        for (Map.Entry<String, GeoRings.Area> e : areas.entrySet())
            if (e.getValue().contains(lat, lon))
                return e.getKey();
        return "";
    }

    public int size() {
        return areas.size();
    }
}
