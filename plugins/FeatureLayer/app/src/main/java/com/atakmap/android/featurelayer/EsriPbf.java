package com.atakmap.android.featurelayer;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reader for an ArcGIS query answered with {@code f=pbf}: Esri's FeatureCollection
 * protobuf (github.com/Esri/arcgis-pbf, FeatureCollection.proto). The same page of a
 * land layer is four to nine times smaller than its JSON (SRA around Los Angeles:
 * 72 KB against 653 KB, 2026-10-16), and the wire was most of a land layer's redraw
 * time on the phone.
 *
 * This is the protobuf wire format read by hand, no runtime library: ATAK ships none a
 * plugin may use, and the schema is a dozen messages. Every length is checked against
 * the bytes in hand before it is trusted, so a short or hostile answer ends in an
 * exception, never a read past the end or an allocation sized by the server.
 *
 * Coordinates arrive quantized: integers on a grid, delta-encoded within each ring, and
 * the result's transform says how to scale them back. The server quantizes to the
 * query's maxAllowableOffset when no quantizationParameters are given, which is what a
 * land layer asks for anyway (one map pixel at the time of the fetch).
 */
final class EsriPbf {
    private EsriPbf() {
    }

    /** What one page hands back, feature by feature. */
    interface Sink {
        /**
         * @param attributes the row's fields by name, in the service's order; values are
         *                   String, Long, Double or Boolean, nulls left out
         * @param type       Esri geometry type: 0 point, 2 polyline, 3 polygon
         * @param parts      each part's vertices as x,y pairs in the result's spatial
         *                   reference (lon, lat here); a point is one part of one vertex
         */
        void feature(Map<String, Object> attributes, int type, List<double[]> parts) throws Exception;
    }

    /** The page's header: whether another page follows, and how many rows it carried. */
    static final class Page {
        boolean exceededTransferLimit;
        /** Every feature in the page, with or without a geometry: the server's row count. */
        int count;
    }

    // FeatureCollectionPBuffer
    private static final int FC_QUERY_RESULT = 2;
    // QueryResult
    private static final int QR_FEATURE_RESULT = 1;
    // FeatureResult
    private static final int FR_GEOMETRY_TYPE = 7, FR_EXCEEDED = 9, FR_HAS_Z = 10, FR_HAS_M = 11, FR_TRANSFORM = 12,
            FR_FIELDS = 13, FR_FEATURES = 15;
    // Transform / Scale / Translate
    private static final int TR_ORIGIN = 1, TR_SCALE = 2, TR_TRANSLATE = 3;
    // Field
    private static final int FIELD_NAME = 1, FIELD_TYPE = 2;
    // Feature
    private static final int FEAT_ATTRIBUTES = 1, FEAT_GEOMETRY = 2;
    // Geometry
    private static final int GEOM_LENGTHS = 2, GEOM_COORDS = 3;
    // Value
    private static final int VAL_STRING = 1, VAL_FLOAT = 2, VAL_DOUBLE = 3, VAL_SINT32 = 4, VAL_UINT32 = 5,
            VAL_INT64 = 6, VAL_UINT64 = 7, VAL_SINT64 = 8, VAL_BOOL = 9, VAL_NULL = 10, VAL_INDEX = 11;

    static final int TYPE_POINT = 0, TYPE_POLYLINE = 2, TYPE_POLYGON = 3;

    /** Reads one query answer and hands every feature to {@code sink}. */
    static Page read(byte[] data, Sink sink) throws Exception {
        final Reader top = new Reader(data, 0, data.length);
        final Page page = new Page();
        while (top.hasMore()) {
            final int tag = top.tag();
            if (field(tag) == FC_QUERY_RESULT && wire(tag) == 2) {
                final Reader qr = top.message();
                while (qr.hasMore()) {
                    final int t2 = qr.tag();
                    if (field(t2) == QR_FEATURE_RESULT && wire(t2) == 2)
                        featureResult(qr.message(), page, sink);
                    else
                        qr.skip(t2);
                }
            } else {
                top.skip(tag);
            }
        }
        return page;
    }

