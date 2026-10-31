package com.atakmap.android.firecast;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.util.Xml;

import com.atakmap.android.features.FeatureDataStoreDeepMapItemQuery;
import com.atakmap.android.features.FeatureDataStoreMapOverlay;
import com.atakmap.android.maps.MapItem;
import com.atakmap.android.maps.MapView;
import com.atakmap.coremap.filesystem.FileSystemUtils;
import com.atakmap.coremap.log.Log;
import com.atakmap.map.layer.feature.AttributeSet;
import com.atakmap.map.layer.feature.Feature;
import com.atakmap.map.layer.feature.FeatureDataStore2;
import com.atakmap.map.layer.feature.FeatureLayer3;
import com.atakmap.map.layer.feature.FeatureSet;
import com.atakmap.map.layer.feature.FeatureSetCursor;
import com.atakmap.map.layer.feature.datastore.FeatureSetDatabase2;
import com.atakmap.map.layer.feature.geometry.Geometry;
import com.atakmap.map.layer.feature.geometry.GeometryCollection;
import com.atakmap.map.layer.feature.geometry.LineString;
import com.atakmap.map.layer.feature.geometry.Polygon;
import com.atakmap.map.layer.feature.style.BasicStrokeStyle;

import org.xmlpull.v1.XmlPullParser;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * FIRIS model runs: the spread runs fire analysts make for California incidents,
 * published as one KML for ATAK. Each run is a set of isochrones, 0.5 h to 6 h from
 * the time it was started, with the weather and fuel moisture it was run with.
 *
 * <p>Reference data a crew uses in the field, so it is kept: the last good KML is saved
 * and redrawn at start, with no network, and a failed fetch never empties the layer.
 * Off hides the sets, it deletes nothing.
 */
public final class FirisModels {

    private static final String TAG = "FireCast";
    public static final String URL = "https://atak.s3.us-west-1.amazonaws.com/FIRIS_models.kml";
    private static final long EVERY_MS = 30 * 60_000L;
    private static final int MAX_POINTS = 2_000_000, MAX_RUNS = 500;

    public interface Listener {
        /** Main thread. */
        void onFirisChanged();
    }

    /** One fire's run: its isochrones and what it was run with. */
    public static final class Run {
        public String fire = "", started = "", author = "", inputs = "";
        public long startMs;
        final List<Ring> rings = new ArrayList<>();

        /** The run's isochrone times, ascending, without repeats. */
        public double[] hours() {
            final java.util.TreeSet<Double> s = new java.util.TreeSet<>();
            for (Ring g : rings)
                s.add(g.hours);
            final double[] out = new double[s.size()];
            int i = 0;
            for (double h : s)
                out[i++] = h;
            return out;
        }

        /** Acres inside the isochrone at that time, from the area the source states; -1 if not given. */
        public double acres(double h) {
            double m2 = 0;
            for (Ring g : rings)
                if (g.hours == h) {
                    if (g.areaM2 < 0)
                        return -1;
                    m2 += g.areaM2;
                }
            return m2 / 4046.8564224;
        }

        public String key() {
            return fire + "|" + started;
        }

        /** "SPRINGS", out of "CA-MNF-SPRINGS-20261017-2002". */
        public String shortName() {
            final String[] p = fire.split("-");
            return p.length >= 3 ? p[2] : fire;
        }
    }

    static final class Ring {
        double hours, areaM2 = -1;
        int color;
        Geometry geom;
    }

    private final MapView mapView;
    private final File root, kmlFile, storeFile;
    private final SharedPreferences prefs;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ScheduledExecutorService worker = Executors.newSingleThreadScheduledExecutor();
    private final Object lock = new Object();
    private FeatureSetDatabase2 store;
    private FeatureLayer3 layer;
    private FeatureDataStoreMapOverlay overlay;
    private volatile Listener listener;

    public volatile List<Run> runs = Collections.emptyList();
    /** When the KML being shown was fetched; 0 if never. */
    public volatile long fetchedAt;
    /** Why the last fetch failed, in words, or null. */
    public volatile String problem;
    public volatile boolean busy;

    public FirisModels(MapView mapView, Context pluginContext) {
        this.mapView = mapView;
        this.root = FileSystemUtils.getItem("tools/firecast");
        this.kmlFile = new File(root, "firis_models.kml");
        this.storeFile = new File(root, "firis.sqlite");
        this.prefs = mapView.getContext().getSharedPreferences("firecast", Context.MODE_PRIVATE);
        this.fetchedAt = prefs.getLong("firis.fetchedAt", 0L);
    }

    public void setListener(Listener l) {
        listener = l;
    }

