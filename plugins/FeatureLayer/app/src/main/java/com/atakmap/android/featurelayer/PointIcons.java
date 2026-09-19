package com.atakmap.android.featurelayer;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;

import com.atakmap.coremap.log.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * NWCG secondary symbology for points: the Repair Status is a colored disc behind the
 * symbol (a ring for In Use). ATAK draws one icon per point, so the disc and the symbol
 * are composited into one PNG, once per pairing, and kept beside the icons.
 */
final class PointIcons {
    private static final String TAG = "FeatureLayer";

    private final File dir;
    private final Map<String, String> uris = new HashMap<>();

    /**
     * Whether this plugin load has already refreshed the composites. They are redrawn
     * once per load on a background thread, never once per layer on the caller's.
     *
     * <p>The constructor used to redraw all of them inline, and it runs from
     * {@code LoadedLayer}'s constructor, which runs from {@code LayerManager.add} on the
     * main thread. With 127 composites on disk that blocked past Android's 10 s input
     * timeout and raised an ANR: two of them on 2026-09-17, one from adding a layer and
     * one from restoring at start, both reading to the operator as "it crashed the app".
     * A timestamp check does not help, because the plugin rewrites its own symbols every
     * time it unpacks them at start, so every composite always looks stale.
     */
    private static boolean refreshStarted;

    PointIcons(File dir) {
        this.dir = dir;
        refreshComposites(dir);
    }

    /**
     * Redraws every composite from the symbol it was made from, once per plugin load, off
     * the caller's thread. The cached store points at these by name and ATAK remembers a
     * failed icon load until it restarts, so a composite is never deleted -- it is
     * redrawn in place from the (possibly corrected) symbol.
     *
     * <p>The pass is advisory: {@link #withStatus} composes anything missing on demand,
     * and each file is written whole and renamed into place, so a point drawn while this
     * is running gets the previous composite rather than a torn one.
     */
    private static void refreshComposites(final File dir) {
        synchronized (PointIcons.class) {
            if (refreshStarted)
                return;
            refreshStarted = true;
        }
        final Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                final File[] old = dir.listFiles();
                if (old == null)
                    return;
                int redrawn = 0;
                final long t0 = System.currentTimeMillis();
                for (File f : old) {
                    final String n = f.getName();
                    if (!n.startsWith("status_") || !n.endsWith(".png"))
                        continue;
                    try {
                        // status_<argb>_<disc|ring>_<icon file>
                        final String[] parts = n.split("_", 4);
                        if (parts.length < 4)
                            continue;
                        final int color = (int) Long.parseLong(parts[1], 16);
                        final boolean hollow = "ring".equals(parts[2]);
                        final File icon = new File(dir, parts[3]);
                        if (icon.isFile() && compose(icon, color, hollow, f))
                            redrawn++;
                    } catch (Exception e) {
                        Log.w(TAG, "status icon " + n + " not redrawn", e);
                    }
                }
                Log.d(TAG, "status icons: " + redrawn + " redrawn in "
                        + (System.currentTimeMillis() - t0) + " ms, off the main thread");
            }
        }, "featurelayer-status-icons");
        t.setPriority(Thread.MIN_PRIORITY);
        t.start();
    }

    /** The icon on its status disc, or the icon itself when nothing can be composited. */
    synchronized String withStatus(String iconUri, int color, boolean hollow) {
        if (iconUri == null || !iconUri.startsWith("file://"))
            return iconUri;
        final File icon = new File(iconUri.substring("file://".length()));
        final String key = String.format(Locale.US, "status_%08x_%s_%s", color, hollow ? "ring" : "disc", icon.getName());
        String uri = uris.get(key);
        if (uri != null)
            return uri;
        final File f = new File(dir, key);
        try {
            if (!f.isFile() && !compose(icon, color, hollow, f))
                return iconUri;
            uri = "file://" + f.getAbsolutePath();
        } catch (Exception e) {
            Log.w(TAG, "status icon " + key + " failed", e);
            return iconUri;
        }
        uris.put(key, uri);
        return uri;
    }

    /** Draws the disc (or ring) with the symbol centered on it; false when the symbol will not decode. */
    private static boolean compose(File icon, int color, boolean hollow, File out) throws Exception {
        final Bitmap src = BitmapFactory.decodeFile(icon.getAbsolutePath());
        if (src == null)
            return false;
        final int size = Math.max(src.getWidth(), src.getHeight()) + 26;
        final Bitmap bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        final Canvas c = new Canvas(bmp);
        final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(color);
        if (hollow) {
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(7f);
            c.drawCircle(size / 2f, size / 2f, size / 2f - 4.5f, p);
        } else {
            p.setStyle(Paint.Style.FILL);
            c.drawCircle(size / 2f, size / 2f, size / 2f - 1f, p);
        }
        c.drawBitmap(src, (size - src.getWidth()) / 2f, (size - src.getHeight()) / 2f, null);
        // Written whole, then moved into place: a reader never sees a half file.
        final File tmp = new File(out.getPath() + ".tmp");
        final FileOutputStream o = new FileOutputStream(tmp);
        try {
            bmp.compress(Bitmap.CompressFormat.PNG, 100, o);
        } finally {
            o.close();
            bmp.recycle();
            src.recycle();
        }
        //noinspection ResultOfMethodCallIgnored
        tmp.renameTo(out);
        return true;
    }
}
