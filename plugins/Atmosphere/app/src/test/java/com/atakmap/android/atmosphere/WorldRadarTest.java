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
        // Cabo San Lucas, three degrees across: zoom 7 is the finest there is, and it fits.
        final WorldRadar.Plan cabo = WorldRadar.plan(-111.4, 21.4, -108.4, 24.4, 1024);
        assertEquals(7, cabo.zoom);
        assertTrue(cabo.toString(), cabo.tileCount() <= 4);
        assertEquals(546, cabo.outW);
        assertEquals(593, cabo.outH);
        assertEquals("https://tilecache.rainviewer.com/v2/radar/abc/512/7/" + cabo.tx0 + "/" + cabo.ty0
                + "/6/1_1.png", cabo.tileUrl("/v2/radar/abc", cabo.tx0, cabo.ty0));
        // Forty degrees of Mexico and the Gulf: zoom 4, under a thousand pixels, a few tiles.
        final WorldRadar.Plan gulf = WorldRadar.plan(-120, 10, -80, 40, 1024);
        assertEquals(4, gulf.zoom);
        assertTrue(gulf.toString(), gulf.tileCount() <= 9);
        assertEquals(910, gulf.outW);
        // The whole world: zoom 1, four tiles, the poles cut to Mercator's reach.
        final WorldRadar.Plan world = WorldRadar.plan(-180, -90, 180, 90, 1024);
        assertEquals(1, world.zoom);
        assertEquals(4, world.tileCount());
        assertEquals(85.0, world.north, 0.0);
        assertEquals(-85.0, world.south, 0.0);
        assertNull(WorldRadar.plan(10, 10, 10, 20, 1024));
    }

    /**
     * A region that is exactly one zoom-3 tile comes back as that tile resampled onto
     * a lon/lat quad: the top row is the tile's top row, the bottom row its bottom
     * row, and the middle latitude lands below the tile's middle because Mercator
     * stretches the north (row 296 of 512 for the tile spanning 41 N to 66.5 N).
     */
    @Test
    public void assemblyResamplesMercatorOntoTheQuad() {
        final double north = latOf(2 * T, 3), south = latOf(3 * T, 3);
        // At 600 px the finest zoom whose 45 degrees fit is 3 (512 px); 4 would be 1024.
        final WorldRadar.Plan p = WorldRadar.plan(-135, south, -90, north, 600);
        assertEquals(3, p.zoom);
        assertEquals(1, p.tileCount());
        assertEquals(1, p.tx0);
        assertEquals(2, p.ty0);
        assertEquals(T, p.outW);
        assertEquals(T, p.outH);
        final int[] tile = new int[T * T];
        for (int y = 0; y < T; y++)
            for (int x = 0; x < T; x++)
                tile[y * T + x] = 0xFF000000 | (y << 12) | x;
        final int[] out = WorldRadar.assemble(p, new int[][] { tile });
        assertEquals(T * T, out.length);
        assertEquals(tile[0], out[0]);
        assertEquals(tile[T - 1], out[T - 1]);
        assertEquals(tile[(T - 1) * T], out[(T - 1) * T]);
        final int midRow = (out[(T / 2) * T] >> 12) & 0xFFF;
        assertEquals(296, midRow);
        // Nothing where a tile is missing.
        final int[] none = WorldRadar.assemble(p, new int[][] { null });
        assertEquals(0, none[0]);
        assertEquals(0, none[none.length - 1]);
    }

    @Test
    public void tilesOutsideThePlanLeaveTheirPartClear() {
        final WorldRadar.Plan p = WorldRadar.plan(-120, 10, -80, 40, 1024);
        assertNotNull(p);
        final int[][] tiles = new int[p.tileCount()][];
        final int[] out = WorldRadar.assemble(p, tiles);
        for (int v : out)
            assertEquals(0, v);
    }

    /** The latitude at a Mercator pixel row, the inverse of the class's own projection. */
    private static double latOf(double y, int zoom) {
        final double n = (1 << zoom) * (double) T;
        return Math.toDegrees(Math.atan(Math.sinh(Math.PI * (1 - 2 * y / n))));
    }
}
