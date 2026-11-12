package com.atakmap.android.atmosphere.overlay;

import android.graphics.Bitmap;
import android.util.Pair;

import com.atakmap.coremap.maps.coords.GeoBounds;
import com.atakmap.coremap.maps.coords.GeoPoint;
import com.atakmap.map.MapRenderer;
import com.atakmap.map.layer.Layer;
import com.atakmap.map.layer.control.SurfaceRendererControl;
import com.atakmap.map.layer.feature.geometry.Envelope;
import com.atakmap.map.layer.opengl.GLAbstractLayer;
import com.atakmap.map.layer.opengl.GLLayer2;
import com.atakmap.map.layer.opengl.GLLayerSpi2;
import com.atakmap.map.opengl.GLMapView;
import com.atakmap.opengl.GLES20FixedPipeline;
import com.atakmap.opengl.GLTexture;
import com.atakmap.util.Visitor;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.DoubleBuffer;
import java.nio.FloatBuffer;

/**
 * Draws a {@link RasterLayer}: one texture over a lat/lon mesh, re-projected each
 * frame. Copied forward from Dozer Country's GLSlopeLayer (the SDK's
 * GLSimpleHeatMapLayer), which drew one quad: four corners forwarded and the
 * picture stretched flat between them. On the globe a flat quad is only right for a
 * small box; a continent-wide one cut through the sphere and a box reaching the
 * pole folded on itself (the temperature over Alaska, 2026-11-12). So the box is
 * cut into cells a couple of degrees across and every corner is forwarded.
 * Registered with {@code GLLayerFactory.register(SPI)}; ATAK builds one of these
 * whenever a RasterLayer is added to the map.
 *
 * <p>GL objects are only touched on the GL thread: every allocate, load and release
 * goes through {@code renderContext.queueEvent}. Getting this wrong is a native crash
 * with no Java stack trace. The bitmap is not recycled here; the layer's owner keeps it.
 */
