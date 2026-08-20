
package com.atakmap.android.plss.graphics;

import android.graphics.Color;
import android.opengl.GLES20;
import android.util.Pair;

import com.atakmap.android.maps.MapTextFormat;
import com.atakmap.android.plss.PlssOverlay;
import com.atakmap.android.plss.PlssStore;
import com.atakmap.coremap.log.Log;
import com.atakmap.map.MapRenderer;
import com.atakmap.map.layer.Layer;
import com.atakmap.map.layer.opengl.GLAbstractLayer2;
import com.atakmap.map.layer.opengl.GLLayer2;
import com.atakmap.map.layer.opengl.GLLayerSpi2;
import com.atakmap.map.opengl.GLMapView;
import com.atakmap.map.opengl.GLRenderGlobals;
import com.atakmap.opengl.GLES20FixedPipeline;
import com.atakmap.opengl.GLText;

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
     * Offsets, in pixels, at which the halo copies of a label are drawn.
     *
     * The halo is drawn by hand because its colour has to follow the text: white
     * behind dark labels, black behind light ones. ATAK's default text format
     * outlines in white unconditionally, which makes a white label invisible.
     */
    private static final float[][] HALO_OFFSETS = {
            { -1.5f, 0f }, { 1.5f, 0f }, { 0f, -1.5f }, { 0f, 1.5f },
            { -1f, -1f }, { 1f, -1f }, { -1f, 1f }, { 1f, 1f }
    };

    /**
     * Gap required between two labels, in pixels, per axis.
     *
     * Horizontal only. A township's centre is a section corner -- where sections
     * 15, 16, 21 and 22 meet -- so its label sits half a section above and below
     * the four section numbers around it. That clearance is real but small, and
     * padding it vertically as well is what makes a township label knock out the
     * section numbers next to it.
     */
    private static final float LABEL_SPACING_X = 4f;
    private static final float LABEL_SPACING_Y = 0f;

    /**
     * Largest share of a feature's on-screen width a label may occupy.
     *
     * A label wider than its own township spills across the boundary lines on
     * both sides, which is what the grid is for. Requiring clearance means a
     * label simply waits until the feature is big enough to hold it.
     */
    private static final float LABEL_FIT = 0.7f;

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
         * Label size relative to ATAK's default, applied as a matrix scale.
         *
         * Not a second font: giving each tier its own GLText truncated the
         * longer labels ("T17S-R" for "T17S-R11E"), because GLText shares glyph
         * state between instances and the larger face perturbed the smaller
         * one's metrics. One font, scaled at draw time, avoids that entirely.
         */
        final float fontScale;

        final AtomicBoolean loading = new AtomicBoolean(false);

        DoubleBuffer geo;
        FloatBuffer screen;
        int vertexCount;

        DoubleBuffer labelGeo;
        FloatBuffer labelScreen;
        String[] labels;

        double west, south, east, north;
        boolean loaded;

        Tier(String table, double maxResolution, float lineWidth,
                int featureLimit, float fontScale) {
            this.table = table;
            this.township = "township".equals(table);
            this.maxResolution = maxResolution;
            this.lineWidth = lineWidth;
            this.featureLimit = featureLimit;
            this.fontScale = fontScale;
        }

        void clear() {
            geo = null;
            screen = null;
            vertexCount = 0;
            labelGeo = null;
            labelScreen = null;
            labels = null;
        }
    }

    private final PlssOverlay subject;

    private final Tier townships = new Tier("township", 28.0, 3f, 4000, 1.55f);
    private final Tier sections = new Tier("section", 14.5, 1.5f, 20000, 2.1f);

    private final ExecutorService loader = Executors.newSingleThreadExecutor();

    /**
     * Bounding boxes of labels already placed this frame, as x0,y0,x1,y1 runs.
     *
     * Where the rectangular survey ran up against a Spanish or Mexican land grant
     * it went around it, leaving small irregular township remnants whose centres
     * sit almost on top of each other -- the San Fernando Valley is the textbook
     * case. Without this their labels overprint into an unreadable stack.
     */
    private float[] placedLabels = new float[256];
    private int placedCount;

    /**
     * Throttled so the current zoom can be read off logcat while tuning the
     * thresholds against the device -- ATAK's scale bar changes length to suit
     * round numbers, so it cannot be converted to m/px reliably.
     */

    /**
     * One text instance shared by every tier, sized at ATAK's default.
     *
     * Deliberately not one per tier: GLText shares glyph state between
     * instances, and adding a second at a larger size truncated the longer
     * labels ("T17S-R" for "T17S-R11E"). Tiers scale this one at draw time.
     */
    private MapTextFormat textFormat;
    private GLText glText;

    /** written on the UI thread by the colour picker, read on the GL thread */
    private volatile int sectionColor;
    private volatile int townshipColor;
    private volatile int sectionLabelColor;
    private volatile int townshipLabelColor;

    public GLPlssOverlay(MapRenderer surface, PlssOverlay subject) {
        // Lines go on the map surface so they sit under ATAK's own markers;
        // labels are drawn in the sprites pass. The surface is composited in
        // tiles, and text spanning a tile seam is cut mid-glyph -- which is why
        // one label rendered whole in one place and clipped in another. That
        // pass also magnified text by roughly 1.55x, which the tier font scales
        // now carry instead.
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

        final GLMapView.State scene = view.currentScene;

        // Sections first so the township grid draws over it -- the coarse frame
        // has to stay readable where the two coincide, which is every township
        // boundary.
        updateTier(scene, sections);
        updateTier(scene, townships);

        if ((renderPass & GLMapView.RENDER_PASS_SURFACE) != 0) {
            drawLines(view, scene, sections, sectionColor);
            drawLines(view, scene, townships, townshipColor);
        }

        if ((renderPass & GLMapView.RENDER_PASS_SPRITES) == 0)
            return;

        // townships are placed first so they win any contested spot -- losing a
        // section number is cheaper than losing the survey identity
        // Each tier declutters against itself only. A township's centre is a
        // section corner, so its label sits right between sections 15, 16, 21
        // and 22 -- sharing one set meant the label suppressed those numbers,
        // which are the primary data. The two tiers differ in size and colour,
        // so the rare few-pixel overlap reads fine.
        placedCount = 0;
        drawLabels(view, scene, townships, townshipLabelColor);

        placedCount = 0;
        drawLabels(view, scene, sections, sectionLabelColor);
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
     * Labels centred on each feature's index-box centre.
     *
     * Labels use ATAK's default text format, which outlines the glyphs itself.
     * An earlier attempt drew a filled quad behind each label instead and it read
     * badly, and a hand-built format clipped strings mid-word -- see the note at
     * the format's construction below.
     */
    private void drawLabels(GLMapView view, GLMapView.State scene, Tier tier,
            int c) {

        if (scene.drawMapResolution > tier.maxResolution
                || tier.labelGeo == null || tier.labels == null
                || tier.labels.length == 0)
            return;

        if (glText == null) {
            // One shared instance for every tier. MapTextFormat(Typeface, int)
            // is MapTextFormat(typeface, false, size), so the default is already
            // un-outlined -- the halo below is the only outline, and its colour
            // is ours.
            textFormat = GLRenderGlobals.getDefaultTextFormat();
            glText = GLText.getInstance(textFormat);

            Log.d(TAG, "font: size=" + textFormat.getFontSize()
                    + " densityAdjusted=" + textFormat.getDensityAdjustedFontSize()
                    + " relativeScaling=" + GLRenderGlobals.getRelativeScaling()
                    + " tallestGlyph=" + textFormat.getTallestGlyphHeight()
                    + " glTextCharHeight=" + glText.getCharHeight()
                    + " glTextStringHeight=" + glText.getStringHeight()
                    + " measure9=" + textFormat.measureTextWidth("T16S-R11E")
                    + " glTextW9=" + glText.getStringWidth("T16S-R11E"));
        }

        final float scale = tier.fontScale;

        tier.labelGeo.rewind();
        tier.labelScreen.rewind();
        view.forward(tier.labelGeo, tier.labelScreen);
        tier.labelScreen.rewind();

        final float minX = scene.left;
        final float maxX = scene.right;
        final float minY = scene.bottom;
        final float maxY = scene.top;

        final float r = Color.red(c) / 255f;
        final float g = Color.green(c) / 255f;
        final float b = Color.blue(c) / 255f;
        final float a = Color.alpha(c) / 255f;

        // Rec. 601 luma: a light label needs a dark halo and the reverse. Using
        // perceived brightness rather than a plain average keeps yellow -- which
        // is bright but low in blue -- on the correct side of the line.
        final float luma = 0.299f * r + 0.587f * g + 0.114f * b;
        final float halo = luma > 0.5f ? 0f : 1f;

        // unscaled metrics: the matrix applies the tier's scale at draw time
        final float glyphHalf = textFormat.getTallestGlyphHeight() / 2f;
        final float half = glyphHalf * scale;

        // the map's own rotation, applied to every label this pass
        final float rotation = (float) -scene.drawRotation;

        for (int i = 0; i < tier.labels.length; i++) {
            // the feature's index box, projected: corners at 4i and 4i+2
            final float bx0 = tier.labelScreen.get(i * 4);
            final float by0 = tier.labelScreen.get(i * 4 + 1);
            final float bx1 = tier.labelScreen.get(i * 4 + 2);
            final float by1 = tier.labelScreen.get(i * 4 + 3);

            final float x = (bx0 + bx1) / 2f;
            final float y = (by0 + by1) / 2f;

            // cheap reject -- most of a padded load is off screen
            if (x < minX || x > maxX || y < minY || y > maxY)
                continue;

            final String text = tier.labels[i];
            final float textW = textFormat.measureTextWidth(text);
            final float w = textW * scale;


            // a label wider than its own feature would cross the boundary lines
            if (w > Math.abs(bx1 - bx0) * LABEL_FIT)
                continue;

            // first label wins the spot; later ones that would overprint it are
            // dropped rather than stacked
            if (!claimLabelSpace(x - w / 2f, y - half, x + w / 2f, y + half))
                continue;

            // Rotate with the map so a label lies along its own township or
            // section rather than across it once the operator turns the map off
            // north-up. Rotating about the anchor first, then stepping back by
            // half the text extents, keeps it centred through the turn.
            // halo offsets are screen pixels, so they are applied before the
            // tier's scale rather than through it
            for (int o = 0; o < HALO_OFFSETS.length; o++) {
                GLES20FixedPipeline.glPushMatrix();
                GLES20FixedPipeline.glTranslatef(x + HALO_OFFSETS[o][0],
                        y + HALO_OFFSETS[o][1], 0f);
                if (rotation != 0f)
                    GLES20FixedPipeline.glRotatef(rotation, 0f, 0f, 1f);
                GLES20FixedPipeline.glScalef(scale, scale, 1f);
                GLES20FixedPipeline.glTranslatef(-textW / 2f, -glyphHalf, 0f);
                glText.draw(text, halo, halo, halo, a);
                GLES20FixedPipeline.glPopMatrix();
            }

            GLES20FixedPipeline.glPushMatrix();
            GLES20FixedPipeline.glTranslatef(x, y, 0f);
            if (rotation != 0f)
                GLES20FixedPipeline.glRotatef(rotation, 0f, 0f, 1f);
            GLES20FixedPipeline.glScalef(scale, scale, 1f);
            GLES20FixedPipeline.glTranslatef(-textW / 2f, -glyphHalf, 0f);
            glText.draw(text, r, g, b, a);
            GLES20FixedPipeline.glPopMatrix();
        }
    }

    /**
     * Reserves screen space for a label, or reports that something is already
     * there. Linear in the number placed, which is bounded by what fits on a
     * screen, so it stays cheap.
     */
    private boolean claimLabelSpace(float x0, float y0, float x1, float y1) {
        for (int i = 0; i < placedCount; i += 4) {
            if (x0 < placedLabels[i + 2] + LABEL_SPACING_X
                    && x1 + LABEL_SPACING_X > placedLabels[i]
                    && y0 < placedLabels[i + 3] + LABEL_SPACING_Y
                    && y1 + LABEL_SPACING_Y > placedLabels[i + 1])
                return false;
        }

        if (placedCount + 4 > placedLabels.length) {
            final float[] bigger = new float[placedLabels.length * 2];
            System.arraycopy(placedLabels, 0, bigger, 0, placedCount);
            placedLabels = bigger;
        }

        placedLabels[placedCount++] = x0;
        placedLabels[placedCount++] = y0;
        placedLabels[placedCount++] = x1;
        placedLabels[placedCount++] = y1;
        return true;
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
        }
    }
}
