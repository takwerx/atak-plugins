package com.atakmap.android.atmosphere.overlay;

import android.content.Context;
import android.content.SharedPreferences;

import com.atakmap.android.atmosphere.compat.MapCompat;
import com.atakmap.android.atmosphere.data.GaugeFavorites;
import com.atakmap.android.atmosphere.data.Nwps;
import com.atakmap.android.atmosphere.net.EgressPolicy;
import com.atakmap.android.atmosphere.net.Http;
import com.atakmap.android.atmosphere.units.UnitSystem;
import com.atakmap.android.maps.MapView;
import com.atakmap.coremap.log.Log;
import com.atakmap.coremap.maps.coords.GeoCalculations;
import com.atakmap.coremap.maps.coords.GeoPoint;
import com.atakmap.map.layer.feature.AttributeSet;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * River gauges around the operator, colored by flood category the way
 * water.noaa.gov colors them.
 *
 * <p>The gauges come from the National Water Prediction Service ({@link Nwps}), which
 * answers a box with every gauge in it and each one's latest observation and
 * forecast, so the whole layer is one request. Scoped by distance around the
 * operator or the middle of the map, the way the station layer is, with the same
 * two controls.
 *
 * <p>Most of what a gauge says is about the gauge, not the water: in California
 * half have no flood stages defined and a tenth are not current. The symbol says
 * which, in the service's own colors, and the record says it in words -- a gauge
 * that is not current is never drawn as "no flooding".
 */
public final class GaugeOverlay {

    private static final String TAG = "AtmosphereGauges";

    public static final String LAYER_ID = "gauges";
    public static final String HOST = Nwps.HOST;
    private static final String NAME = "River gauges";

    private static final String PREF_ON = "weather.layer.gauges.on";
    private static final String PREF_MILES = "weather.layer.gauges.miles";
    private static final String PREF_FROM_ME = "weather.layer.gauges.fromme";
    private static final String PREF_UNITS = "weather.units";

    /** Most gauges report every fifteen minutes; asking more often gains nothing. */
    private static final long POLL_MS = 15 * 60 * 1000L;
    private static final long REFRESH_MS = 60 * 1000L;
    /** How long the map must sit still before a move is acted on. */
    private static final long SETTLE_MS = 600L;

    /** What the distance buttons offer, in miles. */
    public static final int[] RADII = { 25, 50, 100, 250 };
    private static final int DEFAULT_MILES = 100;

    /** The legend, in the order water.noaa.gov lists it. */
    public static final String[] LEGEND = { Nwps.MAJOR, Nwps.MODERATE, Nwps.MINOR,
            Nwps.ACTION, Nwps.NO_FLOODING, Nwps.NOT_DEFINED, Nwps.LOW_THRESHOLD,
            Nwps.OBS_NOT_CURRENT, Nwps.OUT_OF_SERVICE };

    /** Every gauge the box returned. */
    public static final int SHOW_ALL = 0;
    /** At action stage or worse. */
    public static final int SHOW_HIGH = 1;
    /** Only the starred ones. */
    public static final int SHOW_FAVORITES = 2;
    private static final String PREF_SHOW = "weather.layer.gauges.show";

    public interface Listener {
        void onGaugesStatus(String message);

        void onGaugesDrawn(int drawn, int total, int flooding);

        /** The map moved: anything ordered by distance needs reordering. */
        void onOriginMoved();
    }

    private final MapView mapView;
    private final EgressPolicy egress;
    private final AtmosphereFeatures features;
    private final GaugeIcons icons;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();

    private Listener listener;
    private boolean started, on, inFlight;
    private int generation;
    private long lastPoll;
    private int miles;
    private boolean fromMe;
    private int show;
    private final GaugeFavorites favorites;
    private List<Nwps.Gauge> gauges = new ArrayList<>();
    /** Where the gauges currently on the map were asked for. */
    private GeoPoint fetchedFrom;

    private final Runnable autoPoll = new Runnable() {
        @Override
        public void run() {
            refresh(true);
            mapView.postDelayed(this, POLL_MS);
        }
    };

