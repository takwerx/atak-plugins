package com.atakmap.android.atmosphere.overlay;

import android.content.Context;
import android.content.SharedPreferences;

import com.atakmap.android.atmosphere.data.RedFlag;
import com.atakmap.android.atmosphere.data.Raws;
import com.atakmap.android.atmosphere.net.EgressPolicy;
import com.atakmap.android.atmosphere.net.Http;
import com.atakmap.android.atmosphere.units.Quantity;
import com.atakmap.android.atmosphere.units.UnitSystem;
import com.atakmap.android.atmosphere.units.Units;
import com.atakmap.android.atmosphere.compat.MapCompat;
import com.atakmap.android.atmosphere.compat.ScaleBar;
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
 * Fire weather stations near the operator, drawn the way a snooper draws them.
 *
 * <p>Modeled on NWS Los Angeles's Fire Weather Snooper, which a fire weather crew
 * already reads: every station in an area, each showing where the wind is from and the
 * two numbers that decide a burn -- wind speed and relative humidity -- with the ones
 * at or near Red Flag conditions standing out.
 *
 * <p>The stations come from NIFC's public RAWS view, which carries the latest
 * observation with each station, so the whole layer is one request. See {@link Raws}
 * for why that feed and not api.weather.gov.
 *
 * <p><b>Scoped by distance, not by the whole country.</b> A spot forecast list is worth
 * holding nationally because a crew searches it by name; a wall of station readings is
 * not. This asks for a radius around either the operator or the middle of the map, the
 * way the Snooper asks around an office, and the server does the distance.
 */
public final class StationOverlay {

    private static final String TAG = "AtmosphereStations";

    public static final String LAYER_ID = "stations";
    public static final String HOST = Raws.HOST;
    private static final String NAME = "Weather stations";

    /** The three states, so the pane's legend and the map can never disagree. */
    public static final int NORMAL = StationIcons.NORMAL;
    public static final int NEAR = StationIcons.NEAR;
    public static final int CRITICAL = StationIcons.CRITICAL;

    private static final String PREF_ON = "weather.layer.stations.on";
    private static final String PREF_MILES = "weather.layer.stations.miles";
    private static final String PREF_FROM_ME = "weather.layer.stations.fromme";
    private static final String PREF_LABELS = "weather.layer.stations.labels";
    private static final String PREF_SHOW = "weather.layer.stations.show";

    /** Every station the feed gives back. */
    public static final int SHOW_ALL = 0;
    /** Only the ones with a criterion already met, either one. */
    public static final int SHOW_WATCH = 1;
    /** Only the ones at Red Flag: both criteria at once. */
    public static final int SHOW_RED = 2;
    /**
     * New keys on purpose.
     *
     * <p>The old ones hold whatever the broken builds of 2026-09-25 wrote: an int
     * from one build, an infinity from the next. Nothing in them was a value the
     * operator chose, so they are abandoned rather than migrated, and the defaults
     * get their first honest chance.
     */
    private static final String PREF_GATE_STATIONS = "weather.layer.stations.gate2";
    private static final String PREF_GATE_LABELS = "weather.layer.stations.labelgate2";
    private static final String PREF_UNITS = "weather.units";

    /** RAWS report hourly, so there is nothing to gain from asking more often. */
    private static final long POLL_MS = 10 * 60 * 1000L;
    private static final long REFRESH_MS = 60 * 1000L;

    /** What the distance buttons offer, in miles. */
    public static final int[] RADII = { 25, 50, 100, 250 };
    private static final int DEFAULT_MILES = 50;

    /**
     * The zoom bands, in meters per pixel, coarsest first.
     *
     * <p>ATAK gates a feature set by resolution itself, so these need no listener and
     * cost nothing to hold: a set outside its band is simply not drawn. Two bands that
     * meet at {@link #LABEL_GSD} mean exactly one of them is on screen at any zoom --
     * bare symbols when the view is wide, the same symbols carrying their readings and
     * name once it is close enough for them to be worth the space. This is how Feature
     * Layer gates its own points, and the reason it is done with resolutions rather
     * than by redrawing on every zoom is that redrawing is a database write.
     *
     * <p>Past {@link #STATION_GSD} nothing is drawn at all: at a state-wide view a
     * hundred stations is a wall of symbols with no map left under it.
     */
    private static final double ALWAYS = 100_000d;
    private static final double FINEST = 0d;

