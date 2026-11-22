package com.atakmap.android.ipaws.data;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Where to put an alert that has no area to draw: the middle of its state, or of its
 * stretch of water.
 *
 * <p>About 93% of alerts carry no geometry and name forecast zones instead, and nearly
 * all of those resolve. When none of an alert's zones will -- the service no longer
 * holds them, or it named none -- the alert used to be listed and not drawn at all,
 * which is a confident-looking map missing an alert. It is drawn here instead, as a
 * point, and the status line says how many were placed this way.
 *
 * <p>The table is the infra-TAK Node-RED IPAWS flow's {@code STATE_CENTROIDS}
 * ({@code nodered/build-flows.js}), "used only as last-resort fallback when zone
 * geometry fetch fails", so the plugin and the server feed put a lost alert in the same
 * place. Six area codes the plugin offers are not in that table and were added here,
 * approximately: FM, MH and PW (the island states' main atolls), PM (the waters around
 * the Marianas), PS (American Samoa's waters) and SL (the St. Lawrence).
 */
public final class StateCenters {

    private StateCenters() {
    }

    /** Area code to {lon, lat}. */
    private static final Map<String, double[]> AT = new HashMap<>();

    static {
        put("AL", -86.9023, 32.3182); put("AK", -153.3691, 64.2008); put("AZ", -111.0937, 34.2744);
        put("AR", -92.4426, 34.7465); put("CA", -119.4179, 37.1551); put("CO", -105.5478, 38.9972);
        put("CT", -72.7273, 41.6032); put("DE", -75.5277, 38.9108); put("FL", -81.5158, 27.7663);
        put("GA", -83.6431, 32.9866); put("HI", -155.5828, 19.8968); put("ID", -114.7420, 44.2394);
        put("IL", -89.1965, 40.3495); put("IN", -86.2816, 39.8494); put("IA", -93.2140, 42.0115);
        put("KS", -98.3804, 38.5266); put("KY", -85.3021, 37.6690); put("LA", -91.9623, 31.1695);
        put("ME", -69.3819, 44.6939); put("MD", -76.8021, 39.0639); put("MA", -71.5301, 42.2302);
        put("MI", -84.5603, 43.3266); put("MN", -94.6859, 45.6945); put("MS", -89.6678, 32.7364);
        put("MO", -92.3022, 38.4623); put("MT", -110.3626, 46.8797); put("NE", -99.9018, 41.4925);
        put("NV", -116.4194, 38.8026); put("NH", -71.5724, 43.1939); put("NJ", -74.4057, 40.0583);
        put("NM", -106.1126, 34.5199); put("NY", -74.9481, 42.1657); put("NC", -79.0193, 35.6301);
        put("ND", -101.0020, 47.5289); put("OH", -82.9071, 40.3888); put("OK", -97.0929, 35.5653);
        put("OR", -120.5542, 44.5720); put("PA", -77.1945, 40.5908); put("RI", -71.4774, 41.6809);
        put("SC", -81.1637, 33.8569); put("SD", -99.9018, 44.2998); put("TN", -86.6923, 35.7478);
        put("TX", -99.3413, 31.4757); put("UT", -111.0937, 39.3210); put("VT", -72.7107, 44.0459);
        put("VA", -78.6569, 37.4316); put("WA", -120.7401, 47.4009); put("WV", -80.4549, 38.9179);
        put("WI", -89.6165, 44.2685); put("WY", -107.5512, 43.0760);
        put("DC", -77.0369, 38.9072); put("PR", -66.5901, 18.2208); put("VI", -64.8963, 17.7297);
        put("GU", 144.7937, 13.4443); put("AS", -170.7020, -14.2710); put("MP", 145.6739, 14.8901);
        put("AN", -70.0, 40.5); put("AM", -78.0, 32.0); put("GM", -89.5, 27.0);
        put("PZ", -124.5, 38.5); put("PH", -157.5, 21.0); put("PK", -153.0, 57.5);
        put("BZ", -170.0, 60.5);
        put("LS", -86.5, 47.0); put("LM", -87.0, 44.0); put("LH", -83.0, 44.5);
        put("LE", -81.0, 42.0); put("LO", -77.0, 43.5);
        // Not in the Node-RED table; approximate.
        put("FM", 158.2, 6.9); put("MH", 171.2, 7.1); put("PW", 134.6, 7.5);
        put("PM", 145.0, 14.5); put("PS", -170.7, -14.3); put("SL", -75.5, 44.6);
    }

    private static void put(String code, double lon, double lat) {
        AT.put(code, new double[] { lon, lat });
    }

    /**
     * The center for an alert: from its first UGC code (the state or water letters,
     * as the Node-RED flow reads them), else from its first zone URL's id. Null when
     * neither names a known area.
     *
     * @return {lon, lat}
     */
    public static double[] of(Alert a) {
        final double[] fromUgc = firstKnown(a.ugc);
        if (fromUgc != null)
            return fromUgc;
        for (String url : a.zoneUrls) {
            final int slash = url.lastIndexOf('/');
            final double[] c = lookup(slash < 0 ? url : url.substring(slash + 1));
            if (c != null)
                return c;
        }
        return null;
    }

    private static double[] firstKnown(List<String> codes) {
        for (String c : codes) {
            final double[] at = lookup(c);
            if (at != null)
                return at;
        }
        return null;
    }

    private static double[] lookup(String zoneId) {
        if (zoneId == null || zoneId.length() < 2)
            return null;
        return AT.get(zoneId.substring(0, 2).toUpperCase(Locale.US));
    }
}
