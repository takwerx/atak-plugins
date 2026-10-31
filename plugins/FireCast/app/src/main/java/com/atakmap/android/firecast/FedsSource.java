package com.atakmap.android.firecast;

import com.atakmap.map.layer.feature.geometry.Envelope;
import com.atakmap.map.layer.feature.geometry.Geometry;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * FEDS Perimeters: NASA's Fire Event Data Suite (Chen et al. 2022) draws an outline
 * around every fire's VIIRS detections, every 12 hours, day pass and night pass, for the
 * lower 48 - so a fire nobody has mapped has a perimeter, and one between the incident's
 * own updates has a newer one. Built from the same detections as the VIIRS feed, which
 * is why it sits with the feeds and not with the model runs.
 *
 * <p>It is a GIS-style perimeter, but an algorithm's: 375 m steps, and every edge it ever
 * reached, burning or not. The panel says so; it is never the incident's mapped line.
 *
 * <p>Served open as OGC API Features on NASA VEDA. Ask for f=geojson: this server answers
 * f=json with a flat list and WKT geometry.
 */
public class FedsSource implements DetectionSource {

    static final String ITEMS = "https://openveda.cloud/api/features/collections/"
            + "public.eis_fire_snapshot_perimeter_nrt/items";
    /** One answer at most; a 12-degree view in a busy week held about 100. */
    private static final int MAX_FEATURES = 200, MAX_VERTICES = 200_000;
    /**
     * Outlines are 12 hours apart and reach the service up to a day and more after the pass, so
     * a window shorter than this would show none: the window is never narrower.
     */
    private static final long MIN_WINDOW_MS = Filters.FEDS_WINDOW_MS;
    private static final double KM2_TO_ACRES = 247.105d;

    @Override
    public String id() {
        return "feds";
    }

    @Override
    public String title() {
        return "NASA Fire Event Perimeters";
    }

    @Override
    public String summary() {
        return "NASA's Fire Event Data Suite: fire outlines from the VIIRS detections, every 12 hours";
    }

    @Override
    public boolean defaultOn() {
        return false;
    }

    private static long since(long sinceMillis) {
        return Math.min(sinceMillis, System.currentTimeMillis() - MIN_WINDOW_MS);
    }

    @Override
    public int count(Esri.Box box, long sinceMillis) throws Exception {
        final JSONArray features = query(box, true);
        final long cut = since(sinceMillis);
        int n = 0;
        for (int i = 0; features != null && i < features.length() && i < MAX_FEATURES; i++) {
            final JSONObject f = features.optJSONObject(i);
            final JSONObject p = f == null ? null : f.optJSONObject("properties");
            if (p != null && Sources.parseIso(p.optString("t", "")) >= cut)
                n++;
        }
        return n;
    }

    @Override
    public List<HeatDetection> fetch(Esri.Box box, long sinceMillis, int max) throws Exception {
        final JSONArray features = query(box, false);
        final List<HeatDetection> out = new ArrayList<>();
        final long cut = since(sinceMillis);
        int vertices = 0;
        for (int i = 0; features != null && i < features.length() && i < MAX_FEATURES; i++) {
            if (max > 0 && out.size() >= max)
                break;
            final JSONObject f = features.optJSONObject(i);
            final JSONObject p = f == null ? null : f.optJSONObject("properties");
            if (p == null || Sources.parseIso(p.optString("t", "")) < cut)
                continue;
            final JSONArray rings = esriRings(f.optJSONObject("geometry"));
            if (rings == null)
                continue;
            for (int r = 0; r < rings.length(); r++)
                vertices += rings.getJSONArray(r).length();
            if (vertices > MAX_VERTICES)
                break;
            final JSONObject a = new JSONObject();
            for (String k : new String[] { "fireid", "t", "farea", "fperim", "flinelen", "duration", "meanfrp",
                    "n_pixels", "n_newpixels", "isactive" })
                if (p.has(k) && !p.isNull(k))
                    a.put(k, p.get(k));
            a.put("id", f.optString("id", ""));
            final JSONObject esri = new JSONObject().put("rings", rings)
                    .put("spatialReference", new JSONObject().put("wkid", 4326));
            final Geometry g = Esri.fromEsriJson(esri);
            final HeatDetection d = g == null ? null : read(a, g, esri);
            if (d != null)
                out.add(d);
        }
        return out;
    }

    /** The service's features in the box, newest first; geometry left out for a count. */
    private static JSONArray query(Esri.Box box, boolean boxesOnly) throws Exception {
        final String url = ITEMS + "?f=geojson&sortby=-t&limit=" + MAX_FEATURES
                + (boxesOnly ? "&bbox-only=true&properties=t" : "")
                + "&bbox=" + String.format(Locale.US, "%.4f,%.4f,%.4f,%.4f", box.west, box.south, box.east, box.north);
        return new JSONObject(Esri.get(url)).optJSONArray("features");
    }

