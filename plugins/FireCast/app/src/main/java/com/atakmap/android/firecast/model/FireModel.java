package com.atakmap.android.firecast.model;

import android.content.Context;
import android.graphics.PointF;
import android.os.Handler;
import android.os.Looper;
import android.view.KeyEvent;
import android.view.View;

import com.atakmap.android.features.FeatureDataStoreDeepMapItemQuery;
import com.atakmap.android.features.FeatureDataStoreMapOverlay;
import com.atakmap.android.firecast.Units;
import com.atakmap.android.maps.MapEvent;
import com.atakmap.android.maps.MapEventDispatcher;
import com.atakmap.android.maps.DefaultMapGroup;
import com.atakmap.android.maps.MapGroup;
import com.atakmap.android.maps.MapItem;
import com.atakmap.android.maps.MapView;
import com.atakmap.android.maps.Marker;
import com.atakmap.android.toolbar.widgets.TextContainer;
import com.atakmap.coremap.filesystem.FileSystemUtils;
import com.atakmap.coremap.log.Log;
import com.atakmap.coremap.maps.coords.GeoPoint;
import com.atakmap.coremap.maps.coords.GeoPointMetaData;
import com.atakmap.map.layer.feature.AttributeSet;
import com.atakmap.map.layer.feature.Feature;
import com.atakmap.map.layer.feature.FeatureDataStore2;
import com.atakmap.map.layer.feature.FeatureLayer3;
import com.atakmap.map.layer.feature.FeatureSet;
import com.atakmap.map.layer.feature.FeatureSetCursor;
import com.atakmap.map.layer.feature.datastore.FeatureSetDatabase2;
import com.atakmap.map.layer.feature.geometry.LineString;
import com.atakmap.map.layer.feature.geometry.Polygon;
import com.atakmap.map.layer.feature.style.BasicFillStyle;
import com.atakmap.map.layer.feature.style.BasicStrokeStyle;
import com.atakmap.map.layer.feature.style.CompositeStyle;
import com.atakmap.map.layer.feature.style.Style;

import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * A fire behavior run from a point the operator taps: where the fire is at 20, 40 and
 * 60 minutes, drawn on the map, with every input named on a page.
 *
 * <p>Proof of concept, 2026-10-11. One fuel model, one slope, one wind for the whole
 * run, read at the ignition: LANDFIRE for the ground, the nearest RAWS for the weather.
 * The engine is the Fire Lab's behave library ({@link Behave}); the shape is its own
 * fire ellipse with the ignition at the rear focus. A real run over terrain, fuel
 * changes and a wind field is the server version; this one is what a crew can get
 * at the fire with no signal, once the ground around it has been fetched.
 *
 * <p>Lives for the plugin's life, never inside a Tool: the run finishes and draws
 * whether the pane is open or not, and the last run comes back after a restart.
 */
public final class FireModel {

    private static final String TAG = "FireCast";
    public static final int[] STEPS_MIN = { 20, 40, 60 };
    /** The ground fetched around a start, each way. 4 km holds an hour of anything short of a wind-driven grass run. */
    static final double HALF_KM = 4;
    private static final double FT_TO_M = 0.3048d;

    public interface Listener {
        /** Main thread. */
        void onModelChanged();
    }

    /** Everything about one run: where, what went in, what came out. */
    public static final class Run {
        public double lat, lon;
        public long ranAt;
        public Landfire.Site site;
        public Weather.Reading weather;
        /** Every station with wind within reach, closest first, for the picker. */
        public java.util.List<Weather.Reading> stations = new java.util.ArrayList<>();
        /** The start's height above sea level, feet; NaN when the phone has no terrain here. */
        public double startElevFt = Double.NaN;
        /** The moistures used, percent: 1 h, 10 h, 100 h, live herb, live woody. */
        public double m1, m10, m100, mHerb, mWoody;
        public double windMph, windFromDeg;
        public boolean tenMeterWind;
        public String windNote;
        public String moistureNote;
        public double canopyHeightFt, crownRatio;
        public Behave.Result result;
        public String fuelName;
        public String conditions = COND_OBSERVED;
        /** Why there is no result, when there is none. */
        public String problem;
        /** The feature set that draws this run, 0 until drawn. */
        public long fsid;
        /** The run across the ground, or null when it was the one-point ellipse. */
        public Spread.Result ground;
        /** Why it was the ellipse, when it was. */
        public String groundNote;
        /** The perimeter the run began from, [lat, lon] pairs, or null for a point start. */
        public double[][] perimeter;
        /** When the fire started, epoch ms, or 0 for unknown; with a perimeter it calibrates the run. */
        public long startedAt;
        /** The rate multiplier calibration settled on; 1 when there was none. */
        public double rateScale = 1;
        public String calibrationNote;
        /** The HRRR field the run used, or null for one wind. */
        public WindField windField;
        public String windFieldNote;

        /** The grid's reach each way from the start, km. */
        public double halfKm = HALF_KM;
        /** Buildings reached by each step, homes among them, and people, from FEMA's inventory; null when not fetched. */
        public int[] structures, homes;
        public double[] people;
        public String structureNote;
        /** On the map, or hidden by its switch. Hidden is not deleted. */
        public boolean visible = true;

        /** "20:43 run, SH2, 10 mph from SE": how a run is named in a list. */
        public String label() {
            final StringBuilder sb = new StringBuilder(stamp(ranAt)).append(" run");
            if (fuelName != null && !fuelName.isEmpty())
                sb.append(", ").append(fuelName.split(" ")[0]);
            if (result != null)
                sb.append(String.format(Locale.US, ", %.0f mph from %s, %s in 1 h", windMph, Units.compass(windFromDeg),
                        acres(60) < 10 ? String.format(Locale.US, "%.1f ac", acres(60)) : String.format(Locale.US, "%,.0f ac", acres(60))));
            if (perimeter != null)
                sb.append(", from a perimeter");
            if (structures != null)
                sb.append(", ").append(structures[STEPS_MIN.length - 1]).append(" bldg");
            if (!COND_OBSERVED.equals(conditions))
                sb.append(", ").append(conditionsLabel(conditions).toLowerCase(Locale.US));
            return sb.toString();
        }

        /** Head fire distance at a time, meters: the farthest cell reached, or the ellipse's head. */
        public double headM(int minutes) {
            if (ground != null)
                return ground.reach(minutes)[0];
            return (result.bFtPerMin + result.cFtPerMin) * minutes * FT_TO_M;
        }

        /** Which way the farthest reach is, degrees; the ellipse's heading without a ground run. */
        public double headDeg(int minutes) {
            if (ground != null)
                return ground.reach(minutes)[1];
            return result.headingDeg;
        }

        /** Acres at a time: cells reached, or the ellipse's pi a b. */
        public double acres(int minutes) {
            if (ground != null)
                return ground.acres(minutes);
            final double a = result.aFtPerMin * minutes, b = result.bFtPerMin * minutes;
            return Math.PI * a * b / 43560d;
        }
    }

    private final MapView mapView;
    private final Context pluginContext;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final File root, storeFile, saveFile;
    private final Object lock = new Object();
    private FeatureSetDatabase2 store;
    private FeatureLayer3 layer;
    private FeatureDataStoreMapOverlay overlay;
    private volatile Run last;
    /** Every run on the map, newest first; each is its own feature set and its own layer in Overlay Manager. */
    private final List<Run> runs = new java.util.concurrent.CopyOnWriteArrayList<>();
    private static final int MAX_RUNS = 10;
    private volatile boolean running;
    private volatile String conditionsForRun = COND_OBSERVED;
    private volatile String status = "";
    private Listener listener;
    private boolean started;

