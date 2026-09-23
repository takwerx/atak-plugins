
package com.atakmap.android.atmosphere.overlay;

import android.content.SharedPreferences;

import com.atakmap.android.atmosphere.compat.MapCompat;
import com.atakmap.android.atmosphere.data.Nhc;
import com.atakmap.android.atmosphere.net.EgressPolicy;
import com.atakmap.android.atmosphere.net.Http;
import com.atakmap.android.drawing.mapItems.DrawingShape;
import com.atakmap.android.maps.DefaultMapGroup;
import com.atakmap.android.maps.MapGroup;
import com.atakmap.android.maps.MapView;
import com.atakmap.coremap.log.Log;
import com.atakmap.coremap.maps.coords.GeoPoint;
import com.atakmap.map.layer.feature.Feature;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Live tropical cyclones on the map: the forecast cone, the forecast track, and where
 * the storm is now.
 *
 * <p>Two requests per storm and one for the list, all small. {@code CurrentStorms.json}
 * says what is running; NOAA's tropical ArcGIS service hands back the shapes as GeoJSON,
 * so nothing here unpacks a shapefile or a KMZ. See {@link Nhc} for the join between
 * them, which is the storm's slot rather than its name.
 *
 * <p>The shapes are ordinary ATAK map items in the plugin's own group, so they appear in
 * Overlay Manager and ATAK's own visibility controls work on them. They are clamped to
 * the ground: a plugin shape whose points carry an altitude sinks under the terrain the
 * moment somebody tilts or zooms, and a cone that vanishes reads as a broken layer.
 *
 * <p>Nothing is polled. An advisory is issued every six hours and this refreshes when
 * the layer is switched on and when the pane is opened, which is when somebody is
 * actually looking.
 */
public final class TropicalOverlay {

    private static final String TAG = "AtmosphereTropical";

    public static final String LAYER_ID = "tropical";
    public static final String HOST = Nhc.MAP_HOST;

    private static final String PREF_ON = "weather.layer.tropical.on";
    private static final String GROUP = "Hurricanes";

    /** An advisory is six-hourly; asking again inside this is asking for the same file. */
    private static final long REFRESH_MS = 10 * 60 * 1000L;

    /** The cone is a forecast, not a boundary: outlined, barely filled, never solid. */
    private static final int CONE_STROKE = 0xFFFFFFFF;
    private static final int CONE_FILL = 0x33FFFFFF;
    private static final double CONE_WEIGHT = 2.0;
    private static final double TRACK_WEIGHT = 3.0;

    public interface Listener {
        /** What is running, in words, for the pane. Empty when nothing is. */
        void onStorms(List<String> storms);

        /** A line for the pane while something is happening, or "". */
        void onStatus(String status);
    }

    private final MapView mapView;
    private final EgressPolicy egress;

    private MapGroup group;
    private Listener listener;
    private boolean started;
    private boolean on;
    private long lastRefresh;
    /** Bumped whenever the layer is cleared, so a late response cannot redraw. */
    private int generation;
    private final List<String> names = new ArrayList<>();

    public TropicalOverlay(MapView mapView, EgressPolicy egress) {
        this.mapView = mapView;
        this.egress = egress;
    }

    public void setListener(Listener l) {
        listener = l;
        if (l != null) {
            l.onStorms(new ArrayList<>(names));
            l.onStatus("");
        }
    }

    public void start() {
        started = true;
        group = new DefaultMapGroup(GROUP);
        group.setMetaString("overlay", GROUP);
        group.setMetaBoolean("permaGroup", true);
        mapView.getRootGroup().addGroup(group);
        final SharedPreferences p = MapCompat.prefs();
        if (p != null && p.getBoolean(PREF_ON, false) && egress.isLayerEnabled(LAYER_ID))
            setOn(true);
    }

    public void stop() {
        started = false;
        on = false;
        clear();
        if (group != null) {
            // Off the group's own thread work and out of the tree: a plugin that
            // leaves items behind leaves them on the map with nothing to remove them.
            mapView.getRootGroup().removeGroup(group);
            group = null;
        }
    }

    public boolean isOn() {
        return on;
    }

