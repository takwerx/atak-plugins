package com.atakmap.android.signaldf.net;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.atakmap.android.signaldf.model.Bearing;
import com.atakmap.android.signaldf.model.BearingConvention;
import com.atakmap.android.signaldf.model.FeedFrame;
import com.atakmap.android.signaldf.model.FeedSource;

import org.junit.Test;

/** The DOA_value.html adapter: the mirror, multiple VFOs, and torn reads. */
public class DoaCsvFeedTest {

    private static final long RX = 1_700_000_000_000L;
    private static final double EPS = 1.0e-9;

    private static Bearing only(FeedFrame f) {
        assertEquals("expected exactly one bearing", 1, f.bearings.size());
        return f.bearings.get(0);
    }

    @Test
    public void readsTheSampleLine() {
        FeedFrame f = new DoaCsvFeed().parse(Fixtures.load("DOA_value_single.html"), RX);
        assertEquals(FeedSource.CSV, f.source);
        assertEquals(0, f.discarded);

        Bearing b = only(f);
        assertEquals("KRAKEN-1", b.stationId);
        assertEquals(0, b.vfo);
        assertEquals(RX, b.receivedMs);
        assertEquals(1_757_894_400_000L, b.timeMs);
        assertTrue(b.positionReported);
        assertEquals(39.0, b.latitude, EPS);
        assertEquals(-120.0, b.longitude, EPS);
    }

    /**
     * The trap, and it bites in the direction nobody expects. This feed writes
     * {@code 360 - theta_0} where the XML writes {@code theta_0}, so it looks
     * like the odd one out -- but {@code 360 - theta_0} is already the compass
     * bearing, so this is the feed that needs nothing applied. Signal DF
     * shipped it the wrong way round until the radio's own
     * {@code calculate_end_lat_lng} settled it.
     */
    @Test
    public void carriesTheCompassBearingAlready() {
        Bearing b = only(new DoaCsvFeed().parse(Fixtures.load("DOA_value_single.html"), RX));
        assertEquals(BearingConvention.DIRECT, b.convention);
        assertEquals(Fixtures.CSV_REPORTED, b.reportedDeg, EPS);
        assertEquals(Fixtures.COMPASS, b.arrayRelativeDeg, EPS);
    }

    /** This feed applies none of the XML's scaling: Hz, 0..1, dB as-is. */
    @Test
    public void scalarsAreUnscaledHere() {
        Bearing b = only(new DoaCsvFeed().parse(Fixtures.load("DOA_value_single.html"), RX));
        assertEquals(155_160_000.0, b.frequencyHz, 1.0);
        assertEquals(0.87, b.confidence, EPS);
        assertEquals(-37.6, b.powerDb, EPS);
        assertFalse(b.powerClipped);
    }

    @Test
    public void countsTheSpectrumWithoutKeepingIt() {
        Bearing b = only(new DoaCsvFeed().parse(Fixtures.load("DOA_value_single.html"), RX));
        assertEquals(360, b.spectrumSampleCount);
    }

    /**
     * This feed carries no ADC overdrive flag, correlated-source count or SNR.
     * They stay at their absent values so that a bearing which was never
     * checked for front-end saturation cannot be mistaken for one that passed.
     */
    @Test
    public void fieldsThisFeedLacksStayAbsent() {
        Bearing b = only(new DoaCsvFeed().parse(Fixtures.load("DOA_value_single.html"), RX));
        assertEquals(-1, b.correlatedSources);
        assertTrue(Double.isNaN(b.snrDb));
    }

    /** One line per active VFO, in order, unlabeled. Two emitters, two rows. */
    @Test
    public void everyActiveVfoGetsItsOwnBearing() {
        FeedFrame f = new DoaCsvFeed().parse(Fixtures.load("DOA_value_two_vfo.html"), RX);
        assertEquals(2, f.bearings.size());
        assertEquals(0, f.discarded);

        assertEquals(0, f.bearings.get(0).vfo);
        assertEquals(155_160_000.0, f.bearings.get(0).frequencyHz, 1.0);
        assertEquals(Fixtures.COMPASS, f.bearings.get(0).arrayRelativeDeg, EPS);

        assertEquals(1, f.bearings.get(1).vfo);
        assertEquals(162_550_000.0, f.bearings.get(1).frequencyHz, 1.0);
        assertEquals(43.0, f.bearings.get(1).reportedDeg, EPS);
        assertEquals(43.0, f.bearings.get(1).arrayRelativeDeg, EPS);
    }

