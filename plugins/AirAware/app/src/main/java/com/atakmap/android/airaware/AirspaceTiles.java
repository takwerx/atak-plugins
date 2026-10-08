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
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.zip.GZIPInputStream;

/**
 * Airspace as tiles we publish, rather than live from the FAA.
 *
 * <p>The FAA's service is keyless and public and its quota -- 6,000 request units a
 * minute -- is shared with every other caller in the world. One phone and one laptop
 * spent it on 2026-10-08, and the operator met "the FAA airspace service is busy" over
 * and over while testing a layer that had nothing wrong with it. Thirty EUDs in a fire
 * camp would never have seen airspace at all. Airspace also changes on the 56-day
 * publication cycle, so asking a live service on every pan was the wrong model twice.
 *
 * <p>So {@code tools/build_airspace_pack.py} pulls the country once per cycle and writes
 * two-degree tiles to our own storage, and this reads them. No quota, no rate limit, and
 * a tile that has been read once is on the phone for good -- which is the rule for
 * downloaded data, and the thing the live fetch could never do for a crew driving into a
 * canyon.
 *
 * <p>A tile holds the service's own shape, so {@link AirspaceFeed#parse} reads a tile and
 * a live answer with one reader and the two can never disagree about what a ring means.
 */
public final class AirspaceTiles {

    private static final String TAG = "AirspaceTiles";

    static final String BASE = "https://mapdepot.takwerx.org/airaware/airspace/";
    private static final String MANIFEST = "airspace.json";
    /**
     * Shapes too big to tile, loaded wherever the operator is.
     *
     * <p>Class A is one polygon from 18,000 ft to FL600 over the whole contiguous United
     * States. Tiling it wrote its outline into 390 tiles and made a three-degree test
     * slice 39.9 MB; in its own file the same slice is 0.2 MB.
     */
    private static final String WIDE = "wide.json.gz";

    /** The manifest is re-read this often; the tiles it names never change under it. */
    private static final long MANIFEST_MAX_AGE_MS = 24L * 60L * 60L * 1000L;

    private AirspaceTiles() {
    }

    /** What the manifest says exists, so a tile over empty ocean is never asked for. */
    public static class Index {
        public String cycle = "";
        public int tileDegrees = 2;
        public String credit = "";
        public final Set<String> tiles = new HashSet<>();

        public boolean isEmpty() {
            return tiles.isEmpty();
        }
    }

    /** Where tiles live on the phone. Never the cache dir, which the system clears. */
    public static File dir(File toolsDir) {
        final File d = new File(toolsDir, "airspace");
        if (!d.exists() && !d.mkdirs())
            Log.w(TAG, "could not make " + d);
        return d;
    }

    /**
     * The manifest, from disk when it is fresh and from the network when it is not.
     *
     * <p>A stale manifest is still a good manifest: the tiles it names are all still
     * there, so a failed refresh keeps working rather than emptying the layer.
     */
    public static Index index(File dir) {
        final File file = new File(dir, MANIFEST);
        final boolean fresh = file.exists()
                && System.currentTimeMillis() - file.lastModified() < MANIFEST_MAX_AGE_MS;
        if (!fresh) {
            try {
                write(file, Http.get(BASE + MANIFEST));
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
        index.tileDegrees = Math.max(1, root.optInt("tile_degrees", 2));
        index.credit = root.optString("credit", "");
        final JSONObject tiles = root.optJSONObject("tiles");
        if (tiles != null) {
            final java.util.Iterator<String> keys = tiles.keys();
            while (keys.hasNext())
                index.tiles.add(keys.next());
        }
        return index;
    }

    /**
     * Every tile key a view touches, south-west corner first, floored to the tile size.
     *
     * <p>Only keys the manifest knows about: most of a two-degree grid over the country
     * is ocean and desert with no airspace in it, and asking for those is a round trip
     * per pan to be told nothing is there.
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

    /** The wide shapes, which belong everywhere. Cached like a tile. */
    public static List<Airspace> wide(File dir) throws IOException {
        return load(dir, WIDE, BASE + WIDE);
    }

    /** One tile, from disk when it is there and from our storage when it is not. */
    public static List<Airspace> tile(File dir, String key) throws IOException {
        final String name = key + ".json.gz";
        return load(dir, name, BASE + "tiles/" + name);
    }

    /**
     * A tile is written once and then read from disk for good.
     *
     * <p>Nothing re-downloads one: a tile belongs to a publication cycle, and when a new
     * cycle lands the manifest's cycle changes and {@link #clear} takes the old ones
     * away in one go. So an operator who has been somewhere keeps that airspace with no
     * network, and a pan back over it costs nothing.
     */
    private static List<Airspace> load(File dir, String name, String url)
            throws IOException {
        final File file = new File(dir, name);
        byte[] gz;
        if (file.exists()) {
            gz = readAll(file);
        } else {
            gz = Http.get(url);
            write(file, gz);
        }
        final String json = new String(gunzip(gz), Charset.forName("UTF-8"));
        try {
            return AirspaceFeed.parse(new JSONObject(json));
        } catch (Exception e) {
            // A half-written or corrupt tile should not be kept and retried forever.
            if (!file.delete())
                Log.w(TAG, "could not drop the unreadable tile " + name);
            throw new IOException("tile " + name + " was not readable");
        }
    }

    /** Drop every tile, for a new publication cycle. */
    public static void clear(File dir) {
        final File[] files = dir.listFiles();
        if (files == null)
            return;
        for (File f : files) {
            if (f.getName().endsWith(".json.gz") && !f.delete())
                Log.w(TAG, "could not remove " + f.getName());
        }
    }

    /** What is already on the phone, so the pane can say what it has without a network. */
    public static int cachedTiles(File dir) {
        final File[] files = dir.listFiles();
        if (files == null)
            return 0;
        int n = 0;
        for (File f : files)
            if (f.getName().endsWith(".json.gz"))
                n++;
        return n;
    }

    private static byte[] gunzip(byte[] gz) throws IOException {
        final ByteArrayOutputStream out = new ByteArrayOutputStream(Math.max(64 * 1024,
                gz.length * 4));
        InputStream in = null;
        try {
            in = new GZIPInputStream(new java.io.ByteArrayInputStream(gz));
            final byte[] buf = new byte[32 * 1024];
            int n;
            while ((n = in.read(buf)) > 0)
                out.write(buf, 0, n);
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

    static String describe(int tiles) {
        return String.format(Locale.US, "%d tile%s on this phone", tiles,
                tiles == 1 ? "" : "s");
    }
}
