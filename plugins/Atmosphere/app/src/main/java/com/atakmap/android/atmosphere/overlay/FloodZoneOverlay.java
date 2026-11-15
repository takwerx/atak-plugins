package com.atakmap.android.atmosphere.overlay;

import android.content.Context;
import android.content.SharedPreferences;

import com.atakmap.android.atmosphere.compat.MapCompat;
import com.atakmap.android.atmosphere.compat.ScaleBar;
import com.atakmap.android.atmosphere.data.FloodZones;
import com.atakmap.android.atmosphere.net.EgressPolicy;
import com.atakmap.android.atmosphere.net.Http;
import com.atakmap.android.maps.MapView;
import com.atakmap.coremap.filesystem.FileSystemUtils;
import com.atakmap.coremap.log.Log;
import com.atakmap.coremap.maps.coords.GeoBounds;
import com.atakmap.map.AtakMapView;
import com.atakmap.map.layer.feature.AttributeSet;
import com.atakmap.map.layer.feature.geometry.Geometry;
import com.atakmap.map.layer.feature.geometry.GeometryCollection;
import com.atakmap.map.layer.feature.geometry.LineString;
import com.atakmap.map.layer.feature.geometry.Polygon;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;

/**
 * FEMA's flood zones (the National Flood Hazard Layer) on the map: the 1% (100-year)
 * and coastal zones, the floodway on top, the 0.2% (500-year) band, areas behind a
 * levee and areas FEMA never studied. The "minimal flood hazard" X zones are not
 * drawn. This is the regulatory map, not a forecast: the Flash Flood Outlook and
 * Flooded Ground layers are the weather's side of flooding.
 *
 * <p>Reference data, so it is kept (CLAUDE.md, "Downloaded data survives a restart
 * with no network"): the zones come in fixed 0.1-degree tiles, each written to
 * {@code tools/atmosphere/floodzones/} in the plugin's own format, and a view is
 * drawn from those files first and fetched only where a tile is missing or older
 * than a month. A failed fetch keeps what the device has. The switch and the zoom
 * gate hide the feature sets; neither deletes anything.
 *
 * <p>Only the tiles' boxes are sent, never a position.
 */
public final class FloodZoneOverlay {
    private static final String TAG = "AtmosphereFloodZones";
    public static final String LAYER_ID = "floodzones";
    public static final String NAME = "FEMA Flood Zones";
    public static final String HOST = FloodZones.HOST;
    private static final String PREF_ON = "weather.layer.floodzones.on";
    private static final String PREF_GATE = "weather.layer.floodzones.gate";
    /** Default gate: 3 of the big unit or closer on the scale bar (PLAN, 2026-11-14). */
    private static final double DEFAULT_BIG = 3d;
    private static final double GATE_SLACK = 1.01;
    /**
     * The layer's own limit past any gate: more tiles than this in view and it says to
     * zoom in. 30 tiles is about 6 by 5 of them, 35 miles across at mid latitudes, and
     * a dense city can be a few hundred zones a tile.
     */
    private static final int MAX_TILES = 30;
    private static final long MOVE_SETTLE_MS = 700L;
    /** FEMA's map changes by letters of map revision, months apart: a month is fresh. */
    private static final long FRESH_MS = 30L * 24 * 60 * 60 * 1000;
    /** Disk the tiles may take before the oldest go. */
    private static final long CAP_BYTES = 40L * 1024 * 1024;
    /** Tiles held in memory, beyond the view's own. */
    private static final int MEMORY_TILES = 120;
    private static final String DIR = "tools/atmosphere/floodzones";

    /** Edge and fill per class, in {@link FloodZones} class order. */
    // FEMA's own NFHL legend (MapServer/28 drawingInfo): 1% cyan, floodway red,
    // 0.2% orange, D pale yellow, levee black. Two departures for a dark map:
    // FEMA draws V/VE in the same cyan as A, so coastal is violet here; and
    // black does not show, so the levee is light grey.
    private static final int[] EDGE = {
            0xFFF2E673, // not studied (D): FEMA pale yellow
            0xFFBDBDBD, // levee: light grey
            0xFFFF8000, // 0.2%: FEMA orange
            0xFF00E6FF, // 1%: FEMA cyan
            0xFFB060FF, // coastal V/VE: violet
            0xFFFF0000, // floodway: FEMA red
    };
    private static final int[] FILL = {
            0x00000000, 0x4DBDBDBD, 0x40FF8000, 0x4D00E6FF, 0x59B060FF, 0x66FF0000 };
    private static final float[] WEIGHT = { 1.5f, 1.2f, 1.2f, 1.5f, 2.5f, 2f };

