package com.atakmap.android.airaware;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Handler;
import android.os.Looper;
import android.preference.PreferenceManager;

import com.atakmap.android.maps.MapView;
import com.atakmap.android.airaware.ui.ScaleBar;
import com.atakmap.coremap.filesystem.FileSystemUtils;
import com.atakmap.coremap.log.Log;
import com.atakmap.coremap.maps.coords.GeoBounds;
import com.atakmap.coremap.maps.coords.GeoPoint;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The switch, the sync, the filters, and everything the plugin knows about the national
 * picture. One rule behind the list, the map and the counts.
 *
 * <p>Lives for the plugin's life and never inside a {@code Tool}: ATAK ends the active
 * tool whenever another starts, a pane opens, or Back is pressed, and a TFR that stopped
 * refreshing because somebody switched base maps would read as airspace that had been
 * lifted.
 *
 * <p>The list API carries no coordinates, so a radius or an extent cannot be evaluated
 * until the geometry is on the device. That is why the sync is national and every filter
 * afterwards is local: the whole country is about 104 documents of 26 KB.
 */
public class TfrManager {

    private static final String TAG = "TfrManager";

    private static final String PREFS = "airaware";
    static final String PREF_TYPES_OFF = "typesOff";
    static final String PREF_LAYERS_OFF = "layersOff";

    /** The layers an operator turns on and off as wholes. */
    public static final String LAYER_RESTRICTIONS = "restrictions";
    public static final String LAYER_AIRFIELDS = MetarFeatures.SET_KEY;
    public static final String LAYER_AIRSPACE = Airspace.LAYER_CLASSES;
    public static final String LAYER_SUA = Airspace.LAYER_SUA;
    public static final String LAYER_OBSTACLES = "obstacles";
    public static final String LAYER_UASFM = UasfmFeatures.SET_KEY;
    public static final String LAYER_NOTAMS = "notams";
    static final String PREF_NOTAM_KINDS_OFF = "notamKindsOff";
    static final String PREF_NOTAM_BAR_M = "notamBarM";
    /** Set once the NOTAM layer has been seen, so an upgrade does not switch it on. */
    static final String PREF_NOTAMS_INTRODUCED = "notamsIntroduced";
    static final String PREF_AIRSPACE_BAR_M = "airspaceBarM";
    static final String PREF_AIRSPACE_CYCLE = "airspaceCycle";
    static final String PREF_AIRSPACE_LABELS = "airspaceLabels";
    static final String PREF_AIRSPACE_3D = "airspace3d";
    static final String PREF_OBSTACLE_CYCLE = "obstacleCycle";
    static final String PREF_OBSTACLE_GROUPS_OFF = "obstacleGroupsOff";
    static final String PREF_OBSTACLE_FLOOR_FT = "obstacleFloorFt";
    static final String PREF_OBSTACLE_BAR_M = "obstacleBarM";
    static final String PREF_UASFM_CYCLE = "uasfmCycle";
    static final String PREF_UASFM_BAR_M = "uasfmBarM";
    static final String PREF_CLASSES_OFF = "classesOff";
    static final String PREF_GATE_BAR_M = "gateBarM";
    static final String PREF_LABEL_BAR_M = "labelBarM";
    static final String PREF_AIRFIELD_BAR_M = "airfieldBarM";
    static final String PREF_WHERE_MODE = "whereMode";
    static final String PREF_WHERE_VALUES = "whereValues";
    static final String PREF_AREA_MODE = "areaMode";
    static final String PREF_AREA_RADIUS_M = "areaRadiusM";
    static final String PREF_MEASURE_FROM = "measureFrom";

    /** What distances and the radius are measured from. */
    public static final int FROM_ME = 0;
    public static final int FROM_MAP_CENTER = 1;

    /** How the operator is saying where they care about. */
    public static final int WHERE_EVERYWHERE = 0;
    public static final int WHERE_STATE = 1;
    public static final int WHERE_REGION = 2;
    public static final int WHERE_CENTER = 3;

    /** How much of it the list covers. */
    public static final int AREA_EVERYWHERE = 0;
    public static final int AREA_IN_VIEW = 1;
    public static final int AREA_RADIUS = 2;

    /** Tool Preferences, shared with ATAK's own settings screen. */
    public static final String PREF_REFRESH_MIN = "tfr_refresh_minutes";
    public static final String PREF_COLOR_ACTIVE = "tfr_color_active";
    public static final String PREF_COLOR_UPCOMING = "tfr_color_upcoming";

    /**
     * Red for what is in effect, amber for what is scheduled. ForeFlight, Garmin Pilot,
     * SkyVector and the Leidos briefer all color a TFR by status and none of them by
     * category, so an operator who has seen a TFR anywhere else already knows what these
     * mean. Both are overridable in Tool Preferences.
     */
    public static final int DEFAULT_ACTIVE = Color.rgb(0xE0, 0x1B, 0x24);
    public static final int DEFAULT_UPCOMING = Color.rgb(0xFF, 0xB3, 0x00);

    /**
     * Security is half the national list and is mostly standing stadium and capital
     * airspace that never changes. Off out of the box, remembered once touched.
     */
    private static final String DEFAULT_OFF = "SECURITY";
    /**
     * Airspace kinds a fresh install leaves off.
     *
     * <p>Class A starts at 18,000 ft, so nothing an operator on the ground or an aircraft
     * over a fire does is affected by it, and drawn it is a lid over the whole map. Class
     * G is uncontrolled -- it is everywhere that is not something else, and charts do not
     * depict it either.
     */
    private static final Set<String> DEFAULT_CLASSES_OFF = new HashSet<>(
            java.util.Arrays.asList("as:A", "as:G", "as:?"));

    private static final long TICK_MS = 60 * 1000L;
    /** The shortest gap between airfield fetches, however far the map is moved. */
    private static final long METAR_MIN_GAP_MS = 20 * 1000L;
    /**
     * The most tiles loaded for one view. Two-degree tiles and a hundred-mile zoom gate
     * mean a handful in practice; this is a ceiling against a view that somehow asks for
     * the country.
     */
    private static final int MAX_TILES = 24;
    /**
     * The widest scale bar at which airspace is drawn with height, about fifteen miles.
     *
     * <p>Not a preference. Height is a close-in thing by nature: six hundred shelves
     * standing up across a region is a wireframe thicket nobody can read, and the same
     * picture flat is still the whole lateral story. So 3D arrives when the view is
     * close enough for it to mean something and leaves when it is not, and the operator
     * never has to manage it.
     */
    private static final long VOLUME_BAR_M = 24140L;
    /**
     * The most obstacles drawn at once, three features each.
     *
     * <p>Los Angeles holds 12,542 in two degrees and the filter does not always cut
     * that far, so there has to be a stop. The pane says when it bites.
     */
    private static final int MAX_OBSTACLES = 400;
    /** Grid squares are one feature each and tiny; past this the view is a wash. */
    private static final int MAX_UASFM_CELLS = 1500;
    /** NOTAMs drawn at once: nearest the middle of the view first, like the obstacles. */
    private static final int MAX_NOTAMS = 400;
    /** Amber: a thing that sticks up, not a restriction and not weather. */
    private static final int DEFAULT_OBSTACLE = 0xFFFFB300;
    private static final int DEFAULT_REFRESH_MIN = 30;

    public interface Listener {
        /** Anything the pane shows has changed. Main thread. */
        void onChanged();
    }

    private final MapView mapView;
    private final Context pluginContext;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final TfrCache cache = new TfrCache();
    private final TfrOverlay overlay;

    private Listener listener;
    private boolean started;
    /** Raw FAA type strings the operator has switched off. */
    private final Set<String> typesOff = new LinkedHashSet<>();
    /** Whole layers switched off. Restrictions and airfields are independent. */
    private final Set<String> layersOff = new LinkedHashSet<>();

    private volatile List<Tfr> known = Collections.emptyList();
    private volatile boolean syncing;
    private volatile String syncStatus = "";
    private volatile long lastSuccessMs;
    private volatile String lastError;
    /** The scale-bar distance at or below which the map draws; -1 is always. */
    private volatile long gateBarM;
    private volatile boolean gateHiding;
    /**
     * The labels have their own gate, because an operator wants to see where the
     * restrictions are from much further out than they want to read their names.
     * Ten miles by default (operator, 2026-10-07).
     */
    private volatile long labelBarM = 16093L;
    private volatile boolean labelsHidden;
    /**
     * The airfield chips are dense -- seventy across California and Nevada -- so they are
     * a close-in thing and gate tighter than the restrictions, which matter from far out.
     */
    private volatile long airfieldBarM = 48280L;
    private volatile boolean airfieldsHidden;
    /** Geofences whose restriction has expired, been lifted or changed under them. */
    private volatile List<TfrWatch.Watched> staleFences = Collections.emptyList();
    private volatile List<TfrWatch.Watched> allFences = Collections.emptyList();
    private volatile int whereMode;
    private final Set<String> whereValues = new LinkedHashSet<>();
    private volatile int areaMode = AREA_IN_VIEW;
    private volatile long areaRadiusM = 80467L;
    private volatile int measureFrom = FROM_ME;
    /** Airfield observations for where the operator is looking, and the box they cover. */
    private volatile List<Metar> metars = Collections.emptyList();
    private volatile double[] metarBox;
    private volatile long metarFetchedMs;
    /**
     * Airspace shelves for where the operator is looking.
     *
     * <p>Held far longer than the observations: airspace changes on the FAA's 56-day
     * publication cycle, and the service's quota is shared with every other caller in the
     * world, so a refetch is a once-a-day thing and a pan inside the box is free.
     */
    private volatile List<Airspace> airspaces = Collections.emptyList();
    private volatile long airspaceFetchedMs;

    /** Tiles the held shelves came from, so a pan inside them costs nothing. */
    private volatile Set<String> airspaceKeys = Collections.emptySet();
    /** The publication cycle on the phone; a new one clears the tiles. */
    private volatile String airspaceCycle = "";
    /** True when a tile this view needs is not downloaded and could not be fetched. */
    private volatile boolean airspaceMissing;
    /**
     * Whether airspace shelves carry their name on the map. <b>Off.</b>
     *
     * <p>A label per shelf is forty labels over Los Angeles, stacked over each other and
     * over everything else -- operator: "labels i think are useless". The chart
     * underneath already names what it draws, the pinned line says what you are standing
     * in, and a tap gives the whole shelf, so this is something to switch on rather than
     * something to switch off.
     */
    private volatile boolean airspaceLabels;
    /**
     * Whether class shelves stand up. <b>On.</b>
     *
     * <p>Taken out on the operator's own reasoning that a dozen nested shelves read as a
     * block, and put straight back when they saw it -- "oh shit the 3d is bitchen for
     * airspace i just had a view". So it is theirs to switch rather than mine to decide,
     * and it starts on. Special use always stands up either way.
     */
    private volatile boolean airspace3d;
    /** True while the view is too wide for height to read. */
    private volatile boolean volumesGated;
    /** The FAA obstacle file, for the tiles the view needs. */
    private volatile List<Obstacle> obstacles = Collections.emptyList();
    private volatile Set<String> obstacleKeys = Collections.emptySet();
    private volatile String obstacleCycle = "";
    private volatile boolean obstaclesHidden;
    private volatile boolean obstaclesCapped;
    private volatile boolean obstaclesMissing;
    /** Everything the loaded tiles hold that passes the filter. */
    private volatile List<Obstacle> obstaclesAll = Collections.emptyList();
    /** The padded box the drawn set was picked for; panning inside it is free. */
    private volatile double[] obstacleBox;
    private volatile long obstacleBarM = 16093L;
    private volatile double obstacleFloorFt = Obstacle.DEFAULT_MIN_AGL_FT;
    private final Set<String> obstacleGroupsOff = new LinkedHashSet<>();
    private ObstaclePills pills;
    /** The UAS Facility Map: what a drone may fly to here without an authorization. */
    private volatile List<UasfmCell> uasfmAll = Collections.emptyList();
    private volatile List<UasfmCell> uasfm = Collections.emptyList();
    private volatile Set<String> uasfmKeys = Collections.emptySet();
    private volatile double[] uasfmBox;
    private volatile String uasfmCycle = "";
    private volatile boolean uasfmHidden;
    private volatile boolean uasfmCapped;
    private volatile long uasfmBarM = 8047L;

