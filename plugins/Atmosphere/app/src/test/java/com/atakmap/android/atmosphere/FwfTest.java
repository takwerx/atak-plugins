package com.atakmap.android.atmosphere;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.atakmap.android.atmosphere.data.FireZones;
import com.atakmap.android.atmosphere.data.Fwf;

import org.junit.Test;

import java.util.List;

/**
 * The fire weather planning forecast cut to one zone, and the zone lookups, on the
 * LOX (period blocks) and TAE (table) issuances of 2026-09-28, abridged.
 */
public class FwfTest {

    private static final String LOX = ""
            + "\n000\nFNUS56 KLOX 282226\nFWFLOX\n\n"
            + "Fire Weather Planning Forecast For Southwestern California\n"
            + "National Weather Service Los Angeles/Oxnard CA\n"
            + "326 PM PDT Mon Sep 28 2026\n\n"
            + "...ELEVATED FIRE WEATHER CONDITIONS ACROSS INTERIOR AND SOUTHERN\n"
            + "SANTA BARBARA COUNTY THROUGH TUESDAY EVENING...\n\n"
            + ".DISCUSSION...\n"
            + "A mostly dry upper level low pressure will bring increasing north to\n"
            + "northeast winds.   \n\n\n"
            + "CAZ354-355-362-366>368-291130-\n"
            + "Ventura County Beaches-Ventura County Inland Coast-Malibu Coast-\n"
            + "326 PM PDT Mon Sep 28 2026\n\n"
            + ".TONIGHT...\n"
            + "Sky/weather.........Clear. \n\n$$\n\n"
            + "CAZ356>358-369>375-548-291130-\n"
            + "Lake Casitas-Ojai Valley-Central Ventura County Valleys-\n"
            + "Santa Susana Mountains-Los Angeles County San Gabriel Valley-\n"
            + "326 PM PDT Mon Sep 28 2026\n\n"
            + ".TONIGHT...\n"
            + "Sky/weather.........Clear. \n"
            + "Min temperature.....54-64. \n"
            + "Max humidity........60-80 percent except 40-55 percent drier peaks. \n\n"
            + "$$\n";

    private static final String TAE = ""
            + "\n000\nFNUS52 KTAE 281838\nFWFTAE\n\n"
            + "Fire Weather Planning Forecast for North Florida...Southwest\n"
            + "Georgia...and Southeast Alabama\n"
            + "National Weather Service Tallahassee FL\n"
            + "235 PM EDT Mon Sep 28 2026\n\n"
            + "...ELEVATED FIRE CONCERNS TUESDAY DUE TO NEAR CRITICAL RH\n"
            + "VALUES...\n\n"
            + "FLZ027-290800-\n"
            + "Inland Wakulla-\n"
            + "235 PM EDT Mon Sep 28 2026\n\n"
            + "                      Tonight      Tue          Tue Night    Wed          \n\n"
            + "Cloud Cover           Mclear       Mclear       Mclear       Mclear       \n"
            + "Max/Min RH %          87           42           100          53           \n"
            + "\n$$\n";

    @Test
    public void cutsTheZoneOutOfARangedHeader() {
        final String s = Fwf.section(LOX, "CAZ548");
        assertTrue(s, s.startsWith("Lake Casitas"));
        assertTrue(s.contains("Min temperature.....54-64."));
        assertTrue(!s.contains("Ventura County Beaches"));
        assertTrue(Fwf.section(LOX, "CAZ372").contains("Santa Susana"));
        assertTrue(Fwf.section(LOX, "CAZ367").startsWith("Ventura County Beaches"));
        assertNull(Fwf.section(LOX, "CAZ999"));
    }

    @Test
    public void keepsATableAsWritten() {
        final String s = Fwf.section(TAE, "FLZ027");
        assertTrue(s.contains(
                "Max/Min RH %          87           42           100          53"));
    }

    @Test
    public void theOfficeAndItsDiscussion() {
        assertEquals("Los Angeles/Oxnard CA", Fwf.office(LOX));
        assertEquals("Tallahassee FL", Fwf.office(TAE));
        final String d = Fwf.discussion(LOX);
        assertTrue(d, d.startsWith("...ELEVATED FIRE WEATHER"));
        assertTrue(d.endsWith("northeast winds."));
        assertTrue(!d.contains("CAZ354"));
        assertTrue(Fwf.discussion(TAE).startsWith("...ELEVATED FIRE CONCERNS"));
        assertEquals("", Fwf.discussion("no product here"));
    }

    @Test
    public void readsTheIssuancesNewestFirst() {
        final String list = "{\"@graph\":["
                + "{\"id\":\"762dadbe-7abd-4573-b97e-17d749f81080\","
                + "\"issuanceTime\":\"2026-09-28T22:26:00+00:00\"},"
                + "{\"id\":\"../../etc\",\"issuanceTime\":\"2026-09-28T16:24:00+00:00\"},"
                + "{\"id\":\"45fdb896-a6cf-4149-8f75-eaa0d6feac2b\","
                + "\"issuanceTime\":\"2026-09-28T16:24:00+00:00\"}]}";
        final List<Fwf.Issuance> got = Fwf.parseList(list, 4);
        // An id that is not a product id never reaches a URL.
        assertEquals(2, got.size());
        assertEquals("762dadbe-7abd-4573-b97e-17d749f81080", got.get(0).id);
        assertTrue(got.get(0).issuedAt > got.get(1).issuedAt);
        assertTrue(Fwf.parseList("{\"@graph\":[]}", 4).isEmpty());
        assertTrue(Fwf.parseList("not json", 4).isEmpty());
    }

    @Test
    public void aZoneNumberIsReadEveryWayItIsTyped() {
        assertEquals("CA548", FireZones.normalize("CAZ548"));
        assertEquals("CA548", FireZones.normalize("ca548"));
        assertEquals("CA548", FireZones.normalize(" caz 548 "));
        assertEquals("AZ101", FireZones.normalize("AZZ101"));
        assertEquals("MT004", FireZones.normalize("MT4"));
        assertNull(FireZones.normalize("548"));
        assertNull(FireZones.normalize("San Gabriel"));
        assertEquals("CAZ548", FireZones.ugc("CA548"));
    }

    @Test
    public void theSearchSendsNothingButLettersAndDigits() {
        assertTrue(FireZones.searchUrl("CAZ548").contains("where=state_zone%3D%27CA548%27"));
        assertTrue(FireZones.searchUrl("548").contains("where=zone%3D%27548%27"));
        final String byName = FireZones.searchUrl("san gabriel");
        assertTrue(byName, byName.contains("%25SAN%25GABRIEL%25"));
        // A quote typed into the box is not a quote in the query.
        final String hostile = FireZones.searchUrl("x' OR 1=1 --");
        assertTrue(hostile, !hostile.contains("%27+OR") && !hostile.contains("'"));
        assertNull(FireZones.searchUrl("a"));
        assertNull(FireZones.searchUrl(""));
    }

    @Test
    public void theCellIsSentNotThePoint() {
        final String u = FireZones.cellUrl(34.2012, -118.1734);
        assertTrue(u, u.contains("geometry=-118.5,34,-118,34.5"));
        assertEquals(FireZones.cellKey(34.01, -118.49), FireZones.cellKey(34.49, -118.01));
        assertTrue(!FireZones.cellKey(34.49, -118.01).equals(FireZones.cellKey(34.51, -118.01)));
    }
}
