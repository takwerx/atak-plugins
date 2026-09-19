package com.atakmap.android.mast;

import android.content.Context;
import android.graphics.Color;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * A mission is data: one JSON file under {@code assets/missions/}, schema version 1,
 * read at plugin start (PLAN-MAST §4.4). A mission is a name, an icon, whether its
 * markers carry a floor, and its Tactical tiles. Nothing in it is an Android resource
 * id; icons are drawable names looked up at build time so a CloudTAK counterpart could
 * read the same file.
 *
 * <p>The parser refuses what it does not know and names the file, so a typo in a
 * mission is a failed load with a reason, never a tile that silently does nothing.
 */
public final class Mission {

    public static final int SCHEMA_VERSION = 1;

    public enum Kind { ATAK, SHAPE, MARKER, PLUGIN }

    /** One tile of the Tactical grid. Which fields are set depends on the kind. */
    public static final class Tile {
        public final int index;
        public final String label;
        public final String icon;
        public final Kind kind;
        // ATAK screen
        public final String action;
        public final Map<String, Boolean> prefs;
        public final String ask;
        public final String askTitle;
        // Shape
        public final String title;
        public final int color;
        public final int fill;
        public final boolean closed;
        public final String geofenceTrigger;
        public final String geofenceMonitor;
        // Marker
        public final String type;
        public final boolean count;
        public final String text;
        // Plugin
        public final String pkg;
        public final String pluginName;

        Tile(int index, JSONObject o, String file) throws Exception {
            this.index = index;
            label = need(o, "label", file);
            icon = need(o, "icon", file);
            String k = need(o, "kind", file);
            Kind kk;
            try {
                kk = Kind.valueOf(k.toUpperCase());
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException(file + ": tile '" + label
                        + "' has unknown kind '" + k + "'");
            }
            kind = kk;
            action = o.optString("action", null);
            ask = o.optString("ask", null);
            askTitle = o.optString("askTitle", label);
            Map<String, Boolean> p = new HashMap<String, Boolean>();
            JSONObject po = o.optJSONObject("prefs");
            if (po != null) {
                Iterator<String> it = po.keys();
                while (it.hasNext()) {
                    String key = it.next();
                    p.put(key, po.getBoolean(key));
                }
            }
            prefs = Collections.unmodifiableMap(p);
            title = o.optString("title", label);
            color = o.has("color") ? Color.parseColor(o.getString("color")) : Color.WHITE;
            fill = o.has("fill") ? Color.parseColor(o.getString("fill")) : Color.argb(64, 255, 255, 255);
            closed = o.optBoolean("closed", true);
            JSONObject gf = o.optJSONObject("geofence");
            geofenceTrigger = gf == null ? null : gf.optString("trigger", "Both");
            geofenceMonitor = gf == null ? null : gf.optString("monitor", "All");
            type = o.optString("type", null);
            count = o.optBoolean("count", false);
            text = o.optString("text", null);
            pkg = o.optString("package", null);
            pluginName = o.optString("name", label);
            switch (kind) {
                case ATAK:
                    if (action == null)
                        throw new IllegalArgumentException(file + ": tile '" + label + "' needs an action");
                    break;
                case MARKER:
                    if (type == null)
                        throw new IllegalArgumentException(file + ": tile '" + label + "' needs a type");
                    break;
                case PLUGIN:
                    if (pkg == null)
                        throw new IllegalArgumentException(file + ": tile '" + label + "' needs a package");
                    break;
                default:
                    break;
            }
        }

        public boolean hasGeofence() {
            return geofenceTrigger != null;
        }
    }

    public final String id;
    public final String name;
    public final String icon;
    public final boolean floors;
    public final List<Tile> tiles;

    private Mission(String id, String name, String icon, boolean floors, List<Tile> tiles) {
        this.id = id;
        this.name = name;
        this.icon = icon;
        this.floors = floors;
        this.tiles = Collections.unmodifiableList(tiles);
    }

    /** Read one mission from the plugin's assets; throws with the file named on any defect. */
    public static Mission load(Context plugin, String assetPath) throws Exception {
        InputStream in = plugin.getAssets().open(assetPath);
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        byte[] b = new byte[4096];
        int n;
        while ((n = in.read(b)) > 0)
            buf.write(b, 0, n);
        in.close();
        JSONObject root = new JSONObject(buf.toString("UTF-8"));
        int v = root.optInt("schemaVersion", -1);
        if (v != SCHEMA_VERSION)
            throw new IllegalArgumentException(assetPath + ": schemaVersion " + v
                    + " (this build reads " + SCHEMA_VERSION + ")");
        String id = need(root, "id", assetPath);
        String name = need(root, "name", assetPath);
        String icon = need(root, "icon", assetPath);
        JSONArray arr = root.optJSONArray("tiles");
        if (arr == null || arr.length() == 0)
            throw new IllegalArgumentException(assetPath + ": no tiles");
        List<Tile> tiles = new ArrayList<Tile>();
        for (int i = 0; i < arr.length(); i++)
            tiles.add(new Tile(i, arr.getJSONObject(i), assetPath));
        return new Mission(id, name, icon, root.optBoolean("floors", false), tiles);
    }

    private static String need(JSONObject o, String key, String file) throws Exception {
        String v = o.optString(key, null);
        if (v == null || v.length() == 0)
            throw new IllegalArgumentException(file + ": missing '" + key + "'");
        return v;
    }
}
