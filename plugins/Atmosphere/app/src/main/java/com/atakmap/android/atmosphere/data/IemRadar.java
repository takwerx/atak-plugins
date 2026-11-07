package com.atakmap.android.atmosphere.data;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;
import java.util.TreeSet;

/**
 * The NWS radar rain estimate (MRMS) as it was at a past hour: the Iowa Environmental
 * Mesonet keeps every hour's 1, 24, 48 and 72 hour totals as one national picture, and
 * takwerx's relay cuts each into 2-degree tiles and keeps them 45 days (notes repo,
 * tools/iem_radar.py), where the NWS service holds only the current hour (operator,
 * 2026-10-14: "can that overlay map also have a date and time picker?"). Through 0.17
 * each phone asked the mesonet for a picture of its view, and the mesonet turns away an
 * address that asks fast.
 *
 * <p>The tiles are the national picture's own pixels, 0.01 degree, in the mesonet's
 * color ramp. Each of its 256 colors stands for one amount (its lookup table,
 * rasters.php?rid=6, read 2026-10-14; the three smallest amounts are drawn in blues the
 * table lists as black), so each pixel is read back to inches and painted in the NWS key
 * the rest of Rain Totals uses.
 *
 * <p>No Android types; tested.
 */
public final class IemRadar {

    /** The stretches kept, hours, and their product names. */
    public static final int[] HOURS = { 1, 24, 48, 72 };
    private static final String[] PRODUCTS = { "p1h", "p24h", "p48h", "p72h" };
    private static final long HOUR = 3_600_000L;

    /** The national picture: 0.01 degree pixels, 55 N 130 W at its top left. */
    static final int WIDTH = 7000, HEIGHT = 3500, TILE_PX = 200;
    private static final double WEST = -130, NORTH = 55, PER_DEGREE = 100;
    /** The relay's first radar hour: its backfill reached 2026-10-10 20:00 UTC. */
    static final long SINCE_MS = 1_791_662_400_000L;
    /** How a pixel outside the radar reads: the ramp's last color, no data. */
    public static final int NO_DATA = 0xFF909090;