    /** What the run assumes about the day: the station as read, or a dry preset. */
    public static final String COND_OBSERVED = "observed", COND_GUST = "gust", COND_DRY = "dry", COND_DRY_WINDY = "drywindy";
    private final android.content.SharedPreferences prefs;
    private static final String PREF_COND = "model.conditions", PREF_LAT = "model.startLat", PREF_LON = "model.startLon";
    private static final String PREF_STATION = "model.station";

    /** The station the operator picked by id, or "" for the representative one. */
    public String station() {
        return prefs.getString(PREF_STATION, "");
    }

    public void setStation(String wxId) {
        prefs.edit().putString(PREF_STATION, wxId == null ? "" : wxId).apply();
    }

    public String conditions() {
        return prefs.getString(PREF_COND, COND_OBSERVED);
    }

    public void setConditions(String c) {
        prefs.edit().putString(PREF_COND, c).apply();
    }

    public static String conditionsLabel(String c) {
        if (COND_GUST.equals(c))
            return "As observed, gusts";
        if (COND_DRY.equals(c))
            return "Dry day";
        if (COND_DRY_WINDY.equals(c))
            return "Dry and 20 mph";
        return "As observed";
    }

    // the start marker: an ordinary ATAK marker, so Move and Fine Adjust on its radial
    // are ATAK's own, and the model reads wherever it was left
    private static final String GROUP = "Fire Cast";
    private static final String MARKER_UID = "firecast.model.start";
    private MapGroup group;
    private Marker marker;
    private Runnable onPlaced;
    private PerimeterPicker perimeterPicker;
    /** The drawn perimeter waiting for a run, or null. */
    private volatile double[][] perimeter;
    private static final String PREF_PERIMETER = "model.perimeter", PREF_STARTED = "model.startedMin";

    public double[][] perimeter() {
        return perimeter;
    }

    /** Minutes ago the fire started, as the operator set it; 0 for unknown. */
    public int startedMinutesAgo() {
        return prefs.getInt(PREF_STARTED, 0);
    }

    public void setStartedMinutesAgo(int m) {
        prefs.edit().putInt(PREF_STARTED, m).apply();
    }

    /** Hands the operator ATAK's polygon tool; the shape they close becomes the perimeter. */
    public void drawPerimeter() {
        if (perimeterPicker == null)
            perimeterPicker = new PerimeterPicker(mapView, new PerimeterPicker.Callback() {
                @Override
                public void onPerimeter(GeoPoint[] ring) {
                    setPerimeter(ring);
                    if (onPlaced != null)
                        onPlaced.run();
                }

                @Override
                public void onCancelled(String reason) {
                    status = reason == null ? "" : reason;
                    changed();
                }
            });
        perimeterPicker.start();
    }

    private void setPerimeter(GeoPoint[] ring) {
        final double[][] r = new double[ring.length][];
        for (int i = 0; i < ring.length; i++)
            r[i] = new double[] { ring[i].getLatitude(), ring[i].getLongitude() };
        perimeter = r;
        try {
            final org.json.JSONArray a = new org.json.JSONArray();
            for (double[] p : r)
                a.put(new org.json.JSONArray().put(p[0]).put(p[1]));
            prefs.edit().putString(PREF_PERIMETER, a.toString()).apply();
        } catch (Exception ignored) {
        }
    }

    public void clearPerimeter() {
        perimeter = null;
        prefs.edit().remove(PREF_PERIMETER).apply();
        if (perimeterPicker != null)
            perimeterPicker.clearDrawn();
    }

    /** Runs from the perimeter's middle with everything inside it already burning. */
    public boolean runFromPerimeter() {
        final double[][] ring = perimeter;
        if (ring == null || ring.length < 3)
            return false;
        double lat = 0, lon = 0;
        for (double[] p : ring) {
            lat += p[0];
            lon += p[1];
        }
        run(lat / ring.length, lon / ring.length);
        return true;
    }

    public void setOnPlaced(Runnable r) {
        onPlaced = r;
    }

    /** Where the start marker is, or null when there is none. */
    public GeoPoint startPoint() {
        return marker == null ? null : marker.getPoint();
    }

    /** Puts the start marker here, or moves it; remembered so it is back after a restart. */
    public void place(GeoPoint p) {
        prefs.edit().putFloat(PREF_LAT, (float) p.getLatitude())
                .putFloat(PREF_LON, (float) p.getLongitude()).apply();
        if (marker == null) {
            marker = new Marker(p, MARKER_UID);
            marker.setType("b-m-p-s-m");
            marker.setTitle("Fire start");
            marker.setMetaString("callsign", "Fire start");
            // Working mark, not a report: not saved by ATAK, not sent to anyone.
            marker.setMetaBoolean("archive", false);
            marker.setMetaBoolean("removable", true);
            marker.setMetaBoolean("editable", true);
            marker.setMovable(true);
            marker.setClickable(true);
            // Cyan, like the rest of the plugin's own marks, so it reads against tan ground.
            marker.setMetaInteger("color", 0xFF00E5FF);
            marker.setMetaString("how", "h-g-i-g-o");
            // ATAK's own spot-marker icon: an explicit asset icon drew nothing (dev 1,
            // 2026-10-11); the default dot draws and takes the color meta.
            group().addItem(marker);
        } else {
            marker.setPoint(p);
        }
        // The marker had left its group between the restart and the tap ("in group
        // false", dev 1, 2026-10-11); whatever took it out, it goes back in.
        if (marker.getGroup() == null)
            group().addItem(marker);
        try {
            final PointF at = mapView.forward(marker.getPoint());
            Log.d(TAG, "start marker placed; on screen at " + (at == null ? "?" : Math.round(at.x) + "," + Math.round(at.y))
                    + " of " + mapView.getWidth() + "x" + mapView.getHeight() + "; in group " + (marker.getGroup() != null)
                    + "; group in root " + (mapView.getRootGroup().findMapGroup(GROUP) != null));
        } catch (Exception e) {
            Log.w(TAG, "start marker check", e);
        }
    }

    /**
     * Brings the start into the half of the screen the page does not cover. The page
     * reopens over the right half the moment the start is placed, and a tap made there
     * put the marker straight under it (dev 1, 2026-10-11).
     */
    private void showStart() {
        final Marker m = marker;
        if (m == null)
            return;
        try {
            final PointF at = mapView.forward(m.getPoint());
            final float w = mapView.getWidth(), h = mapView.getHeight();
            if (at == null || w <= 0 || h <= 0)
                return;
            // Where the map center has to go so the marker lands a quarter of the way in.
            final GeoPointMetaData c = mapView.inverseWithElevation(at.x + w / 4f, h / 2f);
            if (c != null && c.get() != null && c.get().isValid())
                mapView.getMapController().panTo(c.get(), true);
        } catch (Exception e) {
            Log.w(TAG, "could not pan to the start", e);
        }
    }

    private void removeMarker() {
        if (marker == null)
            return;
        try {
            if (marker.getGroup() != null)
                marker.removeFromGroup();
        } catch (RuntimeException ignored) {
        }
        marker = null;
    }

    private MapGroup group() {
        if (group != null && mapView.getRootGroup().findMapGroup(GROUP) == null) {
            mapView.getRootGroup().addGroup(group);
            return group;
        }
        if (group != null)
            return group;
        MapGroup g = mapView.getRootGroup().findMapGroup(GROUP);
        if (g == null) {
            g = new DefaultMapGroup(GROUP);
            mapView.getRootGroup().addGroup(g);
        }
        group = g;
        return g;
    }
    /** Run around the pick: the plugin hides its other layer so the tap reaches the map. */
    private Runnable onPickStart, onPickEnd;

