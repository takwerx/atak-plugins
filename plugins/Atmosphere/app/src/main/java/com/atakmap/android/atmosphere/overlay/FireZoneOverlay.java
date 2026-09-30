package com.atakmap.android.atmosphere.overlay;

import android.content.Context;
import android.content.SharedPreferences;

import com.atakmap.android.atmosphere.compat.MapCompat;
import com.atakmap.android.atmosphere.compat.ScaleBar;
import com.atakmap.android.atmosphere.data.FireAlerts;
import com.atakmap.android.atmosphere.data.FireZones;
import com.atakmap.android.atmosphere.data.ZoneFavorites;
import com.atakmap.android.atmosphere.net.EgressPolicy;
import com.atakmap.android.atmosphere.net.Http;
import com.atakmap.android.maps.MapView;
import com.atakmap.coremap.log.Log;
import com.atakmap.coremap.maps.coords.GeoBounds;
import com.atakmap.map.AtakMapView;
import com.atakmap.map.layer.feature.AttributeSet;
import com.atakmap.map.layer.feature.geometry.Geometry;
import com.atakmap.map.layer.feature.geometry.GeometryCollection;
import com.atakmap.map.layer.feature.geometry.LineString;
import com.atakmap.map.layer.feature.geometry.Polygon;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;

/**
 * The NWS fire weather zones on the map: outlines with the zone number, shaded in
 * the NWS colors where a Red Flag Warning or a Fire Weather Watch is in effect, and
 * a tap that opens the zone's planning forecast on the Fire Weather Zones page.
 *
 * <p>Read-only reference data through {@link AtmosphereFeatures}, like the storms:
 * listed in Overlay Manager, nothing to recolor or send. Outlines only where
 * nothing is in effect, so radar, smoke and satellite read through them.
 *
 * <p>The zones are asked for the map's view, padded, from layer 9 of the NWS zone
 * service, and asked again when the view leaves that box or zooms well into it.
 * Wider than a few states the layer says to zoom in rather than draw a thousand
 * zones; zone numbers are drawn once the view is close enough to read them. The
 * view is what is sent, never a position.
 */
public final class FireZoneOverlay {
    private static final String TAG = "AtmosphereZoneLayer";
    public static final String LAYER_ID = "firezonelayer";
    public static final String NAME = "Fire Weather Zones";
    private static final String PREF_ON = "weather.layer.firezones.on";

    /**
     * The layer's own limit, degrees of longitude: wider than this it says to zoom
     * in whatever the gate says, because a view that wide is hundreds of zones.
     */
    private static final double MAX_SPAN_LON = 8d;
    /**
     * The zoom gates, the way the station, gauge and buoy layers keep theirs
     * (operator, 2026-09-29: "can i get a zoom gate like the others?"): the
     * coarsest meters per pixel at which the zones, and their numbers, still draw,
     * picked as a scale-bar reading. Defaults: zones at 50 of the big unit or
     * closer, numbers at 15.
     */
    private static final String PREF_GATE = "weather.layer.firezones.gate";
    private static final String PREF_LABEL_GATE = "weather.layer.firezones.labelgate";
    private static final double DEFAULT_BIG = 50d;
    private static final double DEFAULT_LABEL_BIG = 15d;
    private double gate, labelGate;
    private static final long MOVE_SETTLE_MS = 700L;
    /** The warnings are asked for again this often while the layer is on. */
    private static final long ALERTS_POLL_MS = 10 * 60 * 1000L;
    private static final int FILL_ALPHA = 0x59;
    /**
     * Orange, not white: white outlines read poorly over the satellite map
     * (operator, 2026-09-29: "drawing the boundaries in white is not good can they
     * be orange?"). Starred stays yellow and heavier so it still stands apart.
     */
    private static final int PLAIN = 0xFFFF8C1A;
    private static final int STARRED = 0xFFFFD84D;

