package com.atakmap.android.comms.data;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.preference.PreferenceManager;

import com.atakmap.android.comms.model.Catalog;
import com.atakmap.android.comms.net.Http;
import com.atakmap.android.maps.MapView;
import com.atakmap.coremap.filesystem.FileSystemUtils;
import com.atakmap.coremap.log.Log;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Where the catalog comes from, in order: the copy last downloaded to disk, the copy
 * built into the APK, and the depot host when it has been a day or the operator
 * presses Sync.
 *
 * <p>The status line says which one the pane is showing and how old it is, because
 * an operator in a canyon with no signal should know they are reading yesterday's
 * catalog rather than wonder why a new site is missing.
 *
 * <p>The on-disk copy lives at a fixed path under ATAK's own tree; nothing about the
 * path comes from the network. A download replaces it only after it has parsed as a
 * catalog this build understands, so a bad response can never leave the plugin with
 * nothing to show.
 */
public final class CatalogStore {

    private static final String TAG = "CommsStore";

    public static final String DEFAULT_BASE_URL = "https://mapdepot.takwerx.org/comms";
    private static final String PREF_BASE_URL = "comms_base_url";
    private static final String PREF_LAST_FETCH = "comms_catalog_fetched";
    private static final String PREF_LAST_ORIGIN = "comms_catalog_origin";
    /** Refresh on open once this old; the catalog changes by the week, not the minute. */
    private static final long STALE_MS = 24L * 60 * 60 * 1000;

    public interface Listener {
        /** A catalog is ready, or a newer one replaced it. */
        void onCatalog(Catalog catalog, String status);

        /** A fetch failed; the current catalog stands. */
        void onFetchFailed(String status);
    }

    private final Context pluginContext;
    private final MapView mapView;
    private final Listener listener;
    private final File cacheFile;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private Catalog catalog;
    private String status = "Loading catalog…";
    private boolean fetching;

    public CatalogStore(Context pluginContext, MapView mapView, Listener listener) {
        this.pluginContext = pluginContext;
        this.mapView = mapView;
        this.listener = listener;
        this.cacheFile = new File(FileSystemUtils.getItem("tools/comms"), "catalog.json");
    }

    public Catalog catalog() {
        return catalog;
    }

    public String status() {
        return status;
    }

    /** Disk, then the bundled copy, off the main thread; then a refresh if it is time. */
    public void load() {
        worker.execute(new Runnable() {
            @Override
            public void run() {
                Catalog c = null;
                String origin = null;
                if (cacheFile.isFile()) {
                    try {
                        c = parse(new String(FileSystemUtils.read(cacheFile), "UTF-8"));
                        origin = "downloaded " + when(prefs().getLong(PREF_LAST_FETCH, cacheFile.lastModified()));
                    } catch (Exception e) {
                        Log.w(TAG, "on-disk catalog unreadable, using the built-in copy", e);
                    }
                }
                if (c == null) {
                    try {
                        c = parse(readAsset("catalog.json"));
                        origin = "built in";
                    } catch (Exception e) {
                        Log.e(TAG, "bundled catalog unreadable", e);
                        publish(null, "Catalog unreadable");
                        return;
                    }
                }
                publish(c, describe(c, origin));
                final long last = prefs().getLong(PREF_LAST_FETCH, 0);
                if (System.currentTimeMillis() - last > STALE_MS)
                    main.post(new Runnable() {
                        @Override
                        public void run() {
                            fetch();
                        }
                    });
            }
        });
    }

    /** The operator's Sync: fetch now whatever the age. */
    public void sync() {
        fetch();
    }

    public boolean isFetching() {
        return fetching;
    }

    private void fetch() {
        if (fetching)
            return;
        fetching = true;
        final String url = baseUrl() + "/catalog.json";
        Http.get(url, new Http.Callback() {
            @Override
            public void onSuccess(final byte[] body) {
                worker.execute(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            final Catalog c = parse(new String(body, "UTF-8"));
                            if (c.sites.isEmpty())
                                throw new IllegalStateException("catalog is empty");
                            write(body);
                            final long now = System.currentTimeMillis();
                            prefs().edit().putLong(PREF_LAST_FETCH, now)
                                    .putString(PREF_LAST_ORIGIN, "downloaded").apply();
                            fetching = false;
                            publish(c, describe(c, "downloaded " + when(now)));
                        } catch (Exception e) {
                            Log.w(TAG, "depot catalog rejected: " + e.getMessage());
                            fetching = false;
                            failed("Catalog download rejected: " + e.getMessage());
                        }
                    }
                });
            }

            @Override
            public void onFailure(String error) {
                fetching = false;
                failed("Catalog not refreshed (" + error + ")");
            }
        });
    }

    private Catalog parse(String json) throws Exception {
        final Catalog c = new Catalog(new JSONObject(json));
        if (c.format > Catalog.SUPPORTED_FORMAT)
            throw new IllegalStateException("catalog format " + c.format + " is newer than this plugin");
        return c;
    }

    private void write(byte[] body) throws Exception {
        final File dir = cacheFile.getParentFile();
        if (dir != null && !dir.isDirectory() && !dir.mkdirs())
            throw new IllegalStateException("cannot create " + dir);
        final File tmp = new File(dir, "catalog.json.part");
        try (OutputStream out = new FileOutputStream(tmp)) {
            out.write(body);
        }
        if (!tmp.renameTo(cacheFile) && !(cacheFile.delete() && tmp.renameTo(cacheFile)))
            throw new IllegalStateException("cannot replace " + cacheFile);
    }

    private String describe(Catalog c, String origin) {
        final StringBuilder b = new StringBuilder();
        b.append(String.format(Locale.US, "%,d sites, %,d nets", c.sites.size(), c.nets.size()));
        if (!c.generated.isEmpty())
            b.append(" · catalog of ").append(localDay(c.generated));
        b.append(" · ").append(origin);
        return b.toString();
    }

    private void publish(final Catalog c, final String s) {
        main.post(new Runnable() {
            @Override
            public void run() {
                if (c != null) {
                    catalog = c;
                    Tones.use(c.tones);
                }
                status = s;
                listener.onCatalog(c, s);
            }
        });
    }

    private void failed(final String s) {
        main.post(new Runnable() {
            @Override
            public void run() {
                listener.onFetchFailed(s);
            }
        });
    }

    private SharedPreferences prefs() {
        return PreferenceManager.getDefaultSharedPreferences(mapView.getContext());
    }

    /** The depot host from the preference, HTTPS only; anything else is the default. */
    private String baseUrl() {
        final String u = prefs().getString(PREF_BASE_URL, DEFAULT_BASE_URL).trim()
                .replaceAll("/+$", "");
        return u.startsWith("https://") ? u : DEFAULT_BASE_URL;
    }

    private static String when(long millis) {
        return new SimpleDateFormat("MMM d HH:mm", Locale.US).format(new Date(millis));
    }

    /** "2026-09-10" in the phone's time zone for the catalog's UTC stamp; the stamp itself if unreadable. */
    private static String localDay(String utc) {
        try {
            final SimpleDateFormat in = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US);
            in.setTimeZone(TimeZone.getTimeZone("UTC"));
            return new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(in.parse(utc));
        } catch (Exception e) {
            return utc;
        }
    }

    private String readAsset(String name) throws Exception {
        try (InputStream in = pluginContext.getAssets().open(name)) {
            final java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            final byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0)
                bos.write(buf, 0, n);
            return new String(bos.toByteArray(), "UTF-8");
        }
    }

    public void dispose() {
        worker.shutdownNow();
    }
}
