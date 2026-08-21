
package com.atakmap.android.plss.graphics;

import android.graphics.Color;
import android.opengl.GLES20;
import android.util.Pair;

import com.atakmap.android.maps.MapTextFormat;
import com.atakmap.android.maps.graphics.GLSegmentFloatingLabel;
import com.atakmap.android.plss.PlssOverlay;
import com.atakmap.android.plss.PlssStore;
import com.atakmap.coremap.log.Log;
import com.atakmap.coremap.maps.coords.GeoPoint;
import com.atakmap.map.MapRenderer;
import com.atakmap.map.layer.Layer;
import com.atakmap.map.layer.opengl.GLAbstractLayer2;
import com.atakmap.map.layer.opengl.GLLayer2;
import com.atakmap.map.layer.opengl.GLLayerSpi2;
import com.atakmap.map.opengl.GLMapView;
import com.atakmap.map.opengl.GLRenderGlobals;
import com.atakmap.math.MathUtils;
import com.atakmap.math.PointD;
import com.atakmap.opengl.GLES20FixedPipeline;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.DoubleBuffer;
import java.nio.FloatBuffer;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Renderer for {@link PlssOverlay}, mirroring GLGridLinesOverlay.
 *
 * Draws lines directly rather than materializing map items -- that distinction is
 * the whole point of the design (PLAN-PLSS-v0.1.md section 5.1), since the section
 * layer is millions of polygons.
 *
 * Two tiers, each with its own zoom band: townships carry the survey identity
 * ("T19S-R25E") and come in first; sections and their numbers come in closer.
 * Thresholds are tuned against a real device rather than taken from BLM's own
 * cartographic breaks, which assume a desktop screen.
 *
 * Geometry is fetched off the GL thread for the visible box plus a margin, so a
 * small pan redraws from what is already loaded instead of hitting SQLite. Each
 * tier's whole visible set draws in a single GL_LINES call.
 *
 * Note: anonymous classes rather than lambdas throughout. Lambdas break under
 * release proguard -- see CLAUDE.md.
 */
