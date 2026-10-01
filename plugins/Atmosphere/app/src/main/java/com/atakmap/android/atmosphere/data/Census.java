
package com.atakmap.android.atmosphere.data;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Street addresses, looked up against the Census Bureau's geocoder.
 *
 * <p>Used ahead of the geocoder ATAK is set to, because the Android one answers a
 * house number it does not hold with the <b>city</b> and reports it as one confident
 * match: a residential address in Murrieta came back as "Murrieta, CA, USA" at the
 * city center, six miles out, and nothing in that answer says it is a town rather
 * than an address (operator, 2026-09-25). This service answers with the address or
 * with nothing:
 *
 * <pre>
 *   "24601 Jefferson Ave, Murrieta, CA" -> 24601 JEFFERSON AVE, MURRIETA, CA, 92562
 *   "24601 Jefferson, Murrieta, CA"     -> 24601 JEFFERSON AVE, MURRIETA, CA, 92562
 *   "41000 Main St, Murrieta, CA"       -> no matches
 * </pre>
 *
 * <p>The suffix is optional, which matters: people write their own address the way
 * they say it. TIGER address ranges are interpolated along the street segment, so a
 * match is on the right block and side of the street rather than on the roof -- close
 * enough for a spot forecast, which is asked for in terms of a fire, not a mailbox.
 *
 * <p>No key, no quota published, public domain, and it covers the United States and
 * its territories only -- which is the same footprint as the spot forecasts this page
 * requests. Outside it, the caller falls back to ATAK's own geocoder.
 *
 * <p>No Android types here, so the parsing can be tested.
 */
public final class Census {

    public static final String HOST = "geocoding.geo.census.gov";

    /**
     * Where to ask for one typed address.
     *
     * <p>{@code Public_AR_Current} is the current public address ranges; the dated
     * benchmarks exist for matching against a past vintage, which is not what a
     * forecast wants.
     */
    public static String lookupUrl(String address) {
        return "https://" + HOST + "/geocoder/locations/onelineaddress"
                + "?benchmark=Public_AR_Current&format=json&address=" + encode(address);
    }

    private static String encode(String s) {
        if (s == null)
            return "";
        final StringBuilder b = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            final char c = s.charAt(i);
            if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
                    || c == '-' || c == '_' || c == '.' || c == '~') {
                b.append(c);
            } else if (c == ' ') {
                b.append("+");
            } else {
                // UTF-8, byte by byte: an address can carry an accent or a dash.
                final byte[] bytes = String.valueOf(c).getBytes(java.nio.charset.Charset
                        .forName("UTF-8"));
                for (byte v : bytes)
                    b.append('%').append(String.format(java.util.Locale.US, "%02X", v));
            }
        }
        return b.toString();
    }

    /** One matched address and where it is. */
    public static final class Match {
        /** As the Census wrote it back, e.g. "24601 JEFFERSON AVE, MURRIETA, CA, 92562". */
        public final String address;
        public final double latitude, longitude;

        Match(String address, double latitude, double longitude) {
            this.address = address;
            this.latitude = latitude;
            this.longitude = longitude;
        }
    }

    /**
     * Every match in an answer, best first as the service ordered them. An answer that
     * cannot be read is no matches rather than an exception, so the caller falls
     * through to the other geocoder instead of showing an error.
     */
    public static List<Match> parse(String body) {
        final List<Match> out = new ArrayList<>();
        if (body == null || body.isEmpty())
            return out;
        try {
            final JSONObject result = new JSONObject(body).optJSONObject("result");
            if (result == null)
                return out;
            final JSONArray matches = result.optJSONArray("addressMatches");
            if (matches == null)
                return out;
            for (int i = 0; i < matches.length(); i++) {
                final JSONObject m = matches.optJSONObject(i);
                if (m == null)
                    continue;
                final JSONObject c = m.optJSONObject("coordinates");
                if (c == null)
                    continue;
                // x is longitude and y is latitude. Naming them the other way round
                // puts a California address in the Indian Ocean.
                final double lon = c.optDouble("x", Double.NaN);
                final double lat = c.optDouble("y", Double.NaN);
                if (Double.isNaN(lat) || Double.isNaN(lon))
                    continue;
                if (lat == 0 && lon == 0)
                    continue;
                final String label = m.optString("matchedAddress", "").trim();
                out.add(new Match(label, lat, lon));
            }
        } catch (Exception e) {
            return out;
        }
        return out;
    }

    private Census() {
    }
}
