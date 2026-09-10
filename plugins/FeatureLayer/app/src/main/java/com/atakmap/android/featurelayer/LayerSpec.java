package com.atakmap.android.featurelayer;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * What to load: a feature service, which of its layers, a where clause, and how to
 * style it. Serializable, so loaded layers come back after a restart.
 */
public class LayerSpec {
    public enum Profile { NWCG, GENERIC }

    public String id;          // stable key, e.g. "nifs-live:{IRWIN}"
    public String title;       // "Plaskett"
    public String subtitle;    // "NIFS live", "SARCOP training", "NIFS archive"
    public String portal;      // sign-in portal, null when the service is public
    public String orgName;     // what to call the portal to a person: "NIFC", "City of Corona"; never its address
    public String base;        // .../FeatureServer
    public int[] layerIds;
    public String where;
    public boolean geojson;
    public Profile profile = Profile.GENERIC;
    public double lat = Double.NaN, lon = Double.NaN;
    /** Extent of everything fetched last time, for "Go to": south, west, north, east. */
    public double[] bounds;
    public boolean live;       // worth refreshing on a timer
    public int refreshMinutes = 5; // automatic refresh interval; 0 = manual only
    /** NWCG Repair Status halos and bands: off, like NIFC's own incident map; its Repair Status view turns them on. */
    public boolean repairStatus = false;
    /** Map labels on points (names, categories). Lines and areas are never labeled. */
    public boolean labels = true;
    /**
     * Zoom gate, meters per pixel: nothing in the layer draws when the map is zoomed
     * further out than this (Cam Depot's "no further out than" limit). MAX = always.
     */
    public double gateGsd = Double.MAX_VALUE;
    public int maxFeatures;    // per source layer, 0 = no cap
    /** A date field to window on, and how far back: "poly_DateCurrent", 72 h. 0 = everything. */
    public String timeField;
    public int sinceHours;
    /** Split the layer's features into types by this field's value (FIRIS: "source"), instead of one type per source layer. */
    public String setField;
    /** The field drawn as the map label, when the service's own display field is not the one people know. */
    public String labelField;
    /**
     * Keep only the newest feature (by {@link #timeField}) per key: each entry is a field,
     * or fields separated by "|" tried in turn ("incident_name|mission"). Null = keep all.
     */
    public String[] latestBy;