    private static void featureResult(Reader fr, Page page, Sink sink) throws Exception {
        final List<String> names = new ArrayList<>();
        final List<Integer> types = new ArrayList<>();
        final List<Reader> features = new ArrayList<>();
        int geometryType = TYPE_POLYGON;
        int dims = 2;
        // Default transform: the server always writes one for a geometry result, but
        // an identity is the safe reading of a message without it.
        double sx = 1, sy = 1, tx = 0, ty = 0;
        boolean upperLeft = true;
        while (fr.hasMore()) {
            final int tag = fr.tag();
            switch (field(tag)) {
                case FR_GEOMETRY_TYPE:
                    geometryType = (int) fr.varint();
                    break;
                case FR_EXCEEDED:
                    page.exceededTransferLimit = fr.varint() != 0;
                    break;
                case FR_HAS_Z:
                    if (fr.varint() != 0)
                        dims++;
                    break;
                case FR_HAS_M:
                    if (fr.varint() != 0)
                        dims++;
                    break;
                case FR_TRANSFORM: {
                    final Reader tr = fr.message();
                    while (tr.hasMore()) {
                        final int t = tr.tag();
                        switch (field(t)) {
                            case TR_ORIGIN:
                                upperLeft = tr.varint() == 0;
                                break;
                            case TR_SCALE: {
                                final double[] v = doubles(tr.message());
                                sx = v[0];
                                sy = v[1];
                                break;
                            }
                            case TR_TRANSLATE: {
                                final double[] v = doubles(tr.message());
                                tx = v[0];
                                ty = v[1];
                                break;
                            }
                            default:
                                tr.skip(t);
                        }
                    }
                    break;
                }
                case FR_FIELDS: {
                    final Reader f = fr.message();
                    String name = null;
                    int type = 0;
                    while (f.hasMore()) {
                        final int t = f.tag();
                        if (field(t) == FIELD_NAME)
                            name = f.string();
                        else if (field(t) == FIELD_TYPE)
                            type = (int) f.varint();
                        else
                            f.skip(t);
                    }
                    names.add(name == null ? "" : name);
                    types.add(type);
                    break;
                }
                case FR_FEATURES:
                    // Held until the header is complete: the fields and the transform
                    // may follow the features in the stream order of some servers.
                    features.add(fr.message());
                    break;
                default:
                    fr.skip(tag);
            }
        }
        for (Reader f : features) {
            final Map<String, Object> attrs = new LinkedHashMap<>();
            List<double[]> parts = null;
            int slot = 0;
            while (f.hasMore()) {
                final int tag = f.tag();
                if (field(tag) == FEAT_ATTRIBUTES && wire(tag) == 2) {
                    final Object[] v = value(f.message());
                    final int at = v[1] != null ? (Integer) v[1] : slot;
                    slot++;
                    if (v[0] != null && at >= 0 && at < names.size())
                        attrs.put(names.get(at), v[0]);
                } else if (field(tag) == FEAT_GEOMETRY && wire(tag) == 2) {
                    parts = geometry(f.message(), geometryType, dims, sx, sy, tx, ty, upperLeft);
                } else {
                    f.skip(tag);
                }
            }
            page.count++;
            if (parts == null || parts.isEmpty())
                continue;
            sink.feature(attrs, geometryType, parts);
        }
    }

    /** A Scale or Translate message: its first two doubles (x, y). */
    private static double[] doubles(Reader r) throws Exception {
        final double[] v = new double[4];
        while (r.hasMore()) {
            final int t = r.tag();
            final int f = field(t);
            if (wire(t) == 1 && f >= 1 && f <= 4)
                v[f - 1] = r.fixed64Double();
            else
                r.skip(t);
        }
        return v;
    }

    /** A Value: {the value or null, the field index or null}. */
    private static Object[] value(Reader r) throws Exception {
        Object v = null;
        Integer index = null;
        while (r.hasMore()) {
            final int t = r.tag();
            switch (field(t)) {
                case VAL_STRING:
                    v = r.string();
                    break;
                case VAL_FLOAT:
                    v = (double) r.fixed32Float();
                    break;
                case VAL_DOUBLE:
                    v = r.fixed64Double();
                    break;
                case VAL_SINT32:
                    v = zigzag(r.varint());
                    break;
                case VAL_UINT32:
                case VAL_INT64:
                case VAL_UINT64:
                    v = r.varint();
                    break;
                case VAL_SINT64:
                    v = zigzag(r.varint());
                    break;
                case VAL_BOOL:
                    v = r.varint() != 0;
                    break;
                case VAL_NULL:
                    r.varint();
                    v = null;
                    break;
                case VAL_INDEX:
                    index = (int) r.varint();
                    break;
                default:
                    r.skip(t);
            }
        }
        return new Object[] { v, index };
    }

