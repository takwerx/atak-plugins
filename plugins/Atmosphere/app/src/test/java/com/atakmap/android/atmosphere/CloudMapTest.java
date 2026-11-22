package com.atakmap.android.atmosphere;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.atakmap.android.atmosphere.cloud.NomadsCloud;
import com.atakmap.android.atmosphere.cloud.NomadsCloud.Layer;
import com.atakmap.android.atmosphere.smoke.SmokeGrid;

import org.junit.Test;

import java.io.File;
import java.nio.file.Files;
import java.util.Calendar;
import java.util.TimeZone;

/**
 * The cloud map layer against a real NOMADS grib filter response: GFS low, middle, high
 * and total cloud cover in one file, 11 by 10 degrees over California and Nevada (32-42
 * N, 125-114 W), the 2026-11-21 18Z run at f003. Each field is in the file twice, the
 * value at the hour (product template 4.0) and the average since hour 0 (4.8), and the
 * two differ a lot: the layer must draw the first. The expected numbers were decoded
 * from the file by a second, independent reader.
 *
 * <pre>
 *                      node 37.00 N 122.50 W    node 33.25 N 117.50 W
 *   low   at the hour        8.2                       0.0
 *         average           13.8                       0.0
 *   mid   at the hour      100.0                      96.8
 *         average          100.0                      33.3
 *   high  at the hour      100.0                      98.7
 *         average          100.0                      39.0
 *   total at the hour      100.0                      98.7
 *         average          100.0                      39.4
 * </pre>
 */
public class CloudMapTest {

    private static byte[] california() throws Exception {
        return Files.readAllBytes(
                new File("src/test/resources/gfs_cloud_california.grib2").toPath());
    }

    private static final double NODE_A_LAT = 37.0, NODE_A_LON = -122.5;
    private static final double NODE_B_LAT = 33.25, NODE_B_LON = -117.5;

    @Test
    public void drawsTheValueAtTheHourNotTheAverageSinceHourZero() throws Exception {
        final byte[] body = california();
        final SmokeGrid low = NomadsCloud.read(body, Layer.LOW);
        final SmokeGrid mid = NomadsCloud.read(body, Layer.MID);
        final SmokeGrid high = NomadsCloud.read(body, Layer.HIGH);
        final SmokeGrid total = NomadsCloud.read(body, Layer.TOTAL);
        // The average would read 13.8 here.
        assertEquals(8.2, low.sample(NODE_A_LAT, NODE_A_LON), 0.15);
        // The average would read 33.3, 39.0 and 39.4 here.
        assertEquals(96.8, mid.sample(NODE_B_LAT, NODE_B_LON), 0.15);
        assertEquals(98.7, high.sample(NODE_B_LAT, NODE_B_LON), 0.15);
        assertEquals(98.7, total.sample(NODE_B_LAT, NODE_B_LON), 0.15);
        assertEquals(100.0, mid.sample(NODE_A_LAT, NODE_A_LON), 0.15);
    }

    /**
     * What the pane's Here line reads, at the point the tak-dev emulator sat on
     * (39.237255 N, 123.150032 W), from a real response: the 2026-11-22 00Z run at f006,
     * 36-42 N, 126-120 W. The expected numbers come from a separate Python reader
     * (decode_cloud.py), template 4.0. The averages since hour 0 would read 99.9, 99.3,
     * 100 and 100 here, so a chip that drew the wrong message fails this.
     */
    @Test
    public void hereReadsEachChipAtThePanesPointFromTheHourNotTheAverage() throws Exception {
        final byte[] body = Files.readAllBytes(
                new File("src/test/resources/gfs_cloud_mendocino_f006.grib2").toPath());
        final double lat = 39.237255, lon = -123.150032;
        assertEquals(75.1, NomadsCloud.read(body, Layer.LOW).sample(lat, lon), 0.15);
        assertEquals(99.0, NomadsCloud.read(body, Layer.MID).sample(lat, lon), 0.15);
        assertEquals(19.7, NomadsCloud.read(body, Layer.HIGH).sample(lat, lon), 0.15);
        assertEquals(99.4, NomadsCloud.read(body, Layer.TOTAL).sample(lat, lon), 0.15);
        assertEquals("mostly cloudy", Layer.LOW.words(75.1f));
        assertEquals("a few clouds", Layer.HIGH.words(19.7f));
    }