    public void setPickHooks(Runnable onStart, Runnable onEnd) {
        onPickStart = onStart;
        onPickEnd = onEnd;
    }

    private void layerVisible(boolean v) {
        try {
            final FeatureLayer3 l = layer;
            if (l != null)
                l.setVisible(v);
        } catch (Exception ignored) {
        }
    }

    // the tap
    private boolean picking;
    private MapEventDispatcher.MapEventDispatchListener tapListener;
    /** Where the finger last lifted, for an item click ATAK delivers with no point. */
    private volatile PointF lastUp;
    private final View.OnTouchListener touchWatcher = new View.OnTouchListener() {
        @Override
        public boolean onTouch(View v, android.view.MotionEvent e) {
            if (e.getActionMasked() == android.view.MotionEvent.ACTION_UP
                    || e.getActionMasked() == android.view.MotionEvent.ACTION_DOWN)
                lastUp = new PointF(e.getX(), e.getY());
            return false;   // watched, never taken
        }
    };
    private final View.OnKeyListener backListener = new View.OnKeyListener() {
        @Override
        public boolean onKey(View v, int keyCode, KeyEvent event) {
            if (keyCode != KeyEvent.KEYCODE_BACK)
                return false;
            cancelPick();
            return true;
        }
    };

    public FireModel(MapView mapView, Context pluginContext) {
        this.mapView = mapView;
        this.pluginContext = pluginContext;
        this.root = FileSystemUtils.getItem("tools/firecast");
        this.storeFile = new File(root, "model.sqlite");
        this.saveFile = new File(root, "model.json");
        this.prefs = mapView.getContext().getSharedPreferences("firecast", Context.MODE_PRIVATE);
    }

    public void setListener(Listener l) {
        listener = l;
    }

    public Run last() {
        return last;
    }

    /** The runs on the map, newest first. */
    public List<Run> runs() {
        return new ArrayList<>(runs);
    }

    public boolean isRunning() {
        return running;
    }

    public String status() {
        return status;
    }

    public void start() {
        started = true;
        if (!root.isDirectory() && !root.mkdirs())
            Log.w(TAG, "could not create " + root);
        try {
            synchronized (lock) {
                attachLocked();
                // The store is rebuilt from the save file below; whatever the last
                // session left in it would otherwise sit beside the rebuilt sets as
                // rings no row owns and no Delete can reach (dev 1, 2026-10-11).
                dropAllLocked();
            }
        } catch (Exception e) {
            Log.e(TAG, "could not put the model layer on the map", e);
        }
        final float lat = prefs.getFloat(PREF_LAT, Float.NaN), lon = prefs.getFloat(PREF_LON, Float.NaN);
        if (!Float.isNaN(lat) && !Float.isNaN(lon))
            place(new GeoPoint(lat, lon));
        try {
            final String saved = prefs.getString(PREF_PERIMETER, null);
            if (saved != null) {
                final org.json.JSONArray a = new org.json.JSONArray(saved);
                final double[][] r = new double[a.length()][];
                for (int i = 0; i < a.length(); i++)
                    r[i] = new double[] { a.getJSONArray(i).getDouble(0), a.getJSONArray(i).getDouble(1) };
                if (r.length >= 3)
                    perimeter = r;
            }
        } catch (Exception e) {
            Log.w(TAG, "saved perimeter unreadable", e);
        }
        worker.execute(new Runnable() {
            @Override
            public void run() {
                restore();
            }
        });
    }

    public void stop() {
        started = false;
        cancelPick();
        if (perimeterPicker != null) {
            perimeterPicker.dispose();
            perimeterPicker = null;
        }
        removeMarker();
        if (group != null) {
            try {
                mapView.getRootGroup().removeGroup(group);
            } catch (RuntimeException ignored) {
            }
            group = null;
        }
        synchronized (lock) {
            detachLocked();
        }
        worker.shutdownNow();
    }

    // ---- the tap ------------------------------------------------------------------

    /** Waits for one tap on the map and puts the start marker there. */
    public void pickStart() {
        if (picking)
            cancelPick();
        if (running)
            return;
        final MapEventDispatcher d = mapView.getMapEventDispatcher();
        d.pushListeners();
        d.clearListeners(MapEvent.MAP_CLICK);
        d.clearListeners(MapEvent.MAP_LONG_PRESS);
        d.clearListeners(MapEvent.ITEM_CLICK);
        tapListener = new MapEventDispatcher.MapEventDispatchListener() {
            @Override
            public void onMapEvent(MapEvent event) {
                // A tap on another plugin's shape arrives as an item click, and one of
                // ATAK's two paths for that carries no point; the finger's last position
                // is the same tap.
                PointF pf = event.getPointF();
                if (pf == null)
                    pf = lastUp;
                if (pf == null)
                    return;
                final GeoPointMetaData gp = mapView.inverseWithElevation(pf.x, pf.y);
                cancelPick();
                if (gp == null || gp.get() == null || !gp.get().isValid())
                    return;
                Log.d(TAG, "pick tap: " + event.getType() + (event.getPointF() == null ? " (no point, used the finger)" : ""));
                place(new GeoPoint(gp.get().getLatitude(), gp.get().getLongitude()));
                // The map stays where the operator left it (they said so, 2026-10-11).
                if (onPlaced != null)
                    onPlaced.run();
            }
        };
        d.addMapEventListener(MapEvent.MAP_CLICK, tapListener);
        d.addMapEventListener(MapEvent.ITEM_CLICK, tapListener);
        lastUp = null;
        mapView.addOnTouchListener(touchWatcher);
        mapView.addOnKeyListener(backListener);
        // Nothing of ours under the finger: the footprints and the last run step aside
        // so the tap is a tap on the ground, not a pick of an item.
        layerVisible(false);
        if (onPickStart != null)
            onPickStart.run();
        // Other plugins' items are under the finger too, and ATAK's touch controller
        // opens its "select item" list when a tap hits more than one. Off while picking.
        try {
            mapView.getMapTouchController().skipDeconfliction(true);
        } catch (Exception ignored) {
        }
        TextContainer.getInstance().displayPrompt("Tap the map where the fire starts. Move it after if it needs to be exact.");
        picking = true;
    }

    /** Stops waiting for a tap and gives ATAK its listeners back; not optional. */
    public void cancelPick() {
        if (!picking)
            return;
        picking = false;
        TextContainer.getInstance().closePrompt();
        final MapEventDispatcher d = mapView.getMapEventDispatcher();
        if (tapListener != null) {
            d.removeMapEventListener(MapEvent.MAP_CLICK, tapListener);
            d.removeMapEventListener(MapEvent.ITEM_CLICK, tapListener);
            tapListener = null;
        }
        d.popListeners();
        mapView.removeOnKeyListener(backListener);
        mapView.removeOnTouchListener(touchWatcher);
        layerVisible(true);
        if (onPickEnd != null)
            onPickEnd.run();
        try {
            mapView.getMapTouchController().skipDeconfliction(false);
        } catch (Exception ignored) {
        }
    }

    // ---- the run ------------------------------------------------------------------

    /** Puts the start on an earlier run's point and runs again under today's conditions. */
    public void rerun(Run r) {
        if (r == null || running)
            return;
        place(new GeoPoint(r.lat, r.lon));
        run(r.lat, r.lon);
    }

