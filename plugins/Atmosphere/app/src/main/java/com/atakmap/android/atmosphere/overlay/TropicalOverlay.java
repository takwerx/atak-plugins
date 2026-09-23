
package com.atakmap.android.atmosphere.overlay;

import android.content.Context;
import android.content.SharedPreferences;

import com.atakmap.android.atmosphere.compat.MapCompat;
import com.atakmap.android.atmosphere.data.Nhc;
import com.atakmap.android.atmosphere.net.EgressPolicy;
import com.atakmap.android.atmosphere.net.Http;
import com.atakmap.android.drawing.mapItems.DrawingShape;
import com.atakmap.android.maps.DefaultMapGroup;
import com.atakmap.android.maps.MapGroup;
import com.atakmap.android.maps.MapView;
import com.atakmap.android.maps.Marker;
import com.atakmap.android.atmosphere.plugin.R;
import com.atakmap.coremap.maps.assets.Icon;
import com.atakmap.coremap.log.Log;
import com.atakmap.coremap.maps.coords.GeoPoint;
import com.atakmap.map.layer.feature.Feature;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
    /** A watch or warning is a coastline, and red is the only color for it. */
    private static final int WATCH_STROKE = 0xFFFF2020;
    /** Arrival contours are a timetable, not a hazard: readable, not alarming. */
    private static final int ARRIVAL_STROKE = 0xFF90E0FF;

    public interface Listener {
        /** What is running. Empty when nothing is. */
        void onStorms(List<Nhc.Storm> storms);

        /** A line for the pane while something is happening, or "". */
        void onStatus(String status);
    }

    private final MapView mapView;
    private final Context pluginContext;
    private final EgressPolicy egress;

    private MapGroup group;
    private Listener listener;
    private boolean started;
    private boolean on;
    private long lastRefresh;
    /** Bumped whenever the layer is cleared, so a late response cannot redraw. */
    private int generation;
    private final StormIcons icons;
    private final List<Nhc.Storm> active = new ArrayList<>();
    /**
     * What each storm covers on the map, by slot: minLat, minLon, maxLat, maxLon.
     * Grown as its shapes arrive, so "go to" frames the cone rather than dropping the
     * operator on a point inside it.
     */
    private final Map<String, double[]> extents = new HashMap<>();

    public TropicalOverlay(MapView mapView, Context pluginContext, EgressPolicy egress) {
        this.mapView = mapView;
        this.pluginContext = pluginContext;
        this.egress = egress;
        // ATAK's own storage, not the plugin's: the plugin package's cache dir belongs
        // to a different uid than the process this runs in, so mkdirs there fails and
        // every composite lands on ENOENT (XCover, 2026-09-23). Feature Layer keeps its
        // label composites under tools/ for the same reason.
        this.icons = new StormIcons(pluginContext,
                com.atakmap.coremap.filesystem.FileSystemUtils.getItem(
                        "tools/atmosphere/storm-icons"));
    }

    /**
     * Whether a storm is showing one of its products. Keyed by the storm's own id
     * rather than its slot: a slot is reused by whatever storm is running, so
     * "EP2" means a different hurricane next week, and a toggle would carry over
     * to a storm nobody chose it for.
     */
    public boolean isEnabled(Nhc.Storm storm, Nhc.Product product) {
        final SharedPreferences p = MapCompat.prefs();
        if (p == null || storm == null)
            return product.onByDefault;
        return p.getBoolean(key(storm, product), product.onByDefault);
    }

    public void setEnabled(Nhc.Storm storm, Nhc.Product product, boolean enabled) {
        final SharedPreferences p = MapCompat.prefs();
        if (p != null && storm != null)
            p.edit().putBoolean(key(storm, product), enabled).apply();
        redrawAll();
    }

    private static String key(Nhc.Storm storm, Nhc.Product product) {
        return "weather.tropical." + storm.id + "." + product.name();
    }

    /**
     * Wipe and redraw every storm. A toggle refetches rather than hiding what is
     * already there: an advisory is a handful of small files, and a layer that keeps
     * shapes it is not showing is a layer that drifts from what the operator sees.
     */
    private void redrawAll() {
        if (!on || !started)
            return;
        final List<Nhc.Storm> storms = new ArrayList<>(active);
        clear();
        for (Nhc.Storm s : storms)
            draw(s, generation);
    }

    public void setListener(Listener l) {
        listener = l;
        if (l != null) {
            l.onStorms(new ArrayList<>(active));
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
            active.clear();
            if (listener != null) {
                listener.onStorms(new ArrayList<Nhc.Storm>());
                listener.onStatus("");
            }
        }
    }

    /** The storms being drawn. */
    public List<Nhc.Storm> storms() {
        return new ArrayList<>(active);
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
                active.clear();
                if (storms.isEmpty()) {
                    // Saying so is the point: an empty map and a broken layer look
                    // identical, and most of the year this is the true answer.
                    status("No storms right now");
                    if (listener != null)
                        listener.onStorms(new ArrayList<Nhc.Storm>());
                    return;
                }
                active.addAll(storms);
                for (Nhc.Storm s : storms)
                    draw(s, generation);
                status("");
                if (listener != null)
                    listener.onStorms(new ArrayList<>(active));
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

    /** "Hurricane Polo, 150 mph" -- what it is, and how hard, for a map item's title. */
    public static String label(Nhc.Storm s) {
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
        for (Nhc.Product product : Nhc.Product.values()) {
            if (!isEnabled(storm, product))
                continue;
            final int layer = product.layer(storm.bin);
            switch (product) {
                case CONE:
                    fetchShape(storm, layer, mine, name + " cone", CONE_STROKE,
                            CONE_FILL, CONE_WEIGHT, true);
                    break;
                case TRACK:
                    // Drawn from the forecast positions rather than the service's own
                    // track line, so each leg can carry the category it ends at --
                    // which is how every track map anybody has read is drawn. The
                    // points layer answers both; POINTS asks for it too and the second
                    // ask is a few KB.
                    fetchPoints(storm, Nhc.Product.POINTS.layer(storm.bin), mine,
                            true, isEnabled(storm, Nhc.Product.POINTS));
                    break;
                case POINTS:
                    if (!isEnabled(storm, Nhc.Product.TRACK))
                        fetchPoints(storm, layer, mine, false, true);
                    break;
                case WATCHES:
                    fetchShape(storm, layer, mine, name + " watches", WATCH_STROKE,
                            0, TRACK_WEIGHT, false);
                    break;
                case ARRIVAL:
                    fetchShape(storm, layer, mine, name + " wind arrival",
                            ARRIVAL_STROKE, 0, CONE_WEIGHT, false);
                    break;
            }
        }
    }

    /**
     * The forecast positions, each as a marker carrying its day and time.
     *
     * <p>This is the part a crew reads off the map: where it is now, and when it is
     * forecast to be somewhere else. The symbol is NHC's own letter -- D depression,
     * S storm, H hurricane, M major -- punched through a disc tinted by category, and
     * the label is always drawn, because these are looked at from hundreds of meters
     * per pixel and ATAK otherwise holds a marker's name back until 10 m/px.
     */
    private void fetchPoints(final Nhc.Storm storm, final int layer, final int mine,
            final boolean drawTrack, final boolean drawMarkers) {
        if (layer < 0)
            return;
        Http.get(Nhc.queryUrl(layer), egress.userAgent(), null, new Http.Callback() {
            @Override
            public void onSuccess(String body) {
                if (mine != generation || !on || group == null)
                    return;
                try {
                    final JSONArray feats = new JSONObject(body).optJSONArray("features");
                    if (feats == null)
                        return;
                    final List<GeoPoint> path = new ArrayList<>();
                    final List<Integer> colors = new ArrayList<>();
                    for (int i = 0; i < feats.length(); i++) {
                        final JSONObject f = feats.optJSONObject(i);
                        if (f == null)
                            continue;
                        final JSONObject g = f.optJSONObject("geometry");
                        final JSONObject pr = f.optJSONObject("properties");
                        if (g == null || pr == null
                                || !"Point".equals(g.optString("type", "")))
                            continue;
                        final JSONArray c = g.optJSONArray("coordinates");
                        if (c == null || c.length() < 2)
                            continue;
                        final GeoPoint p = new GeoPoint(c.optDouble(1), c.optDouble(0));
                        path.add(p);
                        colors.add(categoryColor(pr.optInt("ssnum", 0),
                                pr.optInt("maxwind", -1)));
                        if (drawMarkers)
                            addPoint(storm, p, pr);
                    }
                    if (drawTrack)
                        drawLegs(storm, path, colors);
                } catch (Exception e) {
                    Log.w(TAG, "forecast points unreadable", e);
                }
            }

            @Override
            public void onFailure(String error) {
                if (mine == generation && on)
                    Log.w(TAG, "points layer " + layer + " failed: " + error);
            }
        });
    }

    /**
     * The track as one short shape per leg, each in the color of the category it
     * arrives at. The service does publish a single track line, but a line has one
     * stroke, and a track whose color never changes throws away the thing the map is
     * for: seeing where it becomes a major hurricane.
     */
    private void drawLegs(Nhc.Storm storm, List<GeoPoint> path, List<Integer> colors) {
        if (group == null || path.size() < 2)
            return;
        for (int i = 0; i + 1 < path.size(); i++) {
            final DrawingShape leg = new DrawingShape(mapView, group,
                    UUID.randomUUID().toString());
            leg.setPoints(new GeoPoint[] { path.get(i), path.get(i + 1) });
            leg.setClosed(false);
            leg.setStrokeColor(colors.get(i + 1));
            leg.setStrokeWeight(TRACK_WEIGHT);
            leg.setTitle(label(storm) + " track");
            leg.setAltitudeMode(Feature.AltitudeMode.ClampToGround);
            leg.setMetaBoolean("removable", false);
            leg.setMetaString("menu", "");
            group.addItem(leg);
            grow(storm.bin, new GeoPoint[] { path.get(i), path.get(i + 1) });
        }
    }

    private void addPoint(Nhc.Storm storm, GeoPoint p, JSONObject pr) {
        // tau is the forecast hour, so tau 0 is where the storm is right now.
        final int tau = pr.optInt("tau", -1);
        final String when = pr.optString("datelbl", "");
        final Marker m = new Marker(p, UUID.randomUUID().toString());
        // A plain point. Two types were wrong before this one, both because ATAK
        // decides a marker's symbol from its CoT type (XCover, 2026-09-23):
        // "a-h-X-i-g" is an ATOM with a HOSTILE affiliation, so every forecast
        // position drew as a red 2525 diamond; "b-m-p-s-p-loc" is the sensor point
        // Cam Depot uses precisely because ATAK ships an icon for it, and that icon
        // won over ours. "u-d-p" carries neither.
        m.setType("u-d-p");
        // Ours alone, and it never becomes traffic on the network.
        m.setMetaBoolean("nevercot", true);
        m.setMetaBoolean("addToObjList", false);
        final String label = tau == 0 ? storm.display()
                : (when.isEmpty() ? storm.name : when);
        m.setTitle(label);
        m.setMetaString("callsign", label);
        // The label is drawn into the icon, so ATAK must not draw one of its own: its
        // engine trims marker labels freely and a plugin cannot give them a priority.
        m.setTextRenderFlag(Marker.TEXT_STATE_NEVER_SHOW);
        m.setMetaString("remarks", remarks(storm, pr));
        m.setMetaBoolean("removable", false);
        m.setMetaBoolean("editable", false);
        // Tinted by THIS position's forecast category, not the storm's current one,
        // so a track that strengthens or weakens shows it.
        final Icon icon = icons.labelled(label,
                categoryColor(pr.optInt("ssnum", 0), pr.optInt("maxwind", -1)),
                tau == 0 ? 40 : 26);
        group.addItem(m);
        // After the add, not before: joining a group is where ATAK settles a marker's
        // symbol from its type, and an icon set first is the one that loses.
        if (icon != null)
            m.setIcon(icon);
        grow(storm.bin, new GeoPoint[] { p });
    }

    /** Everything the advisory says about this position, for the marker's detail. */
    private static String remarks(Nhc.Storm storm, JSONObject pr) {
        final StringBuilder b = new StringBuilder();
        line(b, "", pr.optString("tcdvlp", ""));
        line(b, "Valid ", pr.optString("fldatelbl", ""));
        final int wind = pr.optInt("maxwind", -1);
        if (wind > 0)
            line(b, "Wind ", Math.round(wind * 1.15078) + " mph sustained");
        final int gust = pr.optInt("gust", -1);
        if (gust > 0)
            line(b, "Gusts ", Math.round(gust * 1.15078) + " mph");
        final int mslp = pr.optInt("mslp", -1);
        if (mslp > 0)
            line(b, "Pressure ", mslp + " mb");
        final int dir = pr.optInt("tcdir", -1);
        final int spd = pr.optInt("tcspd", -1);
        if (dir >= 0 && spd >= 0)
            line(b, "Moving ", dir + " deg at " + Math.round(spd * 1.15078) + " mph");
        line(b, "Advisory ", pr.optString("advisnum", ""));
        line(b, "", storm.display());
        return b.toString();
    }

    private static void line(StringBuilder b, String prefix, String value) {
        if (value == null || value.isEmpty() || value.equals("null"))
            return;
        if (b.length() > 0)
            b.append('\n');
        b.append(prefix).append(value);
    }

    /**
     * The Saffir-Simpson track-map palette: the colors NHC's own track maps use, and
     * with them every outlet and every briefing anybody has seen. Worth taking as
     * given rather than inventing, because the whole value of it is that a crew
     * already knows what orange means (operator, 2026-09-23: "if there is any
     * standard symbology for hurricane strength that we can apply to the segments").
     *
     * <p>Depression and storm are below category one and the scale does not number
     * them, so they are told apart by wind rather than by {@code ssnum}.
     */
    private static final int[] SAFFIR_SIMPSON = {
            0xFF5EBAFF,   // tropical depression, under 39 mph
            0xFF00FAF4,   // tropical storm, 39-73
            0xFFFFFFCC,   // category 1, 74-95
            0xFFFFE775,   // category 2, 96-110
            0xFFFFC140,   // category 3, 111-129
            0xFFFF8F20,   // category 4, 130-156
            0xFFFF6060,   // category 5, 157 and up
    };

    /** Where a position sits on the scale: 0 depression, 1 storm, 2-6 categories 1-5. */
    private static int rung(int category, int windKt) {
        if (category >= 1)
            return Math.min(6, category + 1);
        if (windKt >= 34)
            return 1;
        return 0;
    }

    private static int categoryColor(int category, int windKt) {
        return SAFFIR_SIMPSON[rung(category, windKt)];
    }

    private static int trackColor(Nhc.Storm s) {
        return categoryColor(s.category(), s.intensityKt);
    }

    private void fetchShape(final Nhc.Storm storm, final int layer, final int mine,
            final String title, final int stroke, final int fill, final double weight,
            final boolean closed) {
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
                    grow(storm.bin, ring);
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

    /** Widen what a storm is known to cover, as each of its shapes lands. */
    private void grow(String bin, GeoPoint[] ring) {
        double[] e = extents.get(bin);
        if (e == null) {
            e = new double[] { Double.MAX_VALUE, Double.MAX_VALUE,
                    -Double.MAX_VALUE, -Double.MAX_VALUE };
            extents.put(bin, e);
        }
        for (GeoPoint p : ring) {
            e[0] = Math.min(e[0], p.getLatitude());
            e[1] = Math.min(e[1], p.getLongitude());
            e[2] = Math.max(e[2], p.getLatitude());
            e[3] = Math.max(e[3], p.getLongitude());
        }
    }

    /**
     * Put a storm on screen, cone and all.
     *
     * <p>Deliberately unlike the "go to" in Cam Depot and Comms, which will not zoom
     * the operator back out to reach a site. A five-day cone is a thousand miles
     * across, so arriving at the storm's own position at a street-level zoom shows a
     * patch of empty ocean inside the cone. This frames what was drawn.
     */
    public void goTo(Nhc.Storm storm) {
        if (storm == null)
            return;
        final double[] e = extents.get(storm.bin);
        final GeoPoint here = Double.isNaN(storm.latitude) || Double.isNaN(storm.longitude)
                ? null : new GeoPoint(storm.latitude, storm.longitude);
        if (e == null || e[0] > e[2]) {
            // Shapes have not landed yet: the storm's own position is still an answer.
            if (here != null)
                pan(here, Double.NaN);
            return;
        }
        final GeoPoint center = new GeoPoint((e[0] + e[2]) / 2, (e[1] + e[3]) / 2);
        pan(center, fitResolution(e));
    }

    /** Meters per pixel that fits a box in the map view, with room around it. */
    private double fitResolution(double[] e) {
        final int w = Math.max(1, mapView.getWidth());
        final int h = Math.max(1, mapView.getHeight());
        final double midLat = Math.toRadians((e[0] + e[2]) / 2);
        final double tall = (e[2] - e[0]) * 111_320.0;
        final double wide = (e[3] - e[1]) * 111_320.0 * Math.max(0.1, Math.cos(midLat));
        // A sixth again, so the cone is not drawn against the edges of the screen.
        return Math.max(tall / h, wide / w) * 1.15;
    }

    private void pan(GeoPoint p, double resolution) {
        try {
            if (Double.isNaN(resolution)) {
                mapView.getMapController().panTo(p, true);
                return;
            }
            mapView.getMapController().panZoomTo(p,
                    mapView.mapResolutionAsMapScale(resolution), true);
        } catch (LinkageError | RuntimeException ex) {
            Log.w(TAG, "panZoomTo failed; falling back to a plain pan", ex);
            mapView.getMapController().panTo(p, true);
        }
    }

    /** Drop everything drawn and make any response in flight land nowhere. */
    private void clear() {
        generation++;
        extents.clear();
        if (group != null)
            group.clearItems();
    }

    private void status(String s) {
        if (listener != null)
            listener.onStatus(s);
    }
}
