package com.atakmap.android.atmosphere.overlay;

import android.content.Context;

import com.atakmap.android.atmosphere.compat.ScaleBar;
import com.atakmap.android.atmosphere.data.Psps;
import com.atakmap.android.atmosphere.net.EgressPolicy;
import com.atakmap.android.maps.MapView;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Public Safety Power Shutoffs on the map, California, by way of takwerx's relay: the
 * counties a utility has warned Cal OES may have a shutoff (amber), the circuits SCE
 * and SDG&E are watching (orange), and the areas shut off (red). In the Fire group because
 * a shutoff is called for fire weather (operator, 2026-09-30); Atmosphere's and not
 * Critical Infrastructure's (2026-10-01).
 *
 * <p>One request every 15 minutes while on, the relay's file (460 KB gzipped for 1,400
 * areas). The status line always says what it covers, and a part whose source or the
 * relay has stopped is unknown, never "none".
 */
public final class PspsOverlay extends OutlookOverlay {

    public static final String LAYER_ID = "psps";
    public static final String HOST = Psps.HOST;
    private static final String[] URLS = { Psps.URL };
    private static final String COVERS = "Covers PG&E, SCE and SDG&E customers in California only;"
            + " not PacifiCorp, Liberty, Bear Valley or other states.";

    /** The key: each kind with what it means. */
    public static final String[][] LEGEND = {
            { Psps.POSSIBLE + ": a utility has warned Cal OES it may cut power in the county",
                    String.valueOf(Psps.COLORS[0]) },
            { Psps.WATCH + ": the utility may shut the power off here",
                    String.valueOf(Psps.COLORS[1]) },
            { Psps.OFF + ": the utility has shut the power off here",
                    String.valueOf(Psps.COLORS[2]) } };

    /** Who each source's areas come from, for the details. */
    private static final Map<String, String> FROM = new HashMap<>();
    static {
        FROM.put("PG&E", "California Governor's Office of Emergency Services, as reported by PG&E");
        FROM.put("SCE", "Southern California Edison");
        FROM.put("SDG&E", "San Diego Gas & Electric");
    }

    private volatile long countiesUpdated;
    private volatile int countyRows;
    private volatile List<Psps.Source> sources = new ArrayList<>();

    public PspsOverlay(MapView mapView, Context pluginContext, EgressPolicy egress) {
        super(mapView, pluginContext, egress, "AtmospherePsps", LAYER_ID, "PSPS");
        this.mapView = mapView;
        setSetFilter(new AtmosphereFeatures.SetFilter() {
            @Override
            public boolean shown(String set) {
                final int b = band;
                if (set.startsWith(COUNTIES + " labels"))
                    return b <= COUNTY_LABELS_BAND;
                if (set.startsWith(CIRCUITS + " labels"))
                    return b == OWN;
                if (set.startsWith(PILLS))
                    return set.equals(PILLS + b);
                return true;
            }
        });
    }

    @Override
    public void start() {
        super.start();
        settle.run();
        mapView.addOnMapMovedListener(moved);
    }

    @Override
    public void stop() {
        mapView.removeOnMapMovedListener(moved);
        mapView.removeCallbacks(settle);
        super.stop();
    }

    @Override
    protected String[] urls() {
        return URLS;
    }

    /** The file is 1 MB as JSON in a big shutoff and a quarter of that gzipped. */
    @Override
    protected Map<String, String> headers(int index) {
        final Map<String, String> h = new HashMap<>();
        h.put("Accept-Encoding", "gzip");
        return h;
    }

    /**
     * PSPS has no days; the details pane printed "Day 1" under a county's title on
     * the signed 0.8 (S22 Ultra, 2026-10-01).
     */
    @Override
    protected String setName(Area a) {
        return a != null && Psps.POSSIBLE.equals(a.label) ? COUNTIES : CIRCUITS;
    }

    /** The two kinds of area live in sets of their own, so their labels can be shown apart. */
    private static final String COUNTIES = "Public Safety Power Shutoffs, warned counties",
            CIRCUITS = "Public Safety Power Shutoffs, California", PILLS = "PSPS pills ";

