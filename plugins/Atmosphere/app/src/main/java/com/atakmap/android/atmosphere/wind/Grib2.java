package com.atakmap.android.atmosphere.wind;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.TimeZone;

/**
 * The smallest GRIB2 reader that serves the wind grid: messages in simple packing
 * (data representation template 5.0) on a Lambert conformal grid (template 3.30,
 * HRRR and RAP) or a plain longitude/latitude lattice (template 3.0, GFS), which is
 * what the NOMADS grib filter returns for 10 m wind. Anything else is refused by
 * name, never guessed. Checked against real filter responses: HRRR 4x3 degrees,
 * 44 KB, 144 by 135 Lambert cells; RAP CONUS, 231 KB, 427 by 240 Lambert cells;
 * GFS CONUS, 75 KB, 237 by 105 quarter-degree cells.
 *
 * <p>Offsets follow the WMO FM 92 GRIB edition 2 tables; signed fields use GRIB's
 * sign-and-magnitude form (the top bit is the sign), not two's complement.
 */
public final class Grib2 {

    /** One decoded field. */
    public static final class Message {
        public final int discipline, category, number;
        /** Model run time, UTC millis. */
        public final long referenceTime;
        /** Hours after the run this field is valid for. */
        public final int forecastHours;
        /** The Lambert grid, when the message is on one; null for a lat/lon grid. */
        public final Lcc grid;
        /** The lat/lon lattice, when the message is on one; null for a Lambert grid. */
        public final LatLonGrid latLon;
        public final int nx, ny;
        /** Row-major, row 0 the first row in the file; {@link #jNorthUp} says which way rows run. */
        public final float[] values;
        /** True when successive rows go south to north (scanning mode bit 2 set). */
        public final boolean jNorthUp;

        Message(int discipline, int category, int number, long referenceTime, int forecastHours,
                Lcc grid, LatLonGrid latLon, int nx, int ny, float[] values, boolean jNorthUp) {
            this.discipline = discipline;
            this.category = category;
            this.number = number;
            this.referenceTime = referenceTime;
            this.forecastHours = forecastHours;
            this.grid = grid;
            this.latLon = latLon;
            this.nx = nx;
            this.ny = ny;
            this.values = values;
            this.jNorthUp = jNorthUp;
        }

        /** The value at column i, row j counted from the south, or NaN outside. */
        public float at(int i, int jFromSouth) {
            if (i < 0 || i >= nx || jFromSouth < 0 || jFromSouth >= ny)
                return Float.NaN;
            final int row = jNorthUp ? jFromSouth : ny - 1 - jFromSouth;
            return values[row * nx + i];
        }
    }

    private Grib2() {
    }

    public static List<Message> read(byte[] b) throws IOException {
        final List<Message> out = new ArrayList<>();
        int pos = 0;
        while (pos + 16 <= b.length) {
            if (b[pos] != 'G' || b[pos + 1] != 'R' || b[pos + 2] != 'I' || b[pos + 3] != 'B') {
                pos++;
                continue;
            }
            final int edition = u8(b, pos + 7);
            if (edition != 2)
                throw new IOException("GRIB edition " + edition + ", only 2 is read");
            final long total = u64(b, pos + 8);
            if (total < 16 || pos + total > b.length)
                throw new IOException("truncated GRIB message");
            out.add(readMessage(b, pos, (int) total));
            pos += (int) total;
        }
        if (out.isEmpty())
            throw new IOException("no GRIB message in " + b.length + " bytes");
        return out;
    }

