package com.atakmap.android.tfr;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Handler;
import android.os.Looper;
import android.preference.PreferenceManager;

import com.atakmap.android.maps.MapView;
import com.atakmap.coremap.log.Log;
import com.atakmap.coremap.maps.coords.GeoBounds;
import com.atakmap.coremap.maps.coords.GeoPoint;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The switch, the sync, and everything the plugin knows about the national picture.
 *
 * <p>This lives for the plugin's life and never inside a {@code Tool} or the pane. ATAK ends the
 * active tool whenever another starts, a dropdown opens, or Back is pressed, and a TFR that
 * stopped refreshing because somebody switched base maps would read as airspace that had been
 * lifted. The pane is only this object's face.
 *
 * <p>The list API carries no coordinates, so a radius or an extent cannot be evaluated until the
 * geometry is on the device. That is why the sync is national and every filter afterwards is
 * local: the whole country is about 104 documents of 26 KB.
 */
public class TfrManager {

    private static final String TAG = "TfrManager";

    /** Plugin-private UI state: the switch, the type filter. */
    private static final String PREFS = "tfr";
    static final String PREF_ON = "on";
    static final String PREF_TYPES_OFF = "typesOff";

    /** Tool Preferences, shared with ATAK's own settings screen. */
    public static final String PREF_REFRESH_MIN = "tfr_refresh_minutes";
    public static final String PREF_COLOR_ACTIVE = "tfr_color_active";
    public static final String PREF_COLOR_UPCOMING = "tfr_color_upcoming";

    /**
     * Red for what is in effect, amber for what is scheduled. ForeFlight, Garmin Pilot,
     * SkyVector and the Leidos briefer all color a TFR by status and none of them by category,
     * so an operator who has seen a TFR anywhere else already knows what these mean.
     */
    public static final int DEFAULT_ACTIVE = Color.rgb(0xE0, 0x1B, 0x24);
    public static final int DEFAULT_UPCOMING = Color.rgb(0xFF, 0xB3, 0x00);

    /**
     * SECURITY is half the national list and is mostly standing stadium and capital airspace
     * that never changes. Off out of the box, remembered once touched.
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
    private final TfrShapes shapes;

    private Listener listener;
    private boolean started;
    private boolean on;
    private final Set<String> typesOff = new LinkedHashSet<>();

    /** Everything known, replaced wholesale so readers never see a half-built list. */
    private volatile List<Tfr> known = Collections.emptyList();
    private volatile boolean syncing;
    private volatile String syncStatus = "";
    private volatile long lastSuccessMs;
    private volatile String lastError;

    public TfrManager(MapView mapView, Context pluginContext) {
        this.mapView = mapView;
        this.pluginContext = pluginContext;
        this.shapes = new TfrShapes(mapView);
        SharedPreferences p = prefs();
        // A fresh install starts off: nothing pulls the country before it has been asked to.
        on = p.getBoolean(PREF_ON, false);
        String off = p.getString(PREF_TYPES_OFF, DEFAULT_OFF);
        if (off != null && !off.isEmpty())
            typesOff.addAll(Arrays.asList(off.split("\\|")));
    }

    public void setListener(Listener l) {
        this.listener = l;
    }

    public void start() {
        if (started)
            return;
        started = true;
        shapes.start();
        mapView.addOnMapMovedListener(moved);
        main.postDelayed(tick, TICK_MS);
        if (on)
            loadThenSync();
    }

    /** Take everything off the map and stop. Called from the plugin's own onStop. */
    public void dispose() {
        started = false;
        main.removeCallbacks(tick);
        main.removeCallbacks(moveTick);
        mapView.removeOnMapMovedListener(moved);
        worker.shutdownNow();
        shapes.dispose();
    }

    // ---- the switch ----

    public boolean isOn() {
        return on;
    }

    /**
     * On syncs at open and keeps itself current; off touches nothing.
     *
     * <p>Off leaves the cache alone on purpose, so turning it back on is a list diff of 24 KB
     * rather than another three megabytes.
     */
    public void setOn(boolean value) {
        if (on == value)
            return;
        on = value;
        prefs().edit().putBoolean(PREF_ON, value).apply();
        if (on) {
            loadThenSync();
        } else {
            lastError = null;
            syncStatus = "";
            shapes.clear();
            changed();
        }
    }

    // ---- the type filter ----

    public boolean isTypeOn(String type) {
        return !typesOff.contains(type);
    }

    public void setTypeOn(String type, boolean value) {
        if (value)
            typesOff.remove(type);
        else
            typesOff.add(type);
        StringBuilder b = new StringBuilder();
        for (String t : typesOff) {
            if (b.length() > 0)
                b.append('|');
            b.append(t);
        }
        prefs().edit().putString(PREF_TYPES_OFF, b.toString()).apply();
        redraw();
        changed();
    }

