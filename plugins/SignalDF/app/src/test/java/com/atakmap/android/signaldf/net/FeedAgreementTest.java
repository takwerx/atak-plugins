package com.atakmap.android.signaldf.net;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

import com.atakmap.android.signaldf.model.Bearing;

import org.junit.Test;

/**
 * The two feeds describe the same physical direction with different numbers.
 *
 * <p>This is the single assertion the whole plugin rests on. The fixtures are
 * the same frame of the same radio: {@code doa.xml} says 142 and
 * {@code DOA_value.html} says 218, and both mean an emitter 142 degrees
 * clockwise from the antenna array's zero. Any future change that makes the
 * adapters agree on {@code reportedDeg}, or disagree on
 * {@code arrayRelativeDeg}, has broken the convention handling -- and on a map
 * that shows up as a plausible line pointing the wrong way rather than as
 * anything that looks like a bug.
 */
public class FeedAgreementTest {

    private static final long RX = 1_700_000_000_000L;

    @Test
    public void bothFeedsResolveToTheSameDirection() {
        Bearing xml = new DoaXmlFeed()
                .parse(Fixtures.load("doa.xml"), RX).bearings.get(0);
        Bearing csv = new DoaCsvFeed()
                .parse(Fixtures.load("DOA_value_single.html"), RX).bearings.get(0);

        assertNotEquals("the fixtures must exercise the mirror",
                xml.reportedDeg, csv.reportedDeg, 1.0e-9);
        assertEquals(Fixtures.COMPASS, xml.arrayRelativeDeg, 1.0e-9);
        assertEquals(Fixtures.COMPASS, csv.arrayRelativeDeg, 1.0e-9);
    }

    /** And the scalars agree once each feed's own scaling is undone. */
    @Test
    public void bothFeedsResolveToTheSameFrequencyAndConfidence() {
        Bearing xml = new DoaXmlFeed()
                .parse(Fixtures.load("doa.xml"), RX).bearings.get(0);
        Bearing csv = new DoaCsvFeed()
                .parse(Fixtures.load("DOA_value_single.html"), RX).bearings.get(0);

        assertEquals(xml.frequencyHz, csv.frequencyHz, 1.0);
        assertEquals(xml.confidence, csv.confidence, 1.0e-9);
        assertEquals(xml.powerDb, csv.powerDb, 1.0e-9);
    }

    /**
     * Heading is never folded in by the radio on any export path, so the true
     * bearing is the consumer's sum and both feeds produce it identically.
     */
    @Test
    public void trueBearingIsTheConsumersSum() {
        Bearing xml = new DoaXmlFeed()
                .parse(Fixtures.load("doa.xml"), RX).bearings.get(0);
        // The radio's own formula: my_bearing + (360 - theta_0).
        assertEquals(308.0, xml.trueBearing(90.0), 1.0e-9);
        assertEquals(158.0, xml.trueBearing(300.0), 1.0e-9);
        assertEquals(Fixtures.COMPASS, xml.trueBearing(0.0), 1.0e-9);
    }
}
