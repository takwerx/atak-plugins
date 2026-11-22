package com.atakmap.android.atmosphere;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.atakmap.android.atmosphere.rain.NomadsRain;
import com.atakmap.android.atmosphere.rain.RainGrid;

import org.junit.Test;

import java.io.File;
import java.nio.file.Files;

/**
 * The rain forecast read off a saved filter answer: a 20 by 15 degree box over Baja
 * and the Mexican mainland, 2026-09-27 06Z +6 h, with Hurricane Polo inside it. The
 * file carries two messages, the instantaneous rate and the 0-6 hour average.
 */
public class RainTest {

    /** 2026-09-27 06:00Z. */
    private static final long RUN_06Z = 1790488800000L;

    private static byte[] fixture() throws Exception {
        return Files.readAllBytes(new File("src/test/resources/gfs_prate_baja_box.grib2").toPath());
    }

    @Test
    public void readsTheInstantaneousRateAsMillimetersAnHour() throws Exception {
        final RainGrid g = NomadsRain.read(fixture());
        assertEquals(81, g.nx);
        assertEquals(61, g.ny);
        assertEquals(-120, g.west, 0.01);
        assertEquals(-100, g.east, 0.01);
        assertEquals(15, g.south, 0.01);
        assertEquals(30, g.north, 0.01);
        // The instantaneous message is valid at +6 h (the 06Z run, so 12Z); the
        // average's forecast time is the bucket's start and would read as 06Z.
        assertEquals(RUN_06Z + 6 * 3_600_000L, g.validTime);
        float max = 0;
        int wet = 0;
        for (float v : g.rate) {
            assertTrue("rate " + v, Float.isNaN(v) || (v >= 0 && v < 200));
            if (!Float.isNaN(v) && v > 0.25f)
                wet++;
            if (!Float.isNaN(v) && v > max)
                max = v;
        }
        // A hurricane in the box: somewhere it rains hard, and not everywhere.
        assertTrue("max " + max, max > 5f);
        assertTrue("wet " + wet, wet > 20 && wet < g.rate.length / 2);
        // Polo's center at 21.3 N 113.9 W has rain; the Sonoran desert at 30 N 112 W does not.
        assertTrue(g.sample(21.3, -113.9) > 0.5f);
        assertTrue(g.sample(30.0, -112.0) < 0.25f);
    }

    @Test
    public void bandsWordsAndTextsFollowTheForecasterScale() {
        assertEquals(-1, NomadsRain.band(Float.NaN));
        assertEquals(-1, NomadsRain.band(0.1f));          // a hundredth of an inch is drizzle
        assertEquals(0, NomadsRain.band(1f));             // 0.04 in/hr: light
        assertEquals(1, NomadsRain.band(5f));             // 0.2 in/hr: moderate
        assertEquals(2, NomadsRain.band(12f));            // 0.47 in/hr: heavy
        assertEquals(3, NomadsRain.band(30f));            // 1.2 in/hr: very heavy
        assertEquals(4, NomadsRain.band(60f));            // 2.4 in/hr: violent
        assertEquals("heavy rain", NomadsRain.words(12f));
        assertEquals("", NomadsRain.words(0.1f));
        assertEquals(0, NomadsRain.color(0.1f));
        assertTrue((NomadsRain.color(12f) >>> 24) > 0);
        assertEquals("0.47 in/hr", NomadsRain.rateText(12f, false));
        assertEquals("1.2 in/hr", NomadsRain.rateText(30f, false));
        assertEquals("6.4 mm/hr", NomadsRain.rateText(6.4f, true));
        assertEquals("30 mm/hr", NomadsRain.rateText(30f, true));
        assertEquals(5, NomadsRain.legendColors().length);
        assertEquals(4, NomadsRain.legendBreaks(false).length);
        assertEquals(0.1f, NomadsRain.legendBreaks(false)[0], 0.0001f);
        assertEquals(2.5f, NomadsRain.legendBreaks(true)[0], 0.0001f);
    }

    @Test
    public void thePictureIsClearWhereItIsDryAndColoredWhereItRains() throws Exception {
        final RainGrid g = NomadsRain.read(fixture());
        final int w = g.nx * 8, h = g.ny * 8;
        final int[] px = NomadsRain.render(g, w, h);
        assertEquals(w * h, px.length);
        final double rain = NomadsRain.rainPercent(px);
        assertTrue("rain " + rain, rain > 1 && rain < 50);
        // Row 0 is the north edge: the desert corner at 30 N 119 W is clear.
        assertEquals(0, px[(w / 20)]);
    }

    @Test
    public void theRequestNamesTheRunHourAndBox() {
        final long run = RUN_06Z;
        final String u = NomadsRain.url(run, 6, -120, 15, -100, 30);
        assertTrue(u, u.startsWith("https://nomads.ncep.noaa.gov/cgi-bin/filter_gfs_0p25.pl?dir=%2Fgfs.20260927%2F06%2Fatmos"
                + "&file=gfs.t06z.pgrb2.0p25.f006&var_PRATE=on&lev_surface=on&subregion="));
        assertTrue(u, u.endsWith("&toplat=30.000&leftlon=-120.000&rightlon=-100.000&bottomlat=15.000"));
        assertEquals(run, NomadsRain.latestRun(run + 5 * 3_600_000L + 1));
        assertEquals(run - 6 * 3_600_000L, NomadsRain.latestRun(run + 5 * 3_600_000L - 1));
    }
}
