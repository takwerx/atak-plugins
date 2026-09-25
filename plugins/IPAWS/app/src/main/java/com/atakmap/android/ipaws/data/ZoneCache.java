package com.atakmap.android.ipaws.data;

import com.atakmap.android.ipaws.net.Http;
import com.atakmap.coremap.filesystem.FileSystemUtils;
import com.atakmap.coremap.log.Log;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Zone polygons, fetched one at a time and kept on disk.
 *
 * <p>This is the expensive half of the plugin, and it is not optional: measured on
 * 2026-09-16, <b>312 of 337 active alerts nationally carried no geometry of their
 * own</b>, and between them referenced <b>997 distinct zones</b>. Per zone the polygon
 * JSON ran a median of 3.7 KB and a maximum of 168 KB.
 *
 * <p>One at a time, because the bulk form does not work and fails silently:
 * {@code /zones?id=A,B,C} answers <b>200 with features and null geometry</b> for
 * {@code include_geometry=true}, {@code =1}, and omitted alike, and
 * {@code include_geometry=TRUE} answers 200 with zero features. The verifier keeps a
 * regression guard on that, so if NWS ever starts serving bulk geometry we find out
 * and N requests become one.
 *
 * <p>The URL is used <b>verbatim</b> as the alert gave it, never rebuilt, which is
 * what keeps the zone type (forecast / county / fire) from ever being guessed. Note
 * that {@code include_geometry=true} on the single-zone endpoint is a 400; the single
 * form returns geometry by default.
 *
 * <h3>Negative caching</h3>
 * A 404 is remembered, and nothing else is. api.weather.gov answers an unknown zone
 * with a clean {@code 404} and {@code "type": ".../InvalidZone"} -- durable absence,
 * not a failure -- so remembering it stops a dead zone being re-requested on every
 * poll forever. A timeout, a 5xx or a TLS failure is a working zone we could not
 * reach today and is never written down: caching that is how a zone that comes back
 * tomorrow stays invisible. The miss is also given a much shorter life than a hit,
 * so even a wrong one heals ({@code negative-cache-must-not-outlive-positive}).
 */
public class ZoneCache {

    private static final String TAG = "IPAWS";

    /** NWS redraws zones a few times a year, so a hit is good for a long time. */
    private static final long HIT_TTL_MS = 30L * 24 * 60 * 60 * 1000;
    /** A miss is good for far less than a hit, on purpose. */
    private static final long MISS_TTL_MS = 3L * 24 * 60 * 60 * 1000;

    /**
     * Zones fetched per poll. A user who selects many states can reference hundreds,
     * and a phone is not going to open hundreds of requests at once; the rest arrive
     * on later polls and the map fills in.
     */
    public static final int MAX_FETCH_PER_POLL = 40;

    private final File dir;
    private final AlertSource source;
    private final Set<String> inFlight = new LinkedHashSet<>();
    /**
     * Each zone's extent, {@code {south, west, north, east}}, kept in memory once the
     * zone has been read. The distance scope rejects far-away alerts on this alone, so
     * a scoped rebuild never opens their files: reading and parsing every cached zone
     * -- some of them 1.7 MB -- is what makes a national rebuild take eleven seconds.
     */
    private final java.util.Map<String, double[]> envelopes =
            new java.util.concurrent.ConcurrentHashMap<>();
    private final ExecutorService disk = Executors.newSingleThreadExecutor(
            new ThreadFactory() {
                @Override
                public Thread newThread(Runnable r) {
                    final Thread t = new Thread(r, "ipaws-zones");
                    t.setDaemon(true);
                    return t;
                }
            });

    public interface Settled {
        void onSettled();
    }

    public ZoneCache(File root, AlertSource source) {
        this.source = source;
        dir = new File(root, "zones");
        if (!dir.isDirectory() && !dir.mkdirs())
            Log.w(TAG, "could not create " + dir);
    }

    /**
     * The cached geometry for a zone URL, or null when it is not held, is stale, or is
     * a remembered miss. Reads from disk, so a worker thread only.
     */
    public JSONObject geometry(String url) {
        final File f = hitFile(url);
        if (f == null || !f.isFile())
            return null;
        if (System.currentTimeMillis() - f.lastModified() > HIT_TTL_MS)
            return null;
        try {
            final String s;
            try (InputStream in = new FileInputStream(f)) {
                s = new String(FileSystemUtils.read(in), FileSystemUtils.UTF8_CHARSET);
            }
            final JSONObject o = new JSONObject(s);
            // A file that parsed but holds nothing drawable is corruption, not a zone.
            if (o.optJSONArray("coordinates") == null && o.optJSONArray("geometries") == null)
                return null;
            final double[] env = Geo.envelopeOf(o);
            if (env != null)
                envelopes.put(url, env);
            return o;
        } catch (Exception e) {
            Log.w(TAG, "unreadable cached zone " + f.getName() + ", dropping", e);
            if (!f.delete())
                Log.w(TAG, "could not delete " + f);
            return null;
        }
    }

