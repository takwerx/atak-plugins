package com.atakmap.android.iwi.plugin;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

import javax.net.ssl.HttpsURLConnection;

/**
 * Small HTTPS GET client: bounded time, bounded response size, HTTPS only.
 *
 * <p>AirAware's, carried forward. IWI uses it for exactly one thing, fetching the
 * trauma center pack from our own depot, on the manager's worker thread.
 *
 * <p>Anonymous classes rather than lambdas anywhere this grows: the SDK documents lambdas
 * breaking under release proguard, and this ships in release builds.
 */
public final class Http {

    private static final int CONNECT_TIMEOUT_MS = 10_000;
    private static final int READ_TIMEOUT_MS = 20_000;
    /** The pack is under 100 KB gzipped; a response many times that is not the pack. */
    private static final int MAX_BYTES = 4 * 1024 * 1024;

    private Http() {
    }

    /**
     * Fetch a URL on the calling thread.
     *
     * @throws IOException with a message already phrased for the operator
     */
    public static byte[] get(String url) throws IOException {
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
        final ByteArrayOutputStream out = new ByteArrayOutputStream(64 * 1024);
        final byte[] buf = new byte[16384];
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

    /** What to put in front of an operator when a fetch fails. */
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
