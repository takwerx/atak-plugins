package com.atakmap.android.uasflightplan.net;

import android.os.Handler;
import android.os.Looper;

import com.atakmap.coremap.log.Log;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

import javax.net.ssl.HttpsURLConnection;

/**
 * Small HTTPS client: bounded threads, bounded time, bounded response size.
 * IPAWS's, copied forward, with a form POST added: an ArcGIS query that carries
 * a geometry is longer than the REST gateway accepts on a GET (it answers 404,
 * not 414, past about 2,000 characters), so every query here is a POST.
 *
 * <p>Anonymous classes rather than lambdas throughout: the SDK documents lambdas
 * breaking under release proguard, and this ships in release builds.
 */
public final class Http {

    private static final String TAG = "UASHttp";

    private static final int CONNECT_TIMEOUT_MS = 10_000;
    private static final int READ_TIMEOUT_MS = 30_000;
    /** One page of 2,000 obstacles with every field is about 1 MB. */
    private static final int MAX_BYTES = 8 * 1024 * 1024;

    /** No HTTP response at all: DNS, TLS, timeout, no route. */
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

    private static final ExecutorService EXECUTOR = Executors.newFixedThreadPool(
            2, new ThreadFactory() {
                @Override
                public Thread newThread(Runnable r) {
                    final Thread t = new Thread(r, "uas-flight-plan-http");
                    t.setDaemon(true);
                    return t;
                }
            });

    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    /** The repo is the contact: a person's address never enters a public tree. */
    public static final String USER_AGENT =
            "(takwerx-uas-flight-plan-atak-plugin, https://github.com/takwerx/uas-flight-plan)";

    private Http() {
    }

    /** Posts a form on a worker thread; the callback lands on the main thread. */
    public static void postForm(final String url, final Map<String, String> form,
            final Callback callback) {
        EXECUTOR.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    deliver(callback, request(url, encode(form)), NO_RESPONSE, null);
                } catch (HttpStatusException e) {
                    deliver(callback, null, e.status, "server returned HTTP " + e.status);
                } catch (IOException e) {
                    Log.w(TAG, "POST failed: " + forLog(url), e);
                    deliver(callback, null, NO_RESPONSE, describe(e));
                } catch (RuntimeException e) {
                    // Never let a plugin thread take ATAK down.
                    Log.e(TAG, "POST failed hard: " + forLog(url), e);
                    deliver(callback, null, NO_RESPONSE, "request failed");
                }
            }
        });
    }

    private static byte[] encode(Map<String, String> form) throws IOException {
        final StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : form.entrySet()) {
            if (sb.length() > 0)
                sb.append('&');
            sb.append(URLEncoder.encode(e.getKey(), "UTF-8"))
                    .append('=')
                    .append(URLEncoder.encode(e.getValue(), "UTF-8"));
        }
        return sb.toString().getBytes("UTF-8");
    }

    /** A response that arrived and was not 200. */
    private static final class HttpStatusException extends IOException {
        final int status;

        HttpStatusException(int status) {
            super("HTTP " + status);
            this.status = status;
        }
    }

    private static byte[] request(String url, byte[] body) throws IOException {
        final URL parsed = new URL(url);
        if (!"https".equalsIgnoreCase(parsed.getProtocol()))
            throw new IOException("refusing a non-https request");

        HttpsURLConnection conn = null;
        InputStream in = null;
        try {
            conn = (HttpsURLConnection) parsed.openConnection();
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(READ_TIMEOUT_MS);
            // No redirects: following one would carry the request somewhere the
            // caller's origin never saw.
            conn.setInstanceFollowRedirects(false);
            conn.setRequestProperty("User-Agent", USER_AGENT);
            conn.setRequestProperty("Accept", "application/json");
            conn.setRequestProperty("Content-Type",
                    "application/x-www-form-urlencoded; charset=UTF-8");
            conn.setDoOutput(true);
            conn.setFixedLengthStreamingMode(body.length);
            final OutputStream out = conn.getOutputStream();
            try {
                out.write(body);
            } finally {
                out.close();
            }

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
            return "no route to the FAA server";
        if (e instanceof javax.net.ssl.SSLException)
            return "TLS failed";
        return message == null ? "network error" : message;
    }

    /** Host and path only: the query carries the launch point, which the log never gets. */
    static String forLog(String url) {
        if (url == null)
            return "";
        final int q = url.indexOf('?');
        return q < 0 ? url : url.substring(0, q);
    }
}
