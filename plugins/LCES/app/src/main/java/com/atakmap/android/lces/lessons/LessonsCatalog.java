package com.atakmap.android.lces.lessons;

import android.content.Context;

import com.atakmap.coremap.filesystem.FileSystemUtils;
import com.atakmap.coremap.log.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.zip.GZIPInputStream;

/**
 * The Lessons Learned catalog: the snapshot bundled in the APK, and a newer copy from
 * our depot when one has been fetched.
 *
 * <p>The layer works on first start with no network because the APK carries the
 * catalog ({@code assets/lessons/lessons.json}). A refresh, at most once a day, reads
 * the depot's manifest and fetches the catalog only when its hash changed; it is kept
 * in {@code tools/lces/lessons/} (ATAK's own area: the plugin's files dir is another
 * uid's, and the cache dir is cleared by the system). Whichever copy was built later
 * is the one used, so an APK update with a newer snapshot wins over an older download.
 *
 * <p>Worker thread only: everything here reads files or the network.
 */
public final class LessonsCatalog {

    private static final String TAG = "LCES-Lessons";

    static final String BASE = "https://mapdepot.takwerx.org/lces/lessons/";
    static final String MANIFEST = "lessons.json";
    static final String FILE = "lessons.json.gz";
    static final String ASSET = "lessons/lessons.json";

    /** The manifest is re-read this often. */
    private static final long MANIFEST_MAX_AGE_MS = 24L * 60L * 60L * 1000L;
    private static final int MAX_MANIFEST = 64 * 1024;
    /** The catalog gzips to about 600 KB. */
    private static final int MAX_GZ = 8 * 1024 * 1024;
    /** And inflates to about 2.2 MB. */
    private static final int MAX_INFLATED = 32 * 1024 * 1024;
    /** A catalog with far fewer incidents than the one held is a broken build, not news. */
    private static final double MIN_SHARE_OF_HELD = 0.5;

    /** A loaded catalog. */
    public static final class Data {
        public final List<Incident> incidents;
        /** When it was built, ISO-8601 UTC, as the builder wrote it. */
        public final String built;
        /** When the LLC last edited the source, YYYY-MM-DD. */
        public final String sourceEdited;
        /** "bundled" or "downloaded". */
        public final String origin;

        Data(List<Incident> incidents, String built, String sourceEdited, String origin) {
            this.incidents = Collections.unmodifiableList(incidents);
            this.built = built;
            this.sourceEdited = sourceEdited;
            this.origin = origin;
        }
    }

    private final Context pluginContext;
    private final File dir;

    public LessonsCatalog(Context pluginContext) {
        this.pluginContext = pluginContext;
        this.dir = new File(FileSystemUtils.getItem("tools/lces"), "lessons");
    }

    /** The catalog to draw: the newer of the bundled snapshot and the downloaded copy. */
    public Data load() {
        Data bundled = null;
        try {
            bundled = parse(readAsset(), "bundled");
        } catch (Exception e) {
            Log.e(TAG, "the bundled catalog is unreadable", e);
        }
        Data downloaded = null;
        final File file = new File(dir, FILE);
        if (file.isFile()) {
            try {
                downloaded = parse(gunzip(readAll(file, MAX_GZ)), "downloaded");
            } catch (Exception e) {
                Log.w(TAG, "the downloaded catalog is unreadable; dropping it", e);
                if (!file.delete())
                    Log.w(TAG, "could not drop " + file);
            }
        }
        if (downloaded != null && (bundled == null
                || downloaded.built.compareTo(bundled.built) > 0))
            return downloaded;
        return bundled;
    }