    /**
     * GeoJSON polygons as Esri rings: the outer ring of each part only, thinned, closed and
     * turned clockwise, which is how Esri JSON says "a new outer ring" (counter-clockwise
     * would be read as a hole in the part before it).
     */
    private static JSONArray esriRings(JSONObject g) throws Exception {
        if (g == null)
            return null;
        final String type = g.optString("type", "");
        final JSONArray c = g.optJSONArray("coordinates");
        if (c == null)
            return null;
        final List<JSONArray> outer = new ArrayList<>();
        if ("Polygon".equals(type))
            outer.add(c.optJSONArray(0));
        else if ("MultiPolygon".equals(type))
            for (int i = 0; i < c.length(); i++) {
                final JSONArray poly = c.optJSONArray(i);
                outer.add(poly == null ? null : poly.optJSONArray(0));
            }
        final JSONArray rings = new JSONArray();
        for (JSONArray r : outer) {
            if (r == null || r.length() < 4 || r.length() > MAX_VERTICES)
                continue;
            // Every corner read, then simplified by distance, the same as the model's start:
            // skipping every Nth corner cut the tips off fingers (operator, 2026-10-30).
            final double[][] full = new double[r.length()][];
            for (int i = 0; i < full.length; i++) {
                final JSONArray p = r.optJSONArray(i);
                final double lon = p == null ? Double.NaN : p.optDouble(0, Double.NaN), lat = p == null ? Double.NaN : p.optDouble(1, Double.NaN);
                if (!(Math.abs(lat) <= 90) || !(Math.abs(lon) <= 180))
                    return null;
                full[i] = new double[] { lat, lon };
            }
            final List<double[]> pts = new ArrayList<>();
            for (double[] q : com.atakmap.android.firecast.model.Feds.thin(full))
                pts.add(new double[] { q[1], q[0] });
            if (pts.size() > 1 && pts.get(0)[0] == pts.get(pts.size() - 1)[0] && pts.get(0)[1] == pts.get(pts.size() - 1)[1])
                pts.remove(pts.size() - 1);
            if (pts.size() < 3)
                continue;
            double area2 = 0;
            for (int i = 0; i < pts.size(); i++) {
                final double[] a = pts.get(i), b = pts.get((i + 1) % pts.size());
                area2 += a[0] * b[1] - b[0] * a[1];
            }
            if (area2 > 0)
                java.util.Collections.reverse(pts);
            final JSONArray ring = new JSONArray();
            for (double[] p : pts)
                ring.put(new JSONArray().put(p[0]).put(p[1]));
            ring.put(new JSONArray().put(pts.get(0)[0]).put(pts.get(0)[1]));
            rings.put(ring);
        }
        return rings.length() == 0 ? null : rings;
    }

    @Override
    public HeatDetection read(JSONObject a, Geometry g, JSONObject esri) {
        final Envelope e = g == null ? null : g.getEnvelope();
        if (e == null || Double.isNaN(e.minX))
            return null;
        final long at = Sources.parseIso(a.optString("t", ""));
        if (at <= 0)
            return null;
        final HeatDetection d = new HeatDetection(id(), title(), "NASA Fire Event Data Suite, from VIIRS",
                (e.minY + e.maxY) / 2d, (e.minX + e.maxX) / 2d, at, Footprint.outlined(g));
        final double km2 = a.optDouble("farea", Double.NaN), days = a.optDouble("duration", Double.NaN);
        final boolean active = a.optInt("isactive", 0) == 1;
        d.incidentName = "NASA fire event perimeter" + (km2 >= 0 && km2 < 1e6 ? String.format(Locale.US, ", %,.0f acres", km2 * KM2_TO_ACRES) : "");
        d.classification = active ? "active" : "not active at the last pass";
        d.trackingId = a.optString("id", null);
        if (d.trackingId != null && !d.trackingId.matches("[A-Z]{2,12}\\|[0-9.]{1,12}\\|[0-9T:-]{10,25}"))
            d.trackingId = null;
        d.persistenceHours = days >= 0 && days < 1e5 ? days * 24d : null;
        d.confidenceText = null;
        final List<String> caveats = new ArrayList<>();
        caveats.add("Not the incident's mapped perimeter. An algorithm drew it around the VIIRS detections; incident GIS draws the official one.");
        caveats.add("Its edges are 375 m steps, the size of a VIIRS pixel, so a line can be off by that much either way.");
        caveats.add("It is every edge the fire has reached, burning or not. Where the fire is active now is where new detections are.");
        caveats.add("It is as of the satellite pass, every 12 hours and up to a day late. Cloud or thick smoke at a pass leaves the outline where it was.");
        d.caveats = caveats.toArray(new String[0]);
        d.record = PolarSource.record(a, esri);
        d.raw = Esri.toAttributes(a);
        return d;
    }
}