    /** Every type in the national list with how many carry it, for the filter's own labels. */
    public List<String[]> typeCounts() {
        List<String> order = new ArrayList<>();
        List<Integer> counts = new ArrayList<>();
        for (Tfr t : known) {
            int i = order.indexOf(t.type);
            if (i < 0) {
                order.add(t.type);
                counts.add(1);
            } else {
                counts.set(i, counts.get(i) + 1);
            }
        }
        List<String[]> out = new ArrayList<>();
        for (int i = 0; i < order.size(); i++)
            out.add(new String[] {
                    order.get(i), Integer.toString(counts.get(i))
            });
        return out;
    }

    // ---- what the pane shows ----

    public List<Tfr> all() {
        return known;
    }

    /** Everything the type filter lets through. This is what gets drawn. */
    public List<Tfr> shown() {
        List<Tfr> out = new ArrayList<>();
        for (Tfr t : known)
            if (isTypeOn(t.type))
                out.add(t);
        return out;
    }

    /**
     * What is in view, which is what the list shows.
     *
     * <p>EvacZone settled this argument once already: a list that follows the map extent answers
     * "what am I looking at" without anybody having to subscribe to anything. The map still
     * carries every TFR the filter allows, so panning toward a fire finds its ring already
     * drawn rather than waiting on a fetch.
     */
    public List<Tfr> inView() {
        GeoBounds b = mapView.getBounds();
        List<Tfr> out = new ArrayList<>();
        if (b == null)
            return shown();
        for (Tfr t : shown()) {
            if (intersects(t, b))
                out.add(t);
        }
        return out;
    }

    private static boolean intersects(Tfr t, GeoBounds view) {
        for (TfrArea a : t.areas) {
            for (double[] p : a.ring)
                if (view.contains(new GeoPoint(p[0], p[1])))
                    return true;
            // A ring larger than the screen has no vertex on it -- the capital's 30 NM ring at
            // a city zoom is the everyday case -- so the box the ring spans is checked too.
            if (!a.ring.isEmpty() && spans(a, view))
                return true;
        }
        return false;
    }

    private static boolean spans(TfrArea a, GeoBounds view) {
        double minLat = Double.MAX_VALUE, maxLat = -Double.MAX_VALUE;
        double minLon = Double.MAX_VALUE, maxLon = -Double.MAX_VALUE;
        for (double[] p : a.ring) {
            minLat = Math.min(minLat, p[0]);
            maxLat = Math.max(maxLat, p[0]);
            minLon = Math.min(minLon, p[1]);
            maxLon = Math.max(maxLon, p[1]);
        }
        return view.getSouth() <= maxLat && view.getNorth() >= minLat
                && view.getWest() <= maxLon && view.getEast() >= minLon;
    }

    // ---- status the pane prints ----

    public boolean isSyncing() {
        return syncing;
    }

    public String status() {
        if (!on)
            return "off";
        if (syncing)
            return syncStatus;
        if (lastError != null)
            return lastError + (lastSuccessMs > 0 ? ", showing the last good copy" : "");
        if (lastSuccessMs == 0)
            return "not synced yet";
        long age = System.currentTimeMillis() - lastSuccessMs;
        return "updated " + ago(age);
    }

    static String ago(long ms) {
        long min = ms / 60000L;
        if (min < 1)
            return "just now";
        if (min < 60)
            return min + " min ago";
        long hours = min / 60;
        if (hours < 24)
            return hours + (hours == 1 ? " hour ago" : " hours ago");
        long days = hours / 24;
        return days + (days == 1 ? " day ago" : " days ago");
    }

    // ---- sync ----

