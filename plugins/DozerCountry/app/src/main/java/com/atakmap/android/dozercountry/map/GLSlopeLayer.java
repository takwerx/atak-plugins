package com.atakmap.android.dozercountry.map;

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
 * Draws {@link SlopeLayer} — one texture, one quad, re-projected each frame.
 *
 * <p>Derived from the SDK's {@code helloworld} sample {@code GLSimpleHeatMapLayer}.
 * Registered with {@code GLLayerFactory.register(SPI)}; ATAK builds an instance of
 * this whenever a {@link SlopeLayer} is added to the map.
 *
 * <h3>GL objects are only touched on the GL thread</h3>
 *
 * The raster arrives from a worker. Everything that allocates, loads or releases a
 * texture is pushed through {@code renderContext.queueEvent}, so the worker never
 * touches GL state. Getting this wrong is a native crash with no Java stack trace.
 */
public final class GLSlopeLayer extends GLAbstractLayer
        implements SlopeLayer.OnLayerChangedListener {

    public static final GLLayerSpi2 SPI = new GLLayerSpi2() {
        @Override
        public int getPriority() {
            return 1;
        }

        @Override
        public GLLayer2 create(Pair<MapRenderer, Layer> object) {
            if (!(object.second instanceof SlopeLayer))
                return null;
            return new GLSlopeLayer(object.first, (SlopeLayer) object.second);
        }
    };

    private final SlopeLayer subject;
    private Data frame;

    public GLSlopeLayer(MapRenderer surface, SlopeLayer subject) {
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

        // Corner coordinates to screen space. Done every frame because the map moves;
        // the texture itself is untouched.
        view.forward(d.points, d.vertexCoordinates);
        d.texture.draw(4, GLES20FixedPipeline.GL_FLOAT,
                d.textureCoordinates, d.vertexCoordinates);
    }

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
    public void onLayerChanged(SlopeLayer layer) {
        final int[] argb = layer.getArgb();
        final GeoPoint[] pts = layer.getPoints();
        final GeoBounds bounds = layer.getBounds();

        if (argb == null || pts == null || bounds == null) {
            renderContext.queueEvent(new Runnable() {
                @Override
                public void run() {
                    if (frame != null)
                        frame.invalidate();
                }
            });
            // Nothing to mark dirty against; the next frame simply draws nothing.
            return;
        }

        final int width = layer.getWidth();
        final int height = layer.getHeight();
        // Built here, off the GL thread, so the GL thread only uploads.
        final Bitmap bitmap = Bitmap.createBitmap(argb, width, height,
                Bitmap.Config.ARGB_8888);

        renderContext.queueEvent(new Runnable() {
            @Override
            public void run() {
                try {
                    if (frame != null)
                        frame.update(bitmap, width, height,
                                pts[0], pts[1], pts[2], pts[3]);
                } finally {
                    bitmap.recycle();
                }
            }
        });

        markDirty(bounds);
    }

    /**
     * Tells the surface renderer the ground under the overlay changed.
     *
     * <p>Without this the new raster does not appear until something else happens to
     * make ATAK redraw that part of the map.
     */
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
        GLTexture texture;
        boolean valid;
        final DoubleBuffer points;
        final FloatBuffer vertexCoordinates;
        final ByteBuffer textureCoordinates;

        Data() {
            points = ByteBuffer.allocateDirect(8 * 2 * 4)
                    .order(ByteOrder.nativeOrder()).asDoubleBuffer();
            vertexCoordinates = ByteBuffer.allocateDirect(4 * 2 * 4)
                    .order(ByteOrder.nativeOrder()).asFloatBuffer();
            textureCoordinates = ByteBuffer.allocateDirect(4 * 2 * 4)
                    .order(ByteOrder.nativeOrder());
        }

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

        void update(Bitmap src, int width, int height,
                GeoPoint ul, GeoPoint ur, GeoPoint lr, GeoPoint ll) {

            if (texture == null
                    || texture.getTexWidth() < width
                    || texture.getTexHeight() < height) {
                if (texture != null)
                    texture.release();
                texture = new GLTexture(width, height, src.getConfig());
            }

            // Clear the region first: a smaller raster reusing a larger texture would
            // otherwise leave the previous area of interest around its edges.
            texture.load(null, 0, 0, width, height);

            // 'v' runs from the upper left so the GPU does the vertical flip. Row 0 of
            // the raster is the north edge, which is how the sampler generated it.
            textureCoordinates.clear();
            final float u = (float) width / (float) texture.getTexWidth();
            final float v = (float) height / (float) texture.getTexHeight();
            textureCoordinates.putFloat(0f).putFloat(0f);
            textureCoordinates.putFloat(u).putFloat(0f);
            textureCoordinates.putFloat(u).putFloat(v);
            textureCoordinates.putFloat(0f).putFloat(v);
            textureCoordinates.flip();

            // Pairs are X, Y — longitude then latitude.
            points.clear();
            points.put(ul.getLongitude()).put(ul.getLatitude());
            points.put(ur.getLongitude()).put(ur.getLatitude());
            points.put(lr.getLongitude()).put(lr.getLatitude());
            points.put(ll.getLongitude()).put(ll.getLatitude());
            points.flip();

            texture.load(src);
            valid = true;
        }
    }
}
