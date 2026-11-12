package com.atakmap.android.iwi.plugin;


import com.atakmap.coremap.filesystem.FileSystemUtils;
import com.atakmap.coremap.log.Log;


import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.util.List;
import java.util.zip.GZIPInputStream;

/**
 * The trauma center pack we publish, downloaded once and then read from disk.
 *
 * <p>AirAware's {@code TilePack} cut to one file. The manifest names the pack's sha256,
 * so the file is fetched again only when it changed, and with no network the copy on
 * disk is what answers. The pack lives in ATAK's own area, never the plugin's files
 * dir (which ATAK's process cannot write) and never the cache dir (which the system
 * clears out from under a plugin). Worker thread only.
 */
public final class TraumaPack {

    private static final String TAG = "IWI-TraumaPack";

    /** v2, 2026-11-09: ERs, trauma and burn centers, state lists first (CA). */
    static final String BASE = "https://mapdepot.takwerx.org/iwi/hospitals/";
    static final String MANIFEST = "hospitals.json";
    static final String FILE = "hospitals.json.gz";

    /** The manifest is re-read this often; the FAA cycle behind it is 28 days. */
    private static final long MANIFEST_MAX_AGE_MS = 24L * 60L * 60L * 1000L;

    /** The most the pack may inflate to. The real one is about half a megabyte. */
    private static final int MAX_INFLATED = 16 * 1024 * 1024;
    /** The pack is under 100 KB gzipped; an answer many times that is not the pack. */
    private static final int MAX_DOWNLOAD = 4 * 1024 * 1024;
    /** The manifest is under a kilobyte. */
    private static final int MAX_MANIFEST = 64 * 1024;

    /**
     * The copy built into the APK, under assets/hospitals/: the depot's manifest and
     * pack at build time. The pack is renamed because the Android build drops a ".gz"
     * suffix from an asset, and then the two names collide.
     */
    private static final String ASSET_DIR = "hospitals/";
    private static final String ASSET_PACK = "pack.bin";
    private static final String ASSET_MANIFEST = "manifest.json";

    private final File dir;
    /** Where the built-in copy is read from; null in a test. */
    private final android.content.Context pluginContext;

    public TraumaPack(android.content.Context pluginContext) {
        this(new File(FileSystemUtils.getItem("tools/iwi"), "hospitals"), pluginContext);
    }

    TraumaPack(File dir, android.content.Context pluginContext) {
        this.dir = dir;
        this.pluginContext = pluginContext;
        if (!dir.exists() && !dir.mkdirs())
            Log.w(TAG, "could not make " + dir);
    }

    /**
     * With no pack on the device, the one built into the plugin is written there, so a
     * device loaded with no network still lists hospitals (operator, 2026-11-11). Its
     * manifest is dated long ago, so the first refresh with a network asks the depot at
     * once and replaces the copy when the depot has a newer one. Worker thread only.
     */
    public void seed() {
        if (pluginContext == null || new File(dir, FILE).exists())
            return;
        try {
            final byte[] gz = asset(ASSET_PACK, MAX_DOWNLOAD);
            final byte[] manifest = asset(ASSET_MANIFEST, MAX_MANIFEST);
            // Only a copy that agrees with its own manifest is written.
            final Index idx = read(new String(manifest, Charset.forName("UTF-8")));
            if (idx.isEmpty() || !idx.sha.equalsIgnoreCase(sha256(gz))) {
                Log.w(TAG, "the built-in pack does not match its manifest");
                return;
            }
            write(new File(dir, FILE), gz);
            final File m = new File(dir, MANIFEST);
            write(m, manifest);
            if (!m.setLastModified(0L))
                Log.d(TAG, "could not age the built-in manifest");
            Log.d(TAG, "seeded the built-in pack, " + idx.count + " hospitals");
        } catch (Exception e) {
            Log.w(TAG, "could not seed the built-in pack", e);
        }
    }