    /** The mesonet's colors, by index. */
    static final int[] PALETTE = {
            0x000000, 0x0006FF, 0x000CFF, 0x0012FF, 0x0018FF, 0x001EFF, 0x0024FF, 0x002AFF,
            0x0030FF, 0x0036FF, 0x003CFF, 0x0042FF, 0x0048FF, 0x004EFF, 0x0054FF, 0x005AFF,
            0x0060FF, 0x0066FF, 0x006CFF, 0x0072FF, 0x0078FF, 0x007EFF, 0x0084FF, 0x008AFF,
            0x0090FF, 0x0096FF, 0x009CFF, 0x00A2FF, 0x00A8FF, 0x00AEFF, 0x00B4FF, 0x00BAFF,
            0x00C0FF, 0x00C6FF, 0x00CCFF, 0x00D2FF, 0x00D8FF, 0x00FEF0, 0x00FEEA, 0x00FEE4,
            0x00FEDE, 0x00FED8, 0x00FED2, 0x00FECC, 0x00FEC6, 0x00FEC0, 0x00FEBA, 0x00FEB4,
            0x00FEAE, 0x00FEA8, 0x00FEA2, 0x00FE9C, 0x00FE96, 0x00FE90, 0x00FE8A, 0x00FE84,
            0x00FE7E, 0x00FE78, 0x00FE72, 0x00FE6C, 0x00FE66, 0x00FE60, 0x00FE5A, 0x00FE54,
            0x00FE4E, 0x00FE48, 0x00FE42, 0x00FE3C, 0x00FE36, 0x00FE30, 0x00FE2A, 0x00FE24,
            0x00FE1E, 0x00FE18, 0x00FE12, 0x00FE0C, 0x00FE06, 0x00FE00, 0x06FE00, 0x0CFE00,
            0x12FE00, 0x18FE00, 0x1EFE00, 0x24FE00, 0x2AFE00, 0x30FE00, 0x36FE00, 0x3CFE00,
            0x42FE00, 0x48FE00, 0x4EFE00, 0x54FE00, 0x5AFE00, 0x60FE00, 0x66FE00, 0x6CFE00,
            0x72FE00, 0x78FE00, 0x7EFE00, 0x84FE00, 0x8AFE00, 0x90FE00, 0x96FE00, 0x9CFE00,
            0xA2FE00, 0xA8FE00, 0xAEFE00, 0xB4FE00, 0xBAFE00, 0xC0FE00, 0xC6FE00, 0xCCFE00,
            0xD2FE00, 0xD8FE00, 0xDEFE00, 0xE4FE00, 0xEAF000, 0xFEEA00, 0xFEE400, 0xFEDE00,
            0xFED800, 0xFED200, 0xFECC00, 0xFEC600, 0xFEC000, 0xFEBA00, 0xFEB400, 0xFEAE00,
            0xFEA800, 0xFEA200, 0xFE9C00, 0xFE9600, 0xFE9000, 0xFE8A00, 0xFE8400, 0xFE7E00,
            0xFE7800, 0xFE7200, 0xFE6C00, 0xFE6600, 0xFE6000, 0xFE5A00, 0xFE5400, 0xFE4E00,
            0xFE4800, 0xFE4200, 0xFE3C00, 0xFE3600, 0xFE3000, 0xFE2A00, 0xFE2400, 0xFE1E00,
            0xFE1800, 0xFE1200, 0xFE0C00, 0xFE0600, 0xFE0000, 0xFE0000, 0xFE0000, 0xFE0000,
            0xFE0000, 0xFE0006, 0xFE000C, 0xFE0012, 0xFE0018, 0xFE001E, 0xFE0024, 0xFE002A,
            0xFE0030, 0xFE0036, 0xFE003C, 0xFE0042, 0xFE0048, 0xFE004E, 0xFE0054, 0xFE005A,
            0xFE0060, 0xFE0066, 0xFE006C, 0xFE0072, 0xFE0078, 0xFE007E, 0xFE0084, 0xFE008A,
            0xFE0090, 0xFE0096, 0xFE009C, 0xFE00A2, 0xFE00A8, 0xFE00AE, 0xFE00B4, 0xFE00BA,
            0xFE00C0, 0xFE00C6, 0xFE00CC, 0xFE00D2, 0xFE00FE, 0xFE04FE, 0xFE08FE, 0xFE0CFE,
            0xFE10FE, 0xFE14FE, 0xFE18FE, 0xFE1CFE, 0xFE20FE, 0xFE24FE, 0xFE28FE, 0xFE2CFE,
            0xFE30FE, 0xFE34FE, 0xFE38FE, 0xFE3CFE, 0xFE40FE, 0xFE44FE, 0xFE48FE, 0xFE4CFE,
            0xFE50FE, 0xFE54FE, 0xFE58FE, 0xFE5CFE, 0xFE60FE, 0xFE64FE, 0xFE68FE, 0xFE6CFE,
            0xFE70FE, 0xFE74FE, 0xFE78FE, 0xFE7CFE, 0xFE80FE, 0xFE84FE, 0xFE88FE, 0xFE8CFE,
            0xFE90FE, 0xFE94FE, 0xFE98FE, 0xFE9CFE, 0xFEA0FE, 0xFEA4FE, 0xFEA8FE, 0xFEACFE,
            0xFEB0FE, 0xFEB4FE, 0xFEB8FE, 0xFEBCFE, 0xFEC0FE, 0xFEC4FE, 0xFEC8FE, 0xFECCFE,
            0xFED0FE, 0xFED4FE, 0xFED8FE, 0xFEDCFE, 0xFEE0FE, 0xFEE4FE, 0xFEE8FE, 0x909090,
    };

    private static final Map<Integer, Integer> INDEX = new HashMap<>();
    static {
        // The first index wins where two share a color; only black does, below 1 mm.
        for (int i = PALETTE.length - 1; i >= 0; i--)
            INDEX.put(PALETTE[i], i);
    }

    /** The oldest hour the relay holds. */
    public static long since(long now) {
        return Math.max(SINCE_MS, hourOf(now - IemRelay.KEEP_MS) + HOUR);
    }

    private static String product(int hours) {
        for (int i = 0; i < HOURS.length; i++)
            if (HOURS[i] == hours)
                return PRODUCTS[i];
        return PRODUCTS[1];
    }

    /** "2026101720": the picture of the hour at or before the moment, UTC. */
    private static String hourName(long millis) {
        final java.util.Calendar c = java.util.Calendar.getInstance(TimeZone.getTimeZone("UTC"));
        c.setTimeInMillis(hourOf(millis));
        return String.format(Locale.US, "%04d%02d%02d%02d", c.get(java.util.Calendar.YEAR),
                c.get(java.util.Calendar.MONTH) + 1, c.get(java.util.Calendar.DAY_OF_MONTH),
                c.get(java.util.Calendar.HOUR_OF_DAY));
    }

