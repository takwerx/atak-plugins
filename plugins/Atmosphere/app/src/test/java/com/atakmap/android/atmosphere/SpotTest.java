package com.atakmap.android.atmosphere;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.atakmap.android.atmosphere.data.IsoTime;
import com.atakmap.android.atmosphere.data.Spot;

import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;

/**
 * Spot requests against the shape of the Spot Forecast Monitor's public list
 * (2026-09-24, five real requests with their names, agencies and remarks replaced),
 * and the Eureka office's FWS list from api.weather.gov the same night. The Eureka
 * request here is the one whose forecast was matched to its product by hand.
 */
public class SpotTest {

    private static String read(String name) throws Exception {
        return new String(Files.readAllBytes(new File("src/test/resources/" + name).toPath()),
                StandardCharsets.UTF_8);
    }

    private static List<Spot.Request> all() throws Exception {
        return Spot.parse(read("spot_requests.json"));
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
        assertEquals(5, all.size());
        final Spot.Request eka = byId(all, "2622444");
        assertEquals("Up River Pond Cx _ BLR", eka.project);
        assertEquals("Prescribed Fire", eka.kind);
        assertEquals("EKA", eka.office);
        assertEquals("WR", eka.region);
        assertEquals("CA", eka.state);
        assertEquals(40.8709, eka.lat, 1e-6);
        assertEquals("Forecast issued", eka.status());
        assertEquals(IsoTime.parse("2026-09-24T03:13:14+00:00"), eka.latest().filledAt);
    }

    @Test
    public void theLatestUpdateIsTheNewestNotTheFirstListed() throws Exception {
        // The API lists a request's forecasts newest first; the MT fire has fourteen.
        final Spot.Request mt = byId(all(), "2621219");
        assertEquals(14, mt.issued.size());
        assertEquals(13, mt.latest().update);
    }

    @Test
    public void aRequestWithNoForecastIsWaiting() throws Exception {
        final Spot.Request ca = byId(all(), "2622095");
        assertNull(ca.latest());
        assertEquals("Waiting for the forecast", ca.status());
    }

    @Test
    public void filtersByStateRegionDistanceAndBox() throws Exception {
        final List<Spot.Request> all = all();
        assertEquals(2, Spot.inState(all, "CA").size());
        assertEquals(1, Spot.inRegion(all, "SR").size());
        assertEquals(3, Spot.inRegion(all, "WR").size());
        // 250 km of Eureka reaches the Eureka burn only; the Bay Area request is 390.
        final List<Spot.Request> near = Spot.near(all, 40.80, -124.16, 250_000);
        assertEquals(1, near.size());
        assertEquals("2622444", near.get(0).id);
        // Northern California's box holds both California requests, nearest the
        // box's center first.
        final List<Spot.Request> box = Spot.inBox(all, 42, -125, 36, -119);
        assertEquals(2, box.size());
    }

    @Test
    public void countsNameTheStatesAndRegions() throws Exception {
        final Map<String, Integer> states = Spot.countByState(all());
        assertEquals(Integer.valueOf(2), states.get("CA"));
        // In name order: Alaska, California, Montana, Texas.
        assertEquals("AK", states.keySet().iterator().next());
        final Map<String, Integer> regions = Spot.countByRegion(all());
        assertEquals("WR", regions.keySet().iterator().next());
        assertFalse(regions.containsKey("ER"));
        assertEquals("California", Spot.stateName("CA"));
        assertEquals("At sea", Spot.stateName("OC"));
        assertEquals("Western", Spot.regionName("WR"));
        assertEquals("National centers", Spot.regionName("NC"));
    }

    @Test
    public void theForecastIsTheProductIssuedWithinAQuarterHour() throws Exception {
        final Spot.Request eka = byId(all(), "2622444");
        final List<String> c = Spot.candidates(read("fws_list_eka.json"), eka.latest().filledAt);
        // 03:12 issued against a 03:13:14 fill; 01:46 and 23:01 are other forecasts.
        assertEquals(1, c.size());
        assertTrue(c.get(0).startsWith("https://api.weather.gov/products/"));
        assertTrue(Spot.oldestListed(read("fws_list_eka.json"))
                < IsoTime.parse("2026-09-24T03:12:00+00:00"));
    }

    @Test
    public void theTitleLineSettlesWhichProductItIs() {
        final String text = "000\nFNUS76 KEKA 240312\nFWSEKA\n\n"
                + "Spot Forecast for Up River Pond Cx _ BLR...Blue Lake Rancheria Tribe\n"
                + "National Weather Service Eureka CA\n";
        assertTrue(Spot.isFor(text, "Up River Pond Cx _ BLR"));
        assertFalse(Spot.isFor(text, "Test Ridge RX"));
        assertFalse(Spot.isFor("no title here", "Up River Pond Cx _ BLR"));
        assertFalse(Spot.isFor(text, ""));
    }

    @Test
    public void theRequestsAreThePublicOnesOnly() {
        assertEquals("https://spot.weather.gov/cms/api/1.0/requests?isArchived=false",
                Spot.LIST_URL);
        assertEquals("https://api.weather.gov/products/types/FWS/locations/EKA",
                Spot.fwsListUrl("EKA"));
        // An office id is letters; anything else is dropped, never passed along.
        assertEquals("https://api.weather.gov/products/types/FWS/locations/EKA",
                Spot.fwsListUrl("E/K.A?"));
    }
}
