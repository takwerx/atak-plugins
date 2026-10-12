package com.atakmap.android.firecast.model;

import com.atakmap.android.firecast.Esri;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The buildings inside a box, from FEMA's USA Structures (Oak Ridge National
 * Laboratory for FEMA), the national building inventory: every structure with its
 * occupancy class and a median population estimate, served keyless from ArcGIS
 * Online (measured 2026-10-11: 357 buildings in a 2 km box near Santa Clarita).
 */
public final class Structures {

    private static final String LAYER = "https://services2.arcgis.com/FiaPA4ga0iQKduv3/arcgis/rest/services/USA_Structures_View/FeatureServer/0";
    /** Enough for a town; past this the run says the count is a floor. */
    public static final int MAX = 8000;

    public static final class Building {
        public final double lat, lon;
        /** FEMA's top-level class: Residential, Commercial, Industrial, Education, Government, Assembly, Agriculture, Utility, Unclassified. */
        public final String occupancy;
        /** Median population estimate for the building; 0 when FEMA has none. */
        public final double population;

        Building(double lat, double lon, String occupancy, double population) {
            this.lat = lat;
            this.lon = lon;
            this.occupancy = occupancy;
            this.population = population;
        }

        public boolean residential() {
            return occupancy != null && occupancy.toLowerCase(Locale.US).startsWith("res");
        }
    }

    private Structures() {
    }

    /** Every building in the box, by its centroid, paged; stops at {@link #MAX}. Worker thread. */
    public static List<Building> within(double south, double west, double north, double east) throws Exception {
        final List<Building> out = new ArrayList<>();
        int offset = 0;
        while (out.size() < MAX) {
            final String url = LAYER + "/query?where=" + Esri.enc("1=1")
                    + "&geometry=" + Esri.enc(String.format(Locale.US, "%.5f,%.5f,%.5f,%.5f", west, south, east, north))
                    + "&geometryType=esriGeometryEnvelope&inSR=4326&spatialRel=esriSpatialRelIntersects"
                    + "&outFields=" + Esri.enc("OCC_CLS,LATITUDE,LONGITUDE,POP_MEDIAN")
                    + "&returnGeometry=false&orderByFields=OBJECTID&resultRecordCount=2000&resultOffset=" + offset + "&f=json";
            final JSONObject d = new JSONObject(Esri.get(url));
            if (d.has("error"))
                throw new IllegalStateException(d.getJSONObject("error").optString("message"));
            final JSONArray feats = d.optJSONArray("features");
            if (feats == null || feats.length() == 0)
                break;
            for (int i = 0; i < feats.length(); i++) {
                final JSONObject a = feats.getJSONObject(i).optJSONObject("attributes");
                if (a == null)
                    continue;
                final double lat = a.optDouble("LATITUDE", Double.NaN), lon = a.optDouble("LONGITUDE", Double.NaN);
                if (Double.isNaN(lat) || Double.isNaN(lon))
                    continue;
                out.add(new Building(lat, lon, a.optString("OCC_CLS", ""), a.optDouble("POP_MEDIAN", 0)));
            }
            if (!d.optBoolean("exceededTransferLimit", false) && feats.length() < 2000)
                break;
            offset += feats.length();
        }
        return out;
    }
}
