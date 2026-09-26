package com.atakmap.android.atmosphere;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.atakmap.android.atmosphere.data.Coops;

import org.junit.Test;

import java.util.List;

public class CoopsTest {

    @Test
    public void stationsNearestAndBins() {
        final String body = "{\"stations\":["
                + "{\"id\":\"9410230\",\"name\":\"La Jolla\",\"lat\":32.8669,\"lng\":-117.2571,\"type\":\"H\"},"
                + "{\"id\":\"9410032\",\"name\":\"Wilson Cove\",\"lat\":33.005,\"lng\":-118.557,\"type\":\"S\"},"
                + "{\"id\":\"PCT0026\",\"name\":\"Point Loma\",\"lat\":32.66583,\"lng\":-117.22617,\"type\":\"S\",\"currbin\":2,\"depth\":33},"
                + "{\"id\":\"PCT0026\",\"name\":\"Point Loma\",\"lat\":32.66583,\"lng\":-117.22617,\"type\":\"S\",\"currbin\":1,\"depth\":15}]}";
        final List<Coops.Station> st = Coops.parseStations(body);
        assertEquals(4, st.size());
        assertTrue(st.get(0).measures());
        final Coops.Station n = Coops.nearest(st, 32.7, -117.24, 30);
        assertEquals("PCT0026", n.id);
        assertEquals(1, n.bin);                            // bin 1 wins the tie
        assertNull(Coops.nearest(st, 40.0, -124.0, 30));   // nothing within 30 mi
    }

    @Test
    public void theHiloUrlBeginsOnADate() {
        final String u = Coops.hiloUrl("TWC0419", "20260926");
        assertTrue(u.contains("begin_date=20260926&range=48"));
        assertTrue(u.contains("interval=hilo"));
        assertEquals(8, Coops.today().length());
    }

    @Test
    public void tidesAndLevels() {
        final List<Coops.Tide> t = Coops.parseHilo("{\"predictions\":[{\"t\":\"2026-09-26 03:15\",\"v\":\"0.426\",\"type\":\"L\"},"
                + "{\"t\":\"2026-09-26 09:26\",\"v\":\"5.493\",\"type\":\"H\"}]}");
        assertEquals(2, t.size());
        assertTrue(t.get(1).high);
        assertEquals(5.493, t.get(1).feet, 1e-9);
        assertEquals("9:26 AM", Coops.clock(t.get(1).at));
        assertEquals("3:15 AM", Coops.clock(t.get(0).at));
        assertEquals("Sat 26", Coops.day(t.get(0).at));
        final Coops.Tide wl = Coops.parseWaterLevel("{\"metadata\":{\"id\":\"9410230\"},\"data\":[{\"t\":\"2026-09-26 15:48\",\"v\":\"1.195\",\"s\":\"0.449\",\"f\":\"1,0,0,0\",\"q\":\"p\"}]}");
        assertEquals(1.195, wl.feet, 1e-9);
        assertNull(Coops.parseWaterLevel("{\"error\":{\"message\":\"No data was found.\"}}"));
    }

    @Test
    public void currents() {
        final List<Coops.Current> c = Coops.parseCurrents("{\"current_predictions\":{\"cp\":["
                + "{\"Type\":\"ebb\",\"meanFloodDir\":355,\"Bin\":\"1\",\"meanEbbDir\":173,\"Time\":\"2026-09-26 00:47\",\"Velocity_Major\":-2.13},"
                + "{\"Type\":\"slack\",\"meanFloodDir\":355,\"Bin\":\"1\",\"meanEbbDir\":173,\"Time\":\"2026-09-26 03:56\",\"Velocity_Major\":0}]}}");
        assertEquals(2, c.size());
        assertEquals("ebb", c.get(0).type);
        assertEquals(-2.13, c.get(0).knots, 1e-9);
        assertEquals(173.0, c.get(0).ebbDir, 1e-9);
        assertEquals("12:47 AM", Coops.clock(c.get(0).at));
    }
}