    /**
     * Ask the depot for a newer catalog.
     *
     * @param held the catalog in use, never null
     * @return the new catalog, or null when there is nothing newer or it could not be
     *         had. A failure keeps what is held; nothing is ever emptied by a refresh.
     */
    public Data refresh(Data held, boolean force) {
        final File manifestFile = new File(dir, MANIFEST);
        if (!force && manifestFile.isFile()
                && System.currentTimeMillis() - manifestFile.lastModified() < MANIFEST_MAX_AGE_MS)
            return null;
        if (!dir.isDirectory() && !dir.mkdirs()) {
            Log.w(TAG, "could not make " + dir);
            return null;
        }
        try {
            final byte[] mbody = Http.get(BASE + MANIFEST, MAX_MANIFEST);
            final JSONObject m = new JSONObject(new String(mbody, Charset.forName("UTF-8")));
            write(manifestFile, mbody);
            final JSONObject entry = m.optJSONObject("files") == null ? null
                    : m.optJSONObject("files").optJSONObject(FILE);
            final String sha = entry == null ? "" : entry.optString("sha256", "");
            final String built = m.optString("built", "");
            if (!sha.matches("[0-9a-fA-F]{64}")) {
                Log.w(TAG, "the depot manifest names no catalog");
                return null;
            }
            if (built.compareTo(held.built) <= 0) {
                Log.d(TAG, "the depot's catalog (" + built + ") is not newer than "
                        + held.origin + " " + held.built);
                return null;
            }
            // The digest in the address: the CDN keeps a file for hours, and without
            // it a new manifest would arrive with yesterday's catalog behind it.
            final byte[] gz = Http.get(BASE + FILE + "?sha256=" + sha.toLowerCase(java.util.Locale.ROOT),
                    MAX_GZ);
            if (!sha.equalsIgnoreCase(sha256(gz))) {
                Log.w(TAG, "the depot's catalog does not match its manifest; keeping "
                        + held.origin);
                return null;
            }
            final Data fresh = parse(gunzip(gz), "downloaded");
            if (fresh.incidents.size() < held.incidents.size() * MIN_SHARE_OF_HELD) {
                Log.w(TAG, "the depot's catalog has " + fresh.incidents.size()
                        + " incidents against " + held.incidents.size() + " held; keeping "
                        + held.origin);
                return null;
            }
            write(new File(dir, FILE), gz);
            Log.d(TAG, "catalog refreshed: " + fresh.incidents.size() + " incidents, built "
                    + fresh.built);
            return fresh;
        } catch (Exception e) {
            // Touch the manifest time anyway, so an unreachable depot is asked once a
            // day and not at every start.
            if (manifestFile.isFile() && !manifestFile.setLastModified(System.currentTimeMillis()))
                Log.d(TAG, "could not touch the manifest");
            Log.d(TAG, "catalog refresh failed, keeping " + held.origin + ": " + e);
            return null;
        }
    }

    // ---- parsing ---------------------------------------------------------------

    static Data parse(byte[] body, String origin) throws Exception {
        final JSONObject root = new JSONObject(new String(body, Charset.forName("UTF-8")));
        if (root.optInt("version", 0) != 1)
            throw new IOException("catalog version " + root.optInt("version", 0));
        final JSONArray rows = root.getJSONArray("incidents");
        final List<Incident> out = new ArrayList<>(rows.length());
        final Set<String> ids = new HashSet<>();
        int skipped = 0;
        for (int k = 0; k < rows.length(); k++) {
            final Incident i = Incident.parse(rows.optJSONObject(k));
            if (i == null || !ids.add(i.id)) {
                skipped++;
                continue;
            }
            out.add(i);
        }
        if (out.isEmpty())
            throw new IOException("catalog holds no incidents");
        if (skipped > 0)
            Log.w(TAG, origin + " catalog: skipped " + skipped + " rows");
        return new Data(out, root.optString("built", ""), root.optString("sourceEdited", ""),
                origin);
    }

    private byte[] readAsset() throws IOException {
        InputStream in = null;
        try {
            in = pluginContext.getAssets().open(ASSET);
            return readBounded(in, MAX_INFLATED);
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (IOException ignored) {
                    // Already have the bytes or the failure.
                }
            }
        }
    }

    // ---- files -------------------------------------------------------------------

    static String sha256(byte[] body) {
        try {
            final byte[] d = java.security.MessageDigest.getInstance("SHA-256").digest(body);
            final StringBuilder b = new StringBuilder(64);
            for (byte x : d)
                b.append(Character.forDigit((x >> 4) & 0xF, 16))
                        .append(Character.forDigit(x & 0xF, 16));
            return b.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            return "";
        }
    }

    static byte[] gunzip(byte[] gz) throws IOException {
        InputStream in = null;
        try {
            in = new GZIPInputStream(new ByteArrayInputStream(gz));
            return readBounded(in, MAX_INFLATED);
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

    private static byte[] readBounded(InputStream in, int max) throws IOException {
        final ByteArrayOutputStream out = new ByteArrayOutputStream(256 * 1024);
        final byte[] buf = new byte[32 * 1024];
        int n;
        while ((n = in.read(buf)) > 0) {
            out.write(buf, 0, n);
            if (out.size() > max)
                throw new IOException("larger than " + max + " bytes");
        }
        return out.toByteArray();
    }

    private static byte[] readAll(File file, int max) throws IOException {
        if (file.length() > max)
            throw new IOException(file.getName() + " is too large to be ours");
        FileInputStream in = null;
        try {
            in = new FileInputStream(file);
            return readBounded(in, max);
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (IOException ignored) {
                    // Already have the bytes or the failure.
                }
            }
        }
    }

    /** Written to a temp file and renamed, so a killed process never leaves half a file. */
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
}
