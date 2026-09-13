package com.atakmap.android.dozercountry.map;

import android.content.Context;

import com.atakmap.android.dozercountry.model.DozerStandard;
import com.atakmap.android.dozercountry.model.SlopeBand;
import com.atakmap.android.dozercountry.model.Standards;
import com.atakmap.android.dozercountry.terrain.SlopeField;
import com.atakmap.android.dozercountry.terrain.TerrainSampler;
import com.atakmap.android.maps.MapView;
import com.atakmap.android.widgets.GradientWidget;
import com.atakmap.android.widgets.LinearLayoutWidget;
import com.atakmap.android.widgets.RootLayoutWidget;
import com.atakmap.coremap.log.Log;
import com.atakmap.coremap.maps.coords.GeoBounds;
import com.atakmap.map.layer.opengl.GLLayerFactory;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Owns the slope overlay for the life of the plugin: the layer, its renderer, the
 * legend, and the worker that computes a field when an area is drawn.
 *
 * <p>Deliberately not inside a {@code Tool} and not inside the pane. ATAK ends the
 * active tool whenever another starts, a dropdown opens or Back is pressed, and the
 * pane comes and goes with the toolbar button. The painting has to survive all of
 * that, so it lives here and the pane only reads and drives it.
 */
public final class SlopeOverlay {

    private static final String TAG = "DozerOverlay";

    /** What the pane is told when a computation finishes. */
    public interface Listener {
        void onComputed(SlopeField field, DozerStandard standard);

        void onComputeFailed(String reason);

        void onCleared();
    }

    private final MapView mapView;
    private final Context pluginContext;
    private final SlopeLayer layer = new SlopeLayer();

    private final ExecutorService worker = Executors.newSingleThreadExecutor(
            new ThreadFactory() {
                @Override
                public Thread newThread(Runnable r) {
                    final Thread t = new Thread(r, "dozer-country-slope");
                    t.setPriority(Thread.NORM_PRIORITY - 1);
                    return t;
                }
            });

    /**
     * Bumped every time a new area is requested. A result whose generation is stale is
     * dropped, so drawing a second area while the first is still computing cannot end
     * with the older raster winning the race and painting the wrong ground.
     */
    private final AtomicInteger generation = new AtomicInteger();

    private GradientWidget legend;
    private boolean started;
    private SlopeField lastField;
    private Listener listener;

    public SlopeOverlay(MapView mapView, Context pluginContext) {
        this.mapView = mapView;
        this.pluginContext = pluginContext;
    }

    public void setListener(Listener l) {
        this.listener = l;
    }

    public SlopeLayer getLayer() {
        return layer;
    }

    public SlopeField getLastField() {
        return lastField;
    }

    public DozerStandard getStandard() {
        return Standards.active(pluginContext);
    }

    /** Puts the layer on the map. Called once when the plugin starts. */
    public void start() {
        if (started)
            return;
        started = true;
        GLLayerFactory.register(GLSlopeLayer.SPI);
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
        hideLegend();
        layer.clear();
        mapView.removeLayer(MapView.RenderStack.MAP_SURFACE_OVERLAYS, layer);
        GLLayerFactory.unregister(GLSlopeLayer.SPI);
        worker.shutdownNow();
    }

    public boolean isVisible() {
        return layer.isVisible() && layer.hasField();
    }

    public void setVisible(boolean visible) {
        layer.setVisible(visible);
        if (visible && layer.hasField())
            showLegend();
        else
            hideLegend();
    }

    public void setOpacity(float opacity) {
        layer.setOpacity(opacity);
    }

    public float getOpacity() {
        return layer.getOpacity();
    }

    /** Removes the painting and the legend, leaving the layer in place for the next area. */
    public void clear() {
        generation.incrementAndGet();
        lastField = null;
        layer.clear();
        layer.setVisible(false);
        hideLegend();
        if (listener != null)
            listener.onCleared();
    }

