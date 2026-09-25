package com.atakmap.android.ipaws;

import android.content.Context;
import android.content.SharedPreferences;
import android.preference.PreferenceManager;

import com.atakmap.android.ipaws.data.Alert;
import com.atakmap.android.ipaws.data.Areas;
import com.atakmap.android.ipaws.data.EventColors;
import com.atakmap.android.ipaws.data.AlertSource;
import com.atakmap.android.ipaws.data.Filter;
import com.atakmap.android.ipaws.data.Geo;
import com.atakmap.android.ipaws.data.GeoJson;
import com.atakmap.android.ipaws.data.MainThread;
import com.atakmap.android.ipaws.data.NwsSource;
import com.atakmap.android.ipaws.data.ZoneCache;
import com.atakmap.android.ipaws.net.Http;
import com.atakmap.android.maps.MapView;
import com.atakmap.coremap.filesystem.FileSystemUtils;
import com.atakmap.coremap.log.Log;
import com.atakmap.map.layer.feature.AttributeSet;
import com.atakmap.map.layer.feature.geometry.Geometry;
import com.atakmap.map.layer.feature.geometry.GeometryCollection;

import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

/**
 * The poll, the overlay, and the current picture. Lives for the plugin's life.
 *
 * <p>Started from {@code onStart} and stopped from {@code onStop}, never from a
 * {@code Tool}: ATAK ends the active tool whenever another starts, a dropdown opens or
 * Back is pressed, and an alert feed that stops because the user switched base maps is
 * the FOBS 0.4 failure. The pane is only this object's controls.
 */
public class AlertManager {

    private static final String TAG = "IPAWS";

    /** The timer ticks this often; a poll happens when the filter's interval has elapsed. */
    private static final long TICK_MS = 60_000L;

    /**
     * How many times one poll will go back for more zone geometry before waiting for
     * the next one. Bounded so that zones which cannot be reached -- a dead network,
     * which is deliberately never cached as a miss -- cannot spin.
     */
    private static final int MAX_ZONE_ROUNDS = 5;

    private static final String PREF_FILTER = "ipaws.filter";

    public interface Listener {
        void onChanged();
    }

    private final MapView mapView;
    private final Context pluginContext;
    private final AlertSource source = new NwsSource();
    private final ZoneCache zones;
    private final com.atakmap.android.ipaws.data.Counties countyLists;
    private final AlertOverlay overlay;
    private final SharedPreferences prefs;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(
            new ThreadFactory() {
                @Override
                public Thread newThread(Runnable r) {
                    final Thread t = new Thread(r, "ipaws-work");
                    t.setDaemon(true);
                    return t;
                }
            });

    private Filter filter;
    private Listener listener;
    private boolean started;
    private boolean polling;

    /**
     * Everything the last successful poll returned, before the filter. Kept so that a
     * change the server was not asked about -- counties, event types -- can be applied
     * to what is already in hand instead of costing a request and a wait.
     */
    private List<Alert> raw = new ArrayList<>();
    /** The last set the feed actually gave us, filtered. Never cleared by a failure. */
    private List<Alert> alerts = new ArrayList<>();
    /** Event types with an area on the map, NWS priority first. */
    private List<String> mapKey = new ArrayList<>();
    /** Where the last rebuild measured the distance scope from, {lat, lon}; null when off. */
    private volatile double[] scopeAt;
    /**
     * "What is in view": the padded box the last rebuild kept alerts in, {south, west,
     * north, east}, and the unpadded width it came from; null when off or unknown.
     */
    private volatile double[] scopeBox;
    private volatile double scopeViewWidth;
    /** The last rebuild wanted My Location and had no fix, so it used the map center. */
    private volatile boolean scopeNoFix;
    private long lastScopeRebuild;
    private volatile boolean scopeRebuildQueued;
    private long lastGoodAt;
    private long lastPollAttempt;
    private String lastError;
    private int zonesPending;
    private int undrawable;
    /**
     * Alert ids seen on a previous poll. Notifications fire for what is new to this,
     * never for what was already active when the filter changed -- switching a state
     * on must not set off a dozen notifications for weather that was there all along.
     */
    private final Set<String> announced = new java.util.HashSet<>();
    private boolean primed;
    /**
     * The areas the last announced poll asked for. When this changes, the next poll
     * brings back weather that was always there and is merely newly visible, so it
     * re-primes instead of announcing.
     */
    private Set<String> announcedAreas = new LinkedHashSet<>();

    private final Runnable timer = new Runnable() {
        @Override
        public void run() {
            if (!started)
                return;
            final long due = Math.max(1, filter.pollMinutes) * 60_000L;
            if (!polling && System.currentTimeMillis() - lastPollAttempt >= due)
                poll();
            else
                followScope();   // My Location moves without the map moving
            MainThread.postDelayed(this, TICK_MS);
        }
    };

    public AlertManager(MapView mapView, Context pluginContext) {
        this.mapView = mapView;
        this.pluginContext = pluginContext;
        final File root = FileSystemUtils.getItem("tools/ipaws");
        if (!root.isDirectory() && !root.mkdirs())
            Log.w(TAG, "could not create " + root);
        zones = new ZoneCache(root, source);
        countyLists = new com.atakmap.android.ipaws.data.Counties(root);
        overlay = new AlertOverlay(mapView, pluginContext, new File(root, "alerts.db"),
                "IPAWS Alerts");
        prefs = PreferenceManager.getDefaultSharedPreferences(mapView.getContext());
        filter = loadFilter();
    }

