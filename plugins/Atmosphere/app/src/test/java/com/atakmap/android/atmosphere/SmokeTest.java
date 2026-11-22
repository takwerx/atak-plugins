package com.atakmap.android.atmosphere;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.atakmap.android.atmosphere.smoke.NomadsSmoke;
import com.atakmap.android.atmosphere.smoke.NomadsSmoke.Height;
import com.atakmap.android.atmosphere.smoke.SmokeGrid;
import com.atakmap.android.atmosphere.wind.NomadsWind.Model;

import org.junit.Test;

import java.io.File;
import java.nio.file.Files;
import java.util.Calendar;
import java.util.TimeZone;

/**
 * The smoke against a real NOMADS grib filter response: HRRR MASSDEN at 8 m and COLMD
 * in one file, 4 by 3 degrees over Southern California (33-36 N, 120-116 W), the
 * 2026-09-23 12Z run at f01. That day the ground there read 0.4 ug/m3 at the median
 * and 16 at the most: a little smoke, some of it moderate.
 */
public class SmokeTest {

    private static byte[] socal() throws Exception {
        return Files.readAllBytes(new File("src/test/resources/hrrr_smoke_socal.grib2").toPath());
    }

    private static float max(SmokeGrid g) {
        float m = Float.NEGATIVE_INFINITY;
        for (float v : g.values)
            if (!Float.isNaN(v))
                m = Math.max(m, v);
        return m;
    }

    @Test
    public void readsTheGroundInMicrogramsPerCubicMeter() throws Exception {
        final SmokeGrid g = NomadsSmoke.read(socal(), Height.GROUND);
        final float top = max(g);
        // The file says 1.62e-8 kg/m3; a scale slip of 1000 either way fails this.
        assertTrue("max " + top, top > 10 && top < 25);
        final Calendar c = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
        c.clear();
        c.set(2026, Calendar.SEPTEMBER, 23, 13, 0, 0);
        assertEquals(c.getTimeInMillis(), g.validTime);
    }

    @Test
    public void readsTheSkyFromTheSameResponse() throws Exception {
        final SmokeGrid sky = NomadsSmoke.read(socal(), Height.SKY);
        final SmokeGrid ground = NomadsSmoke.read(socal(), Height.GROUND);
        // Two different fields, not the first message twice.
        assertTrue(max(sky) != max(ground));
        assertTrue("sky max " + max(sky), max(sky) > 0 && max(sky) < 1000);
    }

    @Test
    public void theGridCoversTheBoxAtTheModelsOwnResolution() throws Exception {
        final SmokeGrid g = NomadsSmoke.read(socal(), Height.GROUND);
        assertTrue(g.west <= -119.9 && g.east >= -116.1);
        assertTrue(g.south <= 33.1 && g.north >= 35.9);
        // 144 Lambert columns in the file; the lattice keeps them.
        assertEquals(144, g.nx);
        assertTrue(g.contains(34.0, -117.5));
        assertTrue(!Float.isNaN(g.sample(34.0, -117.5)));
        assertTrue(Float.isNaN(g.sample(40.0, -117.5)));
    }

    @Test
    public void theGroundBandsAreTheAqiBreakpoints() {
        final Height h = Height.GROUND;
        assertEquals(-1, h.band(4.9f));
        assertEquals(-1, h.band(Float.NaN));
        assertEquals(0, h.band(9.0f));          // good, drawn as haze
        assertEquals(1, h.band(9.1f));          // moderate
        assertEquals(1, h.band(35.4f));
        assertEquals(2, h.band(35.5f));         // unhealthy for sensitive groups
        assertEquals(3, h.band(125.4f));        // unhealthy
        assertEquals(4, h.band(225.4f));        // very unhealthy
        assertEquals(5, h.band(2840f));         // hazardous: the plume measured 2026-09-24
        assertEquals("unhealthy for sensitive groups", h.words(42f));
        assertEquals("little or no smoke", h.words(1f));
        assertArrayEquals(new float[] { 9, 35, 55, 125, 225 }, h.legendBreaks(), 0f);
        assertEquals(h.legendColors().length, h.legendBreaks().length + 1);
        assertEquals(0, h.color(1f));
    }

    @Test
    public void theSkyIsWordsAtTheEndsNotNumbers() {
        assertEquals(0, Height.SKY.legendBreaks().length);
        assertEquals("thick smoke overhead", Height.SKY.words(150f));
        assertEquals("little or no smoke overhead", Height.SKY.words(3.6f));
    }

    @Test
    public void everyZoomInTheLower48IsHrrr() {
        // A fire's worth of map.
        assertEquals(Model.HRRR, NomadsSmoke.forView(-121, 38, -119, 39.5, Height.GROUND));
        // The West at once: still HRRR, so the plume keeps its shape as the map zooms.
        assertEquals(Model.HRRR, NomadsSmoke.forView(-125, 30, -100, 49, Height.GROUND));
        assertEquals(Model.HRRR, NomadsSmoke.forView(-125, 30, -100, 49, Height.SKY));
        // Alaska: nothing here carries smoke.
        assertNull(NomadsSmoke.forView(-152, 60, -148, 63, Height.GROUND));
        // A few states at most are asked for; wider views are drawn for their middle.
        assertEquals(25.0, NomadsSmoke.maxSpanLon(Model.HRRR), 0);
        assertEquals(18.0, NomadsSmoke.maxSpanLat(Model.HRRR), 0);
    }

    @Test
    public void theRequestAsksForTheSmokeInTheWindsFiles() {
        final Calendar c = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
        c.clear();
        c.set(2026, Calendar.SEPTEMBER, 24, 0, 0, 0);
        final String ground = NomadsSmoke.url(Model.HRRR, Height.GROUND, c.getTimeInMillis(),
                48, -124, 36, -116, 42);
        assertTrue(ground, ground.contains("filter_hrrr_2d.pl?dir=%2Fhrrr.20260924%2Fconus"));
        assertTrue(ground, ground.contains("&file=hrrr.t00z.wrfsfcf48.grib2"));
        assertTrue(ground, ground.contains("&var_MASSDEN=on&lev_8_m_above_ground=on&subregion="));
        assertTrue(ground, ground.contains("&toplat=42.000&leftlon=-124.000"));
        final String sky = NomadsSmoke.url(Model.HRRR, Height.SKY, c.getTimeInMillis(), 1,
                -120, 33, -116, 36);
        assertTrue(sky, sky.contains("&var_COLMD=on&lev_entire_atmosphere_"));
        final String rap = NomadsSmoke.url(Model.RAP, Height.GROUND, c.getTimeInMillis(), 1,
                -132, 21, -60, 52);
        assertTrue(rap, rap.contains("rap.t00z.awp130pgrbf01.grib2&var_MASSDEN=on"));
    }

    @Test
    public void thePictureIsNorthUpAndClearWhereTheAirIs() throws Exception {
        final SmokeGrid g = NomadsSmoke.read(socal(), Height.GROUND);
        final int w = g.nx * 2, h = g.ny * 2;
        final int[] px = NomadsSmoke.render(g, Height.GROUND, w, h);
        assertEquals(w * h, px.length);
        final double smoky = NomadsSmoke.smokyPercent(px);
        // A 16 ug/m3 day draws some smoke and leaves most of the map clear.
        assertTrue("smoky " + smoky, smoky > 0 && smoky < 50);
        // Row 0 is the north edge: its first pixel is the north-west corner's value.
        final float nw = g.sample(g.north - (g.north - g.south) / h / 2,
                g.west + (g.east - g.west) / w / 2);
        assertEquals(Height.GROUND.color(nw), px[0]);
    }
}