    /**
     * Samples the area and paints it. Returns immediately; the listener is called on
     * the main thread when the work is done.
     */
    public void computeFor(final GeoBounds bounds) {
        // A pane left on screen by a plugin reload still holds a reference to the
        // overlay that was stopped underneath it. Its worker is shut down, so handing
        // it work would throw RejectedExecutionException straight out of a click
        // handler and take ATAK with it.
        if (!started) {
            fail("Dozer Country was reloaded. Close this panel and open it again.");
            return;
        }

        final DozerStandard standard = Standards.active(pluginContext);
        if (standard == null) {
            fail("The slope standard could not be read. dozer_data.json is missing or invalid.");
            return;
        }

        final int mine = generation.incrementAndGet();
        final double windowM = Standards.workingWindowMeters(pluginContext);
        final double waterM2 = Standards.waterMinAreaM2(pluginContext);

        worker.execute(new Runnable() {
            @Override
            public void run() {
                final SlopeField field;
                try {
                    field = TerrainSampler.sample(bounds, windowM, waterM2);
                } catch (RuntimeException e) {
                    Log.e(TAG, "slope computation failed", e);
                    postFail(mine, "The slope could not be computed for that area.");
                    return;
                }

                if (mine != generation.get())
                    return;

                if (field.isNothingToClass() && !field.isEmpty()) {
                    postFail(mine, "That area is all water. Draw one that covers "
                            + "ground.");
                    return;
                }

                if (field.isEmpty()) {
                    // The honest failure mode: the plugin has no elevation of its own
                    // and this device has none for this ground.
                    postFail(mine, "No elevation data loaded for that area. "
                            + "Load DTED covering it, then draw the area again.");
                    return;
                }

                final int[] argb = field.toArgb(standard);

                mapView.post(new Runnable() {
                    @Override
                    public void run() {
                        if (mine != generation.get())
                            return;
                        lastField = field;
                        layer.setField(argb, field.width, field.height, bounds);
                        layer.setVisible(true);
                        showLegend();
                        if (listener != null)
                            listener.onComputed(field, standard);
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
        lastField = null;
        layer.clear();
        layer.setVisible(false);
        hideLegend();
        if (listener != null)
            listener.onComputeFailed(reason);
    }

    /* ----- the legend ----- */

    /**
     * ATAK's own gradient legend, the one the operator already knows from the elevation
     * overlay. Four colors and four labels; nothing custom drawn.
     */
    private void showLegend() {
        final DozerStandard standard = Standards.active(pluginContext);
        if (standard == null)
            return;

        final LinearLayoutWidget root = legendRoot();
        if (root == null)
            return;

        if (legend == null) {
            legend = new GradientWidget();
            legend.setBarSize(26f, 26f);
            legend.setPadding(8f, 8f, 8f, 8f);
        }

        final List<SlopeBand> bands = standard.bands;
        final boolean above = standard.aboveStandard != null;
        final int n = bands.size() + (above ? 1 : 0);
        final int[] colors = new int[n];
        final String[] labels = new String[n];

        // Steepest at the top, the way a legend of severity reads.
        for (int i = 0; i < bands.size(); i++) {
            final SlopeBand b = bands.get(bands.size() - 1 - i);
            final int at = above ? i + 1 : i;
            colors[at] = opaque(b.argb);
            labels[at] = b.label;
        }
        if (above) {
            colors[0] = opaque(standard.aboveStandard.argb);
            labels[0] = standard.aboveStandard.label;
        }

        legend.setLegend(colors, labels);
        if (legend.getParent() == null)
            root.addChildWidget(legend);
    }

    /**
     * The legend swatches are drawn opaque even though the overlay is translucent.
     * A legend at the overlay's own alpha washes out against whatever is behind it,
     * and the operator is matching hue, not transparency.
     */
    private static int opaque(int argb) {
        return 0xFF000000 | (argb & 0x00FFFFFF);
    }

    private void hideLegend() {
        if (legend == null)
            return;
        final LinearLayoutWidget root = legendRoot();
        if (root != null)
            root.removeChildWidget(legend);
    }

    private LinearLayoutWidget legendRoot() {
        final Object extra = mapView.getComponentExtra("rootLayoutWidget");
        if (!(extra instanceof RootLayoutWidget)) {
            Log.w(TAG, "no root layout widget; legend not shown");
            return null;
        }
        return ((RootLayoutWidget) extra).getLayout(RootLayoutWidget.BOTTOM_LEFT);
    }
}
