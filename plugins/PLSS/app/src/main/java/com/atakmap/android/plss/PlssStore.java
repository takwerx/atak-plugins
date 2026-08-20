
package com.atakmap.android.plss;

import com.atakmap.coremap.log.Log;
import com.atakmap.database.DatabaseIface;
import com.atakmap.database.Databases;
import com.atakmap.database.QueryIface;

import java.io.File;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.DoubleBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * Read side of the packed PLSS store built by tools/pack_plss.py.
 *
 * Queries by bounding box through the R-tree so a state is never loaded whole
 * (PLAN-PLSS-v0.1.md section 5.2), and decodes the quantized delta-varint blobs
 * straight into a buffer of line segments the renderer can hand to GL.
 *
 * Goes through ATAK's DatabaseIface rather than android.database.sqlite: Android's
 * bundled SQLite is built without the R*Tree module, so the index is invisible to
 * it ("no such module: rtree"). ATAK ships its own SQLite for SpatiaLite, which
 * has R*Tree. Binding also becomes typed -- Android's rawQuery passes every
 * argument as text, which compares wrongly against the index's REAL columns.
 *
 * Geometry comes back as segment endpoints -- each edge contributes both of its
 * vertices -- so the whole visible set draws in one GL_LINES call rather than one
 * call per polygon.
 */
public class PlssStore {

    public static final String TAG = "PlssStore";

    /** must match SCALE in tools/pack_plss.py */
    private static final double SCALE = 1000000.0;

    /** One bbox query's worth of geometry and labels. */
    public static final class Result {
        /** lon/lat pairs, two per segment */
        public final DoubleBuffer segments;
        public final int segmentVertexCount;

        /**
         * Two lon/lat points per label -- the south-west and north-east corners
         * of the feature's index box. The renderer projects both so it knows how
         * wide the feature is on screen, and can drop labels that would not fit
         * inside it.
         */
        public final DoubleBuffer labelPoints;
        public final String[] labels;

        Result(DoubleBuffer segments, int segmentVertexCount,
                DoubleBuffer labelPoints, String[] labels) {
            this.segments = segments;
            this.segmentVertexCount = segmentVertexCount;
            this.labelPoints = labelPoints;
            this.labels = labels;
        }
    }

    private final DatabaseIface db;

    private PlssStore(DatabaseIface db) {
        this.db = db;
    }

    public static PlssStore open(File path) {
        if (!path.exists()) {
            Log.w(TAG, "no PLSS store at " + path);
            return null;
        }

        try {
            final DatabaseIface db = Databases.openDatabase(
                    path.getAbsolutePath(), true);
            if (db == null) {
                Log.e(TAG, "could not open PLSS store " + path);
                return null;
            }

            Log.d(TAG, "opened " + path + " (" + path.length() + " bytes)");
            return new PlssStore(db);
        } catch (Exception e) {
            Log.e(TAG, "cannot open PLSS store " + path, e);
            return null;
        }
    }

    public void close() {
        try {
            db.close();
        } catch (Exception ignored) {
            // closing a read-only handle; nothing actionable
        }
    }

    public Result querySections(double west, double south,
            double east, double north, int limit) {
        return query("section", west, south, east, north, limit);
    }

    public Result queryTownships(double west, double south,
            double east, double north, int limit) {
        return query("township", west, south, east, north, limit);
    }

