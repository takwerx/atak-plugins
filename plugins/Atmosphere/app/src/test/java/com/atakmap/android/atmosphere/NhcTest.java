
package com.atakmap.android.atmosphere;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.atakmap.android.atmosphere.data.Nhc;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.util.List;

public class NhcTest {

    private static String resource(String name) throws IOException {
        try (InputStream in = NhcTest.class.getClassLoader().getResourceAsStream(name)) {
            assertTrue("missing test resource " + name, in != null);
            final ByteArrayOutputStream out = new ByteArrayOutputStream();
            final byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0)
                out.write(buf, 0, n);
            return new String(out.toByteArray(), Charset.forName("UTF-8"));
        }
    }

    @Test
    public void slotLayerIdsMatchTheLiveService() {
        // Read off the service's own layer listing on 2026-09-23. The blocks are 26
        // apart and every storm's layers sit at a fixed offset inside its block, so
        // the ids are arithmetic rather than a lookup -- but only if these hold.
        assertEquals(4, Nhc.baseLayer("AT1"));
        assertEquals(30, Nhc.baseLayer("AT2"));
        assertEquals(134, Nhc.baseLayer("EP1"));
        assertEquals(160, Nhc.baseLayer("EP2"));
        assertEquals(264, Nhc.baseLayer("CP1"));
        assertEquals(368, Nhc.baseLayer("CP5"));

        // AT1: 6 Forecast Points, 7 Forecast Track, 8 Forecast Cone, 9 Watch-Warning.
        assertEquals(6, Nhc.pointsLayer("AT1"));
        assertEquals(7, Nhc.trackLayer("AT1"));
        assertEquals(8, Nhc.coneLayer("AT1"));
        assertEquals(9, Nhc.watchLayer("AT1"));
        // EP2, the slot Polo was in when this was written.
        assertEquals(162, Nhc.pointsLayer("EP2"));
        assertEquals(163, Nhc.trackLayer("EP2"));
        assertEquals(164, Nhc.coneLayer("EP2"));

        assertEquals(-1, Nhc.baseLayer("EP6"));
        assertEquals(-1, Nhc.coneLayer(null));
        assertEquals(15, Nhc.SLOTS.length);
    }

    @Test
    public void theQueryAsksForGeoJsonInLatLon() throws IOException {
        final String u = Nhc.queryUrl(164);
        assertTrue(u, u.startsWith("https://" + Nhc.MAP_HOST + "/"));
        assertTrue(u, u.contains("/164/query?"));
        assertTrue(u, u.contains("f=geojson"));
        assertTrue(u, u.contains("outSR=4326"));
        assertTrue(u, u.contains("returnGeometry=true"));
        // Short enough to stay a GET: past about 2,000 characters an ArcGIS gateway
        // answers 404 rather than 414, which reads as a missing layer.
        assertTrue("query is " + u.length() + " chars", u.length() < 2000);
    }

    @Test
    public void theLiveFeedParsesIntoStorms() throws IOException {
        // A trimmed copy of the real feed from 2026-09-23, when three systems were
        // running in the eastern Pacific and nothing in the Atlantic.
        final List<Nhc.Storm> storms = Nhc.parseActive(resource("nhc_current_storms.json"));
        assertEquals(3, storms.size());

        final Nhc.Storm polo = storms.get(1);
        assertEquals("EP2", polo.bin);
        assertEquals("Polo", polo.name);
        assertEquals("Hurricane Polo", polo.display());
        assertEquals(130, polo.intensityKt);
        assertEquals(922, polo.pressureMb);
        assertEquals(4, polo.category());
        assertEquals(15.3, polo.latitude, 0.001);
        assertEquals(-101.4, polo.longitude, 0.001);
        // The join to the map service is the slot, not the name or the id.
        assertEquals(164, Nhc.coneLayer(polo.bin));

        final Nhc.Storm td = storms.get(2);
        assertEquals("Tropical Depression Fifteen-E", td.display());
        assertEquals(0, td.category());

        // No two-letter code reaches anything a crew reads.
        for (Nhc.Storm s : storms) {
            assertFalse(s.display(), s.display().contains(s.classification));
            assertFalse(s.display().isEmpty());
        }
    }

    @Test
    public void categoryFollowsTheSaffirSimpsonThresholds() {
        assertEquals(0, storm(63).category());
        assertEquals(1, storm(64).category());
        assertEquals(2, storm(83).category());
        assertEquals(3, storm(96).category());
        assertEquals(4, storm(113).category());
        assertEquals(5, storm(137).category());
    }

    @Test
    public void aFeedThatCannotBeReadIsNoStormsRatherThanACrash() {
        assertEquals(0, Nhc.parseActive(null).size());
        assertEquals(0, Nhc.parseActive("").size());
        assertEquals(0, Nhc.parseActive("not json at all").size());
        assertEquals(0, Nhc.parseActive("{}").size());
        assertEquals(0, Nhc.parseActive("{\"activeStorms\":[]}").size());
        // A slot the map service does not carry is skipped, not guessed at.
        assertEquals(0, Nhc.parseActive(
                "{\"activeStorms\":[{\"binNumber\":\"XX9\",\"name\":\"Nope\"}]}").size());
    }

    private static Nhc.Storm storm(int kt) {
        return Nhc.parseActive("{\"activeStorms\":[{\"binNumber\":\"AT1\",\"name\":\"X\","
                + "\"classification\":\"HU\",\"intensity\":\"" + kt + "\"}]}").get(0);
    }
}