    /** The where clause to query with right now: the fixed one, plus the time window from the present. */
    public String whereNow() {
        final String fixed = where == null || where.trim().isEmpty() ? "1=1" : where;
        if (timeField == null || sinceHours <= 0)
            return fixed;
        final long since = System.currentTimeMillis() - sinceHours * 3600000L;
        final java.text.SimpleDateFormat f = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US);
        f.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
        return "(" + fixed + ") AND " + timeField + " >= TIMESTAMP '" + f.format(new java.util.Date(since)) + "'";
    }

    /** "Last 3 days", "All time". */
    public String windowLabel() {
        if (timeField == null || sinceHours <= 0)
            return "All time";
        if (sinceHours < 48)
            return "Last " + sinceHours + " hours";
        return "Last " + (sinceHours / 24) + " days";
    }
    public String iconSet;     // "sarcop" swaps in NAPSG/NWCG icons; null keeps the service symbols
    public int fillAlpha = 0x40; // polygon fill opacity 0..255; 0 = outline only
    /** Per feature type (set name): fill opacity override, and the geometry kind seen at load. */
    public final java.util.Map<String, Integer> setFill = new java.util.HashMap<>();
    public final java.util.Map<String, String> setKind = new java.util.HashMap<>();
    /** Per feature type: drawn or not. Absent = on. Kept here, not asked of the store. */
    public final java.util.Map<String, Boolean> setOn = new java.util.HashMap<>();
    /**
     * Types a source always offers, in order, each with a line saying what it is, so the
     * Features list shows them all before the first fetch and when a window holds none.
     * Not saved: a built-in source fills it again on load.
     */
    public final java.util.LinkedHashMap<String, String> setNotes = new java.util.LinkedHashMap<>();

    public boolean isOn(String setName) {
        final Boolean v = setOn.get(setName);
        return v == null || v;
    }

    /** The fill opacity for one feature type: its own setting, else the layer's. */
    public int fillFor(String setName) {
        final Integer v = setFill.get(setName);
        return v == null ? fillAlpha : v;
    }

    public JSONObject toJson() throws Exception {
        final JSONObject o = new JSONObject();
        o.put("id", id).put("title", title).put("subtitle", subtitle).put("portal", portal)
                .put("base", base).put("where", where).put("geojson", geojson)
                .put("profile", profile.name()).put("lat", lat).put("lon", lon).put("live", live).put("maxFeatures", maxFeatures).put("iconSet", iconSet).put("fillAlpha", fillAlpha).put("refreshMinutes", refreshMinutes).put("repairStatus", repairStatus).put("labels", labels)
                .put("timeField", timeField).put("sinceHours", sinceHours).put("setField", setField).put("labelField", labelField).put("gateGsd", gateGsd == Double.MAX_VALUE ? -1 : gateGsd).put("orgName", orgName);
        final JSONArray ids = new JSONArray();
        for (int i : layerIds)
            ids.put(i);
        o.put("layerIds", ids);
        o.put("setFill", new JSONObject(setFill));
        o.put("setKind", new JSONObject(setKind));
        o.put("setOn", new JSONObject(setOn));
        if (bounds != null)
            o.put("bounds", new JSONArray(java.util.Arrays.asList(bounds[0], bounds[1], bounds[2], bounds[3])));
        return o;
    }

    public static LayerSpec fromJson(JSONObject o) throws Exception {
        final LayerSpec s = new LayerSpec();
        s.id = o.getString("id");
        s.title = o.optString("title", s.id);
        s.subtitle = o.optString("subtitle", "");
        s.portal = o.isNull("portal") ? null : o.optString("portal", null);
        s.base = o.getString("base");
        s.where = o.optString("where", "1=1");
        s.geojson = o.optBoolean("geojson", false);
        s.profile = Profile.valueOf(o.optString("profile", "GENERIC"));
        s.lat = o.optDouble("lat", Double.NaN);
        s.lon = o.optDouble("lon", Double.NaN);
        s.live = o.optBoolean("live", false);
        s.maxFeatures = o.optInt("maxFeatures", 0);
        s.iconSet = o.isNull("iconSet") ? null : o.optString("iconSet", null);
        s.fillAlpha = o.optInt("fillAlpha", 0x40);
        s.refreshMinutes = o.optInt("refreshMinutes", o.optBoolean("live", false) ? 5 : 0);
        s.repairStatus = o.optBoolean("repairStatus", false);
        s.labels = o.optBoolean("labels", true);
        s.timeField = o.isNull("timeField") ? null : o.optString("timeField", null);
        s.sinceHours = o.optInt("sinceHours", 0);
        s.setField = o.isNull("setField") ? null : o.optString("setField", null);
        s.labelField = o.isNull("labelField") ? null : o.optString("labelField", null);
        s.orgName = o.isNull("orgName") ? null : o.optString("orgName", null);
        final double gate = o.optDouble("gateGsd", -1);
        s.gateGsd = gate <= 0 ? Double.MAX_VALUE : gate;
        final JSONArray ids = o.getJSONArray("layerIds");
        s.layerIds = new int[ids.length()];
        for (int i = 0; i < ids.length(); i++)
            s.layerIds[i] = ids.getInt(i);
        final JSONObject sf = o.optJSONObject("setFill");
        if (sf != null) {
            final java.util.Iterator<String> k = sf.keys();
            while (k.hasNext()) {
                final String name = k.next();
                s.setFill.put(name, sf.optInt(name, s.fillAlpha));
            }
        }
        final JSONArray b = o.optJSONArray("bounds");
        if (b != null && b.length() == 4)
            s.bounds = new double[] { b.getDouble(0), b.getDouble(1), b.getDouble(2), b.getDouble(3) };
        final JSONObject so = o.optJSONObject("setOn");
        if (so != null) {
            final java.util.Iterator<String> k = so.keys();
            while (k.hasNext()) {
                final String name = k.next();
                s.setOn.put(name, so.optBoolean(name, true));
            }
        }
        final JSONObject sk = o.optJSONObject("setKind");
        if (sk != null) {
            final java.util.Iterator<String> k = sk.keys();
            while (k.hasNext()) {
                final String name = k.next();
                s.setKind.put(name, sk.optString(name, ""));
            }
        }
        return s;
    }

    /** A file-system-safe name for this layer's store. */
    public String fileKey() {
        return id.toLowerCase().replaceAll("[^a-z0-9]+", "_");
    }
}