    @Override
    protected long pollMs() {
        return 15 * 60 * 1000L;
    }

    @Override
    public int days() {
        return 1;
    }

    @Override
    public boolean allDays() {
        return false;
    }

    @Override
    protected int fillAlpha(Area a) {
        return Psps.POSSIBLE.equals(a.label) ? 0x48 : 0x80;
    }

    @Override
    protected int[] labelColors(Area a) {
        return new int[] { Psps.textColor(a.label), Psps.color(a.label) };
    }

    /**
     * Which labels show, by what ATAK's scale bar reads (operator, 2026-10-16, over
     * 1,400 circuit labels on the coast: "like 5 miles they are individual. but further
     * out ... make one in an area if they are multiple", and at 380 miles: "at this
     * level no labels"). Up to 10 miles each circuit has its own label; to 20, 40, 60,
     * 100 and 150 miles nearby circuits share pills worked out for that range; past 150
     * there are none. A county's name shows to 60 miles, where 31 of them start to cover
     * each other. The ranges are narrow because a pill's grouping is worked out for a
     * range's far edge: from 25 to 60 miles it merged circuits 70 miles apart. The plugin picks the range when the map settles and shows that range's
     * sets: ATAK's own zoom bands on a set test a window two tile steps wide around the
     * map, and drew three ranges' pills at once (XCover).
     */
    private static final double[] BAND_MILES = { 10, 20, 40, 60, 100, 150 };
    /** The range where each circuit has its own label, and the one past every range. */
    private static final int OWN = 0, NONE = BAND_MILES.length;
    private static final int COUNTY_LABELS_BAND = 3;
    /** How far apart two circuits may be and share a pill, in pill widths. */
    private static final double JOIN_WIDTHS = 1.0;
    private static final long SETTLE_MS = 400;

    private final MapView mapView;
    private volatile int band = OWN;
    /** How many pixels ATAK's scale bar spans, read when the map settles. */
    private volatile double barPixels = ScaleBar.FALLBACK_BAR_PIXELS;

    private final com.atakmap.map.AtakMapView.OnMapMovedListener moved =
            new com.atakmap.map.AtakMapView.OnMapMovedListener() {
                @Override
                public void onMapMoved(com.atakmap.map.AtakMapView v, boolean animate) {
                    // GL thread. Nothing is touched here but the callback queue.
                    mapView.removeCallbacks(settle);
                    mapView.postDelayed(settle, SETTLE_MS);
                }
            };

    /** On main: the range the scale bar is in now, and the sets for it if it changed. */
    private final Runnable settle = new Runnable() {
        @Override
        public void run() {
            final double bar = ScaleBar.meters(mapView);
            final double res = mapView.getMapResolution();
            if (bar <= 0)
                return;
            if (res > 0)
                barPixels = bar / res;
            int b = 0;
            while (b < BAND_MILES.length && bar > BAND_MILES[b] * 1609.344)
                b++;
            if (b == band)
                return;
            band = b;
            applySetFilter();
        }
    };

    /** A county is big; drawn as soon as the scale bar says so, see {@link #BAND_MILES}. */
    @Override
    protected double labelMaxResolution() {
        return Double.MAX_VALUE;
    }

