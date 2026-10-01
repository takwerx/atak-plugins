package com.atakmap.android.atmosphere.source;

import com.atakmap.android.atmosphere.units.Quantity;

/**
 * One weather variable as a source definition describes it: what to ask the API for,
 * where the answer lives in the response, and what unit it comes back in.
 *
 * <p>Immutable — the registry hands the same instance to the UI and the parser.
 */
public final class WxParam {

    /** Identifier within the source; also the value sent in a request parameter list. */
    public final String key;
    /** What the operator sees, e.g. "Wind gust". */
    public final String label;
    public final Quantity quantity;
    /** Unit the API returns, as declared by the source. Null means "already canonical". */
    public final String unit;
    /** Path to a per-value unit string in the response (NWS does this). Null if fixed. */
    public final String unitPath;
    /** Shown by default the first time this source is used. */
    public final boolean defaultOn;
    /** Request groups this key belongs in, e.g. {@code current}, {@code hourly}. */
    public final String[] requestGroups;
    /** Absolute path to the current value, or null when the source has no "current". */
    public final String currentPath;
    /**
     * Path to this variable in the time series — absolute for a {@code columns} layout,
     * relative to each record for a {@code records} layout. Null if not in the series.
     */
    public final String seriesPath;
    /** How to coerce the raw value: {@code number}, {@code leadingNumber}, {@code compass}. */
    public final String parse;
    /**
     * For {@code parse: "lookup"}: substrings tried in order against the raw value, and
     * the number each stands for. NWS puts sky cover in an icon URL ({@code .../few?...});
     * Open-Meteo puts weather in a WMO code. Null for the other parse modes.
     */
    public final String[] lookupKeys;
    public final double[] lookupValues;

    WxParam(String key, String label, Quantity quantity, String unit, String unitPath,
            boolean defaultOn, String[] requestGroups, String currentPath,
            String seriesPath, String parse) {
        this(key, label, quantity, unit, unitPath, defaultOn, requestGroups, currentPath,
                seriesPath, parse, null, null);
    }

    WxParam(String key, String label, Quantity quantity, String unit, String unitPath,
            boolean defaultOn, String[] requestGroups, String currentPath,
            String seriesPath, String parse, String[] lookupKeys, double[] lookupValues) {
        this.key = key;
        this.label = label;
        this.quantity = quantity;
        this.unit = unit;
        this.unitPath = unitPath;
        this.defaultOn = defaultOn;
        this.requestGroups = requestGroups;
        this.currentPath = currentPath;
        this.seriesPath = seriesPath;
        this.parse = parse;
        this.lookupKeys = lookupKeys;
        this.lookupValues = lookupValues;
    }

    public boolean inGroup(String group) {
        if (group == null)
            return false;
        for (String g : requestGroups) {
            if (g.equalsIgnoreCase(group))
                return true;
        }
        return false;
    }

    @Override
    public String toString() {
        return key + " (" + label + ")";
    }
}
