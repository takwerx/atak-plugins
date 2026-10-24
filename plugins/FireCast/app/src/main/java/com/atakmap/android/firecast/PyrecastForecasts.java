package com.atakmap.android.firecast;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import com.atakmap.android.features.FeatureDataStoreDeepMapItemQuery;
import com.atakmap.android.features.FeatureDataStoreMapOverlay;
import com.atakmap.android.firecast.model.Spread;
import com.atakmap.android.maps.MapItem;
import com.atakmap.android.maps.MapView;
import com.atakmap.coremap.filesystem.FileSystemUtils;
import com.atakmap.coremap.log.Log;
import com.atakmap.coremap.maps.coords.GeoPoint;
import com.atakmap.map.layer.feature.AttributeSet;
import com.atakmap.map.layer.feature.Feature;
import com.atakmap.map.layer.feature.FeatureDataStore2;
import com.atakmap.map.layer.feature.FeatureLayer3;
import com.atakmap.map.layer.feature.FeatureSet;
import com.atakmap.map.layer.feature.FeatureSetCursor;
import com.atakmap.map.layer.feature.datastore.FeatureSetDatabase2;
import com.atakmap.map.layer.feature.geometry.LineString;
import com.atakmap.map.layer.feature.geometry.Polygon;
import com.atakmap.map.layer.feature.style.BasicStrokeStyle;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * PyreCast's fire spread forecasts: ELMFIRE runs CloudFire publishes for large fires,
 * shown by permission (2026-10-21, with a link to pyrecast.com and their logo beside
 * them). For each fire the plugin holds the newest run's time-of-arrival rasters for
 * the 10th, 50th and 90th percentile. PyreCast ranks its ensemble by the area burned at
 * the end of the forecast, so a percentile is one whole run (the 90th: nine in ten runs
 * ended smaller), and runs need not nest hour by hour; the plugin shows one at a time,
 * the middle one unless the operator picks an end, as the fire's edge at fixed hours
 * after the run, and a picked forecast gets the slider with that run's edge in cyan.
 *
 * <p>Reference data for a crew, so it is kept: every raster is saved under
 * tools/firecast/pyrecast and comes back at start with no network; off hides the sets
 * and deletes nothing; a failed fetch never empties the layer.
 */
public final class PyrecastForecasts {

    private static final String TAG = "FireCast";
    public static final String BASE = "https://data.pyrecast.org/fire_spread_forecast/";
    public static final String SITE = "https://www.pyrecast.com";
    private static final long EVERY_MS = 30 * 60_000L;
    private static final int MAX_FIRES = 60;
    private static final long MAX_FILE = 24L << 20;
    private static final int MAX_DIM = 8192, MARGIN = 2;
    /** PyreCast's "Predicted Fire Size" choices, in the order its own menu lists them. */
    public static final int[] PERCENTILES = { 90, 70, 50, 30, 10 };
    /** PyreCast's own words for what the choice is, from its map's help text. */
    public static final String SIZE_HELP = "Each fire forecast is an ensemble of 1,000 separate simulations to account"
            + " for uncertainty in model inputs. This leads to a range of predicted fire sizes.";

    /** The hours after the run the layer draws the likely edge at, and their colors, warm near the fire. */
    public static final int[] RING_HOURS = { 1, 3, 6, 12, 24, 48, 72, 168, 336 };
    private static final int[] RING_COLORS = { 0xFFFFE000, 0xFFFFA000, 0xFFFF6000, 0xFFFF2000, 0xFFD00000,
            0xFFA0006A, 0xFF7000A8, 0xFF4040D0, 0xFF2090E0 };
    /** The slider's notches, in hours after the run. */
    private static final int[] NOTCHES = { 1, 2, 3, 4, 5, 6, 8, 10, 12, 15, 18, 21, 24, 30, 36, 42, 48, 60, 72, 96,
            120, 144, 168, 192, 216, 240, 264, 288, 312, 336 };

