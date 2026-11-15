package com.atakmap.android.sitrep.section;

import com.atakmap.android.sitrep.Area;
import com.atakmap.android.sitrep.net.Esri;
import com.atakmap.android.sitrep.net.Http;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.util.List;
import java.util.Locale;

/**
 * Wildfire exposure from the USFS Wildfire Risk to Communities rasters (PLAN 4.3):
 * residential buildings directly, indirectly and not exposed to wildland fuel, the
 * same split for the land, the yearly chance of fire and the flame length if it burns.
 *
 * <p>Exposure Type is sampled at each residential building of FEMA's USA Structures
 * (its LATITUDE/LONGITUDE). The image service answers at most 1,000 points, so an area
 * with more buildings is sampled evenly by OBJECTID and the line says so (decision 12).
 * Values, from the RDS-2020-0016-2 metadata: 1000 = burnable fuel in the pixel
 * (direct), 1-999 = non-burnable but within reach of embers or spread (indirect), 0 =
 * more than 1,530 m from wildland fuel.
 *
 * <p>The service stores the rasters as integers. Burn probability is the yearly
 * probability times 10,000 (its 1352 maximum matches the 0.1367 maximum of the
 * Probabilistic Wildfire Risk service); conditional flame length is whole feet
 * (checked 2026-11-14 against the 4 ft and 8 ft exceedance rasters on a grid near
 * Corona). Housing Unit Risk is not used: the service keeps only its relative values.
 */
public final class Exposure extends Section {

    static final String BASE = "https://imagery.geoplatform.gov/iipp/rest/services/Fire_Aviation/USFS_EDW_RMRS_WRC_";
    static final String EXPOSURE = BASE + "ExposureType/ImageServer";
    static final String BURN = BASE + "BurnProbability/ImageServer";
    static final String FLAME = BASE + "ConditionalFlameLength/ImageServer";

    /** getSamples answers at most 1,000 points; aim below it so the even sample fits. */
    private static final int SAMPLES = 950;
    /**
     * The homes Structures counts as residential: not hotels, nursing homes or
     * dormitories, which FEMA also files under Residential.
     */
    private static final String RESIDENTIAL = "OCC_CLS='Residential' AND (PRIM_OCC IN "
            + "('Single Family Dwelling','Manufactured Home','Unclassified') OR PRIM_OCC LIKE 'Multi%')";

    public Exposure() {
        super("exposure", "Wildfire exposure", false,
                "USFS Wildfire Risk to Communities (2024 data, fuels as of 2020) sampled at each residential "
                        + "building of FEMA USA Structures; land share, yearly chance of fire and flame length over the area.");
    }

