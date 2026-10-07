package com.atakmap.android.featurelayer;

import com.atakmap.coremap.log.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * CAL FIRE's own record of a fire, for the fires it runs or posts: its incident page,
 * and its acres, containment, location and update time, which are often fresher than
 * the IRWIN record (Fork, 2026-10-06: 57 ac and 25% at CAL FIRE, 46 ac in IRWIN).
 *
 * <p><b>CAL FIRE's developer data.</b> fire.ca.gov/incidents offers "Data access for
 * software developers": a JSON list, the same as GeoJSON, and a CSV, with {@code year}
 * and {@code inactive} parameters. The active list is a few kilobytes (10 incidents on
 * 2026-10-06) and is read every 30 minutes; the whole season (530 incidents, 410 KB) is
 * read once a day, so a fire IRWIN still lists that CAL FIRE has closed says so.
 * Resource counts (engines, dozers, crews) are on the incident page, not in the list,
 * which is why the details carry the page.
 *
 * <p><b>Matched by place and name,</b> as InciWeb is: CAL FIRE's point within 10 km of
 * the IRWIN point and a shared word of the name. 7 of the 10 active incidents matched
 * that day, all within 1.5 km.
 */
final class CalFire {

    private static final String TAG = "FeatureLayer";
    private static final String BASE = "https://incidents.fire.ca.gov/umbraco/api/IncidentApi/List";
    /** Every page link is rebuilt on this prefix from checked parts, never taken as written. */
    private static final String PAGE_PREFIX = "https://www.fire.ca.gov/incidents/";
    private static final Pattern PAGE = Pattern.compile(
            "^https://www\\.fire\\.ca\\.gov/incidents/(\\d{4})/(\\d{1,2})/(\\d{1,2})/([a-z0-9-]{1,120})/?$");

    private static final long ACTIVE_TTL_MS = 30 * 60_000L;
    private static final long SEASON_TTL_MS = 24 * 3600_000L;
    private static final long RETRY_MS = 5 * 60_000L;
    private static final int MAX_BYTES = 4 * 1024 * 1024;
    private static final double NEAR_M = 10_000;

    /** One CAL FIRE incident, the parts the details show. */
    static final class Incident {
        final String name, url, county, location, updated, extinguished;
        final double lat, lon, acres, percent;
        final boolean active;
        final Set<String> words;

        Incident(String name, String url, String county, String location, String updated, String extinguished,
                double lat, double lon, double acres, double percent, boolean active) {
            this.name = name;
            this.url = url;
            this.county = county;
            this.location = location;
            this.updated = updated;
            this.extinguished = extinguished;
            this.lat = lat;
            this.lon = lon;
            this.acres = acres;
            this.percent = percent;
            this.active = active;
            this.words = InciWeb.words(name);
        }
    }

    private static List<Incident> active = Collections.emptyList(), season = Collections.emptyList();
    private static long activeAt, activeTried, seasonAt, seasonTried;

    private CalFire() {
    }

