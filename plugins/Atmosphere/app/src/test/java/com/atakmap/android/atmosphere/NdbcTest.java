package com.atakmap.android.atmosphere;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.atakmap.android.atmosphere.data.IsoTime;
import com.atakmap.android.atmosphere.data.Ndbc;

import org.junit.Test;

import java.util.List;
import java.util.Map;

/** The NDBC file read by its header, and the station table joined to it. */
public class NdbcTest {

    private static final String OBS = ""
            + "#STN       LAT      LON  YYYY MM DD hh mm WDIR WSPD   GST WVHT  DPD APD MWD   PRES  PTDY  ATMP  WTMP  DEWP  VIS   TIDE\n"
            + "#text      deg      deg   yr mo day hr mn degT  m/s   m/s   m   sec sec degT   hPa   hPa  degC  degC  degC  nmi     ft\n"
            + "46025    33.765 -119.077 2026 09 26 22 00 290   4.0   5.0   MM   MM  MM  MM 1012.3  -0.8  22.4  20.1  20.6   MM     MM\n"
            + "46224    33.178 -117.472 2026 09 26 21 56  MM    MM    MM  1.0   13  MM 206     MM    MM    MM  20.8    MM   MM     MM\n"
            + "NOPOS    0.000    0.000 2026 09 26 22 00 200   1.0    MM   MM   MM  MM  MM     MM    MM    MM    MM    MM   MM     MM\n";

    @Test
    public void readsByHeaderNotByPosition() {
        final List<Ndbc.Buoy> b = Ndbc.parseObs(OBS);
        assertEquals(2, b.size());
        final Ndbc.Buoy met = b.get(0);
        assertEquals("46025", met.id);
        assertEquals(290.0, met.windFromDeg, 1e-9);        // not the day of the month
        assertEquals(4.0, met.windMs, 1e-9);
        assertEquals(5.0, met.gustMs, 1e-9);
        assertTrue(Double.isNaN(met.waveHeightM));
        assertEquals(1012.3, met.pressureHpa, 1e-9);
        assertEquals(-0.8, met.pressureTendencyHpa, 1e-9);
        assertEquals(IsoTime.parse("2026-09-26T22:00:00Z"), met.observedAt);
        assertTrue(met.hasWind());
        assertFalse(met.hasWaves());

        final Ndbc.Buoy wave = b.get(1);
        assertFalse(wave.hasWind());
        assertTrue(wave.hasWaves());
        assertEquals(1.0, wave.waveHeightM, 1e-9);
        assertEquals(13.0, wave.dominantPeriodS, 1e-9);
        assertEquals(206.0, wave.waveFromDeg, 1e-9);
        assertEquals(20.8, wave.waterTempC, 1e-9);
    }

    @Test
    public void humidityFromTemperatureAndDewpoint() {
        final Ndbc.Buoy met = Ndbc.parseObs(OBS).get(0);
        // 22.4 C air, 20.6 C dewpoint: about 89.5%.
        assertEquals(89.5, met.relativeHumidity(), 1.0);
        assertEquals(100.0, Ndbc.humidity(20, 20), 0.01);
        assertTrue(Double.isNaN(Ndbc.humidity(20, Double.NaN)));
    }

    @Test
    public void namesComeFromTheStationTable() {
        final String table = "# STATION_ID | OWNER | TTYPE | HULL | NAME | PAYLOAD | LOCATION | TIMEZONE | FORECAST | NOTE\n"
                + "46025|N|3-meter discus buoy w/ seal cage|3D15|Santa Monica Basin - 33NM WSW of Santa Monica, CA|SCOOP payload|33.765 N 119.077 W (33&#176;45'54\" N)|P|FZUS56.KLOX |\n"
                + "46224|R|Waverider Buoy||Oceanside Offshore, CA (045)||33.178 N 117.472 W|P|FZUS56.KSGX |\n";
        final Map<String, Ndbc.Station> t = Ndbc.parseStations(table);
        assertEquals(2, t.size());
        final List<Ndbc.Buoy> b = Ndbc.parseObs(OBS);
        assertEquals("46025", b.get(0).label());            // the id until named
        Ndbc.name(b, t);
        assertEquals("Santa Monica Basin - 33NM WSW of Santa Monica, CA", b.get(0).label());
        assertEquals("Waverider Buoy", b.get(1).type);
    }

    @Test
    public void withinIsABoxAroundThePoint() {
        final List<Ndbc.Buoy> b = Ndbc.parseObs(OBS);
        assertEquals(1, Ndbc.within(b, 33.2, -117.4, 25).size());
        assertEquals(2, Ndbc.within(b, 33.5, -118.3, 100).size());
        assertEquals(0, Ndbc.within(b, 40.0, -124.0, 25).size());
    }
}
