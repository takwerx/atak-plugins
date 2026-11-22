package com.atakmap.android.signaldf.net;

import java.util.Locale;

/**
 * The address of the operator's radio, parsed from a preference and turned into
 * a URL that can only ever reach the Kraken's own status files.
 *
 * <p>This is a small class with one security job. The host is the only part of
 * any request the plugin makes that a person types, and the paths and ports are
 * constants owned by the {@link Feed} adapters. So the whole question is whether
 * a host string can smuggle anything past its own field: a path
 * ({@code 127.0.0.1/../../x}), a query, a fragment, userinfo
 * ({@code evil.example@127.0.0.1}), a second scheme, or whitespace and control
 * characters that some URL parsers skip and others do not. Everything that is
 * not a plain hostname, IPv4 literal or bracketed IPv6 literal -- with an
 * optional port -- is refused with a reason the operator can act on.
 *
 * <p><b>There is deliberately no private-address restriction.</b> An obvious
 * hardening would be to allow only RFC 1918 ranges, and it would be wrong here:
 * the Pi is reached over vehicle WiFi sometimes and over the team's NetBird
 * overlay other times, and overlay addresses land in the shared 100.64/10 space
 * rather than anywhere a simple "is this a LAN address" test would accept. The
 * operator types their own radio's address; there is no untrusted input choosing
 * where this connects, so a range check would cost a real deployment and buy
 * nothing.
 *
 * <p>Requests are plaintext {@code http} because that is all the Kraken serves.
 * Its status files are unauthenticated, so nothing private crosses the wire, but
 * this is still a LAN-only design and the pane says so rather than implying a
 * private channel.
 */
public final class KrakenHost {

    /** php or miniserve over {@code _share/}, and the stock image's default. */
    public static final int DEFAULT_SHARE_PORT = 8081;

    /** What the stock KrakenSDR image answers to over mDNS. */
    public static final String DEFAULT_HOST = "krakensdr.local";

    /** RFC 1035's limit; anything longer is not a hostname. */
    private static final int MAX_HOST_CHARS = 253;

    private final String host;
    private final int sharePort;

    private KrakenHost(String host, int sharePort) {
        this.host = host;
        this.sharePort = sharePort;
    }

    /** Refused input, with the reason already phrased for the operator. */
    public static final class InvalidHost extends IllegalArgumentException {
        InvalidHost(String message) {
            super(message);
        }
    }