    @Override
    public Result fetch(Area area) throws Exception {
        final long[] land = histogram(EXPOSURE, area);
        long landTotal = 0;
        for (long c : land)
            landTotal += c;
        if (landTotal == 0) {
            final Result r = new Result("Not published here", null);
            r.notPublishedHere = true;
            return r.line("The USFS publishes wildfire exposure for the United States only.");
        }
        // The bins stop at the area's own highest value: value 1000 is there only when present.
        final long landNone = land[0], landDirect = land.length > 1000 ? land[1000] : 0;
        final long landIndirect = landTotal - landNone - landDirect;

        // Residential buildings: count, then an even sample when there are too many to send.
        final int homes = Esri.count(Structures.LAYER, RESIDENTIAL, area);
        int direct = 0, indirect = 0, none = 0, sampled = 0;
        if (homes > 0) {
            final int step = (int) Math.ceil(homes / (double) SAMPLES);
            final String where = step <= 1 ? RESIDENTIAL : RESIDENTIAL + " AND MOD(OBJECTID," + step + ")=0";
            final List<JSONObject> rows = Esri.attributes(Structures.LAYER, where, area, "LATITUDE,LONGITUDE");
            final StringBuilder pts = new StringBuilder("{\"points\":[");
            int n = 0;
            for (JSONObject a : rows) {
                final double lat = a.optDouble("LATITUDE"), lon = a.optDouble("LONGITUDE");
                if (!(Math.abs(lat) <= 90) || !(Math.abs(lon) <= 180))
                    continue;
                if (n == 1000)
                    break;
                pts.append(n++ == 0 ? "" : ",")
                        .append(String.format(Locale.US, "[%.6f,%.6f]", lon, lat));
            }
            pts.append("],\"spatialReference\":{\"wkid\":4326}}");
            if (n > 0) {
                final JSONObject o = parse(Http.post(EXPOSURE + "/getSamples",
                        "geometry=" + Esri.enc(pts.toString())
                                + "&geometryType=esriGeometryMultipoint&returnFirstValueOnly=true&f=json"));
                final JSONArray samples = o.optJSONArray("samples");
                if (samples == null)
                    throw new IOException("no samples in the answer");
                for (int i = 0; i < samples.length(); i++) {
                    final JSONObject s = samples.optJSONObject(i);
                    final double v = number(s == null ? null : s.opt("value"));
                    if (Double.isNaN(v))
                        continue;
                    sampled++;
                    if (v >= 1000)
                        direct++;
                    else if (v > 0)
                        indirect++;
                    else
                        none++;
                }
            }
        }

        final Result r;
        final boolean estimate = sampled > 0 && sampled < homes;
        if (homes == 0 || sampled == 0) {
            r = new Result(pct(landDirect, landTotal) + " of the land is wildland fuel", null);
            r.line(homes == 0 ? "No residential buildings are mapped inside the area."
                    : "Residential buildings: no exposure value for them.");
        } else {
            final String d = scaled(direct, sampled, homes, estimate);
            final String in = scaled(indirect, sampled, homes, estimate);
            r = new Result(d + " residential directly exposed, " + in + " indirectly",
                    d + " residential in fuel");
            r.line(GROUP + "Residential buildings: " + Structures.count(homes));
            r.line("  " + cap(d) + " directly exposed: in wildland fuel");
            r.line("  " + cap(in) + " indirectly exposed: embers or fire spread from fuel nearby");
            r.line("  " + cap(scaled(none, sampled, homes, estimate)) + " not exposed: about 1 mi or more from wildland fuel");
            if (estimate)
                r.line("  Estimated from " + Structures.count(sampled) + " buildings sampled evenly.");
        }
        r.line(GROUP + "Land in the area");
        r.line("  " + pct(landDirect, landTotal) + " wildland fuel (direct)");
        r.line("  " + pct(landIndirect, landTotal) + " within reach of fuel (indirect)");
        r.line("  " + pct(landNone, landTotal) + " not exposed");

        // Yearly chance of fire: mean over the area, and the most fire-prone tenth.
        final long[] bp = histogram(BURN, area);
        final double[] bpStats = meanAndP90(bp, 1);
        if (bpStats != null && bpStats[0] > 0)
            r.line("Yearly chance of fire: " + oneIn(bpStats[0] / 10_000d) + " on average"
                    + (bpStats[1] > bpStats[0] ? ", " + oneIn(bpStats[1] / 10_000d) + " in the most fire-prone tenth" : ""));

        // Flame length where it can burn (value 0 is non-burnable).
        final long[] fl = histogram(FLAME, area);
        final double[] flStats = meanAndP90(fl, 1);
        if (flStats != null) {
            long burnable = 0, over4 = 0, over8 = 0;
            for (int i = 1; i < fl.length; i++) {
                burnable += fl[i];
                if (i > 4)
                    over4 += fl[i];
                if (i > 8)
                    over8 += fl[i];
            }
            r.line(GROUP + "Flame length if it burns: " + Math.round(flStats[2]) + " ft typical");
            r.line("  " + Math.round(flStats[1]) + " ft in the worst tenth of the burnable land");
            r.line("  Over 4 ft on " + pct(over4, burnable) + " of the burnable land, over 8 ft on " + pct(over8, burnable));
        }
        return r;
    }