    private static Message readMessage(byte[] b, int start, int total) throws IOException {
        final int discipline = u8(b, start + 6);
        long referenceTime = 0;
        int category = -1, number = -1, forecastHours = 0;
        Lcc grid = null;
        LatLonGrid latLon = null;
        int nx = 0, ny = 0;
        boolean jNorthUp = true;
        float ref = 0;
        int binScale = 0, decScale = 0, bits = 0;
        int npts = 0, packed = -1;
        boolean[] bitmap = null;
        float[] values = null;
        int p = start + 16;
        final int end = start + total;
        while (p + 5 <= end) {
            if (b[p] == '7' && b[p + 1] == '7' && b[p + 2] == '7' && b[p + 3] == '7')
                break;
            final int len = (int) u32(b, p);
            final int sec = u8(b, p + 4);
            if (len < 5 || p + len > end)
                throw new IOException("bad section " + sec + " length " + len);
            switch (sec) {
                case 1: {
                    final Calendar c = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
                    c.clear();
                    c.set(u16(b, p + 12), u8(b, p + 14) - 1, u8(b, p + 15), u8(b, p + 16),
                            u8(b, p + 17), u8(b, p + 18));
                    referenceTime = c.getTimeInMillis();
                    break;
                }
                case 3: {
                    final int template = u16(b, p + 12);
                    if (template != 30 && template != 0)
                        throw new IOException("grid template 3." + template
                                + ", only 3.0 (lat/lon) and 3.30 (Lambert) are read");
                    npts = (int) u32(b, p + 6);
                    if (template == 0) {
                        // Octets are 1-based in the WMO tables; octet N is at p + N - 1.
                        if (u32(b, p + 38) != 0)
                            throw new IOException("a basic angle other than degrees is not read");
                        nx = (int) u32(b, p + 30);
                        ny = (int) u32(b, p + 34);
                        final double a1 = s32(b, p + 46) / 1e6, o1 = normLon(s32(b, p + 50) / 1e6);
                        final double a2 = s32(b, p + 55) / 1e6, o2 = normLon(s32(b, p + 59) / 1e6);
                        final int scan0 = u8(b, p + 71);
                        if ((scan0 & 0x80) != 0 || (scan0 & 0x20) != 0)
                            throw new IOException("scanning mode " + scan0
                                    + " (reversed i or j-first) is not read");
                        if (o2 <= o1)
                            throw new IOException("a grid crossing the antimeridian is not read");
                        jNorthUp = (scan0 & 0x40) != 0;
                        latLon = new LatLonGrid(nx, ny, o1, Math.min(a1, a2), o2, Math.max(a1, a2));
                        break;
                    }
                    final int shape = u8(b, p + 14);
                    double radius = 6371229d;
                    if (shape == 1) {
                        final int scale = u8(b, p + 15);
                        radius = u32(b, p + 16) / Math.pow(10, scale);
                    } else if (shape != 6 && shape != 0) {
                        // 0 is the 6367470 m sphere; others are ellipsoids, close enough
                        // at grid resolution for wind, and named here so it is not silent.
                        radius = shape == 0 ? 6367470d : 6371229d;
                    }
                    nx = (int) u32(b, p + 30);
                    ny = (int) u32(b, p + 34);
                    final double la1 = s32(b, p + 38) / 1e6, lo1 = s32(b, p + 42) / 1e6;
                    final double laD = s32(b, p + 47) / 1e6, loV = s32(b, p + 51) / 1e6;
                    final double dx = u32(b, p + 55) / 1e3, dy = u32(b, p + 59) / 1e3;
                    final int scan = u8(b, p + 64);
                    final double latin1 = s32(b, p + 65) / 1e6, latin2 = s32(b, p + 69) / 1e6;
                    if ((scan & 0x80) != 0 || (scan & 0x20) != 0)
                        throw new IOException("scanning mode " + scan + " (reversed i or j-first) is not read");
                    jNorthUp = (scan & 0x40) != 0;
                    grid = new Lcc(radius, latin1, latin2, laD, loV, la1, lo1, dx, dy);
                    break;
                }
                case 4: {
                    final int template = u16(b, p + 7);
                    if (template != 0 && template != 8)
                        throw new IOException("product template 4." + template + " is not read");
                    category = u8(b, p + 9);
                    number = u8(b, p + 10);
                    final int unit = u8(b, p + 17);
                    final int ft = (int) s32(b, p + 18);
                    switch (unit) {
                        case 0: forecastHours = ft / 60; break;      // minutes
                        case 1: forecastHours = ft; break;           // hours
                        case 2: forecastHours = ft * 24; break;      // days
                        case 10: forecastHours = ft * 3; break;
                        case 11: forecastHours = ft * 6; break;
                        case 12: forecastHours = ft * 12; break;
                        default: throw new IOException("forecast time unit " + unit + " is not read");
                    }
                    break;
                }
                case 5: {
                    final int template = u16(b, p + 9);
                    if (template != 0)
                        throw new IOException("data template 5." + template + ", only 5.0 (simple packing) is read");
                    // Octets 6-9 count the points actually packed, which under a
                    // bitmap is the sea cells only (845 of a 1,333-cell wave grid,
                    // 2026-09-27). The grid's own count from section 3 is what the
                    // bitmap and the unpacked field are sized by; this one is only
                    // checked against it when there is no bitmap.
                    packed = (int) u32(b, p + 5);
                    ref = Float.intBitsToFloat((int) u32(b, p + 11));
                    binScale = s16(b, p + 15);
                    decScale = s16(b, p + 17);
                    bits = u8(b, p + 19);
                    break;
                }
                case 6: {
                    final int indicator = u8(b, p + 5);
                    if (indicator == 0) {
                        bitmap = new boolean[npts];
                        for (int i = 0; i < npts; i++)
                            bitmap[i] = (b[p + 6 + (i >> 3)] & (0x80 >> (i & 7))) != 0;
                    } else if (indicator != 255) {
                        throw new IOException("bitmap indicator " + indicator + " is not read");
                    }
                    break;
                }
                case 7: {
                    if (bitmap == null && packed >= 0 && packed != npts)
                        throw new IOException("section 5 packs " + packed + " points for a grid of " + npts);
                    values = unpack(b, p + 5, len - 5, npts, bitmap, ref, binScale, decScale, bits);
                    break;
                }
                default:
                    break;
            }
            p += len;
        }
        if ((grid == null && latLon == null) || values == null || nx * ny != values.length)
            throw new IOException("incomplete GRIB message (grid " + (grid != null || latLon != null)
                    + ", values " + (values == null ? "none" : values.length) + ", nx*ny " + (nx * ny) + ")");
        return new Message(discipline, category, number, referenceTime, forecastHours, grid,
                latLon, nx, ny, values, jNorthUp);
    }

