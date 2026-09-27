package com.atakmap.android.atmosphere;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.atakmap.android.atmosphere.overlay.WorldRadar;

import org.junit.Test;

/** The world radar's frame list, tile plan and Mercator-to-lon/lat assembly, pinned on the JVM. */
public class WorldRadarTest {

    private static final int T = WorldRadar.TILE_PX;

    @Test
    public void framesArePastRadarOldestFirstAndTheHostIsOurs() {
        final WorldRadar.Frames f = WorldRadar.parseFrames("{\"version\":\"2.0\",\"generated\":1790523600,"
                + "\"host\":\"https://tilecache.rainviewer.com\",\"radar\":{"
                + "\"past\":[{\"time\":1790523000,\"path\":\"/v2/radar/5d7f67e9705a\"},"
                + "{\"time\":1790522400,\"path\":\"/v2/radar/1790522400\"},"
                + "{\"time\":1790521800,\"path\":\"/v2/radar/../etc\"}],"
                + "\"nowcast\":[{\"time\":1790523600,\"path\":\"/v2/radar/nowcast_abc\"}]},"
                + "\"satellite\":{\"infrared\":[]}}");
        assertEquals(2, f.times.size());
        assertEquals("2026-09-27T15:20:00Z", f.times.get(0));
        assertEquals("2026-09-27T15:30:00Z", f.times.get(1));
        assertEquals("/v2/radar/5d7f67e9705a", f.pathOf("2026-09-27T15:30:00Z"));
        assertNull(f.pathOf("2026-09-27T15:40:00Z"));
        // A frame list naming another host still gets its tiles asked of the one allowed.
        final WorldRadar.Frames other = WorldRadar.parseFrames("{\"host\":\"https://evil.example\","
                + "\"radar\":{\"past\":[{\"time\":1790523000,\"path\":\"/v2/radar/x\"}]}}");
        final WorldRadar.Plan p = WorldRadar.plan(-111.4, 21.4, -108.4, 24.4, 1024);
        assertTrue(p.tileUrl(other.pathOf("2026-09-27T15:30:00Z"), p.tx0, p.ty0)
                .startsWith("https://tilecache.rainviewer.com/v2/radar/x/512/7/"));
        assertTrue(WorldRadar.parseFrames("not json").times.isEmpty());
        assertTrue(WorldRadar.parseFrames(null).times.isEmpty());
    }

    @Test
    public void aCityGetsTheFinestZoomAndAContinentACoarseOne() {
        // Cabo San Lucas, three degrees across: zoom 7 is the finest there is, drawn 1:1.
        final WorldRadar.Plan cabo = WorldRadar.plan(-111.4, 21.4, -108.4, 24.4, 1024);
        assertEquals(7, cabo.zoom);
        assertTrue(cabo.toString(), cabo.tileCount() <= 4);
        assertEquals(546, cabo.outW);
        assertEquals(593, cabo.outH);
        assertEquals("https://tilecache.rainviewer.com/v2/radar/abc/512/7/" + cabo.tx0 + "/" + cabo.ty0
                + "/6/1_1.png", cabo.tileUrl("/v2/radar/abc", cabo.tx0, cabo.ty0));
        // The region the phone drew over Central America on 2026-09-27 at zoom 5 and
        // 571 px: now zoom 6, 1,140 px of source for a 1,024 px picture, 12 tiles.
        final WorldRadar.Plan cam = WorldRadar.plan(-93.76, 5.57, -81.23, 17.34, 1024);
        assertEquals(6, cam.zoom);
        assertEquals(12, cam.tileCount());
        assertEquals(1024, cam.outW);
        assertEquals(983, cam.outH);
        // Forty degrees of Mexico and the Gulf: zoom 5, the picture capped at 1,024
        // on its long side and keeping its shape.
        final WorldRadar.Plan gulf = WorldRadar.plan(-120, 10, -80, 40, 1024);
        assertEquals(5, gulf.zoom);
        assertEquals(16, gulf.tileCount());
        assertEquals(1024, gulf.outW);
        assertEquals(862, gulf.outH);
        // The whole world: zoom 2, sixteen tiles, the poles cut to Mercator's reach.
        final WorldRadar.Plan world = WorldRadar.plan(-180, -90, 180, 90, 1024);
        assertEquals(2, world.zoom);
        assertEquals(16, world.tileCount());
        assertEquals(85.0, world.north, 0.0);
        assertEquals(-85.0, world.south, 0.0);
        assertNull(WorldRadar.plan(10, 10, 10, 20, 1024));
    }

