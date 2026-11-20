package com.atakmap.android.atmosphere.net;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.atakmap.android.atmosphere.data.Census;

import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * FirstNet LTE gives the device an IPv6 address. The Census Bureau's firewall answers
 * it with a "Request Rejected" page (HTTP 200, HTML) and the same request over IPv4
 * with data, so the Spot page's address search found nothing on a FirstNet phone
 * (Sit Rep hit the same on a Note 20, 2026-11-17). These tests drive the decision
 * with fake connections: no network, no Android.
 */
public class Ipv4FallbackTest {

    private static final String HOST = Census.HOST;
    private static final byte[] REJECTED = ("<html><head><title>Request Rejected</title></head>"
            + "<body>The requested URL was rejected.</body></html>")
                    .getBytes(StandardCharsets.UTF_8);
    private static final byte[] DATA = "{\"result\":{\"addressMatches\":[]}}"
            .getBytes(StandardCharsets.UTF_8);

    /** A connection that counts its calls and answers with one body, or fails. */
    private static final class Fake implements Ipv4Fallback.Transport {
        final byte[] body;
        final IOException failure;
        int calls;

        Fake(byte[] body) {
            this.body = body;
            this.failure = null;
        }

        Fake(IOException failure) {
            this.body = null;
            this.failure = failure;
        }

        @Override
        public byte[] get() throws IOException {
            calls++;
            if (failure != null)
                throw failure;
            return body;
        }
    }

    private static Ipv4Fallback.V6Probe v6(final boolean silent) {
        return new Ipv4Fallback.V6Probe() {
            @Override
            public boolean silent() {
                return silent;
            }
        };
    }

    @Before
    public void fresh() {
        Ipv4Fallback.reset();
    }

    @Test
    public void theCensusGeocoderTakesThisPathAndAnUnlistedHostDoesNot() {
        assertTrue(Ipv4Fallback.appliesTo(Census.HOST));
        assertTrue(Ipv4Fallback.appliesTo("GEOCODING.GEO.CENSUS.GOV"));
        assertFalse(Ipv4Fallback.appliesTo("api.weather.gov"));
        assertFalse(Ipv4Fallback.appliesTo(null));
    }

    @Test
    public void theRejectionPageIsRecognizedAndRealDataIsNot() {
        assertTrue(Ipv4Fallback.rejected(REJECTED));
        assertFalse(Ipv4Fallback.rejected(DATA));
        assertFalse(Ipv4Fallback.rejected(new byte[0]));
        assertFalse(Ipv4Fallback.rejected(null));
        // A big page that happens to carry the words is somebody's real page.
        final byte[] big = new byte[5000];
        java.util.Arrays.fill(big, (byte) ' ');
        System.arraycopy(REJECTED, 0, big, 0, REJECTED.length);
        assertFalse(Ipv4Fallback.rejected(big));
    }

    @Test
    public void aRejectedAnswerIsAskedAgainOverIpv4AndTheDataIsReturned() throws Exception {
        final Fake normal = new Fake(REJECTED);
        final Fake v4 = new Fake(DATA);
        final byte[] got = Ipv4Fallback.fetch(HOST, normal, v4, v6(false));
        assertArrayEquals(DATA, got);
        assertEquals(1, normal.calls);
        assertEquals(1, v4.calls);
        assertTrue(Ipv4Fallback.isIpv4Only(HOST));
    }

    @Test
    public void afterOneRejectionTheHostGoesStraightToIpv4() throws Exception {
        Ipv4Fallback.fetch(HOST, new Fake(REJECTED), new Fake(DATA), v6(false));
        final Fake normal = new Fake(REJECTED);
        final Fake v4 = new Fake(DATA);
        assertArrayEquals(DATA, Ipv4Fallback.fetch(HOST, normal, v4, v6(false)));
        assertEquals("no second trip over IPv6", 0, normal.calls);
        assertEquals(1, v4.calls);
    }

    @Test
    public void aGoodAnswerOverIpv6IsLeftAlone() throws Exception {
        final Fake normal = new Fake(DATA);
        final Fake v4 = new Fake(REJECTED);
        assertArrayEquals(DATA, Ipv4Fallback.fetch(HOST, normal, v4, v6(false)));
        assertEquals(0, v4.calls);
        assertFalse(Ipv4Fallback.isIpv4Only(HOST));
    }

    @Test
    public void aSilentIpv6AddressGoesToIpv4WithoutWaitingOnIpv6() throws Exception {
        final Fake normal = new Fake(DATA);
        final Fake v4 = new Fake(DATA);
        assertArrayEquals(DATA, Ipv4Fallback.fetch(HOST, normal, v4, v6(true)));
        assertEquals(0, normal.calls);
        assertEquals(1, v4.calls);
        assertTrue(Ipv4Fallback.isIpv4Only(HOST));
    }

    @Test
    public void theIpv6ProbeRunsOncePerHost() throws Exception {
        final int[] asked = {0};
        final Ipv4Fallback.V6Probe counting = new Ipv4Fallback.V6Probe() {
            @Override
            public boolean silent() {
                asked[0]++;
                return false;
            }
        };
        Ipv4Fallback.fetch(HOST, new Fake(DATA), new Fake(DATA), counting);
        Ipv4Fallback.fetch(HOST, new Fake(DATA), new Fake(DATA), counting);
        assertEquals(1, asked[0]);
    }

    @Test
    public void ifIpv4FailsTooTheFirstAnswerIsReturnedAndNothingIsRemembered() throws Exception {
        final Fake normal = new Fake(REJECTED);
        final Fake v4 = new Fake(new IOException("no IPv4 address"));
        assertArrayEquals(REJECTED, Ipv4Fallback.fetch(HOST, normal, v4, v6(false)));
        assertFalse(Ipv4Fallback.isIpv4Only(HOST));
    }

    @Test
    public void ifIpv4IsRejectedTooTheHostIsNotSwitched() throws Exception {
        final Fake normal = new Fake(REJECTED);
        final Fake v4 = new Fake(REJECTED);
        assertArrayEquals(REJECTED, Ipv4Fallback.fetch(HOST, normal, v4, v6(false)));
        assertFalse(Ipv4Fallback.isIpv4Only(HOST));
    }

    @Test
    public void aFailureOnTheNormalConnectionIsNotHiddenByTheFallback() {
        final Fake normal = new Fake(new IOException("timed out"));
        final Fake v4 = new Fake(DATA);
        try {
            Ipv4Fallback.fetch(HOST, normal, v4, v6(false));
            fail("the failure should reach the caller, as it does for any host");
        } catch (IOException e) {
            assertEquals("timed out", e.getMessage());
            assertEquals(0, v4.calls);
        }
    }
}