    /**
     * The DSP rewrites this file in place on every frame, so a poll can land
     * mid-write. A line cut inside the fixed header has no bearing record in
     * it: skipped, counted, and the whole read is not failed for it.
     */
    @Test
    public void aLineTornInsideTheHeaderIsSkippedAndCounted() {
        FeedFrame f = new DoaCsvFeed().parse(Fixtures.load("DOA_value_torn.html"), RX);
        assertEquals(1, f.bearings.size());
        assertEquals(1, f.discarded);
        assertFalse("a discard has to reach the pane in words", f.note.isEmpty());
        assertEquals(Fixtures.COMPASS, f.bearings.get(0).arrayRelativeDeg, EPS);
    }

    /**
     * A line cut inside the spectrum still has a whole header, so the bearing
     * is good and only the lobe is short. Discarding it would throw away a
     * usable measurement.
     */
    @Test
    public void aLineTornInsideTheSpectrumIsKept() {
        FeedFrame f = new DoaCsvFeed().parse(
                Fixtures.load("DOA_value_short_spectrum.html"), RX);
        assertEquals(0, f.discarded);
        Bearing b = only(f);
        assertEquals(Fixtures.COMPASS, b.arrayRelativeDeg, EPS);
        assertEquals(12, b.spectrumSampleCount);
    }

    /**
     * Field 13 is the literal "GPS". It is the cheapest proof the fields line
     * up, and a line read at an offset would otherwise yield a plausible
     * bearing built from the wrong columns.
     */
    @Test
    public void aLineAtTheWrongOffsetIsRejected() {
        String shifted = "0.9," + Fixtures.load("DOA_value_single.html").trim();
        FeedFrame f = new DoaCsvFeed().parse(shifted, RX);
        assertTrue(f.isEmpty());
        assertEquals(1, f.discarded);
    }

    /**
     * The VFO index is the line's position in the file, not a count of the
     * lines that parsed. The writer emits the active VFOs in order and does not
     * label them, so a line that could not be read leaves a hole rather than
     * pulling everything after it up one. Getting this wrong files one
     * emitter's bearing under another emitter's frequency, which is the exact
     * shape of mistake this plugin cannot afford: it draws a plausible line.
     */
    @Test
    public void vfoIndexIsThePositionInTheFileNotTheAcceptedCount() {
        // Line 0 torn, line 1 whole: the survivor is VFO 1, not VFO 0.
        String torn = Fixtures.load("DOA_value_single.html").trim().substring(0, 44);
        String body = torn + "\n" + Fixtures.load("DOA_value_single.html").trim() + "\n";
        FeedFrame f = new DoaCsvFeed().parse(body, RX);
        assertEquals(1, f.bearings.size());
        assertEquals(1, f.discarded);
        assertEquals(1, f.bearings.get(0).vfo);
    }

    /** And with both lines readable the indices are simply 0 and 1. */
    @Test
    public void vfoIndexFollowsTheRowOrder() {
        FeedFrame f = new DoaCsvFeed().parse(Fixtures.load("DOA_value_two_vfo.html"), RX);
        assertEquals(0, f.bearings.get(0).vfo);
        assertEquals(1, f.bearings.get(1).vfo);
    }

    /** The wrong port answering 200 with an error page is one note, not noise. */
    @Test
    public void markupIsReportedAsTheWrongFeed() {
        FeedFrame f = new DoaCsvFeed().parse(
                "<!DOCTYPE html><html><body>404</body></html>", RX);
        assertTrue(f.isEmpty());
        assertEquals(0, f.discarded);
        assertTrue(f.note.contains("not the CSV feed"));
    }

    @Test
    public void emptyAndNullBodiesAreNotErrors() {
        assertTrue(new DoaCsvFeed().parse(null, RX).isEmpty());
        assertTrue(new DoaCsvFeed().parse("\n\n", RX).isEmpty());
    }

    @Test
    public void nullIslandIsTreatedAsNoPosition() {
        String body = Fixtures.load("DOA_value_single.html")
                .replace(",39.000000,-120.000000,", ",0.0,0.0,");
        Bearing b = only(new DoaCsvFeed().parse(body, RX));
        assertFalse(b.positionReported);
    }

    /** The bench seam, should the operator's build have stopped mirroring. */
    @Test
    public void conventionCanBeFlippedForTheBench() {
        Bearing b = only(new DoaCsvFeed(BearingConvention.MIRRORED)
                .parse(Fixtures.load("DOA_value_single.html"), RX));
        assertEquals(218.0, b.reportedDeg, EPS);
        assertEquals(142.0, b.arrayRelativeDeg, EPS);
    }
}
