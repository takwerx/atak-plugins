package com.atakmap.android.signaldf.net;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * Sample payloads for the feed adapters.
 *
 * <p><b>These are synthesized from the wire formats documented in the plan, not
 * captured from a radio.</b> The operator's KrakenSDR does not exist yet. What
 * they prove is that the adapters implement the field order and the scaling as
 * they were read out of {@code krakensdr_doa}'s source -- which is worth having,
 * because that arithmetic is where the silent failures live -- and what they
 * cannot prove is that the source that was read matches the build the radio will
 * run. The bearing convention in particular is version dependent by the source's
 * own admission. Replace these with real captures on bench day; see
 * {@code BENCH-SignalDF-bearing-validation.md} in the notes repo.
 *
 * <p>{@code doa.xml} and {@code DOA_value_single.html} deliberately describe the
 * same frame -- theta_0 = 142 degrees -- so that a test can assert the two
 * adapters agree about the physical direction while disagreeing about the number
 * on the wire.
 */
final class Fixtures {

    /**
     * Raw {@code theta_0} in both fixtures. This is what the XML and the
     * WebSocket put on the wire, and it runs counter-clockwise in the array's
     * own scan frame, so it is NOT a compass bearing.
     */
    static final double THETA = 142.0;

    /**
     * The compass-convention bearing for that theta: {@code 360 - 142}. This is
     * what the CSV puts on the wire and what every adapter has to resolve to.
     * {@code calculate_end_lat_lng} in the radio's own DSP computes a
     * geolocated endpoint as {@code my_bearing + (360 - theta_0)}, which is the
     * authority for this being the right way round.
     */
    static final double COMPASS = 218.0;

    /** What the CSV writes: the compass bearing, needing nothing applied. */
    static final double CSV_REPORTED = COMPASS;

    private Fixtures() {
    }

    static String load(String name) {
        InputStream in = Fixtures.class.getResourceAsStream("/" + name);
        if (in == null)
            throw new IllegalStateException("fixture not on the test classpath: " + name);
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0)
                out.write(buf, 0, n);
            return out.toString("UTF-8");
        } catch (IOException e) {
            throw new IllegalStateException("could not read fixture " + name, e);
        } finally {
            try {
                in.close();
            } catch (IOException ignored) {
                // nothing useful to do while closing a fixture
            }
        }
    }
}
