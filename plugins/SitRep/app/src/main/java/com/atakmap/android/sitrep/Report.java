package com.atakmap.android.sitrep;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * One saved sit rep: the area, when it was made, and every section's answer. Read
 * only (PLAN decision 5), except for the name, which the operator may change.
 */
public final class Report {

    /** Where one section stands. */
    public enum State {
        LOADING, OK, NOT_HERE, FAILED, OFFLINE
    }

    /** One section's answer as saved. */
    public static final class Answer {
        public State state;
        public String value;
        public String summary;
        public List<String> lines = new ArrayList<>();
        /** When this answer was fetched, epoch ms; 0 while loading. */
        public long at;
        /** The last Refresh did not answer, so this is the answer kept from before. */
        public boolean lastTryFailed;
        /** ... and it did not answer because this device had no network. */
        public boolean lastTryOffline;
        /**
         * Places a section found, for the map: each {@code {c, n, lat, lon, i}}
         * (category, name, position, detail lines). Null when it has none.
         */
        public JSONArray points;

        static Answer loading() {
            final Answer a = new Answer();
            a.state = State.LOADING;
            return a;
        }

        JSONObject toJson() throws JSONException {
            final JSONObject o = new JSONObject().put("state", state.name()).put("at", at)
                    .put("lastTryFailed", lastTryFailed).put("lastTryOffline", lastTryOffline);
            if (value != null)
                o.put("value", value);
            if (summary != null)
                o.put("summary", summary);
            final JSONArray l = new JSONArray();
            for (String s : lines)
                l.put(s);
            if (points != null)
                o.put("points", points);
            return o.put("lines", l);
        }

        static Answer fromJson(JSONObject o) {
            final Answer a = new Answer();
            try {
                a.state = State.valueOf(o.optString("state", "FAILED"));
            } catch (IllegalArgumentException e) {
                a.state = State.FAILED;
            }
            a.value = text(o.optString("value", null), 300);
            a.summary = text(o.optString("summary", null), 120);
            a.at = Math.max(0, o.optLong("at", 0));
            a.lastTryFailed = o.optBoolean("lastTryFailed", false);
            a.lastTryOffline = o.optBoolean("lastTryOffline", false);
            a.points = cleanPoints(o.optJSONArray("points"));
            final JSONArray l = o.optJSONArray("lines");
            if (l != null)
                for (int i = 0; i < l.length() && i < 200; i++) {
                    final String s = text(l.optString(i, null), 500);
                    if (s != null)
                        a.lines.add(s);
                }
            return a;
        }
    }

    public final String id;
    public final long made;
    public final Area area;
    /** The county (or place) the area is in, once a section has found it; null until then. */
    public String place;
    /** The name the operator gave this report; null keeps the automatic name. */
    public String label;
    /** This report's own map switch; the Areas switch on the main screen is over all. */
    public boolean onMap = true;
    /** Place categories switched on for the map (Hospitals, Schools ...). */
    public final java.util.Set<String> placesOn = new java.util.LinkedHashSet<>();
    /** Section key to answer, in report order. */
    public final Map<String, Answer> answers = new LinkedHashMap<>();

    public Report(String id, long made, Area area) {
        this.id = id;
        this.made = made;
        this.area = area;
    }

    /** {@code Riverside Co, Nov 12 14:05}. */
    public String name() {
        if (label != null)
            return label;
        return (place != null ? place : "Area") + ", "
                + new SimpleDateFormat("MMM d HH:mm", Locale.US).format(new Date(made));
    }

    public Answer answer(String key) {
        return answers.get(key);
    }

    public int loading() {
        int n = 0;
        for (Answer a : answers.values())
            if (a.state == State.LOADING)
                n++;
        return n;
    }

    public int failed() {
        int n = 0;
        for (Answer a : answers.values())
            if (a.state == State.FAILED || a.state == State.OFFLINE)
                n++;
        return n;
    }

    public JSONObject toJson() throws JSONException {
        final JSONObject o = new JSONObject().put("v", 1).put("id", id).put("made", made)
                .put("area", area.toJson());
        if (place != null)
            o.put("place", place);
        if (label != null)
            o.put("label", label);
        o.put("onMap", onMap);
        o.put("placesOn", new JSONArray(placesOn));
        final JSONObject s = new JSONObject();
        for (Map.Entry<String, Answer> e : answers.entrySet())
            s.put(e.getKey(), e.getValue().toJson());
        return o.put("sections", s);
    }

