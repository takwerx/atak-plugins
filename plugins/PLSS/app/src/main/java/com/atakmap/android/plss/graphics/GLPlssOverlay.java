
package com.atakmap.android.plss.graphics;

import android.graphics.Color;
import android.util.Pair;

import com.atakmap.android.maps.MapTextFormat;
import com.atakmap.android.maps.graphics.GLSegmentFloatingLabel;
import com.atakmap.android.plss.PlssOverlay;
import com.atakmap.android.plss.PlssStore;
import com.atakmap.coremap.log.Log;
import com.atakmap.coremap.maps.coords.GeoPoint;
import com.atakmap.map.MapRenderer;
import com.atakmap.map.layer.Layer;
import com.atakmap.map.layer.feature.Feature;
import com.atakmap.map.layer.opengl.GLAbstractLayer2;
import com.atakmap.map.layer.opengl.GLLayer2;
import com.atakmap.map.layer.opengl.GLLayerSpi2;
import com.atakmap.map.opengl.GLAntiAliasedLine;
import com.atakmap.map.opengl.GLMapView;
import com.atakmap.map.opengl.GLRenderGlobals;
import com.atakmap.math.MathUtils;
import com.atakmap.math.PointD;

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
     * Below this the clipped segment is treated as degenerate and the label
     * skipped -- guards the division that solves for the segment weight.
     */
    private static final float MIN_CLIP_SPAN = 1e-4f;


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

        /**
         * ATAK's own line renderer, which holds the grid in map-projection
         * space and applies the pass's scene matrix itself. See drawLines.
         */
        GLAntiAliasedLine line;

        DoubleBuffer labelGeo;
        FloatBuffer labelScreen;
        String[] labels;

        /**
         * One ATAK floating label per feature, built when the tier loads rather
         * than per frame -- each one carries the projected state the class
         * memoises against the draw version.
         */
        GLSegmentFloatingLabel[] floating;

        /**
         * Segment weight last handed to each label. Setting a weight discards
         * the class's cached placement, so it is only set when it changes.
         */
        float[] weights;

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
            if (line != null) {
                line.release();
                line = null;
            }
            labelGeo = null;
            labelScreen = null;
            labels = null;
            floating = null;
            weights = null;
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
        // Lines on the surface so they are warped along with the imagery while
        // the map moves rather than re-projected against it; labels in the
        // sprites pass, which is where ATAK draws its own text and the only
        // pass that does not cut a text quad at a surface tile seam.
        super(surface, subject, GLMapView.RENDER_PASS_SURFACE);

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

            // The surface pass is invoked more than once per frame, each
            // invocation carrying its own currentPass -- drawVersion changes
            // between them, so renderPump is what identifies a frame, not
            // drawVersion. The count is logged because it is what to look at
            // first if labels ever ghost again.
            if (view.currentPass.renderPump != lastRenderPump) {
                lastRenderPump = view.currentPass.renderPump;
                if (surfacePasses > 0 && surfacePasses != loggedPasses) {
                    loggedPasses = surfacePasses;
                    Log.d(TAG, "surface invocations per frame = "
                            + surfacePasses);
                }
                if (!labelsDrawnThisFrame && !useFirstInvocation) {
                    if (++blankFrames > 60) {
                        useFirstInvocation = true;
                        Log.w(TAG, "no final surface part seen in " + blankFrames
                                + " frames; drawing labels on the first"
                                + " invocation instead");
                    }
                } else {
                    blankFrames = 0;
                }
                surfacePasses = 0;
                labelsDrawnThisFrame = false;
            }
            surfacePasses++;

            // Sections first so the township grid draws over it -- the coarse
            // frame has to stay readable where the two coincide, which is every
            // township boundary.
            updateTier(scene, sections);
            updateTier(scene, townships);

            drawLines(view, scene, sections, sectionColor);
            drawLines(view, scene, townships, townshipColor);

            // Once per frame, not once per invocation.
            //
            // The surface pass is invoked many times per frame -- 12 measured
            // on the XCover -- each carrying its own currentPass. Drawing the
            // labels in each one put the same section number on screen several
            // times over and made others come and go. multiPartPass is ATAK's
            // own flag for this: it is false on the final part, which is the
            // test GLGridTile uses to do end-of-frame work.
            final boolean finalPart = useFirstInvocation
                    ? surfacePasses == 1
                    : !view.multiPartPass;

            if (finalPart && !labelsDrawnThisFrame) {
                labelsDrawnThisFrame = true;
                drawLabels(view, scene, townships, townshipLabelColor);
                drawLabels(view, scene, sections, sectionLabelColor);
            }
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

    /**
     * The grid, drawn by ATAK's {@link GLAntiAliasedLine}.
     *
     * Not a hand-rolled GL_LINES call over screen coordinates from
     * view.forward(). That was the bug behind labels landing in a neighbouring
     * cell: geometry drawn in the surface pass is drawn under a model-view
     * matrix ATAK has already set to that pass's scene forward, so coordinates
     * that are already in screen space get transformed a second time. The
     * surface pass renders into a tile carrying its own resolution -- 2.389
     * against a scene resolution of 3.379 at one measured zoom, 4.777 against
     * 7.695 at another -- and the grid came out scaled about the focus point by
     * that ratio, up to 1.6x, walking off a shoreline the section lines are
     * surveyed to follow.
     *
     * This class holds the geometry in map-projection space and applies
     * currentPass.scene.forward itself, which is what makes it correct in
     * either pass. It is what ATAK's own grid lines use.
     */
    private void drawLines(GLMapView view, GLMapView.State scene, Tier tier,
            int c) {

        if (scene.drawMapResolution > tier.maxResolution || tier.line == null)
            return;

        tier.line.draw(view,
                Color.red(c) / 255f,
                Color.green(c) / 255f,
                Color.blue(c) / 255f,
                Color.alpha(c) / 255f,
                tier.lineWidth);
    }

    /**
     * Labels drawn by ATAK's own {@link GLSegmentFloatingLabel}, one per feature,
     * along the feature's index-box diagonal.
     *
     * The class supplies its own dark backdrop and, unlike a hand-rolled text
     * quad, is drawn in the pass ATAK itself uses for text -- so it never gets
     * cut at a surface tile seam, which is the whole reason for the move.
     *
     * The segment weight is solved per frame so the label lands on the feature's
     * centre and stays there. See {@link #centringWeight}.
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

        int drawn = 0;
        float maxOff = 0f;

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

            // A cell shows its number only while its own centre is on screen.
            // Anything less and the label would have to sit somewhere other
            // than the middle of the cell to be visible at all, which is
            // precisely what makes it read as the neighbour's.
            if (cx < vl || cx > vr || cy < vb || cy > vt)
                continue;

            // A label wider than its own feature spills across the boundary
            // lines on both sides. Measured in ground units so the test holds
            // when the map is rotated, where the projected box corners no
            // longer bracket the feature's width.
            final double west = tier.labelGeo.get(i * 4);
            final double south = tier.labelGeo.get(i * 4 + 1);
            final double east = tier.labelGeo.get(i * 4 + 2);
            final double north = tier.labelGeo.get(i * 4 + 3);

            // Measured against the resolution of the pass being drawn, not the
            // scene's. The surface pass renders into a tile carrying its own,
            // finer resolution, so the feature is wider in this space than the
            // scene resolution suggests -- using the scene's understated the
            // room available by that ratio and silently dropped every label
            // near the limit, which is why whole pills and section numbers
            // failed to appear as a tier came on.
            final double widthPx = Math.abs(east - west) * METRES_PER_DEGREE
                    * Math.cos(Math.toRadians((south + north) / 2.0))
                    / Math.max(view.currentPass.drawMapResolution, 0.0001);

            if (textFormat.measureTextWidth(tier.labels[i]) > widthPx
                    * LABEL_FIT)
                continue;

            final float w = centringWeight(sx, sy, nx, ny, vl, vb, vr, vt);
            if (Float.isNaN(w))
                continue;

            // Setting the weight invalidates the class's cached placement, so
            // only touch it when it has actually changed -- otherwise a still
            // map re-projects every label on every frame for nothing.
            if (w != tier.weights[i]) {
                tier.weights[i] = w;
                l.setSegmentPositionWeight(w);
            }

            l.setTextColor(c);
            l.draw(view);
            drawn++;

            l.getTextPoint(probe);
            final float off = (float) Math.hypot((float) probe.x - cx,
                    (float) probe.y - cy);
            if (off > maxOff)
                maxOff = off;
        }

        // Diagnostic, throttled. `maxOffPx` is how far the furthest label
        // landed from its feature's centre and should stay at zero; the class
        // nudges a label inward by up to textWidth/2 + 16 when its anchor is
        // within that of the screen edge, so a value up to about that near an
        // edge is the padding, not the slide coming back.
        final long now = System.currentTimeMillis();
        if (now - lastTerrainLog > 5000L) {
            lastTerrainLog = now;
            final GLMapView.State p = view.currentPass;
            Log.d(TAG, "cam perspective=" + p.scene.camera.perspective
                    + " tilt=" + String.format("%.1f", p.drawTilt)
                    + " elevOffset=" + GLMapView.elevationOffset
                    + " elevScale=" + view.elevationScaleFactor
                    + " terrainAtCentre=" + String.format("%.1f",
                            view.getTerrainMeshElevation(p.drawLat, p.drawLng))
                    + " passRes=" + String.format("%.3f", p.drawMapResolution)
                    + " sceneRes=" + String.format("%.3f",
                            view.currentScene.drawMapResolution));
        }
        if (drawn > 0 && now - lastLabelLog > 5000L) {
            lastLabelLog = now;
            Log.d(TAG, "labels " + tier.table + " drawn=" + drawn
                    + " maxOffPx=" + String.format("%.1f", maxOff));
        }
    }

    /**
     * The segment weight that puts the label on the middle of its segment,
     * cancelling the class's slide. {@link Float#NaN} if it cannot.
     *
     * GLSegmentFloatingLabel keeps a label visible by clipping its segment to
     * the viewport and re-placing the label at the weighted point of what is
     * left: {@code P1 + weight * (P2 - P1)}, where P1 and P2 are the clip
     * points and P1 is the one nearer the segment's start. Good for a grid line
     * running off the edge, wrong for a label naming a cell -- the further the
     * feature hangs off screen, the further its name walks towards the
     * neighbour it borders, and along one screen edge every label walks the
     * same way, so an outer one closes on its neighbour and the two read as a
     * pair in one box.
     *
     * Clipping the segment here as well gives the parameters t0 and t1 of those
     * same two points along SW-NE, and the placement above is at t0 + w(t1-t0).
     * The centre is at t=0.5, so w = (0.5 - t0) / (t1 - t0) puts it there and
     * holds it there however much of the feature is off screen. Unclipped that
     * is t0=0, t1=1, w=0.5, so one expression covers both.
     *
     * Liang-Barsky rather than the class's own polygon intersection: only the
     * parameter range is wanted, not the points, and the rectangle is the same
     * one the class tests against -- GLArrow2.getWidgetViewF, which is
     * currentScene, not currentPass.
     */
    private static float centringWeight(float sx, float sy, float nx, float ny,
            float vl, float vb, float vr, float vt) {

        final float dx = nx - sx;
        final float dy = ny - sy;

        float t0 = 0f;
        float t1 = 1f;

        for (int edge = 0; edge < 4; edge++) {
            final float p;
            final float q;
            switch (edge) {
                case 0:
                    p = -dx;
                    q = sx - vl;
                    break;
                case 1:
                    p = dx;
                    q = vr - sx;
                    break;
                case 2:
                    p = -dy;
                    q = sy - vb;
                    break;
                default:
                    p = dy;
                    q = vt - sy;
                    break;
            }

            if (p == 0f) {
                // parallel to this edge; outside it means no visible span
                if (q < 0f)
                    return Float.NaN;
                continue;
            }

            final float r = q / p;
            if (p < 0f) {
                if (r > t1)
                    return Float.NaN;
                if (r > t0)
                    t0 = r;
            } else {
                if (r < t0)
                    return Float.NaN;
                if (r < t1)
                    t1 = r;
            }
        }

        final float span = t1 - t0;
        if (span < MIN_CLIP_SPAN)
            return Float.NaN;

        return (0.5f - t0) / span;
    }

    /** throttles the label-placement log above */
    private long lastLabelLog;
    private long lastTerrainLog;

    /**
     * Labels are drawn on one invocation per frame; see drawImpl.
     *
     * {@code blankFrames} is the safety net. If this build is ever run against
     * a renderer that never reports a final part, the preferred condition would
     * never fire and the map would lose its labels entirely, which is not a
     * failure anyone should have to diagnose from a plane. After a second or so
     * of that, fall back to the first invocation instead and say so once.
     */
    private boolean labelsDrawnThisFrame;
    private int blankFrames;
    private boolean useFirstInvocation;

    private int lastRenderPump = -1;
    private int surfacePasses;
    private int loggedPasses = -1;

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
        tier.geo.rewind();
        // lon/lat pairs, two per segment -- SEGMENTS steps two points at a
        // time, so the buffer is consumed exactly as GL_LINES consumed it
        tier.line = new GLAntiAliasedLine();
        tier.line.setLineData(tier.geo, 2,
                GLAntiAliasedLine.ConnectionType.SEGMENTS,
                Feature.AltitudeMode.Absolute);

        if (loaded.labelPoints != null && loaded.labels.length > 0) {
            tier.labelGeo = loaded.labelPoints;
            tier.labels = loaded.labels;
            // two points per label -- the projected corners of its index box
            tier.labelScreen = ByteBuffer
                    .allocateDirect(tier.labels.length * 4 * 4)
                    .order(ByteOrder.nativeOrder()).asFloatBuffer();
            tier.floating = buildFloatingLabels(tier, loaded);
            // NaN so the first frame always sets a weight
            tier.weights = new float[tier.labels.length];
            java.util.Arrays.fill(tier.weights, Float.NaN);
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
