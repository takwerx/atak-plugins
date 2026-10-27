package com.atakmap.android.atmosphere;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
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

    /** The loop plays up to twenty frames, evenly spread, oldest first, always ending at the newest. */
    @Test
    public void loopSpreadsTwentyFramesEndingAtTheNewest() {
        // The NWS: a frame every two minutes for two hours.
        final List<Integer> nws = RadarOverlay.playFrames(61);
        assertEquals(20, nws.size());
        assertEquals(0, (int) nws.get(0));
        assertEquals(60, (int) nws.get(19));
        for (int i = 1; i < nws.size(); i++)
            assertTrue(nws.get(i) - nws.get(i - 1) >= 3 && nws.get(i) - nws.get(i - 1) <= 4);
        // RainViewer's thirteen: all of them.
        final List<Integer> world = RadarOverlay.playFrames(13);
        assertEquals(13, world.size());
        assertEquals(0, (int) world.get(0));
        assertEquals(12, (int) world.get(12));
        assertTrue(RadarOverlay.playFrames(0).isEmpty());
    }

    /**
     * Only the loop's own frame schedules its next step. With the map locked on GPS
     * a move re-shows the frame on the map every second; when that rescheduled the
     * step, the 1.5 s hold on the newest frame never ran out and the loop stuck there.
     */
    @Test
    public void aMapMoveDoesNotPushBackTheLoopsStep() {
        final RadarOverlay.LoopTurn turn = new RadarOverlay.LoopTurn();
        // The loop moves to the newest frame and it goes on the map: the hold is scheduled.
        turn.owe();
        assertTrue(turn.take());
        // The map re-centres on the operator, three times during the hold: each re-show leaves it alone.
        assertFalse(turn.take());
        assertFalse(turn.take());
        assertFalse(turn.take());
        // The hold runs out, the loop starts over, and that frame schedules the next step.
        turn.owe();
        assertTrue(turn.take());
        assertFalse(turn.take());
    }
}
