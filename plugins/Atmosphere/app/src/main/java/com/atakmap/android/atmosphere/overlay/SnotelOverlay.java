package com.atakmap.android.atmosphere.overlay;

import android.content.Context;
import android.content.SharedPreferences;

import com.atakmap.android.atmosphere.compat.MapCompat;
import com.atakmap.android.atmosphere.compat.ScaleBar;
import com.atakmap.android.atmosphere.data.Snotel;
import com.atakmap.android.atmosphere.net.EgressPolicy;
import com.atakmap.android.atmosphere.net.Http;
import com.atakmap.android.atmosphere.units.Quantity;
import com.atakmap.android.atmosphere.units.UnitSystem;
import com.atakmap.android.atmosphere.units.Units;
import com.atakmap.android.maps.MapView;
import com.atakmap.coremap.log.Log;
import com.atakmap.coremap.maps.coords.GeoBounds;
import com.atakmap.map.AtakMapView;
import com.atakmap.map.layer.feature.AttributeSet;

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

/**
 * SNOTEL stations on the map: a disc in the snow depth's color at each station
 * in view, the readings and the name on a pill when the map is close, the whole
 * record behind a tap. The national station list is fetched once a session; the
 * stations in the map's neighborhood get the last six hours of readings, sixty to
 * a request, and are asked again when the view leaves the box or every fifteen
 * minutes. Past 240 stations in view the nearest 240 to the center are drawn and
 * the status says so.
 */
public final class SnotelOverlay {

    private static final String TAG = "AtmosphereSnotel";
    public static final String LAYER_ID = "snotel";
    public static final String HOST = Snotel.HOST;
    public static final String NAME = "Snow stations";
    private static final String PREF_ON = "weather.layer.snotel.on";
    private static final String PREF_UNITS = "weather.units";
    private static final long POLL_MS = 15 * 60 * 1000L;
    private static final long SETTLE_MS = 700L;
    private static final long WINDOW_MS = 6 * 60 * 60 * 1000L;
    private static final double MAX_SPAN_LON = 12, MAX_SPAN_LAT = 8, ZOOM_IN_SPAN = 30;
    private static final int MAX_STATIONS = 240;
    /** Pills go on when the scale bar reads this many miles or fewer. */
    private static final double LABEL_BIG_MILES = 30d;
    private static final double FINEST = 0d;

    public static final String[][] LEGEND;
    static {
        final String[] names = { "Under 2 in", "2 to 4 in", "4 to 10 in", "10 to 20 in", "20 to 39 in",
                "39 to 59 in", "5 to 8 ft", "8 to 16 ft", "16 to 25 ft", "25 to 33 ft", "Over 33 ft" };
        LEGEND = new String[names.length + 2][];
        LEGEND[0] = new String[] { "Bare ground", String.valueOf(Snotel.NO_SNOW) };
        for (int i = 0; i < names.length; i++)
            LEGEND[i + 1] = new String[] { names[i], String.valueOf(Snotel.DEPTH_COLORS[i]) };
        LEGEND[names.length + 1] = new String[] { "No report in six hours", String.valueOf(Snotel.NO_REPORT) };
    }

    public interface Listener {
        void onStatus(String status);
    }

    private final MapView mapView;
    private final EgressPolicy egress;
    private final AtmosphereFeatures features;
    private final SnotelIcons icons;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private Listener listener;
    private boolean started, on, listInFlight;
    private int generation;
    private List<Snotel.Station> national = new ArrayList<>();
    private List<Snotel.Station> inView = new ArrayList<>();
    private Map<String, Snotel.Reading> readings = new HashMap<>();
    private GeoBounds region;
    private long fetchedAt;
    private String pendingKey;
    private boolean labelsWanted;
    private Set<String> lastLabeled = new HashSet<>();
    private int cut;

    private final Runnable settled = new Runnable() {
        @Override
        public void run() {
            if (!on)
                return;
            final boolean wanted = mapView.getMapResolution() <= gsdForBig(LABEL_BIG_MILES);
            final boolean crossed = wanted != labelsWanted;
            labelsWanted = wanted;
            if (!ensureRegion(false) && (crossed || (labelsWanted && labeledSetChanged())))
                redraw();
        }
    };

