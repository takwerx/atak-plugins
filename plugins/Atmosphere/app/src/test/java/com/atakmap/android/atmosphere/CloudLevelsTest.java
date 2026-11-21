package com.atakmap.android.atmosphere;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.atakmap.android.atmosphere.data.ResponseMapper;
import com.atakmap.android.atmosphere.units.Quantity;
import com.atakmap.android.atmosphere.model.Reading;
import com.atakmap.android.atmosphere.model.Snapshot;
import com.atakmap.android.atmosphere.source.WxParam;
import com.atakmap.android.atmosphere.source.WxSourceDef;
import com.atakmap.android.atmosphere.source.WxSourceParser;
import com.atakmap.android.atmosphere.ui.CloudLevel;

import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;

/**
 * Low, mid and high cloud in the point forecast (ops #976, atmosphere #10). Open-Meteo
 * reports each deck as its own percentage next to total cloud cover; the NWS grid has
 * only the total. These tests pin that the bundled Open-Meteo definition asks for the
 * three decks and reads them, and that the pane can tell a deck from the total (the
 * total is drawn as the okta circle, a deck is a plain percentage).
 */
public class CloudLevelsTest {

    private static WxSourceDef openMeteo() throws Exception {
        final String json = new String(Files.readAllBytes(
                new File("src/main/assets/wx_sources/open-meteo.json").toPath()),
                StandardCharsets.UTF_8);
        final WxSourceParser.Result r = WxSourceParser.parse(json,
                WxSourceDef.Origin.BUNDLED, "open-meteo.json");
        assertTrue(r.errors.toString(), r.ok());
        return r.def;
    }

    @Test
    public void bundledOpenMeteoReadsEachDeck() throws Exception {
        final WxSourceDef def = openMeteo();
        for (String k : new String[] {"cloud_cover_low", "cloud_cover_mid", "cloud_cover_high"}) {
            final WxParam p = def.param(k);
            assertNotNull(k + " must be in the bundled Open-Meteo definition", p);
            assertEquals(Quantity.PERCENT, p.quantity);
            assertEquals("hourly." + k, p.seriesPath);
            assertEquals("current." + k, p.currentPath);
            assertTrue(k + " is on by default", p.defaultOn);
            assertTrue(Arrays.asList(p.requestGroups).contains("hourly"));
            assertTrue(Arrays.asList(p.requestGroups).contains("current"));
        }
        // Still one host: no new server for the user's firewall or FirstNet to learn.
        assertEquals("[api.open-meteo.com]", def.hosts().toString());
    }

    @Test
    public void decksAndTotalMapSeparately() throws Exception {
        final String body = "{"
                + "\"current\": {\"time\": \"2025-08-22T14:00\", \"cloud_cover\": 80,"
                + "  \"cloud_cover_low\": 10, \"cloud_cover_mid\": 55, \"cloud_cover_high\": 100},"
                + "\"hourly\": {\"time\": [\"2025-08-22T14:00\", \"2025-08-22T15:00\"],"
                + "  \"cloud_cover\": [80, 60],"
                + "  \"cloud_cover_low\": [10, 0], \"cloud_cover_mid\": [55, 20],"
                + "  \"cloud_cover_high\": [100, 90]}}";
        final Snapshot s = ResponseMapper.map(openMeteo(), body, 39.5, -120.25, 1_000_000L);

        assertEquals(10.0, current(s, "cloud_cover_low").value, 1e-6);
        assertEquals(55.0, current(s, "cloud_cover_mid").value, 1e-6);
        assertEquals(100.0, current(s, "cloud_cover_high").value, 1e-6);
        assertEquals("55%", current(s, "cloud_cover_mid")
                .format(com.atakmap.android.atmosphere.units.UnitSystem.METRIC));
        assertEquals(2, s.series.size());
        assertEquals(0.0, s.series.get(1).reading("cloud_cover_low").value, 1e-6);
        assertEquals(90.0, s.series.get(1).reading("cloud_cover_high").value, 1e-6);

        // The pane sorts by key and label: each deck is its own level, the total is none.
        assertEquals(CloudLevel.LOW, levelOf(current(s, "cloud_cover_low")));
        assertEquals(CloudLevel.MID, levelOf(current(s, "cloud_cover_mid")));
        assertEquals(CloudLevel.HIGH, levelOf(current(s, "cloud_cover_high")));
        assertNull("total cloud cover stays the okta circle",
                levelOf(current(s, "cloud_cover")));
    }

    @Test
    public void classifierNeedsAWholeWordAndTheWordCloud() {
        assertEquals(CloudLevel.LOW, CloudLevel.of("cloud_cover_low Low cloud"));
        assertEquals(CloudLevel.MID, CloudLevel.of("mid_cloud Mid cloud"));
        assertEquals(CloudLevel.HIGH, CloudLevel.of("high_cloud High cloud"));
        assertNull(CloudLevel.of("cloud_cover Cloud cover"));
        assertNull(CloudLevel.of("skyCover Sky cover"));
        assertNull("low alone is not a cloud level", CloudLevel.of("low_temperature Low"));
        assertNull("high as part of another word", CloudLevel.of("cloud_cover_highres Cloud"));
        assertNull(CloudLevel.of(null));
    }

    private static CloudLevel levelOf(Reading r) {
        return CloudLevel.of(r.key + " " + r.label);
    }

    private static Reading current(Snapshot s, String key) {
        for (Reading r : s.current)
            if (r.key.equals(key))
                return r;
        return null;
    }
}
