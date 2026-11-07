package com.atakmap.android.atmosphere;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.atakmap.android.atmosphere.data.IemRadar;

import org.junit.Test;

/** The mesonet's past radar pictures as the relay keeps them: an hour's tiles, and colors read back to inches. */
public class IemRadarTest {

    @Test
    public void colorsReadBackToTheirAmounts() {
        // Index 4 (#0018ff) is 1 mm; the three blues below it are 0.25 to 0.75 mm.
        assertEquals(1 / 25.4, IemRadar.inches(0xFF0018FF), 1e-9);
        assertEquals(0.25 / 25.4, IemRadar.inches(0xFF0006FF), 1e-9);
        // Index 88 (#42fe00) is 22 mm; 168 (#fe0030) is 110 mm; 238 (#fea8fe) is 415 mm.
        assertEquals(22 / 25.4, IemRadar.inches(0xFF42FE00), 1e-9);
        assertEquals(110 / 25.4, IemRadar.inches(0xFFFE0030), 1e-9);
        assertEquals(415 / 25.4, IemRadar.inches(0xFFFEA8FE), 1e-9);
        assertEquals(0, IemRadar.inches(0xFF000000), 1e-9);
        assertTrue(Double.isNaN(IemRadar.inches(0xFF909090)));
        // A blended color takes the nearest one's amount.
        assertEquals(22 / 25.4, IemRadar.inches(0xFF43FD01), 1e-9);
    }

    @Test
    public void specNamesTheHourAtOrBeforeAndTheBox() {
        // 2026-10-11 20:57 UTC is the 20:00 picture.
        final long t = 1_791_752_220_000L;
        final String u = IemRadar.spec(24, t, -119.5, 33.5, -117.5, 35.0, 400, 300);
        assertEquals("https://mapdepot.takwerx.org/atmosphere/iem/radar/p24h/2026101120/index.json"
                + "#-119.5000,33.5000,-117.5000,35.0000,400,300", u);
        final IemRadar.Spec s = IemRadar.Spec.parse(u);
        assertEquals("https://mapdepot.takwerx.org/atmosphere/iem/radar/p24h/2026101120/index.json", s.indexUrl);
        assertEquals(-117.5, s.east, 1e-9);
        assertEquals(300, s.py);
        assertEquals("https://mapdepot.takwerx.org/atmosphere/iem/radar/p24h/2026101120/32_-120.png",
                s.tileUrl("32_-120"));
        assertTrue(IemRadar.spec(72, t, -119.5, 33.5, -117.5, 35.0, 400, 300).contains("/radar/p72h/"));
        assertTrue(IemRadar.spec(1, t, -119.5, 33.5, -117.5, 35.0, 400, 300).contains("/radar/p1h/"));
        // Any other picture is asked for as it is.
        assertNull(IemRadar.Spec.parse("https://mapservices.weather.noaa.gov/raster/rest/services/x?bbox=1"));
        assertNull(IemRadar.Spec.parse("https://mapdepot.takwerx.org/atmosphere/iem/radar/p24h/2026101120/index.json"));
        assertNull(IemRadar.Spec.parse(u.replace(",400,300", ",400,99999")));
    }

    @Test
    public void indexListsRainAndNoData() {
        final IemRadar.Index i = IemRadar.Index.parse(
                "{\"v\":1,\"rain\":[\"32_-118\",\"../x\"],\"nodata\":[\"20_-130\"]}");
        assertTrue(i.rain.contains("32_-118"));
        assertEquals(1, i.rain.size());
        assertTrue(i.noData.contains("20_-130"));
        assertNull(IemRadar.Index.parse("{\"error\":1}"));
    }

    @Test
    public void tilesAreTheRelaysCrops() {
        // The relay crops (x0, y0) = ((lon + 130) / 0.01, (55 - (lat + 2)) / 0.01), the top row 100 high.
        assertArrayEquals(new int[] { 1200, 1900, 200, 200 }, IemRadar.tileBox("34_-118"));
        assertArrayEquals(new int[] { 0, 0, 200, 100 }, IemRadar.tileBox("54_-130"));
        assertArrayEquals(new int[] { 6800, 3300, 200, 200 }, IemRadar.tileBox("20_-62"));
        // A box over Los Angeles: two tiles across, one down.
        final IemRadar.Plan p = IemRadar.plan(-119.0, 34.2, -117.0, 34.8, 200, 60);
        assertEquals(java.util.Arrays.asList("34_-118", "34_-120"), p.tiles());
    }

    @Test
    public void paintPutsEachTilePixelUnderItsPlace() {
        // One pixel a national pixel: the box is exactly tile 34_-118's 1 x 1 degree corner.
        final IemRadar.Plan p = IemRadar.plan(-118.0, 35.0, -117.0, 36.0, 100, 100);
        final int[] tile = new int[200 * 200];
        for (int y = 0; y < 200; y++)
            for (int x = 0; x < 200; x++)
                tile[y * 200 + x] = 0xFF000000 | (y << 8) | x;
        final int[] out = new int[100 * 100];
        IemRadar.paint(p, "34_-118", tile, 0, out);
        // The box's top left is the tile's top left (36 N); its bottom right is the tile's middle.
        assertEquals(0xFF000000, out[0]);
        assertEquals(0xFF000000 | (99 << 8) | 99, out[99 * 100 + 99]);
        // No data paints a flat color.
        final int[] grey = new int[100 * 100];
        IemRadar.paint(p, "34_-118", null, IemRadar.NO_DATA, grey);
        assertEquals(IemRadar.NO_DATA, grey[5050]);
        // A tile the box is not over paints nothing.
        final int[] none = new int[100 * 100];
        IemRadar.paint(p, "30_-100", null, IemRadar.NO_DATA, none);
        assertEquals(0, none[5050]);
    }

    @Test
    public void relayKeeps45Days() {
        final long now = 1_800_000_000_000L;
        assertEquals(IemRadar.hourOf(now - 45L * 24 * 3_600_000L) + 3_600_000L, IemRadar.since(now));
        assertEquals(1_791_662_400_000L, IemRadar.since(1_791_700_000_000L));
    }
}
