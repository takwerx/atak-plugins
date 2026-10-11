package com.atakmap.android.atmosphere;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.atakmap.android.atmosphere.data.IsoTime;
import com.atakmap.android.atmosphere.data.Spot;
import com.atakmap.android.atmosphere.data.States;

import org.junit.BeforeClass;
import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;

/**
 * Spot requests against NWS's spot forecast map service as it answered 2026-09-24
 * (seven of its 423 rows, unaltered: it is public), the Eureka office's FWS list the
 * same night, and the plugin's own state outlines.
 */
public class SpotTest {

    private static States states;

    @BeforeClass
    public static void loadStates() throws Exception {
        states = States.parse(read("../../main/assets/us_states.json"));
    }

    private static String read(String name) throws Exception {
        return new String(Files.readAllBytes(new File("src/test/resources/" + name).toPath()),
                StandardCharsets.UTF_8);
    }

    private static List<Spot.Request> all() throws Exception {
        return Spot.parse(read("spot_mapserver.json"), states);
    }

    private static Spot.Request byId(List<Spot.Request> all, String id) {
        for (Spot.Request r : all)
            if (r.id.equals(id))
                return r;
        return null;
    }

    @Test
    public void readsWhatACrewNeedsFromEachRequest() throws Exception {
        final List<Spot.Request> all = all();
        assertEquals(7, all.size());
        final Spot.Request eka = byId(all, "2622444");
        assertEquals("Up River Pond Cx _ BLR", eka.project);
        assertEquals("Prescribed Fire", eka.kind);
        assertEquals("EKA", eka.office);
        assertEquals("Eureka", eka.officeName);
        assertEquals("WR", eka.region);
        assertEquals("CA", eka.state);
        assertEquals(40.8709, eka.lat, 1e-6);
        assertEquals("Forecast issued", eka.status());
    }

    @Test
    public void localTimesAreTwentyFourHourWithTheirZone() throws Exception {
        final List<Spot.Request> all = all();
        // "2026-09-23 20:13:14 PM PDT": 8 pm Pacific, 03:13:14Z -- the fill time the
        // Monitor recorded for this request, and the FWS product's issuance within 2 min.
        assertEquals(IsoTime.parse("2026-09-24T03:13:14Z"), byId(all, "2622444").filledAt);
        // The Montana fire's newest of fourteen updates, 14:00:18 MDT.
        assertEquals(IsoTime.parse("2026-09-18T20:00:18Z"), byId(all, "2621219").filledAt);
        // Alaska daylight time.
        assertEquals(IsoTime.parse("2026-09-19T21:43:10Z"), byId(all, "2621056").filledAt);
    }

    @Test
    public void aRequestNotYetFilledIsWaiting() throws Exception {
        final Spot.Request pending = byId(all(), "2621722");
        assertTrue(pending.pending);
        assertEquals(0, pending.filledAt);
        assertEquals("Waiting for the forecast", pending.status());
    }

    @Test
    public void theKindComesFromTheTypeId() throws Exception {
        final List<Spot.Request> all = all();
        assertEquals("Wildfire", byId(all, "2621219").kind);
        assertEquals("HAZMAT", byId(all, "2622135").kind);
        assertEquals("Special event", byId(all, "2621056").kind);
        assertEquals("Search and Rescue at sea", byId(all, "2622133").kind);
    }

    @Test
    public void stateFromThePointAndRegionFromTheOffice() throws Exception {
        final List<Spot.Request> all = all();
        assertEquals("MT", byId(all, "2621219").state);
        assertEquals("AK", byId(all, "2621056").state);
        assertEquals("TX", byId(all, "2622057").state);
        assertEquals("CO", byId(all, "2622135").state);
        // A Coast Guard search off the Virgin Islands is in no state.
        final Spot.Request sea = byId(all, "2622133");
        assertEquals("", sea.state);
        assertEquals("At sea", Spot.stateName(sea.state));
        assertEquals("NC", sea.region);
        assertEquals("AR", byId(all, "2621056").region);
        assertEquals("SR", byId(all, "2622057").region);
    }

    @Test
    public void theStateOutlinesPlaceKnownTowns() {
        assertEquals(52, states.size());
        assertEquals("CA", states.at(41.487, -120.542));   // Alturas
        assertEquals("NV", states.at(39.53, -119.81));     // Reno
        assertEquals("OR", states.at(42.33, -122.87));     // Medford
        assertEquals("", states.at(30, -40));              // mid-Atlantic
    }

    @Test
    public void filtersByStateRegionDistanceAndBox() throws Exception {
        final List<Spot.Request> all = all();
        assertEquals(1, Spot.inState(all, "CA").size());
        assertEquals(1, Spot.inState(all, "").size());
        assertEquals(2, Spot.inRegion(all, "WR").size());
        // 250 km of Eureka reaches the Eureka burn only.
        final List<Spot.Request> near = Spot.near(all, 40.80, -124.16, 250_000);
        assertEquals(1, near.size());
        assertEquals("2622444", near.get(0).id);
        final List<Spot.Request> box = Spot.inBox(all, 49, -125, 30, -100);
        assertEquals(4, box.size());
        final Map<String, Integer> states = Spot.countByState(all);
        // Name order, at sea last.
        assertEquals("AK", states.keySet().iterator().next());
        String last = null;
        for (String k : states.keySet())
            last = k;
        assertEquals("", last);
        assertEquals("WR", Spot.countByRegion(all).keySet().iterator().next());
    }

    @Test
    public void theForecastIsTheProductIssuedWithinAQuarterHour() throws Exception {
        final Spot.Request eka = byId(all(), "2622444");
        final List<String> c = Spot.candidates(read("fws_list_eka.json"), eka.filledAt);
        // 03:12 issued against a 03:13:14 fill; 01:46 and 23:01 are other forecasts.
        assertEquals(1, c.size());
        assertTrue(c.get(0).startsWith("https://api.weather.gov/products/"));
    }

    @Test
    public void theTitleLineSettlesWhichProductItIs() {
        final String text = "000\nFNUS76 KEKA 240312\nFWSEKA\n\n"
                + "Spot Forecast for Up River Pond Cx _ BLR...Blue Lake Rancheria Tribe\n"
                + "National Weather Service Eureka CA\n";
        assertTrue(Spot.isFor(text, "Up River Pond Cx _ BLR"));
        assertFalse(Spot.isFor(text, "Upton 3"));
        assertFalse(Spot.isFor("no title here", "Up River Pond Cx _ BLR"));
        assertFalse(Spot.isFor(text, ""));
    }

    @Test
    public void theRequestsAreNwssPublishedServicesOnly() {
        assertTrue(Spot.LIST_URL.startsWith("https://mapservices.weather.noaa.gov/vector/rest/"
                + "services/fire_weather/nws_fire_weather_spot/MapServer/0/query?"));
        assertFalse(Spot.LIST_URL.contains("spot.weather.gov"));
        assertNotNull(Spot.fwsListUrl("EKA"));
        assertEquals("https://api.weather.gov/products/types/FWS/locations/EKA",
                Spot.fwsListUrl("E/K.A?"));
    }
}
