package com.atakmap.android.lces.lessons;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.preference.PreferenceManager;

import com.atakmap.android.ipc.AtakBroadcast;
import com.atakmap.android.maps.MapItem;
import com.atakmap.android.maps.MapView;
import com.atakmap.android.maps.Marker;
import com.atakmap.android.menu.MapMenuEventListener;
import com.atakmap.android.menu.MapMenuReceiver;
import com.atakmap.coremap.log.Log;
import com.atakmap.coremap.maps.coords.GeoPoint;
import com.atakmap.map.AtakMapView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

/**
 * The Lessons Learned layer's owner, for the plugin's whole life: the catalog, the
 * map layer, the one rule both the list and the map follow, the map listener, the
 * timer and the worker.
 *
 * <p><b>One rule.</b> {@link #kept} decides what is shown; one pass on the worker
 * produces the map and the list from it, and the pane only shows what it is handed.
 * The settings are read from prefs at each pass, never copied.
 *
 * <p><b>Survives a restart with no network.</b> The catalog is in the APK, and a
 * newer download is on disk; the store is rebuilt from it at every start, with the
 * switch's visibility, so the layer, the list and every details page come back with
 * no network whether the layer was on or off.
 */
public final class LessonsManager {

    private static final String TAG = "LCES-Lessons";

    public static final String PREF_ON = "lces.lessonsOn";
    /** Kinds switched off, by {@link Incident.Type#name()}, comma separated. */
    public static final String PREF_HIDDEN = "lces.lessons.hidden";
    /**
     * LLC categories switched off, one per line. Off, not on, is stored so a category
     * a later catalog brings in starts shown.
     */
    public static final String PREF_HIDDEN_CATS = "lces.lessons.hiddenCategories";
    /** The Area before the operator picks one: 50 miles, or 50 km on metric. */
    private static final int DEFAULT_AREA = 50;
    /** Area radius in meters; -1 for everything. */
    public static final String PREF_AREA_M = "lces.lessons.areaM";
    /** Measure from the map center instead of My Location. */
    public static final String PREF_FROM_CENTER = "lces.lessons.fromCenter";
    /**
     * Zoom gate: the map draws lessons only while ATAK's scale bar reads this many
     * meters or less (plugin-ui section 3). Missing or -1 is Always.
     */
    public static final String PREF_GATE_M = "lces.lessons.gateBarM";

    /** The list shows this many, nearest first, and says so when there are more. */
    public static final int LIST_CAP = 100;
    private static final long TICK_MS = 30_000;
    /** Resolution to fly to on Go to, in meters per pixel. */
    private static final double GOTO_RESOLUTION = 10;

    /** What one pass produced, for the pane. Immutable. */
    public static final class Result {
        /** Nearest first, at most {@link #LIST_CAP}. */
        public final List<Near> nearest;
        /** How many the rule kept, all of which are on the map. */
        public final int kept;
        /** Every incident in the catalog, per kind: the counts on the Types filter. */
        public final Map<Incident.Type, Integer> counts;
        /** Every incident in the catalog, per LLC category, largest first. */
        public final Map<String, Integer> categoryCounts;
        public final boolean fromCenter;
        /** Measuring from the map center because there is no GPS fix. */
        public final boolean noFix;
        public final String sourceEdited;
        public final boolean ready;
        /** Where distances are measured from, or null when nowhere is known yet. */
        public final GeoPoint origin;

        Result(List<Near> nearest, int kept, Map<Incident.Type, Integer> counts,
                Map<String, Integer> categoryCounts, boolean fromCenter, boolean noFix, String sourceEdited, boolean ready,
                GeoPoint origin) {
            this.origin = origin;
            this.nearest = nearest;
            this.kept = kept;
            this.counts = counts;
            this.categoryCounts = categoryCounts;
            this.fromCenter = fromCenter;
            this.noFix = noFix;
            this.sourceEdited = sourceEdited;
            this.ready = ready;
        }
    }

    /** An incident and how far it is from where the list measures. */
    public static final class Near {
        public final Incident incident;
        public final double meters;