    private static final Pattern SLUG = Pattern.compile("^[a-z0-9][a-z0-9_-]{0,63}$");
    private static final Pattern RUN = Pattern.compile("^[0-9]{8}_[0-9]{6}$");
    private static final Pattern HREF = Pattern.compile("href=\"([A-Za-z0-9_.-]{1,80})/\"");

    public static int ringColor(int hours) {
        for (int i = 0; i < RING_HOURS.length; i++)
            if (RING_HOURS[i] == hours)
                return RING_COLORS[i];
        return RING_COLORS[RING_COLORS.length - 1];
    }

    public interface Listener {
        /** Main thread. */
        void onPyrecastChanged();
    }

    /** One fire's newest forecast: a raster per percentile and when the run started. */
    public static final class Forecast {
        public final String slug, run;
        public final long runMs;
        private final java.util.Map<Integer, GeoTiffGrid> grids;

        /** @param grids by percentile; the 50th is always there */
        Forecast(String slug, String run, java.util.Map<Integer, GeoTiffGrid> grids) {
            this.slug = slug;
            this.run = run;
            this.runMs = utc(run);
            this.grids = grids;
        }

        /** This device holds that Predicted Fire Size. */
        public boolean has(int pct) {
            return grids.containsKey(pct);
        }

        boolean complete() {
            return grids.size() == PERCENTILES.length;
        }

        /** That percentile's raster, or the median's if this device has not fetched it yet. */
        GeoTiffGrid grid(int pct) {
            final GeoTiffGrid g = grids.get(pct);
            return g != null ? g : grids.get(50);
        }

        public String key() {
            return slug + "|" + run;
        }

        /**
         * "Bee Haven", out of "fl-bee-haven". The two letters in front are not always
         * the state the fire is in ("or-garda-falls" burns on Mount Rainier, in
         * Washington), so they are left off rather than shown as a place.
         */
        public String name() {
            final String[] p = slug.split("-");
            final int from = p.length >= 2 && p[0].length() == 2 ? 1 : 0;
            final StringBuilder s = new StringBuilder();
            for (int i = from; i < p.length; i++)
                s.append(i > from ? " " : "").append(title(p[i]));
            return s.toString();
        }

        /** Latitude and longitude of the middle of the ground the forecast covers. */
        public double[] center() {
            final GeoTiffGrid g = grid(50);
            return g.latLon(g.nx / 2.0, g.ny / 2.0);
        }

        /** How far out the run goes, in hours. */
        public double maxHours(int pct) {
            return grid(pct).max;
        }

        /** The slider's notches, ascending, up to where the run stops. */
        public double[] hours(int pct) {
            final double top = Math.ceil(maxHours(pct));
            int n = 0;
            while (n < NOTCHES.length && (n == 0 || NOTCHES[n] <= top))
                n++;
            final double[] out = new double[n];
            for (int i = 0; i < n; i++)
                out[i] = NOTCHES[i];
            return out;
        }

        /** Acres that percentile's run has burned by that hour. */
        public double acres(int pct, double h) {
            final GeoTiffGrid g = grid(pct);
            int c = 0;
            for (float f : g.v)
                if (f <= h)
                    c++;
            return c * g.cell * g.cell / 4046.8564224;
        }

        /** The fire's edge at that hour as rings in degrees. */
        List<Polygon> rings(int pct, double h) {
            final GeoTiffGrid g = grid(pct);
            final List<List<double[]>> loops = Spread.Isoline.trace(g.v, g.nx, g.ny, (float) h);
            final List<Polygon> out = new ArrayList<>();
            for (List<double[]> loop : loops) {
                if (loop.size() < 3)
                    continue;
                final LineString ring = new LineString(2);
                for (double[] p : loop) {
                    final double[] ll = g.latLon(p[0] + 0.5, p[1] + 0.5);
                    ring.addPoint(ll[1], ll[0]);
                }
                final double[] f = g.latLon(loop.get(0)[0] + 0.5, loop.get(0)[1] + 0.5);
                ring.addPoint(f[1], f[0]);
                final Polygon poly = new Polygon(2);
                poly.addRing(ring);
                out.add(poly);
            }
            return out;
        }