    /**
     * For each range from 10 to 150 miles, the circuits of one utility and kind close
     * enough to crowd one pill's space share a pill, "SCE - PSPS (23)", on the point
     * inside the group's own circuit nearest its middle (the point a lone circuit's
     * label takes). Grouping every utility together labeled 14 PG&E circuits in Amador
     * on an SCE circuit at the Nevada line, and the middle of a circuit's box can be
     * open ground between its pieces (XCover, 2026-10-16). A watched pill that would
     * cover a shut-off one moves down one pill, once; moving pills again and again put
     * them out at sea. Counties keep their own labels.
     */
    @Override
    protected List<AtmosphereFeatures.Drawn> drawnTogether(List<Area> areas,
            List<com.atakmap.map.layer.feature.geometry.Geometry> shapes) {
        final List<AtmosphereFeatures.Drawn> out = new ArrayList<>();
        final List<double[]> points = new ArrayList<>();
        final List<Area> circuits = new ArrayList<>();
        for (int i = 0; i < areas.size(); i++) {
            final Area a = areas.get(i);
            if (Psps.POSSIBLE.equals(a.label))
                continue;
            final double[] p = AtmosphereFeatures.labelPoint(shapes.get(i));
            if (p == null)
                continue;
            circuits.add(a);
            points.add(p);
        }
        if (circuits.isEmpty())
            return out;
        // The size a pill takes, from the longest title; a count adds a little.
        int pillW = 0, pillH = 0;
        for (String kind : new String[] { Psps.OFF, Psps.WATCH }) {
            final int[] px = pillPixels("SDG&E - " + kind + " (000)", Psps.textColor(kind),
                    Psps.color(kind));
            if (px != null) {
                pillW = Math.max(pillW, px[0]);
                pillH = Math.max(pillH, px[1]);
            }
        }
        if (pillW == 0)
            return out;
        final double barPx = barPixels;
        for (int b = OWN + 1; b < NONE; b++) {
            // Ground per pixel at the range's far edge: where a pill covers the most.
            final double mpp = BAND_MILES[b] * 1609.344 / barPx;
            final double join = JOIN_WIDTHS * pillW * mpp;
            final List<Group> groups = new ArrayList<>();
            for (int i = 0; i < circuits.size(); i++) {
                final Area a = circuits.get(i);
                final double[] c = points.get(i);
                Group near = null;
                for (Group g : groups)
                    if (g.title.equals(a.title) && meters(g.lat(), g.lon(), c[0], c[1]) < join) {
                        near = g;
                        break;
                    }
                if (near == null) {
                    near = new Group(a.title, a.label);
                    groups.add(near);
                }
                near.add(c);
            }
            // Shut off first, the biggest first: those keep their place.
            java.util.Collections.sort(groups, new java.util.Comparator<Group>() {
                @Override
                public int compare(Group x, Group y) {
                    final boolean xo = Psps.OFF.equals(x.kind), yo = Psps.OFF.equals(y.kind);
                    if (xo != yo)
                        return xo ? -1 : 1;
                    return y.points.size() - x.points.size();
                }
            });
            final double w = pillW * mpp, h = pillH * 1.1 * mpp;
            final List<double[]> placed = new ArrayList<>();
            for (Group g : groups) {
                final double[] at = g.anchor();
                double lat = at[0];
                if (covers(placed, lat, at[1], w, h))
                    lat -= h / 110_540d;
                placed.add(new double[] { lat, at[1] });
                final int n = g.points.size();
                final AtmosphereFeatures.Drawn d = clusterPill(PILLS + b,
                        n == 1 ? g.title : g.title + " (" + n + ")", Psps.textColor(g.kind),
                        Psps.color(g.kind), lat, at[1], Double.MAX_VALUE, 0d);
                if (d != null)
                    out.add(d);
            }
        }
        return out;
    }

    /** Circuits of one utility and kind close together in one range. */
    private static final class Group {
        final String title, kind;
        final List<double[]> points = new ArrayList<>();
        double sumLat, sumLon;

        Group(String title, String kind) {
            this.title = title;
            this.kind = kind;
        }

        void add(double[] c) {
            points.add(c);
            sumLat += c[0];
            sumLon += c[1];
        }

        double lat() {
            return sumLat / points.size();
        }

        double lon() {
            return sumLon / points.size();
        }

        /** The group's own circuit nearest its middle, so a pill is always on one. */
        double[] anchor() {
            double[] best = points.get(0);
            double bestD = Double.MAX_VALUE;
            for (double[] p : points) {
                final double d = meters(lat(), lon(), p[0], p[1]);
                if (d < bestD) {
                    bestD = d;
                    best = p;
                }
            }
            return best;
        }
    }

