package com.atakmap.android.atmosphere.overlay;

import android.content.Context;

import com.atakmap.android.atmosphere.compat.GeneratedFiles;
import com.atakmap.android.atmosphere.data.FuelMoisture;
import com.atakmap.android.atmosphere.data.States;
import com.atakmap.android.atmosphere.net.EgressPolicy;
import com.atakmap.android.maps.MapView;
import com.atakmap.coremap.log.Log;
import com.atakmap.coremap.maps.assets.Icon;
import com.atakmap.coremap.maps.coords.GeoPoint;
import com.atakmap.map.layer.feature.AttributeSet;
import com.atakmap.map.layer.feature.geometry.Geometry;
import com.atakmap.map.AtakMapView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Fuel moisture samples on the map: every sampling site in the state the map is
 * centered on, a dot colored by its newest live fuel's moisture against the chaparral
 * thresholds, the fuel and its percent in the pill, every fuel the site samples behind
 * a tap. The day row picks how old a sample may be (30 days, 90 days, a year); the
 * second row picks live fuels, dead fuels or either.
 *
 * <p>One request per state, the state found on the phone from the map center with the
 * outlines the plugin ships; asked again when the map settles in another state. Nothing
 * about the phone leaves. Asked for by the operator on 2026-10-11: "i want to bring in
 * fuel sample shit ... the usfs has live fuel moisture maps or readings".
 */
public final class FuelMoistureOverlay extends OutlookOverlay {

    public static final String LAYER_ID = "fuel";
    public static final String HOST = FuelMoisture.HOST;
    private static final String TAG = "AtmosphereFuel";
    private static final long SETTLE_MS = 1200;
    private static final long DAY_MS = 24L * 60 * 60 * 1000;
    private static final long[] WINDOW_MS = { 30 * DAY_MS, 90 * DAY_MS, 365 * DAY_MS };
    private static final String[] WINDOWS = { "30 days", "90 days", "1 year" };
    private static final String[] KINDS = { "Live fuels", "Dead fuels", "Either" };

    /** The key. */
    public static final String[][] LEGEND = {
            { "Critical", String.valueOf(FuelMoisture.COLORS[0]) },
            { "High", String.valueOf(FuelMoisture.COLORS[1]) },
            { "Moderate", String.valueOf(FuelMoisture.COLORS[2]) },
            { "Low", String.valueOf(FuelMoisture.COLORS[3]) },
            { "Live fuels: critical at 60% and under, high to 79, moderate to 99, low from 100"
                    + " (the chaparral thresholds)", "0" },
            { "Dead 1 and 10 hour: 6 / 9 / 15. 100 hour: 10 / 13 / 18. 1000 hour: 13 / 16 / 22"
                    + " (Texas A&M and Oklahoma Mesonet figures; no national table)", "0" },
            { "Duff: no published scale", String.valueOf(FuelMoisture.DEAD_COLOR) },
            { "Moisture is water as a percent of the fuel's dry weight; the pill names the fuel",
                    "0" } };

    private final Context pluginContext;
    private final DotIcons icons;
    private States states;
    private volatile String state = "";
    private volatile String asked = "";
    private volatile int sites;

    private final Runnable settled = new Runnable() {
        @Override
        public void run() {
            if (!isOn())
                return;
            final String s = stateAtCenter();
            if (!s.isEmpty() && !s.equals(asked))
                refresh(true);
        }
    };

    private final AtakMapView.OnMapMovedListener moved = new AtakMapView.OnMapMovedListener() {
        @Override
        public void onMapMoved(AtakMapView v, boolean animate) {
            // The GL thread; nothing of the map or the pane is touched here.
            mapView.removeCallbacks(settled);
            mapView.postDelayed(settled, SETTLE_MS);
        }
    };

    public FuelMoistureOverlay(MapView mapView, Context pluginContext, EgressPolicy egress) {
        super(mapView, pluginContext, egress, TAG, LAYER_ID, "Fuel Moisture");
        this.pluginContext = pluginContext;
        icons = new DotIcons(GeneratedFiles.icons("dots"));
    }

    @Override
    public void start() {
        super.start();
        mapView.addOnMapMovedListener(moved);
    }

    @Override
    public void stop() {
        mapView.removeOnMapMovedListener(moved);
        mapView.removeCallbacks(settled);
        super.stop();
    }

    @Override
    public void setOn(boolean value) {
        super.setOn(value);
        if (!value)
            asked = "";
    }

