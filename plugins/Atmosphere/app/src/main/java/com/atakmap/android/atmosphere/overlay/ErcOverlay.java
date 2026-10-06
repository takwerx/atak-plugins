package com.atakmap.android.atmosphere.overlay;

import android.content.Context;

import com.atakmap.android.atmosphere.data.Erc;
import com.atakmap.android.atmosphere.net.EgressPolicy;
import com.atakmap.android.maps.MapView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Fire danger on the map: every Predictive Service Area in the lower 48 shaded by its
 * Energy Release Component percentile, in the Forest Service's own classes, yesterday
 * observed or today forecast. A tap opens the area's numbers and its GACC's own chart.
 *
 * <p>ERC is published per PSA, not per fire weather zone: a PSA is a group of key
 * RAWS a GACC draws, and covers many zones (operator, 2026-10-05: "can we make the
 * ERCs part of fire weather zones?"). One request for the whole country, about
 * 120 KB compressed, rebuilt by the source once a day; asked again every three hours.
 */
public final class ErcOverlay extends OutlookOverlay {

    public static final String LAYER_ID = "erc";
    public static final String HOST = Erc.HOST;
    /** The GACCs' own charts, which the details show. */
    public static final String CHART_HOST = "gacc.nifc.gov";
    private static final String TAG = "AtmosphereErc";
    private static final long POLL_MS = 3L * 60 * 60 * 1000;
    /** A PSA with no station reporting: an outline, and a fill too faint to see so it still answers a tap. */
    private static final int NO_READING_EDGE = 0xFF9E9E9E;

    /** The key: the agency's classes, then what an empty outline means. */
    public static final String[][] LEGEND;
    static {
        LEGEND = new String[Erc.CLASS_NAMES.length + 1][];
        for (int i = 0; i < Erc.CLASS_NAMES.length; i++)
            LEGEND[i] = new String[] { Erc.CLASS_NAMES[i], String.valueOf(Erc.COLORS[i]) };
        LEGEND[Erc.CLASS_NAMES.length] = new String[] {
                "Gray outline: no station reporting", String.valueOf(NO_READING_EDGE) };
    }

    private volatile List<Erc.Psa> psas = Collections.emptyList();

    public ErcOverlay(MapView mapView, Context pluginContext, EgressPolicy egress) {
        super(mapView, pluginContext, egress, TAG, LAYER_ID, "Fire Danger");
    }

    /** The PSA with this code from the last answer, or null. */
    public Erc.Psa find(String code) {
        for (Erc.Psa p : psas)
            if (p.code.equals(code))
                return p;
        return null;
    }

    @Override
    protected String[] urls() {
        return new String[] { Erc.allUrl() };
    }

    @Override
    protected long pollMs() {
        return POLL_MS;
    }

    @Override
    public int days() {
        return 2;
    }

    @Override
    public boolean allDays() {
        return false;
    }

    /** "Observed Oct 4" and "Forecast Oct 5", once the dates are known. */
    @Override
    public String dayLabel(int day) {
        final String updated = psas.isEmpty() ? "" : psas.get(0).updated;
        final String date = day == 1 ? Erc.observedDay(updated) : Erc.forecastDay(updated);
        return (day == 1 ? "Observed" : "Forecast") + (date.isEmpty() ? "" : " " + date);
    }

    @Override
    protected String setName(Area a) {
        return a.day == 1 ? "Observed" : "Forecast";
    }

    /** A quarter, the agency's own web map: the ground still reads under it. */
    @Override
    protected int fillAlpha(Area a) {
        return a.label.isEmpty() ? 0x01 : 0x40;
    }

    @Override
    protected int strokeColor(Area a) {
        return a.label.isEmpty() ? NO_READING_EDGE : 0xFF5A5A5A;
    }

    /** Each area's label is a tile in its class color, dark text on the light classes. */
    @Override
    protected int[] labelColors(Area a) {
        if (a.label.isEmpty())
            return null;
        final int c = a.color | 0xFF000000;
        final boolean dark = c == Erc.COLORS[0] || c == Erc.COLORS[4] || c == Erc.COLORS[5];
        return new int[] { dark ? 0xFFFFFFFF : 0xFF000000, c };
    }

    /** The areas tile the country: only a tap on an area's label opens it. */
    @Override
    protected boolean tapsAtLabelOnly() {
        return true;
    }

    /** A PSA is a hundred miles across; its label draws from a regional view in. */
    @Override
    protected double labelMaxResolution() {
        return 1000d;
    }

    @Override
    protected List<Area> parse(int index, String body) {
        final List<Erc.Psa> got = Erc.parseAll(body);
        final List<Area> out = new ArrayList<>();
        if (got.isEmpty())
            return out;
        psas = got;
        for (Erc.Psa p : got) {
            if (p.geometry == null)
                continue;
            final String details = details(p);
            out.add(area(1, p, p.ercObserved, details));
            out.add(area(2, p, p.ercForecast, details));
        }
        return out;
    }

    private static Area area(int day, Erc.Psa p, Erc.Reading r, String details) {
        final boolean known = r.known();
        final String title = p.name + ": " + (known
                ? "ERC " + Math.round(r.value) + " (" + Erc.ordinal(r.percentile) + ")"
                : "no reading");
        return new Area(day, title, known ? Erc.CLASS_NAMES[Erc.classOf(r.percentile)] : "",
                known ? Erc.color(r.percentile) : NO_READING_EDGE, p.geometry, details,
                p.code);
    }

    /** The record's text: both days, both indices, and what the percentile is of. */
    public static String details(Erc.Psa p) {
        final StringBuilder b = new StringBuilder();
        b.append(p.name).append(" (").append(p.code).append(')');
        if (!p.gaccName.isEmpty())
            b.append('\n').append(p.gaccName);
        b.append("\n\nEnergy Release Component\n");
        b.append(line("Observed " + Erc.observedDay(p.updated), p.ercObserved));
        b.append('\n').append(line("Forecast " + Erc.forecastDay(p.updated), p.ercForecast));
        b.append("\n\nBurning Index\n");
        b.append(line("Observed " + Erc.observedDay(p.updated), p.biObserved));
        b.append('\n').append(line("Forecast " + Erc.forecastDay(p.updated), p.biForecast));
        b.append("\n\nPercentiles are of every day of the year, 2005 to 2022, fuel model Y. ")
                .append("The area's value is the average of its key fire weather stations.");
        b.append("\nFrom: USDA Forest Service, from FEMS, updated ")
                .append(Erc.forecastDay(p.updated)).append(" at 3 AM Mountain.");
        return b.toString();
    }

    /** "Observed Oct 4: 48 (92nd percentile), rising" or "... no reading". */
    public static String line(String when, Erc.Reading r) {
        if (!r.known())
            return when + ": no reading";
        final String trend = r.trendWord();
        return when + ": " + Math.round(r.value) + " (" + Erc.ordinal(r.percentile)
                + " percentile)" + (trend.isEmpty() ? "" : ", " + trend);
    }

    /** "Observed Oct 4: 18 areas at the 90th percentile or higher, 3 at the 97th." */
    @Override
    protected String summary(List<Area> areas) {
        final List<Erc.Psa> all = psas;
        if (all.isEmpty())
            return "The fire danger service came back empty.";
        final int shown = day();
        int high = 0, extreme = 0, none = 0;
        for (Erc.Psa p : all) {
            final Erc.Reading r = shown == 2 ? p.ercForecast : p.ercObserved;
            if (!r.known()) {
                none++;
                continue;
            }
            if (r.percentile >= 90)
                high++;
            if (r.percentile >= 97)
                extreme++;
        }
        return dayLabel(shown == 2 ? 2 : 1) + ": " + high + " of " + all.size()
                + " areas at the 90th percentile or higher, " + extreme + " at the 97th."
                + (none == 0 ? "" : " " + none + " with no station reporting.")
                + " Alaska is not rated this way.";
    }

    @Override
    protected String noun() {
        return "fire danger";
    }
}