    public GaugeOverlay(MapView mapView, Context pluginContext, EgressPolicy egress) {
        this.mapView = mapView;
        this.egress = egress;
        this.features = new AtmosphereFeatures(mapView, pluginContext, TAG, NAME,
                "gauges.sqlite", "gauges", false);
        this.icons = new GaugeIcons(
                pluginContext.getResources().getDisplayMetrics().density);
        final SharedPreferences p = MapCompat.prefs();
        miles = p == null ? DEFAULT_MILES : p.getInt(PREF_MILES, DEFAULT_MILES);
        fromMe = p == null || p.getBoolean(PREF_FROM_ME, true);
        show = p == null ? SHOW_ALL : p.getInt(PREF_SHOW, SHOW_ALL);
        favorites = new GaugeFavorites(mapView.getContext());
    }

    public int show() {
        return show;
    }

    public void setShow(int value) {
        if (show == value)
            return;
        show = value;
        final SharedPreferences p = MapCompat.prefs();
        if (p != null)
            p.edit().putInt(PREF_SHOW, value).apply();
        redraw();
    }

    /**
     * Whether a gauge passes the map's filter. Public so the list asks the same
     * question; a filter on one surface is a filter the other disagrees with.
     */
    public boolean passes(int show, Nwps.Gauge g) {
        if (show == SHOW_FAVORITES)
            return favorites.contains(g.lid);
        if (show == SHOW_HIGH)
            return Nwps.severity(g.category()) >= Nwps.severity(Nwps.ACTION);
        return true;
    }

    public boolean isFavorite(Nwps.Gauge g) {
        return g != null && favorites.contains(g.lid);
    }

    /** @return true if the gauge is starred afterwards */
    public boolean toggleFavorite(Nwps.Gauge g) {
        final boolean now = favorites.toggle(g.lid);
        if (show == SHOW_FAVORITES)
            redraw();
        return now;
    }

    private void redraw() {
        if (!on)
            return;
        final int mine = generation;
        worker.execute(new Runnable() {
            @Override
            public void run() {
                rebuild(mine);
            }
        });
    }

    /** The legend's color for a category, for the pane's swatches. */
    public static int legendColor(String category) {
        return GaugeIcons.color(category);
    }

    public void setListener(Listener l) {
        listener = l;
    }

    public void start() {
        started = true;
        features.attach();
        mapView.addOnMapMovedListener(moved);
        final SharedPreferences p = MapCompat.prefs();
        if (p != null && p.getBoolean(PREF_ON, false) && egress.isLayerEnabled(LAYER_ID))
            setOn(true);
    }

    public void stop() {
        started = false;
        on = false;
        generation++;
        mapView.removeCallbacks(autoPoll);
        mapView.removeCallbacks(settled);
        mapView.removeOnMapMovedListener(moved);
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
        mapView.removeCallbacks(autoPoll);
        generation++;
        inFlight = false;
        if (value) {
            refresh(true);
            mapView.postDelayed(autoPoll, POLL_MS);
        } else {
            gauges = new ArrayList<>();
            fetchedFrom = null;
            clearOffMain();
            status("");
            drawn(0, 0, 0);
        }
    }

    /** How far out gauges are asked for, in miles. */
    public int miles() {
        return miles;
    }

    /** Whether the box is around the operator or around the middle of the map. */
    public boolean isFromMe() {
        return fromMe;
    }

    public void setMiles(int value) {
        if (miles == value)
            return;
        miles = value;
        fetchedFrom = null;
        final SharedPreferences p = MapCompat.prefs();
        if (p != null)
            p.edit().putInt(PREF_MILES, value).apply();
        refresh(true);
    }

    public void setFromMe(boolean value) {
        if (fromMe == value)
            return;
        fromMe = value;
        fetchedFrom = null;
        final SharedPreferences p = MapCompat.prefs();
        if (p != null)
            p.edit().putBoolean(PREF_FROM_ME, value).apply();
        refresh(true);
    }

    /** The gauges currently held, for a list that must agree with the map. */
    public List<Nwps.Gauge> gauges() {
        return new ArrayList<>(gauges);
    }

    /** Where distances are measured from right now: the operator, or the map. */
    public GeoPoint originPoint() {
        return origin();
    }

    private GeoPoint origin() {
        final GeoPoint p = fromMe ? MapCompat.selfPoint() : MapCompat.mapCenter();
        return p != null && p.isValid() ? p : null;
    }