    /**
     * The pixel counts of the area, one per integer value from 0 (the service bins
     * this U16 data one value to a bin, from -0.5). Empty when the area is outside it.
     */
    private static long[] histogram(String service, Area area) throws IOException {
        final JSONObject o = parse(Http.post(service + "/computeHistograms",
                "geometry=" + Esri.enc(area.esriPolygon()) + "&geometryType=esriGeometryPolygon&f=json"));
        final JSONArray hs = o.optJSONArray("histograms");
        if (hs == null)
            throw new IOException("no histograms in the answer");
        final JSONObject h = hs.optJSONObject(0);
        final JSONArray counts = h == null ? null : h.optJSONArray("counts");
        if (counts == null || counts.length() == 0)
            return new long[0];
        final double min = h.optDouble("min"), max = h.optDouble("max");
        if (Math.abs(min + 0.5) > 1e-6 || Math.abs((max - min) - counts.length()) > 1e-6)
            throw new IOException("histogram bins are not one value wide");
        final long[] out = new long[counts.length()];
        for (int i = 0; i < out.length; i++)
            out[i] = Math.max(0, counts.optLong(i, 0));
        return out;
    }

    /**
     * Mean of all pixels, the 90th percentile and the median of the pixels with value
     * {@code from} or above: {mean (all), p90, median}. Null when there are none.
     */
    private static double[] meanAndP90(long[] h, int from) {
        long all = 0, sel = 0;
        double sum = 0;
        for (int i = 0; i < h.length; i++) {
            all += h[i];
            sum += (double) i * h[i];
            if (i >= from)
                sel += h[i];
        }
        if (all == 0 || sel == 0)
            return null;
        double p90 = 0, median = 0;
        long acc = 0;
        boolean gotMedian = false;
        for (int i = from; i < h.length; i++) {
            acc += h[i];
            if (!gotMedian && acc * 2 >= sel) {
                median = i;
                gotMedian = true;
            }
            if (acc * 10 >= sel * 9) {
                p90 = i;
                break;
            }
        }
        return new double[] { sum / all, p90, median };
    }

    /** {@code 1 in 190}; a very small chance reads {@code less than 1 in 10,000}. */
    static String oneIn(double p) {
        if (p < 1e-4)
            return "less than 1 in 10,000";
        final double n = 1 / p;
        final long r = n >= 1000 ? Math.round(n / 100) * 100 : n >= 100 ? Math.round(n / 10) * 10 : Math.round(n);
        return "1 in " + Structures.count((int) Math.max(1, r));
    }

    static String pct(long part, long whole) {
        if (whole <= 0)
            return "0%";
        final long p = Math.round(100d * part / whole);
        return (p == 0 && part > 0 ? "under 1" : String.valueOf(p)) + "%";
    }

    /** An exact count, or {@code about 850} scaled from the sample to two figures. */
    static String scaled(int part, int sampled, int whole, boolean estimate) {
        if (!estimate)
            return Structures.count(part);
        if (part == 0)
            return "0";
        final double v = (double) part * whole / sampled;
        final double mag = Math.pow(10, Math.max(0, Math.floor(Math.log10(v)) - 1));
        return "about " + Structures.count((int) (Math.round(v / mag) * mag));
    }

    private static String cap(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    private static double number(Object v) {
        if (v == null)
            return Double.NaN;
        try {
            return Double.parseDouble(String.valueOf(v).trim());
        } catch (NumberFormatException e) {
            return Double.NaN;
        }
    }

    private static JSONObject parse(String body) throws IOException {
        try {
            final JSONObject o = new JSONObject(body);
            if (o.has("error"))
                throw new IOException("service error: " + o.optJSONObject("error"));
            return o;
        } catch (org.json.JSONException e) {
            throw new IOException("answer is not JSON");
        }
    }
}
