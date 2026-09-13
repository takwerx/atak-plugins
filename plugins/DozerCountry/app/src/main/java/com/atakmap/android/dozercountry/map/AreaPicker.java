package com.atakmap.android.dozercountry.map;

import com.atakmap.android.maps.MapEvent;
import com.atakmap.android.maps.MapEventDispatcher;
import com.atakmap.android.maps.MapView;
import com.atakmap.coremap.maps.coords.GeoBounds;
import com.atakmap.coremap.maps.coords.GeoPoint;
import com.atakmap.coremap.maps.coords.GeoPointMetaData;

/**
 * Two taps on the map give an area of interest: one corner, then the opposite one.
 *
 * <h3>Why this is not a Tool</h3>
 *
 * ATAK runs one {@code Tool} at a time and ends the active one whenever another starts,
 * a dropdown opens or Back is pressed. Nothing here outlives the second tap, so there
 * is nothing to lose — but the overlay it produces is owned by {@link SlopeOverlay} for
 * the plugin's life, not by this. Switching base maps after the area is drawn must not
 * take the painting with it.
 *
 * <h3>Listener discipline</h3>
 *
 * Takes the map's event listeners exclusively with {@code pushListeners()} and hands
 * them back with {@code popListeners()}. Every exit path pops — finished, cancelled, or
 * the pane closing underneath it — because a picker that forgets leaves the map deaf to
 * ATAK's own handlers and reads as ATAK having broken.
 */
public final class AreaPicker {

    public interface Callback {
        /** Both corners taken. */
        void onAreaPicked(GeoBounds bounds);

        /** First corner taken; the second is still wanted. */
        void onFirstCorner(GeoPoint corner);

        void onCancelled();
    }

    private final MapView mapView;
    private final Callback callback;

    private boolean active;
    private GeoPoint first;

    private final MapEventDispatcher.MapEventDispatchListener listener =
            new MapEventDispatcher.MapEventDispatchListener() {
                @Override
                public void onMapEvent(MapEvent event) {
                    handleTap(event);
                }
            };

    public AreaPicker(MapView mapView, Callback callback) {
        this.mapView = mapView;
        this.callback = callback;
    }

    public boolean isActive() {
        return active;
    }

    /** Begins picking. Safe to call when already active; it restarts from no corners. */
    public void start() {
        if (active) {
            first = null;
            return;
        }
        active = true;
        first = null;

        final MapEventDispatcher d = mapView.getMapEventDispatcher();
        d.pushListeners();
        // Everything else on the map goes quiet, so a tap cannot also open a marker.
        d.clearListeners();
        d.addMapEventListener(MapEvent.MAP_CLICK, listener);
    }

    public void cancel() {
        if (!active)
            return;
        finish();
        callback.onCancelled();
    }

    private void handleTap(MapEvent event) {
        if (!active || event.getPointF() == null)
            return;

        // The finger position, inverted through the map. inverseWithElevation rather
        // than a plain inverse so a tap on a hillside lands on the ground the operator
        // is looking at rather than on the ellipsoid under it.
        final GeoPointMetaData p = mapView.inverseWithElevation(
                event.getPointF().x, event.getPointF().y);
        if (p == null || p.get() == null || !p.get().isValid())
            return;

        final GeoPoint point = p.get();

        if (first == null) {
            first = point;
            callback.onFirstCorner(point);
            return;
        }

        final GeoBounds bounds = GeoBounds.createFromPoints(new GeoPoint[] {
                first, point
        });
        finish();

        // A single tap twice in the same place is not an area, and sampling it would
        // divide by a zero-metre span.
        if (bounds.getNorth() == bounds.getSouth()
                || bounds.getEast() == bounds.getWest()) {
            callback.onCancelled();
            return;
        }
        callback.onAreaPicked(bounds);
    }

    private void finish() {
        active = false;
        first = null;
        mapView.getMapEventDispatcher().popListeners();
    }
}
