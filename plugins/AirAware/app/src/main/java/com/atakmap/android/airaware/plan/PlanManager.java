package com.atakmap.android.airaware.plan;

import android.content.Context;
import android.content.SharedPreferences;

import com.atakmap.android.airaware.Obstacle;
import com.atakmap.android.airaware.terrain.Extent;
import com.atakmap.android.airaware.terrain.TerrainGrid;
import com.atakmap.android.maps.MapView;
import com.atakmap.coremap.log.Log;
import com.atakmap.coremap.maps.coords.GeoPoint;

import java.util.List;

/**
 * The flight plan: a launch point, an area around it, and a ceiling.
 *
 * <p>Carried over from UAS Flight Plan, which the operator deprecated into AirAware on
 * 2026-10-08. The parts that draw -- the launch marker, the terrain islands and the area
 * picker -- came across as they were; this is the thing that holds them together, which
 * in the old plugin was spread through its pane.
 *
 * <p><b>A plan is a page, not a layer.</b> AirAware's front page is layers, and a
 * calculator is not one: it is something you set up and read an answer from. So the
 * planner lives on its own page and what it draws appears while a plan is active.
 *
 * <p><b>Two scenarios, never one number with a sync button</b>
 * ({@code one-number-per-screen-no-derived-twin}). Either the operator has been given a
 * ceiling and wants to see what breaches it, or they need to ask for one and want the
 * number. Those are different questions and each keeps its own answer.
 *
 * <p>Everything here survives a restart with no network, because the operator set it up:
 * the launch point, the radius, the mode and the typed ceiling are all written as they
 * change and read back at start.
 */
public final class PlanManager {

    private static final String TAG = "AirAwarePlan";
    private static final String PREFS = "airaware";

    static final String PREF_MODE = "planMode";
    static final String PREF_CEILING_FT = "planCeilingAglFt";
    static final String PREF_RADIUS_M = "planRadiusM";
    static final String PREF_CLEARANCE_FT = "planClearanceFt";
    static final String PREF_ISLANDS = "planIslands";

    /** The operator has a ceiling and wants to see what reaches it. */
    public static final int MODE_GIVEN = 0;
    /** The operator needs a ceiling to ask for, and this works it out. */
    public static final int MODE_NEED = 1;

    public static final double[] RADIUS_PRESETS_M = { 804.7, 1609.3, 3218.7, 8046.7 };
    public static final double[] CEILING_PRESETS_FT = { 200, 400, 1000, 2000, 5000 };
    /** What a pilot wants between the aircraft and the highest thing under it. */
    public static final double DEFAULT_CLEARANCE_FT = 200d;

    public interface Listener {
        void onPlanChanged();
    }

    private final MapView mapView;
    private final Context pluginContext;
    private final LaunchPoint launch;
    private final IslandOverlay islands;
    private Listener listener;

    private int mode;
    private double ceilingAglFt;
    private double radiusM;
    private double clearanceFt;
    private boolean islandsOn;

    private TerrainGrid grid;
    private boolean working;
    private String status = "";

    public PlanManager(MapView mapView, Context pluginContext) {
        this.mapView = mapView;
        this.pluginContext = pluginContext;
        this.islands = new IslandOverlay(mapView);
        this.launch = new LaunchPoint(mapView, pluginContext, new LaunchPoint.Callback() {
            @Override
            public void onPicked(GeoPoint p) {
                recompute();
                changed();
            }

            @Override
            public void onPickCancelled() {
                changed();
            }
        });
        final SharedPreferences p = prefs();
        mode = p.getInt(PREF_MODE, MODE_GIVEN);
        ceilingAglFt = p.getFloat(PREF_CEILING_FT, 400f);
        radiusM = p.getFloat(PREF_RADIUS_M, (float) RADIUS_PRESETS_M[1]);
        clearanceFt = p.getFloat(PREF_CLEARANCE_FT, (float) DEFAULT_CLEARANCE_FT);
        islandsOn = p.getBoolean(PREF_ISLANDS, true);
    }