    /**
     * A region that is exactly one zoom-3 tile, drawn at 300 px, comes back as that
     * tile resampled onto a lon/lat quad: the top row from near the tile's top, the
     * bottom row from its bottom, and the middle latitude from below the tile's
     * middle, because Mercator stretches the north (row 296 of 512 for the tile
     * spanning 41 N to 66.5 N).
     */
    @Test
    public void assemblyResamplesMercatorOntoTheQuad() {
        final double north = latOf(2 * T, 3), south = latOf(3 * T, 3);
        // At 300 px the finest zoom whose 45 degrees fit in twice that is 3 (512 px).
        final WorldRadar.Plan p = WorldRadar.plan(-135, south, -90, north, 300);
        assertEquals(3, p.zoom);
        assertEquals(1, p.tileCount());
        assertEquals(1, p.tx0);
        assertEquals(2, p.ty0);
        assertEquals(300, p.outW);
        assertEquals(300, p.outH);
        final int[] tile = new int[T * T];
        for (int y = 0; y < T; y++)
            for (int x = 0; x < T; x++)
                tile[y * T + x] = 0xFF000000 | (y << 12) | x;
        final int[] out = WorldRadar.assemble(p, new int[][] { tile });
        assertEquals(300 * 300, out.length);
        assertEquals(1, row(out[0]));
        assertEquals(0, col(out[0]));
        assertEquals(511, col(out[299]));
        assertEquals(511, row(out[299 * 300]));
        assertEquals(296, row(out[150 * 300]));
        // Nothing where a tile is missing.
        final int[] none = WorldRadar.assemble(p, new int[][] { null });
        assertEquals(0, none[0]);
        assertEquals(0, none[none.length - 1]);
    }

    /** Painting tile by tile gives the same picture as painting all at once. */
    @Test
    public void paintingTileByTileMatchesTheWhole() {
        final WorldRadar.Plan p = WorldRadar.plan(-93.76, 5.57, -81.23, 17.34, 1024);
        final int[][] tiles = new int[p.tileCount()][];
        for (int ty = p.ty0; ty <= p.ty1; ty++)
            for (int tx = p.tx0; tx <= p.tx1; tx++) {
                final int[] t = new int[T * T];
                java.util.Arrays.fill(t, 0xFF000000 | (tx << 8) | ty);
                tiles[p.slot(tx, ty)] = t;
            }
        final int[] whole = WorldRadar.assemble(p, tiles);
        final int[] byTile = new int[p.outW * p.outH];
        final int[] gx = WorldRadar.columns(p), gy = WorldRadar.rows(p);
        for (int ty = p.ty1; ty >= p.ty0; ty--)          // any order
            for (int tx = p.tx0; tx <= p.tx1; tx++)
                WorldRadar.paintTile(p, gx, gy, tx, ty, tiles[p.slot(tx, ty)], byTile);
        assertTrue(java.util.Arrays.equals(whole, byTile));
        for (int v : whole)
            assertTrue(v != 0);
    }

    private static int row(int argb) {
        return (argb >> 12) & 0xFFF;
    }

    private static int col(int argb) {
        return argb & 0xFFF;
    }

    /** The latitude at a Mercator pixel row, the inverse of the class's own projection. */
    private static double latOf(double y, int zoom) {
        final double n = (1 << zoom) * (double) T;
        return Math.toDegrees(Math.atan(Math.sinh(Math.PI * (1 - 2 * y / n))));
    }
}
