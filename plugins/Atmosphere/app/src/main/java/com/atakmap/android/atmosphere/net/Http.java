package com.atakmap.android.atmosphere.net;

import android.os.Handler;
import android.os.Looper;

import com.atakmap.coremap.log.Log;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

import javax.net.ssl.HttpsURLConnection;

/**
 * Small HTTPS GET client: bounded threads, bounded time, bounded response size.
 *
 * <p>Callbacks land on the main thread, so callers can touch views directly. Anonymous
 * classes rather than lambdas throughout — the ATAK SDK documents lambdas breaking under
 * release proguard, and this code ships in release builds.
 */
public final class Http {

    private static final String TAG = "WxHttp";

    private static final int CONNECT_TIMEOUT_MS = 10_000;
    private static final int READ_TIMEOUT_MS = 15_000;
    /** A forecast response is tens of KB. Anything past this is not one. */
    private static final int MAX_BYTES = 4 * 1024 * 1024;
    /** Bounded pool: a burst of requests must not spawn a thread per request. */
    private static final int MAX_CONCURRENT = 3;

    public interface Callback {
        void onSuccess(String body);

        /** @param error already phrased for the operator, not a stack trace */
        void onFailure(String error);
    }

    private static final ExecutorService EXECUTOR = Executors.newFixedThreadPool(
            MAX_CONCURRENT, new ThreadFactory() {
                @Override
                public Thread newThread(Runnable r) {
                    final Thread t = new Thread(r, "wx-http");
                    t.setDaemon(true);
                    return t;
                }
            });

    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private Http() {
    }

    public static void get(final String url, final String userAgent,
            final Map<String, String> headers, final Callback callback) {

        EXECUTOR.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    deliver(callback, request(url, userAgent, headers), null);
                } catch (IOException e) {
                    Log.w(TAG, "GET failed: " + safeUrl(url), e);
                    deliver(callback, null, describe(e));
                } catch (RuntimeException e) {
                    // Never let a plugin thread take ATAK down.
                    Log.e(TAG, "GET failed hard: " + safeUrl(url), e);
                    deliver(callback, null, "request failed");
                }
            }
        });
    }

    private static String request(String url, String userAgent, Map<String, String> headers)
            throws IOException {

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
            conn.setInstanceFollowRedirects(true);
            conn.setRequestProperty("User-Agent", userAgent);
            conn.setRequestProperty("Accept-Encoding", "identity");
            if (headers != null) {
                for (Map.Entry<String, String> e : headers.entrySet())
                    conn.setRequestProperty(e.getKey(), e.getValue());
            }

            final int status = conn.getResponseCode();
            if (status == HttpURLConnection.HTTP_NO_CONTENT)
                return "";
            if (status != HttpURLConnection.HTTP_OK)
                throw new IOException("provider returned HTTP " + status);

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

    private static String read(InputStream in) throws IOException {
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
        return out.toString("UTF-8");
    }

    private static void deliver(final Callback callback, final String body,
            final String error) {
        if (callback == null)
            return;
        MAIN.post(new Runnable() {
            @Override
            public void run() {
                if (error == null)
                    callback.onSuccess(body);
                else
                    callback.onFailure(error);
            }
        });
    }

    private static String describe(IOException e) {
        final String message = e.getMessage();
        if (e instanceof java.net.SocketTimeoutException)
            return "timed out";
        if (e instanceof java.net.UnknownHostException)
            return "no route to the provider";
        if (e instanceof javax.net.ssl.SSLException)
            return "TLS failed";
        return message == null ? "network error" : message;
    }

    /** Query strings can carry coordinates; keep them out of the log. */
    private static String safeUrl(String url) {
        if (url == null)
            return "";
        final int q = url.indexOf('?');
        return q < 0 ? url : url.substring(0, q) + "?…";
    }
}