    public void setListener(Listener l) {
        this.listener = l;
    }

    public void start() {
        islands.setListener(new IslandOverlay.Listener() {
            @Override
            public void onSampled(TerrainGrid g, double groundMslM) {
                grid = g;
                // The ground is only known once the terrain is in memory, and in "Need
                // a ceiling" the whole answer hangs off it, so the ceiling is pushed
                // again here rather than at the moment the operator asked.
                repaintCeiling();
            }

            @Override
            public void onPainted(TerrainGrid g, double ceilingMslM, int islandCells) {
                grid = g;
                working = false;
                changed();
            }

            @Override
            public void onFailed(String reason) {
                working = false;
                status = reason;
                changed();
            }

            @Override
            public void onCleared() {
                grid = null;
                working = false;
                changed();
            }
        });
        islands.start();
        islands.setVisible(islandsOn);
        // The plan the operator set up comes back on its own, with no network: a launch
        // point typed into a phone and lost on the next reload is the bug this rule
        // exists for.
        final GeoPoint saved = launch.saved();
        if (saved != null) {
            launch.place(saved);
            recompute();
        }
    }

    public void stop() {
        launch.dispose();
        islands.stop();
    }

    // ---- what the operator set ----

    public GeoPoint launchPoint() {
        return launch.getPoint();
    }

    public boolean isPicking() {
        return launch.isPicking();
    }

    /** Hands the map over: the next tap is the launch point. */
    public void pickLaunch() {
        launch.startPick();
        changed();
    }

    public void useMyPosition() {
        if (mapView.getSelfMarker() == null)
            return;
        final GeoPoint me = mapView.getSelfMarker().getPoint();
        if (me == null)
            return;
        launch.place(me);
        recompute();
        changed();
    }

    /** Only the operator's own Clear forgets a plan. Stopping never does. */
    public void clearPlan() {
        launch.clear();
        islands.clear();
        grid = null;
        status = "";
        changed();
    }

    public int mode() {
        return mode;
    }

    public void setMode(int value) {
        if (mode == value)
            return;
        mode = value;
        prefs().edit().putInt(PREF_MODE, value).apply();
        recompute();
        changed();
    }

    public double ceilingAglFt() {
        return ceilingAglFt;
    }

    public void setCeilingAglFt(double ft) {
        ceilingAglFt = ft;
        prefs().edit().putFloat(PREF_CEILING_FT, (float) ft).apply();
        recompute();
        changed();
    }

    public double radiusM() {
        return radiusM;
    }

    public void setRadiusM(double m) {
        radiusM = m;
        prefs().edit().putFloat(PREF_RADIUS_M, (float) m).apply();
        recompute();
        changed();
    }

    public double clearanceFt() {
        return clearanceFt;
    }

    public void setClearanceFt(double ft) {
        clearanceFt = ft;
        prefs().edit().putFloat(PREF_CLEARANCE_FT, (float) ft).apply();
        changed();
    }

    public boolean islandsOn() {
        return islandsOn;
    }

    public void setIslandsOn(boolean on) {
        islandsOn = on;
        prefs().edit().putBoolean(PREF_ISLANDS, on).apply();
        islands.setVisible(on);
        changed();
    }

    public boolean isWorking() {
        return working;
    }

    // ---- the answers ----

    /** The ground at the launch point, feet MSL, or NaN before there is one. */
    public double groundMslFt() {
        if (launch.getPoint() == null)
            return Double.NaN;
        final double m = islands.getGroundMslM();
        return Double.isNaN(m) ? Double.NaN : m / 0.3048d;
    }

    /** The highest terrain in the area, feet MSL, or NaN before it is sampled. */
    public double highestTerrainMslFt() {
        final TerrainGrid g = grid;
        if (g == null)
            return Double.NaN;
        double best = Double.NaN;
        for (int i = 0; i < g.msl.length; i++) {
            if (g.inCircle != null && i < g.inCircle.length && !g.inCircle[i])
                continue;
            final double v = g.msl[i];
            if (Double.isNaN(v))
                continue;
            if (Double.isNaN(best) || v > best)
                best = v;
        }
        return Double.isNaN(best) ? Double.NaN : best / 0.3048d;
    }

