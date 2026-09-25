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
        overlay.setVisible(on);
        changed();
    }

    /**
     * Zoom gate, Feature Layer's: alerts draw only when zoomed in at least this far,
     * in meters per pixel; {@code Double.MAX_VALUE} is Always. Changes the sets in
     * place, so it needs no poll and no rebuild.
     */
    public void setGate(double metersPerPixel) {
        filter.gateGsd = metersPerPixel;
        saveFilter();
        overlay.setGate(metersPerPixel);
        changed();
    }

    /** True when a zoom gate is set and the map is zoomed out past it. Main thread. */
    public boolean zoomedOutPastGate() {
        return filter.mapOn && filter.gateGsd != Double.MAX_VALUE
                && mapView.getMapResolution() > filter.gateGsd;
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
            overlay.setVisible(filter.mapOn);
            overlay.setGate(filter.gateGsd);
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

        for (Alert a : current) {
            if (!filter.accepts(a))
                continue;
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
                    && keptIds.contains(a.id) && filter.shouldNotify(a))
                fresh.add(a);
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
        MainThread.post(new Runnable() {
            @Override
            public void run() {
                try {
                    com.atakmap.android.util.NotificationUtil.getInstance().postNotification(
                            NOTIFY_ID,
                            com.atakmap.android.util.NotificationUtil.GeneralIcon.STATUS_RED.getID(),
                            "IPAWS Alerts", msg, msg);
                } catch (LinkageError | RuntimeException notThisBuild) {
                    // A build without the notification helper still gets the overlay
                    // and the list; it just does not chime.
                    Log.w(TAG, "could not post a notification: " + notThisBuild);
                }
            }
        });
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