    /** Whether a pill w by h meters at this point would cover one already placed. */
    private static boolean covers(List<double[]> placed, double lat, double lon, double w, double h) {
        for (double[] p : placed) {
            final double dy = Math.abs(p[0] - lat) * 110_540d;
            final double dx = Math.abs(p[1] - lon) * 111_320d * Math.cos(Math.toRadians(lat));
            if (dx < w && dy < h)
                return true;
        }
        return false;
    }

    /** Meters between two points, flat: plenty for a few pill widths. */
    private static double meters(double lat1, double lon1, double lat2, double lon2) {
        final double dy = (lat2 - lat1) * 110_540d;
        final double dx = (lon2 - lon1) * 111_320d * Math.cos(Math.toRadians((lat1 + lat2) / 2));
        return Math.sqrt(dx * dx + dy * dy);
    }

    @Override
    protected List<Area> parse(int index, String body) {
        final Psps.Feed feed = Psps.parse(body);
        if (feed == null)
            throw new Refused("the shutoff list could not be read");
        countiesUpdated = feed.countiesUpdated;
        countyRows = feed.countyRows;
        sources = feed.sources;
        final List<Area> out = new ArrayList<>();
        for (Psps.Area p : feed.areas) {
            final StringBuilder d = new StringBuilder();
            final String title;
            final long when;
            final String from;
            if (Psps.POSSIBLE.equals(p.kind)) {
                title = p.county + ": " + Psps.POSSIBLE;
                when = feed.countiesUpdated;
                from = "California Governor's Office of Emergency Services";
                d.append(p.county).append(" County\n").append(Psps.POSSIBLE)
                        .append(": a utility has told Cal OES it may cut power somewhere in the"
                                + " county to keep its lines from starting a fire. Which"
                                + " neighborhoods is the utility's to say.");
            } else {
                final boolean off = Psps.OFF.equals(p.kind);
                title = (p.utility.isEmpty() ? "" : p.utility + " - ") + p.kind;
                when = updated(feed.sources, p.utility);
                from = FROM.containsKey(p.utility) ? FROM.get(p.utility) : p.utility;
                d.append(title);
                if (!off)
                    d.append("\n").append(p.utility.isEmpty() ? "The utility" : p.utility)
                            .append(" may shut the power off here to keep its lines from"
                                    + " starting a fire.");
                if (!p.circuit.isEmpty())
                    d.append("\nCircuit: ").append(p.circuit);
                if (!p.county.isEmpty())
                    d.append("\nCounty: ").append(p.county);
                if (!p.community.isEmpty())
                    d.append("\nCommunity: ").append(p.community);
                if (p.customers > 0)
                    d.append(off ? "\nCustomers out: " : "\nCustomers: ").append(p.customers);
                if (off && p.since > 0)
                    d.append("\nOff since: ").append(day(p.since));
                else if (off && !p.sinceText.isEmpty())
                    d.append("\nOff since: ").append(p.sinceText);
                if (p.start > 0 && p.end > 0)
                    d.append("\nShutoff window: ").append(day(p.start)).append(" to ")
                            .append(day(p.end));
                if (p.restore > 0)
                    d.append(off ? "\nExpected back on: " : "\nExpected back on if shut off: ")
                            .append(day(p.restore));
                else if (!p.restoreText.isEmpty())
                    d.append(off ? "\nExpected back on: " : "\nExpected back on if shut off: ")
                            .append(p.restoreText);
                else if (off)
                    d.append("\nExpected back on: the utility has not said");
            }
            if (when > 0)
                d.append("\nUpdated: ").append(day(when));
            d.append("\nFrom: ").append(from);
            out.add(new Area(1, title, p.kind, Psps.color(p.kind), p.geometry, d.toString()));
        }
        // The layer draws in this order, the last on top: a county under the circuits in
        // it, and a circuit shut off over one only watched, whose shapes overlap.
        final List<Area> ordered = new ArrayList<>(out.size());
        for (String kind : Psps.KINDS)
            for (Area a : out)
                if (kind.equals(a.label))
                    ordered.add(a);
        return ordered;
    }

