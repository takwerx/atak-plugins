package com.atakmap.android.atmosphere.overlay;

import android.content.Context;

import com.atakmap.android.atmosphere.data.Psps;
import com.atakmap.android.atmosphere.net.EgressPolicy;
import com.atakmap.android.maps.MapView;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Public Safety Power Shutoffs on the map, California, from Cal OES: the counties a
 * utility has warned may have a shutoff (amber), and the areas where the power is
 * off (red), off because a circuit upstream was cut (orange), or back on (green).
 * In the Fire group because a shutoff is called for fire weather (operator,
 * 2026-09-30); Atmosphere's and not Critical Infrastructure's (2026-10-01).
 *
 * <p>Three requests every 15 minutes while on: the county rows' newest stamp (about
 * 700 bytes), the flagged counties, the shutoff areas. The status line always says
 * what it covers, and when Cal OES has gone quiet it says the status is unknown
 * rather than "none".
 */
public final class PspsOverlay extends OutlookOverlay {

    public static final String LAYER_ID = "psps";
    public static final String HOST = Psps.HOST;
    private static final String[] URLS = { Psps.FRESHNESS_URL, Psps.COUNTIES_URL, Psps.AREAS_URL };
    private static final String COVERS = "Covers PG&E, SCE and SDG&E customers in California only;"
            + " not PacifiCorp, Liberty, Bear Valley or other states.";

    /** The key: each kind with what it means. */
    public static final String[][] LEGEND = {
            { Psps.POSSIBLE + ": a utility has warned Cal OES it may cut power in the county",
                    String.valueOf(Psps.COLORS[0]) },
            { Psps.OFF + ": the utility has cut power here", String.valueOf(Psps.COLORS[1]) },
            { Psps.DOWNSTREAM + ": off because a line feeding it was cut",
                    String.valueOf(Psps.COLORS[2]) },
            { Psps.RESTORED + ": power is back on", String.valueOf(Psps.COLORS[3]) } };

    private volatile long updated;
    private volatile long rows;

    public PspsOverlay(MapView mapView, Context pluginContext, EgressPolicy egress) {
        super(mapView, pluginContext, egress, "AtmospherePsps", LAYER_ID, "PSPS");
    }

    @Override
    protected String[] urls() {
        return URLS;
    }

    /**
     * PSPS has no days; the details pane printed "Day 1" under a county's title on
     * the signed 0.8 (S22 Ultra, 2026-10-01).
     */
    @Override
    protected String setName(Area a) {
        return "Public Safety Power Shutoffs, California";
    }

    /** Without the stamp the areas still draw, and the status says it cannot vouch for them. */
    @Override
    protected boolean optional(int index) {
        return index == 0;
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
        final List<Area> out = new ArrayList<>();
        if (index == 0) {
            // A failed check leaves the last stamp read: still the real time Cal OES last
            // published, and past 45 minutes it says "unknown" by itself.
            final long[] f = Psps.freshness(body);
            updated = f[0];
            rows = f[1];
            return out;
        }
        final String when = updated > 0 ? time(updated) : "";
        final List<Psps.Area> got = index == 1 ? Psps.parseCounties(body) : Psps.parseAreas(body);
        for (Psps.Area p : got) {
            final StringBuilder d = new StringBuilder();
            final String title;
            if (Psps.POSSIBLE.equals(p.kind)) {
                title = p.county + ": " + Psps.POSSIBLE;
                d.append(p.county).append(" County\n").append(Psps.POSSIBLE)
                        .append(": a utility has told Cal OES it may cut power somewhere in the"
                                + " county to keep its lines from starting a fire. Which"
                                + " neighborhoods is the utility's to say.");
            } else {
                title = (p.utility.isEmpty() ? "" : p.utility + ": ") + p.kind;
                d.append(title).append("\nCounty: ").append(p.county)
                        .append("\nStatus: ").append(p.status);
                if (!p.event.isEmpty())
                    d.append("\nEvent: ").append(p.event);
            }
            if (!when.isEmpty())
                d.append("\nCal OES updated: ").append(when);
            d.append("\nFrom: California Governor's Office of Emergency Services, as reported by")
                    .append(" PG&E, SCE and SDG&E");
            out.add(new Area(1, title, p.kind, Psps.color(p.kind), p.geometry, d.toString()));
        }
        return out;
    }

    @Override
    protected String summary(List<Area> areas) {
        final StringBuilder b = new StringBuilder();
        final long age = updated > 0 ? System.currentTimeMillis() - updated : Long.MAX_VALUE;
        final boolean stale = age > Psps.STALE_MS || rows == 0;
        if (stale)
            b.append(updated > 0
                    ? "Cal OES has not updated since " + time(updated) + ": shutoff status unknown."
                    : "Cal OES did not say when it last updated: shutoff status unknown.");
        final Set<String> counties = new LinkedHashSet<>();
        int off = 0, downstream = 0, restored = 0;
        for (Area a : areas) {
            if (Psps.POSSIBLE.equals(a.label))
                counties.add(a.title.substring(0, a.title.indexOf(':')));
            else if (Psps.OFF.equals(a.label))
                off++;
            else if (Psps.DOWNSTREAM.equals(a.label))
                downstream++;
            else if (Psps.RESTORED.equals(a.label))
                restored++;
        }
        if (!stale) {
            if (counties.isEmpty() && off + downstream + restored == 0)
                b.append("No shutoffs warned or in effect.");
            else {
                b.append(counties.isEmpty() ? "No counties warned."
                        : Psps.POSSIBLE + ": " + join(counties) + ".");
                b.append(off == 0 ? " Power off: none." : " Power off: " + areasText(off) + ".");
                if (downstream > 0)
                    b.append(" Off upstream: ").append(areasText(downstream)).append('.');
                if (restored > 0)
                    b.append(" Restored: ").append(areasText(restored)).append('.');
            }
            b.append(" Cal OES updated ").append(time(updated)).append('.');
        }
        return b.append('\n').append(COVERS).toString();
    }

    @Override
    protected String noun() {
        return "PSPS status";
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
}