    /** Draw whatever is cached first, then go and find out what changed. */
    private void loadThenSync() {
        worker.execute(new Runnable() {
            @Override
            public void run() {
                loadFromCache();
                syncOnWorker();
            }
        });
    }

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
        byte[] listBody = cache.readList();
        if (listBody == null)
            return;
        try {
            List<Tfr> rows = TfrFeed.parseList(listBody);
            for (Tfr t : rows) {
                byte[] xml = cache.read(t.notamId);
                if (xml != null)
                    parseQuietly(xml, t);
            }
            publish(rows);
        } catch (Exception e) {
            Log.w(TAG, "the cached list was not usable", e);
        }
    }

    private void syncOnWorker() {
        syncing = true;
        lastError = null;
        progress("checking the FAA list");
        List<Tfr> rows;
        byte[] listBody;
        try {
            listBody = TfrFeed.listBytes();
            rows = TfrFeed.parseList(listBody);
        } catch (Exception e) {
            // The last good geometry stays on the map. A network that dropped is not a reason
            // to tell an operator the airspace is clear.
            fail(com.atakmap.android.tfr.net.Http.describe(e));
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
                progress("downloading " + done + " of " + rows.size());
                try {
                    xml = TfrFeed.detail(t.notamId);
                    cache.write(t.notamId, xml);
                } catch (Exception e) {
                    // One missing detail is one TFR without a shape, not a failed sync. It is
                    // not written down as an answer either: a detail that 404s today can be
                    // there tomorrow.
                    failed++;
                    continue;
                }
            }
            parseQuietly(xml, t);
        }

        // Reconcile: anything no longer in the list is canceled or expired, and comes off the
        // map and out of the cache. The list churns daily -- it was 108 one day and 104 the
        // next -- so this is the ordinary case, not an edge one.
        Set<String> live = new HashSet<>();
        for (Tfr t : rows)
            live.add(t.notamId);
        cache.prune(live);

        lastSuccessMs = System.currentTimeMillis();
        syncing = false;
        syncStatus = "";
        if (failed > 0)
            lastError = failed + (failed == 1 ? " TFR" : " TFRs") + " would not download";
        publish(rows);
    }

    private void parseQuietly(byte[] xml, Tfr t) {
        try {
            TfrParser.parseDetail(xml, t);
        } catch (Exception e) {
            Log.w(TAG, "could not parse the detail for " + t.notamId, e);
        }
    }

    private void publish(final List<Tfr> rows) {
        known = Collections.unmodifiableList(rows);
        main.post(new Runnable() {
            @Override
            public void run() {
                if (!started)
                    return;
                redraw();
                changed();
            }
        });
    }

    private void progress(final String message) {
        syncStatus = message;
        main.post(new Runnable() {
            @Override
            public void run() {
                if (started)
                    changed();
            }
        });
    }

    private void fail(final String message) {
        syncing = false;
        syncStatus = "";
        lastError = message;
        main.post(new Runnable() {
            @Override
            public void run() {
                if (started)
                    changed();
            }
        });
    }

    // ---- drawing ----

    /** Main thread only. */
    private void redraw() {
        // A sync in flight when the plugin stops still has a callback to deliver, and
        // TfrShapes.draw() makes its group if there is none -- so without this guard a stopped
        // plugin puts its shapes back on the map with nothing left running to take them off or
        // keep them current. Stale airspace, drawn by a plugin that is no longer there.
        if (!started)
            return;
        if (!on) {
            shapes.clear();
            return;
        }
        shapes.draw(shown(), System.currentTimeMillis(), color(PREF_COLOR_ACTIVE, DEFAULT_ACTIVE),
                color(PREF_COLOR_UPCOMING, DEFAULT_UPCOMING));
    }

    private int color(String key, int fallback) {
        try {
            String v = atakPrefs().getString(key, null);
            if (v == null || v.trim().isEmpty())
                return fallback;
            return Color.parseColor(v.trim());
        } catch (Exception e) {
            return fallback;
        }
    }

    // ---- the clock ----

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (!started)
                return;
            main.postDelayed(this, TICK_MS);
            if (!on || syncing)
                return;
            // Never assume the timer fired. Doze defers alarms and a phone can be asleep for an
            // hour, so what decides a refresh is the age of the last success, not a tick count.
            long interval = refreshMinutes() * 60L * 1000L;
            if (lastSuccessMs == 0 || System.currentTimeMillis() - lastSuccessMs >= interval)
                syncNow();
            else
                changed();
        }
    };

    private int refreshMinutes() {
        try {
            String v = atakPrefs().getString(PREF_REFRESH_MIN, null);
            if (v == null)
                return DEFAULT_REFRESH_MIN;
            int n = Integer.parseInt(v.trim());
            return n < 5 ? 5 : n;
        } catch (Exception e) {
            return DEFAULT_REFRESH_MIN;
        }
    }

    /**
     * <strong>Runs on the GL render thread.</strong> ATAK dispatches this over JNI from
     * {@code GLMapView}; touching a View or a map item here is a native SIGSEGV with no Java
     * stack trace. Post to the main looper, and coalesce, because during a pinch it fires every
     * frame.
     */
    private final com.atakmap.map.AtakMapView.OnMapMovedListener moved =
            new com.atakmap.map.AtakMapView.OnMapMovedListener() {
                @Override
                public void onMapMoved(com.atakmap.map.AtakMapView v, boolean animate) {
                    main.removeCallbacks(moveTick);
                    main.postDelayed(moveTick, 400);
                }
            };

    private final Runnable moveTick = new Runnable() {
        @Override
        public void run() {
            if (started)
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

    String describeCache() {
        long bytes = cache.bytesOnDisk();
        return String.format(Locale.US, "%.1f MB cached", bytes / (1024.0 * 1024.0));
    }

    void clearCache() {
        cache.clear();
    }
}
