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

    /** theta_0 in both fixtures, degrees clockwise from the array's zero. */
    static final double THETA = 142.0;

    /** What the CSV writes for that theta: 360 - 142. */
    static final double CSV_REPORTED = 218.0;

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
