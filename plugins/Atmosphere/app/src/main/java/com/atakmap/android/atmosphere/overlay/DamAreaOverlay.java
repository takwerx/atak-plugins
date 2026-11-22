package com.atakmap.android.atmosphere.overlay;

import android.content.Context;
import android.content.SharedPreferences;

import com.atakmap.android.atmosphere.compat.MapCompat;
import com.atakmap.android.atmosphere.compat.ScaleBar;
import com.atakmap.android.atmosphere.data.DamAreas;
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

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.zip.GZIPInputStream;

/**
 * California's dam failure inundation areas on the map: what would flood if a dam,
 * or its spillway, outlet or a saddle dam, failed suddenly. California only.
 *
 * <p>Reference data, kept (CLAUDE.md, "Downloaded data survives a restart with no
 * network"): the half-degree tiles of our pack are fetched where the map is and
 * written gzipped to {@code tools/atmosphere/dams/}; a view draws from those files
 * first. The pack changes only when the state's copy does, so a tile is kept until
 * the manifest names a new build. The switch and the zoom gate hide the sets.
 *
 * <p>Nothing about the device is sent: the requests name only tiles of our own pack.
 */
public final class DamAreaOverlay {
    private static final String TAG = "AtmosphereDams";
    public static final String LAYER_ID = "damareas";
    public static final String NAME = "Dam Failure Areas";
    public static final String HOST = DamAreas.HOST;
    private static final String PREF_ON = "weather.layer.damareas.on";
    private static final String PREF_GATE = "weather.layer.damareas.gate";
    /** Default gate: 15 of the big unit on the scale bar; a dam's area runs for miles. */
    private static final double DEFAULT_BIG = 15d;
    private static final double GATE_SLACK = 1.01;
    /** At most this many half-degree tiles in view: about 100 miles across. */
    private static final int MAX_TILES = 9;
    private static final long MOVE_SETTLE_MS = 700L;
    /** How often the manifest is asked again for a new build. */
    private static final long MANIFEST_MS = 30L * 24 * 60 * 60 * 1000;
    private static final int MEMORY_TILES = 12;
    /** A tile unpacks to at most this; the largest built was 7.5 MB (2026-11-14). */
    private static final int MAX_INFLATED = 24 * 1024 * 1024;
    private static final String DIR = "tools/atmosphere/dams";
    /** Closer than this, the tap is "at" the dam: NID's point is the dam, not its ends. */
    private static final double HERE_M = 400;

    /** One look for every area: magenta, apart from the FEMA zones' colors. */
    private static final int EDGE = 0xFFFF3DB4;
    private static final int FILL = 0x2EFF3DB4;
    private static final float WEIGHT = 2f;

    public static final String[][] LEGEND = {
            { "Would flood if the dam failed", String.valueOf(EDGE) } };

    public interface Listener {
        void onStatus(String status);
    }

    private final MapView mapView;
    private final EgressPolicy egress;
    private final AtmosphereFeatures features;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final ExecutorService fetcher = Executors.newFixedThreadPool(2);
    private Listener listener;
    private String lastStatus = "";
    private boolean started, on;
    private double gate;
    private boolean gateChosen;
    private volatile boolean visible;
    private int generation;