    private final AtakMapView.OnMapMovedListener moved = new AtakMapView.OnMapMovedListener() {
        @Override
        public void onMapMoved(AtakMapView v, boolean animate) {
            mapView.removeCallbacks(settled);
            mapView.postDelayed(settled, SETTLE_MS);
        }
    };

    private final Runnable autoPoll = new Runnable() {
        @Override
        public void run() {
            if (!on || !started)
                return;
            ensureRegion(true);
            mapView.postDelayed(this, POLL_MS);
        }
    };

    public SnotelOverlay(MapView mapView, Context pluginContext, EgressPolicy egress) {
        this.mapView = mapView;
        this.egress = egress;
        this.features = new AtmosphereFeatures(mapView, pluginContext, TAG, NAME,
                "snotel.sqlite", "snotel", false);
        this.icons = new SnotelIcons(pluginContext.getResources().getDisplayMetrics().density);
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
        if (!on)
            drawNothing();
    }

    public void stop() {
        started = false;
        on = false;
        generation++;
        mapView.removeCallbacks(autoPoll);
        mapView.removeCallbacks(settled);
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
        mapView.removeCallbacks(settled);
        generation++;
        pendingKey = null;
        if (value) {
            labelsWanted = mapView.getMapResolution() <= gsdForBig(LABEL_BIG_MILES);
            mapView.addOnMapMovedListener(moved);
            if (national.isEmpty())
                fetchStations();
            else
                ensureRegion(true);
            mapView.postDelayed(autoPoll, POLL_MS);
        } else {
            mapView.removeOnMapMovedListener(moved);
            region = null;
            inView = new ArrayList<>();
            readings = new HashMap<>();
            drawNothing();
            status("");
        }
    }

    public void refresh(boolean force) {
        if (on)
            ensureRegion(force);
    }

    private double gsdForBig(double big) {
        final double res = mapView.getMapResolution();
        final double m = res <= 0 ? 0 : ScaleBar.meters(mapView);
        final double barPx = m > 0 && res > 0 ? m / res : ScaleBar.FALLBACK_BAR_PIXELS;
        return ScaleBar.bigToMeters(big) / barPx;
    }

    /** The national list, once a session; 408 KB on the large-request thread. */
    private void fetchStations() {
        if (listInFlight)
            return;
        listInFlight = true;
        final int mine = generation;
        status("Getting the station list…");
        Http.getLarge(Snotel.STATIONS_URL, egress.userAgent(), new HashMap<String, String>(),
                new Http.Callback() {
                    @Override
                    public void onSuccess(String body) {
                        listInFlight = false;
                        final List<Snotel.Station> got = Snotel.parseStations(body);
                        if (got.isEmpty()) {
                            status("Snow stations: nothing usable in the station list");
                            return;
                        }
                        national = got;
                        if (mine == generation && on)
                            ensureRegion(true);
                    }

                    @Override
                    public void onFailure(String error) {
                        listInFlight = false;
                        if (mine == generation && on)
                            status("Snow stations: " + error);
                    }
                });
    }

