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

    @Test
    public void noDimensionMeansNoFrames() throws Exception {
        final java.lang.reflect.Method m = RadarOverlay.class.getDeclaredMethod("parseTimes", String.class);
        m.setAccessible(true);
        assertTrue(((List<?>) m.invoke(null, "<Layer/>")).isEmpty());
        assertTrue(((List<?>) m.invoke(null, (Object) null)).isEmpty());
    }
}
