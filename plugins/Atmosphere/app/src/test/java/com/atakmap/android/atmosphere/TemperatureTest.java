package com.atakmap.android.atmosphere;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import com.atakmap.android.atmosphere.rain.NomadsTemperature;
import com.atakmap.android.atmosphere.rain.RainGrid;

import org.junit.Test;

import java.io.File;
import java.nio.ByteBuffer;
import java.nio.file.Files;

public class TemperatureTest {
    @Test
    public void requestUsesTwoMeterTemperature() {
        final String url = NomadsTemperature.url(1790488800000L, 6, -120, 15, -100, 30);
        assertTrue(url, url.contains("var_TMP=on&lev_2_m_above_ground=on"));
        assertTrue(url, url.contains("gfs.t06z.pgrb2.0p25.f006"));
    }

    @Test
    public void requestAtAltitudeNamesThePressureSurface() {
        final String url = NomadsTemperature.url(1790488800000L, 6, -120, 15, -100, 30,
                com.atakmap.android.atmosphere.wind.NomadsWind.Level.MB_850);
        assertTrue(url, url.contains("var_TMP=on&lev_850_mb=on"));
        // The ground rung is the 2 m temperature, whatever the wind calls it.
        assertEquals("lev_2_m_above_ground", NomadsTemperature.levelParam(
                com.atakmap.android.atmosphere.wind.NomadsWind.Level.AGL_10));
        assertEquals(com.atakmap.android.atmosphere.wind.NomadsWind.Level.AGL_10,
                NomadsTemperature.LEVELS[0]);
        for (com.atakmap.android.atmosphere.wind.NomadsWind.Level l : NomadsTemperature.LEVELS)
            assertTrue(l.name(), l != com.atakmap.android.atmosphere.wind.NomadsWind.Level.AGL_80);
        // The scale slides with height by the standard lapse, so the typical
        // temperature aloft takes the color the typical ground temperature does.
        assertEquals(0f, NomadsTemperature.scaleShift(com.atakmap.android.atmosphere.wind.NomadsWind.Level.AGL_10), 0f);
        assertEquals(1500 * 0.0065f, NomadsTemperature.scaleShift(com.atakmap.android.atmosphere.wind.NomadsWind.Level.MB_850), 0.001f);
        final float aloft = NomadsTemperature.scaleShift(com.atakmap.android.atmosphere.wind.NomadsWind.Level.MB_250);
        assertTrue(aloft > 60 && aloft < 75);
        assertNotEquals(NomadsTemperature.bandColor(-52f, false), NomadsTemperature.bandColor(-52f, false, aloft));
        assertNotEquals(NomadsTemperature.bandColor(-50f, false, aloft), NomadsTemperature.bandColor(-55f, false, aloft));
    }

    @Test
    public void colorsInterpolateAndMissingValuesAreTransparent() {
        assertEquals(0, NomadsTemperature.color(Float.NaN));
        assertEquals(0xC800C8FF, NomadsTemperature.color(0));
        assertNotEquals(NomadsTemperature.color(0), NomadsTemperature.color(5));
        assertNotEquals(NomadsTemperature.color(5), NomadsTemperature.color(10));
        assertEquals(NomadsTemperature.color(-80), NomadsTemperature.color(-120));
        assertEquals(NomadsTemperature.color(45), NomadsTemperature.color(80));
        // The upper air at 250 mb, -40 to -60, still steps by band.
        assertNotEquals(NomadsTemperature.bandColor(-42f, false), NomadsTemperature.bandColor(-52f, false));
        assertNotEquals(NomadsTemperature.bandColor(-52f, false), NomadsTemperature.bandColor(-62f, false));
    }

    @Test
    public void readsKelvinAsCelsiusAndPreservesValidTime() throws Exception {
        final byte[] body = Files.readAllBytes(new File("src/test/resources/gfs_prate_baja_box.grib2").toPath());
        final ByteBuffer buffer = ByteBuffer.wrap(body);
        int offset = 16;
        while (offset + 5 < body.length && body[offset] != '7') {
            final int length = buffer.getInt(offset);
            final int section = body[offset + 4] & 255;
            if (section == 4) {
                body[offset + 9] = 0;
                body[offset + 10] = 0;
            } else if (section == 5) {
                buffer.putFloat(offset + 11, 293.15f);
                buffer.putShort(offset + 15, (short) 0);
                buffer.putShort(offset + 17, (short) 0);
                body[offset + 19] = 0;
            }
            offset += length;
        }
        final RainGrid grid = NomadsTemperature.read(body);
        assertEquals(20f, grid.sample(22, -110), 0.001f);
        assertEquals(1790488800000L + 6 * 3_600_000L, grid.validTime);
        assertEquals(NomadsTemperature.bandColor(20, false), NomadsTemperature.render(grid, 4, 4)[0]);
        assertEquals(NomadsTemperature.bandColor(20, true), NomadsTemperature.render(grid, 4, 4, true)[0]);
        // One value on the whole grid still gives the legend a join to label.
        assertArrayEquals(new float[] { 20, 25 }, NomadsTemperature.bandEdges(grid, false), 0f);
        assertArrayEquals(new float[] { 65, 70 }, NomadsTemperature.bandEdges(grid, true), 0f);
    }

