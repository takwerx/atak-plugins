package com.atakmap.android.airaware;

import com.atakmap.coremap.filesystem.FileSystemUtils;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * The FAA Digital Obstacle File as tiles we publish.
 *
 * <p>657,732 obstacles nationally. The FAA serves the same data from the ArcGIS
 * organization behind the airspace layer, where that many rows is 326 paged queries
 * against a quota shared with every other caller in the world -- so
 * {@code tools/build_obstacle_pack.py} reads the FAA's own bulk file instead, once a
 * day, and cuts it into the same two-degree tiles. 10.7 MB for the country, a median
 * tile of 1 KB and the worst (Los Angeles) 193 KB.
 *
 * <p>A record is an <b>array, not an object</b>, because the key names would otherwise
 * be most of the file at that many rows. The order is fixed by the publisher and
 * repeated in its manifest:
 *
 * <pre>[oas, type, lat, lon, aglFt, amslFt, lighting, verified, quantity, city, state]</pre>
 */
public final class ObstacleTiles {

    static final String BASE = "https://mapdepot.takwerx.org/airaware/obstacles/";

    private ObstacleTiles() {
    }

    public static TilePack pack() {
        return new TilePack(BASE, "obstacles.json",
                new File(FileSystemUtils.getItem("tools/airaware"), "obstacles"));
    }

    /** Every obstacle in one tile. Worker thread only. */
    public static List<Obstacle> tile(TilePack pack, String key) throws IOException {
        final JSONObject root = pack.tile(key);
        final JSONArray rows = root.optJSONArray("obstacles");
        final List<Obstacle> out = new ArrayList<>();
        if (rows == null)
            return out;
        for (int i = 0; i < rows.length(); i++) {
            final Obstacle o = one(rows.optJSONArray(i));
            if (o != null)
                out.add(o);
        }
        return out;
    }

    private static Obstacle one(JSONArray r) {
        if (r == null || r.length() < 11)
            return null;
        final double lat = r.optDouble(2, Double.NaN);
        final double lon = r.optDouble(3, Double.NaN);
        if (Double.isNaN(lat) || Double.isNaN(lon))
            return null;
        return new Obstacle(
                r.optString(0, ""), r.optString(1, ""), lat, lon,
                r.optDouble(4, 0d), r.optDouble(5, 0d),
                r.optString(6, ""), r.optString(7, ""), r.optInt(8, 1),
                r.optString(9, ""), r.optString(10, ""), Double.NaN);
    }
}
