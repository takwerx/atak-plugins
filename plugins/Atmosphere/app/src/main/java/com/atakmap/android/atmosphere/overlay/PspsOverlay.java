package com.atakmap.android.atmosphere.overlay;

import android.content.Context;

import com.atakmap.android.atmosphere.data.Psps;
import com.atakmap.android.atmosphere.net.EgressPolicy;
import com.atakmap.android.maps.MapView;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Public Safety Power Shutoffs on the map, California, from Cal OES by way of
 * takwerx's relay: the counties a utility has warned may have a shutoff (amber), and
 * the areas where the power is off for public safety (red). In the Fire group because
 * a shutoff is called for fire weather (operator, 2026-09-30); Atmosphere's and not
 * Critical Infrastructure's (2026-10-01).
 *
 * <p>One request every 15 minutes while on, the relay's file (250 KB gzipped for 528
 * areas). The status line always says what it covers, and a part Cal OES or the relay
 * has stopped rewriting is unknown, never "none".
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
            { Psps.OFF + ": the utility has shut the power off here",
                    String.valueOf(Psps.COLORS[1]) } };

    private volatile long countiesUpdated, areasUpdated;
    private volatile int countyRows;

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
        areasUpdated = feed.areasUpdated;
        final List<Area> out = new ArrayList<>();
        for (Psps.Area p : feed.areas) {
            final StringBuilder d = new StringBuilder();
            final String title;
            final long when;
            if (Psps.POSSIBLE.equals(p.kind)) {
                title = p.county + ": " + Psps.POSSIBLE;
                when = feed.countiesUpdated;
                d.append(p.county).append(" County\n").append(Psps.POSSIBLE)
                        .append(": a utility has told Cal OES it may cut power somewhere in the"
                                + " county to keep its lines from starting a fire. Which"
                                + " neighborhoods is the utility's to say.");
            } else {
                title = (p.utility.isEmpty() ? "" : p.utility + " - ") + p.kind;
                when = feed.areasUpdated;
                d.append(title).append("\nCounty: ").append(p.county);
                if (p.customers > 0)
                    d.append("\nCustomers out: ").append(p.customers);
                if (p.since > 0)
                    d.append("\nOff since: ").append(day(p.since));
                d.append("\nExpected back on: ")
                        .append(p.restore > 0 ? day(p.restore) : "the utility has not said");
            }
            if (when > 0)
                d.append("\nCal OES updated: ").append(day(when));
            d.append("\nFrom: California Governor's Office of Emergency Services, as reported by")
                    .append(" PG&E, SCE and SDG&E");
            out.add(new Area(1, title, p.kind, Psps.color(p.kind), p.geometry, d.toString()));
        }
        return out;
    }

    @Override
    protected String summary(List<Area> areas) {
        final long now = System.currentTimeMillis();
        final boolean countiesFresh = fresh(countiesUpdated, now) && countyRows > 0;
        final boolean areasFresh = fresh(areasUpdated, now);
        final Set<String> counties = new LinkedHashSet<>();
        int off = 0;
        for (Area a : areas) {
            if (Psps.POSSIBLE.equals(a.label))
                counties.add(a.title.substring(0, a.title.indexOf(':')));
            else if (Psps.OFF.equals(a.label))
                off++;
        }
        final StringBuilder b = new StringBuilder();
        if (countiesFresh && areasFresh && counties.isEmpty() && off == 0)
            b.append("No shutoffs warned or in effect.");
        else {
            if (!countiesFresh)
                b.append("Warned counties unknown: ").append(quiet(countiesUpdated)).append('.');
            else
                b.append(counties.isEmpty() ? "No counties warned."
                        : Psps.POSSIBLE + ": " + join(counties) + ".");
            b.append(' ');
            if (!areasFresh)
                b.append("PSPS areas unknown: ").append(quiet(areasUpdated)).append('.');
            else
                b.append(off == 0 ? "PSPS: no areas shut off."
                        : "PSPS: " + areasText(off) + " shut off.");
        }
        if (countiesFresh && areasFresh)
            b.append(" Cal OES updated ").append(time(Math.min(countiesUpdated, areasUpdated)))
                    .append('.');
        return b.append('\n').append(COVERS).toString();
    }

    @Override
    protected String noun() {
        return "PSPS status";
    }

    private static boolean fresh(long updated, long now) {
        return updated > 0 && now - updated <= Psps.STALE_MS;
    }

    private static String quiet(long updated) {
        return updated > 0 ? "Cal OES has not updated it since " + time(updated)
                : "Cal OES could not be read";
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
