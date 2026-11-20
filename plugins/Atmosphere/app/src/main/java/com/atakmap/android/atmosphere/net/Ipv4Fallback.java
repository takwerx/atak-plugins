package com.atakmap.android.atmosphere.net;

import com.atakmap.coremap.log.Log;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import javax.net.ssl.HttpsURLConnection;

/**
 * Asking a host over IPv4 when this device's IPv6 does not work with it.
 *
 * <p>FirstNet LTE gives the device an IPv6 address and Android tries IPv6 first. The
 * Census Bureau's firewall answers FirstNet's IPv6 addresses with a "Request
 * Rejected" page (HTTP 200, text/html) and the same request over IPv4 with data
 * (Note 20 on FirstNet LTE, 2026-11-17: every Census lookup in Sit Rep failed with
 * "answer is not JSON"). From the Studio the host works, so no desk test finds it.
 *
 * <p>Copied forward from Sit Rep's {@code net/Http} (no shared module), with the same
 * rules: https only, the host name still sent for SNI and checked against the
 * certificate, no redirects, bounded time and size.
 *
 * <p><b>Only the hosts in {@link #HOSTS} take this path.</b> Every other host goes
 * through the plain connection exactly as before. If a FirstNet probe finds another
 * host rejected or silent on IPv6 (the gate is {@code scripts/check-firstnet.sh}),
 * the whole change is adding its name to {@link #HOSTS}: for the world model layers
 * that is {@code NomadsWind.HOST}, which {@code Http.fetchBytes} and
 * {@code Http.getLarge} reach through the same {@code requestBytes}.
 *
 * <p>The decision ({@link #fetch}) takes its transports as arguments so it can be
 * tested without a network or Android.
 */
final class Ipv4Fallback {

    /**
     * Hosts asked over IPv4 when IPv6 is rejected or silent. Lower case. Census: the
     * geocoder behind the Spot page's address search.
     */
    static final Set<String> HOSTS = Collections.unmodifiableSet(
            new HashSet<>(java.util.Arrays.asList("geocoding.geo.census.gov")));

    private static final int CONNECT_TIMEOUT_MS = 10_000;
    private static final int MAX_BYTES = 8 * 1024 * 1024;
    private static final int IPV6_PROBE_MS = 3_000;

    /** One way of getting the body: the normal connection, or IPv4. */
    interface Transport {
        byte[] get() throws IOException;
    }

    /** Is this host's IPv6 address silent? Asked at most once per host a session. */
    interface V6Probe {
        boolean silent() throws IOException;
    }

    /** Hosts whose firewall rejected this device's IPv6 address and answered over IPv4. */
    private static final Set<String> IPV4_ONLY =
            Collections.synchronizedSet(new HashSet<String>());

    /** Hosts whose IPv6 address was already tried this session. */
    private static final Set<String> IPV6_CHECKED =
            Collections.synchronizedSet(new HashSet<String>());

    private Ipv4Fallback() {
    }

    static boolean appliesTo(String host) {
        return host != null && HOSTS.contains(host.toLowerCase(java.util.Locale.US));
    }

    /** True once a host has been switched to IPv4 for this session. */
    static boolean isIpv4Only(String host) {
        return IPV4_ONLY.contains(host.toLowerCase(java.util.Locale.US));
    }

    /** Forgets what was learned this session. For tests. */
    static void reset() {
        IPV4_ONLY.clear();
        IPV6_CHECKED.clear();
    }

    /**
     * The body for {@code host}: over IPv4 at once when IPv6 is already known bad,
     * else the normal connection, retried over IPv4 when it came back as the
     * firewall's rejection page.
     *
     * @param normal the plain connection (what every other host uses)
     * @param ipv4   the same request over the host's IPv4 address
     * @param probe  whether the host's IPv6 address is silent, asked once per host
     */
    static byte[] fetch(String host, Transport normal, Transport ipv4, V6Probe probe)
            throws IOException {
        final String h = host.toLowerCase(java.util.Locale.US);
        if (IPV4_ONLY.contains(h) || ipv6Dead(h, probe))
            return ipv4.get();
        final byte[] body = normal.get();
        if (!rejected(body))
            return body;
        // Android tried IPv6 first and the firewall refused it with a page that is
        // not what was asked for. The same request over IPv4 gets the data.
        final byte[] again;
        try {
            again = ipv4.get();
        } catch (IOException e) {
            // IPv4 failed too: hand back the first answer, which is what it was.
            return body;
        }
        if (!rejected(again))
            IPV4_ONLY.add(h);
        return again;
    }