    /** Runs from wherever the start marker is now. */
    public boolean runFromStart() {
        final GeoPoint p = startPoint();
        if (p == null)
            return false;
        run(p.getLatitude(), p.getLongitude());
        return true;
    }

    /** Fetches the ground and the weather, runs the model, draws. Any thread. */
    public void run(final double lat, final double lon) {
        if (running)
            return;
        running = true;
        conditionsForRun = conditions();
        status = "Reading the ground and the weather…";
        changed();
        worker.execute(new Runnable() {
            @Override
            public void run() {
                Log.d(TAG, "model run started");
                try {
                    runNow(lat, lon);
                } catch (Throwable t) {
                    // The executor swallows an uncaught throwable and the flag would
                    // stay up, so no later run could start (dev 1, 2026-10-11).
                    Log.e(TAG, "model run died", t);
                    status = "The run failed: " + t;
                } finally {
                    running = false;
                    changed();
                }
            }
        });
    }

    /** The run itself, on the worker. */
    private void runNow(double lat, double lon) {
        {
            {
                final Run r = new Run();
                r.lat = lat;
                r.lon = lon;
                r.ranAt = System.currentTimeMillis();
                r.perimeter = perimeter;
                final int ago = startedMinutesAgo();
                r.startedAt = ago > 0 ? r.ranAt - ago * 60000L : 0;
                try {
                    r.site = Landfire.at(lat, lon);
                } catch (Exception e) {
                    Log.w(TAG, "LANDFIRE failed", e);
                    r.problem = "Could not read the fuel and slope here (LANDFIRE did not answer).";
                }
                if (r.problem == null) {
                    try {
                        final double hae = com.atakmap.map.elevation.ElevationManager.getElevation(lat, lon, null);
                        if (!Double.isNaN(hae))
                            r.startElevFt = hae / FT_TO_M;
                    } catch (Throwable t) {
                        Log.w(TAG, "no terrain height at the start: " + t);
                    }
                    try {
                        r.stations = Weather.within(lat, lon, 50);
                    } catch (Exception e) {
                        Log.w(TAG, "RAWS failed", e);
                    }
                    try {
                        // The NWS networks: airports, utilities, citizens, in the valleys
                        // the RAWS are not. A RAWS already listed is left out by name.
                        for (Weather.Reading n : Weather.nwsNear(lat, lon, r.startElevFt, 10)) {
                            boolean dup = false;
                            for (Weather.Reading s : r.stations)
                                if (s.station.equalsIgnoreCase(n.station)
                                        && com.atakmap.android.firecast.DetectionFeed.metersBetween(new double[] { s.latitude, s.longitude }, n.latitude, n.longitude) < 1500)
                                    dup = true;
                            if (!dup)
                                r.stations.add(n);
                        }
                    } catch (Exception e) {
                        Log.w(TAG, "NWS stations failed", e);
                    }
                    try {
                        final Weather.Reading fc = Weather.forecastAt(lat, lon, r.ranAt);
                        if (fc != null)
                            r.stations.add(0, fc);
                    } catch (Exception e) {
                        Log.w(TAG, "NWS forecast grid failed", e);
                    }
                    try {
                        // The 3 km wind field over the run's box, this hour and the next.
                        final double dLat = r.halfKm * 1000d / 111320d;
                        final double dLon = r.halfKm * 1000d / (111320d * Math.max(0.02d, Math.cos(Math.toRadians(lat))));
                        r.windField = WindField.fetch(lat - dLat, lon - dLon, lat + dLat, lon + dLon, r.ranAt);
                        final double[] w = r.windField.at(lat, lon, r.ranAt);
                        if (w != null)
                            r.stations.add(0, new Weather.Reading("HRRR", "HRRR", "HRRR 3 km wind field, this hour",
                                    lat, lon, 0, r.ranAt, w[0], Double.NaN, w[1], Double.NaN, Double.NaN));
                    } catch (Exception e) {
                        Log.w(TAG, "HRRR wind field failed: " + e);
                        r.windFieldNote = "No HRRR wind field: " + e.getMessage();
                    }

                    try {
                        final String want = station();
                        for (Weather.Reading s : r.stations)
                            if (!want.isEmpty() && want.equals(s.wxId))
                                r.weather = s;
                        if (r.weather == null) {
                            final java.util.List<Weather.Reading> observed = new java.util.ArrayList<>();
                            for (Weather.Reading s : r.stations)
                                if (!"NWS forecast".equals(s.source) && !"HRRR".equals(s.source))
                                    observed.add(s);
                            r.weather = Weather.representative(observed, r.startElevFt);
                            if (r.weather == null && !r.stations.isEmpty())
                                r.weather = r.stations.get(0);
                        }
                    } catch (Exception e) {
                        Log.w(TAG, "RAWS failed", e);
                    }
                    compute(r);
                    if (r.problem == null)
                        acrossTheGround(r);
                }
                last = r;
                status = r.problem != null ? r.problem : "Model on the map";
                if (r.problem == null) {
                    synchronized (lock) {
                        drawLocked(r);
                    }
                    runs.add(0, r);
                    while (runs.size() > MAX_RUNS) {
                        final Run old = runs.remove(runs.size() - 1);
                        synchronized (lock) {
                            dropLocked(old);
                        }
                    }
                    save();
                }
            }
        }
    }

