package com.atakmap.android.atmosphere.data;

import android.os.Handler;
import android.os.Looper;

import com.atakmap.android.atmosphere.net.Http;
import com.atakmap.coremap.log.Log;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * NWS zone polygons, fetched one at a time and kept on disk: the join that turns a
 * zone-based alert -- a Red Flag Warning is one -- into a shape.
 *
 * <p>Carried forward from IPAWS ({@code ipaws.data.ZoneCache}, 2026-09-24), which
 * worked all of this out; see {@code shared-helpers-are-copy-forward}. Its findings,
 * which still hold:
 *
 * <ul>
 *   <li>Most alerts carry no geometry of their own (IPAWS 2026-09-16: 312 of 337; here
 *       2026-09-24: 229 of 260 warnings and watches).</li>
 *   <li>One zone per request, because the bulk form fails silently:
 *       {@code /zones?id=A,B,C} answers 200 with features and null geometry.</li>
 *   <li>The URL is used verbatim as the alert gave it, never rebuilt, so the zone type
 *       (forecast / county / fire) is never guessed. The single-zone endpoint returns
 *       geometry by default; {@code include_geometry=true} on it is a 400.</li>
 *   <li>A 404 is durable absence ({@code InvalidZone}) and is remembered, briefly; a
 *       timeout, 5xx or TLS failure is a working zone not reached today and is never
 *       written down ({@code negative-cache-must-not-outlive-positive}).</li>
 * </ul>
 *
 * <p>What changed on the way: Atmosphere's {@link Http} (its status-carrying GET),
 * plain {@code java.io} so the key and the reader run in a JVM test, and the trust
 * check is {@link NwsAlerts#isZoneUrl}, the same one the alert parser keeps zones by.
 */
public class ZoneCache {

    private static final String TAG = "AtmosphereWarnings";
    private static final Charset UTF8 = Charset.forName("UTF-8");

    /** NWS redraws zones a few times a year, so a hit is good for a long time. */
    private static final long HIT_TTL_MS = 30L * 24 * 60 * 60 * 1000;
    /** A miss is good for far less than a hit, on purpose. */
    private static final long MISS_TTL_MS = 3L * 24 * 60 * 60 * 1000;

    /**
     * Zones asked for per round. The whole country's warnings named 323 that night;
     * a phone is not going to open hundreds of requests at once, so the rest arrive on
     * later rounds and the map fills in.
     */
    public static final int MAX_FETCH_PER_ROUND = 40;

    private final File dir;
    private final String userAgent;
    private final Set<String> inFlight = new LinkedHashSet<>();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService disk = Executors.newSingleThreadExecutor(
            new ThreadFactory() {
                @Override
                public Thread newThread(Runnable r) {
                    final Thread t = new Thread(r, "atmosphere-zones");
                    t.setDaemon(true);
                    return t;
                }
            });

    public interface Settled {
        void onSettled();
    }

    public ZoneCache(File dir, String userAgent) {
        this.dir = dir;
        this.userAgent = userAgent;
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
            final JSONObject o = new JSONObject(new String(readAll(f), UTF8));
            // A file that parsed but holds nothing drawable is corruption, not a zone.
            return o.optJSONArray("coordinates") == null
                    && o.optJSONArray("geometries") == null ? null : o;
        } catch (Exception e) {
            Log.w(TAG, "unreadable cached zone " + f.getName() + ", dropping", e);
            if (!f.delete())
                Log.w(TAG, "could not delete " + f);
            return null;
        }
    }

    /** True when the server has said it does not hold this zone, recently. */
    public boolean isKnownAbsent(String url) {
        final File f = missFile(url);
        return f != null && f.isFile()
                && System.currentTimeMillis() - f.lastModified() <= MISS_TTL_MS;
    }

    /**
     * Fetches whichever of these zones is not already held, capped, and calls back
     * once when that batch has settled -- succeeded, 404'd or failed -- on main. Returns
     * the number requested, so the caller can say how much is still filling in.
     */
    public int fetchMissing(Collection<String> urls, int max, final Settled onSettled) {
        final List<String> want = new ArrayList<>();
        synchronized (inFlight) {
            for (String url : urls) {
                if (want.size() >= max)
                    break;
                if (url == null || inFlight.contains(url))
                    continue;
                if (hitFile(url) == null)
                    continue; // not a URL we follow or can key
                want.add(url);
            }
            inFlight.addAll(want);
        }
        if (want.isEmpty()) {
            if (onSettled != null)
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        onSettled.onSettled();
                    }
                });
            return 0;
        }
        final AtomicInteger outstanding = new AtomicInteger(want.size());
        for (final String url : want)
            fetchOne(url, outstanding, onSettled);
        return want.size();
    }

    private void fetchOne(final String url, final AtomicInteger outstanding,
            final Settled onSettled) {
        Http.getBytes(url, userAgent, new Http.StatusCallback() {
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
            public void onFailure(int status, String error) {
                if (status == 404) {
                    // Durable absence: the server says it does not hold this zone.
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
                // down -- it is asked for again on the next round.
                Log.d(TAG, "zone " + zoneKey(url) + " unavailable (" + error + ")");
                done();
            }

            private void done() {
                synchronized (inFlight) {
                    inFlight.remove(url);
                }
                if (outstanding.decrementAndGet() == 0 && onSettled != null)
                    main.post(new Runnable() {
                        @Override
                        public void run() {
                            onSettled.onSettled();
                        }
                    });
            }
        });
    }

    /** Keeps only the zone's geometry: the rest of the response is metadata. */
    private void store(String url, byte[] body) {
        final File f = hitFile(url);
        if (f == null || body == null)
            return;
        try {
            final JSONObject geom = new JSONObject(new String(body, UTF8))
                    .optJSONObject("geometry");
            if (geom == null) {
                // 200 with null geometry is what the bulk endpoint does; if the single
                // one ever starts, the map would silently lose zones.
                Log.w(TAG, "zone " + zoneKey(url) + ": 200 with no geometry");
                return;
            }
            writeAtomic(f, geom.toString().getBytes(UTF8));
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
     * {@code https://api.weather.gov/zones/fire/CAZ285} to {@code fire_CAZ285}, or null
     * for a URL this plugin will not follow. The single gate: everything that fetches
     * or reads a zone goes through a key, so a URL with no key cannot be requested and
     * cannot name a file. The whole URL must match {@link NwsAlerts#isZoneUrl} --
     * api.weather.gov, a known zone type, a UGC-shaped id -- so no segment of a
     * server-supplied string can climb out of the cache directory.
     */
    static String zoneKey(String url) {
        if (!NwsAlerts.isZoneUrl(url))
            return null;
        final String[] parts = url.split("/");
        return parts[parts.length - 2] + "_" + parts[parts.length - 1].toUpperCase(Locale.US);
    }

    private File hitFile(String url) {
        final String key = zoneKey(url);
        return key == null ? null : new File(dir, key + ".json");
    }

    private File missFile(String url) {
        final String key = zoneKey(url);
        return key == null ? null : new File(dir, key + ".miss");
    }

    private static byte[] readAll(File f) throws IOException {
        try (InputStream in = new FileInputStream(f)) {
            final ByteArrayOutputStream out = new ByteArrayOutputStream();
            final byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0)
                out.write(buf, 0, n);
            return out.toByteArray();
        }
    }

    /**
     * Writes through a temporary file and renames: a plain write interrupted by ATAK
     * being killed leaves a truncated zone that can parse as a valid but incomplete
     * polygon. The rename is the only step a reader can observe.
     */
    private static void writeAtomic(File f, byte[] bytes) throws IOException {
        final File tmp = new File(f.getParentFile(), f.getName() + ".tmp");
        try (OutputStream out = new FileOutputStream(tmp)) {
            out.write(bytes);
        }
        if (!tmp.renameTo(f)) {
            if (!tmp.delete())
                Log.w(TAG, "could not clean up " + tmp);
            throw new IOException("could not replace " + f.getName());
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
            final long ttl = name.endsWith(".tmp") ? 0
                    : name.endsWith(".miss") ? MISS_TTL_MS : HIT_TTL_MS;
            if (now - f.lastModified() > ttl && f.delete())
                dropped++;
        }
        if (dropped > 0)
            Log.d(TAG, "zone cache: " + dropped + " expired entries dropped");
    }

    /** Runs a job on the cache's disk thread, for callers that read many zones at once. */
    public void onDiskThread(Runnable job) {
        disk.execute(job);
    }

    public void dispose() {
        disk.shutdownNow();
    }
}