    public boolean isOn() {
        return prefs.getBoolean("firis.on", false);
    }

    public void setOn(final boolean on) {
        prefs.edit().putBoolean("firis.on", on).apply();
        worker.execute(new Runnable() {
            @Override
            public void run() {
                synchronized (lock) {
                    showLocked(on);
                    redrawAtLocked();
                }
                if (on && System.currentTimeMillis() - fetchedAt > EVERY_MS)
                    fetch();
                changed();
            }
        });
    }

    public void start() {
        if (!root.isDirectory() && !root.mkdirs())
            Log.w(TAG, "could not create " + root);
        worker.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    synchronized (lock) {
                        attachLocked();
                    }
                    // What this device saved comes back first, network or not.
                    if (kmlFile.isFile()) {
                        final List<Run> saved = parse(kmlFile);
                        synchronized (lock) {
                            runs = saved;
                            drawLocked();
                            redrawAtLocked();
                        }
                    }
                } catch (Throwable t) {
                    Log.w(TAG, "FIRIS layer could not start", t);
                }
                changed();
            }
        });
        worker.scheduleWithFixedDelay(new Runnable() {
            @Override
            public void run() {
                if (isOn() && System.currentTimeMillis() - fetchedAt > EVERY_MS - 60_000L)
                    fetch();
            }
        }, 5, 60, TimeUnit.SECONDS);
    }

    public void stop() {
        worker.shutdownNow();
        synchronized (lock) {
            try {
                if (layer != null)
                    mapView.removeLayer(MapView.RenderStack.VECTOR_OVERLAYS, layer);
                if (overlay != null)
                    mapView.getMapOverlayManager().removeOverlay(overlay);
            } catch (Exception e) {
                Log.w(TAG, "FIRIS detach failed", e);
            }
            // Never disposed: disposing a feature store aborts ATAK.
            layer = null;
            overlay = null;
            store = null;
        }
    }

    /** Fetches now, on the worker. */
    public void refresh() {
        worker.execute(new Runnable() {
            @Override
            public void run() {
                fetch();
            }
        });
    }

    /** Fits the map to a run's largest isochrone. Main thread. */
    public void goTo(Run r) {
        double s = 90, w = 180, n = -90, e = -180;
        for (Ring g : r.rings) {
            final com.atakmap.map.layer.feature.geometry.Envelope v = g.geom.getEnvelope();
            s = Math.min(s, v.minY);
            w = Math.min(w, v.minX);
            n = Math.max(n, v.maxY);
            e = Math.max(e, v.maxX);
        }
        if (n < s)
            return;
        DetectionFeed.fitVisible(mapView, s, w, n, e);
    }

    // ---- where it is by ----------------------------------------------------------

    /** The run the slider is on, by key, and the time shown; kept across a restart. */
    public String focusKey() {
        return prefs.getString("firis.focus", null);
    }

    public double focusHours() {
        return prefs.getFloat("firis.focusHours", 0f);
    }

    public Run focus() {
        final String k = focusKey();
        if (k != null)
            for (Run r : runs)
                if (k.equals(r.key()))
                    return r;
        return null;
    }

    /** Puts the slider on a run, or takes it off with null. */
    public void setFocus(Run r) {
        prefs.edit().putString("firis.focus", r == null ? null : r.key()).putFloat("firis.focusHours", 0f).apply();
        showAt(r, 0);
    }

    /** Draws a run's edge at that time in cyan over its rings; 0 draws none. */
    public void showAt(final Run r, final double h) {
        prefs.edit().putFloat("firis.focusHours", (float) h).apply();
        worker.execute(new Runnable() {
            @Override
            public void run() {
                synchronized (lock) {
                    drawAtLocked(r, h);
                }
            }
        });
    }

    private long atFsid = -1;

    /** Lock held. */
    private void drawAtLocked(Run r, double h) {
        if (store == null)
            return;
        boolean bulk = false;
        try {
            store.acquireModifyLock(true);
            bulk = true;
            if (atFsid >= 0) {
                store.deleteFeatureSet(atFsid);
                atFsid = -1;
            }
            if (r == null || h <= 0 || !isOn())
                return;
            atFsid = store.insertFeatureSet(new FeatureSet("FireCast", "FIRIS", "FIRIS at", Double.MAX_VALUE, 0d));
            store.setFeatureSetVisible(atFsid, true);
            final String name = r.shortName() + ", " + hoursText(h) + " after " + when(r.startMs);
            for (Ring g : r.rings)
                if (g.hours == h) {
                    final AttributeSet a = new AttributeSet();
                    a.setAttribute("_title", name);
                    store.insertFeature(new Feature(atFsid, name, g.geom, new com.atakmap.map.layer.feature.style.CompositeStyle(new com.atakmap.map.layer.feature.style.Style[] { new BasicStrokeStyle(0xC0000000, 7f), new BasicStrokeStyle(0xFF00E5FF, 4f) }), a,
                            Feature.AltitudeMode.ClampToGround, 0d));
                }
        } catch (Exception e) {
            Log.w(TAG, "FIRIS edge draw failed", e);
        } finally {
            if (bulk)
                store.releaseModifyLock();
        }
    }

    // ---- fetch ---------------------------------------------------------------------

    /** Worker thread. */
    private void fetch() {
        busy = true;
        changed();
        try {
            final String kml = Esri.get(URL);
            final File tmp = new File(root, "firis_models.kml." + System.nanoTime() + ".tmp");
            try (OutputStreamWriter w = new OutputStreamWriter(new FileOutputStream(tmp), StandardCharsets.UTF_8)) {
                w.write(kml);
            }
            final List<Run> got = parse(tmp);
            // An empty answer is not news that every fire is out; keep what is held.
            if (got.isEmpty() && !runs.isEmpty()) {
                FileSystemUtils.delete(tmp);
                problem = "the feed came back empty";
            } else {
                if (!tmp.renameTo(kmlFile))
                    Log.w(TAG, "could not save the FIRIS KML");
                fetchedAt = System.currentTimeMillis();
                prefs.edit().putLong("firis.fetchedAt", fetchedAt).apply();
                problem = null;
                synchronized (lock) {
                    runs = got;
                    drawLocked();
                    redrawAtLocked();
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "FIRIS fetch failed: " + t);
            problem = "no network";
        } finally {
            busy = false;
            changed();
        }
    }

    // ---- parse ---------------------------------------------------------------------

    private static final Pattern CELL = Pattern.compile(
            "<td>([A-Za-z_0-9()]{1,40})</td>\\s*<td>([^<]{0,400})</td>");

    /**
     * Reads the KML with a pull parser (no DTDs, no entities from outside), keeping each
     * placemark's isochrone and the attribute table ArcGIS put in its description.
     */
    static List<Run> parse(File f) throws Exception {
        final Map<String, String> styles = new HashMap<>();
        final LinkedHashMap<String, Run> byKey = new LinkedHashMap<>();
        final XmlPullParser x = Xml.newPullParser();
        x.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false);
        int points = 0;
        try (InputStream in = new FileInputStream(f)) {
            x.setInput(in, null);
            String styleId = null, styleUrl = null, desc = null;
            boolean inPlacemark = false, inPoly = false, inPolyStyle = false;
            List<Polygon> polys = new ArrayList<>();
            Polygon poly = null;
            for (int e = x.getEventType(); e != XmlPullParser.END_DOCUMENT; e = x.next()) {
                if (e == XmlPullParser.START_TAG) {
                    final String n = x.getName();
                    if ("Style".equals(n)) {
                        styleId = x.getAttributeValue(null, "id");
                    } else if ("color".equals(n) && styleId != null && inPolyStyle) {
                        styles.put(styleId, x.nextText().trim());
                    } else if ("PolyStyle".equals(n)) {
                        inPolyStyle = true;
                    } else if ("Placemark".equals(n)) {
                        inPlacemark = true;
                        styleUrl = null;
                        desc = null;
                        polys = new ArrayList<>();
                    } else if (inPlacemark && "styleUrl".equals(n)) {
                        styleUrl = x.nextText().trim();
                    } else if (inPlacemark && "description".equals(n)) {
                        desc = x.nextText();
                    } else if (inPlacemark && "Polygon".equals(n)) {
                        inPoly = true;
                        poly = new Polygon(2);
                    } else if (inPoly && "coordinates".equals(n)) {
                        final LineString ring = ring(x.nextText());
                        points += ring.getNumPoints();
                        if (points > MAX_POINTS)
                            throw new java.io.IOException("FIRIS KML too large");
                        if (ring.getNumPoints() >= 4)
                            poly.addRing(ring);
                    }
                } else if (e == XmlPullParser.END_TAG) {
                    final String n = x.getName();
                    if ("PolyStyle".equals(n)) {
                        inPolyStyle = false;
                    } else if ("Style".equals(n)) {
                        styleId = null;
                    } else if ("Polygon".equals(n)) {
                        inPoly = false;
                        if (poly != null && poly.getExteriorRing() != null && poly.getExteriorRing().getNumPoints() >= 4)
                            polys.add(poly);
                        poly = null;
                    } else if ("Placemark".equals(n)) {
                        inPlacemark = false;
                        if (desc != null && !polys.isEmpty())
                            add(byKey, cells(desc), polys, color(styles, styleUrl));
                        if (byKey.size() > MAX_RUNS)
                            throw new java.io.IOException("FIRIS KML has too many runs");
                    }
                }
            }
        }
        final List<Run> out = new ArrayList<>(byKey.values());
        Collections.sort(out, new Comparator<Run>() {
            @Override
            public int compare(Run a, Run b) {
                return Long.compare(b.startMs, a.startMs);
            }
        });
        return out;
    }

    private static LineString ring(String text) {
        final LineString ls = new LineString(2);
        for (String t : text.trim().split("\\s+")) {
            final String[] c = t.split(",");
            if (c.length < 2)
                continue;
            try {
                final double lon = Double.parseDouble(c[0]), lat = Double.parseDouble(c[1]);
                if (lon >= -180 && lon <= 180 && lat >= -90 && lat <= 90)
                    ls.addPoint(lon, lat);
            } catch (NumberFormatException ignored) {
            }
        }
        return ls;
    }

    private static Map<String, String> cells(String desc) {
        final Map<String, String> m = new HashMap<>();
        final Matcher mt = CELL.matcher(desc);
        while (mt.find() && m.size() < 80) {
            final String v = mt.group(2).replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&").trim();
            if (!"<Null>".equals(v))
                m.put(mt.group(1), v);
        }
        return m;
    }

    /** KML colors are aabbggrr; shown at full strength, the way they read in Google Earth's outlines. */
    private static int color(Map<String, String> styles, String url) {
        final String s = url == null ? null : styles.get(url.startsWith("#") ? url.substring(1) : url);
        try {
            if (s != null && s.length() == 8) {
                final long v = Long.parseLong(s, 16);
                final int b = (int) (v >> 16) & 0xFF, g = (int) (v >> 8) & 0xFF, r = (int) v & 0xFF;
                return 0xFF000000 | (r << 16) | (g << 8) | b;
            }
        } catch (NumberFormatException ignored) {
        }
        return 0xFFFF4500;
    }

    private static void add(Map<String, Run> byKey, Map<String, String> a, List<Polygon> polys, int color) {
        final String fire = a.containsKey("fire_name") ? a.get("fire_name") : "Unnamed fire";
        final String started = a.containsKey("start_time") ? a.get("start_time") : "";
        final String key = fire + "|" + started;
        Run r = byKey.get(key);
        if (r == null) {
            r = new Run();
            r.fire = fire;
            r.started = started;
            r.startMs = utc(started);
            final String who = a.get("author_name"), org = a.get("author_affiliation");
            r.author = who == null ? "" : who + (org == null ? "" : ", " + org);
            r.inputs = inputs(a);
            byKey.put(key, r);
        }
        final Ring g = new Ring();
        g.hours = hours(a.get("elapsed_minutes"));
        g.color = color;
        try {
            if (a.containsKey("st_area(shape)"))
                g.areaM2 = Double.parseDouble(a.get("st_area(shape)"));
        } catch (NumberFormatException ignored) {
        }
        if (polys.size() == 1) {
            g.geom = polys.get(0);
        } else {
            final GeometryCollection gc = new GeometryCollection(2);
            for (Polygon p : polys)
                gc.addGeometry(p);
            g.geom = gc;
        }
        r.rings.add(g);
    }

    private static String inputs(Map<String, String> a) {
        final StringBuilder s = new StringBuilder();
        if (a.containsKey("wind_speed"))
            s.append("Wind ").append(a.get("wind_speed")).append(" mph")
                    .append(a.containsKey("wind_direction") ? " from " + a.get("wind_direction") + "°" : "");
        if (a.containsKey("air_temperature"))
            s.append(s.length() == 0 ? "" : ", ").append(a.get("air_temperature")).append("°F");
        if (a.containsKey("relative_humidity"))
            s.append(s.length() == 0 ? "" : ", RH ").append(a.get("relative_humidity")).append("%");
        if (a.containsKey("fm_dead_1"))
            s.append(s.length() == 0 ? "" : ", ").append("1-h fuel ").append(a.get("fm_dead_1")).append("%");
        return s.toString();
    }

    /** "2.5 Hours" to 2.5. */
    private static double hours(String s) {
        if (s == null)
            return 0;
        final Matcher m = Pattern.compile("([0-9.]+)").matcher(s);
        try {
            return m.find() ? Double.parseDouble(m.group(1)) : 0;
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** "10/15/2026 8:35:00 PM", in UTC: the fire's name carries the same moment in Pacific time. */
    private static long utc(String s) {
        try {
            final SimpleDateFormat f = new SimpleDateFormat("M/d/yyyy h:mm:ss a", Locale.US);
            f.setTimeZone(TimeZone.getTimeZone("UTC"));
            final Date d = f.parse(s);
            return d == null ? 0 : d.getTime();
        } catch (Exception e) {
            return 0;
        }
    }

    public static String clock(long ms) {
        return new SimpleDateFormat("HH:mm", Locale.US).format(new Date(ms));
    }

    public static String when(long ms) {
        return ms <= 0 ? "time not given" : new SimpleDateFormat("MMM d HH:mm", Locale.US).format(new Date(ms));
    }

    public static String hoursText(double h) {
        return (h == Math.floor(h) ? String.valueOf((int) h) : String.valueOf(h)) + " h";
    }

    // ---- the map -------------------------------------------------------------------

    private void attachLocked() throws Exception {
        store = new FeatureSetDatabase2(storeFile);
        final FeatureDataStore2.FeatureQueryParameters visibleOnly = new FeatureDataStore2.FeatureQueryParameters();
        visibleOnly.visibleOnly = true;
        layer = new FeatureLayer3("FIRIS model runs", store, visibleOnly);
        final FeatureDataStoreDeepMapItemQuery query = new FeatureDataStoreDeepMapItemQuery(layer) {
            @Override
            protected MapItem featureToMapItem(Feature feature) {
                final MapItem item = super.featureToMapItem(feature);
                item.setMetaString("title", feature.getName());
                item.setMetaString("callsign", feature.getName());
                return item;
            }
        };
        overlay = new FeatureDataStoreMapOverlay(mapView.getContext(), store, null,
                "FIRIS model runs", "file://asset/nothing", query, null, null);
        mapView.getMapOverlayManager().addFilesOverlay(overlay);
        mapView.addLayer(MapView.RenderStack.VECTOR_OVERLAYS, layer);
    }

    private List<Long> setIdsLocked() {
        final List<Long> ids = new ArrayList<>();
        FeatureSetCursor c = null;
        try {
            c = store.queryFeatureSets(new FeatureDataStore2.FeatureSetQueryParameters());
            while (c.moveToNext())
                ids.add(c.get().getId());
        } catch (Exception e) {
            Log.w(TAG, "FIRIS sets", e);
        } finally {
            if (c != null)
                c.close();
        }
        return ids;
    }

    /** Lock held. Replaces every set with the runs held now, in one write. */
    private void drawLocked() {
        if (store == null)
            return;
        final boolean on = isOn();
        boolean bulk = false;
        try {
            store.acquireModifyLock(true);
            bulk = true;
            for (long id : setIdsLocked())
                store.deleteFeatureSet(id);
            atFsid = -1;
            for (Run r : runs) {
                final long fsid = store.insertFeatureSet(new FeatureSet("FireCast", "FIRIS",
                        r.fire + " " + r.started, Double.MAX_VALUE, 0d));
                store.setFeatureSetVisible(fsid, on);
                final List<Ring> rings = new ArrayList<>(r.rings);
                // Largest first, so the first hour draws on top.
                Collections.sort(rings, new Comparator<Ring>() {
                    @Override
                    public int compare(Ring a, Ring b) {
                        return Double.compare(b.hours, a.hours);
                    }
                });
                for (Ring g : rings) {
                    final String name = r.shortName() + ", " + hoursText(g.hours) + " after " + when(r.startMs);
                    final AttributeSet a = new AttributeSet();
                    a.setAttribute("_title", name);
                    store.insertFeature(new Feature(fsid, name, g.geom, new BasicStrokeStyle(g.color, 2.5f), a,
                            Feature.AltitudeMode.ClampToGround, 0d));
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "FIRIS draw failed", e);
        } finally {
            if (bulk)
                store.releaseModifyLock();
        }
    }

    /** Lock held. By ids, so off hides and deletes nothing. */
    private void showLocked(boolean on) {
        if (store == null)
            return;
        try {
            for (long id : setIdsLocked())
                store.setFeatureSetVisible(id, on);
        } catch (Exception e) {
            Log.w(TAG, "FIRIS visibility", e);
        }
    }

    /** Lock held: the cyan edge comes back with the runs, after a restart or a fetch. */
    private void redrawAtLocked() {
        drawAtLocked(focus(), focusHours());
    }

    private void changed() {
        main.post(new Runnable() {
            @Override
            public void run() {
                final Listener l = listener;
                if (l != null)
                    l.onFirisChanged();
            }
        });
    }
}
