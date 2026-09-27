package com.atakmap.android.atmosphere;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.atakmap.android.atmosphere.data.Cwf;

import org.junit.Test;

import java.util.List;
import java.util.Set;

/** The coastal waters forecast cut to one zone, on the SGX issuance of 2026-09-26. */
public class CwfTest {

    private static final String TEXT = ""
            + "\n000\nFZUS56 KSGX 262143\nCWFSGX\n\n"
            + "Coastal Waters Forecast for California\n"
            + "National Weather Service San Diego CA\n"
            + "243 PM PDT Sat Sep 26 2026\n\n"
            + "San Mateo Point to the Mexican border out 60 nm\n\n"
            + "PZZ700-271015-\n"
            + "243 PM PDT Sat Sep 26 2026\n\n"
            + ".Synopsis for the far Southern CA coast...\n"
            + "At 2 PM, a 1024 mb high was 600 nautical miles west of San\n"
            + "Francisco.\n\n$$\n\n"
            + "PZZ740-271015-\n"
            + "Coastal Waters from San Mateo Point to the Mexican Border and out\n"
            + "to 10 nm-\n"
            + "243 PM PDT Sat Sep 26 2026\n\n"
            + "...SMALL CRAFT ADVISORY IN EFFECT FROM LATE SUNDAY NIGHT THROUGH\n"
            + "MONDAY EVENING...\n\n"
            + ".TONIGHT...Wind variable less than 10 kt. Seas 4 ft. Wave Detail:\n"
            + "W 2 ft at 9 seconds and SW 4 ft at 14 seconds. \n"
            + ".SUN...Wind variable less than 10 kt...becoming W 10 kt in the\n"
            + "afternoon. Seas 3 to 4 ft. \n"
            + ".SUN NIGHT...Wind NW 10 kt. Seas 3 to 5 ft. \n"
            + "\n$$\n\n"
            + "PZZ750-753-755-760-762-765-770-775-776-271015-\n"
            + "Outer waters-\n"
            + "243 PM PDT Sat Sep 26 2026\n\n"
            + ".TONIGHT...Wind NW 15 to 20 kt. \n\n$$\n";

    @Test
    public void cutsTheZoneSectionAndReadsItsPeriods() {
        final String s = Cwf.section(TEXT, "PZZ740");
        assertEquals("Coastal Waters from San Mateo Point to the Mexican Border and out to 10 nm",
                Cwf.name(s));
        assertEquals("2:43 PM PDT Sat Sep 26", Cwf.issued(s));
        final List<Cwf.Period> p = Cwf.periods(s);
        assertEquals(3, p.size());
        assertEquals("Tonight", p.get(0).name);
        assertEquals("Wind variable less than 10 kt. Seas 4 ft. Wave Detail: W 2 ft at 9 seconds"
                + " and SW 4 ft at 14 seconds.", p.get(0).text);
        assertEquals("Sun", p.get(1).name);
        assertTrue(p.get(1).text.endsWith("Seas 3 to 4 ft."));
        assertEquals("Sun night", p.get(2).name);
        // the headline is not a period, and the synopsis section is not this zone
        assertEquals("Wind NW 15 to 20 kt.", Cwf.periods(Cwf.section(TEXT, "PZZ776")).get(0).text);
        assertNull(Cwf.section(TEXT, "PZZ745"));
    }

    @Test
    public void ugcHeadersExpand() {
        assertEquals(1, Cwf.expandUgc("PZZ740-271015-").size());
        final Set<String> two = Cwf.expandUgc("AMZ650-670-270900-");
        assertTrue(two.contains("AMZ650") && two.contains("AMZ670") && two.size() == 2);
        final Set<String> mixed = Cwf.expandUgc("AMZ600-GMZ606-270900-");
        assertTrue(mixed.contains("AMZ600") && mixed.contains("GMZ606") && mixed.size() == 2);
        final Set<String> range = Cwf.expandUgc("PZZ750>753-760-271015-");
        assertTrue(range.contains("PZZ751") && range.contains("PZZ753") && range.contains("PZZ760"));
        assertEquals(5, range.size());
    }

