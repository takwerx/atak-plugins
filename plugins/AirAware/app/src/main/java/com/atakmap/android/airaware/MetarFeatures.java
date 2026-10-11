package com.atakmap.android.airaware;

import com.atakmap.map.layer.feature.AttributeSet;
import com.atakmap.map.layer.feature.Feature;
import com.atakmap.map.layer.feature.geometry.Point;
import com.atakmap.map.layer.feature.style.LabelPointStyle;

import java.util.ArrayList;
import java.util.List;

/**
 * Airfield observations on the map: one labeled point per station, colored by its flight
 * category.
 *
 * <p>The colors are the aviation convention, the same four every chart and briefing tool
 * uses -- green VFR, blue MVFR, red IFR, magenta LIFR -- so a pilot or a UAS operator
 * reads them without a legend. Where the source publishes no category the chip is grey
 * and says so; a station reporting no cloud at all is not the same as a clear one.
 */
public final class MetarFeatures {

    /** The set these live in, and the name Overlay Manager shows. */
    public static final String SET_KEY = "airfields";
    public static final String SET_NAME = "Airfield conditions";

    /** Published flight category colors. */
    public static final int VFR = 0xFF3DDC61;
    public static final int MVFR = 0xFF2196F3;
    public static final int IFR = 0xFFE01B24;
    public static final int LIFR = 0xFFD500F9;
    public static final int UNKNOWN = 0xFF9E9E9E;

    /**
     * The coarsest map resolution the chips draw at. Stations are dense -- seventy across
     * California and Nevada -- so they are a close-in thing, unlike a restriction which
     * matters from a long way out.
     */
    private static final double MAX_RES = 400d;

    private MetarFeatures() {
    }

    public static int color(Metar.Cat c) {
        switch (c) {
            case VFR:
                return VFR;
            case MVFR:
                return MVFR;
            case IFR:
                return IFR;
            case LIFR:
                return LIFR;
            default:
                return UNKNOWN;
        }
    }

    public static List<TfrOverlay.Drawn> drawn(List<Metar> stations) {
        final List<TfrOverlay.Drawn> out = new ArrayList<>();
        for (Metar m : stations) {
            final int c = color(m.category);
            final AttributeSet a = new AttributeSet();
            a.setAttribute("metar_icao", m.icao);
            a.setAttribute("station", m.name);
            a.setAttribute("category", m.categoryLabel());
            a.setAttribute("ceiling", m.ceilingLabel());
            a.setAttribute("visibility", m.visibility);
            a.setAttribute("wind", m.windLabel());
            a.setAttribute("raw", m.raw);
            // The category's own color behind the text: the colored chip every aviation
            // tool shows, without needing an icon per category.
            final TfrOverlay.Drawn d = new TfrOverlay.Drawn(m.icao, SET_KEY, SET_NAME,
                    m.icao + "  " + m.categoryLabel(), new Point(m.lon, m.lat),
                    new LabelPointStyle(m.icao + "  " + m.categoryLabel(), 0xFF000000,
                            c, LabelPointStyle.ScrollMode.OFF, 0f, 0, 0, 0f, false, MAX_RES),
                    a, Feature.AltitudeMode.ClampToGround, 0d);
            out.add(d);
        }
        return out;
    }
}
