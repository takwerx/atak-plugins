package com.atakmap.android.atmosphere;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.atakmap.android.atmosphere.data.Lightning;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

public class LightningTest {

    private static String resource(String name) throws IOException {
        try (InputStream in = LightningTest.class.getClassLoader().getResourceAsStream(name)) {
            final ByteArrayOutputStream out = new ByteArrayOutputStream();
            final byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0)
                out.write(buf, 0, n);
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    @Test
    public void theNewestFrameIsTheLastTimeListed() throws IOException {
        // Capabilities read 2026-10-01 02:30Z: frames 20:15Z to 02:30Z, every 15 min.
        assertEquals("2026-10-01T02:30:00.000Z",
                Lightning.newestFrame(resource("nowcoast_lightning_caps.xml")));
        assertNull(Lightning.newestFrame("<WMS_Capabilities/>"));
        assertNull(Lightning.newestFrame(null));
    }

    @Test
    public void greenNeverSurvivesTheRepaint() {
        // NOAA's densest class is pure green; ours is deep purple.
        assertEquals(0xFF5B0A91, Lightning.repaint(0xFF00FF00));
        assertEquals(0xFFFFE34D, Lightning.repaint(0xFFFFFFCC));
        assertEquals(0xFFFF9900, Lightning.repaint(0xFFFF7800));
        assertEquals(0xFFFF1A1A, Lightning.repaint(0xFFBC0025));
        assertEquals(0xFFD100D1, Lightning.repaint(0xFF4000C0));
        // Alpha is kept; transparent is untouched.
        assertEquals(0x80FF1A1A, Lightning.repaint(0x80FF0000));
        assertEquals(0x00123456, Lightning.repaint(0x00123456));
        // An off-palette pixel goes to its nearest class.
        assertEquals(0xFFFF1A1A, Lightning.repaint(0xFFF00505));
        for (int c : Lightning.BAND_COLORS) {
            final int g = (c >> 8) & 0xFF, r = (c >> 16) & 0xFF, b = c & 0xFF;
            assertFalse("a band reads as green", g > r && g > b);
        }
    }

    @Test
    public void theMapUrlNamesTheFrame() {
        final String u = Lightning.mapUrl(-106, 27, -96, 37, 1024, 1024, "2026-10-01T02:30:00.000Z");
        assertTrue(u.startsWith("https://nowcoast.noaa.gov/geoserver/lightning_detection/wms?"));
        assertTrue(u.contains("&layers=lightning_detection:ldn_lightning_strike_density"));
        assertTrue(u.contains("&bbox=-106.0000,27.0000,-96.0000,37.0000"));
        assertTrue(u.endsWith("&time=2026-10-01T02:30:00.000Z"));
        assertFalse(Lightning.mapUrl(-106, 27, -96, 37, 512, 512, null).contains("time="));
        assertEquals(5, Lightning.BANDS.length);
        assertEquals(5, Lightning.BAND_COLORS.length);
    }
}
