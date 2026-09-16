package com.atakmap.android.ipaws.data;

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
 * <p>Feature Layer's {@code Esri.fromGeoJson}, carried forward (there is no shared
 * module; see {@code shared-helpers-are-copy-forward}). Its Esri-JSON half is not
 * here: api.weather.gov serves real GeoJSON, where the first ring of a polygon is the
 * outer one and the rest are holes, so the winding-order rule Esri needs does not apply.
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
            case "Point":
                return new Point(c.getDouble(0), c.getDouble(1));
            case "MultiPoint": {
                final GeometryCollection gc = new GeometryCollection(2);
                for (int i = 0; i < c.length(); i++)
                    gc.addGeometry(new Point(c.getJSONArray(i).getDouble(0),
                            c.getJSONArray(i).getDouble(1)));
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
        for (int i = 0; i < coords.length(); i++) {
            final JSONArray p = coords.getJSONArray(i);
            ls.addPoint(p.getDouble(0), p.getDouble(1));
        }
        return ls;
    }

    private static Polygon polygon(JSONArray rings) throws Exception {
        final Polygon poly = new Polygon(2);
        for (int i = 0; i < rings.length(); i++)
            poly.addRing(line(rings.getJSONArray(i)));
        return poly;
    }

    /** The centre of a geometry's extent; null when it has no points. */
    public static double[] center(Geometry g) {
        if (g == null)
            return null;
        final com.atakmap.map.layer.feature.geometry.Envelope e = g.getEnvelope();
        if (e == null)
            return null;
        return new double[] { (e.minY + e.maxY) / 2d, (e.minX + e.maxX) / 2d };
    }
}
