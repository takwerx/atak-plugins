package com.atakmap.android.atmosphere.net;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import org.junit.Test;

import java.io.IOException;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;

import javax.net.ssl.SSLHandshakeException;

/**
 * With Air Temperature on and the connection to nomads.ncep.noaa.gov refused, the status
 * line read "Failed to connect to nomads.ncep.noaa.gov/[2600:..]:443" (ops #1384). A
 * silent server and a phone with no network already had words; a refused one did not.
 */
public class FailureTextTest {

    private static final String SERVER = "the NOAA weather server";
    private static final String HOST_ERROR =
            "Failed to connect to nomads.ncep.noaa.gov/[2600:1f18:1234::1]:443";

    @Test
    public void refusedConnectionNeverShowsTheHostError() {
        final String said = FailureText.describe(new ConnectException(HOST_ERROR));
        assertEquals("could not connect", said);
        assertFalse(said.contains("nomads"));
        assertFalse(said.contains("["));
    }

    @Test
    public void resetAndPlainIoErrorsWithTheSameMessageGetTheSameWords() {
        assertEquals("could not connect",
                FailureText.describe(new SocketException("Connection reset")));
        assertEquals("could not connect",
                FailureText.describe(new IOException(HOST_ERROR)));
    }

    @Test
    public void silentServerIsInWords() {
        assertEquals("no answer in time",
                FailureText.describe(new SocketTimeoutException("timed out")));
    }

    @Test
    public void noNetworkIsInWords() {
        assertEquals("no network connection",
                FailureText.describe(new UnknownHostException("nomads.ncep.noaa.gov")));
        assertEquals("no network connection",
                FailureText.describe(new NoRouteToHostException("No route to host")));
        assertEquals("no network connection", FailureText.describe(new ConnectException(
                "connect failed: ENETUNREACH (Network is unreachable)")));
    }

    @Test
    public void otherFailuresKeepTheirOwnWords() {
        assertEquals("TLS failed", FailureText.describe(new SSLHandshakeException("x")));
        assertEquals("network error", FailureText.describe(new IOException((String) null)));
        assertEquals("response larger than 8 MB",
                FailureText.describe(new IOException("response larger than 8 MB")));
    }

    @Test
    public void statusLineNamesTheServerAndKeepsThePictureWhenOneIsDrawn() {
        assertEquals("Can't reach the NOAA weather server. Showing the last picture.",
                FailureText.line("could not connect", SERVER, true));
        assertEquals("The NOAA weather server did not answer in time. Showing the last picture.",
                FailureText.line("no answer in time", SERVER, true));
        assertEquals("No network connection. Showing the last picture.",
                FailureText.line("no network connection", SERVER, true));
    }

    @Test
    public void statusLineDoesNotClaimAPictureWhenNothingIsDrawn() {
        assertEquals("Can't reach the NOAA weather server.",
                FailureText.line("could not connect", SERVER, false));
    }

    @Test
    public void statusLineHandlesAnEmptyError() {
        assertEquals("Request failed.", FailureText.line(null, SERVER, false));
        assertEquals("Request failed. Showing the last picture.",
                FailureText.line("", SERVER, true));
    }
}
