package com.atakmap.android.sitrep;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Writes a whole file so a reader sees the old one or the new one, never half of one.
 *
 * <p>A temp file of its own per write, synced, then renamed over the target. Copied
 * forward from FireCast, where a shared temp name let a reinstall (old and new plugin
 * both saving) put an empty file in place.
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