    /**
     * A Geometry: {@code lengths} is the vertex count of each part, {@code coords} the
     * vertices as zigzag integers, each ring starting from the origin and every vertex
     * after the first a delta from the one before it. A point has no lengths and one
     * vertex.
     */
    private static List<double[]> geometry(Reader r, int type, int dims, double sx, double sy, double tx,
            double ty, boolean upperLeft) throws Exception {
        long[] lengths = null;
        long[] coords = null;
        while (r.hasMore()) {
            final int t = r.tag();
            if (field(t) == GEOM_LENGTHS)
                lengths = r.packedVarints(t);
            else if (field(t) == GEOM_COORDS)
                coords = r.packedVarints(t);
            else
                r.skip(t);
        }
        if (coords == null)
            return null;
        if (lengths == null || lengths.length == 0)
            lengths = new long[] { coords.length / dims };
        final List<double[]> parts = new ArrayList<>(lengths.length);
        int at = 0;
        for (long len : lengths) {
            if (len < 0 || len > Integer.MAX_VALUE / dims || at + len * dims > coords.length)
                throw new IllegalStateException("pbf: part of " + len + " vertices, " + (coords.length - at)
                        + " values left");
            final int n = (int) len;
            final double[] part = new double[n * 2];
            long x = 0, y = 0;
            for (int i = 0; i < n; i++) {
                x += zigzag(coords[at]);
                y += zigzag(coords[at + 1]);
                at += dims;
                part[i * 2] = tx + x * sx;
                part[i * 2 + 1] = upperLeft ? ty - y * sy : ty + y * sy;
            }
            parts.add(part);
        }
        return parts;
    }

    private static int field(int tag) {
        return tag >>> 3;
    }

    private static int wire(int tag) {
        return tag & 7;
    }

    private static long zigzag(long v) {
        return (v >>> 1) ^ -(v & 1);
    }

    /** A cursor over one message's bytes; a sub-message is a cursor over its slice. */
    private static final class Reader {
        private final byte[] b;
        private int pos;
        private final int end;

        Reader(byte[] b, int pos, int end) {
            this.b = b;
            this.pos = pos;
            this.end = end;
        }

        boolean hasMore() {
            return pos < end;
        }

        int tag() throws Exception {
            final long t = varint();
            if (t <= 0 || t > Integer.MAX_VALUE)
                throw new IllegalStateException("pbf: bad tag");
            return (int) t;
        }

        long varint() throws Exception {
            long r = 0;
            int shift = 0;
            while (true) {
                if (pos >= end)
                    throw new IllegalStateException("pbf: truncated");
                final int c = b[pos++] & 0xff;
                r |= (long) (c & 0x7f) << shift;
                if (c < 0x80)
                    return r;
                shift += 7;
                if (shift > 63)
                    throw new IllegalStateException("pbf: varint too long");
            }
        }

        private int length() throws Exception {
            final long n = varint();
            if (n < 0 || n > end - pos)
                throw new IllegalStateException("pbf: length " + n + " past the end");
            return (int) n;
        }

        Reader message() throws Exception {
            final int n = length();
            final Reader r = new Reader(b, pos, pos + n);
            pos += n;
            return r;
        }

        String string() throws Exception {
            final int n = length();
            final String s = new String(b, pos, n, StandardCharsets.UTF_8);
            pos += n;
            return s;
        }

        double fixed64Double() throws Exception {
            if (end - pos < 8)
                throw new IllegalStateException("pbf: truncated");
            long v = 0;
            for (int i = 7; i >= 0; i--)
                v = (v << 8) | (b[pos + i] & 0xffL);
            pos += 8;
            return Double.longBitsToDouble(v);
        }

        float fixed32Float() throws Exception {
            if (end - pos < 4)
                throw new IllegalStateException("pbf: truncated");
            int v = 0;
            for (int i = 3; i >= 0; i--)
                v = (v << 8) | (b[pos + i] & 0xff);
            pos += 4;
            return Float.intBitsToFloat(v);
        }

        /** A packed repeated varint field, or one unpacked element of it. */
        long[] packedVarints(int tag) throws Exception {
            if (wire(tag) == 0)
                return new long[] { varint() };
            final int n = length();
            final int stop = pos + n;
            // Each varint is at least one byte, so this is the most there can be.
            long[] out = new long[Math.min(n, 1024)];
            int count = 0;
            while (pos < stop) {
                if (count == out.length)
                    out = java.util.Arrays.copyOf(out, Math.min(n, out.length * 2));
                out[count++] = varint();
            }
            if (pos != stop)
                throw new IllegalStateException("pbf: packed field overran");
            return count == out.length ? out : java.util.Arrays.copyOf(out, count);
        }

        void skip(int tag) throws Exception {
            switch (wire(tag)) {
                case 0:
                    varint();
                    break;
                case 1:
                    if (end - pos < 8)
                        throw new IllegalStateException("pbf: truncated");
                    pos += 8;
                    break;
                case 2: {
                    // Not pos += length(): Java reads pos before the call, and
                    // length() moves it.
                    final int n = length();
                    pos += n;
                    break;
                }
                case 5:
                    if (end - pos < 4)
                        throw new IllegalStateException("pbf: truncated");
                    pos += 4;
                    break;
                default:
                    throw new IllegalStateException("pbf: wire type " + wire(tag));
            }
        }
    }
}
