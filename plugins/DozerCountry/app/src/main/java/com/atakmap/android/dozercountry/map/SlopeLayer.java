package com.atakmap.android.dozercountry.map;

import com.atakmap.coremap.maps.coords.GeoBounds;
import com.atakmap.coremap.maps.coords.GeoPoint;
import com.atakmap.map.layer.AbstractLayer;

import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * The slope overlay on the map: an ARGB raster pinned to the corners of the area of
 * interest.
 *
 * <p>Derived from the SDK's {@code helloworld} sample {@code SimpleHeatMapLayer}, which
 * is the sanctioned way for a plugin to paint a raster. ATAK's own
 * {@code HeatMapOverlay} and {@code ElevationHeatmapLayer} cannot be used for this:
 * both color by HSV across an elevation range with no hook for a classifier, and
 * {@code GLHeatMap}'s constructor and parameter object are package-private.
 *
 * <h3>Computed once, not per frame</h3>
 *
 * The raster is built when the operator draws an area and then left alone. Nothing here
 * recomputes on map movement, so no slope arithmetic can end up on the GL thread and
 * panning never costs anything. The renderer re-projects the four corners each frame;
 * that is all.
 *
 * <p>Thread safety: {@link #setField} and {@link #clear} are called from a worker,
 * {@link #getArgb} and {@link #getPoints} from the GL thread. Every mutation swaps
 * whole immutable objects behind a lock rather than editing an array in place, so the
 * renderer can never read a half-written raster.
 */
public final class SlopeLayer extends AbstractLayer {

    public static final String NAME = "Dozer Country";

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
        void onLayerChanged(SlopeLayer layer);
    }

    private final ConcurrentLinkedQueue<OnLayerChangedListener> listeners = new ConcurrentLinkedQueue<>();

    private final Object lock = new Object();
    private Frame frame;
    /** The colors as classified, before opacity. Kept so opacity can be re-applied. */
    private int[] base;
    private float opacity = 1f;

    public SlopeLayer() {
        super(NAME);
    }

    /**
     * Replaces the raster.
     *
     * @param argb   row-major, row 0 northmost, one entry per cell; 0 where unknown
     * @param width  cells east-west
     * @param height cells north-south
     * @param bounds the area the raster covers
     */
    public void setField(int[] argb, int width, int height, GeoBounds bounds) {
        synchronized (lock) {
            base = argb;
            frame = new Frame(applyOpacity(argb, opacity), width, height, bounds);
        }
        dispatch();
    }

    /** Takes the overlay off the map without removing the layer itself. */
    public void clear() {
        synchronized (lock) {
            base = null;
            frame = null;
        }
        dispatch();
    }

    public boolean hasField() {
        synchronized (lock) {
            return frame != null;
        }
    }

    /**
     * Sets overall transparency, 0 to 1.
     *
     * <p>Applied into the pixels rather than as a GL blend so the texture carries it:
     * the unknown cells stay fully transparent whatever the operator does with the
     * slider, and there is no GL state to get out of step with the raster.
     */
    public void setOpacity(float value) {
        final float v = Math.max(0f, Math.min(1f, value));
        synchronized (lock) {
            if (Math.abs(v - opacity) < 0.001f)
                return;
            opacity = v;
            if (base != null && frame != null)
                frame = new Frame(applyOpacity(base, v), frame.width, frame.height,
                        new GeoBounds(frame.ul, frame.lr));
        }
        dispatch();
    }

    public float getOpacity() {
        synchronized (lock) {
            return opacity;
        }
    }

    private static int[] applyOpacity(int[] src, float opacity) {
        if (opacity >= 0.999f)
            return src.clone();
        final int[] out = new int[src.length];
        for (int i = 0; i < src.length; i++) {
            final int c = src[i];
            final int a = (int) (((c >>> 24) & 0xFF) * opacity);
            out[i] = (a << 24) | (c & 0x00FFFFFF);
        }
        return out;
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
