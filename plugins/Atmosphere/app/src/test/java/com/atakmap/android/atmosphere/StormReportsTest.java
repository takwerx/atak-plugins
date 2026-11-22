package com.atakmap.android.atmosphere;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.atakmap.android.atmosphere.data.StormReports;

import org.junit.Test;

import java.util.List;

public class StormReportsTest {

    private static final String BODY = "{\"type\":\"FeatureCollection\",\"exceededTransferLimit\":true,\"features\":["
            + "{\"type\":\"Feature\",\"geometry\":{\"type\":\"Point\",\"coordinates\":[-149.65,61.08]},\"properties\":{"
            + "\"wfo\":\"Anchorage AK\",\"lsr_validtime\":1791691200000,\"descript\":\"Non-Tstm Wnd Gst\",\"loc_desc\":\"2 SE Anchorage\","
            + "\"state\":\"AK\",\"magnitude\":\"66\",\"units\":\"MPH\",\"remarks\":\" \"}},"
            + "{\"type\":\"Feature\",\"geometry\":{\"type\":\"Point\",\"coordinates\":[-79.73,35.31]},\"properties\":{"
            + "\"wfo\":\"Raleigh NC\",\"lsr_validtime\":1791700000000,\"descript\":\"Tornado\",\"loc_desc\":\"1 NNE Candor\","
            + "\"state\":\"NC\",\"magnitude\":null,\"units\":null,\"remarks\":\"One building destroyed.\"}},"
            + "{\"type\":\"Feature\",\"geometry\":{\"type\":\"Point\",\"coordinates\":[-97.5,35.5]},\"properties\":{"
            + "\"descript\":\"Hail\",\"magnitude\":\"1.75\",\"units\":\"Inch\",\"lsr_validtime\":1791700000000}},"
            + "{\"type\":\"Feature\",\"geometry\":null,\"properties\":{\"descript\":\"Flood\"}}]}";

    @Test
    public void parsesReportsAndSkipsTheShapeless() {
        final List<StormReports.Report> r = StormReports.parse(BODY);
        assertEquals(3, r.size());
        assertEquals("Gust 66 mph", r.get(0).label());
        assertEquals(1, r.get(0).family);
        assertEquals("", r.get(0).remarks);
        assertEquals("Tornado", r.get(1).label());
        assertEquals(0, r.get(1).family);
        assertEquals("One building destroyed.", r.get(1).remarks);
        assertEquals("Hail 1.75 in", r.get(2).label());
        assertEquals(2, r.get(2).family);
        assertTrue(StormReports.exceeded(BODY));
        assertFalse(StormReports.exceeded("{\"features\":[]}"));
        assertTrue(StormReports.parse("not json").isEmpty());
    }

    @Test
    public void familiesAndLabels() {
        assertEquals(3, StormReports.family("Flash Flood"));
        assertEquals(4, StormReports.family("Rain"));
        assertEquals(5, StormReports.family("Snow/Ice Dmg"));
        assertEquals(6, StormReports.family("Debris Flow"));
        assertEquals(6, StormReports.family("Landslide"));
        assertEquals(7, StormReports.family("Wildfire"));
        assertEquals(0, StormReports.family("Waterspout"));
        assertEquals("Snow 10 in", StormReports.label("Snow", "10.0", "Inch"));
        assertEquals("Wind damage", StormReports.label("Tstm Wnd Dmg", "0", "MPH"));
        assertEquals("Marine hazard", StormReports.label("Misc Mrn/Srf Hzd", null, null));
        assertEquals(StormReports.url(0) + "&resultOffset=2000", StormReports.url(2000));
        assertTrue(StormReports.url(0).startsWith("https://" + StormReports.HOST + "/"));
    }
}