    @Override
    protected String summary(List<Area> areas) {
        final long now = System.currentTimeMillis();
        final boolean countiesFresh = fresh(countiesUpdated, now) && countyRows > 0;
        final List<Psps.Source> from = sources;
        final List<Psps.Source> stale = new ArrayList<>();
        long oldest = countiesUpdated;
        for (Psps.Source s : from) {
            if (!fresh(s.updated, now))
                stale.add(s);
            oldest = Math.min(oldest, s.updated);
        }
        final Set<String> counties = new LinkedHashSet<>();
        final Map<String, Integer> offBy = new LinkedHashMap<>();
        int off = 0, watch = 0;
        for (Area a : areas) {
            if (Psps.POSSIBLE.equals(a.label))
                counties.add(a.title.substring(0, a.title.indexOf(':')));
            else if (Psps.WATCH.equals(a.label))
                watch++;
            else if (Psps.OFF.equals(a.label)) {
                off++;
                final int dash = a.title.indexOf(" - ");
                final String who = dash > 0 ? a.title.substring(0, dash) : "";
                offBy.put(who, offBy.containsKey(who) ? offBy.get(who) + 1 : 1);
            }
        }
        final StringBuilder b = new StringBuilder();
        if (countiesFresh && stale.isEmpty() && counties.isEmpty() && off + watch == 0)
            b.append("No shutoffs warned or in effect.");
        else {
            if (!countiesFresh)
                b.append("Warned counties unknown: ").append(quiet(countiesUpdated)).append('.');
            else
                b.append(counties.isEmpty() ? "No counties warned."
                        : Psps.POSSIBLE + ": " + join(counties) + ".");
            b.append(off == 0 ? " PSPS: no areas shut off." : " PSPS: " + areasText(off)
                    + " shut off" + (offBy.size() > 1 ? " (" + split(offBy) + ")" : "") + ".");
            if (watch > 0)
                b.append(' ').append(Psps.WATCH).append(": ").append(areasText(watch)).append('.');
            for (Psps.Source s : stale)
                b.append(' ').append(s.name).append(" unknown: ").append(quiet(s.updated))
                        .append('.');
        }
        if (countiesFresh && stale.isEmpty() && oldest > 0)
            b.append(" Updated ").append(time(oldest)).append('.');
        return b.append('\n').append(COVERS).toString();
    }

    @Override
    protected String noun() {
        return "PSPS status";
    }

    /** When the source an area came from was last updated; 0 when unknown. */
    private static long updated(List<Psps.Source> sources, String name) {
        for (Psps.Source s : sources)
            if (s.name.equals(name))
                return s.updated;
        return 0;
    }

    private static String split(Map<String, Integer> by) {
        final StringBuilder b = new StringBuilder();
        for (Map.Entry<String, Integer> e : by.entrySet())
            b.append(b.length() > 0 ? ", " : "").append(e.getKey().isEmpty() ? "other" : e.getKey())
                    .append(' ').append(e.getValue());
        return b.toString();
    }

    private static boolean fresh(long updated, long now) {
        return updated > 0 && now - updated <= Psps.STALE_MS;
    }

    private static String quiet(long updated) {
        return updated > 0 ? "not updated since " + time(updated) : "could not be read";
    }

    private static String areasText(int n) {
        return n == 1 ? "1 area" : n + " areas";
    }

    private static String join(Set<String> names) {
        final StringBuilder b = new StringBuilder();
        for (String n : names)
            b.append(b.length() > 0 ? ", " : "").append(n);
        return b.toString();
    }

    private static String time(long millis) {
        return new SimpleDateFormat("h:mm a", Locale.US).format(new Date(millis));
    }

    /** A shutoff runs past midnight, so its times carry the day. */
    private static String day(long millis) {
        return new SimpleDateFormat("EEE MMM d, h:mm a", Locale.US).format(new Date(millis));
    }
}
