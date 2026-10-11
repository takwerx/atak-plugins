package com.atakmap.android.atmosphere.overlay;

import android.content.Context;

import com.atakmap.android.atmosphere.compat.GeneratedFiles;
import com.atakmap.android.atmosphere.data.Quakes;
import com.atakmap.android.atmosphere.net.EgressPolicy;
import com.atakmap.android.maps.MapView;
import com.atakmap.coremap.maps.assets.Icon;
import com.atakmap.map.layer.feature.AttributeSet;
import com.atakmap.map.layer.feature.geometry.Geometry;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Earthquakes on the map, the way USGS draws them: a circle sized by magnitude and
 * colored by age, the magnitude in the pill from M2.5 up, and for the largest, the
 * ShakeMap's intensity contours with their Roman numerals. The day row picks the
 * feed (past day, week or month); a second row picks the smallest magnitude shown.
 *
 * <p>One national feed every five minutes, then for up to five of the largest quakes
 * of M4.5 or more that have a ShakeMap, the event's detail and its contours. Nothing
 * about the phone leaves. In Atmosphere rather than a plugin of its own (operator,
 * 2026-10-11: "another plugin for earthquakes ... does not seem like enough").
 */
public final class QuakesOverlay extends OutlookOverlay {

    public static final String LAYER_ID = "quakes";
    public static final String HOST = Quakes.HOST;
    private static final String TAG = "AtmosphereQuakes";
    private static final String SHAKING = "Shaking (ShakeMap)";
    private static final int MAX_SHAKEMAPS = 5;
    private static final double SHAKEMAP_MIN_MAG = 4.5;
    private static final double LABEL_MIN_MAG = 2.5;

    /** The key. */
    public static final String[][] LEGEND = {
            { "Past hour", String.valueOf(Quakes.AGE_COLORS[0]) },
            { "Past day", String.valueOf(Quakes.AGE_COLORS[1]) },
            { "Past week", String.valueOf(Quakes.AGE_COLORS[2]) },
            { "Older", String.valueOf(Quakes.AGE_COLORS[3]) },
            { "Circle size: magnitude. Lines: how hard the ground shook, from the ShakeMap", "0" } };

    private final DotIcons icons;
    private final List<String> chain = new CopyOnWriteArrayList<>();
    private volatile int window = 2;

    public QuakesOverlay(MapView mapView, Context pluginContext, EgressPolicy egress) {
        super(mapView, pluginContext, egress, TAG, LAYER_ID, "Earthquakes");
        icons = new DotIcons(GeneratedFiles.icons("dots"));
        window = day();
        chain.add(Quakes.url(window));
    }

    @Override
    protected void onRefreshStart() {
        window = day();
        chain.clear();
        chain.add(Quakes.url(window));
    }

    @Override
    protected String[] urls() {
        return chain.toArray(new String[0]);
    }

    @Override
    protected boolean optional(int index) {
        return index > 0;
    }

    @Override
    protected boolean large(int index) {
        return index == 0 && window != 1;
    }

    @Override
    protected long pollMs() {
        return 5 * 60 * 1000L;
    }

    @Override
    protected int defaultDay() {
        return 2;
    }

    @Override
    public boolean allDays() {
        return false;
    }

    @Override
    public String dayLabel(int day) {
        return Quakes.WINDOWS[Math.max(1, Math.min(3, day)) - 1];
    }

    @Override
    protected boolean refetchOnDay() {
        return true;
    }

    @Override
    public String[] choices() {
        return Quakes.FLOORS;
    }

    @Override
    public String choiceHeading() {
        return "Smallest shown";
    }

    @Override
    protected int defaultChoice() {
        return 2;
    }

    @Override
    protected boolean shown(Area a, int showDay) {
        if (a.extra instanceof Quakes.Quake)
            return ((Quakes.Quake) a.extra).mag >= Quakes.FLOOR_MAG[choice()];
        return true;
    }

    @Override
    protected String setName(Area a) {
        return a.label;
    }

