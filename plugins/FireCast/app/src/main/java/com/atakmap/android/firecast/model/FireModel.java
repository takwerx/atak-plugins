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
    /** The 3 hour card: half-hour steps, the way the Cal OES product reads. */
    public static final int[] STEPS_3H = { 30, 60, 90, 120, 150, 180 };
    private static final String PREF_HORIZON = "model.horizonMin";

    /** Minutes the next run projects: 60 or 180. */
    public int horizonMinutes() {
        return prefs.getInt(PREF_HORIZON, 60);
    }

    public void setHorizonMinutes(int m) {
        prefs.edit().putInt(PREF_HORIZON, m).apply();
    }

    /**
     * The steps of a run from an ignition: every 20, 30 or 60 minutes out to {@code total},
     * with the minute the run was made among them so one ring is "now".
     */
    static int[] stepsTo(int total, int now) {
        final int every = total <= 75 ? 20 : total <= 300 ? 30 : 60;
        final java.util.TreeSet<Integer> set = new java.util.TreeSet<>();
        for (int m = every; m < total + every; m += every)
            set.add(Math.min(m, total));
        if (now > 0)
            set.add(now);
        // Steps closer than 10 minutes to "now" give way to it.
        final java.util.Iterator<Integer> it = set.iterator();
        while (it.hasNext()) {
            final int m = it.next();
            if (m != now && now > 0 && Math.abs(m - now) < 10)
                it.remove();
        }
        final int[] out = new int[set.size()];
        int i = 0;
        for (int m : set)
            out[i++] = m;
        return out;
    }

    /** The color of a step's ring: red for the first, through orange to yellow for the last. */
    static int stepColor(int i, int n) {
        final double f = n <= 1 ? 0 : i / (double) (n - 1);
        final int r = 0xFF, g = (int) Math.round(0x2A + (0xD6 - 0x2A) * f), b = 0x00;
        return (r << 16) | (g << 8) | b;
    }
    /** The ground fetched around a start, each way. 4 km holds an hour of anything short of a wind-driven grass run. */
    static final double HALF_KM = 4;
    private static final double FT_TO_M = 0.3048d;

    public interface Listener {
        /** Main thread. */
        void onModelChanged();
    }

    /** One hour of a run's weather: the wind at the start and the dead fuel moisture, percent. */
    public static final class Hour {
        public final long at;
        public final double windMph, windFromDeg, m1, m10, m100;

        Hour(long at, double windMph, double windFromDeg, double m1, double m10, double m100) {
            this.at = at;
            this.windMph = windMph;
            this.windFromDeg = windFromDeg;
            this.m1 = m1;
            this.m10 = m10;
            this.m100 = m100;
        }
    }

    /** Everything about one run: where, what went in, what came out. */
    public static final class Run {
        /** Made at run time; names the run's file and makes a resend replace, not duplicate. */
        public String runId = java.util.UUID.randomUUID().toString();
        /** Who the file says ran it, for a run that came from another phone; null for one run here. */
        public String by;
        /** The device the file says it came from; a resend replaces only a run with the same one. */
        public String byUid;
        public long receivedAt;
        /** The file ATAK handed over (inside its data package): deleting the package deletes the run. */
        public String sourcePath;
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
        /** Conifer needles, percent, for the crown fire question: measured nearby, or 100. */
        public double mFoliar = 100;
        public String foliarNote;
        /** The canopy over the start from the grids: cover %, height ft, base ft, density kg/m3; null without them. */
        public double[] startCanopy;
        /** The fire danger station's hours around the run, or null; not saved, the moistures above are. */
        public FuelMoisture.Dead dead;
        /** The nearest measured brush, grass and needles; not saved. */
        public FuelMoisture.Live live;
        /** {10 h, 100 h} from the 10 km grid when no station answered, else null. */
        public double[] moistureGrid;
        public double windMph, windFromDeg;
        public boolean tenMeterWind;
        /** The wind was read at eye level (the operator's observation): behave takes it as the midflame wind. */
        public boolean eyeLevelWind;
        /** The operator's observation this run used, in words; null when it used a station. */
        public String obsNote;

        /** Where the run's own wind was read, as the fire library takes it. */
        public int windHeight() {
            return eyeLevelWind ? Behave.AT_EYE_LEVEL : tenMeterWind ? Behave.AT_10M : Behave.AT_20FT;
        }
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
        /** The steps this run was projected at, minutes. */
        public int[] steps = STEPS_MIN;
        /** The cell the ground run used, meters: 15 for an hour, 30 for three. */
        public double cellM = 15;
        /** The run across the ground, or null when it was the one-point ellipse. */
        public Spread.Result ground;
        /** Why it was the ellipse, when it was. */
        public String groundNote;
        /** The perimeter the run began from, [lat, lon] pairs, or null for a point start. */
        public double[][] perimeter;
        /** Every part of it, the main one first, when NASA mapped the fire as several pieces; null for one. */
        public double[][][] perimeterParts;

        /** What the run lights at time 0: every part. */
        double[][][] seeds() {
            return perimeterParts != null ? perimeterParts : perimeter == null ? null : new double[][][] { perimeter };
        }
        /** Where the perimeter came from when it was not drawn: the satellite outline and its age. */
        public String perimeterNote;
        /** "nasa", "shape" (a shape already on the map) or null (drawn here). */
        public String perimeterKind;
        /** When the fire started, epoch ms, or 0 for unknown; with a perimeter it calibrates the run. */
        public long startedAt;
        /**
         * Where the run's clock starts, epoch ms: the ignition the operator set, for a
         * point start, so the rings are where the fire should be at each time since,
         * one of them now; 0 for a run that starts at {@link #ranAt}.
         */
        public long originAt;

        /** The moment the run's minutes count from: the ignition when set, else when it ran. */
        public long t0() {
            return originAt > 0 ? originAt : ranAt;
        }

        /** Minutes from the run's start to when it ran: the fire's age at the run, 0 without an ignition. */
        public int nowMinutes() {
            return originAt > 0 ? (int) Math.round((ranAt - originAt) / 60000d) : 0;
        }
        /** True when the pixel under the marker does not burn; the ground run starts from the nearest fuel. */
        public boolean startNotBurnable;
        /** The rate multiplier calibration settled on; 1 when there was none. */
        public double rateScale = 1;
        public String calibrationNote;
        /** The HRRR field the run used, or null for one wind. */
        public WindField windField;
        public String windFieldNote;
        /** Whether the run's wind came from that field, cell by cell. */
        public boolean fieldWind;
        /** The NWS forecast for the start at each hour of the run; not saved, the hours below are. */
        public Weather.Reading[] forecast;
        /** The run's weather hour by hour; the first is what went in. Null for runs saved before it existed. */
        public Hour[] hourly;
        /** Where the later hours came from, in words. */
        public String hourlyNote;

        /** The grid's reach each way from the start, km. */
        public double halfKm = HALF_KM;
        /**
         * Predicted Fire Size, PyreCast's words for it: acres at the end of the run for the
         * 10th, 30th, 50th, 70th and 90th percentile of the same fire run many times with
         * the wind and the dead fuel moisture nudged; null when it was not run.
         */
        public double[] sizeAcres;
        /** How many runs the sizes come from. */
        public int sizeRuns;
        /** The nudges behind each size, in {@link #SIZE_PCT} order: {wind x, direction + deg, moisture x}. */
        public double[][] sizeNudges;
        /** The size shown: one of {@link #SIZE_PCT}, Median unless the operator picks another. */
        public int sizePct = 50;
        /** Buildings reached by each step, homes among them, and people, from FEMA's inventory; null when not fetched. */
        public int[] structures, homes;
        public double[] people;
        public String structureNote;
        /** Schools, hospitals, care homes, stations and the like the fire reaches: [kind, lat, lon, step index]. */
        public java.util.List<Object[]> critical = new java.util.ArrayList<>();
        /** On the map, or hidden by its switch. Hidden is not deleted. */
        public boolean visible = true;
        /** Each building the last step reaches: minutes until the fire does, a home or not, people; null when not counted. */
        public float[] bArrival, bPeople;
        public boolean[] bHome;
        /** The time the operator slid to, minutes after the run; 0 for none. */
        public int atMinutes;
        /** The features drawing that time's edge, in this run's own set. */
        final List<Long> atFids = new ArrayList<>();

        /** {buildings, homes, people} the fire reaches by a time; null when buildings were not counted. */
        public double[] reachedBy(double minutes) {
            if (bArrival == null || bHome == null || bPeople == null)
                return null;
            int n = 0, h = 0;
            double p = 0;
            for (int i = 0; i < bArrival.length; i++)
                if (bArrival[i] <= minutes) {
                    n++;
                    if (bHome[i])
                        h++;
                    p += bPeople[i];
                }
            return new double[] { n, h, p };
        }

        /** "20:43 run, SH2, 10 mph from SE": how a run is named in a list; "Jones · 20:43 run, ..." for one sent here. */
        public String label() {
            final StringBuilder sb = new StringBuilder();
            if (by != null)
                sb.append(by).append(" \u00B7 ");
            sb.append(stamp(ranAt)).append(" run");
            if (fuelName != null && !fuelName.isEmpty())
                sb.append(", ").append(fuelName.split(" ")[0]);
            if (result != null)
                sb.append(String.format(Locale.US, ", %.0f mph from %s, %s in 1 h", windMph, Units.compass(windFromDeg),
                        acres(60) < 10 ? String.format(Locale.US, "%.1f ac", acres(60)) : String.format(Locale.US, "%,.0f ac", acres(60))));
            if (perimeter != null)
                sb.append(", from a perimeter");
            if (structures != null)
                sb.append(", ").append(structures[steps.length - 1]).append(" bldg");
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
    /** The operator's own weather, when set; the run uses it while it is under six hours old. */
    public Observation observation() {
        return Observation.load(prefs);
    }

    public void setObservation(Observation ob) {
        Observation.save(prefs, ob);
        changed();
    }

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
    private static final String GROUP = "FireCast";
    private static final String MARKER_UID = "firecast.model.start";
    private MapGroup group;
    private Marker marker;
    private Runnable onPlaced;
    private PerimeterPicker perimeterPicker;
    /** The drawn perimeter waiting for a run, or null. */
    private volatile double[][] perimeter;
    /** Every part of a NASA perimeter, the main one first; null for one part or a drawn one. */
    private volatile double[][][] perimeterParts;
    private static final String PREF_PERIMETER_PARTS = "model.perimeterParts";
    private static final String PREF_PERIMETER = "model.perimeter", PREF_STARTED = "model.startedMin",
            PREF_PERIMETER_NOTE = "model.perimeterNote";
    /** Null for a drawn perimeter; for a satellite one, what it is and when it was drawn. */
    private volatile String perimeterNote;

    public String perimeterNote() {
        return perimeterNote;
    }

    /** "nasa", "shape" or null for a perimeter drawn here. */
    private volatile String perimeterKind;
    private static final String PREF_PERIMETER_KIND = "model.perimeterKind";

    public String perimeterKind() {
        return perimeterKind;
    }

    /** What the page says about the start: where its perimeter came from, or why there is none. */
    private volatile String startNote;

    public String startNote() {
        return startNote;
    }

    public double[][] perimeter() {
        return perimeter;
    }

    /**
     * When the fire ignited, epoch ms, as the operator set it: "5 minutes before
     * dispatch". 0 when not known. A clock time, not minutes ago, so it stays the same
     * moment however long the crew takes to get there (operator, 2026-10-31).
     */
    public long ignitionAt() {
        return prefs.getLong(PREF_IGNITION, 0L);
    }

    public void setIgnitionAt(long at) {
        prefs.edit().putLong(PREF_IGNITION, at > 0 ? at : 0L).apply();
    }

    /** The ignition when it is usable now: in the past and within {@link #IGNITION_MAX_MS}. */
    public long usableIgnition(long now) {
        final long at = ignitionAt();
        return at > 0 && at <= now && now - at <= IGNITION_MAX_MS ? at : 0;
    }

    /** The oldest ignition a run will start from: past this the weather and fuel since are not known. */
    public static final long IGNITION_MAX_MS = 12L * 3600_000L;
    private static final String PREF_IGNITION = "model.ignitionAt";

    /** Hands the operator ATAK's polygon tool; the shape they close becomes the perimeter. */
    public void drawPerimeter() {
        if (perimeterPicker == null)
            drawPerimeterPicker();
        perimeterPicker.start();
    }

    /**
     * A fire picked from NASA's list (FEDS): its outline fetched and made the run's
     * perimeter, drawn on the map like a traced one, the map taken to it, and the page
     * told where it came from. The answer lands in {@link #startNote()}.
     */
    public void satellitePerimeter(final Feds.Fire fire) {
        startNote = "Fetching NASA's satellite perimeter...";
        changed();
        worker.execute(new Runnable() {
            @Override
            public void run() {
                String said = null;
                Feds.Perimeter p = null;
                try {
                    p = Feds.outline(fire);
                    if (Feds.spanM(p.rings) / 2000d + spreadRoomKm(horizonMinutes() >= 180) > MAX_HALF_KM)
                        said = "This fire's satellite perimeter is " + Units.format(Feds.spanM(p.rings))
                                + " across, more than the model can take on the phone ("
                                + Units.format(2 * (MAX_HALF_KM - spreadRoomKm(horizonMinutes() >= 180)) * 1000d)
                                + "). Choose A point and place it on the edge that is moving instead.";
                } catch (Exception e) {
                    Log.w(TAG, "FEDS perimeter: " + e);
                    said = "Could not fetch NASA's satellite perimeter (no network, or the service did not answer).";
                }
                final Feds.Perimeter found = said == null ? p : null;
                final String problem = said;
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        if (found == null) {
                            startNote = problem;
                            changed();
                            return;
                        }
                        usePerimeter(found.rings != null ? found.rings : new double[][][] { found.ring },
                                fedsNote(found, fire), "nasa", true);
                    }
                });
            }
        });
    }

    /**
     * A perimeter that is already somewhere else (NASA's outline, a shape on the map)
     * as the start: every part, the largest the main one, no copy drawn (the outline
     * is already on the map), the marker on it and, when asked, the map fitted to it.
     */
    private void usePerimeter(double[][][] rings, String note, String kind, boolean fit) {
        int main = 0;
        for (int i = 1; i < rings.length; i++)
            if (Spread.ringAcres(rings[i]) > Spread.ringAcres(rings[main]))
                main = i;
        final double[][] big = rings[main];
        final GeoPoint[] pts = new GeoPoint[big.length];
        double lat = 0, lon = 0;
        for (int i = 0; i < pts.length; i++) {
            pts[i] = new GeoPoint(big[i][0], big[i][1]);
            lat += big[i][0];
            lon += big[i][1];
        }
        setPerimeter(pts, note);
        setKind(kind);
        if (rings.length > 1) {
            final double[][][] ordered = rings.clone();
            ordered[main] = rings[0];
            ordered[0] = big;
            setParts(ordered);
        }
        // No copy of the outline: it is on the map already, and a red twin on the same
        // edge read as a second perimeter (operator, 2026-10-30).
        if (perimeterPicker != null)
            perimeterPicker.clearDrawn();
        // The marker goes with the fire, so the map does not show a start somewhere else.
        place(new GeoPoint(lat / pts.length, lon / pts.length));
        if (fit) {
            try {
                double s = 90, w = 180, n = -90, e = -180;
                for (double[][] part : rings)
                    for (double[] q : part) {
                        s = Math.min(s, q[0]);
                        n = Math.max(n, q[0]);
                        w = Math.min(w, q[1]);
                        e = Math.max(e, q[1]);
                    }
                com.atakmap.android.firecast.DetectionFeed.fitVisible(mapView, s, w, n, e);
            } catch (Exception e) {
                Log.w(TAG, "could not go to the perimeter", e);
            }
        }
        changed();
    }

    private void setKind(String kind) {
        perimeterKind = kind;
        if (kind == null)
            prefs.edit().remove(PREF_PERIMETER_KIND).apply();
        else
            prefs.edit().putString(PREF_PERIMETER_KIND, kind).apply();
    }

    /**
     * Waits for a tap on a shape already on the map, a drawn one, one a contact sent,
     * an imported KML or another plugin's polygon (Feature Layer's FIRIS perimeters),
     * and makes it the start. A line is refused. Main thread.
     */
    public void pickShape() {
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
                final MapItem item = event.getItem();
                if (!MapEvent.ITEM_CLICK.equals(event.getType()) || !(item instanceof com.atakmap.android.maps.Shape)) {
                    TextContainer.getInstance().displayPrompt("That is not a shape. Tap the outline of an area, or Back to stop.");
                    return;
                }
                final String said = takeShape((com.atakmap.android.maps.Shape) item);
                cancelPick();
                if (said != null) {
                    startNote = said;
                    changed();
                }
                if (onPlaced != null)
                    onPlaced.run();
            }
        };
        d.addMapEventListener(MapEvent.MAP_CLICK, tapListener);
        d.addMapEventListener(MapEvent.ITEM_CLICK, tapListener);
        mapView.addOnKeyListener(backListener);
        if (onPickStart != null)
            onPickStart.run();
        TextContainer.getInstance().displayPrompt("Tap the shape to start the run from: a perimeter on the map, drawn, sent or from another layer.");
        picking = true;
    }

    /** The shape's rings as the start; null when taken, else why not. */
    private String takeShape(com.atakmap.android.maps.Shape shape) {
        final GeoPoint[] pts;
        try {
            pts = shape.getPoints();
        } catch (Exception e) {
            return "That shape's points could not be read.";
        }
        if (pts == null || pts.length < 3 || pts.length > 100000)
            return "That shape has no area to start from.";
        final List<double[][]> rings = ShapeRings.split(pts);
        final boolean closedType = !(shape instanceof com.atakmap.android.maps.Polyline)
                || (shape.getStyle() & com.atakmap.android.maps.Polyline.STYLE_CLOSED_MASK) != 0
                || shape.getMetaBoolean("collection", false);
        if (rings.isEmpty() || (!closedType && !ShapeRings.closes(pts)))
            return "That is a line, not an area. Tap the outline of an area.";
        final double[][][] all = new double[rings.size()][][];
        for (int i = 0; i < all.length; i++)
            all[i] = Feds.thin(rings.get(i));
        if (Feds.spanM(all) / 2000d + spreadRoomKm(horizonMinutes() >= 180) > MAX_HALF_KM)
            return "That shape is " + Units.format(Feds.spanM(all)) + " across, more than the model can take on the phone. "
                    + "Choose A point and place it on the edge that is moving instead.";
        String title = shape.getTitle();
        if (title == null || title.trim().isEmpty() || title.length() > 80)
            title = "a shape on the map";
        double acres = 0;
        for (double[][] r : all)
            acres += Spread.ringAcres(r);
        usePerimeter(all, "From " + title.trim() + String.format(Locale.US, ", %s, a shape already on the map%s.",
                acresText(acres), all.length > 1 ? " in " + all.length + " pieces" : ""), "shape", false);
        return null;
    }

    /** The widest square a run will take: 40 km across, 60 m cells, about 450,000 of them. */
    static final double MAX_HALF_KM = 20;

    /** Room past a perimeter's edge for the fire to spread into, km. */
    private static double spreadRoomKm(boolean threeHours) {
        return threeHours ? 8 : 3;
    }

    /** The farthest corner of a perimeter from a point, km. */
    static double perimeterReachKm(double[][] ring, double lat, double lon) {
        final double mLon = 111.32d * Math.max(0.02d, Math.cos(Math.toRadians(lat)));
        double far = 0;
        for (double[] p : ring) {
            final double dy = (p[0] - lat) * 111.32d, dx = (p[1] - lon) * mLon;
            far = Math.max(far, Math.sqrt(dx * dx + dy * dy));
        }
        return far;
    }

    private static String fedsNote(Feds.Perimeter p, Feds.Fire fire) {
        final StringBuilder b = new StringBuilder("Satellite perimeter: NASA's Fire Event Data Suite, drawn from the VIIRS heat detections");
        if (p.atMs > 0) {
            final long agoMin = Math.max(0, (System.currentTimeMillis() - p.atMs) / 60000L);
            b.append(" at ").append(new java.text.SimpleDateFormat("MMM d HH:mm", Locale.US).format(new java.util.Date(p.atMs)))
                    .append(" (").append(agoMin < 120 ? agoMin + " min" : (agoMin / 60) + " h").append(" ago)");
        }
        b.append(". Its edges are 375 m steps; it is not the incident's mapped perimeter.");
        return b.toString();
    }

    private void drawPerimeterPicker() {
        perimeterPicker = new PerimeterPicker(mapView, new PerimeterPicker.Callback() {
            @Override
            public void onPerimeter(GeoPoint[] ring) {
                setPerimeter(ring, null);
                if (onPlaced != null)
                    onPlaced.run();
            }

            @Override
            public void onCancelled(String reason) {
                status = reason == null ? "" : reason;
                changed();
            }
        });
    }

    /** The other parts of a NASA perimeter, kept with it so a restart has them too. */
    private void setParts(double[][][] parts) {
        perimeterParts = parts;
        if (parts == null) {
            prefs.edit().remove(PREF_PERIMETER_PARTS).apply();
            return;
        }
        try {
            prefs.edit().putString(PREF_PERIMETER_PARTS, partsJson(parts).toString()).apply();
        } catch (Exception ignored) {
        }
    }

    static org.json.JSONArray partsJson(double[][][] parts) throws org.json.JSONException {
        final org.json.JSONArray out = new org.json.JSONArray();
        for (double[][] part : parts) {
            final org.json.JSONArray a = new org.json.JSONArray();
            for (double[] p : part)
                a.put(new org.json.JSONArray().put(p[0]).put(p[1]));
            out.put(a);
        }
        return out;
    }

    /** Parts back from JSON, every coordinate checked; null when any is not a usable ring. */
    static double[][][] partsFrom(org.json.JSONArray out) {
        if (out == null || out.length() < 1 || out.length() > 200)
            return null;
        try {
            final double[][][] parts = new double[out.length()][][];
            int total = 0;
            for (int k = 0; k < out.length(); k++) {
                final org.json.JSONArray a = out.getJSONArray(k);
                total += a.length();
                if (a.length() < 3 || total > 50000)
                    return null;
                parts[k] = new double[a.length()][];
                for (int i = 0; i < a.length(); i++) {
                    final double la = a.getJSONArray(i).getDouble(0), lo = a.getJSONArray(i).getDouble(1);
                    if (!(Math.abs(la) <= 90) || !(Math.abs(lo) <= 180))
                        return null;
                    parts[k][i] = new double[] { la, lo };
                }
            }
            return parts;
        } catch (Exception e) {
            return null;
        }
    }

    private void setPerimeter(GeoPoint[] ring, String note) {
        if (note == null)
            setKind(null);
        perimeterNote = note;
        perimeterParts = null;
        prefs.edit().remove(PREF_PERIMETER_PARTS).apply();
        startNote = note;
        if (note == null)
            prefs.edit().remove(PREF_PERIMETER_NOTE).apply();
        else
            prefs.edit().putString(PREF_PERIMETER_NOTE, note).apply();
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
        setKind(null);
        perimeterParts = null;
        prefs.edit().remove(PREF_PERIMETER_PARTS).apply();
        perimeterNote = null;
        startNote = null;
        prefs.edit().remove(PREF_PERIMETER).remove(PREF_PERIMETER_NOTE).apply();
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
                if (r.length >= 3) {
                    perimeter = r;
                    perimeterNote = prefs.getString(PREF_PERIMETER_NOTE, null);
                    perimeterKind = prefs.getString(PREF_PERIMETER_KIND, perimeterNote != null ? "nasa" : null);
                    startNote = perimeterNote;
                    final String parts = prefs.getString(PREF_PERIMETER_PARTS, null);
                    perimeterParts = parts == null ? null : partsFrom(new org.json.JSONArray(parts));
                }
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
        // Run files from other phones, by whatever road they reach ATAK.
        try {
            gov.tak.api.commons.graphics.Drawable icon = null;
            try {
                icon = gov.tak.platform.marshal.MarshalManager.marshal(
                        pluginContext.getDrawable(com.atakmap.android.firecast.plugin.R.drawable.ic_launcher),
                        android.graphics.drawable.Drawable.class, gov.tak.api.commons.graphics.Drawable.class);
            } catch (Throwable t) {
                Log.d(TAG, "no icon for the run importer: " + t);
            }
            importer = new RunImportResolver(this, icon);
            com.atakmap.android.importexport.ImportExportMapComponent.getInstance().addImporterClass(importer);
            content = new RunContent(this);
            com.atakmap.android.data.URIContentManager.getInstance().registerResolver(content);
        } catch (Throwable t) {
            Log.w(TAG, "run importer not registered", t);
        }
    }

    private RunImportResolver importer;
    private RunContent content;
    static final String EXTRA_RUN = "runId";

    public void stop() {
        started = false;
        if (importer != null) {
            try {
                com.atakmap.android.importexport.ImportExportMapComponent.getInstance().removeImporterClass(importer);
            } catch (Throwable ignored) {
            }
            importer = null;
        }
        if (content != null) {
            try {
                com.atakmap.android.data.URIContentManager.getInstance().unregisterResolver(content);
            } catch (Throwable ignored) {
            }
            content = null;
        }
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
                r.perimeterNote = r.perimeter == null ? null : perimeterNote;
                r.perimeterKind = r.perimeter == null ? null : perimeterKind;
                r.perimeterParts = r.perimeter == null ? null : perimeterParts;
                final long ign = usableIgnition(r.ranAt);
                // A point start with an ignition runs from the ignition: the rings are
                // where the fire should be at each time since, through now and on by the
                // horizon. A perimeter is the fire as it is now; there the ignition is
                // its age, which calibrates the rates.
                if (ign > 0 && perimeter == null)
                    r.originAt = ign;
                r.startedAt = ign;
                final int total = r.nowMinutes() + horizonMinutes();
                final boolean threeHours = total > 60;
                r.steps = r.originAt > 0 ? stepsTo(total, r.nowMinutes()) : threeHours ? STEPS_3H : STEPS_MIN;
                r.halfKm = threeHours ? 10 : HALF_KM;
                r.cellM = threeHours ? 30 : 15;
                if (total > 180) {
                    // Longer than three hours of spread: more ground, coarser cells.
                    r.halfKm = Math.min(MAX_HALF_KM, 10 + Math.ceil((total - 180) / 60d) * 2);
                    r.cellM = r.halfKm > 10 ? 60 : 30;
                }
                if (r.perimeter != null) {
                    // A perimeter wider than the usual square gets a square that holds it with
                    // room to spread past its edge; past 10 km the cells grow to 60 m so the run
                    // stays the same size on the phone.
                    double reach = 0;
                    for (double[][] part : r.seeds())
                        reach = Math.max(reach, perimeterReachKm(part, lat, lon));
                    final double need = reach + spreadRoomKm(threeHours);
                    if (need > r.halfKm) {
                        r.halfKm = Math.min(MAX_HALF_KM, Math.ceil(need));
                        r.cellM = r.halfKm > 10 ? 60 : 30;
                    }
                }
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
                    final int hoursN = Math.max(1, (r.steps[r.steps.length - 1] + 59) / 60);
                    try {
                        final long[] times = new long[hoursN];
                        for (int h = 0; h < hoursN; h++)
                            times[h] = r.t0() + h * 3_600_000L;
                        r.forecast = Weather.forecastAt(lat, lon, times);
                        if (r.forecast[0] != null)
                            r.stations.add(0, r.forecast[0]);
                    } catch (Exception e) {
                        Log.w(TAG, "NWS forecast grid failed", e);
                    }
                    try {
                        // The 3 km wind field over the run's box, this hour and the next.
                        final double dLat = r.halfKm * 1000d / 111320d;
                        final double dLon = r.halfKm * 1000d / (111320d * Math.max(0.02d, Math.cos(Math.toRadians(lat))));
                        r.windField = WindField.fetch(lat - dLat, lon - dLon, lat + dLat, lon + dLon, r.ranAt, hoursN + 1);
                        final double[] w = r.windField.at(lat, lon, r.ranAt);
                        if (w != null)
                            r.stations.add(0, new Weather.Reading("HRRR", "HRRR", "HRRR 3 km wind field, this hour",
                                    lat, lon, 0, r.ranAt, w[0], Double.NaN, w[1], Double.NaN, Double.NaN));
                    } catch (Exception e) {
                        Log.w(TAG, "HRRR wind field failed: " + e);
                        r.windFieldNote = "No HRRR wind field: " + e.getMessage();
                    }

                    final Observation ob = observation();
                    if (ob != null && ob.usable(r.ranAt)) {
                        // The operator's own reading at the fire comes before any station.
                        r.weather = Weather.Reading.observation(ob, lat, lon,
                                Double.isNaN(r.startElevFt) ? 0 : (int) Math.round(r.startElevFt));
                        r.stations.add(0, r.weather);
                        r.obsNote = ob.summary();
                        r.eyeLevelWind = ob.eyeLevel;
                    }
                    try {
                        final String want = station();
                        for (Weather.Reading s : r.stations)
                            if (r.obsNote == null && !want.isEmpty() && want.equals(s.wxId))
                                r.weather = s;
                        if (r.weather == null) {
                            final java.util.List<Weather.Reading> observed = new java.util.ArrayList<>();
                            for (Weather.Reading s : r.stations)
                                if (!"NWS forecast".equals(s.source) && !"HRRR".equals(s.source) && fresh(s, r.ranAt))
                                    observed.add(s);
                            // No station read in the last 3 hours: the HRRR field or the
                            // forecast, which are always current, rather than a station
                            // that stopped reporting (a portable RAWS a month gone).
                            r.weather = Weather.representative(observed, r.startElevFt);
                            if (r.weather == null && !r.stations.isEmpty())
                                r.weather = r.stations.get(0);
                        }
                    } catch (Exception e) {
                        Log.w(TAG, "RAWS failed", e);
                    }
                    // Fuel moisture: the nearest fire danger station's hours (the RAWS the
                    // weather came from, when it is one), and the nearest measured brush,
                    // grass and needles.
                    try {
                        r.dead = FuelMoisture.dead(lat, lon, r.startElevFt,
                                r.weather != null && "RAWS".equals(r.weather.source) ? r.weather.station : null, r.ranAt);
                    } catch (Exception e) {
                        Log.w(TAG, "fuel moisture stations failed: " + e);
                    }
                    if (r.dead == null)
                        r.moistureGrid = FuelMoisture.grid(lat, lon);
                    r.live = FuelMoisture.live(lat, lon, r.ranAt);
                    Log.d(TAG, "moisture: station " + (r.dead == null ? "none" : r.dead.station.name + " "
                            + Math.round(r.dead.station.distanceM) + " m, " + r.dead.hours.size() + " hours")
                            + (r.moistureGrid != null ? ", grid " + r.moistureGrid[0] + "/" + r.moistureGrid[1] : "")
                            + ", brush " + (r.live.brush == null ? "none" : r.live.brush.value + " " + r.live.brush.site)
                            + ", grass " + (r.live.grass == null ? "none" : r.live.grass.value + " " + r.live.grass.site)
                            + ", needles " + (r.live.needles == null ? "none" : r.live.needles.value + " " + r.live.needles.site));
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
                    // Ten of each kind: what this phone ran, and what it was sent.
                    int local = 0;
                    for (int i = 0; i < runs.size(); i++) {
                        final Run old = runs.get(i);
                        if (old.by != null || ++local <= MAX_RUNS)
                            continue;
                        runs.remove(i--);
                        synchronized (lock) {
                            dropLocked(old);
                        }
                        RunFile.delete(old.runId);
                    }
                    save();
                }
            }
        }
    }

    /** Model inputs from the site and the reading, with the assumptions written down. */
    private void compute(Run r) {
        r.conditions = conditionsForRun;
        // The one pixel under the marker may be a road or a roof while the ground
        // around it burns; the ground run lights the nearest fuel and says how far.
        // Only a run with no ground to search is refused here.
        r.startNotBurnable = !r.site.burnable();
        if (r.startNotBurnable) {
            r.fuelName = r.site.fuelModel < 0 ? "unknown" : Behave.name(r.site.fuelModel);
            final int stand = 102;   // GR2 for the ellipse fallback; the ground run uses the real cells
            r.site = new Landfire.Site(stand, r.site.slopeDeg, r.site.aspectDeg, r.site.coverPct);
            r.fuelName = Behave.name(stand) + " (the pixel under the marker is " + (r.fuelName.isEmpty()
                    ? "code " + r.site.fuelModel : r.fuelName) + ", not burnable; the run lights the nearest fuel)";
        } else {
            r.fuelName = Behave.name(r.site.fuelModel);
        }
        // The weather reading the moisture and the wind are read from.
        Weather.Reading w = r.weather;
        final boolean fieldWind = w != null && "HRRR".equals(w.source) && r.windField != null;
        r.fieldWind = fieldWind;
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
                    if (!"NWS forecast".equals(s.source) && !"HRRR".equals(s.source) && fresh(s, r.ranAt))
                        observed.add(s);
                other = Weather.representative(observed, r.startElevFt);
            }
            if (other != null)
                w = other;
        }
        moisture(r, w);
        final boolean obs = w != null && "Observation".equals(w.source);
        if (obs && !COND_DRY.equals(r.conditions) && !COND_DRY_WINDY.equals(r.conditions)) {
            final Observation ob = observation();
            final double emc = Weather.fineFuelMoisture(w.relativeHumidity, w.airTempF);
            if (ob != null && !Double.isNaN(ob.fineFuelPct)) {
                r.m1 = ob.fineFuelPct;
                r.moistureNote = "The 1 h is the fine fuel moisture you entered, " + Math.round(r.m1) + "%. " + r.moistureNote;
            } else if (!Double.isNaN(emc)) {
                r.m1 = emc;
                r.moistureNote = "The 1 h is worked out from your observation's humidity and temperature. " + r.moistureNote;
            }
        }
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
        // An eye-level reading stays one only while it is the run's wind.
        r.eyeLevelWind = r.eyeLevelWind && obs && !fieldWind && !COND_DRY_WINDY.equals(r.conditions);
        if (obs && !COND_DRY_WINDY.equals(r.conditions))
            r.windNote = (COND_GUST.equals(r.conditions) ? "the gust in " : "") + r.obsNote
                    + (r.eyeLevelWind ? "; an eye-level wind, taken as the wind at the flames" : "");
        hourly(r);
        r.canopyHeightFt = r.site.coverPct >= 10 ? 30 : 0;
        r.crownRatio = r.site.coverPct >= 10 ? 0.5 : 0;
        r.result = Behave.surface(r.site.fuelModel, r.m1, r.m10, r.m100, r.mHerb, r.mWoody,
                r.windMph, r.windHeight(), r.windFromDeg, r.site.slopeDeg, r.site.aspectDeg,
                r.site.coverPct, r.canopyHeightFt, r.crownRatio);
        finish(r);
    }

    /**
     * The five moistures and where each came from, in words. Dead fuel from the fire
     * danger station when one answered; its 1 h only when the station is within 25 km
     * and 1,500 ft of the start, since fine fuel follows the air it sits in within the
     * hour and the run's own weather reading is then the nearer one. Live fuel from the
     * nearest measurement, else the station's seasonal figure, else dry-season defaults.
     */
    private static void moisture(Run r, Weather.Reading w) {
        final StringBuilder note = new StringBuilder();
        final boolean dry = COND_DRY.equals(r.conditions) || COND_DRY_WINDY.equals(r.conditions);
        final FuelMoisture.Hour h = r.dead == null ? null : r.dead.at(r.ranAt);
        final double emc = w == null ? Double.NaN : Weather.fineFuelMoisture(w.relativeHumidity, w.airTempF);
        if (dry) {
            r.m1 = 4;
            r.m10 = 5;
            r.m100 = 7;
            note.append("Dead fuel: dry day preset.");
        } else if (h != null) {
            final FuelMoisture.Station s = r.dead.station;
            final boolean near = s.distanceM <= 25_000 && (Double.isNaN(r.startElevFt) || s.elevFt <= 0
                    || Math.abs(s.elevFt - r.startElevFt) <= 1500);
            r.m10 = h.m10;
            r.m100 = h.m100;
            note.append("Dead fuel is the Forest Service's figure for the ").append(s.name).append(" station, ")
                    .append(Units.format(s.distanceM)).append(" away").append(heightNote(r, s.elevFt))
                    .append(h.observed ? ", from its " + stamp(h.at) + " reading." : ", forecast for " + stamp(h.at) + ".");
            if (near || Double.isNaN(emc)) {
                r.m1 = h.m1;
            } else {
                r.m1 = emc;
                note.append(" The 1 h is worked out from the humidity and temperature at ").append(w.station)
                        .append(", which sits nearer the fire.");
            }
        } else if (r.moistureGrid != null) {
            r.m10 = r.moistureGrid[0];
            r.m100 = r.moistureGrid[1];
            r.m1 = Double.isNaN(emc) ? Math.max(2, r.m10 - 1) : emc;
            note.append("No fire danger station answered; the 10 h and 100 h are today's driest from the Forest "
                    + "Service's grid of ").append(Units.format(11_000)).append(" squares")
                    .append(Double.isNaN(emc) ? ", and the 1 h is a point under the 10 h."
                            : ", and the 1 h is worked out from the humidity and temperature at " + w.station + ".");
        } else if (w != null && !Double.isNaN(w.fuelMoisture) && w.fuelMoisture > 0 && w.fuelMoisture < 40) {
            r.m10 = w.fuelMoisture;
            r.m1 = Math.max(2, w.fuelMoisture - 1);
            r.m100 = w.fuelMoisture + 2;
            note.append("No fire danger station answered; the 10 h is ").append(w.station).append("'s fuel stick.");
        } else if (!Double.isNaN(emc)) {
            r.m1 = emc;
            r.m10 = r.m1 + 1;
            r.m100 = r.m1 + 3;
            note.append("No fire danger station answered; the 1 h is worked out from the humidity and temperature at ")
                    .append(w.station).append(", and the 10 h and 100 h are set just above it.");
        } else {
            r.m1 = 4;
            r.m10 = 5;
            r.m100 = 7;
            note.append("Dead fuel: dry-season defaults; no station reading to go on.");
        }

        final FuelMoisture.Live live = r.live;
        final String reach = Units.format(FuelMoisture.REACH_M);
        if (live != null && live.brush != null) {
            r.mWoody = live.brush.value;
            note.append(String.format(Locale.US, " Live brush %.0f%%: ", r.mWoody)).append(measured(live.brush));
        } else if (h != null && !Double.isNaN(h.woody)) {
            r.mWoody = h.woody;
            note.append(String.format(Locale.US, " Live brush %.0f%%: the seasonal figure at %s; none measured within %s "
                    + "in the last 45 days.", r.mWoody, r.dead.station.name, reach));
        } else {
            r.mWoody = 60;
            note.append(String.format(Locale.US, " Live brush 60%%: dry-season default; none measured within %s in the "
                    + "last 45 days.", reach));
        }
        if (live != null && live.grass != null) {
            r.mHerb = live.grass.value;
            note.append(String.format(Locale.US, " Live grass %.0f%%%s: ", r.mHerb, r.mHerb <= 30 ? " (cured)" : ""))
                    .append(measured(live.grass));
        } else if (h != null && !Double.isNaN(h.herb)) {
            r.mHerb = h.herb;
            note.append(String.format(Locale.US, " Live grass %.0f%%%s: the seasonal figure at %s.", r.mHerb,
                    r.mHerb <= 30 ? " (cured)" : "", r.dead.station.name));
        } else {
            r.mHerb = 30;
            note.append(" Live grass 30% (cured): dry-season default.");
        }
        r.moistureNote = note.toString();
        // Conifer needles decide when a surface fire climbs into the trees.
        if (live != null && live.needles != null) {
            r.mFoliar = Math.max(60, Math.min(300, live.needles.value));
            r.foliarNote = String.format(Locale.US, "Conifer needles %.0f%%: ", r.mFoliar) + measured(live.needles);
        } else {
            r.mFoliar = 100;
            r.foliarNote = "Conifer needles 100%, the usual figure; none measured within " + reach + " in the last 45 days.";
        }
    }

    /**
     * The run's weather hour by hour. The first hour is what went in. Each later hour
     * carries the forecast's change since the first onto it, so a station reading 25 mph
     * where the forecast said 15 stays 10 above the forecast as it changes: the station's
     * own fuel moisture forecast for the dead fuel, the NWS forecast for this spot for the
     * wind (and for the fine fuel when there is no station), the wind field where it is
     * the run's wind. A preset holds through the run.
     */
    private static void hourly(Run r) {
        final int n = Math.max(1, (r.steps[r.steps.length - 1] + 59) / 60);
        r.hourly = new Hour[n];
        r.hourly[0] = new Hour(r.ranAt, r.windMph, r.windFromDeg, r.m1, r.m10, r.m100);
        r.hourlyNote = null;
        if (n == 1)
            return;
        final boolean dry = COND_DRY.equals(r.conditions) || COND_DRY_WINDY.equals(r.conditions);
        final boolean windy = COND_DRY_WINDY.equals(r.conditions) || r.obsNote != null, gust = COND_GUST.equals(r.conditions);
        final FuelMoisture.Hour d0 = r.dead == null ? null : r.dead.at(r.ranAt);
        final Weather.Reading f0 = r.forecast == null ? null : r.forecast[0];
        String windFrom = null, wetFrom = null;
        for (int h = 1; h < n; h++) {
            final long at = r.ranAt + h * 3_600_000L;
            final Weather.Reading fh = r.forecast == null || h >= r.forecast.length ? null : r.forecast[h];
            double mph = r.windMph, from = r.windFromDeg;
            if (r.fieldWind && !windy && r.windField != null) {
                final double[] fw = r.windField.at(r.lat, r.lon, at);
                if (fw != null) {
                    mph = fw[0];
                    from = fw[1];
                    windFrom = "the 3 km wind field";
                }
            } else if (!windy && f0 != null && fh != null && f0.hasWind() && fh.hasWind()) {
                final double s0 = gust && !Double.isNaN(f0.gustMph) ? f0.gustMph : f0.windMph;
                final double sh = gust && !Double.isNaN(fh.gustMph) ? fh.gustMph : fh.windMph;
                // The forecast is a 10 m wind; a RAWS reads at 20 ft, about 0.87 of it.
                final double k = r.tenMeterWind ? 1d : 0.87d;
                mph = Math.max(0, r.windMph + (sh - s0) * k);
                from = (r.windFromDeg + (((fh.windFromDeg - f0.windFromDeg) % 360 + 540) % 360 - 180) + 360) % 360;
                windFrom = "the NWS forecast for this spot";
            }
            double m1 = r.m1, m10 = r.m10, m100 = r.m100;
            if (!dry) {
                final FuelMoisture.Hour dh = r.dead == null ? null : r.dead.at(at);
                if (d0 != null && dh != null) {
                    m1 += dh.m1 - d0.m1;
                    m10 += dh.m10 - d0.m10;
                    m100 += dh.m100 - d0.m100;
                    wetFrom = r.dead.station.name + "'s fuel moisture forecast";
                } else if (f0 != null && fh != null) {
                    final double e0 = Weather.fineFuelMoisture(f0.relativeHumidity, f0.airTempF);
                    final double eh = Weather.fineFuelMoisture(fh.relativeHumidity, fh.airTempF);
                    if (!Double.isNaN(e0) && !Double.isNaN(eh)) {
                        // The 1 h follows the air within the hour; the 10 h a tenth of the way an hour.
                        m1 += eh - e0;
                        m10 += (eh - e0) * (1 - Math.exp(-h / 10d));
                        wetFrom = "the forecast humidity and temperature";
                    }
                }
                m1 = Math.max(1, Math.min(60, m1));
                m10 = Math.max(1, Math.min(60, m10));
                m100 = Math.max(1, Math.min(60, m100));
            }
            r.hourly[h] = new Hour(at, mph, from, m1, m10, m100);
        }
        r.hourlyNote = "Later hours: the wind " + (r.obsNote != null && !COND_DRY_WINDY.equals(r.conditions) ? "held as you observed it"
                : windy ? "held at the preset" : windFrom == null
                ? "held as read (no forecast reached)" : "follows " + windFrom) + "; the dead fuel "
                + (dry ? "held at the preset" : wetFrom == null ? "held as read" : "follows " + wetFrom) + ".";
    }

    /** A sender's callsign as a list can show it: printable, short, never empty. */
    static String callsign(String s) {
        final StringBuilder b = new StringBuilder();
        for (int i = 0; s != null && i < s.length() && b.length() < 40; i++) {
            final char c = s.charAt(i);
            final int type = Character.getType(c);
            // No control, bidi or invisible formatting characters, no line or paragraph separators.
            if (c >= 0x20 && c != 0x7F && type != Character.FORMAT && type != Character.LINE_SEPARATOR
                    && type != Character.PARAGRAPH_SEPARATOR && type != Character.CONTROL)
                b.append(c);
        }
        final String t = b.toString().trim();
        return t.isEmpty() ? "Someone" : t;
    }

    /**
     * A run someone sent: its inputs rerun here with this phone's own engine and
     * ground, its buildings counted again here, drawn and listed with the sender's
     * name. A resend of the same run replaces it. Nothing the sender computed is shown
     * as ours: the rings, acres and counts are this phone's. Any thread.
     */
    void importRun(final JSONObject saved, final File source) {
        // ATAK saves a sent data package on the sending phone too, and imports it there:
        // a run carrying this phone's own id is its own run coming back, and is left out.
        if (ownRun(saved.optString("byUid", ""))) {
            Log.d(TAG, "own run file, not taken in again: " + source.getName());
            return;
        }
        worker.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    final Run r = fromJson(saved);
                    if (r == null) {
                        Log.w(TAG, "received run did not come out: " + source.getName());
                        return;
                    }
                    r.by = callsign(saved.optString("by", ""));
                    r.byUid = deviceUid(saved.optString("byUid", ""));
                    r.receivedAt = System.currentTimeMillis();
                    r.sourcePath = source.getAbsolutePath();
                    // A resend replaces the run it resends: a received run from the same
                    // device. Never a run made here, and never someone else's: a file
                    // can carry any id and any name, so a clash is listed beside it.
                    Run replaced = null;
                    boolean clash = false;
                    for (Run x : runs)
                        if (x.runId.equals(r.runId)) {
                            if (x.by != null && r.byUid != null && r.byUid.equals(x.byUid))
                                replaced = x;
                            else
                                clash = true;
                        }
                    if (clash && replaced == null)
                        r.runId = java.util.UUID.randomUUID().toString();
                    // The file first, while it is still there: ATAK may delete a
                    // package's files once the import returns.
                    keepFile(source, r.runId);
                    r.visible = true;
                    // Recounted here from FEMA's inventory, never the sender's numbers.
                    r.structures = null;
                    r.homes = null;
                    r.people = null;
                    r.bArrival = null;
                    r.bHome = null;
                    r.bPeople = null;
                    r.critical = new ArrayList<>();
                    r.structureNote = null;
                    if (r.ground != null)
                        countStructures(r);
                    if (replaced != null) {
                        runs.remove(replaced);
                        synchronized (lock) {
                            dropLocked(replaced);
                        }
                    }
                    synchronized (lock) {
                        drawLocked(r);
                    }
                    runs.add(0, r);
                    // Ten of each kind: what this phone ran, and what it was sent.
                    int received = 0;
                    for (int i = 0; i < runs.size(); i++) {
                        final Run x = runs.get(i);
                        if (x.by == null || ++received <= MAX_RUNS)
                            continue;
                        runs.remove(i--);
                        synchronized (lock) {
                            dropLocked(x);
                        }
                        RunFile.delete(x.runId);
                    }
                    last = r;
                    status = "Run from " + r.by + " on the map";
                    save();
                    Log.d(TAG, "received run " + r.runId + " from " + r.by);
                    changed();
                    notifyArrived(r);
                } catch (Throwable t) {
                    Log.w(TAG, "received run failed", t);
                }
            }
        });
    }

    /** The run with this id, or null. Any thread. */
    public Run find(String runId) {
        if (runId == null)
            return null;
        try {
            for (Run r : new ArrayList<>(runs))
                if (runId.equals(r.runId))
                    return r;
        } catch (java.util.ConcurrentModificationException e) {
            // The worker changed the list mid-copy; the caller asks again.
        }
        return null;
    }

    /** The received run a data package's file brought, or null. */
    Run receivedFrom(File f) {
        final String path = f.getAbsolutePath();
        try {
            for (Run r : new ArrayList<>(runs))
                if (r.by != null && path.equals(r.sourcePath))
                    return r;
        } catch (java.util.ConcurrentModificationException e) {
            // As in find().
        }
        return null;
    }

    /** Opens a run's page and flies to it, as the arrival notice does. Any thread. */
    boolean open(String runId) {
        if (find(runId) == null)
            return false;
        try {
            com.atakmap.android.ipc.AtakBroadcast.getInstance().sendBroadcast(
                    new android.content.Intent(ModelReceiver.ACTION).putExtra(EXTRA_RUN, runId));
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "could not open run " + runId, t);
            return false;
        }
    }

    /** Its data package was deleted: the run goes too. Any thread. */
    void deleteReceived(final String runId) {
        main.post(new Runnable() {
            @Override
            public void run() {
                if (!started)
                    return;
                final Run r = find(runId);
                if (r != null && r.by != null) {
                    Log.d(TAG, "package deleted, run " + runId + " goes with it");
                    clear(r);
                    clearNotice(runId);
                }
            }
        });
    }

    /** The package's eye: shows or hides a run by id. */
    boolean setVisible(String runId, boolean on) {
        final Run r = find(runId);
        if (r == null)
            return false;
        if (r.visible != on)
            setVisible(r, on);
        return true;
    }

    android.graphics.drawable.Drawable icon() {
        try {
            return pluginContext.getDrawable(com.atakmap.android.firecast.plugin.R.drawable.ic_toolbar);
        } catch (Throwable t) {
            return null;
        }
    }

    private final java.util.Map<String, Integer> notices = new java.util.HashMap<>();

    /**
     * "FireCast run from TAPER" in ATAK's notifications; a tap opens the run and flies
     * to it. A resend of the same run replaces its notice. Any thread.
     */
    private void notifyArrived(final Run r) {
        main.post(new Runnable() {
            @Override
            public void run() {
                try {
                    final com.atakmap.android.util.NotificationUtil n = com.atakmap.android.util.NotificationUtil.getInstance();
                    Integer id = notices.get(r.runId);
                    if (id == null) {
                        id = n.reserveNotifyId();
                        notices.put(r.runId, id);
                    }
                    final android.content.Intent open = new android.content.Intent(ModelReceiver.ACTION)
                            .putExtra(EXTRA_RUN, r.runId);
                    n.postNotification(id, com.atakmap.android.util.NotificationUtil.GeneralIcon.DOWNLOAD_COMPLETE.getID(),
                            "FireCast run from " + r.by, "FireCast run from " + r.by,
                            r.label() + ". Tap to see it.", open, true);
                } catch (Throwable t) {
                    Log.w(TAG, "could not post the arrival notice", t);
                }
            }
        });
    }

    private void clearNotice(String runId) {
        final Integer id = notices.remove(runId);
        if (id != null)
            try {
                com.atakmap.android.util.NotificationUtil.getInstance().clearNotification(id);
            } catch (Throwable ignored) {
            }
    }

    /** Whether a device uid from a run file is this phone's. */
    static boolean ownRun(String byUid) {
        try {
            final String me = MapView.getDeviceUid();
            return me != null && !me.isEmpty() && me.equals(byUid);
        } catch (Throwable t) {
            return false;
        }
    }

    /** A received run keeps its file under its own id, so it can be sent on; the sender's file name is never used. */
    private static void keepFile(File source, String runId) {
        try {
            final File to = RunFile.fileFor(runId);
            if (source.getCanonicalPath().equals(to.getCanonicalPath()))
                return;
            final File tmp = new File(to.getPath() + ".tmp");
            final java.io.InputStream in = new java.io.FileInputStream(source);
            final java.io.OutputStream out = new java.io.FileOutputStream(tmp);
            try {
                final byte[] buf = new byte[16384];
                long total = 0;
                int n;
                while ((n = in.read(buf)) > 0) {
                    total += n;
                    if (total > RunFile.MAX_FILE)
                        throw new IllegalStateException("run file too large");
                    out.write(buf, 0, n);
                }
            } finally {
                in.close();
                out.close();
            }
            if (!tmp.renameTo(to) && !(to.delete() && tmp.renameTo(to)))
                Log.w(TAG, "could not keep the received run's file");
        } catch (Exception e) {
            Log.w(TAG, "could not keep the received run's file: " + e);
        }
    }

    /** A device uid as a key: printable ASCII, 64 at most; null for none. */
    static String deviceUid(String s) {
        return s != null && s.matches("[\\x21-\\x7E]{1,64}") ? s : null;
    }

    /** A number into a range; NaN or a non-number to the default. */
    private static double clamp(double v, double lo, double hi, double dflt) {
        return Double.isNaN(v) ? dflt : Math.max(lo, Math.min(hi, v));
    }

    /** Text from a saved or received run, cut to a length a page can carry. */
    private static String text(String s) {
        return s == null || s.length() <= 2000 ? s : s.substring(0, 2000);
    }

    private static double moist(double m) {
        return Double.isNaN(m) ? 5 : Math.max(1, Math.min(60, m));
    }

    /** "chamise measured at Quigley Canyon, 4.2 mi away, 17 days ago." */
    private static String measured(FuelMoisture.Sample s) {
        final long days = Math.max(0, (System.currentTimeMillis() - s.sampledAt) / 86_400_000L);
        return s.what + " measured at " + s.site + ", " + Units.format(s.distanceM) + " away, "
                + (days == 0 ? "today" : days == 1 ? "yesterday" : days + " days ago")
                + (s.kinds > 1 ? ", the driest of " + s.kinds + " plants sampled there." : ".");
    }

    /** ", 2,100 ft above the start" for a height in feet far from the start's; "" otherwise. */
    static String heightNote(Run r, int elevFt) {
        if (Double.isNaN(r.startElevFt) || elevFt <= 0)
            return "";
        final double d = elevFt - r.startElevFt;
        if (Math.abs(d) < 300)
            return "";
        return String.format(Locale.US, ", %,.0f ft %s the start", Math.abs(d), d > 0 ? "above" : "below");
    }

    static String acresText(double a) {
        return a < 10 ? String.format(Locale.US, "%.1f acres", a) : String.format(Locale.US, "%,.0f acres", a);
    }

    /** ", 2,100 ft above the start" when the station's height is far from the start's; "" otherwise. */
    static String heightNote(Run r, Weather.Reading w) {
        return w == null ? "" : heightNote(r, w.elevationFt);
    }

    /**
     * The run across the real ground: LANDFIRE's grids around the start, the engine
     * asked once per kind of cell with the run's wind and moistures, the fire spread
     * cell by cell. Falls back to the ellipse, and says so, when the ground cannot
     * be had (no network and nothing on disk). Worker thread.
     */
    /**
     * How a cell burns for this run, with the wind's speed times {@code windX}, its
     * direction turned by {@code dirPlus} degrees, and the dead fuel moisture times
     * {@code moistX}; (1, 0, 1) is the run as it stands.
     */
    private static Spread.Behavior behaviorFor(final Run r, final double windX, final double dirPlus, final double moistX) {
        return new Spread.Behavior() {
                    @Override
                    public double[] run(Spread.Ground g, double windMph, double windFrom, int hour) {
                        final Hour hr = r.hourly == null || r.hourly.length == 0 ? null
                                : r.hourly[Math.max(0, Math.min(r.hourly.length - 1, hour))];
                        final boolean own = Double.isNaN(windMph);
                        final double mph = windX * (own ? (hr == null ? r.windMph : hr.windMph) : windMph);
                        final double from = dirPlus + (own ? (hr == null ? r.windFromDeg : hr.windFromDeg) : windFrom);
                        final int height = own ? r.windHeight() : Behave.AT_10M;
                        final double m1 = moistX * (hr == null ? r.m1 : hr.m1), m10 = moistX * (hr == null ? r.m10 : hr.m10),
                                m100 = moistX * (hr == null ? r.m100 : hr.m100);
                        if (g.canopy()) {
                            // Trees over it: whether the fire stays down, torches or crowns.
                            final Behave.Crown c = Behave.crown(g.fuel, m1, m10, m100, r.mHerb, r.mWoody, r.mFoliar,
                                    mph, height, from, g.slopeDeg, g.aspectDeg, g.coverPct, g.heightFt, g.baseFt, g.density);
                            if (c == null)
                                return null;
                            return new double[] { c.rosFtPerMin, c.headingDeg, c.aFtPerMin, c.bFtPerMin, c.cFtPerMin,
                                    c.flameLengthFt, c.spotM, c.fireType };
                        }
                        // Open ground, or no canopy grids: the start's canopy as before.
                        final boolean known = !Double.isNaN(g.coverPct);
                        final double cover = known ? 0 : r.site.coverPct, heightFt = known ? 0 : r.canopyHeightFt,
                                ratio = known ? 0 : r.crownRatio;
                        final Behave.Result b = Behave.surface(g.fuel, m1, m10, m100, r.mHerb, r.mWoody,
                                mph, height, from, g.slopeDeg, g.aspectDeg, cover, heightFt, ratio);
                        if (b == null)
                            return null;
                        // Embers from this kind of cell: Albini's surface-fire reach at the
                        // 20 ft wind (a 10 m wind scaled down to 20 ft), over open ground
                        // or the canopy here.
                        final double wind20 = height == Behave.AT_10M ? mph * 0.87d
                                : height == Behave.AT_EYE_LEVEL ? (b.wind20Mph > 0 ? b.wind20Mph : mph) : mph;
                        final double spotM = Behave.spotDistanceM(b.flameLengthFt, wind20, heightFt);
                        return new double[] { b.rosFtPerMin, b.headingDeg, b.aFtPerMin, b.bFtPerMin, b.cFtPerMin,
                                b.flameLengthFt, spotM, Behave.SURFACE };
                    }
                };
    }

    /** The run's HRRR field with the same nudge to its wind; null for a run on one wind. */
    private static Spread.Wind fieldFor(final Run r, final boolean useField, final double windX, final double dirPlus) {
        if (!useField)
            return null;
        return new Spread.Wind() {
            @Override
            public double[] at(double lat, double lon, long whenMillis) {
                final double[] w = r.windField.at(lat, lon, whenMillis);
                return w == null ? null : new double[] { w[0] * windX, w[1] + dirPlus };
            }
        };
    }

    /** How many times the fire is run with the weather nudged, and the most time that may take. */
    static final int SIZE_RUNS = 30, SIZE_RUNS_MIN = 10;
    static final long SIZE_MS = 45_000L;
    static final int[] SIZE_PCT = { 10, 30, 50, 70, 90 };

    /**
     * Predicted Fire Size: the same fire run again and again, each time with the wind's
     * speed, its direction and the dead fuel moisture nudged the way a forecast misses
     * (speed within about a quarter either way, direction about 15 degrees, moisture
     * about a sixth), then ranked by the acres at the end, the way PyreCast ranks its
     * ensemble. The run as it stands stays the one everything else on the page uses.
     * Seeded from the run's id, so the same run gives the same sizes on every phone.
     */
    private void sizes(Run r, Landscape land, boolean useField) {
        final java.util.Random rnd = new java.util.Random(r.runId.hashCode());
        final int last = r.steps[r.steps.length - 1];
        final int hours = r.hourly == null ? 1 : r.hourly.length;
        final List<double[]> nudges = new ArrayList<>();
        final List<Double> acres = new ArrayList<>();
        final long t0 = System.currentTimeMillis();
        for (int k = 0; k < SIZE_RUNS; k++) {
            if (Thread.currentThread().isInterrupted())
                return;
            if (k >= SIZE_RUNS_MIN && System.currentTimeMillis() - t0 > SIZE_MS)
                break;
            final double[] n = {
                    clamp(Math.exp(0.25 * rnd.nextGaussian()), 0.5, 1.8, 1),
                    clamp(15 * rnd.nextGaussian(), -40, 40, 0),
                    clamp(Math.exp(0.15 * rnd.nextGaussian()), 0.7, 1.4, 1) };
            status = "Running the fire " + (k + 1) + " of " + SIZE_RUNS + " times with the weather nudged…";
            changed();
            final Spread.Result m = Spread.run(land, r.lat, r.lon, r.halfKm, last, behaviorFor(r, n[0], n[1], n[2]),
                    r.seeds(), r.rateScale, fieldFor(r, useField, n[0], n[1]), r.t0(), r.cellM, hours);
            nudges.add(n);
            acres.add(m.acres(last));
        }
        final int count = acres.size();
        final Integer[] order = new Integer[count];
        for (int i = 0; i < count; i++)
            order[i] = i;
        java.util.Arrays.sort(order, new java.util.Comparator<Integer>() {
            @Override
            public int compare(Integer a, Integer b) {
                return Double.compare(acres.get(a), acres.get(b));
            }
        });
        r.sizeAcres = new double[SIZE_PCT.length];
        for (int i = 0; i < SIZE_PCT.length; i++)
            r.sizeAcres[i] = acres.get(order[(int) Math.round(SIZE_PCT[i] / 100d * (count - 1))]);
        r.sizeRuns = count;
        r.sizeNudges = new double[SIZE_PCT.length][];
        for (int i = 0; i < SIZE_PCT.length; i++)
            r.sizeNudges[i] = nudges.get(order[(int) Math.round(SIZE_PCT[i] / 100d * (count - 1))]);
        Log.d(TAG, "sizes: " + count + " runs in " + (System.currentTimeMillis() - t0) + " ms, acres "
                + java.util.Arrays.toString(r.sizeAcres));
        sizeGround(r, land, useField);
    }

    /**
     * The picked size becomes the run on the map: that one run again from its nudge,
     * so the rings, the slider, the acres and the buildings all describe it. Seeded
     * nudges, so it is the same run every time it is drawn.
     */
    private void sizeGround(Run r, Landscape land, boolean useField) {
        if (r.sizeNudges == null)
            return;
        int i = 2;
        for (int k = 0; k < SIZE_PCT.length; k++)
            if (SIZE_PCT[k] == r.sizePct)
                i = k;
        final double[] n = r.sizeNudges[i];
        final Spread.Result m = Spread.run(land, r.lat, r.lon, r.halfKm, r.steps[r.steps.length - 1],
                behaviorFor(r, n[0], n[1], n[2]), r.seeds(), r.rateScale, fieldFor(r, useField, n[0], n[1]),
                r.t0(), r.cellM, r.hourly == null ? 1 : r.hourly.length);
        if (m.cellsSeen > 4)
            r.ground = m;
    }

    /** Shows another Predicted Fire Size for a run: redrawn, buildings counted again, kept. */
    public void setSize(final Run r, final int pct) {
        if (running)
            return;
        worker.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    r.sizePct = pct;
                    final Landscape land = Landscape.around(r.lat, r.lon, r.halfKm);
                    sizeGround(r, land, r.windField != null && r.fieldWind && !COND_DRY_WINDY.equals(r.conditions));
                    countStructures(r);
                    synchronized (lock) {
                        dropLocked(r);
                        drawLocked(r);
                    }
                    save();
                } catch (Throwable t) {
                    Log.w(TAG, "size change failed: " + t);
                }
                changed();
            }
        });
    }

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
            if (r.startNotBurnable) {
                r.problem = "LANDFIRE says the pixel under the marker does not burn, and the ground around it could not be "
                        + "fetched (no network, nothing saved here) to find the nearest fuel. Move the start onto wildland fuel.";
                return;
            }
            r.groundNote = "The ground around the start could not be fetched (no network, nothing saved here), "
                    + "so this is the one-fuel ellipse.";
            return;
        }
        if (land.cc != null && land.ch != null && land.cbh != null && land.cbd != null) {
            final int cc = land.cc.at(r.lat, r.lon), ch = land.ch.at(r.lat, r.lon), cbh = land.cbh.at(r.lat, r.lon),
                    cbd = land.cbd.at(r.lat, r.lon);
            final boolean trees = cc > 0 && cc <= 100 && ch > 0 && ch != land.ch.nodata && cbd > 0 && cbd != land.cbd.nodata
                    && cbh >= 0 && cbh != land.cbh.nodata;
            r.startCanopy = trees ? new double[] { cc, ch / 10d * 3.28084d, cbh / 10d * 3.28084d, cbd / 100d }
                    : new double[] { 0, 0, 0, 0 };
        }
        final long t0 = System.currentTimeMillis();
        final boolean useField = r.windField != null && r.fieldWind && !COND_DRY_WINDY.equals(r.conditions);
        final Spread.Behavior behavior = behaviorFor(r, 1, 0, 1);
        // Calibration: with a perimeter and a start time, the model is run from the
        // perimeter's middle for the time elapsed, and the ratio of what the fire has
        // actually covered to what the model would have gives the rate scale, by the
        // square root since area grows with the square of the rate. Clamped: a fire
        // that has done ten times the model is a wind the station did not see, and
        // the page says so rather than a number nobody should trust.
        r.rateScale = 1;
        if (r.perimeter != null && r.startedAt > 0) {
            final double elapsedMin = (r.ranAt - r.startedAt) / 60000d;
            double observedAcres = 0;
            for (double[][] part : r.seeds())
                observedAcres += Spread.ringAcres(part);
            if (elapsedMin >= 5 && observedAcres > 0.1) {
                final Spread.Result trial = Spread.run(land, r.lat, r.lon, r.halfKm, elapsedMin, behavior, null, 1d, null, r.startedAt, r.cellM);
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
        final Spread.Wind field = fieldFor(r, useField, 1, 0);
        final Spread.Result g = Spread.run(land, r.lat, r.lon, r.halfKm, r.steps[r.steps.length - 1],
                behavior, r.seeds(), r.rateScale, field, r.t0(), r.cellM, r.hourly == null ? 1 : r.hourly.length);
        Log.d(TAG, "ground run: " + g.cellsSeen + " cells reached of " + g.cellsBurnable + " burnable, "
                + g.fuelsCrossed + " fuels, " + (System.currentTimeMillis() - t0) + " ms");
        if (g.cellsSeen < 2) {
            r.groundNote = "The ground run did not take: the start sits on ground that does not burn. This is the one-fuel ellipse.";
            return;
        }
        if (g.cellsSeen <= 4 && g.walled) {
            r.problem = "The fire cannot leave the start: within 120 m the ground is not fuel (commercial, water, "
                    + "bare or farmed in LANDFIRE's 30 m cells) or is too wet or sparse to carry fire in these conditions. "
                    + "Move the start, or try Dry day to see what a drier day would do.";
            return;
        }
        r.ground = g;
        if (skipStructures)
            sizeGround(r, land, useField);
        else
            sizes(r, land, useField);
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
        final int last = r.steps[r.steps.length - 1];
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
        final int[] count = new int[r.steps.length], homes = new int[r.steps.length];
        final double[] people = new double[r.steps.length];
        final java.util.List<Object[]> critical = new java.util.ArrayList<>();
        // Every reached building's own time, for the slider's count at any minute.
        final List<float[]> reached = new ArrayList<>();
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
            if (t <= last)
                reached.add(new float[] { t, b.residential() ? 1 : 0, (float) b.population });
            int firstStep = -1;
            for (int i = 0; i < r.steps.length; i++) {
                if (t <= r.steps[i]) {
                    if (firstStep < 0)
                        firstStep = i;
                    count[i]++;
                    if (b.residential())
                        homes[i]++;
                    people[i] += b.population;
                }
            }
            final String kind = firstStep < 0 ? null : b.critical();
            if (kind != null && critical.size() < 40)
                critical.add(new Object[] { kind, b.lat, b.lon, firstStep });
        }
        java.util.Collections.sort(critical, new java.util.Comparator<Object[]>() {
            @Override
            public int compare(Object[] a, Object[] b) {
                return Integer.compare((Integer) a[3], (Integer) b[3]);
            }
        });
        r.critical = critical;
        r.structures = count;
        r.homes = homes;
        r.people = people;
        r.bArrival = new float[reached.size()];
        r.bHome = new boolean[reached.size()];
        r.bPeople = new float[reached.size()];
        for (int i = 0; i < reached.size(); i++) {
            r.bArrival[i] = reached.get(i)[0];
            r.bHome[i] = reached.get(i)[1] > 0;
            r.bPeople[i] = reached.get(i)[2];
        }
        r.structureNote = buildings.size() >= Structures.MAX
                ? "The inventory stopped at " + Structures.MAX + " buildings, so these counts are a floor."
                : null;
        Log.d(TAG, "structures: " + buildings.size() + " in the box, " + count[r.steps.length - 1] + " reached by the last step");
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
            final double reachM = Math.max(60, r.headM(r.steps[r.steps.length - 1]) * 1.3d);
            final double dLat = reachM / 111320d;
            final double dLon = reachM / (111320d * Math.max(0.02d, Math.cos(Math.toRadians(r.lat))));
            // Into the map the operator can see: the same fit as every Go to in FireCast,
            // the box widened east by the open pane's share (2026-10-30).
            com.atakmap.android.firecast.DetectionFeed.fitVisible(mapView, r.lat - dLat, r.lon - dLon, r.lat + dLat, r.lon + dLon);
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

    /**
     * Sends a run as one file through ATAK's own send dialog: contacts, groups, feeds,
     * other apps, whatever this phone can send to. FireCast never picks the
     * destination. The file is written on the worker, the dialog shown on the main
     * thread. Main thread.
     */
    public void send(final Run r) {
        if (r == null || r.result == null)
            return;
        worker.execute(new Runnable() {
            @Override
            public void run() {
                final File f, named;
                // The package's title is also its file name on every phone, and Android
                // storage refuses a colon in a file name ("10:12" failed with EPERM). A
                // different package already saved under the same title (another run with
                // the same label) is never touched: this one is told apart by its id.
                String t = RunFile.safeName("FireCast - " + r.label());
                final File dpDir = FileSystemUtils.getItem("tools/datapackage");
                final String existing = RunFile.packageUid(new File(dpDir, t + ".zip"));
                if (existing != null && !existing.equals(r.runId))
                    t = RunFile.safeName(t + " " + r.runId.substring(0, 4));
                final String title = t;
                try {
                    final JSONObject saved = toJson(r);
                    // A run passed on keeps the name of whoever ran it.
                    saved.put("by", r.by != null ? r.by : mapView.getDeviceCallsign())
                            .put("engine", "behave d4acc8a").put("pluginVersion", pluginVersion());
                    saved.put("byUid", r.by != null ? (r.byUid == null ? "" : r.byUid) : MapView.getDeviceUid());
                    // Where this phone keeps it and when it got it are nobody else's business.
                    saved.remove("sourcePath");
                    saved.remove("receivedAt");
                    f = RunFile.write(r, saved, card(r), startIcon(), saved.optString("by", ""));
                    named = RunFile.readableCopy(f, title);
                    Log.d(TAG, "run file written: " + f.getName() + ", " + f.length() + " bytes");
                } catch (Exception e) {
                    Log.w(TAG, "run file failed", e);
                    status = "Could not write the run's file: " + e.getMessage();
                    changed();
                    return;
                }
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            // A data package, not a bare file: it keeps its title and stays
                            // in Data Packages on every phone it reaches, its run imported
                            // (a KML overlay, or a run where FireCast is installed). Its id is
                            // the run's, so a resend updates the package instead of adding one.
                            final com.atakmap.android.missionpackage.file.MissionPackageManifest m =
                                    new com.atakmap.android.missionpackage.file.MissionPackageManifest(title, r.runId,
                                            FileSystemUtils.getItem("tools/datapackage").getPath());
                            m.addFile(named, null);
                            m.setRemarks(card(r));
                            m.getConfiguration().setImportInstructions(true, false, null);
                            // ATAK sends a package already saved at this path as it is ("Send
                            // Only"), and the receiver skips one it holds by checksum: the last
                            // send's copy goes, so this one is built from the run as it is now.
                            final File old = new File(m.getPath());
                            if (old.isFile() && r.runId.equals(RunFile.packageUid(old)) && !old.delete())
                                Log.w(TAG, "could not replace the last sent package");
                            Log.d(TAG, "package at " + m.getPath() + (old.isFile() ? " (still there)" : ""));
                            new com.atakmap.android.importexport.send.SendDialog.Builder(mapView)
                                    .setName(title)
                                    .setIcon(pluginContext.getDrawable(com.atakmap.android.firecast.plugin.R.drawable.ic_toolbar))
                                    .setMissionPackage(m)
                                    .build()
                                    .show();
                        } catch (Exception e) {
                            Log.w(TAG, "send dialog failed", e);
                        }
                    }
                });
            }
        });
    }

    /** The start's marker for the run file (an orange-red dot in a white ring, 72 px); null when it cannot be read. */
    private byte[] startIcon() {
        try {
            final java.io.InputStream in = pluginContext.getResources().openRawResource(
                    com.atakmap.android.firecast.plugin.R.drawable.firecast_start);
            try {
                final java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
                final byte[] buf = new byte[16384];
                int n;
                while ((n = in.read(buf)) > 0)
                    out.write(buf, 0, n);
                return out.toByteArray();
            } finally {
                in.close();
            }
        } catch (Exception e) {
            return null;
        }
    }

    private String pluginVersion() {
        try {
            return pluginContext.getPackageManager().getPackageInfo(pluginContext.getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "";
        }
    }

    /** The run in plain words, for the file's card: what went in, what came out, what it does not mean. */
    static String card(Run r) {
        final StringBuilder c = new StringBuilder();
        c.append("FireCast model run, ").append(new java.text.SimpleDateFormat("EEE d MMM yyyy HH:mm", Locale.US)
                .format(new java.util.Date(r.ranAt))).append(".\n");
        if (r.originAt > 0)
            c.append("Ignition set to ").append(stamp(r.originAt)).append(", ").append(r.nowMinutes())
                    .append(" min before the run: the rings run from the ignition.\n");
        c.append(r.perimeter == null ? "Start at " : "nasa".equals(r.perimeterKind) ? "From NASA's satellite perimeter around "
                : "shape".equals(r.perimeterKind) ? "From a shape on the map around " : "From a drawn perimeter around ")
                .append(String.format(Locale.US, "%.5f, %.5f.\n", r.lat, r.lon));
        if (r.fuelName != null)
            c.append("Fuel: ").append(r.fuelName).append(" (LANDFIRE 2024).\n");
        c.append(String.format(Locale.US, "Wind %.0f mph from %s", r.windMph, Units.compass(r.windFromDeg)));
        if (r.windNote != null && !r.windNote.isEmpty())
            c.append(": ").append(r.windNote);
        c.append(".\n");
        c.append(String.format(Locale.US, "Fuel moisture 1 h %.0f%%, 10 h %.0f%%, 100 h %.0f%%, live grass %.0f%%, live brush %.0f%%.",
                r.m1, r.m10, r.m100, r.mHerb, r.mWoody));
        if (r.moistureNote != null)
            c.append(' ').append(r.moistureNote);
        c.append('\n');
        if (r.hourly != null && r.hourly.length > 1) {
            c.append("Hour by hour:");
            for (Hour h : r.hourly)
                c.append(String.format(Locale.US, " %s %.0f mph from %s, 1 h %.0f%%;", stamp(h.at), h.windMph,
                        Units.compass(h.windFromDeg), h.m1));
            c.append('\n');
        }
        for (int i = 0; i < r.steps.length; i++) {
            c.append("By ").append(stamp(r.t0() + r.steps[i] * 60000L)).append(" (").append(r.steps[i]).append(" min): ")
                    .append(acresText(r.acres(r.steps[i])));
            if (r.structures != null)
                c.append(", ").append(r.structures[i]).append(r.structures[i] == 1 ? " building" : " buildings");
            c.append(".\n");
        }
        if (r.ground != null && r.startCanopy != null) {
            final double[] crown = r.ground.crownAcres(r.steps[r.steps.length - 1]);
            if (crown[0] >= 0.05 || crown[1] >= 0.05)
                c.append("Crown fire: ").append(acresText(crown[0])).append(" crowning, ").append(acresText(crown[1]))
                        .append(" torching.\n");
        }
        c.append("What this does not mean: a planning-grade model. Real fuel and slope cell by cell, the weather as "
                + "read and forecast, embers across walls narrower than their reach; no suppression, no fire-made "
                + "wind. Run again as conditions change.");
        return c.toString();
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
                RunFile.delete(r.runId);
                save();
                changed();
            }
        });
    }

    /** Takes every run and the start marker off the map and forgets them all. */
    public void clearAll() {
        final List<Run> gone = new ArrayList<>(runs);
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
                for (Run g : gone)
                    RunFile.delete(g.runId);
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
        layer = new FeatureLayer3("FireCast fire model", store, visibleOnly);
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
                "FireCast fire model", "file://asset/nothing", query, null, null);
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
                final long fsid = store.insertFeatureSet(new FeatureSet("FireCast", "Fire model",
                        r.label(), Double.MAX_VALUE, 0d));
                store.setFeatureSetVisible(fsid, r.visible);
                r.fsid = fsid;
                // Largest first so the first ring draws on top of the last.
                for (int i = r.steps.length - 1; i >= 0; i--) {
                    final int color = stepColor(i, r.steps.length);
                    final Style style = new CompositeStyle(new Style[] {
                            new BasicFillStyle((0x30 << 24) | color),
                            new BasicStrokeStyle(0xFF000000 | color, 3f) });
                    final List<Polygon> shapes = new ArrayList<>();
                    if (r.ground != null)
                        shapes.addAll(r.ground.rings(r.steps[i]));
                    else
                        shapes.add(ellipse(r, r.steps[i]));
                    for (Polygon p : shapes) {
                        final AttributeSet a = new AttributeSet();
                        a.setAttribute("_title", r.steps[i] + " min");
                        store.insertFeature(new Feature(fsid, r.steps[i] + " min", p, style, a,
                                Feature.AltitudeMode.ClampToGround, 0d));
                    }
                }
                r.atFids.clear();
                insertAtLocked(r);
            }
        } catch (Exception e) {
            Log.w(TAG, "model draw failed", e);
        } finally {
            if (bulk)
                store.releaseModifyLock();
        }
    }

    private final ExecutorService atWorker = Executors.newSingleThreadExecutor();
    private final java.util.concurrent.atomic.AtomicBoolean atQueued = new java.util.concurrent.atomic.AtomicBoolean();
    private volatile Run atRun;

    /**
     * Draws where a run's fire is at a time the operator slid to, in cyan over its
     * steps. A drag fires this many times a second; the worker draws only the latest
     * value, so the edge follows the finger without a queue of stale ones behind it.
     * {@code keep} saves it, on the finger's release, so it comes back after a restart.
     * Main thread.
     */
    public void showAt(final Run r, int minutes, boolean keep) {
        if (r == null)
            return;
        r.atMinutes = Math.max(0, minutes);
        atRun = r;
        if (atQueued.compareAndSet(false, true))
            atWorker.execute(new Runnable() {
                @Override
                public void run() {
                    atQueued.set(false);
                    final Run x = atRun;
                    synchronized (lock) {
                        drawAtLocked(x);
                    }
                }
            });
        if (keep)
            worker.execute(new Runnable() {
                @Override
                public void run() {
                    save();
                }
            });
    }

    /** Replaces a run's slid-to edge. Lock held. */
    private void drawAtLocked(Run r) {
        if (store == null || r == null || r.fsid <= 0)
            return;
        boolean bulk = false;
        try {
            store.acquireModifyLock(true);
            bulk = true;
            for (Long fid : r.atFids)
                store.deleteFeature(fid);
            r.atFids.clear();
            insertAtLocked(r);
        } catch (Exception e) {
            Log.w(TAG, "model time edge failed", e);
        } finally {
            if (bulk)
                store.releaseModifyLock();
        }
    }

    /** The slid-to edge's features, inserted after the steps so they draw on top. Lock and modify lock held. */
    private void insertAtLocked(Run r) throws Exception {
        if (r.atMinutes <= 0 || r.result == null)
            return;
        // Cyan over a dark halo reads on imagery, terrain and the step colors alike,
        // and is the plugin's highlight color everywhere else.
        final Style style = new CompositeStyle(new Style[] {
                new BasicFillStyle(0x2800E5FF),
                new BasicStrokeStyle(0xC0000000, 7f),
                new BasicStrokeStyle(0xFF00E5FF, 4f) });
        final List<Polygon> shapes = new ArrayList<>();
        if (r.ground != null)
            shapes.addAll(r.ground.rings(r.atMinutes));
        else
            shapes.add(ellipse(r, r.atMinutes));
        final String title = "By " + stamp(r.t0() + r.atMinutes * 60000L);
        for (Polygon p : shapes) {
            final AttributeSet a = new AttributeSet();
            a.setAttribute("_title", title);
            r.atFids.add(store.insertFeature(new Feature(r.fsid, title, p, style, a,
                    Feature.AltitudeMode.ClampToGround, 0d)));
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
            com.atakmap.android.firecast.SafeFile.write(saveFile, out.toString());
        } catch (Exception e) {
            Log.w(TAG, "could not save the model runs", e);
        }
    }

    private static void putNumber(JSONObject o, String key, double v) throws Exception {
        if (!Double.isNaN(v) && !Double.isInfinite(v))
            o.put(key, v);
    }

    static JSONObject toJson(Run r) throws Exception {
        {
            final JSONObject o = new JSONObject();
            o.put("runId", r.runId);
            if (r.by != null) {
                o.put("by", r.by).put("receivedAt", r.receivedAt);
                if (r.byUid != null)
                    o.put("byUid", r.byUid);
                if (r.sourcePath != null)
                    o.put("sourcePath", r.sourcePath);
            }
            o.put("lat", r.lat).put("lon", r.lon).put("ranAt", r.ranAt);
            o.put("fuelModel", r.site.fuelModel).put("slopeDeg", r.site.slopeDeg)
                    .put("aspectDeg", r.site.aspectDeg).put("coverPct", r.site.coverPct);
            o.put("m1", r.m1).put("m10", r.m10).put("m100", r.m100).put("mHerb", r.mHerb).put("mWoody", r.mWoody);
            o.put("windMph", r.windMph).put("windFromDeg", r.windFromDeg).put("windNote", r.windNote)
                    .put("tenMeterWind", r.tenMeterWind).put("moistureNote", r.moistureNote)
                    .put("eyeLevelWind", r.eyeLevelWind).put("obsNote", r.obsNote == null ? "" : r.obsNote);
            o.put("canopyHeightFt", r.canopyHeightFt).put("crownRatio", r.crownRatio).put("conditions", r.conditions)
                    .put("visible", r.visible).put("ground", r.ground != null).put("halfKm", r.halfKm)
                    .put("startedAt", r.startedAt).put("rateScale", r.rateScale).put("cellM", r.cellM)
                    .put("originAt", r.originAt);
            final org.json.JSONArray stepsA = new org.json.JSONArray();
            for (int m : r.steps)
                stepsA.put(m);
            o.put("steps", stepsA);
            if (r.calibrationNote != null)
                o.put("calibrationNote", r.calibrationNote);
            if (r.sizeAcres != null && r.sizeNudges != null) {
                final org.json.JSONArray sa = new org.json.JSONArray(), sn = new org.json.JSONArray();
                for (double v : r.sizeAcres)
                    sa.put(v);
                for (double[] n : r.sizeNudges)
                    sn.put(new org.json.JSONArray().put(n[0]).put(n[1]).put(n[2]));
                o.put("sizeAcres", sa).put("sizeRuns", r.sizeRuns).put("sizeNudges", sn).put("sizePct", r.sizePct);
            }

            if (r.perimeter != null) {
                final org.json.JSONArray pa = new org.json.JSONArray();
                for (double[] p : r.perimeter)
                    pa.put(new org.json.JSONArray().put(p[0]).put(p[1]));
                o.put("perimeter", pa);
                if (r.perimeterParts != null)
                    o.put("perimeterParts", partsJson(r.perimeterParts));
                if (r.perimeterNote != null)
                    o.put("perimeterNote", r.perimeterNote);
                if (r.perimeterKind != null)
                    o.put("perimeterKind", r.perimeterKind);
            }
            if (r.structures != null) {
                final org.json.JSONArray st = new org.json.JSONArray(), hm = new org.json.JSONArray(), pp = new org.json.JSONArray();
                for (int i = 0; i < r.steps.length; i++) {
                    st.put(r.structures[i]);
                    hm.put(r.homes[i]);
                    pp.put(r.people[i]);
                }
                o.put("structures", st).put("homes", hm).put("people", pp);
            }
            if (r.structureNote != null)
                o.put("structureNote", r.structureNote);
            if (r.bArrival != null) {
                final org.json.JSONArray ba = new org.json.JSONArray(), bh = new org.json.JSONArray(), bp = new org.json.JSONArray();
                for (int i = 0; i < r.bArrival.length; i++) {
                    ba.put(Math.round(r.bArrival[i] * 10) / 10d);
                    bh.put(r.bHome[i] ? 1 : 0);
                    bp.put(Math.round(r.bPeople[i] * 10) / 10d);
                }
                o.put("bArrival", ba).put("bHome", bh).put("bPeople", bp);
            }
            o.put("atMinutes", r.atMinutes);
            o.put("mFoliar", r.mFoliar);
            if (r.foliarNote != null)
                o.put("foliarNote", r.foliarNote);

            if (r.hourly != null) {
                final org.json.JSONArray hs = new org.json.JSONArray();
                for (Hour h : r.hourly)
                    hs.put(new org.json.JSONArray().put(h.at).put(h.windMph).put(h.windFromDeg)
                            .put(h.m1).put(h.m10).put(h.m100));
                o.put("hourly", hs);
                if (r.hourlyNote != null)
                    o.put("hourlyNote", r.hourlyNote);
            }
            o.put("fieldWind", r.fieldWind);
            if (r.windField != null)
                o.put("fieldRun", r.windField.run).put("fieldHour", r.windField.firstHour).put("fieldHours", r.windField.size());
            final org.json.JSONArray cr = new org.json.JSONArray();
            for (Object[] c : r.critical)
                cr.put(new org.json.JSONArray().put(c[0]).put(c[1]).put(c[2]).put(c[3]));
            o.put("critical", cr);
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
            for (int i = 0; i < arr.length(); i++) {
                try {
                    read.add(fromJson(arr.getJSONObject(i)));
                } catch (Throwable t) {
                    // One bad run must not stop ATAK at every start.
                    Log.w(TAG, "a saved run could not be read back", t);
                }
            }
            if (!runs.isEmpty())
                return;   // a run finished first
            for (Run r : read) {
                if (r == null)
                    continue;
                if (r.by != null && r.sourcePath != null && !new File(r.sourcePath).exists()) {
                    // Its package is gone. "Remove Contents" took the run with it when it
                    // happened; this is "Leave Contents" (ATAK deletes the package's files
                    // either way), so the run stays, no longer tied to a package.
                    Log.d(TAG, "package gone, received run " + r.runId + " kept on its own");
                    r.sourcePath = null;
                }
                if (r.by != null && ownRun(r.byUid)) {
                    // This phone's own run, taken back in from its own sent package before
                    // that was caught (2026-10-17): the original is in the list already.
                    RunFile.delete(r.runId);
                    continue;
                }
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
            // Our own id or a new one: it names a file, so nothing else is taken.
            final String id = o.optString("runId", "");
            if (id.matches("[0-9a-f-]{36}"))
                r.runId = id;
            if (o.has("receivedAt")) {
                r.by = callsign(o.optString("by", ""));
                r.byUid = deviceUid(o.optString("byUid", ""));
                r.receivedAt = o.optLong("receivedAt", 0);
                r.sourcePath = text(o.optString("sourcePath", null));
            }
            // Every number is held to what a run of ours can produce: this reads our own
            // file and a file someone sent, and a size or an index from either reaches
            // allocations, array indexes and the native engine.
            r.lat = o.getDouble("lat");
            r.lon = o.getDouble("lon");
            if (!(Math.abs(r.lat) <= 90) || !(Math.abs(r.lon) <= 180))
                return null;
            r.ranAt = o.optLong("ranAt", 0L);
            r.site = new Landfire.Site(o.getInt("fuelModel"), clamp(o.optDouble("slopeDeg", 0), 0, 90, 0),
                    (clamp(o.optDouble("aspectDeg", 0), -1e6, 1e6, 0) % 360 + 360) % 360, clamp(o.optDouble("coverPct", 0), 0, 100, 0));
            r.m1 = moist(o.optDouble("m1", 4));
            r.m10 = moist(o.optDouble("m10", 5));
            r.m100 = moist(o.optDouble("m100", 7));
            r.mHerb = clamp(o.optDouble("mHerb", 30), 20, 400, 30);
            r.mWoody = clamp(o.optDouble("mWoody", 60), 20, 400, 60);
            r.windMph = clamp(o.optDouble("windMph", 0), 0, 150, 0);
            // behave walks a direction down by 360 at a time; a huge one from a damaged file would never finish.
            r.windFromDeg = (o.optDouble("windFromDeg", 0) % 360 + 360) % 360;
            if (Double.isNaN(r.windFromDeg))
                r.windFromDeg = 0;
            r.windNote = text(o.optString("windNote", ""));
            r.tenMeterWind = o.optBoolean("tenMeterWind", false);
            r.eyeLevelWind = o.optBoolean("eyeLevelWind", false);
            final String on = o.optString("obsNote", "");
            r.obsNote = on.isEmpty() || on.length() > 300 ? null : on;
            r.moistureNote = text(o.optString("moistureNote", ""));
            r.canopyHeightFt = clamp(o.optDouble("canopyHeightFt", 0), 0, 400, 0);
            r.crownRatio = clamp(o.optDouble("crownRatio", 0), 0, 1, 0);
            final String cond = o.optString("conditions", COND_OBSERVED);
            r.conditions = COND_GUST.equals(cond) || COND_DRY.equals(cond) || COND_DRY_WINDY.equals(cond) ? cond : COND_OBSERVED;
            r.visible = o.optBoolean("visible", true);
            // The grid is (2 halfKm / cell)^2 cells: only the two sizes a run makes, and a
            // whole number of km, which is how the ground is kept on disk.
            final double cell = o.optDouble("cellM", 15);
            r.cellM = cell >= 60 ? 60 : cell >= 30 ? 30 : 15;
            final double half = o.optDouble("halfKm", r.cellM >= 30 ? 10 : HALF_KM);
            r.halfKm = half >= HALF_KM && half <= MAX_HALF_KM ? Math.rint(half) : r.cellM >= 30 ? 10 : HALF_KM;
            r.startedAt = o.optLong("startedAt", 0);
            final long origin = o.optLong("originAt", 0);
            r.originAt = origin > 0 && origin <= r.ranAt && r.ranAt - origin <= IGNITION_MAX_MS ? origin : 0;
            final org.json.JSONArray stepsA = o.optJSONArray("steps");
            if (stepsA != null && stepsA.length() > 0 && stepsA.length() <= 6) {
                final int[] st = new int[stepsA.length()];
                boolean ok = true;
                for (int i = 0; i < st.length; i++) {
                    st[i] = stepsA.optInt(i);
                    if (st[i] < 1 || st[i] > 180 || (i > 0 && st[i] <= st[i - 1]))
                        ok = false;
                }
                if (ok)
                    r.steps = st;
            }
            r.rateScale = clamp(o.optDouble("rateScale", 1), 0.3, 3, 1);
            r.calibrationNote = text(o.optString("calibrationNote", null));
            r.atMinutes = Math.max(0, Math.min(r.steps[r.steps.length - 1], o.optInt("atMinutes", 0)));
            r.mFoliar = Math.max(60, Math.min(300, o.optDouble("mFoliar", 100)));
            if (Double.isNaN(r.mFoliar))
                r.mFoliar = 100;
            r.foliarNote = text(o.optString("foliarNote", null));
            final org.json.JSONArray hs = o.optJSONArray("hourly");
            if (hs != null && hs.length() > 0 && hs.length() <= 6) {
                final Hour[] hours = new Hour[hs.length()];
                for (int i = 0; i < hs.length(); i++) {
                    final org.json.JSONArray h = hs.optJSONArray(i);
                    if (h == null || h.length() < 6) {
                        hours[0] = null;
                        break;
                    }
                    // Back into the ranges the engine is given on a fresh run.
                    hours[i] = new Hour(h.optLong(0), clamp(h.optDouble(1, 0), 0, 150, 0),
                            (clamp(h.optDouble(2, 0), -1e6, 1e6, 0) % 360 + 360) % 360, moist(h.optDouble(3, 4)),
                            moist(h.optDouble(4, 5)), moist(h.optDouble(5, 7)));
                }
                if (hours[0] != null)
                    r.hourly = hours;
                r.hourlyNote = text(o.optString("hourlyNote", null));
            }
            r.fieldWind = o.optBoolean("fieldWind", false);
            if (r.fieldWind && o.has("fieldRun")) {
                // From disk only: a run coming back at start must not wait on the network.
                final double dLat = r.halfKm * 1000d / 111320d;
                final double dLon = r.halfKm * 1000d / (111320d * Math.max(0.02d, Math.cos(Math.toRadians(r.lat))));
                r.windField = WindField.fromDisk(r.lat - dLat, r.lon - dLon, r.lat + dLat, r.lon + dLon,
                        o.optLong("fieldRun"), o.optInt("fieldHour"), o.optInt("fieldHours", 2));
                if (r.windField == null)
                    r.windFieldNote = "The wind field this run used is no longer on the phone; it is redrawn with one wind.";
            }

            final org.json.JSONArray pa = o.optJSONArray("perimeter");
            if (pa != null && pa.length() >= 3 && pa.length() <= 5000) {
                final double[][] per = new double[pa.length()][];
                for (int i = 0; i < pa.length() && per != null; i++) {
                    final double la = pa.getJSONArray(i).getDouble(0), lo = pa.getJSONArray(i).getDouble(1);
                    if (!(Math.abs(la) <= 90) || !(Math.abs(lo) <= 180))
                        return null;
                    per[i] = new double[] { la, lo };
                }
                r.perimeter = per;
                r.perimeterParts = partsFrom(o.optJSONArray("perimeterParts"));
                final String pn = o.optString("perimeterNote", "");
                r.perimeterNote = pn.isEmpty() || pn.length() > 600 ? null : pn;
                final String pk = o.optString("perimeterKind", r.perimeterNote != null ? "nasa" : "");
                r.perimeterKind = "nasa".equals(pk) || "shape".equals(pk) ? pk : null;
            }
            r.fuelName = Behave.name(r.site.fuelModel);
            r.result = Behave.surface(r.site.fuelModel, r.m1, r.m10, r.m100, r.mHerb, r.mWoody,
                    r.windMph, r.windHeight(), r.windFromDeg, r.site.slopeDeg, r.site.aspectDeg,
                    r.site.coverPct, r.canopyHeightFt, r.crownRatio);
            finish(r);
            if (r.problem != null)
                return null;
            // Predicted Fire Size: held to the bounds the nudges are drawn in, since a
            // sent run's file comes from another phone.
            final org.json.JSONArray sa = o.optJSONArray("sizeAcres"), sn = o.optJSONArray("sizeNudges");
            if (sa != null && sa.length() == SIZE_PCT.length && sn != null && sn.length() == SIZE_PCT.length) {
                final double[] acres = new double[SIZE_PCT.length];
                final double[][] nudges = new double[SIZE_PCT.length][];
                boolean ok = true;
                for (int i = 0; i < acres.length && ok; i++) {
                    acres[i] = sa.optDouble(i, Double.NaN);
                    final org.json.JSONArray one = sn.optJSONArray(i);
                    nudges[i] = one == null || one.length() != 3 ? null : nudge(one);
                    ok = acres[i] >= 0 && acres[i] < 1e7 && nudges[i] != null;
                }
                if (ok) {
                    r.sizeAcres = acres;
                    r.sizeRuns = Math.max(0, Math.min(SIZE_RUNS, o.optInt("sizeRuns", 0)));
                    r.sizeNudges = nudges;
                    final int pct = o.optInt("sizePct", 50);
                    for (int q : SIZE_PCT)
                        if (q == pct)
                            r.sizePct = pct;
                }
            }
            if (o.optBoolean("ground", false)) {
                // The grids are on disk, so the ground run needs no network; the
                // building counts are kept from the run, not fetched again.
                final org.json.JSONArray st = o.optJSONArray("structures"), hm = o.optJSONArray("homes"), pp = o.optJSONArray("people");
                acrossTheGroundNoStructures(r);
                if (st != null && hm != null && pp != null && st.length() == r.steps.length) {
                    r.structures = new int[r.steps.length];
                    r.homes = new int[r.steps.length];
                    r.people = new double[r.steps.length];
                    for (int i = 0; i < r.steps.length; i++) {
                        r.structures[i] = st.optInt(i);
                        r.homes[i] = hm.optInt(i);
                        r.people[i] = pp.optDouble(i, 0);
                    }
                }
                r.structureNote = text(o.optString("structureNote", null));
                final org.json.JSONArray ba = o.optJSONArray("bArrival"), bh = o.optJSONArray("bHome"), bp = o.optJSONArray("bPeople");
                if (ba != null && bh != null && bp != null && ba.length() == bh.length() && ba.length() == bp.length()
                        && ba.length() <= Structures.MAX) {
                    r.bArrival = new float[ba.length()];
                    r.bHome = new boolean[ba.length()];
                    r.bPeople = new float[ba.length()];
                    for (int i = 0; i < ba.length(); i++) {
                        r.bArrival[i] = (float) ba.optDouble(i, Double.POSITIVE_INFINITY);
                        r.bHome[i] = bh.optInt(i) == 1;
                        r.bPeople[i] = (float) bp.optDouble(i, 0);
                    }
                }
                final org.json.JSONArray cr = o.optJSONArray("critical");
                if (cr != null)
                    for (int i = 0; i < cr.length() && r.critical.size() < 40; i++) {
                        final org.json.JSONArray c = cr.optJSONArray(i);
                        // The step index picks a step on the page: held to the run's own.
                        if (c != null && c.length() == 4 && c.optInt(3, -1) >= 0 && c.optInt(3, -1) < r.steps.length
                                && Math.abs(c.optDouble(1, 999)) <= 90 && Math.abs(c.optDouble(2, 999)) <= 180)
                            r.critical.add(new Object[] { text(c.optString(0)), c.optDouble(1), c.optDouble(2), c.optInt(3) });
                    }
            }
            return r;
        } catch (Throwable e) {
            Log.w(TAG, "could not read a saved model run", e);
            return null;
        }
    }

    /** How old an observation may be and still stand for the weather now. */
    static final long FRESH_MS = 3 * 3_600_000L;

    /** Read within {@link #FRESH_MS} of the run. */
    static boolean fresh(Weather.Reading w, long at) {
        return w.observedAt > 0 && Math.abs(at - w.observedAt) <= FRESH_MS;
    }

    /** A saved nudge, or null when it is outside what {@link #sizes} draws. */
    private static double[] nudge(org.json.JSONArray a) {
        final double w = a.optDouble(0, Double.NaN), d = a.optDouble(1, Double.NaN), m = a.optDouble(2, Double.NaN);
        if (!(w >= 0.5 && w <= 1.8) || !(d >= -40 && d <= 40) || !(m >= 0.7 && m <= 1.4))
            return null;
        return new double[] { w, d, m };
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
