package com.atakmap.android.iwi.plugin;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

import javax.net.ssl.HttpsURLConnection;

/**
 * Small HTTPS GET client: bounded time, bounded response size, HTTPS only, no
 * redirects.
 *
 * <p>IWI's (AirAware's before it), carried forward; shared helpers are copy-forward.
 * IWI uses it on our own depot, on its manager's worker thread:
 * the Lessons Learned catalog and IWI's trauma center pack.
 */
public final class Http {

    private static final int CONNECT_TIMEOUT_MS = 10_000;
    private static final int READ_TIMEOUT_MS = 30_000;

    private Http() {
    }

    /**
     * Fetch a URL on the calling thread.
     *
     * @param maxBytes the most this answer may be; more is not the file we asked for
     * @throws IOException with a message already phrased for a log
     */
    public static byte[] get(String url, int maxBytes) throws IOException {
        final URL parsed = new URL(url);
        if (!"https".equalsIgnoreCase(parsed.getProtocol()))
            throw new IOException("refusing a non-https request");

        HttpsURLConnection conn = null;
        InputStream in = null;
        try {
            conn = (HttpsURLConnection) parsed.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(READ_TIMEOUT_MS);
            // A redirect off our host would be followed blind; the depot never redirects.
            conn.setInstanceFollowRedirects(false);
            conn.setRequestProperty("User-Agent", "IWI-ATAK-plugin");
            conn.setRequestProperty("Accept-Encoding", "identity");

            final int status = conn.getResponseCode();
            if (status != HttpURLConnection.HTTP_OK)
                throw new IOException("server returned HTTP " + status);
            final long declared = conn.getContentLengthLong();
            if (declared > maxBytes)
                throw new IOException("answer of " + declared + " bytes is too large");

            in = conn.getInputStream();
            return read(in, maxBytes);
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

    private static byte[] read(InputStream in, int maxBytes) throws IOException {
        final ByteArrayOutputStream out = new ByteArrayOutputStream(64 * 1024);
        final byte[] buf = new byte[16384];
        int n;
        int total = 0;
        while ((n = in.read(buf)) > 0) {
            total += n;
            if (total > maxBytes)
                throw new IOException("answer larger than " + maxBytes + " bytes");
            out.write(buf, 0, n);
        }
        return out.toByteArray();
    }

    /** What to put in front of an operator when a fetch fails (IWI's). */
    public static String describe(Exception e) {
        if (e instanceof java.net.SocketTimeoutException)
            return "timed out";
        if (e instanceof java.net.UnknownHostException)
            return "no network";
        if (e instanceof javax.net.ssl.SSLException)
            return "TLS failed";
        final String message = e.getMessage();
        return message == null ? "network error" : message;
    }
}
