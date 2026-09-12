package com.atakmap.android.featurelayer;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;

import com.atakmap.android.maps.MapView;
import com.atakmap.coremap.log.Log;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.security.MessageDigest;
import java.security.SecureRandom;

/**
 * ArcGIS user sign-in (OAuth 2.0 authorization code with PKCE) for one portal.
 *
 * <p>Esri issues tokens to accounts with multifactor authentication only through its
 * own sign-in page, so the page is shown as-is in a web view inside a drop-down pane
 * and the plugin never sees a password. The page redirects to {@code takwerx-mapdepot://oauth?code=...}, which is
 * intercepted here and exchanged for a 30-minute access token plus a refresh token that
 * lasts two weeks by default. The refresh token is what keeps the user signed in; it is
 * stored in a private preferences file of its own for this test (outside ATAK's
 * preference export) and belongs in AtakAuthenticationDatabase or the keystore for a
 * release.
 *
 * <p>Nothing here uses the client secret: PKCE replaces it, and a phone cannot keep a
 * secret anyway.
 */
public class ArcGisAuth {

    private static final String TAG = "FeatureLayer";
    private static final String REDIRECT = "takwerx-mapdepot://oauth";
    /** Prefix per portal, so NIFC's and NAPSG's tokens never collide. */
    private final String PREF;
    private static final long REFRESH_MARGIN_MS = 5 * 60 * 1000L;

    public interface Callback {
        void onSignedIn(String username);

        void onFailed(String reason);
    }

    private final MapView mapView;
    private final String portal;
    private final String clientId;
    private final SharedPreferences prefs;
    private final Handler main = new Handler(Looper.getMainLooper());
    private String verifier;
    private SignInDropDown dropDown;

    /** @param portal e.g. {@code https://nifc.maps.arcgis.com}, no trailing slash. */
    public ArcGisAuth(MapView mapView, String portal, String clientId) {
        this.mapView = mapView;
        this.portal = portal;
        this.clientId = clientId;
        this.PREF = "arcgis." + LayerManager.hostOf(portal) + ".";
        // A file of its own, never ATAK's default preferences: ATAK's Export Preferences
        // copies the default file verbatim to /sdcard/atak/config/prefs, and a refresh
        // token must not ride along in a .pref that gets handed around for staging.
        this.prefs = mapView.getContext().getSharedPreferences("featurelayer.arcgis", Context.MODE_PRIVATE);
    }

    public boolean isConfigured() {
        return clientId != null && !clientId.isEmpty();
    }

    public boolean isSignedIn() {
        return prefs.getString(PREF + "refresh", null) != null;
    }

    public String getUsername() {
        return prefs.getString(PREF + "username", null);
    }

    public void signOut() {
        prefs.edit().remove(PREF + "refresh").remove(PREF + "access")
                .remove(PREF + "expires").remove(PREF + "username").apply();
    }

    /**
     * A token good for at least five minutes, refreshing if needed. Blocking: call from a
     * worker thread. Returns null when not signed in or the refresh failed.
     */
    public String getValidToken() {
        final String access = prefs.getString(PREF + "access", null);
        final long expires = prefs.getLong(PREF + "expires", 0);
        if (access != null && System.currentTimeMillis() + REFRESH_MARGIN_MS < expires)
            return access;
        final String refresh = prefs.getString(PREF + "refresh", null);
        if (refresh == null)
            return null;
        try {
            final JSONObject tok = post(portal + "/sharing/rest/oauth2/token",
                    "grant_type=refresh_token&client_id=" + enc(clientId)
                            + "&refresh_token=" + enc(refresh));
            store(tok);
            return tok.getString("access_token");
        } catch (Exception e) {
            Log.w(TAG, "token refresh failed", e);
            return null;
        }
    }

