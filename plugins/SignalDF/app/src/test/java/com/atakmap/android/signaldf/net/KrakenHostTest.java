package com.atakmap.android.signaldf.net;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

import org.junit.Test;

/**
 * The host preference is the only operator-typed part of any URL this plugin
 * builds, so these tests are mostly about what is refused.
 */
public class KrakenHostTest {

    private static void refused(String raw) {
        try {
            KrakenHost h = KrakenHost.parse(raw);
            fail("should have refused " + raw + " but built " + h.url(new DoaXmlFeed()));
        } catch (KrakenHost.InvalidHost expected) {
            // The message goes in front of the operator, so it has to exist.
            if (expected.getMessage() == null || expected.getMessage().isEmpty())
                fail("refusal of " + raw + " carried no reason");
        }
    }

    @Test
    public void acceptsTheStockMdnsName() {
        KrakenHost h = KrakenHost.parse("krakensdr.local");
        assertEquals("krakensdr.local", h.host());
        assertEquals(8081, h.sharePort());
        assertEquals("http://krakensdr.local:8081/doa.xml", h.url(new DoaXmlFeed()));
        assertEquals("http://krakensdr.local:8081/DOA_value.html",
                h.url(new DoaCsvFeed()));
    }

    @Test
    public void acceptsAnAddressAndAPort() {
        KrakenHost h = KrakenHost.parse("127.0.0.1:8085");
        assertEquals("127.0.0.1", h.host());
        assertEquals(8085, h.sharePort());
        assertEquals("http://127.0.0.1:8085/doa.xml", h.url(new DoaXmlFeed()));
    }

    /** People type the scheme and a trailing slash. Neither is worth refusing. */
    @Test
    public void toleratesWhatPeopleActuallyType() {
        assertEquals("http://krakensdr.local:8081/doa.xml",
                KrakenHost.parse("  http://krakensdr.local/  ").url(new DoaXmlFeed()));
        assertEquals("http://127.0.0.1:8081/doa.xml",
                KrakenHost.parse("127.0.0.1/").url(new DoaXmlFeed()));
    }

    @Test
    public void acceptsABracketedIpv6Literal() {
        KrakenHost h = KrakenHost.parse("[fd00::1]:8081");
        assertEquals("[fd00::1]", h.host());
        assertEquals("http://[fd00::1]:8081/doa.xml", h.url(new DoaXmlFeed()));
    }

    /** A path in the host field is the whole reason this class exists. */
    @Test
    public void refusesAnythingThatIsNotJustAHost() {
        refused("127.0.0.1/../../etc/passwd");
        refused("127.0.0.1/doa.xml");
        refused("127.0.0.1?x=1");
        refused("127.0.0.1#x");
        refused("evil.example@127.0.0.1");
        refused("file:///etc/passwd");
        refused("ftp://127.0.0.1");
        refused("javascript:alert(1)");
    }

    /** A backslash is a path separator to enough parsers to be worth refusing. */
    @Test
    public void refusesABackslash() {
        refused("127.0.0.1" + ((char) 92) + "x");
    }

    /** https is refused with its own reason: the radio does not serve it. */
    @Test
    public void refusesHttpsWithAUsefulReason() {
        try {
            KrakenHost.parse("https://krakensdr.local");
            fail("should have refused https");
        } catch (KrakenHost.InvalidHost e) {
            assertEquals("the Kraken serves plain http, not https", e.getMessage());
        }
    }

    /**
     * Whitespace and control characters are skipped by some URL parsers and not
     * others, which is how a value that looks like a host reaches somewhere else.
     * Built by code point so this file stays free of the characters it tests.
     */
    @Test
    public void refusesWhitespaceAndControlCharactersInside() {
        refused("127.0.0.1 /doa.xml");
        for (int cp : new int[] { 9, 10, 13, 0, 11, 12, 127 })
            refused("kraken" + ((char) cp) + "sdr.local");
    }

    @Test
    public void refusesBadPorts() {
        refused("127.0.0.1:");
        refused("127.0.0.1:0");
        refused("127.0.0.1:65536");
        refused("127.0.0.1:999999");
        refused("127.0.0.1:80a");
        refused("127.0.0.1:-1");
    }

    /** A bare IPv6 address is ambiguous with host:port and has to be bracketed. */
    @Test
    public void refusesUnbracketedIpv6() {
        refused("fd00::1");
        refused("[fd00::1");
        refused("[not-an-address]");
    }

    @Test
    public void refusesMalformedHostnames() {
        refused("");
        refused("   ");
        refused(null);
        refused("-leading.example");
        refused("trailing-.example");
        refused("two..dots");
        refused("under_score.local");
        refused("kraken.local.");
    }

    @Test
    public void refusesAnAbsurdlyLongHost() {
        StringBuilder sb = new StringBuilder();
        while (sb.length() < 300)
            sb.append("abcdefgh.");
        refused(sb.toString());
    }
}
