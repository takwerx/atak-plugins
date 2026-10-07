package com.atakmap.android.atmosphere.overlay;

import android.content.Context;
import android.content.SharedPreferences;

import com.atakmap.android.atmosphere.data.FireZones;
import com.atakmap.android.atmosphere.data.RedFlag;
import com.atakmap.android.atmosphere.data.RedFlagCriteria;
import com.atakmap.android.atmosphere.data.Raws;
import com.atakmap.android.atmosphere.data.StationFavorites;
import com.atakmap.android.atmosphere.data.UtilityStations;
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
    private static final String NAME = "Weather Stations";

    /** The three states, so the pane's legend and the map can never disagree. */
    public static final int NORMAL = StationIcons.NORMAL;
    public static final int NEAR = StationIcons.NEAR;
    public static final int CRITICAL = StationIcons.CRITICAL;

    private static final String PREF_ON = "weather.layer.stations.on";
    private static final String PREF_MILES = "weather.layer.stations.miles";
    private static final String PREF_FROM_ME = "weather.layer.stations.fromme";
    private static final String PREF_LABELS = "weather.layer.stations.labels";
    private static final String PREF_SHOW = "weather.layer.stations.show";

    /**
     * California's utility stations, from Cal OES (see {@link UtilityStations}). Their
     * own allow, because they go to another server with the same position, their own
     * switch, and their own closer zoom: 371 of them sit within 25 miles of Santa
     * Clarita beside 31 RAWS (2026-10-05).
     */
    public static final String UTILITY_ID = "utility_stations";
    public static final String UTILITY_HOST = UtilityStations.HOST;
    private static final String PREF_UTILITY = "weather.layer.stations.utility";
    private static final String PREF_GATE_UTILITY = "weather.layer.stations.utilitygate";
    /** They start at a scale bar of five miles: about forty of them on a phone there. */
    private static final double DEFAULT_UTILITY_BIG = 5d;
    /** The Overlay Manager set they are drawn in, one switch for all of them. */
    private static final String UTILITY_SET = "Utility Stations";

    /** Every station the feed gives back. */
    public static final int SHOW_ALL = 0;
    /** Only the ones with a criterion already met, either one. */
    public static final int SHOW_WATCH = 1;
    /** Only the ones at Red Flag: both criteria at once. */
    public static final int SHOW_RED = 2;
    /** Only the starred ones, wherever they are. */
    public static final int SHOW_FAVORITES = 3;
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
    private static final long SETTLE_MS = 220L;

    public interface Listener {
        void onStationsStatus(String message);

        /**
         * @param drawn           fire weather stations drawn
         * @param critical        how many of those are hitting their criteria
         * @param utility         utility stations held, which the map shows only zoomed in
         * @param utilityCritical how many of those are hitting their criteria, counted
         *                        apart because their wind reads higher
         */
        void onStationsDrawn(int drawn, int total, int critical, int utility,
                int utilityCritical);

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
    /**
     * The map's rotation, in degrees, when the labels were last placed. A label sits
     * opposite its barb on screen, and the barb turns with the map while the label
     * stays level, so a turn of more than {@link #TURN_DEG} places them again.
     */
    private volatile double placedAtRotation;
    /**
     * How far the map turns before the labels are placed again. A label is far
     * enough from its barb that it only meets it after nearly a quarter turn, and
     * track-up moves the map with every bend in the road: each placement is a
     * rewrite of every station.
     */
    private static final double TURN_DEG = 30d;
    private List<Raws.Station> stations = new ArrayList<>();
    /**
     * Starred stations the radius did not reach, fetched by id. Held apart from the
     * radius answer so that unstarring one drops it on the next redraw, without a
     * request.
     */
    private List<Raws.Station> beyond = new ArrayList<>();
    /** Utility stations around the origin, and the starred ones from beyond it. */
    private volatile List<Raws.Station> utilityNear = new ArrayList<>();
    private volatile List<Raws.Station> utilityBeyond = new ArrayList<>();
    private boolean utilityOn, utilityInFlight;
    /** Whether the last rebuild was zoomed in far enough to write the utility stations. */
    private volatile boolean utilityWasShown;
    private double utilityGate;
    private final StationFavorites favorites;
    /** The fire weather zones around the origin, and where they were asked for. */
    private List<FireZones.Zone> zones = new ArrayList<>();
    private GeoPoint zonesFrom;
    /** Each held station's zone, by station id. Replaced whole on the worker. */
    private volatile Map<String, FireZones.Zone> zoneOf = new HashMap<>();
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
        // ATAK's own context: preferences written through the plugin context are
        // gone the next time the plugin loads.
        this.favorites = new StationFavorites(mapView.getContext());
        final SharedPreferences p = MapCompat.prefs();
        miles = p == null ? DEFAULT_MILES : p.getInt(PREF_MILES, DEFAULT_MILES);
        fromMe = p == null || p.getBoolean(PREF_FROM_ME, true);
        labels = p == null || p.getBoolean(PREF_LABELS, true);
        show = p == null ? SHOW_ALL : p.getInt(PREF_SHOW, SHOW_ALL);
        stationGate = storedGate(p, PREF_GATE_STATIONS, ALWAYS_GATE);
        // Worked out against this device's own scale bar, not a nominal width. A
        // threshold derived from an assumed 200 pixel bar is wrong by whatever the
        // real bar differs by -- here that is most of a factor of two, so a gate
        // labeled "30 mi or closer" was still drawing at 53 (operator, 2026-09-25).
        labelGate = storedGate(p, PREF_GATE_LABELS, gsdForBig(DEFAULT_LABEL_BIG));
        utilityOn = p != null && p.getBoolean(PREF_UTILITY, false);
        utilityGate = storedGate(p, PREF_GATE_UTILITY, gsdForBig(DEFAULT_UTILITY_BIG));
    }

    /** Whether the utility stations are wanted; they also need their server allowed. */
    public boolean isUtilityOn() {
        return utilityOn;
    }

    public void setUtilityOn(boolean value) {
        if (utilityOn == value)
            return;
        utilityOn = value;
        final SharedPreferences p = MapCompat.prefs();
        if (p != null)
            p.edit().putBoolean(PREF_UTILITY, value).apply();
        if (value) {
            fetchUtility(origin(), generation);
        } else {
            utilityNear = new ArrayList<>();
            utilityBeyond = new ArrayList<>();
            redraw();
        }
    }

    /** Coarsest meters per pixel at which the utility stations still draw. */
    public double utilityGate() {
        return utilityGate;
    }

    public void setUtilityGate(double metersPerPixel) {
        if (utilityGate == metersPerPixel)
            return;
        utilityGate = metersPerPixel;
        remember(PREF_GATE_UTILITY, metersPerPixel);
        redraw();
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
     * <p>Public so the list can ask the same question. A filter that lives on one
     * surface is a filter the other one disagrees with.
     */
    public boolean passes(int show, Raws.Station s) {
        if (show == SHOW_FAVORITES)
            return favorites.contains(s.wxId);
        final int state = stateOf(s);
        if (show == SHOW_RED)
            return state == RedFlag.CRITICAL;
        if (show == SHOW_WATCH)
            return state != RedFlag.BELOW;
        return true;
    }

    public boolean isFavorite(Raws.Station s) {
        return s != null && favorites.contains(s.wxId);
    }

    /**
     * Star or unstar a station the map already holds.
     *
     * <p>Nothing to fetch: a station being starred is one the operator is looking
     * at, so it is in the radius answer or already held from beyond it. The map
     * redraws only when it is showing the starred ones, since a star changes
     * nothing else it draws.
     *
     * @return true if the station is starred afterwards
     */
    public boolean toggleFavorite(Raws.Station s) {
        final boolean now = favorites.toggle(s.wxId);
        if (show == SHOW_FAVORITES)
            redraw();
        return now;
    }

    /** How many starred stations are held from beyond the radius. */
    public int favoritesBeyond() {
        int n = 0;
        for (Raws.Station s : beyond)
            if (favorites.contains(s.wxId))
                n++;
        for (Raws.Station s : utilityBeyond)
            if (favorites.contains(s.wxId))
                n++;
        return n;
    }

    /**
     * Everything drawn: the radius answer, the starred stations beyond it, and the
     * utility stations when they are on.
     *
     * <p>A utility station NIFC already gave is never held twice. The network filter
     * already leaves RAWS out of the utility answer; this is the check that it did, by
     * MesoWest id, keeping NIFC's copy, which alone carries fuel moisture.
     */
    private List<Raws.Station> held() {
        final List<Raws.Station> near = stations;
        final List<Raws.Station> far = beyond;
        final List<Raws.Station> uNear = utilityNear;
        final List<Raws.Station> uFar = utilityBeyond;
        if (far.isEmpty() && uNear.isEmpty() && uFar.isEmpty())
            return near;
        final List<Raws.Station> out = new ArrayList<>(
                near.size() + far.size() + uNear.size() + uFar.size());
        out.addAll(near);
        for (Raws.Station s : far)
            // Unstarred since it was fetched: it is outside the radius, so it goes.
            if (favorites.contains(s.wxId))
                out.add(s);
        if (uNear.isEmpty() && uFar.isEmpty())
            return out;
        final java.util.Set<String> nifc = new java.util.HashSet<>();
        for (Raws.Station s : out)
            if (!s.mesowestId.isEmpty())
                nifc.add(s.mesowestId);
        final java.util.Set<String> seen = new java.util.HashSet<>();
        for (Raws.Station s : uNear)
            if (!nifc.contains(s.mesowestId) && seen.add(s.wxId))
                out.add(s);
        for (Raws.Station s : uFar)
            if (favorites.contains(s.wxId) && !nifc.contains(s.mesowestId)
                    && seen.add(s.wxId))
                out.add(s);
        return out;
    }

    /**
     * Ask Cal OES for the utility stations around the origin, and for the starred ones
     * beyond it, then draw. Beside the RAWS request rather than after it: a utility
     * answer that is slow or fails leaves the RAWS on the map as they were.
     */
    private void fetchUtility(final GeoPoint from, final int mine) {
        if (!utilityOn || !on || !started || utilityInFlight
                || !egress.isLayerEnabled(UTILITY_ID))
            return;
        final List<String> starred = new ArrayList<>();
        for (String id : favorites.ids()) {
            final String stid = UtilityStations.stidOf(id);
            if (stid != null)
                starred.add(stid);
        }
        final boolean inCalifornia = from != null
                && UtilityStations.covers(from.getLatitude(), from.getLongitude());
        if (!inCalifornia) {
            utilityNear = new ArrayList<>();
            if (starred.isEmpty()) {
                if (!utilityBeyond.isEmpty()) {
                    utilityBeyond = new ArrayList<>();
                    redraw();
                }
                return;
            }
            utilityInFlight = true;
            fetchUtilityBeyond(starred, mine);
            return;
        }
        utilityInFlight = true;
        final Map<String, String> headers = new HashMap<>();
        headers.put("Accept", "application/json");
        // Rounded like the RAWS request: this one sends the same point.
        final String url = UtilityStations.nearUrl(
                Double.parseDouble(egress.latitude(from)),
                Double.parseDouble(egress.longitude(from)), miles);
        Http.get(url, egress.userAgent(), headers, new Http.Callback() {
            @Override
            public void onSuccess(final String body) {
                if (mine != generation || !on) {
                    utilityInFlight = false;
                    return;
                }
                worker.execute(new Runnable() {
                    @Override
                    public void run() {
                        final List<Raws.Station> got = UtilityStations.parse(body);
                        utilityNear = got;
                        Log.d(TAG, "holding " + got.size() + " utility station(s)");
                        final java.util.Set<String> have = new java.util.HashSet<>();
                        for (Raws.Station s : got)
                            have.add(s.mesowestId);
                        final List<String> missing = new ArrayList<>();
                        for (String stid : starred)
                            if (!have.contains(stid))
                                missing.add(stid);
                        if (missing.isEmpty()) {
                            utilityBeyond = new ArrayList<>();
                            utilityInFlight = false;
                            rebuild(mine);
                        } else {
                            fetchUtilityBeyond(missing, mine);
                        }
                    }
                });
            }

            @Override
            public void onFailure(String error) {
                // What was held stays on the map; the next poll asks again.
                Log.w(TAG, "utility stations: " + error);
                utilityInFlight = false;
            }
        });
    }

    /** The starred utility stations outside the radius, by MesoWest id. */
    private void fetchUtilityBeyond(List<String> stids, final int mine) {
        final List<String> chunk = stids.size() > UtilityStations.MAX_IDS_PER_QUERY
                ? stids.subList(0, UtilityStations.MAX_IDS_PER_QUERY) : stids;
        final Map<String, String> headers = new HashMap<>();
        headers.put("Accept", "application/json");
        Http.get(UtilityStations.byIdUrl(chunk), egress.userAgent(), headers,
                new Http.Callback() {
                    @Override
                    public void onSuccess(final String body) {
                        if (mine != generation || !on) {
                            utilityInFlight = false;
                            return;
                        }
                        worker.execute(new Runnable() {
                            @Override
                            public void run() {
                                utilityBeyond = UtilityStations.parse(body);
                                utilityInFlight = false;
                                rebuild(mine);
                            }
                        });
                    }

                    @Override
                    public void onFailure(String error) {
                        Log.w(TAG, "starred utility stations: " + error);
                        utilityInFlight = false;
                    }
                });
    }

    /** The starred ids the radius answer does not carry. */
    private List<String> missingFavorites(List<Raws.Station> near) {
        final List<String> missing = new ArrayList<>();
        if (favorites.isEmpty())
            return missing;
        final java.util.Set<String> have = new java.util.HashSet<>();
        for (Raws.Station s : near)
            have.add(s.wxId);
        for (String id : favorites.ids())
            // A starred utility station is asked of Cal OES, not of NIFC.
            if (!have.contains(id) && UtilityStations.stidOf(id) == null)
                missing.add(id);
        return missing;
    }

    /**
     * Ask for the starred stations the radius did not reach, a hundred ids at a time,
     * then draw. The URL has a length the gateway will take, so a long list is several
     * requests in a row rather than one that is refused.
     */
    private void fetchBeyond(final List<String> remaining, final List<Raws.Station> acc,
            final int mine) {
        final int n = Math.min(remaining.size(), Raws.MAX_IDS_PER_QUERY);
        final List<String> chunk = new ArrayList<>(remaining.subList(0, n));
        final List<String> rest = new ArrayList<>(remaining.subList(n, remaining.size()));
        final Map<String, String> headers = new HashMap<>();
        headers.put("Accept", "application/json");
        Http.get(Raws.byIdUrl(chunk), egress.userAgent(), headers, new Http.Callback() {
            @Override
            public void onSuccess(final String body) {
                if (mine != generation || !on) {
                    inFlight = false;
                    return;
                }
                worker.execute(new Runnable() {
                    @Override
                    public void run() {
                        acc.addAll(Raws.parse(body));
                        if (!rest.isEmpty()) {
                            fetchBeyond(rest, acc, mine);
                            return;
                        }
                        beyond = acc;
                        Log.d(TAG, "holding " + acc.size()
                                + " starred station(s) from beyond the radius");
                        inFlight = false;
                        rebuild(mine);
                    }
                });
            }

            @Override
            public void onFailure(final String error) {
                Log.w(TAG, "starred stations beyond the radius: " + error);
                if (mine != generation || !on) {
                    inFlight = false;
                    return;
                }
                worker.execute(new Runnable() {
                    @Override
                    public void run() {
                        // Whatever was held from last time stays; the map draws.
                        inFlight = false;
                        rebuild(mine);
                    }
                });
            }
        });
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
    private boolean applyLabelBand() {
        final boolean wanted = labels
                && mapView.getMapResolution() <= labelGate;
        if (wanted == labelsWanted)
            return false;
        labelsWanted = wanted;
        redraw();
        return true;
    }

    /** Coalesced: a pinch is hundreds of callbacks and each redraw is a DB write. */
    private final Runnable bandSettled = new Runnable() {
        @Override
        public void run() {
            if (!on)
                return;
            final boolean crossed = applyLabelBand();
            followMapCenter();
            // Which stations are on screen has changed, and that is what decides
            // which ones carry a label. Once, though: crossing the band has already
            // queued a redraw, and it reads the view when it runs, so a second one
            // here was the same compose-and-rewrite of every station again. Every
            // crossing paid twice -- the log showed them in pairs, 19,280 ms then
            // 1,436 ms, 1,351 then 1,135 (XCover, 2026-09-26).
            if (labelsWanted && !crossed && (labeledSetChanged() || turned()))
                redraw();
            // Only the utility stations in view at their zoom are written, so a zoom
            // across their gate needs them written, or taken away.
            else if (!crossed && utilityOn && utilityShownNow() != utilityWasShown)
                redraw();
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
            beyond = new ArrayList<>();
            utilityNear = new ArrayList<>();
            utilityBeyond = new ArrayList<>();
            utilityInFlight = false;
            zones = new ArrayList<>();
            zonesFrom = null;
            zoneOf = new HashMap<>();
            clearOffMain();
            status("");
            drawn(0, 0, 0, 0, 0);
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
        return new ArrayList<>(held());
    }

    /**
     * What a station's state is, by the same rule the map colors it with: its zone's
     * criteria when the table has them, the common pair when it does not.
     */
    public int stateOf(Raws.Station s) {
        return RedFlag.state(s.relativeHumidity, s.windMph, s.gustMph, criteriaFor(s));
    }

    /** The zone a held station stands in, or null before the zones have arrived. */
    public FireZones.Zone zoneOf(Raws.Station s) {
        return s == null ? null : zoneOf.get(s.wxId);
    }

    /** The pair a station is held against; never null. */
    public RedFlag.Criteria criteriaFor(Raws.Station s) {
        final RedFlag.Criteria c = RedFlagCriteria.forZone(zoneOf(s));
        return c == null ? RedFlag.Criteria.NATIONAL : c;
    }

    /**
     * Join every held station to its zone. Worker only: three hundred stations
     * against ninety outlines is tens of milliseconds, and it runs at the top of
     * every rebuild so the colors and the records agree.
     */
    private void joinZones(List<Raws.Station> held) {
        final List<FireZones.Zone> z = zones;
        final Map<String, FireZones.Zone> joined = new HashMap<>();
        if (!z.isEmpty())
            for (Raws.Station s : held) {
                final FireZones.Zone in = FireZones.at(s.latitude, s.longitude, z);
                if (in != null)
                    joined.put(s.wxId, in);
            }
        zoneOf = joined;
    }

    /**
     * Ask for the zones around the origin, once per place: they are asked for again
     * only when the origin has moved the same third of the radius that refetches the
     * stations, never on the ten-minute poll.
     */
    private void fetchZones(final GeoPoint from, final int mine) {
        if (zonesFrom != null && !zones.isEmpty()
                && GeoCalculations.distanceTo(zonesFrom, from) / 1609.344 < miles / 3.0)
            return;
        final Map<String, String> headers = new HashMap<>();
        headers.put("Accept", "application/json");
        final String url = FireZones.nearUrl(Double.parseDouble(egress.latitude(from)),
                Double.parseDouble(egress.longitude(from)), miles);
        Http.get(url, egress.userAgent(), headers, new Http.Callback() {
            @Override
            public void onSuccess(final String body) {
                if (mine != generation || !on)
                    return;
                worker.execute(new Runnable() {
                    @Override
                    public void run() {
                        final List<FireZones.Zone> parsed = FireZones.parse(body);
                        if (parsed.isEmpty()) {
                            Log.w(TAG, "fire weather zones: empty answer");
                            return;
                        }
                        zones = parsed;
                        zonesFrom = from;
                        Log.d(TAG, "holding " + parsed.size() + " fire weather zones ("
                                + parsed.get(0).source + ")");
                        // The colors may change now that the zones are known.
                        rebuild(mine);
                    }
                });
            }

            @Override
            public void onFailure(final String error) {
                // The stations draw on the common pair, and the record says so.
                Log.w(TAG, "fire weather zones: " + error);
            }
        });
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
        fetchZones(from, mine);
        fetchUtility(from, mine);
        if (stations.isEmpty())
            status("Getting stations…");
        final Map<String, String> headers = new HashMap<>();
        headers.put("Accept", "application/json");
        // Rounded the way every other outbound coordinate in this plugin is rounded.
        // This layer is the one that sends the operator's own position -- the others
        // send a map extent or nothing -- so the rounding has to reach it,
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
                        // A starred station outside the radius is not in that answer
                        // at all -- the server did the distance -- so those are asked
                        // for by id, and only then is the map drawn.
                        final List<String> missing = missingFavorites(all);
                        if (missing.isEmpty()) {
                            beyond = new ArrayList<>();
                            inFlight = false;
                            rebuild(mine);
                        } else {
                            fetchBeyond(missing, new ArrayList<Raws.Station>(), mine);
                        }
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
        final long began = android.os.SystemClock.elapsedRealtime();
        final List<Raws.Station> held = held();
        joinZones(held);
        final long now = System.currentTimeMillis();
        final UnitSystem system = units();
        final boolean withLabels = labelsWanted;
        final double[] view = viewBounds();
        final double rotation = mapView.getMapRotation();
        placedAtRotation = rotation;
        {
            final java.util.Set<String> labeled = new java.util.HashSet<>();
            if (withLabels)
                for (Raws.Station s : held)
                    if (onScreen(s, view))
                        labeled.add(s.wxId);
            lastLabeled = labeled;
        }
        // A feature set's coarsest resolution has to be a real number: MAX_VALUE has
        // no level of detail to become, and the hit test then never reaches the layer.
        final double gate = isAlways(stationGate) ? 100_000d : stationGate;
        final List<AtmosphereFeatures.Drawn> drawn = new ArrayList<>();
        int critical = 0;
        int stations = 0;
        int utility = 0;
        int utilityCritical = 0;
        // Never coarser than the stations' own gate: zoomed out past it, nothing draws.
        final double utilityAt = Math.min(gate, isAlways(utilityGate) ? ALWAYS : utilityGate);
        final boolean utilityShown = drawingNow(utilityAt);
        utilityWasShown = utilityShown;
        for (Raws.Station s : held) {
            // A station with nothing to say is left off rather than drawn as a reading
            // of nothing, and one that stopped reporting days ago is not current
            // weather however much it looks like it.
            if (s.silent() || s.stale(now))
                continue;
            if (!passes(show, s))
                continue;
            // The zone's own rule, the same one the record and the list read: the
            // map colored SAC NWR "Below criteria" while its record said "Flirting"
            // (2026-09-26), because this line still held the common pair.
            final int level = stateOf(s);
            final int color = color(level);
            final boolean fromUtility = s.isUtility();
            if (fromUtility) {
                // Counted whether drawn or not, so the status can say how many there
                // are. Only the ones on screen, zoomed in far enough to be drawn, are
                // written: rewriting all 472 around Vista took 3.4 s a pan (XCover,
                // 2026-10-05), and the zoom and the pan both redraw anyway.
                utility++;
                if (level == RedFlag.CRITICAL)
                    utilityCritical++;
                if (!utilityShown || !onScreen(s, view))
                    continue;
            } else if (level == RedFlag.CRITICAL) {
                critical++;
            }
            // The pill gets the operator's unit; the feathers get knots, which is
            // what a barb has always counted in.
            // The feathers count the sustained wind, which is what a barb shows; the
            // color is decided by the strongest wind the station has, gust included,
            // so the gust is printed beside it or the color has nothing behind it.
            // A label is composed only for a station that is on screen.
            //
            // Every labeled icon is a PNG encode and a file write, and crossing the
            // label zoom recomposed all of them: 325 stations took THIRTY SECONDS,
            // which is exactly the "labels come on very late" the operator kept
            // reporting (measured 2026-09-25). The radius is 250 miles and the view
            // is a few; almost all of that work was for symbols nobody could see.
            final boolean labelThis = withLabels && onScreen(s, view);
            final AttributeSet a = attrs(s, color, now, system, labelThis);
            // The set is the state, so Overlay Manager can show the stations at
            // criteria on their own and ATAK's own switches work on one at a time;
            // the utility stations are one set of their own.
            if (add(drawn, s, fromUtility ? UTILITY_SET : StationIcons.stateLabel(color), a,
                    color, system, labelThis, rotation, fromUtility ? utilityAt : gate,
                    FINEST) && !fromUtility)
                stations++;
        }
        if (mine != generation || !on)
            return;
        features.rewrite(drawn);
        final int n = stations;
        final int total = held.size();
        final int red = critical;
        final int fromUtilities = utility;
        final int utilityRed = utilityCritical;
        mapView.post(new Runnable() {
            @Override
            public void run() {
                if (mine != generation || !on)
                    return;
                status("");
                drawn(n, total, red, fromUtilities, utilityRed);
            }
        });
        Log.d(TAG, String.format(Locale.US,
                "drew %d of %d stations (%d utility) within %d mi of %s, %d at criteria, "
                        + "labels=%b, took %d ms",
                n, total, fromUtilities, miles, fromMe ? "me" : "the map", red, withLabels,
                android.os.SystemClock.elapsedRealtime() - began));
    }

    /**
     * One station in one zoom band: its symbol, and its pill when labeled.
     *
     * <p>Two features at one point, because they turn differently when the map is
     * spun: the barb is a bearing and turns with the map, the pill is read and stays
     * level (operator, 2026-10-05: labels "not rotating when spinning"). One bitmap
     * cannot do both, and a composite style draws only its first icon. Both carry the
     * station's name and attributes, so a tap on either opens the station and the
     * chooser lists it once (one row per name and place).
     *
     * @return whether the station was drawn
     */
    private boolean add(List<AtmosphereFeatures.Drawn> drawn, Raws.Station s, String set,
            AttributeSet a, int color, UnitSystem system, boolean withLabel,
            double mapRotation, double minGsd, double maxGsd) {
        final StationIcons.Composed symbol = icons.symbol(s.windFromDeg, knots(s.windMph),
                color, s.isUtility());
        if (symbol == null)
            return false;
        final com.atakmap.map.layer.feature.geometry.Geometry at =
                AtmosphereFeatures.point(s.latitude, s.longitude);
        drawn.add(new AtmosphereFeatures.Drawn(set, s.name, at,
                AtmosphereFeatures.turning(symbol.uri, symbol.width, symbol.height),
                a, minGsd, maxGsd));
        if (!withLabel)
            return true;
        // The feathers count the sustained wind, which is what a barb shows; the
        // color is decided by the strongest wind the station has, gust included, so
        // the gust is printed beside it or the color has nothing behind it.
        final StationIcons.Composed pill = icons.label(s.name,
                speed(s.windMph, system), speed(s.gustMph, system), speedUnit(),
                s.relativeHumidity, s.fuelMoisture, s.windFromDeg, knots(s.windMph),
                mapRotation);
        if (pill != null)
            drawn.add(new AtmosphereFeatures.Drawn(set, s.name,
                    AtmosphereFeatures.point(s.latitude, s.longitude),
                    AtmosphereFeatures.icon(pill.uri, pill.width, pill.height,
                            pill.offsetX, pill.offsetY),
                    a, minGsd, maxGsd));
        return true;
    }

    /**
     * The map's own extent, padded, as south, west, north, east -- or null when there
     * is none to be had.
     *
     * <p>Padded by half a view so a station just off the edge is already labeled
     * when it is panned to, rather than arriving a redraw later. Null on the globe,
     * where the bounds read as NaN.
     */

    /** Whether the map is zoomed in far enough for the utility stations to draw. */
    private boolean utilityShownNow() {
        final double gate = isAlways(stationGate) ? ALWAYS : stationGate;
        return drawingNow(Math.min(gate, isAlways(utilityGate) ? ALWAYS : utilityGate));
    }

    /** Whether the map has turned far enough since the labels were placed to place them again. */
    private boolean turned() {
        final double d = Math.abs(((mapView.getMapRotation() - placedAtRotation) % 360d
                + 540d) % 360d - 180d);
        return d >= TURN_DEG;
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
        for (Raws.Station b : held())
            if (onScreen(b, view))
                now.add(b.wxId);
        return !now.equals(lastLabeled);
    }

    private double[] viewBounds() {
        try {
            final com.atakmap.coremap.maps.coords.GeoBounds b = mapView.getBounds();
            if (b == null)
                return null;
            final double s = b.getSouth(), w = b.getWest();
            final double n = b.getNorth(), e = b.getEast();
            if (Double.isNaN(s) || Double.isNaN(w) || Double.isNaN(n) || Double.isNaN(e))
                return null;
            final double padLat = Math.abs(n - s) / 2.0;
            final double padLon = Math.abs(e - w) / 2.0;
            return new double[] { s - padLat, w - padLon, n + padLat, e + padLon };
        } catch (Exception noBounds) {
            return null;
        }
    }

    /** Whether a station falls inside that extent. No extent means everything does. */
    private static boolean onScreen(Raws.Station s, double[] view) {
        if (view == null)
            return true;
        return s.latitude >= view[0] && s.latitude <= view[2]
                && s.longitude >= view[1] && s.longitude <= view[3];
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
    public List<String[]> describe(Raws.Station s, UnitSystem system, long now) {
        final List<String[]> out = new ArrayList<>();
        row(out, "Status", StationIcons.stateLabel(colorFor(s)));
        // Said first after the state, because it changes how the numbers read.
        if (s.isUtility())
            row(out, "Utility station", s.network + ". Wind is averaged over a minute or "
                    + "two, not ten like a RAWS, so it reads higher than a RAWS beside it. "
                    + "No fuel moisture.");
        // Which zone it stands in, and the pair it is held against -- with where the
        // pair came from, because a color that claims to be the zone's criteria and
        // is the common rule of thumb is worse than one that says which it is.
        final FireZones.Zone zone = zoneOf(s);
        row(out, "Fire weather zone", zone == null ? "" : zone.label());
        final RedFlag.Criteria c = criteriaFor(s);
        row(out, "Red Flag criteria", c.describe() + " (" + c.source + ")");
        row(out, "Wind", wind(s, system));
        row(out, "Gust", Double.isNaN(s.gustMph) ? ""
                : Units.format(Quantity.SPEED, s.gustMph * 0.44704, system)
                        + direction(s.gustFromDeg));
        row(out, "Humidity", percent(s.relativeHumidity));
        row(out, "Temperature", fahrenheit(s.airTempF, system));
        row(out, "Fuel moisture", percent(s.fuelMoisture));
        row(out, "Fuel temperature", fahrenheit(s.fuelTempF, system));
        row(out, "Observed", observed(s, now));
        // Feet or meters, never miles. Quantity.LENGTH formats in the big unit, so a
        // 2,000 foot station read "0.4 mi" -- true, and useless (operator,
        // 2026-09-25).
        row(out, "Elevation", s.elevation <= 0 ? "" : elevation(s.elevation, system));
        row(out, "Agency", s.agency);
        row(out, "Unit", s.unit);
        row(out, "County", s.county);
        row(out, "State", s.state);
        row(out, "Station status", s.status);
        if (!s.isUtility())
            row(out, "Station id", s.wxId);
        row(out, "MesoWest id", s.mesowestId);
        if (s.isUtility())
            row(out, "Source", "Synoptic Data via Cal OES, not verified by Cal OES");
        row(out, "Position", String.format(Locale.US, "%.5f, %.5f",
                s.latitude, s.longitude));
        return out;
    }

    /** The color the map would draw this station, without needing the map. */
    public int colorFor(Raws.Station s) {
        return color(stateOf(s));
    }

    private static void row(List<String[]> out, String label, String value) {
        if (value != null && !value.trim().isEmpty())
            out.add(new String[] { label, value.trim() });
    }

    private AttributeSet attrs(Raws.Station s, int color, long now, UnitSystem system,
            boolean withLabel) {
        final AttributeSet a = new AttributeSet();
        put(a, "Station", s.name);
        for (String[] r : describe(s, system, now))
            put(a, r[0], r[1]);
        // Plumbing, not a field. Underscored so the details pane skips it.
        //
        // The station whole -- symbol, barb and pill in one bitmap -- for the map item a
        // tap materializes and the Select Item row it is listed by; the map draws the
        // symbol and the pill apart (add()). In the bitmap's own pixels, because a
        // Marker icon is sized in pixels and not scaled by density the way the
        // feature's width is (StationIcons.Composed).
        final StationIcons.Composed own = icons.compose(s.name,
                speed(s.windMph, system), speed(s.gustMph, system), speedUnit(),
                s.relativeHumidity, s.fuelMoisture, s.windFromDeg, knots(s.windMph),
                color, withLabel, s.isUtility());
        if (own != null) {
            put(a, "_chooserIcon", own.uri);
            a.setAttribute("_chooserW", own.pxWidth);
            a.setAttribute("_chooserH", own.pxHeight);
            a.setAttribute("_chooserAnchorX", own.pxAnchorX);
            a.setAttribute("_chooserAnchorY", own.pxAnchorY);
        }
        // The details pane sorts what it is given, which turned a list that starts
        // with the wind into one that starts with the agency. Pre-rendered in the
        // order it was written, and the pane prefers this when it is there.
        final StringBuilder text = new StringBuilder();
        for (String[] r : describe(s, system, now)) {
            if (text.length() > 0)
                text.append('\n');
            text.append(r[0]).append(": ").append(r[1]);
        }
        put(a, "_details", text.toString());
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

    /** A height above sea level, in the small unit of whichever system is in use. */
    private static String elevation(int feet, UnitSystem system) {
        if (system == UnitSystem.METRIC)
            return String.format(Locale.US, "%,d m", Math.round(feet * 0.3048));
        return String.format(Locale.US, "%,d ft", feet);
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

    private void drawn(int n, int total, int critical, int utility, int utilityCritical) {
        final Listener l = listener;
        if (l != null)
            l.onStationsDrawn(n, total, critical, utility, utilityCritical);
    }
}
