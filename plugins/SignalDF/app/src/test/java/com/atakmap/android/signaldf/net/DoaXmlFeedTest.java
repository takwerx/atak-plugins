package com.atakmap.android.signaldf.net;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.atakmap.android.signaldf.model.Bearing;
import com.atakmap.android.signaldf.model.BearingConvention;
import com.atakmap.android.signaldf.model.FeedFrame;
import com.atakmap.android.signaldf.model.FeedSource;

import org.junit.Test;

/** The doa.xml adapter: its scaling, its convention, and what it refuses. */
public class DoaXmlFeedTest {

    private static final long RX = 1_700_000_000_000L;
    private static final double EPS = 1.0e-9;

    private static Bearing only(FeedFrame f) {
        assertEquals("expected exactly one bearing", 1, f.bearings.size());
        return f.bearings.get(0);
    }

    @Test
    public void readsTheSampleFrame() {
        FeedFrame f = new DoaXmlFeed().parse(Fixtures.load("doa.xml"), RX);
        assertEquals(FeedSource.XML, f.source);
        assertEquals(0, f.discarded);

        Bearing b = only(f);
        assertEquals("KRAKEN-1", b.stationId);
        assertEquals(0, b.vfo);
        assertEquals(RX, b.receivedMs);
        assertEquals(1_757_894_400_000L, b.timeMs);
        assertTrue(b.positionReported);
        assertEquals(39.0, b.latitude, EPS);
        assertEquals(-120.0, b.longitude, EPS);
        assertEquals(1, b.correlatedSources);
        assertEquals(21.3, b.snrDb, EPS);
        assertFalse(b.adcOverdrive);
    }

    @Test
    public void exportsThetaWithoutMirroring() {
        Bearing b = only(new DoaXmlFeed().parse(Fixtures.load("doa.xml"), RX));
        assertEquals(BearingConvention.DIRECT, b.convention);
        assertEquals(Fixtures.THETA, b.reportedDeg, EPS);
        assertEquals(Fixtures.THETA, b.arrayRelativeDeg, EPS);
    }

    /** FREQUENCY is megahertz here and hertz on the other two feeds. */
    @Test
    public void frequencyIsMegahertzOnTheWire() {
        Bearing b = only(new DoaXmlFeed().parse(Fixtures.load("doa.xml"), RX));
        assertEquals(155_160_000.0, b.frequencyHz, 1.0);
        assertEquals(155.16, b.frequencyMHz(), 1.0e-6);
    }

    /** CONF is conf * 100; everything downstream wants 0..1. */
    @Test
    public void confidenceIsScaledByAHundred() {
        Bearing b = only(new DoaXmlFeed().parse(Fixtures.load("doa.xml"), RX));
        assertEquals(0.87, b.confidence, EPS);
    }

    /** PWR is power + 100. */
    @Test
    public void powerIsOffsetByAHundred() {
        Bearing b = only(new DoaXmlFeed().parse(Fixtures.load("doa.xml"), RX));
        assertEquals(-37.6, b.powerDb, 1.0e-9);
        assertFalse(b.powerClipped);
    }

    /**
     * max(-100, power + 100) means -100 is a floor, not a reading. A plugin
     * that treats it as a measurement reports a precise-looking -200 dB.
     */
    @Test
    public void powerAtTheFloorIsFlaggedAsClipped() {
        Bearing b = only(new DoaXmlFeed().parse(xml("<PWR>-100.0</PWR>"), RX));
        assertTrue(b.powerClipped);
        assertEquals(-200.0, b.powerDb, EPS);
    }

    /** GPS_TIME is seconds where TIME is milliseconds, and neither says so. */
    @Test
    public void gpsTimeInSecondsBecomesMilliseconds() {
        Bearing b = only(new DoaXmlFeed().parse(Fixtures.load("doa.xml"), RX));
        assertEquals(1_757_894_399_000L, b.gpsTimeMs);
    }

    /** 0.0/0.0 is the radio's "no GPS", not a position off West Africa. */
    @Test
    public void nullIslandIsTreatedAsNoPosition() {
        Bearing b = only(new DoaXmlFeed().parse(
                xml("<LATITUDE>0.0</LATITUDE><LONGITUDE>0.0</LONGITUDE>"), RX));
        assertFalse(b.positionReported);
    }