        Near(Incident incident, double meters) {
            this.incident = incident;
            this.meters = meters;
        }
    }

    public interface Listener {
        /** Main thread. A new list and status. */
        void onLessons(Result r);

        /** Main thread. An incident was tapped on the map. */
        void onTapped(Incident i);
    }

    private final MapView mapView;
    private final SharedPreferences prefs;
    private final LessonsCatalog catalog;
    private final LessonsOverlay overlay;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor(new ThreadFactory() {
        @Override
        public Thread newThread(Runnable r) {
            final Thread t = new Thread(r, "lces-lessons");
            t.setDaemon(true);
            return t;
        }
    });

    private volatile LessonsCatalog.Data data;
    private volatile Map<String, Incident> byId = Collections.emptyMap();
    private volatile Result last;
    private Listener listener;
    private MapMenuEventListener tapListener;
    /** What the map holds now, so a pass that keeps the same set does not rewrite it. */
    private String drawnKey;
    /**
     * While a search is typed in the pane: the ids of the incidents it found, which
     * are then all the map shows. Null when there is no search.
     */
    private volatile Set<String> searchIds;
    private boolean stopped;
    /** Zoomed out past the zoom gate at the last settled move. Main thread. */
    private boolean gatedOut;

    private final AtakMapView.OnMapMovedListener moved = new AtakMapView.OnMapMovedListener() {
        @Override
        public void onMapMoved(AtakMapView v, boolean animate) {
            // GL thread: touch nothing else.
            main.removeCallbacks(settled);
            main.postDelayed(settled, 400);
        }
    };

