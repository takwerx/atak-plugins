package com.atakmap.android.uasflightplan.obstacles;

import com.atakmap.android.uasflightplan.net.Http;
import com.atakmap.android.uasflightplan.terrain.Extent;
import com.atakmap.coremap.log.Log;
import com.atakmap.coremap.maps.coords.GeoBounds;
import com.atakmap.coremap.maps.coords.GeoCalculations;
import com.atakmap.coremap.maps.coords.GeoPoint;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The FAA Digital Obstacle File, from the FAA's own feature service, for the circle
 * around a launch point.
 *
 * <p>Verified 2026-09-26 and again 2026-10-06: 652,525 obstacles nationally,
 * published every 8 weeks, 2,000 per page with pagination, "for public use".
 * {@code Type_Code} is space-padded (trimmed here); {@code AGL} and {@code AMSL}
 * are feet. Everything in the circle's bounding square is asked for and the
 * circle is cut client-side, nearest first.
 *
 * <p>Coverage: obstacles that affect charting, verified and unverified. Not every
 * tower, and no distribution lines at all. The pane says so.
 */
public final class DofSource {

    private static final String TAG = "UASObstacles";

    static final String URL = "https://services6.arcgis.com/ssFJjBXIUyZDrSYZ/arcgis/rest/services/Digital_Obstacle_File/FeatureServer/0/query";

    /** The service's own page size. */
    static final int PAGE = 2000;
    /**
     * Pages are fetched until the service says there is no more or this many rows
     * are in hand. A 3 mi circle over a city can hold thousands of poles; past this
     * the list is capped and the pane says so.
     */
    static final int MAX_ROWS = 6000;

    private static final String FIELDS =
            "OAS_Number,Type_Code,AGL,AMSL,Lighting,Verified,Quantity,City,State";

    public interface Callback {
        /**
         * @param inCircle nearest first
         * @param capped   true when the fetch stopped at {@link #MAX_ROWS} and the
         *                 circle may hold more than was asked for
         */
        void onLoaded(List<Obstacle> inCircle, boolean capped);

        void onFailed(String reason);
    }

    private DofSource() {
    }

    /** Fetches on a worker; the callback lands on the main thread. */
    public static void fetch(final Extent extent, final Callback cb) {
        page(extent, 0, new ArrayList<Obstacle>(), cb);
    }

    private static void page(final Extent extent, final int offset,
            final List<Obstacle> acc, final Callback cb) {
        final GeoBounds box = extent.bounds;
        final Map<String, String> form = new LinkedHashMap<>();
        form.put("where", "1=1");
        form.put("geometry", String.format(Locale.US,
                "{\"xmin\":%.6f,\"ymin\":%.6f,\"xmax\":%.6f,\"ymax\":%.6f,"
                        + "\"spatialReference\":{\"wkid\":4326}}",
                box.getWest(), box.getSouth(), box.getEast(), box.getNorth()));
        form.put("geometryType", "esriGeometryEnvelope");
        form.put("inSR", "4326");
        form.put("spatialRel", "esriSpatialRelIntersects");
        form.put("outFields", FIELDS);
        form.put("outSR", "4326");
        form.put("returnGeometry", "true");
        form.put("resultOffset", String.valueOf(offset));
        form.put("resultRecordCount", String.valueOf(PAGE));
        form.put("f", "json");

        Http.postForm(URL, form, new Http.Callback() {
            @Override
            public void onSuccess(byte[] body) {
                final boolean more;
                final int got;
                try {
                    final JSONObject root = new JSONObject(new String(body, "UTF-8"));
                    if (root.has("error")) {
                        final JSONObject err = root.getJSONObject("error");
                        Log.w(TAG, "FAA service error: " + err);
                        cb.onFailed("The FAA server refused the request.");
                        return;
                    }
                    got = parse(root, extent, acc);
                    more = root.optBoolean("exceededTransferLimit", false);
                } catch (JSONException | java.io.UnsupportedEncodingException e) {
                    Log.w(TAG, "FAA response could not be read", e);
                    cb.onFailed("The FAA server sent something that could not be read.");
                    return;
                }
                final int fetched = offset + got;
                if (more && got > 0 && fetched < MAX_ROWS) {
                    page(extent, fetched, acc, cb);
                    return;
                }
                Collections.sort(acc, new Comparator<Obstacle>() {
                    @Override
                    public int compare(Obstacle a, Obstacle b) {
                        return Double.compare(a.distanceM, b.distanceM);
                    }
                });
                Log.d(TAG, "obstacles: " + acc.size() + " in the circle of " + fetched
                        + " fetched" + (more ? ", capped" : ""));
                cb.onLoaded(acc, more);
            }

            @Override
            public void onFailure(int status, String error) {
                cb.onFailed(status == Http.NO_RESPONSE
                        ? "FAA server out of reach (" + error + ")."
                        : "FAA server answered HTTP " + status + ".");
            }
        });
    }

    /** Adds the rows inside the circle to {@code acc}; returns rows seen on the page. */
    private static int parse(JSONObject root, Extent extent,
            List<Obstacle> acc) throws JSONException {
        final JSONArray features = root.optJSONArray("features");
        if (features == null)
            return 0;
        for (int i = 0; i < features.length(); i++) {
            final JSONObject f = features.getJSONObject(i);
            final JSONObject a = f.optJSONObject("attributes");
            final JSONObject g = f.optJSONObject("geometry");
            if (a == null || g == null)
                continue;
            final double lon = g.optDouble("x", Double.NaN);
            final double lat = g.optDouble("y", Double.NaN);
            if (Double.isNaN(lat) || Double.isNaN(lon))
                continue;
            if (!extent.contains(lat, lon))
                continue;
            final double d = GeoCalculations.distanceTo(extent.center, new GeoPoint(lat, lon));
            final double agl = a.optDouble("AGL", Double.NaN);
            final double amsl = a.optDouble("AMSL", Double.NaN);
            if (Double.isNaN(agl) && Double.isNaN(amsl))
                continue;
            acc.add(new Obstacle(
                    trim(a.optString("OAS_Number", "")),
                    trim(a.optString("Type_Code", "")),
                    lat, lon,
                    Double.isNaN(agl) ? 0d : agl,
                    amsl,
                    trim(a.optString("Lighting", "")),
                    trim(a.optString("Verified", "")),
                    a.optInt("Quantity", 1),
                    trim(a.optString("City", "")),
                    trim(a.optString("State", "")),
                    d));
        }
        return features.length();
    }

    private static String trim(String s) {
        return s == null ? "" : s.trim();
    }
}
