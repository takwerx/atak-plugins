package com.atakmap.android.atmosphere.overlay;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;

/**
 * Radar for the rest of the world: RainViewer's composite of the public weather
 * radars of some 150 countries, which is what the radar layer draws wherever no
 * agency mosaic sees -- Mexico south of the border radars, the Caribbean beyond
 * Puerto Rico, the oceans, Europe, everywhere (operator, 2026-09-27: "there is a
 * hurricane in Baja right now would love that radar", "like around the world", and
 * the decision to use RainViewer: "since i give this to the public safety community
 * i think we are safe for leveraging rain viewer"). RainViewer's public API is
 * free for personal, educational and small-scale community use, needs no key, asks
 * for aggressive caching and a visible credit "Weather data by RainViewer" with a
 * link to rainviewer.com, and offers no guarantee the data stays (read
 * 2026-09-27); the credit is on the layers page whenever this source is drawn.
 *
 * <p>The API is a JSON frame list and Web Mercator tile pyramids, one per frame,
 * to zoom 7 (past that the server answers a "Zoom Level Not Supported" plate).
 * The map layer draws a lon/lat quad, so the tiles under the region are fetched
 * and resampled row by row from Mercator onto the quad here. Pure Java on
 * purpose: everything but the fetch and the final bitmap is pinned by
 * {@code WorldRadarTest} on the JVM.
 */
public final class WorldRadar {

    public static final String API_HOST = "api.rainviewer.com";
    public static final String TILE_HOST = "tilecache.rainviewer.com";
    public static final String FRAMES_URL = "https://" + API_HOST + "/public/weather-maps.json";
    /** Where the credit points; the terms ask for the link, not only the name. */
    public static final String CREDIT_URL = "https://www.rainviewer.com/";
    /** The words in the credit line that carry the link. */
    public static final String CREDIT_HOST = "rainviewer.com";

    public static final int TILE_PX = 512;
    static final int MIN_ZOOM = 1;
    /** Measured 2026-09-27: zoom 8 and up return a plate, at 256 and 512 px alike. */
    public static final int MAX_ZOOM = 7;
    /** Mercator's reach. The picture stops here; nothing lives there anyway. */
    public static final double MAX_LAT = 85.0;
    /**
     * Color scheme 6 is NEXRAD Level III, the palette an NWS radar user reads, with
     * smoothing on and snow marked -- so the picture does not change character at
     * the edge of the NWS mosaics.
     */
    static final String STYLE = "6/1_1";

