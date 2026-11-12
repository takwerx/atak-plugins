package com.atakmap.android.firecast;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

/**
 * One band of a float GeoTIFF in a UTM zone, read into a grid cropped to the cells that
 * hold data, with a latitude and longitude for any cell. Written for the time-of-arrival
 * rasters ELMFIRE produces (GDAL: 32-bit float, deflate, floating-point predictor,
 * 512 x 512 tiles, nodata 0), and refusing anything else by name rather than guessing.
 *
 * <p>The file comes from a server, so every count it states is checked against the
 * bytes actually present before it is used as a length or an offset, and the
 * whole file, the image and each tile are capped.
 */
public final class GeoTiffGrid {

    /** Cropped grid size and values, row-major from the north-west; cells with no data hold +infinity. */
    public final int nx, ny;
    public final float[] v;
    /** UTM easting and northing of the cropped grid's north-west corner, and the cell size, in metres. */
    public final double e0, n0, cell;
    public final int zone;
    public final boolean north;
    /** The largest value, and how many cells hold one. */
    public final float max;
    public final int count;

    private GeoTiffGrid(int nx, int ny, float[] v, double e0, double n0, double cell, int zone, boolean north,
            float max, int count) {
        this.nx = nx;
        this.ny = ny;
        this.v = v;
        this.e0 = e0;
        this.n0 = n0;
        this.cell = cell;
        this.zone = zone;
        this.north = north;
        this.max = max;
        this.count = count;
    }

    /** Latitude and longitude of a point in the cropped grid, in cell units from the north-west corner. */
    public double[] latLon(double col, double row) {
        return utmToLatLon(zone, north, e0 + col * cell, n0 - row * cell);
    }

    /** The value at a cell, +infinity outside the grid or where there is none. */
    public float at(int col, int row) {
        if (col < 0 || row < 0 || col >= nx || row >= ny)
            return Float.POSITIVE_INFINITY;
        return v[row * nx + col];
    }

    /**
     * @param maxBytes the most the file may be
     * @param maxDim   the most cells across or down the image may be
     * @param margin   empty cells kept around the data, so a contour closes outside it
     */
    public static GeoTiffGrid read(File f, long maxBytes, int maxDim, int margin) throws IOException {
        final long len = f.length();
        if (len < 16 || len > maxBytes)
            throw new IOException("GeoTIFF is " + (len >> 10) + " KB");
        final byte[] b = new byte[(int) len];
        try (InputStream in = new FileInputStream(f)) {
            int off = 0, n;
            while (off < b.length && (n = in.read(b, off, b.length - off)) > 0)
                off += n;
            if (off != b.length)
                throw new IOException("short read");
        }
        return parse(b, maxDim, margin);
    }

    // ---- TIFF -------------------------------------------------------------------------

    private static final int WIDTH = 256, HEIGHT = 257, BITS = 258, COMPRESSION = 259, STRIP_OFFSETS = 273,
            SAMPLES = 277, ROWS_PER_STRIP = 278, STRIP_BYTES = 279, PREDICTOR = 317, TILE_W = 322, TILE_H = 323,
            TILE_OFFSETS = 324, TILE_BYTES = 325, SAMPLE_FORMAT = 339, PIXEL_SCALE = 33550, TIEPOINT = 33922,
            GEO_KEYS = 34735, NODATA = 42113;

    /** One IFD entry: where its values are and how many. */
    private static final class Entry {
        int type, count, at;
    }

    private static final class Reader {
        final byte[] b;
        final boolean le;

        Reader(byte[] b, boolean le) {
            this.b = b;
            this.le = le;
        }

        int u16(int p) {
            check(p, 2);
            return le ? (b[p] & 0xFF) | (b[p + 1] & 0xFF) << 8 : (b[p] & 0xFF) << 8 | (b[p + 1] & 0xFF);
        }

        int u32(int p) {
            check(p, 4);
            final long v = le
                    ? (b[p] & 0xFFL) | (b[p + 1] & 0xFFL) << 8 | (b[p + 2] & 0xFFL) << 16 | (b[p + 3] & 0xFFL) << 24
                    : (b[p] & 0xFFL) << 24 | (b[p + 1] & 0xFFL) << 16 | (b[p + 2] & 0xFFL) << 8 | (b[p + 3] & 0xFFL);
            if (v > Integer.MAX_VALUE)
                throw new IllegalStateException("offset past 2 GB");
            return (int) v;
        }

        double f64(int p) {
            check(p, 8);
            long v = 0;
            for (int i = 0; i < 8; i++)
                v |= (b[p + (le ? 7 - i : i)] & 0xFFL) << (56 - 8 * i);
            return Double.longBitsToDouble(v);
        }

