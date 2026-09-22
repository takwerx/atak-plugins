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
 * Draws a {@link RasterLayer}: one texture, one quad, re-projected each frame. Copied
 * forward from Dozer Country's GLSlopeLayer (the SDK's GLSimpleHeatMapLayer).
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

    private final RasterLayer subject;
    private Data frame;

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
        d.texture.draw(4, GLES20FixedPipeline.GL_FLOAT, d.textureCoordinates,
                d.vertexCoordinates);
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
    public void onLayerChanged(RasterLayer layer) {
        final Bitmap bitmap = layer.getBitmap();
        final GeoPoint[] pts = layer.getPoints();
        final GeoBounds bounds = layer.getBounds();
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
            textureCoordinates.clear();
            final float u = (float) width / (float) texture.getTexWidth();
            final float v = (float) height / (float) texture.getTexHeight();
            textureCoordinates.putFloat(0f).putFloat(0f);
            textureCoordinates.putFloat(u).putFloat(0f);
            textureCoordinates.putFloat(u).putFloat(v);
            textureCoordinates.putFloat(0f).putFloat(v);
            textureCoordinates.flip();
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
