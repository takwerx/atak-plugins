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
 * The operator's starred fire weather zones: the zone number, its name and its
 * office, in the order they were starred.
 *
 * <p>Kept apart from the place favorites on purpose. A place is a point; a zone is an
 * area with a number the crew already uses on the radio and in the IAP, and it is
 * starred to read that zone's forecast, wherever the pane happens to be pointed.
 * The name and office are stored with the number so the list draws without asking
 * anything.
 *
 * <p>Persisted in ATAK's own default preferences on the <em>MapView</em> context, as
 * JSON under one key: the plugin context's preferences do not survive the plugin
 * being reloaded.
 */
public final class ZoneFavorites {

    private static final String TAG = "AtmosphereZones";
    private static final String PREF = "atmosphere_zone_favorites";

    public static final class Starred {
        /** {@code CA548}, as the zone service keys it. */
        public final String id;
        public final String name;
        public final String cwa;

        public Starred(String id, String name, String cwa) {
            this.id = id;
            this.name = name == null ? "" : name;
            this.cwa = cwa == null ? "" : cwa;
        }
    }

    private final SharedPreferences prefs;
    private final List<Starred> zones = new ArrayList<>();

    /** @param uiContext the MapView context, never the plugin context */
    public ZoneFavorites(Context uiContext) {
        SharedPreferences p = null;
        try {
            p = PreferenceManager.getDefaultSharedPreferences(uiContext);
            zones.addAll(decode(p.getString(PREF, null)));
        } catch (RuntimeException e) {
            Log.w(TAG, "could not read the starred zones", e);
        }
        this.prefs = p;
    }

    public List<Starred> all() {
        return Collections.unmodifiableList(zones);
    }

    public boolean contains(String id) {
        return indexOf(id) >= 0;
    }

    /**
     * Star the zone if it is not starred, unstar it if it is.
     *
     * @return true if the zone is starred afterwards
     */
    public boolean toggle(String id, String name, String cwa) {
        if (id == null || id.isEmpty())
            return false;
        final int i = indexOf(id);
        if (i >= 0)
            zones.remove(i);
        else
            zones.add(new Starred(id, name, cwa));
        save();
        return i < 0;
    }

    private int indexOf(String id) {
        if (id == null)
            return -1;
        for (int i = 0; i < zones.size(); i++)
            if (zones.get(i).id.equals(id))
                return i;
        return -1;
    }

    private void save() {
        if (prefs == null)
            return;
        try {
            prefs.edit().putString(PREF, encode(zones)).apply();
        } catch (RuntimeException e) {
            Log.w(TAG, "could not save the starred zones", e);
        }
    }

    /** The list as a JSON array of {id, name, cwa}. */
    public static String encode(List<Starred> zones) {
        final JSONArray arr = new JSONArray();
        for (Starred z : zones) {
            try {
                arr.put(new JSONObject().put("id", z.id).put("name", z.name)
                        .put("cwa", z.cwa));
            } catch (JSONException e) {
                // A string that JSON cannot hold is dropped, not the whole list.
            }
        }
        return arr.toString();
    }

    /** What {@link #encode} wrote; an unreadable or missing value is an empty list. */
    public static List<Starred> decode(String json) {
        final List<Starred> out = new ArrayList<>();
        if (json == null || json.isEmpty())
            return out;
        try {
            final JSONArray arr = new JSONArray(json);
            for (int i = 0; i < arr.length(); i++) {
                final JSONObject o = arr.optJSONObject(i);
                if (o == null)
                    continue;
                final String id = o.optString("id", "");
                if (FireZones.normalize(id) == null)
                    continue;
                out.add(new Starred(FireZones.normalize(id), o.optString("name", ""),
                        o.optString("cwa", "")));
            }
        } catch (JSONException e) {
            return out;
        }
        return out;
    }
}
