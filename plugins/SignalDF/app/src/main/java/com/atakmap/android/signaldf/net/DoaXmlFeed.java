package com.atakmap.android.signaldf.net;

import com.atakmap.android.signaldf.data.Numbers;
import com.atakmap.android.signaldf.model.Bearing;
import com.atakmap.android.signaldf.model.BearingConvention;
import com.atakmap.android.signaldf.model.FeedFrame;
import com.atakmap.android.signaldf.model.FeedSource;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.EntityResolver;
import org.xml.sax.InputSource;

import java.io.ByteArrayInputStream;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

/**
 * {@code http://<host>:8081/doa.xml} -- the DF Aggregator XML.
 *
 * <p>This is the fallback that always works, and it is the reason the plugin
 * can connect to a Kraken that is merely running rather than a Kraken that has
 * been configured for it. {@code wr_xml} is called before the output-format
 * switch in the DSP, so the file is written in every mode, including the modes
 * where the CSV is absent and the WebSocket is silent.
 *
 * <p>What it costs: VFO 0 only, and no DoA spectrum, so no power lobe. Those
 * are the two things {@link #limits()} has to say out loud.
 *
 * <p><b>Scaling.</b> This feed does not agree with the other two about units,
 * and the differences are silent rather than obvious -- a frequency read as Hz
 * when it is MHz is off by a factor of a million, which is visible, but a
 * confidence read as 0..100 when everything else is 0..1 just makes every
 * bearing look excellent. On the wire here:
 * <ul>
 * <li>{@code FREQUENCY} is MHz; the CSV and JSON report Hz
 * <li>{@code CONF} is {@code conf * 100}
 * <li>{@code PWR} is {@code max(-100, power + 100)}, so -100 is a floor rather
 *     than a measurement and is flagged as clipped
 * </ul>
 *
 * <p><b>Bearing convention:</b> {@link BearingConvention#DIRECT}. This feed
 * writes {@code theta_0} as it comes out of the DSP. The CSV mirrors it; this
 * does not. Unverified against hardware as of 0.1 -- see the bench procedure in
 * the notes repo -- and if the operator's build disagrees, {@link #CONVENTION}
 * is the single line to change.
 */
public final class DoaXmlFeed implements Feed {

    /** The port {@code _share/} is served on by php or miniserve. */
    public static final int PORT = 8081;

    /**
     * Read from {@code krakensdr_doa} source: the XML export writes theta_0
     * unmirrored. Change this one constant if the bench proves otherwise on the
     * operator's build; nothing downstream needs to know.
     */
    public static final BearingConvention CONVENTION = BearingConvention.DIRECT;

    /**
     * A sane ceiling on a document that is normally under a kilobyte. The HTTP
     * layer already bounds the response, but a parser that is handed something
     * enormous should refuse it on its own rather than rely on its caller.
     */
    static final int MAX_BODY_CHARS = 512 * 1024;

    private final BearingConvention convention;

    public DoaXmlFeed() {
        this(CONVENTION);
    }

    /** Test and bench seam: lets the convention be flipped without an edit. */
    public DoaXmlFeed(BearingConvention convention) {
        this.convention = convention;
    }

    @Override
    public FeedSource source() {
        return FeedSource.XML;
    }

    @Override
    public BearingConvention convention() {
        return convention;
    }

    @Override
    public String path() {
        return "doa.xml";
    }

    @Override
    public int port() {
        return PORT;
    }

    @Override
    public String limits() {
        return "VFO 0 only, no DoA spectrum. Switch the Kraken to Kraken App or "
                + "Kraken Pro Local for every VFO and the power lobe.";
    }

