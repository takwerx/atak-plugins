package com.atakmap.android.fobs.track;

import android.content.Context;

import com.atakmap.android.drawing.DrawingToolsMapComponent;
import com.atakmap.android.drawing.mapItems.DrawingShape;
import com.atakmap.android.maps.MapGroup;
import com.atakmap.android.maps.MapItem;
import com.atakmap.android.maps.MapView;
import com.atakmap.android.preference.UnitPreferences;
import com.atakmap.coremap.conversions.Area;
import com.atakmap.coremap.conversions.AreaUtilities;
import com.atakmap.coremap.conversions.Span;
import com.atakmap.coremap.conversions.SpanUtilities;
import com.atakmap.coremap.maps.coords.GeoCalculations;
import com.atakmap.coremap.maps.coords.GeoPoint;

import java.util.List;
import java.util.UUID;

/**
 * FOBS tracks and areas are ordinary ATAK drawing shapes ({@code u-d-f}) in ATAK's own
 * drawing group, so they persist, share over the mesh and delete like any drawing, and
 * every ATAK renders them without the plugin.
 *
 * <p>What makes one ours is metadata, not a subclass: ATAK recreates shapes from its
 * CoT store on restart as plain {@link DrawingShape}, so an {@code instanceof} check
 * would stop working the first time the app was reopened. {@link FobsDetailHandler}
 * carries the metadata through CoT, in both directions.
 */
public final class FobsShapes {

    private FobsShapes() {
    }

    /** "track" or "area". Absent on shapes that are not ours. */
    public static final String META_KIND = "fobs_kind";
    /** Where the points came from: GPS or USER. */
    public static final String META_SOURCE = "fobs_source";
    /** Altitude source of the first fix (DTED2, GPS, ...). */
    public static final String META_ALTSRC = "fobs_altsrc";
    /** Fixes offered to the track before any filtering. */
    public static final String META_RAW = "fobs_raw";
    /** Fixes the live gate and the end pass removed. */
    public static final String META_DROPPED = "fobs_dropped";

    public static final String KIND_TRACK = "track";
    public static final String KIND_AREA = "area";

    public static final String SOURCE_GPS = "GPS";
    public static final String SOURCE_USER = "USER";

    public static boolean isOurs(MapItem item) {
        return item instanceof DrawingShape && item.hasMetaValue(META_KIND);
    }

    public static boolean isTrack(MapItem item) {
        return isOurs(item) && KIND_TRACK.equals(item.getMetaString(META_KIND, ""));
    }

    public static boolean isArea(MapItem item) {
        return isOurs(item) && KIND_AREA.equals(item.getMetaString(META_KIND, ""));
    }

    /**
     * A new, empty, open track in the FOBS default style (popup, Default Style). Not yet
     * on the map and not yet persisted: a polyline with fewer than two points is a
     * degenerate thing to hand the renderer, so the caller adds it with
     * {@link #addToMap} once it has two, and persists after that.
     */
    public static DrawingShape newTrack(MapView mapView, String title, String source) {
        MapGroup group = DrawingToolsMapComponent.getGroup();
        DrawingShape shape = new DrawingShape(mapView, group,
                UUID.randomUUID().toString());
        StylePrefs style = new StylePrefs(mapView);
        shape.setTitle(title);
        shape.setStrokeColor(style.color());
        shape.setStrokeWeight(style.strokeWeight());
        shape.setLineStyle(style.lineStyle());
        shape.setClosed(false);
        // On the ground, always. Points carry GPS or interpolated altitudes; drawn at
        // those, the line sinks under higher terrain and vanishes as you zoom in
        // (operator spotted it, 2026-09-05).
        shape.setAltitudeMode(com.atakmap.map.layer.feature.Feature.AltitudeMode.ClampToGround);
        shape.setMetaBoolean("archive", true);
        shape.setMetaString(META_KIND, KIND_TRACK);
        shape.setMetaString(META_SOURCE, source);
        return shape;
    }

    /** Put a track on the map. Call once it has at least two points. */
    public static void addToMap(DrawingShape shape) {
        if (shape.getGroup() == null)
            DrawingToolsMapComponent.getGroup().addItem(shape);
    }

    /**
     * Close a track into an area: same map item, same UID, so anything watching it on
     * the mesh or in a feed sees the line become a polygon rather than a second item.
     */
    public static void makeArea(MapView mapView, DrawingShape shape) {
        StylePrefs style = new StylePrefs(mapView);
        shape.setClosed(true);
        shape.setFillColor(style.fillColor());
        shape.setAltitudeMode(com.atakmap.map.layer.feature.Feature.AltitudeMode.ClampToGround);
        shape.setMovable(false);
        shape.setMetaString(META_KIND, KIND_AREA);
    }

    public static void persist(MapView mapView, DrawingShape shape, Class<?> from) {
        shape.persist(mapView.getMapEventDispatcher(), null, from);
        // A track in a feed is re-sent to it on every save; see FeedPublisher.
        com.atakmap.android.fobs.feed.FeedPublisher feed =
                com.atakmap.android.fobs.feed.FeedPublisher.get();
        if (feed != null)
            feed.onPersisted(shape);
    }

    /** Perimeter in meters along the points, closing back to the start if closed. */
    public static double perimeterMeters(GeoPoint[] pts, boolean closed) {
        double m = 0;
        for (int i = 1; i < pts.length; i++)
            m += GeoCalculations.distanceTo(pts[i - 1], pts[i]);
        if (closed && pts.length > 2)
            m += GeoCalculations.distanceTo(pts[pts.length - 1], pts[0]);
        return m;
    }

    /** Distance in the user's ATAK range units. Never a hardcoded unit. */
    public static String formatDistance(Context hostContext, double meters) {
        UnitPreferences units = new UnitPreferences(hostContext);
        return SpanUtilities.formatType(units.getRangeSystem(), meters, Span.METER);
    }

    /** Area in the user's ATAK area units. */
    public static String formatArea(Context hostContext, double squareMeters) {
        UnitPreferences units = new UnitPreferences(hostContext);
        return AreaUtilities.formatArea(units.getAreaSystem(), squareMeters, Area.METER2);
    }

    /** First free "<base> N" with N >= from among the drawing shapes on the map. */
    public static int nextSuffix(MapView mapView, String base, int from) {
        List<MapItem> shapes = DrawingToolsMapComponent.getGroup()
                .deepFindItems("type", "u-d-f");
        int n = Math.max(1, from);
        while (true) {
            String candidate = base + " " + n;
            boolean taken = false;
            for (MapItem it : shapes) {
                if (candidate.equals(it.getTitle())) {
                    taken = true;
                    break;
                }
            }
            if (!taken)
                return n;
            n++;
        }
    }

    /**
     * First free "callsign - Track N" among the drawing shapes already on the map, so
     * two tracks never share a default name.
     */
    public static int nextTrackNumber(MapView mapView, String pattern, String callsign) {
        List<MapItem> shapes = DrawingToolsMapComponent.getGroup()
                .deepFindItems("type", "u-d-f");
        int n = 1;
        while (true) {
            String candidate = String.format(pattern, callsign, n);
            boolean taken = false;
            for (MapItem it : shapes) {
                if (candidate.equals(it.getTitle())) {
                    taken = true;
                    break;
                }
            }
            if (!taken)
                return n;
            n++;
        }
    }
}