    /** The key: what each look on the map means. */
    public static final String[][] LEGEND = {
            { FireAlerts.RED_FLAG, String.valueOf(FireAlerts.RED_FLAG_COLOR) },
            { FireAlerts.WATCH, String.valueOf(FireAlerts.WATCH_COLOR) },
            { "Starred zone", String.valueOf(STARRED) },
            { "Fire weather zone", String.valueOf(PLAIN) } };

    public interface Listener {
        void onStatus(String status);
    }

    private final MapView mapView;
    private final EgressPolicy egress;
    private final AtmosphereFeatures features;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final ZonePills pills = new ZonePills();
    private Listener listener;
    private String lastStatus = "";
    private boolean started, on;
    private int generation;
    /** The box the zones were asked for: west, south, east, north. */
    private double[] region;
    private boolean regionLabels;
    private String pendingKey;
    private List<FireZones.Zone> zones = new ArrayList<>();
    /** The zones drawn, by number, for the hit test, which runs off the main thread. */
    private volatile Map<String, FireZones.Zone> drawnById = Collections.emptyMap();
    private Map<String, FireAlerts.Status> alerts = Collections.emptyMap();

    private final Runnable moveSettled = new Runnable() {
        @Override
        public void run() {
            if (on)
                ensure(false);
        }
    };

    private final AtakMapView.OnMapMovedListener moved = new AtakMapView.OnMapMovedListener() {
        @Override
        public void onMapMoved(AtakMapView v, boolean animate) {
            // GL thread: post and coalesce, touch nothing here.
            mapView.removeCallbacks(moveSettled);
            mapView.postDelayed(moveSettled, MOVE_SETTLE_MS);
        }
    };

    private final Runnable alertsPoll = new Runnable() {
        @Override
        public void run() {
            if (!on || !started)
                return;
            fetchAlerts(true);
            mapView.postDelayed(this, ALERTS_POLL_MS);
        }
    };

    public FireZoneOverlay(MapView mapView, Context pluginContext, EgressPolicy egress) {
        this.mapView = mapView;
        this.egress = egress;
        this.features = new AtmosphereFeatures(mapView, pluginContext, TAG, NAME,
                "firezones.sqlite", "firezones", false);
        final SharedPreferences p = MapCompat.prefs();
        gate = storedGate(p, PREF_GATE, gsdForBig(DEFAULT_BIG));
        labelGate = storedGate(p, PREF_LABEL_GATE, gsdForBig(DEFAULT_LABEL_BIG));
        // A tap lists the zone it is inside, not every zone whose box holds it.
        features.setHitFilter(new AtmosphereFeatures.HitFilter() {
            @Override
            public boolean keep(com.atakmap.android.maps.MapItem item,
                    com.atakmap.coremap.maps.coords.GeoPoint tap) {
                final String id = item.getMetaString("zoneId", "");
                if (id.isEmpty())
                    return true;
                final FireZones.Zone z = drawnById.get(id);
                return z == null || z.contains(tap.getLatitude(), tap.getLongitude());
            }
        });
    }

    /** A new listener hears the last line at once; the pane is built after the layer. */
    public void setListener(Listener l) {
        listener = l;
        if (l != null && on && !lastStatus.isEmpty())
            l.onStatus(lastStatus);
    }

    public void start() {
        if (started)
            return;
        started = true;
        features.attach();
        final SharedPreferences p = MapCompat.prefs();
        if (p != null && p.getBoolean(PREF_ON, false) && egress.isLayerEnabled(LAYER_ID))
            setOn(true);
        if (!on)
            drawNothing();
    }

    public void stop() {
        started = false;
        on = false;
        generation++;
        mapView.removeOnMapMovedListener(moved);
        mapView.removeCallbacks(moveSettled);
        mapView.removeCallbacks(alertsPoll);
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
        mapView.removeCallbacks(moveSettled);
        mapView.removeCallbacks(alertsPoll);
        generation++;
        pendingKey = null;
        if (value) {
            mapView.addOnMapMovedListener(moved);
            fetchAlerts(false);
            ensure(true);
            mapView.postDelayed(alertsPoll, ALERTS_POLL_MS);
        } else {
            mapView.removeOnMapMovedListener(moved);
            zones = new ArrayList<>();
            region = null;
            drawNothing();
            status("");
        }
    }