        void check(int p, int n) {
            if (p < 0 || n < 0 || (long) p + n > b.length)
                throw new IllegalStateException("TIFF points past its end");
        }
    }

    private static int typeSize(int type) {
        switch (type) {
            case 1: case 2: case 6: case 7: return 1;
            case 3: case 8: return 2;
            case 4: case 9: case 11: return 4;
            case 5: case 10: case 12: case 16: return 8;
            default: return 0;
        }
    }

    static GeoTiffGrid parse(byte[] b, int maxDim, int margin) throws IOException {
        try {
            final boolean le;
            if (b[0] == 'I' && b[1] == 'I')
                le = true;
            else if (b[0] == 'M' && b[1] == 'M')
                le = false;
            else
                throw new IOException("not a TIFF");
            final Reader r = new Reader(b, le);
            if (r.u16(2) != 42)
                throw new IOException("not a classic TIFF");
            final int ifd = r.u32(4);
            final int n = r.u16(ifd);
            if (n > 512)
                throw new IOException("too many tags");
            final Map<Integer, Entry> tags = new HashMap<>();
            for (int i = 0; i < n; i++) {
                final int p = ifd + 2 + i * 12;
                final Entry e = new Entry();
                final int tag = r.u16(p);
                e.type = r.u16(p + 2);
                e.count = r.u32(p + 4);
                final int size = typeSize(e.type);
                if (size == 0 || e.count < 0 || (long) size * e.count > b.length)
                    throw new IOException("bad tag " + tag);
                e.at = size * e.count <= 4 ? p + 8 : r.u32(p + 8);
                r.check(e.at, size * e.count);
                tags.put(tag, e);
            }
            final int w = one(r, tags, WIDTH), h = one(r, tags, HEIGHT);
            if (w <= 0 || h <= 0 || w > maxDim || h > maxDim)
                throw new IOException("image is " + w + " x " + h);
            if (one(r, tags, BITS) != 32 || one(r, tags, SAMPLE_FORMAT) != 3 || oneOr(r, tags, SAMPLES, 1) != 1)
                throw new IOException("not a one-band 32-bit float image");
            final int compression = oneOr(r, tags, COMPRESSION, 1);
            if (compression != 1 && compression != 8 && compression != 32946)
                throw new IOException("compression " + compression);
            final int predictor = oneOr(r, tags, PREDICTOR, 1);
            if (predictor != 1 && predictor != 3)
                throw new IOException("predictor " + predictor);
            final int tw, th;
            final int[] offsets, counts;
            if (tags.containsKey(TILE_OFFSETS)) {
                tw = one(r, tags, TILE_W);
                th = one(r, tags, TILE_H);
                offsets = ints(r, tags.get(TILE_OFFSETS));
                counts = ints(r, tags.get(TILE_BYTES));
            } else {
                tw = w;
                th = Math.min(h, oneOr(r, tags, ROWS_PER_STRIP, h));
                offsets = ints(r, tags.get(STRIP_OFFSETS));
                counts = ints(r, tags.get(STRIP_BYTES));
            }
            if (tw <= 0 || th <= 0 || tw > 4096 || th > 4096)
                throw new IOException("tiles are " + tw + " x " + th);
            final int across = (w + tw - 1) / tw, down = (h + th - 1) / th;
            if (offsets.length < across * down || counts.length < across * down)
                throw new IOException("tile table short");
            // Georeferencing.
            final Entry scale = tags.get(PIXEL_SCALE), tie = tags.get(TIEPOINT), keys = tags.get(GEO_KEYS);
            if (scale == null || tie == null || keys == null || scale.type != 12 || tie.type != 12 || keys.type != 3
                    || scale.count < 2 || tie.count < 6)
                throw new IOException("no georeferencing");
            final double sx = r.f64(scale.at), sy = r.f64(scale.at + 8);
            if (!(sx > 0) || !(sy > 0) || Math.abs(sx - sy) > 1e-6)
                throw new IOException("cells are not square");
            final double ti = r.f64(tie.at), tj = r.f64(tie.at + 8), tx = r.f64(tie.at + 24), ty = r.f64(tie.at + 32);
            final double imgE0 = tx - ti * sx, imgN0 = ty + tj * sy;
            int epsg = 0;
            final int nkeys = keys.count / 4;
            for (int k = 1; k < nkeys; k++) {
                final int p = keys.at + k * 8;
                if (r.u16(p) == 3072 && r.u16(p + 2) == 0)
                    epsg = r.u16(p + 6);
            }
            final boolean north;
            final int zone;
            if (epsg >= 32601 && epsg <= 32660) {
                north = true;
                zone = epsg - 32600;
            } else if (epsg >= 32701 && epsg <= 32760) {
                north = false;
                zone = epsg - 32700;
            } else
                throw new IOException("projection EPSG " + epsg + " is not a WGS 84 UTM zone");
            float nodata = 0f;
            final Entry nd = tags.get(NODATA);
            if (nd != null && nd.type == 2) {
                final String s = new String(b, nd.at, nd.count, "US-ASCII").trim().replace("\0", "");
                try {
                    nodata = Float.parseFloat(s);
                } catch (NumberFormatException ignored) {
                }
            }
            // Decode tile by tile, keeping only the tiles that hold data.
            final List<int[]> kept = new ArrayList<>();
            final List<float[]> tiles = new ArrayList<>();
            int minX = w, minY = h, maxX = -1, maxY = -1, count = 0;
            float max = Float.NEGATIVE_INFINITY;
            final byte[] raw = new byte[tw * th * 4];
            for (int t = 0; t < across * down; t++) {
                final int tcol = t % across, trow = t / across;
                r.check(offsets[t], counts[t]);
                final int got = compression == 1 ? copy(b, offsets[t], counts[t], raw) : inflate(b, offsets[t], counts[t], raw);
                if (got != raw.length)
                    throw new IOException("tile " + t + " is " + got + " bytes");
                if (predictor == 3)
                    undoFloatPredictor(raw, tw, th);
                float[] tile = null;
                for (int y = 0; y < th; y++) {
                    final int gy = trow * th + y;
                    if (gy >= h)
                        break;
                    for (int x = 0; x < tw; x++) {
                        final int gx = tcol * tw + x;
                        if (gx >= w)
                            break;
                        final int p = (y * tw + x) * 4;
                        final int bits = le
                                ? (raw[p] & 0xFF) | (raw[p + 1] & 0xFF) << 8 | (raw[p + 2] & 0xFF) << 16 | (raw[p + 3] & 0xFF) << 24
                                : (raw[p] & 0xFF) << 24 | (raw[p + 1] & 0xFF) << 16 | (raw[p + 2] & 0xFF) << 8 | (raw[p + 3] & 0xFF);
                        final float f = Float.intBitsToFloat(bits);
                        if (f == nodata || Float.isNaN(f) || Float.isInfinite(f))
                            continue;
                        if (tile == null) {
                            tile = new float[tw * th];
                            java.util.Arrays.fill(tile, Float.POSITIVE_INFINITY);
                        }
                        tile[y * tw + x] = f;
                        count++;
                        if (f > max)
                            max = f;
                        if (gx < minX) minX = gx;
                        if (gx > maxX) maxX = gx;
                        if (gy < minY) minY = gy;
                        if (gy > maxY) maxY = gy;
                    }
                }
                if (tile != null) {
                    kept.add(new int[] { tcol, trow });
                    tiles.add(tile);
                }
            }
            if (count == 0)
                throw new IOException("the image holds no data");
            // Crop to the data with a margin, inside the image.
            final int cx0 = Math.max(0, minX - margin), cy0 = Math.max(0, minY - margin);
            final int cx1 = Math.min(w - 1, maxX + margin), cy1 = Math.min(h - 1, maxY + margin);
            final int nx = cx1 - cx0 + 1, ny = cy1 - cy0 + 1;
            final float[] v = new float[nx * ny];
            java.util.Arrays.fill(v, Float.POSITIVE_INFINITY);
            for (int i = 0; i < kept.size(); i++) {
                final int tcol = kept.get(i)[0], trow = kept.get(i)[1];
                final float[] tile = tiles.get(i);
                for (int y = 0; y < th; y++) {
                    final int gy = trow * th + y - cy0;
                    if (gy < 0 || gy >= ny)
                        continue;
                    for (int x = 0; x < tw; x++) {
                        final int gx = tcol * tw + x - cx0;
                        if (gx < 0 || gx >= nx)
                            continue;
                        v[gy * nx + gx] = tile[y * tw + x];
                    }
                }
            }
            return new GeoTiffGrid(nx, ny, v, imgE0 + cx0 * sx, imgN0 - cy0 * sy, sx, zone, north, max, count);
        } catch (IllegalStateException | IndexOutOfBoundsException | NegativeArraySizeException e) {
            throw new IOException("malformed GeoTIFF: " + e.getMessage());
        }
    }

