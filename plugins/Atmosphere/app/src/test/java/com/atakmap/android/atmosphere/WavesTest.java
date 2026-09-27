package com.atakmap.android.atmosphere;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.atakmap.android.atmosphere.waves.NomadsWaves;
import com.atakmap.android.atmosphere.waves.WaveGrid;

import org.junit.Test;

import java.io.File;
import java.nio.file.Files;

/** The wave model read off a saved filter answer: a 7 by 5 degree box off Southern California, 2026-09-27 00Z +12 h. */
public class WavesTest {

    private static byte[] fixture() throws Exception {
        return Files.readAllBytes(new File("src/test/resources/gfswave_socal_box.grib2").toPath());
    }

    @Test
    public void readsHeightPeriodDirectionAndTheFirstSwellTrain() throws Exception {
        final WaveGrid g = NomadsWaves.read(fixture());
        assertEquals(43, g.nx);
        assertEquals(31, g.ny);
        assertEquals(-123, g.west, 0.01);
        assertEquals(-116, g.east, 0.01);
        assertEquals(31, g.south, 0.01);
        assertEquals(36, g.north, 0.01);
        assertNotNull(g.period);
        assertNotNull(g.dir);
        assertNotNull(g.swellHeight);
        assertNotNull(g.swellDir);
        // Open water off Point Conception has waves; the Mojave does not.
        final float sea = g.sample(g.height, 34.0, -121.0);
        assertTrue("sea " + sea, sea > 0.2f && sea < 15f);
        assertTrue(Float.isNaN(g.sample(g.height, 35.0, -117.0)));
        final float from = g.nearestAt(g.dir, 34.0, -121.0);
        assertTrue("dir " + from, from >= 0 && from <= 360);
        final float period = g.sample(g.period, 34.0, -121.0);
        assertTrue("period " + period, period > 2 && period < 25);
        assertTrue(g.validTime > 0);
    }

    @Test
    public void seaStatesAndWords() {
        assertEquals(0, NomadsWaves.band(0.2f));
        assertEquals(1, NomadsWaves.band(0.5f));
        assertEquals(3, NomadsWaves.band(2.5f));
        assertEquals(7, NomadsWaves.band(20f));
        assertEquals(-1, NomadsWaves.band(Float.NaN));
        assertEquals("smooth", NomadsWaves.words(0.2f));
        assertEquals("rough", NomadsWaves.words(3f));
        assertEquals("", NomadsWaves.words(Float.NaN));
        assertEquals(0, NomadsWaves.color(Float.NaN));
        assertEquals(8, NomadsWaves.legendColors().length);
        assertEquals(7, NomadsWaves.legendBreaks(true).length);
        assertEquals(2f, NomadsWaves.legendBreaks(false)[0], 0);     // 0.5 m is 2 ft
        assertEquals("6 ft", NomadsWaves.heightText(1.83f, false));
        assertEquals("1.8 m", NomadsWaves.heightText(1.83f, true));
        assertEquals("2.0 ft", NomadsWaves.heightText(0.6f, false));
        assertEquals("", NomadsWaves.heightText(Float.NaN, false));
        assertEquals("N", NomadsWaves.compass(0));
        assertEquals("NW", NomadsWaves.compass(315));
        assertEquals("W", NomadsWaves.compass(281));
        assertEquals("SSW", NomadsWaves.compass(202));
        assertEquals("N", NomadsWaves.compass(359));
    }

    @Test
    public void urlGridsAndRuns() {
        final long run = 1790000000000L - 1790000000000L % (6 * 3_600_000L);
        final String u = NomadsWaves.url(NomadsWaves.Grid.FINE, run, 12, -123, 31, -116, 36);
        assertTrue(u, u.startsWith("https://nomads.ncep.noaa.gov/cgi-bin/filter_gfswave.pl?dir=%2Fgfs."));
        assertTrue(u, u.contains("%2Fwave%2Fgridded&file=gfswave.t"));
        assertTrue(u, u.contains("z.global.0p16.f012.grib2&var_HTSGW=on&var_DIRPW=on&var_PERPW=on&var_SWELL=on&var_SWDIR=on&var_SWPER=on&subregion=&toplat=36.000&leftlon=-123.000&rightlon=-116.000&bottomlat=31.000"));
        final String alaska = NomadsWaves.url(NomadsWaves.Grid.COARSE, run, 3, -160, 55, -140, 62);
        assertTrue(alaska, alaska.contains("global.0p25.f003.grib2"));
        assertEquals(NomadsWaves.Grid.FINE, NomadsWaves.forView(20, 40));
        assertEquals(NomadsWaves.Grid.COARSE, NomadsWaves.forView(50, 60));
        assertEquals(NomadsWaves.Grid.COARSE, NomadsWaves.forView(-20, 10));
        assertEquals(0, NomadsWaves.latestRun(run + 5 * 3_600_000L + 1) % (6 * 3_600_000L));
        assertEquals(run - 6 * 3_600_000L, NomadsWaves.previousRun(run));
    }

    @Test
    public void rendersSeaColoredAndLandClearWithArrows() throws Exception {
        final WaveGrid g = NomadsWaves.read(fixture());
        final int[] px = NomadsWaves.render(g, 43 * 8, 31 * 8);
        assertEquals(43 * 8 * 31 * 8, px.length);
        final double sea = NomadsWaves.seaPercent(px);
        assertTrue("sea " + sea, sea > 40 && sea < 95);
        boolean white = false;
        for (int p : px)
            if (p == 0xFFFFFFFF) { white = true; break; }
        assertTrue(white);
        // Land at the top right (the Mojave) is clear.
        assertEquals(0, px[4 * 43 * 8 + 43 * 8 - 4]);
        assertFalse(Float.isNaN(g.sample(g.height, 33.0, -120.0)));
    }
}
