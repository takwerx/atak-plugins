package com.atakmap.android.atmosphere.data;

import com.atakmap.android.atmosphere.model.Reading;
import com.atakmap.android.atmosphere.model.SeriesEntry;
import com.atakmap.android.atmosphere.model.Snapshot;
import com.atakmap.android.atmosphere.source.JsonPath;
import com.atakmap.android.atmosphere.source.WxParam;
import com.atakmap.android.atmosphere.source.WxSourceDef;
import com.atakmap.android.atmosphere.units.Units;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Response JSON plus a {@link WxSourceDef} becomes a {@link Snapshot}.
 *
 * <p>This is the half of the source-definition design that does the work: the definition
 * says where each value lives and what unit it is in, and everything downstream sees
 * canonical numbers. No provider-specific code exists in this class, and none should be
 * added — if a provider cannot be expressed, the schema is what changes.
 */
public final class ResponseMapper {

    /**
     * Seven days of hours. NWS serves 156 hourly periods (six and a half days) and
     * Open-Meteo up to 16 days; the August cap of 48 kept the old vertical list
     * short and cut the days strip to three columns.
     */
    public static final int MAX_SERIES = 168;

    private ResponseMapper() {
    }

    public static Snapshot map(WxSourceDef def, String body, double latitude,
            double longitude, long fetchedAt) throws JSONException {

        final JSONObject root = new JSONObject(body);

        final List<Reading> current = new ArrayList<>();
        for (WxParam p : def.params) {
            if (p.currentPath == null)
                continue;
            current.add(readingAt(root, p, p.currentPath, root));
        }

        final List<SeriesEntry> series;
        switch (def.layout) {
            case RECORDS: series = mapRecords(def, root); break;
            case GRID: series = mapGrid(def, root, fetchedAt); break;
            default: series = mapColumns(def, root); break;
        }

        return new Snapshot(def.id, def.displayName, def.attribution, latitude, longitude,
                fetchedAt, current, series);
    }

    /** Parallel arrays, one per variable — the Open-Meteo shape. */
    private static List<SeriesEntry> mapColumns(WxSourceDef def, JSONObject root) {
        final List<SeriesEntry> out = new ArrayList<>();
        final JSONArray times = JsonPath.getArray(root, def.timePath);
        if (times == null)
            return out;

        final int steps = Math.min(times.length(), MAX_SERIES);
        for (int i = 0; i < steps; i++) {
            final List<Reading> readings = new ArrayList<>();
            for (WxParam p : def.params) {
                if (p.seriesPath == null)
                    continue;
                final JSONArray column = JsonPath.getArray(root, p.seriesPath);
                final Object raw = column == null || i >= column.length()
                        ? null : column.opt(i);
                readings.add(reading(p, raw, unitFor(p, root, root)));
            }
            final String timeRaw = times.optString(i, null);
            out.add(new SeriesEntry(IsoTime.parse(timeRaw), timeRaw, readings));
        }
        return out;
    }

    /**
     * One object of elements, each a series of timed spans -- the NWS forecast grid.
     *
     * <p>A value there is "11 km/h from 00Z for two hours", so every span is spread
     * over the hours it covers and the hours are joined across elements. Hours before
     * the fetch are dropped: the grid holds the current day from midnight, and the
     * first entry is what the readout shows as now. An element with no value for an
     * hour still gets a reading, NaN, so the readout shows a dash and not a different
     * variable in its place.
     */
    private static List<SeriesEntry> mapGrid(WxSourceDef def, JSONObject root, long fetchedAt) {
        final List<SeriesEntry> out = new ArrayList<>();
        final Object elementsObj = JsonPath.get(root, def.recordsPath);
        if (!(elementsObj instanceof JSONObject))
            return out;
        final JSONObject elements = (JSONObject) elementsObj;
        final long hour = 3_600_000L;
        final long start = fetchedAt <= 0 ? 0 : (fetchedAt / hour) * hour;
        final long end = start + MAX_SERIES * hour;

        // hour millis -> readings by param key, in time order
        final java.util.TreeMap<Long, java.util.Map<String, Reading>> hours =
                new java.util.TreeMap<>();
        for (WxParam p : def.params) {
            if (p.seriesPath == null)
                continue;
            final JSONObject el = elements.optJSONObject(p.seriesPath);
            if (el == null)
                continue;
            final JSONArray values = el.optJSONArray("values");
            if (values == null)
                continue;
            final String unit = unitFor(p, el, root);
            for (int i = 0; i < values.length(); i++) {
                final JSONObject v = values.optJSONObject(i);
                if (v == null)
                    continue;
                final String validTime = v.optString("validTime", "");
                final int slash = validTime.indexOf('/');
                final long from = IsoTime.parse(slash < 0 ? validTime
                        : validTime.substring(0, slash));
                if (from == 0)
                    continue;
                final int span = slash < 0 ? 1
                        : IsoTime.durationHours(validTime.substring(slash + 1));
                final Reading r = reading(p, v.opt("value"), unit);
                for (int h = 0; h < span; h++) {
                    final long at = from + h * hour;
                    if (at < start || at >= end)
                        continue;
                    java.util.Map<String, Reading> byKey = hours.get(at);
                    if (byKey == null) {
                        byKey = new java.util.HashMap<>();
                        hours.put(at, byKey);
                    }
                    byKey.put(p.key, r);
                }
            }
        }
        for (java.util.Map.Entry<Long, java.util.Map<String, Reading>> e : hours.entrySet()) {
            final List<Reading> readings = new ArrayList<>();
            for (WxParam p : def.params) {
                if (p.seriesPath == null)
                    continue;
                final Reading r = e.getValue().get(p.key);
                readings.add(r != null ? r
                        : new Reading(p.key, p.label, p.quantity, Double.NaN));
            }
            final long at = e.getKey();
            out.add(new SeriesEntry(at, IsoTime.formatHourUtc(at), readings));
            if (out.size() >= MAX_SERIES)
                break;
        }
        return out;
    }