        /** South-west and north-east corners of the ground that percentile's run covers. */
        GeoPoint[] bounds(int pct) {
            final GeoTiffGrid g = grid(pct);
            final double[] a = g.latLon(0, 0), b = g.latLon(g.nx, g.ny);
            return new GeoPoint[] { new GeoPoint(Math.min(a[0], b[0]), Math.min(a[1], b[1])),
                    new GeoPoint(Math.max(a[0], b[0]), Math.max(a[1], b[1])) };
        }

        private static String title(String w) {
            return w.isEmpty() ? w : Character.toUpperCase(w.charAt(0)) + w.substring(1);
        }
    }

    private final MapView mapView;
    private final File root;
    private final File storeFile;
    private final SharedPreferences prefs;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ScheduledExecutorService worker = Executors.newSingleThreadScheduledExecutor();
    private final Object lock = new Object();
    private FeatureSetDatabase2 store;
    private FeatureLayer3 layer;
    private FeatureDataStoreMapOverlay overlay;
    private volatile Listener listener;

    /** Newest run first. */
    public volatile List<Forecast> forecasts = Collections.emptyList();
    /** When the index was last read with success; 0 if never. */
    public volatile long fetchedAt;
    /** Why the last fetch failed, in words, or null. */
    public volatile String problem;
    public volatile boolean busy;

    public PyrecastForecasts(MapView mapView, Context pluginContext) {
        this.mapView = mapView;
        this.root = new File(FileSystemUtils.getItem("tools/firecast"), "pyrecast");
        this.storeFile = new File(FileSystemUtils.getItem("tools/firecast"), "pyrecast.sqlite");
        this.prefs = mapView.getContext().getSharedPreferences("firecast", Context.MODE_PRIVATE);
        this.fetchedAt = prefs.getLong("pyrecast.fetchedAt", 0L);
    }

    public void setListener(Listener l) {
        listener = l;
    }

    public boolean isOn() {
        return prefs.getBoolean("pyrecast.on", false);
    }

    /** Which Predicted Fire Size is shown: one of {@link #PERCENTILES}, the median unless picked. */
    public int pct() {
        final int p = prefs.getInt("pyrecast.pct", 50);
        for (int q : PERCENTILES)
            if (q == p)
                return p;
        return 50;
    }

    /** PyreCast's word for the choice: "Median". */
    public static String pctName(int pct) {
        switch (pct) {
            case 90: return "Largest";
            case 70: return "Larger";
            case 30: return "Smaller";
            case 10: return "Smallest";
            default: return "Median";
        }
    }

    /** PyreCast's whole label: "Median (50th percentile)". */
    public static String pctLabel(int pct) {
        return pctName(pct) + " (" + pct + "th percentile)";
    }

    public void setPct(int pct) {
        prefs.edit().putInt("pyrecast.pct", pct).apply();
        worker.execute(new Runnable() {
            @Override
            public void run() {
                synchronized (lock) {
                    drawLocked();
                    redrawAtLocked();
                }
                changed();
            }
        });
    }

    public void setOn(final boolean on) {
        prefs.edit().putBoolean("pyrecast.on", on).apply();
        worker.execute(new Runnable() {
            @Override
            public void run() {
                synchronized (lock) {
                    showLocked(on);
                    redrawAtLocked();
                }
                if (on && System.currentTimeMillis() - fetchedAt > (forecasts.isEmpty() || anyIncomplete() ? 10 * 60_000L : EVERY_MS))
                    fetch();
                changed();
            }
        });
    }