    /** Model inputs from the site and the reading, with the assumptions written down. */
    private void compute(Run r) {
        r.conditions = conditionsForRun;
        if (!r.site.burnable()) {
            r.fuelName = r.site.fuelModel < 0 ? "unknown" : Behave.name(r.site.fuelModel);
            r.problem = "LANDFIRE says this ground does not burn (" + (r.fuelName.isEmpty()
                    ? "code " + r.site.fuelModel : r.fuelName) + "). Tap on wildland fuel.";
            return;
        }
        r.fuelName = Behave.name(r.site.fuelModel);
        // Fuel moisture: the nearest station's 10-hour stick when it has one, dry fall
        // defaults when it does not. Live fuels at cured-season values.
        Weather.Reading w = r.weather;
        final boolean fieldWind = w != null && "HRRR".equals(w.source) && r.windField != null;
        if (fieldWind) {
            // The field gives wind only; humidity, temperature and the stick come from
            // the forecast grid or the best station, whichever is there.
            Weather.Reading other = null;
            for (Weather.Reading s : r.stations)
                if ("NWS forecast".equals(s.source))
                    other = s;
            if (other == null) {
                final java.util.List<Weather.Reading> observed = new java.util.ArrayList<>();
                for (Weather.Reading s : r.stations)
                    if (!"NWS forecast".equals(s.source) && !"HRRR".equals(s.source))
                        observed.add(s);
                other = Weather.representative(observed, r.startElevFt);
            }
            if (other != null)
                w = other;
        }
        final boolean dry = COND_DRY.equals(r.conditions) || COND_DRY_WINDY.equals(r.conditions);
        if (dry) {
            r.m1 = 4;
            r.m10 = 5;
            r.m100 = 7;
            r.moistureNote = "Dry day preset.";
        } else if (w != null && !Double.isNaN(w.fuelMoisture) && w.fuelMoisture > 0 && w.fuelMoisture < 40) {
            r.m10 = w.fuelMoisture;
            r.m1 = Math.max(2, w.fuelMoisture - 1);
            r.m100 = w.fuelMoisture + 2;
            r.moistureNote = "The 10 h is the station's fuel stick.";
        } else if (w != null && !Double.isNaN(Weather.fineFuelMoisture(w.relativeHumidity, w.airTempF))) {
            r.m1 = Weather.fineFuelMoisture(w.relativeHumidity, w.airTempF);
            r.m10 = r.m1 + 1;
            r.m100 = r.m1 + 3;
            r.moistureNote = "The 1 h is estimated from the station's humidity and temperature; it has no fuel stick.";
        } else {
            r.m1 = 4;
            r.m10 = 5;
            r.m100 = 7;
            r.moistureNote = "Dry-season defaults; no station reading to go on.";
        }
        r.mHerb = 30;
        r.mWoody = 60;
        if (fieldWind && !COND_DRY_WINDY.equals(r.conditions)) {
            r.windMph = r.weather.windMph;
            r.windFromDeg = r.weather.windFromDeg;
            r.tenMeterWind = true;
            r.windNote = "HRRR 3 km field, varying across the ground and the hour; at the start "
                    + String.format(Locale.US, "%.0f mph from %s", r.windMph, Units.compass(r.windFromDeg));
        } else if (COND_DRY_WINDY.equals(r.conditions)) {
            r.windMph = 20;
            r.windFromDeg = w != null && w.hasWind() ? w.windFromDeg : 0;
            r.windNote = "preset 20 mph" + (w != null && w.hasWind() ? ", from the direction " + w.station + " reads" : ", from the north");
        } else if (COND_GUST.equals(r.conditions) && w != null && w.hasWind()) {
            final boolean hasGust = !Double.isNaN(w.gustMph) && w.gustMph > w.windMph;
            r.windMph = hasGust ? w.gustMph : w.windMph;
            r.windFromDeg = w.windFromDeg;
            r.tenMeterWind = w.tenMeterWind;
            r.windNote = (hasGust ? "the gust at " : "no gust reported, the wind at ") + w.station + " (" + w.source + ")"
                    + ("NWS forecast".equals(w.source) ? "" : ", " + Units.format(w.distanceM) + " away") + heightNote(r, w);
        } else if (w != null && w.hasWind()) {
            r.windMph = w.windMph;
            r.windFromDeg = w.windFromDeg;
            r.tenMeterWind = w.tenMeterWind;
            r.windNote = w.station + " (" + w.source + ")"
                    + ("NWS forecast".equals(w.source) ? ", at this spot" : ", " + Units.format(w.distanceM) + " away") + heightNote(r, w);
        } else {
            r.windMph = 0;
            r.windFromDeg = 0;
            r.windNote = "no station with a wind reading within 50 mi; run with no wind";
        }
        r.canopyHeightFt = r.site.coverPct >= 10 ? 30 : 0;
        r.crownRatio = r.site.coverPct >= 10 ? 0.5 : 0;
        r.result = Behave.surface(r.site.fuelModel, r.m1, r.m10, r.m100, r.mHerb, r.mWoody,
                r.windMph, r.tenMeterWind, r.windFromDeg, r.site.slopeDeg, r.site.aspectDeg,
                r.site.coverPct, r.canopyHeightFt, r.crownRatio);
        finish(r);
    }

    static String acresText(double a) {
        return a < 10 ? String.format(Locale.US, "%.1f acres", a) : String.format(Locale.US, "%,.0f acres", a);
    }

    /** ", 2,100 ft above the start" when the station's height is far from the start's; "" otherwise. */
    static String heightNote(Run r, Weather.Reading w) {
        if (w == null || Double.isNaN(r.startElevFt) || w.elevationFt <= 0)
            return "";
        final double d = w.elevationFt - r.startElevFt;
        if (Math.abs(d) < 300)
            return "";
        return String.format(Locale.US, ", %,.0f ft %s the start", Math.abs(d), d > 0 ? "above" : "below");
    }

    /**
     * The run across the real ground: LANDFIRE's grids around the start, the engine
     * asked once per kind of cell with the run's wind and moistures, the fire spread
     * cell by cell. Falls back to the ellipse, and says so, when the ground cannot
     * be had (no network and nothing on disk). Worker thread.
     */
    private void acrossTheGroundNoStructures(final Run r) {
        skipStructures = true;
        try {
            acrossTheGround(r);
        } finally {
            skipStructures = false;
        }
    }

    private boolean skipStructures;

    private void acrossTheGround(final Run r) {
        final Landscape land;
        try {
            land = Landscape.around(r.lat, r.lon, r.halfKm);
        } catch (Exception e) {
            Log.w(TAG, "landscape unavailable, ellipse instead: " + e);
            r.groundNote = "The ground around the start could not be fetched (no network, nothing saved here), "
                    + "so this is the one-fuel ellipse.";
            return;
        }
        final long t0 = System.currentTimeMillis();
        final boolean useField = r.windField != null && r.weather != null && "HRRR".equals(r.weather.source)
                && !COND_DRY_WINDY.equals(r.conditions);
        final Spread.Behavior behavior = new Spread.Behavior() {
                    @Override
                    public double[] run(int fuelModel, double slopeDeg, double aspectDeg, double windMph, double windFrom) {
                        final double cover = r.site.coverPct;
                        final boolean own = Double.isNaN(windMph);
                        final double mph = own ? r.windMph : windMph, from = own ? r.windFromDeg : windFrom;
                        final boolean tenM = own ? r.tenMeterWind : true;
                        final Behave.Result b = Behave.surface(fuelModel, r.m1, r.m10, r.m100, r.mHerb, r.mWoody,
                                mph, tenM, from, slopeDeg, aspectDeg,
                                cover, r.canopyHeightFt, r.crownRatio);
                        if (b == null)
                            return null;
                        // Embers from this kind of cell: Albini's surface-fire reach at the
                        // 20 ft wind (a 10 m wind scaled down to 20 ft), over open ground
                        // or the canopy here.
                        final double wind20 = tenM ? mph * 0.87d : mph;
                        final double spotM = Behave.spotDistanceM(b.flameLengthFt, wind20, r.canopyHeightFt);
                        return new double[] { b.rosFtPerMin, b.headingDeg, b.aFtPerMin, b.bFtPerMin, b.cFtPerMin, b.flameLengthFt, spotM };
                    }
                };
        // Calibration: with a perimeter and a start time, the model is run from the
        // perimeter's middle for the time elapsed, and the ratio of what the fire has
        // actually covered to what the model would have gives the rate scale, by the
        // square root since area grows with the square of the rate. Clamped: a fire
        // that has done ten times the model is a wind the station did not see, and
        // the page says so rather than a number nobody should trust.
        r.rateScale = 1;
        if (r.perimeter != null && r.startedAt > 0) {
            final double elapsedMin = (r.ranAt - r.startedAt) / 60000d;
            final double observedAcres = Spread.ringAcres(r.perimeter);
            if (elapsedMin >= 5 && observedAcres > 0.1) {
                final Spread.Result trial = Spread.run(land, r.lat, r.lon, r.halfKm, elapsedMin, behavior, null, 1d, null, r.startedAt);
                final double modeled = trial.acres(elapsedMin);
                if (modeled > 0.05) {
                    final double raw = Math.sqrt(observedAcres / modeled);
                    r.rateScale = Math.max(0.3, Math.min(3, raw));
                    r.calibrationNote = String.format(Locale.US,
                            "Calibrated: the fire has covered %s in %.0f min where the model would have covered %s, so its rates are scaled %.1fx%s.",
                            acresText(observedAcres), elapsedMin, acresText(modeled), r.rateScale,
                            raw > 3 ? " (held at 3x; the fire is running far ahead of these conditions)"
                                    : raw < 0.3 ? " (held at 0.3x; the fire is far behind these conditions)" : "");
                }
            }
        }
        final Spread.Wind field = !useField ? null : new Spread.Wind() {
            @Override
            public double[] at(double lat, double lon, long whenMillis) {
                return r.windField.at(lat, lon, whenMillis);
            }
        };
        final Spread.Result g = Spread.run(land, r.lat, r.lon, r.halfKm, STEPS_MIN[STEPS_MIN.length - 1],
                behavior, r.perimeter, r.rateScale, field, r.ranAt);
        Log.d(TAG, "ground run: " + g.cellsSeen + " cells reached of " + g.cellsBurnable + " burnable, "
                + g.fuelsCrossed + " fuels, " + (System.currentTimeMillis() - t0) + " ms");
        if (g.cellsSeen < 2) {
            r.groundNote = "The ground run did not take: the start sits on ground that does not burn. This is the one-fuel ellipse.";
            return;
        }
        if (g.cellsSeen <= 4) {
            r.problem = "The fire cannot leave the start: LANDFIRE marks the ground around it as commercial, water, bare or "
                    + "farmed in 30 m cells, and this model does not spot across that. Move the start onto fuel or "
                    + "into a neighborhood and run again.";
            return;
        }
        r.ground = g;
        if (!skipStructures)
            countStructures(r);
    }

