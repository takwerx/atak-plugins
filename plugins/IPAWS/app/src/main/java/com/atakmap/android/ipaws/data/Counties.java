package com.atakmap.android.ipaws.data;

import com.atakmap.android.ipaws.net.Http;
import com.atakmap.coremap.filesystem.FileSystemUtils;
import com.atakmap.coremap.log.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * The counties of one state, for the picker.
 *
 * <p>{@code /zones?type=county&area=CA} lists them: 58 for California, 3269 across all
 * 59 states and territories, every one of which served when this was checked on
 * 2026-09-16. Each carries a zone id ({@code CAC001}) and a name ({@code Alameda}),
 * and {@link Areas#sameForCountyZone} turns the id into the SAME code an alert
 * actually carries.
 *
 * <p>Kept on disk for a long time, because county boundaries are not weather: a list
 * that is a year old is still right, and a phone with no signal still has to be able
 * to open the picker.
 */
public final class Counties {

    private static final String TAG = "IPAWS";

    /** Counties change on the order of never. */
    private static final long TTL_MS = 180L * 24 * 60 * 60 * 1000;

    /** One county, as the picker shows it and as the filter stores it. */
    public static class County {
        /** "Orange" -- what the picker lists, and nothing else. */
        public final String name;
        /** "006059" -- what an alert carries in geocode.SAME. */
        public final String same;

        County(String name, String same) {
            this.name = name;
            this.same = same;
        }
    }

    public interface Ready {
        void onCounties(List<County> counties);

        /** @param error already phrased for the operator */
        void onFailure(String error);
    }

    private final File dir;

    public Counties(File root) {
        dir = new File(root, "counties");
        if (!dir.isDirectory() && !dir.mkdirs())
            Log.w(TAG, "could not create " + dir);
    }

    /**
     * The counties of a state, from disk when we have them and from the service when
     * we do not. The callback lands on the main thread either way.
     */
    public void get(final String stateCode, final Ready cb) {
        final List<County> cached = readCache(stateCode);
        if (cached != null && !cached.isEmpty()) {
            cb.onCounties(cached);
            return;
        }
        final String url = "https://api.weather.gov/zones?type=county&area="
                + android.net.Uri.encode(stateCode);
        Http.get(url, new Http.Callback() {
            @Override
            public void onSuccess(byte[] body) {
                final List<County> parsed = parse(body);
                if (parsed.isEmpty()) {
                    cb.onFailure("no counties came back for " + Areas.name(stateCode));
                    return;
                }
                writeCache(stateCode, body);
                cb.onCounties(parsed);
            }

            @Override
            public void onFailure(int status, String error) {
                // A stale list beats no list: if the service is unreachable but we
                // have an old copy, use it rather than making the picker useless.
                final List<County> stale = readCache(stateCode, true);
                if (stale != null && !stale.isEmpty()) {
                    Log.d(TAG, "using a stale county list for " + stateCode);
                    cb.onCounties(stale);
                    return;
                }
                cb.onFailure(error);
            }
        });
    }

    /** True when this state's list is already on disk, so the picker opens instantly. */
    public boolean isCached(String stateCode) {
        final List<County> c = readCache(stateCode);
        return c != null && !c.isEmpty();
    }

    private List<County> parse(byte[] body) {
        final List<County> out = new ArrayList<>();
        try {
            final JSONObject doc = new JSONObject(
                    new String(body, FileSystemUtils.UTF8_CHARSET));
            final JSONArray feats = doc.optJSONArray("features");
            if (feats == null)
                return out;
            for (int i = 0; i < feats.length(); i++) {
                final JSONObject p = feats.optJSONObject(i) == null ? null
                        : feats.optJSONObject(i).optJSONObject("properties");
                if (p == null)
                    continue;
                final String id = p.optString("id", "");
                final String name = p.optString("name", "");
                final String same = Areas.sameForCountyZone(id);
                // A county we cannot turn into a SAME code could be listed but never
                // matched, so it is left out rather than offered as a dead control.
                if (same != null && !name.isEmpty())
                    out.add(new County(name, same));
            }
        } catch (Exception e) {
            Log.w(TAG, "could not read the county list", e);
        }
        Collections.sort(out, new Comparator<County>() {
            @Override
            public int compare(County a, County b) {
                return a.name.compareToIgnoreCase(b.name);
            }
        });
        return out;
    }

    private File cacheFile(String stateCode) {
        if (stateCode == null || !Areas.isKnown(stateCode))
            return null;
        return new File(dir, stateCode.toUpperCase(java.util.Locale.US) + ".json");
    }

    private List<County> readCache(String stateCode) {
        return readCache(stateCode, false);
    }

    private List<County> readCache(String stateCode, boolean allowStale) {
        final File f = cacheFile(stateCode);
        if (f == null || !f.isFile())
            return null;
        if (!allowStale && System.currentTimeMillis() - f.lastModified() > TTL_MS)
            return null;
        try (InputStream in = new FileInputStream(f)) {
            return parse(FileSystemUtils.read(in));
        } catch (Exception e) {
            Log.w(TAG, "unreadable county cache for " + stateCode, e);
            return null;
        }
    }

    private void writeCache(String stateCode, byte[] body) {
        final File f = cacheFile(stateCode);
        if (f == null)
            return;
        final File tmp = new File(f.getParentFile(), f.getName() + ".tmp");
        try (OutputStream out = new FileOutputStream(tmp)) {
            out.write(body);
        } catch (Exception e) {
            Log.w(TAG, "could not write the county cache for " + stateCode, e);
            return;
        }
        if (!tmp.renameTo(f) && !tmp.delete())
            Log.w(TAG, "could not clean up " + tmp);
    }
}
