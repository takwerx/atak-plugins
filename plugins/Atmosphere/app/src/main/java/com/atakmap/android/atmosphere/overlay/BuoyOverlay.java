package com.atakmap.android.atmosphere.overlay;

import android.content.Context;
import android.content.SharedPreferences;

import com.atakmap.android.atmosphere.compat.MapCompat;
import com.atakmap.android.atmosphere.compat.ScaleBar;
import com.atakmap.android.atmosphere.data.BuoyFavorites;
import com.atakmap.android.atmosphere.data.Ndbc;
import com.atakmap.android.atmosphere.net.EgressPolicy;
import com.atakmap.android.atmosphere.net.Http;
import com.atakmap.android.atmosphere.units.Quantity;
import com.atakmap.android.atmosphere.units.UnitSystem;
import com.atakmap.android.atmosphere.units.Units;
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
 * Buoys and coastal stations around the operator, from NDBC.
 *
 * <p>The observation file is national and has no query, so it is fetched whole
 * (100 KB) and cut to the box here: this is the one layer that sends nothing
 * about where the operator is. Names come from the station table, fetched once a
 * session. Scoped by distance with the station layer's controls.
 */
public final class BuoyOverlay {

    private static final String TAG = "AtmosphereBuoys";

    public static final String LAYER_ID = "buoys";
    public static final String HOST = Ndbc.HOST;
    private static final String NAME = "Buoys";

    private static final String PREF_ON = "weather.layer.buoys.on";
    private static final String PREF_MILES = "weather.layer.buoys.miles";
    private static final String PREF_FROM_ME = "weather.layer.buoys.fromme";
    private static final String PREF_UNITS = "weather.units";

    /** Buoys report hourly and the file is rebuilt about every ten minutes. */
    private static final long POLL_MS = 10 * 60 * 1000L;
    private static final long REFRESH_MS = 60 * 1000L;
    /** How long the map must sit still before a move is acted on. */
    private static final long SETTLE_MS = 600L;

    /** What the distance buttons offer, in miles. */
    public static final int[] RADII = { 25, 50, 100, 250 };
    private static final int DEFAULT_MILES = 100;

    /** NDBC's legend, in its words. */
    public static final String[][] LEGEND = {
            { "Recent data", String.valueOf(BuoyIcons.RECENT) },
            { "No data in the last 8 hours", String.valueOf(BuoyIcons.SILENT) } };
    /** The station table, fetched once and kept for the session. */
    private java.util.Map<String, Ndbc.Station> stations;
    /** The whole file's stations, so a scope change needs no request. */
    private List<Ndbc.Buoy> national = new ArrayList<>();

    /** Every station in the box. */
    public static final int SHOW_ALL = 0;
    /** The ones reporting wind. */
    public static final int SHOW_WIND = 1;
    /** The ones reporting seas. */
    public static final int SHOW_WAVES = 2;
    /** Only the starred ones. */
    public static final int SHOW_FAVORITES = 3;
    private static final String PREF_SHOW = "weather.layer.buoys.show";
    private static final String PREF_GATE = "weather.layer.buoys.gate";
    private static final String PREF_LABEL_GATE = "weather.layer.buoys.labelgate";
    private static final String PREF_LABELS = "weather.layer.buoys.labels";
    /** No gate: drawn at every zoom. */
    public static final double ALWAYS_GATE = Double.MAX_VALUE;
    /** The labels start at a scale bar of about thirty miles. */
    private static final double DEFAULT_LABEL_BIG = 30d;
    private static final double FINEST = 0d;

    public interface Listener {
        void onBuoysStatus(String message);

        void onBuoysDrawn(int drawn, int total, int flooding);

        /** The map moved: anything ordered by distance needs reordering. */
        void onOriginMoved();
    }

    private final MapView mapView;
    private final EgressPolicy egress;
    private final AtmosphereFeatures features;
    private final BuoyIcons icons;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();

    private Listener listener;
    private boolean started, on, inFlight;
    private int generation;
    private long lastPoll;
    private int miles;
    private boolean fromMe;
    private int show;
    private boolean labels;
    /** Coarsest meters per pixel at which each still draws. */
    private double gate, labelGate;
    /** Whether the pills are on the icons as drawn right now. */
    private boolean labelsWanted;
    private final BuoyFavorites favorites;
    private List<Ndbc.Buoy> buoys = new ArrayList<>();
    /** Where the buoys currently on the map were asked for. */
    private GeoPoint fetchedFrom;

    private final Runnable autoPoll = new Runnable() {
        @Override
        public void run() {
            refresh(true);
            mapView.postDelayed(this, POLL_MS);
        }
    };

