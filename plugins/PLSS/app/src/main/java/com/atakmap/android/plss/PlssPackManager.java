
package com.atakmap.android.plss;

import android.os.Handler;
import android.os.Looper;

import com.atakmap.coremap.log.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Fetches data packs listed by a remote manifest.
 *
 * The packs cannot ship inside the APK -- a tak.gov submission is a source zip
 * of a few hundred KB (PLAN-PLSS-v0.1.md section 4.1) -- so the plugin downloads
 * one once and is fully offline afterwards.
 *
 * Downloads stream straight to a temporary file, and the file only takes its
 * real name once its SHA-256 matches the manifest -- so a pack is never held in
 * memory and a truncated transfer cannot be mistaken for a good one.
 *
 * A partial download is kept, not discarded, and resumed with a Range request.
 * These packs are tens to hundreds of megabytes and the operators who need them
 * are on cellular or satellite links that drop; restarting a 124 MB pack from
 * zero on every stall is not something that would ever finish in the field.
 */
public class PlssPackManager {

    public static final String TAG = "PlssPackManager";

    /**
     * Development endpoint. The fielded build points at the release manifest on
     * the public data repo; pack URLs are relative to this file, so only this
     * one string changes. See the handoff for the hosting decision.
     */
    public static final String MANIFEST_URL =
            "http://100.67.193.92:8088/manifest.json";

    private static final int CONNECT_TIMEOUT = 20000;
    private static final int READ_TIMEOUT = 60000;
    private static final int BUFFER = 64 * 1024;

    /** Attempts per pack before giving up; each resumes where the last stopped. */
    private static final int ATTEMPTS = 6;

    /** Base backoff between attempts, multiplied by the attempt number. */
    private static final long BACKOFF_MS = 2000L;

    /** One downloadable pack, as described by the manifest. */
    public static final class Pack {
        public final String state;
        public final String name;
        public final String url;
        public final long bytes;
        public final String sha256;
        public final int townships;
        public final int sections;

        Pack(JSONObject o) {
            state = o.optString("state");
            name = o.optString("name", state);
            url = o.optString("url");
            bytes = o.optLong("bytes");
            sha256 = o.optString("sha256");
            townships = o.optInt("townships");
            sections = o.optInt("sections");
        }

        /** e.g. "California -- 27.4 MB, 141,935 sections" */
        public String describe() {
            return String.format("%s — %.1f MB, %,d sections",
                    name, bytes / 1048576.0, sections);
        }
    }

    public interface ManifestCallback {
        void onManifest(List<Pack> packs, String sourceDate);

        void onError(String message);
    }

    public interface DownloadCallback {
        /** 0-100, or -1 when the total size is not known */
        void onProgress(int percent, long bytesSoFar, long totalBytes);

        /** A transfer dropped and will be resumed from {@code bytesSoFar}. */
        void onRetry(Pack pack, int attempt, long bytesSoFar);

        void onComplete(Pack pack, File installed);

        void onError(String message);
    }

    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final File destDir;

    public PlssPackManager(File destDir) {
        this.destDir = destDir;
    }

    public void shutdown() {
        worker.shutdownNow();
    }

    // --------------------------------------------------------------- manifest