    /** True when a fetch was started for a new box. */
    private boolean ensureRegion(boolean force) {
        if (national.isEmpty())
            return false;
        final GeoBounds bounds = mapView.getBounds();
        if (bounds == null)
            return false;
        final boolean wholeWorld = Double.isNaN(bounds.getNorth()) || Double.isNaN(bounds.getSouth())
                || Double.isNaN(bounds.getEast()) || Double.isNaN(bounds.getWest())
                || bounds.getEast() <= bounds.getWest()
                || bounds.getEast() - bounds.getWest() >= ZOOM_IN_SPAN;
        if (wholeWorld) {
            region = null;
            inView = new ArrayList<>();
            drawNothing();
            status("Zoom in to see snow stations");
            return false;
        }
        double w = bounds.getWest(), e = bounds.getEast(), s = bounds.getSouth(), n = bounds.getNorth();
        final boolean stale = System.currentTimeMillis() - fetchedAt > POLL_MS;
        boolean refetch = force || stale || region == null || !contains(region, bounds);
        if (!refetch) {
            final double viewSpan = e - w, regionSpan = region.getEast() - region.getWest();
            refetch = viewSpan < regionSpan / 4;
        }
        if (!refetch)
            return false;
        if (e - w > MAX_SPAN_LON) {
            final double c = (e + w) / 2;
            w = c - MAX_SPAN_LON / 2;
            e = c + MAX_SPAN_LON / 2;
        }
        if (n - s > MAX_SPAN_LAT) {
            final double c = (n + s) / 2;
            s = c - MAX_SPAN_LAT / 2;
            n = c + MAX_SPAN_LAT / 2;
        }
        final double padX = Math.min(e - w, (MAX_SPAN_LON - (e - w)) / 2);
        final double padY = Math.min(n - s, (MAX_SPAN_LAT - (n - s)) / 2);
        final GeoBounds r = new GeoBounds(Math.min(85, n + padY), Math.max(-180, w - padX),
                Math.max(-85, s - padY), Math.min(180, e + padX));
        final String key = String.format(Locale.US, "%.2f,%.2f,%.2f,%.2f",
                r.getWest(), r.getSouth(), r.getEast(), r.getNorth());
        if (key.equals(pendingKey))
            return true;
        pendingKey = key;
        List<Snotel.Station> stations = Snotel.within(national, r.getWest(), r.getSouth(), r.getEast(), r.getNorth());
        cut = 0;
        if (stations.size() > MAX_STATIONS) {
            final double cx = (r.getEast() + r.getWest()) / 2, cy = (r.getNorth() + r.getSouth()) / 2;
            Collections.sort(stations, new java.util.Comparator<Snotel.Station>() {
                @Override
                public int compare(Snotel.Station a, Snotel.Station b) {
                    return Double.compare(d2(a, cx, cy), d2(b, cx, cy));
                }
            });
            cut = stations.size() - MAX_STATIONS;
            stations = new ArrayList<>(stations.subList(0, MAX_STATIONS));
        }
        if (stations.isEmpty()) {
            pendingKey = null;
            region = r;
            fetchedAt = System.currentTimeMillis();
            inView = new ArrayList<>();
            readings = new HashMap<>();
            drawNothing();
            status("No snow stations in view");
            return true;
        }
        final int mine = generation;
        final List<Snotel.Station> asked = stations;
        final List<List<String>> chunks = Snotel.chunks(asked, Snotel.MAX_PER_QUERY);
        final long now = System.currentTimeMillis();
        final String begin = Snotel.localStamp(now - WINDOW_MS), end = Snotel.localStamp(now + 3_600_000L);
        if (readings.isEmpty())
            status("Getting the readings…");
        fetchChunk(chunks, 0, new HashMap<String, Snotel.Reading>(), asked, r, key, begin, end, mine);
        return true;
    }

    private static double d2(Snotel.Station s, double cx, double cy) {
        final double dx = (s.longitude - cx) * Math.cos(Math.toRadians(cy)), dy = s.latitude - cy;
        return dx * dx + dy * dy;
    }

    private void fetchChunk(final List<List<String>> chunks, final int index,
            final Map<String, Snotel.Reading> got, final List<Snotel.Station> asked,
            final GeoBounds r, final String key, final String begin, final String end, final int mine) {
        if (mine != generation || !on) {
            if (key.equals(pendingKey))
                pendingKey = null;
            return;
        }
        if (index >= chunks.size()) {
            if (key.equals(pendingKey))
                pendingKey = null;
            region = r;
            fetchedAt = System.currentTimeMillis();
            inView = asked;
            readings = got;
            redraw();
            return;
        }
        final Map<String, Double> tz = new HashMap<>();
        for (Snotel.Station s : asked)
            tz.put(s.triplet, s.tzOffsetHours);
        Http.get(Snotel.dataUrl(chunks.get(index), begin, end), egress.userAgent(),
                new HashMap<String, String>(), new Http.Callback() {
                    @Override
                    public void onSuccess(String body) {
                        got.putAll(Snotel.parseData(body, tz));
                        fetchChunk(chunks, index + 1, got, asked, r, key, begin, end, mine);
                    }

                    @Override
                    public void onFailure(String error) {
                        if (key.equals(pendingKey))
                            pendingKey = null;
                        if (mine == generation && on)
                            status("Snow stations: " + error);
                    }
                });
    }

