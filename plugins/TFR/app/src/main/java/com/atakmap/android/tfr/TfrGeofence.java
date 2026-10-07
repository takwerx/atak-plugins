package com.atakmap.android.tfr;

import android.content.Intent;
import android.util.SparseArray;

import com.atakmap.android.drawing.DrawingToolsMapComponent;
import com.atakmap.android.drawing.mapItems.DrawingShape;
import com.atakmap.android.ipc.AtakBroadcast;
import com.atakmap.android.maps.MapGroup;
import com.atakmap.android.maps.MapView;
import com.atakmap.android.maps.Polyline;
import com.atakmap.android.maps.PointMapItem;
import com.atakmap.coremap.log.Log;
import com.atakmap.coremap.maps.coords.GeoPoint;
import com.atakmap.coremap.maps.coords.GeoPointMetaData;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Turning a restriction into a geofence the operator owns.
 *
 * <p>A geofence attaches to a {@code MapItem} -- {@code GeoFence(MapItem, ...)}, and
 * {@code ClosedShapeGeoFenceMonitor} for a closed shape -- and a feature is not one. That
 * is the same distinction that put the restrictions on a feature layer in the first place,
 * and it makes the split here the right one rather than a workaround: the FAA's data stays
 * read-only underneath, and the thing that alerts is a shape the operator made, can edit,
 * can delete, and which ATAK persists as their own.
 *
 * <p>It is a <b>snapshot</b>. The shape does not follow the restriction: if the TFR moves,
 * changes its ceiling or is canceled, the fence stays as it was. The caller says so before
 * making one, because a fence silently guarding last week's airspace is worse than none.
 *
 * <p>Every option after that is ATAK's own. Rather than reimplement a dialog for trigger,
 * monitored types, range and tracking, this broadcasts {@code GeoFenceReceiver.EDIT} and
 * lets ATAK put up the geofence screen it uses everywhere else -- which is also the only
 * way the operator gets the Custom monitored-types picker.
 */
public final class TfrGeofence {

    private static final String TAG = "TfrGeofence";
    /** ATAK's own geofence editor. Takes one extra, the shape's uid. */
    private static final String ACTION_EDIT = "com.atakmap.android.geofence.EDIT";

    private TfrGeofence() {
    }

