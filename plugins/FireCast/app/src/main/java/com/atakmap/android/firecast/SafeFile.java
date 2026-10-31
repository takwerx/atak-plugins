package com.atakmap.android.firecast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Writes a whole file so a reader sees the old one or the new one, never half of one.
 *
 * <p>A temp file of its own per write, synced, then renamed over the target. A shared
 * temp name ("detections.json.tmp") is not enough: on a reinstall ATAK unloads the old
 * plugin while it loads the new one, both save, one truncates the other's temp file
 * and the rename puts an empty file in place, and the next start has nothing to show
 * with no signal ("could not read the saved detections: End of input at character 0",
 * dev 1, 2026-10-30).
 */
public final class SafeFile {

    private SafeFile() {
    }

    public static void write(File target, String text) throws IOException {
        final File tmp = new File(target.getPath() + "." + System.nanoTime() + ".tmp");
        try {
            final FileOutputStream out = new FileOutputStream(tmp);
            try {
                out.write(text.getBytes(StandardCharsets.UTF_8));
                out.flush();
                out.getFD().sync();
            } finally {
                out.close();
            }
            if (!tmp.renameTo(target)) {
                // Some file systems will not rename over an existing file.
                if (!target.delete() || !tmp.renameTo(target))
                    throw new IOException("could not move " + tmp.getName() + " into place");
            }
        } finally {
            if (tmp.exists() && !tmp.delete())
                tmp.deleteOnExit();
        }
    }

    /** Leftover temp files of earlier writes, from a write killed before its rename. */
    public static void sweep(File dir, String targetName) {
        final File[] left = dir.listFiles();
        if (left == null)
            return;
        for (File f : left)
            if (f.getName().startsWith(targetName + ".") && f.getName().endsWith(".tmp"))
                f.delete();
    }
}
