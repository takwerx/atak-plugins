package com.atakmap.android.signaldf.net;

import com.atakmap.android.signaldf.data.Numbers;
import com.atakmap.android.signaldf.model.Bearing;
import com.atakmap.android.signaldf.model.BearingConvention;
import com.atakmap.android.signaldf.model.FeedFrame;
import com.atakmap.android.signaldf.model.FeedSource;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * {@code http://<host>:8081/DOA_value.html} -- the Kraken App CSV.
 *
 * <p>One line per active VFO, so this is the feed that can show two emitters on
 * two frequencies as two pictures. It also carries the DoA spectrum, which is
 * what the power lobe is drawn from in 0.2. Available in every output mode
 * except {@code Kerberos App}.
 *
 * <p><b>Bearing convention:</b> {@link BearingConvention#MIRRORED}. This feed
 * writes {@code 360 - theta_0} where the XML and the WebSocket write
 * {@code theta_0}. The mirror is deliberate -- there is a comment at that line
 * in {@code krakensdr_doa} reading "Change to this, once we upload new Android
 * APK" -- which also means it is version dependent, and a build newer than the
 * source that was read may have stopped mirroring. A mirrored bearing is not a
 * visibly broken bearing; it is a confident line pointing the wrong way. This
 * is unverified against hardware as of 0.1 and is the first thing the bench
 * procedure settles. {@link #CONVENTION} is the single line to change.
 *
 * <p><b>Torn reads are expected.</b> The DSP rewrites this file in place with
 * {@code seek(0)} / write / {@code truncate} on every frame, so a poll can land
 * mid-write and read a line with half its fields, or a line with the tail of
 * the previous, longer frame still attached. Both are skipped and counted
 * rather than thrown, because one bad poll out of a hundred is a fact about the
 * transport, not about the radio.
 */
public final class DoaCsvFeed implements Feed {

    /** The port {@code _share/} is served on by php or miniserve. */
    public static final int PORT = 8081;

    /**
     * Read from {@code krakensdr_doa} source: the CSV export mirrors theta_0.
     * Change this one constant if the bench proves otherwise on the operator's
     * build; nothing downstream needs to know.
     */
    public static final BearingConvention CONVENTION = BearingConvention.MIRRORED;

    /**
     * Field layout, verified against the CSV writer in
     * {@code kraken_sdr_signal_processor.py}. The spectrum follows field 16.
     */
    static final int F_TIME = 0;
    static final int F_BEARING = 1;
    static final int F_CONFIDENCE = 2;
    static final int F_POWER = 3;
    static final int F_FREQ = 4;
    static final int F_ANT_ARRANGEMENT = 5;
    static final int F_LATENCY = 6;
    static final int F_STATION_ID = 7;
    static final int F_LATITUDE = 8;
    static final int F_LONGITUDE = 9;
    static final int F_HEADING = 10;
    /**
     * Heading is written twice on purpose. The second is reserved for
     * separating a GPS track from a compass heading and carries the same value
     * today, so it is read and ignored rather than treated as a second fact.
     */
    static final int F_HEADING_2 = 11;
    static final int F_GPS_LITERAL = 12;
    /** Fields 13..16 are the reserved literals "R". */
    static final int F_RESERVED_LAST = 16;

    /** The fixed header, before the spectrum: indices 0..16. */
    static final int FIXED_FIELDS = 17;

    /** Generous ceiling: 16 VFOs, each with a 360-point spectrum. */
    static final int MAX_BODY_CHARS = 1024 * 1024;

    private final BearingConvention convention;

    public DoaCsvFeed() {
        this(CONVENTION);
    }

    /** Test and bench seam: lets the convention be flipped without an edit. */
    public DoaCsvFeed(BearingConvention convention) {
        this.convention = convention;
    }

    @Override
    public FeedSource source() {
        return FeedSource.CSV;
    }

    @Override
    public BearingConvention convention() {
        return convention;
    }

    @Override
    public String path() {
        return "DOA_value.html";
    }

    @Override
    public int port() {
        return PORT;
    }

    @Override
    public String limits() {
        return "Not served in Kerberos App mode. Kraken Pro Local adds the live "
                + "WebSocket feed and its lower latency.";
    }

    @Override
    public FeedFrame parse(String body, long receivedMs) {
        if (body == null)
            return FeedFrame.empty(source(), "no response");
        if (body.length() > MAX_BODY_CHARS)
            return FeedFrame.empty(source(), "response too large to be DOA_value.html");
        String text = body.trim();
        if (text.isEmpty())
            return FeedFrame.empty(source(), "empty response");

        // An error page from the wrong port answers 200 with HTML. That is a
        // misconfiguration to report, not a hundred malformed lines to count.
        String lead = text.substring(0, Math.min(64, text.length()))
                .toLowerCase(Locale.US);
        if (lead.startsWith("<!doctype") || lead.startsWith("<html")
                || lead.startsWith("<?xml"))
            return FeedFrame.empty(source(), "not the CSV feed (got markup)");

        List<Bearing> out = new ArrayList<>(4);
        int discarded = 0;
        // The VFO index is the line's POSITION in the file, not the number of
        // lines accepted before it. The writer emits the active VFOs in order
        // and does not label them, so line 2 is VFO 2 whether or not line 1 was
        // readable. Counting accepted lines instead shifts every row after a
        // torn one onto the wrong VFO -- which puts one emitter's bearing and
        // frequency under another's heading, and looks entirely plausible.
        int line0 = 0;
        for (String raw : text.split("\\r?\\n")) {
            String line = raw.trim();
            if (line.isEmpty())
                continue;
            int vfo = line0++;
            Bearing b = parseLine(line, vfo, receivedMs);
            if (b == null) {
                discarded++;
                continue;
            }
            out.add(b);
        }

        String note = "";
        if (discarded > 0 && out.isEmpty())
            note = "no usable line in this read";
        else if (discarded > 0)
            note = discarded + (discarded == 1 ? " line" : " lines") + " torn mid-write";
        return new FeedFrame(source(), out, discarded, note);
    }

    /**
     * One CSV line, or null if it is not a whole one.
     *
     * @param vfo the VFO index, taken from the line's position in the file --
     *            the writer emits the active VFOs in order and does not label
     *            them
     */
    private Bearing parseLine(String line, int vfo, long receivedMs) {
        String[] f = line.split(",", -1);
        if (f.length < FIXED_FIELDS)
            return null;

        // The bearing and the frequency are what this record is for; a line
        // missing either is a torn read however many commas it has.
        double reported = Numbers.d(f[F_BEARING], Double.NaN);
        if (Double.isNaN(reported))
            return null;
        double freqHz = Numbers.d(f[F_FREQ], Double.NaN);
        if (Double.isNaN(freqHz))
            return null;

        // The reserved literals are the cheapest proof that the fields line up:
        // a line whose field 13 is not "GPS" has been read at an offset, and
        // every value taken from it would be the wrong field's.
        if (!"GPS".equalsIgnoreCase(f[F_GPS_LITERAL].trim()))
            return null;

        double lat = Numbers.d(f[F_LATITUDE], Double.NaN);
        double lon = Numbers.d(f[F_LONGITUDE], Double.NaN);
        // Same sentinel as the XML feed: 0.0/0.0 is "no GPS", not Null Island.
        boolean hasPos = !Double.isNaN(lat) && !Double.isNaN(lon)
                && !(lat == 0.0 && lon == 0.0);

        String headingText = f[F_HEADING];
        boolean hasHeading = headingText != null && !headingText.trim().isEmpty();

        // Everything past the reserved literals is the DoA spectrum, offset so
        // its minimum is zero. Counted, not kept -- see Bearing.spectrumSampleCount.
        int spectrum = 0;
        for (int i = F_RESERVED_LAST + 1; i < f.length; i++) {
            if (!f[i].trim().isEmpty())
                spectrum++;
        }

        // This feed carries no ADC overdrive, correlated-source count or SNR;
        // they reach the plugin only over the XML and the WebSocket. Left at
        // their "absent" values rather than defaulted to something benign,
        // because a bearing that was never checked for front-end saturation
        // must not look like one that was checked and passed.
        return Bearing.builder(source(), convention)
                .received(receivedMs)
                .time(Numbers.epochMs(f[F_TIME]))
                .stationId(f[F_STATION_ID].trim())
                .vfo(vfo)
                .reported(reported)
                .confidence(Numbers.d(f[F_CONFIDENCE], Double.NaN))
                .power(Numbers.d(f[F_POWER], Double.NaN), false)
                .frequencyHz(freqHz)
                .position(hasPos ? lat : 0.0, hasPos ? lon : 0.0, hasPos)
                .heading(Numbers.d(headingText, 0.0), hasHeading)
                .speed(0.0)
                .adcOverdrive(false)
                .correlatedSources(-1)
                .snrDb(Double.NaN)
                .spectrumSamples(spectrum)
                .build();
    }
}
