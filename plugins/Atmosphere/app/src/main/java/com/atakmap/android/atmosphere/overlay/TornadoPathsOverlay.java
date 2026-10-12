package com.atakmap.android.atmosphere.overlay;

import android.content.Context;

import com.atakmap.android.atmosphere.compat.GeneratedFiles;
import com.atakmap.android.atmosphere.data.DebrisFlow;
import com.atakmap.android.atmosphere.data.TornadoPaths;
import com.atakmap.coremap.maps.assets.Icon;
import com.atakmap.android.atmosphere.net.EgressPolicy;
import com.atakmap.android.maps.MapView;
import com.atakmap.map.layer.feature.AttributeSet;
import com.atakmap.map.layer.feature.geometry.Geometry;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Surveyed tornado paths on the map: the track a survey team walked, as a line in
 * the DAT viewer's color for its EF rating, labeled with the rating and the
 * office's name for the event. The day row is a window, 30 days, 90 days or a
 * year, over the one file.
 *
 * <p>A year of paths is asked for whole, hourly: surveys land a day or more after
 * the storm and are edited for days after that. Nothing about the phone leaves.
 */
public final class TornadoPathsOverlay extends OutlookOverlay {

    public static final String LAYER_ID = "tornado";
    public static final String HOST = TornadoPaths.HOST;
    private static final String TAG = "AtmosphereTornado";
    private static final String[] WINDOWS = { "2 days", "7 days", "30 days", "1 year" };
    private static final long DAY_MS = 24L * 60 * 60 * 1000;

    /** The key: each rating with its color. */
    public static final String[][] LEGEND;

    static {
        LEGEND = new String[TornadoPaths.RATINGS.length][];
        for (int i = 0; i < TornadoPaths.RATINGS.length; i++)
            LEGEND[i] = new String[] { TornadoPaths.MEANINGS[i],
                    String.valueOf(TornadoPaths.COLORS[i]) };
    }

    private final DotIcons icons;
    private final List<String> pages = new CopyOnWriteArrayList<>();
    private volatile String since = TornadoPaths.ymd(System.currentTimeMillis() - 366 * DAY_MS);
    private volatile int received;

    public TornadoPathsOverlay(MapView mapView, Context pluginContext, EgressPolicy egress) {
        super(mapView, pluginContext, egress, TAG, LAYER_ID, "Tornado Paths");
        icons = new DotIcons(GeneratedFiles.icons("dots"));
        pages.add(TornadoPaths.url(since, 0));
    }

    @Override
    protected void onRefreshStart() {
        since = TornadoPaths.ymd(System.currentTimeMillis() - 366 * DAY_MS);
        pages.clear();
        pages.add(TornadoPaths.url(since, 0));
        received = 0;
    }

    @Override
    protected String[] urls() {
        return pages.toArray(new String[0]);
    }

    @Override
    protected boolean large(int index) {
        return true;
    }

    @Override
    protected boolean optional(int index) {
        return index > 0;
    }

    @Override
    protected long pollMs() {
        return 60 * 60 * 1000L;
    }

    @Override
    public int days() {
        return 4;
    }

    /** 30 days on a new install: two days is often empty, since a survey takes a day or more. */
    @Override
    protected int defaultDay() {
        return 3;
    }

    @Override
    public boolean allDays() {
        return false;
    }

    @Override
    public String dayLabel(int day) {
        return WINDOWS[Math.max(1, Math.min(4, day)) - 1];
    }

    @Override
    protected boolean shown(Area a, int showDay) {
        return a.day <= showDay;
    }

    @Override
    protected String setName(Area a) {
        return a.label;
    }

    @Override
    protected List<Area> parse(int index, String body) {
        final List<Area> out = new ArrayList<>();
        final List<TornadoPaths.Track> got = TornadoPaths.parse(body);
        received += got.size();
        if (!got.isEmpty() && com.atakmap.android.atmosphere.data.StormReports.exceeded(body))
            pages.add(TornadoPaths.url(since, received));
        final long now = System.currentTimeMillis();
        for (TornadoPaths.Track t : got) {
            final long age = now - (t.stormDate > 0 ? t.stormDate : t.start);
            final int day = age <= 2 * DAY_MS ? 1 : age <= 7 * DAY_MS ? 2
                    : age <= 30 * DAY_MS ? 3 : 4;
            final String rating = TornadoPaths.rating(t.rating);
            final String title = rating + (t.event.isEmpty() ? "" : " " + t.event);
            final StringBuilder d = new StringBuilder();
            d.append(rating.equals("Wind") ? "Thunderstorm wind damage, not a tornado"
                    : rating.equals("Unrated") ? "Tornado, not yet rated" : "Tornado rated " + rating);
            if (!t.event.isEmpty())
                d.append("\nEvent: ").append(t.event);
            if (!t.office.isEmpty())
                d.append("\nOffice: ").append(t.office);
            d.append("\nStorm: ").append(StormReportsOverlay.when(t.stormDate > 0 ? t.stormDate : t.start));
            if (t.start > 0 && t.end > t.start)
                d.append("\nOn the ground: ").append(minutes(t.end - t.start));
            if (!Double.isNaN(t.lengthMi) && t.lengthMi > 0)
                d.append("\nPath length: ").append(String.format(Locale.US, "%.1f mi (%.1f km)",
                        t.lengthMi, t.lengthMi * 1.609344));
            if (!Double.isNaN(t.widthYd) && t.widthYd > 0)
                d.append("\nPath width: ").append(String.format(Locale.US, "%.0f yd (%.0f m)",
                        t.widthYd, t.widthYd * 0.9144));
            if (t.maxWind > 0)
                d.append("\nPeak wind the damage implies: ").append(t.maxWind).append(" mph (")
                        .append(Math.round(t.maxWind * 1.609344)).append(" km/h)");
            d.append("\nInjuries: ").append(t.injuries).append("  Deaths: ").append(t.fatalities);
            if (!t.comments.isEmpty())
                d.append("\nSurvey: ").append(t.comments);
            d.append("\nFrom: NWS Damage Assessment Toolkit, preliminary until the office finalizes it");
            out.add(new Area(day, title, rating.equals("Unrated") ? "EFU" : rating.equals("Wind")
                    ? "TSTM/Wind" : rating, TornadoPaths.color(t.rating), t.geometry,
                    d.toString(), "", t));
        }
        return out;
    }

