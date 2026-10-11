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
 * The FAA's two endpoints, and nothing else.
 *
 * <p>The list is 24 KB of six flat fields and <b>carries no coordinates</b>: the place lives in
 * prose inside {@code description}. That single fact decides how the plugin syncs — a "within
 * 50 NM of me" question cannot be answered until the geometry is on the device, so the sync is
 * national and the filters are local. The whole country is around 104 details of 26 KB, under
 * 3 MB, which is what makes that honest rather than reckless.
 */
public final class TfrFeed {

    private static final String TAG = "TfrFeed";

    public static final String LIST_URL = "https://tfr.faa.gov/tfrapi/exportTfrList";

    private TfrFeed() {
    }

    /** The list body, straight off the wire, so the caller can cache exactly what it parsed. */
    public static byte[] listBytes() throws IOException {
        return Http.get(LIST_URL);
    }

    /**
     * The national list. Each row carries only what the list API has; the geometry, the
     * vertical limits and the effective window arrive with {@link #detail}.
     *
     * <p>Takes bytes rather than fetching, because the same parsing has to serve the cached copy
     * at start-up: the map has to come back without the network.
     */
    public static List<Tfr> parseList(byte[] body) throws IOException {
        List<Tfr> out = new ArrayList<>();
        try {
            JSONArray rows = new JSONArray(new String(body, Charset.forName("UTF-8")));
            for (int i = 0; i < rows.length(); i++) {
                JSONObject r = rows.optJSONObject(i);
                if (r == null)
                    continue;
                String id = r.optString("notam_id", "").trim();
                if (!validNotamId(id)) {
                    // The id becomes a URL and a file name. Anything that is not a NOTAM
                    // number is not something to build either one out of.
                    Log.w(TAG, "skipping a row with an unusable notam_id");
                    continue;
                }
                Tfr t = new Tfr();
                t.notamId = id;
                t.type = r.optString("type", "").trim();
                t.state = r.optString("state", "").trim();
                t.facility = r.optString("facility", "").trim();
                t.description = r.optString("description", "").trim();
                t.creationDate = r.optString("creation_date", "").trim();
                out.add(t);
            }
        } catch (Exception e) {
            throw new IOException("the TFR list was not readable");
        }
        if (out.isEmpty())
            throw new IOException("the TFR list came back empty");
        return out;
    }

    /** One TFR's detail document, straight off the wire and unparsed. */
    public static byte[] detail(String notamId) throws IOException {
        if (!validNotamId(notamId))
            throw new IOException("bad NOTAM id");
        return Http.get("https://tfr.faa.gov/download/detail_" + notamId.replace('/', '_') + ".xml");
    }

    /**
     * A NOTAM number is digits, one slash, digits — "6/3349", "0/0367".
     *
     * <p>Checked because this string is concatenated into a URL and, after the slash becomes an
     * underscore, into a file name in the plugin's cache directory. A server that answered with
     * {@code ../../something} would otherwise be choosing where the plugin writes.
     */
    static boolean validNotamId(String id) {
        if (id == null)
            return false;
        String s = id.trim();
        if (s.length() < 3 || s.length() > 12)
            return false;
        int slashes = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '/') {
                slashes++;
                if (i == 0 || i == s.length() - 1)
                    return false;
            } else if (c < '0' || c > '9') {
                return false;
            }
        }
        return slashes == 1;
    }

    /** The cache file name for a NOTAM, safe by construction from {@link #validNotamId}. */
    static String fileName(String notamId) {
        return notamId.replace('/', '_').toLowerCase(Locale.US) + ".xml";
    }
}