    private final Runnable settled = new Runnable() {
        @Override
        public void run() {
            checkGate(false);
            // Only a list measured from the map center follows the map.
            if (measuringFromCenter())
                rebuild();
        }
    };

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            // My Location moves without the map moving.
            if (!measuringFromCenter())
                rebuild();
            main.postDelayed(this, TICK_MS);
        }
    };

    public LessonsManager(MapView mapView, Context pluginContext) {
        this.mapView = mapView;
        this.prefs = PreferenceManager.getDefaultSharedPreferences(mapView.getContext());
        this.catalog = new LessonsCatalog(pluginContext);
        this.overlay = new LessonsOverlay(mapView, pluginContext, isOn());
    }

    /** Main thread, from the plugin's start. Nothing slow here: it all goes to the worker. */
    public void start() {
        mapView.addOnMapMovedListener(moved);
        registerTap();
        main.postDelayed(tick, TICK_MS);
        worker.execute(new Runnable() {
            @Override
            public void run() {
                final long t0 = System.currentTimeMillis();
                final LessonsCatalog.Data d = catalog.load();
                if (d != null)
                    take(d);
                Log.d(TAG, "catalog: " + (d == null ? "none" : d.incidents.size() + " incidents, "
                        + d.origin + ", built " + d.built) + " in "
                        + (System.currentTimeMillis() - t0) + " ms");
                overlay.attach();
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        checkGate(true);
                        rebuild();
                    }
                });
            }
        });
        worker.execute(new Runnable() {
            @Override
            public void run() {
                refreshNow(false);
            }
        });
    }

    /** Main thread, from the plugin's stop. Takes the layer off the map; forgets nothing. */
    public void stop() {
        stopped = true;
        main.removeCallbacksAndMessages(null);
        mapView.removeOnMapMovedListener(moved);
        if (tapListener != null) {
            try {
                final MapMenuReceiver menus = MapMenuReceiver.getInstance();
                if (menus != null)
                    menus.removeEventListener(tapListener);
            } catch (LinkageError | RuntimeException ignored) {
                // The receiver went with ATAK.
            }
            tapListener = null;
        }
        overlay.detach();
        worker.shutdownNow();
        listener = null;
    }

    public void setListener(Listener l) {
        listener = l;
        final Result r = last;
        if (l != null && r != null)
            l.onLessons(r);
    }

    public Result last() {
        return last;
    }

    public Incident get(String id) {
        return id == null ? null : byId.get(id);
    }

    // ---- the switch and the settings ------------------------------------------------

    public boolean isOn() {
        return prefs.getBoolean(PREF_ON, false);
    }

    public void setOn(final boolean on) {
        prefs.edit().putBoolean(PREF_ON, on).apply();
        if (!applyVisible())
            return;
        rebuild();
    }

    // ---- the zoom gate --------------------------------------------------------------

    /** The gate as the scale bar distance in meters, or -1 for Always. */
    public double gateMeters() {
        return prefs.getFloat(PREF_GATE_M, -1f);
    }

    public void setGate(double barMeters) {
        prefs.edit().putFloat(PREF_GATE_M, (float) (barMeters > 0 ? barMeters : -1)).apply();
        checkGate(true);
    }

    /** True when the switch is on and the map is zoomed out past the gate. Main thread. */
    public boolean zoomedOutPastGate() {
        return isOn() && gatedOut;
    }

    private boolean pastGate() {
        final double g = gateMeters();
        // Two percent of slack, so a gate taken with "Use this zoom" holds at that zoom.
        return g > 0 && com.atakmap.android.lces.ui.ScaleBar.meters(mapView) > g * 1.02;
    }

    /** Main thread. Shows or hides the layer when the gate's answer changed, or always. */
    private void checkGate(boolean force) {
        if (stopped)
            return;
        final boolean past = pastGate();
        if (!force && past == gatedOut)
            return;
        gatedOut = past;
        applyVisible();
        final Listener l = listener;
        final Result r = last;
        if (l != null && r != null)
            l.onLessons(r);
    }

    /** The one place the layer is shown or hidden: the switch and the gate together. */
    private boolean applyVisible() {
        final boolean visible = isOn() && !gatedOut;
        try {
            worker.execute(new Runnable() {
                @Override
                public void run() {
                    overlay.setVisible(visible);
                }
            });
            return true;
        } catch (java.util.concurrent.RejectedExecutionException stoppedAlready) {
            return false;
        }
    }

    public Set<Incident.Type> shownTypes() {
        final EnumSet<Incident.Type> on = EnumSet.allOf(Incident.Type.class);
        for (String s : prefs.getString(PREF_HIDDEN, "").split(",")) {
            for (Incident.Type t : Incident.Type.values())
                if (t.name().equals(s.trim()))
                    on.remove(t);
        }
        return on;
    }

    public void setTypeShown(Incident.Type t, boolean shown) {
        final Set<Incident.Type> on = shownTypes();
        if (shown)
            on.add(t);
        else
            on.remove(t);
        final StringBuilder b = new StringBuilder();
        for (Incident.Type x : Incident.Type.values()) {
            if (!on.contains(x)) {
                if (b.length() > 0)
                    b.append(',');
                b.append(x.name());
            }
        }
        prefs.edit().putString(PREF_HIDDEN, b.toString()).apply();
        rebuild();
    }

    /** The LLC categories switched off. */
    public Set<String> hiddenCategories() {
        final Set<String> off = new HashSet<>();
        for (String s : prefs.getString(PREF_HIDDEN_CATS, "").split("\n")) {
            if (!s.isEmpty())
                off.add(s);
        }
        return off;
    }

    public void setCategoryShown(String category, boolean shown) {
        final Set<String> off = hiddenCategories();
        if (shown)
            off.remove(category);
        else
            off.add(category);
        setHiddenCategories(off);
    }

    /** Replaces the whole set: All passes an empty set, None every category. */
    public void setHiddenCategories(Set<String> off) {
        final List<String> sorted = new ArrayList<>(off);
        Collections.sort(sorted);
        final StringBuilder b = new StringBuilder();
        for (String s : sorted) {
            if (b.length() > 0)
                b.append('\n');
            b.append(s);
        }
        prefs.edit().putString(PREF_HIDDEN_CATS, b.toString()).apply();
        rebuild();
    }

    /** Meters, or -1 for everything. */
    public double areaMeters() {
        // Until the operator picks one: within 50 of My Location, in the large unit
        // they use, so the default is one of the Area tiles.
        if (!prefs.contains(PREF_AREA_M))
            return com.atakmap.android.lces.ui.Units.bigToMeters(DEFAULT_AREA);
        return prefs.getFloat(PREF_AREA_M, -1f);
    }

    public void setAreaMeters(double m) {
        prefs.edit().putFloat(PREF_AREA_M, (float) m).apply();
        rebuild();
    }

    public boolean fromCenterPref() {
        return prefs.getBoolean(PREF_FROM_CENTER, false);
    }

    public void setFromCenter(boolean c) {
        prefs.edit().putBoolean(PREF_FROM_CENTER, c).apply();
        rebuild();
    }

    /** Ask the depot for a newer catalog now, whatever the last check said. */
    public void refresh() {
        try {
            worker.execute(new Runnable() {
                @Override
                public void run() {
                    refreshNow(true);
                }
            });
        } catch (java.util.concurrent.RejectedExecutionException stoppedAlready) {
            // The plugin stopped.
        }
    }

    // ---- the rule --------------------------------------------------------------------

    /**
     * What the list and the map both show. During a search, exactly what the search
     * found, as its list does; otherwise a kind that is switched on, in a category
     * that is switched on, inside the Area. The one place this is decided.
     */
    public static boolean kept(Incident i, Set<String> searchIds, Set<Incident.Type> types,
            Set<String> hiddenCats, double areaM, double meters) {
        if (searchIds != null)
            return searchIds.contains(i.id);
        return types.contains(i.type) && !hiddenCats.contains(i.category)
                && (areaM <= 0 || meters <= areaM);
    }

    /**
     * Main thread. The map shows only what this search found until it is cleared;
     * null or fewer than two letters ends the search. Returns what it found.
     */
    public List<Incident> setSearch(String q) {
        final String t = q == null ? "" : q.trim();
        final List<Incident> found = t.length() >= 2 ? search(t) : null;
        Set<String> ids = null;
        if (found != null) {
            ids = new HashSet<>();
            for (Incident i : found)
                ids.add(i.id);
        }
        final Set<String> was = searchIds;
        searchIds = ids == null ? null : Collections.unmodifiableSet(ids);
        if (was == null ? ids != null : !was.equals(ids))
            rebuild();
        return found == null ? Collections.<Incident>emptyList() : found;
    }

    public boolean searching() {
        return searchIds != null;
    }

    /** Main thread: read where the list measures from, then do the pass on the worker. */
    public void rebuild() {
        if (stopped)
            return;
        final GeoPoint self = selfPoint();
        final boolean fromCenter = fromCenterPref() || self == null;
        final GeoPoint origin = fromCenter ? centerPoint() : self;
        final boolean noFix = self == null && !fromCenterPref();
        final Set<Incident.Type> types = shownTypes();
        final Set<String> hiddenCats = hiddenCategories();
        final Set<String> ids = searchIds;
        final double areaM = areaMeters();
        try {
            worker.execute(new Runnable() {
                @Override
                public void run() {
                    pass(origin, fromCenter, noFix, ids, types, hiddenCats, areaM);
                }
            });
        } catch (java.util.concurrent.RejectedExecutionException stoppedAlready) {
            // The plugin stopped.
        }
    }

    private void pass(GeoPoint origin, boolean fromCenter, boolean noFix, Set<String> ids,
            Set<Incident.Type> types, Set<String> hiddenCats, double areaM) {
        final LessonsCatalog.Data d = data;
        final Map<Incident.Type, Integer> counts = new EnumMap<>(Incident.Type.class);
        for (Incident.Type t : Incident.Type.values())
            counts.put(t, 0);
        if (d == null) {
            publish(new Result(Collections.<Near>emptyList(), 0, counts,
                    Collections.<String, Integer>emptyMap(), fromCenter, noFix,
                    "", false, origin));
            return;
        }
        final List<Near> kept = new ArrayList<>();
        final List<Incident> drawn = new ArrayList<>();
        final Map<String, Integer> cats = new HashMap<>();
        for (Incident i : d.incidents) {
            counts.put(i.type, counts.get(i.type) + 1);
            final Integer c = cats.get(i.category);
            cats.put(i.category, c == null ? 1 : c + 1);
            final double m = origin == null ? Double.NaN
                    : origin.distanceTo(new GeoPoint(i.lat, i.lon));
            if (kept(i, ids, types, hiddenCats, origin == null ? -1 : areaM, m)) {
                kept.add(new Near(i, m));
                drawn.add(i);
            }
        }
        Collections.sort(kept, new Comparator<Near>() {
            @Override
            public int compare(Near a, Near b) {
                if (Double.isNaN(a.meters) || Double.isNaN(b.meters))
                    return a.incident.date.compareTo(b.incident.date);
                return Double.compare(a.meters, b.meters);
            }
        });
        // The map gets the same set as the list; rewrite only when that set changed.
        final String key = setKey(d, ids, types, hiddenCats, areaM, kept);
        if (!key.equals(drawnKey)) {
            overlay.rewrite(drawn);
            drawnKey = key;
        }
        final List<Near> nearest = new ArrayList<>(kept.subList(0, Math.min(LIST_CAP, kept.size())));
        publish(new Result(Collections.unmodifiableList(nearest), kept.size(), counts,
                byCount(cats), fromCenter, noFix, d.sourceEdited, true, origin));
    }

    /** Equal keys mean equal sets on the map. */
    /** Largest first, then by name, so the tiles do not move between passes. */
    private static Map<String, Integer> byCount(final Map<String, Integer> cats) {
        final List<String> names = new ArrayList<>(cats.keySet());
        Collections.sort(names, new Comparator<String>() {
            @Override
            public int compare(String a, String b) {
                final int n = cats.get(b) - cats.get(a);
                return n != 0 ? n : a.compareTo(b);
            }
        });
        final Map<String, Integer> out = new LinkedHashMap<>();
        for (String s : names)
            out.put(s, cats.get(s));
        return Collections.unmodifiableMap(out);
    }

    private static String setKey(LessonsCatalog.Data d, Set<String> ids,
            Set<Incident.Type> types, Set<String> hiddenCats, double areaM, List<Near> kept) {
        final List<String> off = new ArrayList<>(hiddenCats);
        Collections.sort(off);
        final StringBuilder b = new StringBuilder(d.built).append('|').append(types)
                .append('|').append(off);
        if (ids == null && areaM <= 0)
            return b.toString();
        // A search or a radius: name the members, since the set is no longer fixed by
        // the filters alone.
        long h = 1125899906842597L;
        final List<String> members = new ArrayList<>(kept.size());
        for (Near n : kept)
            members.add(n.incident.id);
        Collections.sort(members);
        for (String id : members)
            h = 31 * h + id.hashCode();
        return b.append('|').append(ids != null).append('|').append(areaM).append('|')
                .append(members.size()).append('|').append(h)
                .toString();
    }

    private void publish(final Result r) {
        last = r;
        main.post(new Runnable() {
            @Override
            public void run() {
                if (listener != null)
                    listener.onLessons(r);
            }
        });
    }

    private void take(LessonsCatalog.Data d) {
        final Map<String, Incident> m = new HashMap<>(d.incidents.size() * 2);
        for (Incident i : d.incidents)
            m.put(i.id, i);
        byId = m;
        data = d;
    }

    private void refreshNow(boolean force) {
        final LessonsCatalog.Data held = data;
        if (held == null)
            return;
        final LessonsCatalog.Data fresh = catalog.refresh(held, force);
        if (fresh == null)
            return;
        take(fresh);
        main.post(new Runnable() {
            @Override
            public void run() {
                rebuild();
            }
        });
    }

    // ---- where -----------------------------------------------------------------------

    private boolean measuringFromCenter() {
        return fromCenterPref() || selfPoint() == null;
    }

    /** My Location, or null with no fix. 0,0 is what the self marker reads before one. */
    private GeoPoint selfPoint() {
        try {
            final Marker self = mapView.getSelfMarker();
            if (self == null)
                return null;
            final GeoPoint p = self.getPoint();
            if (p == null || !p.isValid() || (p.getLatitude() == 0 && p.getLongitude() == 0))
                return null;
            return p;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private GeoPoint centerPoint() {
        try {
            final GeoPoint p = mapView.getCenterPoint().get();
            return p != null && p.isValid() ? p : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** Fly to an incident, close enough to read it; never zooms the operator out. */
    public void goTo(Incident i) {
        if (i == null)
            return;
        final GeoPoint p = new GeoPoint(i.lat, i.lon);
        try {
            final double target = Math.min(mapView.getMapResolution(), GOTO_RESOLUTION);
            mapView.getMapController().panZoomTo(p, mapView.mapResolutionAsMapScale(target), true);
        } catch (LinkageError | RuntimeException e) {
            Log.w(TAG, "panZoomTo failed; a plain pan instead", e);
            mapView.getMapController().panTo(p, true);
        }
    }

    // ---- a tap on the map -------------------------------------------------------------

    /**
     * A tap on an incident opens its page in the pane, no radial (PLAN decision 2).
     * ATAK asks this before it opens a radial; true stops the radial and says the tap
     * was handled. Only our own items are claimed.
     */
    private void registerTap() {
        try {
            final MapMenuReceiver menus = MapMenuReceiver.getInstance();
            if (menus == null) {
                Log.w(TAG, "no radial menu receiver; a tap shows ATAK's own radial");
                return;
            }
            tapListener = new MapMenuEventListener() {
                @Override
                public boolean onShowMenu(MapItem item) {
                    final String id = item == null ? null
                            : item.getMetaString(LessonsOverlay.META_ID, null);
                    final Incident i = get(id);
                    if (i == null)
                        return false;
                    // A moment later: a pick from ATAK's Select Item list posts its own
                    // show-details next, which would close a page opened now.
                    main.postDelayed(new Runnable() {
                        @Override
                        public void run() {
                            clearAtakSelection();
                            if (listener != null)
                                listener.onTapped(i);
                        }
                    }, 250);
                    return true;
                }

                @Override
                public void onHideMenu(MapItem item) {
                }
            };
            menus.addEventListener(tapListener);
        } catch (LinkageError | RuntimeException e) {
            Log.w(TAG, "could not take incident taps", e);
            tapListener = null;
        }
    }

    private static void clearAtakSelection() {
        try {
            AtakBroadcast.getInstance().sendBroadcast(
                    new Intent("com.atakmap.android.maps.UNFOCUS"));
            AtakBroadcast.getInstance().sendBroadcast(
                    new Intent("com.atakmap.android.maps.HIDE_DETAILS"));
        } catch (LinkageError | RuntimeException e) {
            Log.d(TAG, "could not clear ATAK's selection: " + e);
        }
    }

    /** Incidents whose name, fire, state, category or summary hold every word of the search. */
    public List<Incident> search(String q) {
        final LessonsCatalog.Data d = data;
        final List<Incident> out = new ArrayList<>();
        if (d == null || q == null)
            return out;
        final String[] words = q.toLowerCase(Locale.US).trim().split("\\s+");
        for (Incident i : d.incidents) {
            final String hay = (i.name + " " + i.fire + " " + i.state + " " + i.category
                    + " " + i.summary)
                    .toLowerCase(Locale.US);
            boolean all = true;
            for (String w : words) {
                if (!w.isEmpty() && !hay.contains(w)) {
                    all = false;
                    break;
                }
            }
            if (all)
                out.add(i);
        }
        // Name matches first, then newest.
        final String first = words.length > 0 ? words[0] : "";
        Collections.sort(out, new Comparator<Incident>() {
            @Override
            public int compare(Incident a, Incident b) {
                final boolean an = a.name.toLowerCase(Locale.US).contains(first);
                final boolean bn = b.name.toLowerCase(Locale.US).contains(first);
                if (an != bn)
                    return an ? -1 : 1;
                return b.date.compareTo(a.date);
            }
        });
        return out;
    }
}
