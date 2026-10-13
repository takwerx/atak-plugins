package com.atakmap.android.atmosphere.data;

import android.content.Context;
import android.content.SharedPreferences;
import android.preference.PreferenceManager;

import com.atakmap.coremap.log.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The operator's saved places, a name and a point each, the way Cam Depot keeps its
 * starred cameras. A shift plans on a handful of places (the incident, the ICP, home
 * base), and typing them in each time is what this replaces.
 *
 * <p>One flat list, never scoped by source, units or anything else in the pane.
 * Persisted in ATAK's own default preferences on the <em>MapView</em> context, as
 * JSON under one key: the plugin context's preferences do not survive the plugin
 * being reloaded.
 */
public final class Favorites {

    private static final String TAG = "AtmosphereFavorites";
    private static final String PREF = "atmosphere_favorites";
    /** Two points closer than this are the same place, about 50 m. */
    private static final double SAME_PLACE_DEG = 0.0005;

    public static final class Place {
        public final String name;
        public final double latitude;
        public final double longitude;

        public Place(String name, double latitude, double longitude) {
            this.name = name;
            this.latitude = latitude;
            this.longitude = longitude;
        }
    }

    private final SharedPreferences prefs;
    private final List<Place> places = new ArrayList<>();

    /** @param uiContext the MapView context, never the plugin context */
    public Favorites(Context uiContext) {
        SharedPreferences p = null;
        try {
            p = PreferenceManager.getDefaultSharedPreferences(uiContext);
            final List<Place> stored = decode(p.getString(PREF, null));
            if (stored == null)
                Log.w(TAG, "favorites list unreadable, starting empty");
            else
                places.addAll(stored);
        } catch (RuntimeException e) {
            Log.w(TAG, "could not read the favorites list", e);
        }
        this.prefs = p;
    }

    public List<Place> all() {
        return Collections.unmodifiableList(places);
    }

    public boolean isEmpty() {
        return places.isEmpty();
    }

    public int size() {
        return places.size();
    }

    public Place byName(String name) {
        if (name == null)
            return null;
        for (Place p : places)
            if (p.name.equals(name))
                return p;
        return null;
    }

    /**
     * Save a place. One already at the same point, or with the same name, is replaced,
     * so starring the same spot twice renames it rather than doubling it.
     *
     * @return the saved place, or null when the name was blank
     */
    public Place add(String name, double latitude, double longitude) {
        final String n = name == null ? "" : name.trim();
        if (n.isEmpty())
            return null;
        for (int i = places.size() - 1; i >= 0; i--) {
            final Place q = places.get(i);
            if (q.name.equalsIgnoreCase(n) || samePlace(q, latitude, longitude))
                places.remove(i);
        }
        final Place p = new Place(n, latitude, longitude);
        places.add(p);
        save();
        return p;
    }

    /** @return true if a place of that name was removed */
    public boolean remove(String name) {
        for (int i = 0; i < places.size(); i++) {
            if (places.get(i).name.equals(name)) {
                places.remove(i);
                save();
                return true;
            }
        }
        return false;
    }

    private static boolean samePlace(Place q, double latitude, double longitude) {
        return Math.abs(q.latitude - latitude) < SAME_PLACE_DEG
                && Math.abs(q.longitude - longitude) < SAME_PLACE_DEG;
    }

    private void save() {
        if (prefs == null)
            return;
        try {
            prefs.edit().putString(PREF, encode(places)).apply();
        } catch (RuntimeException e) {
            Log.w(TAG, "could not save the favorites list", e);
        }
    }

    /** The list as a JSON array of {name, lat, lon}. */
    public static String encode(List<Place> places) {
        final JSONArray arr = new JSONArray();
        for (Place p : places) {
            try {
                arr.put(new JSONObject().put("name", p.name).put("lat", p.latitude)
                        .put("lon", p.longitude));
            } catch (JSONException e) {
                // NaN is the only way put() fails on these, and a place has a real point.
                Log.w(TAG, "skipping unencodable favorite " + p.name, e);
            }
        }
        return arr.toString();
    }

    /**
     * The stored list; a damaged entry is skipped, never a crash. Null when the string
     * is not JSON at all, so the caller can say so; pure, so it is unit-tested.
     */
    public static List<Place> decode(String json) {
        final List<Place> out = new ArrayList<>();
        if (json == null || json.trim().isEmpty())
            return out;
        try {
            final JSONArray arr = new JSONArray(json);
            for (int i = 0; i < arr.length(); i++) {
                final JSONObject o = arr.optJSONObject(i);
                if (o == null)
                    continue;
                final String name = o.optString("name", "").trim();
                final double lat = o.optDouble("lat", Double.NaN);
                final double lon = o.optDouble("lon", Double.NaN);
                if (name.isEmpty() || Double.isNaN(lat) || Double.isNaN(lon))
                    continue;
                out.add(new Place(name, lat, lon));
            }
        } catch (JSONException e) {
            return null;
        }
        return out;
    }
}