    public void setOn(boolean value) {
        if (!started || on == value)
            return;
        on = value;
        final SharedPreferences p = MapCompat.prefs();
        if (p != null)
            p.edit().putBoolean(PREF_ON, value).apply();
        if (value) {
            refresh(true);
        } else {
            clear();
            names.clear();
            if (listener != null) {
                listener.onStorms(new ArrayList<String>());
                listener.onStatus("");
            }
        }
    }

    /** The storms being drawn, in words. */
    public List<String> storms() {
        return new ArrayList<>(names);
    }

    /**
     * Ask NHC what is running and draw it. Cheap enough to call whenever the pane is
     * opened; {@code force} is for the toggle, which should answer immediately even if
     * the list was read a minute ago.
     */
    public void refresh(boolean force) {
        if (!on || !started)
            return;
        final long now = System.currentTimeMillis();
        if (!force && now - lastRefresh < REFRESH_MS)
            return;
        lastRefresh = now;
        status("Looking for storms…");
        final int mine = generation;
        Http.get(Nhc.CURRENT_STORMS, egress.userAgent(), null, new Http.Callback() {
            @Override
            public void onSuccess(String body) {
                if (mine != generation || !on)
                    return;
                final List<Nhc.Storm> storms = Nhc.parseActive(body);
                clear();
                names.clear();
                if (storms.isEmpty()) {
                    // Saying so is the point: an empty map and a broken layer look
                    // identical, and most of the year this is the true answer.
                    status("No storms right now");
                    if (listener != null)
                        listener.onStorms(new ArrayList<String>());
                    return;
                }
                for (Nhc.Storm s : storms) {
                    names.add(label(s));
                    draw(s, generation);
                }
                status("");
                if (listener != null)
                    listener.onStorms(new ArrayList<>(names));
            }

            @Override
            public void onFailure(String error) {
                if (mine != generation || !on)
                    return;
                Log.w(TAG, "storm list failed: " + error);
                status("Could not reach the hurricane center");
            }
        });
    }

    /** "Hurricane Polo, 150 mph" -- what it is, and how hard, in one line. */
    private String label(Nhc.Storm s) {
        final StringBuilder b = new StringBuilder(s.display());
        if (s.intensityKt > 0) {
            // Knots are how the advisory is written and how nobody outside aviation
            // reads it; the pane's own unit choice does not reach the map, so this is
            // the number a crew would hear on the news.
            b.append(", ").append(Math.round(s.intensityKt * 1.15078)).append(" mph");
            if (s.category() > 0)
                b.append(" (cat ").append(s.category()).append(')');
        }
        return b.toString();
    }

    private void draw(final Nhc.Storm storm, final int mine) {
        final String name = label(storm);
        // The cone first: it is the biggest thing and the one people look for.
        fetchShape(Nhc.coneLayer(storm.bin), mine, name + " cone", CONE_STROKE,
                CONE_FILL, CONE_WEIGHT, true);
        fetchShape(Nhc.trackLayer(storm.bin), mine, name + " track",
                trackColor(storm), 0, TRACK_WEIGHT, false);
    }

    /** Category color, the way every outlet draws it: hotter is stronger. */
    private static int trackColor(Nhc.Storm s) {
        switch (s.category()) {
            case 5:
            case 4:
                return 0xFFFF3030;
            case 3:
                return 0xFFFF8000;
            case 2:
            case 1:
                return 0xFFFFD000;
            default:
                return 0xFF50C0FF;
        }
    }