    /** Quarter-degree nodes (multiples of 0.25) inside an interval. */
    private static int nodesIn(double low, double high) {
        return (int) (Math.floor(high / 0.25) - Math.ceil(low / 0.25)) + 1;
    }

    /**
     * Zoomed in to about 7 km on the emulator's point (39.237 N, 123.150 W) the box
     * asked for was the view plus half again, 0.2 degrees: one row of nodes, and that row
     * (39.25) was north of the point, so the picture was empty and Here said "outside the
     * area being drawn". The box now holds at least four cells, with nodes on both sides.
     */
    @Test
    public void aTightZoomStillAsksForARealLattice() {
        final double lat = 39.237255, lon = -123.150032;
        final double halfView = 0.05, pad = halfView; // the view, plus half again each side
        final double[] la = NomadsCloud.atLeast(lat - halfView - pad, lat + halfView + pad,
                NomadsCloud.MIN_SPAN, -90, 90);
        final double[] lo = NomadsCloud.atLeast(lon - halfView - pad, lon + halfView + pad,
                NomadsCloud.MIN_SPAN, -180, 180);
        assertEquals(1.0, la[1] - la[0], 1e-9);
        assertEquals(1.0, lo[1] - lo[0], 1e-9);
        assertTrue(nodesIn(la[0], la[1]) >= 4);
        assertTrue(nodesIn(lo[0], lo[1]) >= 4);
        // Nodes below and above, left and right of the point: the point is inside a cell.
        assertTrue(Math.floor(lat / 0.25) * 0.25 >= la[0]);
        assertTrue(Math.ceil(lat / 0.25) * 0.25 <= la[1]);
        assertTrue(Math.floor(lon / 0.25) * 0.25 >= lo[0]);
        assertTrue(Math.ceil(lon / 0.25) * 0.25 <= lo[1]);
        // The box that was asked for before holds one row and no column, so no cell.
        assertTrue(nodesIn(lat - halfView - pad, lat + halfView + pad) < 2);
        assertTrue(nodesIn(lon - halfView - pad, lon + halfView + pad) < 2);
    }

    @Test
    public void aBoxAtTheModelsEdgeSlidesInsteadOfShrinking() {
        final double[] a = NomadsCloud.atLeast(89.9, 90.0, 1.0, -90, 90);
        assertEquals(89.0, a[0], 1e-9);
        assertEquals(90.0, a[1], 1e-9);
        final double[] b = NomadsCloud.atLeast(-180.0, -179.95, 1.0, -180, 180);
        assertEquals(-180.0, b[0], 1e-9);
        assertEquals(-179.0, b[1], 1e-9);
        // A box already wide enough is left alone.
        final double[] c = NomadsCloud.atLeast(10, 14, 1.0, -180, 180);
        assertEquals(10, c[0], 1e-9);
        assertEquals(14, c[1], 1e-9);
    }

    @Test
    public void eachLayerIsItsOwnField() throws Exception {
        final byte[] body = california();
        final float low = NomadsCloud.read(body, Layer.LOW).sample(NODE_B_LAT, NODE_B_LON);
        final float mid = NomadsCloud.read(body, Layer.MID).sample(NODE_B_LAT, NODE_B_LON);
        final float high = NomadsCloud.read(body, Layer.HIGH).sample(NODE_B_LAT, NODE_B_LON);
        // Clear below, overcast above: three different decks, not the first message thrice.
        assertEquals(0.0, low, 0.15);
        assertTrue("mid " + mid, mid > 90);
        assertTrue("high " + high, high > 90);
    }

    @Test
    public void theGridIsSouthUpOverTheBoxInPercent() throws Exception {
        final SmokeGrid g = NomadsCloud.read(california(), Layer.TOTAL);
        assertEquals(45, g.nx);
        assertEquals(41, g.ny);
        assertEquals(-125.0, g.west, 1e-6);
        assertEquals(-114.0, g.east, 1e-6);
        assertEquals(32.0, g.south, 1e-6);
        assertEquals(42.0, g.north, 1e-6);
        for (float v : g.values)
            assertTrue("percent " + v, v >= 0 && v <= 100);
        // Valid at the run plus three hours: 2026-11-21 18Z + 3 h.
        final Calendar c = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
        c.clear();
        c.set(2026, Calendar.NOVEMBER, 21, 21, 0, 0);
        assertEquals(c.getTimeInMillis(), g.validTime);
    }