    /** The key, top of the pile first. */
    public static final String[][] LEGEND = {
            { "Floodway: keep clear, deep fast water", String.valueOf(EDGE[FloodZones.FLOODWAY]) },
            { "Coastal 1% chance with waves (V, VE)", String.valueOf(EDGE[FloodZones.COASTAL]) },
            { "1% chance a year, 100-year (A zones)", String.valueOf(EDGE[FloodZones.ONE_PCT]) },
            { "0.2% chance a year, 500-year", String.valueOf(EDGE[FloodZones.MODERATE]) },
            { "Behind a levee", String.valueOf(EDGE[FloodZones.LEVEE]) },
            { "Not studied by FEMA (zone D)", String.valueOf(EDGE[FloodZones.NOT_STUDIED]) } };

    public interface Listener {
        void onStatus(String status);
    }

    private final MapView mapView;
    private final EgressPolicy egress;
    private final AtmosphereFeatures features;
    /** Disk, memory and the store: one thread, so they never race. */
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    /** The fetches: two at a time, so a view of tiles fills in without a burst. */
    private final ExecutorService fetcher = Executors.newFixedThreadPool(2);
    private Listener listener;
    private String lastStatus = "";
    private boolean started, on;
    private double gate;
    /**
     * Whether the operator picked a gate. Until then the default is worked out from
     * the live scale bar each time: at plugin start the bar is not drawn yet, and the
     * fallback bar width read 3 mi as 2.44 mi (s10-dev-2, 2026-11-14).
     */
    private boolean gateChosen;
    /** Whether the sets are shown: on, under the gate and not too wide. */
    private volatile boolean visible;
    private int generation;

