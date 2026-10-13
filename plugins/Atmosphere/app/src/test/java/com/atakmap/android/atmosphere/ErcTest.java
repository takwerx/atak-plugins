package com.atakmap.android.atmosphere;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.atakmap.android.atmosphere.data.Erc;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.util.List;

public class ErcTest {

    private static String resource(String name) throws IOException {
        try (InputStream in = ErcTest.class.getClassLoader().getResourceAsStream(name)) {
            assertTrue("missing test resource " + name, in != null);
            final ByteArrayOutputStream out = new ByteArrayOutputStream();
            final byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0)
                out.write(buf, 0, n);
            return new String(out.toByteArray(), Charset.forName("UTF-8"));
        }
    }

    @Test
    public void aPointFindsItsPsaAndItsReadings() throws IOException {
        final Erc.Psa p = Erc.parseAt(resource("erc_at_dana_point.json"));
        assertNotNull(p);
        assertEquals("SC08", p.code);
        assertEquals("South Coast", p.name);
        assertEquals("USCAOSCC", p.gacc);
        assertEquals(48.47, p.ercObserved.value, 0.001);
        assertEquals(92.27, p.ercObserved.percentile, 0.001);
        assertEquals("rising", p.ercObserved.trendWord());
        assertEquals(50.35, p.ercForecast.value, 0.001);
        assertEquals("steady", p.ercForecast.trendWord());
        assertTrue(p.biObserved.known());
        // The service ran on the 5th; what it observed was the 4th.
        assertEquals("2026-10-05", p.updated);
        assertEquals("Oct 4", Erc.observedDay(p.updated));
        assertEquals("Oct 5", Erc.forecastDay(p.updated));
    }

    @Test
    public void aPsaWithNoStationReportingHasNoReading() throws IOException {
        final List<Erc.Psa> all = Erc.parseAll(resource("erc_three.geojson"));
        assertEquals(3, all.size());
        for (Erc.Psa p : all) {
            assertNotNull(p.code, p.geometry);
            if (p.code.equals("SA03") || p.code.equals("EA20")) {
                // SA03 is null; EA20 forecasts 0 at a 24th percentile. Neither is a reading.
                assertFalse(p.code, p.ercObserved.known());
                assertFalse(p.code, p.ercForecast.known());
            }
        }
    }

    @Test
    public void thePercentileClassesAreTheAgencys() {
        assertEquals(0, Erc.classOf(12.5));
        assertEquals(1, Erc.classOf(60));
        assertEquals(2, Erc.classOf(89.99));
        assertEquals(3, Erc.classOf(92.27));
        assertEquals(4, Erc.classOf(97));
        assertEquals(5, Erc.classOf(99.5));
        assertEquals(0xFFFFAA00, Erc.color(92.27));
        assertEquals("92nd", Erc.ordinal(92.27));
        assertEquals("11th", Erc.ordinal(11.9));
        assertEquals("21st", Erc.ordinal(21.2));
    }

    @Test
    public void eachGaccsChartIsWhereItsIndexSays() {
        assertEquals("https://gacc.nifc.gov/oncc/predictive/weather/FuelsCharts_Images_FEMS/"
                + "current/SC08_ERC.png", Erc.chartUrl("SC08", "USCAOSCC"));
        assertTrue(Erc.chartUrl("NR01", "USMTNRC").endsWith("/GraphPlots/ERC-1.jpeg"));
        assertTrue(Erc.chartUrl("NR10", "USMTNRC").endsWith("/GraphPlots/ERC-10.jpeg"));
        assertTrue(Erc.chartUrl("SW06N", "USNMSWC").endsWith("/images/PSA_6N.png"));
        assertTrue(Erc.chartUrl("GB10", "USUTGBC").endsWith("/charts_automated/GB10_ERC.png"));
        // The Southern and Eastern Areas make none; a strange code is not put in a path.
        assertNull(Erc.chartUrl("SA03", "USGASAC"));
        assertNull(Erc.chartUrl("EA20", "USWIEACC"));
        assertNull(Erc.chartUrl("../x", "USCAOSCC"));
    }

    @Test
    public void theRequestsAskForWhatIsDrawn() {
        final String all = Erc.allUrl();
        assertTrue(all, all.startsWith("https://" + Erc.HOST + "/"));
        assertTrue(all, all.contains("f=geojson"));
        assertTrue(all, all.contains("maxAllowableOffset=0.01"));
        assertFalse(all, all.contains("outFields=*"));
        assertTrue(Erc.atUrl(33.42, -117.7).contains("geometry=-117.700,33.420"));
        assertNull(Erc.parseAt("{\"features\":[]}"));
        assertTrue(Erc.parseAll("<html>").isEmpty());
    }
}
