package com.atakmap.android.airaware;

import com.atakmap.coremap.filesystem.FileSystemUtils;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * NOTAMs as tiles the takwerx relay publishes, every three minutes.
 *
 * <p>The same {@link TilePack} as the airspace and obstacles, with one difference that
 * matters: those packs change on a 56-day cycle and a tile read once is kept for good,
 * while this one changes all day. So the manifest is re-read after five minutes rather
 * than a day, and it carries a hash per tile: a tile whose hash changed is fetched
 * again when the view next needs it, and one that did not is read from disk. Nothing
 * is ever cleared first, so with no network the last picture stays up.
 */
public final class NotamTiles {

    static final String BASE = "https://mapdepot.takwerx.org/airaware/notams/";

    /** How long the manifest on disk is trusted before it is asked for again. */
    static final long MANIFEST_AGE_MS = 5L * 60L * 1000L;

    private NotamTiles() {
    }

    public static TilePack pack() {
        return new TilePack(BASE, "notams.json",
                new File(FileSystemUtils.getItem("tools/airaware"), "notams"),
                MANIFEST_AGE_MS);
    }

    /** Every NOTAM in one tile. Worker thread only. */
    public static List<Notam> tile(TilePack pack, String key, String sha) throws IOException {
        final JSONObject root = pack.tile(key, sha);
        final List<Notam> out = new ArrayList<>();
        final JSONArray arr = root.optJSONArray("notams");
        if (arr == null)
            return out;
        for (int i = 0; i < arr.length(); i++) {
            final Notam n = Notam.from(arr.optJSONObject(i));
            if (n != null)
                out.add(n);
        }
        return out;
    }
}