    // Worker-only state.
    private DamAreas.Manifest manifest;
    private boolean manifestFailed;
    private final LinkedHashMap<String, List<DamAreas.Area>> memory =
            new LinkedHashMap<String, List<DamAreas.Area>>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(
                        Map.Entry<String, List<DamAreas.Area>> e) {
                    return size() > MEMORY_TILES;
                }
            };
    private List<String> viewTiles = Collections.emptyList();
    private boolean viewInCalifornia = true;
    private String drawnKey = "";
    private final Set<String> failed = new HashSet<>();
    private final Set<String> inFlight = Collections.newSetFromMap(
            new ConcurrentHashMap<String, Boolean>());
    private volatile Map<Long, DamAreas.Area> drawnById = Collections.emptyMap();
    /** The view's box, w s e n, set on main in ensure() for the worker's status line. */
    private volatile double[] viewBox;

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

    public DamAreaOverlay(MapView mapView, Context pluginContext, EgressPolicy egress) {
        this.mapView = mapView;
        this.egress = egress;
        this.features = new AtmosphereFeatures(mapView, pluginContext, TAG, NAME,
                "damareas.sqlite", "damareas", false);
        final SharedPreferences prefs = MapCompat.prefs();
        gateChosen = prefs != null && prefs.contains(PREF_GATE);
        gate = storedGate(prefs, gsdForBig(DEFAULT_BIG));
        // Flat fills, as the flood zones: see AtmosphereFeatures.setRhumb.
        features.setRhumb(true);
        features.setSetFilter(new AtmosphereFeatures.SetFilter() {
            @Override
            public boolean shown(String setName) {
                return visible;
            }
        });
        features.setTapNote(new AtmosphereFeatures.TapNote() {
            @Override
            public void note(com.atakmap.android.maps.MapItem item,
                    com.atakmap.coremap.maps.coords.GeoPoint tap) {
                noteWhereTheDamIs(item, tap);
            }
        });
        // A tap lists the areas it is inside, not every area whose box holds it.
        // A tap lists the areas it is inside, one row per dam: the first area of a dam
        // that holds the tap stands for all of that dam's areas there.
        features.setHitFilter(new AtmosphereFeatures.HitFilter() {
            @Override
            public boolean keep(com.atakmap.android.maps.MapItem item,
                    com.atakmap.coremap.maps.coords.GeoPoint tap) {
                final DamAreas.Area a = areaOf(item);
                if (a == null)
                    return true;
                if (!a.contains(tap.getLatitude(), tap.getLongitude()))
                    return false;
                final List<DamAreas.Area> here = sameDamAt(a, tap);
                return here.isEmpty() || here.get(0).id == a.id;
            }
        });
    }

    private DamAreas.Area areaOf(com.atakmap.android.maps.MapItem item) {
        final String id = item.getMetaString("damId", "");
        if (id.isEmpty())
            return null;
        try {
            return drawnById.get(Long.parseLong(id));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** The drawn areas of a's dam that hold the tap, by id. */
    private List<DamAreas.Area> sameDamAt(DamAreas.Area a,
            com.atakmap.coremap.maps.coords.GeoPoint tap) {
        final String key = DamAreas.damKey(a);
        final List<DamAreas.Area> out = new ArrayList<>();
        for (DamAreas.Area o : drawnById.values())
            if (key.equals(DamAreas.damKey(o))
                    && o.contains(tap.getLatitude(), tap.getLongitude()))
                out.add(o);
        Collections.sort(out, new Comparator<DamAreas.Area>() {
            @Override
            public int compare(DamAreas.Area x, DamAreas.Area y) {
                return Long.compare(x.id, y.id);
            }
        });
        return out;
    }

    /**
     * The tap's details: where the dam is from the tap, and every failure of that dam
     * whose area holds the tap. The title covers them all.
     */
    private void noteWhereTheDamIs(com.atakmap.android.maps.MapItem item,
            com.atakmap.coremap.maps.coords.GeoPoint tap) {
        if (item.hasMetaValue("_damNoted"))
            return;
        final DamAreas.Area a = areaOf(item);
        if (a == null)
            return;
        List<DamAreas.Area> here = sameDamAt(a, tap);
        if (here.isEmpty())
            here = Collections.singletonList(a);
        String where = "";
        if (DamAreas.hasDam(a)) {
            final com.atakmap.coremap.maps.coords.GeoPoint dam =
                    new com.atakmap.coremap.maps.coords.GeoPoint(a.damLat, a.damLon);
            final double m = tap.distanceTo(dam);
            where = DamAreas.whereIsDam(a, ScaleBar.describe(m), tap.bearingTo(dam),
                    m < HERE_M);
        }
        final String title = DamAreas.tapTitle(here);
        item.setMetaString("remarks", DamAreas.tapDetails(here, where));
        item.setMetaString("title", title);
        item.setMetaString("callsign", title);
        item.setTitle(title);
        item.setMetaBoolean("_damNoted", true);
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

    /** Whether the view holds more tiles than the layer draws, when the gate lets it. */
    public boolean tooWide() {
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
            return "about 75 miles across";
        final double widest = 3 * DamAreas.TILE;
        return ScaleBar.describe(m * widest / (b.getEast() - b.getWest()));
    }

    // ---- the view -------------------------------------------------------------------

    private static List<String> tiles(GeoBounds b) {
        final double w = b.getWest(), e = b.getEast(), s = b.getSouth(), n = b.getNorth();
        if (Double.isNaN(w) || Double.isNaN(e) || Double.isNaN(s) || Double.isNaN(n) || e <= w
                || n <= s)
            return null;
        final int x0 = DamAreas.tileX(w), x1 = DamAreas.tileX(e);
        final int y0 = DamAreas.tileY(s), y1 = DamAreas.tileY(n);
        if ((long) (x1 - x0 + 1) * (y1 - y0 + 1) > MAX_TILES)
            return null;
        final List<String> out = new ArrayList<>();
        for (int y = y0; y <= y1; y++)
            for (int x = x0; x <= x1; x++)
                out.add(DamAreas.key(x, y));
        return out;
    }

    private void ensure(final boolean force) {
        final GeoBounds b = mapView.getBounds();
        if (b == null)
            return;
        final boolean inCa = DamAreas.touchesCalifornia(b.getWest(), b.getSouth(),
                b.getEast(), b.getNorth());
        final double res = mapView.getMapResolution();
        final boolean gated = res > gate() * GATE_SLACK;
        final List<String> want = gated || !inCa ? null : tiles(b);
        if (!inCa) {
            show(false);
            status("California only: dam failure areas come from California's dam"
                    + " safety office");
            return;
        }
        if (gated || want == null) {
            show(false);
            if (!gated)
                status("This view is too wide for dam failure areas. Zoom in to about "
                        + widestBar() + " on the scale bar.");
            else
                status("Zoom in to see dam failure areas");
            return;
        }
        show(true);
        viewBox = new double[] { b.getWest(), b.getSouth(), b.getEast(), b.getNorth() };
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
        offMain(new Runnable() {
            @Override
            public void run() {
                features.applySetFilter();
            }
        });
    }

    // ---- worker ---------------------------------------------------------------------

    private void load(List<String> want, boolean force, int mine) {
        if (mine != generation)
            return;
        viewTiles = want;
        if (force) {
            failed.clear();
            manifestFailed = false;
        }
        if (!haveManifest()) {
            draw(false);
            return;
        }
        final List<String> fetch = new ArrayList<>();
        for (String key : want) {
            if (!manifest.tiles.contains(key) || memory.containsKey(key))
                continue;
            final List<DamAreas.Area> t = readTile(key);
            if (t != null)
                memory.put(key, t);
            else if (!failed.contains(key))
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

    /**
     * The manifest, from disk, and asked again once a month. A new build clears the
     * kept tiles, which belong to the old one. With no network the kept manifest
     * stands, however old.
     */
    private boolean haveManifest() {
        if (manifest == null)
            manifest = readManifest();
        final File f = new File(dir(), "dams.json");
        final boolean old = !f.isFile()
                || System.currentTimeMillis() - f.lastModified() > MANIFEST_MS;
        if ((manifest == null || old) && !manifestFailed) {
            try {
                final String body = new String(Http.fetchBytes(DamAreas.manifestUrl(),
                        egress.userAgent()), "UTF-8");
                final DamAreas.Manifest m = DamAreas.manifest(body);
                if (manifest != null && !manifest.built.equals(m.built))
                    clearTiles();
                writeAtomically(f, body.getBytes("UTF-8"));
                manifest = m;
            } catch (Exception e) {
                Log.w(TAG, "dam pack manifest failed: " + e.getMessage());
                manifestFailed = true;
            }
        }
        return manifest != null;
    }

    private void fetchTile(final String key) {
        List<DamAreas.Area> got = null;
        try {
            final byte[] gz = Http.fetchBytes(DamAreas.tileUrl(key), egress.userAgent());
            got = DamAreas.parseTile(inflate(gz));
            writeAtomically(file(key), gz);
        } catch (Exception e) {
            Log.w(TAG, "dam tile " + key + " failed: " + e.getMessage());
        }
        final List<DamAreas.Area> done = got;
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
            }
        });
    }

    private void draw(boolean tileArrived) {
        int pending = 0;
        for (String k : viewTiles)
            if (inFlight.contains(k))
                pending++;
        final StringBuilder keyB = new StringBuilder();
        final List<List<DamAreas.Area>> have = new ArrayList<>();
        int missing = 0, packed = 0;
        for (String k : viewTiles) {
            if (manifest == null || !manifest.tiles.contains(k))
                continue;
            packed++;
            final List<DamAreas.Area> t = memory.get(k);
            if (t == null) {
                missing++;
                continue;
            }
            have.add(t);
            keyB.append(k).append(';');
        }
        final String key = keyB.toString();
        if (!key.equals(drawnKey) && (pending == 0 || drawnKey.isEmpty() || !tileArrived)) {
            drawnKey = key;
            rewrite(have);
        }
        int failedHere = 0;
        for (String k : viewTiles)
            if (failed.contains(k))
                failedHere++;
        final String line = line(manifest == null, packed, pending, missing, failedHere,
                namesInView());
        mapView.post(new Runnable() {
            @Override
            public void run() {
                if (on && visible)
                    status(line);
            }
        });
    }

    /** The dams whose areas are drawn and reach into the view, by name. */
    private List<String> namesInView() {
        final double[] b = viewBox;
        final Set<String> names = new LinkedHashSet<>();
        if (b == null)
            return new ArrayList<>(names);
        for (DamAreas.Area a : drawnById.values()) {
            boolean in = false;
            for (List<double[]> poly : a.polygons) {
                final double[] r = poly.get(0);
                for (int i = 0; i + 1 < r.length && !in; i += 2)
                    in = r[i] >= b[0] && r[i] <= b[2] && r[i + 1] >= b[1]
                            && r[i + 1] <= b[3];
                if (in)
                    break;
            }
            if (in)
                names.add(DamAreas.damName(a));
        }
        final List<String> out = new ArrayList<>(names);
        Collections.sort(out);
        return out;
    }

    private void rewrite(List<List<DamAreas.Area>> tiles) {
        final Map<Long, DamAreas.Area> byId = new HashMap<>();
        final List<DamAreas.Area> all = new ArrayList<>();
        for (List<DamAreas.Area> t : tiles)
            for (DamAreas.Area a : t)
                if (byId.put(a.id, a) == null)
                    all.add(a);
        Collections.sort(all, new Comparator<DamAreas.Area>() {
            @Override
            public int compare(DamAreas.Area a, DamAreas.Area b) {
                return Long.compare(a.id, b.id);
            }
        });
        final List<AtmosphereFeatures.Drawn> drawn = new ArrayList<>(all.size());
        for (DamAreas.Area a : all) {
            final Geometry g = geometry(a);
            if (g == null)
                continue;
            final AttributeSet attrs = new AttributeSet();
            attrs.setAttribute("_damId", String.valueOf(a.id));
            attrs.setAttribute("_details", DamAreas.details(a));
            drawn.add(new AtmosphereFeatures.Drawn(NAME, DamAreas.title(a), g,
                    AtmosphereFeatures.area(EDGE, WEIGHT, FILL), attrs));
        }
        drawnById = byId;
        features.rewrite(drawn);
        Log.d(TAG, "drew " + drawn.size() + " dam areas from " + tiles.size() + " tiles");
    }

    private static Geometry geometry(DamAreas.Area a) {
        final List<Polygon> polys = new ArrayList<>();
        for (List<double[]> rings : a.polygons) {
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

    /** The status line: never "safe", only what the state's maps show here. */
    private static String line(boolean noManifest, int packed, int pending, int missing,
            int failed, List<String> names) {
        if (noManifest)
            return "Could not reach the dam failure maps. This device has none saved yet.";
        if (packed > 0 && missing == packed && pending > 0)
            return "Getting dam failure areas…";
        final StringBuilder b = new StringBuilder();
        if (!names.isEmpty()) {
            b.append("In this view, if these dams failed: ");
            final int show = Math.min(4, names.size());
            for (int i = 0; i < show; i++)
                b.append(i > 0 ? ", " : "").append(names.get(i));
            if (names.size() > show)
                b.append(" and others");
        } else {
            // Not "safe": only that the state's 2020 copy has no area here.
            b.append("No dam failure area in this view in the state's 2020 maps");
        }
        if (pending > 0)
            b.append(". Getting more…");
        else if (failed > 0)
            b.append(". Part of this view could not be loaded");
        return b.toString();
    }

    // ---- disk -----------------------------------------------------------------------

    private static File dir() {
        final File d = FileSystemUtils.getItem(DIR);
        if (!d.isDirectory() && !d.mkdirs())
            Log.w(TAG, "could not make " + DIR);
        return d;
    }

    /** A tile's file. The name is built from two integers only, never server text. */
    private static File file(String key) {
        final String[] xy = key.split("_");
        final int x = Integer.parseInt(xy[0]), y = Integer.parseInt(xy[1]);
        return new File(dir(), String.format(Locale.US, "d_%d_%d.json.gz", x, y));
    }

    private static DamAreas.Manifest readManifest() {
        try {
            final byte[] b = readAll(new File(dir(), "dams.json"), 1024 * 1024);
            return b == null ? null : DamAreas.manifest(new String(b, "UTF-8"));
        } catch (Exception e) {
            return null;
        }
    }

    private static List<DamAreas.Area> readTile(String key) {
        try {
            final byte[] gz = readAll(file(key), 8 * 1024 * 1024);
            return gz == null ? null : DamAreas.parseTile(inflate(gz));
        } catch (Exception e) {
            Log.w(TAG, "could not read dam tile " + key, e);
            return null;
        }
    }

    private static byte[] readAll(File f, int cap) throws IOException {
        if (!f.isFile() || f.length() > cap)
            return null;
        try (InputStream in = new FileInputStream(f)) {
            final byte[] buf = new byte[(int) f.length()];
            int n = 0, r;
            while (n < buf.length && (r = in.read(buf, n, buf.length - n)) > 0)
                n += r;
            return n == buf.length ? buf : null;
        }
    }

    private static String inflate(byte[] gz) throws IOException {
        final ByteArrayOutputStream out = new ByteArrayOutputStream(gz.length * 6);
        try (InputStream in = new GZIPInputStream(new ByteArrayInputStream(gz))) {
            final byte[] buf = new byte[32 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
                if (out.size() > MAX_INFLATED)
                    throw new IOException("dam tile inflates past " + MAX_INFLATED);
            }
        }
        return out.toString("UTF-8");
    }

    private static void writeAtomically(File f, byte[] body) throws IOException {
        final File tmp = new File(f.getPath() + ".tmp");
        try (OutputStream out = new FileOutputStream(tmp)) {
            out.write(body);
        }
        if (!tmp.renameTo(f)) {
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
            throw new IOException("could not keep " + f.getName());
        }
    }

    /** A new build: the kept tiles are the old build's. */
    private void clearTiles() {
        final File[] files = dir().listFiles();
        if (files != null)
            for (File f : files)
                if (f.getName().startsWith("d_"))
                    //noinspection ResultOfMethodCallIgnored
                    f.delete();
        memory.clear();
        drawnKey = "";
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
