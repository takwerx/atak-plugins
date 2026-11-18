package com.atakmap.android.sitrep.net;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

import javax.net.ssl.HttpsURLConnection;

/**
 * HTTPS GET and POST for the section fetches. Synchronous: every caller is already on
 * a report worker, and one section waiting on its server must hold only its own
 * thread.
 *
 * <p>Copied forward from IPAWS's {@code Http} (no shared module): https only, no
 * redirects (a redirect would carry the request somewhere the source constant never
 * named), bounded time, bounded size.
 */
public final class Http {

    private static final int CONNECT_TIMEOUT_MS = 15_000;
    private static final int READ_TIMEOUT_MS = 45_000;
    /** A statistics answer is a few KB; anything near this is not what was asked for. */
    private static final int MAX_BYTES = 8 * 1024 * 1024;

    public static final String USER_AGENT =
            "(takwerx-sitrep-atak-plugin, https://github.com/takwerx/sit-rep)";

    private Http() {
    }

    /** A response that arrived and was not 200. */
    public static final class StatusException extends IOException {
        public final int status;

        StatusException(int status) {
            super("HTTP " + status);
            this.status = status;
        }
    }

    public static String get(String url) throws IOException {
        return new String(request(url, null), StandardCharsets.UTF_8);
    }

    /** A gzipped file (our tile packs), unpacked; capped like any other answer. */
    public static String getGzip(String url) throws IOException {
        final java.util.zip.GZIPInputStream in = new java.util.zip.GZIPInputStream(
                new java.io.ByteArrayInputStream(request(url, null)));
        try {
            return new String(read(in), StandardCharsets.UTF_8);
        } finally {
            in.close();
        }
    }

    /** POSTs an already-encoded form body ({@code a=1&b=2}). */
    public static String post(String url, String form) throws IOException {
        return new String(request(url, form), StandardCharsets.UTF_8);
    }

    /**
     * Hosts whose firewall rejected this device's IPv6 address and answered over
     * IPv4: asked over IPv4 for the rest of the session.
     */
    private static final java.util.Set<String> IPV4_ONLY =
            java.util.Collections.synchronizedSet(new java.util.HashSet<String>());

    private static byte[] request(String url, String form) throws IOException {
        final URL parsed = new URL(url);
        if (!"https".equalsIgnoreCase(parsed.getProtocol()))
            throw new IOException("refusing a non-https request");
        final String host = parsed.getHost().toLowerCase(java.util.Locale.US);
        if (IPV4_ONLY.contains(host))
            return overIpv4(parsed, form);
        final byte[] body = connection(parsed, form);
        if (!rejected(body))
            return body;
        // TIGERweb's firewall answers FirstNet's IPv6 addresses with a "Request
        // Rejected" page (HTTP 200, text/html) and the same request over IPv4 with
        // data (Note 20 on FirstNet LTE, 2026-11-17: every Census section and place
        // pick failed with "answer is not JSON"). Android tries IPv6 first.
        final byte[] again;
        try {
            again = overIpv4(parsed, form);
        } catch (IOException e) {
            com.atakmap.coremap.log.Log.w("SitRep", host + " rejected the request; IPv4 failed too: " + e);
            return body;
        }
        if (!rejected(again)) {
            IPV4_ONLY.add(host);
            com.atakmap.coremap.log.Log.w("SitRep", host + " rejected this device's IPv6 address; using IPv4");
        }
        return again;
    }

    /** A firewall's rejection page (F5): HTTP 200 with a short HTML body. */
    static boolean rejected(byte[] body) {
        if (body == null || body.length > 4096)
            return false;
        return new String(body, StandardCharsets.UTF_8).contains("<title>Request Rejected</title>");
    }

    /**
     * The same request over the host's IPv4 address. HttpsURLConnection picks the
     * address itself, so this is a plain TLS socket: the host name is still the one
     * sent for SNI and the one the certificate is checked against. HTTP/1.0, so the
     * body ends when the server closes the connection; no redirects, as above.
     */
    static byte[] overIpv4(URL url, String form) throws IOException {
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
                    .append("User-Agent: ").append(USER_AGENT).append("\r\n")
                    .append("Accept: */*\r\nConnection: close\r\n");
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
            if (status != HttpURLConnection.HTTP_OK)
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

    private static byte[] connection(URL parsed, String form) throws IOException {
        HttpsURLConnection conn = null;
        InputStream in = null;
        try {
            conn = (HttpsURLConnection) parsed.openConnection();
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(READ_TIMEOUT_MS);
            conn.setInstanceFollowRedirects(false);
            conn.setRequestProperty("User-Agent", USER_AGENT);
            if (form != null) {
                final byte[] body = form.getBytes(StandardCharsets.UTF_8);
                conn.setRequestMethod("POST");
                conn.setDoOutput(true);
                conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
                conn.setFixedLengthStreamingMode(body.length);
                final OutputStream out = conn.getOutputStream();
                try {
                    out.write(body);
                } finally {
                    out.close();
                }
            } else {
                conn.setRequestMethod("GET");
            }
            final int status = conn.getResponseCode();
            if (status != HttpURLConnection.HTTP_OK)
                throw new StatusException(status);
            in = conn.getInputStream();
            return read(in);
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (IOException ignored) {
                    // Already have the body or the failure.
                }
            }
            if (conn != null)
                conn.disconnect();
        }
    }

    private static byte[] read(InputStream in) throws IOException {
        final ByteArrayOutputStream out = new ByteArrayOutputStream(16 * 1024);
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

    /** True when the failure means this device has no network, not that the server is down. */
    public static boolean noNetwork(Throwable e) {
        return e instanceof java.net.UnknownHostException
                || e instanceof java.net.ConnectException
                || e instanceof java.net.NoRouteToHostException;
    }

    /** A URL as it may appear in the log: no query string. */
    public static String forLog(String url) {
        if (url == null)
            return "";
        final int q = url.indexOf('?');
        return q < 0 ? url : url.substring(0, q);
    }
}
