package com.atakmap.android.atmosphere.overlay;

import android.content.Context;
import android.content.SharedPreferences;

import com.atakmap.android.atmosphere.compat.MapCompat;
import com.atakmap.android.atmosphere.data.GeoJson;
import com.atakmap.android.atmosphere.data.GeoRings;
import com.atakmap.android.atmosphere.data.NwsAlerts;
import com.atakmap.android.atmosphere.data.ZoneCache;
import com.atakmap.android.atmosphere.net.EgressPolicy;
import com.atakmap.android.atmosphere.net.Http;
import com.atakmap.android.maps.MapView;
import com.atakmap.coremap.filesystem.FileSystemUtils;
import com.atakmap.coremap.log.Log;
import com.atakmap.map.layer.feature.AttributeSet;
import com.atakmap.map.layer.feature.geometry.Geometry;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The Weather Service's warnings and watches on the map, Red Flag Warnings first among
 * them: a read-only feature layer in NWS's own colors, the more urgent drawn on top.
 * Not time-enabled; it can be on beside anything.
 *
 * <p>Most warnings carry no shape; they name zones. {@link ZoneCache} is IPAWS's join,
 * carried forward: the shapes are fetched a round at a time, kept on disk for a month,
 * and the map fills in as they land. What is known is drawn at once rather than waiting
 * for the last zone.
 *
 * <p>Three groups, each its own switch: fire weather (Red Flag, Fire Weather Watch and
 * the other fire products), the rest on land, and marine -- off to start, because the
 * night this was built 128 of the country's 260 warnings were gales at sea.
 */
public final class WarningsOverlay {

    private static final String TAG = "AtmosphereWarnings";

    public static final String LAYER_ID = "warnings";
    public static final String HOST = NwsAlerts.HOST;
    public static final String NAME = "Warnings";

    private static final String PREF_ON = "weather.layer.warnings.on";
    private static final String PREF_GROUP = "weather.layer.warnings.group.";

    /** Warnings change on no schedule; five minutes is fresh without hammering the API. */
    private static final long POLL_MS = 5 * 60 * 1000L;
    private static final long REFRESH_MS = 3 * 60 * 1000L;
    /** Zone rounds per poll before waiting for the next poll to ask again. */
    private static final int MAX_ROUNDS = 10;

    private static final int FILL_ALPHA = 0x40;
    private static final float WEIGHT = 3f;

    public interface Listener {
        void onStatus(String status);

        /** What is drawn changed, so "in effect here" may have. */
        void onAlerts();
    }

    private final MapView mapView;
    private final EgressPolicy egress;
    private final AtmosphereFeatures features;
    private final ZoneCache zones;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private Listener listener;
    private boolean started, on, inFlight;
    private long lastPoll;
    private int generation;
    private List<NwsAlerts.Alert> alerts = new ArrayList<>();
    /** What each drawn alert covers, for "in effect here". Replaced whole, never edited. */
    private volatile List<Covered> covered = new ArrayList<>();
    private final Map<NwsAlerts.Group, Boolean> groups = new HashMap<>();

    /** An alert and the area it is drawn over. */
    public static final class Covered {
        public final NwsAlerts.Alert alert;
        final GeoRings.Area area;

        Covered(NwsAlerts.Alert alert, GeoRings.Area area) {
            this.alert = alert;
            this.area = area;
        }
    }

    private final Runnable autoPoll = new Runnable() {
        @Override
        public void run() {
            if (!on || !started)
                return;
            refresh(true);
            mapView.postDelayed(this, POLL_MS);
        }
    };

    public WarningsOverlay(MapView mapView, Context pluginContext, EgressPolicy egress) {
        this.mapView = mapView;
        this.egress = egress;
        this.features = new AtmosphereFeatures(mapView, pluginContext, TAG, NAME,
                "warnings.sqlite", "warnings", false);
        this.zones = new ZoneCache(FileSystemUtils.getItem("tools/atmosphere/zones"),
                egress.userAgent());
        final SharedPreferences p = MapCompat.prefs();
        for (NwsAlerts.Group g : NwsAlerts.Group.values())
            groups.put(g, p == null ? g != NwsAlerts.Group.MARINE
                    : p.getBoolean(PREF_GROUP + g.name(), g != NwsAlerts.Group.MARINE));
    }

    public void setListener(Listener l) {
        listener = l;
    }

    public void start() {
        started = true;
        features.attach();
        zones.onDiskThread(new Runnable() {
            @Override
            public void run() {
                zones.sweep();
            }
        });
        final SharedPreferences p = MapCompat.prefs();
        if (p != null && p.getBoolean(PREF_ON, false) && egress.isLayerEnabled(LAYER_ID))
            setOn(true);
        // The store outlives a session. If ATAK was killed with the layer on, last
        // session's warnings are still in it, and they would draw now however long
        // ago they expired.
        if (!on)
            drawNothing();
    }

