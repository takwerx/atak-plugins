package com.atakmap.android.atmosphere.compat;

import android.content.Context;
import android.content.SharedPreferences;
import android.preference.PreferenceManager;

import com.atakmap.android.maps.MapView;
import com.atakmap.android.maps.Marker;
import com.atakmap.coremap.maps.coords.GeoPoint;

/**
 * The one place this plugin reaches into {@code com.atakmap.android.*} internals.
 *
 * <p>Everything else is written against {@code gov.tak.api.*}, which is not obfuscated and
 * survives ATAK upgrades. These few calls have no stable-API equivalent today, so they are
 * isolated here: when an upgrade breaks the plugin, this file is the whole search space.
 *
 * <p>Each method states what it needs, so a future replacement is obvious.
 */
public final class MapCompat {

    private MapCompat() {
    }

    /** Needs: the map's current centre. No stable-API equivalent as of ATAK 5.7. */
    public static GeoPoint mapCenter() {
        final MapView mv = MapView.getMapView();
        if (mv == null)
            return null;
        return mv.getPoint().get();
    }

    /** Needs: the operator's own position, or null when there is no self marker fix. */
    public static GeoPoint selfPoint() {
        final MapView mv = MapView.getMapView();
        if (mv == null)
            return null;
        final Marker self = mv.getSelfMarker();
        if (self == null)
            return null;
        final GeoPoint p = self.getPoint();
        if (p == null || !p.isValid())
            return null;
        return p;
    }

    /**
     * Needs: ATAK's own context.
     *
     * <p>Preferences must be stored against the host app context, not the plugin context.
     * A plugin context has no writable {@code shared_prefs} directory, and writing through
     * it fails with {@code mkdir failed} — settings then look like they save and are gone
     * on the next start.
     */
    public static Context atakContext() {
        final MapView mv = MapView.getMapView();
        return mv == null ? null : mv.getContext();
    }

    /** ATAK's shared preference store, or null before the map exists. */
    public static SharedPreferences prefs() {
        final Context ctx = atakContext();
        if (ctx == null)
            return null;
        return PreferenceManager.getDefaultSharedPreferences(ctx);
    }
}
