package com.atakmap.android.firecast.model;

import com.atakmap.android.firecast.model.wind.NomadsWind;
import com.atakmap.android.firecast.model.wind.WindGrid;
import com.atakmap.coremap.filesystem.FileSystemUtils;
import com.atakmap.coremap.log.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Locale;

/**
 * The HRRR 3 km wind over the run's box for the run hour and the next, from NOMADS's
 * grib filter, decoded on the phone with the reader copied forward from Atmosphere
 * (387 bytes for an 8 km box, measured 2026-10-12). Sampled per cell and per time as
 * the fire reaches each cell, so a run carries the wind's turn with the terrain and
 * its change over the hour. Kept on disk under the run hour so a rerun needs no
 * network.
 */
public final class WindField {

    private static final String TAG = "FireCast";
    private final WindGrid[] hours;
    private final long[] validAt;

    private WindField(WindGrid[] hours, long[] validAt) {
        this.hours = hours;
        this.validAt = validAt;
    }

    /** Which run and forecast hours this field holds, for the page. */
    public String describe() {
        return "HRRR 3 km, " + hours.length + " hourly fields";
    }

    /**
     * Wind at a point and a moment: {mph, from-degrees}, or null outside the grid.
     * Between the two hours it is a straight blend; past them the last one holds.
     */
    public double[] at(double lat, double lon, long whenMillis) {
        final float[] uv = new float[2];
        int i = 0;
        double w = 0;
        for (int k = 0; k < validAt.length - 1; k++)
            if (whenMillis >= validAt[k]) {
                i = k;
                w = Math.max(0, Math.min(1, (whenMillis - validAt[k]) / (double) (validAt[k + 1] - validAt[k])));
            }
        if (hours.length == 1) {
            i = 0;
            w = 0;
        }
        hours[i].sample(lat, lon, uv);
        if (Float.isNaN(uv[0]))
            return null;
        double u = uv[0], v = uv[1];
        if (w > 0 && i + 1 < hours.length) {
            final float[] uv2 = new float[2];
            hours[i + 1].sample(lat, lon, uv2);
            if (!Float.isNaN(uv2[0])) {
                u = u * (1 - w) + uv2[0] * w;
                v = v * (1 - w) + uv2[1] * w;
            }
        }
        final double mps = Math.sqrt(u * u + v * v);
        // u, v are where the wind goes; the model wants where it comes from.
        final double from = (Math.toDegrees(Math.atan2(u, v)) + 180d + 360d) % 360d;
        return new double[] { mps * 2.23694d, from };
    }

    /**
     * The field over a box for the hour containing {@code when} and the hour after.
     * Worker thread. Throws when NOMADS does not answer and nothing is on disk.
     */
    public static WindField fetch(double south, double west, double north, double east, long when) throws Exception {
        final NomadsWind.Model m = NomadsWind.Model.HRRR;
        long run = m.latestRun(when);
        final File dir = FileSystemUtils.getItem("tools/firecast/wind");
        if (!dir.isDirectory() && !dir.mkdirs())
            Log.w(TAG, "could not create " + dir);
        final WindGrid[] grids = new WindGrid[2];
        final long[] valid = new long[2];
        for (int attempt = 0; attempt < 2 && grids[0] == null; attempt++) {
            final long hour0 = (when / 3_600_000L) * 3_600_000L;
            final int fh0 = (int) ((hour0 - run) / 3_600_000L);
            if (fh0 < 0 || fh0 + 1 > m.forecastHours(run)) {
                run = m.previousRun(run);
                continue;
            }
            try {
                for (int k = 0; k < 2; k++) {
                    final int fh = fh0 + k;
                    final File f = new File(dir, String.format(Locale.US, "hrrr_%d_%02d_%.2f_%.2f.grib2", run / 3_600_000L, fh, south, west));
                    byte[] body = f.isFile() ? readAll(f) : null;
                    if (body == null || !NomadsWind.looksLikeGrib(body)) {
                        body = get(NomadsWind.url(m, run, fh, west - 0.05, south - 0.05, east + 0.05, north + 0.05));
                        if (!NomadsWind.looksLikeGrib(body))
                            throw new IllegalStateException("NOMADS has no HRRR file for that hour yet");
                        write(f, body);
                    }
                    grids[k] = NomadsWind.read(body, 24);
                    valid[k] = run + fh * 3_600_000L;
                }
            } catch (Exception e) {
                Log.w(TAG, "HRRR run " + (run / 3_600_000L) + " not usable: " + e);
                grids[0] = null;
                run = m.previousRun(run);
            }
        }
        if (grids[0] == null)
            throw new IllegalStateException("no HRRR wind for this hour");
        return grids[1] == null ? new WindField(new WindGrid[] { grids[0] }, new long[] { valid[0] })
                : new WindField(grids, valid);
    }

    private static byte[] get(String url) throws Exception {
        final HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(20000);
        c.setReadTimeout(60000);
        c.setRequestProperty("User-Agent", "FireCast (ATAK plugin)");
        try {
            if (c.getResponseCode() != 200)
                throw new IllegalStateException("NOMADS answered " + c.getResponseCode());
            if (!"https".equals(c.getURL().getProtocol()))
                throw new IllegalStateException("not https");
            final InputStream in = c.getInputStream();
            final java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            try {
                final byte[] buf = new byte[16384];
                int r;
                while ((r = in.read(buf)) > 0) {
                    out.write(buf, 0, r);
                    if (out.size() > 2 * 1024 * 1024)
                        throw new IllegalStateException("HRRR answer over 2 MB");
                }
            } finally {
                in.close();
            }
            return out.toByteArray();
        } finally {
            c.disconnect();
        }
    }

    private static byte[] readAll(File f) throws Exception {
        final byte[] d = new byte[(int) f.length()];
        final InputStream in = new FileInputStream(f);
        try {
            int off = 0, r;
            while (off < d.length && (r = in.read(d, off, d.length - off)) > 0)
                off += r;
        } finally {
            in.close();
        }
        return d;
    }

    private static void write(File f, byte[] body) throws Exception {
        final File tmp = new File(f.getPath() + ".tmp");
        final FileOutputStream out = new FileOutputStream(tmp);
        try {
            out.write(body);
        } finally {
            out.close();
        }
        if (!tmp.renameTo(f)) {
            f.delete();
            tmp.renameTo(f);
        }
    }
}