public class GLPlssOverlay extends GLAbstractLayer2
        implements PlssOverlay.OnPlssColorChangedListener {

    public static final String TAG = "GLPlssOverlay";

    /**
     * ATAK instantiates this renderer through the SPI when a PlssOverlay is added
     * to the map. Registered with GLLayerFactory by the plugin at startup.
     */
    public static final GLLayerSpi2 SPI2 = new GLLayerSpi2() {
        @Override
        public int getPriority() {
            // PlssOverlay : Layer
            return 1;
        }

        @Override
        public GLLayer2 create(Pair<MapRenderer, Layer> object) {
            if (!(object.second instanceof PlssOverlay))
                return null;

            return new GLPlssOverlay(object.first, (PlssOverlay) object.second);
        }
    };

    /** Fraction of the view size loaded beyond each edge, so small pans are free. */
    private static final double MARGIN = 0.25;

    /**
     * Largest share of a feature's on-screen width a label may occupy.
     *
     * A label wider than its own township spills across the boundary lines on
     * both sides, which is what the grid is for. Requiring clearance means a
     * label simply waits until the feature is big enough to hold it.
     */
    private static final float LABEL_FIT = 0.7f;

    /**
     * How far a label may sit from its own feature's centre, as a fraction of
     * the feature's projected diagonal, before it is dropped instead of drawn.
     *
     * GLSegmentFloatingLabel slides a label along its segment to keep it on
     * screen: when one end of the segment is outside the viewport it clips the
     * segment to the view and re-places the label at the weighted point of what
     * is left. That is right for a grid line running off the edge and wrong for
     * a label naming a cell -- a township half off screen ends up with its name
     * pressed against the boundary it shares with its neighbour, reading as two
     * labels in one cell.
     *
     * Measured rather than assumed: instrumenting getTextPoint against the
     * projected box centre showed the displacement is *exactly* zero for every
     * feature whose index box is wholly on screen, and reached 0.44 of the
     * diagonal for those that were not. So the slide is the only thing this
     * rejects.
     *
     * The displacement is (1 - visible)/2 of the diagonal, so a quarter admits
     * any feature at least half on screen and holds the label inside the middle
     * half of its own cell. A feature less than half visible loses its label,
     * which is what ATAK's own grid does at the screen edge.
     */
    private static final float LABEL_MAX_SLIDE = 0.25f;

    /** metres per degree of latitude, near enough for a fit test */
    private static final double METRES_PER_DEGREE = 111320.0;

    /**
     * One zoom tier's worth of geometry, labels and load state.
     *
     * Thresholds were measured on the device rather than derived: ATAK's scale bar
     * changes length to suit round numbers, so it does not convert to a fixed
     * metres-per-pixel and cannot be used to work them out on paper.
     */
    private static final class Tier {
        final String table;
        final boolean township;

        /**
         * Metres per pixel above which this tier is not drawn at all -- lines and
         * labels share it deliberately. Lines without their numbers are just
         * noise on the map, so the two must switch on together; one field makes
         * that impossible to get wrong.
         */
        final double maxResolution;
        final float lineWidth;
        final int featureLimit;

        final AtomicBoolean loading = new AtomicBoolean(false);

        DoubleBuffer geo;
        FloatBuffer screen;
        int vertexCount;

        DoubleBuffer labelGeo;
        FloatBuffer labelScreen;
        String[] labels;

        /**
         * One ATAK floating label per feature, built when the tier loads rather
         * than per frame -- each one carries the projected state the class
         * memoises against the draw version.
         */
        GLSegmentFloatingLabel[] floating;

        double west, south, east, north;
        boolean loaded;

        Tier(String table, double maxResolution, float lineWidth,
                int featureLimit) {
            this.table = table;
            this.township = "township".equals(table);
            this.maxResolution = maxResolution;
            this.lineWidth = lineWidth;
            this.featureLimit = featureLimit;
        }

        void clear() {
            geo = null;
            screen = null;
            vertexCount = 0;
            labelGeo = null;
            labelScreen = null;
            labels = null;
            floating = null;
        }
    }

    private final PlssOverlay subject;

    private final Tier townships = new Tier("township", 28.0, 3f, 4000);
    private final Tier sections = new Tier("section", 14.5, 1.5f, 20000);

    private final ExecutorService loader = Executors.newSingleThreadExecutor();

    /**
     * ATAK's default text format, used both to size the labels and to measure
     * them for the fit test.
     *
     * Deliberately the default and not a size of our own: GLSegmentFloatingLabel
     * otherwise builds itself a GLText two sizes up, and GLText shares glyph
     * state between instances -- a second, larger face perturbs the smaller
     * one's metrics and truncates labels mid-string.
     */
    private MapTextFormat textFormat;

    /** scratch for reading back where a label actually landed */
    private final PointD probe = new PointD(0d, 0d, 0d);

    /** written on the UI thread by the colour picker, read on the GL thread */
    private volatile int sectionColor;
    private volatile int townshipColor;
    private volatile int sectionLabelColor;
    private volatile int townshipLabelColor;

    public GLPlssOverlay(MapRenderer surface, PlssOverlay subject) {
        // Lines on the surface so they warp with the map and sit under ATAK's
        // own markers; labels in the sprites pass, which is where ATAK draws
        // its own text and the only pass that does not cut a text quad at a
        // surface tile seam.
        super(surface, subject, GLMapView.RENDER_PASS_SURFACE
                | GLMapView.RENDER_PASS_SPRITES);

        this.subject = subject;
        this.sectionColor = subject.getSectionColor();
        this.townshipColor = subject.getTownshipColor();
        this.sectionLabelColor = subject.getSectionLabelColor();
        this.townshipLabelColor = subject.getTownshipLabelColor();
    }

    @Override
    public void start() {
        super.start();

        subject.addOnPlssColorChangedListener(this);
        // pick up any change made while this renderer did not exist
        onPlssColorChanged(subject);
    }

    @Override
    public void stop() {
        subject.removeOnPlssColorChangedListener(this);

        super.stop();
    }

    @Override
    public void release() {
        // release() can be reached without stop() on renderer teardown
        subject.removeOnPlssColorChangedListener(this);

        loader.shutdownNow();

        townships.clear();
        townships.loaded = false;
        sections.clear();
        sections.loaded = false;

        super.release();
    }

    @Override
    public void onPlssColorChanged(PlssOverlay overlay) {
        sectionColor = overlay.getSectionColor();
        townshipColor = overlay.getTownshipColor();
        sectionLabelColor = overlay.getSectionLabelColor();
        townshipLabelColor = overlay.getTownshipLabelColor();

        // ask for a redraw; without this the new colour waits for the next
        // unrelated map movement
        invalidate();
    }

    @Override
    protected void drawImpl(GLMapView view, int renderPass) {

        if (MathUtils.hasBits(renderPass, GLMapView.RENDER_PASS_SURFACE)) {
            final GLMapView.State scene = view.currentScene;

            // Sections first so the township grid draws over it -- the coarse
            // frame has to stay readable where the two coincide, which is every
            // township boundary.
            updateTier(scene, sections);
            updateTier(scene, townships);

            drawLines(view, scene, sections, sectionColor);
            drawLines(view, scene, townships, townshipColor);
        }

        if (MathUtils.hasBits(renderPass, GLMapView.RENDER_PASS_SPRITES)) {
            // GLSegmentFloatingLabel projects against currentPass.scene, so the
            // fit and placement tests here have to use the same state.
            final GLMapView.State pass = view.currentPass;

            drawLabels(view, pass, townships, townshipLabelColor);
            drawLabels(view, pass, sections, sectionLabelColor);
        }
    }

    /** Drops or reloads a tier for the current view. */
    private void updateTier(GLMapView.State scene, Tier tier) {
        // Zoomed too far out for this tier to be readable -- and far too many to
        // draw. Drop what is loaded so panning at this zoom stays cheap.
        if (scene.drawMapResolution > tier.maxResolution) {
            if (tier.loaded) {
                tier.clear();
                tier.loaded = false;
            }
            return;
        }

        maybeLoad(tier, scene);
    }

    private void drawLines(GLMapView view, GLMapView.State scene, Tier tier,
            int c) {

        if (scene.drawMapResolution > tier.maxResolution
                || tier.geo == null || tier.vertexCount == 0)
            return;

        // geo -> screen every frame; the projection changes as the map moves
        tier.geo.rewind();
        tier.screen.rewind();
        view.forward(tier.geo, tier.screen);
        tier.screen.rewind();

        GLES20FixedPipeline
                .glEnableClientState(GLES20FixedPipeline.GL_VERTEX_ARRAY);
        GLES20FixedPipeline.glVertexPointer(2, GLES20.GL_FLOAT, 0, tier.screen);
        GLES20FixedPipeline.glColor4f(Color.red(c) / 255f,
                Color.green(c) / 255f,
                Color.blue(c) / 255f,
                Color.alpha(c) / 255f);
        GLES20FixedPipeline.glLineWidth(tier.lineWidth);
        GLES20FixedPipeline.glDrawArrays(GLES20.GL_LINES, 0, tier.vertexCount);
        GLES20FixedPipeline
                .glDisableClientState(GLES20FixedPipeline.GL_VERTEX_ARRAY);
    }

    /**
     * Labels drawn by ATAK's own {@link GLSegmentFloatingLabel}, one per feature,
     * along the feature's index-box diagonal.
     *
     * The class supplies its own dark backdrop and, unlike a hand-rolled text
     * quad, is drawn in the pass ATAK itself uses for text -- so it never gets
     * cut at a surface tile seam, which is the whole reason for the move.
     */
    private void drawLabels(GLMapView view, GLMapView.State scene, Tier tier,
            int c) {

        if (scene.drawMapResolution > tier.maxResolution
                || tier.floating == null || tier.labelGeo == null
                || tier.labels == null)
            return;

        if (textFormat == null)
            textFormat = GLRenderGlobals.getDefaultTextFormat();

        tier.labelGeo.rewind();
        tier.labelScreen.rewind();
        view.forward(tier.labelGeo, tier.labelScreen);
        tier.labelScreen.rewind();

        // the rectangle GLSegmentFloatingLabel itself tests a segment against,
        // via GLArrow2.getWidgetViewF -- currentScene, not currentPass
        final float vl = view.currentScene.left;
        final float vr = view.currentScene.right;
        final float vb = view.currentScene.bottom;
        final float vt = view.currentScene.top;

        int considered = 0;
        int drawn = 0;
        int slid = 0;
        int dropped = 0;
        float maxMove = 0f;
        float maxMoveFrac = 0f;

        for (int i = 0; i < tier.floating.length; i++) {
            final GLSegmentFloatingLabel l = tier.floating[i];
            if (l == null)
                continue;

            // the feature's index box, projected: SW at 4i, NE at 4i+2
            final float sx = tier.labelScreen.get(i * 4);
            final float sy = tier.labelScreen.get(i * 4 + 1);
            final float nx = tier.labelScreen.get(i * 4 + 2);
            final float ny = tier.labelScreen.get(i * 4 + 3);

            final float cx = (sx + nx) / 2f;
            final float cy = (sy + ny) / 2f;

            // cheap reject -- most of a padded load is off screen
            if (cx < vl - 256f || cx > vr + 256f
                    || cy < vb - 256f || cy > vt + 256f)
                continue;

            // A label wider than its own feature spills across the boundary
            // lines on both sides. Measured in ground units so the test holds
            // when the map is rotated, where the projected box corners no
            // longer bracket the feature's width.
            final double west = tier.labelGeo.get(i * 4);
            final double south = tier.labelGeo.get(i * 4 + 1);
            final double east = tier.labelGeo.get(i * 4 + 2);
            final double north = tier.labelGeo.get(i * 4 + 3);

            final double widthPx = Math.abs(east - west) * METRES_PER_DEGREE
                    * Math.cos(Math.toRadians((south + north) / 2.0))
                    / Math.max(scene.drawMapResolution, 0.0001);

            if (textFormat.measureTextWidth(tier.labels[i]) > widthPx
                    * LABEL_FIT)
                continue;

            considered++;

            l.setTextColor(c);

            // update() is memoised against the draw version, so reading the
            // chosen position back here costs nothing extra: the draw() below
            // reuses it.
            l.update(view);
            l.getTextPoint(probe);

            final float move = (float) Math.hypot((float) probe.x - cx,
                    (float) probe.y - cy);
            final float diag = (float) Math.hypot(nx - sx, ny - sy);

            if (move > 1f) {
                if (move > maxMove)
                    maxMove = move;
                if (diag > 0f && move / diag > maxMoveFrac)
                    maxMoveFrac = move / diag;
                slid++;
            }

            // the class has carried this label too far from the feature it
            // names -- drop it rather than let it read as the neighbour's
            if (diag > 0f && move > diag * LABEL_MAX_SLIDE) {
                dropped++;
                continue;
            }

            l.draw(view);
            drawn++;
        }

        // Throttled, and only when the rule actually fires. Keeping it means the
        // next person can re-establish the numbers above from a running device
        // instead of re-deriving them: `slid` counts labels the class moved at
        // all, `dropped` those it moved too far.
        final long now = System.currentTimeMillis();
        if (dropped > 0 && now - lastLabelLog > 5000L) {
            lastLabelLog = now;
            Log.d(TAG, "labels " + tier.table
                    + " considered=" + considered
                    + " drawn=" + drawn
                    + " slid=" + slid
                    + " dropped=" + dropped
                    + " maxSlidePx=" + String.format("%.1f", maxMove)
                    + " maxSlideFrac=" + String.format("%.2f", maxMoveFrac));
        }
    }

    /** throttles the label-placement log above */
    private long lastLabelLog;

    /** Kicks a background load when the view has left this tier's loaded box. */
    private void maybeLoad(final Tier tier, GLMapView.State scene) {
        if (tier.loaded
                && scene.westBound >= tier.west
                && scene.eastBound <= tier.east
                && scene.southBound >= tier.south
                && scene.northBound <= tier.north)
            return;

        final java.util.List<PlssStore> stores = subject.getStores();
        if (stores.isEmpty())
            return;

        // one load in flight per tier; a fast pan should not queue up work
        if (!tier.loading.compareAndSet(false, true))
            return;

        final double padX = (scene.eastBound - scene.westBound) * MARGIN;
        final double padY = (scene.northBound - scene.southBound) * MARGIN;

        final double west = scene.westBound - padX;
        final double east = scene.eastBound + padX;
        final double south = scene.southBound - padY;
        final double north = scene.northBound + padY;

        Log.d(TAG, tier.table + " load at "
                + String.format("%.1f", scene.drawMapResolution) + " m/px");

        loader.execute(new Runnable() {
            @Override
            public void run() {
                PlssStore.Result loaded = null;
                try {
                    // every installed pack is asked; the R-tree makes a pack
                    // that does not cover this box practically free
                    final java.util.List<PlssStore.Result> parts =
                            new java.util.ArrayList<>();
                    for (PlssStore store : stores) {
                        final PlssStore.Result part = "township"
                                .equals(tier.table)
                                        ? store.queryTownships(west, south,
                                                east, north,
                                                tier.featureLimit)
                                        : store.querySections(west, south,
                                                east, north,
                                                tier.featureLimit);
                        if (part != null)
                            parts.add(part);
                    }
                    loaded = PlssStore.Result.merge(parts);
                } catch (Exception e) {
                    Log.e(TAG, tier.table + " query failed", e);
                }

                final PlssStore.Result result = loaded;
                runOnGLThread(new Runnable() {
                    @Override
                    public void run() {
                        install(tier, result, west, south, east, north);
                        tier.loading.set(false);
                        invalidate();
                    }
                });
            }
        });
    }

    /** GL thread only. */
    private void install(Tier tier, PlssStore.Result loaded, double west,
            double south, double east, double north) {

        tier.west = west;
        tier.south = south;
        tier.east = east;
        tier.north = north;
        tier.loaded = true;

        tier.clear();

        if (loaded == null)
            return;

        tier.geo = loaded.segments;
        tier.vertexCount = loaded.segmentVertexCount;
        tier.screen = ByteBuffer
                .allocateDirect(tier.vertexCount * 2 * 4)
                .order(ByteOrder.nativeOrder()).asFloatBuffer();

        if (loaded.labelPoints != null && loaded.labels.length > 0) {
            tier.labelGeo = loaded.labelPoints;
            tier.labels = loaded.labels;
            // two points per label -- the projected corners of its index box
            tier.labelScreen = ByteBuffer
                    .allocateDirect(tier.labels.length * 4 * 4)
                    .order(ByteOrder.nativeOrder()).asFloatBuffer();
            tier.floating = buildFloatingLabels(tier, loaded);
        }
    }

    /**
     * One label per feature, laid along its index box's SW-NE diagonal.
     *
     * Built here rather than per frame: the class caches its projected state
     * against the draw version, so a per-frame instance would recompute
     * everything and allocate for every feature on screen.
     *
     * Clamping to ground is off. With it on the labels shift as terrain streams
     * in, because the clamp resolves against elevation that arrives late.
     */
    private GLSegmentFloatingLabel[] buildFloatingLabels(Tier tier,
            PlssStore.Result loaded) {

        if (textFormat == null)
            textFormat = GLRenderGlobals.getDefaultTextFormat();

        final int n = loaded.labels.length;
        final GLSegmentFloatingLabel[] out = new GLSegmentFloatingLabel[n];
        final DoubleBuffer pts = loaded.labelPoints;

        for (int i = 0; i < n; i++) {
            final GeoPoint sw = new GeoPoint(pts.get(i * 4 + 1),
                    pts.get(i * 4));
            final GeoPoint ne = new GeoPoint(pts.get(i * 4 + 3),
                    pts.get(i * 4 + 2));

            final GLSegmentFloatingLabel l = new GLSegmentFloatingLabel();
            l.setTextFormat(textFormat);
            l.setClampToGround(false);
            l.setRotateToAlign(false);
            l.setBackgroundColor(0f, 0f, 0f, 0.6f);
            l.setSegmentPositionWeight(0.5f);
            l.setSegment(new GeoPoint[] {
                    sw, ne
            });
            l.setText(tier.labels[i]);
            l.setInsets(0f, 0f, 0f, 0f);
            out[i] = l;
        }
        return out;
    }
}
