package com.atakmap.android.signaldf.net;

import com.atakmap.android.signaldf.model.BearingConvention;
import com.atakmap.android.signaldf.model.FeedFrame;
import com.atakmap.android.signaldf.model.FeedSource;

/**
 * One of the KrakenSDR's readable interfaces.
 *
 * <p>An adapter owns two things nothing else may assume: the
 * {@link BearingConvention} its feed writes, and the unit scaling its feed
 * applies. Both differ between the three interfaces on the same radio, in the
 * same frame -- the CSV mirrors the bearing and the XML reports megahertz where
 * the others report hertz -- so a single shared parser reading "the Kraken"
 * would be wrong roughly half the time in a way that still draws a plausible
 * line on the map.
 *
 * <p>Implementations are pure: text in, {@link FeedFrame} out, no I/O and no
 * clock. That is what makes the arithmetic testable off the phone, which for a
 * plugin whose whole correctness question is "is this angle right" is the only
 * place it can honestly be tested at all.
 */
public interface Feed {

    /** Which interface this reads. */
    FeedSource source();

    /** How this feed writes a bearing on the wire. */
    BearingConvention convention();

    /** Path below the host root, no leading slash, e.g. {@code doa.xml}. */
    String path();

    /** The port this feed is served on. */
    int port();

    /**
     * Parses one response body.
     *
     * @param body       the response text; null or blank yields an empty frame
     * @param receivedMs the phone's clock when the body arrived, stamped onto
     *                   every bearing so age is measured against a clock we
     *                   trust rather than the Pi's
     * @return a frame, never null. A frame with no bearings is a normal result
     *         -- the radio may simply not be producing -- and is not an error.
     */
    FeedFrame parse(String body, long receivedMs);

    /**
     * What this feed cannot give, in words for the pane. The plugin falls back
     * through the interfaces rather than failing, so the operator has to be
     * told what they are missing and what would restore it.
     */
    String limits();
}
