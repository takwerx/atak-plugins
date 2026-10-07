package com.atakmap.android.featurelayer;

import com.atakmap.coremap.log.Log;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Which fires have an InciWeb page, so a fire's details can open it (operator,
 * 2026-10-05: "for fed fires a link to inciweb").
 *
 * <p><b>From InciWeb's Google Earth feed.</b> IRWIN, where every fire record comes from,
 * carries no InciWeb address, and InciWeb has no API. It does publish a KML of its
 * incidents "via a Network Link feed that is automatically updated" (its Feeds page):
 * a placemark per incident with its name, point and page. About 100 KB and 159
 * incidents on 2026-10-05, read at most every 30 minutes. Its robots.txt disallows
 * everything, which is addressed to crawlers; this reads the one file InciWeb offers for
 * automatic reading, at a reader's pace, and never fetches its pages.
 *
 * <p><b>Matched by place and name.</b> A fire gets a page when the page's point is
 * within 15 km and the two names share a word, "fire", "complex" and the like aside:
 * InciWeb names an incident "WAOWF Three Queens" and IRWIN "THREE QUEENS", and the
 * points are the same IRWIN origin (0.0 km for most of the 57 fires matched that day,
 * 39 of them Forest Service). A program page ("San Juan National Forest Prescribed Fire
 * Program") shares no word with a burn's own name and is left alone.
 */
final class InciWeb {

    private static final String TAG = "FeatureLayer";
    static final String FEED = "https://inciweb.wildfire.gov/feeds/maps/placemarks.kml";
    /** Every page link is rebuilt on this prefix from a checked slug, never taken as written. */
    static final String PAGE_PREFIX = "https://inciweb.wildfire.gov/incident-information/";
    private static final Pattern PAGE = Pattern.compile(
            "^https://inciweb\\.wildfire\\.gov/incident-information/[a-z0-9-]{1,200}$");

    private static final long TTL_MS = 30 * 60_000L;
    private static final long RETRY_MS = 5 * 60_000L;
    private static final int MAX_BYTES = 2 * 1024 * 1024;
    private static final double NEAR_M = 15_000;

    private static final Pattern PLACEMARK = Pattern.compile("<Placemark>(.*?)</Placemark>", Pattern.DOTALL);
    private static final Pattern NAME = Pattern.compile("<name>(.*?)</name>", Pattern.DOTALL);
    private static final Pattern LINK = Pattern.compile(
            "https?://inciweb\\.wildfire\\.gov/incident-information/([a-z0-9-]{1,200})");
    private static final Pattern COORDS = Pattern.compile(
            "<coordinates>\\s*(-?[0-9]{1,3}(?:\\.[0-9]+)?)\\s*,\\s*(-?[0-9]{1,2}(?:\\.[0-9]+)?)");
    private static final Set<String> NOT_A_NAME = new HashSet<>(java.util.Arrays.asList(
            "fire", "fires", "complex", "prescribed", "burn", "burns", "the", "and", "national", "forest",
            "program", "operations", "district", "ranger", "fall", "spring"));

    /** One InciWeb incident: its page, its point and the words of its name. */
    static final class Page {
        final String url;
        final double lat, lon;
        final Set<String> words;

        Page(String url, double lat, double lon, Set<String> words) {
            this.url = url;
            this.lat = lat;
            this.lon = lon;
            this.words = words;
        }
    }

    private static List<Page> pages = Collections.emptyList();
    private static long fetchedAt, triedAt;

    private InciWeb() {
    }

    /**
     * Reads the feed when the last read is more than 30 minutes old, or five minutes
     * after a failure; the last good list stands meanwhile, and an unreachable InciWeb
     * costs a fire its button and nothing else. Worker thread only.
     */
    static synchronized void refresh() {
        final long now = System.currentTimeMillis();
        if (now - fetchedAt < TTL_MS || now - triedAt < RETRY_MS)
            return;
        triedAt = now;
        try {
            pages = parse(fetch());
            fetchedAt = now;
            Log.d(TAG, "InciWeb: " + pages.size() + " incident pages");
        } catch (Exception e) {
            Log.w(TAG, "InciWeb feed unreadable; keeping " + pages.size() + " pages", e);
        }
    }

    /** The InciWeb page for a fire of this name at this point, or null. */
    static String pageFor(String incidentName, double lat, double lon) {
        final Set<String> mine = words(incidentName);
        if (mine.isEmpty() || Double.isNaN(lat) || Double.isNaN(lon))
            return null;
        final List<Page> all;
        synchronized (InciWeb.class) {
            all = pages;
        }
        Page best = null;
        double bestM = NEAR_M;
        for (Page p : all) {
            final double m = distanceM(lat, lon, p.lat, p.lon);
            if (m > bestM)
                continue;
            for (String w : mine)
                if (p.words.contains(w)) {
                    best = p;
                    bestM = m;
                    break;
                }
        }
        return best == null ? null : best.url;
    }

    /** A page address that is safe to open: InciWeb's incident pages and nothing else. */
    static boolean isPage(String url) {
        return url != null && PAGE.matcher(url).matches();
    }

    private static String fetch() throws Exception {
        final HttpURLConnection c = (HttpURLConnection) new URL(FEED).openConnection();
        c.setConnectTimeout(20000);
        c.setReadTimeout(60000);
        c.setInstanceFollowRedirects(false);
        c.setRequestProperty("User-Agent", "FeatureLayer (ATAK plugin)");
        try {
            final int code = c.getResponseCode();
            if (code != 200)
                throw new IllegalStateException("HTTP " + code + " for the InciWeb feed");
            final java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            try (InputStream in = c.getInputStream()) {
                final byte[] buf = new byte[16384];
                int n;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                    if (out.size() > MAX_BYTES)
                        throw new IllegalStateException("InciWeb feed larger than " + MAX_BYTES + " bytes");
                }
            }
            return out.toString("UTF-8");
        } finally {
            c.disconnect();
        }
    }

    static List<Page> parse(String kml) {
        final List<Page> out = new ArrayList<>();
        final Matcher pm = PLACEMARK.matcher(kml);
        while (pm.find()) {
            final String p = pm.group(1);
            final Matcher n = NAME.matcher(p), l = LINK.matcher(p), c = COORDS.matcher(p);
            if (!n.find() || !l.find() || !c.find())
                continue;
            final String url = PAGE_PREFIX + l.group(1);
            final double lon = Double.parseDouble(c.group(1)), lat = Double.parseDouble(c.group(2));
            final Set<String> w = words(n.group(1));
            if (!w.isEmpty() && isPage(url) && Math.abs(lat) <= 90 && Math.abs(lon) <= 180)
                out.add(new Page(url, lat, lon, w));
        }
        return out;
    }

    /** The words of a name that identify it: lower case, three letters or more, the generic ones dropped. */
    static Set<String> words(String name) {
        final Set<String> out = new HashSet<>();
        if (name == null)
            return out;
        for (String w : name.toLowerCase(Locale.US).split("[^a-z0-9]+"))
            if (w.length() >= 3 && !NOT_A_NAME.contains(w))
                out.add(w);
        return out;
    }

    private static double distanceM(double lat1, double lon1, double lat2, double lon2) {
        final double r = 6371000d, dLat = Math.toRadians(lat2 - lat1), dLon = Math.toRadians(lon2 - lon1);
        final double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return 2 * r * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }
}