    public void setListener(Listener l) {
        listener = l;
    }

    public Filter getFilter() {
        return filter;
    }

    /**
     * The event types drawn on the map right now, in NWS's priority order, for the
     * pane's key. Only what is drawn: an alert still waiting for its area is in the
     * list but not here, because the key explains colors that are on the screen.
     */
    public List<String> mapKey() {
        synchronized (this) {
            // All OFF: nothing is on the map, so there is nothing to explain.
            return filter.mapOn ? new ArrayList<>(mapKey) : new ArrayList<String>();
        }
    }

    /**
     * All ON / All OFF. Instant, on the main thread: it hides or shows the layer and
     * touches nothing else. The first version emptied the store instead and waited
     * for a rebuild, which at national scale is eleven seconds behind the rebuilds
     * already queued -- so nothing happened when it was tapped, a second tap turned
     * it back on before the first had landed, and the operator reported it as not
     * working at all.
     */
    public void setMapOn(boolean on) {
        filter.mapOn = on;
        saveFilter();
        applyVisibility();
        changed();
    }

    /**
     * Zoom gate, Feature Layer's: alerts draw only when zoomed in at least this far,
     * in meters per pixel; {@code Double.MAX_VALUE} is Always.
     *
     * <p>Applied here, from {@code getMapResolution()} on every settled map move, and
     * not as the feature sets' resolution range. That was the first version, and on
     * s10-dev-1 the map went empty at a zoom the status line called close enough:
     * ATAK's renderer tests the range against its own draw resolution, which is not
     * the map resolution the scale bar and "Use this zoom" read, and rounds it to a
     * whole tile level besides. One number now decides the map, the status line and
     * the button, so they cannot disagree.
     */
    public void setGate(double metersPerPixel) {
        filter.gateGsd = metersPerPixel;
        saveFilter();
        gatedOut = pastGate();
        applyVisibility();
        changed();
    }

    /** Zoomed out past the gate right now: whatever the switch, nothing should draw. */
    private boolean gatedOut;
    /** What was last handed to the overlay, so a map move that changes nothing writes nothing. */
    private Boolean shown;

    private boolean pastGate() {
        return filter.gateGsd != Double.MAX_VALUE && mapView.getMapResolution() > filter.gateGsd;
    }

    /** The one place the overlay is shown or hidden: All ON/OFF and the zoom gate together. */
    private void applyVisibility() {
        final boolean on = filter.mapOn && !gatedOut;
        if (shown != null && shown == on)
            return;
        shown = on;
        overlay.setVisible(on);
    }

    /**
     * Distance scope, Feature Layer's: only alerts reaching to within {@code radiusM}
     * of My Location ({@code "me"}) or the map center ({@code "center"}); 0 is
     * Everywhere. Re-filters what is in hand, no request.
     */
    public void setScope(String mode, String from, double radiusM) {
        filter.scopeFrom = "center".equals(from) ? "center" : "me";
        if ("radius".equals(mode) && radiusM > 0) {
            filter.scope = "radius";
            filter.scopeRadiusM = radiusM;
        } else {
            filter.scope = "view".equals(mode) ? "view" : "all";
        }
        saveFilter();
        lastScopeRebuild = System.currentTimeMillis();
        reapplyFilter();
        changed();
    }

    /** The last rebuild wanted My Location, had no GPS fix, and measured from the map center. */
    public boolean scopeHasNoFix() {
        return scopeNoFix && "radius".equals(filter.scope);
    }

    /**
     * Keeps the scope where it says it is. Feature Layer's rule: re-filter once the
     * point has moved a fifth of the radius, never less than 250 m -- panning half a
     * screen should bring the circle along -- and not more often than every few
     * seconds, because a pinch fires this every frame.
     */
    private void followScope() {
        if (scopeRebuildQueued || !scopeMoved())
            return;
        final long t = System.currentTimeMillis();
        if (t - lastScopeRebuild < SCOPE_MIN_GAP_MS)
            return;
        lastScopeRebuild = t;
        scopeRebuildQueued = true;
        worker.execute(new Runnable() {
            @Override
            public void run() {
                scopeRebuildQueued = false;
                final boolean haveAnything;
                synchronized (AlertManager.this) {
                    haveAnything = !raw.isEmpty();
                }
                if (haveAnything)
                    rebuild(0, Integer.MAX_VALUE);
            }
        });
    }

    private static final long SCOPE_MIN_GAP_MS = 3_000L;

    /** Whether what the last rebuild kept no longer answers the scope. Main thread. */
    private boolean scopeMoved() {
        if ("view".equals(filter.scope)) {
            // Feature Layer's rule for the view: the last rebuild kept a padded box,
            // and a pan that stays inside it has nothing new to show. Zooming well in
            // narrows the list, so that counts too.
            final double[] now = viewBox(0);
            if (now == null)
                return false;
            final double[] box = scopeBox;
            if (box == null)
                return true;   // the last rebuild had no view (the globe); now it does
            return now[0] < box[0] || now[1] < box[1] || now[2] > box[2] || now[3] > box[3]
                    || (now[3] - now[1]) < scopeViewWidth * 0.5;
        }
        if (!"radius".equals(filter.scope))
            return false;
        final double[] was = scopeAt;
        double[] now = "center".equals(filter.scopeFrom) ? null : ownPosition();
        if (now == null)
            now = mapCenter();
        if (was == null || now == null)
            return false;
        final double moved = Geo.envelopeDistanceM(was[0], was[1], was[0], was[1], now[0], now[1]);
        return moved > Math.max(250d, filter.scopeRadiusM * 0.2);
    }