    private static final SimpleDateFormat ISO = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US);
    static {
        ISO.setTimeZone(TimeZone.getTimeZone("UTC"));
    }

    private WorldRadar() {
    }

    /** The frame list: ISO times oldest first, and the tile path each one is served under. */
    public static final class Frames {
        public final List<String> times;
        private final Map<String, String> paths;
        /** Why the list is what it is, when that is worth a log line; null when nothing is. */
        public final String note;

        Frames(List<String> times, Map<String, String> paths, String note) {
            this.times = Collections.unmodifiableList(times);
            this.paths = paths;
            this.note = note;
        }

        public String pathOf(String time) {
            return paths.get(time);
        }
    }

    /**
     * The past frames of {@code weather-maps.json}: the observed radar, ten minutes
     * apart over the last two hours. The nowcast frames are a forecast and stay out;
     * this layer is what the radars saw. The tiles are always asked of
     * {@link #TILE_HOST}, the host the operator allowed by name: the file names a
     * host too, and a host a server hands back is held to the one consented to
     * (the same rule the weather services follow), so a different one is logged
     * and not followed.
     */
    public static Frames parseFrames(String json) {
        final List<String> times = new ArrayList<>();
        final Map<String, String> paths = new LinkedHashMap<>();
        String note = null;
        if (json == null)
            return new Frames(times, paths, "no frame list");
        try {
            final JSONObject root = new JSONObject(json);
            final String host = root.optString("host", "");
            if (!host.isEmpty() && !host.equals("https://" + TILE_HOST))
                note = "frame list names another tile host; asking " + TILE_HOST + " anyway";
            final JSONObject radar = root.optJSONObject("radar");
            final JSONArray past = radar == null ? null : radar.optJSONArray("past");
            if (past == null)
                return new Frames(times, paths, "frame list has no past radar");
            for (int i = 0; i < past.length(); i++) {
                final JSONObject f = past.optJSONObject(i);
                if (f == null)
                    continue;
                final long epoch = f.optLong("time", 0);
                final String path = f.optString("path", "");
                if (epoch <= 0 || !path.startsWith("/") || path.contains("..")
                        || !path.matches("[A-Za-z0-9_/.-]+"))
                    continue;
                final String time;
                synchronized (ISO) {
                    time = ISO.format(new Date(epoch * 1000L));
                }
                if (paths.containsKey(time))
                    continue;
                times.add(time);
                paths.put(time, path);
            }
        } catch (JSONException e) {
            note = "frame list not readable: " + e.getMessage();
        }
        Collections.sort(times);
        return new Frames(times, paths, note);
    }

    // ---- tiles -----------------------------------------------------------------

    /** The tiles a region needs at the zoom that draws it at about the asked size. */
    public static final class Plan {
        public final int zoom;
        /** Tile ranges, inclusive. */
        public final int tx0, ty0, tx1, ty1;
        /** The region drawn, latitude clamped to Mercator's reach. */
        public final double west, south, east, north;
        /** The picture's size in pixels. */
        public final int outW, outH;

        Plan(int zoom, int tx0, int ty0, int tx1, int ty1, double west, double south,
                double east, double north, int outW, int outH) {
            this.zoom = zoom;
            this.tx0 = tx0;
            this.ty0 = ty0;
            this.tx1 = tx1;
            this.ty1 = ty1;
            this.west = west;
            this.south = south;
            this.east = east;
            this.north = north;
            this.outW = outW;
            this.outH = outH;
        }

        public int across() {
            return tx1 - tx0 + 1;
        }

        public int down() {
            return ty1 - ty0 + 1;
        }

        public int tileCount() {
            return across() * down();
        }

        /** Index into the tile array {@link #assemble} reads, row-major from the northwest tile. */
        public int slot(int tx, int ty) {
            return (ty - ty0) * across() + (tx - tx0);
        }

        public String tileUrl(String path, int tx, int ty) {
            return "https://" + TILE_HOST + path + "/" + TILE_PX + "/" + zoom + "/" + tx + "/" + ty
                    + "/" + STYLE + ".png";
        }

        @Override
        public String toString() {
            return String.format(Locale.US, "z%d tiles %d..%d,%d..%d (%d) over %.2f,%.2f..%.2f,%.2f -> %dx%d",
                    zoom, tx0, tx1, ty0, ty1, tileCount(), west, south, east, north, outW, outH);
        }
    }

    /**
     * The plan for a region: the highest zoom, up to {@link #MAX_ZOOM}, at which the
     * region is no more than twice {@code maxPx} in tile pixels, and a picture of at
     * most {@code maxPx} on its long side, the region's own shape. A zoom step
     * doubles the pixels, so the tiles land between one and two times the picture:
     * it is always drawn from at least as fine a source as it shows.
     *
     * <p>The first rule was "the region fits in {@code maxPx}", which left the
     * picture between half and all of it and usually near half: over Central
     * America on 2026-09-27 a 12-degree region came from zoom 5 at 571 px, and the
     * operator asked whether that was the best there was. Zoom 6 is 1,140 px of
     * source for a 1,024 px picture. The cost is tiles, at most a five-by-five of
     * 512s for a square region and usually a four-by-three, which the overlay
     * fetches four at a time.
     */
    public static Plan plan(double west, double south, double east, double north, int maxPx) {
        south = Math.max(-MAX_LAT, south);
        north = Math.min(MAX_LAT, north);
        west = Math.max(-180, west);
        east = Math.min(180, east);
        if (east <= west || north <= south)
            return null;
        final int limit = 2 * maxPx;
        int zoom = MIN_ZOOM;
        for (int z = MAX_ZOOM; z >= MIN_ZOOM; z--) {
            final double w = xPx(east, z) - xPx(west, z);
            final double h = yPx(south, z) - yPx(north, z);
            if (w <= limit && h <= limit) {
                zoom = z;
                break;
            }
        }
        final int n = 1 << zoom;
        // The first pixel the region covers and the last, so an edge that lands on a
        // tile boundary (a region that is exactly a tile) does not drag the next tile in.
        final int tx0 = clamp(firstPx(xPx(west, zoom)) / TILE_PX, 0, n - 1);
        final int tx1 = clamp(lastPx(xPx(east, zoom)) / TILE_PX, 0, n - 1);
        final int ty0 = clamp(firstPx(yPx(north, zoom)) / TILE_PX, 0, n - 1);
        final int ty1 = clamp(lastPx(yPx(south, zoom)) / TILE_PX, 0, n - 1);
        final double w = xPx(east, zoom) - xPx(west, zoom);
        final double h = yPx(south, zoom) - yPx(north, zoom);
        final double scale = Math.min(1.0, maxPx / Math.max(w, h));
        final int outW = clamp((int) Math.round(w * scale), 64, maxPx);
        final int outH = clamp((int) Math.round(h * scale), 64, maxPx);
        return new Plan(zoom, tx0, ty0, tx1, ty1, west, south, east, north, outW, outH);
    }

    /**
     * For each column of the picture, the Mercator pixel it is drawn from; the same
     * on every row. Worked out once per frame and handed to {@link #paintTile}.
     */
    public static int[] columns(Plan p) {
        final int worldPx = (1 << p.zoom) * TILE_PX;
        final int[] gx = new int[p.outW];
        final double lonSpan = p.east - p.west;
        for (int i = 0; i < p.outW; i++) {
            final double lon = p.west + (i + 0.5) * lonSpan / p.outW;
            gx[i] = clamp((int) Math.floor(xPx(lon, p.zoom)), 0, worldPx - 1);
        }
        return gx;
    }

    /**
     * For each row of the picture, the Mercator pixel row it is drawn from. The
     * picture's rows are even steps of latitude, which is what the lon/lat quad the
     * map draws expects; Mercator stretches the north, so they are not even steps
     * of the tiles.
     */
    public static int[] rows(Plan p) {
        final int worldPx = (1 << p.zoom) * TILE_PX;
        final int[] gy = new int[p.outH];
        final double latSpan = p.north - p.south;
        for (int j = 0; j < p.outH; j++) {
            final double lat = p.north - (j + 0.5) * latSpan / p.outH;
            gy[j] = clamp((int) Math.floor(yPx(lat, p.zoom)), 0, worldPx - 1);
        }
        return gy;
    }

    /**
     * Copy one tile's share of the picture into {@code out}, nearest pixel. Tiles
     * are painted as they arrive, so a frame holds one tile's pixels at a time
     * rather than all of them: a four-by-four of 512s held at once is 16 MB of the
     * Java heap per frame, which is the churn that stalls a small phone.
     */
    public static void paintTile(Plan p, int[] gx, int[] gy, int tx, int ty, int[] tile, int[] out) {
        if (tile == null)
            return;
        // Rows and columns are monotonic, so this tile's share is one run of each.
        int j0 = -1, j1 = -1;
        for (int j = 0; j < gy.length; j++) {
            if (gy[j] / TILE_PX == ty) {
                if (j0 < 0)
                    j0 = j;
                j1 = j;
            }
        }
        int i0 = -1, i1 = -1;
        for (int i = 0; i < gx.length; i++) {
            if (gx[i] / TILE_PX == tx) {
                if (i0 < 0)
                    i0 = i;
                i1 = i;
            }
        }
        if (j0 < 0 || i0 < 0)
            return;
        for (int j = j0; j <= j1; j++) {
            final int src = (gy[j] % TILE_PX) * TILE_PX;
            final int dst = j * p.outW;
            for (int i = i0; i <= i1; i++)
                out[dst + i] = tile[src + gx[i] % TILE_PX];
        }
    }

    /**
     * The whole picture from tiles already in hand, indexed by {@link Plan#slot};
     * a missing tile (null) leaves its part clear. The overlay paints tile by tile
     * with {@link #paintTile}; this is the same done at once, for the tests.
     */
    public static int[] assemble(Plan p, int[][] tiles) {
        final int[] out = new int[p.outW * p.outH];
        final int[] gx = columns(p), gy = rows(p);
        for (int ty = p.ty0; ty <= p.ty1; ty++)
            for (int tx = p.tx0; tx <= p.tx1; tx++)
                paintTile(p, gx, gy, tx, ty, tiles[p.slot(tx, ty)], out);
        return out;
    }

    /** Web Mercator pixel x of a longitude at a zoom, in {@link #TILE_PX} tiles. */
    static double xPx(double lon, int zoom) {
        return (lon + 180.0) / 360.0 * (1 << zoom) * TILE_PX;
    }

    /** Web Mercator pixel y of a latitude at a zoom; 0 at the top of the world. */
    static double yPx(double lat, int zoom) {
        final double phi = Math.toRadians(Math.max(-MAX_LAT, Math.min(MAX_LAT, lat)));
        final double y = (1 - Math.log(Math.tan(phi) + 1 / Math.cos(phi)) / Math.PI) / 2;
        return y * (1 << zoom) * TILE_PX;
    }

    /** The first whole pixel at or past a Mercator coordinate, within rounding. */
    private static int firstPx(double v) {
        return (int) Math.floor(v + 1e-6);
    }

    /** The last pixel a Mercator coordinate still touches, within rounding. */
    private static int lastPx(double v) {
        return Math.max(0, (int) Math.ceil(v - 1e-6) - 1);
    }

    private static int clamp(int v, int lo, int hi) {
        return v < lo ? lo : Math.min(v, hi);
    }
}
