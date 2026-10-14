package com.atakmap.android.atmosphere;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.atakmap.android.atmosphere.data.IemRadar;

import org.junit.Test;

/** The mesonet's past radar pictures: the request for an hour, and its colors read back to inches. */
public class IemRadarTest {

    @Test
    public void colorsReadBackToTheirAmounts() {
        // Index 4 (#0018ff) is 1 mm; the three blues below it are 0.25 to 0.75 mm.
        assertEquals(1 / 25.4, IemRadar.inches(0xFF0018FF), 1e-9);
        assertEquals(0.25 / 25.4, IemRadar.inches(0xFF0006FF), 1e-9);
        // Index 88 (#42fe00) is 22 mm; 168 (#fe0030) is 110 mm; 238 (#fea8fe) is 415 mm.
        assertEquals(22 / 25.4, IemRadar.inches(0xFF42FE00), 1e-9);
        assertEquals(110 / 25.4, IemRadar.inches(0xFFFE0030), 1e-9);
        assertEquals(415 / 25.4, IemRadar.inches(0xFFFEA8FE), 1e-9);
        assertEquals(0, IemRadar.inches(0xFF000000), 1e-9);
        assertTrue(Double.isNaN(IemRadar.inches(0xFF909090)));
        // A blended color takes the nearest one's amount.
        assertEquals(22 / 25.4, IemRadar.inches(0xFF43FD01), 1e-9);
    }

    @Test
    public void urlAsksForTheHourAtOrBefore() {
        // 2026-10-11 20:57 UTC is the 20:00 picture.
        final long t = 1_791_752_220_000L;
        final String u = IemRadar.url(24, t, -119.5, 33.5, -117.5, 35.0, 400, 300);
        assertTrue(u, u.startsWith("https://mesonet.agron.iastate.edu/cgi-bin/wms/us/mrms.cgi?"));
        assertTrue(u, u.contains("&prod=p24h&year=2026&month=10&day=11&time=2000"));
        assertTrue(IemRadar.url(72, t, -119.5, 33.5, -117.5, 35.0, 400, 300).contains("&prod=p72h&"));
        assertTrue(IemRadar.url(1, t, -119.5, 33.5, -117.5, 35.0, 400, 300).contains("&prod=p1h&"));
    }
}
