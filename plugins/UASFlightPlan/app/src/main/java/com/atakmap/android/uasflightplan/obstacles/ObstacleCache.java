package com.atakmap.android.uasflightplan.obstacles;

import com.atakmap.android.uasflightplan.terrain.Extent;
import com.atakmap.coremap.filesystem.FileSystemUtils;
import com.atakmap.coremap.log.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The last obstacle list the FAA sent, kept on the phone for the area it was
 * fetched for, so the plan comes back after a restart with no network.
 *
 * <p>One file, one area: a plan is one area at a time, and the file is a copy of
 * public data that changes every 8 weeks. The key names the area; a different
 * area gets nothing from the file. Worker thread only.
 */
final class ObstacleCache {

    private static final String TAG = "UASObstacles";
    private static final String FILE = "obstacles-last.json";

    private ObstacleCache() {
    }

    /** Names the area: its box to five decimals plus its kind. */
    static String key(Extent e) {
        return String.format(Locale.US, "%.5f,%.5f,%.5f,%.5f,%s",
                e.bounds.getNorth(), e.bounds.getSouth(), e.bounds.getEast(),
                e.bounds.getWest(), e.isCircle() ? "c" + Math.round(e.radiusM) : "d");
    }

    private static File file() {
        final File dir = FileSystemUtils.getItem("tools/uasflightplan");
        if (dir == null)
            return null;
        if (!dir.isDirectory() && !dir.mkdirs())
            return null;
        return new File(dir, FILE);
    }

    static void save(String key, List<Obstacle> list) {
        final File f = file();
        if (f == null)
            return;
        try {
            final JSONArray rows = new JSONArray();
            for (Obstacle o : list) {
                final JSONObject r = new JSONObject();
                r.put("oas", o.oas);
                r.put("type", o.typeCode);
                r.put("lat", o.lat);
                r.put("lon", o.lon);
                r.put("agl", o.aglFt);
                r.put("amsl", o.amslFt);
                r.put("light", o.lighting);
                r.put("ver", o.verified);
                r.put("qty", o.quantity);
                r.put("city", o.city);
                r.put("state", o.state);
                r.put("dist", o.distanceM);
                rows.put(r);
            }
            final JSONObject root = new JSONObject();
            root.put("key", key);
            root.put("rows", rows);
            final File tmp = new File(f.getPath() + ".tmp");
            final FileOutputStream out = new FileOutputStream(tmp);
            try {
                out.write(root.toString().getBytes("UTF-8"));
            } finally {
                out.close();
            }
            //noinspection ResultOfMethodCallIgnored
            tmp.renameTo(f);
        } catch (Exception e) {
            Log.w(TAG, "could not save the obstacle list", e);
        }
    }

    /** The saved list for this key, or null when there is none or it is for another area. */
    static List<Obstacle> load(String key) {
        final File f = file();
        if (f == null || !f.isFile())
            return null;
        try {
            final InputStream in = new FileInputStream(f);
            final byte[] body;
            try {
                final java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream();
                final byte[] chunk = new byte[16384];
                int n;
                while ((n = in.read(chunk)) > 0)
                    buf.write(chunk, 0, n);
                body = buf.toByteArray();
            } finally {
                in.close();
            }
            final JSONObject root = new JSONObject(new String(body, "UTF-8"));
            if (!key.equals(root.optString("key")))
                return null;
            final JSONArray rows = root.getJSONArray("rows");
            final List<Obstacle> out = new ArrayList<>(rows.length());
            for (int i = 0; i < rows.length(); i++) {
                final JSONObject r = rows.getJSONObject(i);
                // A corrupt or edited file must not push NaN into the native
                // geometry: the same check the FAA parse and the SHOW receiver make.
                final double lat = r.optDouble("lat", Double.NaN);
                final double lon = r.optDouble("lon", Double.NaN);
                if (Double.isNaN(lat) || Double.isNaN(lon)
                        || Math.abs(lat) > 90d || Math.abs(lon) > 180d)
                    continue;
                out.add(new Obstacle(r.optString("oas", ""), r.optString("type", ""),
                        lat, lon, r.optDouble("agl", 0d),
                        r.optDouble("amsl", Double.NaN), r.optString("light", ""),
                        r.optString("ver", ""), r.optInt("qty", 1), r.optString("city", ""),
                        r.optString("state", ""), r.optDouble("dist", 0d)));
            }
            Log.d(TAG, "obstacles from the phone's copy: " + out.size());
            return out;
        } catch (Exception e) {
            Log.w(TAG, "could not read the saved obstacle list", e);
            return null;
        }
    }
}