    /**
     * Whether a zone's geometry is held, without reading it: from memory when it has
     * been read this session, else whether a fresh cache file exists. Worker thread.
     */
    public boolean isHeld(String url) {
        if (envelopes.containsKey(url))
            return true;
        final File f = hitFile(url);
        return f != null && f.isFile() && System.currentTimeMillis() - f.lastModified() <= HIT_TTL_MS;
    }

    /**
     * A zone's extent, {@code {south, west, north, east}}, from memory when it has been
     * read this session, else read once. Null when the zone is not held. Worker thread.
     */
    public double[] envelope(String url) {
        final double[] hit = envelopes.get(url);
        if (hit != null)
            return hit;
        return geometry(url) == null ? null : envelopes.get(url);
    }

    /**
     * True when the server has told us it does not hold this zone and that answer has
     * not expired yet. Such a zone is not "missing" -- it is absent, and a caller
     * should stop counting it as something still on its way.
     */
    public boolean isKnownAbsent(String url) {
        final File f = missFile(url);
        return f != null && f.isFile()
                && System.currentTimeMillis() - f.lastModified() <= MISS_TTL_MS;
    }

    /**
     * Fetches whichever of these zones is not already held, capped, and calls back
     * once when that batch has settled -- succeeded, 404'd or failed, it does not
     * matter which, because the caller redraws from the cache either way.
     *
     * <p>Returns the number actually requested, so the caller can say how much of the
     * picture is still filling in. Safe to call from any thread; {@code onSettled}
     * lands on the main thread.
     */
    public int fetchMissing(Collection<String> urls, int max, final Settled onSettled) {
        final List<String> want = new ArrayList<>();
        synchronized (inFlight) {
            for (String url : urls) {
                if (want.size() >= max)
                    break;
                if (url == null || inFlight.contains(url))
                    continue;
                if (isKnownAbsent(url))
                    continue;
                if (hitFile(url) == null)
                    continue; // not an origin we follow, or not a URL we can key
                want.add(url);
            }
            inFlight.addAll(want);
        }
        if (want.isEmpty()) {
            if (onSettled != null)
                onSettled.onSettled();
            return 0;
        }
        final AtomicInteger outstanding = new AtomicInteger(want.size());
        for (final String url : want)
            fetchOne(url, outstanding, onSettled);
        return want.size();
    }

    private void fetchOne(final String url, final AtomicInteger outstanding,
            final Settled onSettled) {
        Http.get(url, new Http.Callback() {
            @Override
            public void onSuccess(final byte[] body) {
                disk.execute(new Runnable() {
                    @Override
                    public void run() {
                        store(url, body);
                        done();
                    }
                });
            }

            @Override
            public void onFailure(final int status, String error) {
                if (status == 404) {
                    // Durable absence: the server says it does not hold this zone.
                    // Only ever reached for an allowlisted origin, because an
                    // untrusted URL never got a key and so was never requested.
                    disk.execute(new Runnable() {
                        @Override
                        public void run() {
                            rememberMiss(url);
                            done();
                        }
                    });
                    return;
                }
                // Everything else is a zone we could not reach today. Write nothing
                // down -- it is tried again on the next poll.
                Log.d(TAG, "zone " + zoneKey(url) + " unavailable (" + error + ")");
                done();
            }

            private void done() {
                synchronized (inFlight) {
                    inFlight.remove(url);
                }
                if (outstanding.decrementAndGet() == 0 && onSettled != null)
                    com.atakmap.android.ipaws.data.MainThread.post(new Runnable() {
                        @Override
                        public void run() {
                            onSettled.onSettled();
                        }
                    });
            }
        });
    }

    /** Keeps only the zone's geometry: the rest of the response is 25 KB of metadata. */
    private void store(String url, byte[] body) {
        final File f = hitFile(url);
        if (f == null || body == null)
            return;
        try {
            final JSONObject doc = new JSONObject(
                    new String(body, FileSystemUtils.UTF8_CHARSET));
            final JSONObject geom = doc.optJSONObject("geometry");
            if (geom == null) {
                // 200 with null geometry. That is what the bulk endpoint does, and if
                // the single one ever starts, the map would silently lose zones.
                Log.w(TAG, "zone " + zoneKey(url) + ": 200 with no geometry");
                return;
            }
            writeAtomic(f, geom.toString().getBytes(FileSystemUtils.UTF8_CHARSET));
            final File miss = missFile(url);
            if (miss != null && miss.isFile() && !miss.delete())
                Log.w(TAG, "could not clear miss for " + zoneKey(url));
        } catch (Exception e) {
            Log.w(TAG, "could not store zone " + zoneKey(url), e);
        }
    }

