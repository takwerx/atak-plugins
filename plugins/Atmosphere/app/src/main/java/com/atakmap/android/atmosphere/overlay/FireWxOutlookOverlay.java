package com.atakmap.android.atmosphere.overlay;

import android.content.Context;

import com.atakmap.android.atmosphere.data.SpcFireWx;
import com.atakmap.android.atmosphere.net.EgressPolicy;
import com.atakmap.android.maps.MapView;

import java.util.ArrayList;
import java.util.List;
import java.util.TimeZone;

/**
 * SPC's fire weather outlook on the map, days 1 to 3: the Elevated / Critical /
 * Extreme areas and the dry thunderstorm areas, in SPC's colors, with the day,
 * category and valid window behind a tap. Six small requests every half hour
 * while on; most days most of them answer "no areas".
 */
public final class FireWxOutlookOverlay extends OutlookOverlay {

    public static final String LAYER_ID = "firewx";
    public static final String HOST = SpcFireWx.HOST;

    /** SPC's categories, in its order, from the same table the map draws with. */
    public static final String[][] LEGEND;
    static {
        LEGEND = new String[SpcFireWx.LEGEND_CODES.length][];
        for (int i = 0; i < SpcFireWx.LEGEND_CODES.length; i++) {
            final SpcFireWx.Kind k = SpcFireWx.LEGEND_CODES[i][0] == 0
                    ? SpcFireWx.Kind.OUTLOOK : SpcFireWx.Kind.DRY_THUNDER;
            final int dn = SpcFireWx.LEGEND_CODES[i][1];
            LEGEND[i] = new String[] { SpcFireWx.label(k, dn), String.valueOf(SpcFireWx.color(k, dn)) };
        }
    }

    private final String[] urls;

    public FireWxOutlookOverlay(MapView mapView, Context pluginContext, EgressPolicy egress) {
        super(mapView, pluginContext, egress, "AtmosphereFireWx", LAYER_ID, "Fire Weather Outlook");
        urls = new String[SpcFireWx.LAYERS.length];
        for (int i = 0; i < urls.length; i++)
            urls[i] = SpcFireWx.LAYERS[i].url();
    }

    @Override
    protected String[] urls() {
        return urls;
    }

    @Override
    protected List<Area> parse(int index, String body) {
        final List<Area> out = new ArrayList<>();
        final TimeZone zone = TimeZone.getDefault();
        for (SpcFireWx.Area a : SpcFireWx.parse(body, SpcFireWx.LAYERS[index])) {
            final StringBuilder d = new StringBuilder(a.title());
            if (!a.valid.isEmpty())
                d.append("\nValid: ").append(SpcFireWx.when(a.valid, zone))
                        .append(a.expire.isEmpty() ? "" : " to " + SpcFireWx.when(a.expire, zone));
            d.append("\nFrom: NOAA Storm Prediction Center");
            out.add(new Area(a.day, a.title(), a.label(), a.color(), a.geometry, d.toString()));
        }
        return out;
    }

    @Override
    protected String summary(List<Area> areas) {
        return byDay(areas, 3);
    }

    @Override
    protected String noun() {
        return "fire weather outlook";
    }
}