    /**
     * The map's view, {south, west, north, east}, widened by {@code pad} of its size on
     * every side; null when ATAK has none. On the globe {@code getBounds()} can be NaN,
     * and "what is in view" then means everything rather than nothing.
     */
    private double[] viewBox(double pad) {
        final com.atakmap.coremap.maps.coords.GeoBounds b = mapView.getBounds();
        if (b == null)
            return null;
        final double s = b.getSouth(), w = b.getWest(), n = b.getNorth(), e = b.getEast();
        if (Double.isNaN(s) || Double.isNaN(w) || Double.isNaN(n) || Double.isNaN(e) || n <= s || e <= w)
            return null;
        final double py = Math.max(0.01, (n - s) * pad), px = Math.max(0.01, (e - w) * pad);
        return new double[] { s - py, w - px, n + py, e + px };
    }

    /**
     * onMapMoved runs on the GL thread, every frame of a pinch: it only posts, and the
     * posts are coalesced, so the check runs once the map has settled (Cam Depot's).
     */
    private final com.atakmap.map.AtakMapView.OnMapMovedListener moved =
            new com.atakmap.map.AtakMapView.OnMapMovedListener() {
                @Override
                public void onMapMoved(com.atakmap.map.AtakMapView view, boolean animate) {
                    MainThread.remove(scopeTick);
                    MainThread.postDelayed(scopeTick, 300);
                }
            };

    private final Runnable scopeTick = new Runnable() {
        @Override
        public void run() {
            if (!started)
                return;
            final boolean past = pastGate();
            if (past != gatedOut) {
                gatedOut = past;
                applyVisibility();
                changed();   // the status line says "zoom in" from the same test
            }
            followScope();
        }
    };

    /**
     * A usable own position, {lat, lon}, or null. The self marker reads 0,0 before a
     * fix and calls itself valid, which put Feature Layer's "near me" in the Gulf of
     * Guinea.
     */
    private double[] ownPosition() {
        final com.atakmap.android.maps.Marker self = mapView.getSelfMarker();
        final com.atakmap.coremap.maps.coords.GeoPoint p = self == null ? null : self.getPoint();
        if (p == null || !p.isValid()
                || (Math.abs(p.getLatitude()) < 0.01 && Math.abs(p.getLongitude()) < 0.01))
            return null;
        return new double[] { p.getLatitude(), p.getLongitude() };
    }

    private double[] mapCenter() {
        final com.atakmap.coremap.maps.coords.GeoPoint c = mapView.getPoint().get();
        if (c == null || !c.isValid())
            return null;
        return new double[] { c.getLatitude(), c.getLongitude() };
    }

    /** A {south, west, north, east} box as a polygon, for the exact in-view test. */
    private static Geometry boxShape(double[] b) {
        final com.atakmap.map.layer.feature.geometry.LineString ring =
                new com.atakmap.map.layer.feature.geometry.LineString(2);
        ring.addPoint(b[1], b[0]);
        ring.addPoint(b[3], b[0]);
        ring.addPoint(b[3], b[2]);
        ring.addPoint(b[1], b[2]);
        ring.addPoint(b[1], b[0]);
        final com.atakmap.map.layer.feature.geometry.Polygon p =
                new com.atakmap.map.layer.feature.geometry.Polygon(2);
        p.addRing(ring);
        return p;
    }

    private static boolean overlaps(double[] a, double[] b) {
        return a[0] <= b[2] && b[0] <= a[2] && a[1] <= b[3] && b[1] <= a[3];
    }

    /**
     * An alert's extent without building its geometry: its own, or the union of its
     * zones'. Null when any zone is not held -- unknown is kept, not scoped away.
     */
    private double[] extentOf(Alert a) {
        if (a.hasOwnGeometry())
            return Geo.envelopeOf(a.geometry);
        if (a.zoneUrls.isEmpty())
            return null;
        final double[] e = { Double.MAX_VALUE, Double.MAX_VALUE, -Double.MAX_VALUE, -Double.MAX_VALUE };
        for (String url : a.zoneUrls) {
            final double[] z = zones.envelope(url);
            if (z == null)
                return null;
            Geo.grow(e, z);
        }
        return e[0] > e[2] ? null : e;
    }

    /** True when a zoom gate is set and the map is zoomed out past it. Main thread. */
    public boolean zoomedOutPastGate() {
        return filter.mapOn && pastGate();
    }

    /** The current picture, most severe first. A copy: the poll rewrites the original. */
    public List<Alert> snapshot() {
        synchronized (this) {
            return new ArrayList<>(alerts);
        }
    }

    // ---- lifecycle -----------------------------------------------------------------

    /** Set by the plugin once the pane exists, so the map's radial can reach it. */
    public void setDetailsReceiver(android.content.BroadcastReceiver r) {
        if (detailsReceiver != null)
            unregisterDetails();
        detailsReceiver = r;
        if (r == null)
            return;
        final com.atakmap.android.ipc.AtakBroadcast.DocumentedIntentFilter f =
                new com.atakmap.android.ipc.AtakBroadcast.DocumentedIntentFilter();
        f.addAction(com.atakmap.android.ipaws.ui.AlertDetailsReceiver.ACTION,
                "show one alert's metadata");
        com.atakmap.android.ipc.AtakBroadcast.getInstance().registerReceiver(r, f);
    }

