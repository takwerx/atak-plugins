package com.atakmap.android.atmosphere.data;

import com.atakmap.coremap.log.Log;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Arrays;
import java.util.Comparator;

/**
 * Last-good responses on disk, so the plugin still answers after comms drop.
 *
 * <p>Deliberately files and not a database: a cached forecast is one small JSON document
 * keyed by source and place, and a plugin should not carry an ORM, an annotation
 * processor and a migration story to store one.
 *
 * <p>The raw response body is stored rather than parsed values, so a later build with a
 * corrected source definition re-reads old responses correctly.
 *
 * <p>Lives in ATAK's private files directory, not on external storage: it holds places
 * the operator has looked at.
 */
public final class SnapshotStore {

    private static final String TAG = "WxSnapshotStore";
    private static final int MAX_FILES = 128;

    private final File dir;

    public SnapshotStore(File dir) {
        this.dir = dir;
        if (!dir.isDirectory() && !dir.mkdirs())
            Log.w(TAG, "cannot create cache dir " + dir);
    }

    /** One cached response. */
    public static final class Entry {
        public final String body;
        public final long fetchedAt;
        public final double latitude;
        public final double longitude;

        Entry(String body, long fetchedAt, double latitude, double longitude) {
            this.body = body;
            this.fetchedAt = fetchedAt;
            this.latitude = latitude;
            this.longitude = longitude;
        }

        public long ageMillis(long now) {
            return Math.max(0L, now - fetchedAt);
        }
    }

    /**
     * @param key already includes the rounded position, so two nearby lookups share a
     *            cache entry exactly when they shared a request
     */
    public Entry read(String key) {
        final File f = fileFor(key);
        if (!f.isFile())
            return null;
        InputStream in = null;
        try {
            in = new FileInputStream(f);
            final JSONObject o = new JSONObject(readAll(in));
            return new Entry(o.optString("body", ""), o.optLong("fetchedAt", 0L),
                    o.optDouble("latitude", Double.NaN),
                    o.optDouble("longitude", Double.NaN));
        } catch (IOException | JSONException e) {
            Log.w(TAG, "unreadable cache entry, dropping: " + f.getName());
            if (!f.delete())
                Log.w(TAG, "could not delete " + f.getName());
            return null;
        } finally {
            closeQuietly(in);
        }
    }

    public void write(String key, String body, long fetchedAt, double latitude,
            double longitude) {
        final File f = fileFor(key);
        OutputStream out = null;
        try {
            final JSONObject o = new JSONObject();
            o.put("body", body);
            o.put("fetchedAt", fetchedAt);
            // Android's org.json refuses NaN in put(); the resolve entries carry no
            // position, and read() already defaults a missing field to NaN.
            if (!Double.isNaN(latitude))
                o.put("latitude", latitude);
            if (!Double.isNaN(longitude))
                o.put("longitude", longitude);
            out = new FileOutputStream(f);
            out.write(o.toString().getBytes("UTF-8"));
        } catch (IOException | JSONException e) {
            Log.w(TAG, "could not cache " + key, e);
        } finally {
            if (out != null) {
                try {
                    out.close();
                } catch (IOException ignored) {
                    // The entry is either written or will be re-fetched.
                }
            }
        }
        trim();
    }

    /** Cache key for a source at a rounded position. */
    public static String key(String sourceId, String latitude, String longitude) {
        return sanitize(sourceId) + "_" + sanitize(latitude) + "_" + sanitize(longitude);
    }

    public void clear() {
        final File[] files = dir.listFiles();
        if (files == null)
            return;
        for (File f : files) {
            if (!f.delete())
                Log.w(TAG, "could not delete " + f.getName());
        }
    }

    public int count() {
        final File[] files = dir.listFiles();
        return files == null ? 0 : files.length;
    }

    private File fileFor(String key) {
        return new File(dir, sanitize(key) + ".json");
    }

    /** Oldest entries go first once the cache exceeds its cap. */
    private void trim() {
        final File[] files = dir.listFiles();
        if (files == null || files.length <= MAX_FILES)
            return;
        Arrays.sort(files, new Comparator<File>() {
            @Override
            public int compare(File a, File b) {
                return Long.compare(a.lastModified(), b.lastModified());
            }
        });
        for (int i = 0; i < files.length - MAX_FILES; i++) {
            if (!files[i].delete())
                Log.w(TAG, "could not evict " + files[i].getName());
        }
    }

    /** Keys come from source ids and formatted coordinates; keep them path-safe anyway. */
    private static String sanitize(String s) {
        final StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            final char c = s.charAt(i);
            if (Character.isLetterOrDigit(c) || c == '-' || c == '_')
                sb.append(c);
            else if (c == '.')
                sb.append('p');
            else
                sb.append('x');
        }
        return sb.toString();
    }

    private static String readAll(InputStream in) throws IOException {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        final byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0)
            out.write(buf, 0, n);
        return out.toString("UTF-8");
    }

    private static void closeQuietly(InputStream in) {
        if (in == null)
            return;
        try {
            in.close();
        } catch (IOException ignored) {
            // Read already completed or already failed.
        }
    }
}
