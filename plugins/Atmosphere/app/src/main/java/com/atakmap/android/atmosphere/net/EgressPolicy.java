package com.atakmap.android.atmosphere.net;

import android.content.SharedPreferences;

import com.atakmap.android.atmosphere.compat.MapCompat;
import com.atakmap.android.atmosphere.source.WxSourceDef;
import com.atakmap.coremap.maps.coords.GeoPoint;

import java.util.Locale;

/**
 * The single choke point for anything this plugin sends off the device.
 *
 * <p>Two guarantees, both of which are the reason this class exists rather than being
 * spread across the request code:
 *
 * <ol>
 *   <li><b>Nothing reaches the network without the operator's choice.</b> A map layer
 *       asks once, naming its server, before its first request. The forecast asks only
 *       the weather service the operator has picked: picking it is the choice, with no
 *       second allow step (operator, 2026-09-28: "your just picking a forecast
 *       service"). NWS is picked on a new install.</li>
 *   <li><b>Position precision is a decision, not an accident.</b> A weather query needs
 *       to know roughly where you are; a provider does not need your exact position to
 *       three decimal places of a second. Coordinates are rounded to about 100 m before they
 *       leave.</li>
 * </ol>
 */
public final class EgressPolicy {

    private static final String PREF_LAYER_PREFIX = "weather.layer.enabled.";

    /** ~110 m at the equator. Plenty for a forecast, coarse enough not to be a fix. */
    public static final int DEFAULT_DECIMALS = 3;

    private final String userAgent;

    public EgressPolicy(String pluginVersion) {
        // Several public providers (NWS above all) require a User-Agent that identifies
        // the client and will block or throttle requests without one.
        this.userAgent = "takwerx-atmosphere/" + pluginVersion
                + " (+https://github.com/takwerx/atmosphere)";
    }

    public String userAgent() {
        return userAgent;
    }

    /**
     * Map layers are off until the operator allows the host, once, by name. A layer
     * request carries no position beyond the map view's extent unless the layer says so.
     */
    public boolean isLayerEnabled(String layerId) {
        final SharedPreferences prefs = MapCompat.prefs();
        return prefs != null && layerId != null
                && prefs.getBoolean(PREF_LAYER_PREFIX + layerId, false);
    }

    public void setLayerEnabled(String layerId, boolean enabled) {
        final SharedPreferences prefs = MapCompat.prefs();
        if (prefs == null || layerId == null)
            return;
        prefs.edit().putBoolean(PREF_LAYER_PREFIX + layerId, enabled).apply();
    }

    /**
     * Decimal places kept on coordinates sent to a provider: always about 100 m. It was
     * a setting ("Position sent") until 2026-09-28, when the operator took it out as
     * confusing; a value an older build stored is ignored, so no phone keeps a
     * rounding nobody can see or change.
     */
    public int positionDecimals() {
        return DEFAULT_DECIMALS;
    }
    /** The latitude as it will be sent — rounded, formatted, no locale surprises. */
    public String latitude(GeoPoint point) {
        return format(point.getLatitude());
    }

    /** The longitude as it will be sent. */
    public String longitude(GeoPoint point) {
        return format(point.getLongitude());
    }

    private String format(double value) {
        final int decimals = positionDecimals();
        if (decimals == 0)
            return String.format(Locale.US, "%.0f", value);
        return String.format(Locale.US, "%." + decimals + "f", value);
    }

    /**
     * @return null if the request may proceed, otherwise the reason it may not — shown to
     *         the operator verbatim
     */
    public String refuse(WxSourceDef def) {
        if (def == null)
            return "no source selected";
        if (def.requiresApiKey)
            return def.displayName + " needs an API key, and this build does not store "
                    + "keys yet.";
        return null;
    }

}
