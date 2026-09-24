package com.atakmap.android.atmosphere;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.atakmap.android.atmosphere.data.GeoRings;
import com.atakmap.android.atmosphere.data.NwsAlerts;
import com.atakmap.android.atmosphere.data.NwsHazards;

import org.json.JSONObject;
import org.junit.Test;

import java.io.File;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;

/**
 * NWS warnings against the night's real feed (2026-09-24 about 05Z, six alerts kept and
 * their text shortened): the Medford Red Flag Warning and a Reno Fire Weather Watch,
 * both zone-based with no polygon; a gale off Alaska; a flood warning with a polygon of
 * its own; a Small Craft Advisory and a test message that are not drawn. And the Red
 * Flag's own fire zone, CAZ285, as api.weather.gov served it.
 */
public class NwsAlertsTest {

    private static String read(String name) throws Exception {
        return new String(Files.readAllBytes(new File("src/test/resources/" + name).toPath()),
                StandardCharsets.UTF_8);
    }

    private static NwsAlerts.Alert find(List<NwsAlerts.Alert> all, String event) {
        for (NwsAlerts.Alert a : all)
            if (a.event.equals(event))
                return a;
        return null;
    }

    @Test
    public void drawsWarningsAndWatchesAndDropsTheRest() throws Exception {
        final List<NwsAlerts.Alert> all = NwsAlerts.parse(read("nws_alerts.json"));
        assertEquals(4, all.size());
        assertNull(find(all, "Small Craft Advisory"));
        assertNull(find(all, "Test Message"));
    }

    @Test
    public void theRedFlagIsFireWeatherAndDrawnFromItsFireZone() throws Exception {
        final NwsAlerts.Alert rfw = find(NwsAlerts.parse(read("nws_alerts.json")),
                "Red Flag Warning");
        assertNotNull(rfw);
        assertEquals(NwsAlerts.Group.FIRE, rfw.group);
        assertNull(rfw.geometry);
        assertEquals(1, rfw.zones.size());
        assertEquals("https://api.weather.gov/zones/fire/CAZ285", rfw.zones.get(0));
        assertTrue(rfw.until() > 0);
        assertEquals(0xFFFF1493, rfw.color());
    }

    @Test
    public void aGaleOffAlaskaIsMarineAndAFloodIsLand() throws Exception {
        final List<NwsAlerts.Alert> all = NwsAlerts.parse(read("nws_alerts.json"));
        assertEquals(NwsAlerts.Group.MARINE, find(all, "Gale Warning").group);
        final NwsAlerts.Alert flood = find(all, "Flood Warning");
        assertEquals(NwsAlerts.Group.LAND, flood.group);
        assertNotNull(flood.geometry);
    }

    @Test
    public void theMostUrgentIsLastSoItDrawsOnTop() throws Exception {
        final List<NwsAlerts.Alert> all = NwsAlerts.parse(read("nws_alerts.json"));
        for (int i = 1; i < all.size(); i++)
            assertTrue(all.get(i - 1).priority() >= all.get(i).priority());
        // NWS ranks Red Flag 52, Flood Warning 39, Gale Warning 48, Fire Weather Watch 96.
        assertEquals("Fire Weather Watch", all.get(0).event);
        assertEquals("Flood Warning", all.get(all.size() - 1).event);
    }

    @Test
    public void theHazardTableIsNwssOwn() {
        assertEquals(111, NwsHazards.size());
        assertEquals(1, NwsHazards.priority("Tsunami Warning"));
        assertEquals(52, NwsHazards.priority("Red Flag Warning"));
        assertEquals(0xFFFFDEAD, NwsHazards.color("Fire Weather Watch"));
        assertEquals(NwsHazards.UNRANKED, NwsHazards.priority("Made Up Warning"));
        assertEquals(NwsHazards.UNKNOWN_COLOR, NwsHazards.color("Made Up Warning"));
    }

    @Test
    public void onlyApiWeatherGovZonesAreFollowed() throws Exception {
        assertTrue(NwsAlerts.isZoneUrl("https://api.weather.gov/zones/fire/CAZ285"));
        assertTrue(NwsAlerts.isZoneUrl("https://api.weather.gov/zones/county/INC003"));
        assertFalse(NwsAlerts.isZoneUrl("http://api.weather.gov/zones/fire/CAZ285"));
        assertFalse(NwsAlerts.isZoneUrl("https://api.weather.gov.evil.example/zones/fire/CAZ285"));
        assertFalse(NwsAlerts.isZoneUrl("https://api.weather.gov/zones/fire/../../x"));
        assertFalse(NwsAlerts.isZoneUrl("https://api.weather.gov/zones/fire/CAZ285?x=1"));
        assertFalse(NwsAlerts.isZoneUrl(null));
        // The cache file is named from the key, and only a followed URL has one.
        final Method key = Class.forName("com.atakmap.android.atmosphere.data.ZoneCache")
                .getDeclaredMethod("zoneKey", String.class);
        key.setAccessible(true);
        assertEquals("fire_CAZ285", key.invoke(null, "https://api.weather.gov/zones/fire/CAZ285"));
        assertNull(key.invoke(null, "https://api.weather.gov/zones/fire/..%2F..%2Fx"));
    }

    @Test
    public void inEffectHereIsAPointInsideTheZone() throws Exception {
        final GeoRings.Area modoc = GeoRings.of(new JSONObject(read("zone_fire_CAZ285.json")));
        assertNotNull(modoc);
        // "Modoc County Except for the Surprise Valley": Alturas is in it, Cedarville
        // in the Surprise Valley is not, and Redding is nowhere near.
        assertTrue(modoc.contains(41.487, -120.542));
        assertFalse(modoc.contains(41.529, -120.173));
        assertFalse(modoc.contains(40.587, -122.392));
    }
}