    /**
     * The ceiling to ask for, feet above the launch point.
     *
     * <p>Highest terrain in the area, plus the clearance, expressed the way the pilot
     * types it into the controller -- above the launch ground, because that is what a
     * controller reports. The MSL figure is shown beside it for anybody working in MSL.
     */
    public double neededAglFt() {
        final double top = highestTerrainMslFt();
        final double ground = groundMslFt();
        if (Double.isNaN(top) || Double.isNaN(ground))
            return Double.NaN;
        return Math.max(0d, top - ground) + clearanceFt;
    }

    /** The ceiling in force as feet MSL, whichever mode is on, or NaN. */
    public double ceilingMslFt() {
        final double ground = groundMslFt();
        if (Double.isNaN(ground))
            return Double.NaN;
        final double agl = mode == MODE_NEED ? neededAglFt() : ceilingAglFt;
        return Double.isNaN(agl) ? Double.NaN : ground + agl;
    }

    /**
     * The obstacles inside the area that reach the ceiling, tallest first.
     *
     * <p>Terrain is only half of what is under an aircraft. The obstacle layer is
     * already holding what is nearby, so this asks it rather than fetching again.
     */
    public List<Obstacle> breaching(List<Obstacle> nearby) {
        final java.util.List<Obstacle> out = new java.util.ArrayList<>();
        final GeoPoint p = launch.getPoint();
        final double ceiling = ceilingMslFt();
        if (p == null || Double.isNaN(ceiling) || nearby == null)
            return out;
        for (Obstacle o : nearby) {
            if (metersBetween(p, o.lat, o.lon) > radiusM)
                continue;
            if (o.amslFt >= ceiling)
                out.add(o);
        }
        java.util.Collections.sort(out, new java.util.Comparator<Obstacle>() {
            @Override
            public int compare(Obstacle a, Obstacle b) {
                return Double.compare(b.amslFt, a.amslFt);
            }
        });
        return out;
    }

    private static double metersBetween(GeoPoint from, double lat, double lon) {
        final double dy = lat - from.getLatitude();
        final double dx = (lon - from.getLongitude())
                * Math.cos(Math.toRadians(from.getLatitude()));
        return Math.sqrt(dy * dy + dx * dx) * 111320d;
    }

    public String status() {
        if (launch.isPicking())
            return "Tap the map where you will launch.";
        if (launch.getPoint() == null)
            return "No launch point yet.";
        if (working)
            return "Working out the terrain...";
        if (!status.isEmpty())
            return status;
        if (grid == null)
            return "No terrain here. Check the map packages for this area.";
        return "";
    }

    /** Resample and repaint for the current launch point, area and ceiling. */
    private void recompute() {
        final GeoPoint p = launch.getPoint();
        if (p == null) {
            islands.clear();
            grid = null;
            return;
        }
        working = true;
        status = "";
        try {
            islands.computeFor(p, Extent.circle(p, radiusM));
            final double ceiling = ceilingMslFt();
            if (!Double.isNaN(ceiling))
                islands.setCeiling(ceiling * 0.3048d);
        } catch (Exception e) {
            working = false;
            status = "The terrain could not be read here.";
            Log.w(TAG, "terrain sample failed", e);
        }
    }

    /** Recolor for a new ceiling without resampling: milliseconds, not seconds. */
    public void repaintCeiling() {
        final double ceiling = ceilingMslFt();
        if (!Double.isNaN(ceiling))
            islands.setCeiling(ceiling * 0.3048d);
    }

    private void changed() {
        if (listener != null)
            listener.onPlanChanged();
    }

    private SharedPreferences prefs() {
        return mapView.getContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
