package com.atakmap.android.airaware;

import com.atakmap.android.airaware.net.Http;
import com.atakmap.coremap.log.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Airfield observations from aviationweather.gov.
 *
 * <p>Open and keyless, and queryable by bounding box, which is what a plugin needs: a
 * two-degree box around Los Angeles returns thirty-four stations, the whole of California
 * and Nevada seventy. So unlike the restrictions -- which are fetched nationally because
 * the list carries no coordinates -- these are fetched for where the operator is looking.
 */
public final class MetarFeed {

    private static final String TAG = "MetarFeed";

    private MetarFeed() {
    }

    /** Observations inside a bounding box. Worker thread only. */
    public static List<Metar> inBox(double south, double west, double north, double east)
            throws IOException {
        final String url = String.format(Locale.US,
                "https://aviationweather.gov/api/data/metar?bbox=%.4f,%.4f,%.4f,%.4f&format=json",
                south, west, north, east);
        final byte[] body = Http.get(url);
        final List<Metar> out = new ArrayList<>();
        try {
            final JSONArray rows = new JSONArray(new String(body, Charset.forName("UTF-8")));
            for (int i = 0; i < rows.length(); i++) {
                final JSONObject r = rows.optJSONObject(i);
                if (r == null)
                    continue;
                final Metar m = one(r);
                if (m != null)
                    out.add(m);
            }
        } catch (Exception e) {
            throw new IOException("the observations were not readable");
        }
        return out;
    }

    private static Metar one(JSONObject r) {
        final Metar m = new Metar();
        m.icao = r.optString("icaoId", "").trim();
        if (m.icao.isEmpty())
            return null;
        m.name = r.optString("name", m.icao).trim();
        m.lat = r.optDouble("lat", Double.NaN);
        m.lon = r.optDouble("lon", Double.NaN);
        if (Double.isNaN(m.lat) || Double.isNaN(m.lon))
            return null;
        m.elevM = r.optDouble("elev", Double.NaN);
        // obsTime is seconds since the epoch, not millis.
        final long secs = r.optLong("obsTime", 0L);
        m.obsMs = secs > 0 ? secs * 1000L : 0L;
        m.raw = r.optString("rawOb", "").trim();
        m.visibility = r.isNull("visib") ? "" : r.optString("visib", "").trim();
        m.windDirDeg = r.isNull("wdir") ? -1 : r.optInt("wdir", -1);
        m.windKt = r.isNull("wspd") ? -1 : r.optInt("wspd", -1);
        m.tempC = r.isNull("temp") ? Double.NaN : r.optDouble("temp", Double.NaN);
        m.dewpointC = r.isNull("dewp") ? Double.NaN : r.optDouble("dewp", Double.NaN);
        m.altimeterHpa = r.isNull("altim") ? Double.NaN : r.optDouble("altim", Double.NaN);

        // Published, never derived.
        final String cat = r.optString("fltCat", "").trim().toUpperCase(Locale.US);
        try {
            m.category = cat.isEmpty() ? Metar.Cat.UNKNOWN : Metar.Cat.valueOf(cat);
        } catch (IllegalArgumentException e) {
            m.category = Metar.Cat.UNKNOWN;
        }

        // A ceiling is the lowest broken or overcast layer. Few and scattered are not a
        // ceiling, which is why a sky with cloud in it can still be unlimited.
        final JSONArray clouds = r.optJSONArray("clouds");
        if (clouds != null) {
            for (int i = 0; i < clouds.length(); i++) {
                final JSONObject c = clouds.optJSONObject(i);
                if (c == null)
                    continue;
                final String cover = c.optString("cover", "").trim().toUpperCase(Locale.US);
                final int base = c.isNull("base") ? -1 : c.optInt("base", -1);
                m.layers.add(base >= 0 ? cover + " " + Metar.comma(base) + " ft" : cover);
                final boolean isCeiling = "BKN".equals(cover) || "OVC".equals(cover)
                        || "OVX".equals(cover);
                if (isCeiling && base >= 0 && (m.ceilingFt < 0 || base < m.ceilingFt))
                    m.ceilingFt = base;
            }
        }
        return m;
    }

    static void debug(String message) {
        Log.d(TAG, message);
    }
}
