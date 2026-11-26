package com.atakmap.android.sitrep;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import com.atakmap.android.maps.MapView;
import com.atakmap.android.sitrep.net.Http;
import com.atakmap.android.sitrep.section.Section;
import com.atakmap.android.sitrep.section.Sections;
import com.atakmap.coremap.filesystem.FileSystemUtils;
import com.atakmap.coremap.log.Log;
import com.atakmap.coremap.maps.coords.GeoPoint;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

/**
 * Owns the reports for the plugin's life: drawing the area, fetching every section on
 * workers, saving each report to disk, and the areas on the map. Never inside a
 * {@code Tool}: a fetch keeps going when another tool starts, a dropdown opens or the
 * pane is closed (PLAN section 8).
 *
 * <p>On disk, the way FireCast keeps its runs: {@code tools/sitrep/reports.json} lists
 * the reports newest first, and each report is {@code tools/sitrep/reports/<id>.json},
 * every write a temp file and a rename. Delete is the only way a report goes.
 *
 * <p>All state is touched on the main thread; workers only fetch and write files.
 */
public final class SitRepManager {

    private static final String TAG = "SitRep";
    public static final String PREF_NEARBY = "sitrep.nearbyMi";
    /** The places zoom gate: the scale bar's reading in meters, -1 for Always. */
    public static final String PREF_GATE = "sitrep.placesGateBarM";
    public static final double GATE_DEFAULT_M = 8_046.72;   // 5 mi
    /** The widest area a report is made for: past this the servers time out and the counts stop meaning much. */
    /** Larger than any county a crew would ask about (PLAN decision 12); a state is refused. */
    static final double MAX_SPAN_M = 700_000;

    public interface Listener {
        void changed();

        /** A new report was started from a drawn area; the pane opens it. */
        void reportStarted(Report r);
    }

    private final MapView mapView;
    private final SharedPreferences prefs;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final File root, dir, index;
    private final ExecutorService fetch = Executors.newFixedThreadPool(4, named("sitrep-fetch"));
    private final ExecutorService disk = Executors.newSingleThreadExecutor(named("sitrep-disk"));
    private final List<Report> reports = new ArrayList<>();
    /** report id + "/" + section key, for sections being fetched again over a kept answer. */
    private final Set<String> refreshing = new HashSet<>();
    private final AreaOutlines outlines;
    private AreaPicker picker;
    private Listener listener;
    private boolean loaded, stopped;
    /** Something to say once on the status line (a refused area), or null. */
    private String note;

    public SitRepManager(MapView mapView, Context pluginContext) {
        this.pick = new com.atakmap.android.sitrep.MapPick(mapView);
        this.places = new PlaceLayer(mapView, pluginContext);
        this.mapView = mapView;
        this.prefs = mapView.getContext().getSharedPreferences("sitrep", Context.MODE_PRIVATE);
        this.root = FileSystemUtils.getItem("tools/sitrep");
        this.dir = new File(root, "reports");
        this.index = new File(root, "reports.json");
        this.outlines = new AreaOutlines(mapView);
    }

    public void setListener(Listener l) {
        listener = l;
    }

    // ------------------------------------------------------------------ lifetime

