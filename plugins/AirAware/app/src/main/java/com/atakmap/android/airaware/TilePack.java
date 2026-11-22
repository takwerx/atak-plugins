package com.atakmap.android.airaware;

import com.atakmap.android.airaware.net.Http;
import com.atakmap.coremap.log.Log;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.zip.GZIPInputStream;

/**
 * A set of map tiles we publish, downloaded once and then read from disk.
 *
 * <p>Both of AirAware's bulk datasets work this way and for the same reason. The FAA
 * serves airspace and obstacles from one ArcGIS organization whose quota -- 6,000
 * request units a minute -- is shared with every other caller in the world; one phone
 * and one laptop spent it on 2026-10-08. Both also change on a slow cycle. So the
 * country is pulled once by {@code tools/build_airspace_pack.py} and
 * {@code tools/build_obstacle_pack.py}, cut into two-degree tiles on our own storage,
 * and a tile read once stays on the phone -- which is the rule for downloaded data, and
 * the thing a live fetch could never give a crew driving into a canyon.
 *
 * <p>This holds everything that is the same between them: the manifest, which tiles a
 * view touches, the download, the atomic write and the cache. What a tile <i>contains</i>
 * is the caller's business.
 */
public final class TilePack {

    private static final String TAG = "TilePack";

    /** The manifest is re-read this often; the tiles it names never change under it. */
    private static final long MANIFEST_MAX_AGE_MS = 24L * 60L * 60L * 1000L;

    private final String base;
    private final String manifestName;
    private final File dir;
    private final long manifestMaxAgeMs;

    /**
     * @param base the published folder, ending in a slash
     * @param manifestName the manifest file inside it
     * @param dir where tiles live on the phone. Never the cache dir, which the system
     *            clears out from under a plugin.
     */
    public TilePack(String base, String manifestName, File dir) {
        this(base, manifestName, dir, MANIFEST_MAX_AGE_MS);
    }

    /**
     * @param manifestMaxAgeMs how long the manifest on disk is trusted. A day for a pack
     *            on the FAA's 56-day cycle; minutes for the NOTAMs, which the relay
     *            republishes all day.
     */
    public TilePack(String base, String manifestName, File dir, long manifestMaxAgeMs) {
        this.base = base;
        this.manifestName = manifestName;
        this.dir = dir;
        this.manifestMaxAgeMs = manifestMaxAgeMs;
        if (dir != null && !dir.exists() && !dir.mkdirs())
            Log.w(TAG, "could not make " + dir);
    }

    /** What the manifest says exists, so a tile over empty ocean is never asked for. */
    public static class Index {
        public String cycle = "";
        /** When the pack was built, as the builder stamps it; empty for the older packs. */
        public String built = "";
        public int tileDegrees = 2;
        public String credit = "";
        public final Set<String> tiles = new HashSet<>();
        /** Each tile's sha256 where the manifest carries one, so a changed tile is known. */
        public final java.util.Map<String, String> sha = new java.util.HashMap<>();

        public boolean isEmpty() {
            return tiles.isEmpty();
        }
    }

    /**
     * The manifest, from disk when it is fresh and from the network when it is not.
     *
     * <p>A stale manifest is still a good manifest: the tiles it names are all still
     * there, so a failed refresh keeps working rather than emptying the layer.
     */
    public Index index() {
        final File file = new File(dir, manifestName);
        final boolean fresh = file.exists()
                && System.currentTimeMillis() - file.lastModified() < manifestMaxAgeMs;
        if (!fresh) {
            try {
                write(file, Http.get(base + manifestName));
            } catch (Exception e) {
                Log.d(TAG, "manifest refresh failed, using what is on disk: " + e);
            }
        }
        if (!file.exists())
            return new Index();
        try {
            return read(new String(readAll(file), Charset.forName("UTF-8")));
        } catch (Exception e) {
            Log.w(TAG, "the manifest on disk is unreadable", e);
            return new Index();
        }
    }

