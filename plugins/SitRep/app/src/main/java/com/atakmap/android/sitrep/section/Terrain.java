package com.atakmap.android.sitrep.section;

import com.atakmap.android.sitrep.Area;
import com.atakmap.android.sitrep.Units;
import com.atakmap.android.sitrep.net.Esri;
import com.atakmap.android.sitrep.net.Http;
import com.atakmap.coremap.conversions.Span;
import com.atakmap.coremap.maps.coords.Ellipsoid;
import com.atakmap.coremap.maps.coords.MGRSPoint;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Locale;

/**
 * Elevation, slope and aspect inside the area from USGS 3DEP, and where the area is
 * (PLAN 4.12b). Slope is measured against the S-236 dozer limits Dozer Country paints
 * by: 45% sidehill, 55% uphill, 75% downhill.
 */
public final class Terrain extends Section {

    static final String DEM = "https://elevation.nationalmap.gov/arcgis/rest/services/3DEPElevation/ImageServer";
    /** Most pixels asked for; the pixel grows with the area past this. */
    private static final double MAX_PIXELS = 1_500_000d;

    public Terrain() {
        super("terrain", "Terrain", false,
                "USGS 3D Elevation Program (3DEP), about 30 m cells; slope limits from USFS/NWCG S-236.");
        seeIn = "See slope on the map in Dozer Country.";
    }

    @Override
    public Result fetch(Area area) throws Exception {
        final double lat0 = Math.cos(Math.toRadians((area.south + area.north) / 2));
        final double degPerM = 1 / 111_320d;
        double px = 30 * degPerM;
        final double pixels = area.squareMeters() / (30 * 30);
        if (pixels > MAX_PIXELS)
            px *= Math.sqrt(pixels / MAX_PIXELS);
        final String pixel = "{\"x\":" + String.format(Locale.US, "%.7f", px / Math.max(0.2, lat0))
                + ",\"y\":" + String.format(Locale.US, "%.7f", px) + ",\"spatialReference\":{\"wkid\":4326}}";

        final JSONObject elev = stats(area, pixel, null);
        final JSONObject slope = stats(area, pixel, "Slope Degrees");
        final JSONObject aspect = stats(area, pixel, "Aspect Degrees");

        final JSONObject es = first(elev.optJSONArray("statistics"));
        if (es == null || es.optInt("count", 0) == 0) {
            final Result r = new Result("Not published here", null);
            r.notPublishedHere = true;
            return r.line("USGS 3DEP covers the United States and its territories.");
        }
        final double lo = es.optDouble("min"), hi = es.optDouble("max"), mean = es.optDouble("mean");
        final Result r = new Result(height(lo) + " to " + height(hi), null);
        r.line(GROUP + "Elevation: " + height(lo) + " to " + height(hi));
        r.line("  Lowest " + height(lo) + ", highest " + height(hi) + ", average " + height(mean) + ".");
        r.line("  " + height(hi - lo) + " of relief.");

        final JSONObject sh = first(slope.optJSONArray("histograms"));
        final JSONObject ss = first(slope.optJSONArray("statistics"));
        if (sh != null && ss != null) {
            final double steep = ss.optDouble("max");
            final double side = share(sh, deg(45)), up = share(sh, deg(55)), down = share(sh, deg(75));
            r.line(GROUP + "Slope: steepest " + pct(steep) + "%, " + Math.round(side * 100) + "% over 45%");
            r.line("  Average " + pct(ss.optDouble("mean")) + "%, steepest " + pct(steep) + "%.");
            r.line("  " + Math.round(side * 100) + "% of the area is steeper than 45% (dozer sidehill limit).");
            r.line("  " + Math.round(up * 100) + "% is steeper than 55% (dozer uphill limit).");
            r.line("  " + Math.round(down * 100) + "% is steeper than 75% (dozer downhill limit).");
        }

        final JSONObject ah = first(aspect.optJSONArray("histograms"));
        if (ah != null) {
            final double[] sectors = sectors(ah);
            final String[] names = { "north", "northeast", "east", "southeast", "south", "southwest", "west", "northwest" };
            int best = 0;
            double total = 0;
            for (int i = 0; i < 8; i++) {
                total += sectors[i];
                if (sectors[i] > sectors[best])
                    best = i;
            }
            if (total > 0) {
                r.line(GROUP + "Aspect: mostly " + names[best]);
                final StringBuilder b = new StringBuilder();
                for (int i = 0; i < 8; i++)
                    b.append(i == 0 ? "" : ", ").append(names[i]).append(' ')
                            .append(Math.round(sectors[i] / total * 100)).append('%');
                r.line("  Slopes facing " + b + ".");
            }
        }

        final double cLat = (area.south + area.north) / 2, cLon = (area.west + area.east) / 2;
        r.line(GROUP + "Location");
        try {
            final MGRSPoint m = MGRSPoint.fromLatLng(Ellipsoid.WGS_84, cLat, cLon, null);
            r.line("  Center " + m.getZoneDescriptor() + m.getGridDescriptor() + " "
                    + m.getEastingDescriptor() + " " + m.getNorthingDescriptor() + " (MGRS/USNG).");
        } catch (RuntimeException e) {
            // A point MGRS cannot express (the poles): the lat/long line still says where.
        }
        r.line(String.format(Locale.US, "  Center %.5f, %.5f.", cLat, cLon));
        return r;
    }