    /**
     * What the fire reaches: FEMA's building inventory inside the hour's reach, each
     * building placed in the step its cell is reached. Worker thread; a failed fetch
     * leaves the counts out and says so, never zero.
     */
    private void countStructures(Run r) {
        final Spread.Result g = r.ground;
        // The box the hour ring fits in, a cell of margin each way.
        double s = Double.MAX_VALUE, w = Double.MAX_VALUE, n = -Double.MAX_VALUE, e = -Double.MAX_VALUE;
        final int last = STEPS_MIN[STEPS_MIN.length - 1];
        for (int y = 0; y < g.ny; y++)
            for (int x = 0; x < g.nx; x++) {
                if (g.arrival[y * g.nx + x] > last)
                    continue;
                s = Math.min(s, g.lat0 + y * g.dLat);
                n = Math.max(n, g.lat0 + (y + 1) * g.dLat);
                w = Math.min(w, g.lon0 + x * g.dLon);
                e = Math.max(e, g.lon0 + (x + 1) * g.dLon);
            }
        if (s > n) {
            r.structureNote = "Nothing reached in the hour.";
            return;
        }
        final List<Structures.Building> buildings;
        try {
            buildings = Structures.within(s - g.dLat, w - g.dLon, n + g.dLat, e + g.dLon);
        } catch (Exception ex) {
            Log.w(TAG, "structures failed", ex);
            r.structureNote = "Structures not counted: the building inventory could not be reached.";
            return;
        }
        final int[] count = new int[STEPS_MIN.length], homes = new int[STEPS_MIN.length];
        final double[] people = new double[STEPS_MIN.length];
        for (Structures.Building b : buildings) {
            final int x = (int) Math.floor((b.lon - g.lon0) / g.dLon), y = (int) Math.floor((b.lat - g.lat0) / g.dLat);
            if (x < 0 || y < 0 || x >= g.nx || y >= g.ny)
                continue;
            // A building's own cell is urban and never burns; the fire reaches the
            // building when it reaches any cell touching it.
            float t = Float.POSITIVE_INFINITY;
            for (int dy = -1; dy <= 1; dy++)
                for (int dx = -1; dx <= 1; dx++) {
                    final int xx = x + dx, yy = y + dy;
                    if (xx >= 0 && yy >= 0 && xx < g.nx && yy < g.ny)
                        t = Math.min(t, g.arrival[yy * g.nx + xx]);
                }
            for (int i = 0; i < STEPS_MIN.length; i++) {
                if (t <= STEPS_MIN[i]) {
                    count[i]++;
                    if (b.residential())
                        homes[i]++;
                    people[i] += b.population;
                }
            }
        }
        r.structures = count;
        r.homes = homes;
        r.people = people;
        r.structureNote = buildings.size() >= Structures.MAX
                ? "The inventory stopped at " + Structures.MAX + " buildings, so these counts are a floor."
                : null;
        Log.d(TAG, "structures: " + buildings.size() + " in the box, " + count[STEPS_MIN.length - 1] + " reached in the hour");
    }

    /** The one judgment on a run's numbers: no spread is a sentence, not a zero. */
    private static void finish(Run r) {
        if (r.result == null)
            r.problem = "The fire model did not run.";
        else if (r.result.rosFtPerMin < 0.05)
            r.problem = String.format(Locale.US, "Will not carry fire here as it stands: fuel moisture %.0f%% in %s is past "
                    + "what this fuel burns at. Try Conditions: Dry day to see what a dry day does.", r.m1,
                    r.fuelName == null || r.fuelName.isEmpty() ? "this fuel" : r.fuelName.split(" ")[0]);
    }

    /** Frames a run's hour ring on the map, in the half of the screen the page leaves open. */
    public void goTo(Run r) {
        if (r == null || r.result == null)
            return;
        try {
            final double reachM = Math.max(60, r.headM(STEPS_MIN[STEPS_MIN.length - 1]) * 1.3d);
            final double dLat = reachM / 111320d;
            final double dLon = reachM / (111320d * Math.max(0.02d, Math.cos(Math.toRadians(r.lat))));
            final GeoPoint[] corners = {
                    new GeoPoint(r.lat - dLat, r.lon - dLon), new GeoPoint(r.lat + dLat, r.lon + dLon) };
            // The same fit the detection page's Go to uses; nothing cleverer, the half
            // screen arithmetic sent the map somewhere else entirely (dev 1, 2026-10-11).
            com.atakmap.android.util.ATAKUtilities.scaleToFit(mapView, corners, 0d,
                    mapView.getWidth(), mapView.getHeight());
        } catch (Exception e) {
            Log.w(TAG, "go to run failed", e);
        }
    }

    /** The run's map switch: hides or shows its set, never deletes it. */
    public void setVisible(final Run r, final boolean on) {
        r.visible = on;
        worker.execute(new Runnable() {
            @Override
            public void run() {
                synchronized (lock) {
                    showLocked(r, on);
                }
                save();
                changed();
            }
        });
    }

    /** Lock held. By ids, not setFeatureSetVisible, which rewrites every set in the store. */
    private void showLocked(Run r, boolean on) {
        if (store == null || r == null || r.fsid <= 0)
            return;
        try {
            final FeatureDataStore2.FeatureSetQueryParameters p = new FeatureDataStore2.FeatureSetQueryParameters();
            p.ids = java.util.Collections.singleton(r.fsid);
            store.setFeatureSetsVisible(p, on);
        } catch (Exception e) {
            Log.w(TAG, "model set visibility", e);
        }
    }

    /** Takes one run off the map and forgets it: the operator's own Clear. */
    public void clear(final Run r) {
        runs.remove(r);
        if (last == r)
            last = runs.isEmpty() ? null : runs.get(0);
        // The start belongs to the next run; with no run left there is nothing to
        // rerun, so it goes too (operator, 2026-10-12).
        if (runs.isEmpty()) {
            removeMarker();
            prefs.edit().remove(PREF_LAT).remove(PREF_LON).apply();
        }
        worker.execute(new Runnable() {
            @Override
            public void run() {
                synchronized (lock) {
                    dropLocked(r);
                }
                save();
                changed();
            }
        });
    }