    @Test
    public void pointsAnswerNamesTheMarineZone() {
        final String over = "{\"properties\":{\"forecastZone\":\"https://api.weather.gov/zones/forecast/PZZ740\","
                + "\"cwa\":\"SGX\"}}";
        final Cwf.Zone z = Cwf.parsePoint(over);
        assertEquals("PZZ740", z.id);
        assertEquals("CWF", z.productType());
        final Cwf.Zone off = Cwf.parsePoint(over.replace("PZZ740", "PZZ840").replace("SGX", "ONP"));
        assertEquals("OFF", off.productType());
        assertNull(Cwf.parsePoint(over.replace("PZZ740", "CAZ043")));   // a pier on land
        assertEquals("abc", Cwf.parseLatestId("{\"@graph\":[{\"id\":\"abc\"},{\"id\":\"old\"}]}"));
        assertNull(Cwf.parseLatestId("{\"@graph\":[]}"));
    }

    /** Where the OFF product for an offshore zone is filed, best guess first. */
    @Test
    public void offshoreZonesMapToTheirProductLocation() {
        assertEquals(java.util.Arrays.asList("PZ6", "PZ5"), Cwf.offshoreLocations("PZZ840"));   // Tanner Bank
        assertEquals(java.util.Arrays.asList("PZ5", "PZ6"), Cwf.offshoreLocations("PZZ905"));
        assertEquals(java.util.Arrays.asList("PZ7", "PZ8"), Cwf.offshoreLocations("PMZ011"));
        assertEquals(java.util.Arrays.asList("PZ8", "PZ7"), Cwf.offshoreLocations("PMZ113"));
        assertEquals(java.util.Arrays.asList("NT1", "NT2"), Cwf.offshoreLocations("ANZ805"));
        assertEquals(java.util.Arrays.asList("NT2", "NT1"), Cwf.offshoreLocations("ANZ935"));
        assertEquals(java.util.Arrays.asList("NT3", "NT5"), Cwf.offshoreLocations("AMZ041"));
        assertEquals(java.util.Arrays.asList("NT5", "NT3"), Cwf.offshoreLocations("AMZ088"));
        assertEquals(java.util.Arrays.asList("NT4"), Cwf.offshoreLocations("GMZ049"));
        assertEquals(java.util.Arrays.asList("HFO"), Cwf.offshoreLocations("PHZ180"));
        assertEquals(4, Cwf.offshoreLocations("PKZ150").size());
        assertTrue(Cwf.offshoreLocations("CAZ043").isEmpty());
        assertTrue(Cwf.offshoreLocations(null).isEmpty());
    }

    /** An OFF section has the same shape as a CWF one, with the zone name over the issued line. */
    @Test
    public void offshoreSectionReads() {
        final String off = "PZZ840-271615-\n"
                + "Santa Cruz Island, CA to San Clemente Island, CA between 60 NM\n"
                + "and 150 NM offshore-\n"
                + "907 PM PDT Sat Sep 26 2026\n\n"
                + ".TONIGHT...N to NW winds 10 to 20 kt. Seas 4 to 8 ft. \n"
                + ".SUN...N to NW winds 10 to 20 kt, becoming NW 5 to 15 kt. Seas\n"
                + "4 to 8 ft. \n\n$$\n\nPZZ940-271615-\nSanta Cruz Island, CA to 120W-\n907 PM PDT Sat Sep 26 2026\n\n.TONIGHT...N winds.\n\n$$\n";
        final String s = Cwf.section(off, "PZZ840");
        assertEquals("Santa Cruz Island, CA to San Clemente Island, CA between 60 NM and 150 NM offshore",
                Cwf.name(s));
        assertEquals("9:07 PM PDT Sat Sep 26", Cwf.issued(s));
        final List<Cwf.Period> p = Cwf.periods(s);
        assertEquals(2, p.size());
        assertEquals("Sun", p.get(1).name);
        assertEquals("N to NW winds 10 to 20 kt, becoming NW 5 to 15 kt. Seas 4 to 8 ft.", p.get(1).text);
        assertNull(Cwf.section(off, "PZZ845"));
    }
}
