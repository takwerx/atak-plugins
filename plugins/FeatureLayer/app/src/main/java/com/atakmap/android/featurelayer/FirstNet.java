package com.atakmap.android.featurelayer;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;

import javax.net.ssl.HttpsURLConnection;

/**
 * The IPv4 fallback for FirstNet, copied forward from Sit Rep 0.4's {@code net/Http}
 * (no shared module).
 *
 * <p>FirstNet LTE gives the device an IPv6 address and Android tries IPv6 first. The
 * Census Bureau's firewall answers FirstNet's IPv6 addresses with a "Request Rejected"
 * page (HTTP 200, text/html) and the same request over IPv4 with data: TIGERweb, which
 * every Boundaries layer reads (Sit Rep on a Note 20, Arlington VA, 2026-11-17). Some
 * hosts take no IPv6 connection at all, and HttpsURLConnection moves on to IPv4 only
 * after the full connect timeout, on every request (EPA geodata, 2026-11-18).
 */
final class FirstNet {
    private FirstNet() {
    }

    private static final String TAG = "FeatureLayer";
    private static final int CONNECT_TIMEOUT_MS = 20_000;
    private static final int READ_TIMEOUT_MS = 60_000;
    private static final int IPV6_PROBE_MS = 3_000;
    /** A whole-state boundary page is a few MB; far past this is not an answer. */
    private static final int MAX_BYTES = 64 * 1024 * 1024;

    /** Hosts that rejected or ignored this device's IPv6 address: IPv4 for the session. */
    private static final java.util.Set<String> IPV4_ONLY =
            java.util.Collections.synchronizedSet(new java.util.HashSet<String>());

    /** Hosts whose IPv6 address was already tried this session. */
    private static final java.util.Set<String> IPV6_CHECKED =
            java.util.Collections.synchronizedSet(new java.util.HashSet<String>());

    /** A response that arrived over IPv4 and was not 200. */
    static final class StatusException extends IOException {
        final int status;

        StatusException(int status) {
            super("HTTP " + status);
            this.status = status;
        }
    }

    /** True when this host goes straight to IPv4: already known, or IPv6 is silent. */
    static boolean useIpv4(URL url) {
        final String host = url.getHost().toLowerCase(java.util.Locale.US);
        return IPV4_ONLY.contains(host) || ipv6Dead(host, url.getPort() < 0 ? 443 : url.getPort());
    }

    /** Records that this host answered over IPv4 after rejecting IPv6. */
    static void rememberIpv4(URL url) {
        final String host = url.getHost().toLowerCase(java.util.Locale.US);
        if (IPV4_ONLY.add(host))
            com.atakmap.coremap.log.Log.w(TAG, host + " rejected this device's IPv6 address; using IPv4");
    }

    /**
     * True when the host has an IPv4 address and its IPv6 address does not take a TCP
     * connection within 3 s. Checked once per host per session. A network with no IPv6
     * refuses at once, and Android moves to IPv4 at once by itself; only a silent
     * address costs the full connect timeout.
     */
    static boolean ipv6Dead(String host, int port) {
        if (!IPV6_CHECKED.add(host))
            return false;
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
            IPV4_ONLY.add(host);
            com.atakmap.coremap.log.Log.w(TAG, host + " does not answer on IPv6; using IPv4");
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

    /** A firewall's rejection page (F5): HTTP 200 with a short HTML body. */
    static boolean rejected(byte[] body) {
        if (body == null || body.length > 4096)
            return false;
        return new String(body, StandardCharsets.UTF_8).contains("<title>Request Rejected</title>");
    }

    /**
     * The same request over the host's IPv4 address. HttpsURLConnection picks the
     * address itself, so this is a plain TLS socket: the host name is still the one sent
     * for SNI and the one the certificate is checked against. HTTP/1.0, so the body ends
     * when the server closes the connection; no redirects. The token travels in the Esri
     * authorization header, as on the normal path.
     */
    static byte[] overIpv4(URL url, String form, String token, String userAgent) throws IOException {
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
            raw.setSoTimeout(READ_TIMEOUT_MS);
            s = (javax.net.ssl.SSLSocket) ((javax.net.ssl.SSLSocketFactory) javax.net.ssl.SSLSocketFactory.getDefault())
                    .createSocket(raw, host, port, true);
            if (android.os.Build.VERSION.SDK_INT >= 24) {
                final javax.net.ssl.SSLParameters p = s.getSSLParameters();
                p.setServerNames(java.util.Collections.<javax.net.ssl.SNIServerName>singletonList(
                        new javax.net.ssl.SNIHostName(host)));
                p.setEndpointIdentificationAlgorithm("HTTPS");
                s.setSSLParameters(p);
            }
            s.startHandshake();
            if (!HttpsURLConnection.getDefaultHostnameVerifier().verify(host, s.getSession()))
                throw new javax.net.ssl.SSLPeerUnverifiedException("certificate does not name " + host);

            final String path = url.getFile().isEmpty() ? "/" : url.getFile();
            final byte[] body = form == null ? null : form.getBytes(StandardCharsets.UTF_8);
            final StringBuilder head = new StringBuilder();
            head.append(body == null ? "GET " : "POST ").append(path).append(" HTTP/1.0\r\n")
                    .append("Host: ").append(host).append(url.getPort() < 0 ? "" : ":" + port).append("\r\n")
                    .append("User-Agent: ").append(userAgent).append("\r\n")
                    .append("Accept: */*\r\nConnection: close\r\n");
            if (token != null)
                head.append("X-Esri-Authorization: Bearer ").append(token).append("\r\n");
            if (body != null)
                head.append("Content-Type: application/x-www-form-urlencoded\r\n")
                        .append("Content-Length: ").append(body.length).append("\r\n");
            head.append("\r\n");
            final OutputStream out = s.getOutputStream();
            out.write(head.toString().getBytes(StandardCharsets.UTF_8));
            if (body != null)
                out.write(body);
            out.flush();

            final InputStream in = new java.io.BufferedInputStream(s.getInputStream());
            final String headers = readHeaders(in);
            final java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("^HTTP/1\\.[01] (\\d{3})").matcher(headers);
            if (!m.find())
                throw new IOException("not an HTTP answer");
            final int status = Integer.parseInt(m.group(1));
            if (status != 200)
                throw new StatusException(status);
            final String lower = headers.toLowerCase(java.util.Locale.US);
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

    static byte[] read(InputStream in) throws IOException {
        final ByteArrayOutputStream out = new ByteArrayOutputStream(64 * 1024);
        final byte[] buf = new byte[16384];
        int n;
        int total = 0;
        while ((n = in.read(buf)) > 0) {
            total += n;
            if (total > MAX_BYTES)
                throw new IOException("response larger than " + (MAX_BYTES / (1024 * 1024)) + " MB");
            out.write(buf, 0, n);
        }
        return out.toByteArray();
    }
}
