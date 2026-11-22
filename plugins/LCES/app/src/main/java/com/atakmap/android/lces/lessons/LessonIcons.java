package com.atakmap.android.lces.lessons;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;

import com.atakmap.coremap.filesystem.FileSystemUtils;
import com.atakmap.coremap.log.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

/**
 * One map icon per kind of incident: a disc in the kind's color inside a dark ring,
 * composed once and kept on disk.
 *
 * <p>A feature's point style is one {@code IconPointStyle}; a second point style in a
 * composite is ignored, and {@code BasicPointStyle} draws at an unpredictable size on
 * 5.8 (Traffic, Fire Cast), so the ring is in the picture. The version is in the
 * file name because the store keeps a feature's style with it: bump it with any change
 * to how these look. Worker thread only: PNG encodes and file writes.
 */
final class LessonIcons {

    private static final String TAG = "LCES-Lessons";
    private static final int VERSION = 1;
    /** Bitmap pixels; drawn at {@link #SIZE_DP} on the map. */
    private static final int PX = 48;
    static final float SIZE_DP = 20f;

    private LessonIcons() {
    }

    /** The file:// URI of a kind's icon, composing it when it is not on disk yet. */
    static String uri(Incident.Type type) {
        final File dir = new File(FileSystemUtils.getItem("tools/lces"), "icons");
        if (!dir.isDirectory() && !dir.mkdirs())
            Log.w(TAG, "could not make " + dir);
        nomedia(dir);
        final File out = new File(dir, type.name().toLowerCase(java.util.Locale.US)
                + "_v" + VERSION + ".png");
        if (!out.isFile())
            compose(type.color, out);
        return "file://" + out.getAbsolutePath();
    }

    private static void compose(int color, File out) {
        final Bitmap bmp = Bitmap.createBitmap(PX, PX, Bitmap.Config.ARGB_8888);
        final Canvas c = new Canvas(bmp);
        final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        final float r = PX / 2f;
        p.setColor(0xFF101010);
        c.drawCircle(r, r, r - 1, p);
        p.setColor(0xFFFFFFFF);
        c.drawCircle(r, r, r - 6, p);
        p.setColor(color);
        c.drawCircle(r, r, r - 9, p);
        final File tmp = new File(out.getPath() + ".part");
        FileOutputStream fos = null;
        try {
            fos = new FileOutputStream(tmp);
            bmp.compress(Bitmap.CompressFormat.PNG, 100, fos);
            fos.close();
            fos = null;
            if (!tmp.renameTo(out))
                Log.w(TAG, "could not save " + out.getName());
        } catch (IOException e) {
            Log.w(TAG, "could not write " + out.getName(), e);
        } finally {
            if (fos != null) {
                try {
                    fos.close();
                } catch (IOException ignored) {
                    // Nothing to keep.
                }
            }
            bmp.recycle();
        }
    }

    private static void nomedia(File dir) {
        final File f = new File(dir, ".nomedia");
        if (f.exists())
            return;
        try {
            if (!f.createNewFile())
                Log.d(TAG, "no .nomedia in " + dir);
        } catch (IOException e) {
            Log.d(TAG, "no .nomedia in " + dir + ": " + e);
        }
    }
}
