package com.atakmap.android.ipaws.net;

import android.os.Handler;
import android.os.Looper;

import com.atakmap.coremap.log.Log;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

import javax.net.ssl.HttpsURLConnection;

/**
 * Small HTTPS GET client: bounded threads, bounded time, bounded response size.
 *
 * <p>Comms's, with one addition that the rest of this plugin depends on: the failure
 * callback carries the HTTP status. api.weather.gov answers 404 with
 * {@code "type": ".../InvalidZone"} for a zone it does not hold, and that is durable
 * absence a cache may remember. A timeout is not, and caching it the same way would
 * poison the zone that comes back tomorrow. A caller cannot tell those apart from a
 * message string, so the status comes through.
 *
 * <p>Anonymous classes rather than lambdas throughout: the SDK documents lambdas
 * breaking under release proguard, and this ships in release builds.
 */
public final class Http {

    private static final String TAG = "IPAWS";

    private static final int CONNECT_TIMEOUT_MS = 10_000;
    private static final int READ_TIMEOUT_MS = 30_000;
    /**
     * Every active alert in the country was about 1.5 MB when this was measured; a
     * user may select all 74 areas, and anything past this is not the feed.
     */
    private static final int MAX_BYTES = 8 * 1024 * 1024;

    /** No HTTP response at all -- DNS, TLS, timeout, no route. Never cacheable. */
    public static final int NO_RESPONSE = 0;

    public interface Callback {
        void onSuccess(byte[] body);

        /**
         * @param status the HTTP status, or {@link #NO_RESPONSE} when the request never
         *               got one
         * @param error  already phrased for the operator, not a stack trace
         */
        void onFailure(int status, String error);
    }

    /**
     * Two threads. The alert poll and the zone fetches share them, so a slow zone
     * cannot stall the poll behind it and neither can open an unbounded number of
     * sockets from a phone.
     */
    private static final ExecutorService EXECUTOR = Executors.newFixedThreadPool(
            2, new ThreadFactory() {
                @Override
                public Thread newThread(Runnable r) {
                    final Thread t = new Thread(r, "ipaws-http");
                    t.setDaemon(true);
                    return t;
                }
            });

    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private Http() {
    }

    /** Fetches on a worker thread; the callback lands on the main thread. */
    public static void get(final String url, final Callback callback) {
        EXECUTOR.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    deliver(callback, request(url), NO_RESPONSE, null);
                } catch (HttpStatusException e) {
                    // Not logged as a warning: an expected 404 for a zone the server
                    // does not hold is ordinary, and the caller decides what it means.
                    deliver(callback, null, e.status, "server returned HTTP " + e.status);
                } catch (IOException e) {
                    Log.w(TAG, "GET failed: " + url, e);
                    deliver(callback, null, NO_RESPONSE, describe(e));
                } catch (RuntimeException e) {
                    // Never let a plugin thread take ATAK down.
                    Log.e(TAG, "GET failed hard: " + url, e);
                    deliver(callback, null, NO_RESPONSE, "request failed");
                }
            }
        });
    }

    /** A response that arrived and was not 200. Carries the status so a caller can act on it. */
    private static final class HttpStatusException extends IOException {
        final int status;

        HttpStatusException(int status) {
            super("HTTP " + status);
            this.status = status;
        }
    }

    private static byte[] request(String url) throws IOException {
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
            // No redirects. Both endpoints were measured answering 200 with zero
            // redirects, so nothing needs this -- and following one would carry the
            // request somewhere the caller's origin check never saw, since that check
            // only ever looks at the URL it was handed.
            conn.setInstanceFollowRedirects(false);
            // NWS asks callers to identify themselves. The repo is the contact: a
            // person's address may never enter a public tree (CLAUDE.md, publish scrub).
            conn.setRequestProperty("User-Agent", USER_AGENT);
            conn.setRequestProperty("Accept", "application/geo+json");

            final int status = conn.getResponseCode();
            if (status != HttpURLConnection.HTTP_OK)
                throw new HttpStatusException(status);

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

    public static final String USER_AGENT =
            "(takwerx-ipaws-atak-plugin, https://github.com/takwerx/ipaws-alerts)";

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

    private static void deliver(final Callback callback, final byte[] body,
            final int status, final String error) {
        if (callback == null)
            return;
        MAIN.post(new Runnable() {
            @Override
            public void run() {
                if (error == null)
                    callback.onSuccess(body);
                else
                    callback.onFailure(status, error);
            }
        });
    }

    private static String describe(IOException e) {
        final String message = e.getMessage();
        if (e instanceof java.net.SocketTimeoutException)
            return "timed out";
        if (e instanceof java.net.UnknownHostException)
            return "no route to the alert service";
        if (e instanceof javax.net.ssl.SSLException)
            return "TLS failed";
        return message == null ? "network error" : message;
    }
}
