package com.atakmap.android.evaczone;

import android.content.Context;
import android.content.SharedPreferences;
import android.preference.PreferenceManager;

import com.atakmap.android.maps.MapView;
import com.atakmap.coremap.log.Log;
import com.atakmap.coremap.maps.coords.GeoPoint;

/**
 * What Cam Depot calls the visibility tools, for zones: a radius from a point (the
 * operator, or wherever the map is now) and a zoom threshold below which nothing is
 * drawn. One setting for every source, remembered across restarts, applied by the
 * manager to every layer that is on.
 */
public final class Visibility {

    private static final String TAG = "EvacZone";
    static final String PREF_ZOOM = "evaczone_zoom_threshold";
    static final String PREF_RADIUS = "evaczone_radius_big";
    /** The MODE, not the point: "Map Center" means wherever the map is now. */
    static final String PREF_FROM_MAP = "evaczone_radius_from_map";

    /**
     * A hair of tolerance, and it is load bearing. "Use this zoom" stores the CURRENT
     * resolution as the threshold, so the scale you were looking at must count as
     * within it; the threshold is persisted as a float while the comparison is in
     * double, so after a restart it comes back a fraction below what was set.
     */
    static final double ZOOM_EPSILON = 1.001;

    /** The slider's top, in the operator's own big unit. */
    public static final int RADIUS_MAX = 50;

    private final SharedPreferences prefs;
    /** Zones draw at or below this many meters per pixel; MAX_VALUE means always. */
    public double maxResolution = Double.MAX_VALUE;
    /** Radius in the operator's big unit (miles, km or NM); 0 is off. */
    public int radiusBig;
    public boolean fromMap;

    Visibility(Context host) {
        prefs = PreferenceManager.getDefaultSharedPreferences(host);
        try {
            final float z = prefs.getFloat(PREF_ZOOM, Float.MAX_VALUE);
            maxResolution = z >= Float.MAX_VALUE / 2 ? Double.MAX_VALUE : z;
            radiusBig = Math.max(0, Math.min(RADIUS_MAX, prefs.getInt(PREF_RADIUS, 0)));
            fromMap = prefs.getBoolean(PREF_FROM_MAP, false);
        } catch (RuntimeException e) {
            Log.w(TAG, "visibility settings unreadable; starting fresh", e);
        }
    }

    void save() {
        try {
            prefs.edit()
                    .putFloat(PREF_ZOOM, maxResolution == Double.MAX_VALUE ? Float.MAX_VALUE : (float) maxResolution)
                    .putInt(PREF_RADIUS, radiusBig)
                    .putBoolean(PREF_FROM_MAP, fromMap)
                    .apply();
        } catch (RuntimeException e) {
            Log.w(TAG, "could not remember the visibility settings", e);
        }
    }

    public double radiusMeters() {
        return radiusBig > 0 ? Units.bigToMeters(radiusBig) : 0;
    }

    /** The point the radius is measured from: the map now, or the operator, else the map. */
    public GeoPoint from(MapView mv) {
        if (fromMap)
            return mv.getPoint().get();
        try {
            final com.atakmap.android.maps.Marker self = mv.getSelfMarker();
            if (self != null && self.getPoint() != null)
                return self.getPoint();
        } catch (RuntimeException e) {
            Log.w(TAG, "self marker unavailable", e);
        }
        return mv.getPoint().get();
    }

    public String fromLabel() {
        return fromMap ? "Map Center" : "My Location";
    }

    public boolean withinZoom(MapView mv) {
        return maxResolution == Double.MAX_VALUE || mv.getMapResolution() <= maxResolution * ZOOM_EPSILON;
    }
}
