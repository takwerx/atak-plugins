
package com.atakmap.android.plss.graphics;

import android.graphics.Color;
import android.util.Pair;

import com.atakmap.android.maps.MapTextFormat;
import com.atakmap.android.plss.PlssOverlay;
import com.atakmap.android.plss.PlssStore;
import com.atakmap.coremap.log.Log;
import com.atakmap.coremap.maps.coords.GeoPoint;
import com.atakmap.map.MapRenderer;
import com.atakmap.map.layer.Layer;
import com.atakmap.map.layer.control.SurfaceRendererControl;
import com.atakmap.map.layer.feature.geometry.Envelope;
import com.atakmap.map.layer.feature.geometry.Point;
import com.atakmap.map.layer.feature.Feature;
import com.atakmap.map.layer.opengl.GLAbstractLayer2;
import com.atakmap.map.layer.opengl.GLLayer2;
import com.atakmap.map.layer.opengl.GLLayerSpi2;
import com.atakmap.map.opengl.GLAntiAliasedLine;
import com.atakmap.map.opengl.GLLabelManager;
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
    /** backdrop behind label text; the engine fills and outlines it for us */
    private static final int LABEL_BACKDROP = 0x99000000;


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

        /**
         * Height of this tier's label text, in metres on the ground.
         *
         * Ground units rather than font points, because that is what
         * GLLabelManager wants for a surface label and because it is the right
         * model for a survey grid: the number stays the same fraction of its
         * cell at every zoom. A section is 1609 m across, a township 9656.
         */
        final double labelHeightMetres;

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
         * One GLLabelManager id per feature, registered when the tier loads.
         *
         * The engine owns placement, sizing, decluttering and which pass to
         * draw in; this array is only the handles needed to update colour and
         * to release them again.
         */
        int[] labelIds;



        double west, south, east, north;
        boolean loaded;

        Tier(String table, double maxResolution, float lineWidth,
                int featureLimit, double labelHeightMetres) {
            this.table = table;
            this.township = "township".equals(table);
            this.maxResolution = maxResolution;
            this.lineWidth = lineWidth;
            this.featureLimit = featureLimit;
            this.labelHeightMetres = labelHeightMetres;
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
            labelIds = null;
        }
    }

    private final PlssOverlay subject;

    private final Tier townships = new Tier("township", 28.0, 3f, 4000, 300.0);
    private final Tier sections = new Tier("section", 14.5, 1.5f, 20000, 200.0);

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

    /** kept so a background load can reach the label manager and the surface */
    private GLMapView glMapView;

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
        // Lines on the surface so they warp along with the imagery rather than
        // being reprojected against it; labels in the sprites pass, which runs
        // once per frame in screen space.
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

        clearTier(townships);
        townships.loaded = false;
        clearTier(sections);
        sections.loaded = false;

        super.release();
    }

    @Override
    public void onPlssColorChanged(PlssOverlay overlay) {
        sectionColor = overlay.getSectionColor();
        townshipColor = overlay.getTownshipColor();
        sectionLabelColor = overlay.getSectionLabelColor();
        townshipLabelColor = overlay.getTownshipLabelColor();

        // The engine holds the colours now, so push them rather than relying on
        // a redraw to pick them up.
        pushLabelColours();

        invalidate();
    }

    @Override
    protected void drawImpl(GLMapView view, int renderPass) {

        // kept so a background load can mark the surface dirty when it lands
        glMapView = view;

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

        // Labels are not drawn here at all. They are registered with ATAK's
        // GLLabelManager, which owns their placement, sizing, decluttering and
        // which pass they belong to. See registerLabels.
    }

    /** Drops or reloads a tier for the current view. */
    private void updateTier(GLMapView.State scene, Tier tier) {
        // Zoomed too far out for this tier to be readable -- and far too many to
        // draw. Drop what is loaded so panning at this zoom stays cheap.
        if (scene.drawMapResolution > tier.maxResolution) {
            if (tier.loaded) {
                clearTier(tier);
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


    /**
     * Hands every label for a tier to ATAK's own label engine.
     *
     * This replaces the whole hand-rolled label path, and the reason is that
     * placing text on this map from outside the engine could not be made to
     * work. Labels drawn in the sprites pass are projected on a scale that does
     * not match the surface-drawn grid -- measured at 7.86 m/px against 6.79 --
     * so the lattice shrinks about the focus point and numbers drift out of
     * their cells with distance from the middle of the screen. Drawn in the
     * surface pass instead they duplicate, because it is invoked once per tile
     * with a different frame each time, and they rotate with the imagery. There
     * is no correction factor available: drawMapResolution disagrees with
     * view.forward() even within a single pass, by 1.67 in one measurement.
     *
     * GLLabelManager sidesteps all of it. It takes a geographic point and a
     * string, and owns the rest -- which pass to draw in, the tiling, rotation,
     * font size and decluttering -- in the same native engine that places every
     * other label on the map, so it agrees with the map by construction.
     *
     * HINT_SURFACE anchors a label to the ground, which is what keeps it welded
     * to the grid while panning. setHeightInMeters sizes the text in ground
     * units, so a section number stays the same fraction of its section at
     * every zoom; the engine works the font size out itself, including the
     * tile-scale factor its own source comments call puzzling.
     *
     * GL thread only.
     */
    private int[] registerLabels(Tier tier, PlssStore.Result loaded) {
        final GLLabelManager mgr = labelManager();
        if (mgr == null)
            return null;

        final int n = loaded.labels.length;
        final int[] ids = new int[n];
        final DoubleBuffer pts = loaded.labelPoints;
        final int argb = tier.township ? townshipLabelColor : sectionLabelColor;

        for (int i = 0; i < n; i++) {
            // centre of the feature's index box
            final double lon = (pts.get(i * 4) + pts.get(i * 4 + 2)) / 2.0;
            final double lat = (pts.get(i * 4 + 1) + pts.get(i * 4 + 3)) / 2.0;

            final int id = mgr.addLabel(tier.labels[i]);
            mgr.setGeometry(id, new Point(lon, lat));
            mgr.setAltitudeMode(id, Feature.AltitudeMode.ClampToGround);
            mgr.setHints(id, GLLabelManager.HINT_SURFACE);
            mgr.setHeightInMeters(id, tier.labelHeightMetres);
            mgr.setMaxDrawResolution(id, tier.maxResolution);
            mgr.setPriority(id, tier.township
                    ? GLLabelManager.Priority.High
                    : GLLabelManager.Priority.Standard);
            mgr.setColor(id, argb);
            mgr.setBackgroundColor(id, LABEL_BACKDROP);
            mgr.setFill(id, true);
            mgr.setVisible(id, true);
            ids[i] = id;
        }

        Log.d(TAG, "registered " + n + " " + tier.table + " labels");
        return ids;
    }

    /**
     * Drops a tier's geometry and gives its labels back to the engine.
     *
     * Always both. Clearing the tier without releasing leaves the labels
     * registered with no way to reach them again, and they keep drawing: zoom
     * past a tier's threshold and back a few times and the orphans stack up as
     * extra numbers inside cells that already have one.
     */
    private void clearTier(Tier tier) {
        releaseLabels(tier.labelIds);
        tier.clear();
    }

    /** Releases a tier's labels back to the engine. GL thread only. */
    private void releaseLabels(int[] ids) {
        final GLLabelManager mgr = labelManager();
        if (mgr == null || ids == null)
            return;
        for (int id : ids)
            mgr.removeLabel(id);
    }

    /** GL thread only in practice; colour changes arrive on the UI thread. */
    private void pushLabelColours() {
        final GLLabelManager mgr = labelManager();
        if (mgr == null)
            return;
        if (townships.labelIds != null)
            for (int id : townships.labelIds)
                mgr.setColor(id, townshipLabelColor);
        if (sections.labelIds != null)
            for (int id : sections.labelIds)
                mgr.setColor(id, sectionLabelColor);
    }

    private GLLabelManager labelManager() {
        return glMapView != null ? glMapView.getLabelManager() : null;
    }

    /** Tells the cached surface that this box needs redrawing. */
    private void markSurfaceDirty(double west, double south, double east,
            double north) {
        if (glMapView == null)
            return;
        final SurfaceRendererControl ctrl = glMapView
                .getControl(SurfaceRendererControl.class);
        if (ctrl != null)
            ctrl.markDirty(new Envelope(west, south, 0d, east, north, 0d),
                    false);
    }

    /** GL thread only. */
    private void install(Tier tier, PlssStore.Result loaded, double west,
            double south, double east, double north) {

        tier.west = west;
        tier.south = south;
        tier.east = east;
        tier.north = north;
        tier.loaded = true;

        // The surface is cached: ATAK renders a tile once and keeps it until
        // something says otherwise. Geometry loads off the GL thread, so a tile
        // drawn before its features arrived would keep its stale contents and
        // those labels would simply never appear -- which is what "some of the
        // labels aren't populating on the first render" is. invalidate() asks
        // for a frame; it does not invalidate the surface. ATAK's own grid
        // marks the loaded envelope dirty for exactly this reason.
        markSurfaceDirty(west, south, east, north);

        clearTier(tier);

        if (loaded == null)
            return;

        tier.geo = loaded.segments;
        tier.geo.rewind();
        // lon/lat pairs, two per segment -- SEGMENTS steps two points at a
        // time, so the buffer is consumed exactly as GL_LINES consumed it
        tier.line = new GLAntiAliasedLine();
        // Absolute, i.e. sea level, and deliberately so.
        //
        // Relative was tried, to sit the grid on the terrain where the imagery
        // is draped. It is far too expensive: the class does a terrain-mesh
        // elevation lookup per vertex and re-projects the whole buffer every
        // time the terrain version changes, which at section zoom is tens of
        // thousands of segments redone as terrain streams in. Section drawing
        // slowed to a crawl.
        //
        // It also was not buying much. Labels are drawn at altitude zero too,
        // so both are displaced from the imagery by the same amount and still
        // agree with each other, which is what actually matters for a number
        // sitting in its own cell.
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
            tier.labelIds = registerLabels(tier, loaded);
        }
    }

}