    private static boolean contains(GeoBounds outer, GeoBounds inner) {
        return inner.getWest() >= outer.getWest() && inner.getEast() <= outer.getEast()
                && inner.getSouth() >= outer.getSouth() && inner.getNorth() <= outer.getNorth();
    }

    private void redraw() {
        final int mine = generation;
        worker.execute(new Runnable() {
            @Override
            public void run() {
                rebuild(mine);
            }
        });
    }

    /** Worker only: composes icons and writes the store. */
    private void rebuild(int mine) {
        if (mine != generation || !on)
            return;
        final List<Snotel.Station> held = inView;
        final Map<String, Snotel.Reading> read = readings;
        final boolean withLabels = labelsWanted;
        final double[] view = viewBounds();
        final UnitSystem system = units();
        final long now = System.currentTimeMillis();
        final Set<String> labeled = new HashSet<>();
        if (withLabels)
            for (Snotel.Station s : held)
                if (onScreen(s, view))
                    labeled.add(s.triplet);
        lastLabeled = labeled;
        final List<AtmosphereFeatures.Drawn> drawn = new ArrayList<>();
        int withSnow = 0, reporting = 0;
        for (Snotel.Station s : held) {
            final Snotel.Reading r = read.get(s.triplet);
            final double depth = r == null ? Double.NaN : r.snowDepthIn;
            final int color = Snotel.depthColor(depth);
            if (r != null && r.hasAny())
                reporting++;
            if (!Double.isNaN(depth) && depth >= Snotel.DEPTH_STEPS_IN[0])
                withSnow++;
            final String set = r == null || !r.hasAny() ? "No report" : depth >= Snotel.DEPTH_STEPS_IN[0]
                    ? "Snow on the ground" : "Bare ground";
            final AtmosphereFeatures.Drawn d;
            if (withLabels && onScreen(s, view)) {
                final SnotelIcons.Composed c = icons.labeled(color, 0xFFFFFFFF, pill(r, system), s.name);
                if (c == null)
                    continue;
                d = new AtmosphereFeatures.Drawn(set, s.name,
                        AtmosphereFeatures.point(s.latitude, s.longitude),
                        AtmosphereFeatures.icon(c.uri, c.width, c.height, c.offsetX, c.offsetY),
                        attrs(s, r, now, system), 100_000d, FINEST);
            } else {
                final String uri = icons.uri(color);
                if (uri == null)
                    continue;
                d = new AtmosphereFeatures.Drawn(set, s.name,
                        AtmosphereFeatures.point(s.latitude, s.longitude),
                        AtmosphereFeatures.icon(uri, SnotelIcons.SIZE_DP, SnotelIcons.SIZE_DP),
                        attrs(s, r, now, system), 100_000d, FINEST);
            }
            drawn.add(d);
        }
        if (mine != generation || !on)
            return;
        features.rewrite(drawn);
        final String line = line(drawn.size(), reporting, withSnow, cut);
        mapView.post(new Runnable() {
            @Override
            public void run() {
                if (mine != generation || !on)
                    return;
                Log.d(TAG, "drew " + drawn.size() + " snow stations: " + line);
                status(line);
            }
        });
    }

    private static String line(int n, int reporting, int withSnow, int cut) {
        if (n == 0)
            return "No snow stations in view";
        final StringBuilder b = new StringBuilder();
        b.append(n).append(n == 1 ? " snow station" : " snow stations").append(" on the map, ");
        b.append(withSnow == 0 ? "none with snow" : withSnow + " with snow");
        if (reporting < n)
            b.append(", ").append(n - reporting).append(" not reporting");
        if (cut > 0)
            b.append(". Map shows the nearest ").append(n).append(", zoom in for ").append(cut).append(" more");
        return b.toString();
    }

    /** "0 in · 0.2 in SWE · 49 °F" in the operator's units. */
    static String pill(Snotel.Reading r, UnitSystem system) {
        if (r == null || !r.hasAny())
            return "No report";
        final StringBuilder b = new StringBuilder();
        if (!Double.isNaN(r.snowDepthIn))
            b.append(inches(r.snowDepthIn, system));
        if (!Double.isNaN(r.sweIn))
            append(b, inches(r.sweIn, system) + " SWE");
        if (!Double.isNaN(r.tempF))
            append(b, Units.format(Quantity.TEMPERATURE, (r.tempF - 32) / 1.8, system));
        return b.toString();
    }