    private byte[] asset(String name, int max) throws IOException {
        InputStream in = null;
        try {
            in = pluginContext.getAssets().open(ASSET_DIR + name);
            final ByteArrayOutputStream out = new ByteArrayOutputStream(128 * 1024);
            final byte[] buf = new byte[32 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
                if (out.size() > max)
                    throw new IOException(name + " is larger than " + max + " bytes");
            }
            return out.toByteArray();
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (IOException ignored) {
                    // Already have the body or the failure.
                }
            }
        }
    }

    /** What the manifest says about the pack. */
    public static final class Index {
        /** FAA NASR effective date, YYYY-MM-DD. */
        public String cycle = "";
        public String built = "";
        public String hospitalsDate = "";
        public String hospitalsSource = "";
        public int count;
        /** Per state with its own lists: "trauma CA EMSA 2025-08-25, burn ..., ER ...". */
        public final java.util.Map<String, String> states = new java.util.TreeMap<>();
        public String sha = "";

        public boolean isEmpty() {
            return sha.isEmpty();
        }
    }

    /** Whether the pack itself is on the phone, without touching the network. */
    public boolean onDisk() {
        return new File(dir, FILE).exists();
    }

    /**
     * The manifest, from disk when it is fresh and from the depot when it is not.
     *
     * @param network whether the depot may be asked at all. A stale manifest is still a
     *            good manifest: the pack it names is still there, so a failed refresh
     *            keeps working rather than emptying the line.
     */
    public Index index(boolean network) {
        final File file = new File(dir, MANIFEST);
        final boolean fresh = file.exists()
                && System.currentTimeMillis() - file.lastModified() < MANIFEST_MAX_AGE_MS;
        if (network && !fresh) {
            try {
                write(file, Http.get(BASE + MANIFEST, MAX_MANIFEST));
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
        final JSONObject elsewhere = root.optJSONObject("elsewhere");
        if (elsewhere != null) {
            index.hospitalsDate = elsewhere.optString("date", "");
            index.hospitalsSource = elsewhere.optString("source", "");
        }
        final JSONObject states = root.optJSONObject("states");
        if (states != null) {
            final java.util.Iterator<String> it = states.keys();
            while (it.hasNext()) {
                final String st = it.next();
                final JSONObject o = states.optJSONObject(st);
                if (o != null)
                    index.states.put(st, "trauma levels " + o.optString("trauma", "?")
                            + ", burn centers " + o.optString("burn", "?")
                            + ", ERs " + o.optString("er", "?"));
            }
        }
        final JSONObject files = root.optJSONObject("files");
        final JSONObject entry = files == null ? null : files.optJSONObject(FILE);
        if (entry != null) {
            index.sha = entry.optString("sha256", "");
            index.count = entry.optInt("count", 0);
        }
        return index;
    }

    /**
     * Every center in the pack: from disk when the copy there is the one the manifest
     * names, from the depot when it is not and the network may be used.
     *
     * @param sha the manifest's sha256, or null to take whatever is on disk
     * @param network whether the depot may be asked
     * @throws IOException when there is no pack on the phone and none could be fetched
     */
    public List<TraumaCenter> load(String sha, boolean network) throws IOException {
        final File file = new File(dir, FILE);
        byte[] gz = null;
        if (file.exists()) {
            gz = readAll(file);
            if (network && sha != null && !sha.isEmpty()
                    && !sha.equalsIgnoreCase(sha256(gz))) {
                // The manifest names a newer pack. Fetch it, and only on success replace
                // what is held: a phone with no network keeps the old one.
                try {
                    final byte[] fresh = fetch(sha);
                    write(file, fresh);
                    gz = fresh;
                } catch (IOException e) {
                    Log.d(TAG, "newer pack not reachable, keeping the old: " + e);
                }
            }
        } else {
            if (!network)
                throw new IOException("no pack on this device");
            gz = fetch(sha);
            write(file, gz);
        }
        try {
            return TraumaCenter.parse(new JSONObject(
                    new String(gunzip(gz), Charset.forName("UTF-8"))));
        } catch (Exception e) {
            // A half-written or corrupt pack should not be kept and retried forever.
            if (!file.delete())
                Log.w(TAG, "could not drop the unreadable pack");
            throw new IOException("the pack was not readable");
        }
    }

    /**
     * The pack from the depot, refused unless it is the one the manifest names: a
     * truncated or swapped answer is never written over a good pack (added in LCES;
     * the lessons catalog is checked the same way).
     */
    private static byte[] fetch(String sha) throws IOException {
        final byte[] body = Http.get(BASE + FILE, MAX_DOWNLOAD);
        if (sha != null && !sha.isEmpty() && !sha.equalsIgnoreCase(sha256(body)))
            throw new IOException("the pack does not match its manifest");
        return body;
    }

    private static String sha256(byte[] body) {
        try {
            final java.security.MessageDigest md =
                    java.security.MessageDigest.getInstance("SHA-256");
            final byte[] d = md.digest(body);
            final StringBuilder b = new StringBuilder(64);
            for (byte x : d)
                b.append(Character.forDigit((x >> 4) & 0xF, 16))
                        .append(Character.forDigit(x & 0xF, 16));
            return b.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            return "";
        }
    }

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
                    throw new IOException("pack inflates past " + MAX_INFLATED + " bytes");
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

    /** Written to a temp file and renamed, so a killed process never leaves half a pack. */
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
        final long length = file.length();
        if (length > MAX_INFLATED)
            throw new IOException(file.getName() + " is too large to be ours");
        final ByteArrayOutputStream out = new ByteArrayOutputStream((int) length);
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