    /**
     * The path as an unlabeled line, and a level pill at the touchdown. A label along
     * the line rode at the line's angle and read sideways (operator, 2026-10-11: "im
     * not sure if along the line is best"); labels stay level, in a pill above a mark.
     */
    @Override
    protected List<AtmosphereFeatures.Drawn> drawnAll(Area a, Geometry g, AttributeSet s) {
        final List<AtmosphereFeatures.Drawn> out = new ArrayList<>(2);
        if (!(a.extra instanceof TornadoPaths.Track))
            return out;
        final TornadoPaths.Track t = (TornadoPaths.Track) a.extra;
        final float weight = 3f + Math.max(0, t.efNum) * 0.75f;
        out.add(new AtmosphereFeatures.Drawn(setName(a), a.title, g,
                AtmosphereFeatures.stroke(a.title, a.color, weight, false), s));
        final double[] start = DebrisFlow.firstPoint(t.geometry);
        final Icon icon = start == null ? null : icons.labelled(a.title, a.color, 0xFFFFFFFF, 12);
        if (icon != null) {
            final AttributeSet s2 = new AttributeSet();
            s2.setAttribute("_details", a.details);
            s2.setAttribute("Outlook", a.title);
            out.add(new AtmosphereFeatures.Drawn(setName(a), a.title,
                    AtmosphereFeatures.point(start[0], start[1]),
                    AtmosphereFeatures.icon(icon.getImageUri(Icon.STATE_DEFAULT), icon.getWidth(),
                            icon.getHeight()), s2));
        }
        return out;
    }

    @Override
    protected AtmosphereFeatures.Drawn drawn(Area a, Geometry g, AttributeSet s) {
        return null;
    }

    @Override
    protected String summary(List<Area> areas) {
        final int window = day();
        int n = 0, deaths = 0, injuries = 0, lo = 99, hi = -1;
        TornadoPaths.Track newest = null;
        for (Area a : areas) {
            if (!shown(a, window) || !(a.extra instanceof TornadoPaths.Track))
                continue;
            final TornadoPaths.Track t = (TornadoPaths.Track) a.extra;
            n++;
            deaths += t.fatalities;
            injuries += t.injuries;
            if (t.efNum >= 0) {
                lo = Math.min(lo, t.efNum);
                hi = Math.max(hi, t.efNum);
            }
            if (newest == null || t.stormDate > newest.stormDate)
                newest = t;
        }
        final StringBuilder b = new StringBuilder("Last ").append(dayLabel(window)).append(": ");
        if (n == 0)
            b.append("no surveyed paths.");
        else {
            b.append(n).append(n == 1 ? " surveyed path" : " surveyed paths");
            if (hi >= 0)
                b.append(lo == hi ? " (EF" + hi + ")" : " (EF" + lo + " to EF" + hi + ")");
            b.append(", ").append(deaths).append(deaths == 1 ? " death, " : " deaths, ")
                    .append(injuries).append(injuries == 1 ? " injury." : " injuries.");
            if (newest != null)
                b.append(" Newest: ").append(TornadoPaths.rating(newest.rating))
                        .append(newest.event.isEmpty() ? "" : " near " + newest.event)
                        .append(", ").append(StormReportsOverlay.when(newest.stormDate)).append('.');
        }
        b.append("\nA path appears a day or more after the storm, once a survey team walks it.");
        return b.toString();
    }

    @Override
    protected String noun() {
        return "tornado paths";
    }

    private static String minutes(long ms) {
        final long m = Math.round(ms / 60000d);
        return m < 1 ? "under a minute" : m == 1 ? "1 minute" : m + " minutes";
    }
}