    // NOTAMs: every record in the held tiles, and the ones picked for the view.
    private final Set<String> notamKindsOff = new LinkedHashSet<>();
    private volatile List<Notam> notamsAll = Collections.emptyList();
    private volatile List<Notam> notams = Collections.emptyList();
    private volatile Set<String> notamKeys = Collections.emptySet();
    private volatile double[] notamBox;
    private volatile Map<String, Integer> notamCounts = Collections.emptyMap();
    private volatile String notamCycle = "";
    private volatile String notamBuilt = "";
    private volatile long notamLoadedAt;
    private volatile boolean notamHidden;
    private volatile boolean notamCapped;
    private volatile boolean notamsMissing;
    private volatile long notamBarM = 16093L;

    private volatile boolean airspaceCapped;
    private volatile long airspaceBarM = 160934L;
    private volatile boolean airspaceHidden;
    /** Classes and special-use kinds switched off individually, by set key. */
    private final Set<String> classesOff = new LinkedHashSet<>();

    public TfrManager(MapView mapView, Context pluginContext) {
        this.mapView = mapView;
        this.pluginContext = pluginContext;
        this.overlay = new TfrOverlay(mapView, pluginContext,
                new File(FileSystemUtils.getItem("tools/airaware"), "airaware.sqlite"), "AirAware");
        final SharedPreferences p = prefs();
        // A fresh install starts with every layer off: nothing pulls the country, or a
        // megabyte of airspace, before it is asked to.
        gateBarM = p.getLong(PREF_GATE_BAR_M, -1L);
        labelBarM = p.getLong(PREF_LABEL_BAR_M, 16093L);
        airfieldBarM = p.getLong(PREF_AIRFIELD_BAR_M, 48280L);
        // A hundred miles: airspace is big, and a shelf matters long before you reach it.
        airspaceBarM = p.getLong(PREF_AIRSPACE_BAR_M, 160934L);
        airspaceCycle = p.getString(PREF_AIRSPACE_CYCLE, "");
        airspaceLabels = p.getBoolean(PREF_AIRSPACE_LABELS, false);
        airspace3d = p.getBoolean(PREF_AIRSPACE_3D, true);
        obstacleCycle = p.getString(PREF_OBSTACLE_CYCLE, "");
        uasfmCycle = p.getString(PREF_UASFM_CYCLE, "");
        // Five miles. The cells are half a nautical mile across, so any wider and
        // the grid is a wash of color rather than a rule you can read.
        uasfmBarM = p.getLong(PREF_UASFM_BAR_M, 8047L);
        obstacleBarM = p.getLong(PREF_OBSTACLE_BAR_M, 16093L);
        obstacleFloorFt = p.getFloat(PREF_OBSTACLE_FLOOR_FT,
                (float) Obstacle.DEFAULT_MIN_AGL_FT);
        final Set<String> groupsOff = new LinkedHashSet<>();
        for (String g : Obstacle.GROUPS)
            if (!Obstacle.DEFAULT_ON.contains(g))
                groupsOff.add(g);
        obstacleGroupsOff.addAll(p.getStringSet(PREF_OBSTACLE_GROUPS_OFF, groupsOff));
        final Set<String> kindsOff = new LinkedHashSet<>();
        for (String k : Notam.KINDS)
            if (!Notam.DEFAULT_ON.contains(k))
                kindsOff.add(k);
        notamKindsOff.addAll(p.getStringSet(PREF_NOTAM_KINDS_OFF, kindsOff));
        // Ten miles, the obstacles' gate: NOTAMs are as dense as towers are, and the
        // operator wants the layers to come in together.
        notamBarM = p.getLong(PREF_NOTAM_BAR_M, 16093L);
        classesOff.addAll(p.getStringSet(PREF_CLASSES_OFF, DEFAULT_CLASSES_OFF));
        // A set, not a joined string: a type carrying the separator would come back as two
        // bogus entries and the filter would restore wrong.
        whereMode = p.getInt(PREF_WHERE_MODE, WHERE_EVERYWHERE);
        whereValues.addAll(p.getStringSet(PREF_WHERE_VALUES, Collections.<String> emptySet()));
        areaMode = p.getInt(PREF_AREA_MODE, AREA_IN_VIEW);
        areaRadiusM = p.getLong(PREF_AREA_RADIUS_M, 80467L);
        measureFrom = p.getInt(PREF_MEASURE_FROM, FROM_ME);
        layersOff.addAll(p.getStringSet(PREF_LAYERS_OFF, allLayerKeys()));
        // The off-set is stored, so a layer that did not exist when it was saved comes
        // back ON after an upgrade. A new layer starts off, the once, like a fresh install.
        if (!p.getBoolean(PREF_NOTAMS_INTRODUCED, false)) {
            layersOff.add(LAYER_NOTAMS);
            p.edit().putBoolean(PREF_NOTAMS_INTRODUCED, true)
                    .putStringSet(PREF_LAYERS_OFF, new HashSet<>(layersOff)).apply();
        }
        typesOff.addAll(p.getStringSet(PREF_TYPES_OFF,
                new HashSet<>(Collections.singletonList(DEFAULT_OFF))));
    }

    public void setListener(Listener l) {
        this.listener = l;
    }

    public void start() {
        if (started)
            return;
        started = true;
        try {
            overlay.attach(isAnyLayerOn() && !gateHiding, hiddenSets());
            if (overlay.storeWasReset())
                Log.i(TAG, "the feature store was rebuilt; the first pass reads the"
                        + " tiles already on disk, so this works with no network");
        } catch (Exception e) {
            Log.w(TAG, "attaching the overlay failed", e);
        }
        mapView.addOnMapMovedListener(moved);
        main.postDelayed(tick, TICK_MS);
        // Airspace rode entirely on the map moving, so after a plugin reload the map had
        // restrictions and no airspace until the operator happened to pan -- which is
        // exactly how it looked to them, as 3D not working. The tiles are on disk, so
        // this is a file read, not a download.
        //
        // Retried rather than tried once at 1500 ms. maybeFetchAirspace gives up without
        // a word when the map has no bounds yet, and on a cold start it does not: the one
        // attempt landed too early and nothing loaded until the operator panned. That was
        // invisible while the store still held last session's features and ATAK drew them
        // from disk, and it stops being invisible the moment the store is rebuilt.
        main.postDelayed(new Runnable() {
            private int tries = 0;

            @Override
            public void run() {
                if (!started)
                    return;
                final GeoBounds b = mapView.getBounds();
                final boolean ready = b != null && !Double.isNaN(b.getNorth())
                        && !Double.isNaN(b.getSouth());
                if (ready) {
                    maybeFetchAirspace();
                    return;
                }
                if (++tries < 20)
                    main.postDelayed(this, 500L);
                else
                    Log.w(TAG, "the map never reported bounds; waiting for a pan");
            }
        }, 1500L);
        // The store is a file, so last session's areas are already on the map. Read them
        // back into the list before any network call, then catch up.
        worker.execute(new Runnable() {
            @Override
            public void run() {
                loadFromCache();
                if (isLayerOn(LAYER_RESTRICTIONS))
                    syncOnWorker();
            }
        });
    }

    public void dispose() {
        started = false;
        main.removeCallbacks(tick);
        main.removeCallbacks(settled);
        mapView.removeOnMapMovedListener(moved);
        // Each reinstall otherwise pins this generation's threads through its own static
        // pools, and ATAK runs out of memory after enough reloads.
        worker.shutdownNow();
        overlay.detach();
    }

    // ---- the switch ----

    /**
     * Whether the plugin is doing anything at all, which is no longer a switch of its
     * own: it is whether any layer is on.
     *
     * <p>There was a master switch above the layer rows, from before this plugin had
     * layers, and with four of them it had become a second vaguer copy of All off --
     * operator, 2026-10-08: "im confused with gui what does airaware off at top adn then
     * all on all off mean?". So a layer is the only unit, and on means it downloads and
     * it draws. That is the rule the operator set for the whole plugin originally ("if
     * you have it on it syncs at open if you have it set to off it does not"), now per
     * layer, so restrictions can stay current while airspace is off.
     */
    public boolean isOn() {
        return isAnyLayerOn();
    }

    private boolean isAnyLayerOn() {
        for (String[] layer : layers())
            if (!layersOff.contains(layer[0]))
                return true;
        return false;
    }

    // ---- the type filter ----

    public boolean isTypeOn(String rawType) {
        return !typesOff.contains(rawType);
    }

    public void setTypeOn(String rawType, boolean value) {
        if (value)
            typesOff.remove(rawType);
        else
            typesOff.add(rawType);
        prefs().edit().putStringSet(PREF_TYPES_OFF, new HashSet<>(typesOff)).apply();
        // Hidden, never deleted: a type switched off has to come back after a restart
        // with no network, which it cannot do if the features were dropped.
        overlay.setTypesOff(hiddenSets());
        changed();
    }

    /** Every type in the national list with how many carry it, for the filter's labels. */
    public List<String[]> typeCounts() {
        final List<String> order = new ArrayList<>();
        final List<Integer> counts = new ArrayList<>();
        for (Tfr t : known) {
            final int i = order.indexOf(t.type);
            if (i < 0) {
                order.add(t.type);
                counts.add(1);
            } else {
                counts.set(i, counts.get(i) + 1);
            }
        }
        final List<String[]> out = new ArrayList<>();
        for (int i = 0; i < order.size(); i++)
            out.add(new String[] { order.get(i), Integer.toString(counts.get(i)) });
        return out;
    }

    /** One station by its identifier, for a tap on its chip. */
    public Metar airfield(String icao) {
        if (icao == null)
            return null;
        for (Metar m : metars)
            if (icao.equals(m.icao))
                return m;
        return null;
    }

    // ---- one rule for the list and the map ----