    public void stop() {
        started = false;
        on = false;
        generation++;
        mapView.removeCallbacks(autoPoll);
        worker.shutdownNow();
        features.detach();
        zones.dispose();
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
            alerts = new ArrayList<>();
            covered = new ArrayList<>();
            drawNothing();
            status("");
        }
    }

    public boolean isShowing(NwsAlerts.Group g) {
        return Boolean.TRUE.equals(groups.get(g));
    }

    /** Switch a group; redrawn from what is already held, no new poll. */
    public void setShowing(NwsAlerts.Group g, boolean show) {
        groups.put(g, show);
        final SharedPreferences p = MapCompat.prefs();
        if (p != null)
            p.edit().putBoolean(PREF_GROUP + g.name(), show).apply();
        if (on)
            rebuild(0, generation);
    }

    /** One alert near a point: how far to its edge and which way. */
    public static final class Nearby {
        public final NwsAlerts.Alert alert;
        public final double miles, bearing;

        Nearby(NwsAlerts.Alert alert, double miles, double bearing) {
            this.alert = alert;
            this.miles = miles;
            this.bearing = bearing;
        }
    }

    /**
     * The drawn alerts whose area comes within {@code miles} of a point but does
     * not cover it, nearest first. "Around me", not only "here" (operator,
     * 2026-09-26: "no way for like around me not just map center?").
     */
    public List<Nearby> nearby(double lat, double lon, double miles) {
        final List<Nearby> out = new ArrayList<>();
        for (Covered c : covered) {
            if (c.area == null || c.area.contains(lat, lon))
                continue;
            final double d = c.area.distanceMiles(lat, lon);
            if (d <= miles)
                out.add(new Nearby(c.alert, d, c.area.bearingTo(lat, lon)));
        }
        Collections.sort(out, new java.util.Comparator<Nearby>() {
            @Override
            public int compare(Nearby a, Nearby b) {
                return Double.compare(a.miles, b.miles);
            }
        });
        return out;
    }

    /** The drawn alerts covering a point, most urgent first. */
    public List<NwsAlerts.Alert> inEffectAt(double lat, double lon) {
        final List<NwsAlerts.Alert> out = new ArrayList<>();
        final List<Covered> now = covered;
        // Drawn least urgent first, so read backwards for the most urgent first.
        for (int i = now.size() - 1; i >= 0; i--)
            if (now.get(i).area != null && now.get(i).area.contains(lat, lon))
                out.add(now.get(i).alert);
        return out;
    }

    /** Ask for the country's active alerts, unless that was done very recently. */
    public void refresh(boolean force) {
        if (!on || !started || inFlight)
            return;
        final long now = System.currentTimeMillis();
        if (!force && now - lastPoll < REFRESH_MS)
            return;
        lastPoll = now;
        inFlight = true;
        final int mine = generation;
        if (alerts.isEmpty())
            status("Getting warnings…");
        final Map<String, String> headers = new HashMap<>();
        headers.put("Accept", "application/geo+json");
        // 2.27 MB as it stands, 151 KB gzipped (2026-09-24).
        headers.put("Accept-Encoding", "gzip");
        Http.get(NwsAlerts.ACTIVE_URL, egress.userAgent(), headers, new Http.Callback() {
            @Override
            public void onSuccess(final String body) {
                if (mine != generation || !on) {
                    inFlight = false;
                    return;
                }
                worker.execute(new Runnable() {
                    @Override
                    public void run() {
                        List<NwsAlerts.Alert> parsed = null;
                        try {
                            parsed = NwsAlerts.parse(body);
                        } catch (Exception e) {
                            Log.w(TAG, "alerts unreadable", e);
                        }
                        final List<NwsAlerts.Alert> got = parsed;
                        mapView.post(new Runnable() {
                            @Override
                            public void run() {
                                inFlight = false;
                                if (mine != generation || !on)
                                    return;
                                if (got == null) {
                                    // What is drawn stays drawn: a failed read is not
                                    // "no warnings".
                                    status("Warnings could not be read");
                                    return;
                                }
                                // The feed answered 200 with nothing in it at 22:10 on
                                // 2026-09-26 (44 warnings the poll before, "drew 0 of 0"
                                // after) and the map went blank. An empty answer on the
                                // heels of a full one is a hiccup, not a quiet country:
                                // keep what is drawn and ask again at the next poll.
                                Log.d(TAG, "alerts: " + got.size() + " from "
                                        + (body == null ? 0 : body.length()) + " chars");
                                if (got.isEmpty() && !alerts.isEmpty()) {
                                    status("Warnings feed answered empty; keeping the last ones");
                                    return;
                                }
                                alerts = got;
                                rebuild(0, mine);
                            }
                        });
                    }
                });
            }

            @Override
            public void onFailure(String error) {
                inFlight = false;
                if (mine != generation || !on)
                    return;
                Log.w(TAG, "alerts failed: " + error);
                status(alerts.isEmpty() ? "Could not reach the Weather Service"
                        : "Could not check for newer warnings");
            }
        });
    }

    /**
     * Assemble each shown alert's area from its own shape or its cached zones, write the
     * lot, then go back for the zones that were missing. On the worker: zones are read
     * from disk and a country's worth of shapes is written to a database.
     */
    private void rebuild(final int round, final int mine) {
        final List<NwsAlerts.Alert> snapshot = new ArrayList<>(alerts);
        final Map<NwsAlerts.Group, Boolean> show = new HashMap<>(groups);
        worker.execute(new Runnable() {
            @Override
            public void run() {
                if (mine != generation)
                    return;
                final List<AtmosphereFeatures.Drawn> drawn = new ArrayList<>();
                final List<Covered> cover = new ArrayList<>();
                final Set<String> missing = new LinkedHashSet<>();
                int shown = 0, waiting = 0;
                for (NwsAlerts.Alert a : snapshot) {
                    if (!Boolean.TRUE.equals(show.get(a.group)))
                        continue;
                    shown++;
                    final List<JSONObject> parts = new ArrayList<>();
                    if (a.geometry != null) {
                        parts.add(a.geometry);
                    } else {
                        for (String z : a.zones) {
                            final JSONObject g = zones.geometry(z);
                            if (g != null)
                                parts.add(g);
                            else if (!zones.isKnownAbsent(z))
                                missing.add(z);
                        }
                    }
                    if (parts.isEmpty()) {
                        waiting++;
                        continue;
                    }
                    final Geometry g = geometryOf(parts);
                    if (g == null) {
                        waiting++;
                        continue;
                    }
                    final int c = a.color() & 0x00FFFFFF;
                    drawn.add(new AtmosphereFeatures.Drawn(a.event, a.event, g,
                            AtmosphereFeatures.area(0xFF000000 | c, WEIGHT,
                                    (FILL_ALPHA << 24) | c, a.event),
                            attributes(a)));
                    cover.add(new Covered(a, GeoRings.of(parts)));
                }
                features.rewrite(drawn);
                final int nShown = shown, nDrawn = drawn.size(), nWaiting = waiting;
                mapView.post(new Runnable() {
                    @Override
                    public void run() {
                        if (mine != generation || !on)
                            return;
                        covered = cover;
                        Log.d(TAG, "drew " + nDrawn + " of " + nShown + " warnings, round "
                                + round + ", " + missing.size() + " zones missing");
                        status(line(nDrawn, nWaiting));
                        if (listener != null)
                            listener.onAlerts();
                        if (!missing.isEmpty() && round < MAX_ROUNDS)
                            zones.fetchMissing(missing, ZoneCache.MAX_FETCH_PER_ROUND,
                                    new ZoneCache.Settled() {
                                        @Override
                                        public void onSettled() {
                                            if (mine == generation && on)
                                                rebuild(round + 1, mine);
                                        }
                                    });
                    }
                });
            }
        });
    }

    /** One zone is its own shape; several are one collection, so the alert is one feature. */
    private static Geometry geometryOf(List<JSONObject> parts) {
        try {
            if (parts.size() == 1)
                return GeoJson.parse(parts.get(0));
            final JSONObject gc = new JSONObject();
            gc.put("type", "GeometryCollection");
            gc.put("geometries", new JSONArray(parts));
            return GeoJson.parse(gc);
        } catch (Exception e) {
            // GeoJson refuses a ring with unusable coordinates rather than hand native
            // code a broken one; the rest of the warnings still draw.
            Log.w(TAG, "unusable warning shape", e);
            return null;
        }
    }

    /** The Weather Service's own fields, for the details pane. */
    private static AttributeSet attributes(NwsAlerts.Alert a) {
        final AttributeSet s = new AttributeSet();
        s.setAttribute("Event", a.event);
        put(s, "Headline", a.headline);
        put(s, "Area", a.areaDesc);
        put(s, "Severity", a.severity);
        put(s, "Issued by", a.sender);
        put(s, "Starts", a.onset > 0 ? clock(a.onset) : "");
        put(s, "Until", a.until() > 0 ? clock(a.until()) : "");
        put(s, "What", a.description);
        put(s, "What to do", a.instruction);
        return s;
    }

    private static void put(AttributeSet s, String k, String v) {
        if (v != null && !v.isEmpty())
            s.setAttribute(k, v);
    }

    /** "12 warnings on the map", and what is still coming in. */
    private static String line(int drawn, int waiting) {
        final String s = drawn == 0 && waiting == 0 ? "No warnings in effect"
                : drawn + (drawn == 1 ? " warning" : " warnings") + " on the map";
        return waiting > 0 ? s + ", " + waiting + " more still loading" : s;
    }

    /** "Thu 8 pm", in the phone's zone. */
    public static String clock(long when) {
        return new SimpleDateFormat("EEE h:mm a", Locale.US).format(new Date(when))
                .replace(":00 ", " ").replace("AM", "am").replace("PM", "pm");
    }

    private void drawNothing() {
        worker.execute(new Runnable() {
            @Override
            public void run() {
                features.rewrite(Collections.<AtmosphereFeatures.Drawn>emptyList());
            }
        });
    }

    private void status(String s) {
        if (listener != null)
            listener.onStatus(s);
    }
}
