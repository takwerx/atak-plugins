package com.atakmap.android.dozercountry.map;

import android.content.Context;

import com.atakmap.android.dozercountry.model.DozerStandard;
import com.atakmap.android.dozercountry.model.SlopeBand;
import com.atakmap.android.dozercountry.model.Standards;
import com.atakmap.android.dozercountry.terrain.SlopeField;
import com.atakmap.android.dozercountry.terrain.TerrainSampler;
import com.atakmap.android.maps.MapTextFormat;
import com.atakmap.android.maps.MapView;
import com.atakmap.android.widgets.GradientWidget;
import com.atakmap.android.widgets.LinearLayoutWidget;
import com.atakmap.android.widgets.RootLayoutWidget;
import com.atakmap.coremap.log.Log;
import com.atakmap.coremap.maps.coords.GeoBounds;
import com.atakmap.map.layer.opengl.GLLayerFactory;

import java.util.List;
import java.util.Locale;
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

    /**
     * A dark plate behind the legend.
     *
     * <p>{@link GradientWidget} draws its labels straight onto whatever is under them,
     * which is fine over ATAK's elevation overlay and not fine here: the legend sits
     * bottom-left over a basemap that is mostly white, and on the first manual shot
     * "Over 75%" was unreadable behind a creek name. The plate is the same dark the
     * coordinate readout uses, so the corner reads as one of ATAK's own boxes.
     */
    private LinearLayoutWidget legendPlate;
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
    public void computeFor(final Area area) {
        final GeoBounds bounds = area.bounds;
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
                // Check the data BEFORE computing anything from it. getElevation will
                // happily return a number off DTED0 at a kilometre per post, and that
                // number would paint confident safety bands over terrain nothing can
                // actually see.
                final TerrainSampler.Coverage coverage =
                        TerrainSampler.surveyCoverage(bounds, area.ring);
                if (!coverage.meetsDted2()) {
                    postFail(mine, noDted2Message(coverage));
                    return;
                }

                final SlopeField field;
                try {
                    field = TerrainSampler.sample(bounds, windowM, waterM2, area.ring);
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

                // A source can cover the area and still have holes, and a hole is not
                // a slope of zero. Anything short of full coverage is refused rather
                // than painted with gaps: an operator reading a safety overlay should
                // not have to notice which parts of it are missing.
                if (field.unknownCells > 0) {
                    final double pct = 100d * field.unknownCells
                            / (field.width * (double) field.height);
                    postFail(mine, String.format(Locale.US,
                            "%.0f%% of that area has no elevation data. %s",
                            Math.max(1d, pct), LOAD_DTED2));
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

    /**
     * What to do about it, in the operator's terms and naming the tool that does it.
     * "Load DTED" on its own is a fact about the world, not an instruction.
     */
    private static final String LOAD_DTED2 =
            "Load DTED2 covering it \u2014 the Map Depot plugin can download it \u2014 "
                    + "then draw the area again.";

    /**
     * Says what is actually there as well as what is missing.
     *
     * <p>"No elevation data" is wrong and unhelpful when the device has DTED1: the
     * operator will look at the map, see terrain shading, and conclude the plugin is
     * broken. Naming the resolution that was found is what makes the refusal
     * believable.
     */
    private static String noDted2Message(TerrainSampler.Coverage coverage) {
        if (coverage.isEmpty()) {
            return "No elevation data for that area. Dozer Country needs DTED2 "
                    + "(30 m posts) or better to say where a dozer can work. "
                    + LOAD_DTED2;
        }
        if (coverage.good == 0) {
            return "That area is only covered by " + coverage.describe()
                    + ". That is too coarse to say where a dozer can work \u2014 Dozer "
                    + "Country needs DTED2 (30 m posts) or better. " + LOAD_DTED2;
        }
        // Mixed: some of it is fine and some is not, which is the case an operator is
        // most likely to misread, because the map looks the same either way.
        return "Only part of that area has DTED2 or better; the rest is "
                + coverage.describe() + ". " + LOAD_DTED2;
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
            legend.setPadding(8f, 0f, 8f, 8f);
        }
        if (legendPlate == null) {
            legendPlate = new LinearLayoutWidget();
            legendPlate.setOrientation(LinearLayoutWidget.VERTICAL);
            legendPlate.setBackingColor(LEGEND_PLATE_COLOR);
            legendPlate.addChildWidget(legend);
        }

        // Only the bands that are actually shaded. A color key for something the map
        // never draws is worse than no key: it invites the operator to go looking for
        // a green that is deliberately not there.
        final List<SlopeBand> painted = new java.util.ArrayList<>();
        if (standard.aboveStandard != null && standard.aboveStandard.paint)
            painted.add(standard.aboveStandard);
        for (int i = standard.bands.size() - 1; i >= 0; i--) {
            final SlopeBand b = standard.bands.get(i);
            if (b.paint)
                painted.add(b);
        }
        if (painted.isEmpty())
            return;

        final int[] colors = new int[painted.size()];
        final String[] labels = new String[painted.size()];
        for (int i = 0; i < painted.size(); i++) {
            colors[i] = opaque(painted.get(i).argb);
            labels[i] = painted.get(i).label;
        }

        legend.setLegend(colors, labels);
        sizePlate(labels);
        if (legendPlate.getParent() == null)
            root.addChildWidget(legendPlate);
    }

    /**
     * The legend swatches are drawn opaque even though the overlay is translucent.
     * A legend at the overlay's own alpha washes out against whatever is behind it,
     * and the operator is matching hue, not transparency.
     */
    /** ATAK's own readout plate: black at about 70%, dark enough for white text. */
    private static final int LEGEND_PLATE_COLOR = 0xB3000000;

    /** Space {@link GradientWidget} leaves between its bar and its labels. */
    private static final float LEGEND_LABEL_GAP = 8f;

    /**
     * A little plate below the legend, so the bottom row never sits on the edge.
     *
     * <p>Measured on the XCover: the legend draws its bar and labels starting exactly
     * one top-padding above the plate's own top edge, whatever the plate's height, so
     * the strip hung out in the open until the top padding went to zero -- the
     * operator, watching: "vertical side look box not big enough". With the top flush
     * the only slack that can be added lands at the bottom, which is where it is
     * wanted anyway.
     */
    private static final float LEGEND_PLATE_SLACK = 10f;

    /**
     * Size the plate to the text, because the legend does not.
     *
     * <p>{@link GradientWidget} reports a width covering its bar and its padding but
     * not its labels, so a backing drawn at the widget's own size stops part way
     * through the longest one and its tail sits on the bare map — which is the whole
     * thing the plate is there to prevent. The operator caught it on the first build
     * that had a plate at all: "its not completely covering all the text".
     *
     * <p>The labels are measured with the widget's own {@link MapTextFormat}, so this
     * is the width it will really draw rather than an estimate, and the result is
     * never allowed to shrink the widget's own reported size.
     */
    private void sizePlate(String[] labels) {
        try {
            final MapTextFormat tf = legend.getTextFormat();
            final float[] bar = legend.getBarSize();
            final float[] pad = legend.getPadding();
            if (tf == null || bar == null || bar.length < 2 || pad == null || pad.length < 4)
                return;

            int widest = 0;
            for (int i = 0; i < labels.length; i++) {
                final int w = tf.measureTextWidth(labels[i]);
                if (w > widest)
                    widest = w;
            }

            final float width = Math.max(legend.getWidth(),
                    pad[0] + bar[0] + LEGEND_LABEL_GAP + widest + pad[2]);
            final float height = legend.getHeight() + LEGEND_PLATE_SLACK;
            if (width > 0f && height > 0f)
                legendPlate.setLayoutParams((int) Math.ceil(width), (int) Math.ceil(height));
        } catch (RuntimeException e) {
            // A legend a little too narrow is worth far less than a crash.
            Log.w(TAG, "could not size the legend plate", e);
        }
    }

    private static int opaque(int argb) {
        return 0xFF000000 | (argb & 0x00FFFFFF);
    }

    private void hideLegend() {
        if (legendPlate == null)
            return;
        final LinearLayoutWidget root = legendRoot();
        if (root != null)
            root.removeChildWidget(legendPlate);
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
