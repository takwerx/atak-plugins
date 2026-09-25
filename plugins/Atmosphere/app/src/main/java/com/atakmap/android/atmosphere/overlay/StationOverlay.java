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
import com.atakmap.android.maps.MapView;
import com.atakmap.coremap.log.Log;
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
    private static final double STATION_GSD = 600d;
    private static final double LABEL_GSD = 90d;
    private static final double FINEST = 0d;

    public interface Listener {
        void onStationsStatus(String message);

        void onStationsDrawn(int drawn, int total, int critical);
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
    private List<Raws.Station> stations = new ArrayList<>();

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
        redraw();
    }

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
        final SharedPreferences p = MapCompat.prefs();
        if (p != null && p.getBoolean(PREF_ON, false) && egress.isLayerEnabled(LAYER_ID))
            setOn(true);
    }

    public void stop() {
        started = false;
        on = false;
        generation++;
        mapView.removeCallbacks(autoPoll);
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
        final SharedPreferences p = MapCompat.prefs();
        if (p != null)
            p.edit().putBoolean(PREF_FROM_ME, value).apply();
        refresh(true);
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
        final List<AtmosphereFeatures.Drawn> drawn = new ArrayList<>();
        int critical = 0;
        for (Raws.Station s : held) {
            // A station with nothing to say is left off rather than drawn as a reading
            // of nothing, and one that stopped reporting days ago is not current
            // weather however much it looks like it.
            if (s.silent() || s.stale(now))
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
            final String set = StationIcons.stateLabel(color);
            // Bare, for the wide view -- and for the whole range when labels are off.
            add(drawn, s, set, a, color, system, false,
                    STATION_GSD, labels ? LABEL_GSD : FINEST);
            if (labels)
                // The same station again, carrying its pill, for the close view. The
                // two bands meet, so only ever one of them is on screen.
                add(drawn, s, set + " \u00b7 labelled", a, color, system, true,
                        LABEL_GSD, FINEST);
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
                s.relativeHumidity, s.windFromDeg, knots(s.windMph), color, withLabel);
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
    private AttributeSet attrs(Raws.Station s, int color, long now, UnitSystem system) {
        final AttributeSet a = new AttributeSet();
        put(a, "Station", s.name);
        put(a, "Status", StationIcons.stateLabel(color));
        put(a, "Wind", wind(s, system));
        put(a, "Gust", Double.isNaN(s.gustMph) ? ""
                : Units.format(Quantity.SPEED, s.gustMph * 0.44704, system)
                        + direction(s.gustFromDeg));
        put(a, "Humidity", percent(s.relativeHumidity));
        put(a, "Temperature", fahrenheit(s.airTempF, system));
        put(a, "Fuel moisture", percent(s.fuelMoisture));
        put(a, "Fuel temperature", fahrenheit(s.fuelTempF, system));
        put(a, "Observed", observed(s, now));
        put(a, "Elevation", s.elevation <= 0 ? ""
                : Units.format(Quantity.LENGTH, s.elevation * 0.3048, system));
        put(a, "Agency", s.agency);
        put(a, "Unit", s.unit);
        put(a, "County", s.county);
        put(a, "State", s.state);
        put(a, "Station status", s.status);
        put(a, "Station id", s.wxId);
        put(a, "MesoWest id", s.mesowestId);
        put(a, "Position", String.format(Locale.US, "%.5f, %.5f",
                s.latitude, s.longitude));
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
