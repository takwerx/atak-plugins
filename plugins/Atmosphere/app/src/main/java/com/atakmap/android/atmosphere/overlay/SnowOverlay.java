package com.atakmap.android.atmosphere.overlay;

import com.atakmap.android.atmosphere.net.EgressPolicy;
import com.atakmap.android.maps.MapView;

import java.util.Locale;

/**
 * NOHRSC's daily snow analysis -- snow depth over the lower 48 -- as a picture
 * from NWS's map service, in the service's own classes and colors (read from
 * its legend, 2026-09-26). The depth is what a person on the ground can check
 * against; the water equivalent is the hydrologist's number and is not drawn.
 */
public final class SnowOverlay extends ImageOverlay {

    public static final String LAYER_ID = "snow";
    public static final String HOST = "mapservices.weather.noaa.gov";
    private static final String EXPORT = "https://" + HOST
            + "/raster/rest/services/snow/NOHRSC_Snow_Analysis/MapServer/export";

    /** The service's snow depth classes, inches, with its colors. */
    public static final String[][] LEGEND = {
            { "Under 2 in", "#FFABC1BF" }, { "2 to 4 in", "#FF66C1C4" }, { "4 to 10 in", "#FF63A9CB" },
            { "10 to 20 in", "#FF5079C8" }, { "20 to 39 in", "#FF3C3FC2" }, { "39 to 59 in", "#FF5720C3" },
            { "5 to 8 ft", "#FF7D01BB" }, { "8 to 16 ft", "#FFB404B1" }, { "16 to 25 ft", "#FFA91377" },
            { "25 to 33 ft", "#FF992B50" }, { "Over 33 ft", "#FF8B4545" } };

    public SnowOverlay(MapView mapView, EgressPolicy egress) {
        super(mapView, egress, "AtmosphereSnow", LAYER_ID, "Snow Depth");
    }

    @Override
    protected String url(double west, double south, double east, double north, int px, int py) {
        return EXPORT + String.format(Locale.US, "?bbox=%.4f,%.4f,%.4f,%.4f", west, south, east, north)
                + "&bboxSR=4326&imageSR=4326&size=" + px + "," + py
                + "&layers=show:3&format=png32&transparent=true&f=image";
    }

    @Override
    protected int alpha() {
        return 200;
    }

    @Override
    protected String noun() {
        return "snow analysis";
    }

    @Override
    protected String shown() {
        return "Snow depth on the map: NOHRSC's daily analysis, lower 48 only";
    }
}