    /**
     * True when the host has an IPv4 address and its IPv6 address does not take a
     * connection within 3 s. Checked once per host per session: HttpsURLConnection
     * moves on to IPv4 only after the full connect timeout, on every request.
     */
    static boolean ipv6Dead(String host, V6Probe probe) throws IOException {
        if (!IPV6_CHECKED.add(host))
            return false;
        if (probe.silent()) {
            IPV4_ONLY.add(host);
            return true;
        }
        return false;
    }

    /** The real probe: a TCP connect to the host's IPv6 address, 3 s. */
    static V6Probe socketProbe(final String host, final int port) {
        return new V6Probe() {
            @Override
            public boolean silent() {
                java.net.InetAddress v6 = null;
                boolean hasV4 = false;
                try {
                    for (java.net.InetAddress a : java.net.InetAddress.getAllByName(host)) {
                        if (a instanceof java.net.Inet6Address && v6 == null)
                            v6 = a;
                        else if (a instanceof java.net.Inet4Address)
                            hasV4 = true;
                    }
                } catch (IOException e) {
                    return false; // Let the real request report it.
                }
                if (v6 == null || !hasV4)
                    return false;
                final java.net.Socket s = new java.net.Socket();
                try {
                    s.connect(new java.net.InetSocketAddress(v6, port), IPV6_PROBE_MS);
                    return false;
                } catch (java.net.SocketTimeoutException e) {
                    // Only a silent address costs the full connect timeout. A network
                    // with no IPv6 refuses at once, and Android moves to IPv4 itself.
                    Log.w("WxHttp", host + " does not answer on IPv6; using IPv4");
                    return true;
                } catch (IOException e) {
                    return false;
                } finally {
                    try {
                        s.close();
                    } catch (IOException ignored) {
                        // Probe only.
                    }
                }
            }
        };
    }

    /** A firewall's rejection page (F5): HTTP 200 with a short HTML body. */
    static boolean rejected(byte[] body) {
        if (body == null || body.length > 4096)
            return false;
        return new String(body, StandardCharsets.UTF_8).contains("<title>Request Rejected</title>");
    }

