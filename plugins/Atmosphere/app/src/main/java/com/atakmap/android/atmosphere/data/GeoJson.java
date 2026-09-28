package com.atakmap.android.atmosphere.data;

import com.atakmap.map.layer.feature.geometry.Geometry;
import com.atakmap.map.layer.feature.geometry.GeometryCollection;
import com.atakmap.map.layer.feature.geometry.LineString;
import com.atakmap.map.layer.feature.geometry.Point;
import com.atakmap.map.layer.feature.geometry.Polygon;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * GeoJSON to ATAK feature geometry.
 *
 * <p>Feature Layer's {@code Esri.fromGeoJson} by way of IPAWS ({@code ipaws.data.GeoJson},
 * 2026-09-23), carried forward (there is no shared module; see
 * {@code shared-helpers-are-copy-forward}). Its Esri-JSON half is not here: EPA's
 * ArcGIS service is asked for {@code f=geojson} and api.weather.gov serves GeoJSON, where
 * the first ring of a polygon is the outer one and the rest are holes, so the
 * winding-order rule Esri needs does not apply.
 */
public final class GeoJson {

    private GeoJson() {
    }

    /** Null when the object is not a geometry this can draw. */
    public static Geometry parse(JSONObject g) throws Exception {
        if (g == null)
            return null;
        final String type = g.optString("type", "");
        if ("GeometryCollection".equals(type)) {
            final JSONArray gs = g.optJSONArray("geometries");
            if (gs == null)
                return null;
            final GeometryCollection gc = new GeometryCollection(2);
            for (int i = 0; i < gs.length(); i++) {
                final Geometry child = parse(gs.optJSONObject(i));
                if (child != null)
                    gc.addGeometry(child);
            }
            return gc;
        }
        final JSONArray c = g.optJSONArray("coordinates");
        if (c == null)
            return null;
        switch (type) {
            case "Point": {
                final double lon = c.getDouble(0), lat = c.getDouble(1);
                return isSane(lon, lat) ? new Point(lon, lat) : null;
            }
            case "MultiPoint": {
                final GeometryCollection gc = new GeometryCollection(2);
                for (int i = 0; i < c.length(); i++) {
                    final double lon = c.getJSONArray(i).getDouble(0);
                    final double lat = c.getJSONArray(i).getDouble(1);
                    if (isSane(lon, lat))
                        gc.addGeometry(new Point(lon, lat));
                }
                return gc;
            }
            case "LineString":
                return line(c);
            case "MultiLineString": {
                final GeometryCollection gc = new GeometryCollection(2);
                for (int i = 0; i < c.length(); i++)
                    gc.addGeometry(line(c.getJSONArray(i)));
                return gc;
            }
            case "Polygon":
                return polygon(c);
            case "MultiPolygon": {
                final GeometryCollection gc = new GeometryCollection(2);
                for (int i = 0; i < c.length(); i++)
                    gc.addGeometry(polygon(c.getJSONArray(i)));
                return gc;
            }
            default:
                return null;
        }
    }

    private static LineString line(JSONArray coords) throws Exception {
        final LineString ls = new LineString(2);
        int kept = 0;
        for (int i = 0; i < coords.length(); i++) {
            final JSONArray p = coords.getJSONArray(i);
            final double lon = p.getDouble(0);
            final double lat = p.getDouble(1);
            if (!isSane(lon, lat))
                continue;
            ls.addPoint(lon, lat);
            kept++;
        }
        // A ring that lost points is not a ring. Better to drop the shape than to
        // hand the renderer a two-point polygon and draw a lie.
        if (kept < coords.length())
            throw new IllegalArgumentException("geometry had " + (coords.length() - kept)
                    + " unusable coordinates");
        return ls;
    }

    /**
     * Coordinates are checked before they reach ATAK's geometry, which is native.
     *
     * <p>A NaN, an infinity or a longitude of 4e9 arriving from the feed goes into
     * {@code LineString.addPoint} and from there into native code, and a native crash
     * in a plugin gives no Java stack trace and looks like ATAK falling over on its
     * own. That is a bad enough failure mode to be worth four comparisons per point,
     * whatever the odds of the feed sending one.
     */
    private static boolean isSane(double lon, double lat) {
        return !Double.isNaN(lon) && !Double.isNaN(lat)
                && !Double.isInfinite(lon) && !Double.isInfinite(lat)
                && lon >= -180d && lon <= 180d && lat >= -90d && lat <= 90d;
    }

    private static Polygon polygon(JSONArray rings) throws Exception {
        final Polygon poly = new Polygon(2);
        for (int i = 0; i < rings.length(); i++)
            poly.addRing(line(rings.getJSONArray(i)));
        return poly;
    }

    /** The center of a geometry's extent; null when it has no points. */
    public static double[] center(Geometry g) {
        if (g == null)
            return null;
        final com.atakmap.map.layer.feature.geometry.Envelope e = g.getEnvelope();
        if (e == null)
            return null;
        return new double[] { (e.minY + e.maxY) / 2d, (e.minX + e.maxX) / 2d };
    }
}