    // Worker-only state.
    private final LinkedHashMap<String, FloodZones.Tile> memory =
            new LinkedHashMap<String, FloodZones.Tile>(64, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, FloodZones.Tile> e) {
                    return size() > MEMORY_TILES;
                }
            };
    private List<String> viewTiles = Collections.emptyList();
    private String drawnKey = "";
    private final Set<String> failed = new HashSet<>();

    private final Set<String> inFlight = Collections.newSetFromMap(
            new ConcurrentHashMap<String, Boolean>());
    /** The zones drawn, by FEMA id, for the hit test, which runs off the main thread. */
    private volatile Map<Long, FloodZones.Zone> drawnById = Collections.emptyMap();

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

    public FloodZoneOverlay(MapView mapView, Context pluginContext, EgressPolicy egress) {
        this.mapView = mapView;
        this.egress = egress;
        this.features = new AtmosphereFeatures(mapView, pluginContext, TAG, NAME,
                "floodzones.sqlite", "floodzones", false);
        final SharedPreferences prefs = MapCompat.prefs();
        gateChosen = prefs != null && prefs.contains(PREF_GATE);
        gate = storedGate(prefs, gsdForBig(DEFAULT_BIG));
        // Flat fills: see AtmosphereFeatures.setRhumb.
        features.setRhumb(true);
        features.setSetFilter(new AtmosphereFeatures.SetFilter() {
            @Override
            public boolean shown(String setName) {
                return visible;
            }
        });
        // A tap lists the zone it is inside, not every zone whose box holds it.
        features.setHitFilter(new AtmosphereFeatures.HitFilter() {
            @Override
            public boolean keep(com.atakmap.android.maps.MapItem item,
                    com.atakmap.coremap.maps.coords.GeoPoint tap) {
                final String id = item.getMetaString("floodId", "");
                if (id.isEmpty())
                    return true;
                try {
                    final FloodZones.Zone z = drawnById.get(Long.parseLong(id));
                    return z == null || z.contains(tap.getLatitude(), tap.getLongitude());
                } catch (NumberFormatException e) {
                    return true;
                }
            }
        });
    }

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
    }

    public void stop() {
        started = false;
        on = false;
        generation++;
        mapView.removeOnMapMovedListener(moved);
        mapView.removeCallbacks(moveSettled);
        fetcher.shutdownNow();
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
        generation++;
        if (value) {
            mapView.addOnMapMovedListener(moved);
            ensure(true);
        } else {
            mapView.removeOnMapMovedListener(moved);
            show(false);
            status("");
        }
    }

    /** Called when the pane opens: the view may have moved while it was closed. */
    public void refresh() {
        // Forced, so a tile that failed earlier is asked for again.
        if (on)
            ensure(true);
    }

    public double gate() {
        if (!gateChosen)
            gate = gsdForBig(DEFAULT_BIG);
        return gate;
    }

    public void setGate(double metersPerPixel) {
        if (gateChosen && gate == metersPerPixel)
            return;
        gate = metersPerPixel;
        gateChosen = true;
        final SharedPreferences p = MapCompat.prefs();
        if (p != null)
            p.edit().putFloat(PREF_GATE, (float) Math.min(metersPerPixel, Float.MAX_VALUE))
                    .apply();
        if (on)
            ensure(false);
    }

    private double gsdForBig(double big) {
        final double res = mapView.getMapResolution();
        final double m = res <= 0 ? 0 : ScaleBar.meters(mapView);
        final double barPx = m > 0 && res > 0 ? m / res : ScaleBar.FALLBACK_BAR_PIXELS;
        return ScaleBar.bigToMeters(big) / barPx;
    }

    private static double storedGate(SharedPreferences p, double fallback) {
        if (p == null)
            return fallback;
        try {
            return p.getFloat(PREF_GATE, (float) Math.min(fallback, Float.MAX_VALUE));
        } catch (ClassCastException oldFormat) {
            p.edit().remove(PREF_GATE).apply();
            return fallback;
        }
    }

    /** Whether the view holds more tiles than the layer draws, whatever the gate. */
    public boolean tooWide() {
        // Only when the zoom gate lets the zones through: zoomed out past the gate,
        // the gate is the reason and its own line already says "hidden". Both at once
        // read "hidden -- hidden, the view is too wide" (s10-dev-2, 2026-11-14).
        if (mapView.getMapResolution() > gate() * GATE_SLACK)
            return false;
        final GeoBounds b = mapView.getBounds();
        return b != null && tiles(b) == null;
    }

    /** The scale-bar reading at which the view is as wide as the layer draws. */
    public String widestBar() {
        final GeoBounds b = mapView.getBounds();
        final double m = ScaleBar.meters(mapView);
        if (b == null || m <= 0 || b.getEast() <= b.getWest())
            return "a few miles across";
        // About six tiles across.
        final double widest = 6 * FloodZones.TILE;
        return ScaleBar.describe(m * widest / (b.getEast() - b.getWest()));
    }

    // ---- the view -------------------------------------------------------------------

    /** The tiles a view covers, or null for more than the layer draws. */
    private static List<String> tiles(GeoBounds b) {
        final double w = b.getWest(), e = b.getEast(), s = b.getSouth(), n = b.getNorth();
        if (Double.isNaN(w) || Double.isNaN(e) || Double.isNaN(s) || Double.isNaN(n) || e <= w
                || n <= s)
            return null;
        final int x0 = FloodZones.tileX(w), x1 = FloodZones.tileX(e);
        final int y0 = FloodZones.tileY(Math.max(-89.9, s)), y1 = FloodZones.tileY(Math.min(89.9, n));
        final long count = (long) (x1 - x0 + 1) * (y1 - y0 + 1);
        if (count > MAX_TILES || count <= 0)
            return null;
        final List<String> out = new ArrayList<>();
        for (int y = y0; y <= y1; y++)
            for (int x = x0; x <= x1; x++)
                out.add(x + "_" + y);
        return out;
    }

    private void ensure(final boolean force) {
        final GeoBounds b = mapView.getBounds();
        if (b == null)
            return;
        final double res = mapView.getMapResolution();
        final boolean gated = res > gate() * GATE_SLACK;
        final List<String> want = gated ? null : tiles(b);
        if (gated || want == null) {
            show(false);
            if (!gated)
                status("This view is too wide for flood zones. Zoom in to about "
                        + widestBar() + " on the scale bar.");
            else
                status("Zoom in to see FEMA flood zones");
            return;
        }
        show(true);
        Log.d(TAG, String.format(Locale.US, "view %.3f,%.3f,%.3f,%.3f: %d tiles",
                b.getWest(), b.getSouth(), b.getEast(), b.getNorth(), want.size()));
        final int mine = generation;
        offMain(new Runnable() {
            @Override
            public void run() {
                load(want, force, mine);
            }
        });
    }

    private void show(final boolean value) {
        if (visible == value)
            return;
        visible = value;
        Log.d(TAG, "sets shown: " + value);
        offMain(new Runnable() {
            @Override
            public void run() {
                features.applySetFilter();
            }
        });
    }

    // ---- worker ---------------------------------------------------------------------

    /** Draw what the device has for these tiles, and fetch what is missing or old. */
    private void load(List<String> want, boolean force, int mine) {
        if (mine != generation)
            return;
        viewTiles = want;
        if (force)
            failed.clear();
        final long now = System.currentTimeMillis();
        final List<String> fetch = new ArrayList<>();
        for (String key : want) {
            FloodZones.Tile t = memory.get(key);
            if (t == null) {
                t = readTile(key);
                if (t != null)
                    memory.put(key, t);
            }
            if ((t == null || now - t.fetched > FRESH_MS) && !failed.contains(key))
                fetch.add(key);
        }
        draw(false);
        for (final String key : fetch) {
            if (!inFlight.add(key))
                continue;
            try {
                fetcher.execute(new Runnable() {
                    @Override
                    public void run() {
                        fetchTile(key);
                    }
                });
            } catch (RejectedExecutionException e) {
                inFlight.remove(key);
                return;
            }
        }
        if (!fetch.isEmpty())
            draw(false);
    }

    /** On a fetch thread: one tile, every page, written to disk. */
    private void fetchTile(final String key) {
        FloodZones.Tile got = null;
        try {
            final String[] xy = key.split("_");
            final double[] box = FloodZones.tileBox(Integer.parseInt(xy[0]),
                    Integer.parseInt(xy[1]));
            final String ua = egress.userAgent();
            final int studies = FloodZones.count(get(FloodZones.availabilityUrl(box), ua));
            final List<FloodZones.Zone> zones = new ArrayList<>();
            if (studies > 0) {
                final Set<Long> seen = new HashSet<>();
                int offset = 0;
                for (int page = 0; ; page++) {
                    if (page >= FloodZones.MAX_PAGES)
                        throw new IOException("too many pages for one tile");
                    final JSONObject o = FloodZones.answer(
                            get(FloodZones.zonesUrl(box, offset), ua));
                    for (FloodZones.Zone z : FloodZones.parsePage(o))
                        if (seen.add(z.id))
                            zones.add(z);
                    // Uneven pages (notes): stop on the flag only, never on a short
                    // or empty page, and step by what was asked for.
                    if (!FloodZones.exceeded(o))
                        break;
                    offset += FloodZones.PAGE;
                }
            }
            got = new FloodZones.Tile(System.currentTimeMillis(), studies > 0, zones);
            writeTile(key, got);
            Log.d(TAG, "tile " + key + ": " + zones.size() + " zones"
                    + (studies > 0 ? "" : ", no FEMA map"));
        } catch (Exception e) {
            Log.w(TAG, "tile " + key + " failed: " + e.getMessage());
        }
        final FloodZones.Tile done = got;
        offMain(new Runnable() {
            @Override
            public void run() {
                inFlight.remove(key);
                if (done != null) {
                    memory.put(key, done);
                    failed.remove(key);
                } else {
                    failed.add(key);
                }
                if (viewTiles.contains(key))
                    draw(true);
                if (done != null)
                    prune();
            }
        });
    }

    private static String get(String url, String ua) throws IOException {
        return new String(Http.fetchBytes(url, ua), "UTF-8");
    }

    /** Rewrite the store when the view's zones changed, then say what is shown. */
    private void draw(boolean tileArrived) {
        int pending = 0;
        for (String k : viewTiles)
            if (inFlight.contains(k))
                pending++;
        // Redraw when the last tile of the view is in, or the first time the view
        // has anything; not once per tile, which is a store rewrite each.
        final StringBuilder keyB = new StringBuilder();
        final List<FloodZones.Tile> have = new ArrayList<>();
        int unmapped = 0, missing = 0;
        long oldest = Long.MAX_VALUE;
        for (String k : viewTiles) {
            final FloodZones.Tile t = memory.get(k);
            if (t == null) {
                missing++;
                continue;
            }
            have.add(t);
            keyB.append(k).append('@').append(t.fetched).append(';');
            if (!t.mapped)
                unmapped++;
            oldest = Math.min(oldest, t.fetched);
        }
        final String key = keyB.toString();
        if (!key.equals(drawnKey) && (pending == 0 || drawnKey.isEmpty() || !tileArrived)) {
            drawnKey = key;
            rewrite(have);
        }
        final Map<Long, FloodZones.Zone> byId = drawnById;
        final int[] perClass = new int[FloodZones.CLASSES];
        for (FloodZones.Zone z : byId.values())
            perClass[z.cls]++;
        int failedHere = 0;
        for (String k : viewTiles)
            if (failed.contains(k))
                failedHere++;
        final String line = line(viewTiles.size(), pending, missing, unmapped, failedHere,
                oldest, byId.size(), perClass);
        mapView.post(new Runnable() {
            @Override
            public void run() {
                if (on && visible)
                    status(line);
            }
        });
    }

    /** The zones of these tiles into the store, once each, bottom class first. */
    private void rewrite(List<FloodZones.Tile> tiles) {
        final Map<Long, FloodZones.Zone> byId = new HashMap<>();
        final List<FloodZones.Zone> all = new ArrayList<>();
        for (FloodZones.Tile t : tiles)
            for (FloodZones.Zone z : t.zones)
                if (byId.put(z.id, z) == null)
                    all.add(z);
        // Inserted in class order: the store draws in insert order (Order.ID), so the
        // floodway lands on top of the 1% zone around it.
        Collections.sort(all, new Comparator<FloodZones.Zone>() {
            @Override
            public int compare(FloodZones.Zone a, FloodZones.Zone b) {
                if (a.cls != b.cls)
                    return a.cls - b.cls;
                return Long.compare(a.id, b.id);
            }
        });
        final List<AtmosphereFeatures.Drawn> drawn = new ArrayList<>(all.size());
        for (FloodZones.Zone z : all) {
            final Geometry g = geometry(z);
            if (g == null)
                continue;
            drawn.add(new AtmosphereFeatures.Drawn(FloodZones.CLASS_NAMES[z.cls],
                    FloodZones.title(z), g,
                    AtmosphereFeatures.area(EDGE[z.cls], WEIGHT[z.cls], FILL[z.cls]),
                    attributes(z)));
        }
        drawnById = byId;
        features.rewrite(drawn);
        final int[] per = new int[FloodZones.CLASSES];
        double w = 180, so = 90, e = -180, n = -90;
        for (FloodZones.Zone z : all) {
            per[z.cls]++;
            for (List<double[]> poly : z.polygons) {
                final double[] r = poly.get(0);
                for (int i = 0; i + 1 < r.length; i += 2) {
                    w = Math.min(w, r[i]);
                    e = Math.max(e, r[i]);
                    so = Math.min(so, r[i + 1]);
                    n = Math.max(n, r[i + 1]);
                }
            }
        }
        Log.d(TAG, String.format(Locale.US,
                "drew %d zones from %d tiles, per class %s, box %.3f,%.3f,%.3f,%.3f, shown %b",
                drawn.size(), tiles.size(), Arrays.toString(per), w, so, e, n, visible));
    }

    private static Geometry geometry(FloodZones.Zone z) {
        final List<Polygon> polys = new ArrayList<>();
        for (List<double[]> rings : z.polygons) {
            final Polygon p = new Polygon(2);
            for (double[] ring : rings) {
                final LineString l = new LineString(2);
                for (int i = 0; i + 1 < ring.length; i += 2)
                    l.addPoint(ring[i], ring[i + 1]);
                if (ring.length >= 8)
                    p.addRing(l);
            }
            if (p.getExteriorRing() != null)
                polys.add(p);
        }
        if (polys.isEmpty())
            return null;
        if (polys.size() == 1)
            return polys.get(0);
        // Flat: never a collection inside a collection, which lands at 0,0.
        final GeometryCollection gc = new GeometryCollection(2);
        for (Polygon p : polys)
            gc.addGeometry(p);
        return gc;
    }

    private static AttributeSet attributes(FloodZones.Zone z) {
        final AttributeSet a = new AttributeSet();
        a.setAttribute("_floodId", String.valueOf(z.id));
        a.setAttribute("_details", FloodZones.details(z));
        return a;
    }

    /** What the status line says: what is drawn, and what is not and why. */
    private static String line(int tiles, int pending, int missing, int unmapped, int failed,
            long oldest, int drawn, int[] perClass) {
        final StringBuilder b = new StringBuilder();
        if (missing == tiles && pending > 0)
            return "Getting FEMA flood zones…";
        if (missing == tiles && failed > 0)
            return "Could not reach FEMA for flood zones. This device has none saved for this"
                    + " view.";
        if (drawn > 0) {
            // Which kinds are here, not how many: a count of polygons means nothing
            // to a crew (field operators, not data engineers).
            b.append("In this view: ");
            final int[] order = { FloodZones.FLOODWAY, FloodZones.COASTAL, FloodZones.ONE_PCT,
                    FloodZones.MODERATE, FloodZones.LEVEE, FloodZones.NOT_STUDIED };
            final String[] words = { "floodway", "coastal 1%", "1% (100-year)",
                    "0.2% (500-year)", "behind a levee", "not studied" };
            int n = 0;
            for (int i = 0; i < order.length; i++)
                if (perClass[order[i]] > 0)
                    b.append(n++ > 0 ? ", " : "").append(words[i]);
        } else if (unmapped >= tiles - missing && tiles - missing > 0) {
            b.append("FEMA has no digital flood map for this view");
        } else {
            b.append("FEMA shows no 1% or 0.2% flood zone in this view");
        }
        if (unmapped > 0 && unmapped < tiles - missing)
            b.append(". FEMA has no digital flood map for part of this view");
        if (pending > 0)
            b.append(". Getting more…");
        else if (failed > 0 && missing > 0)
            b.append(". Part of this view could not be loaded from FEMA");
        else if (failed > 0 && oldest != Long.MAX_VALUE)
            b.append(". Could not reach FEMA, showing what this device saved ")
                    .append(ago(oldest));
        return b.toString();
    }

    private static String ago(long t) {
        final long min = Math.max(0, (System.currentTimeMillis() - t) / 60000L);
        if (min < 90)
            return min + " min ago";
        final long h = min / 60;
        if (h < 48)
            return h + " h ago";
        return (h / 24) + " days ago";
    }

    // ---- disk -----------------------------------------------------------------------

    private static File dir() {
        final File d = FileSystemUtils.getItem(DIR);
        if (!d.isDirectory() && !d.mkdirs())
            Log.w(TAG, "could not make " + DIR);
        return d;
    }

    /** The file of a tile. The name is built from two integers only, never server text. */
    private static File file(String key) {
        final String[] xy = key.split("_");
        final int x = Integer.parseInt(xy[0]), y = Integer.parseInt(xy[1]);
        return new File(dir(), String.format(Locale.US, "z_%d_%d.json", x, y));
    }

    private static FloodZones.Tile readTile(String key) {
        final File f = file(key);
        if (!f.isFile() || f.length() > 16L * 1024 * 1024)
            return null;
        try (InputStream in = new FileInputStream(f)) {
            final byte[] buf = new byte[(int) f.length()];
            int n = 0, r;
            while (n < buf.length && (r = in.read(buf, n, buf.length - n)) > 0)
                n += r;
            return FloodZones.fromJson(new String(buf, 0, n, "UTF-8"));
        } catch (IOException | RuntimeException e) {
            Log.w(TAG, "could not read tile " + key, e);
            return null;
        }
    }

    private static void writeTile(String key, FloodZones.Tile t) throws Exception {
        final File f = file(key);
        final File tmp = new File(f.getPath() + ".tmp");
        try (OutputStream out = new FileOutputStream(tmp)) {
            out.write(FloodZones.toJson(t.fetched, t.mapped, t.zones).getBytes("UTF-8"));
        }
        if (!tmp.renameTo(f)) {
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
            throw new IOException("could not keep tile " + key);
        }
    }

    /** Keep the tiles under the cap, oldest written first out; never the view's own. */
    private void prune() {
        final File[] files = dir().listFiles();
        if (files == null)
            return;
        long total = 0;
        for (File f : files)
            total += f.length();
        if (total <= CAP_BYTES)
            return;
        Arrays.sort(files, new Comparator<File>() {
            @Override
            public int compare(File a, File b) {
                return Long.compare(a.lastModified(), b.lastModified());
            }
        });
        final Set<String> keep = new HashSet<>();
        for (String k : viewTiles)
            keep.add(file(k).getName());
        for (File f : files) {
            if (total <= CAP_BYTES)
                break;
            if (keep.contains(f.getName()))
                continue;
            final long len = f.length();
            if (f.delete())
                total -= len;
        }
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
