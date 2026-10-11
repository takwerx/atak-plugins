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
            // East of 100 W on purpose: a West Coast longitude has three integer digits
            // and three decimals, and followed by the year that reads as a phone
            // number to the publish scrub. The format is NDBC's either way.
            + "44013    42.346  -70.651 2026 09 26 22 00 290   4.0   5.0   MM   MM  MM  MM 1012.3  -0.8  22.4  20.1  20.6   MM     MM\n"
            + "44091    39.778  -73.769 2026 09 26 21 56  MM    MM    MM  1.0   13  MM 206     MM    MM    MM  20.8    MM   MM     MM\n"
            + "NOPOS    0.000    0.000 2026 09 26 22 00 200   1.0    MM   MM   MM  MM  MM     MM    MM    MM    MM    MM   MM     MM\n";

    @Test
    public void readsByHeaderNotByPosition() {
        final List<Ndbc.Buoy> b = Ndbc.parseObs(OBS);
        assertEquals(2, b.size());
        final Ndbc.Buoy met = b.get(0);
        assertEquals("44013", met.id);
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
                + "44013|N|3-meter discus buoy|3D15|BOSTON 16 NM East of Boston, MA|SCOOP payload|42.346 N 70.651 W (42&#176;20'46\" N)|E|FZUS51.KBOX |\n"
                + "44091|R|Waverider Buoy||Barnegat, NJ (209)||39.778 N 73.769 W|E|FZUS51.KPHI |\n";
        final Map<String, Ndbc.Station> t = Ndbc.parseStations(table);
        assertEquals(2, t.size());
        final List<Ndbc.Buoy> b = Ndbc.parseObs(OBS);
        assertEquals("44013", b.get(0).label());            // the id until named
        Ndbc.name(b, t);
        assertEquals("Boston", b.get(0).label());                       // the place, not the bearing
        assertEquals("BOSTON 16 NM East of Boston, MA", b.get(0).name);  // the record keeps the whole name
        assertEquals("Waverider Buoy", b.get(1).type);
    }

    @Test
    public void withinIsABoxAroundThePoint() {
        final List<Ndbc.Buoy> b = Ndbc.parseObs(OBS);
        assertEquals(1, Ndbc.within(b, 42.3, -70.6, 25).size());
        assertEquals(2, Ndbc.within(b, 41.0, -72.5, 150).size());
        assertEquals(0, Ndbc.within(b, 40.0, -124.0, 25).size());
    }

    @Test
    public void shortNamesArePlaces() {
        assertEquals("Cape San Martin", Ndbc.shortName("CAPE SAN MARTIN - 55NM West NW of Morro Bay, CA"));
        assertEquals("East Santa Barbara", Ndbc.shortName("EAST SANTA BARBARA  - 12NM Southwest of Santa Barbara, CA"));
        assertEquals("West Santa Barbara", Ndbc.shortName("WEST SANTA BARBARA  38 NM West of Santa Barbara, CA"));
        assertEquals("Santa Monica Basin", Ndbc.shortName("Santa Monica Basin - 33NM WSW of Santa Monica, CA"));
        assertEquals("Pt. San Luis", Ndbc.shortName("Pt. San Luis, CA - 18 NM South Southwest of Morro Bay, CA"));
        assertEquals("San Diego", Ndbc.shortName("9410170 - San Diego, CA"));
        assertEquals("Santa Monica Bay", Ndbc.shortName("Santa Monica Bay, CA (028)"));
        assertEquals("Ventura Nearshore", Ndbc.shortName("Ventura Nearshore, CA - 169"));
        assertEquals("Aptos Creek Nearshore", Ndbc.shortName("Aptos Creek Nearshore, CA 275"));
        assertEquals("Cabrillo Point, Monterey Bay", Ndbc.shortName("Cabrillo Point, Monterey Bay, CA  (158)"));
        assertEquals("SCRIPPS Nearshore", Ndbc.shortName("SCRIPPS Nearshore, CA (201)"));
        assertEquals("Sturgeon Bay CG Station", Ndbc.shortName("Sturgeon Bay CG Station, WI"));
        assertEquals("SD 1063", Ndbc.shortName("SD 1063 - 24 NM SSW of San Francisco, CA (Site of 46012)"));
        assertEquals("Pt Arena", Ndbc.shortName("PT ARENA - 19NM North of Point Arena, CA"));
        assertEquals("Reggae", Ndbc.shortName("Reggae"));
        assertEquals("", Ndbc.shortName(null));
        assertEquals("", Ndbc.shortName("  "));
    }
}
