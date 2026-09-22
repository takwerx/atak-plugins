package com.atakmap.android.atmosphere.source;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.ArrayList;

/**
 * A weather source, described entirely by data.
 *
 * <p>This is the core bet of the plugin: an API is a JSON file, not a Java class. Adding
 * a forecast provider — or replacing one an agency has stopped trusting — is a file the
 * operator drops on the device, not a plugin release. See {@code docs/SOURCES.md} for the
 * schema and {@code assets/wx_sources/} for the bundled examples.
 *
 * <p>Two response layouts cover the public APIs we have met:
 * <ul>
 *   <li>{@link Layout#COLUMNS} — parallel arrays, one per variable (Open-Meteo)</li>
 *   <li>{@link Layout#RECORDS} — an array of period objects (NWS)</li>
 * </ul>
 */
public final class WxSourceDef {

    public enum Layout {
        COLUMNS,
        RECORDS
    }

    /** Where the definition came from — shown in the UI so overrides are never a mystery. */
    public enum Origin {
        BUNDLED,
        EXTERNAL
    }

    public final int schemaVersion;
    public final String id;
    public final String displayName;
    public final String description;
    /** Attribution the licence requires; the UI shows it whenever the source is used. */
    public final String attribution;
    public final String termsUrl;

    /**
     * Optional first request that yields the real data URL. NWS needs it: a point lookup
     * returns the gridpoint forecast URL. Null when the data URL is directly templatable.
     */
    public final String resolveUrl;
    /** Path in the resolve response holding the data URL. */
    public final String resolvePath;
    /**
     * Paths in the resolve response that name the place, joined with ", " for the
     * status line: NWS carries the nearest city and state beside the gridpoint URL.
     * Empty when the source names none.
     */
    public final List<String> placePaths;

    /** Data request URL template. Placeholders: {lat} {lon} {group:NAME} {apiKey}. */
    public final String requestUrl;
    /** Extra request headers, e.g. {@code Accept}. Never credentials. */
    public final Map<String, String> headers;

    public final boolean requiresApiKey;

    public final Layout layout;
    /** For RECORDS: path to the array of period objects. */
    public final String recordsPath;
    /** Time field: absolute path (COLUMNS) or relative field name (RECORDS). */
    public final String timePath;
    /** Absolute path to the time of the "current" block, if the source has one. */
    public final String currentTimePath;

    public final List<WxParam> params;

    public final Origin origin;
    /** File the definition was read from, for error messages. */
    public final String originFile;

    WxSourceDef(int schemaVersion, String id, String displayName, String description,
            String attribution, String termsUrl, String resolveUrl, String resolvePath,
            List<String> placePaths,
            String requestUrl, Map<String, String> headers, boolean requiresApiKey,
            Layout layout, String recordsPath, String timePath, String currentTimePath,
            List<WxParam> params, Origin origin, String originFile) {
        this.schemaVersion = schemaVersion;
        this.id = id;
        this.displayName = displayName;
        this.description = description;
        this.attribution = attribution;
        this.termsUrl = termsUrl;
        this.resolveUrl = resolveUrl;
        this.resolvePath = resolvePath;
        this.placePaths = Collections.unmodifiableList(new ArrayList<>(placePaths));
        this.requestUrl = requestUrl;
        this.headers = Collections.unmodifiableMap(new LinkedHashMap<>(headers));
        this.requiresApiKey = requiresApiKey;
        this.layout = layout;
        this.recordsPath = recordsPath;
        this.timePath = timePath;
        this.currentTimePath = currentTimePath;
        this.params = Collections.unmodifiableList(params);
        this.origin = origin;
        this.originFile = originFile;
    }

    public boolean hasResolveStep() {
        return resolveUrl != null && resolvePath != null;
    }

    public boolean hasCurrent() {
        for (WxParam p : params) {
            if (p.currentPath != null)
                return true;
        }
        return false;
    }

    public WxParam param(String key) {
        for (WxParam p : params) {
            if (p.key.equals(key))
                return p;
        }
        return null;
    }

    /** Every host this source can contact — what the UI shows before enabling it. */
    public List<String> hosts() {
        final java.util.ArrayList<String> out = new java.util.ArrayList<>(2);
        addHost(out, resolveUrl);
        addHost(out, requestUrl);
        return out;
    }

    private static void addHost(List<String> out, String url) {
        if (url == null)
            return;
        try {
            final String host = new java.net.URI(url.replace("{", "%7B").replace("}", "%7D"))
                    .getHost();
            if (host != null && !out.contains(host))
                out.add(host);
        } catch (java.net.URISyntaxException ignored) {
            // A malformed URL never reaches here: the parser rejects it.
        }
    }

    @Override
    public String toString() {
        return id + " [" + origin + " " + originFile + "]";
    }
}
