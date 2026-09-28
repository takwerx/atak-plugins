package com.atakmap.android.signaldf.net;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;

import com.atakmap.coremap.log.Log;

/**
 * Holds a handle on the phone's WiFi network so the radio can be polled over it
 * while everything else keeps using mobile data.
 *
 * <p><b>The problem this solves is the whole vehicle deployment.</b> A phone has
 * one WiFi radio. On a truck the operator needs the KrakenSDR, which is on its
 * own access point with no internet, and at the same time needs the TAK server,
 * the map sources and the rest of ATAK, which need the internet. Joining the
 * radio's network normally costs the second thing: Android asks "Internet may
 * not be available", and answering "Stay connected" sets
 * {@code acceptUnvalidated} and makes that network the default route. Measured
 * on s10-dev-2, 2026-09-27: the default route became
 * {@code 0.0.0.0/0 -> 192.168.50.5}, which is a radio with no internet behind
 * it. Everything else on the phone then has nowhere to go.
 *
 * <p>The fix is not a setting the operator has to know about. Android lets an
 * app send a particular request down a particular network:
 * {@link ConnectivityManager#requestNetwork} for a WiFi transport, then
 * {@link Network#openConnection} for each call. So Signal DF's polling goes out
 * the WiFi explicitly, the phone's <em>default</em> network stays cellular, and
 * ATAK carries on as if nothing happened. The operator does not have to choose
 * between the radio and the team.
 *
 * <p>Deliberately does <b>not</b> call {@code bindProcessToNetwork}. That would
 * pin the whole process -- all of ATAK -- to the WiFi, which is the very failure
 * being avoided, and it would do it to somebody else's application.
 *
 * <p>Needs only {@code ACCESS_NETWORK_STATE}, which ATAK holds.
 * process a plugin runs in (verified granted on 5.8.0.3). If the request is
 * refused or no WiFi is available, {@link #network()} is null and callers fall
 * back to the default network, which is exactly today's behavior -- so this can
 * only ever help.
 */
public final class WifiPin {

    private static final String TAG = "SignalDF.WifiPin";

    private final ConnectivityManager cm;
    private ConnectivityManager.NetworkCallback callback;
    private volatile Network wifi;

    public WifiPin(Context context) {
        ConnectivityManager c = null;
        try {
            // NOT getApplicationContext(): on a plugin context that returns
            // null, and this blew up with an NPE the first time it ran on
            // hardware. The caller passes the host's context.
            c = (ConnectivityManager) context.getSystemService(
                    Context.CONNECTIVITY_SERVICE);
        } catch (RuntimeException e) {
            Log.w(TAG, "no connectivity service; using the default network", e);
        }
        this.cm = c;
    }

    /** Starts watching for a WiFi network. Safe to call more than once. */
    public void start() {
        if (cm == null || callback != null)
            return;
        try {
            // Transport only. Deliberately no NET_CAPABILITY_INTERNET: the
            // radio's access point has none, and requiring it would match
            // nothing in the one situation this exists for.
            NetworkRequest req = new NetworkRequest.Builder()
                    .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                    .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .build();
            callback = new ConnectivityManager.NetworkCallback() {
                @Override
                public void onAvailable(Network n) {
                    Log.d(TAG, "wifi available, pinning radio traffic to it");
                    wifi = n;
                }

                @Override
                public void onLost(Network n) {
                    if (n.equals(wifi)) {
                        Log.d(TAG, "wifi lost, falling back to the default network");
                        wifi = null;
                    }
                }
            };
            // registerNetworkCallback, not requestNetwork. We are not asking
            // Android to bring WiFi up -- the operator has already joined the
            // radio's network -- we only want a handle on it. Measured on
            // s10-dev-2: requestNetwork with no NET_CAPABILITY_INTERNET lands
            // as a LISTEN with activeRequest null anyway, so this is the same
            // thing asked for honestly, and it needs no CHANGE_NETWORK_STATE.
            cm.registerNetworkCallback(req, callback);
        } catch (SecurityException noPermission) {
            // ATAK holds CHANGE_NETWORK_STATE, but a future build might not.
            Log.w(TAG, "not allowed to request a network; using the default", noPermission);
            callback = null;
        } catch (RuntimeException e) {
            Log.w(TAG, "could not request a wifi network; using the default", e);
            callback = null;
        }
    }

    public void stop() {
        if (cm == null || callback == null)
            return;
        try {
            cm.unregisterNetworkCallback(callback);
        } catch (RuntimeException alreadyGone) {
            Log.d(TAG, "network callback was not registered");
        }
        callback = null;
        wifi = null;
    }

    /**
     * The WiFi network to send radio requests over, or null to use whatever the
     * phone's default is. Null is a normal answer, not an error.
     */
    public Network network() {
        return wifi;
    }

    /** For the pane: whether radio traffic is being kept off the default route. */
    public boolean isPinned() {
        return wifi != null;
    }
}
