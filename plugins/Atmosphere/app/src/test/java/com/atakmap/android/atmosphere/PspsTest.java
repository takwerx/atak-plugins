package com.atakmap.android.atmosphere;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.atakmap.android.atmosphere.data.Psps;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

public class PspsTest {

    private static String resource(String name) throws IOException {
        try (InputStream in = PspsTest.class.getClassLoader().getResourceAsStream(name)) {
            final ByteArrayOutputStream out = new ByteArrayOutputStream();
            final byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0)
                out.write(buf, 0, n);
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    @Test
    public void theRelayFileOfTheShutoffs() throws IOException {
        // 2026-10-16 16:51Z, cut to one warned county, two PG&E areas off, one SCE area
        // off and one watched; SDG&E was empty, so its area is made up in its shape.
        final Psps.Feed f = Psps.parse(resource("psps_relay_2026-10-16.json"));
        assertEquals(1792169366480L, f.countiesUpdated);
        assertEquals(58, f.countyRows);
        assertEquals(3, f.sources.size());
        assertEquals("SCE", f.sources.get(1).name);
        assertEquals(1792169509795L, f.sources.get(1).updated);
        assertEquals(6, f.areas.size());

        assertEquals(Psps.POSSIBLE, f.areas.get(0).kind);
        assertEquals("Kern", f.areas.get(0).county);

        final Psps.Area pge = f.areas.get(1);
        assertEquals(Psps.OFF, pge.kind);
        assertEquals("PG&E", pge.utility);
        assertEquals("Contra Costa", pge.county);
        assertEquals(1792077480000L, pge.since);
        assertEquals(1792191600000L, pge.restore);
        assertEquals(0, f.areas.get(2).restore);

        final Psps.Area sce = f.areas.get(3);
        assertEquals(Psps.OFF, sce.kind);
        assertEquals("SCE", sce.utility);
        assertEquals("NEWHALL-SAN FERNANDO-SUNSHINE_144", sce.circuit);
        assertEquals(0, sce.since);
        assertEquals(1792112400000L, sce.start);
        assertEquals(1792209600000L, sce.end);
        assertEquals(1792238400000L, sce.restore);
        assertEquals(Psps.WATCH, f.areas.get(4).kind);

        final Psps.Area sdge = f.areas.get(5);
        assertEquals("SDG&E", sdge.utility);
        assertEquals("Alpine", sdge.community);
        assertEquals("", sdge.county);
        assertEquals(312, sdge.customers);
        assertEquals("10/16/2026 6:00 AM", sdge.sinceText);
        assertEquals("10/17/2026 6:00 PM", sdge.restoreText);
        assertTrue(pge.geometry.optString("type").endsWith("Polygon"));
    }

    @Test
    public void anythingButTheFileIsNoAnswer() {
        // What 0.16 read as "Power off: none" while 528 areas were off.
        assertNull(Psps.parse("{\"error\":{\"code\":499,\"message\":\"Token Required\","
                + "\"messageCode\":\"GWM_0003\",\"details\":[\"Token Required\"]}}"));
        assertNull(Psps.parse("<html><body>502 Bad Gateway</body></html>"));
        assertNull(Psps.parse("{\"type\":\"FeatureCollection\",\"features\":[]}"));
        assertNull(Psps.parse(""));
        assertNull(Psps.parse(null));
    }

    @Test
    public void anEmptyFileIsAnAnswer() {
        final Psps.Feed f = Psps.parse("{\"type\":\"FeatureCollection\",\"psps\":{\"v\":2,"
                + "\"counties\":{\"updated\":5,\"rows\":58},\"sources\":[{\"name\":\"SCE\","
                + "\"updated\":7}]},\"features\":[]}");
        assertEquals(0, f.areas.size());
        assertEquals(5, f.countiesUpdated);
        assertEquals(7, f.sources.get(0).updated);
        // The first relay file, before SCE and SDG&E: no sources, so no answer.
        assertNull(Psps.parse("{\"type\":\"FeatureCollection\",\"psps\":{\"v\":1,"
                + "\"counties\":{\"updated\":5,\"rows\":58},\"areas\":{\"updated\":7}},\"features\":[]}"));
    }

    @Test
    public void urlAndColors() {
        assertEquals("https://mapdepot.takwerx.org/atmosphere/psps/psps.json", Psps.URL);
        assertEquals(0xFFD50000, Psps.color(Psps.OFF));
        assertEquals(0xFFFF6D00, Psps.color(Psps.WATCH));
        assertEquals(0xFF000000, Psps.textColor(Psps.WATCH));
        assertEquals(0xFF000000, Psps.textColor(Psps.POSSIBLE));
        assertEquals(0xFFFFFFFF, Psps.textColor(Psps.OFF));
    }
}
