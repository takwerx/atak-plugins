package com.atakmap.android.sitrep;

import android.graphics.PointF;

import com.atakmap.android.maps.MapEvent;
import com.atakmap.android.maps.MapEventDispatcher;
import com.atakmap.android.maps.MapItem;
import com.atakmap.android.maps.MapView;
import com.atakmap.android.maps.Shape;
import com.atakmap.coremap.log.Log;
import com.atakmap.coremap.maps.coords.GeoPoint;
import com.atakmap.coremap.maps.coords.GeoPointMetaData;

/**
 * One tap on the map: a point (County or city here) or a shape's outline (Use a
 * shape). The map's own tap handling is pushed aside for that one tap and given back,
 * IWI's pattern.
 */
public final class MapPick {

    private static final String TAG = "SitRep.MapPick";

    public interface Callback {
        /** A point, or for a shape pick the shape's outline; null-free. */
        void picked(GeoPoint point, GeoPoint[] outline);

        /** Said in words when the tap was not what was asked for. */
        void missed(String why);
    }

    private final MapView mapView;
    private Callback callback;
    private boolean shapes;
    private boolean active;

    public MapPick(MapView mapView) {
        this.mapView = mapView;
    }

    public boolean isActive() {
        return active;
    }

    /** The next tap: a point when {@code shapes} is false, a shape when true. */
    public boolean start(boolean shapes, Callback cb) {
        if (active)
            cancel();
        this.shapes = shapes;
        this.callback = cb;
        try {
            final MapEventDispatcher d = mapView.getMapEventDispatcher();
            d.pushListeners();
            d.clearListeners(MapEvent.MAP_CLICK);
            d.clearListeners(MapEvent.ITEM_CLICK);
            d.clearListeners(MapEvent.ITEM_LONG_PRESS);
            d.addMapEventListener(MapEvent.MAP_CLICK, listener);
            d.addMapEventListener(MapEvent.ITEM_CLICK, listener);
            mapView.getMapTouchController().setToolActive(true);
            active = true;
            return true;
        } catch (RuntimeException e) {
            Log.w(TAG, "could not take the map for a tap", e);
            return false;
        }
    }

    public void cancel() {
        if (!active)
            return;
        active = false;
        try {
            mapView.getMapEventDispatcher().popListeners();
            mapView.getMapTouchController().setToolActive(false);
        } catch (RuntimeException e) {
            Log.w(TAG, "could not give the map back", e);
        }
    }

    private final MapEventDispatcher.MapEventDispatchListener listener =
            new MapEventDispatcher.MapEventDispatchListener() {
                @Override
                public void onMapEvent(MapEvent event) {
                    final Callback cb = callback;
                    if (!active || cb == null)
                        return;
                    if (shapes) {
                        final MapItem item = MapEvent.ITEM_CLICK.equals(event.getType()) ? event.getItem() : null;
                        if (!(item instanceof Shape)) {
                            post(cb, null, null, "That is not a shape. Tap the edge of a polygon, circle or rectangle.");
                            return;
                        }
                        final GeoPoint[] pts = ((Shape) item).getPoints();
                        if (pts == null || pts.length < 3) {
                            post(cb, null, null, "That shape has no area. Tap a polygon, circle or rectangle.");
                            return;
                        }
                        final GeoPoint[] copy = new GeoPoint[pts.length];
                        for (int i = 0; i < pts.length; i++)
                            copy[i] = new GeoPoint(pts[i].getLatitude(), pts[i].getLongitude());
                        event.getExtras().putBoolean("eventNotHandled", false);
                        post(cb, null, copy, null);
                        return;
                    }
                    GeoPoint gp = null;
                    if (MapEvent.MAP_CLICK.equals(event.getType())) {
                        final PointF pf = event.getPointF();
                        if (pf != null) {
                            final GeoPointMetaData m = mapView.inverseWithElevation(pf.x, pf.y);
                            gp = m == null ? null : m.get();
                        }
                    } else if (MapEvent.ITEM_CLICK.equals(event.getType())) {
                        final MapItem item = event.getItem();
                        if (item instanceof com.atakmap.android.maps.PointMapItem)
                            gp = ((com.atakmap.android.maps.PointMapItem) item).getPoint();
                        else if (item instanceof Shape)
                            gp = ((Shape) item).getClickPoint();
                    }
                    if (gp == null || !gp.isValid())
                        return;
                    post(cb, new GeoPoint(gp.getLatitude(), gp.getLongitude()), null, null);
                }
            };

    private void post(final Callback cb, final GeoPoint p, final GeoPoint[] outline, final String why) {
        mapView.post(new Runnable() {
            @Override
            public void run() {
                if (why != null) {
                    cb.missed(why);   // keep the pick: the next tap may be right
                    return;
                }
                cancel();
                cb.picked(p, outline);
            }
        });
    }
}
