package com.atakmap.android.atmosphere;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.atakmap.android.atmosphere.wind.Grib2;
import com.atakmap.android.atmosphere.wind.Lcc;
import com.atakmap.android.atmosphere.wind.NomadsWind;
import com.atakmap.android.atmosphere.overlay.WindScaleView;
import com.atakmap.android.atmosphere.wind.NomadsWind.Model;
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
    public void gridCoversTheRequestedBoxAtThreeKilometers() throws Exception {
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
        final String url = NomadsWind.url(Model.HRRR, run, 7, -119.5, 32.5, -115.5, 35.5);
        assertTrue(url, url.contains("filter_hrrr_2d.pl"));
        assertTrue(url, url.contains("dir=%2Fhrrr.20260922%2Fconus"));
        assertTrue(url, url.contains("file=hrrr.t03z.wrfsfcf07.grib2"));
        assertTrue(url, url.contains("toplat=35.500&leftlon=-119.500&rightlon=-115.500&bottomlat=32.500"));
    }

    // ---- the model ladder ---------------------------------------------------------

    @Test
    public void eachModelBuildsItsOwnRequest() {
        final long run = java.time.Instant.parse("2026-09-22T03:00:00Z").toEpochMilli();
        final String rap = NomadsWind.url(Model.RAP, run, 7, -125, 24, -66, 50);
        assertTrue(rap, rap.contains("filter_rap.pl"));
        assertTrue(rap, rap.contains("dir=%2Frap.20260922"));
        assertTrue(rap, rap.contains("file=rap.t03z.awp130pgrbf07.grib2"));

        // GFS keeps its files under the run hour and counts forecast hours in three digits.
        final long gfsRun = java.time.Instant.parse("2026-09-22T00:00:00Z").toEpochMilli();
        final String gfs = NomadsWind.url(Model.GFS, gfsRun, 3, -160, 15, -60, 60);
        assertTrue(gfs, gfs.contains("filter_gfs_0p25.pl"));
        assertTrue(gfs, gfs.contains("dir=%2Fgfs.20260922%2F00%2Fatmos"));
        assertTrue(gfs, gfs.contains("file=gfs.t00z.pgrb2.0p25.f003"));
    }

    @Test
    public void runsAreTheNewestCertainlyPublished() {
        final long now = java.time.Instant.parse("2026-09-22T05:40:00Z").toEpochMilli();
        // HRRR and RAP run hourly and are two hours behind.
        assertEquals(java.time.Instant.parse("2026-09-22T03:00:00Z").toEpochMilli(),
                Model.HRRR.latestRun(now));
        assertEquals(java.time.Instant.parse("2026-09-22T03:00:00Z").toEpochMilli(),
                Model.RAP.latestRun(now));
        // GFS runs every six hours; six back from 05:40 is 23:40, which floors to 18Z.
        assertEquals(java.time.Instant.parse("2026-09-21T18:00:00Z").toEpochMilli(),
                Model.GFS.latestRun(now));
        assertEquals(java.time.Instant.parse("2026-09-22T02:00:00Z").toEpochMilli(),
                Model.HRRR.previousRun(java.time.Instant.parse("2026-09-22T03:00:00Z").toEpochMilli()));
        assertEquals(java.time.Instant.parse("2026-09-21T12:00:00Z").toEpochMilli(),
                Model.GFS.previousRun(java.time.Instant.parse("2026-09-21T18:00:00Z").toEpochMilli()));
    }

    @Test
    public void aRunReachesAsFarAsNomadsPublishesIt() {
        // Measured against NOMADS on 2026-09-23, not assumed: hrrr.t12z.wrfsfcf48 is
        // on disk and hrrr.t13z stops at f17, rap.t12z stops at f21, and GFS runs to
        // f384 but only hourly to f120.
        final long z12 = java.time.Instant.parse("2026-09-23T12:00:00Z").toEpochMilli();
        final long z13 = java.time.Instant.parse("2026-09-23T13:00:00Z").toEpochMilli();
        final long z18 = java.time.Instant.parse("2026-09-23T18:00:00Z").toEpochMilli();
        assertEquals(48, Model.HRRR.forecastHours(z12));
        assertEquals(48, Model.HRRR.forecastHours(z18));
        assertEquals(NomadsWind.HOURS, Model.HRRR.forecastHours(z13));
        assertEquals(21, Model.RAP.forecastHours(z12));
        assertEquals(120, Model.GFS.forecastHours(z12));
        // Whatever the run, a model reaches at least the short-run floor, and the
        // picker is built off that number.
        for (Model m : Model.values())
            for (int h = 0; h < 24; h++)
                assertTrue(m + " at " + h + "z", m.forecastHours(z12 + h * 3_600_000L)
                        >= NomadsWind.HOURS);
    }

    @Test
    public void aHeightSaysAHeightAndNothingElse() {
        // The buttons carry these, so they are the whole of what a crew reads. The
        // cell size used to sit beside them and is gone: how much ground one forecast
        // cell covers is not something anybody can act on either (operator,
        // 2026-09-22: "what does 33ft above ground 2 mi detail mean?").
        assertEquals("Ground", NomadsWind.Level.AGL_10.shortLabel(false));
        assertEquals("Ground", NomadsWind.Level.AGL_10.shortLabel(true));
        assertEquals("260 ft", NomadsWind.Level.AGL_80.shortLabel(false));
        assertEquals("80 m", NomadsWind.Level.AGL_80.shortLabel(true));
        assertEquals("5,000 ft", NomadsWind.Level.MB_850.shortLabel(false));
        assertEquals("10,000 ft", NomadsWind.Level.MB_700.shortLabel(false));
        assertEquals("3,000 m", NomadsWind.Level.MB_700.shortLabel(true));
        // "wtf is HRRR?" -- the model's name is for the log and nowhere else.
        for (NomadsWind.Level l : NomadsWind.Level.values())
            for (boolean metric : new boolean[] { false, true })
                for (String shown : new String[] { l.shortLabel(metric),
                        l.label(metric, false), l.label(metric, true) })
                    for (Model m : Model.values())
                        assertFalse(shown + " leaks " + m.label, shown.contains(m.label));
    }

    @Test
    public void theHeightLadderRisesAndLabelsItself() {
        final NomadsWind.Level[] all = NomadsWind.Level.values();
        assertEquals(NomadsWind.Level.AGL_10, all[0]);
        int last = -1;
        for (NomadsWind.Level l : all) {
            assertTrue("heights must rise: " + l, l.approxMeters > last);
            last = l.approxMeters;
        }
        // The two lowest are heights above ground and live in HRRR's surface file;
        // everything above is a pressure surface that HRRR cannot serve.
        assertTrue(NomadsWind.Level.AGL_10.surface);
        assertTrue(NomadsWind.Level.AGL_80.surface);
        assertFalse(NomadsWind.Level.MB_700.surface);
        assertEquals(700, NomadsWind.Level.MB_700.millibars());
        assertEquals(0, NomadsWind.Level.AGL_10.millibars());

        assertEquals("10 m above ground", NomadsWind.Level.AGL_10.label(true, false));
        assertEquals("33 ft above ground", NomadsWind.Level.AGL_10.label(false, false));
        // A crew gets the height alone; the millibars are for aviation units, where a
        // pilot is briefed on the pressure surface itself.
        assertEquals("about 10,000 ft", NomadsWind.Level.MB_700.label(false, false));
        assertEquals("about 10,000 ft (700 mb)", NomadsWind.Level.MB_700.label(false, true));
        assertEquals("about 3,000 m", NomadsWind.Level.MB_700.label(true, false));
    }

    @Test
    public void aHeightAboveTheSurfaceTakesTheAnswerOffHrrr() {
        // HRRR has no pressure-level filter on NOMADS, so a close-in view that would
        // otherwise be HRRR has to come from RAP once the slider leaves the ground.
        assertEquals(Model.HRRR,
                NomadsWind.forView(-118.5, 33, -116.5, 35, NomadsWind.Level.AGL_10));
        assertEquals(Model.HRRR,
                NomadsWind.forView(-118.5, 33, -116.5, 35, NomadsWind.Level.AGL_80));
        assertEquals(Model.RAP,
                NomadsWind.forView(-118.5, 33, -116.5, 35, NomadsWind.Level.MB_700));
        // Outside CONUS it is GFS at every height.
        assertEquals(Model.GFS,
                NomadsWind.forView(-159, 19, -154, 23, NomadsWind.Level.MB_700));
    }

    @Test
    public void theRequestCarriesTheLevel() {
        final long run = java.time.Instant.parse("2026-09-22T18:00:00Z").toEpochMilli();
        final String url = NomadsWind.url(Model.RAP, NomadsWind.Level.MB_700, run, 1,
                -119, 33, -117, 35);
        assertTrue(url, url.contains("lev_700_mb=on"));
        assertFalse(url, url.contains("lev_10_m_above_ground"));
    }

    @Test
    public void theViewPicksTheModel() {
        // A fire line: HRRR's 3 km cells.
        assertEquals(Model.HRRR, NomadsWind.forView(-118.5, 33, -116.5, 35));
        // Half the west: too wide for HRRR, still CONUS, so RAP.
        assertEquals(Model.RAP, NomadsWind.forView(-125, 30, -95, 45));
        // The continent: wider than RAP is asked for.
        assertEquals(Model.GFS, NomadsWind.forView(-160, 20, -60, 60));
        // Alaska and Hawaii are small views the CONUS models do not reach.
        assertEquals(Model.GFS, NomadsWind.forView(-155, 60, -145, 66));
        assertEquals(Model.GFS, NomadsWind.forView(-159, 19, -154, 23));
    }

    @Test
    public void theLegendUsesTheSameBandsAsTheParticles() {
        // The bar and the trails read the same table, so a color on the map always
        // means what the legend says. Six bands, five boundaries, rising.
        assertEquals(6, WindScaleView.bandCount());
        float last = -1;
        for (int i = 0; i < WindScaleView.bandCount() - 1; i++) {
            final float edge = WindScaleView.bandEdgeMs(i);
            assertTrue("band edges must rise: " + edge + " after " + last, edge > last);
            last = edge;
        }
        // 18 m/s is the last boundary, so a gale lands in the top band.
        assertEquals(18f, WindScaleView.bandEdgeMs(WindScaleView.bandCount() - 2), 1e-6);
    }

    @Test
    public void everyModelsRunLandsOnItsOwnCadence() {
        // The overlay recomputes the run when the zoom changes the model; a run must
        // always be a time that model publishes, or the filter has no such file.
        final long now = java.time.Instant.parse("2026-09-22T14:20:00Z").toEpochMilli();
        for (Model m : Model.values()) {
            final long run = m.latestRun(now);
            final java.util.Calendar c = java.util.Calendar.getInstance(
                    java.util.TimeZone.getTimeZone("UTC"));
            c.setTimeInMillis(run);
            assertEquals("run is on the hour", 0, c.get(java.util.Calendar.MINUTE));
            assertTrue(m + " run " + c.get(java.util.Calendar.HOUR_OF_DAY) + "Z is not published",
                    m == Model.GFS ? c.get(java.util.Calendar.HOUR_OF_DAY) % 6 == 0 : true);
            assertTrue("run is in the past", run < now);
        }
    }

    @Test
    public void rapReadsAsLambertAndCoversConus() throws Exception {
        final byte[] body = Files.readAllBytes(
                new File("src/test/resources/rap_wind_conus.grib2").toPath());
        final List<Grib2.Message> msgs = Grib2.read(body);
        assertEquals(2, msgs.size());
        assertEquals(427, msgs.get(0).nx);
        assertEquals(240, msgs.get(0).ny);
        assertNotNull("RAP is on a Lambert grid", msgs.get(0).grid);

        final WindGrid grid = NomadsWind.read(body, 160);
        for (double[] p : new double[][] { { 33.835, -117.573 }, { 44.0, -103.0 }, { 30.0, -90.0 } }) {
            final float speed = grid.speed(p[0], p[1]);
            assertFalse("no wind at " + p[0] + "," + p[1], Float.isNaN(speed));
            assertTrue("wind " + speed + " m/s at " + p[0] + "," + p[1], speed >= 0 && speed < 60);
        }
    }

    @Test
    public void gfsReadsAsALatLonGridAtItsOwnResolution() throws Exception {
        final byte[] body = Files.readAllBytes(
                new File("src/test/resources/gfs_wind_conus.grib2").toPath());
        final List<Grib2.Message> msgs = Grib2.read(body);
        assertEquals(2, msgs.size());
        assertNotNull("GFS is on a lat/lon grid", msgs.get(0).latLon);
        assertEquals(237, msgs.get(0).latLon.ni);
        assertEquals(105, msgs.get(0).latLon.nj);

        // Taken as it stands: no resampling, and the 0..360 longitudes come back as the
        // map's own -180..180.
        final WindGrid grid = NomadsWind.read(body, 160);
        assertEquals(237, grid.nx);
        assertEquals(105, grid.ny);
        assertEquals(-125.0, grid.west, 1e-6);
        assertEquals(-66.0, grid.east, 1e-6);
        assertEquals(24.0, grid.south, 1e-6);
        assertEquals(50.0, grid.north, 1e-6);
        final float speed = grid.speed(33.835, -117.573);
        assertFalse(Float.isNaN(speed));
        assertTrue("wind " + speed + " m/s", speed >= 0 && speed < 60);
    }
}
