
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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

        /**
         * One state-independent identity per label, parallel to labels.
         *
         * CadNSDI is published per state and clips features at the state
         * line, so a border township exists in both neighbours' packs --
         * CA210160N0180E0 and NV210160N0180E0 are the same township -- each
         * fragment carrying its own index box. The key is the PLSSID (or the
         * section's division id) with the two-letter state prefix stripped,
         * which is identical across the packs.
         */
        final String[] labelKeys;

        Result(DoubleBuffer segments, int segmentVertexCount,
                DoubleBuffer labelPoints, String[] labels,
                String[] labelKeys) {
            this.segments = segments;
            this.segmentVertexCount = segmentVertexCount;
            this.labelPoints = labelPoints;
            this.labels = labels;
            this.labelKeys = labelKeys;
        }

        /**
         * Combines per-pack results into one, so a bbox spanning a state line
         * still draws in a single GL call. Packs are per-state and the R-tree
         * makes a miss cheap, so most of these contribute nothing.
         *
         * A feature split by the state line arrives once from each pack;
         * labels are deduplicated on labelKeys and the survivor's index box
         * grown to the union of the fragments', so the single label centres
         * on the whole feature rather than on one state's piece. The line
         * segments are left duplicated -- the fragments abut, so the grid
         * draws correctly either way.
         */
        public static Result merge(List<Result> parts) {
            if (parts.isEmpty())
                return null;
            if (parts.size() == 1)
                return parts.get(0);

            int segDoubles = 0, labelCount = 0;
            for (Result r : parts) {
                segDoubles += r.segments.limit();
                if (r.labelPoints != null)
                    labelCount += r.labels.length;
            }

            final DoubleBuffer segs = ByteBuffer.allocateDirect(segDoubles * 8)
                    .order(ByteOrder.nativeOrder()).asDoubleBuffer();
            for (Result r : parts) {
                r.segments.rewind();
                segs.put(r.segments);
            }
            segs.flip();

            final Map<String, Integer> byKey = new HashMap<>();
            final List<String> outLabels = new ArrayList<>(labelCount);
            final List<String> outKeys = new ArrayList<>(labelCount);
            final double[] boxes = new double[labelCount * 4];

            for (Result r : parts) {
                if (r.labelPoints == null)
                    continue;
                r.labelPoints.rewind();
                for (int i = 0; i < r.labels.length; i++) {
                    final double minx = r.labelPoints.get();
                    final double miny = r.labelPoints.get();
                    final double maxx = r.labelPoints.get();
                    final double maxy = r.labelPoints.get();

                    final String key = r.labelKeys[i];
                    final Integer seen = key != null ? byKey.get(key) : null;
                    if (seen != null) {
                        final int base = seen * 4;
                        boxes[base] = Math.min(boxes[base], minx);
                        boxes[base + 1] = Math.min(boxes[base + 1], miny);
                        boxes[base + 2] = Math.max(boxes[base + 2], maxx);
                        boxes[base + 3] = Math.max(boxes[base + 3], maxy);
                        continue;
                    }

                    final int at = outLabels.size();
                    if (key != null)
                        byKey.put(key, at);
                    final int base = at * 4;
                    boxes[base] = minx;
                    boxes[base + 1] = miny;
                    boxes[base + 2] = maxx;
                    boxes[base + 3] = maxy;
                    outLabels.add(r.labels[i]);
                    outKeys.add(key);
                }
            }

            DoubleBuffer anchors = null;
            if (!outLabels.isEmpty()) {
                anchors = ByteBuffer.allocateDirect(outLabels.size() * 4 * 8)
                        .order(ByteOrder.nativeOrder()).asDoubleBuffer();
                anchors.put(boxes, 0, outLabels.size() * 4);
                anchors.flip();
            }

            return new Result(segs, segDoubles / 2, anchors,
                    outLabels.toArray(new String[0]),
                    outKeys.toArray(new String[0]));
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
        // just its centre so the renderer can size it against the feature. The
        // id column feeds cross-pack label deduplication -- see Result.merge;
        // divid rather than plssid for sections, because a section's plssid is
        // its parent township's and would collapse all 36 to one label.
        final String idColumn = "township".equals(table) ? "t.plssid"
                : "t.divid";
        final String sql = "SELECT t.geom, t.label,"
                + " i.minx, i.miny, i.maxx, i.maxy, " + idColumn
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
        final List<String> labelKeys = new ArrayList<>();
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

                    // state prefix off, so CA/NV copies of a border feature
                    // share a key
                    final String id = q.getString(6);
                    labelKeys.add(id != null && id.length() > 2
                            ? id.substring(2)
                            : null);
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
                labels.toArray(new String[0]),
                labelKeys.toArray(new String[0]));
    }

    // ------------------------------------------------------- point lookup

    /** A PLSS address, as far down as the installed packs can resolve it. */
    public static final class Position {
        /** e.g. "Boise Meridian" */
        public final String meridian;
        /** e.g. "T57N-R1E" */
        public final String township;
        /** e.g. "34", or null where no section covers the point */
        public final String section;

        Position(String meridian, String township, String section) {
            this.meridian = meridian;
            this.township = township;
            this.section = section;
        }

        /** The form you would read over the radio. */
        public String describe() {
            final StringBuilder sb = new StringBuilder(township);
            if (section != null)
                sb.append(" Sec ").append(section);
            return sb.toString();
        }
    }

    /**
     * The PLSS address of a point, or null if this pack does not cover it.
     *
     * The R-tree narrows to candidates by bounding box, but the answer is
     * decided by a ray cast against the feature's own rings: index boxes
     * overlap wherever the survey is irregular -- along meander lines and state
     * borders especially -- so a box hit is not containment. Reporting the
     * wrong section over the radio is worse than reporting none.
     */
    public Position describe(double lat, double lon) {
        final String[] twp = containing("township", lat, lon, true);
        if (twp == null)
            return null;
        final String[] sec = containing("section", lat, lon, false);
        return new Position(twp[1], twp[0], sec != null ? sec[0] : null);
    }

    /** {label, meridian} of the feature whose rings contain the point. */
    private String[] containing(String table, double lat, double lon,
            boolean wantMeridian) {

        final String sql = "SELECT t.label, t.geom"
                + (wantMeridian ? ", t.meridian" : "")
                + " FROM " + table + " t"
                + " JOIN " + table + "_idx i ON i.id = t.id"
                + " WHERE i.minx <= ? AND i.maxx >= ?"
                + " AND i.miny <= ? AND i.maxy >= ?";

        QueryIface q = null;
        try {
            q = db.compileQuery(sql);
            q.bind(1, lon);
            q.bind(2, lon);
            q.bind(3, lat);
            q.bind(4, lat);

            double[] segs = new double[8192];
            while (q.moveToNext()) {
                final byte[] blob = q.getBlob(1);
                if (blob == null)
                    continue;

                final int needed = countSegmentDoubles(blob);
                if (needed > segs.length)
                    segs = new double[needed];

                final int n = decodeSegments(blob, segs, 0);
                if (ringsContain(segs, n, lon, lat)) {
                    return new String[] {
                            q.getString(0),
                            wantMeridian ? q.getString(2) : null
                    };
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "point lookup failed on " + table, e);
        } finally {
            if (q != null)
                q.close();
        }
        return null;
    }

    /**
     * Ray cast against the decoded rings -- an odd number of crossings east of
     * the point means inside. The decoder emits closed rings as independent
     * segments, which is all this needs; it does not care about winding or the
     * order the segments arrive in.
     */
    private static boolean ringsContain(double[] segs, int n, double lon,
            double lat) {
        boolean inside = false;
        for (int i = 0; i + 3 < n; i += 4) {
            final double x1 = segs[i];
            final double y1 = segs[i + 1];
            final double x2 = segs[i + 2];
            final double y2 = segs[i + 3];

            if ((y1 > lat) != (y2 > lat)) {
                final double x = x1 + (lat - y1) / (y2 - y1) * (x2 - x1);
                if (x > lon)
                    inside = !inside;
            }
        }
        return inside;
    }

    // --------------------------------------------------------------- lookup

    /** Principal meridians present in this pack, in name order. */
    public List<String> meridians() {
        final List<String> out = new ArrayList<>();
        QueryIface q = null;
        try {
            q = db.compileQuery("SELECT DISTINCT meridian FROM township"
                    + " WHERE meridian IS NOT NULL AND meridian <> ''"
                    + " ORDER BY meridian");
            while (q.moveToNext())
                out.add(q.getString(0));
        } catch (Exception e) {
            Log.e(TAG, "meridian query failed", e);
        } finally {
            if (q != null)
                q.close();
        }
        return out;
    }

    /**
     * Bounding box of one township, as {west, south, east, north}, or null when
     * this pack does not hold it.
     *
     * Keyed on meridian plus label because township and range numbers repeat
     * across meridians -- "T1N-R1W" exists under most of them, and they are
     * nowhere near each other on the ground.
     */
    public double[] findTownship(String meridian, String label) {
        QueryIface q = null;
        try {
            q = db.compileQuery("SELECT i.minx, i.miny, i.maxx, i.maxy"
                    + " FROM township t JOIN township_idx i ON i.id = t.id"
                    + " WHERE t.meridian = ? AND t.label = ? LIMIT 1");
            q.bind(1, meridian);
            q.bind(2, label);

            if (q.moveToNext())
                return new double[] {
                        q.getDouble(0), q.getDouble(1),
                        q.getDouble(2), q.getDouble(3)
                };
        } catch (Exception e) {
            Log.e(TAG, "township lookup failed", e);
        } finally {
            if (q != null)
                q.close();
        }
        return null;
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
