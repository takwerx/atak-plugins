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

    /** POSTs an already-encoded form body ({@code a=1&b=2}). */
    public static String post(String url, String form) throws IOException {
        return new String(request(url, form), StandardCharsets.UTF_8);
    }

    private static byte[] request(String url, String form) throws IOException {
        final URL parsed = new URL(url);
        if (!"https".equalsIgnoreCase(parsed.getProtocol()))
            throw new IOException("refusing a non-https request");
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
