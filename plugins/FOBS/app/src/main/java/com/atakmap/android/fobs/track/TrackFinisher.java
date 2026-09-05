package com.atakmap.android.fobs.track;

import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.widget.Toast;

import com.atakmap.android.drawing.mapItems.DrawingShape;
import com.atakmap.android.fobs.plugin.R;
import com.atakmap.android.maps.MapView;
import com.atakmap.coremap.log.Log;
import com.atakmap.coremap.maps.coords.GeoPoint;

/**
 * The end of every track, whichever tool made it: persist, then ask
 * <b>Single line</b> or <b>Make polygon</b>. A polygon is the same shape closed, same
 * UID, so anything watching it sees the line become an area.
 */
public class TrackFinisher {

    private static final String TAG = "FOBS.TrackFinisher";

    private final MapView mapView;
    private final Context plugin;
    private final Context host;

    public TrackFinisher(MapView mapView, Context pluginContext) {
        this.mapView = mapView;
        this.plugin = pluginContext;
        this.host = mapView.getContext();
    }

    /**
     * Ask <b>Make polygon</b> / <b>Single line</b> / <b>Cancel</b> while the tool is
     * still running, so a fat-fingered End costs nothing. {@code end} ends the tool
     * (which finalizes and persists the shape); Make polygon runs it and then closes
     * the shape. Cancel, back, or a tap outside leaves the tool exactly as it was.
     */
    public void askThenEnd(final DrawingShape shape, final Runnable end) {
        GeoPoint[] pts = shape.getPoints();
        String length = FobsShapes.formatDistance(host,
                FobsShapes.perimeterMeters(pts, false));
        String message = plugin.getString(R.string.end_message, shape.getTitle(),
                pts.length, length);
        new AlertDialog.Builder(host)
                .setTitle(plugin.getString(R.string.end_title))
                .setMessage(message)
                .setPositiveButton(plugin.getString(R.string.make_polygon),
                        new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface d, int which) {
                                end.run();
                                makePolygon(shape);
                            }
                        })
                .setNegativeButton(plugin.getString(R.string.single_line),
                        new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface d, int which) {
                                end.run();
                            }
                        })
                .setNeutralButton(plugin.getString(R.string.cancel), null)
                .setCancelable(true)
                .show();
    }

    /**
     * The same question after the tool has already ended (ATAK's back button ends a
     * tool without asking us), so Cancel is not on offer.
     */
    public void ask(final DrawingShape shape, Class<?> from) {
        FobsShapes.persist(mapView, shape, from);
        GeoPoint[] pts = shape.getPoints();
        String length = FobsShapes.formatDistance(host,
                FobsShapes.perimeterMeters(pts, false));
        String message = plugin.getString(R.string.end_message, shape.getTitle(),
                pts.length, length);
        new AlertDialog.Builder(host)
                .setTitle(plugin.getString(R.string.end_title))
                .setMessage(message)
                .setPositiveButton(plugin.getString(R.string.make_polygon),
                        new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface d, int which) {
                                makePolygon(shape);
                            }
                        })
                .setNegativeButton(plugin.getString(R.string.single_line), null)
                .setCancelable(true)
                .show();
    }

    /** Close the shape into an area and say how big it is, in the user's units. */
    public void makePolygon(DrawingShape shape) {
        if (shape.getNumPoints() < 3) {
            toast(plugin.getString(R.string.toast_area_too_few));
            return;
        }
        FobsShapes.makeArea(mapView, shape);
        FobsShapes.persist(mapView, shape, getClass());
        GeoPoint[] pts = shape.getPoints();
        String area = FobsShapes.formatArea(host, shape.getArea());
        String perimeter = FobsShapes.formatDistance(host,
                FobsShapes.perimeterMeters(pts, true));
        toast(plugin.getString(R.string.toast_area, area, perimeter));
        Log.d(TAG, "area " + shape.getUID() + " " + area + " " + perimeter);
    }

    public void toast(String s) {
        Toast.makeText(host, s, Toast.LENGTH_SHORT).show();
    }
}