    private static Index read(String json) throws Exception {
        final JSONObject root = new JSONObject(json);
        final Index index = new Index();
        index.cycle = root.optString("cycle", "");
        index.built = root.optString("built", "");
        index.tileDegrees = Math.min(10, Math.max(1, root.optInt("tile_degrees", 2)));
        index.credit = root.optString("credit", "");
        final JSONObject tiles = root.optJSONObject("tiles");
        if (tiles != null) {
            final Iterator<String> keys = tiles.keys();
            while (keys.hasNext()) {
                final String key = keys.next();
                index.tiles.add(key);
                final JSONObject entry = tiles.optJSONObject(key);
                final String sha = entry == null ? "" : entry.optString("sha256", "");
                if (!sha.isEmpty())
                    index.sha.put(key, sha);
            }
        }
        // A pack that is one file rather than tiles names it here, with its hash, so
        // the file is fetched again only when it changed. Keyed by file name.
        final JSONObject files = root.optJSONObject("files");
        if (files != null) {
            final Iterator<String> names = files.keys();
            while (names.hasNext()) {
                final String name = names.next();
                final JSONObject entry = files.optJSONObject(name);
                final String sha = entry == null ? "" : entry.optString("sha256", "");
                if (!sha.isEmpty())
                    index.sha.put(name, sha);
            }
        }
        return index;
    }

    /**
     * Every tile key a view touches, south-west corner first, floored to the tile size.
     *
     * <p>Only keys the manifest knows about: most of a two-degree grid over the country
     * is ocean and desert with nothing in it, and asking for those is a round trip per
     * pan to be told so.
     */
    public static List<String> keysFor(Index index, double south, double west,
            double north, double east) {
        final int step = index.tileDegrees;
        final List<String> keys = new ArrayList<>();
        for (int lat = floor(south, step); lat <= floor(north, step); lat += step) {
            for (int lon = floor(west, step); lon <= floor(east, step); lon += step) {
                final String key = lat + "_" + lon;
                if (index.tiles.contains(key))
                    keys.add(key);
            }
        }
        return keys;
    }

    private static int floor(double v, int step) {
        return (int) (Math.floor(v / step) * step);
    }

    /** One tile's JSON, from disk when it is there and from our storage when it is not. */
    public JSONObject tile(String key) throws IOException {
        return json(key + ".json.gz", base + "tiles/" + key + ".json.gz", null);
    }

    /**
     * One tile's JSON, fetched again when the copy on disk is not the one the manifest
     * names. For a pack that changes all day: the manifest's hash says whether the tile
     * on disk is current, and nothing is thrown away before its replacement is in hand.
     *
     * @param sha the manifest's sha256 for the tile, or null to take whatever is on disk
     */
    public JSONObject tile(String key, String sha) throws IOException {
        return json(key + ".json.gz", base + "tiles/" + key + ".json.gz", sha);
    }

    /** Drop tiles the manifest no longer names. For a pack that changes all day. */
    public void prune(Index index) {
        final File[] files = dir.listFiles();
        if (files == null)
            return;
        for (File f : files) {
            final String name = f.getName();
            if (!name.endsWith(".json.gz"))
                continue;
            final String key = name.substring(0, name.length() - ".json.gz".length());
            if (!index.tiles.contains(key) && !f.delete())
                Log.w(TAG, "could not remove the stale tile " + name);
        }
    }

    /** Any other published file in the pack, cached the same way. */
    public JSONObject file(String name) throws IOException {
        return json(name, base + name, null);
    }

    /**
     * A published file fetched again when the copy on disk is not the one the manifest
     * names, the way {@link #tile(String, String)} does for a tile.
     *
     * @param sha the manifest's sha256 for the file, or null to take whatever is on disk
     */
    public JSONObject file(String name, String sha) throws IOException {
        return json(name, base + name, sha);
    }

