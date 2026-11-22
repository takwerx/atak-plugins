package com.atakmap.android.mast;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The missions MAST offers and the plugins each one opens, read from
 * {@code assets/missions.json} at plugin start. A mission is data, not code, so the
 * operator's edits to a list, and later an agency's own mission (with its own layer
 * tags), are a file change (PLAN-MAST-v0.1, direction change 2026-11-22). An
 * optional {@code tools} list names ATAK's own tools shown above the plugins.
 */
public final class Missions {

    /** One plugin tile on a mission page. */
    public static final class Plugin {
        public final String name;
        /** The plugin's package, which is also its toolbar item's identifier. */
        public final String pkg;

        Plugin(String name, String pkg) {
            this.name = name;
            this.pkg = pkg;
        }
    }

    public static final class Mission {
        public final String id;
        public final String name;
        public final List<Plugin> plugins;
        /**
         * ATAK's own tools for this mission, each named by its item file in atak.apk
         * ({@code assets/actionbar/items/redx.xml}); see {@link AtakTools}.
         */
        public final List<String> tools;

        Mission(String id, String name, List<Plugin> plugins, List<String> tools) {
            this.id = id;
            this.name = name;
            this.plugins = Collections.unmodifiableList(plugins);
            this.tools = Collections.unmodifiableList(tools);
        }
    }

    private Missions() {
    }

    /** Reads the file; throws with the file named when it is not schema 2. */
    public static List<Mission> load(Context plugin, String asset) throws Exception {
        final JSONObject root = new JSONObject(read(plugin, asset));
        if (root.optInt("schema") != 2)
            throw new IllegalArgumentException(asset + ": schema " + root.optInt("schema")
                    + ", MAST reads schema 2");
        final JSONArray ms = root.getJSONArray("missions");
        final List<Mission> out = new ArrayList<>();
        for (int i = 0; i < ms.length(); i++) {
            final JSONObject m = ms.getJSONObject(i);
            final JSONArray ps = m.getJSONArray("plugins");
            final List<Plugin> plugins = new ArrayList<>();
            for (int j = 0; j < ps.length(); j++) {
                final JSONObject p = ps.getJSONObject(j);
                plugins.add(new Plugin(p.getString("name"), p.getString("pkg")));
            }
            final JSONArray ts = m.optJSONArray("tools");
            final List<String> tools = new ArrayList<>();
            if (ts != null)
                for (int j = 0; j < ts.length(); j++)
                    tools.add(ts.getString(j));
            out.add(new Mission(m.getString("id"), m.getString("name"), plugins, tools));
        }
        return Collections.unmodifiableList(out);
    }

    private static String read(Context plugin, String asset) throws Exception {
        try (InputStream in = plugin.getAssets().open(asset)) {
            final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            final byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0)
                bytes.write(buf, 0, n);
            return bytes.toString("UTF-8");
        }
    }
}