    private void rememberMiss(String url) {
        final File f = missFile(url);
        if (f == null)
            return;
        try {
            writeAtomic(f, new byte[0]);
        } catch (Exception e) {
            Log.w(TAG, "could not remember miss for " + zoneKey(url), e);
        }
    }

    /**
     * {@code https://api.weather.gov/zones/forecast/CAZ300} to
     * {@code api_weather_gov_forecast_CAZ300}, or null for a URL this plugin will not
     * follow. This is the single gate: everything that fetches or reads a zone goes
     * through a key, so a URL with no key is a URL that cannot be requested and
     * cannot name a file.
     *
     * <p>The origin is part of the key. It is not what stops a hostile URL -- the
     * allowlist above does that -- but it means two origins can never share a cache
     * file, which is the property that has to hold if a second source is ever added.
     *
     * <p>The type is kept because a county and a forecast zone can share a number.
     * Every segment is checked against a strict character set and refused rather than
     * sanitized: a zone id is server-supplied, and a name built from it must never be
     * able to climb out of the cache directory.
     */
    String zoneKey(String url) {
        if (url == null || !source.trustsZoneUrl(url))
            return null;
        final String host;
        final String path;
        try {
            final java.net.URL u = new java.net.URL(url);
            // Lowercased: a host differing only in case is the same origin, and
            // two spellings of it must not become two cache files.
            host = u.getHost().toLowerCase(java.util.Locale.US).replace('.', '_');
            path = u.getPath();
        } catch (java.net.MalformedURLException e) {
            return null;
        }
        final String[] parts = path.split("/");
        if (parts.length < 2)
            return null;
        final String id = parts[parts.length - 1];
        final String type = parts[parts.length - 2];
        if (!isSafe(host) || !isSafe(type) || !isSafe(id))
            return null;
        return host + "_" + type + "_" + id;
    }

    /**
     * Letters, digits and underscore only, and short. No dot, no slash, no percent,
     * and nothing is decoded after this runs, so no segment can become a path.
     */
    private static boolean isSafe(String s) {
        if (s == null || s.isEmpty() || s.length() > 64)
            return false;
        for (int i = 0; i < s.length(); i++) {
            final char c = s.charAt(i);
            final boolean ok = (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z')
                    || (c >= '0' && c <= '9') || c == '_';
            if (!ok)
                return false;
        }
        return true;
    }

    private File hitFile(String url) {
        final String key = zoneKey(url);
        return key == null ? null : new File(dir, key + ".json");
    }

    private File missFile(String url) {
        final String key = zoneKey(url);
        return key == null ? null : new File(dir, key + ".miss");
    }

    /**
     * Writes through a temporary file and renames.
     *
     * <p>A zone file is read back and parsed as JSON, and a plain write interrupted by
     * ATAK being killed leaves a truncated one that parses as garbage or, worse,
     * parses as a valid but incomplete polygon. The rename is the only step a reader
     * can observe.
     */
    private static void writeAtomic(File f, byte[] bytes) throws java.io.IOException {
        final File tmp = new File(f.getParentFile(), f.getName() + ".tmp");
        try (OutputStream out = new FileOutputStream(tmp)) {
            out.write(bytes);
        }
        if (!tmp.renameTo(f)) {
            if (!tmp.delete())
                Log.w(TAG, "could not clean up " + tmp);
            throw new java.io.IOException("could not replace " + f.getName());
        }
    }

    /** Drops cache entries past their life. Worker thread only. */
    public void sweep() {
        final File[] files = dir.listFiles();
        if (files == null)
            return;
        final long now = System.currentTimeMillis();
        int dropped = 0;
        for (File f : files) {
            final String name = f.getName();
            // A .tmp is a write that never finished; it is nothing to anybody.
            final long ttl = name.endsWith(".tmp") ? 0
                    : name.endsWith(".miss") ? MISS_TTL_MS : HIT_TTL_MS;
            if (now - f.lastModified() > ttl && f.delete())
                dropped++;
        }
        if (dropped > 0)
            Log.d(TAG, "zone cache: " + dropped + " expired entries dropped");
    }
}