    /** Opens Esri's sign-in page in a drop-down pane. Main thread. */
    public void signIn(final Callback cb) {
        if (!isConfigured()) {
            cb.onFailed("No ArcGIS client ID in this build");
            return;
        }
        verifier = randomVerifier();
        final String url;
        try {
            url = portal + "/sharing/rest/oauth2/authorize?client_id=" + enc(clientId)
                    + "&response_type=code&redirect_uri=" + enc(REDIRECT)
                    + "&code_challenge=" + enc(challenge(verifier))
                    + "&code_challenge_method=S256&expiration=20160&locale=en";
        } catch (Exception e) {
            cb.onFailed("could not build sign-in request: " + e);
            return;
        }
        if (dropDown == null)
            dropDown = new SignInDropDown(mapView);
        dropDown.show(url, REDIRECT, new SignInDropDown.Handler() {
            @Override
            public void onRedirect(String u) {
                final Uri uri = Uri.parse(u);
                final String code = uri.getQueryParameter("code");
                final String err = uri.getQueryParameter("error");
                if (code == null)
                    cb.onFailed(err != null ? err : "no code in redirect");
                else
                    exchange(code, cb);
            }

            @Override
            public void onCancelled() {
                cb.onFailed("cancelled");
            }
        });
    }

    public void dispose() {
        if (dropDown != null) {
            dropDown.dispose();
            dropDown = null;
        }
    }

    private void exchange(final String code, final Callback cb) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    final JSONObject tok = post(portal + "/sharing/rest/oauth2/token",
                            "grant_type=authorization_code&client_id=" + enc(clientId)
                                    + "&code=" + enc(code) + "&redirect_uri=" + enc(REDIRECT)
                                    + "&code_verifier=" + enc(verifier));
                    store(tok);
                    final String user = tok.optString("username", "");
                    main.post(new Runnable() {
                        @Override
                        public void run() {
                            cb.onSignedIn(user);
                        }
                    });
                } catch (final Exception e) {
                    Log.w(TAG, "token exchange failed", e);
                    main.post(new Runnable() {
                        @Override
                        public void run() {
                            cb.onFailed("token exchange failed: " + e.getMessage());
                        }
                    });
                }
            }
        }, "nifs-oauth").start();
    }

    private void store(JSONObject tok) throws Exception {
        if (tok.has("error"))
            throw new IllegalStateException(tok.getJSONObject("error").optString("message", "error"));
        final SharedPreferences.Editor e = prefs.edit();
        e.putString(PREF + "access", tok.getString("access_token"));
        e.putLong(PREF + "expires", System.currentTimeMillis() + tok.optLong("expires_in", 1800) * 1000L);
        if (tok.has("refresh_token"))
            e.putString(PREF + "refresh", tok.getString("refresh_token"));
        if (tok.has("username"))
            e.putString(PREF + "username", tok.getString("username"));
        e.apply();
    }

    // ---- plumbing -----------------------------------------------------------------

    private static JSONObject post(String url, String form) throws Exception {
        final HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(20000);
        c.setReadTimeout(30000);
        c.setDoOutput(true);
        c.setRequestMethod("POST");
        c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
        c.setRequestProperty("Accept", "application/json");
        try {
            final byte[] body = (form + "&f=json").getBytes("UTF-8");
            try (OutputStream out = c.getOutputStream()) {
                out.write(body);
            }
            final int code = c.getResponseCode();
            final StringBuilder sb = new StringBuilder();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(
                    code >= 400 ? c.getErrorStream() : c.getInputStream(), "UTF-8"))) {
                final char[] buf = new char[4096];
                int n;
                while ((n = r.read(buf)) > 0)
                    sb.append(buf, 0, n);
            }
            return new JSONObject(sb.toString());
        } finally {
            c.disconnect();
        }
    }

    private static String randomVerifier() {
        final byte[] b = new byte[32];
        new SecureRandom().nextBytes(b);
        return Base64.encodeToString(b, Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);
    }

    private static String challenge(String verifier) throws Exception {
        final byte[] d = MessageDigest.getInstance("SHA-256").digest(verifier.getBytes("US-ASCII"));
        return Base64.encodeToString(d, Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);
    }

    private static String enc(String s) throws Exception {
        return URLEncoder.encode(s, "UTF-8");
    }
}