    /**
     * What the gate rows offer, as <b>how wide the map is on screen</b>, in miles.
     * Zero is "always".
     *
     * <p>Said that way because that is the number already on the operator's screen:
     * the scale bar. A gate in meters per pixel is the same fact in a unit nobody
     * reads off a map. The resolution is worked out from the live map width, so the
     * label on the button is true on whatever screen it is running on.
     */
    /** No gate: drawn at every zoom. */
    public static final double ALWAYS_GATE = Double.MAX_VALUE;

    /**
     * Whether a gate means "always", tested by size rather than by equality.
     *
     * <p>A preference holds a float. {@code (float) Double.MAX_VALUE} is infinity, and
     * what comes back is not equal to what went in, so an equality test says the gate
     * is a real distance and the control renders it: the operator's screen read
     * "Stations drawn at 9,223,372,036,854,775,807 mi or closer" (2026-09-25). Any
     * number this big is the same answer, so the test is a threshold, and NaN -- which
     * is what a corrupted preference reads as -- falls on the same side by writing it
     * as a negated less-than.
     */
    public static boolean isAlways(double gate) {
        return !(gate < 1e12);
    }
    /** The readings start at a scale bar of about thirty miles. */
    private static final double DEFAULT_LABEL_BIG = 30d;

    /** How long the map must sit still before a band change is acted on. */
    private static final long SETTLE_MS = 400L;

    public interface Listener {
        void onStationsStatus(String message);

        void onStationsDrawn(int drawn, int total, int critical);

        /** The map moved: anything ordered by distance needs reordering. */
        void onOriginMoved();
    }

    private final MapView mapView;
    private final Context pluginContext;
    private final EgressPolicy egress;
    private final AtmosphereFeatures features;
    private final StationIcons icons = new StationIcons();
    private final ExecutorService worker = Executors.newSingleThreadExecutor();

    private Listener listener;
    private boolean started, on, inFlight;
    private int generation;
    private long lastPoll;
    private int miles;
    private boolean fromMe, labels;
    private int show;
    /** Coarsest meters per pixel at which each still draws. */
    private double stationGate, labelGate;
    /** Whether the pills are on the icons as drawn right now. */
    private boolean labelsWanted;
    private List<Raws.Station> stations = new ArrayList<>();
    /** Where the stations currently on the map were asked for. */
    private GeoPoint fetchedFrom;

    private final Runnable autoPoll = new Runnable() {
        @Override
        public void run() {
            refresh(true);
            mapView.postDelayed(this, POLL_MS);
        }
    };

    public StationOverlay(MapView mapView, Context pluginContext, EgressPolicy egress) {
        this.mapView = mapView;
        this.pluginContext = pluginContext;
        this.egress = egress;
        this.features = new AtmosphereFeatures(mapView, pluginContext, TAG, NAME,
                "stations.sqlite", "stations", false);
        final SharedPreferences p = MapCompat.prefs();
        miles = p == null ? DEFAULT_MILES : p.getInt(PREF_MILES, DEFAULT_MILES);
        fromMe = p == null || p.getBoolean(PREF_FROM_ME, true);
        labels = p == null || p.getBoolean(PREF_LABELS, true);
        show = p == null ? SHOW_ALL : p.getInt(PREF_SHOW, SHOW_ALL);
        stationGate = storedGate(p, PREF_GATE_STATIONS, ALWAYS_GATE);
        // Worked out against this device's own scale bar, not a nominal width. A
        // threshold derived from an assumed 200 pixel bar is wrong by whatever the
        // real bar differs by -- here that is most of a factor of two, so a gate
        // labelled "30 mi or closer" was still drawing at 53 (operator, 2026-09-25).
        labelGate = storedGate(p, PREF_GATE_LABELS, gsdForBig(DEFAULT_LABEL_BIG));
    }