    public void start() {
        if (!root.isDirectory() && !root.mkdirs())
            Log.w(TAG, "could not create " + root);
        worker.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    synchronized (lock) {
                        attachLocked();
                    }
                    // What this device saved comes back first, network or not.
                    final List<Forecast> saved = loadSaved();
                    synchronized (lock) {
                        forecasts = saved;
                        drawLocked();
                        redrawAtLocked();
                    }
                } catch (Throwable t) {
                    Log.w(TAG, "PyreCast layer could not start", t);
                }
                changed();
            }
        });
        worker.scheduleWithFixedDelay(new Runnable() {
            @Override
            public void run() {
                // An empty layer asks again sooner: one small page, and a fire that has
                // just been picked up shows within ten minutes of being switched on.
                final long every = forecasts.isEmpty() || anyIncomplete() ? 10 * 60_000L : EVERY_MS;
                if (isOn() && System.currentTimeMillis() - fetchedAt > every - 60_000L)
                    fetch();
            }
        }, 5, 60, TimeUnit.SECONDS);
    }

    /** A forecast is missing a Predicted Fire Size: ask again sooner than every half hour. */
    private boolean anyIncomplete() {
        for (Forecast f : forecasts)
            if (!f.complete())
                return true;
        return false;
    }

    public void stop() {
        worker.shutdownNow();
        synchronized (lock) {
            try {
                if (layer != null)
                    mapView.removeLayer(MapView.RenderStack.VECTOR_OVERLAYS, layer);
                if (overlay != null)
                    mapView.getMapOverlayManager().removeOverlay(overlay);
            } catch (Exception e) {
                Log.w(TAG, "PyreCast detach failed", e);
            }
            // Never disposed: disposing a feature store aborts ATAK.
            layer = null;
            overlay = null;
            store = null;
        }
    }

    /** Fetches now, on the worker. */
    public void refresh() {
        worker.execute(new Runnable() {
            @Override
            public void run() {
                fetch();
            }
        });
    }

    /** Fits the map to a forecast. Main thread. */
    public void goTo(Forecast f) {
        com.atakmap.android.util.ATAKUtilities.scaleToFit(mapView, f.bounds(pct()), mapView.getWidth(), mapView.getHeight());
    }

    // ---- where it is by ----------------------------------------------------------

    public String focusKey() {
        return prefs.getString("pyrecast.focus", null);
    }

    public double focusHours() {
        return prefs.getFloat("pyrecast.focusHours", 0f);
    }

    public Forecast focus() {
        final String k = focusKey();
        if (k != null)
            for (Forecast f : forecasts)
                if (k.equals(f.key()))
                    return f;
        return null;
    }

    /** Puts the slider on a forecast, or takes it off with null. */
    public void setFocus(Forecast f) {
        prefs.edit().putString("pyrecast.focus", f == null ? null : f.key()).putFloat("pyrecast.focusHours", 0f).apply();
        showAt(f, 0);
    }

    /** Draws a forecast's edge at that hour in cyan over its rings; 0 draws none. */
    public void showAt(final Forecast f, final double h) {
        prefs.edit().putFloat("pyrecast.focusHours", (float) h).apply();
        worker.execute(new Runnable() {
            @Override
            public void run() {
                synchronized (lock) {
                    drawAtLocked(f, h);
                }
            }
        });
    }

    private long atFsid = -1;

    /** Lock held. */
    private void drawAtLocked(Forecast f, double h) {
        if (store == null)
            return;
        boolean bulk = false;
        try {
            store.acquireModifyLock(true);
            bulk = true;
            if (atFsid >= 0) {
                store.deleteFeatureSet(atFsid);
                atFsid = -1;
            }
            if (f == null || h <= 0 || !isOn())
                return;
            atFsid = store.insertFeatureSet(new FeatureSet("FireCast", "PyreCast", "PyreCast at", Double.MAX_VALUE, 0d));
            store.setFeatureSetVisible(atFsid, true);
            final String name = "PyreCast: " + f.name() + ", " + hoursText(h) + " after " + when(f.runMs);
            for (Polygon p : f.rings(pct(), h))
                insert(atFsid, name, p, new com.atakmap.map.layer.feature.style.CompositeStyle(new com.atakmap.map.layer.feature.style.Style[] { new BasicStrokeStyle(0xC0000000, 7f), new BasicStrokeStyle(0xFF00E5FF, 4f) }));
        } catch (Exception e) {
            Log.w(TAG, "PyreCast edge draw failed", e);
        } finally {
            if (bulk)
                store.releaseModifyLock();
        }
    }

    private void insert(long fsid, String name, Polygon p, com.atakmap.map.layer.feature.style.Style style) throws Exception {
        final AttributeSet a = new AttributeSet();
        a.setAttribute("_title", name);
        store.insertFeature(new Feature(fsid, name, p, style, a, Feature.AltitudeMode.ClampToGround, 0d));
    }

    // ---- fetch ---------------------------------------------------------------------

    /**
     * Worker thread. Reads the index, then for each fire its newest run; a run already on
     * this device is not asked for again. Only then are fires PyreCast no longer lists
     * dropped, so the index has to have answered before anything is removed.
     */
    private void fetch() {
        busy = true;
        changed();
        try {
            final List<String> slugs = names(Esri.get(BASE), SLUG, MAX_FIRES);
            String trouble = null;
            for (String slug : slugs) {
                try {
                    final List<String> runs = names(Esri.get(BASE + slug + "/"), RUN, 200);
                    if (runs.isEmpty())
                        continue;
                    Collections.sort(runs);
                    final String run = runs.get(runs.size() - 1);
                    final File dir = new File(new File(root, slug), run);
                    if (!complete(dir))
                        download(slug, run, dir);
                    // Older runs of this fire give way to the newest.
                    final File[] old = new File(root, slug).listFiles();
                    if (old != null)
                        for (File o : old)
                            if (o.isDirectory() && !o.getName().equals(run))
                                FileSystemUtils.deleteDirectory(o, false);
                } catch (Throwable t) {
                    Log.w(TAG, "PyreCast " + slug + ": " + t);
                    trouble = "could not fetch every fire";
                }
            }
            final File[] held = root.listFiles();
            if (held != null)
                for (File d : held)
                    if (d.isDirectory() && !slugs.contains(d.getName()))
                        FileSystemUtils.deleteDirectory(d, false);
            fetchedAt = System.currentTimeMillis();
            prefs.edit().putLong("pyrecast.fetchedAt", fetchedAt).apply();
            problem = trouble;
            final List<Forecast> got = loadSaved();
            synchronized (lock) {
                forecasts = got;
                drawLocked();
                redrawAtLocked();
            }
        } catch (Throwable t) {
            Log.w(TAG, "PyreCast fetch failed: " + t);
            problem = "no network";
        } finally {
            busy = false;
            changed();
        }
    }

    /** The directory names a listing links to, each checked against the shape it must have. */
    static List<String> names(String html, Pattern shape, int max) {
        final List<String> out = new ArrayList<>();
        final Matcher m = HREF.matcher(html);
        while (m.find() && out.size() < max) {
            final String n = m.group(1);
            if (shape.matcher(n).matches() && !out.contains(n))
                out.add(n);
        }
        return out;
    }

    /** Every Predicted Fire Size is on this device. */
    private static boolean complete(File dir) {
        for (int p : PERCENTILES)
            if (!new File(dir, "toa" + p + ".tif").isFile())
                return false;
        return true;
    }

    /**
     * Enough to show: the median. A run saved before the 30th and 70th were fetched
     * still comes back with no network; the missing sizes arrive with the next fetch.
     */
    private static boolean usable(File dir) {
        return new File(dir, "toa50.tif").isFile();
    }

    /** The rasters land in a temporary directory, each read once to prove it, before the run appears. */
    private void download(String slug, String run, File dir) throws Exception {
        final File tmp = new File(root, slug + "." + run + ".tmp");
        FileSystemUtils.deleteDirectory(tmp, false);
        if (!tmp.mkdirs())
            throw new IOException("could not create " + tmp.getName());
        try {
            for (int p : PERCENTILES) {
                final File f = new File(tmp, "toa" + p + ".tif");
                fetchTo(BASE + slug + "/" + run + "/elmfire/landfire/" + p + "/time-of-arrival.tif", f);
                GeoTiffGrid.read(f, MAX_FILE, MAX_DIM, MARGIN);
            }
            final File parent = dir.getParentFile();
            if (parent != null && !parent.isDirectory() && !parent.mkdirs())
                throw new IOException("could not create " + parent.getName());
            FileSystemUtils.deleteDirectory(dir, false);
            if (!tmp.renameTo(dir))
                throw new IOException("could not keep the run");
        } finally {
            FileSystemUtils.deleteDirectory(tmp, false);
        }
    }

    /** A GET to a file, https only, capped. */
    static void fetchTo(String url, File to) throws Exception {
        if (!url.startsWith("https://"))
            throw new IllegalStateException("not https");
        final HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(20000);
        c.setReadTimeout(60000);
        c.setRequestProperty("User-Agent", "FireCast (ATAK plugin)");
        try {
            final int code = c.getResponseCode();
            final URL landed = c.getURL();
            if (landed != null && !"https".equalsIgnoreCase(landed.getProtocol()))
                throw new IllegalStateException("redirected off https to " + landed.getHost());
            if (code != 200)
                throw new IllegalStateException("HTTP " + code + " from " + Esri.hostOf(url));
            long total = 0;
            try (InputStream in = c.getInputStream(); OutputStream out = new FileOutputStream(to)) {
                final byte[] buf = new byte[16384];
                int n;
                while ((n = in.read(buf)) > 0) {
                    total += n;
                    if (total > MAX_FILE)
                        throw new IOException("file over " + (MAX_FILE >> 20) + " MB");
                    out.write(buf, 0, n);
                }
            }
        } finally {
            c.disconnect();
        }
    }

    /** Every complete run on disk, one per fire, newest first; a run that will not read is removed. */
    private List<Forecast> loadSaved() {
        final List<Forecast> out = new ArrayList<>();
        final File[] fires = root.listFiles();
        if (fires == null)
            return out;
        for (File fire : fires) {
            if (!fire.isDirectory() || !SLUG.matcher(fire.getName()).matches())
                continue;
            final File[] runs = fire.listFiles();
            if (runs == null)
                continue;
            final List<String> names = new ArrayList<>();
            for (File r : runs)
                if (r.isDirectory() && RUN.matcher(r.getName()).matches() && usable(r))
                    names.add(r.getName());
            if (names.isEmpty())
                continue;
            Collections.sort(names);
            final String run = names.get(names.size() - 1);
            final File dir = new File(fire, run);
            try {
                final java.util.Map<Integer, GeoTiffGrid> grids = new java.util.HashMap<>();
                for (int p : PERCENTILES) {
                    final File f = new File(dir, "toa" + p + ".tif");
                    if (f.isFile())
                        grids.put(p, GeoTiffGrid.read(f, MAX_FILE, MAX_DIM, MARGIN));
                }
                out.add(new Forecast(fire.getName(), run, grids));
            } catch (Throwable t) {
                Log.w(TAG, "PyreCast " + fire.getName() + " " + run + " will not read: " + t);
                FileSystemUtils.deleteDirectory(dir, false);
            }
            if (out.size() >= MAX_FIRES)
                break;
        }
        Collections.sort(out, new Comparator<Forecast>() {
            @Override
            public int compare(Forecast a, Forecast b) {
                return Long.compare(b.runMs, a.runMs);
            }
        });
        return out;
    }

    /** "20261023_183200" is the run's start, UTC. */
    static long utc(String run) {
        try {
            final SimpleDateFormat f = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US);
            f.setTimeZone(TimeZone.getTimeZone("UTC"));
            final Date d = f.parse(run);
            return d == null ? 0 : d.getTime();
        } catch (Exception e) {
            return 0;
        }
    }

    public static String when(long ms) {
        return ms <= 0 ? "time not given" : new SimpleDateFormat("MMM d HH:mm", Locale.US).format(new Date(ms));
    }

    /** "18:32" within the first day of the run, "Nov 6 10:50" past it. */
    public static String clockFrom(long runMs, double h) {
        final long ms = runMs + (long) (h * 3_600_000L);
        return new SimpleDateFormat(h < 24 ? "HH:mm" : "MMM d HH:mm", Locale.US).format(new Date(ms));
    }

    /** How far out a run goes, in round words: "36 h", "13 days". */
    public static String spanText(double h) {
        if (h >= 48)
            return Math.round(h / 24) + " days";
        return Math.round(h) + " h";
    }

    public static String hoursText(double h) {
        if (h >= 48 && h % 24 == 0)
            return (int) (h / 24) + " days";
        return (h == Math.floor(h) ? String.valueOf((int) h) : String.valueOf(h)) + " h";
    }

    // ---- the map -------------------------------------------------------------------

    private void attachLocked() throws Exception {
        store = new FeatureSetDatabase2(storeFile);
        final FeatureDataStore2.FeatureQueryParameters visibleOnly = new FeatureDataStore2.FeatureQueryParameters();
        visibleOnly.visibleOnly = true;
        layer = new FeatureLayer3("PyreCast forecasts", store, visibleOnly);
        final FeatureDataStoreDeepMapItemQuery query = new FeatureDataStoreDeepMapItemQuery(layer) {
            @Override
            protected MapItem featureToMapItem(Feature feature) {
                final MapItem item = super.featureToMapItem(feature);
                item.setMetaString("title", feature.getName());
                item.setMetaString("callsign", feature.getName());
                return item;
            }
        };
        overlay = new FeatureDataStoreMapOverlay(mapView.getContext(), store, null,
                "PyreCast forecasts", "file://asset/nothing", query, null, null);
        mapView.getMapOverlayManager().addFilesOverlay(overlay);
        mapView.addLayer(MapView.RenderStack.VECTOR_OVERLAYS, layer);
    }

    private List<Long> setIdsLocked() {
        final List<Long> ids = new ArrayList<>();
        FeatureSetCursor c = null;
        try {
            c = store.queryFeatureSets(new FeatureDataStore2.FeatureSetQueryParameters());
            while (c.moveToNext())
                ids.add(c.get().getId());
        } catch (Exception e) {
            Log.w(TAG, "PyreCast sets", e);
        } finally {
            if (c != null)
                c.close();
        }
        return ids;
    }

    /** Lock held. Replaces every set with the forecasts held now, in one write. */
    private void drawLocked() {
        if (store == null)
            return;
        final boolean on = isOn();
        final int pct = pct();
        boolean bulk = false;
        try {
            store.acquireModifyLock(true);
            bulk = true;
            for (long id : setIdsLocked())
                store.deleteFeatureSet(id);
            atFsid = -1;
            for (Forecast f : forecasts) {
                final long fsid = store.insertFeatureSet(new FeatureSet("FireCast", "PyreCast",
                        f.name() + " " + f.run, Double.MAX_VALUE, 0d));
                store.setFeatureSetVisible(fsid, on);
                // Far hours first, so the first hour draws on top. The first ring past the
                // run's end is drawn too: it is the whole ground the run reaches.
                final double top = f.maxHours(pct);
                for (int i = RING_HOURS.length - 1; i >= 0; i--) {
                    final int h = RING_HOURS[i];
                    if (h > top && (i > 0 && RING_HOURS[i - 1] > top))
                        continue;
                    final String name = "PyreCast: " + f.name() + ", " + hoursText(h) + " after " + when(f.runMs);
                    for (Polygon p : f.rings(pct, h))
                        insert(fsid, name, p, new BasicStrokeStyle(RING_COLORS[i], 2.5f));
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "PyreCast draw failed", e);
        } finally {
            if (bulk)
                store.releaseModifyLock();
        }
    }

    /** Lock held. By ids, so off hides and deletes nothing. */
    private void showLocked(boolean on) {
        if (store == null)
            return;
        try {
            for (long id : setIdsLocked())
                store.setFeatureSetVisible(id, on);
        } catch (Exception e) {
            Log.w(TAG, "PyreCast visibility", e);
        }
    }

    /** Lock held: the slider's edges come back with the rings, after a restart or a fetch. */
    private void redrawAtLocked() {
        drawAtLocked(focus(), focusHours());
    }

    private void changed() {
        main.post(new Runnable() {
            @Override
            public void run() {
                final Listener l = listener;
                if (l != null)
                    l.onPyrecastChanged();
            }
        });
    }
}