    /** The hour's index: which tiles hold rain and which lie outside the radar. */
    public static String indexUrl(int hours, long endMs) {
        return IemRelay.BASE + "radar/" + product(hours) + "/" + hourName(endMs) + "/index.json";
    }

    static String tileUrl(String indexUrl, String tile) {
        return indexUrl.substring(0, indexUrl.lastIndexOf('/') + 1) + tile + ".png";
    }

    /**
     * What a picture of a box is, as one string, so the overlay asks for it as it asks
     * for any picture: the hour's index, then the box and its size after a '#', which
     * no request carries.
     */
    public static String spec(int hours, long endMs, double west, double south, double east, double north, int px,
            int py) {
        return indexUrl(hours, endMs)
                + String.format(Locale.US, "#%.4f,%.4f,%.4f,%.4f,%d,%d", west, south, east, north, px, py);
    }

    /** A picture to build from the relay's tiles. */
    public static final class Spec {
        public final String indexUrl;
        public final double west, south, east, north;
        public final int px, py;

        private Spec(String indexUrl, double west, double south, double east, double north, int px, int py) {
            this.indexUrl = indexUrl;
            this.west = west;
            this.south = south;
            this.east = east;
            this.north = north;
            this.px = px;
            this.py = py;
        }

        /** The spec a {@link #spec} string is, or null for any other URL. */
        public static Spec parse(String s) {
            if (s == null || !s.startsWith(IemRelay.BASE + "radar/"))
                return null;
            final int hash = s.indexOf('#');
            if (hash < 0 || !s.substring(0, hash).matches(".*/radar/p\\d{1,2}h/\\d{10}/index\\.json"))
                return null;
            final String[] f = s.substring(hash + 1).split(",", -1);
            if (f.length != 6)
                return null;
            try {
                final double w = Double.parseDouble(f[0]), so = Double.parseDouble(f[1]), e = Double.parseDouble(f[2]),
                        n = Double.parseDouble(f[3]);
                final int px = Integer.parseInt(f[4]), py = Integer.parseInt(f[5]);
                if (!(e > w) || !(n > so) || px < 1 || py < 1 || px > 2048 || py > 2048)
                    return null;
                return new Spec(s.substring(0, hash), w, so, e, n, px, py);
            } catch (NumberFormatException ex) {
                return null;
            }
        }

        public String tileUrl(String tile) {
            return IemRadar.tileUrl(indexUrl, tile);
        }
    }

    /** An hour's index: the tiles with rain, and those outside the radar; any other tile is dry. */
    public static final class Index {
        public final Set<String> rain, noData;

        private Index(Set<String> rain, Set<String> noData) {
            this.rain = rain;
            this.noData = noData;
        }

        /** {"v": 1, "rain": [tile, ...], "nodata": [tile, ...]}, or null. */
        public static Index parse(String json) {
            try {
                final JSONObject o = new JSONObject(json);
                final JSONArray r = o.optJSONArray("rain"), n = o.optJSONArray("nodata");
                if (r == null || n == null)
                    return null;
                return new Index(names(r), names(n));
            } catch (Exception e) {
                return null;
            }
        }

        private static Set<String> names(JSONArray a) {
            final Set<String> out = new HashSet<>();
            for (int i = 0; i < a.length(); i++) {
                final String t = a.optString(i, "");
                if (t.matches("\\d{2}_-\\d{2,3}"))
                    out.add(t);
            }
            return out;
        }
    }

    /**
     * Where each pixel of a box's picture (a plain lon/lat quad, px across) falls in
     * the national picture: its column for each of the picture's columns, its row for
     * each row, -1 outside.
     */
    public static final class Plan {
        public final int px, py;
        final int[] gx, gy;

        private Plan(int px, int py, int[] gx, int[] gy) {
            this.px = px;
            this.py = py;
            this.gx = gx;
            this.gy = gy;
        }

        /** The tiles any pixel falls in. */
        public List<String> tiles() {
            final TreeSet<String> out = new TreeSet<>();
            int lastX = -1;
            for (int x : gx) {
                if (x < 0 || x / TILE_PX == lastX)
                    continue;
                lastX = x / TILE_PX;
                int lastY = -2;
                for (int y : gy) {
                    if (y < 0 || rowOf(y) == lastY)
                        continue;
                    lastY = rowOf(y);
                    out.add(tileOf(x, y));
                }
            }
            return new ArrayList<>(out);
        }
    }

