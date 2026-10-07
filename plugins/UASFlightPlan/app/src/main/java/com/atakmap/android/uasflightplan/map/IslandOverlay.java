package com.atakmap.android.uasflightplan.map;

import com.atakmap.android.maps.MapView;
import com.atakmap.android.uasflightplan.terrain.TerrainGrid;
import com.atakmap.android.uasflightplan.terrain.TerrainSampler;
import com.atakmap.coremap.log.Log;
import com.atakmap.coremap.maps.coords.GeoPoint;
import com.atakmap.map.layer.opengl.GLLayerFactory;

import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Owns the islands overlay for the life of the plugin: the layer, its renderer, the
 * cached terrain grid, and the worker that samples it when the launch point moves.
 *
 * <p>Deliberately not inside a {@code Tool} and not inside the pane. ATAK ends the
 * active tool whenever another starts, a dropdown opens or Back is pressed, and the
 * pane comes and goes with the toolbar button. The painting has to survive all of
 * that, so it lives here and the pane only reads and drives it.
 *
 * <h3>Two steps, two costs</h3>
 *
 * {@link #computeFor} reads the terrain from disk: a few seconds on the worker, once
 * per launch point. {@link #setCeiling} recolors the cached grid: milliseconds, as
 * often as the pilot moves the ceiling. Nothing here ever resamples for a ceiling
 * change.
 *
 * <p>Every callback arrives on the main thread.
 */
public final class IslandOverlay {

    private static final String TAG = "UASOverlay";

    /** Ground at or above the ceiling: opaque, cannot be here at all. */
    public static final int ISLAND_ARGB = 0xD9E53935;
    /**
     * Ground below the ceiling: a wash, so "under water" reads as water.
     *
     * <p>45%, not the 24% of the first build, which was invisible over imagery at
     * every zoom. The raster goes through a premultiplied bitmap and the wash
     * looked dimmed twice; measured by eye on the XCover, not in GL.
     */
    public static final int WATER_ARGB = 0x731E88E5;
    /** The circle's edge: the limit of what was checked, white so it reads on any ground. */
    public static final int EDGE_ARGB = 0xD9FFFFFF;

    /** What the pane is told. */
    public interface Listener {
        /** The terrain around the launch point is in memory; the ground there is known. */
        void onSampled(TerrainGrid grid, double groundMslM);

        /** The islands are painted for this ceiling. */
        void onPainted(TerrainGrid grid, double ceilingMslM, int islandCells);

        void onFailed(String reason);

        void onCleared();
    }

    private final MapView mapView;
    private final IslandLayer layer = new IslandLayer();

    private final ExecutorService worker = Executors.newSingleThreadExecutor(
            new ThreadFactory() {
                @Override
                public Thread newThread(Runnable r) {
                    final Thread t = new Thread(r, "uas-flight-plan-terrain");
                    t.setPriority(Thread.NORM_PRIORITY - 1);
                    return t;
                }
            });

    /**
     * Bumped every time a new launch point is requested. A result whose generation is
     * stale is dropped, so placing a second point while the first is still sampling
     * cannot end with the older grid winning the race and painting the wrong ground.
     */
    private final AtomicInteger generation = new AtomicInteger();

    private boolean started;
    private Listener listener;

    // Main-thread state, read by the pane.
    private TerrainGrid grid;
    private double groundMslM = Double.NaN;
    private Double ceilingMslM;
    private GeoPoint launch;
    private double radiusM;

    public IslandOverlay(MapView mapView) {
        this.mapView = mapView;
    }

    public void setListener(Listener l) {
        this.listener = l;
    }

    public IslandLayer getLayer() {
        return layer;
    }

    public TerrainGrid getGrid() {
        return grid;
    }

    /** Ground at the launch point, meters MSL, or NaN before one is sampled. */
    public double getGroundMslM() {
        return groundMslM;
    }

    /** The ceiling in force, meters MSL, or null before one is set. */
    public Double getCeilingMslM() {
        return ceilingMslM;
    }

    public GeoPoint getLaunch() {
        return launch;
    }

    public double getRadiusM() {
        return radiusM;
    }

    /** Puts the layer on the map. Called once when the plugin starts. */
    public void start() {
        if (started)
            return;
        started = true;
        GLLayerFactory.register(GLIslandLayer.SPI);
        // MAP_SURFACE_OVERLAYS drapes the raster on the terrain rather than floating it
        // flat above the map, which is what makes it read as ground and not as a sticker.
        mapView.addLayer(MapView.RenderStack.MAP_SURFACE_OVERLAYS, layer);
        layer.setVisible(false);
    }

    /** Takes everything back off. Called when the plugin stops or is reloaded. */
    public void stop() {
        if (!started)
            return;
        started = false;
        layer.clear();
        mapView.removeLayer(MapView.RenderStack.MAP_SURFACE_OVERLAYS, layer);
        GLLayerFactory.unregister(GLIslandLayer.SPI);
        worker.shutdownNow();
    }

    public boolean isVisible() {
        return layer.isVisible() && layer.hasField();
    }

    public void setVisible(boolean visible) {
        layer.setVisible(visible);
    }

    /** Removes the painting and forgets the terrain, leaving the layer in place. */
    public void clear() {
        generation.incrementAndGet();
        grid = null;
        groundMslM = Double.NaN;
        launch = null;
        layer.clear();
        layer.setVisible(false);
        if (listener != null)
            listener.onCleared();
    }

    /**
     * Samples the circle around a launch point. Returns immediately; the listener
     * hears {@link Listener#onSampled} on the main thread, and then
     * {@link Listener#onPainted} if a ceiling is already in force.
     */
    public void computeFor(final GeoPoint point, final double radius) {
        // A pane left on screen by a plugin reload still holds a reference to the
        // overlay that was stopped underneath it. Its worker is shut down, so handing
        // it work would throw RejectedExecutionException straight out of a click
        // handler and take ATAK with it.
        if (!started) {
            fail("UAS Flight Plan was reloaded. Close this panel and open it again.");
            return;
        }

        launch = point;
        radiusM = radius;
        final int mine = generation.incrementAndGet();

        worker.execute(new Runnable() {
            @Override
            public void run() {
                // Check the data BEFORE computing anything from it. getElevation will
                // happily return a number off DTED0 at a kilometer per post, and that
                // number would paint a confident sea over a ridge nothing can see.
                final TerrainSampler.Coverage coverage =
                        TerrainSampler.surveyCoverage(point, radius);
                if (!coverage.meetsDted2()) {
                    postFail(mine, noDted2Message(coverage));
                    return;
                }

                final double ground = TerrainSampler.groundMsl(point);
                final TerrainGrid g;
                try {
                    g = TerrainSampler.sample(point, radius, coverage);
                } catch (RuntimeException e) {
                    Log.e(TAG, "terrain sampling failed", e);
                    postFail(mine, "The terrain around that point could not be read.");
                    return;
                }
                if (mine != generation.get())
                    return;
                if (g.knownCells() == 0 || Double.isNaN(ground)) {
                    postFail(mine, "No terrain data here. " + LOAD_DTED2);
                    return;
                }

                mapView.post(new Runnable() {
                    @Override
                    public void run() {
                        if (mine != generation.get())
                            return;
                        grid = g;
                        groundMslM = ground;
                        if (listener != null)
                            listener.onSampled(g, ground);
                        if (ceilingMslM != null)
                            paint(g, ceilingMslM, mine);
                    }
                });
            }
        });
    }

    /**
     * Sets the ceiling and repaints the cached terrain for it. Before a launch point
     * is sampled this only remembers the value; the paint follows the sample.
     */
    public void setCeiling(double mslM) {
        ceilingMslM = mslM;
        if (grid != null)
            paint(grid, mslM, generation.get());
    }

    private void paint(final TerrainGrid g, final double ceiling, final int mine) {
        if (!started)
            return;
        worker.execute(new Runnable() {
            @Override
            public void run() {
                final int[] argb = g.toArgb(ceiling, ISLAND_ARGB, WATER_ARGB, EDGE_ARGB);
                final int islands = g.islandCells(ceiling);
                mapView.post(new Runnable() {
                    @Override
                    public void run() {
                        if (mine != generation.get() || grid != g)
                            return;
                        layer.setField(argb, g.width, g.height, g.bounds);
                        layer.setVisible(true);
                        if (listener != null)
                            listener.onPainted(g, ceiling, islands);
                    }
                });
            }
        });
    }

    private void postFail(final int mine, final String reason) {
        mapView.post(new Runnable() {
            @Override
            public void run() {
                if (mine != generation.get())
                    return;
                fail(reason);
            }
        });
    }

    private void fail(String reason) {
        grid = null;
        groundMslM = Double.NaN;
        layer.clear();
        layer.setVisible(false);
        if (listener != null)
            listener.onFailed(reason);
    }

    /**
     * What to do about it, in the pilot's terms and naming the tool that does it.
     * "Load DTED" on its own is a fact about the world, not an instruction.
     */
    private static final String LOAD_DTED2 =
            "Load DTED2 covering this ground (the Map Depot plugin can download it), "
                    + "then place the launch point again.";

    /**
     * Says what is actually there as well as what is missing.
     *
     * <p>"No elevation data" is wrong and unhelpful when the device has DTED0: the
     * pilot will look at the map, see terrain shading, and conclude the plugin is
     * broken. Naming what was found is what makes the refusal believable.
     */
    private static String noDted2Message(TerrainSampler.Coverage coverage) {
        if (coverage.isEmpty())
            return "No terrain data here. " + LOAD_DTED2;
        if (coverage.good == 0)
            return String.format(Locale.US,
                    "Only %s covers this ground. That is too coarse to find a ridge "
                            + "between its posts, so nothing is painted. %s",
                    coverage.describe(), LOAD_DTED2);
        return "Only part of the circle has DTED2 or better; the rest is "
                + coverage.describe() + ". " + LOAD_DTED2;
    }
}