    /** Ask NWPS for the gauges in the box around the origin. */
    public void refresh(boolean force) {
        if (!on || !started || inFlight)
            return;
        final long now = System.currentTimeMillis();
        if (!force && now - lastPoll < REFRESH_MS)
            return;
        final GeoPoint from = origin();
        if (from == null) {
            status(fromMe ? "No position yet — switch to the map, or wait for a fix"
                    : "The map has no center yet");
            return;
        }
        lastPoll = now;
        inFlight = true;
        fetchedFrom = from;
        final int mine = generation;
        if (gauges.isEmpty())
            status("Getting gauges…");
        final Map<String, String> headers = new HashMap<>();
        headers.put("Accept", "application/json");
        // Rounded the way every other outbound coordinate is rounded; the box is
        // miles across, so a coarser center loses nothing.
        final String url = Nwps.boxUrl(Double.parseDouble(egress.latitude(from)),
                Double.parseDouble(egress.longitude(from)), miles);
        Http.get(url, egress.userAgent(), headers, new Http.Callback() {
            @Override
            public void onSuccess(final String body) {
                if (mine != generation || !on) {
                    inFlight = false;
                    return;
                }
                worker.execute(new Runnable() {
                    @Override
                    public void run() {
                        gauges = Nwps.parse(body);
                        inFlight = false;
                        rebuild(mine);
                    }
                });
            }

            @Override
            public void onFailure(final String error) {
                inFlight = false;
                if (mine != generation || !on)
                    return;
                Log.w(TAG, "gauge fetch failed: " + error);
                status("Could not reach the gauge service");
            }
        });
    }

    /** Draw what is held. Worker thread only: it composes icons and writes the store. */
    private void rebuild(int mine) {
        if (mine != generation || !on)
            return;
        final long began = android.os.SystemClock.elapsedRealtime();
        final List<Nwps.Gauge> held = gauges;
        final long now = System.currentTimeMillis();
        final UnitSystem system = units();
        final List<AtmosphereFeatures.Drawn> drawn = new ArrayList<>();
        int flooding = 0;
        for (Nwps.Gauge g : held) {
            if (!passes(show, g))
                continue;
            final String category = g.category();
            final String uri = icons.uri(GaugeIcons.color(category));
            if (uri == null)
                continue;
            if (Nwps.severity(category) >= Nwps.severity(Nwps.ACTION))
                flooding++;
            // The set is the category, so Overlay Manager lists "Minor flooding" on
            // its own and ATAK's own switches work on one at a time.
            drawn.add(new AtmosphereFeatures.Drawn(Nwps.label(category), g.name,
                    AtmosphereFeatures.point(g.latitude, g.longitude),
                    AtmosphereFeatures.icon(uri, GaugeIcons.SIZE_DP, GaugeIcons.SIZE_DP),
                    attrs(g, now, system)));
        }
        if (mine != generation || !on)
            return;
        features.rewrite(drawn);
        final int n = drawn.size();
        final int total = held.size();
        final int high = flooding;
        mapView.post(new Runnable() {
            @Override
            public void run() {
                if (mine != generation || !on)
                    return;
                status("");
                drawn(n, total, high);
            }
        });
        Log.d(TAG, String.format(Locale.US,
                "drew %d of %d gauges within %d mi of %s, %d at or above action stage, took %d ms",
                n, total, miles, fromMe ? "me" : "the map", high,
                android.os.SystemClock.elapsedRealtime() - began));
    }

    /**
     * The record a tap shows, in the order it is worth reading: what the water is
     * doing, then the numbers, then whose gauge it is.
     */
    public static List<String[]> describe(Nwps.Gauge g, UnitSystem system, long now) {
        final List<String[]> out = new ArrayList<>();
        final Nwps.Reading o = g.observed;
        row(out, "Status", o.category.isEmpty() && !o.any() ? "No observation"
                : Nwps.label(o.category));
        row(out, "Stage", stage(o.stage, system));
        row(out, "Flow", flow(o.flow, system));
        row(out, "Observed", when(o.at, now));
        final Nwps.Reading f = g.forecast;
        if (f.any()) {
            final StringBuilder b = new StringBuilder();
            append(b, stage(f.stage, system));
            append(b, flow(f.flow, system));
            if (!f.category.isEmpty())
                append(b, Nwps.label(f.category));
            if (f.at > 0)
                append(b, "at " + clock(f.at));
            row(out, "Forecast", b.toString());
        } else if (Nwps.FCST_NOT_CURRENT.equals(f.category)) {
            row(out, "Forecast", "Not current");
        }
        row(out, "Forecast office", g.wfo);
        row(out, "River forecast center", g.rfc);
        row(out, "State", g.state);
        row(out, "Gauge id", g.lid);
        row(out, "Hydrograph", "https://water.noaa.gov/gauges/" + g.lid);
        row(out, "Position", String.format(Locale.US, "%.5f, %.5f", g.latitude, g.longitude));
        return out;
    }