    private static int one(Reader r, Map<Integer, Entry> tags, int tag) throws IOException {
        final Entry e = tags.get(tag);
        if (e == null)
            throw new IOException("no tag " + tag);
        return value(r, e, 0);
    }

    private static int oneOr(Reader r, Map<Integer, Entry> tags, int tag, int dflt) throws IOException {
        final Entry e = tags.get(tag);
        return e == null ? dflt : value(r, e, 0);
    }

    private static int value(Reader r, Entry e, int i) throws IOException {
        if (i >= e.count)
            throw new IOException("tag value " + i + " missing");
        switch (e.type) {
            case 3: return r.u16(e.at + 2 * i);
            case 4: return r.u32(e.at + 4 * i);
            case 1: return r.b[e.at + i] & 0xFF;
            default: throw new IOException("tag type " + e.type);
        }
    }

    private static int[] ints(Reader r, Entry e) throws IOException {
        if (e == null)
            throw new IOException("tile table missing");
        if (e.count > 1 << 20)
            throw new IOException("tile table has " + e.count + " entries");
        final int[] out = new int[e.count];
        for (int i = 0; i < e.count; i++)
            out[i] = value(r, e, i);
        return out;
    }

    private static int copy(byte[] b, int off, int len, byte[] out) {
        final int n = Math.min(len, out.length);
        System.arraycopy(b, off, out, 0, n);
        return n;
    }