    /** How wide the map may be, in miles, and still draw the stations. 0 is always. */
    /** Coarsest meters per pixel at which the stations still draw. */
    public double stationGate() {
        return stationGate;
    }

    /** The same for the readings and names beside them. */
    public double labelGate() {
        return labelGate;
    }

    /**
     * Set the gate to whatever the operator is looking at.
     *
     * <p>Set by example rather than by a number: there is no scale to interpret and
     * nothing to convert. Zoom to where you want these to appear, press the button,
     * and that view is the threshold. Cam Depot's control, and the reason for it.
     */
    public void setStationGateToThisView() {
        setStationGate(mapView.getMapResolution());
    }

    public void setLabelGateToThisView() {
        setLabelGate(mapView.getMapResolution());
    }

    public void setStationGate(double metersPerPixel) {
        if (stationGate == metersPerPixel)
            return;
        stationGate = metersPerPixel;
        remember(PREF_GATE_STATIONS, metersPerPixel);
        redraw();                   // the gate lives on the feature set: rewrite it
    }

    public void setLabelGate(double metersPerPixel) {
        if (labelGate == metersPerPixel)
            return;
        labelGate = metersPerPixel;
        remember(PREF_GATE_LABELS, metersPerPixel);
        applyLabelBand();
    }

    private static void remember(String key, double metersPerPixel) {
        final SharedPreferences p = MapCompat.prefs();
        if (p != null)
            p.edit().putFloat(key,
                    (float) Math.min(metersPerPixel, Float.MAX_VALUE)).apply();
    }

    /**
     * A stored gate, or the default.
     *
     * <p>Guarded because earlier builds wrote this key as an int, and asking
     * SharedPreferences for a float where an int is stored throws -- in this
     * constructor, which would take the whole layer down on the next load for anyone
     * who had ever pressed one of the old buttons.
     */
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

    /**
     * A distance on the scale bar, as a map resolution, measured against the bar this
     * device is actually drawing.
     *
     * <p>The bar's pixel width is the whole conversion, and it is not 200 everywhere.
     * Using a nominal figure makes every preset and every default a promise the map
     * does not keep: the number on the button and the zoom it takes effect at are
     * different by whatever the real bar differs by.
     */
    public double gsdForBig(double big) {
        return ScaleBar.bigToMeters(big) / barPixels();
    }

    /** How many pixels ATAK's scale bar spans right now. */
    public double barPixels() {
        final double res = mapView.getMapResolution();
        if (res <= 0)
            return ScaleBar.FALLBACK_BAR_PIXELS;
        final double m = ScaleBar.meters(mapView);
        return m > 0 ? m / res : ScaleBar.FALLBACK_BAR_PIXELS;
    }

    /** Whether something gated at this resolution is on screen at the moment. */
    public boolean drawingNow(double gate) {
        return gate >= mapView.getMapResolution();
    }

    /** The map's current resolution, for describing a gate in the operator's terms. */
    public double resolution() {
        return mapView.getMapResolution();
    }

    /** Which stations are drawn: all of them, the ones worth watching, or Red Flag. */
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
     * Whether a station passes the map's own filter.
     *
     * <p>Public and static so the list can ask the same question. A filter that lives
     * on one surface is a filter the other one disagrees with.
     */
    public static boolean passes(int show, Raws.Station s) {
        final int state = stateOf(s);
        if (show == SHOW_RED)
            return state == RedFlag.CRITICAL;
        if (show == SHOW_WATCH)
            return state != RedFlag.BELOW;
        return true;
    }

    /** Whether the readings and name are drawn beside each station. */
    public boolean hasLabels() {
        return labels;
    }

    /** Switch them; redrawn from what is already held, no new request. */
    public void setLabels(boolean value) {
        if (labels == value)
            return;
        labels = value;
        final SharedPreferences p = MapCompat.prefs();
        if (p != null)
            p.edit().putBoolean(PREF_LABELS, value).apply();
        applyLabelBand();
    }