    private static void row(List<String[]> out, String label, String value) {
        if (value != null && !value.isEmpty())
            out.add(new String[] { label, value });
    }

    private static void append(StringBuilder b, String s) {
        if (s == null || s.isEmpty())
            return;
        if (b.length() > 0)
            b.append("  ·  ");
        b.append(s);
    }

    /** A stage to the hundredth, in feet or meters: the gauge reads to that. */
    public static String stage(double feet, UnitSystem system) {
        if (Double.isNaN(feet))
            return "";
        if (system == UnitSystem.METRIC)
            return String.format(Locale.US, "%.2f m", feet * 0.3048);
        return String.format(Locale.US, "%.2f ft", feet);
    }

    /** A flow in whole cubic feet per second, or cubic meters. */
    public static String flow(double kcfs, UnitSystem system) {
        if (Double.isNaN(kcfs))
            return "";
        if (system == UnitSystem.METRIC)
            return String.format(Locale.US, "%,.0f m³/s", kcfs * 28.316847);
        return String.format(Locale.US, "%,.0f cfs", kcfs * 1000.0);
    }

    private static String when(long at, long now) {
        if (at <= 0)
            return "";
        final double hours = (now - at) / 3_600_000.0;
        final String ago;
        if (hours < 1.5)
            ago = Math.max(1, Math.round(hours * 60)) + " minutes ago";
        else if (hours < 48)
            ago = Math.round(hours) + " hours ago";
        else
            ago = Math.round(hours / 24) + " days ago";
        return ago + ", " + clock(at);
    }

    private static String clock(long at) {
        return new java.text.SimpleDateFormat("EEE MMM d, h:mm a", Locale.US)
                .format(new java.util.Date(at));
    }

    private AttributeSet attrs(Nwps.Gauge g, long now, UnitSystem system) {
        final AttributeSet a = new AttributeSet();
        put(a, "Gauge", g.name);
        final StringBuilder text = new StringBuilder();
        for (String[] r : describe(g, system, now)) {
            put(a, r[0], r[1]);
            if (text.length() > 0)
                text.append('\n');
            text.append(r[0]).append(": ").append(r[1]);
        }
        // The details pane sorts what it is given; this keeps the order above.
        put(a, "_details", text.toString());
        return a;
    }

    private static void put(AttributeSet a, String key, String value) {
        if (value != null && !value.trim().isEmpty())
            a.setAttribute(key, value.trim());
    }

    private static UnitSystem units() {
        final SharedPreferences p = MapCompat.prefs();
        return UnitSystem.fromName(p == null ? null : p.getString(PREF_UNITS, null),
                UnitSystem.IMPERIAL);
    }

    /**
     * Ask again when the map has been moved somewhere else: only when the box is
     * around the map, and only past a third of the radius.
     */
    private void followMapCenter() {
        if (fromMe || inFlight)
            return;
        final GeoPoint now = origin();
        if (now == null)
            return;
        if (fetchedFrom != null
                && GeoCalculations.distanceTo(fetchedFrom, now) / 1609.344 < miles / 3.0)
            return;
        refresh(true);
    }

    private final Runnable settled = new Runnable() {
        @Override
        public void run() {
            if (!on)
                return;
            followMapCenter();
            final Listener l = listener;
            if (l != null)
                l.onOriginMoved();
        }
    };

    private final com.atakmap.map.AtakMapView.OnMapMovedListener moved =
            new com.atakmap.map.AtakMapView.OnMapMovedListener() {
                @Override
                public void onMapMoved(com.atakmap.map.AtakMapView v, boolean animate) {
                    // GL thread. Nothing is touched here but the callback queue.
                    mapView.removeCallbacks(settled);
                    mapView.postDelayed(settled, SETTLE_MS);
                }
            };

    private void clearOffMain() {
        worker.execute(new Runnable() {
            @Override
            public void run() {
                features.clear();
            }
        });
    }

    private void status(String message) {
        final Listener l = listener;
        if (l != null)
            l.onGaugesStatus(message);
    }

    private void drawn(int n, int total, int flooding) {
        final Listener l = listener;
        if (l != null)
            l.onGaugesDrawn(n, total, flooding);
    }
}