    /**
     * The same GET over the host's IPv4 address. HttpsURLConnection picks the address
     * itself, so this is a plain TLS socket: the host name is still the one sent for
     * SNI and the one the certificate is checked against. HTTP/1.0, so the body ends
     * when the server closes the connection; no redirects.
     */
    static byte[] overIpv4(URL url, String userAgent, Map<String, String> headers,
            int readTimeoutMs) throws IOException {
        final String host = url.getHost();
        java.net.InetAddress v4 = null;
        for (java.net.InetAddress a : java.net.InetAddress.getAllByName(host))
            if (a instanceof java.net.Inet4Address) {
                v4 = a;
                break;
            }
        if (v4 == null)
            throw new IOException("no IPv4 address for " + host);
        final int port = url.getPort() < 0 ? 443 : url.getPort();
        final java.net.Socket raw = new java.net.Socket();
        javax.net.ssl.SSLSocket s = null;
        try {
            raw.connect(new java.net.InetSocketAddress(v4, port), CONNECT_TIMEOUT_MS);
            raw.setSoTimeout(readTimeoutMs);
            s = (javax.net.ssl.SSLSocket) ((javax.net.ssl.SSLSocketFactory)
                    javax.net.ssl.SSLSocketFactory.getDefault()).createSocket(raw, host, port, true);
            if (android.os.Build.VERSION.SDK_INT >= 24) {
                final javax.net.ssl.SSLParameters p = s.getSSLParameters();
                p.setServerNames(Collections.<javax.net.ssl.SNIServerName>singletonList(
                        new javax.net.ssl.SNIHostName(host)));
                p.setEndpointIdentificationAlgorithm("HTTPS");
                s.setSSLParameters(p);
            }
            s.startHandshake();
            if (!HttpsURLConnection.getDefaultHostnameVerifier().verify(host, s.getSession()))
                throw new javax.net.ssl.SSLPeerUnverifiedException(
                        "certificate does not name " + host);

            final String path = url.getFile().isEmpty() ? "/" : url.getFile();
            final StringBuilder head = new StringBuilder();
            head.append("GET ").append(path).append(" HTTP/1.0\r\n")
                    .append("Host: ").append(host).append(url.getPort() < 0 ? "" : ":" + port)
                    .append("\r\n")
                    .append("User-Agent: ").append(userAgent).append("\r\n")
                    .append("Accept: */*\r\nAccept-Encoding: identity\r\nConnection: close\r\n");
            if (headers != null)
                for (Map.Entry<String, String> e : headers.entrySet())
                    if (safeHeader(e.getKey(), e.getValue()))
                        head.append(e.getKey()).append(": ").append(e.getValue()).append("\r\n");
            head.append("\r\n");
            final OutputStream out = s.getOutputStream();
            out.write(head.toString().getBytes(StandardCharsets.UTF_8));
            out.flush();

            final InputStream in = new BufferedInputStream(s.getInputStream());
            final String headersIn = readHeaders(in);
            final java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("^HTTP/1\\.[01] (\\d{3})").matcher(headersIn);
            if (!m.find())
                throw new IOException("not an HTTP answer");
            final int status = Integer.parseInt(m.group(1));
            if (status == HttpURLConnection.HTTP_NO_CONTENT)
                return new byte[0];
            if (status == HttpURLConnection.HTTP_NOT_FOUND)
                throw new Http.StatusException(status, "no data for this point (HTTP 404)");
            if (status != HttpURLConnection.HTTP_OK)
                throw new Http.StatusException(status, "provider returned HTTP " + status);
            final String lower = headersIn.toLowerCase(java.util.Locale.US);
            if (lower.contains("\ntransfer-encoding:") && lower.contains("chunked"))
                throw new IOException("chunked answer to an HTTP/1.0 request");
            if (lower.contains("\ncontent-encoding:") && !lower.contains("content-encoding: identity"))
                throw new IOException("encoded answer that was not asked for");
            return read(in);
        } finally {
            try {
                if (s != null)
                    s.close();
                else
                    raw.close();
            } catch (IOException ignored) {
                // Already have the body or the failure.
            }
        }
    }

    /** A header the caller named may not carry a line break into the request. */
    private static boolean safeHeader(String k, String v) {
        return k != null && v != null && k.indexOf('\r') < 0 && k.indexOf('\n') < 0
                && k.indexOf(':') < 0 && v.indexOf('\r') < 0 && v.indexOf('\n') < 0;
    }

    /** The status line and headers, up to the blank line; at most 64 KB. */
    private static String readHeaders(InputStream in) throws IOException {
        final ByteArrayOutputStream h = new ByteArrayOutputStream(1024);
        int c, last4 = 0;
        while ((c = in.read()) >= 0) {
            h.write(c);
            last4 = (last4 << 8) | c;
            if (last4 == 0x0D0A0D0A)
                return new String(h.toByteArray(), StandardCharsets.ISO_8859_1);
            if (h.size() > 64 * 1024)
                throw new IOException("headers larger than 64 KB");
        }
        throw new IOException("connection closed before the headers ended");
    }

    private static byte[] read(InputStream in) throws IOException {
        final ByteArrayOutputStream out = new ByteArrayOutputStream(16 * 1024);
        final byte[] buf = new byte[8192];
        int n;
        int total = 0;
        while ((n = in.read(buf)) > 0) {
            total += n;
            if (total > MAX_BYTES)
                throw new IOException("response larger than "
                        + (MAX_BYTES / (1024 * 1024)) + " MB");
            out.write(buf, 0, n);
        }
        return out.toByteArray();
    }
}
