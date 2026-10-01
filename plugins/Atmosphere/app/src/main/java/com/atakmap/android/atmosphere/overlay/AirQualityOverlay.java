package com.atakmap.android.atmosphere.overlay;

import android.content.Context;
import android.content.SharedPreferences;

import com.atakmap.android.atmosphere.compat.MapCompat;
import com.atakmap.android.atmosphere.data.AirNow;
import com.atakmap.android.atmosphere.data.GeoJson;
import com.atakmap.android.atmosphere.net.EgressPolicy;
import com.atakmap.android.atmosphere.net.Http;
import com.atakmap.android.maps.MapView;
import com.atakmap.coremap.log.Log;
import com.atakmap.map.layer.feature.AttributeSet;
import com.atakmap.map.layer.feature.geometry.Geometry;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * EPA AirNow's latest Air Quality Index on the map: the country in six colored bands,
 * as a read-only feature layer, the same machinery as the storms. Not time-enabled --
 * it is the latest hour and nothing else -- so it does not share the strip and can be
 * on beside smoke, wind or radar.
 *
 * <p>One request draws the whole country (92 KB), so a pan never asks again. While
 * the layer is on it asks for the stamp alone every quarter hour, a few hundred bytes,
 * and fetches the shapes only when the hour has moved. Nothing about the operator is
 * in either request.
 *
 * <p>Every store write is on {@link #worker}, never on main: the store is opened on
 * a thread of its own and registered on the map by a hop back to main, so a write
 * from main can end up waiting for main. This layer did that from start() and froze
 * ATAK for 20 s on every start with the layer off (2026-09-27).
 */
public final class AirQualityOverlay {

    private static final String TAG = "AtmosphereAir";

    public static final String LAYER_ID = "airquality";
    public static final String HOST = AirNow.HOST;
    /** The layer's name in Overlay Manager and the details pane's subtitle. */
    public static final String NAME = "Air Quality";

    private static final String PREF_ON = "weather.layer.airquality.on";
    /** AirNow publishes hourly, a little before the next hour. */
    private static final long AUTO_MS = 15 * 60 * 1000L;
    /** A pane opened sooner than this after the last look does not look again. */
    private static final long REFRESH_MS = 10 * 60 * 1000L;

    /**
     * Filled enough to read the band, thin enough to read the map under it. Good is
     * the palest, because most of the country is Good most of the time and a green
     * wash over everything says nothing a crew needs.
     */
    private static final int FILL_ALPHA = 0x59;
    private static final int GOOD_FILL_ALPHA = 0x26;
    private static final int STROKE_ALPHA = 0xB0;
    private static final float STROKE_WEIGHT = 1.5f;

    public interface Listener {
        /** A line for the pane: what hour is on the map, or what went wrong. */
        void onStatus(String status);

        /** New contours are on the map, so a reading at a point may have changed. */
        void onContours();
    }

    private final MapView mapView;
    private final EgressPolicy egress;
    private final AtmosphereFeatures features;
    private Listener listener;
    private boolean started, on;
    private long lastCheck;
    /** The stamp on the map, as the service wrote it; 0 when nothing is drawn. */
    private long drawnStamp;
    private AirNow.Contours drawn;
    /** Read on the worker, to drop a write a newer refresh or a toggle has overtaken. */
    private volatile int generation;
    private boolean inFlight;
    /** Parses the country and writes the store, in order, off the map's thread. */
    private final ExecutorService worker = Executors.newSingleThreadExecutor();

    private final Runnable autoRefresh = new Runnable() {
        @Override
        public void run() {
            if (!on || !started)
                return;
            refresh(true);
            mapView.postDelayed(this, AUTO_MS);
        }
    };

    public AirQualityOverlay(MapView mapView, Context pluginContext, EgressPolicy egress) {
        this.mapView = mapView;
        this.egress = egress;
        this.features = new AtmosphereFeatures(mapView, pluginContext, TAG, NAME,
                "airquality.sqlite", "airquality", false);
    }

    public void setListener(Listener l) {
        listener = l;
        if (l != null)
            l.onStatus(statusLine());
    }

    public void start() {
        started = true;
        features.attach();
        final SharedPreferences p = MapCompat.prefs();
        if (p != null && p.getBoolean(PREF_ON, false) && egress.isLayerEnabled(LAYER_ID))
            setOn(true);
        // Nothing to clear with the layer off. This cleared the store here, on main,
        // for "a killed ATAK leaves last session's contours" -- but attach() opens an
        // empty file every time now, and the clear waited for a hop to main that only
        // main could run: 20 s of frozen ATAK on every start (2026-09-27).
    }

    public void stop() {
        started = false;
        on = false;
        mapView.removeCallbacks(autoRefresh);
        // Forget what is drawn, but write nothing: detach takes the layer off the map
        // and the next attach opens an empty file, so a clear queued now could only
        // land after the detach. The shutdown drops anything still queued.
        forget();
        worker.shutdownNow();
        features.detach();
    }

    public boolean isOn() {
        return on;
    }

    public void setOn(boolean value) {
        if (!started || on == value)
            return;
        on = value;
        final SharedPreferences p = MapCompat.prefs();
        if (p != null)
            p.edit().putBoolean(PREF_ON, value).apply();
        mapView.removeCallbacks(autoRefresh);
        if (value) {
            refresh(true);
            mapView.postDelayed(autoRefresh, AUTO_MS);
        } else {
            clear();
            status("");
        }
    }

    /** The band at a point, or null outside the contours or with nothing drawn. */
    public AirNow.Category at(double lat, double lon) {
        final AirNow.Contours c = drawn;
        return c == null ? null : c.at(lat, lon);
    }

    /**
     * Ask whether there is a newer hour, and draw it if so. {@code force} is for the
     * toggle and the timer; the pane opening only asks once the last look is stale.
     */
    public void refresh(boolean force) {
        if (!on || !started || inFlight)
            return;
        final long now = System.currentTimeMillis();
        if (!force && now - lastCheck < REFRESH_MS)
            return;
        lastCheck = now;
        final int mine = generation;
        inFlight = true;
        Http.get(AirNow.stampUrl(), egress.userAgent(), null, new Http.Callback() {
            @Override
            public void onSuccess(String body) {
                inFlight = false;
                if (mine != generation || !on)
                    return;
                final long stamp = AirNow.parseStamp(body);
                if (stamp != 0 && stamp == drawnStamp) {
                    status(statusLine());
                    return;
                }
                fetchContours(mine);
            }

            @Override
            public void onFailure(String error) {
                inFlight = false;
                if (mine != generation || !on)
                    return;
                Log.w(TAG, "stamp failed: " + error);
                // What is drawn stays drawn: an hour-old map beats an empty one, and
                // the line says which it is.
                status(drawn == null ? "Could not reach EPA AirNow"
                        : statusLine() + " (could not check for newer)");
            }
        });
    }

    private void fetchContours(final int mine) {
        if (drawn == null)
            status("Getting air quality…");
        inFlight = true;
        Http.get(AirNow.contoursUrl(), egress.userAgent(), null, new Http.Callback() {
            @Override
            public void onSuccess(final String body) {
                inFlight = false;
                if (mine != generation || !on)
                    return;
                // The country's shapes are 92 KB to parse and a store write to draw;
                // neither is work for the thread that draws the map.
                run(new Runnable() {
                    @Override
                    public void run() {
                        draw(body, mine);
                    }
                });
            }

            @Override
            public void onFailure(String error) {
                inFlight = false;
                if (mine != generation || !on)
                    return;
                Log.w(TAG, "contours failed: " + error);
                status(drawn == null ? "Could not reach EPA AirNow"
                        : statusLine() + " (could not check for newer)");
            }
        });
    }

    /**
     * Worker: read the contours and replace the layer with them in one pass, then
     * hand the result to main. A single rewrite, so the map goes from one hour to the
     * next without the blank a clear-then-draw put between them.
     */
    private void draw(String body, final int mine) {
        final AirNow.Contours c;
        try {
            c = AirNow.parse(body);
        } catch (Exception e) {
            Log.w(TAG, "contours unreadable", e);
            onMain(mine, new Runnable() {
                @Override
                public void run() {
                    status("Air quality could not be read");
                }
            });
            return;
        }
        final String when = clock(AirNow.observedAt(c.unixtime));
        final List<AtmosphereFeatures.Drawn> shapes = new ArrayList<>();
        int skipped = 0;
        for (AirNow.Contour k : c.contours) {
            final Geometry g;
            try {
                g = GeoJson.parse(k.geometry);
            } catch (Exception e) {
                // GeoJson refuses a ring with unusable coordinates rather than hand
                // native code a broken one; the rest of the country still draws.
                skipped++;
                continue;
            }
            if (g == null) {
                skipped++;
                continue;
            }
            final int rgb = k.category.color & 0x00FFFFFF;
            final int fillAlpha = k.category == AirNow.Category.GOOD ? GOOD_FILL_ALPHA : FILL_ALPHA;
            shapes.add(new AtmosphereFeatures.Drawn(NAME, k.category.label, g,
                    AtmosphereFeatures.area((STROKE_ALPHA << 24) | rgb, STROKE_WEIGHT,
                            (fillAlpha << 24) | rgb),
                    attributes(k.category, when)));
        }
        // Turned off while parsing: the clear queued behind this is the last word.
        if (mine != generation)
            return;
        features.rewrite(shapes);
        Log.d(TAG, "drew " + shapes.size() + " contours for " + when + (skipped > 0
                ? ", skipped " + skipped : "") + (c.truncated ? ", the service stopped short" : ""));
        onMain(mine, new Runnable() {
            @Override
            public void run() {
                drawn = c;
                drawnStamp = c.unixtime;
                status(c.truncated ? statusLine() + " (not all of it arrived)" : statusLine());
                if (listener != null)
                    listener.onContours();
            }
        });
    }

    /** Back on main, unless a toggle or a newer refresh has overtaken this one. */
    private void onMain(final int mine, final Runnable r) {
        mapView.post(new Runnable() {
            @Override
            public void run() {
                if (mine == generation && on)
                    r.run();
            }
        });
    }

    /** Off the main thread, and never after stop() has shut the worker down. */
    private void run(Runnable r) {
        try {
            worker.execute(r);
        } catch (RuntimeException shuttingDown) {
            Log.d(TAG, "worker is gone, dropping an air quality write");
        }
    }

    /**
     * What the details pane shows for a tapped band: EPA's own name, range and words,
     * and the hour. The source's own field rides along under its own name.
     */
    private static AttributeSet attributes(AirNow.Category cat, String when) {
        final AttributeSet a = new AttributeSet();
        a.setAttribute("Air quality", cat.label);
        a.setAttribute("Index", cat.range);
        a.setAttribute("What it means", cat.meaning);
        a.setAttribute("Measured", when);
        a.setAttribute("Pollutants", "Ozone and fine particles (PM2.5), whichever is worse");
        a.setAttribute("Source", "U.S. EPA AirNow, latest hour");
        // A string, because the details pane prints strings only.
        a.setAttribute("gridcode", String.valueOf(cat.ordinal() + 1));
        return a;
    }

    /**
     * "Measured 9 pm" for what is on the map, or "" with nothing drawn. When EPA has
     * not made a newer hour it re-publishes the old one, so a map five hours old looks
     * fresh unless the line says otherwise (measured 2026-09-24, 04:57Z).
     */
    private String statusLine() {
        if (drawn == null)
            return "";
        final String line = "Measured " + clock(AirNow.observedAt(drawn.unixtime));
        return AirNow.isStale(drawn.unixtime, System.currentTimeMillis())
                ? line + ". EPA has published nothing newer." : line;
    }

    /** "9 pm", or "Tue 9 pm" when it is not today. */
    private static String clock(long when) {
        if (when <= 0)
            return "at an unknown hour";
        final boolean today = new SimpleDateFormat("yyyyMMdd", Locale.US).format(new Date(when))
                .equals(new SimpleDateFormat("yyyyMMdd", Locale.US).format(new Date()));
        return new SimpleDateFormat(today ? "h a" : "EEE h a", Locale.US).format(new Date(when))
                .replace("AM", "am").replace("PM", "pm");
    }

    /** Take the contours off the map. The store write is the worker's. */
    private void clear() {
        forget();
        run(new Runnable() {
            @Override
            public void run() {
                features.clear();
            }
        });
    }

    /** Drop what is drawn and make any response in flight land nowhere. Main only. */
    private void forget() {
        generation++;
        inFlight = false;
        drawn = null;
        drawnStamp = 0;
        if (listener != null)
            listener.onContours();
    }

    private void status(String s) {
        if (listener != null)
            listener.onStatus(s);
    }
}
