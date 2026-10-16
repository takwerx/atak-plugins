package com.atakmap.android.atmosphere.overlay;

import android.content.Context;

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
        return "Public Safety Power Shutoffs, California";
    }

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

    /** A county is big; a shutoff area is a few circuits, labeled once it is a few miles across. */
    @Override
    protected double labelMaxResolution() {
        return 500d;
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