    private States loadStates() {
        try (java.io.InputStream in = pluginContext.getAssets().open("us_states.json")) {
            final java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            final byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0)
                out.write(buf, 0, n);
            return States.parse(out.toString("UTF-8"));
        } catch (Exception e) {
            Log.w(TAG, "state outlines unreadable", e);
            return null;
        }
    }

    /** The state under the map center, or "" at sea, abroad, or before the outlines load. */
    private String stateAtCenter() {
        if (states == null)
            states = loadStates();
        if (states == null)
            return "";
        final GeoPoint c = mapView.getCenterPoint().get();
        if (c == null || !c.isValid())
            return "";
        final String s = states.at(c.getLatitude(), c.getLongitude());
        return s == null ? "" : s;
    }

    @Override
    protected void onRefreshStart() {
        final String s = stateAtCenter();
        if (!s.isEmpty())
            state = s;
        asked = state;
    }

    @Override
    protected String[] urls() {
        final String s = state;
        return s.isEmpty() ? new String[0] : new String[] { FuelMoisture.url(s) };
    }

    @Override
    protected boolean large(int index) {
        return true;
    }

    @Override
    protected long pollMs() {
        return 60 * 60 * 1000L;
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
        return WINDOWS[Math.max(1, Math.min(3, day)) - 1];
    }

    @Override
    public String[] choices() {
        return KINDS;
    }

    @Override
    public String choiceHeading() {
        return "Show";
    }

    @Override
    protected int defaultChoice() {
        return 0;
    }

    /** The sample the dot stands for under the current rows, or null when the site has none. */
    private FuelMoisture.Sample pick(Area a) {
        if (!(a.extra instanceof FuelMoisture.Site))
            return null;
        final FuelMoisture.Sample s = ((FuelMoisture.Site) a.extra).newest(choice());
        if (s == null)
            return null;
        final long maxAge = WINDOW_MS[Math.max(1, Math.min(3, day())) - 1];
        return System.currentTimeMillis() - s.sampledAt > maxAge ? null : s;
    }

    @Override
    protected boolean shown(Area a, int showDay) {
        return pick(a) != null;
    }

    @Override
    protected String setName(Area a) {
        return "Fuel Moisture";
    }

    @Override
    protected double labelMaxResolution() {
        return 400d;
    }

    @Override
    protected List<Area> parse(int index, String body) {
        final List<Area> out = new ArrayList<>();
        final List<FuelMoisture.Site> got = FuelMoisture.parse(body);
        sites = got.size();
        for (FuelMoisture.Site site : got) {
            final StringBuilder d = new StringBuilder();
            d.append("Fuel moisture samples at ").append(site.name);
            d.append("\nWater as a percent of the fuel's dry weight; live fuels run past 100.");
            for (FuelMoisture.Sample s : site.samples) {
                d.append('\n').append(s.fuel).append(": ")
                        .append(String.format(Locale.US, "%.0f%%", s.pct));
                final int level = FuelMoisture.level(s.fuel, s.pct);
                if (level >= 0)
                    d.append(" (").append(FuelMoisture.LEVELS[level].toLowerCase(Locale.US))
                            .append(')');
                d.append(", ").append(StormReportsOverlay.when(s.sampledAt)).append(" (")
                        .append(StormReportsOverlay.ago(s.sampledAt)).append(')');
                if (s.firstYear > 0)
                    d.append(", sampled since ").append(s.firstYear);
            }
            d.append("\nFrom: National Fuel Moisture Database (US Forest Service), through"
                    + " San Jose State University's fuel moisture portal");
            final JSONObject g = new JSONObject();
            try {
                g.put("type", "Point");
                g.put("coordinates", new JSONArray().put(site.lon).put(site.lat));
            } catch (Exception e) {
                continue;
            }
            out.add(new Area(1, site.name, "Fuel moisture", FuelMoisture.DEAD_COLOR, g,
                    d.toString(), "", site));
        }
        return out;
    }

    @Override
    protected AtmosphereFeatures.Drawn drawn(Area a, Geometry g, AttributeSet s) {
        final FuelMoisture.Sample pick = pick(a);
        if (pick == null)
            return null;
        final int color = FuelMoisture.color(pick.fuel, pick.pct);
        final String pill = FuelMoisture.shortFuel(pick.fuel)
                + String.format(Locale.US, " %.0f%%", pick.pct);
        final Icon icon = icons.labelled(pill, color, 0xFF202020, 14);
        if (icon == null)
            return null;
        return new AtmosphereFeatures.Drawn(setName(a), a.title, g,
                AtmosphereFeatures.icon(icon.getImageUri(Icon.STATE_DEFAULT), icon.getWidth(),
                        icon.getHeight()), s);
    }

    @Override
    protected String summary(List<Area> areas) {
        int n = 0;
        FuelMoisture.Sample driest = null;
        String driestAt = "";
        for (Area a : areas) {
            final FuelMoisture.Sample s = pick(a);
            if (s == null)
                continue;
            n++;
            if (driest == null || FuelMoisture.level(s.fuel, s.pct) >= 0
                    && (FuelMoisture.level(driest.fuel, driest.pct) < 0
                            || FuelMoisture.level(s.fuel, s.pct) < FuelMoisture.level(driest.fuel, driest.pct)
                            || FuelMoisture.level(s.fuel, s.pct) == FuelMoisture.level(driest.fuel, driest.pct)
                                    && s.pct < driest.pct)) {
                driest = s;
                driestAt = a.title;
            }
        }
        final StringBuilder b = new StringBuilder();
        final String s = asked.isEmpty() ? state : asked;
        if (s.isEmpty())
            b.append("Center the map on a state to see its sampling sites.");
        else {
            final String kind = choice() == 0 ? "live fuel" : choice() == 1 ? "dead fuel" : "fuel";
            final String span = day() == 1 ? "30 days" : day() == 3 ? "year" : "90 days";
            b.append(s).append(": ").append(n).append(" of ").append(sites)
                    .append(sites == 1 ? " site has" : " sites have").append(" a ").append(kind)
                    .append(" sample from the last ").append(span).append('.');
            if (driest != null)
                b.append(" Driest: ").append(driest.fuel).append(' ')
                        .append(String.format(Locale.US, "%.0f%%", driest.pct)).append(" at ")
                        .append(driestAt).append(", ").append(StormReportsOverlay.ago(driest.sampledAt))
                        .append('.');
        }
        b.append("\nSamples are cut and weighed by crews every week or two; the state under the"
                + " map center is asked.");
        return b.toString();
    }

    @Override
    protected String noun() {
        return "fuel moisture sites";
    }
}