    /**
     * Put the pills on or take them off, if the zoom has crossed the line.
     *
     * <p>Two feature sets in complementary zoom bands would be the cheap way to do
     * this -- ATAK would gate them itself and nothing would have to watch the map.
     * It was written that way and it put <b>two features on every station</b>: the
     * resolution gate decides what is <i>drawn</i>, not what is <i>hit</i>, so a tap
     * found both copies and ATAK offered a Select Item chooser with the same station
     * twice (operator, 2026-09-25: "i clicked on a station, two things are coming
     * up"). One station is one feature, so the label has to be baked in or not, and
     * the zoom decides which.
     */
    private void applyLabelBand() {
        final boolean wanted = labels
                && mapView.getMapResolution() <= labelGate;
        if (wanted == labelsWanted)
            return;
        labelsWanted = wanted;
        redraw();
    }

    /** Coalesced: a pinch is hundreds of callbacks and each redraw is a DB write. */
    private final Runnable bandSettled = new Runnable() {
        @Override
        public void run() {
            if (!on)
                return;
            applyLabelBand();
            followMapCenter();
            // Even when nothing is refetched, anything ordered by distance from the
            // map is now in the wrong order.
            final Listener l = listener;
            if (l != null)
                l.onOriginMoved();
        }
    };

    /**
     * Ask again when the map has been moved somewhere else.
     *
     * <p>Scoped to the map center, the layer fetched once and then never again until
     * the ten minute poll: the origin moved under it and nothing asked. Panning to a
     * fire two counties away left southern California's stations sitting on the map
     * (operator, 2026-09-25: "seems locked to so cal").
     *
     * <p>Only past a third of the radius, because the answer barely changes before
     * that and each one is a request and a full redraw. Measured from where the
     * stations on screen were actually fetched, not from the last check, so a slow
     * drag still triggers exactly once when it has gone far enough.
     */
    private void followMapCenter() {
        if (fromMe || inFlight)
            return;
        final GeoPoint now = origin();
        if (now == null)
            return;
        if (fetchedFrom != null) {
            final double movedMiles = GeoCalculations.distanceTo(fetchedFrom, now) / 1609.344;
            if (movedMiles < miles / 3.0)
                return;
        }
        refresh(true);
    }

    private final com.atakmap.map.AtakMapView.OnMapMovedListener moved =
            new com.atakmap.map.AtakMapView.OnMapMovedListener() {
                @Override
                public void onMapMoved(com.atakmap.map.AtakMapView v, boolean animate) {
                    // GL thread. Nothing is touched here but the callback queue.
                    mapView.removeCallbacks(bandSettled);
                    mapView.postDelayed(bandSettled, SETTLE_MS);
                }
            };