    /**
     * Make a shape from one area of a restriction and hand it to ATAK's geofence editor.
     *
     * @param withAltitude true to carry the published floor and ceiling, so the fence only
     *            alerts on something actually inside the airspace rather than anything that
     *            crosses the ground footprint
     * @return the shape, or null if it could not be made
     */
    public static DrawingShape create(MapView mapView, Tfr t, TfrArea a, boolean withAltitude) {
        if (mapView == null || a == null || a.ring.size() < 3)
            return null;
        try {
            final MapGroup group = DrawingToolsMapComponent.getGroup();
            final DrawingShape shape = new DrawingShape(mapView, group,
                    UUID.randomUUID().toString());

            final double floorM = withAltitude ? meters(a.floor) : Double.NaN;
            final List<GeoPointMetaData> pts = new ArrayList<>(a.ring.size());
            for (double[] p : a.ring) {
                if (Double.isNaN(floorM)) {
                    pts.add(GeoPointMetaData.wrap(new GeoPoint(p[0], p[1])));
                } else {
                    // USER as the altitude source, or ATAK fills the altitude in from the
                    // terrain model and the fence is built on the ground under the ring
                    // instead of the published floor. Measured: a surface-to-7,500 ft MSL
                    // restriction came out as a fence from 3,027 to 10,527 ft, the right
                    // thickness in the wrong place, because the centroid sits on a
                    // 3,000 ft ridge.
                    pts.add(GeoPointMetaData.wrap(new GeoPoint(p[0], p[1], floorM),
                            GeoPointMetaData.USER, GeoPointMetaData.USER));
                }
            }
            shape.setPoints(pts, new SparseArray<PointMapItem>());
            shape.setClosed(true);
            shape.setTitle(title(t, a));
            // A fence must not look like the restriction it was cut from. Both are rings
            // in the same place, and the operator tapped one expecting the read-only
            // airspace and got an editable drawing: "ook its showing as an editbale item
            // wtf is this" (2026-10-07). So: cyan, dashed, and no fill, against the
            // restriction's solid red or amber with a translucent fill.
            shape.setStrokeColor(0xFF00E5FF);
            shape.setFillColor(0x00000000);
            shape.setStrokeWeight(4.0d);
            shape.setLineStyle(com.atakmap.android.maps.Polyline.BASIC_LINE_STYLE_DASHED);

            if (withAltitude) {
                // The fence derives its band from the item: base from the shape's own
                // center altitude, top from base + getHeight(). The base is not ours to
                // set -- ATAK resolves the centroid's altitude off the terrain model
                // whatever altitude the points carry, and the fence lives in ATAK's own
                // database rather than in item metadata, so it cannot be pre-seeded
                // either. Measured first as a surface-to-7,500 ft MSL restriction coming
                // out as a fence from 3,027 to 10,527: the right thickness, sitting on a
                // 3,000 ft ridge.
                //
                // So read back the number ATAK will use and size the wall against it.
                // The top then lands on the published ceiling, which is the figure that
                // matters. The bottom stays at the ground: for a surface floor that is
                // exactly right, and where the restriction has a floor above ground the
                // fence is deliberately bigger than the airspace. Being warned on the way
                // up is the safe error; the other way round is not.
                final double ceilM = meters(a.ceiling);
                final double baseMsl = baseMslOf(shape);
                final double wall = ceilM - baseMsl;
                if (wall > 1) {
                    shape.setHeight(wall);
                    shape.setHeightStyle(Polyline.HEIGHT_STYLE_POLYGON
                            | Polyline.HEIGHT_STYLE_OUTLINE);
                }
            } else {
                // No altitude: keep it on the ground, or a shape carrying altitudes with no
                // height sinks under terrain and vanishes as you zoom in.
                shape.setAltitudeMode(
                        com.atakmap.map.layer.feature.Feature.AltitudeMode.ClampToGround);
            }

            // Theirs now: archived, persisted, editable, deletable. The opposite of the
            // read-only feature layer it came from.
            shape.setMetaBoolean("archive", true);
            // What it was cut from, so a later sync can tell whether it still holds.
            TfrWatch.stamp(shape, t, a);
            if (shape.getGroup() == null)
                group.addItem(shape);
            shape.persist(mapView.getMapEventDispatcher(), null, TfrGeofence.class);
            return shape;
        } catch (Exception e) {
            Log.w(TAG, "could not build a geofence shape for " + t.notamId, e);
            return null;
        }
    }

    /** Open ATAK's own geofence screen on a shape, which is where every option lives. */
    public static void edit(DrawingShape shape) {
        if (shape == null)
            return;
        final Intent i = new Intent(ACTION_EDIT);
        i.putExtra("uid", shape.getUID());
        AtakBroadcast.getInstance().sendBroadcast(i);
    }

    /**
     * The altitude in meters above mean sea level that the fence will treat as this
     * shape's floor, which is whatever ATAK makes of its own centroid.
     */
    private static double baseMslOf(DrawingShape shape) {
        try {
            final GeoPointMetaData c = shape.getCenter();
            if (c != null && c.get() != null && c.get().isAltitudeValid()) {
                final double msl = com.atakmap.coremap.maps.conversion.EGM96.getMSL(c.get());
                if (!Double.isNaN(msl))
                    return msl;
            }
        } catch (Exception e) {
            Log.w(TAG, "could not read the shape's base altitude", e);
        }
        return 0d;
    }

    private static double meters(TfrArea.Vert v) {
        if (!v.present || v.surface)
            return 0d;
        return v.feet * 0.3048d;
    }

    /**
     * Named as what it is. "TFR 6/7093 11NM NE SANTA CLARITA" read as the restriction
     * itself in ATAK's own lists, which is the same confusion as the matching ring.
     */
    private static String title(Tfr t, TfrArea a) {
        final StringBuilder b = new StringBuilder("Geofence: ");
        b.append(t.place());
        if (!a.name.isEmpty())
            b.append(" (").append(a.name).append(')');
        return b.toString();
    }
}
