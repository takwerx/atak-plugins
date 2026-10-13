package com.atakmap.android.atmosphere;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.atakmap.android.atmosphere.data.RainReports;

import org.junit.Test;

public class RainReportsTest {

    private static final String SGX = "000\nSRUS46 KSGX 111957\nRRMSGX\n\nPRECIPITATION SUMMARY....PRELIMINARY\n"
            + "NATIONAL WEATHER SERVICE SAN DIEGO\n1234 PM PDT SUN OCT 11 2026 \n\n"
            + "*STATIONS ABOVE THE FREEZING LEVEL MAY NOT SHOW\n ACCURATE ACCUMULATING PRECIPITATION.\n\n"
            + "     2 DAY PRECIPITATION TOTALS AS OF 1234 PM SUNDAY\n\n.TOP RAINFALL AMOUNTS FROM ALL ZONES...\n\n"
            + "        STATION              PRECIP(IN)    ELEVATION(FT)\n\n"
            + "     1. PALOMAR CRS             5.16        5387\n     2. BIRCH HILL              4.80        5645\n\n"
            + "-------------------------------------------------------------\n\n.SAN DIEGO COUNTY COASTAL AREAS...         \n\n"
            + "ID      STATION                PRECIP(IN)    ELEVATION(FT)\n\n"
            + "PPDC1   CPEN LAKE ONEILL          1.95          109\nCRQ     CARLSBAD AIRPORT          1.74          357\n"
            + "L13     POINT LOMA                0.75          364\n$$\n";

    private static final String LOX = "RRMLOX\n\nPRECIPITATION SUMMARY\nNATIONAL WEATHER SERVICE LOS ANGELES/OXNARD CA\n"
            + "300 PM PDT SUN OCT 11 2026\n\n...PRELIMINARY PRECIPITATION TOTALS...\n\n"
            + "THE FOLLOWING ARE 24 HOUR RAINFALL TOTALS IN INCHES\nFOR THIS EVENT THROUGH 300 PM SUNDAY AFTERNOON.\n\n"
            + ".LA CO COAST & METRO\nLA DOWNTOWN....................... 0.03\nHAWTHORNE (KHHR)..................    T\nLA AIRPORT(KLAX).................. 0.21\n";

    private static final String SEW = "RRMSEW\n\nMiscellaneous Hydrologic Report\nNational Weather Service Seattle WA\n"
            + "858 PM PDT Sun Oct 11 2026\n\nPrecipitation amounts in Western Washington for the following time\n"
            + "periods ending at  8:58 PM Sunday    H = hours \n\n"
            + "  North Lowlands...      1H     3H     6H    12H    24H    48H    72H\n"
            + "Bellingham              0.00   0.00   0.00   0.00  0.00   0.26   0.47 \n"
            + "Oak Harbor              0.00    M      M     0.00  0.00   0.10   0.26 \n";

    @Test
    public void sanDiegoIdsAndElevations() {
        final RainReports.Report r = RainReports.parse(SGX);
        assertEquals("NATIONAL WEATHER SERVICE SAN DIEGO", r.office);
        assertEquals("1234 PM PDT SUN OCT 11 2026", r.issued);
        assertEquals(1, r.period.size());
        assertTrue(r.period.get(0).contains("2 DAY PRECIPITATION TOTALS AS OF"));
        assertEquals(2, r.sections.size());
        assertEquals("Top Rainfall Amounts From All Zones", r.sections.get(0).name);
        assertEquals("Palomar CRS", r.sections.get(0).rows.get(0).name);
        assertEquals(5.16, r.sections.get(0).rows.get(0).inches, 1e-9);
        assertEquals(5387, r.sections.get(0).rows.get(0).elevationFt);
        final RainReports.Row ppd = r.sections.get(1).rows.get(0);
        assertEquals("PPDC1", ppd.id);
        assertEquals("Cpen Lake Oneill", ppd.name);
        assertEquals(1.95, ppd.inches, 1e-9);
        assertEquals(109, ppd.elevationFt);
        assertEquals("L13", r.sections.get(1).rows.get(2).id);
        assertEquals(5, r.rowCount());
    }

    @Test
    public void losAngelesDotsAndTrace() {
        final RainReports.Report r = RainReports.parse(LOX);
        RainReports.Row airport = null;
        for (RainReports.Section sec : r.sections)
            for (RainReports.Row row : sec.rows)
                if (row.name.startsWith("La Airport"))
                    airport = row;
        assertEquals("KLAX", airport == null ? null : airport.id);
        assertEquals(1, r.sections.size());
        assertEquals("La Co Coast & Metro", r.sections.get(0).name);
        assertEquals("La Downtown", r.sections.get(0).rows.get(0).name);
        assertEquals(0.03, r.sections.get(0).rows.get(0).inches, 1e-9);
        assertEquals("T", r.sections.get(0).rows.get(1).values[0]);
        assertTrue(Double.isNaN(r.sections.get(0).rows.get(1).inches));
        assertEquals(2, r.period.size());
    }

    @Test
    public void seattleColumns() {
        final RainReports.Report r = RainReports.parse(SEW);
        assertEquals(7, r.columns.length);
        assertEquals("24H", r.columns[4]);
        assertEquals(4, RainReports.sortColumn(r.columns));
        assertEquals(1, r.sections.size());
        assertEquals("North Lowlands", r.sections.get(0).name);
        final RainReports.Row b = r.sections.get(0).rows.get(0);
        assertEquals("Bellingham", b.name);
        assertEquals(7, b.values.length);
        assertEquals(0.0, b.inches, 1e-9);
        assertEquals("M", r.sections.get(0).rows.get(1).values[1]);
    }
}
