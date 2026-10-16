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
    public void theRelayFileOfThePgeShutoff() throws IOException {
        // 2026-10-16 16:25Z, cut to one warned county and three of the 529 areas off.
        final Psps.Feed f = Psps.parse(resource("psps_relay_2026-10-16.json"));
        assertEquals(1792167573719L, f.countiesUpdated);
        assertEquals(58, f.countyRows);
        assertEquals(1792167096446L, f.areasUpdated);
        assertEquals(4, f.areas.size());
        assertEquals(Psps.POSSIBLE, f.areas.get(0).kind);
        assertEquals("Alameda", f.areas.get(0).county);
        final Psps.Area off = f.areas.get(1);
        assertEquals(Psps.OFF, off.kind);
        assertEquals("Contra Costa", off.county);
        assertEquals("PG&E", off.utility);
        assertEquals(1, off.customers);
        assertEquals(1792077480000L, off.since);
        assertEquals(1792191600000L, off.restore);
        // The one area with no restore estimate.
        assertEquals(0, f.areas.get(3).restore);
        assertTrue(off.geometry.optString("type").endsWith("Polygon"));
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
        final Psps.Feed f = Psps.parse("{\"type\":\"FeatureCollection\",\"psps\":{\"v\":1,"
                + "\"counties\":{\"updated\":5,\"rows\":58},\"areas\":{\"updated\":7}},\"features\":[]}");
        assertEquals(0, f.areas.size());
        assertEquals(5, f.countiesUpdated);
        assertEquals(7, f.areasUpdated);
    }

    @Test
    public void urlAndColors() {
        assertEquals("https://mapdepot.takwerx.org/atmosphere/psps/psps.json", Psps.URL);
        assertEquals(0xFFD50000, Psps.color(Psps.OFF));
        assertEquals(0xFF000000, Psps.textColor(Psps.POSSIBLE));
        assertEquals(0xFFFFFFFF, Psps.textColor(Psps.OFF));
    }
}
