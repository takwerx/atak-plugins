package com.atakmap.android.atmosphere;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.atakmap.android.atmosphere.overlay.RadarOverlay;

import org.junit.Test;

import java.util.List;

/** The pieces of the radar overlay that can be pinned on the JVM: the frame list and the request. */
public class RadarOverlayTest {

    @Test
    public void framesComeOffTheTimeDimensionOldestFirst() throws Exception {
        final java.lang.reflect.Method m = RadarOverlay.class.getDeclaredMethod("parseTimes", String.class);
        m.setAccessible(true);
        @SuppressWarnings("unchecked")
        final List<String> times = (List<String>) m.invoke(null,
                "<Layer><Dimension name=\"time\" default=\"2026-09-22T04:44:06Z\" units=\"ISO8601\" nearestValue=\"1\">"
                        + "2026-09-22T02:46:07.000Z,2026-09-22T02:48:06.000Z,2026-09-22T04:44:06.000Z</Dimension></Layer>");
        assertEquals(3, times.size());
        assertEquals("2026-09-22T02:46:07.000Z", times.get(0));
        assertEquals("2026-09-22T04:44:06.000Z", times.get(2));
    }

    /** GeoMet gives three hours of six-minute frames as start/end/period; every frame is offered. */
    @Test
    public void aPeriodIsExpandedIntoItsFrames() throws Exception {
        final java.lang.reflect.Method m = RadarOverlay.class.getDeclaredMethod("parseTimes", String.class);
        m.setAccessible(true);
        @SuppressWarnings("unchecked")
        final List<String> times = (List<String>) m.invoke(null,
                "<Layer><Dimension name=\"time\" default=\"2026-09-27T14:54:00Z\" units=\"ISO8601\">"
                        + "2026-09-27T11:54:00Z/2026-09-27T14:54:00Z/PT6M</Dimension></Layer>");
        assertEquals(31, times.size());
        assertEquals("2026-09-27T11:54:00Z", times.get(0));
        assertEquals("2026-09-27T12:00:00Z", times.get(1));
        assertEquals("2026-09-27T14:54:00Z", times.get(30));
        // A period that is not minutes or hours, or a run too long for a strip, keeps the ends only.
        @SuppressWarnings("unchecked")
        final List<String> ends = (List<String>) m.invoke(null,
                "<Dimension name=\"time\">2026-01-01T00:00:00Z/2026-12-31T00:00:00Z/P1D</Dimension>");
        assertEquals(2, ends.size());
    }

    @Test
    public void noDimensionMeansNoFrames() throws Exception {
        final java.lang.reflect.Method m = RadarOverlay.class.getDeclaredMethod("parseTimes", String.class);
        m.setAccessible(true);
        assertTrue(((List<?>) m.invoke(null, "<Layer/>")).isEmpty());
        assertTrue(((List<?>) m.invoke(null, (Object) null)).isEmpty());
    }
}
