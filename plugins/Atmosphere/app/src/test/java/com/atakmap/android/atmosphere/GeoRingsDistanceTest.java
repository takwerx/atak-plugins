package com.atakmap.android.atmosphere;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.atakmap.android.atmosphere.data.GeoRings;

import org.json.JSONObject;
import org.junit.Test;

public class GeoRingsDistanceTest {

    @Test
    public void milesToAnAreaAndWhichWay() throws Exception {
        // A square from 34N to 35N, 118W to 117W.
        final GeoRings.Area a = GeoRings.of(new JSONObject(
                "{\"type\":\"Polygon\",\"coordinates\":[[[-118,34],[-117,34],[-117,35],[-118,35],[-118,34]]]}"));
        assertEquals(0, a.distanceMiles(34.5, -117.5), 1e-9);            // inside
        assertEquals(69.2, a.distanceMiles(33.0, -117.5), 1.0);           // one degree south of the edge
        final double east = a.distanceMiles(34.5, -116.0);                // one degree of longitude east at 34.5N
        assertTrue(east > 55 && east < 58);
        final double b = a.bearingTo(33.0, -117.5);
        assertTrue("bearing " + b, b > 315 || b < 45);                    // the square is north of the point
    }
}