    /**
     * A tile is written once and then read from disk for good.
     *
     * <p>Nothing re-downloads one: a tile belongs to a publication cycle, and when a new
     * cycle lands the manifest's cycle changes and {@link #clear} takes the old ones away
     * in one go. So an operator who has been somewhere keeps it with no network, and a
     * pan back over it costs nothing.
     */
    private JSONObject json(String name, String url, String sha) throws IOException {
        final File file = new File(dir, name);
        byte[] gz = null;
        if (file.exists()) {
            gz = readAll(file);
            if (sha != null && !sha.equalsIgnoreCase(sha256(gz))) {
                // The manifest names a newer tile. Fetch it, and only on success replace
                // what is held: a phone with no network keeps the old one.
                try {
                    final byte[] fresh = Http.get(url);
                    write(file, fresh);
                    gz = fresh;
                } catch (IOException e) {
                    Log.d(TAG, "newer tile " + name + " not reachable, keeping the old: " + e);
                }
            }
        } else {
            gz = Http.get(url);
            write(file, gz);
        }
        try {
            return new JSONObject(new String(gunzip(gz), Charset.forName("UTF-8")));
        } catch (Exception e) {
            // A half-written or corrupt tile should not be kept and retried forever.
            if (!file.delete())
                Log.w(TAG, "could not drop the unreadable tile " + name);
            throw new IOException("tile " + name + " was not readable");
        }
    }

    /** Drop every tile, for a new publication cycle. */
    public void clear() {
        final File[] files = dir.listFiles();
        if (files == null)
            return;
        for (File f : files) {
            if (f.getName().endsWith(".json.gz") && !f.delete())
                Log.w(TAG, "could not remove " + f.getName());
        }
    }

    /** What is already on the phone, so the pane can say what it has without a network. */
    public int cachedTiles() {
        final File[] files = dir.listFiles();
        if (files == null)
            return 0;
        int n = 0;
        for (File f : files)
            if (f.getName().endsWith(".json.gz"))
                n++;
        return n;
    }

    private static String sha256(byte[] body) {
        try {
            final java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            final byte[] d = md.digest(body);
            final StringBuilder b = new StringBuilder(64);
            for (byte x : d)
                b.append(Character.forDigit((x >> 4) & 0xF, 16)).append(Character.forDigit(x & 0xF, 16));
            return b.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            return "";
        }
    }

    /** The most a tile may inflate to. The largest real one is under 2 MB. */
    private static final int MAX_INFLATED = 48 * 1024 * 1024;

    private static byte[] gunzip(byte[] gz) throws IOException {
        final ByteArrayOutputStream out = new ByteArrayOutputStream(
                Math.max(64 * 1024, gz.length * 4));
        InputStream in = null;
        try {
            in = new GZIPInputStream(new java.io.ByteArrayInputStream(gz));
            final byte[] buf = new byte[32 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
                if (out.size() > MAX_INFLATED)
                    throw new IOException("tile inflates past " + MAX_INFLATED + " bytes");
            }
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (IOException ignored) {
                    // Already have the body or the failure.
                }
            }
        }
        return out.toByteArray();
    }

    /** Written to a temp file and renamed, so a killed process never leaves half a tile. */
    private static void write(File file, byte[] body) throws IOException {
        final File tmp = new File(file.getParentFile(),
                file.getName() + ".part" + Long.toString(System.nanoTime(), 36));
        FileOutputStream out = null;
        try {
            out = new FileOutputStream(tmp);
            out.write(body);
            out.getFD().sync();
        } finally {
            if (out != null) {
                try {
                    out.close();
                } catch (IOException ignored) {
                    // The rename below is what decides whether this counts.
                }
            }
        }
        if (!tmp.renameTo(file)) {
            if (!tmp.delete())
                Log.w(TAG, "could not clean up " + tmp.getName());
            throw new IOException("could not save " + file.getName());
        }
    }

    private static byte[] readAll(File file) throws IOException {
        final ByteArrayOutputStream out = new ByteArrayOutputStream((int) file.length());
        FileInputStream in = null;
        try {
            in = new FileInputStream(file);
            final byte[] buf = new byte[32 * 1024];
            int n;
            while ((n = in.read(buf)) > 0)
                out.write(buf, 0, n);
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (IOException ignored) {
                    // Already have the bytes or the failure.
                }
            }
        }
        return out.toByteArray();
    }
}
