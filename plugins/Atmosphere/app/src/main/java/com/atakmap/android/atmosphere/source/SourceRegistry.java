package com.atakmap.android.atmosphere.source;

import android.content.Context;

import com.atakmap.coremap.log.Log;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Every source the plugin knows about, loaded from two places:
 *
 * <ol>
 *   <li>{@code assets/wx_sources/*.json} inside the APK — the sources we ship</li>
 *   <li>{@code /sdcard/atak/Atmosphere/sources/*.json} — what the operator drops on the
 *       device, read alphabetically</li>
 * </ol>
 *
 * <p>Later wins on a duplicate {@code sourceId}, so an external file with the same id as
 * a bundled one <b>replaces</b> it, as long as it keeps the bundled one's servers. That
 * is how a unit fixes a provider's URL change in the field without waiting for a
 * release. One that names other servers is refused and reported (see accept).
 *
 * <p>Nothing here touches the network. Loading a definition does not make a request —
 * see {@code EgressPolicy}.
 */
public final class SourceRegistry {

    private static final String TAG = "WxSourceRegistry";

    /** Where the operator drops their own source files. */
    public static final String EXTERNAL_DIR = "atak/Atmosphere/sources";

    private static final String ASSET_DIR = "wx_sources";

    private final Map<String, WxSourceDef> byId = new LinkedHashMap<>();
    private final List<String> problems = new ArrayList<>();

    /**
     * @param pluginContext the plugin's own context — the only one whose assets contain
     *                      the bundled definitions
     * @param externalDir   directory of operator-supplied definitions; may not exist
     */
    public SourceRegistry(Context pluginContext, File externalDir) {
        loadAssets(pluginContext);
        loadExternal(externalDir);
        Log.d(TAG, "loaded " + byId.size() + " source(s), " + problems.size() + " problem(s)");
    }

    /** Definitions in load order, external overrides already applied. */
    public List<WxSourceDef> sources() {
        return Collections.unmodifiableList(new ArrayList<>(byId.values()));
    }

    public WxSourceDef byId(String id) {
        return byId.get(id);
    }

    /**
     * Errors and warnings from this load, ready to show. Empty means every file parsed.
     * These are surfaced in the UI rather than only logged — a source file that silently
     * does nothing is the single most confusing failure this design can produce.
     */
    public List<String> problems() {
        return Collections.unmodifiableList(problems);
    }

    private void loadAssets(Context pluginContext) {
        if (pluginContext == null)
            return;
        String[] names;
        try {
            names = pluginContext.getAssets().list(ASSET_DIR);
        } catch (IOException e) {
            Log.w(TAG, "cannot list bundled sources", e);
            return;
        }
        if (names == null)
            return;
        Arrays.sort(names);
        for (String name : names) {
            if (!name.endsWith(".json"))
                continue;
            InputStream in = null;
            try {
                in = pluginContext.getAssets().open(ASSET_DIR + "/" + name);
                accept(read(in), WxSourceDef.Origin.BUNDLED, name);
            } catch (IOException e) {
                problems.add(name + ": could not read bundled source (" + e.getMessage() + ")");
            } finally {
                close(in);
            }
        }
    }

    private void loadExternal(File dir) {
        if (dir == null || !dir.isDirectory())
            return;
        final File[] files = dir.listFiles();
        if (files == null)
            return;
        Arrays.sort(files);
        for (File f : files) {
            final String name = f.getName();
            if (!name.endsWith(".json"))
                continue;
            if (f.length() > 512 * 1024) {
                // A source definition is a few KB. Anything this large is not one, and
                // reading it would block the caller for no reason.
                problems.add(name + ": too large to be a source definition ("
                        + (f.length() / 1024) + " KB)");
                continue;
            }
            InputStream in = null;
            try {
                in = new FileInputStream(f);
                accept(read(in), WxSourceDef.Origin.EXTERNAL, name);
            } catch (IOException e) {
                problems.add(name + ": could not read (" + e.getMessage() + ")");
            } finally {
                close(in);
            }
        }
    }

    private void accept(String json, WxSourceDef.Origin origin, String file) {
        final WxSourceParser.Result result = WxSourceParser.parse(json, origin, file);
        problems.addAll(result.errors);
        problems.addAll(result.warnings);
        if (!result.ok())
            return;

        final WxSourceDef previous = byId.get(result.def.id);
        // A file may fix a built-in service's URLs on the same servers, the field fix
        // this override exists for. It may not move one to another server: picking a
        // service is the only consent the forecast asks for now, and NWS is picked on
        // a new install, so a dropped file named "nws" with its own host would have
        // received every first-run request and shown its numbers as the NWS forecast
        // (security review, 2026-09-28). Given its own sourceId it is offered as a
        // separate, marked choice, used only when someone picks it.
        if (previous != null && previous.origin == WxSourceDef.Origin.BUNDLED
                && !previous.hostsKey().equals(result.def.hostsKey())) {
            final StringBuilder hosts = new StringBuilder();
            for (String h : result.def.hosts())
                hosts.append(hosts.length() == 0 ? "" : ", ").append(h);
            problems.add(file + " uses the id of the built-in " + previous.displayName
                    + " but other servers (" + hosts + "), so it is ignored. Give it its"
                    + " own sourceId to offer it as a separate choice in Forecast settings.");
            return;
        }
        if (previous != null) {
            Log.d(TAG, result.def.id + ": " + file + " overrides " + previous.originFile);
            // Keep insertion order stable so the source list does not jump around when a
            // file overrides a bundled definition.
            byId.put(result.def.id, result.def);
        } else {
            byId.put(result.def.id, result.def);
        }
    }

    private static String read(InputStream in) throws IOException {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        final byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0)
            out.write(buf, 0, n);
        return out.toString("UTF-8");
    }

    private static void close(InputStream in) {
        if (in == null)
            return;
        try {
            in.close();
        } catch (IOException ignored) {
            // Nothing useful to do; the read either succeeded or already reported.
        }
    }
}