    private void unregisterDetails() {
        try {
            com.atakmap.android.ipc.AtakBroadcast.getInstance()
                    .unregisterReceiver(detailsReceiver);
        } catch (Exception ignored) {
            // Never registered, or already gone.
        }
        detailsReceiver = null;
    }

    private android.content.BroadcastReceiver detailsReceiver;

    public void start() {
        started = true;
        try {
            overlay.attach();
            gatedOut = pastGate();
            shown = null;
            applyVisibility();
        } catch (Exception e) {
            Log.e(TAG, "could not attach the overlay", e);
        }
        worker.execute(new Runnable() {
            @Override
            public void run() {
                zones.sweep();
            }
        });
        resolveHomeStateIfUnset();
        poll();
        MainThread.postDelayed(timer, TICK_MS);
        mapView.addOnMapMovedListener(moved);
    }

    /**
     * A fresh install has no states, so ask once where the phone is. Only ever when
     * nothing is selected, so it can never move a filter the operator set.
     */
    private void resolveHomeStateIfUnset() {
        if (!filter.areas.isEmpty())
            return;
        com.atakmap.android.ipaws.data.HomeState.resolve(
                mapView.getSelfMarker() == null ? null : mapView.getSelfMarker().getPoint(),
                new com.atakmap.android.ipaws.data.HomeState.Found() {
                    @Override
                    public void onState(String stateCode) {
                        // Checked again: the operator may have picked while we asked.
                        if (!filter.areas.isEmpty())
                            return;
                        filter.areas.add(stateCode);
                        saveFilter();
                        Log.d(TAG, "fresh install homed to " + stateCode);
                        poll();
                    }
                });
    }

    public void stop() {
        started = false;
        MainThread.remove(timer);
        MainThread.remove(scopeTick);
        mapView.removeOnMapMovedListener(moved);
        unregisterDetails();
        overlay.detach();
    }

    // ---- the poll ------------------------------------------------------------------

    /** Asks now, whatever the interval says. */
    public void poll() {
        if (!started || polling)
            return;
        final String url = source.requestUrl(filter);
        lastPollAttempt = System.currentTimeMillis();
        if (url == null) {
            // Nothing selected is a valid answer, not a failure: show nothing and say so.
            synchronized (this) {
                raw = new ArrayList<>();
                alerts = new ArrayList<>();
            }
            lastError = null;
            worker.execute(new Runnable() {
                @Override
                public void run() {
                    overlay.rewrite(new ArrayList<AlertOverlay.Drawn>());
                    changed();
                }
            });
            return;
        }
        polling = true;
        Log.d(TAG, "poll: " + url);
        Http.get(url, new Http.Callback() {
            @Override
            public void onSuccess(final byte[] body) {
                worker.execute(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            final JSONObject doc = new JSONObject(
                                    new String(body, FileSystemUtils.UTF8_CHARSET));
                            accept(source.parse(doc));
                        } catch (Exception e) {
                            Log.w(TAG, "could not read the feed", e);
                            // A body we cannot parse is a failed poll, and a failed
                            // poll leaves the last good set exactly where it is.
                            fail("the feed sent something unreadable");
                        } finally {
                            polling = false;
                        }
                    }
                });
            }