    @Override
    protected List<Area> parse(int index, String body) {
        final List<Area> out = new ArrayList<>();
        final long now = System.currentTimeMillis();
        if (index == 0) {
            final List<Quakes.Quake> got = Quakes.parse(body);
            final List<Quakes.Quake> big = new ArrayList<>();
            for (Quakes.Quake q : got) {
                final int age = Quakes.age(q.time, now);
                final StringBuilder d = new StringBuilder();
                d.append(q.magnitude());
                if (!q.magType.isEmpty())
                    d.append(" (").append(q.magType).append(')');
                if (!q.place.isEmpty())
                    d.append("\nWhere: ").append(q.place);
                d.append("\nWhen: ").append(StormReportsOverlay.when(q.time)).append(" (")
                        .append(StormReportsOverlay.ago(q.time)).append(')');
                if (!Double.isNaN(q.depthKm))
                    d.append("\nDepth: ").append(String.format(Locale.US, "%.1f km (%.1f mi)",
                            q.depthKm, q.depthKm * 0.621371));
                if (q.felt > 0)
                    d.append("\nFelt: ").append(q.felt).append(q.felt == 1 ? " report" : " reports")
                            .append(Double.isNaN(q.cdi) ? ""
                                    : ", intensity " + Quakes.roman(q.cdi) + " ("
                                            + Quakes.shaking(q.cdi) + ")");
                if (!Double.isNaN(q.mmi))
                    d.append("\nShaking estimated: ").append(Quakes.roman(q.mmi)).append(" (")
                            .append(Quakes.shaking(q.mmi)).append(") at the strongest");
                if (!q.alert.isEmpty())
                    d.append("\nPAGER impact: ").append(q.alert);
                if (q.tsunami != 0)
                    d.append("\nTsunami: USGS flagged this one; see tsunami.gov");
                d.append("\nStatus: ").append("reviewed".equalsIgnoreCase(q.status)
                        ? "reviewed by a seismologist" : "automatic, not yet reviewed");
                if (!q.url.isEmpty())
                    d.append("\nUSGS: ").append(q.url);
                d.append("\nFrom: USGS Earthquake Hazards Program");
                final JSONObject g = new JSONObject();
                try {
                    g.put("type", "Point");
                    g.put("coordinates", new JSONArray().put(q.lon).put(q.lat));
                } catch (Exception e) {
                    continue;
                }
                out.add(new Area(1, q.magnitude() + (q.place.isEmpty() ? "" : " - " + q.place),
                        Quakes.AGES[age], Quakes.AGE_COLORS[age], g, d.toString(), "", q));
                if (q.shakeMap && q.mag >= SHAKEMAP_MIN_MAG && !q.detail.isEmpty()
                        && q.detail.startsWith("https://" + Quakes.HOST + "/"))
                    big.add(q);
            }
            Collections.sort(big, new Comparator<Quakes.Quake>() {
                @Override
                public int compare(Quakes.Quake a, Quakes.Quake b) {
                    return Double.compare(b.mag, a.mag);
                }
            });
            for (int i = 0; i < Math.min(MAX_SHAKEMAPS, big.size()); i++)
                chain.add(big.get(i).detail);
            return out;
        }
        if (body != null && body.contains("\"products\"")) {
            final String contours = Quakes.contoursUrl(body);
            if (contours != null)
                chain.add(contours);
            return out;
        }
        for (Quakes.Contour c : Quakes.parseContours(body)) {
            final String roman = Quakes.roman(c.mmi);
            out.add(new Area(1, roman, SHAKING, Quakes.mmiColor(c.mmi), c.geometry,
                    "Shaking intensity " + roman + ": " + Quakes.shaking(c.mmi)
                            + "\nFrom the USGS ShakeMap, modeled from the instruments and the"
                            + " reports, not a survey",
                    "", c));
        }
        return out;
    }

    @Override
    protected AtmosphereFeatures.Drawn drawn(Area a, Geometry g, AttributeSet s) {
        if (a.extra instanceof Quakes.Quake) {
            final Quakes.Quake q = (Quakes.Quake) a.extra;
            final int dp = (int) Math.max(8, Math.min(34, Math.round(6 + 3 * q.mag)));
            final Icon icon = icons.labelled(q.mag >= LABEL_MIN_MAG ? q.magnitude() : "", a.color,
                    0xFF202020, dp);
            if (icon == null)
                return null;
            return new AtmosphereFeatures.Drawn(setName(a), a.title, g,
                    AtmosphereFeatures.icon(icon.getImageUri(Icon.STATE_DEFAULT), icon.getWidth(),
                            icon.getHeight()), s);
        }
        if (a.extra instanceof Quakes.Contour)
            return new AtmosphereFeatures.Drawn(SHAKING, a.title, g,
                    AtmosphereFeatures.stroke(a.title, a.color, 2.5f, true), s);
        return null;
    }

    @Override
    protected String summary(List<Area> areas) {
        final double floor = Quakes.FLOOR_MAG[choice()];
        int n = 0, big = 0, maps = 0;
        Quakes.Quake largest = null;
        for (Area a : areas) {
            if (a.extra instanceof Quakes.Contour) {
                maps++;
                continue;
            }
            if (!(a.extra instanceof Quakes.Quake))
                continue;
            final Quakes.Quake q = (Quakes.Quake) a.extra;
            if (q.mag < floor)
                continue;
            n++;
            if (q.mag >= 4.5)
                big++;
            if (largest == null || q.mag > largest.mag)
                largest = q;
        }
        final StringBuilder b = new StringBuilder(dayLabel(window)).append(": ");
        if (n == 0)
            b.append("no quakes").append(floor > 0 ? " at " + Quakes.FLOORS[choice()] : "")
                    .append('.');
        else {
            b.append(n).append(n == 1 ? " quake" : " quakes");
            if (floor > 0)
                b.append(" at ").append(Quakes.FLOORS[choice()]);
            if (big > 0 && floor < 4.5)
                b.append(", ").append(big).append(" at M4.5+");
            b.append('.');
            if (largest != null)
                b.append(" Largest: ").append(largest.magnitude())
                        .append(largest.place.isEmpty() ? "" : ", " + largest.place).append(", ")
                        .append(StormReportsOverlay.ago(largest.time)).append('.');
            if (maps > 0)
                b.append(" Shaking drawn for the largest.");
        }
        if (window == 3)
            b.append("\nThe month feed carries M2.5 and up only.");
        b.append("\nFrom USGS, worldwide; times are this phone's.");
        return b.toString();
    }

    @Override
    protected String noun() {
        return "earthquakes";
    }
}
