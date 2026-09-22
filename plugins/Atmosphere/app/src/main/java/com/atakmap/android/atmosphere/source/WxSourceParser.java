package com.atakmap.android.atmosphere.source;

import com.atakmap.android.atmosphere.units.Quantity;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns a source-definition file into a {@link WxSourceDef}, or into a list of reasons
 * it could not be used.
 *
 * <p>A definition that fails validation is <b>never</b> loaded silently. The operator
 * dropped a file on the device expecting a new source; if it is wrong they have to be
 * told which file, which field and what was expected — otherwise the plugin looks broken
 * rather than the file.
 */
public final class WxSourceParser {

    /** The only schema version this build understands. */
    public static final int SCHEMA_VERSION = 1;

    private static final Pattern ID = Pattern.compile("[a-z0-9][a-z0-9-]{0,63}");
    // Both braces escaped on purpose. OpenJDK's regex takes a bare closing brace as a
    // literal, so the unit tests passed; Android's ICU engine refuses it with
    // "Syntax error in regexp pattern", the static initializer threw, and ATAK died
    // the first time the pane opened on a phone (XCover, 2026-09-21).
    // RegexPortabilityTest scans the source so this cannot come back quietly.
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{([^}]*)\\}");

    private WxSourceParser() {
    }

    /** Outcome of parsing one file: a usable definition, or the errors that stopped it. */
    public static final class Result {
        public final WxSourceDef def;
        public final List<String> errors;
        public final List<String> warnings;

        Result(WxSourceDef def, List<String> errors, List<String> warnings) {
            this.def = def;
            this.errors = Collections.unmodifiableList(errors);
            this.warnings = Collections.unmodifiableList(warnings);
        }

        public boolean ok() {
            return def != null;
        }
    }

    public static Result parse(String json, WxSourceDef.Origin origin, String file) {
        final List<String> errors = new ArrayList<>();
        final List<String> warnings = new ArrayList<>();

        JSONObject root;
        try {
            root = new JSONObject(json);
        } catch (JSONException e) {
            errors.add(file + ": not valid JSON (" + e.getMessage() + ")");
            return new Result(null, errors, warnings);
        }

        final int schemaVersion = root.optInt("schemaVersion", 0);
        if (schemaVersion != SCHEMA_VERSION) {
            errors.add(file + ": schemaVersion is " + (schemaVersion == 0 ? "missing"
                    : String.valueOf(schemaVersion)) + "; this build reads version "
                    + SCHEMA_VERSION);
            return new Result(null, errors, warnings);
        }

        final String id = str(root, "sourceId");
        if (id == null)
            errors.add(file + ": sourceId is required");
        else if (!ID.matcher(id).matches())
            errors.add(file + ": sourceId \"" + id
                    + "\" must be lowercase letters, digits and hyphens");

        final String displayName = str(root, "displayName");
        if (displayName == null)
            errors.add(file + ": displayName is required");

        final String requestUrl = str(root, "requestUrl");
        final String resolveUrl = str(root, "resolveUrl");
        final String resolvePath = str(root, "resolvePath");

        // A two-step source has no request template of its own: the provider hands back
        // the data URL. Requiring a placeholder one would only invite a wrong guess.
        if (requestUrl == null && resolveUrl == null)
            errors.add(file + ": requestUrl is required (or resolveUrl for a "
                    + "provider that returns its own data URL)");
        else if (requestUrl != null)
            checkUrl(file, "requestUrl", requestUrl, errors);

        if (resolveUrl != null) {
            checkUrl(file, "resolveUrl", resolveUrl, errors);
            if (resolvePath == null)
                errors.add(file + ": resolveUrl needs resolvePath (where the data URL "
                        + "is in the resolve response)");
        } else if (resolvePath != null) {
            warnings.add(file + ": resolvePath is set but resolveUrl is not; ignored");
        }

        // Where the resolve response names the place; each entry a dotted path.
        final List<String> placePaths = new ArrayList<>();
        final JSONArray placeArray = root.optJSONArray("placePaths");
        if (placeArray != null) {
            for (int i = 0; i < placeArray.length(); i++) {
                final String pp = placeArray.optString(i, null);
                if (pp == null || pp.trim().isEmpty())
                    errors.add(file + ": placePaths[" + i + "] must be a non-empty path");
                else
                    placePaths.add(pp.trim());
            }
            if (resolveUrl == null)
                warnings.add(file + ": placePaths is set but resolveUrl is not; the place "
                        + "is read from the resolve response, so it is ignored");
        } else if (root.has("placePaths")) {
            errors.add(file + ": placePaths must be an array of paths");
        }

        WxSourceDef.Layout layout = WxSourceDef.Layout.COLUMNS;
        final String layoutName = str(root, "layout");
        if (layoutName != null) {
            if (layoutName.equalsIgnoreCase("records"))
                layout = WxSourceDef.Layout.RECORDS;
            else if (!layoutName.equalsIgnoreCase("columns"))
                errors.add(file + ": layout \"" + layoutName
                        + "\" is not \"columns\" or \"records\"");
        }

        final String recordsPath = str(root, "recordsPath");
        if (layout == WxSourceDef.Layout.RECORDS && recordsPath == null)
            errors.add(file + ": layout \"records\" needs recordsPath");

        final Map<String, String> headers = new LinkedHashMap<>();
        final JSONObject headerObj = root.optJSONObject("headers");
        if (headerObj != null) {
            final java.util.Iterator<String> keys = headerObj.keys();
            while (keys.hasNext()) {
                final String k = keys.next();
                if (k.equalsIgnoreCase("user-agent")) {
                    // The User-Agent identifies this plugin to the provider and is the
                    // thing several public APIs block on. It is set centrally, not per file.
                    warnings.add(file + ": User-Agent header ignored; the plugin sets it");
                    continue;
                }
                headers.put(k, String.valueOf(headerObj.opt(k)));
            }
        }

        final List<WxParam> params = new ArrayList<>();
        final JSONArray paramArray = root.optJSONArray("parameters");
        if (paramArray == null || paramArray.length() == 0) {
            errors.add(file + ": parameters must list at least one variable");
        } else {
            for (int i = 0; i < paramArray.length(); i++) {
                final JSONObject p = paramArray.optJSONObject(i);
                if (p == null) {
                    errors.add(file + ": parameters[" + i + "] is not an object");
                    continue;
                }
                final WxParam parsed = parseParam(file, i, p, layout, errors, warnings);
                if (parsed != null)
                    params.add(parsed);
            }
        }

        if (requestUrl != null)
            checkPlaceholders(file, requestUrl, params, errors, warnings);

        final boolean requiresApiKey = root.optBoolean("requiresApiKey", false);
        if (requiresApiKey && requestUrl != null && !requestUrl.contains("{apiKey}"))
            warnings.add(file + ": requiresApiKey is true but requestUrl has no {apiKey}");

        if (!errors.isEmpty())
            return new Result(null, errors, warnings);

        final String attribution = str(root, "attribution");
        if (attribution == null)
            warnings.add(file + ": no attribution; most free weather APIs require one");

        final WxSourceDef def = new WxSourceDef(
                schemaVersion, id, displayName, str(root, "description"),
                attribution, str(root, "termsUrl"), resolveUrl, resolvePath, placePaths,
                requestUrl, headers, requiresApiKey, layout, recordsPath,
                str(root, "timePath"), str(root, "currentTimePath"),
                params, origin, file);
        return new Result(def, errors, warnings);
    }

    private static WxParam parseParam(String file, int index, JSONObject p,
            WxSourceDef.Layout layout, List<String> errors, List<String> warnings) {

        final String where = file + ": parameters[" + index + "]";

        final String key = str(p, "key");
        if (key == null) {
            errors.add(where + " needs a key");
            return null;
        }

        final String label = str(p, "label");
        if (label == null) {
            errors.add(where + " (" + key + ") needs a label");
            return null;
        }

        final String currentPath = str(p, "currentPath");
        final String seriesPath = str(p, "seriesPath");
        if (currentPath == null && seriesPath == null) {
            errors.add(where + " (" + key + ") needs currentPath, seriesPath or both");
            return null;
        }

        final String quantityName = str(p, "quantity");
        if (quantityName == null)
            warnings.add(where + " (" + key + ") has no quantity; treated as a plain number");

        final List<String> groups = new ArrayList<>(2);
        final JSONArray groupArray = p.optJSONArray("requestGroups");
        if (groupArray != null) {
            for (int i = 0; i < groupArray.length(); i++) {
                final String g = groupArray.optString(i, null);
                if (g != null && !g.trim().isEmpty())
                    groups.add(g.trim());
            }
        }

        final String parse = str(p, "parse");
        if (parse != null && !parse.equals("number") && !parse.equals("leadingNumber")
                && !parse.equals("compass") && !parse.equals("lookup")) {
            errors.add(where + " (" + key + ") parse \"" + parse
                    + "\" is not number, leadingNumber, compass or lookup");
            return null;
        }

        String[] lookupKeys = null;
        double[] lookupValues = null;
        if ("lookup".equals(parse)) {
            final JSONArray table = p.optJSONArray("lookup");
            if (table == null || table.length() == 0) {
                errors.add(where + " (" + key + ") parse \"lookup\" needs a non-empty "
                        + "\"lookup\" array of [\"substring\", number] pairs");
                return null;
            }
            lookupKeys = new String[table.length()];
            lookupValues = new double[table.length()];
            for (int i = 0; i < table.length(); i++) {
                final JSONArray pair = table.optJSONArray(i);
                final String needle = pair == null ? null : pair.optString(0, null);
                final double value = pair == null ? Double.NaN : pair.optDouble(1, Double.NaN);
                if (needle == null || needle.trim().isEmpty() || Double.isNaN(value)) {
                    errors.add(where + " (" + key + ") lookup[" + i
                            + "] must be [\"substring\", number]");
                    return null;
                }
                lookupKeys[i] = needle.trim();
                lookupValues[i] = value;
            }
        } else if (p.has("lookup")) {
            warnings.add(where + " (" + key + ") has a lookup table but parse is not "
                    + "\"lookup\"; the table is ignored");
        }

        if (layout == WxSourceDef.Layout.RECORDS && seriesPath != null
                && seriesPath.contains("[")) {
            warnings.add(where + " (" + key + ") seriesPath is relative to each record "
                    + "in a records layout; an index is probably a mistake");
        }

        return new WxParam(key, label, Quantity.fromName(quantityName), str(p, "unit"),
                str(p, "unitPath"), p.optBoolean("defaultOn", false),
                groups.toArray(new String[0]), currentPath, seriesPath, parse,
                lookupKeys, lookupValues);
    }

    /**
     * Every {@code {placeholder}} in a URL must be one the request builder fills in.
     * An unrecognized one would otherwise reach the provider verbatim and return a 400
     * that looks like a network fault.
     */
    private static void checkPlaceholders(String file, String url, List<WxParam> params,
            List<String> errors, List<String> warnings) {

        final Matcher m = PLACEHOLDER.matcher(url);
        boolean hasLat = false;
        boolean hasLon = false;
        while (m.find()) {
            final String name = m.group(1);
            if (name.equals("lat")) {
                hasLat = true;
            } else if (name.equals("lon")) {
                hasLon = true;
            } else if (name.equals("apiKey")) {
                continue;
            } else if (name.startsWith("group:")) {
                final String group = name.substring("group:".length());
                boolean used = false;
                for (WxParam p : params) {
                    if (p.inGroup(group)) {
                        used = true;
                        break;
                    }
                }
                if (!used)
                    warnings.add(file + ": {group:" + group
                            + "} in requestUrl but no parameter lists that request group");
            } else {
                errors.add(file + ": unknown placeholder {" + name + "} in requestUrl "
                        + "(supported: {lat} {lon} {apiKey} {group:NAME})");
            }
        }
        if (!hasLat || !hasLon)
            warnings.add(file + ": requestUrl has no {lat}/{lon}; every request will "
                    + "return the same place");
    }

    private static void checkUrl(String file, String field, String url,
            List<String> errors) {
        if (!url.startsWith("https://")) {
            // Plaintext leaks the operator's position to anyone on the path. There is no
            // free weather API worth that, so this is a hard rejection, not a warning.
            errors.add(file + ": " + field + " must be https (got \""
                    + url.split("://")[0] + "\")");
        }
    }

    private static String str(JSONObject o, String key) {
        final String v = o.optString(key, null);
        if (v == null)
            return null;
        final String t = v.trim();
        return t.isEmpty() ? null : t;
    }
}