    /** Called when the pane opens: the view may have moved while it was closed. */
    public void refresh() {
        if (!on)
            return;
        fetchAlerts(false);
        ensure(false);
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
        if (on)
            ensure(true);
    }

    public void setLabelGate(double metersPerPixel) {
        if (labelGate == metersPerPixel)
            return;
        labelGate = metersPerPixel;
        remember(PREF_LABEL_GATE, metersPerPixel);
        if (on)
            ensure(false);
    }

    /** A scale-bar distance as a map resolution, against this device's own bar. */
    private double gsdForBig(double big) {
        final double res = mapView.getMapResolution();
        final double m = res <= 0 ? 0 : ScaleBar.meters(mapView);
        final double barPx = m > 0 && res > 0 ? m / res : ScaleBar.FALLBACK_BAR_PIXELS;
        return ScaleBar.bigToMeters(big) / barPx;
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

    /** A zone was starred or unstarred; draw it the new way. */
    public void restyle() {
        if (on && !zones.isEmpty())
            rebuild(generation);
    }

    // ---- the zones in view --------------------------------------------------------------

    private void ensure(boolean force) {
        final GeoBounds b = mapView.getBounds();
        if (b == null)
            return;
        final double w = b.getWest(), e = b.getEast(), s = b.getSouth(), n = b.getNorth();
        final double res = mapView.getMapResolution();
        // A gate of "Always" is the largest float, which every resolution is under.
        if (Double.isNaN(w) || Double.isNaN(e) || Double.isNaN(s) || Double.isNaN(n)
                || e <= w || e - w > MAX_SPAN_LON || res > gate) {
            if (region != null || !zones.isEmpty() || force) {
                region = null;
                zones = new ArrayList<>();
                pendingKey = null;
                generation++;
                drawNothing();
            }
            status("Zoom in to see the fire weather zones");
            return;
        }
        final double span = e - w;
        final boolean labels = res <= labelGate;
        boolean refetch = force || region == null
                || w < region[0] || s < region[1] || e > region[2] || n > region[3];
        if (!refetch)
            refetch = span < (region[2] - region[0]) / 3.5;
        if (!refetch) {
            // Crossing the numbers' gate is a redraw of what is held, not a fetch.
            if (labels != regionLabels) {
                regionLabels = labels;
                rebuild(generation);
            }
            return;
        }
        final double padX = span * 0.25, padY = (n - s) * 0.25;
        final double[] r = { Math.max(-180, w - padX), Math.max(-85, s - padY),
                Math.min(180, e + padX), Math.min(85, n + padY) };
        // Generalized to about a thousandth of the view: finer than a pixel at any
        // zoom it is drawn at, and a tenth of the bytes of the full outlines.
        final double offset = Math.max(0.0005, Math.min(0.01, span / 1000d));
        final String key = String.format(Locale.US, "%.3f,%.3f,%.3f,%.3f,%b",
                r[0], r[1], r[2], r[3], labels);
        if (key.equals(pendingKey))
            return;
        pendingKey = key;
        final int mine = ++generation;
        if (zones.isEmpty())
            status("Getting the fire weather zones…");
        Http.getLarge(FireZones.boxUrl(r[0], r[1], r[2], r[3], offset), egress.userAgent(),
                new HashMap<String, String>(), new Http.Callback() {
                    @Override
                    public void onSuccess(final String body) {
                        if (key.equals(pendingKey))
                            pendingKey = null;
                        if (mine != generation || !on)
                            return;
                        offMain(new Runnable() {
                            @Override
                            public void run() {
                                final List<FireZones.Zone> parsed = FireZones.parse(body);
                                mapView.post(new Runnable() {
                                    @Override
                                    public void run() {
                                        if (mine != generation || !on)
                                            return;
                                        zones = parsed;
                                        region = r;
                                        regionLabels = labels;
                                        rebuild(mine);
                                    }
                                });
                            }
                        });
                    }

                    @Override
                    public void onFailure(String error) {
                        if (key.equals(pendingKey))
                            pendingKey = null;
                        if (mine != generation || !on)
                            return;
                        status("Fire weather zones: " + error);
                    }
                });
    }

    // ---- warnings and watches -----------------------------------------------------------

    private void fetchAlerts(boolean force) {
        final long now = System.currentTimeMillis();
        final Map<String, FireAlerts.Status> known = FireAlerts.cached(now, 5 * 60 * 1000L);
        if (known != null && !force) {
            if (!known.equals(alerts)) {
                alerts = known;
                restyle();
            }
            return;
        }
        Http.get(FireAlerts.URL, egress.userAgent(), null, new Http.Callback() {
            @Override
            public void onSuccess(String body) {
                final long at = System.currentTimeMillis();
                final Map<String, FireAlerts.Status> got = FireAlerts.parse(body, at);
                FireAlerts.remember(got, at);
                if (!on)
                    return;
                alerts = FireAlerts.last();
                restyle();
            }

            @Override
            public void onFailure(String error) {
                // The outlines still draw; the shading waits for the next poll.
                Log.w(TAG, "warnings: " + error);
            }
        });
    }

    // ---- drawing ------------------------------------------------------------------------

    private void rebuild(final int mine) {
        final List<FireZones.Zone> snapshot = new ArrayList<>(zones);
        final Map<String, FireAlerts.Status> warned = alerts;
        final boolean labels = regionLabels;
        final Set<String> starred = new HashSet<>();
        for (ZoneFavorites.Starred z : new ZoneFavorites(mapView.getContext()).all())
            starred.add(z.id);
        offMain(new Runnable() {
            @Override
            public void run() {
                if (mine != generation)
                    return;
                final List<AtmosphereFeatures.Drawn> drawn = new ArrayList<>();
                int red = 0, watch = 0;
                for (FireZones.Zone z : snapshot) {
                    final Geometry g = geometry(z);
                    if (g == null)
                        continue;
                    final String ugc = z.ugc();
                    final FireAlerts.Status st = warned.get(ugc);
                    // The number is a pill of its own at a point inside the zone, not
                    // ATAK's square label box: the operator asked for a true pill.
                    if (labels) {
                        final ZonePills.Pill pill = pills.pill(ugc);
                        final double[] at = pill == null ? null
                                : AtmosphereFeatures.labelPoint(g);
                        if (at != null) {
                            final AttributeSet la = new AttributeSet();
                            la.setAttribute("_labelOnly", 1);
                            drawn.add(new AtmosphereFeatures.Drawn(NAME, ugc + " " + z.name,
                                    AtmosphereFeatures.point(at[0], at[1]),
                                    AtmosphereFeatures.icon(pill.uri, pill.width, pill.height),
                                    la));
                        }
                    }
                    final com.atakmap.map.layer.feature.style.Style style;
                    if (st != null) {
                        if (st.isRedFlag())
                            red++;
                        else
                            watch++;
                        final int c = st.color();
                        style = AtmosphereFeatures.area(c, 3f,
                                (FILL_ALPHA << 24) | (c & 0x00FFFFFF));
                    } else if (starred.contains(z.id)) {
                        style = AtmosphereFeatures.area(STARRED, 3.5f, 0x00000000);
                    } else {
                        style = AtmosphereFeatures.area(PLAIN, 2f, 0x00000000);
                    }
                    drawn.add(new AtmosphereFeatures.Drawn(NAME,
                            ugc + " " + z.name, g, style, attributes(z, st)));
                }
                final Map<String, FireZones.Zone> byId = new HashMap<>();
                for (FireZones.Zone z : snapshot)
                    byId.put(z.id, z);
                drawnById = byId;
                features.rewrite(drawn);
                final int nDrawn = drawn.size(), nRed = red, nWatch = watch;
                mapView.post(new Runnable() {
                    @Override
                    public void run() {
                        if (mine != generation || !on)
                            return;
                        Log.d(TAG, "drew " + nDrawn + " zones, " + nRed + " red flag, "
                                + nWatch + " watch, labels " + labels);
                        status(line(nDrawn, nRed, nWatch, labels));
                    }
                });
            }
        });
    }

    /** The zone's polygons, holes in place; one polygon alone, several side by side. */
    private static Geometry geometry(FireZones.Zone z) {
        final List<Polygon> polys = new ArrayList<>();
        for (List<double[][]> rings : z.polygons()) {
            final Polygon p = new Polygon(2);
            for (double[][] ring : rings) {
                final LineString l = new LineString(2);
                int n = 0;
                for (double[] pt : ring) {
                    if (!sane(pt[0], pt[1]))
                        continue;
                    l.addPoint(pt[0], pt[1]);
                    n++;
                }
                if (n >= 3)
                    p.addRing(l);
            }
            if (p.getExteriorRing() != null)
                polys.add(p);
        }
        if (polys.isEmpty())
            return null;
        if (polys.size() == 1)
            return polys.get(0);
        // Flat: a collection of polygons, never a collection inside a collection,
        // which the feature store lands at 0,0.
        final GeometryCollection gc = new GeometryCollection(2);
        for (Polygon p : polys)
            gc.addGeometry(p);
        return gc;
    }

    /** Checked before native code sees it: a NaN in a ring is a crash with no stack. */
    private static boolean sane(double lon, double lat) {
        return !Double.isNaN(lon) && !Double.isNaN(lat) && !Double.isInfinite(lon)
                && !Double.isInfinite(lat) && lon >= -180 && lon <= 180 && lat >= -90
                && lat <= 90;
    }

    /** What a tap carries: the zone to open, and plain fields for the chooser. */
    private static AttributeSet attributes(FireZones.Zone z, FireAlerts.Status st) {
        final AttributeSet a = new AttributeSet();
        a.setAttribute("_zoneId", z.id);
        a.setAttribute("_zoneName", z.name);
        a.setAttribute("_zoneCwa", z.cwa);
        a.setAttribute("Zone", z.ugc() + " " + z.name);
        a.setAttribute("Office", z.cwa);
        if (st != null)
            a.setAttribute("In effect", st.event);
        return a;
    }

    private static String line(int drawn, int red, int watch, boolean labels) {
        if (drawn == 0)
            return "No fire weather zones in this view";
        final StringBuilder b = new StringBuilder();
        if (red == 0 && watch == 0)
            b.append("No Red Flag Warning or Fire Weather Watch in this view");
        else {
            if (red > 0)
                b.append(red).append(red == 1 ? " zone" : " zones")
                        .append(" under a Red Flag Warning");
            if (watch > 0)
                b.append(red > 0 ? ", " : "").append(watch)
                        .append(watch == 1 ? " zone" : " zones")
                        .append(" under a Fire Weather Watch");
        }
        if (!labels)
            b.append(". Zoom in for zone numbers");
        return b.toString();
    }

    private void drawNothing() {
        offMain(new Runnable() {
            @Override
            public void run() {
                features.rewrite(Collections.<AtmosphereFeatures.Drawn>emptyList());
            }
        });
    }

    private void offMain(Runnable r) {
        try {
            worker.execute(r);
        } catch (RejectedExecutionException e) {
            Log.d(TAG, "layer stopped, work dropped");
        }
    }

    private void status(String s) {
        lastStatus = s == null ? "" : s;
        if (listener != null)
            listener.onStatus(lastStatus);
    }
}
