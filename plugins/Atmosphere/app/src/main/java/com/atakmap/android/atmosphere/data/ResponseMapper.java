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

        final List<SeriesEntry> series = def.layout == WxSourceDef.Layout.RECORDS
                ? mapRecords(def, root)
                : mapColumns(def, root);

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

    private static Reading reading(WxParam p, Object raw, String unit) {
        final double parsed = JsonPath.number(raw, p.parse);
        final double canonical = Double.isNaN(parsed)
                ? Double.NaN : Units.toCanonical(p.quantity, unit, parsed);
        return new Reading(p.key, p.label, p.quantity, canonical);
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
