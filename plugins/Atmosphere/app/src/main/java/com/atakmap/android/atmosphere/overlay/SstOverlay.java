package com.atakmap.android.atmosphere.overlay;

import com.atakmap.android.atmosphere.net.EgressPolicy;
import com.atakmap.android.maps.MapView;

import java.util.Locale;

/**
 * Sea surface temperature from NOAA's nowCOAST: the global blended analysis
 * as a WMS picture, in nowCOAST's own color scale. The legend is nowCOAST's
 * legend graphic, fetched once, so the map and the scale cannot disagree.
 */
public final class SstOverlay extends ImageOverlay {

    public static final String LAYER_ID = "sst";
    public static final String HOST = "nowcoast.noaa.gov";
    private static final String WMS = "https://" + HOST + "/geoserver/ows?service=WMS&version=1.3.0";
    private static final String LAYER = "sea_surface_temperature:global_sea_surface_temperature";
    public static final String LEGEND_URL = WMS + "&request=GetLegendGraphic&layer=" + LAYER
            + "&format=image/png";

    public SstOverlay(MapView mapView, EgressPolicy egress) {
        super(mapView, egress, "AtmosphereSst", LAYER_ID, "Sea surface temperature");
    }

    @Override
    protected String url(double west, double south, double east, double north, int px, int py) {
        return WMS + "&request=GetMap&layers=" + LAYER + "&styles=&crs=CRS:84"
                + String.format(Locale.US, "&bbox=%.4f,%.4f,%.4f,%.4f", west, south, east, north)
                + "&width=" + px + "&height=" + py + "&format=image/png&transparent=true";
    }

    @Override
    protected int alpha() {
        return 170;
    }

    @Override
    protected String noun() {
        return "sea surface temperature";
    }

    @Override
    protected String shown() {
        return "Sea surface temperature on the map: NOAA's blended analysis";
    }
}
