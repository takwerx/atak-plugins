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
 * only: nothing in it is set by the operator (PLAN decision 5).
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
    /** Section key to answer, in report order. */
    public final Map<String, Answer> answers = new LinkedHashMap<>();

    public Report(String id, long made, Area area) {
        this.id = id;
        this.made = made;
        this.area = area;
    }

    /** {@code Riverside Co, Nov 12 14:05}. */
    public String name() {
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

    private static String text(String s, int max) {
        if (s == null || s.isEmpty())
            return null;
        return s.length() <= max ? s : s.substring(0, max);
    }
}