    @Test
    public void theBandsAreWhiteThinningToGray() {
        final Layer h = Layer.TOTAL;
        assertEquals(-1, h.band(9.9f));
        assertEquals(-1, h.band(Float.NaN));
        assertEquals(0, h.band(10f));
        assertEquals(1, h.band(30f));
        assertEquals(2, h.band(55f));
        assertEquals(3, h.band(80f));
        assertEquals(4, h.band(100f));
        assertEquals(0, h.color(3f));
        assertEquals("clear", h.words(3f));
        assertEquals("mostly cloudy", h.words(63f));
        assertEquals("overcast", h.words(98.7f));
        assertArrayEquals(new float[] { 30, 55, 80, 95 }, h.legendBreaks(), 0f);
        assertEquals(h.legendColors().length, h.legendBreaks().length + 1);
        // Opaque on the legend, so it reads on a dark pane.
        for (int c : h.legendColors())
            assertEquals(0xFF, c >>> 24);
    }

    @Test
    public void theRequestAsksTheGfsFilterForTheOneLayer() {
        final Calendar c = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
        c.clear();
        c.set(2026, Calendar.NOVEMBER, 21, 18, 0, 0);
        final String mid = NomadsCloud.url(Layer.MID, c.getTimeInMillis(), 3, -125, 32, -114, 42);
        assertTrue(mid, mid.startsWith("https://nomads.ncep.noaa.gov/cgi-bin/filter_gfs_0p25.pl?"));
        assertTrue(mid, mid.contains("dir=%2Fgfs.20261121%2F18%2Fatmos"));
        assertTrue(mid, mid.contains("&file=gfs.t18z.pgrb2.0p25.f003"));
        assertTrue(mid, mid.contains("&var_MCDC=on&lev_middle_cloud_layer=on&subregion="));
        assertTrue(mid, mid.contains("&toplat=42.000&leftlon=-125.000&rightlon=-114.000&bottomlat=32.000"));
        final String total = NomadsCloud.url(Layer.TOTAL, c.getTimeInMillis(), 0, -125, 32,
                -114, 42);
        assertTrue(total, total.contains("&file=gfs.t18z.pgrb2.0p25.f000&var_TCDC=on&lev_entire_atmosphere=on"));
    }

    @Test
    public void thePictureIsNorthUpAndClearWhereTheSkyIs() throws Exception {
        final SmokeGrid g = NomadsCloud.read(california(), Layer.TOTAL);
        final int w = g.nx * 2, h = g.ny * 2;
        final int[] px = NomadsCloud.render(g, Layer.TOTAL, w, h);
        assertEquals(w * h, px.length);
        final double cloudy = NomadsCloud.cloudyPercent(px);
        // Over half the box is cloud and some of it is clear: a real front's edge.
        assertTrue("cloudy " + cloudy, cloudy > 30 && cloudy < 100);
        // Row 0 is the north edge: its first pixel is the north-west corner's value.
        final float nw = g.sample(g.north - (g.north - g.south) / h / 2,
                g.west + (g.east - g.west) / w / 2);
        assertEquals(Layer.TOTAL.color(nw), px[0]);
        // The pixel over node B (33.25 N, 117.5 W) is that place's color, counting rows
        // down from the north edge, not up from the south edge.
        final int col = (int) ((NODE_B_LON - g.west) / (g.east - g.west) * w);
        final int row = (int) ((g.north - NODE_B_LAT) / (g.north - g.south) * h);
        final double lat = g.north - (row + 0.5) * (g.north - g.south) / h;
        final double lon = g.west + (col + 0.5) * (g.east - g.west) / w;
        assertEquals(NODE_B_LAT, lat, 0.2);
        assertEquals(Layer.TOTAL.color(g.sample(lat, lon)), px[row * w + col]);
    }
}