    private static JSONObject stats(Area area, String pixel, String function) throws Exception {
        final String form = "f=json&geometry=" + Esri.enc(area.esriPolygon())
                + "&geometryType=esriGeometryPolygon"
                + "&mosaicRule=" + Esri.enc("{\"mosaicMethod\":\"esriMosaicNone\"}")
                + "&pixelSize=" + Esri.enc(pixel)
                + (function == null ? "" : "&renderingRule=" + Esri.enc("{\"rasterFunction\":\"" + function + "\"}"));
        final JSONObject o = new JSONObject(Http.post(DEM + "/computeStatisticsHistograms", form));
        if (o.has("error"))
            throw new java.io.IOException("3DEP error " + o.optJSONObject("error"));
        return o;
    }

    private static JSONObject first(JSONArray a) {
        return a == null || a.length() == 0 ? null : a.optJSONObject(0);
    }

    /** Share of cells at or over a slope in degrees, the bin it falls in split linearly. */
    private static double share(JSONObject h, double threshold) {
        final JSONArray counts = h.optJSONArray("counts");
        final double min = h.optDouble("min"), max = h.optDouble("max");
        if (counts == null || counts.length() == 0 || !(max > min))
            return 0;
        final double w = (max - min) / counts.length();
        double over = 0, all = 0;
        for (int i = 0; i < counts.length(); i++) {
            final double c = counts.optDouble(i, 0), a = min + i * w, b = a + w;
            all += c;
            if (a >= threshold)
                over += c;
            else if (b > threshold)
                over += c * (b - threshold) / w;
        }
        return all == 0 ? 0 : over / all;
    }

    /** Aspect histogram (0-360 degrees) into eight compass sectors. */
    private static double[] sectors(JSONObject h) {
        final double[] s = new double[8];
        final JSONArray counts = h.optJSONArray("counts");
        final double min = h.optDouble("min"), max = h.optDouble("max");
        if (counts == null || counts.length() == 0 || !(max > min))
            return s;
        final double w = (max - min) / counts.length();
        for (int i = 0; i < counts.length(); i++) {
            final double mid = min + (i + 0.5) * w;
            if (mid < 0)
                continue;   // flat cells carry no aspect
            s[(int) Math.round(mid / 45) % 8] += counts.optDouble(i, 0);
        }
        return s;
    }

    private static double deg(double percent) {
        return Math.toDegrees(Math.atan(percent / 100));
    }

    private static long pct(double degrees) {
        return Math.round(Math.tan(Math.toRadians(Math.max(0, Math.min(89, degrees)))) * 100);
    }

    private static String height(double m) {
        if (Units.type() == Span.METRIC)
            return String.format(Locale.US, "%,d m", Math.round(m));
        return String.format(Locale.US, "%,d ft", Math.round(m * 3.28084));
    }
}