    @Test
    public void bandLabelsNameEveryBandInViewOnceAndSpaceOutBigPatches() throws Exception {
        final byte[] body = Files.readAllBytes(new File("src/test/resources/gfs_prate_baja_box.grib2").toPath());
        final ByteBuffer buffer = ByteBuffer.wrap(body);
        int offset = 16;
        while (offset + 5 < body.length && body[offset] != '7') {
            final int length = buffer.getInt(offset);
            final int section = body[offset + 4] & 255;
            if (section == 4) {
                body[offset + 9] = 0;
                body[offset + 10] = 0;
            } else if (section == 5) {
                buffer.putFloat(offset + 11, 293.15f);
                buffer.putShort(offset + 15, (short) 0);
                buffer.putShort(offset + 17, (short) 0);
                body[offset + 19] = 0;
            }
            offset += length;
        }
        final RainGrid flat = NomadsTemperature.read(body);
        // One band everywhere: one label on a small view, several spaced out on a big one.
        java.util.List<com.atakmap.android.atmosphere.rain.BandLabels.Label> few =
                com.atakmap.android.atmosphere.rain.BandLabels.place(flat, -115, 20, -110, 24, 4, 3, true);
        assertEquals(1, few.size());
        assertEquals(65, few.get(0).floor);
        assertTrue(few.get(0).lat > 20 && few.get(0).lat < 24);
        assertTrue(few.get(0).lon > -115 && few.get(0).lon < -110);
        java.util.List<com.atakmap.android.atmosphere.rain.BandLabels.Label> many =
                com.atakmap.android.atmosphere.rain.BandLabels.place(flat, -115, 20, -110, 24, 20, 12, true);
        assertTrue("got " + many.size(), many.size() >= 4);
        for (int i = 0; i < many.size(); i++)
            for (int j = i + 1; j < many.size(); j++)
                assertTrue(Math.abs(many.get(i).lat - many.get(j).lat) > 0.5
                        || Math.abs(many.get(i).lon - many.get(j).lon) > 0.5);

        // Two bands split down the middle: each named once, on its own side.
        final float[] rate = flat.rate;
        for (int y = 0; y < flat.ny; y++)
            for (int x = 0; x < flat.nx; x++)
                rate[y * flat.nx + x] = x < flat.nx / 2 ? 10f : 30f;
        final double mid = (flat.west + flat.east) / 2;
        java.util.List<com.atakmap.android.atmosphere.rain.BandLabels.Label> two =
                com.atakmap.android.atmosphere.rain.BandLabels.place(flat, flat.west, flat.south,
                        flat.east, flat.north, 8, 4, false);
        assertEquals(2, two.size());
        for (com.atakmap.android.atmosphere.rain.BandLabels.Label l : two)
            assertEquals(l.floor == 10 ? "west" : "east", l.lon < mid ? "west" : "east");
        assertEquals("65\u201370 \u00b0F", com.atakmap.android.atmosphere.rain.BandLabels.text(65, true));
        assertEquals("-5\u20130 \u00b0C", com.atakmap.android.atmosphere.rain.BandLabels.text(-5, false));
    }

    @Test
    public void bandsStepByFiveDegreesInTheUnitShown() {
        assertEquals(20f, NomadsTemperature.bandFloor(20f, false), 0f);
        assertEquals(20f, NomadsTemperature.bandFloor(24.9f, false), 0f);
        assertEquals(25f, NomadsTemperature.bandFloor(25f, false), 0f);
        assertEquals(-5f, NomadsTemperature.bandFloor(-0.1f, false), 0f);
        assertEquals(65f, NomadsTemperature.bandFloor(20f, true), 0f);     // 68 °F
        assertEquals(70f, NomadsTemperature.bandFloor(21.5f, true), 0f);   // 70.7 °F
        assertTrue(Float.isNaN(NomadsTemperature.bandFloor(Float.NaN, true)));

        assertEquals(NomadsTemperature.bandColor(20f, false), NomadsTemperature.bandColor(24f, false));
        assertNotEquals(NomadsTemperature.bandColor(24f, false), NomadsTemperature.bandColor(26f, false));
        assertEquals(0, NomadsTemperature.bandColor(Float.NaN, false));

        final float[] edges = { 60, 65, 70 };
        final int[] colors = NomadsTemperature.bandColors(edges, true);
        assertEquals(3, colors.length);
        for (int c : colors)
            assertEquals(0xff000000, c & 0xff000000);
        assertEquals(NomadsTemperature.bandColor(NomadsTemperature.toCelsius(62, true), true) | 0xff000000,
                colors[0]);
    }
}