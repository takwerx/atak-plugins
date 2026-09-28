package com.atakmap.android.signaldf.net;

import android.net.Network;
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

/**
 * Small plain-HTTP GET client for the radio on the local network: bounded
 * threads, bounded time, bounded response size.
 *
 * <p>Adapted from {@code CamDepot/net/Http.java}, and the differences are all
 * consequences of what is at the other end. CamDepot fetches images from a CDN
 * over TLS; this fetches a few hundred bytes of status file from a Raspberry Pi
 * on the same vehicle.
 *
 * <ul>
 * <li><b>Plain http, and https is refused.</b> CamDepot's version refuses
 *     anything that is not https; this is the mirror image, because the Kraken
 *     serves plaintext only and an https attempt would fail with a TLS error
 *     that reads like a broken radio. The status files are unauthenticated and
 *     nothing private crosses the wire, but this is a LAN-only design and the
 *     pane says so rather than implying otherwise.
 * <li><b>Short timeouts.</b> CamDepot waits 10 and 20 seconds. Here the poll
 *     interval is about a second and the host is one hop away, so a request
 *     that has not answered in three seconds has failed -- and a poll that
 *     blocks for twenty is a pane that shows a bearing from half a minute ago
 *     as though it were live.
 * <li><b>Text, not bytes</b>, and no gzip: these three feeds are XML, CSV and
 *     JSON, and none of them is compressed.
 * <li><b>No redirects.</b> There is nowhere for the Kraken's own status file to
 *     redirect to, so a redirect means something other than the radio answered
 *     and following it would send the request somewhere the operator did not
 *     name.
 * </ul>
 *
 * <p>Callbacks land on the main thread, so callers can touch views directly.
 * Anonymous classes rather than lambdas throughout -- the ATAK SDK documents
 * lambdas breaking under release proguard, and this ships in release builds.
 */
public final class Http {

    private static final String TAG = "SignalDFHttp";

    /** One hop on a LAN. Anything slower is a failure worth reporting. */
    private static final int CONNECT_TIMEOUT_MS = 2_500;
    private static final int READ_TIMEOUT_MS = 3_000;

    /**
     * 16 VFOs of 360-point spectrum is around 400 KB; this is comfortably
     * above that and far below anything that would trouble the phone.
     */
    private static final int MAX_BYTES = 2 * 1024 * 1024;

    /** The link polls one feed at a time; the spare is for a probe alongside. */
    private static final int MAX_CONCURRENT = 2;

    public interface Callback {
        void onSuccess(String body);

        /** @param error already phrased for the operator, not a stack trace */
        void onFailure(String error);
    }

    private static final ExecutorService EXECUTOR = Executors.newFixedThreadPool(
            MAX_CONCURRENT, new ThreadFactory() {
                @Override
                public Thread newThread(Runnable r) {
                    final Thread t = new Thread(r, "signaldf-http");
                    t.setDaemon(true);
                    return t;
                }
            });

    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private Http() {
    }

    /** GET {@code url} over the phone's default network. */
    public static void get(final String url, final Callback callback) {
        get(url, null, callback);
    }

    /**
     * GET {@code url}, delivering the body as text on the main thread.
     *
     * @param network the network to send this request over, or null for the
     *                phone's default. See {@link WifiPin}: on a vehicle the
     *                radio is on a WiFi access point with no internet while the
     *                TAK server is on cellular, and pinning only these requests
     *                to the WiFi is what lets both work at once.
     */
    public static void get(final String url, final Network network,
            final Callback callback) {
        EXECUTOR.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    deliver(callback, request(url, network), null);
                } catch (IOException e) {
                    Log.w(TAG, "GET failed: " + url + " (" + describe(e) + ")");
                    deliver(callback, null, describe(e));
                } catch (RuntimeException e) {
                    // Never let a plugin thread take ATAK down.
                    Log.e(TAG, "GET failed hard: " + url, e);
                    deliver(callback, null, "request failed");
                }
            }
        });
    }

    private static String request(String url, Network network) throws IOException {
        final URL parsed = new URL(url);
        if (!"http".equalsIgnoreCase(parsed.getProtocol()))
            throw new IOException("refusing a non-http request");

        HttpURLConnection conn = null;
        InputStream in = null;
        try {
            // openConnection ON the network, not the default one, when we have
            // been given a network to use.
            conn = (HttpURLConnection) (network == null
                    ? parsed.openConnection()
                    : network.openConnection(parsed));
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(READ_TIMEOUT_MS);
            // See the class comment: the radio's own file never redirects.
            conn.setInstanceFollowRedirects(false);
            conn.setUseCaches(false);
            conn.setRequestProperty("User-Agent", "SignalDF-ATAK-plugin");
            conn.setRequestProperty("Accept-Encoding", "identity");
            // The DSP rewrites these files every frame. A cached copy is a
            // stale bearing, which is the one failure this plugin must not have.
            conn.setRequestProperty("Cache-Control", "no-cache");

            final int status = conn.getResponseCode();
            if (status != HttpURLConnection.HTTP_OK)
                throw new IOException("the radio answered HTTP " + status);

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
        final ByteArrayOutputStream out = new ByteArrayOutputStream(8 * 1024);
        final byte[] buf = new byte[8192];
        int n;
        int total = 0;
        while ((n = in.read(buf)) > 0) {
            total += n;
            if (total > MAX_BYTES)
                throw new IOException("the response was larger than "
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

    /**
     * Failures phrased so the operator knows what to go and check. "Cannot
     * connect" with no port number costs a crew twenty minutes in the field,
     * which is why the port is in the message.
     */
    private static String describe(IOException e) {
        final String message = e.getMessage();
        if (e instanceof java.net.SocketTimeoutException)
            return "timed out";
        if (e instanceof java.net.UnknownHostException)
            return "cannot find that host on this network";
        if (e instanceof java.net.ConnectException)
            return "nothing answering on that port";
        if (e instanceof java.net.NoRouteToHostException)
            return "no route to the radio";
        return message == null ? "network error" : message;
    }
}