    /**
     * A reported heading of 0.0 is kept as reported, because the radio writes
     * 0.0 both for true north and for "I have none" and the parser cannot tell
     * them apart. An absent element is a different fact and is recorded as one.
     */
    @Test
    public void headingPresenceIsRecordedSeparatelyFromItsValue() {
        Bearing withHeading = only(new DoaXmlFeed().parse(Fixtures.load("doa.xml"), RX));
        assertTrue(withHeading.headingReported);
        assertEquals(0.0, withHeading.headingDeg, EPS);

        String noHeading = Fixtures.load("doa.xml")
                .replace("<HEADING>0.0</HEADING>", "");
        Bearing without = only(new DoaXmlFeed().parse(noHeading, RX));
        assertFalse(without.headingReported);
    }

    @Test
    public void noSpectrumOnThisFeed() {
        Bearing b = only(new DoaXmlFeed().parse(Fixtures.load("doa.xml"), RX));
        assertEquals(0, b.spectrumSampleCount);
    }

    @Test
    public void emptyAndNullBodiesAreNotErrors() {
        assertTrue(new DoaXmlFeed().parse(null, RX).isEmpty());
        assertTrue(new DoaXmlFeed().parse("   ", RX).isEmpty());
    }

    @Test
    public void malformedXmlIsCountedNotThrown() {
        FeedFrame f = new DoaXmlFeed().parse("<DATA><DOA>142.0</DA", RX);
        assertTrue(f.isEmpty());
        assertEquals(1, f.discarded);
    }

    @Test
    public void aDocumentWithoutADoaIsDiscarded() {
        FeedFrame f = new DoaXmlFeed().parse("<DATA><PWR>62.4</PWR></DATA>", RX);
        assertTrue(f.isEmpty());
        assertEquals(1, f.discarded);
    }

    /**
     * A doctype never appears in the radio's own status file, so its presence
     * means either the wrong server answered or someone on the network is
     * trying an entity expansion. Refused before a parser sees it, which does
     * not depend on which features Android's parser supports.
     */
    @Test
    public void doctypeIsRefusedOutright() {
        String xxe = "<?xml version=\"1.0\"?>"
                + "<!DOCTYPE d [<!ENTITY x SYSTEM \"file:///etc/passwd\">]>"
                + "<DATA><DOA>142.0</DOA><STATION_ID>&x;</STATION_ID></DATA>";
        FeedFrame f = new DoaXmlFeed().parse(xxe, RX);
        assertTrue(f.isEmpty());
        assertTrue(f.note.toLowerCase().contains("doctype"));
    }

    /** Billion laughs is the same refusal, and it never reaches the expander. */
    @Test
    public void entityExpansionIsRefusedOutright() {
        StringBuilder sb = new StringBuilder("<?xml version=\"1.0\"?><!DOCTYPE d [");
        sb.append("<!ENTITY a \"xxxxxxxxxx\">");
        for (char c = 'b'; c <= 'j'; c++) {
            char prev = (char) (c - 1);
            sb.append("<!ENTITY ").append(c).append(" \"");
            for (int i = 0; i < 10; i++)
                sb.append('&').append(prev).append(';');
            sb.append("\">");
        }
        sb.append("]><DATA><DOA>1.0</DOA><STATION_ID>&j;</STATION_ID></DATA>");
        FeedFrame f = new DoaXmlFeed().parse(sb.toString(), RX);
        assertTrue(f.isEmpty());
    }

    @Test
    public void anAbsurdlyLargeBodyIsRefusedWithoutParsing() {
        StringBuilder sb = new StringBuilder(DoaXmlFeed.MAX_BODY_CHARS + 64);
        sb.append("<DATA>");
        while (sb.length() < DoaXmlFeed.MAX_BODY_CHARS + 32)
            sb.append("<PAD>0123456789</PAD>");
        sb.append("</DATA>");
        FeedFrame f = new DoaXmlFeed().parse(sb.toString(), RX);
        assertTrue(f.isEmpty());
        assertTrue(f.note.contains("too large"));
    }

    /**
     * The bench seam. If the operator's build turns out not to match the source
     * that was read, the convention is a constructor argument and nothing
     * downstream changes.
     */
    @Test
    public void conventionCanBeFlippedForTheBench() {
        Bearing b = only(new DoaXmlFeed(BearingConvention.MIRRORED)
                .parse(Fixtures.load("doa.xml"), RX));
        assertEquals(142.0, b.reportedDeg, EPS);
        assertEquals(218.0, b.arrayRelativeDeg, EPS);
    }

    /** The sample document with one element's text replaced or appended. */
    private static String xml(String extra) {
        String base = Fixtures.load("doa.xml");
        for (String tag : new String[] { "PWR", "LATITUDE", "LONGITUDE" }) {
            if (extra.contains("<" + tag + ">"))
                base = base.replaceAll("<" + tag + ">[^<]*</" + tag + ">", "");
        }
        return base.replace("</DATA>", extra + "</DATA>");
    }
}