public final class GLRasterLayer extends GLAbstractLayer
        implements RasterLayer.OnLayerChangedListener {

    public static final GLLayerSpi2 SPI = new GLLayerSpi2() {
        @Override
        public int getPriority() {
            return 1;
        }

        @Override
        public GLLayer2 create(Pair<MapRenderer, Layer> object) {
            if (!(object.second instanceof RasterLayer))
                return null;
            return new GLRasterLayer(object.first, (RasterLayer) object.second);
        }
    };

    private static int registrations;

    /** Register the renderer for the first layer, once; unregister after the last. */
    public static synchronized void register() {
        if (registrations++ == 0)
            com.atakmap.map.layer.opengl.GLLayerFactory.register(SPI);
    }

    public static synchronized void unregister() {
        if (registrations > 0 && --registrations == 0)
            com.atakmap.map.layer.opengl.GLLayerFactory.unregister(SPI);
    }

    private final RasterLayer subject;
    private Data frame;
    /** Where the picture on the map is, so the ground is repainted when it goes. */
    private GeoBounds drawn;
    /** The picture last handed to the renderer: the same one again is a fade, not an upload. */
    private Bitmap uploaded;

    public GLRasterLayer(MapRenderer surface, RasterLayer subject) {
        super(surface, subject);
        this.subject = subject;
    }

    @Override
    protected void init() {
        super.init();
        subject.addOnLayerChangedListener(this);
        frame = new Data();
        onLayerChanged(subject);
    }

    @Override
    protected void drawImpl(GLMapView view) {
        final Data d = frame;
        if (d == null || d.texture == null || !d.valid)
            return;
        view.forward(d.points, d.vertexCoordinates);
        int count = d.vertices;
        FloatBuffer vertices = d.vertexCoordinates, texels = d.textureCoordinates;
        // On the globe, a cell past the horizon still projects to somewhere on the
        // screen, folded back over the near side: a wide picture drew streaks of its
        // far edge across the Pacific (2026-11-12). Cells behind the limb are left
        // out of the draw.
        if (view.currentPass.drawSrid == ECEF && d.sphere != null) {
            final com.atakmap.map.MapSceneModel scene = view.currentPass.scene;
            if (scene != null && scene.camera != null && scene.mapProjection != null) {
                final com.atakmap.math.PointD at = scene.camera.location;
                final GeoPoint under = scene.mapProjection.inverse(at, null);
                final double height = under == null ? Double.NaN : under.getAltitude();
                if (under != null && !Double.isNaN(height) && height > 0) {
                    // A point on the sphere is in front of the limb when the angle
                    // between it and the point under the camera is under the
                    // horizon's: cos = R / (R + h). Loosened a little, so the limb
                    // is never drawn short.
                    final double threshold = Math.max(0, EARTH_RADIUS / (EARTH_RADIUS + height) - 0.02);
                    count = d.cull(under.getLatitude(), under.getLongitude(), threshold);
                    vertices = d.drawVertices;
                    texels = d.drawTexels;
                }
            }
        }
        if (count == 0)
            return;
        // The layer's opacity rides on the draw, so a transparency change needs no
        // new texture (the radar's slider, takwerx/atmosphere#7).
        GLTexture.draw(d.texture.getTexId(), GLES20FixedPipeline.GL_TRIANGLES, count, 2,
                GLES20FixedPipeline.GL_FLOAT, texels, 2, GLES20FixedPipeline.GL_FLOAT,
                vertices, 1f, 1f, 1f, subject.getAlpha());
    }

    /** The globe's spatial reference, and a radius under its polar one so the test is lenient. */
    private static final int ECEF = 4978;
    private static final double EARTH_RADIUS = 6_300_000d;

    @Override
    public void release() {
        subject.removeOnLayerChangedListener(this);
        if (frame != null) {
            frame.release();
            frame = null;
        }
        super.release();
    }

    @Override
    public void onLayerChanged(RasterLayer layer) {
        final Bitmap bitmap = layer.getBitmap();
        final GeoPoint[] pts = layer.getPoints();
        final GeoBounds bounds = layer.getBounds();
        final GeoBounds was;
        final boolean same;
        synchronized (this) {
            was = drawn;
            same = bitmap != null && bitmap == uploaded && bounds != null && was != null
                    && was.getNorth() == bounds.getNorth() && was.getSouth() == bounds.getSouth()
                    && was.getEast() == bounds.getEast() && was.getWest() == bounds.getWest();
            drawn = bitmap == null || pts == null ? null : bounds;
            uploaded = drawn == null ? null : bitmap;
        }
        // The same picture in the same place: only its opacity changed, so the
        // ground under it is redrawn and the texture kept.
        if (same) {
            markDirty(bounds);
            return;
        }
        // The ground the old picture covered is repainted too. The surface keeps
        // what it drew, so a layer turned off left its last picture on the map
        // wherever nothing else redrew that ground (XCover, 2026-09-28: a square
        // of satellite after Layers -> All off).
        if (was != null)
            markDirty(was);
        if (bitmap == null || pts == null || bounds == null) {
            renderContext.queueEvent(new Runnable() {
                @Override
                public void run() {
                    if (frame != null)
                        frame.invalidate();
                }
            });
            return;
        }
        renderContext.queueEvent(new Runnable() {
            @Override
            public void run() {
                if (frame != null && !bitmap.isRecycled())
                    frame.update(bitmap, pts[0], pts[1], pts[2], pts[3]);
            }
        });
        markDirty(bounds);
    }

    /** Without this the new image waits for something else to redraw that ground. */
    private void markDirty(GeoBounds bounds) {
        final SurfaceRendererControl[] ctrl = new SurfaceRendererControl[1];
        renderContext.visitControl(null, new Visitor<SurfaceRendererControl>() {
            @Override
            public void visit(SurfaceRendererControl object) {
                ctrl[0] = object;
            }
        }, SurfaceRendererControl.class);
        if (ctrl[0] == null)
            return;
        ctrl[0].markDirty(new Envelope(bounds.getWest(), bounds.getSouth(), 0d,
                bounds.getEast(), bounds.getNorth(), 0d), true);
    }

    /** GL-side state. Every method here runs on the GL thread. */
    private static final class Data {
        /** A mesh cell is about this wide, degrees, and the mesh is at most this many across. */
        private static final double CELL_DEGREES = 2.5;
        private static final int MAX_CELLS = 72;

        GLTexture texture;
        boolean valid;
        /** Mesh corners as lon/lat, the forwarded screen positions, and each corner's texel. */
        DoubleBuffer points;
        FloatBuffer vertexCoordinates;
        FloatBuffer textureCoordinates;
        /** Each corner as a unit vector on the sphere, for the horizon test. */
        float[] sphere;
        /** The corners in front of the horizon this frame, compacted for the draw. */
        FloatBuffer drawVertices, drawTexels;
        /** How many corners the mesh has: six per cell, a triangle pair. */
        int vertices;
        private int filled;

        void invalidate() {
            valid = false;
        }

        void release() {
            if (texture != null) {
                texture.release();
                texture = null;
            }
            valid = false;
        }

        void update(Bitmap src, GeoPoint ul, GeoPoint ur, GeoPoint lr, GeoPoint ll) {
            final int width = src.getWidth();
            final int height = src.getHeight();
            if (texture == null || texture.getTexWidth() < width
                    || texture.getTexHeight() < height) {
                if (texture != null)
                    texture.release();
                texture = new GLTexture(width, height, src.getConfig());
            }
            // Clear first: a smaller image reusing a larger texture would otherwise
            // leave the previous frame around its edges.
            texture.load(null, 0, 0, width, height);
            final float u = (float) width / (float) texture.getTexWidth();
            final float v = (float) height / (float) texture.getTexHeight();
            final double west = ul.getLongitude(), east = ur.getLongitude();
            final double north = ul.getLatitude(), south = ll.getLatitude();
            final int cols = Math.max(1, Math.min(MAX_CELLS, (int) Math.ceil((east - west) / CELL_DEGREES)));
            final int rows = Math.max(1, Math.min(MAX_CELLS, (int) Math.ceil((north - south) / CELL_DEGREES)));
            vertices = cols * rows * 6;
            if (points == null || points.capacity() < vertices * 2) {
                points = ByteBuffer.allocateDirect(vertices * 2 * 8)
                        .order(ByteOrder.nativeOrder()).asDoubleBuffer();
                vertexCoordinates = ByteBuffer.allocateDirect(vertices * 2 * 4)
                        .order(ByteOrder.nativeOrder()).asFloatBuffer();
                textureCoordinates = ByteBuffer.allocateDirect(vertices * 2 * 4)
                        .order(ByteOrder.nativeOrder()).asFloatBuffer();
                drawVertices = ByteBuffer.allocateDirect(vertices * 2 * 4)
                        .order(ByteOrder.nativeOrder()).asFloatBuffer();
                drawTexels = ByteBuffer.allocateDirect(vertices * 2 * 4)
                        .order(ByteOrder.nativeOrder()).asFloatBuffer();
                sphere = new float[vertices * 3];
            }
            points.clear();
            textureCoordinates.clear();
            filled = 0;
            for (int r = 0; r < rows; r++) {
                final double lat0 = north - (north - south) * r / rows;
                final double lat1 = north - (north - south) * (r + 1) / rows;
                final float v0 = v * r / rows, v1 = v * (r + 1) / rows;
                for (int c = 0; c < cols; c++) {
                    final double lon0 = west + (east - west) * c / cols;
                    final double lon1 = west + (east - west) * (c + 1) / cols;
                    final float u0 = u * c / cols, u1 = u * (c + 1) / cols;
                    corner(lon0, lat0, u0, v0);
                    corner(lon1, lat0, u1, v0);
                    corner(lon1, lat1, u1, v1);
                    corner(lon0, lat0, u0, v0);
                    corner(lon1, lat1, u1, v1);
                    corner(lon0, lat1, u0, v1);
                }
            }
            points.flip();
            textureCoordinates.flip();
            vertexCoordinates.clear();
            vertexCoordinates.limit(vertices * 2);
            texture.load(src);
            valid = true;
        }

        private void corner(double lon, double lat, float s, float t) {
            points.put(lon).put(lat);
            textureCoordinates.put(s).put(t);
            final double la = Math.toRadians(lat), lo = Math.toRadians(lon);
            final double cl = Math.cos(la);
            sphere[filled++] = (float) (cl * Math.cos(lo));
            sphere[filled++] = (float) (cl * Math.sin(lo));
            sphere[filled++] = (float) Math.sin(la);
        }

        /**
         * Keep the triangles whose every corner is in front of the horizon seen from
         * over {@code lat, lon}: the dot product with that point's unit vector over
         * {@code threshold}. Returns how many corners are left to draw.
         */
        int cull(double lat, double lon, double threshold) {
            final double la = Math.toRadians(lat), lo = Math.toRadians(lon);
            final double cl = Math.cos(la);
            final float ux = (float) (cl * Math.cos(lo)), uy = (float) (cl * Math.sin(lo));
            final float uz = (float) Math.sin(la);
            drawVertices.clear();
            drawTexels.clear();
            int kept = 0;
            for (int t = 0; t + 2 < vertices; t += 3) {
                boolean front = true;
                for (int k = 0; k < 3 && front; k++) {
                    final int i = (t + k) * 3;
                    front = sphere[i] * ux + sphere[i + 1] * uy + sphere[i + 2] * uz > threshold;
                }
                if (!front)
                    continue;
                for (int k = 0; k < 3; k++) {
                    final int i = (t + k) * 2;
                    drawVertices.put(vertexCoordinates.get(i)).put(vertexCoordinates.get(i + 1));
                    drawTexels.put(textureCoordinates.get(i)).put(textureCoordinates.get(i + 1));
                }
                kept += 3;
            }
            drawVertices.flip();
            drawTexels.flip();
            return kept;
        }
    }
}