    public void fetchManifest(final ManifestCallback cb) {
        worker.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    final String body = get(MANIFEST_URL);
                    final JSONObject root = new JSONObject(body);
                    final JSONArray arr = root.getJSONArray("packs");

                    final List<Pack> packs = new ArrayList<>();
                    for (int i = 0; i < arr.length(); i++)
                        packs.add(new Pack(arr.getJSONObject(i)));

                    final String date = root.optString("sourceDate", "");
                    Log.d(TAG, "manifest: " + packs.size() + " packs, source "
                            + date);

                    main.post(new Runnable() {
                        @Override
                        public void run() {
                            cb.onManifest(packs, date);
                        }
                    });
                } catch (final Exception e) {
                    Log.e(TAG, "manifest fetch failed", e);
                    postError(e, cb);
                }
            }
        });
    }

    private void postError(final Exception e, final ManifestCallback cb) {
        main.post(new Runnable() {
            @Override
            public void run() {
                cb.onError(describe(e));
            }
        });
    }

    private static String get(String url) throws Exception {
        final HttpURLConnection conn = (HttpURLConnection) new URL(url)
                .openConnection();
        try {
            conn.setConnectTimeout(CONNECT_TIMEOUT);
            conn.setReadTimeout(READ_TIMEOUT);

            final int code = conn.getResponseCode();
            if (code != HttpURLConnection.HTTP_OK)
                throw new IllegalStateException("HTTP " + code);

            final StringBuilder sb = new StringBuilder();
            final byte[] buf = new byte[8192];
            try (InputStream in = conn.getInputStream()) {
                int n;
                while ((n = in.read(buf)) > 0)
                    sb.append(new String(buf, 0, n, "UTF-8"));
            }
            return sb.toString();
        } finally {
            conn.disconnect();
        }
    }

    // --------------------------------------------------------------- download

    public void download(final Pack pack, final DownloadCallback cb) {
        worker.execute(new Runnable() {
            @Override
            public void run() {
                final File tmp = new File(destDir,
                        "plss_" + pack.state + ".sqlite.part");
                Exception last = null;

                for (int attempt = 1; attempt <= ATTEMPTS; attempt++) {
                    try {
                        if (!destDir.exists() && !destDir.mkdirs())
                            throw new IllegalStateException(
                                    "cannot create " + destDir);

                        final String base = MANIFEST_URL.substring(0,
                                MANIFEST_URL.lastIndexOf('/') + 1);

                        final String digest = stream(base + pack.url, tmp,
                                pack, cb);

                        if (!pack.sha256.isEmpty()
                                && !pack.sha256.equalsIgnoreCase(digest)) {
                            // a mismatch means the bytes on disk are wrong, so
                            // resuming from them would never converge
                            if (tmp.exists() && !tmp.delete())
                                Log.w(TAG, "could not discard " + tmp);
                            throw new IllegalStateException(
                                    "checksum mismatch; expected "
                                            + pack.sha256 + " got " + digest);
                        }

                        install(pack, tmp, cb);
                        return;
                    } catch (Exception e) {
                        last = e;
                        Log.w(TAG, "attempt " + attempt + "/" + ATTEMPTS
                                + " failed for " + pack.state + ": "
                                + describe(e));

                        if (attempt == ATTEMPTS)
                            break;

                        final long have = tmp.exists() ? tmp.length() : 0;
                        postRetry(cb, pack, attempt, have);

                        try {
                            Thread.sleep(BACKOFF_MS * attempt);
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                            return;
                        }
                    }
                }

                final Exception fail = last;
                Log.e(TAG, "download failed", fail);
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        cb.onError(describe(fail));
                    }
                });
            }
        });
    }

    private void install(final Pack pack, File tmp, final DownloadCallback cb)
            throws Exception {

        final File dest = new File(destDir, "plss_" + pack.state + ".sqlite");
        if (dest.exists() && !dest.delete())
            throw new IllegalStateException("cannot replace " + dest);
        if (!tmp.renameTo(dest))
            throw new IllegalStateException("cannot install " + dest);

        Log.d(TAG, "installed " + dest + " (" + dest.length() + " bytes)");

        main.post(new Runnable() {
            @Override
            public void run() {
                cb.onComplete(pack, dest);
            }
        });
    }

    private void postRetry(final DownloadCallback cb, final Pack pack,
            final int attempt, final long have) {
        main.post(new Runnable() {
            @Override
            public void run() {
                cb.onRetry(pack, attempt, have);
            }
        });
    }

    /**
     * Streams to {@code dest}, resuming if it already holds part of the file,
     * and returns the SHA-256 of the whole thing.
     *
     * The digest has to cover bytes written by earlier attempts too, so an
     * existing prefix is read back through the digest before the transfer picks
     * up. That costs one sequential read of what is already on disk, which is
     * nothing next to re-downloading it.
     */
    private String stream(String url, File dest, Pack pack,
            final DownloadCallback cb) throws Exception {

        long have = dest.exists() ? dest.length() : 0;

        // a stale part larger than the pack cannot be a prefix of it
        if (pack.bytes > 0 && have > pack.bytes) {
            if (!dest.delete())
                throw new IllegalStateException("cannot discard " + dest);
            have = 0;
        }

        final HttpURLConnection conn = (HttpURLConnection) new URL(url)
                .openConnection();
        conn.setConnectTimeout(CONNECT_TIMEOUT);
        conn.setReadTimeout(READ_TIMEOUT);
        if (have > 0)
            conn.setRequestProperty("Range", "bytes=" + have + "-");

        try {
            final int code = conn.getResponseCode();

            boolean append = false;
            if (code == HttpURLConnection.HTTP_PARTIAL) {
                append = true;
                Log.d(TAG, "resuming " + pack.state + " at " + have + " bytes");
            } else if (code == HttpURLConnection.HTTP_OK) {
                // the server ignored the range, so start over
                have = 0;
            } else {
                throw new IllegalStateException("HTTP " + code);
            }

            final long total = pack.bytes > 0 ? pack.bytes
                    : have + Math.max(conn.getContentLength(), 0);

            final MessageDigest md = MessageDigest.getInstance("SHA-256");
            final byte[] buf = new byte[BUFFER];

            if (append)
                digestExisting(dest, md, buf);

            long soFar = append ? have : 0;
            int lastPercent = -1;

            try (InputStream in = conn.getInputStream();
                    OutputStream out = new FileOutputStream(dest, append)) {
                int n;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                    md.update(buf, 0, n);
                    soFar += n;

                    final int percent = total > 0
                            ? (int) (soFar * 100 / total) : -1;

                    // only cross the thread when the number actually changes
                    if (percent != lastPercent) {
                        lastPercent = percent;
                        final long at = soFar;
                        final long tot = total;
                        main.post(new Runnable() {
                            @Override
                            public void run() {
                                cb.onProgress(percent, at, tot);
                            }
                        });
                    }
                }
            }

            final StringBuilder hex = new StringBuilder();
            for (byte b : md.digest())
                hex.append(String.format("%02x", b));
            return hex.toString();
        } finally {
            conn.disconnect();
        }
    }

    /** Feeds bytes already on disk through the digest before resuming. */
    private static void digestExisting(File f, MessageDigest md, byte[] buf)
            throws Exception {
        try (InputStream in = new java.io.FileInputStream(f)) {
            int n;
            while ((n = in.read(buf)) > 0)
                md.update(buf, 0, n);
        }
    }

    private static String describe(Exception e) {
        final String m = e.getMessage();
        return m != null && !m.isEmpty() ? m : e.getClass().getSimpleName();
    }
}
