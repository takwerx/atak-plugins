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
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private FeatureSetDatabase2 store;
    private FeatureLayer3 layer;
    private FeatureDataStoreMapOverlay overlay;
    private final Map<String, Long> sets = new HashMap<>();
    private PlaceIcons icons;
    /** Feature id to {title, details}: the hit test's copy carries no attributes. */
    private final Map<Long, String[]> details = new ConcurrentHashMap<>();
    private volatile boolean stopped;
    /** Zoomed out past the places gate: the layer is off, the store is kept. */
    private boolean hidden;

    public PlaceLayer(MapView mapView) {
        this.mapView = mapView;
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
        for (Report r : reports) {
            if (r.placesOn.isEmpty() || !r.onMap)
                continue;
            final Report.Answer a = r.answer("places");
            if (a == null || a.points == null)
                continue;
            for (int i = 0; i < a.points.length(); i++) {
                final JSONObject p = a.points.optJSONObject(i);
                if (p == null || !r.placesOn.contains(p.optString("c")))
                    continue;
                JSONArray l = byCat.get(p.optString("c"));
                if (l == null) {
                    l = new JSONArray();
                    byCat.put(p.optString("c"), l);
                }
                l.put(p);
            }
        }
        worker.execute(new Runnable() {
            @Override
            public void run() {
                write(byCat);
            }
        });
    }

    private void write(Map<String, JSONArray> byCat) {
        if (store == null || stopped)
            return;
        try {
            details.clear();
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
                    details.put(fid, new String[] { name, c[1] + "\n" + d });
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "could not write the places: " + t);
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
