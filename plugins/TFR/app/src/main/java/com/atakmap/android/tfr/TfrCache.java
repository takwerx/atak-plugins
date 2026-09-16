package com.atakmap.android.tfr;

import com.atakmap.coremap.filesystem.FileSystemUtils;
import com.atakmap.coremap.log.Log;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Collection;

/**
 * The detail documents, on disk, so the map comes back without the network.
 *
 * <p>ATAK is killed and phones reboot; a TFR plugin that had to re-download the country before
 * it could draw anything would be useless in exactly the situation it is for. Shapes redraw
 * from here first and the refresh catches up afterwards.
 *
 * <p>Only successes are stored. A detail that 404s today can be there tomorrow, so a failure is
 * never written down as an answer — a negative cache that outlives the thing it is negative
 * about is a poisoned one.
 */
public final class TfrCache {

    private static final String TAG = "TfrCache";

    private final File root;
    private final File details;

    public TfrCache() {
        root = FileSystemUtils.getItem("tools/tfr");
        details = new File(root, "details");
    }

    /** The list body as last fetched, for a first draw before any network call returns. */
    public byte[] readList() {
        return read(new File(root, "list.json"));
    }

    public void writeList(byte[] body) {
        write(new File(root, "list.json"), body);
    }

    public boolean has(String notamId) {
        return file(notamId) != null && file(notamId).isFile();
    }

    public byte[] read(String notamId) {
        File f = file(notamId);
        return f == null ? null : read(f);
    }

    public void write(String notamId, byte[] body) {
        File f = file(notamId);
        if (f != null)
            write(f, body);
    }

    /**
     * Drop every cached detail whose NOTAM is no longer in the list.
     *
     * <p>This is the disk half of reconcile. A TFR that has been cancelled has to leave the map,
     * and it has to leave the cache too, or the next start draws it again from here.
     */
    public void prune(Collection<String> keepNotamIds) {
        File[] existing = details.listFiles();
        if (existing == null)
            return;
        java.util.HashSet<String> keep = new java.util.HashSet<>();
        for (String id : keepNotamIds)
            if (TfrFeed.validNotamId(id))
                keep.add(TfrFeed.fileName(id));
        for (File f : existing)
            if (f.isFile() && !keep.contains(f.getName()) && !f.delete())
                Log.w(TAG, "could not drop a stale cached detail");
    }

    /** Everything, for the "clear cached data" preference. */
    public void clear() {
        File[] existing = details.listFiles();
        if (existing != null)
            for (File f : existing)
                if (!f.delete())
                    Log.w(TAG, "could not clear a cached detail");
        File list = new File(root, "list.json");
        if (list.isFile() && !list.delete())
            Log.w(TAG, "could not clear the cached list");
    }

    public long bytesOnDisk() {
        long total = 0;
        File[] existing = details.listFiles();
        if (existing != null)
            for (File f : existing)
                total += f.length();
        return total;
    }

    /**
     * The cache file for a NOTAM, or null when the id is not one.
     *
     * <p>The name is built from a validated NOTAM number and then checked again against the
     * directory it must live in: the id arrives from a server, and a file path assembled from
     * something a server said is where path traversal gets in.
     */
    private File file(String notamId) {
        if (!TfrFeed.validNotamId(notamId))
            return null;
        File f = new File(details, TfrFeed.fileName(notamId));
        try {
            if (!f.getCanonicalPath().startsWith(details.getCanonicalPath() + File.separator))
                return null;
        } catch (IOException e) {
            return null;
        }
        return f;
    }

    private byte[] read(File f) {
        if (f == null || !f.isFile())
            return null;
        InputStream in = null;
        try {
            in = new FileInputStream(f);
            ByteArrayOutputStream out = new ByteArrayOutputStream((int) Math.max(1024, f.length()));
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0)
                out.write(buf, 0, n);
            return out.toByteArray();
        } catch (IOException e) {
            Log.w(TAG, "could not read a cached file");
            return null;
        } finally {
            close(in);
        }
    }

    /** Written to a temporary name and renamed, so a kill mid-write cannot leave half a file. */
    private void write(File f, byte[] body) {
        if (f == null || body == null)
            return;
        File parent = f.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
            Log.w(TAG, "could not make the cache directory");
            return;
        }
        File tmp = new File(f.getAbsolutePath() + ".tmp");
        OutputStream out = null;
        try {
            out = new FileOutputStream(tmp);
            out.write(body);
            out.flush();
        } catch (IOException e) {
            Log.w(TAG, "could not write a cache file");
            close(out);
            if (tmp.isFile() && !tmp.delete())
                Log.w(TAG, "could not clean up a partial cache file");
            return;
        }
        close(out);
        if (f.isFile() && !f.delete())
            Log.w(TAG, "could not replace a cache file");
        if (!tmp.renameTo(f))
            Log.w(TAG, "could not put a cache file in place");
    }

    private static void close(java.io.Closeable c) {
        if (c == null)
            return;
        try {
            c.close();
        } catch (IOException ignored) {
            // Nothing useful to do; the caller already has its result or its failure.
        }
    }
}