    public List<Tfr> all() {
        return known;
    }

    public List<TfrWatch.Watched> staleFences() {
        return staleFences;
    }

    /** Every geofence made from a restriction, whatever state it is in. */
    public List<TfrWatch.Watched> allFences() {
        return allFences;
    }

    /** Take one of the operator's geofences off the map, on their say-so only. */
    public void removeFence(String uid) {
        if (TfrWatch.remove(mapView, uid))
            checkFences();
    }

    /**
     * Re-judge every geofence against what is known now.
     *
     * <p>Walks the map, so it runs on the clock and after a sync rather than on every
     * render of the pane. Expiry needs no network and is caught by the tick alone.
     */
    private void checkFences() {
        try {
            final List<TfrWatch.Watched> was = staleFences;
            allFences = TfrWatch.check(mapView, known, lastSuccessMs > 0 || !known.isEmpty(),
                    System.currentTimeMillis());
            final List<TfrWatch.Watched> stale = new ArrayList<>();
            for (TfrWatch.Watched w : allFences)
                if (w.state != TfrWatch.State.CURRENT)
                    stale.add(w);
            staleFences = stale;
            if (!staleFences.isEmpty() || !was.isEmpty())
                Log.d(TAG, "geofences out of date: " + staleFences.size()
                        + " of " + allFences.size());
        } catch (Exception e) {
            Log.w(TAG, "checking geofences failed", e);
        }
    }

    /**
     * Every layer the front page switches, in the order it shows them: {key, name}.
     *
     * <p>One list, because there are three places that have to agree -- the rows, All on
     * and All off -- and when the airspace layers were added the rows knew about them and
     * All off did not, so All off left two layers drawn and read as broken.
     */
    public List<String[]> layers() {
        final List<String[]> out = new ArrayList<>();
        out.add(new String[] { LAYER_RESTRICTIONS, "TFR" });
        out.add(new String[] { LAYER_NOTAMS, "NOTAMs" });
        out.add(new String[] { LAYER_AIRSPACE, "Airspace" });
        out.add(new String[] { LAYER_SUA, "Special Use" });
        out.add(new String[] { LAYER_UASFM, "UAS ceilings" });
        out.add(new String[] { LAYER_OBSTACLES, "Obstacles" });
        out.add(new String[] { LAYER_AIRFIELDS, "METARs" });
        return out;
    }

    private Set<String> allLayerKeys() {
        final Set<String> keys = new LinkedHashSet<>();
        for (String[] layer : layers())
            keys.add(layer[0]);
        return keys;
    }

    /** All on, all off. One call so the map, the prefs and the rows move together. */
    public void setAllLayersOn(boolean value) {
        if (value)
            layersOff.clear();
        else
            layersOff.addAll(allLayerKeys());
        afterLayerChange();
    }

    /**
     * What every layer switch does once the preference is written.
     *
     * <p>A layer just switched on starts its own download, because that is now the whole
     * meaning of on. Each call is cheap when there is nothing to do: the restriction sync
     * returns while one is already running, and the two box fetches return while the view
     * is still inside the box they last asked for.
     */
    private void afterLayerChange() {
        prefs().edit().putStringSet(PREF_LAYERS_OFF, new HashSet<>(layersOff)).apply();
        applyGate();
        overlay.setTypesOff(hiddenSets());
        if (isLayerOn(LAYER_RESTRICTIONS))
            syncNow();
        maybeFetchMetars();
        maybeFetchAirspace();
        changed();
    }

    /** Every obstacle group on the device with how many pass the filter, for the picker. */
    public List<String[]> obstacleGroupCounts() {
        final Map<String, Integer> counts = new LinkedHashMap<>();
        for (String g : Obstacle.GROUPS)
            counts.put(g, 0);
        for (Obstacle o : obstacles) {
            final Integer n = counts.get(o.group());
            counts.put(o.group(), n == null ? 1 : n + 1);
        }
        final List<String[]> out = new ArrayList<>();
        for (Map.Entry<String, Integer> e : counts.entrySet())
            out.add(new String[] { e.getKey(), Integer.toString(e.getValue()) });
        return out;
    }

    public boolean isLayerOn(String layer) {
        return !layersOff.contains(layer);
    }

    public void setLayerOn(String layer, boolean value) {
        if (value)
            layersOff.remove(layer);
        else
            layersOff.add(layer);
        afterLayerChange();
    }

    /**
     * Every set key the map should be hiding: the kinds switched off, plus everything
     * belonging to a layer switched off.
     *
     * <p>One place computes it, so a layer switch and a type switch cannot disagree about
     * what is on the map.
     */
    private Set<String> hiddenSets() {
        final Set<String> off = new HashSet<>(typesOff);
        if (layersOff.contains(LAYER_AIRFIELDS) || airfieldsHidden)
            off.add(LAYER_AIRFIELDS);
        if (layersOff.contains(LAYER_RESTRICTIONS))
            for (Tfr t : known)
                off.add(t.type);
        // Obstacles: by group, and the whole layer by its own zoom gate. Dense enough
        // that the gate matters more than anywhere else -- 12,542 in the two degrees
        // over Los Angeles.
        if (layersOff.contains(LAYER_UASFM) || uasfmHidden)
            off.add(UasfmFeatures.SET_KEY);
        final boolean obstaclesGone = layersOff.contains(LAYER_OBSTACLES) || obstaclesHidden;
        for (String g : Obstacle.GROUPS)
            if (obstaclesGone || obstacleGroupsOff.contains(g))
                off.add(ObstacleFeatures.SET_PREFIX + g);
        // NOTAMs: by kind, and the whole layer by its own zoom gate.
        final boolean notamsGone = layersOff.contains(LAYER_NOTAMS) || notamHidden;
        for (String k : Notam.KINDS)
            if (notamsGone || notamKindsOff.contains(k))
                off.add(NotamFeatures.SET_PREFIX + k);
        // Airspace: the kinds switched off one at a time, plus everything belonging to a
        // layer that is off or gated out by zoom.
        off.addAll(classesOff);
        // Hiding one kind's names, without hiding the kind.
        if (!airspaceLabels)
            for (Airspace a : airspaces)
                off.add(a.setKey() + TfrOverlay.LABEL_SUFFIX);
        final boolean classesGone = layersOff.contains(LAYER_AIRSPACE) || airspaceHidden;
        final boolean suaGone = layersOff.contains(LAYER_SUA) || airspaceHidden;
        for (Airspace a : airspaces) {
            final boolean isClass = a.isClass();
            if ((isClass && classesGone) || (!isClass && suaGone))
                off.add(a.setKey());
        }
        return off;
    }

    /**
     * Every airspace kind on the device with how many shelves carry it, for the pickers.
     * Keyed by set key so the label and the switch agree with what the map draws.
     */
    public List<String[]> airspaceCounts(boolean specialUse) {
        final Map<String, Integer> counts = new LinkedHashMap<>();
        for (Airspace a : airspaces) {
            if (a.isSpecialUse() != specialUse)
                continue;
            final String k = a.setKey();
            final Integer n = counts.get(k);
            counts.put(k, n == null ? 1 : n + 1);
        }
        final List<String[]> out = new ArrayList<>();
        for (Map.Entry<String, Integer> e : counts.entrySet())
            out.add(new String[] { e.getKey(), Integer.toString(e.getValue()) });
        return out;
    }

    public boolean airspace3dOn() {
        return airspace3d;
    }

    /** Changing this changes which features exist, so the store is rewritten. */
    public void setAirspace3dOn(boolean value) {
        if (airspace3d == value)
            return;
        airspace3d = value;
        prefs().edit().putBoolean(PREF_AIRSPACE_3D, value).apply();
        worker.execute(new Runnable() {
            @Override
            public void run() {
                rewriteOverlay();
                post();
            }
        });
        changed();
    }

    public boolean airspaceLabelsOn() {
        return airspaceLabels;
    }

    public void setAirspaceLabelsOn(boolean value) {
        if (airspaceLabels == value)
            return;
        airspaceLabels = value;
        prefs().edit().putBoolean(PREF_AIRSPACE_LABELS, value).apply();
        overlay.setTypesOff(hiddenSets());
        changed();
    }

    public boolean isClassOn(String setKey) {
        return !classesOff.contains(setKey);
    }

    public void setClassOn(String setKey, boolean value) {
        if (value)
            classesOff.remove(setKey);
        else
            classesOff.add(setKey);
        prefs().edit().putStringSet(PREF_CLASSES_OFF, new HashSet<>(classesOff)).apply();
        overlay.setTypesOff(hiddenSets());
        changed();
    }

    public long airspaceBarMeters() {
        return airspaceBarM;
    }

    public void setAirspaceBarMeters(long m) {
        airspaceBarM = m;
        prefs().edit().putLong(PREF_AIRSPACE_BAR_M, m).apply();
        applyGate();
        changed();
    }

    /** Every shelf on the device, whatever is switched on. */
    public List<Airspace> airspace() {
        return airspaces;
    }

    /** One shelf by the id its features carry, for a tap on the map. */
    public Airspace airspaceById(String id) {
        if (id == null)
            return null;
        for (Airspace a : airspaces)
            if (id.equals(a.id))
                return a;
        return null;
    }

    /**
     * What the operator is standing in, lowest floor first.
     *
     * <p>The question the layer exists to answer. Only shelves whose boundary contains the
     * position and whose floor and ceiling bracket the altitude count, so standing under a
     * 5,000 ft shelf does not report being in it.
     */
    public List<Airspace> airspaceAtMe() {
        final GeoPoint me = mapView.getSelfMarker() == null ? null
                : mapView.getSelfMarker().getPoint();
        if (me == null)
            return Collections.emptyList();
        final double groundFt = TfrVertical.myFeetMsl(me);
        final List<Airspace> out = new ArrayList<>();
        for (Airspace a : airspaces) {
            if (classesOff.contains(a.setKey()))
                continue;
            if (!isLayerOn(a.isClass() ? LAYER_AIRSPACE : LAYER_SUA))
                continue;
            if (!containsPoint(a, me.getLatitude(), me.getLongitude()))
                continue;
            if (a.containsAltitudeFt(groundFt, groundFt))
                out.add(a);
        }
        Collections.sort(out, new java.util.Comparator<Airspace>() {
            @Override
            public int compare(Airspace x, Airspace y) {
                return Integer.compare(floorOf(x), floorOf(y));
            }
        });
        return out;
    }

    private static int floorOf(Airspace a) {
        return a.floor.present && !a.floor.surface ? a.floor.feet : 0;
    }

    /** Ray casting against the outer rings, with holes taken back out. */
    private static boolean containsPoint(Airspace a, double lat, double lon) {
        for (Airspace.Part p : a.parts) {
            if (!TfrVertical.inside(p.outer, lat, lon))
                continue;
            boolean inHole = false;
            for (List<double[]> h : p.holes)
                if (TfrVertical.inside(h, lat, lon)) {
                    inHole = true;
                    break;
                }
            if (!inHole)
                return true;
        }
        return false;
    }

