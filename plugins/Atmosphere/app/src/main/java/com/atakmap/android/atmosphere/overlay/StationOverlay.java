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
    private static final String PREF_UNITS = "weather.units";

    /** RAWS report hourly, so there is nothing to gain from asking more often. */
    private static final long POLL_MS = 10 * 60 * 1000L;
    private static final long REFRESH_MS = 60 * 1000L;

    /** What the distance buttons offer, in miles. */
    public static final int[] RADII = { 25, 50, 100, 250 };
    private static final int DEFAULT_MILES = 50;

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
    private boolean fromMe;
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
        final String url = Raws.nearUrl(from.getLatitude(), from.getLongitude(), miles);
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
            final String uri = icons.uri(speed(s.windMph, system), s.relativeHumidity,
                    s.windFromDeg, color);
            if (uri == null)
                continue;
            // The set is the state, so Overlay Manager can show the stations at
            // criteria on their own and ATAK's own switches work on one at a time.
            drawn.add(new AtmosphereFeatures.Drawn(StationIcons.stateLabel(color), s.name,
                    AtmosphereFeatures.point(s.latitude, s.longitude),
                    AtmosphereFeatures.icon(uri, StationIcons.size(), StationIcons.size()),
                    attrs(s, color, now, system)));
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

    private static int color(int level) {
        if (level == RedFlag.CRITICAL)
            return StationIcons.CRITICAL;
        if (level == RedFlag.NEAR)
            return StationIcons.NEAR;
        return StationIcons.NORMAL;
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

    private AttributeSet attrs(Raws.Station s, int color, long now, UnitSystem system) {
        final AttributeSet a = new AttributeSet();
        a.setAttribute("stationId", s.wxId);
        a.setAttribute("mesowestId", s.mesowestId);
        a.setAttribute("name", s.name);
        a.setAttribute("state", StationIcons.stateLabel(color));
        a.setAttribute("agency", s.agency);
        a.setAttribute("unit", s.unit);
        a.setAttribute("county", s.county);
        a.setAttribute("elevationFt", s.elevation);
        a.setAttribute("observedAt", s.observedAt);
        a.setAttribute("ageHours", s.ageHours(now));
        a.setAttribute("relativeHumidity", s.relativeHumidity);
        a.setAttribute("windMph", s.windMph);
        a.setAttribute("gustMph", s.gustMph);
        a.setAttribute("windFromDeg", s.windFromDeg);
        a.setAttribute("airTempF", s.airTempF);
        a.setAttribute("fuelMoisture", s.fuelMoisture);
        a.setAttribute("speedShown", speed(s.windMph, system));
        a.setAttribute("speedUnit", Units.displayUnit(Quantity.SPEED, system));
        return a;
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