    /** Simple packing: value = (R + X * 2^E) / 10^D, X read as {@code bits}-bit unsigned. */
    static float[] unpack(byte[] b, int off, int len, int npts, boolean[] bitmap, float ref,
            int binScale, int decScale, int bits) throws IOException {
        final float[] out = new float[npts];
        final double binFactor = Math.pow(2, binScale);
        final double decFactor = Math.pow(10, -decScale);
        if (bits == 0) {
            final float constant = (float) (ref * decFactor);
            for (int i = 0; i < npts; i++)
                out[i] = bitmap != null && !bitmap[i] ? Float.NaN : constant;
            return out;
        }
        if (bits > 31)
            throw new IOException(bits + " bits per value is not read");
        long bitPos = 0;
        final long bitEnd = (long) len * 8;
        for (int i = 0; i < npts; i++) {
            if (bitmap != null && !bitmap[i]) {
                out[i] = Float.NaN;
                continue;
            }
            if (bitPos + bits > bitEnd)
                throw new IOException("packed data ends early at point " + i);
            long x = 0;
            for (int k = 0; k < bits; k++) {
                final long bp = bitPos + k;
                final int bit = (b[off + (int) (bp >> 3)] >> (7 - (int) (bp & 7))) & 1;
                x = (x << 1) | bit;
            }
            bitPos += bits;
            out[i] = (float) ((ref + x * binFactor) * decFactor);
        }
        return out;
    }

    /** Longitudes come 0..360 from some models; the map wants -180..180. */
    private static double normLon(double lon) {
        while (lon > 180) lon -= 360;
        while (lon < -180) lon += 360;
        return lon;
    }

    private static int u8(byte[] b, int i) {
        return b[i] & 0xFF;
    }

    private static int u16(byte[] b, int i) {
        return (u8(b, i) << 8) | u8(b, i + 1);
    }

    private static long u32(byte[] b, int i) {
        return ((long) u16(b, i) << 16) | u16(b, i + 2);
    }

    private static long u64(byte[] b, int i) {
        return (u32(b, i) << 32) | u32(b, i + 4);
    }

    /** GRIB2 signed: top bit is the sign, the rest the magnitude. */
    private static int s16(byte[] b, int i) {
        final int v = u16(b, i);
        return (v & 0x8000) != 0 ? -(v & 0x7FFF) : v;
    }

    private static long s32(byte[] b, int i) {
        final long v = u32(b, i);
        return (v & 0x80000000L) != 0 ? -(v & 0x7FFFFFFFL) : v;
    }
}
