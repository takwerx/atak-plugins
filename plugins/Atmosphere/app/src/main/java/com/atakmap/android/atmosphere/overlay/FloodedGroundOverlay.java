package com.atakmap.android.atmosphere.overlay;

import android.content.SharedPreferences;

import com.atakmap.android.atmosphere.compat.MapCompat;
import com.atakmap.android.atmosphere.net.EgressPolicy;
import com.atakmap.android.maps.MapView;

import java.util.Locale;

/**
 * Where the National Weather Service's river model puts water over the banks, as
 * a picture on the map: the National Water Prediction Service's modeled flood
 * inundation extent, either now (the model's analysis, updated hourly) or at the
 * peak of the next five days (its medium-range forecast, updated every six
 * hours). The service calls itself EXPERIMENTAL in its own description and says
 * it covers an area holding 30% of the population, lower 48 only, and that it
 * "does not fully capture coastal processes"; the status line and the pane's
 * note say so in plain words. Confirmed live 2026-09-26.
 *
 * <p>The service draws nothing coarser than 1:400,000. ArcGIS computes that scale
 * from the box, the pixel count and the {@code dpi} the picture is asked at, so
 * at the default 96 dpi a 1,024 px picture goes blank past about one degree of
 * longitude (measured: 0.3 deg has water, 1.0 deg is empty). Asking at 24 dpi
 * makes the same 1,024 px picture "coarser" by four, and it spans 3.6 degrees --
 * a county-wide look -- before the gate closes (measured: 3.0 deg at 24 dpi has
 * water, 2.0 deg at 48 does not). The widest request is kept under that and the
 * view is told to zoom in past it, so the picture always covers the view.
 */
public final class FloodedGroundOverlay extends ImageOverlay {

    public static final String LAYER_ID = "floodground";
    public static final String HOST = "maps.water.noaa.gov";
    private static final String SERVICES = "https://" + HOST + "/server/rest/services/nwm/";
    private static final String NOW = "ana_inundation_extent";
    private static final String DAY5 = "mrf_nbm_5day_max_inundation_extent";

    /**
     * Which run of the model both flood layers draw: the analysis (now) or the
     * peak of the next five days. One preference for the picture and the streams,
     * so the map never shows today's water beside next week's streams.
     */
    public static final String PREF_HORIZON = "weather.flood.horizon";
    public static final int HORIZON_NOW = 0, HORIZON_5DAY = 1;

    /** The service's own fill, rgb(0,110,200), drawn at {@link #alpha()}. */
    public static final int WATER = 0xFF006EC8;
    public static final String[][] LEGEND = { { "Modeled flooded ground", "#FF006EC8" } };

    private static final int DPI = 24;
    private static final double MAX_SPAN_LON = 3.6, MAX_SPAN_LAT = 2.7;

    private int horizon;

    public FloodedGroundOverlay(MapView mapView, EgressPolicy egress) {
        super(mapView, egress, "AtmosphereFloodGround", LAYER_ID, "Flooded Ground");
        horizon = horizonPref();
    }

    /** The shared horizon as last chosen, for a layer or a pane built before the other. */
    public static int horizonPref() {
        final SharedPreferences p = MapCompat.prefs();
        return p == null ? HORIZON_NOW : p.getInt(PREF_HORIZON, HORIZON_NOW);
    }

    public int horizon() {
        return horizon;
    }

    /** Now or the next five days; persisted, and the picture asked for again. */
    public void setHorizon(int value) {
        final int v = value == HORIZON_5DAY ? HORIZON_5DAY : HORIZON_NOW;
        final SharedPreferences p = MapCompat.prefs();
        if (p != null)
            p.edit().putInt(PREF_HORIZON, v).apply();
        if (v == horizon)
            return;
        horizon = v;
        reask();
    }

    @Override
    protected String url(double west, double south, double east, double north, int px, int py) {
        return SERVICES + (horizon == HORIZON_5DAY ? DAY5 : NOW) + "/MapServer/export"
                + String.format(Locale.US, "?bbox=%.4f,%.4f,%.4f,%.4f", west, south, east, north)
                + "&bboxSR=4326&imageSR=4326&size=" + px + "," + py + "&dpi=" + DPI
                + "&layers=show:0&format=png32&transparent=true&f=image";
    }

    @Override
    protected int alpha() {
        return 200;
    }

    @Override
    protected double maxSpanLon() {
        return MAX_SPAN_LON;
    }

    @Override
    protected double maxSpanLat() {
        return MAX_SPAN_LAT;
    }

    @Override
    protected double zoomInSpanLon() {
        return MAX_SPAN_LON;
    }

    @Override
    protected String noun() {
        return "flooded ground";
    }

    @Override
    protected String shown() {
        return horizon == HORIZON_5DAY
                ? "Flooded ground at the worst of the next 5 days: a model's estimate, experimental, lower 48 only"
                : "Flooded ground now: a model's estimate, experimental, lower 48 only";
    }
}