    /** An array of period objects — the NWS shape. */
    private static List<SeriesEntry> mapRecords(WxSourceDef def, JSONObject root) {
        final List<SeriesEntry> out = new ArrayList<>();
        final JSONArray records = JsonPath.getArray(root, def.recordsPath);
        if (records == null)
            return out;

        final int steps = Math.min(records.length(), MAX_SERIES);
        for (int i = 0; i < steps; i++) {
            final JSONObject record = records.optJSONObject(i);
            if (record == null)
                continue;

            final List<Reading> readings = new ArrayList<>();
            for (WxParam p : def.params) {
                if (p.seriesPath == null)
                    continue;
                readings.add(readingAt(record, p, p.seriesPath, root));
            }
            final String timeRaw = def.timePath == null
                    ? null : JsonPath.getString(record, def.timePath);
            out.add(new SeriesEntry(IsoTime.parse(timeRaw), timeRaw, readings));
        }
        return out;
    }

    private static Reading readingAt(Object scope, WxParam p, String path, JSONObject root) {
        return reading(p, JsonPath.get(scope, path), unitFor(p, scope, root));
    }

    /**
     * The place a resolve response names: the values at {@code paths}, in order, joined
     * with ", "; a path that does not resolve is skipped, and no value at all is null.
     * NWS's points response gives "Corona" and "CA".
     */
    public static String place(Object root, List<String> paths) {
        if (root == null || paths == null || paths.isEmpty())
            return null;
        final StringBuilder sb = new StringBuilder();
        for (String p : paths) {
            final String v = JsonPath.getString(root, p);
            if (v == null || v.trim().isEmpty() || "null".equals(v))
                continue;
            if (sb.length() > 0)
                sb.append(", ");
            sb.append(v.trim());
        }
        return sb.length() == 0 ? null : sb.toString();
    }

    private static Reading reading(WxParam p, Object raw, String unit) {
        final double parsed = coerce(raw, p);
        final double canonical = Double.isNaN(parsed)
                ? Double.NaN : Units.toCanonical(p.quantity, unit, parsed);
        return new Reading(p.key, p.label, p.quantity, canonical);
    }

    /**
     * The raw JSON value as a number under the parameter's parse mode. {@code lookup}
     * tries the table's substrings in order against the value's text, case-insensitive,
     * and takes the first match; no match is NaN, shown as a dash, never a guess.
     */
    public static double coerce(Object raw, WxParam p) {
        if (!"lookup".equals(p.parse) || p.lookupKeys == null)
            return JsonPath.number(raw, p.parse);
        if (raw == null || raw == JSONObject.NULL)
            return Double.NaN;
        if (raw instanceof Number)
            return ((Number) raw).doubleValue();
        final String s = String.valueOf(raw).toLowerCase(Locale.US);
        for (int i = 0; i < p.lookupKeys.length; i++) {
            if (s.contains(p.lookupKeys[i].toLowerCase(Locale.US)))
                return p.lookupValues[i];
        }
        return Double.NaN;
    }

    /**
     * The unit for a value: the one the definition fixes, or one the response carries
     * per value ({@code unitPath}) — NWS reports {@code temperatureUnit: "F"} alongside
     * each temperature, and a provider that changes it must not silently change meaning.
     */
    private static String unitFor(WxParam p, Object scope, JSONObject root) {
        if (p.unitPath != null) {
            final String local = JsonPath.getString(scope, p.unitPath);
            if (local != null)
                return local;
            final String global = JsonPath.getString(root, p.unitPath);
            if (global != null)
                return global;
        }
        return p.unit;
    }
}