    /** True when airspace for this view has not reached the phone yet. */
    public boolean isAirspaceBusy() {
        return airspaceMissing;
    }

    /** True when the view held more shelves than one fetch will carry. */
    public boolean isAirspaceCapped() {
        return airspaceCapped;
    }


    /** Everything the type and place filters let through. The map draws exactly this. */
    public List<Tfr> shown() {
        final List<Tfr> out = new ArrayList<>();
        if (!isLayerOn(LAYER_RESTRICTIONS))
            return out;
        for (Tfr t : known)
            if (isTypeOn(t.type) && matchesWhere(t))
                out.add(t);
        return out;
    }

    // ---- where: state, FAA region or center ----

    public int whereMode() {
        return whereMode;
    }

    public Set<String> whereValues() {
        return Collections.unmodifiableSet(whereValues);
    }

    public void setWhere(int mode, Set<String> values) {
        whereMode = mode;
        whereValues.clear();
        if (values != null)
            whereValues.addAll(values);
        prefs().edit().putInt(PREF_WHERE_MODE, mode)
                .putStringSet(PREF_WHERE_VALUES, new HashSet<>(whereValues)).apply();
        pushPlaceFilter();
        changed();
    }

    /**
     * Whether a restriction passes the place filter.
     *
     * <p>A nationwide notice passes whatever is picked. It applies everywhere by
     * definition, so hiding it because the operator chose a state would be hiding
     * something that covers that state.
     */
    public boolean matchesWhere(Tfr t) {
        if (whereMode == WHERE_EVERYWHERE || whereValues.isEmpty())
            return true;
        if ("USA".equals(t.state))
            return true;
        switch (whereMode) {
            case WHERE_STATE:
                return whereValues.contains(t.state);
            case WHERE_REGION:
                final String r = Places.regionOf(t.state);
                return r != null && whereValues.contains(r);
            case WHERE_CENTER:
                return whereValues.contains(t.facility);
            default:
                return true;
        }
    }

    /** What the Where row's head reads. */
    public String whereLabel() {
        if (whereMode == WHERE_EVERYWHERE || whereValues.isEmpty())
            return "Everywhere";
        if (whereValues.size() == 1) {
            final String only = whereValues.iterator().next();
            switch (whereMode) {
                case WHERE_STATE:
                    return Places.stateName(only);
                case WHERE_CENTER:
                    return Places.centerName(only);
                default:
                    return only;
            }
        }
        final String noun = whereMode == WHERE_STATE ? "states"
                : whereMode == WHERE_REGION ? "regions" : "centers";
        return whereValues.size() + " " + noun;
    }

    /** Counts for the picker, keyed the way the chosen mode names places. */
    public Map<String, Integer> whereCounts(int mode) {
        final Map<String, Integer> out = new LinkedHashMap<>();
        for (Tfr t : known) {
            if (!isTypeOn(t.type))
                continue;
            final String key;
            switch (mode) {
                case WHERE_STATE:
                    key = t.state;
                    break;
                case WHERE_REGION:
                    key = Places.regionOf(t.state);
                    break;
                case WHERE_CENTER:
                    key = t.facility;
                    break;
                default:
                    key = null;
            }
            if (key == null || key.isEmpty())
                continue;
            final Integer n = out.get(key);
            out.put(key, n == null ? 1 : n + 1);
        }
        return out;
    }

    private void pushPlaceFilter() {
        if (whereMode == WHERE_EVERYWHERE || whereValues.isEmpty()) {
            overlay.setNotamsShown(null);
            return;
        }
        final Set<String> allow = new HashSet<>();
        for (Tfr t : known)
            if (matchesWhere(t))
                allow.add(t.notamId);
        overlay.setNotamsShown(allow);
    }

    // ---- area: how much of it the list covers ----

    public int areaMode() {
        return areaMode;
    }

    public long areaRadiusMeters() {
        return areaRadiusM;
    }

    public void setArea(int mode, long radiusM) {
        areaMode = mode;
        areaRadiusM = radiusM > 0 ? radiusM : areaRadiusM;
        prefs().edit().putInt(PREF_AREA_MODE, areaMode)
                .putLong(PREF_AREA_RADIUS_M, areaRadiusM).apply();
        changed();
    }

    /** What the list's heading calls itself, which has to match the Area actually set. */
    public String listHeading() {
        switch (areaMode) {
            case AREA_EVERYWHERE:
                return "All restrictions";
            case AREA_RADIUS:
                return "Within " + com.atakmap.android.airaware.ui.ScaleBar.gate(areaRadiusM)
                        + " of me";
            default:
                return "Restrictions in view";
        }
    }

    /** What the Area row's head reads. */
    public String areaLabel() {
        switch (areaMode) {
            case AREA_EVERYWHERE:
                return "Everything";
            case AREA_RADIUS:
                return "Within " + com.atakmap.android.airaware.ui.ScaleBar.describe(areaRadiusM)
                        + " of me";
            default:
                return "What is in view";
        }
    }

    /**
     * What is in view, which is what the list shows.
     *
     * <p>The map carries every TFR the filter allows, so panning toward a fire finds its
     * ring already drawn rather than waiting on a fetch; the list answers "what am I
     * looking at".
     */
    public List<Tfr> inView() {
        if (areaMode == AREA_EVERYWHERE)
            return shown();
        if (areaMode == AREA_RADIUS) {
            final GeoPoint me = self();
            final List<Tfr> out = new ArrayList<>();
            for (Tfr t : shown())
                if (nearest(me, t) <= areaRadiusM)
                    out.add(t);
            return out;
        }
        final GeoBounds b = mapView.getBounds();
        // The globe has no usable extent, and neither does a tilted 3D view at some
        // angles. Everything is a better answer than an empty list that reads as
        // "there are none here".
        if (b == null || Double.isNaN(b.getNorth()) || Double.isNaN(b.getSouth()))
            return shown();
        final List<Tfr> out = new ArrayList<>();
        for (Tfr t : shown())
            if (intersects(t, b))
                out.add(t);
        return out;
    }

    public int measureFrom() {
        return measureFrom;
    }

    public void setMeasureFrom(int mode) {
        measureFrom = mode;
        prefs().edit().putInt(PREF_MEASURE_FROM, mode).apply();
        changed();
    }

    /** What the row's label for that setting reads. */
    public String measureFromLabel() {
        if (measureFrom == FROM_MAP_CENTER)
            return "Map center";
        return hasFix() ? "My location" : "My location (no fix, using the map center)";
    }

    /**
     * The point every distance in the pane is measured from: the operator's own position,
     * or the middle of the map when they asked for that or have no fix.
     *
     * <p>One point for the sort, the row distances and the radius, so the list cannot be
     * ordered by one thing while it reports another.
     */
    public GeoPoint self() {
        if (measureFrom == FROM_ME) {
            final com.atakmap.android.maps.Marker m = mapView.getSelfMarker();
            if (m != null) {
                final GeoPoint p = m.getPoint();
                // 0,0 is not a fix, whatever isValid() says about it.
                if (p != null && p.isValid() && (p.getLatitude() != 0 || p.getLongitude() != 0))
                    return p;
            }
        }
        return mapView.getPoint().get();
    }

    public boolean hasFix() {
        final com.atakmap.android.maps.Marker m = mapView.getSelfMarker();
        if (m == null)
            return false;
        final GeoPoint p = m.getPoint();
        return p != null && p.isValid() && (p.getLatitude() != 0 || p.getLongitude() != 0);
    }

    static double nearest(GeoPoint from, Tfr t) {
        double best = Double.MAX_VALUE;
        for (TfrArea a : t.areas)
            for (double[] p : a.ring)
                best = Math.min(best, com.atakmap.coremap.maps.coords.GeoCalculations
                        .distanceTo(from, new GeoPoint(p[0], p[1])));
        return best;
    }

    private static boolean intersects(Tfr t, GeoBounds view) {
        for (TfrArea a : t.areas) {
            if (a.ring.isEmpty())
                continue;
            for (double[] p : a.ring)
                if (view.contains(new GeoPoint(p[0], p[1])))
                    return true;
            // A ring larger than the screen has no vertex on it -- the capital's 30 NM
            // ring at a city zoom is the everyday case -- so its box is checked too.
            double minLat = Double.MAX_VALUE, maxLat = -Double.MAX_VALUE;
            double minLon = Double.MAX_VALUE, maxLon = -Double.MAX_VALUE;
            for (double[] p : a.ring) {
                minLat = Math.min(minLat, p[0]);
                maxLat = Math.max(maxLat, p[0]);
                minLon = Math.min(minLon, p[1]);
                maxLon = Math.max(maxLon, p[1]);
            }
            if (view.getSouth() <= maxLat && view.getNorth() >= minLat
                    && view.getWest() <= maxLon && view.getEast() >= minLon)
                return true;
        }
        return false;
    }

    // ---- the zoom gate ----

    public long gateBarMeters() {
        return gateBarM;
    }

    public void setGateBarMeters(long meters) {
        gateBarM = meters;
        prefs().edit().putLong(PREF_GATE_BAR_M, meters).apply();
        applyGate();
        changed();
    }

    public long airfieldBarMeters() {
        return airfieldBarM;
    }

    public void setAirfieldBarMeters(long meters) {
        airfieldBarM = meters;
        prefs().edit().putLong(PREF_AIRFIELD_BAR_M, meters).apply();
        applyGate();
        changed();
    }

    public long labelBarMeters() {
        return labelBarM;
    }

    public boolean areLabelsHidden() {
        return labelsHidden;
    }

    public void setLabelBarMeters(long meters) {
        labelBarM = meters;
        prefs().edit().putLong(PREF_LABEL_BAR_M, meters).apply();
        applyGate();
        changed();
    }

    /** What ATAK's own scale bar reads right now, in meters. */
    public double barMeters() {
        return ScaleBar.meters(mapView);
    }

    /**
     * Hide the map past the gate.
     *
     * <p>Compared against the scale bar's own number, never a FeatureSet resolution: the
     * renderer tests that against its own draw resolution truncated to a tile level, so a
     * set gated that way goes empty at a zoom the pane still calls inside the gate, with
     * nothing on screen to say why.
     */
    private void applyGate() {
        final double bar = barMeters();
        final boolean hide = isAnyLayerOn() && gateBarM > 0 && bar > gateBarM * 1.02;
        final boolean was = gateHiding;
        gateHiding = hide;
        overlay.setVisible(isAnyLayerOn() && !hide);

        // The labels gate separately, and only matter while the areas are drawn at all.
        airfieldsHidden = airfieldBarM > 0 && bar > airfieldBarM * 1.02;
        // Airspace has its own gate and a far wider default than the airfields: a shelf
        // matters from a long way out, and a hundred miles of it is still readable.
        airspaceHidden = airspaceBarM > 0 && bar > airspaceBarM * 1.02;
        obstaclesHidden = obstacleBarM > 0 && bar > obstacleBarM * 1.02;
        uasfmHidden = uasfmBarM > 0 && bar > uasfmBarM * 1.02;
        notamHidden = notamBarM > 0 && bar > notamBarM * 1.02;
        // Crossing this changes which features exist, so it is a rewrite rather than a
        // visibility push -- and only on the crossing, never on an ordinary pan.
        final boolean gateVolumes = bar > VOLUME_BAR_M * 1.02;
        if (gateVolumes != volumesGated) {
            volumesGated = gateVolumes;
            worker.execute(new Runnable() {
                @Override
                public void run() {
                    rewriteOverlay();
                    post();
                }
            });
        }
        // Labels belong to a flat map. A name is pinned to the middle of its shape, and
        // the middle of a shape in a tilted view is usually out on the horizon -- the
        // operator's 3D screens were a row of half-clipped boxes stacked on the skyline
        // saying nothing about what was in front of them. Tilted, you identify a volume
        // by tapping it.
        final boolean tilted = Math.abs(mapView.getMapTilt()) > 2d;
        final boolean hideLabels = tilted || (labelBarM > 0 && bar > labelBarM * 1.02);
        final boolean wasLabels = labelsHidden;
        labelsHidden = hideLabels;
        overlay.setLabelsVisible(!hideLabels);
        overlay.setTypesOff(hiddenSets());

        if (was != hide || wasLabels != hideLabels)
            changed();
    }