    public BuoyOverlay(MapView mapView, Context pluginContext, EgressPolicy egress) {
        this.mapView = mapView;
        this.egress = egress;
        this.features = new AtmosphereFeatures(mapView, pluginContext, TAG, NAME,
                "buoys.sqlite", "buoys", false);
        this.icons = new BuoyIcons(
                pluginContext.getResources().getDisplayMetrics().density);
        final SharedPreferences p = MapCompat.prefs();
        miles = p == null ? DEFAULT_MILES : p.getInt(PREF_MILES, DEFAULT_MILES);
        fromMe = p == null || p.getBoolean(PREF_FROM_ME, true);
        show = p == null ? SHOW_ALL : p.getInt(PREF_SHOW, SHOW_ALL);
        labels = p == null || p.getBoolean(PREF_LABELS, true);
        gate = storedGate(p, PREF_GATE, ALWAYS_GATE);
        labelGate = storedGate(p, PREF_LABEL_GATE, gsdForBig(DEFAULT_LABEL_BIG));
        favorites = new BuoyFavorites(mapView.getContext());
    }

    public static boolean isAlways(double gate) {
        return !(gate < 1e12);
    }

    public double gate() {
        return gate;
    }

    public double labelGate() {
        return labelGate;
    }

    public void setGate(double metersPerPixel) {
        if (gate == metersPerPixel)
            return;
        gate = metersPerPixel;
        remember(PREF_GATE, metersPerPixel);
        redraw();
    }

    public void setLabelGate(double metersPerPixel) {
        if (labelGate == metersPerPixel)
            return;
        labelGate = metersPerPixel;
        remember(PREF_LABEL_GATE, metersPerPixel);
        applyLabelBand();
    }

    public boolean hasLabels() {
        return labels;
    }

    public void setLabels(boolean value) {
        if (labels == value)
            return;
        labels = value;
        final SharedPreferences p = MapCompat.prefs();
        if (p != null)
            p.edit().putBoolean(PREF_LABELS, value).apply();
        applyLabelBand();
    }

    private static void remember(String key, double metersPerPixel) {
        final SharedPreferences p = MapCompat.prefs();
        if (p != null)
            p.edit().putFloat(key, (float) Math.min(metersPerPixel, Float.MAX_VALUE)).apply();
    }

    private static double storedGate(SharedPreferences p, String key, double fallback) {
        if (p == null)
            return fallback;
        try {
            return p.getFloat(key, (float) Math.min(fallback, Float.MAX_VALUE));
        } catch (ClassCastException oldFormat) {
            p.edit().remove(key).apply();
            return fallback;
        }
    }

    /** A scale-bar distance as a map resolution, against this device's own bar. */
    public double gsdForBig(double big) {
        return ScaleBar.bigToMeters(big) / barPixels();
    }

    public double barPixels() {
        final double res = mapView.getMapResolution();
        if (res <= 0)
            return ScaleBar.FALLBACK_BAR_PIXELS;
        final double m = ScaleBar.meters(mapView);
        return m > 0 ? m / res : ScaleBar.FALLBACK_BAR_PIXELS;
    }

    public boolean drawingNow(double gate) {
        return gate >= mapView.getMapResolution();
    }

    public double resolution() {
        return mapView.getMapResolution();
    }

