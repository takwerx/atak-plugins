package com.atakmap.android.airaware;

import com.atakmap.map.layer.feature.AttributeSet;
import com.atakmap.map.layer.feature.Feature;
import com.atakmap.map.layer.feature.geometry.LineString;
import com.atakmap.map.layer.feature.geometry.Polygon;
import com.atakmap.map.layer.feature.style.BasicFillStyle;
import com.atakmap.map.layer.feature.style.BasicStrokeStyle;
import com.atakmap.map.layer.feature.style.CompositeStyle;
import com.atakmap.map.layer.feature.style.Style;

import java.util.ArrayList;
import java.util.List;

/**
 * The UAS Facility Map on the map: a grid square per cell, coloured by how high you may
 * fly there without an authorization.
 *
 * <p>Green to red, because the question is permission and not altitude: 400 ft is the
 * most the FAA grants here and reads as clear, 0 ft means nothing may be flown without
 * further coordination and reads as stop. The steps between are the FAA's own, in
 * fifties.
 *
 * <p>Flat on the ground, never extruded. The cell is not a volume you fly under or over
 * -- it is a rule about the column of air above that square -- and standing it up would
 * say something the data does not.
 */
public final class UasfmFeatures {

    public static final String SET_KEY = "uasfm";
    public static final String SET_NAME = "UAS ceilings";

    /** Enough to read the colour through, little enough to see the map under it. */
    private static final int FILL_ALPHA = 0x4D;

    private UasfmFeatures() {
    }

    /**
     * The colour for a ceiling.
     *
     * <p>0 is the one that matters most and is the only one drawn at full strength: a
     * pilot scanning for somewhere to launch needs the squares they cannot use to be the
     * ones that stand out.
     */
    public static int color(int ceilingFt) {
        if (ceilingFt <= 0)
            return 0xFFD32F2F;
        if (ceilingFt <= 100)
            return 0xFFF4511E;
        if (ceilingFt <= 200)
            return 0xFFFFB300;
        if (ceilingFt <= 300)
            return 0xFFC0CA33;
        return 0xFF43A047;
    }

    public static List<TfrOverlay.Drawn> drawn(UasfmCell c) {
        final List<TfrOverlay.Drawn> out = new ArrayList<>();
        final int color = color(c.ceilingFt);

        final LineString ring = new LineString(2);
        ring.addPoint(c.west(), c.south());
        ring.addPoint(c.east(), c.south());
        ring.addPoint(c.east(), c.north());
        ring.addPoint(c.west(), c.north());
        ring.addPoint(c.west(), c.south());

        final Style style = new CompositeStyle(new Style[] {
                new BasicFillStyle((FILL_ALPHA << 24) | (color & 0x00FFFFFF)),
                // A hairline of the same colour: without it the grid reads as one blob
                // where neighbours share a ceiling, and the cell size is the thing that
                // tells a pilot how precise the rule is.
                new BasicStrokeStyle((0x66 << 24) | (color & 0x00FFFFFF), 1f) });

        final AttributeSet a = new AttributeSet();
        final String id = c.latIndex + "_" + c.lonIndex;
        a.setAttribute("uasfm_id", id);
        a.setAttribute("ceiling_ft", c.ceilingFt);
        a.setAttribute("airport", c.where());
        a.setAttribute("laanc", c.laanc ? "yes" : "no");

        out.add(new TfrOverlay.Drawn(id, SET_KEY, SET_NAME, c.label(),
                new Polygon(ring), style, a, Feature.AltitudeMode.ClampToGround, 0d));
        return out;
    }
}
