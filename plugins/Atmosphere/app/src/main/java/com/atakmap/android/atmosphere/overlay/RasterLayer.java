package com.atakmap.android.atmosphere.overlay;

import android.graphics.Bitmap;

import com.atakmap.coremap.maps.coords.GeoBounds;
import com.atakmap.coremap.maps.coords.GeoPoint;
import com.atakmap.map.layer.AbstractLayer;

import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * One image pinned to a lon/lat rectangle on the map surface: a radar frame, a wind
 * field, anything a WMS hands back in CRS:84. Copied forward from Dozer Country's
 * SlopeLayer, which derives from the SDK's helloworld SimpleHeatMapLayer, the
 * sanctioned way for a plugin to paint a raster.
 *
 * <p>The frame is an immutable object swapped whole behind a lock, so the GL thread
 * never reads a half-set frame. The bitmap is owned by whoever set it (the overlay's
 * frame cache) and is never recycled here; the renderer uploads it into its own
 * texture.
 */
public final class RasterLayer extends AbstractLayer {

    private static final class Frame {
        final Bitmap bitmap;
        final GeoPoint ul, ur, lr, ll;

        Frame(Bitmap bitmap, GeoBounds b) {
            this.bitmap = bitmap;
            this.ul = new GeoPoint(b.getNorth(), b.getWest());
            this.ur = new GeoPoint(b.getNorth(), b.getEast());
            this.lr = new GeoPoint(b.getSouth(), b.getEast());
            this.ll = new GeoPoint(b.getSouth(), b.getWest());
        }
    }

    public interface OnLayerChangedListener {
        void onLayerChanged(RasterLayer layer);
    }

    private final ConcurrentLinkedQueue<OnLayerChangedListener> listeners = new ConcurrentLinkedQueue<>();
    private final Object lock = new Object();
    private Frame frame;

    public RasterLayer(String name) {
        super(name);
    }

    /** Replaces the image. Row 0 of the bitmap is the north edge. */
    public void setImage(Bitmap bitmap, GeoBounds bounds) {
        synchronized (lock) {
            frame = bitmap == null || bounds == null ? null : new Frame(bitmap, bounds);
        }
        dispatch();
    }

    /** Takes the image off the map without removing the layer itself. */
    public void clear() {
        setImage(null, null);
    }

    public boolean hasImage() {
        synchronized (lock) {
            return frame != null;
        }
    }

    /* ----- read by the renderer ----- */

    public Bitmap getBitmap() {
        synchronized (lock) {
            return frame == null ? null : frame.bitmap;
        }
    }

    /** Corners in upper-left, upper-right, lower-right, lower-left order, or null. */
    public GeoPoint[] getPoints() {
        synchronized (lock) {
            if (frame == null)
                return null;
            return new GeoPoint[] { frame.ul, frame.ur, frame.lr, frame.ll };
        }
    }

    public GeoBounds getBounds() {
        synchronized (lock) {
            return frame == null ? null : new GeoBounds(frame.ul, frame.lr);
        }
    }

    public void addOnLayerChangedListener(OnLayerChangedListener l) {
        listeners.add(l);
    }

    public void removeOnLayerChangedListener(OnLayerChangedListener l) {
        listeners.remove(l);
    }

    private void dispatch() {
        for (OnLayerChangedListener l : listeners)
            l.onLayerChanged(this);
    }
}
