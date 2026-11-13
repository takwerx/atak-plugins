package com.atakmap.android.sitrep.net;

import com.atakmap.coremap.maps.coords.GeoPoint;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Cities, towns, unincorporated communities and counties by name, and their outlines,
 * from Census TIGERweb (PLAN decision 13).
 */
public final class Places {

    static final String BASE = "https://tigerweb.geo.census.gov/arcgis/rest/services/TIGERweb/";
    /** Incorporated places, census designated places, counties. */
    static final String[] LAYERS = {
            "Places_CouSub_ConCity_SubMCD/MapServer/4",
            "Places_CouSub_ConCity_SubMCD/MapServer/5",
            "State_County/MapServer/1" };

    /** One answer: what to show, and where to fetch the outline. */
    public static final class Place {
        public final String label;
        final String layer, geoid;
        public final String state;
        final double areaLand;

        Place(String label, String layer, String geoid, String state, double areaLand) {
            this.label = label;
            this.layer = layer;
            this.geoid = geoid;
            this.state = state;
            this.areaLand = areaLand;
        }
    }

    private Places() {
    }

    /**
     * Places whose name starts with what was typed, the given state first, then the
     * largest. At most 40.
     */
    public static List<Place> search(String typed, final String preferState) throws Exception {
        final String q = typed == null ? "" : typed.trim().toUpperCase(Locale.US)
                .replaceAll("[^A-Z0-9 .'-]", "").replace("'", "''");
        final List<Place> out = new ArrayList<>();
        if (q.length() < 2)
            return out;
        for (String layer : LAYERS) {
            final String form = "f=json&returnGeometry=false&resultRecordCount=25"
                    + "&where=" + Esri.enc("UPPER(BASENAME) LIKE '" + q + "%'")
                    + "&outFields=" + Esri.enc("NAME,STATE,GEOID,AREALAND");
            final JSONObject o = Esri.parse(Http.post(BASE + layer + "/query", form));
            final JSONArray f = o.optJSONArray("features");
            if (f == null)
                continue;
            for (int i = 0; i < f.length(); i++) {
                final JSONObject a = f.optJSONObject(i) == null ? null : f.optJSONObject(i).optJSONObject("attributes");
                if (a == null)
                    continue;
                final String geoid = a.optString("GEOID", "");
                final String st = POSTAL.get(a.optString("STATE", ""));
                if (!geoid.matches("\\d{5,7}") || st == null)
                    continue;
                final String name = a.optString("NAME", "").replace(" CDP", " (community)");
                out.add(new Place(name + ", " + st, layer, geoid, st, a.optDouble("AREALAND", 0)));
            }
        }
        Collections.sort(out, new Comparator<Place>() {
            @Override
            public int compare(Place a, Place b) {
                final boolean ah = a.state.equals(preferState), bh = b.state.equals(preferState);
                if (ah != bh)
                    return ah ? -1 : 1;
                return Double.compare(b.areaLand, a.areaLand);
            }
        });
        return out.size() > 40 ? new ArrayList<>(out.subList(0, 40)) : out;
    }

    /** Postal code of the state a point is in (NWS county layer); empty when not known. */
    public static String stateAt(GeoPoint p) {
        if (p == null || !(Math.abs(p.getLatitude()) <= 90) || !(Math.abs(p.getLongitude()) <= 180))
            return "";
        try {
            final String form = "f=json&returnGeometry=false&outFields=state&where=1%3D1"
                    + "&geometry=" + Esri.enc(String.format(Locale.US, "%.6f,%.6f", p.getLongitude(), p.getLatitude()))
                    + "&geometryType=esriGeometryPoint&inSR=4326&spatialRel=esriSpatialRelIntersects";
            final JSONArray f = Esri.parse(Http.post(NWS_COUNTIES + "/query", form)).optJSONArray("features");
            final JSONObject a = f == null || f.length() == 0 || f.optJSONObject(0) == null ? null
                    : f.optJSONObject(0).optJSONObject("attributes");
            final String st = a == null ? "" : a.optString("state", "");
            return st.matches("[A-Z]{2}") ? st : "";
        } catch (Exception e) {
            return "";   // the order is a convenience; the search still answers
        }
    }

    static final String NWS_COUNTIES = "https://mapservices.weather.noaa.gov/static/rest/services/"
            + "nws_reference_maps/nws_reference_map/MapServer/2";

    /** The place's outline: its largest ring, thinned by the server. */
    public static GeoPoint[] outline(Place p) throws Exception {
        final String form = "f=json&returnGeometry=true&outSR=4326&maxAllowableOffset=0.0005"
                + "&where=" + Esri.enc("GEOID='" + p.geoid + "'") + "&outFields=GEOID";
        final JSONObject o = Esri.parse(Http.post(BASE + p.layer + "/query", form));
        final JSONArray f = o.optJSONArray("features");
        final JSONObject g = f == null || f.length() == 0 || f.optJSONObject(0) == null ? null
                : f.optJSONObject(0).optJSONObject("geometry");
        final JSONArray rings = g == null ? null : g.optJSONArray("rings");
        if (rings == null || rings.length() == 0)
            throw new java.io.IOException("no outline for that place");
        JSONArray best = null;
        double bestArea = -1;
        for (int i = 0; i < rings.length(); i++) {
            final JSONArray r = rings.optJSONArray(i);
            final double a = r == null ? 0 : Math.abs(shoelace(r));
            if (a > bestArea) {
                bestArea = a;
                best = r;
            }
        }
        final List<GeoPoint> pts = new ArrayList<>();
        for (int i = 0; best != null && i < best.length(); i++) {
            final JSONArray p2 = best.optJSONArray(i);
            if (p2 == null)
                continue;
            final double lon = p2.optDouble(0), lat = p2.optDouble(1);
            if (Math.abs(lat) <= 90 && Math.abs(lon) <= 180)
                pts.add(new GeoPoint(lat, lon));
        }
        if (pts.size() < 3)
            throw new java.io.IOException("the outline has fewer than three points");
        return pts.toArray(new GeoPoint[0]);
    }

    private static double shoelace(JSONArray r) {
        double s = 0;
        for (int i = 0; i < r.length(); i++) {
            final JSONArray a = r.optJSONArray(i), b = r.optJSONArray((i + 1) % r.length());
            if (a != null && b != null)
                s += a.optDouble(0) * b.optDouble(1) - b.optDouble(0) * a.optDouble(1);
        }
        return s / 2;
    }

    /** State FIPS to postal code. */
    private static final Map<String, String> POSTAL = new HashMap<>();

    static {
        final String[] t = { "01AL", "02AK", "04AZ", "05AR", "06CA", "08CO", "09CT", "10DE", "11DC",
                "12FL", "13GA", "15HI", "16ID", "17IL", "18IN", "19IA", "20KS", "21KY", "22LA", "23ME",
                "24MD", "25MA", "26MI", "27MN", "28MS", "29MO", "30MT", "31NE", "32NV", "33NH", "34NJ",
                "35NM", "36NY", "37NC", "38ND", "39OH", "40OK", "41OR", "42PA", "44RI", "45SC", "46SD",
                "47TN", "48TX", "49UT", "50VT", "51VA", "53WA", "54WV", "55WI", "56WY", "60AS", "66GU",
                "69MP", "72PR", "78VI" };
        for (String x : t)
            POSTAL.put(x.substring(0, 2), x.substring(2));
    }
}
