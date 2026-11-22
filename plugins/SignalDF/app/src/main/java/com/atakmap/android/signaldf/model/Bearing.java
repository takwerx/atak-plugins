package com.atakmap.android.signaldf.model;

import com.atakmap.android.signaldf.data.Angles;

import java.util.Locale;

/**
 * One direction of arrival, as one feed reported it, in canonical units.
 *
 * <p>Immutable, and it carries provenance rather than just an angle. A bearing
 * is only meaningful with the receiver's position, the frequency and the time
 * attached: the fix in 0.3 intersects rays from where the receiver was, the
 * terrain-visibility work needs the wavelength, and the rolling window needs the
 * time. Anything that reduces this to a number throws away what later versions
 * are built on, so nothing here is optional.
 *
 * <p>Two numbers describe the same direction and both are kept:
 * <ul>
 * <li>{@link #reportedDeg} is exactly what the wire said, before any
 *     interpretation. It is kept so the pane can show it during the bench
 *     validation, where the whole question is what the radio actually emits.
 * <li>{@link #arrayRelativeDeg} is theta_0 -- clockwise from the antenna
 *     array's own zero -- after this feed's {@link BearingConvention} has been
 *     applied. This is the number the rest of the plugin uses.
 * </ul>
 *
 * <p>Neither is a bearing to true north. The Kraken never adds heading to a
 * bearing on any of its three export paths; it exports heading as its own field
 * and leaves the sum to the consumer. See {@link #trueBearing(double)}.
 */
public final class Bearing {

    /** Which interface this came off. */
    public final FeedSource source;

    /** The convention {@link #reportedDeg} was written in. */
    public final BearingConvention convention;

    /** The radio's own timestamp for the frame, ms since epoch; 0 if absent. */
    public final long timeMs;

    /** The radio's GPS timestamp, ms since epoch; 0 if absent. */
    public final long gpsTimeMs;

    /**
     * The phone's clock when this frame was read, ms. Age is measured against
     * this and never against {@link #timeMs}, because the Pi's clock can be
     * anywhere -- a Kraken with no network time is commonly years out.
     */
    public final long receivedMs;

    /** The radio's station id, or empty. */
    public final String stationId;

    /** VFO index this bearing belongs to. The XML feed is VFO 0 only. */
    public final int vfo;

    /** Exactly what the feed wrote, degrees, unwrapped and uninterpreted. */
    public final double reportedDeg;

    /** theta_0 after the feed's convention, degrees clockwise from array zero. */
    public final double arrayRelativeDeg;

    /** Confidence, normalized to 0..1. The XML scales this by 100 on the wire. */
    public final double confidence;

    /** Received power in dB. The XML offsets this by +100 on the wire. */
    public final double powerDb;

    /**
     * True when the XML's {@code max(-100, power + 100)} floor was hit, so the
     * real power was at or below -200 dB and this value is a floor, not a
     * measurement. Always false on feeds that do not clamp.
     */
    public final boolean powerClipped;

    /** Frequency in Hz. The XML reports MHz on the wire. */
    public final double frequencyHz;

    /** Receiver position. Meaningless unless {@link #positionReported}. */
    public final double latitude;
    public final double longitude;
    public final boolean positionReported;

    /**
     * The array heading the radio reported, degrees. Read the warning on
     * {@link #trueBearing(double)} before trusting it: the Kraken reports 0.0
     * both for "pointing true north" and for "I have no idea", and those are
     * indistinguishable on the wire.
     */
    public final double headingDeg;

    /** Whether the heading field was present at all. */
    public final boolean headingReported;

    /** Ground speed as reported, unconverted. */
    public final double speed;

    /**
     * The front end was saturated for this frame. The radio has already told us
     * the bearing is garbage; 0.3's filter drops these and counts them.
     */
    public final boolean adcOverdrive;

    /** Number of correlated sources the DSP saw, or -1 if absent. */
    public final int correlatedSources;

    /** SNR in dB, or NaN if absent. */
    public final double snrDb;

    /**
     * How many samples of DoA spectrum this feed carried, or 0 if none.
     *
     * <p>The values themselves are deliberately not retained in 0.1. The power
     * lobe is 0.2 work, and a spectrum on every bearing in a rolling window is
     * the one field here big enough to matter. Keeping the count lets the pane
     * say the lobe is available and why it is not drawn yet, which is the house
     * rule about saying what is not being shown.
     */
    public final int spectrumSampleCount;

