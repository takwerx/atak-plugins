package com.atakmap.android.airaware;

import com.atakmap.coremap.filesystem.FileSystemUtils;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * The UAS Facility Map as tiles we publish.
 *
 * <p>371,352 cells nationally, and the whole country is 1.1 MB because a cell is three
 * integers rather than a polygon. The airports are a per-tile table the cells point
 * into, since the same field names the cells around it hundreds of times over.
 */
public final class UasfmTiles {

    static final String BASE = "https://mapdepot.takwerx.org/airaware/uasfm/";

    private UasfmTiles() {
    }

    public static TilePack pack() {
        return new TilePack(BASE, "uasfm.json",
                new File(FileSystemUtils.getItem("tools/airaware"), "uasfm"));
    }

    /** Every cell in one tile. Worker thread only. */
    public static List<UasfmCell> tile(TilePack pack, String key) throws IOException {
        final JSONObject root = pack.tile(key);
        final List<UasfmCell> out = new ArrayList<>();
        final JSONArray airports = root.optJSONArray("airports");
        final JSONArray cells = root.optJSONArray("cells");
        if (cells == null)
            return out;

        final int n = airports == null ? 0 : airports.length();
        final String[] ids = new String[n];
        final String[] names = new String[n];
        final boolean[] laanc = new boolean[n];
        for (int i = 0; i < n; i++) {
            // "ONT|Ontario Intl|1" -- split on the bar, because a field can be empty and
            // a name can hold anything else.
            final String[] parts = airports.optString(i, "").split("\\|", -1);
            ids[i] = parts.length > 0 ? parts[0] : "";
            names[i] = parts.length > 1 ? parts[1] : "";
            laanc[i] = parts.length > 2 && "1".equals(parts[2]);
        }

        for (int i = 0; i < cells.length(); i++) {
            final JSONArray c = cells.optJSONArray(i);
            if (c == null || c.length() < 3)
                continue;
            final int apt = c.length() > 3 ? c.optInt(3, -1) : -1;
            out.add(new UasfmCell(c.optInt(0), c.optInt(1), c.optInt(2),
                    apt >= 0 && apt < n ? ids[apt] : "",
                    apt >= 0 && apt < n ? names[apt] : "",
                    apt >= 0 && apt < n && laanc[apt]));
        }
        return out;
    }
}
