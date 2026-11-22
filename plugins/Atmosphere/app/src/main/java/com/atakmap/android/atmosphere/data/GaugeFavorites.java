package com.atakmap.android.atmosphere.data;

import android.content.Context;
import android.content.SharedPreferences;
import android.preference.PreferenceManager;

import com.atakmap.coremap.log.Log;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * The operator's starred river gauges, by NWS location id.
 *
 * <p>Cam Depot's favorites carried over: one flat set of ids, never scoped by radius,
 * origin or filter, so a station starred from one place still counts from another.
 * Keyed by {@link Nwps.Gauge#lid}, which every gauge carries.
 *
 * <p>Persisted in ATAK's own default preferences, on the <em>MapView</em> context. The
 * plugin context is not a real Android app context for this purpose and its
 * preferences do not survive the plugin being reloaded.
 */
public final class GaugeFavorites {

    private static final String TAG = "AtmosphereGauges";
    private static final String PREF = "atmosphere_gauge_favorites";

    private final SharedPreferences prefs;
    private final Set<String> ids = new HashSet<>();

    /** @param uiContext the MapView context, never the plugin context */
    public GaugeFavorites(Context uiContext) {
        SharedPreferences p = null;
        try {
            p = PreferenceManager.getDefaultSharedPreferences(uiContext);
            // getStringSet hands back the live instance it is caching. Copying is not
            // tidiness: mutating it corrupts the in-memory preference and the change
            // is then never written, because SharedPreferences sees no difference.
            final Set<String> stored = p.getStringSet(PREF, null);
            if (stored != null)
                ids.addAll(stored);
        } catch (RuntimeException e) {
            Log.w(TAG, "could not read the starred gauges", e);
        }
        this.prefs = p;
    }

    public boolean contains(String lid) {
        return lid != null && ids.contains(lid);
    }

    /**
     * Star the station if it is not starred, unstar it if it is.
     *
     * @return true if the station is starred afterwards
     */
    public boolean toggle(String lid) {
        if (lid == null || lid.isEmpty())
            return false;
        final boolean now = !ids.contains(lid);
        if (now)
            ids.add(lid);
        else
            ids.remove(lid);
        save();
        return now;
    }

    public int size() {
        return ids.size();
    }

    public boolean isEmpty() {
        return ids.isEmpty();
    }

    public Set<String> ids() {
        return Collections.unmodifiableSet(ids);
    }

    private void save() {
        if (prefs == null)
            return;
        try {
            prefs.edit().putStringSet(PREF, new HashSet<>(ids)).apply();
        } catch (RuntimeException e) {
            Log.w(TAG, "could not save the starred gauges", e);
        }
    }
}