            @Override
            public void onFailure(int status, String error) {
                polling = false;
                fail(status == Http.NO_RESPONSE ? error : "the alert service returned "
                        + status);
            }
        });
    }

    /** A successful poll. Everything below runs on the worker. */
    private void accept(List<Alert> parsed) {
        synchronized (this) {
            raw = parsed;
        }
        lastGoodAt = System.currentTimeMillis();
        lastError = null;
        Log.d(TAG, "poll ok: " + parsed.size() + " returned");
        // rebuild does the filtering, because the county half of it needs the
        // assembled alert areas and those only exist in there.
        rebuild(0, Integer.MAX_VALUE);
    }

    /**
     * Redraws from the set already in hand, for a filter change the server was never
     * asked about -- counties and event types are applied here, not in the query.
     *
     * <p>This is why they are: the response is reused across filter changes, so
     * narrowing to three counties is instant and costs nothing, where a server-side
     * county filter would be a request and a wait on every tick of a checkbox.
     */
    public void reapplyFilter() {
        worker.execute(new Runnable() {
            @Override
            public void run() {
                final boolean haveAnything;
                synchronized (AlertManager.this) {
                    haveAnything = !raw.isEmpty();
                }
                if (!haveAnything) {
                    // Nothing has come back yet, so there is nothing to re-filter and
                    // the overlay must not be emptied on the strength of it.
                    changed();
                    return;
                }
                rebuild(0, Integer.MAX_VALUE);
            }
        });
    }

    /** The county lists behind the picker; shared so the cache is opened once. */
    public com.atakmap.android.ipaws.data.Counties getCounties() {
        return countyLists;
    }

    private void fail(final String error) {
        lastError = error;
        Log.w(TAG, "poll failed: " + error);
        // The overlay is not touched. An alert that was there ninety seconds ago is
        // still the best information we have, and blanking it would be a lie about
        // the weather rather than about the network.
        changed();
    }

    /**
     * Draws the current set, then goes back for the zone geometry it was missing.
     *
     * <p>Draw first, chase second, on purpose: with 93% of alerts carrying no geometry
     * of their own, waiting for every zone before drawing anything would leave the map
     * empty for as long as the fetching takes. What is known goes up immediately and
     * the rest fills in.
     *
     * <p>This is also where the county filter is finally settled, because deciding
     * whether an alert <b>touches</b> a county needs the assembled area, and that only
     * exists here.
     *
     * @param round         how many times this poll has already been back for zones
     * @param missingBefore what was still outstanding when the previous round started
     */
    private void rebuild(final int round, final int missingBefore) {
        final List<Alert> current;
        synchronized (this) {
            current = new ArrayList<>(raw);
        }
        final List<AlertOverlay.Drawn> drawn = new ArrayList<>();
        final List<Alert> kept = new ArrayList<>();
        final Set<String> missing = new LinkedHashSet<>();
        final List<Geometry> selectedCounties = countyShapes(missing);
        final Set<String> onMap = new LinkedHashSet<>();
        int undrawable = 0;
        Geometry viewShape = null;
        // The distance scope, resolved once for this rebuild: where "me" or the map
        // center is now, not where it was when the control was set.
        final String mode = filter.scope;
        final double radius = filter.scopeRadiusM;
        final boolean wantMe = !"center".equals(filter.scopeFrom);
        double[] scopePoint = null;
        double[] inView = null;
        boolean noFix = false;
        if ("view".equals(mode)) {
            // A fifth of the view each side, so a small pan has alerts under it
            // before the next re-filter, as Feature Layer pads its fetch.
            inView = viewBox(0.2);
            final double[] bare = viewBox(0);
            scopeViewWidth = bare == null ? 0 : bare[3] - bare[1];
            viewShape = inView == null ? null : boxShape(inView);
        } else if ("radius".equals(mode) && radius > 0) {
            scopePoint = wantMe ? ownPosition() : null;
            if (scopePoint == null) {
                noFix = wantMe;
                scopePoint = mapCenter();
            }
        }
        scopeAt = scopePoint;
        scopeBox = inView;
        scopeNoFix = noFix;

        for (Alert a : current) {
            if (!filter.accepts(a))
                continue;
            // Far away on its extent alone: rejected without reading its zones, which
            // is what keeps a scoped rebuild fast enough to follow the map.
            if (scopePoint != null) {
                final double[] env = extentOf(a);
                if (env != null && Geo.envelopeDistanceM(env[0], env[1], env[2], env[3],
                        scopePoint[0], scopePoint[1]) > radius)
                    continue;
            } else if (inView != null) {
                final double[] env = extentOf(a);
                if (env != null && !overlaps(env, inView))
                    continue;
            }
            Geometry g = null;
            if (a.hasOwnGeometry()) {
                try {
                    g = GeoJson.parse(a.geometry);
                } catch (Exception e) {
                    Log.w(TAG, "unreadable geometry on " + a.event, e);
                }
            }
            if (g == null)
                g = fromZones(a, missing);
            // Before anything reads it: the store writes a nested collection as a
            // point at 0,0, and the county test and label point should see what is drawn.
            g = Geo.flatten(g);
            // No area yet is kept, as everywhere: an alert we cannot place is shown
            // rather than scoped away on a guess.
            if (scopePoint != null && g != null
                    && !Geo.withinDistance(g, scopePoint[0], scopePoint[1], radius))
                continue;
            // The shape, not its box: a coastal strip from Orange County to San Diego
            // has a box that takes in Murrieta, 18 mi inland, and was listed as "in
            // view" there with nothing of it on the screen.
            if (viewShape != null && g != null && !Geo.intersects(g, viewShape))
                continue;

            if (!countyAccepts(a, g, selectedCounties))
                continue;
            kept.add(a);

            if (g == null) {
                undrawable++;
                continue;
            }
            drawn.add(new AlertOverlay.Drawn(a.severity, a.event,
                    g, AlertStyles.area(a.event), attributesOf(a)));
            onMap.add(a.event);
            // The name goes on a point in the middle of the area, because a label on
            // the polygon itself renders along its edge and reads as a name for a
            // line. Same attributes, so tapping the words opens the same alert.
            final double[] at = Geo.labelPoint(g);
            if (at != null)
                drawn.add(new AlertOverlay.Drawn(a.severity, a.event,
                        new com.atakmap.map.layer.feature.geometry.Point(at[0], at[1]),
                        AlertStyles.label(a.event), attributesOf(a)));
        }

        Collections.sort(kept, BY_SEVERITY_THEN_SOONEST);
        final List<String> key = new ArrayList<>(onMap);
        Collections.sort(key, BY_NWS_PRIORITY);
        synchronized (this) {
            alerts = kept;
            mapKey = key;
        }
        announce(current, kept);
        // Written whether or not the map is on: All OFF hides the layer, it does not
        // empty it, so All ON shows the current picture the moment it is tapped.
        overlay.rewrite(drawn);
        zonesPending = missing.size();
        this.undrawable = undrawable;
        changed();
        if (undrawable > 0)
            Log.d(TAG, undrawable + " alerts have no area yet, " + missing.size()
                    + " zones outstanding");

        if (round >= MAX_ZONE_ROUNDS || missing.isEmpty())
            return;
        // A round that gained no ground means these zones are unreachable rather than
        // slow -- a network failure is deliberately never remembered as absence -- so
        // stop and let the next poll try again instead of spinning on them.
        if (round > 0 && missing.size() >= missingBefore)
            return;

        final int outstanding = missing.size();
        final int requested = zones.fetchMissing(missing, ZoneCache.MAX_FETCH_PER_POLL,
                new ZoneCache.Settled() {
                    @Override
                    public void onSettled() {
                        worker.execute(new Runnable() {
                            @Override
                            public void run() {
                                rebuild(round + 1, outstanding);
                            }
                        });
                    }
                });
        if (requested > 0)
            Log.d(TAG, "fetching " + requested + " zones (round " + (round + 1) + " of "
                    + MAX_ZONE_ROUNDS + ", " + outstanding + " outstanding)");
    }

    /**
     * Notifies for alerts that are new, at the severities the operator chose.
     *
     * <p>Off by default. A plugin that pops a system notification for a Winter Weather
     * Advisory in the next county is switched off within a day, so when it is on it
     * fires only for Extreme and Severe unless that is changed.
     *
     * <p>The first pass after start only records what is already there. Otherwise
     * every alert in the current filter would announce itself the moment ATAK opens,
     * which is noise, not news.
     */
    private void announce(List<Alert> raw, List<Alert> kept) {
        // Seen-ness is tracked against everything the FEED returned, not against what
        // the filter kept. Tracking the kept set meant an alert the filter dropped was
        // forgotten, so switching a severity or a category off and on again announced
        // weather that had been sitting on the map all along -- the one thing this was
        // built not to do.
        final Set<String> keptIds = new java.util.HashSet<>();
        for (Alert a : kept)
            keptIds.add(a.id);

        // Asking for different states brings back alerts we have genuinely never seen
        // which are nonetheless not news: they were always there, we just were not
        // looking. So an area change re-primes rather than announcing.
        final Set<String> areasNow = new LinkedHashSet<>(filter.areas);
        final boolean areasChanged = !areasNow.equals(announcedAreas);
        announcedAreas = areasNow;

        final List<Alert> fresh = new ArrayList<>();
        for (Alert a : raw)
            if (announced.add(a.id) && primed && !areasChanged
                    && filter.shouldNotify(a) && notifyWhereAccepts(a, keptIds))
                fresh.add(a);
        // Worst first, so the message names the worst of them rather than whichever
        // the feed happened to list first.
        Collections.sort(fresh, BY_SEVERITY_THEN_SOONEST);
        primed = true;
        // Ids the FEED has dropped are forgotten, or the set grows all day; one that
        // comes back after expiring is genuinely new again.
        final Set<String> live = new java.util.HashSet<>();
        for (Alert a : raw)
            live.add(a.id);
        announced.retainAll(live);
        if (fresh.isEmpty())
            return;
        final Alert worst = fresh.get(0);
        final String msg = fresh.size() == 1
                ? worst.event + " - " + worst.areaDesc
                : fresh.size() + " new alerts, worst " + worst.severity + ": " + worst.event;
        Log.d(TAG, "notify: " + msg);
        postNotification(msg);
    }

    /** What the test button sends: the real path, so what is seen is what will arrive. */
    public void testNotification() {
        Log.d(TAG, "notify: test");
        postNotification("Test: new alerts will arrive like this. Tap to go to ATAK.");
    }

    private static final String CHANNEL_ID = "ipaws_alerts";

    /**
     * Posts on a channel of IPAWS's own, at high importance: it pops up over whatever
     * is on screen, with sound and vibration, and the operator can tune it in Android's
     * settings under ATAK as "IPAWS Alerts".
     *
     * <p>Not ATAK's NotificationUtil, which is what this used to call. That posts on
     * "TAK Notifications", which on s10-dev-1 is default importance with no sound and
     * no vibration: two notifications fired on 2026-09-25 and the operator saw neither.
     * A public alert that arrives silently in the shade has not been delivered.
     */
    private void postNotification(final String msg) {
        MainThread.post(new Runnable() {
            @Override
            public void run() {
                try {
                    final Context ctx = mapView.getContext();
                    final android.app.NotificationManager nm = (android.app.NotificationManager)
                            ctx.getSystemService(Context.NOTIFICATION_SERVICE);
                    if (nm == null)
                        return;
                    final android.app.Notification.Builder b;
                    if (android.os.Build.VERSION.SDK_INT >= 26) {
                        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                            final android.app.NotificationChannel ch =
                                    new android.app.NotificationChannel(CHANNEL_ID, "IPAWS Alerts",
                                            android.app.NotificationManager.IMPORTANCE_HIGH);
                            ch.setDescription("New public alerts, at the severities and places"
                                    + " set in the IPAWS Alerts pane");
                            ch.enableVibration(true);
                            nm.createNotificationChannel(ch);
                        }
                        b = new android.app.Notification.Builder(ctx, CHANNEL_ID);
                    } else {
                        b = new android.app.Notification.Builder(ctx)
                                .setPriority(android.app.Notification.PRIORITY_HIGH)
                                .setDefaults(android.app.Notification.DEFAULT_ALL);
                    }
                    // Tapping it brings ATAK forward; nothing from the feed goes in it.
                    final android.content.Intent open = ctx.getPackageManager()
                            .getLaunchIntentForPackage(ctx.getPackageName());
                    if (open != null) {
                        final int flags = android.app.PendingIntent.FLAG_UPDATE_CURRENT
                                | (android.os.Build.VERSION.SDK_INT >= 23
                                        ? android.app.PendingIntent.FLAG_IMMUTABLE : 0);
                        b.setContentIntent(android.app.PendingIntent.getActivity(ctx, NOTIFY_ID,
                                open, flags));
                    }
                    // The small icon has to be a resource of the posting app, which is
                    // ATAK, so it is ATAK's own red status icon rather than the plugin's.
                    b.setSmallIcon(com.atakmap.android.util.NotificationUtil.GeneralIcon.STATUS_RED.getID())
                            .setContentTitle("IPAWS Alerts")
                            .setContentText(msg)
                            .setStyle(new android.app.Notification.BigTextStyle().bigText(msg))
                            .setAutoCancel(true)
                            .setShowWhen(true);
                    nm.notify(NOTIFY_ID, b.build());
                } catch (LinkageError | RuntimeException e) {
                    // The overlay and the list still work; it just does not chime.
                    Log.w(TAG, "could not post a notification: " + e);
                }
            }
        });
    }

    /**
     * The Where of the notification settings, which is not the map's: "In my states"
     * is everything in the states and counties picked under Where, whatever distance
     * the map is set to; "Within N of me" measures from the device; "Same as the map"
     * is exactly what the list shows. The event-type picks apply to all three -- a
     * category switched off is not wanted as a chime either.
     */
    private boolean notifyWhereAccepts(Alert a, Set<String> keptIds) {
        if ("map".equals(filter.notifyWhere))
            return keptIds.contains(a.id);
        if (!filter.acceptsType(a.event))
            return false;
        if (!"near".equals(filter.notifyWhere))
            return filter.acceptsCounties(a) != Filter.Verdict.NO;
        final double[] me = ownPosition();
        if (me == null)
            return true;   // no fix: tell rather than stay silent on a guess
        Geometry g = null;
        try {
            if (a.hasOwnGeometry())
                g = GeoJson.parse(a.geometry);
        } catch (Exception e) {
            Log.w(TAG, "unreadable geometry on " + a.event, e);
        }
        if (g == null)
            g = fromZones(a, new LinkedHashSet<String>());
        g = Geo.flatten(g);
        // No area yet: told, not dropped. A chime about something a little further
        // than asked beats silence about something close.
        return g == null || Geo.withinDistance(g, me[0], me[1], filter.notifyRadiusM);
    }

    /** One id, so a second notification replaces the first rather than stacking up. */
    private static final int NOTIFY_ID = 94651;

    /** Frames one alert's area on the map, or pans to it when it has no extent. */
    public void panTo(Alert a) {
        if (a == null)
            return;
        worker.execute(new Runnable() {
            @Override
            public void run() {
                Geometry g = null;
                try {
                    if (a.hasOwnGeometry())
                        g = GeoJson.parse(a.geometry);
                } catch (Exception ignored) {
                    // Fall through to the zones.
                }
                if (g == null)
                    g = fromZones(a, new LinkedHashSet<String>());
                if (g == null) {
                    Log.d(TAG, "no area to zoom to for " + a.event);
                    return;
                }
                final com.atakmap.map.layer.feature.geometry.Envelope e = g.getEnvelope();
                if (e == null)
                    return;
                MainThread.post(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            final double padLat = Math.max(0.01, (e.maxY - e.minY) * 0.15);
                            final double padLon = Math.max(0.01, (e.maxX - e.minX) * 0.15);
                            final com.atakmap.coremap.maps.coords.GeoPoint[] corners = {
                                    new com.atakmap.coremap.maps.coords.GeoPoint(e.minY - padLat, e.minX - padLon),
                                    new com.atakmap.coremap.maps.coords.GeoPoint(e.maxY + padLat, e.maxX + padLon) };
                            com.atakmap.android.util.ATAKUtilities.scaleToFit(mapView, corners, 0d,
                                    mapView.getWidth(), mapView.getHeight());
                        } catch (Exception ex) {
                            Log.w(TAG, "zoom to failed", ex);
                        }
                    }
                });
            }
        });
    }

    /**
     * The polygons of every county the operator picked, fetching any we do not hold.
     *
     * <p>These come through the same zone cache as everything else: a county zone URL
     * is an api.weather.gov URL like any other, so it passes the same origin gate and
     * is keyed the same way. A handful of counties is a handful of small polygons.
     */
    private List<Geometry> countyShapes(Set<String> missing) {
        final List<Geometry> out = new ArrayList<>();
        if (filter.counties.isEmpty())
            return out;
        for (String same : filter.counties) {
            final String url = Areas.countyZoneUrl(same);
            if (url == null)
                continue;
            final JSONObject geom = zones.geometry(url);
            if (geom == null) {
                if (!zones.isKnownAbsent(url))
                    missing.add(url);
                continue;
            }
            try {
                final Geometry g = GeoJson.parse(geom);
                if (g != null)
                    out.add(g);
            } catch (Exception e) {
                Log.w(TAG, "unreadable county shape for " + same, e);
            }
        }
        return out;
    }

    /**
     * The operator's rule: an alert is kept when it touches a selected county, whether
     * or not NWS attributed it to one.
     *
     * <p>NWS attribution settles most of it -- {@link Filter#acceptsCounties} answers
     * YES for an alert in a state taken whole or in a county that was asked for. What
     * it cannot settle is the case the operator actually raised: a <b>marine</b> zone,
     * which names no county at all, and a forecast zone lapping a county line that NWS
     * chose not to attribute. Those come back MAYBE and are settled on the ground, by
     * intersecting the assembled alert area with the county polygons.
     *
     * <p>When the county shapes have not arrived yet, a MAYBE is kept rather than
     * dropped. The alternative is hiding an alert because a fetch is outstanding.
     */
    private boolean countyAccepts(Alert a, Geometry area, List<Geometry> countyShapes) {
        final Filter.Verdict v = filter.acceptsCounties(a);
        if (v != Filter.Verdict.MAYBE)
            return v == Filter.Verdict.YES;
        if (countyShapes.isEmpty())
            return true;   // nothing to test against yet
        if (area == null)
            return true;   // no area yet either; decide when there is one
        for (Geometry county : countyShapes)
            if (Geo.intersects(area, county))
                return true;
        return false;
    }

    private static final Comparator<String> BY_NWS_PRIORITY = new Comparator<String>() {
        @Override
        public int compare(String a, String b) {
            final int p = Integer.compare(EventColors.priority(a), EventColors.priority(b));
            return p != 0 ? p : a.compareTo(b);
        }
    };

    private static final Comparator<Alert> BY_SEVERITY_THEN_SOONEST = new Comparator<Alert>() {
        @Override
        public int compare(Alert a, Alert b) {
            final int s = a.severityRank() - b.severityRank();
            if (s != 0)
                return s;
            // Then the one that ends soonest, with "no end given" last.
            final long ea = a.until() == 0 ? Long.MAX_VALUE : a.until();
            final long eb = b.until() == 0 ? Long.MAX_VALUE : b.until();
            return Long.compare(ea, eb);
        }
    };

    /**
     * An alert's area assembled from whichever of its zones are cached. Zone URLs that
     * are not held yet are added to {@code missing}; one the server has said it does
     * not hold is not, because it is never going to arrive and counting it would leave
     * the status line saying "still drawing areas" forever.
     *
     * <p>A partly resolved alert is drawn with the zones it has rather than held back:
     * three counties of a five-county advisory on the map beats none of it, and the
     * remaining two appear on the next round.
     */
    private Geometry fromZones(Alert a, Set<String> missing) {
        final List<Geometry> parts = new ArrayList<>();
        for (String url : a.zoneUrls) {
            final JSONObject geom = zones.geometry(url);
            if (geom == null) {
                if (!zones.isKnownAbsent(url))
                    missing.add(url);
                continue;
            }
            try {
                final Geometry g = GeoJson.parse(geom);
                if (g != null)
                    parts.add(g);
            } catch (Exception e) {
                Log.w(TAG, "unreadable cached zone for " + a.event, e);
            }
        }
        if (parts.isEmpty())
            return null;
        if (parts.size() == 1)
            return parts.get(0);
        final GeometryCollection gc = new GeometryCollection(2);
        for (Geometry g : parts)
            gc.addGeometry(g);
        return gc;
    }

    private AttributeSet attributesOf(Alert a) {
        final AttributeSet at = new AttributeSet();
        at.setAttribute("id", a.id);
        at.setAttribute("event", a.event);
        at.setAttribute("severity", a.severity);
        at.setAttribute("urgency", a.urgency);
        at.setAttribute("certainty", a.certainty);
        at.setAttribute("areaDesc", a.areaDesc);
        at.setAttribute("headline", a.headline);
        at.setAttribute("description", a.description);
        at.setAttribute("instruction", a.instruction);
        at.setAttribute("senderName", a.senderName);
        at.setAttribute("expires", a.expires);
        at.setAttribute("ends", a.ends);
        return at;
    }

    // ---- what the pane will ask -----------------------------------------------------

    /**
     * One line saying how old the picture is, because a map that has not been updated
     * for twenty minutes looks exactly like one that has.
     */
    public String statusLine() {
        if (lastGoodAt == 0)
            return lastError == null ? "Waiting for the first update"
                    : "No alerts yet - " + lastError;
        final String age = ago(System.currentTimeMillis() - lastGoodAt);
        if (lastError != null)
            return "Last updated " + age + " - " + lastError;
        // An empty map has to say it was asked to be, or it reads as no weather.
        if (!filter.mapOn)
            return "Updated " + age + " - map off";
        if (zoomedOutPastGate())
            return "Updated " + age + " - zoom in to see alerts on the map";
        // An alert we cannot draw has to be said out loud. Dropping it silently
        // leaves a confident-looking map that is missing an alert, which is the
        // worst way to be wrong; and this happens with no attacker anywhere, on an
        // ordinary 404 for a zone the service no longer holds.
        if (zonesPending > 0)
            return undrawable > 0
                    ? "Updated " + age + " - " + count(undrawable) + " still being drawn"
                    : "Updated " + age + ", still drawing areas";
        if (undrawable > 0)
            return "Updated " + age + " - " + count(undrawable) + " with no map area";
        return "Updated " + age;
    }

    private static String count(int n) {
        return n == 1 ? "1 alert" : n + " alerts";
    }

    private static String ago(long ms) {
        final long minutes = ms / 60_000L;
        if (minutes < 1)
            return "just now";
        if (minutes == 1)
            return "1 minute ago";
        if (minutes < 60)
            return minutes + " minutes ago";
        final long hours = minutes / 60;
        return hours == 1 ? "1 hour ago" : hours + " hours ago";
    }

    public int overlayCount() {
        return overlay.count();
    }

    // ---- the filter -----------------------------------------------------------------

    /** Saves on change, not on dispose: a plugin reload must not be able to lose it. */
    public void saveFilter() {
        try {
            prefs.edit().putString(PREF_FILTER, filter.toJson().toString()).apply();
        } catch (Exception e) {
            Log.w(TAG, "could not save the filter", e);
        }
    }

    private Filter loadFilter() {
        try {
            final String s = prefs.getString(PREF_FILTER, null);
            if (s != null)
                return Filter.fromJson(new JSONObject(s));
        } catch (Exception e) {
            Log.w(TAG, "could not read the stored filter, using defaults", e);
        }
        return Filter.defaults();
    }

    private void changed() {
        MainThread.post(new Runnable() {
            @Override
            public void run() {
                final Listener l = listener;
                if (l != null)
                    l.onChanged();
            }
        });
    }
}