    /**
     * Parses what the operator typed.
     *
     * <p>Accepts {@code krakensdr.local}, {@code 127.0.0.1},
     * {@code 127.0.0.1:8081}, {@code [fd00::1]:8081}, and tolerates a leading
     * {@code http://} and a single trailing slash because people type those.
     * A port here applies to the status-file server; the node middleware's ports
     * are fixed by the middleware and are not the operator's to move.
     *
     * @throws InvalidHost with a message fit to show in the pane
     */
    public static KrakenHost parse(String raw) {
        if (raw == null)
            throw new InvalidHost("no address set");
        String s = raw.trim();
        if (s.isEmpty())
            throw new InvalidHost("no address set");

        String lower = s.toLowerCase(Locale.US);
        if (lower.startsWith("http://"))
            s = s.substring("http://".length());
        else if (lower.startsWith("https://"))
            // The radio serves plaintext only; accepting this would produce a
            // connection that always fails with a confusing reason.
            throw new InvalidHost("the Kraken serves plain http, not https");
        else if (s.contains("://"))
            throw new InvalidHost("just the address, no scheme");

        while (s.endsWith("/"))
            s = s.substring(0, s.length() - 1);
        if (s.isEmpty())
            throw new InvalidHost("no address set");

        // Refuse before splitting, so nothing below has to reason about what a
        // URL parser would do with an embedded delimiter.
        if (s.indexOf('/') >= 0)
            throw new InvalidHost("just the address, no path");
        if (s.indexOf('?') >= 0 || s.indexOf('#') >= 0)
            throw new InvalidHost("just the address, no query");
        if (s.indexOf('@') >= 0)
            throw new InvalidHost("just the address, no user name");
        if (s.indexOf('\\') >= 0)
            throw new InvalidHost("not an address");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c <= ' ' || c == 0x7f)
                throw new InvalidHost("the address has a space in it");
        }

        String hostPart;
        int port = DEFAULT_SHARE_PORT;

        if (s.startsWith("[")) {
            int close = s.indexOf(']');
            if (close < 0)
                throw new InvalidHost("unclosed [ in the address");
            hostPart = s.substring(0, close + 1);
            String rest = s.substring(close + 1);
            if (!rest.isEmpty()) {
                if (rest.charAt(0) != ':')
                    throw new InvalidHost("not an address");
                port = parsePort(rest.substring(1));
            }
            if (!isIpv6Literal(hostPart))
                throw new InvalidHost("not an IPv6 address");
        } else {
            int colon = s.lastIndexOf(':');
            if (colon >= 0) {
                if (s.indexOf(':') != colon)
                    // A bare IPv6 address needs brackets; without them there is
                    // no telling the address from the port.
                    throw new InvalidHost("put an IPv6 address in [brackets]");
                hostPart = s.substring(0, colon);
                port = parsePort(s.substring(colon + 1));
            } else {
                hostPart = s;
            }
            if (hostPart.isEmpty())
                throw new InvalidHost("no address before the port");
            if (hostPart.length() > MAX_HOST_CHARS)
                throw new InvalidHost("that address is too long to be a host name");
            if (!isHostname(hostPart))
                throw new InvalidHost("not a host name or IP address");
        }

        return new KrakenHost(hostPart, port);
    }

    private static int parsePort(String s) {
        if (s.isEmpty())
            throw new InvalidHost("no port after the colon");
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) < '0' || s.charAt(i) > '9')
                throw new InvalidHost("the port has to be a number");
        }
        if (s.length() > 5)
            throw new InvalidHost("the port has to be 1 to 65535");
        int p = Integer.parseInt(s);
        if (p < 1 || p > 65535)
            throw new InvalidHost("the port has to be 1 to 65535");
        return p;
    }

    /**
     * Letters, digits and hyphens in dot-separated labels. Deliberately stricter
     * than the RFCs allow -- no underscores, no trailing dot, no empty label --
     * because every extra shape accepted here is a shape some URL parser
     * downstream might read differently.
     */
    private static boolean isHostname(String s) {
        int labelStart = 0;
        for (int i = 0; i <= s.length(); i++) {
            if (i == s.length() || s.charAt(i) == '.') {
                int len = i - labelStart;
                if (len == 0 || len > 63)
                    return false;
                char first = s.charAt(labelStart);
                char last = s.charAt(i - 1);
                if (first == '-' || last == '-')
                    return false;
                labelStart = i + 1;
                continue;
            }
            char c = s.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9') || c == '-';
            if (!ok)
                return false;
        }
        return true;
    }

    /** Hex groups and colons inside brackets, with an optional %zone. */
    private static boolean isIpv6Literal(String bracketed) {
        String inner = bracketed.substring(1, bracketed.length() - 1);
        if (inner.isEmpty() || inner.length() > 45 + 16)
            return false;
        boolean sawHex = false;
        for (int i = 0; i < inner.length(); i++) {
            char c = inner.charAt(i);
            boolean hex = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f')
                    || (c >= 'A' && c <= 'F');
            if (hex) {
                sawHex = true;
                continue;
            }
            // '.' for a v4-mapped tail, '%' and alphanumerics for a zone id.
            if (c == ':' || c == '.' || c == '%')
                continue;
            if ((c >= 'g' && c <= 'z') || (c >= 'G' && c <= 'Z')) {
                if (inner.indexOf('%') >= 0 && i > inner.indexOf('%'))
                    continue;
                return false;
            }
            return false;
        }
        return sawHex && inner.indexOf(':') >= 0;
    }

    /** The host as typed, without brackets stripped from an IPv6 literal. */
    public String host() {
        return host;
    }

    /** The port the status files are served on. */
    public int sharePort() {
        return sharePort;
    }

    /**
     * The URL for one feed. The path is the adapter's constant and the host has
     * already been proved to be nothing but a host, so this cannot be talked
     * into reaching anything but the Kraken's own status file.
     */
    public String url(Feed feed) {
        int port = feed.port() == DEFAULT_SHARE_PORT ? sharePort : feed.port();
        return "http://" + host + ":" + port + "/" + feed.path();
    }

    /** What to show the operator: host and port, as they will recognize it. */
    @Override
    public String toString() {
        return host + ":" + sharePort;
    }
}