    private Result query(String table, double west, double south,
            double east, double north, int limit) {

        // R-tree columns are (id, minx, maxx, miny, maxy); this is the standard
        // "boxes overlap" test. The label carries the whole index box rather than
        // just its centre so the renderer can size it against the feature.
        final String sql = "SELECT t.geom, t.label,"
                + " i.minx, i.miny, i.maxx, i.maxy"
                + " FROM " + table + " t"
                + " JOIN " + table + "_idx i ON i.id = t.id"
                + " WHERE i.maxx >= ? AND i.minx <= ?"
                + " AND i.maxy >= ? AND i.miny <= ?"
                + " LIMIT ?";

        // Two passes over the result would mean running the query twice, so
        // accumulate into a growable array and copy into a direct buffer once.
        double[] segs = new double[8192];
        int n = 0;
        int features = 0;

        final List<String> labels = new ArrayList<>();
        double[] anchors = new double[1024];
        int an = 0;

        QueryIface q = null;
        try {
            q = db.compileQuery(sql);
            q.bind(1, west);
            q.bind(2, east);
            q.bind(3, south);
            q.bind(4, north);
            q.bind(5, limit);

            while (q.moveToNext()) {
                final byte[] blob = q.getBlob(0);
                if (blob == null)
                    continue;

                features++;

                final int needed = countSegmentDoubles(blob);
                if (n + needed > segs.length) {
                    int cap = segs.length;
                    while (cap < n + needed)
                        cap <<= 1;
                    final double[] bigger = new double[cap];
                    System.arraycopy(segs, 0, bigger, 0, n);
                    segs = bigger;
                }

                n = decodeSegments(blob, segs, n);

                final String label = q.getString(1);
                if (label != null && label.length() > 0) {
                    if (an + 4 > anchors.length) {
                        final double[] bigger = new double[anchors.length * 2];
                        System.arraycopy(anchors, 0, bigger, 0, an);
                        anchors = bigger;
                    }
                    anchors[an++] = q.getDouble(2);   // minx
                    anchors[an++] = q.getDouble(3);   // miny
                    anchors[an++] = q.getDouble(4);   // maxx
                    anchors[an++] = q.getDouble(5);   // maxy
                    labels.add(label);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "query failed on " + table, e);
            return null;
        } finally {
            if (q != null)
                q.close();
        }

        Log.d(TAG, table + ": " + features + " features, " + (n / 4)
                + " segments, " + labels.size() + " labels");

        if (n == 0)
            return null;

        final DoubleBuffer segBuf = ByteBuffer.allocateDirect(n * 8)
                .order(ByteOrder.nativeOrder()).asDoubleBuffer();
        segBuf.put(segs, 0, n);
        segBuf.flip();

        DoubleBuffer anchorBuf = null;
        if (an > 0) {
            anchorBuf = ByteBuffer.allocateDirect(an * 8)
                    .order(ByteOrder.nativeOrder()).asDoubleBuffer();
            anchorBuf.put(anchors, 0, an);
            anchorBuf.flip();
        }

        return new Result(segBuf, n / 2, anchorBuf,
                labels.toArray(new String[0]));
    }

    // ------------------------------------------------------------- decoding

    /** Cursor into a blob during varint decoding. */
    private static final class Reader {
        final byte[] b;
        int pos;

        Reader(byte[] b) {
            this.b = b;
        }

        long varint() {
            long value = 0;
            int shift = 0;
            while (true) {
                final int cur = b[pos++] & 0xFF;
                value |= ((long) (cur & 0x7F)) << shift;
                if ((cur & 0x80) == 0)
                    return value;
                shift += 7;
            }
        }

        long zigzag() {
            final long v = varint();
            return (v >>> 1) ^ -(v & 1);
        }
    }

    /**
     * Doubles needed to hold one feature as segments: each ring of n vertices
     * closes back on itself, so it contributes n segments and 4n doubles.
     */
    private static int countSegmentDoubles(byte[] blob) {
        final Reader r = new Reader(blob);
        r.zigzag();                       // origin lon
        r.zigzag();                       // origin lat

        final int rings = (int) r.varint();
        int doubles = 0;
        for (int i = 0; i < rings; i++) {
            final int verts = (int) r.varint();
            doubles += verts * 4;
            for (int v = 0; v < verts * 2; v++)
                r.varint();               // skip the deltas
        }
        return doubles;
    }

    /**
     * Appends this feature's edges to {@code out} starting at {@code n}, as
     * pairs of lon/lat endpoints. Returns the new fill level.
     */
    private static int decodeSegments(byte[] blob, double[] out, int n) {
        final Reader r = new Reader(blob);

        final long originX = r.zigzag();
        final long originY = r.zigzag();

        final int rings = (int) r.varint();
        for (int i = 0; i < rings; i++) {
            final int verts = (int) r.varint();
            if (verts < 2) {
                for (int v = 0; v < verts * 2; v++)
                    r.varint();
                continue;
            }

            long px = originX;
            long py = originY;

            double firstLon = 0, firstLat = 0;
            double prevLon = 0, prevLat = 0;

            for (int v = 0; v < verts; v++) {
                px += r.zigzag();
                py += r.zigzag();

                final double lon = px / SCALE;
                final double lat = py / SCALE;

                if (v == 0) {
                    firstLon = lon;
                    firstLat = lat;
                } else {
                    out[n++] = prevLon;
                    out[n++] = prevLat;
                    out[n++] = lon;
                    out[n++] = lat;
                }

                prevLon = lon;
                prevLat = lat;
            }

            // close the ring -- BLM rings repeat the first vertex, but not all
            // of them do, and a duplicate zero-length segment costs nothing
            out[n++] = prevLon;
            out[n++] = prevLat;
            out[n++] = firstLon;
            out[n++] = firstLat;
        }

        return n;
    }
}