    /**
     * The active list every 30 minutes, the season once a day, five minutes apart after
     * a failure; the last good lists stand meanwhile. Worker thread only.
     */
    static synchronized void refresh() {
        final long now = System.currentTimeMillis();
        if (now - activeAt >= ACTIVE_TTL_MS && now - activeTried >= RETRY_MS) {
            activeTried = now;
            try {
                active = parse(fetch(BASE + "?inactive=false"));
                activeAt = now;
                Log.d(TAG, "CAL FIRE: " + active.size() + " active incidents");
            } catch (Exception e) {
                Log.w(TAG, "CAL FIRE active list unreadable; keeping " + active.size(), e);
            }
        }
        if (now - seasonAt >= SEASON_TTL_MS && now - seasonTried >= RETRY_MS) {
            seasonTried = now;
            try {
                final int year = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("America/Los_Angeles"))
                        .get(java.util.Calendar.YEAR);
                season = parse(fetch(BASE + "?inactive=true&year=" + year));
                seasonAt = now;
                Log.d(TAG, "CAL FIRE: " + season.size() + " incidents this season");
            } catch (Exception e) {
                Log.w(TAG, "CAL FIRE season list unreadable; keeping " + season.size(), e);
            }
        }
    }

    /** CAL FIRE's record of a fire of this name at this point: an active one first, else this season's; or null. */
    static Incident find(String incidentName, double lat, double lon) {
        final Set<String> mine = InciWeb.words(incidentName);
        if (mine.isEmpty() || Double.isNaN(lat) || Double.isNaN(lon))
            return null;
        final List<Incident> a, s;
        synchronized (CalFire.class) {
            a = active;
            s = season;
        }
        final Incident hit = nearest(a, mine, lat, lon);
        return hit != null ? hit : nearest(s, mine, lat, lon);
    }

    private static Incident nearest(List<Incident> all, Set<String> mine, double lat, double lon) {
        Incident best = null;
        double bestM = NEAR_M;
        for (Incident i : all) {
            final double m = distanceM(lat, lon, i.lat, i.lon);
            if (m > bestM)
                continue;
            for (String w : mine)
                if (i.words.contains(w)) {
                    best = i;
                    bestM = m;
                    break;
                }
        }
        return best;
    }

    /** A page address that is safe to open: CAL FIRE's incident pages and nothing else. */
    static boolean isPage(String url) {
        return url != null && PAGE.matcher(url).matches();
    }

    /** The incident page rebuilt from its checked parts, or null when the link is not one. */
    private static String page(String raw) {
        if (raw == null)
            return null;
        final Matcher m = PAGE.matcher(raw.trim());
        if (!m.matches())
            return null;
        return PAGE_PREFIX + m.group(1) + "/" + Integer.parseInt(m.group(2)) + "/" + Integer.parseInt(m.group(3))
                + "/" + m.group(4) + "/";
    }

    /**
     * Sectigo Public Server Authentication Root R46, DER in base64. CAL FIRE's server
     * moved to a certificate that chains to it (Sectigo OV R36, issued 2026-09-14, live
     * on incidents.fire.ca.gov by the evening of 2026-10-06), and Android before 14 does
     * not carry this root: on dev 1 (Android 12) and the S22+ (13) every CAL FIRE fetch
     * failed with "Trust anchor for certification path not found", so the fires lost
     * their CAL FIRE acres, containment and link. Android 14 ships the same certificate
     * (/apex/com.android.conscrypt/cacerts/e071171e.0); SHA-256
     * 7B:B6:47:A6:2A:EE:AC:88:BF:25:7A:A5:22:D0:1F:FE:A3:95:E0:AB:45:C7:3F:93:F6:56:54:EC:38:F2:5A:06,
     * checked against it. Valid to 2046.
     */
    private static final String SECTIGO_R46 =
            "MIIFijCCA3KgAwIBAgIQdY39i658BwD6qSWn4cetFDANBgkqhkiG9w0BAQwFADBfMQswCQYDVQQG"
            + "EwJHQjEYMBYGA1UEChMPU2VjdGlnbyBMaW1pdGVkMTYwNAYDVQQDEy1TZWN0aWdvIFB1YmxpYyBT"
            + "ZXJ2ZXIgQXV0aGVudGljYXRpb24gUm9vdCBSNDYwHhcNMjEwMzIyMDAwMDAwWhcNNDYwMzIxMjM1"
            + "OTU5WjBfMQswCQYDVQQGEwJHQjEYMBYGA1UEChMPU2VjdGlnbyBMaW1pdGVkMTYwNAYDVQQDEy1T"
            + "ZWN0aWdvIFB1YmxpYyBTZXJ2ZXIgQXV0aGVudGljYXRpb24gUm9vdCBSNDYwggIiMA0GCSqGSIb3"
            + "DQEBAQUAA4ICDwAwggIKAoICAQCTvtU2UnXYASOgHEdCSe5jtrch/cSV1UgrJnwUUxDaef0rty2k"
            + "1Cz66jLdScK5vQ9IPXtamFSvnl0xdE8H/FAh3aTPaE8bEmNtJZlMKpnzSDBh+oF8HqcIStw+Kxwf"
            + "GExxqjWMrfhu6DtK2eWUAtaJhBOqbchPM8xQljeSM9xfiOefVNlI8JhD1mb9nxc4Q8UBUQvX4yMP"
            + "FF1bFOdLvt30yNoDN9HWOaEhUTCDsG3XME6WW5HwcCSrv0WBZEMNvSE6Lzzpng3LILVCJ8zab5vu"
            + "ZDCQOc2TZYEhMbUjUDM3IuM47fgxMMxF/mL50V0yeUKH32rMVhlATc6qu/m1dkmU8Sf4kaWD5Qaz"
            + "Yw6A3OASVYCmO2a0OYctyPDQ0RTp5A1NDvZdV3LFOxxHVp3i1fuBYYzMTYCQNFu31xR13NgESJ/A"
            + "wSiItOkcyqex8Va3e0lMWeUgFaiEAin6OJRpmkkGj80feRQXEgyDet4fsZfu+Zd4KKTIRJLpfSYF"
            + "plhym3kT2BFfrsU4YjRosoYwjviQYZ4ybPUHNs2iTG7sijbt8uaZFURww3y8nDnAtOFr94MlI1fZ"
            + "EoDlSfB1D++N6xybVCi0ITz8fAr/73trdf+LHaAZBav6+CuBQug4urv7qv094PPK306Xlynt8xhW"
            + "6aWWrL3DkJiy4Pmi1KZHQ3xtzwIDAQABo0IwQDAdBgNVHQ4EFgQUVnNYZJX5khqwEioEYnmhQBWI"
            + "IUkwDgYDVR0PAQH/BAQDAgGGMA8GA1UdEwEB/wQFMAMBAf8wDQYJKoZIhvcNAQEMBQADggIBAC9c"
            + "mTz8Bl6MlC5w6tIyMY208FHVvArzZJ8HXtXBc2hkeqK5Duj5XYUtqDdFqij0lgVQYKlJfp/imTYp"
            + "E0RHap1VIDzYm/EDMrraQKFz6oOht0SmDpkBm+S8f74TlH7Kph52gDY9hAaLMyZlbcp+nv4fjFg4"
            + "exqDsQ+8FxG75gbMY/qB8oFM2gsQa6H61SilzwZAFv97fRheORKkU55+MkIQpiGRqRxOF3yEvJ+M"
            + "0ejf5lG5Nkc/kLnHvALcWxxPDkjBJYOcCj+esQMzEhonrPcibCTRAUH4WAP+JWgiH5paPHxsnnVI"
            + "84HxZmduTILA7rpXDhjvLpr3Etiga+kFpaHpaPi8TD8SHkXoUsCjvxInebnMMTzD9joiFgOgyY9m"
            + "pFuiTdaBJQbpdqQACj7LzTWb4OE4y2BThihCQRxEV+ioratF4yUQvNs+ZUH7G6aXD+u5dHn5Hrwd"
            + "Vw1Hr8Mvn4dGp+smWg9WY7ViYG4A++MnESLn/pmPNPW56MORcr3Ywx65LvKRRFHQV80MNNVIIb/b"
            + "E/FmJUNS0nAiNs2fxBx1IK1jcmMGDw4nztJqDby1ORrp0XZ60Vzk50lJLVU3aPAaOpg+VBeHVOmm"
            + "J1CJeyAvP/+/oYtKR5j/K3tJPsMpRmAYQqszKbrAKbkTidOIijlBO8n9pu0f9GBj39ItVQGL";

    private static volatile javax.net.ssl.SSLSocketFactory tls;

    /**
     * The phone's own roots plus Sectigo R46, for CAL FIRE's connection only; every other
     * connection the plugin makes keeps the phone's trust as it is. Hostname checking is
     * HttpsURLConnection's own, untouched.
     */
    private static javax.net.ssl.SSLSocketFactory tls() throws Exception {
        javax.net.ssl.SSLSocketFactory f = tls;
        if (f != null)
            return f;
        final java.security.KeyStore ks = java.security.KeyStore.getInstance(java.security.KeyStore.getDefaultType());
        // An empty in-memory store, opened with the one-argument load: tak.gov's Fortify
        // reported the two-argument load(null, null) on 0.13.
        ks.load((java.security.KeyStore.LoadStoreParameter) null);
        final javax.net.ssl.TrustManagerFactory system = javax.net.ssl.TrustManagerFactory
                .getInstance(javax.net.ssl.TrustManagerFactory.getDefaultAlgorithm());
        system.init((java.security.KeyStore) null);
        int n = 0;
        for (javax.net.ssl.TrustManager tm : system.getTrustManagers())
            if (tm instanceof javax.net.ssl.X509TrustManager)
                for (java.security.cert.X509Certificate ca : ((javax.net.ssl.X509TrustManager) tm).getAcceptedIssuers())
                    ks.setCertificateEntry("system-" + (n++), ca);
        ks.setCertificateEntry("sectigo-r46", java.security.cert.CertificateFactory.getInstance("X.509")
                .generateCertificate(new java.io.ByteArrayInputStream(
                        android.util.Base64.decode(SECTIGO_R46, android.util.Base64.DEFAULT))));
        final javax.net.ssl.TrustManagerFactory tmf = javax.net.ssl.TrustManagerFactory
                .getInstance(javax.net.ssl.TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(ks);
        // A named version, never the generic "TLS" (Fortify, "Weak SSL Protocol", 0.13):
        // 1.3 where Android has it (10 and later), else 1.2. CAL FIRE speaks both.
        javax.net.ssl.SSLContext ctx;
        try {
            ctx = javax.net.ssl.SSLContext.getInstance("TLSv1.3");
        } catch (java.security.NoSuchAlgorithmException e) {
            ctx = javax.net.ssl.SSLContext.getInstance("TLSv1.2");
        }
        ctx.init(null, tmf.getTrustManagers(), null);
        f = ctx.getSocketFactory();
        tls = f;
        return f;
    }

    private static String fetch(String url) throws Exception {
        final HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        if (c instanceof javax.net.ssl.HttpsURLConnection)
            ((javax.net.ssl.HttpsURLConnection) c).setSSLSocketFactory(tls());
        c.setConnectTimeout(20000);
        c.setReadTimeout(60000);
        c.setInstanceFollowRedirects(false);
        c.setRequestProperty("User-Agent", "FeatureLayer (ATAK plugin)");
        c.setRequestProperty("Accept", "application/json");
        try {
            final int code = c.getResponseCode();
            if (code != 200)
                throw new IllegalStateException("HTTP " + code + " for the CAL FIRE incident list");
            final java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            try (InputStream in = c.getInputStream()) {
                final byte[] buf = new byte[16384];
                int n;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                    if (out.size() > MAX_BYTES)
                        throw new IllegalStateException("CAL FIRE list larger than " + MAX_BYTES + " bytes");
                }
            }
            return out.toString("UTF-8");
        } finally {
            c.disconnect();
        }
    }

    static List<Incident> parse(String json) throws Exception {
        final JSONArray arr = new JSONArray(json);
        final List<Incident> out = new ArrayList<>(arr.length());
        for (int i = 0; i < arr.length(); i++) {
            final JSONObject o = arr.optJSONObject(i);
            if (o == null)
                continue;
            final String name = text(o, "Name");
            final String url = page(text(o, "Url"));
            final double lat = o.optDouble("Latitude", Double.NaN), lon = o.optDouble("Longitude", Double.NaN);
            if (name.isEmpty() || url == null || Double.isNaN(lat) || Double.isNaN(lon)
                    || Math.abs(lat) > 90 || Math.abs(lon) > 180)
                continue;
            out.add(new Incident(name, url, text(o, "County"), text(o, "Location"), text(o, "Updated"),
                    text(o, "ExtinguishedDate"), lat, lon, o.optDouble("AcresBurned", -1),
                    o.optDouble("PercentContained", -1), o.optBoolean("IsActive", false)));
        }
        return out;
    }

    /** A field as plain text, trimmed and capped; empty when absent or null. */
    private static String text(JSONObject o, String key) {
        if (o.isNull(key))
            return "";
        final String s = o.optString(key, "").replaceAll("[\\p{Cntrl}]", " ").trim();
        return s.length() > 200 ? s.substring(0, 200) : s;
    }

    /** "2026-10-06T17:56:50Z" as "Oct 6 10:56" on the phone's clock; the text as is when it will not parse. */
    static String when(String iso) {
        if (iso == null || iso.isEmpty())
            return "";
        try {
            final java.text.SimpleDateFormat in = new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US);
            in.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
            return new java.text.SimpleDateFormat("MMM d HH:mm", Locale.US).format(in.parse(iso));
        } catch (Exception e) {
            return iso;
        }
    }

    private static double distanceM(double lat1, double lon1, double lat2, double lon2) {
        final double r = 6371000d, dLat = Math.toRadians(lat2 - lat1), dLon = Math.toRadians(lon2 - lon1);
        final double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return 2 * r * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }
}
