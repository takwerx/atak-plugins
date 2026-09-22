package com.atakmap.android.atmosphere;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.atakmap.android.atmosphere.wind.Grib2;
import com.atakmap.android.atmosphere.wind.Lcc;
import com.atakmap.android.atmosphere.wind.NomadsWind;
import com.atakmap.android.atmosphere.wind.WindGrid;

import org.junit.Test;

import java.io.File;
import java.nio.file.Files;
import java.util.List;

/**
 * The wind grid against a real NOMADS grib filter response: HRRR 10 m U and V over
 * a 4 by 3 degree box around Southern California, saved 2026-09-22 03Z run, f01.
 */
public class WindGridTest {

    private static byte[] socal() throws Exception {
        return Files.readAllBytes(new File("src/test/resources/hrrr_wind_socal.grib2").toPath());
    }

    @Test
    public void readsBothWindComponents() throws Exception {
        final List<Grib2.Message> msgs = Grib2.read(socal());
        assertEquals(2, msgs.size());
        assertEquals(2, msgs.get(0).category);
        assertEquals(2, msgs.get(0).number);   // UGRD
        assertEquals(3, msgs.get(1).number);   // VGRD
        assertEquals(144, msgs.get(0).nx);
        assertEquals(135, msgs.get(0).ny);
        assertEquals(1, msgs.get(0).forecastHours);
        assertTrue("rows run south to north on HRRR", msgs.get(0).jNorthUp);
        int finite = 0;
        for (float f : msgs.get(0).values) {
            if (!Float.isNaN(f)) {
                finite++;
                assertTrue("wind component out of range: " + f, Math.abs(f) < 60);
            }
        }
        assertEquals(144 * 135, finite);
    }

    @Test
    public void gridCoversTheRequestedBoxAtThreeKilometres() throws Exception {
        // Requested: lon -119.5..-115.5, lat 32.5..35.5. The filter cuts an index box on
        // the tilted Lambert grid that covers it, so the box's corners all fall inside
        // the grid within a cell of its edge (its own corners overshoot: the SW one
        // lands near 31.8 N, 26 rows below the requested edge), and the
        // cells are HRRR's 3 km.
        final Grib2.Message u = Grib2.read(socal()).get(0);
        for (double[] ll : new double[][] { { 32.5, -119.5 }, { 32.5, -115.5 }, { 35.5, -119.5 }, { 35.5, -115.5 } }) {
            final double[] ij = u.grid.gridIndex(ll[0], ll[1]);
            assertTrue("corner " + ll[0] + "," + ll[1] + " at " + ij[0] + "," + ij[1],
                    ij[0] >= -1.5 && ij[0] <= u.nx + 0.5 && ij[1] >= -1.5 && ij[1] <= u.ny + 0.5);
        }
        final double[] a = u.grid.latLonOf(0, 0), b = u.grid.latLonOf(1, 0), c = u.grid.latLonOf(0, 1);
        assertEquals(3000, haversine(a, b), 150);
        assertEquals(3000, haversine(a, c), 150);
        assertEquals(3000, u.grid.dx, 1);
    }

    private static double haversine(double[] p, double[] q) {
        final double r = 6371229, dLat = Math.toRadians(q[0] - p[0]), dLon = Math.toRadians(q[1] - p[1]);
        final double h = Math.sin(dLat / 2) * Math.sin(dLat / 2) + Math.cos(Math.toRadians(p[0]))
                * Math.cos(Math.toRadians(q[0])) * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return 2 * r * Math.asin(Math.sqrt(h));
    }

    @Test
    public void lambertRoundTrips() {
        final Lcc g = new Lcc(6371229, 38.5, 38.5, 38.5, -97.5, 21.138, -122.72, 3000, 3000);
        for (double[] ll : new double[][] { { 33.835, -117.573 }, { 45.0, -70.0 }, { 25.0, -100.0 } }) {
            final double[] xy = g.forward(ll[0], ll[1]);
            final double[] back = g.inverse(xy[0], xy[1]);
            assertEquals(ll[0], back[0], 1e-6);
            assertEquals(ll[1], back[1], 1e-6);
        }
    }

    @Test
    public void regularGridSamplesPlausibleWindAtCorona() throws Exception {
        final WindGrid grid = NomadsWind.read(socal(), 128);
        assertTrue(grid.contains(33.835, -117.573));
        final float speed = grid.speed(33.835, -117.573);
        assertFalse("wind at Corona should be a number", Float.isNaN(speed));
        // NWS forecast the same evening: 5 to 10 mph, i.e. 2 to 5 m/s; HRRR near that.
        assertTrue("wind at Corona " + speed + " m/s", speed >= 0 && speed < 12);
        assertTrue(Float.isNaN(grid.speed(40.0, -100.0)));
        assertTrue(grid.validTime > 0);
    }

    @Test
    public void filterUrlNamesRunHourAndBox() {
        final long run = java.time.Instant.parse("2026-09-22T03:00:00Z").toEpochMilli();
        final String url = NomadsWind.url(run, 7, -119.5, 32.5, -115.5, 35.5);
        assertTrue(url, url.contains("dir=%2Fhrrr.20260922%2Fconus"));
        assertTrue(url, url.contains("file=hrrr.t03z.wrfsfcf07.grib2"));
        assertTrue(url, url.contains("toplat=35.500&leftlon=-119.500&rightlon=-115.500&bottomlat=32.500"));
        assertEquals(18, NomadsWind.hoursFor(run));
        assertEquals(48, NomadsWind.hoursFor(java.time.Instant.parse("2026-09-22T06:00:00Z").toEpochMilli()));
        assertEquals(java.time.Instant.parse("2026-09-22T03:00:00Z").toEpochMilli(),
                NomadsWind.latestRun(java.time.Instant.parse("2026-09-22T05:40:00Z").toEpochMilli()));
    }
}
