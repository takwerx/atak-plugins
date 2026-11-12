package com.atakmap.android.atmosphere;

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
    public void colorsInterpolateAndMissingValuesAreTransparent() {
        assertEquals(0, NomadsTemperature.color(Float.NaN));
        assertEquals(0x9c32b8d0, NomadsTemperature.color(0));
        assertNotEquals(NomadsTemperature.color(0), NomadsTemperature.color(5));
        assertNotEquals(NomadsTemperature.color(5), NomadsTemperature.color(10));
        assertEquals(NomadsTemperature.color(-40), NomadsTemperature.color(-80));
        assertEquals(NomadsTemperature.color(50), NomadsTemperature.color(80));
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
        assertEquals(NomadsTemperature.color(20), NomadsTemperature.render(grid, 4, 4)[0]);
    }
}