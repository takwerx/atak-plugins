package com.atakmap.android.tfr;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Handler;
import android.os.Looper;
import android.preference.PreferenceManager;

import com.atakmap.android.maps.MapView;
import com.atakmap.android.tfr.ui.ScaleBar;
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

    private static final String PREFS = "tfr";
    static final String PREF_ON = "on";
    static final String PREF_TYPES_OFF = "typesOff";
    static final String PREF_GATE_BAR_M = "gateBarM";
    static final String PREF_WHERE_MODE = "whereMode";
    static final String PREF_WHERE_VALUES = "whereValues";
    static final String PREF_AREA_MODE = "areaMode";
    static final String PREF_AREA_RADIUS_M = "areaRadiusM";

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

    private static final long TICK_MS = 60 * 1000L;
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
    private boolean on;
    /** Raw FAA type strings the operator has switched off. */
    private final Set<String> typesOff = new LinkedHashSet<>();

    private volatile List<Tfr> known = Collections.emptyList();
    private volatile boolean syncing;
    private volatile String syncStatus = "";
    private volatile long lastSuccessMs;
    private volatile String lastError;
    /** The scale-bar distance at or below which the map draws; -1 is always. */
    private volatile long gateBarM;
    private volatile boolean gateHiding;
    private volatile int whereMode;
    private final Set<String> whereValues = new LinkedHashSet<>();
    private volatile int areaMode = AREA_IN_VIEW;
    private volatile long areaRadiusM = 80467L;

    public TfrManager(MapView mapView, Context pluginContext) {
        this.mapView = mapView;
        this.pluginContext = pluginContext;
        this.overlay = new TfrOverlay(mapView, pluginContext,
                new File(FileSystemUtils.getItem("tools/tfr"), "tfr.sqlite"), "TFR");
        final SharedPreferences p = prefs();
        // A fresh install starts off: nothing pulls the country before it is asked to.
        on = p.getBoolean(PREF_ON, false);
        gateBarM = p.getLong(PREF_GATE_BAR_M, -1L);
        // A set, not a joined string: a type carrying the separator would come back as two
        // bogus entries and the filter would restore wrong.
        whereMode = p.getInt(PREF_WHERE_MODE, WHERE_EVERYWHERE);
        whereValues.addAll(p.getStringSet(PREF_WHERE_VALUES, Collections.<String> emptySet()));
        areaMode = p.getInt(PREF_AREA_MODE, AREA_IN_VIEW);
        areaRadiusM = p.getLong(PREF_AREA_RADIUS_M, 80467L);
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
            overlay.attach(on && !gateHiding, new HashSet<>(typesOff));
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
                if (on)
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

    public boolean isOn() {
        return on;
    }

    /**
     * On syncs at open and keeps itself current; off touches nothing and draws nothing.
     *
     * <p>Off leaves the store and the cache alone, so turning it back on is a 24 KB list
     * diff rather than another three megabytes -- and so the last good picture is still
     * there after a restart with no network, which is the rule for downloaded data.
     *
     * <p>This is deliberately more than the baseline's "the map switch hides the map,
     * nothing else": the operator asked for one switch that decides whether the plugin
     * touches the network at all.
     */
    public void setOn(boolean value) {
        if (on == value)
            return;
        on = value;
        prefs().edit().putBoolean(PREF_ON, value).apply();
        if (on) {
            applyGate();
            syncNow();
        } else {
            lastError = null;
            syncStatus = "";
            overlay.setVisible(false);
        }
        changed();
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
        overlay.setTypesOff(new HashSet<>(typesOff));
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

    // ---- one rule for the list and the map ----

    public List<Tfr> all() {
        return known;
    }

    /** Everything the type and place filters let through. The map draws exactly this. */
    public List<Tfr> shown() {
        final List<Tfr> out = new ArrayList<>();
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

    /** What the Area row's head reads. */
    public String areaLabel() {
        switch (areaMode) {
            case AREA_EVERYWHERE:
                return "Everything";
            case AREA_RADIUS:
                return "Within " + com.atakmap.android.tfr.ui.ScaleBar.describe(areaRadiusM)
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

    /** Where "of me" measures from: the self marker, or the map center with no fix. */
    public GeoPoint self() {
        final com.atakmap.android.maps.Marker m = mapView.getSelfMarker();
        if (m != null) {
            final GeoPoint p = m.getPoint();
            // 0,0 is not a fix, whatever isValid() says about it.
            if (p != null && p.isValid() && (p.getLatitude() != 0 || p.getLongitude() != 0))
                return p;
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
        final boolean hide = on && gateBarM > 0 && barMeters() > gateBarM * 1.02;
        final boolean was = gateHiding;
        gateHiding = hide;
        overlay.setVisible(on && !hide);
        if (was != hide)
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
        if (!on)
            return "TFR off. Turn it on to download and show restrictions.";
        if (syncing && !syncStatus.isEmpty())
            b.append(syncStatus).append('.');
        else if (lastError != null)
            b.append(lastError).append(lastSuccessMs > 0
                    ? ", showing what this phone saved " + ago(System.currentTimeMillis() - lastSuccessMs) + "."
                    : ".");
        else if (lastSuccessMs == 0)
            b.append("Not downloaded yet.");
        else
            b.append("Updated ").append(ago(System.currentTimeMillis() - lastSuccessMs)).append('.');
        if (gateHiding)
            b.append(" Zoom in to see restrictions on the map. Shown at ")
                    .append(ScaleBar.describe(gateBarM)).append(" or closer.");
        return b.toString();
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
        if (!on || syncing)
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
            lastError = lastSuccessMs > 0 ? "No network" : com.atakmap.android.tfr.net.Http.describe(e);
            post();
            return;
        }
        cache.writeList(listBody);

        int done = 0;
        int failed = 0;
        for (Tfr t : rows) {
            if (!on || Thread.currentThread().isInterrupted())
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
        overlay.rewrite(drawn);
        overlay.setTypesOff(new HashSet<>(typesOff));
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
            if (!on || syncing)
                return;
            // Never assume the timer fired. Doze defers everything and a phone can be
            // asleep for an hour, so what decides a refresh is the age of the last
            // success, not a count of ticks.
            final long interval = refreshMinutes() * 60L * 1000L;
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
            changed();
        }
    };

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