    /** Inches, or centimeters on the metric setting. */
    static String inches(double in, UnitSystem system) {
        if (system == UnitSystem.METRIC)
            return String.format(Locale.US, in * 2.54 >= 10 ? "%.0f cm" : "%.1f cm", in * 2.54);
        return String.format(Locale.US, in >= 10 ? "%.0f in" : "%.1f in", in);
    }

    private static void append(StringBuilder b, String s) {
        if (s == null || s.isEmpty())
            return;
        if (b.length() > 0)
            b.append("  ·  ");
        b.append(s);
    }

    private AttributeSet attrs(Snotel.Station s, Snotel.Reading r, long now, UnitSystem system) {
        final AttributeSet a = new AttributeSet();
        final StringBuilder d = new StringBuilder();
        if (r == null || !r.hasAny()) {
            d.append("No report in the last six hours");
        } else {
            if (!Double.isNaN(r.snowDepthIn))
                d.append("Snow depth: ").append(inches(r.snowDepthIn, system));
            if (!Double.isNaN(r.sweIn))
                d.append(d.length() > 0 ? "\n" : "").append("Snow water equivalent: ").append(inches(r.sweIn, system));
            if (!Double.isNaN(r.tempF))
                d.append(d.length() > 0 ? "\n" : "").append("Air temperature: ")
                        .append(Units.format(Quantity.TEMPERATURE, (r.tempF - 32) / 1.8, system));
            if (!Double.isNaN(r.precipIn))
                d.append("\nPrecipitation since October 1: ").append(inches(r.precipIn, system));
            if (r.observedAt > 0)
                d.append("\nObserved: ").append(when(r.observedAt, now));
        }
        if (!Double.isNaN(s.elevationFt))
            d.append("\nElevation: ").append(system == UnitSystem.METRIC
                    ? Math.round(s.elevationFt * 0.3048) + " m" : Math.round(s.elevationFt) + " ft");
        if (!s.county.isEmpty() || !s.state.isEmpty())
            d.append("\nCounty: ").append(s.county).append(s.county.isEmpty() ? "" : ", ").append(s.state);
        d.append("\nStation: SNOTEL ").append(s.id).append(s.shefId.isEmpty() ? "" : " (" + s.shefId + ")");
        d.append("\nStation page: ").append(s.url());
        a.setAttribute("_details", d.toString());
        a.setAttribute("Station", s.name);
        return a;
    }

    private static String when(long at, long now) {
        final double hours = (now - at) / 3_600_000.0;
        final String ago = hours < 1.5 ? Math.max(1, Math.round(hours * 60)) + " minutes ago"
                : hours < 48 ? Math.round(hours) + " hours ago" : Math.round(hours / 24) + " days ago";
        return ago + ", " + new java.text.SimpleDateFormat("EEE MMM d, h:mm a", Locale.US)
                .format(new java.util.Date(at));
    }

    private static UnitSystem units() {
        final SharedPreferences p = MapCompat.prefs();
        return UnitSystem.fromName(p == null ? null : p.getString(PREF_UNITS, null), UnitSystem.IMPERIAL);
    }

    private boolean labeledSetChanged() {
        final double[] view = viewBounds();
        final Set<String> now = new HashSet<>();
        for (Snotel.Station s : inView)
            if (onScreen(s, view))
                now.add(s.triplet);
        return !now.equals(lastLabeled);
    }

    private double[] viewBounds() {
        try {
            final GeoBounds b = mapView.getBounds();
            if (b == null)
                return null;
            final double s = b.getSouth(), w = b.getWest(), n = b.getNorth(), e = b.getEast();
            if (Double.isNaN(s) || Double.isNaN(w) || Double.isNaN(n) || Double.isNaN(e))
                return null;
            final double padLat = Math.abs(n - s) / 2.0, padLon = Math.abs(e - w) / 2.0;
            return new double[] { s - padLat, w - padLon, n + padLat, e + padLon };
        } catch (Exception noBounds) {
            return null;
        }
    }

    private static boolean onScreen(Snotel.Station s, double[] view) {
        if (view == null)
            return true;
        return s.latitude >= view[0] && s.latitude <= view[2]
                && s.longitude >= view[1] && s.longitude <= view[3];
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
