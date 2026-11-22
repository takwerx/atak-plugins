package com.atakmap.android.sitrep;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;

import com.atakmap.android.features.FeatureDataStoreDeepMapItemQuery;
import com.atakmap.android.features.FeatureDataStoreMapOverlay;
import com.atakmap.android.maps.MapItem;
import com.atakmap.android.maps.MapView;
import com.atakmap.android.menu.PluginMenuParser;
import com.atakmap.android.sitrep.section.Places;
import com.atakmap.coremap.filesystem.FileSystemUtils;
import com.atakmap.coremap.log.Log;
import com.atakmap.map.layer.feature.AttributeSet;
import com.atakmap.map.layer.feature.Feature;
import com.atakmap.map.layer.feature.FeatureDataStore2;
import com.atakmap.map.layer.feature.FeatureLayer3;
import com.atakmap.map.layer.feature.FeatureSet;
import com.atakmap.map.layer.feature.datastore.FeatureSetDatabase2;
import com.atakmap.map.layer.feature.geometry.Point;
import com.atakmap.map.layer.feature.style.IconPointStyle;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The places a report found, on the map as a read-only feature layer (the GIS-data
 * rule: somebody else's data is never a Marker the operator owns). One feature set
 * per category, so Overlay Manager lists Hospitals, Schools ... The store is rebuilt
 * from the saved reports, which hold every place, so it comes back offline.
 *
 * <p>Store work only on its own thread (never on main), the store is never disposed
 * (a JNI abort), and the file is emptied at start: sets the API cannot enumerate
 * otherwise pile up (Atmosphere, 2026-09-25).
 */
public final class PlaceLayer {

    private static final String TAG = "SitRep.Places";
    static final String PROVIDER = "sitrep";
    /** A real coarse resolution, metres per pixel: shown from county scale in. */
    private static final double MIN_RES = 5_000d;

    private final MapView mapView;
    private final Context pluginContext;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private FeatureSetDatabase2 store;
    private FeatureLayer3 layer;
    private FeatureDataStoreMapOverlay overlay;
    private final Map<String, Long> sets = new HashMap<>();
    private PlaceIcons icons;
    /**
     * Feature id to {title, details, report id, category, place key}: the hit test's
     * copy carries no attributes. Report id and key are null for lines and edges.
     */
    private final Map<Long, String[]> details = new ConcurrentHashMap<>();
    private volatile boolean stopped;
    /** Zoomed out past the places gate: the layer is off, the store is kept. */
    private boolean hidden;

    public PlaceLayer(MapView mapView, Context pluginContext) {
        this.mapView = mapView;
        this.pluginContext = pluginContext;
    }

    /** One place's identity inside a report: the pane finds its row by it. */
    public static String placeKey(JSONObject p) {
        return p.optString("n") + "|" + p.optDouble("lat") + "|" + p.optDouble("lon");
    }

    /** Opens the store on its thread, then puts the layer on the map on main. */
    public void start() {
        worker.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    final File f = FileSystemUtils.getItem("tools/sitrep/places.sqlite");
                    final File dir = f.getParentFile();
                    if (dir != null && !dir.isDirectory())
                        //noinspection ResultOfMethodCallIgnored
                        dir.mkdirs();
                    if (f.exists() && !f.delete())
                        Log.w(TAG, "could not empty the places store");
                    icons = new PlaceIcons(dir);
                    store = new FeatureSetDatabase2(f);
                    for (String[] c : Places.CATEGORIES) {
                        final long id = store.insertFeatureSet(new FeatureSet(PROVIDER, c[0], c[1], MIN_RES, 0d));
                        store.setFeatureSetVisible(id, true);
                        sets.put(c[0], id);
                    }
                    final FeatureDataStore2.FeatureQueryParameters visibleOnly =
                            new FeatureDataStore2.FeatureQueryParameters();
                    visibleOnly.visibleOnly = true;
                    layer = new FeatureLayer3("Sit Rep places", store, visibleOnly);
                    final FeatureDataStoreDeepMapItemQuery query = new FeatureDataStoreDeepMapItemQuery(layer) {
                        @Override
                        protected MapItem featureToMapItem(Feature feature) {
                            final MapItem item = super.featureToMapItem(feature);
                            final String[] d = details.get(feature.getId());
                            item.setMetaBoolean("sitrep_place", true);
                            item.setMetaBoolean("removable", false);
                            item.setMetaBoolean("editable", false);
                            item.setMetaBoolean("addToObjList", false);
                            item.setMetaBoolean("nevercot", true);
                            item.setMetaString("title", d != null ? d[0] : feature.getName());
                            item.setMetaString("callsign", d != null ? d[0] : feature.getName());
                            item.setMetaString("sitrep_details", d != null ? d[1] : "");
                            if (d != null && d.length > 4 && d[2] != null) {
                                // A point: the radial (PLAN section 11). Lines and edges
                                // keep the details box.
                                item.setMetaString("menu", PluginMenuParser.getMenu(pluginContext, "menu/place.xml"));
                                item.setMetaString("sitrep_report", d[2]);
                                item.setMetaString("sitrep_category", d[3]);
                                item.setMetaString("sitrep_key", d[4]);
                                final com.atakmap.map.layer.feature.geometry.Geometry g = feature.getGeometry();
                                if (g instanceof Point) {
                                    item.setMetaDouble("sitrep_lat", ((Point) g).getY());
                                    item.setMetaDouble("sitrep_lon", ((Point) g).getX());
                                }
                            }
                            return item;
                        }
                    };
                    mapView.post(new Runnable() {
                        @Override
                        public void run() {
                            register(query);
                        }
                    });
                } catch (Throwable t) {
                    Log.w(TAG, "the places store would not open: " + t);
                }
            }
        });
    }

    private void register(FeatureDataStoreDeepMapItemQuery query) {
        if (stopped || layer == null)
            return;
        try {
            final Context ctx = mapView.getContext();
            overlay = new FeatureDataStoreMapOverlay(ctx, store, null, "Sit Rep places",
                    icons.plain("hospital"), query, null, null);
            final boolean added = mapView.getMapOverlayManager().addOverlay(overlay);
            if (!added)
                Log.w(TAG, "Overlay Manager did not take the places overlay");
            mapView.addLayer(MapView.RenderStack.VECTOR_OVERLAYS, layer);
            layer.setVisible(!hidden);
        } catch (Throwable t) {
            Log.w(TAG, "could not put the places on the map: " + t);
        }
    }

    /** Makes the map match the reports: each report's switched-on categories. */
    public void sync(List<Report> reports) {
        // A snapshot taken on main; the store work is on the worker.
        final Map<String, JSONArray> byCat = new HashMap<>();
        final Map<JSONObject, String> owner = new java.util.IdentityHashMap<>();
        for (Report r : reports) {
            if (r.placesOn.isEmpty() || !r.onMap)
                continue;
            // Places, the 911 calls section's calls (category "calls") and History's
            // fatality and entrapment reviews (category "lessons").
            final JSONArray pts = new JSONArray();
            for (String key : new String[] { "places", "calls", "history" }) {
                final Report.Answer a = r.answer(key);
                for (int i = 0; a != null && a.points != null && i < a.points.length(); i++)
                    pts.put(a.points.opt(i));
            }
            for (int i = 0; i < pts.length(); i++) {
                final JSONObject p = pts.optJSONObject(i);
                if (p == null || !r.placesOn.contains(p.optString("c")))
                    continue;
                JSONArray l = byCat.get(p.optString("c"));
                if (l == null) {
                    l = new JSONArray();
                    byCat.put(p.optString("c"), l);
                }
                l.put(p);
                owner.put(p, r.id);
            }
        }
        worker.execute(new Runnable() {
            @Override
            public void run() {
                write(byCat, owner);
            }
        });
    }

    private void write(Map<String, JSONArray> byCat, Map<JSONObject, String> owner) {
        if (store == null || stopped)
            return;
        boolean locked = false;
        try {
            // One transaction and one redraw for the whole write: insert by insert, the
            // store committed each feature to the sdcard on its own, and 2,135 points
            // were still half written after 6 minutes (Murrieta, 2026-11-14).
            store.acquireModifyLock(true);
            locked = true;
            details.clear();
            final long t0 = android.os.SystemClock.elapsedRealtime();
            int written = 0;
            for (String[] c : Places.CATEGORIES) {
                final Long set = sets.get(c[0]);
                if (set == null)
                    continue;
                final FeatureDataStore2.FeatureQueryParameters q = new FeatureDataStore2.FeatureQueryParameters();
                q.featureSetFilter = new FeatureDataStore2.FeatureSetQueryParameters();
                q.featureSetFilter.ids = java.util.Collections.singleton(set);
                store.deleteFeatures(q);
                final JSONArray l = byCat.get(c[0]);
                for (int i = 0; l != null && i < l.length(); i++) {
                    final JSONObject p = l.optJSONObject(i);
                    final String name = p.optString("n", "Unnamed");
                    final AttributeSet attrs = new AttributeSet();
                    final StringBuilder d = new StringBuilder();
                    final JSONArray info = p.optJSONArray("i");
                    for (int k = 0; info != null && k < info.length(); k++)
                        d.append(k == 0 ? "" : "\n").append(info.optString(k));
                    attrs.setAttribute("Details", d.toString());
                    if (Places.isLine(c[0]) && p.optJSONArray("g") != null) {
                        // Rail: bright green on a dark edge so it reads on imagery;
                        // transmission: amber. No icon and no label, details on tap.
                        final com.atakmap.map.layer.feature.style.Style ls = "rail".equals(c[0])
                                ? new com.atakmap.map.layer.feature.style.CompositeStyle(new com.atakmap.map.layer.feature.style.Style[] {
                                        new com.atakmap.map.layer.feature.style.BasicStrokeStyle(0xFF102010, 6f),
                                        new com.atakmap.map.layer.feature.style.BasicStrokeStyle(0xFF00E676, 3.5f) })
                                : new com.atakmap.map.layer.feature.style.BasicStrokeStyle(0xFFFFAB00, 3f);
                        final JSONArray paths = p.optJSONArray("g");
                        for (int k = 0; k < paths.length(); k++) {
                            final JSONArray path = paths.optJSONArray(k);
                            if (path == null || path.length() < 2)
                                continue;
                            final com.atakmap.map.layer.feature.geometry.LineString lsg =
                                    new com.atakmap.map.layer.feature.geometry.LineString(2);
                            for (int v = 0; v < path.length(); v++) {
                                final JSONArray xy = path.optJSONArray(v);
                                if (xy != null)
                                    lsg.addPoint(xy.optDouble(0), xy.optDouble(1));
                            }
                            final long lfid = store.insertFeature(new Feature(set, name, lsg, ls, attrs));
                            details.put(lfid, new String[] { name, c[1] + "\n" + d });
                        }
                        continue;
                    }
                    // An area place (a military installation): its boundary under the icon.
                    final JSONArray rings = p.optJSONArray("g");
                    for (int k = 0; rings != null && k < rings.length(); k++) {
                        final JSONArray ring = rings.optJSONArray(k);
                        if (ring == null || ring.length() < 3)
                            continue;
                        final com.atakmap.map.layer.feature.geometry.LineString edge =
                                new com.atakmap.map.layer.feature.geometry.LineString(2);
                        for (int v = 0; v < ring.length(); v++) {
                            final JSONArray xy = ring.optJSONArray(v);
                            if (xy != null)
                                edge.addPoint(xy.optDouble(0), xy.optDouble(1));
                        }
                        if ("nuclear".equals(c[0])) {
                            // The emergency planning zones: 10 mi red, 50 mi amber, each
                            // labeled at its top and explained on a tap (operator, 2026-11-14).
                            final boolean plume = k == 0;
                            final String zone = plume ? "10 mi plume zone" : "50 mi ingestion zone";
                            final long rfid = store.insertFeature(new Feature(set, name + ": " + zone, edge,
                                    new com.atakmap.map.layer.feature.style.CompositeStyle(new com.atakmap.map.layer.feature.style.Style[] {
                                            new com.atakmap.map.layer.feature.style.BasicStrokeStyle(0xFF101010, plume ? 5.5f : 5f),
                                            new com.atakmap.map.layer.feature.style.BasicStrokeStyle(plume ? 0xFFFF5252 : 0xFFFFC400,
                                                    plume ? 3f : 2.5f) }),
                                    attrs));
                            final String meaning = plume ? Places.PLUME_ZONE : Places.INGESTION_ZONE;
                            details.put(rfid, new String[] { name + ": " + zone, meaning });
                            final JSONArray top = ring.optJSONArray(0);
                            if (top != null) {
                                final long lfid = store.insertFeature(new Feature(set, zone,
                                        new Point(top.optDouble(0), top.optDouble(1)),
                                        new com.atakmap.map.layer.feature.style.LabelPointStyle(zone,
                                                plume ? 0xFFFF5252 : 0xFFFFC400, 0xC0000000,
                                                com.atakmap.map.layer.feature.style.LabelPointStyle.ScrollMode.DEFAULT),
                                        attrs));
                                details.put(lfid, new String[] { name + ": " + zone, meaning });
                            }
                            continue;
                        }
                        final long efid = store.insertFeature(new Feature(set, name, edge,
                                new com.atakmap.map.layer.feature.style.CompositeStyle(new com.atakmap.map.layer.feature.style.Style[] {
                                        new com.atakmap.map.layer.feature.style.BasicStrokeStyle(0xFF1B1F10, 5f),
                                        new com.atakmap.map.layer.feature.style.BasicStrokeStyle(0xFFB5C76A, 2.5f) }),
                                attrs));
                        details.put(efid, new String[] { name, c[1] + "\n" + d });
                    }
                    final PlaceIcons.Composed ic = icons.get(c[0], name);
                    if (ic == null)
                        continue;
                    // The name is drawn in the icon's pill; an empty label style stops
                    // ATAK drawing the feature's name a second time.
                    final com.atakmap.map.layer.feature.style.Style style = new com.atakmap.map.layer.feature.style.CompositeStyle(
                            new com.atakmap.map.layer.feature.style.Style[] {
                                    new IconPointStyle(0xFFFFFFFF, ic.uri, ic.width, ic.height,
                                            ic.offsetX, ic.offsetY, 0, 0, 0f, false),
                                    new com.atakmap.map.layer.feature.style.LabelPointStyle("", 0x00FFFFFF, 0x00000000,
                                            com.atakmap.map.layer.feature.style.LabelPointStyle.ScrollMode.DEFAULT) });
                    final long fid = store.insertFeature(new Feature(set, name,
                            new Point(p.optDouble("lon"), p.optDouble("lat")), style, attrs));
                    details.put(fid, new String[] { name, c[1] + "\n" + d, owner.get(p), c[0], placeKey(p) });
                    written++;
                }
            }
            Log.d(TAG, "places written: " + written + " in "
                    + (android.os.SystemClock.elapsedRealtime() - t0) + " ms");
        } catch (Throwable t) {
            Log.w(TAG, "could not write the places: " + t);
        } finally {
            if (locked)
                store.releaseModifyLock();
        }
    }

    /** Hides or shows every place at once, for the zoom gate. Main thread. */
    public void setHidden(boolean h) {
        hidden = h;
        if (layer != null && !stopped && layer.isVisible() == h)
            layer.setVisible(!h);
    }

    /** Takes the layer off the map. The store is left open: disposing one aborts ATAK. */
    public void stop() {
        stopped = true;
        try {
            if (layer != null)
                mapView.removeLayer(MapView.RenderStack.VECTOR_OVERLAYS, layer);
            if (overlay != null)
                mapView.getMapOverlayManager().removeOverlay(overlay);
        } catch (Throwable t) {
            Log.w(TAG, "could not take the places off the map: " + t);
        }
        worker.shutdown();
    }
}