    public static Plan plan(double west, double south, double east, double north, int px, int py) {
        final int[] gx = new int[px], gy = new int[py];
        for (int i = 0; i < px; i++) {
            final double lon = west + (i + 0.5) * (east - west) / px;
            final int x = (int) Math.floor((lon - WEST) * PER_DEGREE);
            gx[i] = x < 0 || x >= WIDTH ? -1 : x;
        }
        for (int j = 0; j < py; j++) {
            final double lat = north - (j + 0.5) * (north - south) / py;
            final int y = (int) Math.floor((NORTH - lat) * PER_DEGREE);
            gy[j] = y < 0 || y >= HEIGHT ? -1 : y;
        }
        return new Plan(px, py, gx, gy);
    }

    /** The tile rows: the first is the top 100 px (54 to 55 N), every other 200. */
    private static int rowOf(int y) {
        return y < 100 ? -1 : (y - 100) / TILE_PX;
    }

    /** The tile a national pixel is in, by its south-west corner, as the relay names them. */
    static String tileOf(int x, int y) {
        final int lon = (int) WEST + 2 * (x / TILE_PX);
        final int lat = y < 100 ? 54 : 52 - 2 * ((y - 100) / TILE_PX);
        return lat + "_" + lon;
    }

    /** A tile's top left in the national picture, and its size: {x0, y0, width, height}. */
    public static int[] tileBox(String tile) {
        final String[] f = tile.split("_");
        final int lat = Integer.parseInt(f[0]), lon = Integer.parseInt(f[1]);
        final int x0 = (lon - (int) WEST) * 100, y0 = lat >= 54 ? 0 : (53 - lat) * 100;
        return new int[] { x0, y0, TILE_PX, lat >= 54 ? 100 : TILE_PX };
    }

    /**
     * One tile into the picture: every pixel of the plan that falls in it takes the
     * tile's pixel, or {@code color} when {@code pixels} is null (no data, say).
     */
    public static void paint(Plan p, String tile, int[] pixels, int color, int[] out) {
        final int[] b = tileBox(tile);
        for (int j = 0; j < p.py; j++) {
            final int y = p.gy[j] - b[1];
            if (p.gy[j] < 0 || y < 0 || y >= b[3])
                continue;
            for (int i = 0; i < p.px; i++) {
                final int x = p.gx[i] - b[0];
                if (p.gx[i] < 0 || x < 0 || x >= b[2])
                    continue;
                out[j * p.px + i] = pixels == null ? color : pixels[y * b[2] + x];
            }
        }
    }

    /** The picture's hour for a moment: the top of the hour at or before it. */
    public static long hourOf(long millis) {
        return (millis / 3_600_000L) * 3_600_000L;
    }

    /** Millimeters for an index: 0.25 mm steps to 25 mm, 1.25 to 125, then 5; NaN for 255, no data. */
    static double mm(int index) {
        if (index <= 0)
            return 0;
        if (index <= 100)
            return index * 0.25;
        if (index <= 180)
            return 25 + (index - 100) * 1.25;
        if (index <= 254)
            return 125 + (index - 180) * 5;
        return Double.NaN;
    }

    private static final Map<Integer, Double> NEAREST = new HashMap<>();

    /**
     * Inches for a pixel's color (RGB, alpha ignored): its index's amount, or the
     * nearest color's where a server's scaling blended one in. NaN for no data.
     */
    public static synchronized double inches(int rgb) {
        final int c = rgb & 0xFFFFFF;
        final Integer at = INDEX.get(c);
        if (at != null)
            return mm(at) / 25.4;
        final Double hit = NEAREST.get(c);
        if (hit != null)
            return hit;
        int best = 0;
        long bestD = Long.MAX_VALUE;
        for (int i = 0; i < PALETTE.length; i++) {
            final int p = PALETTE[i];
            final long dr = ((p >> 16) & 0xFF) - ((c >> 16) & 0xFF), dg = ((p >> 8) & 0xFF) - ((c >> 8) & 0xFF),
                    db = (p & 0xFF) - (c & 0xFF);
            final long d = dr * dr + dg * dg + db * db;
            if (d < bestD) {
                bestD = d;
                best = i;
            }
        }
        final double v = mm(best) / 25.4;
        if (NEAREST.size() < 4096)
            NEAREST.put(c, v);
        return v;
    }

    private IemRadar() {
    }
}