    // ---- status ----

    public boolean isSyncing() {
        return syncing;
    }

    public boolean isGateHiding() {
        return gateHiding;
    }

    /**
     * The pinned line: how old the picture is and everything that is not being shown,
     * each reason in its own words. Two different reasons never share one sentence.
     */
    public String status() {
        final StringBuilder b = new StringBuilder();
        if (!isAnyLayerOn())
            return "Nothing is switched on. Turn a layer on to download and show it.";
        // First, because it is the question the airspace layer exists to answer and the
        // operator should not have to tap anything to get it.
        final String where = airspaceHereLine();
        if (where != null)
            b.append(where).append(' ');
        // And straight after it, what you may actually do here, which is the question a
        // drone pilot opens this for. 0 is an answer, not a gap.
        final UasfmCell cell = isLayerOn(LAYER_UASFM) ? uasfmHere() : null;
        if (cell != null) {
            b.append(cell.ceilingFt == 0
                    ? "No UAS flight here without further coordination."
                    : "You may fly to " + TfrVertical.comma(cell.ceilingFt)
                            + " ft AGL here without authorization.");
            b.append(' ');
        }
        // Everything from here to the fences belongs to the restrictions, so it is said
        // only while that layer is on. With TFR off and airspace running, the line read
        // "Not downloaded yet." -- which is true of a download the operator had not
        // asked for, and reads as the thing they were looking at being broken.
        if (isLayerOn(LAYER_RESTRICTIONS)) {
            if (syncing && !syncStatus.isEmpty())
                b.append(syncStatus).append('.');
            else if (lastError != null)
                b.append(lastError).append(lastSuccessMs > 0
                        ? ", showing what this phone saved "
                                + ago(System.currentTimeMillis() - lastSuccessMs) + "."
                        : ".");
            else if (lastSuccessMs == 0)
                b.append("Restrictions not downloaded yet.");
            else
                b.append("Updated ").append(ago(System.currentTimeMillis() - lastSuccessMs))
                        .append('.');
            if (gateHiding)
                b.append(" Zoom in to see restrictions on the map. Shown at ")
                        .append(ScaleBar.gate(gateBarM)).append(" or closer.");
            else if (labelsHidden)
                b.append(" Names appear at ").append(ScaleBar.gate(labelBarM))
                        .append(" or closer.");
        }
        // A fence guarding airspace that was lifted is worse than no fence, so this is
        // said on the pinned line rather than left to be discovered.
        final String fences = TfrWatch.line(staleFences);
        if (fences != null)
            b.append(' ').append(fences);
        // Said in what it means for the map, not in what failed: airspace is downloaded
        // once per area and kept, so the honest sentence is which part is not here yet.
        if (airspaceMissing && (isLayerOn(LAYER_AIRSPACE) || isLayerOn(LAYER_SUA)))
            b.append(airspaces.isEmpty()
                    ? " Airspace for here is not downloaded yet."
                    : " Some airspace here is not downloaded yet.");
        return b.toString();
    }

    /**
     * Which airspace the operator is standing in, in one sentence, or null when there is
     * nothing to say.
     *
     * <p>Silent rather than reassuring when nothing is known: no position, no download or
     * the layer switched off all read as "not checked", and "You are in Class G" said on
     * the strength of an empty list would be a confident wrong answer in exactly the place
     * it matters. Class G is also never named -- it is everywhere that is not something
     * else, so saying it adds nothing.
     */
    private String airspaceHereLine() {
        if (!isLayerOn(LAYER_AIRSPACE) || airspaces.isEmpty())
            return null;
        final List<Airspace> here = airspaceAtMe();
        final List<String> names = new ArrayList<>();
        for (Airspace a : here) {
            if (!a.isClass())
                continue;
            final String c = a.classCode == null ? "" : a.classCode.trim().toUpperCase(Locale.US);
            if (c.isEmpty() || "G".equals(c))
                continue;
            final String n = "Class " + c;
            if (!names.contains(n))
                names.add(n);
        }
        if (names.isEmpty())
            return null;
        if (names.size() == 1)
            return "You are in " + names.get(0) + ".";
        final StringBuilder b = new StringBuilder("You are in ");
        for (int i = 0; i < names.size(); i++) {
            if (i > 0)
                b.append(i == names.size() - 1 ? " and " : ", ");
            b.append(names.get(i));
        }
        return b.append('.').toString();
    }

    static String ago(long ms) {
        final long min = ms / 60000L;
        if (min < 1)
            return "just now";
        if (min < 60)
            return min + " min ago";
        final long hours = min / 60;
        if (hours < 24)
            return hours + (hours == 1 ? " hour ago" : " hours ago");
        final long days = hours / 24;
        return days + (days == 1 ? " day ago" : " days ago");
    }

    // ---- sync ----

    public void syncNow() {
        if (!isLayerOn(LAYER_RESTRICTIONS) || syncing)
            return;
        worker.execute(new Runnable() {
            @Override
            public void run() {
                syncOnWorker();
            }
        });
    }

    private void loadFromCache() {
        final byte[] listBody = cache.readList();
        if (listBody == null)
            return;
        try {
            final List<Tfr> rows = TfrFeed.parseList(listBody);
            for (Tfr t : rows) {
                final byte[] xml = cache.read(t.notamId);
                if (xml != null)
                    parseQuietly(xml, t);
            }
            known = Collections.unmodifiableList(rows);
            post();
        } catch (Exception e) {
            Log.w(TAG, "the cached list was not usable", e);
        }
    }

    private void syncOnWorker() {
        syncing = true;
        lastError = null;
        progress("Checking the FAA list");
        final List<Tfr> rows;
        final byte[] listBody;
        try {
            listBody = TfrFeed.listBytes();
            rows = TfrFeed.parseList(listBody);
        } catch (Exception e) {
            // The last good picture stays on the map. A network that dropped is not a
            // reason to tell an operator the airspace is clear.
            syncing = false;
            syncStatus = "";
            lastError = lastSuccessMs > 0 ? "No network" : com.atakmap.android.airaware.net.Http.describe(e);
            post();
            return;
        }
        cache.writeList(listBody);

        int done = 0;
        int failed = 0;
        for (Tfr t : rows) {
            if (!isLayerOn(LAYER_RESTRICTIONS) || Thread.currentThread().isInterrupted())
                break;
            done++;
            byte[] xml = cache.read(t.notamId);
            if (xml == null) {
                progress("Downloading " + done + " of " + rows.size());
                try {
                    xml = TfrFeed.detail(t.notamId);
                    cache.write(t.notamId, xml);
                } catch (Exception e) {
                    // One missing detail is one TFR without a shape, not a failed sync,
                    // and it is not written down as an answer: a detail that 404s today
                    // can be there tomorrow.
                    failed++;
                    continue;
                }
            }
            parseQuietly(xml, t);
        }

        // Reconcile. Anything no longer in the list is canceled or expired and comes off
        // the map and out of the cache. The list churns daily, so this is the ordinary
        // case: a canceled TFR left drawn reads as current airspace.
        final Set<String> live = new HashSet<>();
        for (Tfr t : rows)
            live.add(t.notamId);
        cache.prune(live);

        known = Collections.unmodifiableList(rows);
        lastSuccessMs = System.currentTimeMillis();
        syncing = false;
        syncStatus = "";
        lastError = failed > 0
                ? failed + (failed == 1 ? " restriction" : " restrictions") + " would not download"
                : null;
        rewriteOverlay();
        pushPlaceFilter();
        checkFences();
        post();
    }

    private void parseQuietly(byte[] xml, Tfr t) {
        try {
            TfrParser.parseDetail(xml, t);
        } catch (Exception e) {
            Log.w(TAG, "could not parse the detail for " + t.notamId, e);
        }
    }

    /** Worker thread only: this writes a database. */
    private void rewriteOverlay() {
        final long now = System.currentTimeMillis();
        final int active = color(PREF_COLOR_ACTIVE, DEFAULT_ACTIVE);
        final int upcoming = color(PREF_COLOR_UPCOMING, DEFAULT_UPCOMING);
        final List<TfrOverlay.Drawn> drawn = new ArrayList<>();
        // Every TFR is written, including the types that are switched off: off hides,
        // never deletes, so the picture survives a restart with no network whatever the
        // filter was set to when ATAK stopped.
        for (Tfr t : known)
            drawn.addAll(TfrFeatures.drawn(t, now, active, upcoming));
        // The airfield chips ride in the same rewrite: one store, one locked pass, and
        // the set they land in is switched like any other type.
        drawn.addAll(MetarFeatures.drawn(metars));
        // So do the airspace shelves, for the same reason, and like everything else they
        // are written whether their layer is on or off: off hides, never deletes.
        final boolean solid = !volumesGated;
        final boolean in3d = airspace3d;
        for (Airspace a : airspaces)
            drawn.addAll(AirspaceFeatures.drawn(a, solid, in3d));
        if (pills == null)
            pills = new ObstaclePills();
        for (Obstacle o : obstacles)
            drawn.addAll(ObstacleFeatures.drawn(o, solid, pills, pluginContext,
                    DEFAULT_OBSTACLE));
        for (UasfmCell c : uasfm)
            drawn.addAll(UasfmFeatures.drawn(c));
        for (Notam n : notams)
            drawn.addAll(NotamFeatures.drawn(n, n.isActive(now), pills, pluginContext));
        overlay.rewrite(drawn);
        overlay.setTypesOff(hiddenSets());
    }

    private void progress(final String message) {
        syncStatus = message;
        post();
    }

    private void post() {
        main.post(new Runnable() {
            @Override
            public void run() {
                if (started)
                    changed();
            }
        });
    }

    private int color(String key, int fallback) {
        try {
            final String v = atakPrefs().getString(key, null);
            if (v == null || v.trim().isEmpty())
                return fallback;
            return Color.parseColor(v.trim());
        } catch (Exception e) {
            return fallback;
        }
    }