    /** A report back from disk; null when the file is not one of ours. */
    static Report fromJson(JSONObject o) {
        final String id = o.optString("id", "");
        if (!validId(id))
            return null;
        final Area area = Area.fromJson(o.optJSONArray("area"));
        if (area == null)
            return null;
        final Report r = new Report(id, Math.max(0, o.optLong("made", 0)), area);
        r.place = text(o.optString("place", null), 80);
        r.label = cleanLabel(o.optString("label", null));
        r.onMap = o.optBoolean("onMap", true);
        final JSONArray on = o.optJSONArray("placesOn");
        for (int i = 0; on != null && i < on.length() && i < 20; i++)
            if (on.optString(i, "").matches("[a-z]{1,20}"))
                r.placesOn.add(on.optString(i));
        final JSONObject s = o.optJSONObject("sections");
        if (s != null) {
            final java.util.Iterator<String> keys = s.keys();
            while (keys.hasNext()) {
                final String k = keys.next();
                final JSONObject a = s.optJSONObject(k);
                if (a != null && k.matches("[a-z0-9_]{1,40}"))
                    r.answers.put(k, Answer.fromJson(a));
            }
        }
        return r;
    }

    /** Report ids are our own UUIDs; anything else never names a file. */
    static boolean validId(String id) {
        return id != null && id.matches("[0-9a-f-]{36}");
    }

    static Answer newLoading() {
        return Answer.loading();
    }

    /** Saved places, every field checked; at most 12,000 (500 a category). */
    static JSONArray cleanPoints(JSONArray in) {
        if (in == null)
            return null;
        final JSONArray out = new JSONArray();
        for (int i = 0; i < in.length() && out.length() < 12_000; i++) {
            final JSONObject p = in.optJSONObject(i);
            if (p == null)
                continue;
            final double lat = p.optDouble("lat", Double.NaN), lon = p.optDouble("lon", Double.NaN);
            final String c = p.optString("c", "");
            if (!(Math.abs(lat) <= 90) || !(Math.abs(lon) <= 180) || !c.matches("[a-z]{1,20}"))
                continue;
            try {
                final JSONObject q = new JSONObject().put("c", c).put("lat", lat).put("lon", lon)
                        .put("n", text(p.optString("n", ""), 100) == null ? "Unnamed" : text(p.optString("n", ""), 100));
                final JSONArray info = p.optJSONArray("i"), ci = new JSONArray();
                for (int k = 0; info != null && k < info.length() && k < 12; k++) {
                    final String t = text(info.optString(k, null), 200);
                    if (t != null)
                        ci.put(t);
                }
                final JSONArray g = cleanPaths(p.optJSONArray("g"));
                if (g != null)
                    q.put("g", g);
                // A 911 call's agency and time (ms), for its group and its age.
                final String ag = text(p.optString("ag", ""), 100);
                if (ag != null && !ag.isEmpty())
                    q.put("ag", ag);
                final long t = p.optLong("t", 0);
                if (t > 0)
                    q.put("t", t);
                out.put(q.put("i", ci));
            } catch (JSONException e) {
                // skip it
            }
        }
        return out;
    }

    /** A saved line's paths of [lon, lat], every number checked; at most 4,000 vertices. */
    private static JSONArray cleanPaths(JSONArray in) {
        if (in == null)
            return null;
        final JSONArray out = new JSONArray();
        int vertices = 0;
        for (int i = 0; i < in.length() && i < 200; i++) {
            final JSONArray path = in.optJSONArray(i);
            final JSONArray cp = new JSONArray();
            for (int k = 0; path != null && k < path.length() && vertices < 4000; k++) {
                final JSONArray v = path.optJSONArray(k);
                final double lon = v == null ? Double.NaN : v.optDouble(0), lat = v == null ? Double.NaN : v.optDouble(1);
                if (!(Math.abs(lat) <= 90) || !(Math.abs(lon) <= 180))
                    continue;
                try {
                    cp.put(new JSONArray().put(lon).put(lat));
                } catch (JSONException e) {
                    continue;
                }
                vertices++;
            }
            if (cp.length() >= 2)
                out.put(cp);
        }
        return out.length() == 0 ? null : out;
    }

    /** A name as typed: one line, trimmed, at most 60 characters; null when blank. */
    public static String cleanLabel(String s) {
        if (s == null)
            return null;
        s = s.replaceAll("\\s+", " ").trim();
        if (s.isEmpty())
            return null;
        return s.length() <= 60 ? s : s.substring(0, 60).trim();
    }

    private static String text(String s, int max) {
        if (s == null || s.isEmpty())
            return null;
        return s.length() <= max ? s : s.substring(0, max);
    }
}