    /** Reads the saved reports on a worker; nothing slow on main at plugin start. */
    public void start() {
        places.start();
        mapView.addOnMapMovedListener(moved);
        main.post(gateTick);
        main.postDelayed(callsTick, CALLS_EVERY_MS);
        disk.execute(new Runnable() {
            @Override
            public void run() {
                List<Report> found;
                try {
                    found = load();
                } catch (Throwable t) {
                    // An Error escaping a plugin thread kills ATAK's whole process.
                    Log.w(TAG, "could not read the saved reports: " + t);
                    found = new ArrayList<>();
                }
                final List<Report> fromDisk = found;
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        if (stopped)
                            return;
                        reports.clear();
                        reports.addAll(fromDisk);
                        loaded = true;
                        outlines.sync(reports);
                        places.sync(reports);
                        changed();
                    }
                });
            }
        });
    }

    public void stop() {
        stopped = true;
        mapView.removeOnMapMovedListener(moved);
        main.removeCallbacks(gateTick);
        main.removeCallbacks(callsTick);
        pick.cancel();
        places.stop();
        if (picker != null) {
            picker.dispose();
            picker = null;
        }
        outlines.dispose();
        fetch.shutdownNow();
        // Writes already queued finish: a report saved a moment before a reinstall is kept.
        disk.shutdown();
        listener = null;
    }

    // ------------------------------------------------------------------ reading

    /** The reports, newest first. */
    public List<Report> reports() {
        return new ArrayList<>(reports);
    }

    public Report find(String id) {
        for (Report r : reports)
            if (r.id.equals(id))
                return r;
        return null;
    }

    public boolean isDrawing() {
        return picker != null && picker.isActive();
    }

    public boolean isRefreshing(Report r, String key) {
        return refreshing.contains(r.id + "/" + key);
    }

    /** How many saved reports have their Map switch ON. */
    public int reportsOnMap() {
        int n = 0;
        for (Report r : reports)
            if (r.onMap)
                n++;
        return n;
    }

    /**
     * The main screen's All ON/OFF: sets every report's own Map switch. It replaced
     * an Areas switch over the outlines only, which hid a boundary while the report
     * read Map ON (operator, 2026-11-25).
     */
    public void setAllOnMap(boolean on) {
        for (Report r : reports) {
            if (r.onMap == on)
                continue;
            r.onMap = on;
            save(r);
        }
        outlines.sync(reports);
        places.sync(reports);
        changed();
    }

    /** The places gate as the scale bar reads, meters; -1 is Always. */
    public double placesGate() {
        return Double.longBitsToDouble(prefs.getLong(PREF_GATE, Double.doubleToLongBits(GATE_DEFAULT_M)));
    }

    public void setPlacesGate(double barMeters) {
        prefs.edit().putLong(PREF_GATE, Double.doubleToLongBits(barMeters)).apply();
        gateTick.run();
        changed();
    }

    /** True while the map is zoomed out past the places gate and the places are hidden. */
    public boolean placesGated() {
        return gated;
    }

    private boolean gated;

    /**
     * onMapMoved runs on the GL thread, every frame of a pinch: it only posts, and
     * the posts are coalesced, so the gate is checked once the map has settled.
     */
    private final com.atakmap.map.AtakMapView.OnMapMovedListener moved =
            new com.atakmap.map.AtakMapView.OnMapMovedListener() {
                @Override
                public void onMapMoved(com.atakmap.map.AtakMapView view, boolean animate) {
                    main.removeCallbacks(gateTick);
                    main.postDelayed(gateTick, 300);
                }
            };

    private final Runnable gateTick = new Runnable() {
        @Override
        public void run() {
            if (stopped)
                return;
            final double gate = placesGate();
            final boolean past = gate > 0 && ScaleBar.meters(mapView) > gate * 1.02;
            places.setHidden(past);
            if (past != gated) {
                gated = past;
                changed();
            }
        }
    };

    public int nearbyMiles() {
        return prefs.getInt(PREF_NEARBY, 5);
    }

    public void setNearbyMiles(int mi) {
        prefs.edit().putInt(PREF_NEARBY, mi).apply();
        changed();
    }

    /** The pinned line: what is happening, or what to do next. */
    public String status() {
        if (isDrawing())
            return "Draw the area: tap each corner, then tap the first point to close it.";
        if (!loaded)
            return "Reading saved reports...";
        if (note != null)
            return note;
        for (Report r : reports) {
            final int busy = busy(r);
            if (busy > 0) {
                final int all = r.answers.size();
                return String.format(Locale.US, "Making sit rep: %d of %d %s.", all - busy, all,
                        all == 1 ? "section" : "sections");
            }
        }
        if (reports.isEmpty())
            return "Draw an area to make a sit rep.";
        final Report newest = reports.get(0);
        final int failed = newest.failed();
        if (failed > 0) {
            boolean offline = false;
            for (Report.Answer a : newest.answers.values())
                offline |= a.state == Report.State.OFFLINE;
            return failed + (failed == 1 ? " section" : " sections") + " not available"
                    + (offline ? ": no network." : ". Tap the report, then Refresh.");
        }
        if (gated)
            for (Report r : reports)
                if (!r.placesOn.isEmpty())
                    return "Places hidden: zoom in until the scale bar reads "
                            + ScaleBar.describe(placesGate()) + " or less.";
        return reports.size() + (reports.size() == 1 ? " report" : " reports")
                + " saved. Tap one to read it.";
    }

    private int busy(Report r) {
        int n = r.loading();
        for (String k : r.answers.keySet())
            if (isRefreshing(r, k))
                n++;
        return n;
    }

    // ------------------------------------------------------------------ drawing

    public void drawArea() {
        note = null;
        if (picker == null)
            picker = new AreaPicker(mapView, new AreaPicker.Callback() {
                @Override
                public void onArea(GeoPoint[] ring) {
                    areaDrawn(ring);
                }

                @Override
                public void onCancelled(String reason) {
                    note = reason;
                    changed();
                }
            });
        picker.start();
        changed();
    }

    public void cancelDrawing() {
        if (picker != null)
            picker.cancel();
    }

    private void areaDrawn(GeoPoint[] ring) {
        startReport(ring, null);
    }

    /**
     * Places found by name, on a worker; the answer arrives on the main thread. The
     * state of the map's center is listed first.
     */
    public void searchPlaces(final String typed, final PlaceAnswer<java.util.List<com.atakmap.android.sitrep.net.Places.Place>> answer) {
        final GeoPoint center = centerForSearch;
        fetch.execute(new Runnable() {
            @Override
            public void run() {
                java.util.List<com.atakmap.android.sitrep.net.Places.Place> got = null;
                Throwable error = null;
                try {
                    got = com.atakmap.android.sitrep.net.Places.search(typed,
                            com.atakmap.android.sitrep.net.Places.stateAt(center), center);
                } catch (Throwable e) {
                    error = e;
                    Log.w(TAG, "place search failed: " + e);
                }
                final java.util.List<com.atakmap.android.sitrep.net.Places.Place> g = got;
                final Throwable err = error;
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        answer.done(g, err);
                    }
                });
            }
        });
    }

    /** Fetches a place's outline and makes a report for it, named after the place. */
    public void reportForPlace(final com.atakmap.android.sitrep.net.Places.Place p) {
        note = "Getting the outline of " + p.label + "...";
        changed();
        fetch.execute(new Runnable() {
            @Override
            public void run() {
                GeoPoint[] ring = null;
                try {
                    ring = com.atakmap.android.sitrep.net.Places.outline(p);
                } catch (Throwable e) {
                    Log.w(TAG, "place outline failed: " + e);
                }
                final GeoPoint[] r = ring;
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        if (stopped)
                            return;
                        if (r == null) {
                            note = "Could not get the outline of " + p.label + ". Try again.";
                            changed();
                            return;
                        }
                        note = null;
                        // A town with no boundary: the list says so, the report name does not.
                        startReport(r, p.label.replaceFirst(" \\(town, no boundary.*\\)$", ""));
                    }
                });
            }
        });
    }

    /** Places switched on for the map, every report's. */
    public final PlaceLayer places;

    /** One place category on or off on the map, for one report. */
    public void setPlaceOn(Report r, String category, boolean on) {
        if (!reports.contains(r))
            return;
        if (on)
            r.placesOn.add(category);
        else
            r.placesOn.remove(category);
        // A layer switched on in a report that is off the map would draw nothing.
        final boolean wasOff = on && !r.onMap;
        if (wasOff)
            r.onMap = true;
        save(r);
        if (wasOff)
            outlines.sync(reports);
        places.sync(reports);
        changed();
    }

    /** The place pick and the shape pick share one map tap. */
    public final com.atakmap.android.sitrep.MapPick pick;

    /** The status line, from the pane (what a pick is waiting for). */
    public void setNote(String n) {
        note = n;
        changed();
    }

    /** The city, community and county at a point, on a worker. */
    public void placesAt(final GeoPoint p, final PlaceAnswer<java.util.List<com.atakmap.android.sitrep.net.Places.Place>> answer) {
        fetch.execute(new Runnable() {
            @Override
            public void run() {
                java.util.List<com.atakmap.android.sitrep.net.Places.Place> got = null;
                Throwable error = null;
                try {
                    got = com.atakmap.android.sitrep.net.Places.at(p);
                } catch (Throwable e) {
                    error = e;
                    Log.w(TAG, "places at a point failed: " + e);
                }
                final java.util.List<com.atakmap.android.sitrep.net.Places.Place> g = got;
                final Throwable err = error;
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        answer.done(g, err);
                    }
                });
            }
        });
    }

    /** A shape the operator tapped becomes the area. */
    public void reportForShape(GeoPoint[] outline, String title) {
        note = null;
        startReport(outline, title);
    }

    /** An answer that arrives on the main thread. */
    public interface PlaceAnswer<T> {
        void done(T result, Throwable error);
    }

    /** The map's center when a search was asked for; its state is listed first. */
    public volatile GeoPoint centerForSearch;

    private void startReport(GeoPoint[] ring, String label) {
        final Area area;
        try {
            area = Area.of(ring);
        } catch (IllegalArgumentException e) {
            note = "That area has fewer than three points.";
            changed();
            return;
        }
        if (area.spanMeters() > MAX_SPAN_M) {
            note = "That area is " + Units.distance(area.spanMeters()) + " across. Pick one under "
                    + Units.distance(MAX_SPAN_M) + ".";
            changed();
            return;
        }
        final Report r = new Report(UUID.randomUUID().toString(), System.currentTimeMillis(), area);
        r.label = Report.cleanLabel(label);
        for (Section s : Sections.all())
            r.answers.put(s.key, Report.newLoading());
        reports.add(0, r);
        save(r);
        saveIndex();
        outlines.sync(reports);
        for (Section s : Sections.all())
            run(r, s);
        changed();
        if (listener != null)
            listener.reportStarted(r);
    }

    // ------------------------------------------------------------------ fetching

    /**
     * Fetches one section on a worker. A failure touches this section alone; a failed
     * refresh keeps the answer it had and says how old that is (PLAN section 3).
     */
    /** How often 911 calls on the map are fetched again: the feeds update every 5 min. */
    private static final long CALLS_EVERY_MS = 5L * 60 * 1000;

    /**
     * Every 5 minutes, the 911 calls of each report showing them on the map, whether
     * or not the pane is open (operator, 2026-11-14). Only that section; it lives with
     * the manager, so it outlasts the pane and any tool.
     */
    private final Runnable callsTick = new Runnable() {
        @Override
        public void run() {
            if (stopped)
                return;
            Section calls = null;
            for (Section s : Sections.all())
                if ("calls".equals(s.key))
                    calls = s;
            for (Report r : new ArrayList<>(reports)) {
                if (calls == null || !r.onMap || !r.placesOn.contains("calls"))
                    continue;
                final Report.Answer a = r.answer("calls");
                if (a == null || a.state == Report.State.LOADING || isRefreshing(r, "calls"))
                    continue;
                if (a.state == Report.State.OK)
                    refreshing.add(r.id + "/calls");
                else
                    r.answers.put("calls", Report.newLoading());
                SitRepManager.this.run(r, calls);
            }
            main.postDelayed(this, CALLS_EVERY_MS);
        }
    };

    private void run(final Report r, final Section s) {
        final int nearby = nearbyMiles();
        fetch.execute(new Runnable() {
            @Override
            public void run() {
                Section.Result got = null;
                Throwable failed = null;
                try {
                    got = s.fetch(r.area, nearby);
                } catch (Throwable e) {
                    failed = e;
                    Log.w(TAG, s.key + " not available: " + e);
                }
                final Section.Result result = got;
                final Throwable error = failed;
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        landed(r, s, result, error);
                    }
                });
            }
        });
    }

    private void landed(Report r, Section s, Section.Result got, Throwable error) {
        refreshing.remove(r.id + "/" + s.key);
        if (stopped || find(r.id) == null)
            return;   // deleted while it was fetching
        Report.Answer a = r.answer(s.key);
        final boolean kept = a != null && a.state == Report.State.OK;
        if (got == null && kept) {
            a.lastTryFailed = true;
            a.lastTryOffline = Http.noNetwork(error);
        } else {
            a = new Report.Answer();
            if (got == null) {
                a.state = Http.noNetwork(error) ? Report.State.OFFLINE : Report.State.FAILED;
            } else {
                a.state = got.notPublishedHere ? Report.State.NOT_HERE : Report.State.OK;
                a.value = got.value;
                a.summary = got.summary;
                a.lines.addAll(got.lines);
                a.points = got.points;
                a.at = System.currentTimeMillis();
            }
            r.answers.put(s.key, a);
        }
        save(r);
        if ("places".equals(s.key) || "calls".equals(s.key) || "history".equals(s.key))
            places.sync(reports);
        changed();
    }

    /**
     * Re-runs the live sections for the same area, and any section that did not answer.
     * Reference sections that answered are kept as they are.
     */
    public void refresh(Report r) {
        note = null;
        for (Section s : Sections.all()) {
            Report.Answer a = r.answer(s.key);
            if (a == null) {
                // A section added since this report was made: fetch it now. Marking it
                // loading and then falling through to the loading check below skipped
                // it, and it said Loading forever (s10-dev-2, 2026-11-13).
                r.answers.put(s.key, Report.newLoading());
                run(r, s);
                continue;
            }
            final boolean answered = a.state == Report.State.OK || a.state == Report.State.NOT_HERE;
            if (answered && !s.live)
                continue;
            if (a.state == Report.State.LOADING || isRefreshing(r, s.key))
                continue;
            if (a.state == Report.State.OK)
                refreshing.add(r.id + "/" + s.key);
            else
                r.answers.put(s.key, Report.newLoading());
            run(r, s);
        }
        changed();
    }

    /** Gives a report the operator's own name; a blank name goes back to the automatic one. */
    public void rename(Report r, String typed) {
        if (!reports.contains(r))
            return;
        r.label = Report.cleanLabel(typed);
        save(r);
        outlines.sync(reports);
        changed();
    }

    /** One report's own map switch. */
    public void setOnMap(Report r, boolean on) {
        if (!reports.contains(r))
            return;
        r.onMap = on;
        save(r);
        outlines.sync(reports);
        // Off takes everything of this report off the map; its place switches are
        // kept, so On brings back the same layers (operator, 2026-11-13).
        places.sync(reports);
        changed();
    }

    public void delete(Report r) {
        note = null;
        if (!reports.remove(r))
            return;
        final File f = fileFor(r.id);
        disk.execute(new Runnable() {
            @Override
            public void run() {
                if (f != null && f.exists() && !f.delete())
                    Log.w(TAG, "could not delete a report file");
            }
        });
        saveIndex();
        outlines.sync(reports);
        places.sync(reports);
        changed();
    }

    // ------------------------------------------------------------------ disk

    private File fileFor(String id) {
        return Report.validId(id) ? new File(dir, id + ".json") : null;
    }

    private void save(Report r) {
        final File f = fileFor(r.id);
        if (f == null)
            return;
        final String text;
        try {
            text = r.toJson().toString();
        } catch (Exception e) {
            Log.w(TAG, "could not write a report", e);
            return;
        }
        disk.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    if (!dir.isDirectory() && !dir.mkdirs())
                        throw new java.io.IOException("could not create " + dir.getName());
                    SafeFile.write(f, text);
                } catch (Exception e) {
                    Log.w(TAG, "could not save a report: " + e);
                }
            }
        });
    }

    private void saveIndex() {
        final JSONArray ids = new JSONArray();
        for (Report r : reports)
            ids.put(r.id);
        final String text;
        try {
            text = new JSONObject().put("v", 1).put("reports", ids).toString();
        } catch (Exception e) {
            return;
        }
        disk.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    if (!root.isDirectory() && !root.mkdirs())
                        throw new java.io.IOException("could not create " + root.getName());
                    SafeFile.write(index, text);
                } catch (Exception e) {
                    Log.w(TAG, "could not save the report list: " + e);
                }
            }
        });
    }

    /** Disk thread. Every report in the index that reads back whole, newest first. */
    private List<Report> load() {
        final List<Report> out = new ArrayList<>();
        try {
            if (root.isDirectory())
                SafeFile.sweep(root, index.getName());
            if (!index.isFile())
                return out;
            final JSONArray ids = new JSONObject(read(index, 1 << 20)).optJSONArray("reports");
            if (ids == null)
                return out;
            for (int i = 0; i < ids.length() && i < 500; i++) {
                final File f = fileFor(ids.optString(i, ""));
                if (f == null || !f.isFile())
                    continue;
                try {
                    SafeFile.sweep(dir, f.getName());
                    final Report r = Report.fromJson(new JSONObject(read(f, 4 << 20)));
                    if (r == null)
                        continue;
                    // A section still loading when ATAK stopped never finished: say so,
                    // and Refresh fetches it.
                    for (Report.Answer a : r.answers.values())
                        if (a.state == Report.State.LOADING)
                            a.state = Report.State.FAILED;
                    out.add(r);
                } catch (Exception | StackOverflowError e) {
                    // A file on shared storage can be anything, nested deep enough to
                    // overflow the parser's stack: skip it, never take ATAK down.
                    Log.w(TAG, "could not read a saved report: " + e);
                }
            }
        } catch (Exception | StackOverflowError e) {
            Log.w(TAG, "could not read the saved reports: " + e);
        }
        return out;
    }

    private static String read(File f, int max) throws java.io.IOException {
        if (f.length() > max)
            throw new java.io.IOException(f.getName() + " is too large");
        final byte[] b = new byte[(int) f.length()];
        final java.io.FileInputStream in = new java.io.FileInputStream(f);
        try {
            int off = 0;
            while (off < b.length) {
                final int n = in.read(b, off, b.length - off);
                if (n < 0)
                    break;
                off += n;
            }
        } finally {
            in.close();
        }
        return new String(b, StandardCharsets.UTF_8);
    }

    // ------------------------------------------------------------------ misc

    private void changed() {
        if (listener != null)
            listener.changed();
    }

    private static ThreadFactory named(final String name) {
        return new ThreadFactory() {
            @Override
            public Thread newThread(Runnable r) {
                final Thread t = new Thread(r, name);
                t.setDaemon(true);
                return t;
            }
        };
    }
}
