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
 * The HRRR 3 km wind over the run's box for every hour of the run, from NOMADS's
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
    /** The HRRR run the field came from and its first forecast hour, so a saved run can find the files again. */
    public final long run;
    public final int firstHour;

    private WindField(WindGrid[] hours, long[] validAt, long run, int firstHour) {
        this.hours = hours;
        this.validAt = validAt;
        this.run = run;
        this.firstHour = firstHour;
    }

    /** Which run and forecast hours this field holds, for the page. */
    public String describe() {
        return "HRRR 3 km, " + hours.length + " hourly fields";
    }

    /** How many hourly fields it holds. */
    public int size() {
        return hours.length;
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
     * The field over a box for the hour containing {@code when} and the {@code count - 1}
     * hours after it. Worker thread. Throws when NOMADS does not answer and nothing is on
     * disk. A later hour that is missing ends the field there; the first must be had.
     */
    public static WindField fetch(double south, double west, double north, double east, long when, int count) throws Exception {
        final NomadsWind.Model m = NomadsWind.Model.HRRR;
        long run = m.latestRun(when);
        final File dir = dir();
        count = Math.max(1, Math.min(6, count));
        for (int attempt = 0; attempt < 2; attempt++) {
            final long hour0 = (when / 3_600_000L) * 3_600_000L;
            final int fh0 = (int) ((hour0 - run) / 3_600_000L);
            if (fh0 < 0 || fh0 + count - 1 > m.forecastHours(run)) {
                run = m.previousRun(run);
                continue;
            }
            final java.util.List<WindGrid> grids = new java.util.ArrayList<>();
            final java.util.List<Long> valid = new java.util.ArrayList<>();
            try {
                for (int k = 0; k < count; k++) {
                    final int fh = fh0 + k;
                    final File f = file(dir, run, fh, south, west);
                    byte[] body = f.isFile() ? readAll(f) : null;
                    if (body == null || !NomadsWind.looksLikeGrib(body)) {
                        try {
                            body = get(NomadsWind.url(m, run, fh, west - 0.05, south - 0.05, east + 0.05, north + 0.05));
                        } catch (Exception e) {
                            if (k == 0)
                                throw e;
                            break;
                        }
                        if (!NomadsWind.looksLikeGrib(body)) {
                            if (k == 0)
                                throw new IllegalStateException("NOMADS has no HRRR file for that hour yet");
                            break;
                        }
                        write(f, body);
                    }
                    grids.add(NomadsWind.read(body, 24));
                    valid.add(run + fh * 3_600_000L);
                }
                return make(grids, valid, run, fh0);
            } catch (Exception e) {
                Log.w(TAG, "HRRR run " + (run / 3_600_000L) + " not usable: " + e);
                run = m.previousRun(run);
            }
        }
        throw new IllegalStateException("no HRRR wind for this hour");
    }

    /** A field fetched before, from disk only: a restored run must not wait on the network. Null when the files are gone. */
    public static WindField fromDisk(double south, double west, double north, double east, long run, int fh0, int count) {
        final java.util.List<WindGrid> grids = new java.util.ArrayList<>();
        final java.util.List<Long> valid = new java.util.ArrayList<>();
        try {
            for (int k = 0; k < Math.max(1, Math.min(6, count)); k++) {
                final File f = file(dir(), run, fh0 + k, south, west);
                if (!f.isFile())
                    break;
                final byte[] body = readAll(f);
                if (!NomadsWind.looksLikeGrib(body))
                    break;
                grids.add(NomadsWind.read(body, 24));
                valid.add(run + (fh0 + k) * 3_600_000L);
            }
        } catch (Exception e) {
            Log.w(TAG, "HRRR field from disk failed: " + e);
        }
        return grids.isEmpty() ? null : make(grids, valid, run, fh0);
    }

    private static WindField make(java.util.List<WindGrid> grids, java.util.List<Long> valid, long run, int fh0) {
        final long[] v = new long[valid.size()];
        for (int i = 0; i < v.length; i++)
            v[i] = valid.get(i);
        return new WindField(grids.toArray(new WindGrid[0]), v, run, fh0);
    }

    private static File file(File dir, long run, int fh, double south, double west) {
        return new File(dir, String.format(Locale.US, "hrrr_%d_%02d_%.2f_%.2f.grib2", run / 3_600_000L, fh, south, west));
    }

    private static File dir() {
        final File dir = FileSystemUtils.getItem("tools/firecast/wind");
        if (!dir.isDirectory() && !dir.mkdirs())
            Log.w(TAG, "could not create " + dir);
        return dir;
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
