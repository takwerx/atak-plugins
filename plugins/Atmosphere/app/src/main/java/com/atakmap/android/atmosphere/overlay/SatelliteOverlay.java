package com.atakmap.android.atmosphere.overlay;

import android.content.SharedPreferences;

import com.atakmap.android.atmosphere.compat.MapCompat;
import com.atakmap.android.atmosphere.net.EgressPolicy;
import com.atakmap.android.maps.MapView;

import java.util.Locale;

/**
 * The satellite picture from NOAA's nowCOAST: GOES East and West infrared, which
 * sees cloud tops day and night, or visible, which is the picture a person would
 * take from orbit and is black at night. The newest frame the server has (about ten
 * minutes old), re-asked as the map moves and every half hour. Built for a hurricane
 * south of the tip of Baja (Polo, 2026-09-27): radar reaches 150 miles past the
 * border and no further, and a storm is read from orbit anyway. Covers 179 W to
 * 51 W, 11 N to 50 N: the lower 48, Mexico, Hawaii, the Caribbean, southern Canada.
 */
public final class SatelliteOverlay extends ImageOverlay {

    public static final String LAYER_ID = "satellite";
    public static final String HOST = SstOverlay.HOST;
    private static final String WMS = "https://" + HOST + "/geoserver/satellite/ows?service=WMS&version=1.3.0";
    /** Which band is drawn; the pref survives a restart. */
    public static final String PREF_BAND = "weather.layer.satellite.band";
    public static final int INFRARED = 0, VISIBLE = 1;
    /** The GOES layers' own coverage, from the capabilities. */
    private static final double WEST = -179.5, EAST = -50.7, SOUTH = 10.9, NORTH = 50.5;

    private int band;

    public SatelliteOverlay(MapView mapView, EgressPolicy egress) {
        super(mapView, egress, "AtmosphereSatellite", LAYER_ID, "Satellite");
        final SharedPreferences p = MapCompat.prefs();
        band = p == null ? INFRARED : p.getInt(PREF_BAND, INFRARED);
    }

    public int band() {
        return band;
    }

    /** Infrared or visible; persisted, and the picture asked for again. */
    public void setBand(int value) {
        final int v = value == VISIBLE ? VISIBLE : INFRARED;
        final SharedPreferences p = MapCompat.prefs();
        if (p != null)
            p.edit().putInt(PREF_BAND, v).apply();
        if (v == band)
            return;
        band = v;
        reask();
    }

    @Override
    protected double coverWest() {
        return WEST;
    }

    @Override
    protected double coverEast() {
        return EAST;
    }

    @Override
    protected double coverSouth() {
        return SOUTH;
    }

    @Override
    protected double coverNorth() {
        return NORTH;
    }

    @Override
    protected String url(double west, double south, double east, double north, int px, int py) {
        // The base cuts the box to the coverage above and places the picture over
        // the cut box, so this asks for exactly what it is given.
        return WMS + "&request=GetMap&layers=satellite:"
                + (band == VISIBLE ? "goes_visible_imagery" : "goes_longwave_imagery")
                + "&styles=&crs=CRS:84"
                + String.format(Locale.US, "&bbox=%.4f,%.4f,%.4f,%.4f", west, south, east, north)
                + "&width=" + px + "&height=" + py + "&format=image/png&transparent=true";
    }

    @Override
    protected int alpha() {
        return 225;
    }

    @Override
    protected double maxSpanLon() {
        return 60;
    }

    @Override
    protected double maxSpanLat() {
        return 40;
    }

    @Override
    protected String noun() {
        return "satellite picture";
    }

    @Override
    protected String shown() {
        return band == VISIBLE
                ? "Satellite on the map: visible light, the newest frame; dark where it is night"
                : "Satellite on the map: infrared cloud tops, the newest frame, day or night";
    }
}