    @Override
    public FeedFrame parse(String body, long receivedMs) {
        if (body == null)
            return FeedFrame.empty(source(), "no response");
        String text = body.trim();
        if (text.isEmpty())
            return FeedFrame.empty(source(), "empty response");
        if (text.length() > MAX_BODY_CHARS)
            return FeedFrame.empty(source(), "response too large to be doa.xml");

        // A doctype in a machine-written status file is either a misconfigured
        // server answering with an HTML error page, or someone on the network
        // handing us an entity expansion. Neither is worth parsing, and
        // refusing before the parser sees it does not depend on which parser
        // implementation Android happens to supply.
        String lower = text.toLowerCase(Locale.US);
        if (lower.contains("<!doctype") || lower.contains("<!entity"))
            return FeedFrame.empty(source(), "not doa.xml (doctype present)");

        Document doc;
        try {
            doc = builder().parse(new InputSource(new StringReader(text)));
        } catch (Exception e) {
            // A torn read is normal here: the DSP rewrites this file in place.
            return new FeedFrame(source(), Collections.<Bearing> emptyList(), 1,
                    "unreadable XML, skipped");
        }
        if (doc == null || doc.getDocumentElement() == null)
            return FeedFrame.empty(source(), "empty document");

        String doa = text(doc, "DOA");
        double reported = Numbers.d(doa, Double.NaN);
        if (Double.isNaN(reported))
            return new FeedFrame(source(), Collections.<Bearing> emptyList(), 1,
                    "no DOA element, skipped");

        // max(-100, power + 100) on the wire: -100 means "at or below -200 dB"
        // rather than "-200 dB", so it is a floor and is marked as one.
        String pwrText = text(doc, "PWR");
        double pwrRaw = Numbers.d(pwrText, Double.NaN);
        boolean clipped = !Double.isNaN(pwrRaw) && pwrRaw <= -100.0 + 1.0e-9;
        double powerDb = Double.isNaN(pwrRaw) ? Double.NaN : pwrRaw - 100.0;

        String latText = text(doc, "LATITUDE");
        String lonText = text(doc, "LONGITUDE");
        double lat = Numbers.d(latText, Double.NaN);
        double lon = Numbers.d(lonText, Double.NaN);
        // The radio writes 0.0/0.0 when it has no GPS fix. Null Island is a
        // legal coordinate and an overwhelmingly likely sentinel, and a bearing
        // line drawn from the Gulf of Guinea is worse than one not drawn.
        boolean hasPos = !Double.isNaN(lat) && !Double.isNaN(lon)
                && !(lat == 0.0 && lon == 0.0);

        String headingText = text(doc, "HEADING");
        boolean hasHeading = headingText != null && !headingText.trim().isEmpty();

        String confText = text(doc, "CONF");
        double confRaw = Numbers.d(confText, Double.NaN);

        String freqText = text(doc, "FREQUENCY");
        double freqMHz = Numbers.d(freqText, Double.NaN);

        Bearing b = Bearing.builder(source(), convention)
                .received(receivedMs)
                .time(Numbers.epochMs(text(doc, "TIME")))
                .gpsTime(Numbers.epochMs(text(doc, "GPS_TIME")))
                .stationId(text(doc, "STATION_ID"))
                .vfo(0)
                .reported(reported)
                .confidence(Double.isNaN(confRaw) ? Double.NaN : confRaw / 100.0)
                .power(powerDb, clipped)
                .frequencyHz(Double.isNaN(freqMHz) ? Double.NaN : freqMHz * 1.0e6)
                .position(hasPos ? lat : 0.0, hasPos ? lon : 0.0, hasPos)
                .heading(Numbers.d(headingText, 0.0), hasHeading)
                .speed(Numbers.d(text(doc, "SPEED"), 0.0))
                .adcOverdrive(Numbers.bool(text(doc, "ADC_OVERDRIVE"), false))
                .correlatedSources(Numbers.i(text(doc, "NUM_CORRELATED_SOURCES"), -1))
                .snrDb(Numbers.d(text(doc, "SNR_DB"), Double.NaN))
                .spectrumSamples(0)
                .build();

        List<Bearing> out = new ArrayList<>(1);
        out.add(b);
        return new FeedFrame(source(), out, 0, "");
    }

    /** First element with this tag name anywhere in the document, or null. */
    private static String text(Document doc, String tag) {
        NodeList nodes = doc.getElementsByTagName(tag);
        if (nodes == null || nodes.getLength() == 0)
            return null;
        Node n = nodes.item(0);
        if (!(n instanceof Element))
            return null;
        String s = n.getTextContent();
        return s == null ? null : s.trim();
    }

    /**
     * A parser that will not reach the network and will not expand an entity.
     *
     * <p>Each feature is set in its own try because the set a parser supports
     * differs between the JVM this is unit tested on and the Expat-backed
     * implementation Android supplies, and one unsupported feature throwing
     * must not cost the others. The {@link EntityResolver} is the defense that
     * does not depend on any of them: whatever the parser decides to resolve,
     * it resolves to nothing.
     */
    private static DocumentBuilder builder() throws Exception {
        DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
        trySetFeature(f, XMLConstants.FEATURE_SECURE_PROCESSING, true);
        trySetFeature(f, "http://apache.org/xml/features/disallow-doctype-decl", true);
        trySetFeature(f,
                "http://xml.org/sax/features/external-general-entities", false);
        trySetFeature(f,
                "http://xml.org/sax/features/external-parameter-entities", false);
        trySetFeature(f,
                "http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
        f.setExpandEntityReferences(false);
        f.setNamespaceAware(false);
        f.setValidating(false);
        try {
            f.setXIncludeAware(false);
        } catch (UnsupportedOperationException ignored) {
            // Android's implementation does not offer it and never did XInclude.
        }
        DocumentBuilder b = f.newDocumentBuilder();
        b.setEntityResolver(new EntityResolver() {
            @Override
            public InputSource resolveEntity(String publicId, String systemId) {
                return new InputSource(new ByteArrayInputStream(
                        new byte[0]));
            }
        });
        return b;
    }

    private static void trySetFeature(DocumentBuilderFactory f, String name, boolean value) {
        try {
            f.setFeature(name, value);
        } catch (Exception ignored) {
            // Unsupported on this implementation; the others plus the entity
            // resolver and the doctype refusal still hold.
        }
    }
}
