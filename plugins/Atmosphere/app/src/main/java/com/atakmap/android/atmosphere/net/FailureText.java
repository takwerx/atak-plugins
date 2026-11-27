package com.atakmap.android.atmosphere.net;

import java.io.IOException;

/**
 * A failed request in words an operator can read in the field, never the host error.
 *
 * <p>Kept apart from {@link Http}: that class builds an Android Handler when it loads,
 * so a plain unit test cannot reach it, and this wording is the part that needs a test.
 * "Failed to connect to nomads.ncep.noaa.gov/[2001:..]:443" reached the Air Temperature
 * status line on 2026-11-27 (ops #1384) because a refused connection was the one case
 * {@code describe} had no words for.
 */
final class FailureText {

    static final String NO_ANSWER = "no answer in time";
    static final String NO_NETWORK = "no network connection";
    static final String NO_CONNECT = "could not connect";

    private FailureText() {
    }

    /**
     * Three cases a crew meets: no answer in time, no network at all, and a server that
     * refused or dropped the connection. Callers embed the result in their own sentence
     * ("Could not find the office: ..."), so each is a lower-case fragment that names no
     * server and reads the same in all of them.
     */
    static String describe(IOException e) {
        if (e instanceof java.net.SocketTimeoutException)
            return NO_ANSWER;
        final String message = e.getMessage();
        if (e instanceof java.net.UnknownHostException
                || e instanceof java.net.NoRouteToHostException
                || (message != null && (message.contains("ENETUNREACH")
                        || message.contains("Network is unreachable"))))
            return NO_NETWORK;
        if (e instanceof javax.net.ssl.SSLException)
            return "TLS failed";
        if (e instanceof java.net.SocketException
                || (message != null && message.startsWith("Failed to connect")))
            return NO_CONNECT;
        return message == null ? "network error" : message;
    }

    /**
     * A status line for a layer that keeps its last picture: the failure as a sentence
     * naming the server, and when a picture is still drawn, that it is. A layer that has
     * drawn nothing yet must not claim one.
     */
    static String line(String error, String server, boolean pictureDrawn) {
        final String s;
        if (NO_CONNECT.equals(error))
            s = "Can't reach " + server + ".";
        else if (NO_ANSWER.equals(error))
            s = Character.toUpperCase(server.charAt(0)) + server.substring(1)
                    + " did not answer in time.";
        else if (error == null || error.isEmpty())
            s = "Request failed.";
        else
            s = Character.toUpperCase(error.charAt(0)) + error.substring(1) + ".";
        return pictureDrawn ? s + " Showing the last picture." : s;
    }
}
