package com.atakmap.android.firecast.model;

import com.atakmap.android.firecast.Esri;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Locale;

/**
 * What the ground is at one point, from LANDFIRE's public WMS: the fuel model, the
 * slope, which way it faces and how much canopy is over it.
 *
 * <p>Four GetFeatureInfo requests, no key, about a second each (measured 2026-10-11).
 * LANDFIRE 2024 for fuels and canopy; the topography layers are the 2020 set, which is
 * the newest LANDFIRE publishes. Every URL is a constant here plus four doubles.
 */
public final class Landfire {

    private static final String HOST = "https://edcintl.cr.usgs.gov/geoserver/landfire/";
    private static final String FUELS = HOST + "conus_2024/ows";
    private static final String TOPO = HOST + "conus_topo/ows";

    public static final class Site {
        /** FBFM40 code, 101 to 204; 91 to 99 is non-burnable; -1 when unknown. */
        public final int fuelModel;
        public final double slopeDeg, aspectDeg;
        /** Canopy cover, percent. */
        public final double coverPct;

        Site(int fuelModel, double slopeDeg, double aspectDeg, double coverPct) {
            this.fuelModel = fuelModel;
            this.slopeDeg = slopeDeg;
            this.aspectDeg = aspectDeg;
            this.coverPct = coverPct;
        }

        public boolean burnable() {
            return fuelModel >= 100 || (fuelModel >= 1 && fuelModel <= 13);
        }
    }

    private Landfire() {
    }

    /** Worker thread. Throws when the service does not answer. */
    public static Site at(double lat, double lon) throws Exception {
        final int fuel = pixel(FUELS, "LF2024_FBFM40_CONUS", lat, lon);
        final int slope = pixel(TOPO, "LF2020_SlpD_CONUS", lat, lon);
        final int aspect = pixel(TOPO, "LF2020_Asp_CONUS", lat, lon);
        final int cover = pixel(FUELS, "LF2024_CC_CONUS", lat, lon);
        // LANDFIRE codes flat ground's aspect as -1; the model wants a direction, and
        // on flat ground it does not matter which.
        return new Site(fuel, Math.max(0, slope), aspect < 0 ? 0 : aspect, Math.max(0, cover));
    }

    /** The raster's value under the point: one pixel asked for out of a 3 x 3 window. */
    private static int pixel(String service, String layer, double lat, double lon) throws Exception {
        final double d = 0.0004d;   // about a 30 m pixel either side
        final String url = service + "?service=WMS&version=1.1.1&request=GetFeatureInfo"
                + "&layers=" + layer + "&query_layers=" + layer + "&srs=EPSG:4326"
                + "&bbox=" + String.format(Locale.US, "%.6f,%.6f,%.6f,%.6f", lon - d, lat - d, lon + d, lat + d)
                + "&width=3&height=3&x=1&y=1&info_format=application/json";
        final JSONObject o = new JSONObject(Esri.get(url));
        final JSONArray feats = o.optJSONArray("features");
        if (feats == null || feats.length() == 0)
            return -1;
        final JSONObject props = feats.getJSONObject(0).optJSONObject("properties");
        if (props == null)
            return -1;
        return props.optInt("GRAY_INDEX", -1);
    }
}
