package com.atakmap.android.airaware.plan;

import com.atakmap.coremap.maps.coords.GeoBounds;
import com.atakmap.coremap.maps.coords.GeoPoint;
import com.atakmap.map.layer.AbstractLayer;

import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * The islands overlay on the map: an ARGB raster pinned to the corners of the square
 * around the launch point. Copied forward from Dozer Country's {@code SlopeLayer},
 * which is derived from the SDK's {@code helloworld} sample {@code SimpleHeatMapLayer},
 * the sanctioned way for a plugin to paint a raster.
 *
 * <h3>Computed once, not per frame</h3>
 *
 * The raster is built when the launch point or the ceiling changes and then left
 * alone. Nothing here recomputes on map movement, so no arithmetic can end up on the
 * GL thread and panning never costs anything. The renderer re-projects the four
 * corners each frame; that is all.
 *
 * <p>Thread safety: {@link #setField} and {@link #clear} are called from a worker,
 * {@link #getArgb} and {@link #getPoints} from the GL thread. Every mutation swaps
 * whole immutable objects behind a lock rather than editing an array in place, so the
 * renderer can never read a half-written raster.
 */
public final class IslandLayer extends AbstractLayer {

    public static final String NAME = "UAS Flight Plan";

    /** Immutable snapshot, swapped as a unit so the GL thread always sees one frame. */
    private static final class Frame {
        final int[] argb;
        final int width;
        final int height;
        final GeoPoint ul, ur, lr, ll;

        Frame(int[] argb, int width, int height, GeoBounds b) {
            this.argb = argb;
            this.width = width;
            this.height = height;
            this.ul = new GeoPoint(b.getNorth(), b.getWest());
            this.ur = new GeoPoint(b.getNorth(), b.getEast());
            this.lr = new GeoPoint(b.getSouth(), b.getEast());
            this.ll = new GeoPoint(b.getSouth(), b.getWest());
        }
    }

    public interface OnLayerChangedListener {
        void onLayerChanged(IslandLayer layer);
    }

    private final ConcurrentLinkedQueue<OnLayerChangedListener> listeners = new ConcurrentLinkedQueue<>();

    private final Object lock = new Object();
    private Frame frame;

    public IslandLayer() {
        super(NAME);
    }

    /**
     * Replaces the raster.
     *
     * @param argb   row-major, row 0 northmost, one entry per cell; 0 where nothing is drawn
     * @param width  cells east-west
     * @param height cells north-south
     * @param bounds the area the raster covers
     */
    public void setField(int[] argb, int width, int height, GeoBounds bounds) {
        synchronized (lock) {
            frame = new Frame(argb, width, height, bounds);
        }
        dispatch();
    }

    /** Takes the overlay off the map without removing the layer itself. */
    public void clear() {
        synchronized (lock) {
            frame = null;
        }
        dispatch();
    }

    public boolean hasField() {
        synchronized (lock) {
            return frame != null;
        }
    }

    /* ----- read by the renderer ----- */

    /** The pixels to upload, or null when there is nothing to draw. */
    public int[] getArgb() {
        synchronized (lock) {
            return frame == null ? null : frame.argb;
        }
    }

    public int getWidth() {
        synchronized (lock) {
            return frame == null ? 0 : frame.width;
        }
    }

    public int getHeight() {
        synchronized (lock) {
            return frame == null ? 0 : frame.height;
        }
    }

    /** Corners in upper-left, upper-right, lower-right, lower-left order, or null. */
    public GeoPoint[] getPoints() {
        synchronized (lock) {
            if (frame == null)
                return null;
            return new GeoPoint[] {
                    frame.ul, frame.ur, frame.lr, frame.ll
            };
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