    /** Put the pills on or take them off, if the zoom has crossed the line. */
    private boolean applyLabelBand() {
        final boolean wanted = labels && mapView.getMapResolution() <= labelGate;
        if (wanted == labelsWanted)
            return false;
        labelsWanted = wanted;
        redraw();
        return true;
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
     * Whether a buoy passes the map's filter. Public so the list asks the same
     * question; a filter on one surface is a filter the other disagrees with.
     */
    public boolean passes(int show, Ndbc.Buoy g) {
        if (show == SHOW_FAVORITES)
            return favorites.contains(g.id);
        if (show == SHOW_WIND)
            return g.hasWind();
        if (show == SHOW_WAVES)
            return g.hasWaves();
        return true;
    }

    public boolean isFavorite(Ndbc.Buoy g) {
        return g != null && favorites.contains(g.id);
    }

    /** @return true if the buoy is starred afterwards */
    public boolean toggleFavorite(Ndbc.Buoy g) {
        final boolean now = favorites.toggle(g.id);
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

    /** The diamond's color for a buoy, for the list's swatch: NDBC's rule. */
    public static int colorFor(Ndbc.Buoy b) {
        return BuoyIcons.color(b.ageHours(System.currentTimeMillis()));
    }

    public void setListener(Listener l) {
        listener = l;
    }

    public void start() {
        started = true;
        features.attach();
        labelsWanted = labels && mapView.getMapResolution() <= labelGate;
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
            // Everything, every time the layer comes on: a filter left on from
            // last time is a map with holes in it and no sign why (operator,
            // 2026-09-26, "when you turn it on it should default to All").
            if (show != SHOW_ALL) {
                show = SHOW_ALL;
                if (p != null)
                    p.edit().putInt(PREF_SHOW, SHOW_ALL).apply();
            }
            refresh(true);
            mapView.postDelayed(autoPoll, POLL_MS);
        } else {
            buoys = new ArrayList<>();
            fetchedFrom = null;
            clearOffMain();
            status("");
            drawn(0, 0, 0);
        }
    }

    /** How far out buoys are asked for, in miles. */
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

    /** The buoys currently held, for a list that must agree with the map. */
    public List<Ndbc.Buoy> buoys() {
        return new ArrayList<>(buoys);
    }

    /** Where distances are measured from right now: the operator, or the map. */
    public GeoPoint originPoint() {
        return origin();
    }

    private GeoPoint origin() {
        final GeoPoint p = fromMe ? MapCompat.selfPoint() : MapCompat.mapCenter();
        return p != null && p.isValid() ? p : null;
    }

    /** Fetch the national file and cut it to the box; the table first, once. */
    public void refresh(boolean force) {
        if (!on || !started || inFlight)
            return;
        final long now = System.currentTimeMillis();
        if (!force && now - lastPoll < REFRESH_MS)
            return;
        final GeoPoint from = origin();
        if (from == null) {
            status(fromMe ? "No position yet \u2014 switch to the map, or wait for a fix"
                    : "The map has no center yet");
            return;
        }
        lastPoll = now;
        inFlight = true;
        fetchedFrom = from;
        final int mine = generation;
        if (buoys.isEmpty())
            status("Getting buoys\u2026");
        // The file is national: a new origin within the poll is a cut, not a request.
        if (!national.isEmpty() && now - lastFetched < POLL_MS) {
            worker.execute(new Runnable() {
                @Override
                public void run() {
                    cut(from, mine);
                }
            });
            return;
        }
        final Map<String, String> headers = new HashMap<>();
        Http.get(Ndbc.OBS_URL, egress.userAgent(), headers, new Http.Callback() {
            @Override
            public void onSuccess(final String body) {
                if (mine != generation || !on) {
                    inFlight = false;
                    return;
                }
                worker.execute(new Runnable() {
                    @Override
                    public void run() {
                        final List<Ndbc.Buoy> all = Ndbc.parseObs(body);
                        if (stations != null)
                            Ndbc.name(all, stations);
                        national = all;
                        lastFetched = System.currentTimeMillis();
                        if (stations == null)
                            fetchStations(from, mine);
                        else
                            cut(from, mine);
                    }
                });
            }

            @Override
            public void onFailure(final String error) {
                inFlight = false;
                if (mine != generation || !on)
                    return;
                Log.w(TAG, "buoy fetch failed: " + error);
                status("Could not reach NDBC");
            }
        });
    }

    private long lastFetched;

    /** The station table, once a session; the layer draws with ids until it lands. */
    private void fetchStations(final GeoPoint from, final int mine) {
        Http.get(Ndbc.STATIONS_URL, egress.userAgent(), new HashMap<String, String>(),
                new Http.Callback() {
                    @Override
                    public void onSuccess(final String body) {
                        worker.execute(new Runnable() {
                            @Override
                            public void run() {
                                stations = Ndbc.parseStations(body);
                                Ndbc.name(national, stations);
                                cut(from, mine);
                            }
                        });
                    }

                    @Override
                    public void onFailure(final String error) {
                        Log.w(TAG, "station table: " + error);
                        worker.execute(new Runnable() {
                            @Override
                            public void run() {
                                cut(from, mine);
                            }
                        });
                    }
                });
    }

    /** Worker: the box out of the national list, then draw. */
    private void cut(GeoPoint from, int mine) {
        buoys = Ndbc.within(national, from.getLatitude(), from.getLongitude(), miles);
        inFlight = false;
        rebuild(mine);
    }

    /** Draw what is held. Worker thread only: it composes icons and writes the store. */
    private void rebuild(int mine) {
        if (mine != generation || !on)
            return;
        final long began = android.os.SystemClock.elapsedRealtime();
        final List<Ndbc.Buoy> held = buoys;
        final long now = System.currentTimeMillis();
        final UnitSystem system = units();
        final boolean withLabels = labelsWanted;
        final double[] view = viewBounds();
        {
            final java.util.Set<String> labeled = new java.util.HashSet<>();
            if (withLabels)
                for (Ndbc.Buoy b : held)
                    if (onScreen(b, view))
                        labeled.add(b.id);
            lastLabeled = labeled;
        }
        // A feature set's coarsest resolution has to be a real number.
        final double maxGsd = isAlways(gate) ? 100_000d : gate;
        final List<AtmosphereFeatures.Drawn> drawn = new ArrayList<>();
        int windy = 0;
        for (Ndbc.Buoy g : held) {
            if (!passes(show, g))
                continue;
            final double age = g.ageHours(now);
            final int color = BuoyIcons.color(age);
            final boolean recent = age <= BuoyIcons.SILENT_HOURS;
            if (recent && g.hasWind())
                windy++;
            final String set = !recent ? "No data in 8 hours" : g.hasWind() ? "Wind reported" : "Seas only";
            final AtmosphereFeatures.Drawn d;
            if (withLabels && onScreen(g, view)) {
                final BuoyIcons.Composed c = icons.labeled(color, pillReading(g, system), g.label());
                if (c == null)
                    continue;
                d = new AtmosphereFeatures.Drawn(set, g.label(),
                        AtmosphereFeatures.point(g.latitude, g.longitude),
                        AtmosphereFeatures.icon(c.uri, c.width, c.height, c.offsetX, c.offsetY),
                        attrs(g, now, system), maxGsd, FINEST);
            } else {
                final String uri = icons.uri(color);
                if (uri == null)
                    continue;
                d = new AtmosphereFeatures.Drawn(set, g.label(),
                        AtmosphereFeatures.point(g.latitude, g.longitude),
                        AtmosphereFeatures.icon(uri, BuoyIcons.SIZE_DP, BuoyIcons.SIZE_DP),
                        attrs(g, now, system), maxGsd, FINEST);
            }
            drawn.add(d);
        }
        final int flooding = windy;
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
                "drew %d of %d buoys within %d mi of %s, %d reporting wind, took %d ms",
                n, total, miles, fromMe ? "me" : "the map", high,
                android.os.SystemClock.elapsedRealtime() - began));
    }

    /**
     * The record a tap shows: the wind, then the seas, then the air and the water,
     * each only when the station reports it.
     */
    public static List<String[]> describe(Ndbc.Buoy g, UnitSystem system, long now) {
        final List<String[]> out = new ArrayList<>();
        row(out, "Wind", wind(g, system));
        row(out, "Gust", Double.isNaN(g.gustMs) ? "" : Units.format(Quantity.SPEED, g.gustMs, system));
        row(out, "Seas", Double.isNaN(g.waveHeightM) ? "" : height(g.waveHeightM, system)
                + (Double.isNaN(g.dominantPeriodS) ? "" : " every " + Math.round(g.dominantPeriodS) + " s")
                + direction(g.waveFromDeg));
        row(out, "Average period", Double.isNaN(g.averagePeriodS) ? "" : Math.round(g.averagePeriodS) + " s");
        row(out, "Air temperature", temp(g.airTempC, system));
        row(out, "Water temperature", temp(g.waterTempC, system));
        row(out, "Humidity", Double.isNaN(g.relativeHumidity()) ? ""
                : Math.round(g.relativeHumidity()) + "% (from dewpoint " + temp(g.dewpointC, system) + ")");
        row(out, "Pressure", Double.isNaN(g.pressureHpa) ? "" : Units.format(Quantity.PRESSURE, g.pressureHpa, system)
                + (Double.isNaN(g.pressureTendencyHpa) ? "" : String.format(Locale.US, ", %+.1f hPa in 3 h", g.pressureTendencyHpa)));
        row(out, "Visibility", Double.isNaN(g.visibilityNmi) ? "" : String.format(Locale.US, "%.1f nmi", g.visibilityNmi));
        row(out, "Tide", Double.isNaN(g.tideFt) ? "" : String.format(Locale.US, "%.1f ft above MLLW", g.tideFt));
        row(out, "Observed", when(g.observedAt, now));
        row(out, "Station type", g.type);
        row(out, "Owner", g.owner);
        row(out, "Station id", g.id);
        row(out, "Station page", "https://www.ndbc.noaa.gov/station_page.php?station=" + g.id.toLowerCase(Locale.US));
        row(out, "Position", String.format(Locale.US, "%.3f, %.3f", g.latitude, g.longitude));
        return out;
    }

    private static String wind(Ndbc.Buoy g, UnitSystem system) {
        if (Double.isNaN(g.windMs))
            return "";
        return Units.format(Quantity.SPEED, g.windMs, system) + direction(g.windFromDeg);
    }

    private static String direction(double deg) {
        if (Double.isNaN(deg))
            return "";
        return String.format(Locale.US, " from %d\u00b0 (%s)", Math.round(deg), Units.degreesToCompass(deg));
    }

    /** Seas in feet or meters, to a tenth. */
    static String height(double meters, UnitSystem system) {
        if (Double.isNaN(meters))
            return "";
        if (system == UnitSystem.METRIC)
            return String.format(Locale.US, "%.1f m", meters);
        return String.format(Locale.US, "%.1f ft", meters / 0.3048);
    }

    private static String temp(double c, UnitSystem system) {
        return Double.isNaN(c) ? "" : Units.format(Quantity.TEMPERATURE, c, system);
    }

    /** "12G15 kt · 3.9 ft @ 13 s", whichever halves the station has. */
    static String pillReading(Ndbc.Buoy g, UnitSystem system) {
        final StringBuilder b = new StringBuilder();
        if (!Double.isNaN(g.windMs)) {
            final long w = Math.round(Units.toDisplay(Quantity.SPEED, g.windMs, system));
            b.append(w);
            if (!Double.isNaN(g.gustMs)) {
                final long gu = Math.round(Units.toDisplay(Quantity.SPEED, g.gustMs, system));
                if (gu > w)
                    b.append('G').append(gu);
            }
            b.append(' ').append(Units.displayUnit(Quantity.SPEED, system));
            if (!Double.isNaN(g.windFromDeg))
                b.append(' ').append(Units.degreesToCompass(g.windFromDeg));
        }
        if (!Double.isNaN(g.waveHeightM)) {
            append(b, height(g.waveHeightM, system)
                    + (Double.isNaN(g.dominantPeriodS) ? "" : " @ " + Math.round(g.dominantPeriodS) + " s"));
        }
        if (b.length() == 0)
            append(b, Double.isNaN(g.airTempC) ? "No current reading" : temp(g.airTempC, system));
        return b.toString();
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

    private AttributeSet attrs(Ndbc.Buoy g, long now, UnitSystem system) {
        final AttributeSet a = new AttributeSet();
        put(a, "Buoy", g.label());
        // Plumbing: the details button routes a buoy to its page.
        put(a, "_buoyId", g.id);
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


    /** The ids that carried a label at the last rebuild, so a pan that changes none of them is not a rewrite. */
    private java.util.Set<String> lastLabeled = new java.util.HashSet<>();

    /**
     * Whether the set of items that would carry a label differs from the last
     * rebuild. A tap makes ATAK nudge the map, the settle fires, and a redraw
     * rewrote the store under the radial before it opened -- the operator's
     * "I click and nothing happens" (2026-09-26). A pan that leaves the same
     * items on screen is not a reason to rewrite.
     */
    private boolean labeledSetChanged() {
        final double[] view = viewBounds();
        final java.util.Set<String> now = new java.util.HashSet<>();
        for (Ndbc.Buoy b : buoys)
            if (onScreen(b, view))
                now.add(b.id);
        return !now.equals(lastLabeled);
    }

    private double[] viewBounds() {
        try {
            final com.atakmap.coremap.maps.coords.GeoBounds b = mapView.getBounds();
            if (b == null)
                return null;
            final double s = b.getSouth(), w = b.getWest(), n = b.getNorth(), e = b.getEast();
            if (Double.isNaN(s) || Double.isNaN(w) || Double.isNaN(n) || Double.isNaN(e))
                return null;
            final double padLat = Math.abs(n - s) / 2.0, padLon = Math.abs(e - w) / 2.0;
            return new double[] { s - padLat, w - padLon, n + padLat, e + padLon };
        } catch (Exception noBounds) {
            return null;
        }
    }

    private static boolean onScreen(Ndbc.Buoy g, double[] view) {
        if (view == null)
            return true;
        return g.latitude >= view[0] && g.latitude <= view[2]
                && g.longitude >= view[1] && g.longitude <= view[3];
    }

    private final Runnable settled = new Runnable() {
        @Override
        public void run() {
            if (!on)
                return;
            final boolean crossed = applyLabelBand();
            followMapCenter();
            // Which buoys are on screen decides which carry a label; once.
            if (labelsWanted && !crossed && labeledSetChanged())
                redraw();
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
            l.onBuoysStatus(message);
    }

    private void drawn(int n, int total, int flooding) {
        final Listener l = listener;
        if (l != null)
            l.onBuoysDrawn(n, total, flooding);
    }
}
