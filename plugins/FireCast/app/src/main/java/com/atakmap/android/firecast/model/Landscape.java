package com.atakmap.android.firecast.model;

import com.atakmap.coremap.filesystem.FileSystemUtils;
import com.atakmap.coremap.log.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * The ground around a point as LANDFIRE's grids: fuel model, slope, aspect, vegetation
 * type and the four canopy grids, 30 m cells, from its public coverage service, kept on
 * disk so a run can be redone with no network.
 *
 * <p>The service hands back a plain GeoTIFF: 16-bit, uncompressed, tiled, in plain
 * latitude and longitude with the georeferencing in one affine tag (measured
 * 2026-10-11). That is read here directly; no GDAL, no library. Anything else the
 * service might send one day (compression, floats, a projection) is refused with a
 * message, never guessed at.
 */
public final class Landscape {

    private static final String TAG = "FireCast";
    private static final String HOST = "https://edcintl.cr.usgs.gov/geoserver/landfire/";
    public static final String FUEL = "LF2024_FBFM40_CONUS", SLOPE = "LF2020_SlpD_CONUS", ASPECT = "LF2020_Asp_CONUS";
    /** Existing vegetation type: the one grid that tells houses (7297, 7298) from roads (7299) and parks (7296) inside the urban fuel class. */
    public static final String EVT = "LF2024_EVT_CONUS";
    /**
     * The canopy, for crown fire: cover in percent, height and base height in meters
     * times ten, bulk density in kg/m3 times a hundred (read off forest pixels in Oregon,
     * Colorado and the Sierra, 2026-10-17). Zero where there are no trees.
     */
    public static final String CC = "LF2024_CC_CONUS", CH = "LF2024_CH_CONUS", CBH = "LF2024_CBH_CONUS",
            CBD = "LF2024_CBD_CONUS";

    /** One grid: values by row and column, and where each cell is on the ground. */
    public static final class Raster {
        public final int width, height;
        public final short[] data;
        /** Longitude and latitude of the upper-left corner, and the size of a cell in degrees. */
        public final double lon0, lat0, dLon, dLat;
        public final int nodata;

        Raster(int width, int height, short[] data, double lon0, double lat0, double dLon, double dLat, int nodata) {
            this.width = width;
            this.height = height;
            this.data = data;
            this.lon0 = lon0;
            this.lat0 = lat0;
            this.dLon = dLon;
            this.dLat = dLat;
            this.nodata = nodata;
        }

        /** The value of the cell under a point, or {@link #nodata} outside the grid. */
        public int at(double lat, double lon) {
            final int c = (int) Math.floor((lon - lon0) / dLon);
            final int r = (int) Math.floor((lat - lat0) / dLat);
            if (c < 0 || r < 0 || c >= width || r >= height)
                return nodata;
            return data[r * width + c];
        }
    }

    public final Raster fuel, slope, aspect, evt;
    /** The canopy grids, all four or none: null when they could not be had, and the run is surface fire only. */
    public final Raster cc, ch, cbh, cbd;
    /** The square asked for, degrees: south, west, north, east. */
    public final double south, west, north, east;

    private Landscape(Raster fuel, Raster slope, Raster aspect, Raster evt, Raster[] canopy,
            double s, double w, double n, double e) {
        this.fuel = fuel;
        this.slope = slope;
        this.aspect = aspect;
        this.evt = evt;
        cc = canopy == null ? null : canopy[0];
        ch = canopy == null ? null : canopy[1];
        cbh = canopy == null ? null : canopy[2];
        cbd = canopy == null ? null : canopy[3];
        south = s;
        west = w;
        north = n;
        east = e;
    }

    /** The on-disk name of the square around a point, so two runs there share one fetch. */
    static String key(double lat, double lon, double halfKm) {
        return String.format(Locale.US, "%.3f_%.3f_%.0fkm", lat, lon, halfKm).replace('-', 'm');
    }

