package com.atakmap.android.atmosphere.overlay;

import android.content.Context;

import com.atakmap.android.atmosphere.data.WpcEro;
import com.atakmap.android.atmosphere.net.EgressPolicy;
import com.atakmap.android.maps.MapView;

import java.util.ArrayList;
import java.util.List;

/**
 * WPC's excessive rainfall outlook on the map, days 1 to 3: where rain is
 * expected to exceed flash flood guidance, as Marginal / Slight / Moderate /
 * High areas in WPC's colors. The flash flood risk before the warning.
 */
public final class FloodOutlookOverlay extends OutlookOverlay {

    public static final String LAYER_ID = "flood";
    public static final String HOST = WpcEro.HOST;

    public static final String[][] LEGEND = {
            { "Marginal (" + WpcEro.meaning(1) + ")", String.valueOf(WpcEro.color(1)) },
            { "Slight (" + WpcEro.meaning(2) + ")", String.valueOf(WpcEro.color(2)) },
            { "Moderate (" + WpcEro.meaning(3) + ")", String.valueOf(WpcEro.color(3)) },
            { "High (" + WpcEro.meaning(4) + ")", String.valueOf(WpcEro.color(4)) } };

    private final String[] urls;

    public FloodOutlookOverlay(MapView mapView, Context pluginContext, EgressPolicy egress) {
        super(mapView, pluginContext, egress, "AtmosphereFlood", LAYER_ID, "Flash flood outlook");
        urls = new String[WpcEro.DAYS];
        for (int d = 1; d <= WpcEro.DAYS; d++)
            urls[d - 1] = WpcEro.url(d);
    }

    @Override
    protected String[] urls() {
        return urls;
    }

    @Override
    protected List<Area> parse(int index, String body) {
        final List<Area> out = new ArrayList<>();
        for (WpcEro.Area a : WpcEro.parse(body, index + 1)) {
            final StringBuilder d = new StringBuilder(a.title());
            if (!a.outlook.isEmpty())
                d.append("\nChance of rain beyond flash flood guidance within 25 mi: ").append(a.outlook);
            if (!a.validTime.isEmpty())
                d.append("\nValid: ").append(a.validTime);
            if (!a.issued.isEmpty())
                d.append("\nIssued: ").append(a.issued).append(" UTC");
            d.append("\nFrom: NOAA Weather Prediction Center");
            out.add(new Area(a.day, a.title(), a.label(), a.color(), a.geometry, d.toString()));
        }
        return out;
    }

    @Override
    protected String summary(List<Area> areas) {
        return byDay(areas, WpcEro.DAYS);
    }

    @Override
    protected String noun() {
        return "excessive rainfall outlook";
    }
}
