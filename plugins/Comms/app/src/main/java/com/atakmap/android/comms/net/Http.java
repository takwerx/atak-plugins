package com.atakmap.android.comms.net;

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
 * <p>Cam Depot's, trimmed: the only thing this plugin fetches is the catalog. HTTPS
 * only, and the body is refused past a few megabytes, so a wrong host or a captive
 * portal cannot hand the plugin something it will choke on. Callbacks land on the
 * main thread. Anonymous classes rather than lambdas throughout: the SDK documents
 * lambdas breaking under release proguard, and this ships in release builds.
 */
public final class Http {

    private static final String TAG = "CommsHttp";

    private static final int CONNECT_TIMEOUT_MS = 10_000;
    private static final int READ_TIMEOUT_MS = 20_000;
    /** The catalog is a few hundred KB; anything near this is not it. */
    private static final int MAX_BYTES = 4 * 1024 * 1024;

    public interface Callback {
        void onSuccess(byte[] body);

        /** @param error already phrased for the operator, not a stack trace */
        void onFailure(String error);
    }

    private static final ExecutorService EXECUTOR = Executors.newFixedThreadPool(
            2, new ThreadFactory() {
                @Override
                public Thread newThread(Runnable r) {
                    final Thread t = new Thread(r, "comms-http");
                    t.setDaemon(true);
                    return t;
                }
            });

    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private Http() {
    }

    public static void get(final String url, final Callback callback) {
        EXECUTOR.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    deliver(callback, request(url), null);
                } catch (IOException e) {
                    Log.w(TAG, "GET failed: " + url, e);
                    deliver(callback, null, describe(e));
                } catch (RuntimeException e) {
                    // Never let a plugin thread take ATAK down.
                    Log.e(TAG, "GET failed hard: " + url, e);
                    deliver(callback, null, "request failed");
                }
            }
        });
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
            conn.setInstanceFollowRedirects(true);
            conn.setRequestProperty("User-Agent", "Comms-ATAK-plugin");

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

    private static void deliver(final Callback callback, final byte[] body,
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
            return "no route to the catalog host";
        if (e instanceof javax.net.ssl.SSLException)
            return "TLS failed";
        return message == null ? "network error" : message;
    }
}