    /**
     * The square of {@code halfKm} each way around a point, from disk when it is
     * there, from the service when it is not. Worker thread. Throws when neither
     * has it.
     */
    public static Landscape around(double lat, double lon, double halfKm) throws Exception {
        final double dLat = halfKm * 1000d / 111320d;
        final double dLon = halfKm * 1000d / (111320d * Math.max(0.02d, Math.cos(Math.toRadians(lat))));
        final double s = lat - dLat, n = lat + dLat, w = lon - dLon, e = lon + dLon;
        final File dir = new File(FileSystemUtils.getItem("tools/firecast/landscape"), key(lat, lon, halfKm));
        if (!dir.isDirectory() && !dir.mkdirs())
            Log.w(TAG, "could not create " + dir);
        final Raster fuel = raster(dir, "fuel.tif", "conus_2024", FUEL, s, w, n, e);
        final Raster slope = raster(dir, "slope.tif", "conus_topo", SLOPE, s, w, n, e);
        final Raster aspect = raster(dir, "aspect.tif", "conus_topo", ASPECT, s, w, n, e);
        final Raster evt = raster(dir, "evt.tif", "conus_2024", EVT, s, w, n, e);
        // The canopy is wanted, not needed: without it the run is surface fire and says so.
        Raster[] canopy = null;
        try {
            canopy = new Raster[] { raster(dir, "cc.tif", "conus_2024", CC, s, w, n, e),
                    raster(dir, "ch.tif", "conus_2024", CH, s, w, n, e),
                    raster(dir, "cbh.tif", "conus_2024", CBH, s, w, n, e),
                    raster(dir, "cbd.tif", "conus_2024", CBD, s, w, n, e) };
        } catch (Exception ex) {
            Log.w(TAG, "canopy grids unavailable, surface fire only: " + ex);
        }
        return new Landscape(fuel, slope, aspect, evt, canopy, s, w, n, e);
    }

    /** Whether the square is already on disk, so a run can be promised with no network. */
    public static boolean cached(double lat, double lon, double halfKm) {
        final File dir = new File(FileSystemUtils.getItem("tools/firecast/landscape"), key(lat, lon, halfKm));
        return new File(dir, "fuel.tif").isFile() && new File(dir, "slope.tif").isFile()
                && new File(dir, "aspect.tif").isFile() && new File(dir, "evt.tif").isFile();
    }

    private static Raster raster(File dir, String name, String workspace, String coverage,
            double s, double w, double n, double e) throws Exception {
        final File f = new File(dir, name);
        if (!f.isFile() || f.length() < 1000) {
            final String url = HOST + workspace + "/wcs?service=WCS&version=2.0.1&request=GetCoverage"
                    + "&coverageId=" + coverage
                    + "&subset=" + String.format(Locale.US, "Lat(%.5f,%.5f)", s, n)
                    + "&subset=" + String.format(Locale.US, "Long(%.5f,%.5f)", w, e)
                    + "&subsettingCrs=http://www.opengis.net/def/crs/EPSG/0/4326&format=image/tiff";
            download(url, f);
        }
        return parse(f);
    }

    /** A GET into a temp file then into place, 4 MB at most; the service is a constant. */
    private static void download(String url, File into) throws Exception {
        final HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(20000);
        c.setReadTimeout(60000);
        c.setRequestProperty("User-Agent", "FireCast (ATAK plugin)");
        final File tmp = new File(into.getPath() + ".tmp");
        try {
            if (c.getResponseCode() != 200)
                throw new IllegalStateException("LANDFIRE answered " + c.getResponseCode());
            if (!"https".equals(c.getURL().getProtocol()))
                throw new IllegalStateException("not https");
            final InputStream in = c.getInputStream();
            final FileOutputStream out = new FileOutputStream(tmp);
            try {
                final byte[] buf = new byte[65536];
                long total = 0;
                int r;
                while ((r = in.read(buf)) > 0) {
                    total += r;
                    if (total > 8L * 1024 * 1024)
                        throw new IllegalStateException("LANDFIRE grid over 8 MB");
                    out.write(buf, 0, r);
                }
            } finally {
                out.close();
                in.close();
            }
            if (!tmp.renameTo(into)) {
                into.delete();
                if (!tmp.renameTo(into))
                    throw new IllegalStateException("could not keep the grid");
            }
        } finally {
            tmp.delete();
            c.disconnect();
        }
    }

    // ---- the GeoTIFF, as the service writes it --------------------------------------