    /** Rebuild the map from the stations already held. */
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
        mapView.removeCallbacks(bandSettled);
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
            stations = new ArrayList<>();
            clearOffMain();
            status("");
            drawn(0, 0, 0);
        }
    }

    /** How far out stations are asked for, in miles. */
    public int miles() {
        return miles;
    }

    /** Whether the radius is measured from the operator or from the middle of the map. */
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
        // A different radius is a different question for the server, not a redraw.
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

    /**
     * The stations currently held, whatever the map is showing.
     *
     * <p>Handed out rather than fetched again by whoever wants a list: a list and a
     * map built from two different answers disagree, and the operator finds it.
     */
    public List<Raws.Station> stations() {
        return new ArrayList<>(stations);
    }

    /** What a station's state is, by the same rule the map colors it with. */
    public static int stateOf(Raws.Station s) {
        return RedFlag.state(s.relativeHumidity, s.strongestMph());
    }

    /** Where distances are measured from right now: the operator, or the map. */
    public GeoPoint originPoint() {
        return origin();
    }

    /** Where the radius is measured from, or null when there is no such point yet. */
    private GeoPoint origin() {
        final GeoPoint p = fromMe ? MapCompat.selfPoint() : MapCompat.mapCenter();
        return p != null && p.isValid() ? p : null;
    }

    /** Ask NIFC for the stations around the origin and what they are reading. */
    public void refresh(boolean force) {
        if (!on || !started || inFlight)
            return;
        final long now = System.currentTimeMillis();
        if (!force && now - lastPoll < REFRESH_MS)
            return;
        final GeoPoint from = origin();
        if (from == null) {
            // Said plainly, because the fix is the operator's: they can switch the
            // radius to the map instead of waiting for a fix that may not come indoors.
            status(fromMe ? "No position yet — switch to the map, or wait for a fix"
                    : "The map has no center yet");
            return;
        }
        lastPoll = now;
        inFlight = true;
        fetchedFrom = from;
        final int mine = generation;
        if (stations.isEmpty())
            status("Getting stations…");
        final Map<String, String> headers = new HashMap<>();
        headers.put("Accept", "application/json");
        // Rounded the way every other outbound coordinate in this plugin is rounded.
        // This layer is the one that sends the operator's own position -- the others
        // send a map extent or nothing -- so the precision setting has to reach it,
        // and a radius of tens of miles loses nothing to a coarser origin.
        final String url = Raws.nearUrl(Double.parseDouble(egress.latitude(from)),
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
                        final List<Raws.Station> all = Raws.parse(body);
                        stations = all;
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
                Log.w(TAG, "station fetch failed: " + error);
                mapView.post(new Runnable() {
                    @Override
                    public void run() {
                        if (mine == generation && on)
                            status("Could not reach the station list");
                    }
                });
            }
        });
    }

    /**
     * Draw what is held. <b>Worker thread only</b> -- every feature is a SQLite insert
     * and every icon is a PNG encode and a file write, which is not work for the thread
     * that draws the map. See {@link AtmosphereFeatures#rewrite}.
     */
    private void rebuild(int mine) {
        if (mine != generation || !on)
            return;
        final List<Raws.Station> held = stations;
        final long now = System.currentTimeMillis();
        final UnitSystem system = units();
        final boolean withLabels = labelsWanted;
        // A feature set's coarsest resolution has to be a real number: MAX_VALUE has
        // no level of detail to become, and the hit test then never reaches the layer.
        final double gate = isAlways(stationGate) ? 100_000d : stationGate;
        final List<AtmosphereFeatures.Drawn> drawn = new ArrayList<>();
        int critical = 0;
        for (Raws.Station s : held) {
            // A station with nothing to say is left off rather than drawn as a reading
            // of nothing, and one that stopped reporting days ago is not current
            // weather however much it looks like it.
            if (s.silent() || s.stale(now))
                continue;
            if (!passes(show, s))
                continue;
            final int level = RedFlag.state(s.relativeHumidity, s.strongestMph());
            final int color = color(level);
            if (level == RedFlag.CRITICAL)
                critical++;
            // The pill gets the operator's unit; the feathers get knots, which is
            // what a barb has always counted in.
            // The feathers count the sustained wind, which is what a barb shows; the
            // color is decided by the strongest wind the station has, gust included,
            // so the gust is printed beside it or the color has nothing behind it.
            final AttributeSet a = attrs(s, color, now, system);
            // The set is the state, so Overlay Manager can show the stations at
            // criteria on their own and ATAK's own switches work on one at a time.
            add(drawn, s, StationIcons.stateLabel(color), a, color, system, withLabels,
                    gate, FINEST);
        }
        if (mine != generation || !on)
            return;
        features.rewrite(drawn);
        final int n = drawn.size();
        final int total = held.size();
        final int red = critical;
        mapView.post(new Runnable() {
            @Override
            public void run() {
                if (mine != generation || !on)
                    return;
                status("");
                drawn(n, total, red);
            }
        });
        Log.d(TAG, String.format(Locale.US,
                "drew %d of %d stations within %d mi of %s, %d at criteria",
                n, total, miles, fromMe ? "me" : "the map", red));
    }

    /** One station in one zoom band, with or without its pill. */
    private void add(List<AtmosphereFeatures.Drawn> drawn, Raws.Station s, String set,
            AttributeSet a, int color, UnitSystem system, boolean withLabel,
            double minGsd, double maxGsd) {
        // The feathers count the sustained wind, which is what a barb shows; the
        // color is decided by the strongest wind the station has, gust included, so
        // the gust is printed beside it or the color has nothing behind it.
        final StationIcons.Composed icon = icons.compose(s.name,
                speed(s.windMph, system), speed(s.gustMph, system), speedUnit(),
                s.relativeHumidity, s.fuelMoisture, s.windFromDeg, knots(s.windMph),
                color, withLabel);
        if (icon == null)
            return;
        drawn.add(new AtmosphereFeatures.Drawn(set, s.name,
                AtmosphereFeatures.point(s.latitude, s.longitude),
                AtmosphereFeatures.icon(icon.uri, icon.width, icon.height),
                a, minGsd, maxGsd));
    }

    private static int color(int level) {
        if (level == RedFlag.CRITICAL)
            return StationIcons.CRITICAL;
        if (level == RedFlag.NEAR)
            return StationIcons.NEAR;
        return StationIcons.NORMAL;
    }

    /** Miles per hour as knots, for the barb's feathers. */
    private static double knots(double mph) {
        return Double.isNaN(mph) ? Double.NaN : mph / 1.15078;
    }

    /**
     * A bare example barb at a given speed, for the guide under the layer.
     *
     * <p>Drawn by the same composer the map uses, so the guide can never drift from
     * what is on the map -- a legend that is redrawn by hand is a legend that is
     * eventually wrong.
     *
     * @return a {@code file://} uri, or null if it could not be composed
     */
    public String exampleBarb(double knots) {
        final StationIcons.Composed c = icons.compose(null, Double.NaN, Double.NaN, "",
                Double.NaN, Double.NaN, 270, knots, StationIcons.NORMAL, false);
        return c == null ? null : c.uri;
    }

    /** An example station symbol in one of the three states, for the guide. */
    public String exampleSymbol(int stateColor) {
        final StationIcons.Composed c = icons.compose(null, Double.NaN, Double.NaN, "",
                Double.NaN, Double.NaN, Double.NaN, Double.NaN, stateColor, false);
        return c == null ? null : c.uri;
    }

    /** What the speed of an example barb reads as, in the operator's unit. */
    public String exampleSpeed(double knots) {
        final UnitSystem system = units();
        final int shown = (int) Math.round(Units.toDisplay(Quantity.SPEED,
                knots * 0.514444, system));
        return Math.round(knots) + " kt" + (system == UnitSystem.AVIATION ? ""
                : "  (" + shown + " " + Units.displayUnit(Quantity.SPEED, system) + ")");
    }

    /** The speed printed on the icon, in whatever unit the rest of the plugin shows. */
    private static double speed(double mph, UnitSystem system) {
        if (Double.isNaN(mph))
            return Double.NaN;
        return Units.toDisplay(Quantity.SPEED, mph * 0.44704, system);
    }

    /** The unit suffix, for the line above the map's stations. */
    public String speedUnit() {
        return Units.displayUnit(Quantity.SPEED, units());
    }

    private static UnitSystem units() {
        final SharedPreferences p = MapCompat.prefs();
        return UnitSystem.fromName(p == null ? null : p.getString(PREF_UNITS, null),
                UnitSystem.IMPERIAL);
    }

    /**
     * Everything the station has to say, in words.
     *
     * <p>Written as strings on purpose: the details pane prints an attribute only when
     * it is a string, so a number stored as a number is silently dropped and a tap
     * shows an empty pane. It is also the right shape -- "20 mph from 250 (WSW)" is
     * what somebody tapping a station wants, not three fields to assemble themselves.
     */
    /**
     * Everything a station has to say, as ordered label/value pairs.
     *
     * <p>One list, used by the map tap and by the list page's details. Two copies
     * drift, and the operator is the one who notices that a tap and a row disagree.
     */
    public static List<String[]> describe(Raws.Station s, UnitSystem system, long now) {
        final List<String[]> out = new ArrayList<>();
        row(out, "Status", StationIcons.stateLabel(colorFor(s)));
        row(out, "Wind", wind(s, system));
        row(out, "Gust", Double.isNaN(s.gustMph) ? ""
                : Units.format(Quantity.SPEED, s.gustMph * 0.44704, system)
                        + direction(s.gustFromDeg));
        row(out, "Humidity", percent(s.relativeHumidity));
        row(out, "Temperature", fahrenheit(s.airTempF, system));
        row(out, "Fuel moisture", percent(s.fuelMoisture));
        row(out, "Fuel temperature", fahrenheit(s.fuelTempF, system));
        row(out, "Observed", observed(s, now));
        row(out, "Elevation", s.elevation <= 0 ? ""
                : Units.format(Quantity.LENGTH, s.elevation * 0.3048, system));
        row(out, "Agency", s.agency);
        row(out, "Unit", s.unit);
        row(out, "County", s.county);
        row(out, "State", s.state);
        row(out, "Station status", s.status);
        row(out, "Station id", s.wxId);
        row(out, "MesoWest id", s.mesowestId);
        row(out, "Position", String.format(Locale.US, "%.5f, %.5f",
                s.latitude, s.longitude));
        return out;
    }

    /** The color the map would draw this station, without needing the map. */
    public static int colorFor(Raws.Station s) {
        return color(stateOf(s));
    }

    private static void row(List<String[]> out, String label, String value) {
        if (value != null && !value.trim().isEmpty())
            out.add(new String[] { label, value.trim() });
    }

    private AttributeSet attrs(Raws.Station s, int color, long now, UnitSystem system) {
        final AttributeSet a = new AttributeSet();
        put(a, "Station", s.name);
        for (String[] r : describe(s, system, now))
            put(a, r[0], r[1]);
        return a;
    }

    /** Only what the station actually sent; an empty field is not a field. */
    private static void put(AttributeSet a, String key, String value) {
        if (value != null && !value.trim().isEmpty())
            a.setAttribute(key, value.trim());
    }

    private static String wind(Raws.Station s, UnitSystem system) {
        if (Double.isNaN(s.windMph))
            return "";
        if (s.windMph < 1)
            return "Calm";
        return Units.format(Quantity.SPEED, s.windMph * 0.44704, system)
                + direction(s.windFromDeg);
    }

    /** " from 250 (WSW)", or nothing when the station did not report a direction. */
    private static String direction(double deg) {
        if (Double.isNaN(deg))
            return "";
        return String.format(Locale.US, " from %d\u00b0 (%s)", Math.round(deg),
                Units.degreesToCompass(deg));
    }

    private static String percent(double v) {
        return Double.isNaN(v) ? "" : String.format(Locale.US, "%s%%", trim(v));
    }

    private static String fahrenheit(double f, UnitSystem system) {
        if (Double.isNaN(f))
            return "";
        return Units.format(Quantity.TEMPERATURE, (f - 32) * 5.0 / 9.0, system);
    }

    private static String trim(double v) {
        return v == Math.rint(v) ? String.valueOf(Math.round(v))
                : String.format(Locale.US, "%.1f", v);
    }

    /** "35 minutes ago", and the hour it was, because stale is the thing to notice. */
    private static String observed(Raws.Station s, long now) {
        if (s.observedAt <= 0)
            return "Never reported";
        final double hours = s.ageHours(now);
        final String ago;
        if (hours < 1.5)
            ago = Math.max(1, Math.round(hours * 60)) + " minutes ago";
        else if (hours < 48)
            ago = Math.round(hours) + " hours ago";
        else
            ago = Math.round(hours / 24) + " days ago";
        return ago + ", " + new java.text.SimpleDateFormat("EEE MMM d, h:mm a",
                Locale.US).format(new java.util.Date(s.observedAt));
    }

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
            l.onStationsStatus(message);
    }

    private void drawn(int n, int total, int critical) {
        final Listener l = listener;
        if (l != null)
            l.onStationsDrawn(n, total, critical);
    }
}
