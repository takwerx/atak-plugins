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
    static final String PREF_AIRSPACE_BAR_M = "airspaceBarM";
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
    /** The airspace quota refills by the minute, so the first retry is soon. */
    private static final long FIRST_RETRY_MS = 20 * 1000L;
    private static final long MAX_RETRY_MS = 15 * 60 * 1000L;
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
    private volatile double[] airspaceBox;
    private volatile long airspaceFetchedMs;
    /** Set while the service has refused us, so the status line can say why nothing moved. */
    private volatile long airspaceBusyUntilMs;
    /**
     * How long to wait after a refusal, doubling each time up to a quarter of an hour.
     *
     * <p>It was a flat fifteen minutes, and on the first ever fetch that is a quarter of
     * an hour of empty map and a message about somebody else's quota -- which is how the
     * operator met the layer. The quota refills every minute, so the first retry should
     * be soon and only a service that keeps saying no earns a long wait.
     */
    private volatile long airspaceRetryMs = FIRST_RETRY_MS;
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
        classesOff.addAll(p.getStringSet(PREF_CLASSES_OFF, DEFAULT_CLASSES_OFF));
        // A set, not a joined string: a type carrying the separator would come back as two
        // bogus entries and the filter would restore wrong.
        whereMode = p.getInt(PREF_WHERE_MODE, WHERE_EVERYWHERE);
        whereValues.addAll(p.getStringSet(PREF_WHERE_VALUES, Collections.<String> emptySet()));
        areaMode = p.getInt(PREF_AREA_MODE, AREA_IN_VIEW);
        areaRadiusM = p.getLong(PREF_AREA_RADIUS_M, 80467L);
        measureFrom = p.getInt(PREF_MEASURE_FROM, FROM_ME);
        layersOff.addAll(p.getStringSet(PREF_LAYERS_OFF, allLayerKeys()));
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
        } catch (Exception e) {
            Log.w(TAG, "attaching the overlay failed", e);
        }
        mapView.addOnMapMovedListener(moved);
        main.postDelayed(tick, TICK_MS);
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
        main.removeCallbacks(airspaceRetry);
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
            staleFences = TfrWatch.check(mapView, known, lastSuccessMs > 0 || !known.isEmpty(),
                    System.currentTimeMillis());
            if (!staleFences.isEmpty() || !was.isEmpty())
                Log.d(TAG, "geofences out of date: " + staleFences.size());
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
        out.add(new String[] { LAYER_AIRSPACE, "Airspace" });
        out.add(new String[] { LAYER_SUA, "Special Use" });
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
        // Airspace: the kinds switched off one at a time, plus everything belonging to a
        // layer that is off or gated out by zoom.
        off.addAll(classesOff);
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

    /** Whether the airspace service has turned us away, and we are waiting it out. */
    public boolean isAirspaceBusy() {
        return System.currentTimeMillis() < airspaceBusyUntilMs;
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
        final boolean hideLabels = labelBarM > 0 && bar > labelBarM * 1.02;
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
        if (isAirspaceBusy())
            b.append(airspaces.isEmpty()
                    ? " The FAA airspace service is busy. Trying again in "
                            + Math.max(1, (airspaceBusyUntilMs - System.currentTimeMillis())
                                    / 1000L) + "s."
                    : " The FAA airspace service is busy; airspace is as last downloaded.");
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
        for (Airspace a : airspaces)
            drawn.addAll(AirspaceFeatures.drawn(a));
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

    private final Runnable airspaceRetry = new Runnable() {
        @Override
        public void run() {
            if (started)
                maybeFetchAirspace();
        }
    };

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
    private void maybeFetchAirspace() {
        if (isAirspaceBusy())
            return;
        if (!isLayerOn(LAYER_AIRSPACE) && !isLayerOn(LAYER_SUA))
            return;
        final GeoBounds b = mapView.getBounds();
        if (b == null || Double.isNaN(b.getNorth()) || Double.isNaN(b.getSouth()))
            return;
        final double[] box = airspaceBox;
        final boolean stale = System.currentTimeMillis() - airspaceFetchedMs > 24 * 60 * 60 * 1000L;
        if (!stale && box != null
                && b.getSouth() >= box[0] && b.getWest() >= box[1]
                && b.getNorth() <= box[2] && b.getEast() <= box[3])
            return;
        final double span = Math.abs(b.getNorth() - b.getSouth());
        final double padLat = Math.max(0.3, span * 0.5);
        final double padLon = Math.max(0.3, Math.abs(b.getEast() - b.getWest()) * 0.5);
        final double[] want = {
                b.getSouth() - padLat, b.getWest() - padLon,
                b.getNorth() + padLat, b.getEast() + padLon
        };
        worker.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    final List<Airspace> got = AirspaceFeed.inBox(want[0], want[1], want[2],
                            want[3], want[2] - want[0]);
                    airspaces = Collections.unmodifiableList(got);
                    airspaceBox = want;
                    airspaceFetchedMs = System.currentTimeMillis();
                    airspaceCapped = got.size() >= 4000;
                    airspaceRetryMs = FIRST_RETRY_MS;
                    Log.d(TAG, "airspace: " + got.size() + " shelves in view");
                    rewriteOverlay();
                    post();
                } catch (AirspaceFeed.RateLimited e) {
                    // Shared quota, not our bug and not the operator's. Keep the last good
                    // picture, wait, and try again: the clock retries without needing the
                    // map to move.
                    final long wait = airspaceRetryMs;
                    airspaceBusyUntilMs = System.currentTimeMillis() + wait;
                    Log.d(TAG, "airspace service busy, retrying in " + (wait / 1000L) + "s");
                    airspaceRetryMs = Math.min(MAX_RETRY_MS, airspaceRetryMs * 2);
                    // Retry when the status line says we will, rather than waiting for
                    // the minute clock or for the operator to move the map.
                    main.removeCallbacks(airspaceRetry);
                    main.postDelayed(airspaceRetry, wait + 250L);
                    post();
                } catch (Exception e) {
                    // A failed fetch never empties anything.
                    Log.w(TAG, "fetching airspace failed", e);
                }
            }
        });
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
}
