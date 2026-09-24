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
import java.util.Date;
import java.util.Locale;

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
 */
public final class AirQualityOverlay {

    private static final String TAG = "AtmosphereAir";

    public static final String LAYER_ID = "airquality";
    public static final String HOST = AirNow.HOST;
    /** The layer's name in Overlay Manager and the details pane's subtitle. */
    public static final String NAME = "Air quality";

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
    private int generation;
    private boolean inFlight;

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
    }

    public void stop() {
        started = false;
        on = false;
        mapView.removeCallbacks(autoRefresh);
        clear();
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
            public void onSuccess(String body) {
                inFlight = false;
                if (mine != generation || !on)
                    return;
                final AirNow.Contours c;
                try {
                    c = AirNow.parse(body);
                } catch (Exception e) {
                    Log.w(TAG, "contours unreadable", e);
                    status("Air quality could not be read");
                    return;
                }
                draw(c);
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

    private void draw(AirNow.Contours c) {
        features.clear();
        final String when = clock(AirNow.observedAt(c.unixtime, System.currentTimeMillis()));
        int n = 0, skipped = 0;
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
            features.addShape(NAME, k.category.label, g, (STROKE_ALPHA << 24) | rgb,
                    STROKE_WEIGHT, (fillAlpha << 24) | rgb, attributes(k.category, when));
            n++;
        }
        drawn = c;
        drawnStamp = c.unixtime;
        Log.d(TAG, "drew " + n + " contours for " + when + (skipped > 0 ? ", skipped "
                + skipped : "") + (c.truncated ? ", the service stopped short" : ""));
        status(c.truncated ? statusLine() + " (not all of it arrived)" : statusLine());
        if (listener != null)
            listener.onContours();
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

    /** "Measured 9 pm" for what is on the map, or "" with nothing drawn. */
    private String statusLine() {
        if (drawn == null)
            return "";
        return "Measured "
                + clock(AirNow.observedAt(drawn.unixtime, System.currentTimeMillis()));
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

    private void clear() {
        generation++;
        inFlight = false;
        drawn = null;
        drawnStamp = 0;
        features.clear();
        if (listener != null)
            listener.onContours();
    }

    private void status(String s) {
        if (listener != null)
            listener.onStatus(s);
    }
}
