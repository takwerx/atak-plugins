package com.atakmap.android.sitrep.net;

import com.atakmap.android.sitrep.Area;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Socrata open-data datasets, read the way the 911 calls section and the local place
 * lists need them. Same rules as {@link Http}: https only, bounded, no redirects.
 *
 * <p>A Socrata point column comes back in one of two shapes, and a dataset may also carry
 * plain {@code latitude} and {@code longitude} columns:
 * <ul>
 * <li>GeoJSON: {@code {"type":"Point","coordinates":[lon,lat]}} (Seattle, Austin, the
 * Chicago crashes and cooling and warming centers);</li>
 * <li>legacy location: {@code {"latitude":"41.7","longitude":"-87.5","human_address":"..."}}
 * (Chicago 311, with the numbers as text).</li>
 * </ul>
 * Reading only the first shape made the whole Chicago 311 feed read as "no calls", with
 * no error, because every row was skipped.
 */
public final class Socrata {

    private Socrata() {
    }

    /**
     * The row's position as {@code {lat, lon}}, or null when it has none a map can use:
     * missing, not a number, out of range, or the 0,0 that agencies write for "unknown".
     */
    public static double[] latLon(JSONObject row, String pointField) {
        if (row == null)
            return null;
        final Object p = pointField == null ? null : row.opt(pointField);
        if (p instanceof JSONObject) {
            final JSONObject o = (JSONObject) p;
            final JSONArray c = o.optJSONArray("coordinates");
            if (c != null && c.length() >= 2) {
                final double[] fix = valid(c.optDouble(1, Double.NaN), c.optDouble(0, Double.NaN));
                if (fix != null)
                    return fix;
            }
            final double[] legacy = valid(o.optDouble("latitude", Double.NaN), o.optDouble("longitude", Double.NaN));
            if (legacy != null)
                return legacy;
        }
        return valid(row.optDouble("latitude", Double.NaN), row.optDouble("longitude", Double.NaN));
    }

    private static double[] valid(double lat, double lon) {
        if (Double.isNaN(lat) || Double.isNaN(lon) || Math.abs(lat) > 90 || Math.abs(lon) > 180
                || (lat == 0 && lon == 0))
            return null;
        return new double[] { lat, lon };
    }

    /** A column name: letters, digits and underscores only, so it can sit in a query. */
    public static String field(String name) throws IOException {
        if (name == null || !name.matches("[A-Za-z_][A-Za-z0-9_]{0,63}"))
            throw new IOException("bad field name for a Socrata query");
        return name;
    }

    /** {@code within_box(col, north, west, south, east)}: SoQL wants the north-west corner first. */
    public static String withinBox(String pointField, double south, double west, double north, double east)
            throws IOException {
        return String.format(Locale.US, "within_box(%s, %.6f, %.6f, %.6f, %.6f)",
                field(pointField), north, west, south, east);
    }

    /**
     * Rows of a dataset inside the area or within {@code nearbyMiles} of it: each row's
     * columns plus {@code _lat}, {@code _lon} and {@code _d} (meters from the area, 0 inside).
     * {@code where} is an extra SoQL condition, or null.
     */
    public static List<JSONObject> points(String url, String pointField, String where, Area area, int nearbyMiles)
            throws Exception {
        final double reach = Math.max(0, nearbyMiles) * 1609.344;
        final double padLat = reach / 111_320d;
        final double padLon = reach / (111_320d * Math.max(0.2, Math.cos(Math.toRadians((area.south + area.north) / 2))));
        String w = withinBox(pointField, area.south - padLat, area.west - padLon, area.north + padLat, area.east + padLon);
        if (where != null && !where.isEmpty())
            w += " AND (" + where + ")";
        final JSONArray rows = new JSONArray(Http.get(url + "?$where=" + Esri.enc(w) + "&$limit=2000"));
        final List<JSONObject> out = new ArrayList<>();
        for (int i = 0; i < rows.length(); i++) {
            final JSONObject r = rows.optJSONObject(i);
            final double[] at = latLon(r, pointField);
            if (at == null)
                continue;
            final double d = area.distanceMeters(at[0], at[1]);
            if (d > reach)
                continue;
            r.put("_lat", at[0]).put("_lon", at[1]).put("_d", d);
            out.add(r);
        }
        return out;
    }
}