    private Bearing(Builder b) {
        this.source = b.source;
        this.convention = b.convention;
        this.timeMs = b.timeMs;
        this.gpsTimeMs = b.gpsTimeMs;
        this.receivedMs = b.receivedMs;
        this.stationId = b.stationId == null ? "" : b.stationId;
        this.vfo = b.vfo;
        this.reportedDeg = b.reportedDeg;
        this.arrayRelativeDeg = b.convention.toArrayRelative(b.reportedDeg);
        this.confidence = b.confidence;
        this.powerDb = b.powerDb;
        this.powerClipped = b.powerClipped;
        this.frequencyHz = b.frequencyHz;
        this.latitude = b.latitude;
        this.longitude = b.longitude;
        this.positionReported = b.positionReported;
        this.headingDeg = b.headingDeg;
        this.headingReported = b.headingReported;
        this.speed = b.speed;
        this.adcOverdrive = b.adcOverdrive;
        this.correlatedSources = b.correlatedSources;
        this.snrDb = b.snrDb;
        this.spectrumSampleCount = b.spectrumSampleCount;
    }

    /**
     * The bearing to true north, given a heading for the antenna array.
     *
     * <p>{@code true = (array relative + array heading) mod 360}. The radio does
     * not do this for us on any export path: {@code array_offset} is folded into
     * the DSP's scanning vectors, {@code compass_offset} is applied only inside
     * the web GUI's own display code, and heading is exported as a separate
     * field that is never added in.
     *
     * <p>The caller supplies the heading, and has to know where it came from --
     * the radio's own field, the phone's track, or a calibration. The radio
     * reports 0.0 when it has no heading, which silently asserts that the
     * array's zero is true north, and a parked vehicle with gpsd attached is
     * exactly that case. That is why the plugin resolves heading separately and
     * states its provenance in words rather than reading this field and drawing.
     */
    public double trueBearing(double arrayHeadingDeg) {
        return Angles.norm360(arrayRelativeDeg + arrayHeadingDeg);
    }

    /** Frequency in MHz, for display. */
    public double frequencyMHz() {
        return frequencyHz / 1.0e6;
    }

    @Override
    public String toString() {
        return String.format(Locale.US,
                "Bearing[%s vfo=%d reported=%.1f theta=%.1f conf=%.2f pwr=%.1f %.4fMHz]",
                source.label(), vfo, reportedDeg, arrayRelativeDeg, confidence,
                powerDb, frequencyMHz());
    }

    public static Builder builder(FeedSource source, BearingConvention convention) {
        return new Builder(source, convention);
    }

    /** Mutable collector; adapters fill what their feed carries and build once. */
    public static final class Builder {
        private final FeedSource source;
        private final BearingConvention convention;
        private long timeMs;
        private long gpsTimeMs;
        private long receivedMs;
        private String stationId = "";
        private int vfo;
        private double reportedDeg = Double.NaN;
        private double confidence = Double.NaN;
        private double powerDb = Double.NaN;
        private boolean powerClipped;
        private double frequencyHz = Double.NaN;
        private double latitude;
        private double longitude;
        private boolean positionReported;
        private double headingDeg;
        private boolean headingReported;
        private double speed;
        private boolean adcOverdrive;
        private int correlatedSources = -1;
        private double snrDb = Double.NaN;
        private int spectrumSampleCount;

        private Builder(FeedSource source, BearingConvention convention) {
            this.source = source;
            this.convention = convention;
        }

        public Builder time(long ms) {
            this.timeMs = ms;
            return this;
        }

        public Builder gpsTime(long ms) {
            this.gpsTimeMs = ms;
            return this;
        }

        public Builder received(long ms) {
            this.receivedMs = ms;
            return this;
        }

        public Builder stationId(String id) {
            this.stationId = id;
            return this;
        }

        public Builder vfo(int vfo) {
            this.vfo = vfo;
            return this;
        }

        public Builder reported(double deg) {
            this.reportedDeg = deg;
            return this;
        }

        public Builder confidence(double conf) {
            this.confidence = conf;
            return this;
        }

        public Builder power(double db, boolean clipped) {
            this.powerDb = db;
            this.powerClipped = clipped;
            return this;
        }

        public Builder frequencyHz(double hz) {
            this.frequencyHz = hz;
            return this;
        }

        public Builder position(double lat, double lon, boolean reported) {
            this.latitude = lat;
            this.longitude = lon;
            this.positionReported = reported;
            return this;
        }

        public Builder heading(double deg, boolean reported) {
            this.headingDeg = deg;
            this.headingReported = reported;
            return this;
        }

        public Builder speed(double speed) {
            this.speed = speed;
            return this;
        }

        public Builder adcOverdrive(boolean overdriven) {
            this.adcOverdrive = overdriven;
            return this;
        }

        public Builder correlatedSources(int n) {
            this.correlatedSources = n;
            return this;
        }

        public Builder snrDb(double db) {
            this.snrDb = db;
            return this;
        }

        public Builder spectrumSamples(int n) {
            this.spectrumSampleCount = n;
            return this;
        }

        public Bearing build() {
            return new Bearing(this);
        }
    }
}
