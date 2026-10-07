package com.atakmap.android.uasflightplan.obstacles;

import android.content.Context;
import android.content.SharedPreferences;
import android.preference.PreferenceManager;

import com.atakmap.android.maps.MapView;
import com.atakmap.android.uasflightplan.map.ObstacleOverlay;
import com.atakmap.android.uasflightplan.plugin.R;
import com.atakmap.coremap.log.Log;
import com.atakmap.coremap.maps.coords.GeoCalculations;
import com.atakmap.coremap.maps.coords.GeoPoint;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Owns the obstacles for the life of the plugin: the fetch for the circle, what
 * came back, the layer that draws it, and the switch. Created in {@code onStart},
 * never inside a {@code Tool}; the pane reads it and drives it.
 *
 * <p>One rule for the list and the map: the list is what {@link #getObstacles()}
 * holds, nearest first, and the map is the same list drawn through the overlay,
 * so the two cannot disagree.
 */
public final class ObstacleManager {

    private static final String TAG = "UASObstacles";
    private static final String PREF_ENABLED = "uasflightplan.obstacles";
    private static final String PREF_MIN_AGL_FT = "uasflightplan.obstacles.minAglFt";
    private static final String PREF_GROUP = "uasflightplan.obstacles.group.";

    /**
     * Nothing under this height above ground is shown, by default. The FAA file
     * holds everything ever filed under an obstruction study, down to 20 ft solar
     * arrays on a ballfield, and those are noise to a UAS pilot working a ceiling
     * (operator, 2026-10-06: "remove the 20 ft shit").
     */
    public static final double DEFAULT_MIN_AGL_FT = 50d;
    /** The floor presets, feet above ground; 0 is everything. */
    public static final double[] MIN_AGL_PRESETS_FT = { 0d, 50d, 100d, 200d };

    public interface Listener {
        /** Something the pane shows changed. Main thread. */
        void onObstaclesChanged();
    }

    private final MapView mapView;
    private final Context pluginContext;
    private final SharedPreferences prefs;
    private final ObstacleOverlay overlay;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(
            new ThreadFactory() {
                @Override
                public Thread newThread(Runnable r) {
                    final Thread t = new Thread(r, "uas-flight-plan-obstacles");
                    t.setPriority(Thread.NORM_PRIORITY - 1);
                    return t;
                }
            });
    /** Bumped per load, so a slow answer for an old circle is dropped. */
    private final AtomicInteger generation = new AtomicInteger();

    private Listener listener;
    private boolean started;

    // Main-thread state. 'fetched' is everything the FAA sent for the circle;
    // 'obstacles' is what passes the filter, and it is the one list the map and
    // the pane both show.
    private List<Obstacle> fetched = Collections.emptyList();
    private List<Obstacle> obstacles = Collections.emptyList();
    private boolean capped;
    private boolean loading;
    private String failure;
    private int drawn;
    private double ceilingMslFt = Double.NaN;
    private GeoPoint launch;

    public ObstacleManager(MapView mapView, Context pluginContext) {
        this.mapView = mapView;
        this.pluginContext = pluginContext;
        this.prefs = PreferenceManager.getDefaultSharedPreferences(mapView.getContext());
        this.overlay = new ObstacleOverlay(mapView, pluginContext);
    }

    public void setListener(Listener l) {
        listener = l;
    }

    /** Opens the layer. The store is a file, so that part runs on the worker. */
    public void start() {
        if (started)
            return;
        started = true;
        overlay.setVisible(isEnabled());
        worker.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    overlay.attach();
                } catch (Exception e) {
                    Log.e(TAG, "obstacle layer could not be opened", e);
                }
            }
        });
    }

    public void stop() {
        if (!started)
            return;
        started = false;
        generation.incrementAndGet();
        worker.shutdownNow();
        overlay.detach();
    }

    /* ----- state the pane reads ----- */

    public boolean isEnabled() {
        return prefs.getBoolean(PREF_ENABLED, true);
    }

    public double minAglFt() {
        return prefs.getFloat(PREF_MIN_AGL_FT, (float) DEFAULT_MIN_AGL_FT);
    }

    public void setMinAglFt(double ft) {
        prefs.edit().putFloat(PREF_MIN_AGL_FT, (float) ft).apply();
        refilter();
    }

    public boolean isGroupOn(String group) {
        return prefs.getBoolean(PREF_GROUP + group, Obstacle.DEFAULT_ON.contains(group));
    }

    public void setGroupOn(String group, boolean on) {
        prefs.edit().putBoolean(PREF_GROUP + group, on).apply();
        refilter();
    }

    public void setAllGroups(boolean on) {
        final SharedPreferences.Editor e = prefs.edit();
        for (String g : Obstacle.GROUPS)
            e.putBoolean(PREF_GROUP + g, on);
        e.apply();
        refilter();
    }

    public int groupsOn() {
        int n = 0;
        for (String g : Obstacle.GROUPS)
            if (isGroupOn(g))
                n++;
        return n;
    }

    /** How many of what the FAA sent are in a group, so a tile can say what it costs. */
    public int countInGroup(String group) {
        int n = 0;
        for (Obstacle o : fetched)
            if (group.equals(o.group()))
                n++;
        return n;
    }

    /** Everything the FAA sent for the circle, before the filter. */
    public int fetchedCount() {
        return fetched.size();
    }

    /**
     * Where the distances in the list are measured from: the phone's own position
     * when it has a fix, otherwise the launch point. The operator wants "how far
     * from your location", and in the field the two are usually the same spot.
     */
    public GeoPoint measureFrom() {
        final GeoPoint self = selfPosition();
        return self != null ? self : launch;
    }

    /** True when the list measures from the phone's fix rather than the launch point. */
    public boolean measuringFromSelf() {
        return selfPosition() != null;
    }

    private GeoPoint selfPosition() {
        final com.atakmap.android.maps.Marker self = mapView.getSelfMarker();
        final GeoPoint p = self == null ? null : self.getPoint();
        if (p == null || !p.isValid() || (p.getLatitude() == 0 && p.getLongitude() == 0))
            return null;
        return new GeoPoint(p.getLatitude(), p.getLongitude());
    }

    /** Re-measures and re-sorts the list from {@link #measureFrom()}. Main thread. */
    public void resort() {
        final GeoPoint from = measureFrom();
        if (from == null)
            return;
        for (Obstacle o : fetched)
            o.distanceM = GeoCalculations.distanceTo(from, new GeoPoint(o.lat, o.lon));
        final java.util.Comparator<Obstacle> nearest = new java.util.Comparator<Obstacle>() {
            @Override
            public int compare(Obstacle a, Obstacle b) {
                return Double.compare(a.distanceM, b.distanceM);
            }
        };
        Collections.sort(fetched, nearest);
        Collections.sort(obstacles, nearest);
    }

    /** The filter: reads the settings each time, never a copy. */
    public boolean passes(Obstacle o) {
        return o.aglFt >= minAglFt() && isGroupOn(o.group());
    }

    /** Re-applies the filter to what was fetched, and redraws. */
    private void refilter() {
        final List<Obstacle> kept = new ArrayList<>();
        for (Obstacle o : fetched)
            if (passes(o))
                kept.add(o);
        obstacles = kept;
        resort();
        redraw(generation.get());
        notifyChanged();
    }

    public boolean isLoading() {
        return loading;
    }

    public String getFailure() {
        return failure;
    }

    /** Nearest first. */
    public List<Obstacle> getObstacles() {
        return obstacles;
    }

    public boolean isCapped() {
        return capped;
    }

    public int getDrawn() {
        return drawn;
    }

    public double getCeilingMslFt() {
        return ceilingMslFt;
    }

    public int aboveCeiling() {
        if (Double.isNaN(ceilingMslFt))
            return 0;
        int n = 0;
        for (Obstacle o : obstacles)
            if (o.aboveCeiling(ceilingMslFt))
                n++;
        return n;
    }

    /* ----- driving it ----- */

    /** The switch: hides the map layer, nothing else. The list stays. */
    public void setEnabled(boolean on) {
        prefs.edit().putBoolean(PREF_ENABLED, on).apply();
        overlay.setVisible(on);
        notifyChanged();
    }

    /** Asks the FAA for the circle. Main thread; the answer arrives on main. */
    public void load(final GeoPoint center, final double radiusM) {
        if (!started)
            return;
        launch = center;
        final int mine = generation.incrementAndGet();
        loading = true;
        failure = null;
        notifyChanged();
        DofSource.fetch(center, radiusM, new DofSource.Callback() {
            @Override
            public void onLoaded(List<Obstacle> inCircle, boolean wasCapped) {
                if (mine != generation.get())
                    return;
                fetched = inCircle;
                capped = wasCapped;
                loading = false;
                refilter();
            }

            @Override
            public void onFailed(String reason) {
                if (mine != generation.get())
                    return;
                loading = false;
                failure = reason;
                notifyChanged();
            }
        });
    }

    /** Recolors for a new ceiling: the red ones are those that stick up through it. */
    public void setCeiling(double mslFt) {
        if (!Double.isNaN(ceilingMslFt) && Math.abs(mslFt - ceilingMslFt) < 0.5d)
            return;
        ceilingMslFt = mslFt;
        redraw(generation.get());
        notifyChanged();
    }

    public void clear() {
        generation.incrementAndGet();
        launch = null;
        fetched = Collections.emptyList();
        obstacles = Collections.emptyList();
        capped = false;
        loading = false;
        failure = null;
        drawn = 0;
        if (started)
            worker.execute(new Runnable() {
                @Override
                public void run() {
                    overlay.clear();
                }
            });
        notifyChanged();
    }

    /** The obstacle a drawn feature stands for, or null. Main thread. */
    public Obstacle obstacleFor(long featureId) {
        return overlay.obstacleFor(featureId);
    }

    /** Pans the map to an obstacle. */
    public void panTo(Obstacle o) {
        mapView.getMapController().panTo(new GeoPoint(o.lat, o.lon), true);
    }

    private void redraw(final int mine) {
        if (!started)
            return;
        final List<Obstacle> list = new ArrayList<>(obstacles);
        final double ceiling = ceilingMslFt;
        worker.execute(new Runnable() {
            @Override
            public void run() {
                final int n = list.isEmpty() ? 0 : overlay.rewrite(list, ceiling);
                if (list.isEmpty())
                    overlay.clear();
                mapView.post(new Runnable() {
                    @Override
                    public void run() {
                        if (mine != generation.get())
                            return;
                        drawn = n;
                        notifyChanged();
                    }
                });
            }
        });
    }

    private void notifyChanged() {
        if (listener != null)
            listener.onObstaclesChanged();
    }

    /** What the status line says about obstacles, or null when there is nothing to say. */
    public String statusLine(boolean haveLaunch) {
        if (!haveLaunch)
            return null;
        if (loading)
            return pluginContext.getString(R.string.status_obstacles_loading);
        if (failure != null)
            return failure;
        final StringBuilder sb = new StringBuilder();
        if (obstacles.isEmpty()) {
            sb.append(pluginContext.getString(fetched.isEmpty()
                    ? R.string.status_obstacles_none : R.string.status_obstacles_all_filtered));
        } else {
            sb.append(pluginContext.getString(R.string.status_obstacles_count,
                    obstacles.size(), aboveCeiling()));
            if (capped)
                sb.append(' ').append(pluginContext.getString(
                        R.string.status_obstacles_capped, obstacles.size(), obstacles.size()));
            else if (drawn > 0 && drawn < obstacles.size())
                sb.append(' ').append(pluginContext.getString(
                        R.string.status_obstacles_drawn, drawn, obstacles.size()));
        }
        final int hidden = fetched.size() - obstacles.size();
        if (hidden > 0)
            sb.append(' ').append(pluginContext.getString(R.string.status_obstacles_hidden,
                    hidden));
        if (!isEnabled())
            sb.append(' ').append(pluginContext.getString(R.string.status_obstacles_off));
        return sb.toString();
    }
}