    // ---- the clock and the map ----

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (!started)
                return;
            main.postDelayed(this, TICK_MS);
            if (!isAnyLayerOn() || syncing)
                return;
            // Never assume the timer fired. Doze defers everything and a phone can be
            // asleep for an hour, so what decides a refresh is the age of the last
            // success, not a count of ticks.
            final long interval = refreshMinutes() * 60L * 1000L;
            // Expiry is a clock, not a download: a fence can lapse with no network.
            checkFences();
            maybeRefreshNotams();
            // The airspace retry rides the clock, so a refusal clears itself without the
            // operator having to move the map to find out.
            maybeFetchAirspace();
            if (lastSuccessMs == 0 || System.currentTimeMillis() - lastSuccessMs >= interval)
                syncNow();
            else
                changed();
        }
    };

    private int refreshMinutes() {
        try {
            final String v = atakPrefs().getString(PREF_REFRESH_MIN, null);
            if (v == null)
                return DEFAULT_REFRESH_MIN;
            final int n = Integer.parseInt(v.trim());
            return n < 5 ? 5 : n;
        } catch (Exception e) {
            return DEFAULT_REFRESH_MIN;
        }
    }

    /**
     * <strong>Runs on the GL render thread.</strong> ATAK dispatches this over JNI from
     * {@code GLMapView}; touching a View or a map item here is a native SIGSEGV with no
     * Java stack trace. Post, and coalesce: during a pinch it fires every frame.
     */
    private final com.atakmap.map.AtakMapView.OnMapMovedListener moved =
            new com.atakmap.map.AtakMapView.OnMapMovedListener() {
                @Override
                public void onMapMoved(com.atakmap.map.AtakMapView v, boolean animate) {
                    main.removeCallbacks(settled);
                    main.postDelayed(settled, 400);
                }
            };

    private final Runnable settled = new Runnable() {
        @Override
        public void run() {
            if (!started)
                return;
            applyGate();
            maybeFetchMetars();
            maybeFetchAirspace();
            changed();
        }
    };

    /**
     * Fetch airfield observations for the view, but only when the view has really moved.
     *
     * <p>Unlike the restrictions, which are fetched nationally because their list carries
     * no coordinates, these are a bounding-box query -- so they follow the map. The box is
     * padded and only refetched once the view leaves it, or the report is an hour old,
     * because a pan of half a screen is not new weather.
     */
    private void maybeFetchMetars() {
        if (!isLayerOn(LAYER_AIRFIELDS))
            return;
        final GeoBounds b = mapView.getBounds();
        if (b == null || Double.isNaN(b.getNorth()) || Double.isNaN(b.getSouth()))
            return;
        final double[] box = metarBox;
        final boolean stale = System.currentTimeMillis() - metarFetchedMs > 60 * 60 * 1000L;
        if (!stale && box != null
                && b.getSouth() >= box[0] && b.getWest() >= box[1]
                && b.getNorth() <= box[2] && b.getEast() <= box[3])
            return;
        // Zooming out leaves the box at every step, and each answer rewrites the whole
        // store: one zoom out fetched five times in thirty seconds and the map redrew
        // five times with it. A floor between fetches costs nothing -- an observation is
        // an hour old anyway -- and a wider pad means fewer steps leave the box at all.
        if (System.currentTimeMillis() - metarFetchedMs < METAR_MIN_GAP_MS)
            return;
        final double padLat = Math.max(0.3, (b.getNorth() - b.getSouth()) * 0.6);
        final double padLon = Math.max(0.3, (b.getEast() - b.getWest()) * 0.6);
        final double[] want = {
                b.getSouth() - padLat, b.getWest() - padLon,
                b.getNorth() + padLat, b.getEast() + padLon
        };
        worker.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    final List<Metar> got = MetarFeed.inBox(want[0], want[1], want[2], want[3]);
                    metars = Collections.unmodifiableList(got);
                    metarBox = want;
                    metarFetchedMs = System.currentTimeMillis();
                    Log.d(TAG, "airfields: " + got.size() + " stations in view");
                    rewriteOverlay();
                    post();
                } catch (Exception e) {
                    // The restrictions are the half that matters; a failed observation
                    // fetch leaves the last good chips up and says nothing.
                    Log.w(TAG, "fetching airfield observations failed", e);
                }
            }
        });
    }

    public List<Metar> airfields() {
        return metars;
    }

    /**
     * Fetch the airspace for the view, rarely.
     *
     * <p>Nothing like the observations. Airspace changes on the FAA's 56-day publication
     * cycle, so a day-old answer is a current answer; and the service's quota -- 6,000
     * request units a minute, shared with every other caller in the world -- is spent in
     * eight queries, so a plugin that refetched on every pan would spend most of its life
     * being refused. So: a box padded by half its own width, refetched only when the view
     * leaves it or the answer is a day old, and a refusal parks the layer for a quarter of
     * an hour with what it already has still on the map.
     */
    /**
     * Make sure the airspace tiles for this view are loaded.
     *
     * <p>Nothing is fetched from the FAA any more. {@link AirspaceTiles} reads
     * two-degree tiles we publish ourselves, so there is no shared quota to spend, no
     * rate limit to back off from, and a tile read once stays on the phone -- which is
     * what the live fetch could never give a crew driving into a canyon.
     *
     * <p>The shelves held in memory are exactly the tiles the view needs, rebuilt from
     * disk when that set changes rather than accumulated: panning the country would
     * otherwise grow the list until ATAK ran out of memory, and re-reading a tile that
     * is already a file is cheap. The zoom gate keeps the set small on its own -- past a
     * hundred miles airspace is hidden anyway -- so this is only ever a handful.
     */
    private void maybeFetchAirspace() {
        // No layer check here. It used to gate the whole worker below, obstacles and UAS
        // ceilings included, so with Airspace and Special Use both off neither of the
        // others ever loaded: the operator had UAS ceilings on by themselves, the tiles
        // were already on the phone, and the map stayed empty. Each loader answers for
        // its own layer instead.
        if (!isLayerOn(LAYER_AIRSPACE) && !isLayerOn(LAYER_SUA)
                && !isLayerOn(LAYER_OBSTACLES) && !isLayerOn(LAYER_UASFM)
                && !isLayerOn(LAYER_NOTAMS))
            return;
        final GeoBounds b = mapView.getBounds();
        if (b == null || Double.isNaN(b.getNorth()) || Double.isNaN(b.getSouth()))
            return;
        final double padLat = Math.max(0.1, Math.abs(b.getNorth() - b.getSouth()) * 0.25);
        final double padLon = Math.max(0.1, Math.abs(b.getEast() - b.getWest()) * 0.25);
        final double south = b.getSouth() - padLat;
        final double west = b.getWest() - padLon;
        final double north = b.getNorth() + padLat;
        final double east = b.getEast() + padLon;
        final double viewSouth = b.getSouth();
        final double viewWest = b.getWest();
        final double viewNorth = b.getNorth();
        final double viewEast = b.getEast();
        worker.execute(new Runnable() {
            @Override
            public void run() {
                // All three, then ONE rewrite. Each used to rewrite the whole store as
                // it finished, so a pass cost three full rebuilds -- and they share this
                // single worker, so airspace finishing slowly starved the other two
                // entirely: with every layer switched on, obstacles and UAS ceilings
                // never drew at all.
                if (isLayerOn(LAYER_AIRSPACE) || isLayerOn(LAYER_SUA))
                    loadTilesOnWorker(south, west, north, east);
                loadObstaclesOnWorker(south, west, north, east,
                        viewSouth, viewWest, viewNorth, viewEast);
                loadUasfmOnWorker(south, west, north, east,
                        viewSouth, viewWest, viewNorth, viewEast);
                loadNotamsOnWorker(south, west, north, east,
                        viewSouth, viewWest, viewNorth, viewEast);
                rewriteOverlay();
                post();
            }
        });
    }

    /**
     * The obstacles for this view, from the same kind of tiles as the airspace.
     *
     * <p>Filtered here rather than at the map: a group switched off or a tower under the
     * floor is not drawn, is not in the count, and is not in the list, so the three
     * cannot disagree. The floor matters more than it sounds -- the FAA file lists every
     * obstruction study down to a 20 ft solar array, and 148 became 31 at Corona once
     * the operator's defaults were applied.
     */
    private void loadObstaclesOnWorker(double south, double west, double north,
            double east, double viewSouth, double viewWest, double viewNorth,
            double viewEast) {
        if (!isLayerOn(LAYER_OBSTACLES))
            return;
        final TilePack pack = ObstacleTiles.pack();
        final TilePack.Index index = pack.index();
        if (!index.cycle.isEmpty() && !index.cycle.equals(obstacleCycle)) {
            if (!obstacleCycle.isEmpty()) {
                Log.d(TAG, "obstacle cycle " + obstacleCycle + " -> " + index.cycle);
                pack.clear();
            }
            obstacleCycle = index.cycle;
            prefs().edit().putString(PREF_OBSTACLE_CYCLE, obstacleCycle).apply();
            obstacleKeys = Collections.emptySet();
        }
        List<String> keys = TilePack.keysFor(index, south, west, north, east);
        if (keys.size() > MAX_TILES)
            keys = keys.subList(0, MAX_TILES);
        final Set<String> want = new LinkedHashSet<>(keys);
        if (want.equals(obstacleKeys) && !obstacles.isEmpty())
            return;

        final Set<String> have = new LinkedHashSet<>(obstacleKeys);
        if (!have.containsAll(want) || obstaclesAll.isEmpty()) {
            final Set<String> load = new LinkedHashSet<>(want);
            if (have.size() + want.size() <= MAX_TILES)
                load.addAll(have);
            final List<Obstacle> loaded = new ArrayList<>();
            boolean missed = false;
            for (String key : load) {
                try {
                    for (Obstacle o : ObstacleTiles.tile(pack, key))
                        if (passesObstacleFilter(o))
                            loaded.add(o);
                } catch (Exception e) {
                    missed = true;
                    Log.d(TAG, "obstacle tile " + key + " is not here yet: " + e);
                }
            }
            obstacleKeys = load;
            obstaclesMissing = missed;
            obstaclesAll = Collections.unmodifiableList(loaded);
            obstacleBox = null;
            Log.d(TAG, "obstacles: " + loaded.size() + " held from " + load.size()
                    + " tiles");
        }
        pickObstaclesForView(viewSouth, viewWest, viewNorth, viewEast);
    }

    /**
     * Which of the held obstacles are drawn.
     *
     * <p>Separate from loading because a tile is two degrees and a view can be a few
     * hundred feet. The cap used to keep the tallest in the tile, which at a 636 ft
     * scale bar meant four hundred towers scattered across a county and not one of them
     * on screen -- the operator had the layer on, 4,407 loaded, and an empty map. What
     * the cap has to keep is what is <b>near the view</b>.
     *
     * <p>The box is padded and remembered, so panning inside it costs nothing; only
     * leaving it is a redraw. Without that, a view whose bounds jitter -- which is every
     * tilted view -- rewrote the whole store twice a second.
     */
    private void pickObstaclesForView(double south, double west, double north,
            double east) {
        final double[] box = obstacleBox;
        if (box != null && south >= box[0] && west >= box[1] && north <= box[2]
                && east <= box[3] && !obstacles.isEmpty())
            return;
        final double padLat = Math.max(0.02, (north - south) * 0.6);
        final double padLon = Math.max(0.02, (east - west) * 0.6);
        final double[] want = { south - padLat, west - padLon, north + padLat,
                east + padLon };
        final double midLat = (south + north) / 2d;
        final double midLon = (west + east) / 2d;
        final double lonScale = Math.cos(Math.toRadians(midLat));

        final List<Obstacle> inView = new ArrayList<>();
        for (Obstacle o : obstaclesAll) {
            if (o.lat < want[0] || o.lat > want[2] || o.lon < want[1] || o.lon > want[3])
                continue;
            final double dy = o.lat - midLat;
            final double dx = (o.lon - midLon) * lonScale;
            o.distanceM = Math.sqrt(dy * dy + dx * dx) * 111320d;
            inView.add(o);
        }
        // Nearest first, so the cap keeps what is in front of the operator. Height
        // breaks a tie, because at equal distance the taller one is the one that matters.
        Collections.sort(inView, new java.util.Comparator<Obstacle>() {
            @Override
            public int compare(Obstacle a, Obstacle b) {
                final int d = Double.compare(a.distanceM, b.distanceM);
                return d != 0 ? d : Double.compare(b.aglFt, a.aglFt);
            }
        });
        obstaclesCapped = inView.size() > MAX_OBSTACLES;
        obstacles = Collections.unmodifiableList(
                new ArrayList<>(inView.subList(0, Math.min(MAX_OBSTACLES, inView.size()))));
        obstacleBox = want;
        Log.d(TAG, "obstacles: drawing " + obstacles.size() + " of " + inView.size()
                + " in view");
    }

    /**
     * The UAS Facility Map for this view, held and drawn the same way the obstacles are:
     * tiles fill a list, and what is drawn is picked from it for the view.
     *
     * <p>The cap is tighter than anywhere else because the cells are tiny -- 7,210 in
     * the two degrees over New York -- and a grid square is one feature each.
     */
    private void loadUasfmOnWorker(double south, double west, double north, double east,
            double viewSouth, double viewWest, double viewNorth, double viewEast) {
        if (!isLayerOn(LAYER_UASFM))
            return;
        final TilePack pack = UasfmTiles.pack();
        final TilePack.Index index = pack.index();
        if (!index.cycle.isEmpty() && !index.cycle.equals(uasfmCycle)) {
            if (!uasfmCycle.isEmpty()) {
                Log.d(TAG, "UAS ceiling cycle " + uasfmCycle + " -> " + index.cycle);
                pack.clear();
            }
            uasfmCycle = index.cycle;
            prefs().edit().putString(PREF_UASFM_CYCLE, uasfmCycle).apply();
            uasfmKeys = Collections.emptySet();
        }
        List<String> keys = TilePack.keysFor(index, south, west, north, east);
        if (keys.size() > MAX_TILES)
            keys = keys.subList(0, MAX_TILES);
        final Set<String> want = new LinkedHashSet<>(keys);
        // Reload only when something we need is NOT already held. Reloading whenever the
        // set merely differs meant zooming in threw away cells that were already in
        // memory and read them back off disk, and every one of those ended in a full
        // store rewrite -- 1,800 features, measured at 822 ms blocking the renderer.
        // Zooming in is now free, and only reaching new ground costs anything.
        final Set<String> have = new LinkedHashSet<>(uasfmKeys);
        if (!have.containsAll(want) || uasfmAll.isEmpty()) {
            // Keep what is held and add what is missing, unless that would grow past the
            // tile ceiling -- then start again from what this view actually needs.
            final Set<String> load = new LinkedHashSet<>(want);
            if (have.size() + want.size() <= MAX_TILES)
                load.addAll(have);
            final List<UasfmCell> loaded = new ArrayList<>();
            for (String key : load) {
                try {
                    loaded.addAll(UasfmTiles.tile(pack, key));
                } catch (Exception e) {
                    Log.d(TAG, "UAS ceiling tile " + key + " is not here yet: " + e);
                }
            }
            uasfmKeys = load;
            uasfmAll = Collections.unmodifiableList(loaded);
            uasfmBox = null;
            Log.d(TAG, "UAS ceilings: " + loaded.size() + " held from " + load.size()
                    + " tiles");
        }

        final double[] box = uasfmBox;
        if (box != null && viewSouth >= box[0] && viewWest >= box[1]
                && viewNorth <= box[2] && viewEast <= box[3] && !uasfm.isEmpty())
            return;
        final double padLat = Math.max(0.01, (viewNorth - viewSouth) * 0.3);
        final double padLon = Math.max(0.01, (viewEast - viewWest) * 0.3);
        final double[] want2 = { viewSouth - padLat, viewWest - padLon,
                viewNorth + padLat, viewEast + padLon };
        final List<UasfmCell> inView = new ArrayList<>();
        for (UasfmCell c : uasfmAll) {
            if (c.north() < want2[0] || c.south() > want2[2]
                    || c.east() < want2[1] || c.west() > want2[3])
                continue;
            inView.add(c);
        }
        // Nearest the middle of the view first, then cap. Taking the first N as they
        // came out of the tiles drew solid blocks with whole neighbourhoods missing
        // between them -- the same mistake the obstacles made, and worse here because a
        // missing grid square reads as "no rule applies", which is the opposite of what
        // a gap means.
        final double midLat = (south + north) / 2d;
        final double midLon = (west + east) / 2d;
        final double lonScale = Math.cos(Math.toRadians(midLat));
        Collections.sort(inView, new java.util.Comparator<UasfmCell>() {
            @Override
            public int compare(UasfmCell x, UasfmCell y) {
                return Double.compare(distanceSq(x), distanceSq(y));
            }

            private double distanceSq(UasfmCell c) {
                final double dy = (c.south() + c.north()) / 2d - midLat;
                final double dx = ((c.west() + c.east()) / 2d - midLon) * lonScale;
                return dy * dy + dx * dx;
            }
        });
        uasfmCapped = inView.size() > MAX_UASFM_CELLS;
        final List<UasfmCell> kept = new ArrayList<>(
                inView.subList(0, Math.min(MAX_UASFM_CELLS, inView.size())));
        uasfm = Collections.unmodifiableList(joinRuns(kept));
        uasfmBox = want2;
        Log.d(TAG, "UAS ceilings: drawing " + uasfm.size());
    }

    /**
     * Join neighbouring squares that give the same answer into runs.
     *
     * <p>The grid comes in large uniform blocks, so this is most of them: a band forty
     * cells wide becomes one shape. The store rewrite is what made zooming stutter and
     * it costs per feature, so this is the difference between redrawing 1,500 things and
     * redrawing a couple of hundred. A run says exactly what its cells said; the only
     * thing lost is the lines between them, which were never a rule.
     */
    private static List<UasfmCell> joinRuns(List<UasfmCell> cells) {
        final List<UasfmCell> sorted = new ArrayList<>(cells);
        Collections.sort(sorted, new java.util.Comparator<UasfmCell>() {
            @Override
            public int compare(UasfmCell a, UasfmCell b) {
                if (a.latIndex != b.latIndex)
                    return Integer.compare(a.latIndex, b.latIndex);
                return Integer.compare(a.lonIndex, b.lonIndex);
            }
        });
        final List<UasfmCell> runs = new ArrayList<>();
        UasfmCell run = null;
        for (UasfmCell c : sorted) {
            if (run != null && run.joins(c)) {
                run.span++;
                continue;
            }
            run = new UasfmCell(c.latIndex, c.lonIndex, c.ceilingFt, c.airportId,
                    c.airportName, c.laanc);
            runs.add(run);
        }
        return runs;
    }

    /** The cell the operator is standing in, or null. */
    public UasfmCell uasfmHere() {
        if (mapView.getSelfMarker() == null)
            return null;
        final GeoPoint me = mapView.getSelfMarker().getPoint();
        if (me == null)
            return null;
        // uasfmAll holds single cells, never runs, so this is an exact answer.
        for (UasfmCell c : uasfmAll)
            if (c.contains(me.getLatitude(), me.getLongitude()))
                return c;
        return null;
    }

    /** One cell by the id its feature carries, for a tap on the map. */
    public UasfmCell uasfmById(String id) {
        if (id == null)
            return null;
        for (UasfmCell c : uasfmAll)
            if (id.equals(c.latIndex + "_" + c.lonIndex))
                return c;
        return null;
    }

    public List<UasfmCell> uasfmDrawn() {
        return uasfm;
    }

    public boolean isUasfmCapped() {
        return uasfmCapped;
    }

    public long uasfmBarMeters() {
        return uasfmBarM;
    }

    public void setUasfmBarMeters(long m) {
        uasfmBarM = m;
        prefs().edit().putLong(PREF_UASFM_BAR_M, m).apply();
        applyGate();
        changed();
    }

    private boolean passesObstacleFilter(Obstacle o) {
        if (o.aglFt < obstacleFloorFt)
            return false;
        return !obstacleGroupsOff.contains(o.group());
    }

    public List<Obstacle> obstacles() {
        return obstacles;
    }

    public Obstacle obstacleByOas(String oas) {
        if (oas == null)
            return null;
        for (Obstacle o : obstacles)
            if (oas.equals(o.oas))
                return o;
        return null;
    }

    public boolean isObstaclesCapped() {
        return obstaclesCapped;
    }

    public boolean isObstaclesMissing() {
        return obstaclesMissing;
    }

    public double obstacleFloorFt() {
        return obstacleFloorFt;
    }

    public void setObstacleFloorFt(double ft) {
        obstacleFloorFt = ft;
        prefs().edit().putFloat(PREF_OBSTACLE_FLOOR_FT, (float) ft).apply();
        refetchObstacles();
    }

    public boolean isObstacleGroupOn(String group) {
        return !obstacleGroupsOff.contains(group);
    }

    public void setObstacleGroupOn(String group, boolean on) {
        if (on)
            obstacleGroupsOff.remove(group);
        else
            obstacleGroupsOff.add(group);
        prefs().edit().putStringSet(PREF_OBSTACLE_GROUPS_OFF,
                new HashSet<>(obstacleGroupsOff)).apply();
        refetchObstacles();
    }

    public long obstacleBarMeters() {
        return obstacleBarM;
    }

    public void setObstacleBarMeters(long m) {
        obstacleBarM = m;
        prefs().edit().putLong(PREF_OBSTACLE_BAR_M, m).apply();
        applyGate();
        changed();
    }

    /** The filter decides what is loaded, so changing it reloads from the tiles on disk. */
    private void refetchObstacles() {
        obstacleKeys = Collections.emptySet();
        obstacleBox = null;
        changed();
        main.removeCallbacks(settled);
        main.postDelayed(settled, 50);
    }

    /** Worker thread only: this reads and writes files. */
    private void loadTilesOnWorker(double south, double west, double north, double east) {
        final TilePack pack = AirspaceTiles.pack();
        final TilePack.Index index = pack.index();
        // A new publication cycle makes every tile on the phone the wrong cycle, and the
        // two would otherwise sit side by side with no way to tell them apart.
        if (!index.cycle.isEmpty() && !index.cycle.equals(airspaceCycle)) {
            if (!airspaceCycle.isEmpty()) {
                Log.d(TAG, "airspace cycle " + airspaceCycle + " -> " + index.cycle);
                pack.clear();
            }
            airspaceCycle = index.cycle;
            prefs().edit().putString(PREF_AIRSPACE_CYCLE, airspaceCycle).apply();
            airspaceKeys = Collections.emptySet();
        }

        List<String> keys = TilePack.keysFor(index, south, west, north, east);
        final boolean capped = keys.size() > MAX_TILES;
        if (capped)
            keys = keys.subList(0, MAX_TILES);
        final Set<String> want = new LinkedHashSet<>(keys);
        // Only when something needed is missing. Comparing for equality meant every
        // zoom step reloaded tiles already in memory -- the log showed 8 tiles, then 4,
        // then 2, each with a full store rewrite behind it.
        final Set<String> have = new LinkedHashSet<>(airspaceKeys);
        if (have.containsAll(want) && !airspaces.isEmpty())
            return;
        if (have.size() + want.size() <= MAX_TILES)
            want.addAll(have);

        // Keyed by id: a shelf that straddles a tile boundary is written into both, and
        // the publisher says so.
        final Map<String, Airspace> merged = new LinkedHashMap<>();
        boolean missed = false;
        try {
            for (Airspace a : AirspaceTiles.wide(pack))
                merged.put(a.id, a);
        } catch (Exception e) {
            missed = true;
            Log.d(TAG, "the wide shapes are not here yet: " + e);
        }
        for (String key : want) {
            try {
                for (Airspace a : AirspaceTiles.tile(pack, key))
                    merged.put(a.id, a);
            } catch (Exception e) {
                // One tile short is a thin picture, not an empty one.
                missed = true;
                Log.d(TAG, "tile " + key + " is not here yet: " + e);
            }
        }

        airspaceKeys = want;
        airspaceCapped = capped;
        airspaceMissing = missed;
        airspaces = Collections.unmodifiableList(new ArrayList<>(merged.values()));
        airspaceFetchedMs = System.currentTimeMillis();
        Log.d(TAG, "airspace: " + airspaces.size() + " shelves from " + want.size()
                + " tiles" + (missed ? " (some not downloaded)" : ""));
    }

    private void changed() {
        if (listener != null)
            listener.onChanged();
    }

    private SharedPreferences prefs() {
        return mapView.getContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private SharedPreferences atakPrefs() {
        return PreferenceManager.getDefaultSharedPreferences(mapView.getContext());
    }

    public String describeCacheForDisplay() {
        final double mb = cache.bytesOnDisk() / (1024.0 * 1024.0);
        if (mb < 0.05)
            return "Nothing downloaded yet.";
        return String.format(Locale.US, "%.1f MB saved on this phone.", mb);
    }

    public int refreshMinutesForDisplay() {
        return refreshMinutes();
    }

    public void setRefreshMinutes(int minutes) {
        atakPrefs().edit().putString(PREF_REFRESH_MIN, Integer.toString(minutes)).apply();
    }

    /** The map's own colors, so the map key cannot drift from what is drawn. */
    /** What an obstacle draws in, so the map key cannot drift from the map. */
    public int obstacleColor() {
        return DEFAULT_OBSTACLE;
    }

    public int activeColor() {
        return color(PREF_COLOR_ACTIVE, DEFAULT_ACTIVE);
    }

    public int upcomingColor() {
        return color(PREF_COLOR_UPCOMING, DEFAULT_UPCOMING);
    }

    /** One TFR by its NOTAM number, for the radial's details button. */
    public Tfr byNotam(String notamId) {
        if (notamId == null)
            return null;
        for (Tfr t : known)
            if (notamId.equals(t.notamId))
                return t;
        return null;
    }

    // ---- NOTAMs ----

    /**
     * The NOTAMs for this view, from the relay's tiles.
     *
     * <p>Held and drawn the way the obstacles are, with one difference: the pack changes
     * every few minutes, so a new cycle does not clear anything. The manifest's hash per
     * tile decides whether the copy on disk is current, a changed tile is fetched again
     * as the view needs it, and with no network the last picture stays up.
     */
    private void loadNotamsOnWorker(double south, double west, double north, double east,
            double viewSouth, double viewWest, double viewNorth, double viewEast) {
        if (!isLayerOn(LAYER_NOTAMS))
            return;
        final TilePack pack = NotamTiles.pack();
        final TilePack.Index index = pack.index();
        if (!index.built.isEmpty())
            notamBuilt = index.built;
        final boolean newCycle = !index.cycle.isEmpty() && !index.cycle.equals(notamCycle);
        if (newCycle) {
            if (!notamCycle.isEmpty())
                Log.d(TAG, "NOTAM cycle " + notamCycle + " -> " + index.cycle);
            notamCycle = index.cycle;
            pack.prune(index);
        }
        List<String> keys = TilePack.keysFor(index, south, west, north, east);
        if (keys.size() > MAX_TILES)
            keys = keys.subList(0, MAX_TILES);
        final Set<String> want = new LinkedHashSet<>(keys);
        final Set<String> have = new LinkedHashSet<>(notamKeys);
        if (newCycle || !have.containsAll(want) || notamsAll.isEmpty()) {
            final Set<String> load = new LinkedHashSet<>(want);
            if (have.size() + want.size() <= MAX_TILES)
                load.addAll(have);
            final List<Notam> loaded = new ArrayList<>();
            boolean missed = false;
            for (String key : load) {
                try {
                    loaded.addAll(NotamTiles.tile(pack, key, index.sha.get(key)));
                } catch (Exception e) {
                    missed = true;
                    Log.d(TAG, "NOTAM tile " + key + " is not here yet: " + e);
                }
            }
            notamKeys = load;
            notamsMissing = missed;
            notamsAll = Collections.unmodifiableList(loaded);
            notamBox = null;
            notamLoadedAt = System.currentTimeMillis();
            Log.d(TAG, "NOTAMs: " + loaded.size() + " held from " + load.size() + " tiles");
        }
        pickNotamsForView(viewSouth, viewWest, viewNorth, viewEast);
    }

    /**
     * Which of the held NOTAMs are drawn: the kinds switched on, not yet ended, nearest
     * the middle of the view first, capped. The kind filter is applied here and not when
     * the tiles are read, so switching a kind costs a pick and not a re-read.
     */
    private void pickNotamsForView(double south, double west, double north, double east) {
        final double[] box = notamBox;
        if (box != null && south >= box[0] && west >= box[1] && north <= box[2]
                && east <= box[3] && !notams.isEmpty())
            return;
        final double padLat = Math.max(0.02, (north - south) * 0.6);
        final double padLon = Math.max(0.02, (east - west) * 0.6);
        final double[] want = { south - padLat, west - padLon, north + padLat,
                east + padLon };
        final double midLat = (south + north) / 2d;
        final double midLon = (west + east) / 2d;
        final double lonScale = Math.cos(Math.toRadians(midLat));
        final long now = System.currentTimeMillis();
        final Map<String, Integer> counts = new LinkedHashMap<>();
        for (String k : Notam.KINDS)
            counts.put(k, 0);
        final List<Notam> inView = new ArrayList<>();
        for (Notam n : notamsAll) {
            if (n.lat < want[0] || n.lat > want[2] || n.lon < want[1] || n.lon > want[3])
                continue;
            if (n.isExpired(now))
                continue;
            final Integer c = counts.get(n.kind);
            counts.put(n.kind, c == null ? 1 : c + 1);
            if (notamKindsOff.contains(n.kind))
                continue;
            final double dy = n.lat - midLat;
            final double dx = (n.lon - midLon) * lonScale;
            n.distanceM = Math.sqrt(dy * dy + dx * dx) * 111320d;
            inView.add(n);
        }
        Collections.sort(inView, new java.util.Comparator<Notam>() {
            @Override
            public int compare(Notam a, Notam b) {
                return Double.compare(a.distanceM, b.distanceM);
            }
        });
        notamCapped = inView.size() > MAX_NOTAMS;
        notams = Collections.unmodifiableList(
                new ArrayList<>(inView.subList(0, Math.min(MAX_NOTAMS, inView.size()))));
        notamCounts = Collections.unmodifiableMap(counts);
        notamBox = want;
        Log.d(TAG, "NOTAMs: drawing " + notams.size() + " of " + inView.size() + " in view");
    }

    /**
     * Rides the clock: while the layer is on, the relay's newer picture is read every
     * few minutes whether the map moved or not. A phone parked at an incident must see
     * a NOTAM issued after it stopped panning.
     */
    private void maybeRefreshNotams() {
        if (!isLayerOn(LAYER_NOTAMS))
            return;
        if (System.currentTimeMillis() - notamLoadedAt < NotamTiles.MANIFEST_AGE_MS)
            return;
        notamKeys = Collections.emptySet();
        notamBox = null;
        maybeFetchAirspace();
    }

    private void refetchNotams() {
        notamBox = null;
        changed();
        main.removeCallbacks(settled);
        main.postDelayed(settled, 50);
    }

    public List<Notam> notams() {
        return notams;
    }

    public boolean isNotamsCapped() {
        return notamCapped;
    }

    public boolean isNotamsMissing() {
        return notamsMissing;
    }

    public Notam notamById(String id) {
        if (id == null)
            return null;
        for (Notam n : notamsAll)
            if (id.equals(n.id))
                return n;
        return null;
    }

    /** Every kind with how many are near the view, on or off, for the picker. */
    public List<String[]> notamKindCounts() {
        final List<String[]> out = new ArrayList<>();
        final Map<String, Integer> counts = notamCounts;
        for (String k : Notam.KINDS) {
            final Integer n = counts.get(k);
            out.add(new String[] { k, Integer.toString(n == null ? 0 : n) });
        }
        return out;
    }

    public boolean isNotamKindOn(String kind) {
        return !notamKindsOff.contains(kind);
    }

    public void setNotamKindOn(String kind, boolean on) {
        if (on)
            notamKindsOff.remove(kind);
        else
            notamKindsOff.add(kind);
        prefs().edit().putStringSet(PREF_NOTAM_KINDS_OFF, new HashSet<>(notamKindsOff)).apply();
        overlay.setTypesOff(hiddenSets());
        refetchNotams();
    }

    public long notamBarMeters() {
        return notamBarM;
    }

    public void setNotamBarMeters(long m) {
        notamBarM = m;
        prefs().edit().putLong(PREF_NOTAM_BAR_M, m).apply();
        applyGate();
        changed();
    }

    /** When the relay built the picture on this phone, in millis; 0 when unknown. */
    public long notamBuiltMs() {
        return Notam.parseTime(notamBuilt);
    }
}