    static Raster parse(File f) throws Exception {
        final byte[] d = new byte[(int) f.length()];
        final InputStream in = new FileInputStream(f);
        try {
            int off = 0, r;
            while (off < d.length && (r = in.read(d, off, d.length - off)) > 0)
                off += r;
        } finally {
            in.close();
        }
        final ByteBuffer b = ByteBuffer.wrap(d);
        if (d.length < 8)
            throw new IllegalStateException("not a TIFF");
        if (d[0] == 'I' && d[1] == 'I')
            b.order(ByteOrder.LITTLE_ENDIAN);
        else if (d[0] == 'M' && d[1] == 'M')
            b.order(ByteOrder.BIG_ENDIAN);
        else
            throw new IllegalStateException("not a TIFF");
        if (b.getShort(2) != 42)
            throw new IllegalStateException("not a classic TIFF");
        final int ifd = b.getInt(4);
        final int n = b.getShort(ifd) & 0xFFFF;
        final Map<Integer, long[]> tags = new HashMap<>();
        final Map<Integer, double[]> dtags = new HashMap<>();
        String nodataText = null;
        for (int i = 0; i < n; i++) {
            final int e = ifd + 2 + i * 12;
            final int tag = b.getShort(e) & 0xFFFF, type = b.getShort(e + 2) & 0xFFFF;
            final int count = b.getInt(e + 4);
            final int size = type == 3 ? 2 : type == 4 ? 4 : type == 12 ? 8 : type == 2 ? 1 : type == 1 ? 1 : 4;
            final int at = size * count <= 4 ? e + 8 : b.getInt(e + 8);
            if (type == 2) {
                final StringBuilder sb = new StringBuilder();
                for (int k = 0; k < count && d[at + k] != 0; k++)
                    sb.append((char) d[at + k]);
                if (tag == 42113)
                    nodataText = sb.toString().trim();
            } else if (type == 12) {
                final double[] v = new double[count];
                for (int k = 0; k < count; k++)
                    v[k] = b.getDouble(at + 8 * k);
                dtags.put(tag, v);
            } else if (type == 3 || type == 4) {
                final long[] v = new long[count];
                for (int k = 0; k < count; k++)
                    v[k] = type == 3 ? (b.getShort(at + 2 * k) & 0xFFFF) : (b.getInt(at + 4 * k) & 0xFFFFFFFFL);
                tags.put(tag, v);
            }
        }
        final int width = (int) one(tags, 256), height = (int) one(tags, 257);
        if (one(tags, 258) != 16)
            throw new IllegalStateException("LANDFIRE grid is not 16-bit");
        if (tags.containsKey(259) && one(tags, 259) != 1)
            throw new IllegalStateException("LANDFIRE grid is compressed");
        final short[] data = new short[width * height];
        if (tags.containsKey(324)) {
            final int tw = (int) one(tags, 322), th = (int) one(tags, 323);
            final long[] offs = tags.get(324);
            final int across = (width + tw - 1) / tw;
            for (int t = 0; t < offs.length; t++) {
                final int row0 = (t / across) * th, col0 = (t % across) * tw;
                final int at = (int) offs[t];
                for (int y = 0; y < th && row0 + y < height; y++)
                    for (int x = 0; x < tw && col0 + x < width; x++)
                        data[(row0 + y) * width + col0 + x] = b.getShort(at + 2 * (y * tw + x));
            }
        } else if (tags.containsKey(273)) {
            final long[] offs = tags.get(273);
            final int rps = tags.containsKey(278) ? (int) Math.min(one(tags, 278), height) : height;
            for (int s = 0; s < offs.length; s++) {
                final int at = (int) offs[s];
                for (int y = 0; y < rps && s * rps + y < height; y++)
                    for (int x = 0; x < width; x++)
                        data[(s * rps + y) * width + x] = b.getShort(at + 2 * (y * width + x));
            }
        } else {
            throw new IllegalStateException("LANDFIRE grid has no tiles and no strips");
        }
        final double lon0, lat0, dLon, dLat;
        if (dtags.containsKey(34264)) {
            final double[] m = dtags.get(34264);
            dLon = m[0];
            lon0 = m[3];
            dLat = m[5];
            lat0 = m[7];
        } else if (dtags.containsKey(33550) && dtags.containsKey(33922)) {
            final double[] sc = dtags.get(33550), tp = dtags.get(33922);
            dLon = sc[0];
            dLat = -sc[1];
            lon0 = tp[3] - tp[0] * dLon;
            lat0 = tp[4] - tp[1] * dLat;
        } else {
            throw new IllegalStateException("LANDFIRE grid has no georeferencing");
        }
        int nodata = 32767;
        if (nodataText != null) {
            try {
                nodata = (int) Math.round(Double.parseDouble(nodataText));
            } catch (NumberFormatException ignored) {
            }
        }
        return new Raster(width, height, data, lon0, lat0, dLon, dLat, nodata);
    }

    private static long one(Map<Integer, long[]> tags, int tag) {
        final long[] v = tags.get(tag);
        if (v == null || v.length == 0)
            throw new IllegalStateException("LANDFIRE grid lacks tag " + tag);
        return v[0];
    }
}