    /** Takes every run and the start marker off the map and forgets them all. */
    public void clearAll() {
        runs.clear();
        last = null;
        status = "";
        removeMarker();
        prefs.edit().remove(PREF_LAT).remove(PREF_LON).apply();
        worker.execute(new Runnable() {
            @Override
            public void run() {
                synchronized (lock) {
                    dropAllLocked();
                }
                if (saveFile.isFile() && !saveFile.delete())
                    Log.w(TAG, "could not delete " + saveFile);
                changed();
            }
        });
    }

    // ---- the map ------------------------------------------------------------------

    private void attachLocked() throws Exception {
        store = new FeatureSetDatabase2(storeFile);
        final FeatureDataStore2.FeatureQueryParameters visibleOnly = new FeatureDataStore2.FeatureQueryParameters();
        visibleOnly.visibleOnly = true;
        layer = new FeatureLayer3("Fire Cast fire model", store, visibleOnly);
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
                "Fire Cast fire model", "file://asset/nothing", query, null, null);
        mapView.getMapOverlayManager().addFilesOverlay(overlay);
        mapView.addLayer(MapView.RenderStack.VECTOR_OVERLAYS, layer);
    }

    private void detachLocked() {
        try {
            if (layer != null)
                mapView.removeLayer(MapView.RenderStack.VECTOR_OVERLAYS, layer);
            if (overlay != null)
                mapView.getMapOverlayManager().removeOverlay(overlay);
        } catch (Exception e) {
            Log.w(TAG, "model detach failed", e);
        }
        layer = null;
        overlay = null;
        store = null;
    }

    /** Draws this run's ellipses as a feature set of their own, beside the other runs. Lock held. */
    private void drawLocked(Run r) {
        if (store == null || r == null || r.result == null || r.problem != null)
            return;
        boolean bulk = false;
        try {
            store.acquireModifyLock(true);
            bulk = true;
            {
                final long fsid = store.insertFeatureSet(new FeatureSet("Fire Cast", "Fire model",
                        r.label(), Double.MAX_VALUE, 0d));
                store.setFeatureSetVisible(fsid, r.visible);
                r.fsid = fsid;
                // Largest first so the 20 minute ring draws on top of the 60.
                final int[] colors = { 0xFFD600, 0xFF8A00, 0xFF2A00 };
                for (int i = STEPS_MIN.length - 1; i >= 0; i--) {
                    final Style style = new CompositeStyle(new Style[] {
                            new BasicFillStyle((0x30 << 24) | colors[i]),
                            new BasicStrokeStyle(0xFF000000 | colors[i], 3f) });
                    final List<Polygon> shapes = new ArrayList<>();
                    if (r.ground != null)
                        shapes.addAll(r.ground.rings(STEPS_MIN[i]));
                    else
                        shapes.add(ellipse(r, STEPS_MIN[i]));
                    for (Polygon p : shapes) {
                        final AttributeSet a = new AttributeSet();
                        a.setAttribute("_title", STEPS_MIN[i] + " min");
                        store.insertFeature(new Feature(fsid, STEPS_MIN[i] + " min", p, style, a,
                                Feature.AltitudeMode.ClampToGround, 0d));
                    }
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "model draw failed", e);
        } finally {
            if (bulk)
                store.releaseModifyLock();
        }
    }

    /** Takes one run's set out of the store. Lock held. */
    private void dropLocked(Run r) {
        if (store == null || r == null || r.fsid <= 0)
            return;
        boolean bulk = false;
        try {
            store.acquireModifyLock(true);
            bulk = true;
            store.deleteFeatureSet(r.fsid);
            Log.d(TAG, "model run dropped: set " + r.fsid);
        } catch (Exception e) {
            Log.w(TAG, "model drop failed", e);
        } finally {
            if (bulk)
                store.releaseModifyLock();
        }
        r.fsid = 0;
    }

    /** Empties the store. Lock held. */
    private void dropAllLocked() {
        if (store == null)
            return;
        boolean bulk = false;
        try {
            store.acquireModifyLock(true);
            bulk = true;
            final List<Long> old = new ArrayList<>();
            final FeatureSetCursor c = store.queryFeatureSets(new FeatureDataStore2.FeatureSetQueryParameters());
            try {
                while (c.moveToNext())
                    old.add(c.getId());
            } finally {
                c.close();
            }
            for (Long id : old)
                store.deleteFeatureSet(id);
            Log.d(TAG, "model store emptied: " + old.size() + " sets");
        } catch (Exception e) {
            Log.w(TAG, "model drop all failed", e);
        } finally {
            if (bulk)
                store.releaseModifyLock();
        }
    }

    /**
     * The fire ellipse at a time: semi-major b along the heading, semi-minor a across,
     * center c ahead of the ignition, all from the library per minute of spread.
     */
    static Polygon ellipse(Run r, int minutes) {
        final double a = r.result.aFtPerMin * minutes * FT_TO_M;
        final double b = r.result.bFtPerMin * minutes * FT_TO_M;
        final double c = r.result.cFtPerMin * minutes * FT_TO_M;
        final double th = Math.toRadians(r.result.headingDeg);
        final double mPerDegLat = 111320d;
        final double mPerDegLon = 111320d * Math.max(0.02d, Math.cos(Math.toRadians(r.lat)));
        // Center of the ellipse: c along the heading from the ignition.
        final double cx = r.lon + c * Math.sin(th) / mPerDegLon;
        final double cy = r.lat + c * Math.cos(th) / mPerDegLat;
        final LineString ring = new LineString(2);
        final int n = 72;
        double x0 = 0, y0 = 0;
        for (int i = 0; i < n; i++) {
            final double phi = 2 * Math.PI * i / n;
            final double along = b * Math.cos(phi), across = a * Math.sin(phi);
            final double east = along * Math.sin(th) + across * Math.cos(th);
            final double north = along * Math.cos(th) - across * Math.sin(th);
            final double x = cx + east / mPerDegLon, y = cy + north / mPerDegLat;
            if (i == 0) {
                x0 = x;
                y0 = y;
            }
            ring.addPoint(x, y);
        }
        ring.addPoint(x0, y0);
        final Polygon p = new Polygon(2);
        p.addRing(ring);
        return p;
    }

    private static Polygon circle(double lat, double lon, double radiusM) {
        final double mPerDegLat = 111320d;
        final double mPerDegLon = 111320d * Math.max(0.02d, Math.cos(Math.toRadians(lat)));
        final LineString ring = new LineString(2);
        for (int i = 0; i <= 24; i++) {
            final double phi = 2 * Math.PI * (i % 24) / 24;
            ring.addPoint(lon + radiusM * Math.sin(phi) / mPerDegLon, lat + radiusM * Math.cos(phi) / mPerDegLat);
        }
        final Polygon p = new Polygon(2);
        p.addRing(ring);
        return p;
    }

    static String stamp(long t) {
        return new java.text.SimpleDateFormat("HH:mm", Locale.US).format(new java.util.Date(t));
    }

    // ---- the last run survives a restart ------------------------------------------

    private void save() {
        try {
            final org.json.JSONArray all = new org.json.JSONArray();
            for (Run r : runs)
                if (r.result != null && r.problem == null)
                    all.put(toJson(r));
            final JSONObject out = new JSONObject().put("runs", all);
            final File tmp = new File(root, "model.json.tmp");
            final java.io.Writer w = new java.io.OutputStreamWriter(
                    new java.io.FileOutputStream(tmp), java.nio.charset.StandardCharsets.UTF_8);
            try {
                w.write(out.toString());
            } finally {
                w.close();
            }
            if (!tmp.renameTo(saveFile)) {
                saveFile.delete();
                tmp.renameTo(saveFile);
            }
        } catch (Exception e) {
            Log.w(TAG, "could not save the model runs", e);
        }
    }

    private static void putNumber(JSONObject o, String key, double v) throws Exception {
        if (!Double.isNaN(v) && !Double.isInfinite(v))
            o.put(key, v);
    }

    private static JSONObject toJson(Run r) throws Exception {
        {
            final JSONObject o = new JSONObject();
            o.put("lat", r.lat).put("lon", r.lon).put("ranAt", r.ranAt);
            o.put("fuelModel", r.site.fuelModel).put("slopeDeg", r.site.slopeDeg)
                    .put("aspectDeg", r.site.aspectDeg).put("coverPct", r.site.coverPct);
            o.put("m1", r.m1).put("m10", r.m10).put("m100", r.m100).put("mHerb", r.mHerb).put("mWoody", r.mWoody);
            o.put("windMph", r.windMph).put("windFromDeg", r.windFromDeg).put("windNote", r.windNote)
                    .put("tenMeterWind", r.tenMeterWind).put("moistureNote", r.moistureNote);
            o.put("canopyHeightFt", r.canopyHeightFt).put("crownRatio", r.crownRatio).put("conditions", r.conditions)
                    .put("visible", r.visible).put("ground", r.ground != null).put("halfKm", r.halfKm)
                    .put("startedAt", r.startedAt).put("rateScale", r.rateScale);
            if (r.calibrationNote != null)
                o.put("calibrationNote", r.calibrationNote);

            if (r.perimeter != null) {
                final org.json.JSONArray pa = new org.json.JSONArray();
                for (double[] p : r.perimeter)
                    pa.put(new org.json.JSONArray().put(p[0]).put(p[1]));
                o.put("perimeter", pa);
            }
            if (r.structures != null) {
                final org.json.JSONArray st = new org.json.JSONArray(), hm = new org.json.JSONArray(), pp = new org.json.JSONArray();
                for (int i = 0; i < STEPS_MIN.length; i++) {
                    st.put(r.structures[i]);
                    hm.put(r.homes[i]);
                    pp.put(r.people[i]);
                }
                o.put("structures", st).put("homes", hm).put("people", pp);
            }
            if (r.structureNote != null)
                o.put("structureNote", r.structureNote);
            if (r.weather != null) {
                // Android's org.json refuses NaN; a station without a reading has them.
                o.put("station", r.weather.station).put("observedAt", r.weather.observedAt);
                putNumber(o, "rh", r.weather.relativeHumidity);
                putNumber(o, "tempF", r.weather.airTempF);
                putNumber(o, "gustMph", r.weather.gustMph);
                putNumber(o, "stationM", r.weather.distanceM);
            }
            return o;
        }
    }

    /** Reruns the saved inputs through the library; the numbers come out the same. Worker. */
    private void restore() {
        if (!saveFile.isFile())
            return;
        try {
            final StringBuilder sb = new StringBuilder();
            final java.io.Reader rd = new java.io.InputStreamReader(
                    new java.io.FileInputStream(saveFile), java.nio.charset.StandardCharsets.UTF_8);
            try {
                final char[] buf = new char[4096];
                int n;
                while ((n = rd.read(buf)) > 0)
                    sb.append(buf, 0, n);
            } finally {
                rd.close();
            }
            final JSONObject file = new JSONObject(sb.toString());
            org.json.JSONArray arr = file.optJSONArray("runs");
            if (arr == null)
                arr = new org.json.JSONArray().put(file);   // the single-run file from before
            final List<Run> read = new ArrayList<>();
            for (int i = 0; i < arr.length(); i++)
                read.add(fromJson(arr.getJSONObject(i)));
            if (!runs.isEmpty())
                return;   // a run finished first
            for (Run r : read) {
                if (r == null)
                    continue;
                synchronized (lock) {
                    drawLocked(r);
                }
                runs.add(r);
            }
            if (!runs.isEmpty()) {
                last = runs.get(0);
                status = "Model on the map, " + stamp(last.ranAt) + " run";
            }
            changed();
        } catch (Exception e) {
            Log.w(TAG, "could not read the saved model runs", e);
        }
    }

    /** One saved run back through the library; null when it does not come out. */
    private Run fromJson(JSONObject o) {
        try {
            final Run r = new Run();
            r.lat = o.getDouble("lat");
            r.lon = o.getDouble("lon");
            r.ranAt = o.optLong("ranAt", 0L);
            r.site = new Landfire.Site(o.getInt("fuelModel"), o.optDouble("slopeDeg", 0),
                    o.optDouble("aspectDeg", 0), o.optDouble("coverPct", 0));
            r.m1 = o.optDouble("m1", 4);
            r.m10 = o.optDouble("m10", 5);
            r.m100 = o.optDouble("m100", 7);
            r.mHerb = o.optDouble("mHerb", 30);
            r.mWoody = o.optDouble("mWoody", 60);
            r.windMph = o.optDouble("windMph", 0);
            r.windFromDeg = o.optDouble("windFromDeg", 0);
            r.windNote = o.optString("windNote", "");
            r.tenMeterWind = o.optBoolean("tenMeterWind", false);
            r.moistureNote = o.optString("moistureNote", "");
            r.canopyHeightFt = o.optDouble("canopyHeightFt", 0);
            r.crownRatio = o.optDouble("crownRatio", 0);
            r.conditions = o.optString("conditions", COND_OBSERVED);
            r.visible = o.optBoolean("visible", true);
            r.halfKm = o.optDouble("halfKm", HALF_KM);
            r.startedAt = o.optLong("startedAt", 0);
            r.rateScale = o.optDouble("rateScale", 1);
            r.calibrationNote = o.optString("calibrationNote", null);

            final org.json.JSONArray pa = o.optJSONArray("perimeter");
            if (pa != null && pa.length() >= 3) {
                r.perimeter = new double[pa.length()][];
                for (int i = 0; i < pa.length(); i++)
                    r.perimeter[i] = new double[] { pa.getJSONArray(i).getDouble(0), pa.getJSONArray(i).getDouble(1) };
            }
            r.fuelName = Behave.name(r.site.fuelModel);
            r.result = Behave.surface(r.site.fuelModel, r.m1, r.m10, r.m100, r.mHerb, r.mWoody,
                    r.windMph, r.tenMeterWind, r.windFromDeg, r.site.slopeDeg, r.site.aspectDeg,
                    r.site.coverPct, r.canopyHeightFt, r.crownRatio);
            finish(r);
            if (r.problem != null)
                return null;
            if (o.optBoolean("ground", false)) {
                // The grids are on disk, so the ground run needs no network; the
                // building counts are kept from the run, not fetched again.
                final org.json.JSONArray st = o.optJSONArray("structures"), hm = o.optJSONArray("homes"), pp = o.optJSONArray("people");
                acrossTheGroundNoStructures(r);
                if (st != null && hm != null && pp != null && st.length() == STEPS_MIN.length) {
                    r.structures = new int[STEPS_MIN.length];
                    r.homes = new int[STEPS_MIN.length];
                    r.people = new double[STEPS_MIN.length];
                    for (int i = 0; i < STEPS_MIN.length; i++) {
                        r.structures[i] = st.optInt(i);
                        r.homes[i] = hm.optInt(i);
                        r.people[i] = pp.optDouble(i, 0);
                    }
                }
                r.structureNote = o.optString("structureNote", null);
            }
            return r;
        } catch (Exception e) {
            Log.w(TAG, "could not read a saved model run", e);
            return null;
        }
    }

    private void changed() {
        main.post(new Runnable() {
            @Override
            public void run() {
                if (listener != null)
                    listener.onModelChanged();
            }
        });
    }
}
