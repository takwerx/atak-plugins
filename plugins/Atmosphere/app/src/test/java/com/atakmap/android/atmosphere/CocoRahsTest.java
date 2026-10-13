package com.atakmap.android.atmosphere;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.atakmap.android.atmosphere.data.CocoRahs;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

public class CocoRahsTest {

    private static final String CSV = "ObservationDate,ObservationTime,EntryDateTime,StationNumber,StationName,Latitude,Longitude,TotalPrecipAmt,NewSnowDepth,NewSnowSWE,TotalSnowDepth,TotalSnowSWE,DateTimeStamp\n"
            + "2026-10-12, 04:30 AM, 2026-10-12 04:34 AM, CA-SH-10, Redding 7.9 NNE, 40.67211, -122.29128, 0.00, 0.0, NA, NA, NA, 2026-10-12 11:33 AM\n"
            + "2026-10-12, 06:30 AM, 2026-10-12 08:47 AM, CA-SD-162, El Cajon 7.8 NE, 32.866452774693, -116.850903504863, 3.00, NA, NA, NA, NA, 2026-10-12 03:46 PM\n"
            + "2026-10-12, 07:00 AM, 2026-10-12 07:10 AM, CA-RV-98, La Quinta 1.8 SW, 33.651558, -116.311428, T, NA, NA, NA, NA, 2026-10-12 01:03 PM\n"
            + "2026-10-12, 07:00 AM, 2026-10-12 07:10 AM, CA-XX-1, Nowhere, 0, 0, 1.00, NA, NA, NA, NA, 2026-10-12 01:03 PM\n"
            + "2026-10-12, 07:00 AM, 2026-10-12 07:10 AM, CA-XX-2, No Reading, 34.0, -118.0, NA, NA, NA, NA, NA, 2026-10-12 01:03 PM\n";

    @Test
    public void parsesReportsAndDropsTheUnusable() {
        final List<CocoRahs.Report> r = CocoRahs.parse(CSV);
        assertEquals(3, r.size());
        assertEquals("CA-SH-10", r.get(0).station);
        assertEquals("Redding 7.9 NNE", r.get(0).name);
        assertEquals(0.0, r.get(0).inches, 1e-9);
        assertEquals(3.0, r.get(1).inches, 1e-9);
        assertTrue(r.get(2).trace);
        assertEquals("04:30 AM", r.get(0).time);
        assertTrue(CocoRahs.parse("<html>").isEmpty());
        assertTrue(CocoRahs.parse("").isEmpty());
    }

    @Test
    public void newestKeepsTheLaterDate() {
        final List<CocoRahs.Report> all = new ArrayList<>(CocoRahs.parse(CSV));
        all.addAll(CocoRahs.parse(CSV.replace("2026-10-12, 04:30", "2026-10-13, 04:30")));
        final List<CocoRahs.Report> n = CocoRahs.newest(all);
        assertEquals(3, n.size());
        assertEquals("2026-10-13", n.get(0).date);
        assertTrue(CocoRahs.url("CA", new java.util.Date(0)).contains("State=CA&ReportDateType=reportdate&Date=12/31/1969"));
    }
}