    private void fetchShape(final int layer, final int mine, final String title,
            final int stroke, final int fill, final double weight, final boolean closed) {
        if (layer < 0)
            return;
        Http.get(Nhc.queryUrl(layer), egress.userAgent(), null, new Http.Callback() {
            @Override
            public void onSuccess(String body) {
                if (mine != generation || !on || group == null)
                    return;
                final List<GeoPoint[]> rings = geometry(body);
                if (rings.isEmpty()) {
                    Log.d(TAG, "no geometry in layer " + layer + " for " + title);
                    return;
                }
                int n = 0;
                for (GeoPoint[] ring : rings) {
                    if (ring.length < 2)
                        continue;
                    final DrawingShape shape = new DrawingShape(mapView, group,
                            UUID.randomUUID().toString());
                    shape.setPoints(ring);
                    shape.setClosed(closed);
                    shape.setStrokeColor(stroke);
                    shape.setStrokeWeight(weight);
                    if (closed)
                        shape.setFillColor(fill);
                    shape.setTitle(rings.size() > 1 ? title + " " + (++n) : title);
                    // Without this the shape sinks under the terrain the moment the
                    // map is tilted or zoomed, and reads as a layer that did nothing.
                    shape.setAltitudeMode(Feature.AltitudeMode.ClampToGround);
                    shape.setMetaBoolean("removable", false);
                    shape.setMetaString("menu", "");
                    group.addItem(shape);
                    Log.d(TAG, String.format(Locale.US,
                            "drew %s: %d points, %.2f,%.2f..%.2f,%.2f", shape.getTitle(),
                            ring.length, bound(ring, true, true), bound(ring, false, true),
                            bound(ring, true, false), bound(ring, false, false)));
                }
            }

            @Override
            public void onFailure(String error) {
                if (mine != generation || !on)
                    return;
                Log.w(TAG, "layer " + layer + " failed: " + error);
            }
        });
    }

    /**
     * Every ring and line in a GeoJSON body, as ATAK points.
     *
     * <p>Only the outer ring of a polygon is taken. A forecast cone has no holes, and
     * an inner ring drawn as another outline would read as a second cone.
     */
    static List<GeoPoint[]> geometry(String json) {
        final List<GeoPoint[]> out = new ArrayList<>();
        if (json == null || json.isEmpty())
            return out;
        try {
            final JSONArray feats = new JSONObject(json).optJSONArray("features");
            if (feats == null)
                return out;
            for (int i = 0; i < feats.length(); i++) {
                final JSONObject f = feats.optJSONObject(i);
                if (f == null)
                    continue;
                final JSONObject g = f.optJSONObject("geometry");
                if (g == null)
                    continue;
                final String type = g.optString("type", "");
                final JSONArray c = g.optJSONArray("coordinates");
                if (c == null)
                    continue;
                if (type.equals("LineString"))
                    add(out, ring(c));
                else if (type.equals("MultiLineString") || type.equals("Polygon"))
                    for (int k = 0; k < c.length(); k++)
                        add(out, ring(c.optJSONArray(k)));
                else if (type.equals("MultiPolygon"))
                    for (int k = 0; k < c.length(); k++) {
                        final JSONArray poly = c.optJSONArray(k);
                        if (poly != null && poly.length() > 0)
                            add(out, ring(poly.optJSONArray(0)));
                    }
            }
        } catch (Exception e) {
            Log.w(TAG, "geometry unreadable", e);
        }
        return out;
    }

    private static void add(List<GeoPoint[]> out, GeoPoint[] ring) {
        if (ring != null && ring.length >= 2)
            out.add(ring);
    }

    /** One ring of [lon, lat] pairs. GeoJSON is lon first; GeoPoint is lat first. */
    private static GeoPoint[] ring(JSONArray coords) {
        if (coords == null)
            return null;
        final List<GeoPoint> pts = new ArrayList<>(coords.length());
        for (int i = 0; i < coords.length(); i++) {
            final JSONArray p = coords.optJSONArray(i);
            if (p == null || p.length() < 2)
                continue;
            final double lon = p.optDouble(0, Double.NaN);
            final double lat = p.optDouble(1, Double.NaN);
            if (Double.isNaN(lat) || Double.isNaN(lon))
                continue;
            pts.add(new GeoPoint(lat, lon));
        }
        return pts.toArray(new GeoPoint[0]);
    }

    /** A corner of a ring, for the log: min or max of lat or lon. */
    private static double bound(GeoPoint[] ring, boolean lat, boolean min) {
        double v = min ? Double.MAX_VALUE : -Double.MAX_VALUE;
        for (GeoPoint p : ring) {
            final double x = lat ? p.getLatitude() : p.getLongitude();
            v = min ? Math.min(v, x) : Math.max(v, x);
        }
        return v;
    }

    /** Drop everything drawn and make any response in flight land nowhere. */
    private void clear() {
        generation++;
        if (group != null)
            group.clearItems();
    }

    private void status(String s) {
        if (listener != null)
            listener.onStatus(s);
    }
}
