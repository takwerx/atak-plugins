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
 *   <li><b>A source cannot reach the network until the operator enables it.</b> Bundled
 *       definitions ship disabled. There is no "default provider" quietly fetching.</li>
 *   <li><b>Position precision is a decision, not an accident.</b> A weather query needs
 *       to know roughly where you are; a provider does not need your exact position to
 *       three decimal places of a second. Coordinates are rounded before they leave, and
 *       the operator picks how coarse.</li>
 * </ol>
 */
public final class EgressPolicy {

    private static final String PREF_ENABLED_PREFIX = "weather.source.enabled.";
    private static final String PREF_LAYER_PREFIX = "weather.layer.enabled.";
    private static final String PREF_PRECISION = "weather.position.decimals";

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

    public boolean isEnabled(WxSourceDef def) {
        if (def == null)
            return false;
        final SharedPreferences prefs = MapCompat.prefs();
        if (prefs == null)
            return false;
        return prefs.getBoolean(PREF_ENABLED_PREFIX + def.id, false);
    }

    public void setEnabled(WxSourceDef def, boolean enabled) {
        final SharedPreferences prefs = MapCompat.prefs();
        if (prefs == null || def == null)
            return;
        prefs.edit().putBoolean(PREF_ENABLED_PREFIX + def.id, enabled).apply();
    }

    /**
     * Map layers are gated like sources: off until the operator allows the host, once,
     * by name. A layer request carries no position beyond the map view's extent.
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

    /** Decimal places kept on coordinates sent to a provider. 0 disables rounding. */
    public int positionDecimals() {
        final SharedPreferences prefs = MapCompat.prefs();
        if (prefs == null)
            return DEFAULT_DECIMALS;
        final int d = prefs.getInt(PREF_PRECISION, DEFAULT_DECIMALS);
        return d < 0 ? DEFAULT_DECIMALS : Math.min(d, 6);
    }

    public void setPositionDecimals(int decimals) {
        final SharedPreferences prefs = MapCompat.prefs();
        if (prefs == null)
            return;
        prefs.edit().putInt(PREF_PRECISION, Math.max(0, Math.min(decimals, 6))).apply();
    }

    /** How coarse the current setting is, in meters, for the settings UI. */
    public static int approximateMeters(int decimals) {
        switch (decimals) {
            case 0:
                return 111000;
            case 1:
                return 11100;
            case 2:
                return 1110;
            case 3:
                return 111;
            case 4:
                return 11;
            default:
                return 1;
        }
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
        if (!isEnabled(def))
            return def.displayName + " is not enabled. Enable it in Sources to allow "
                    + "requests to " + hostList(def) + ".";
        if (def.requiresApiKey)
            return def.displayName + " needs an API key, and this build does not store "
                    + "keys yet.";
        return null;
    }

    private static String hostList(WxSourceDef def) {
        final java.util.List<String> hosts = def.hosts();
        if (hosts.isEmpty())
            return "its provider";
        final StringBuilder sb = new StringBuilder();
        for (int i = 0; i < hosts.size(); i++) {
            if (i > 0)
                sb.append(", ");
            sb.append(hosts.get(i));
        }
        return sb.toString();
    }
}
