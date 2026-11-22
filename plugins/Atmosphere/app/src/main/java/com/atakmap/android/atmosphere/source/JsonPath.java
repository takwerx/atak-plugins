package com.atakmap.android.atmosphere.source;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Dotted-path lookup into a parsed JSON response — {@code "properties.periods[0].temperature"}.
 *
 * <p>This is what lets a source definition describe where a value lives instead of
 * requiring a hand-written response class per API. Missing keys, wrong types and
 * out-of-range indices all return null rather than throwing: a source that changes
 * shape should degrade to "no reading", never to a crash inside ATAK.
 */
public final class JsonPath {

    private JsonPath() {
    }

    /** @return the object at {@code path}, or null if any step is missing. */
    public static Object get(Object root, String path) {
        if (root == null || path == null || path.trim().isEmpty())
            return null;

        Object current = root;
        for (String rawStep : path.trim().split("\\.")) {
            if (current == null)
                return null;
            String step = rawStep;

            // Split "periods[0][1]" into the key and its trailing indices.
            int bracket = step.indexOf('[');
            String key = bracket < 0 ? step : step.substring(0, bracket);
            if (!key.isEmpty()) {
                if (!(current instanceof JSONObject))
                    return null;
                current = ((JSONObject) current).opt(key);
            }
            if (bracket >= 0) {
                String indices = step.substring(bracket);
                int i = 0;
                while (i < indices.length()) {
                    int open = indices.indexOf('[', i);
                    int close = open < 0 ? -1 : indices.indexOf(']', open);
                    if (open < 0 || close < 0)
                        return null;
                    int index;
                    try {
                        index = Integer.parseInt(indices.substring(open + 1, close).trim());
                    } catch (NumberFormatException e) {
                        return null;
                    }
                    if (!(current instanceof JSONArray))
                        return null;
                    JSONArray array = (JSONArray) current;
                    if (index < 0 || index >= array.length())
                        return null;
                    current = array.opt(index);
                    i = close + 1;
                }
            }
        }
        return current == JSONObject.NULL ? null : current;
    }

    /** @return the array at {@code path}, or null. */
    public static JSONArray getArray(Object root, String path) {
        Object o = get(root, path);
        return o instanceof JSONArray ? (JSONArray) o : null;
    }

    /** @return the object at {@code path}, or null. */
    public static JSONObject getObject(Object root, String path) {
        Object o = get(root, path);
        return o instanceof JSONObject ? (JSONObject) o : null;
    }

    /** @return the string at {@code path}, or null. Numbers are stringified. */
    public static String getString(Object root, String path) {
        Object o = get(root, path);
        if (o == null || o instanceof JSONObject || o instanceof JSONArray)
            return null;
        return String.valueOf(o);
    }

    /**
     * @return the number at {@code path}, or {@link Double#NaN}.
     * @see #number(Object, String) for how strings such as {@code "10 mph"} are handled
     */
    public static double getDouble(Object root, String path, String parseMode) {
        return number(get(root, path), parseMode);
    }

    /**
     * Coerce a JSON value to a number under a parse mode declared by the source.
     *
     * <ul>
     *   <li>{@code number} (default) — a JSON number, or a string that is entirely a number</li>
     *   <li>{@code leadingNumber} — the number at the front of a string: {@code "10 mph"},
     *       {@code "10 to 15 mph"} (the low end, which is the conservative read for wind)</li>
     *   <li>{@code compass} — a 16-point compass string: {@code "NW"} becomes 315</li>
     * </ul>
     */
    public static double number(Object value, String parseMode) {
        if (value == null || value == JSONObject.NULL)
            return Double.NaN;

        String mode = parseMode == null ? "number" : parseMode.trim();

        if (value instanceof Number)
            return ((Number) value).doubleValue();

        String s = String.valueOf(value).trim();
        if (s.isEmpty())
            return Double.NaN;

        if (mode.equalsIgnoreCase("compass"))
            return com.atakmap.android.atmosphere.units.Units.compassToDegrees(s);

        if (mode.equalsIgnoreCase("leadingNumber")) {
            int end = 0;
            while (end < s.length()) {
                char c = s.charAt(end);
                if (Character.isDigit(c) || ((c == '-' || c == '+' || c == '.') && end == 0)
                        || (c == '.' && end > 0))
                    end++;
                else
                    break;
            }
            if (end == 0)
                return Double.NaN;
            s = s.substring(0, end);
        }

        try {
            return Double.parseDouble(s);
        } catch (NumberFormatException e) {
            return Double.NaN;
        }
    }
}