    private static int inflate(byte[] b, int off, int len, byte[] out) throws IOException {
        final Inflater inf = new Inflater();
        try {
            inf.setInput(b, off, len);
            int got = 0;
            while (got < out.length && !inf.finished()) {
                final int n = inf.inflate(out, got, out.length - got);
                if (n == 0 && (inf.needsInput() || inf.needsDictionary()))
                    break;
                got += n;
            }
            return got;
        } catch (DataFormatException e) {
            throw new IOException("tile will not inflate");
        } finally {
            inf.end();
        }
    }

    /**
     * Undoes libtiff's floating-point predictor, row by row: the bytes were split into
     * four planes, most significant first, and differenced byte against byte; so sum
     * them back and interleave the planes into little-endian floats.
     */
    private static void undoFloatPredictor(byte[] raw, int tw, int th) {
        final int rowBytes = tw * 4;
        final byte[] tmp = new byte[rowBytes];
        for (int y = 0; y < th; y++) {
            final int base = y * rowBytes;
            for (int i = 1; i < rowBytes; i++)
                raw[base + i] = (byte) (raw[base + i] + raw[base + i - 1]);
            System.arraycopy(raw, base, tmp, 0, rowBytes);
            for (int i = 0; i < tw; i++)
                for (int k = 0; k < 4; k++)
                    raw[base + 4 * i + k] = tmp[(3 - k) * tw + i];
        }
    }

    // ---- UTM --------------------------------------------------------------------------

    /** WGS 84 UTM to latitude and longitude in degrees (Snyder's series, within a few mm in a zone). */
    public static double[] utmToLatLon(int zone, boolean north, double easting, double northing) {
        final double a = 6378137.0, f = 1 / 298.257223563, k0 = 0.9996;
        final double e2 = f * (2 - f), ep2 = e2 / (1 - e2);
        final double x = easting - 500000.0, y = north ? northing : northing - 10000000.0;
        final double m = y / k0;
        final double mu = m / (a * (1 - e2 / 4 - 3 * e2 * e2 / 64 - 5 * e2 * e2 * e2 / 256));
        final double e1 = (1 - Math.sqrt(1 - e2)) / (1 + Math.sqrt(1 - e2));
        final double phi1 = mu + (3 * e1 / 2 - 27 * Math.pow(e1, 3) / 32) * Math.sin(2 * mu)
                + (21 * e1 * e1 / 16 - 55 * Math.pow(e1, 4) / 32) * Math.sin(4 * mu)
                + (151 * Math.pow(e1, 3) / 96) * Math.sin(6 * mu)
                + (1097 * Math.pow(e1, 4) / 512) * Math.sin(8 * mu);
        final double sin1 = Math.sin(phi1), cos1 = Math.cos(phi1), tan1 = Math.tan(phi1);
        final double n1 = a / Math.sqrt(1 - e2 * sin1 * sin1);
        final double t1 = tan1 * tan1, c1 = ep2 * cos1 * cos1;
        final double r1 = a * (1 - e2) / Math.pow(1 - e2 * sin1 * sin1, 1.5);
        final double d = x / (n1 * k0);
        final double lat = phi1 - (n1 * tan1 / r1) * (d * d / 2
                - (5 + 3 * t1 + 10 * c1 - 4 * c1 * c1 - 9 * ep2) * Math.pow(d, 4) / 24
                + (61 + 90 * t1 + 298 * c1 + 45 * t1 * t1 - 252 * ep2 - 3 * c1 * c1) * Math.pow(d, 6) / 720);
        final double lon0 = Math.toRadians((zone - 1) * 6 - 180 + 3);
        final double lon = lon0 + (d - (1 + 2 * t1 + c1) * Math.pow(d, 3) / 6
                + (5 - 2 * c1 + 28 * t1 - 3 * c1 * c1 + 8 * ep2 + 24 * t1 * t1) * Math.pow(d, 5) / 120) / cos1;
        return new double[] { Math.toDegrees(lat), Math.toDegrees(lon) };
    }
}
