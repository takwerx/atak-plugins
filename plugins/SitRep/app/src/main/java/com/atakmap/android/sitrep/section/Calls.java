package com.atakmap.android.sitrep.section;

import com.atakmap.android.sitrep.Area;
import com.atakmap.android.sitrep.net.Esri;
import com.atakmap.android.sitrep.net.Http;
import com.atakmap.android.sitrep.net.Socrata;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Recent 911 / dispatch calls in the area, from the agencies that publish their own
 * (operator, 2026-11-14: "consolidated information for responders"). Which agencies
 * publish, and how, is a hosted catalog (survey: notes NOTES-sitrep-public-call-data.md),
 * so a feed that moves is a catalog edit, not a release.
 *
 * <p>Only feeds with a map point are read, so a call is counted only when it is inside
 * the area. A call is never shown finer than the agency shows it, and medical-type
 * calls are cut to the block: Sit Rep puts them in front of a whole team.
 */
public final class Calls extends Section {

    static final String CATALOG = "https://mapdepot.takwerx.org/sitrep/calls/catalog.json";
    /** The window the report counts: calls received in the last two hours. */
    static final long WINDOW_MS = 2L * 3600 * 1000;
    /** The longest window a catalog row may ask for (hours). */
    static final int MAX_WINDOW_HOURS = 72;
    private static final int SHOW_PER_AGENCY = 15;

    /** Call types cut to the block (house number to its hundred). */
    private static final Pattern PRIVATE = Pattern.compile(
            "(?i)medic|medical|\\baid\\b|\\bems\\b|\\bals\\b|\\bbls\\b|ambulance|sick|breath|cardiac|chest|heart|"
                    + "fall|unconscious|overdose|poison|seizure|stroke|bleed|injur|psych|mental|welfare|"
                    + "suicid|assault|sex|rape|domestic|abuse|child|death|dead|doa|diabet|pregnan|birth");
    private static final Pattern HOUSE_NUMBER = Pattern.compile("^\\s*(\\d+)\\s+(.*)$");

    public Calls() {
        super("calls", "911 calls", true,
                "Public 911 and dispatch call feeds, each published by its own agency; the list of feeds is the takwerx call catalog.");
    }

    @Override
    public Result fetch(Area area) throws Exception {
        final JSONArray feeds = new JSONObject(Http.get(CATALOG)).optJSONArray("feeds");
        if (feeds == null)
            throw new java.io.IOException("no feeds in the call catalog");
        final List<JSONObject> here = new ArrayList<>();
        for (int i = 0; i < feeds.length(); i++) {
            final JSONObject f = feeds.optJSONObject(i);
            // Only https feeds; anything else in the catalog is skipped.
            if (f == null || !f.optString("url", "").startsWith("https://"))
                continue;
            final JSONArray b = f.optJSONArray("bbox");
            if (b != null && b.length() == 4 && b.optDouble(0) <= area.east && b.optDouble(2) >= area.west
                    && b.optDouble(1) <= area.north && b.optDouble(3) >= area.south)
                here.add(f);
        }
        if (here.isEmpty()) {
            final Result r = new Result("No public call feed here", null);
            r.notPublishedHere = true;
            return r.line("No agency here publishes its 911 calls in a form Sit Rep can read. "
                    + "Many show them only in the PulsePoint app.");
        }
        final long now = System.currentTimeMillis();
        final JSONArray points = new JSONArray();
        final List<String> lines = new ArrayList<>();
        final List<String> failed = new ArrayList<>();
        int total = 0;
        boolean late = false;
        for (JSONObject f : here) {
            final List<Call> calls;
            final long window = windowMs(f);
            try {
                calls = "socrata".equals(f.optString("adapter")) ? socrata(f, area, now, window)
                        : arcgis(f, area, now, window);
            } catch (Exception e) {
                failed.add(f.optString("agency"));
                continue;
            }
            Collections.sort(calls, new Comparator<Call>() {
                @Override
                public int compare(Call a, Call b) {
                    return Long.compare(b.time, a.time);
                }
            });
            // A feed that publishes hours to days late (Chicago crashes) names its own window
            // and is not part of the "last 2 h" count: it would read as "none" every time.
            final boolean slow = window > WINDOW_MS;
            late |= slow;
            if (!slow)
                total += calls.size();
            lines.add(GROUP + f.optString("agency") + " (" + f.optString("kind") + "): " + calls.size()
                    + (slow ? " in the last " + (window / 3_600_000L) + " h" : ""));
            for (int i = 0; i < calls.size() && i < SHOW_PER_AGENCY; i++)
                lines.add("  " + calls.get(i).describe(now));
            for (Call c : calls)
                points.put(c.point(f.optString("agency")));
            if (calls.size() > SHOW_PER_AGENCY)
                lines.add("  ... and " + (calls.size() - SHOW_PER_AGENCY) + " more.");
        }
        if (lines.isEmpty() && !failed.isEmpty())
            throw new java.io.IOException("no call feed answered");
        final Result r = new Result(total == 0 ? "None in the last 2 h" : total + " in the last 2 h",
                total == 0 ? null : total + " 911 calls");
        r.lines.addAll(lines);
        r.points = points;
        for (String a : failed)
            r.line(a + ": did not answer.");
        r.line("Calls the agencies publish, received in the last 2 hours, inside the area. "
                + "Agencies leave out some calls; medical calls are shown to the block.");
        if (late)
            r.line("Some agencies publish hours to days late. Those are listed with their own time window and are not in the count above.");
        return r;
    }

    // ------------------------------------------------------------------ adapters

    /** A Socrata dataset: a box around the area and the time window, in SoQL. */
    private static List<Call> socrata(JSONObject f, Area area, long now, long window) throws Exception {
        final String point = f.optString("point"), time = f.optString("time");
        final boolean utc = "utc".equals(f.optString("time_kind"));
        final SimpleDateFormat fmt = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US);
        fmt.setTimeZone(TimeZone.getTimeZone(utc ? "UTC" : f.optString("tz", "UTC")));
        final String since = fmt.format(new Date(now - window));
        String where = String.format(Locale.US, "within_box(%s, %.6f, %.6f, %.6f, %.6f) AND %s > '%s'",
                field(point), area.north, area.west, area.south, area.east, field(time), since);
        if (!f.optString("where", "").isEmpty())
            where += " AND " + f.optString("where");
        final String url = f.optString("url") + "?$where=" + Esri.enc(where)
                + "&$order=" + Esri.enc(field(time) + " DESC") + "&$limit=500";
        final JSONArray rows = new JSONArray(Http.get(url));
        final List<Call> out = new ArrayList<>();
        for (int i = 0; i < rows.length(); i++) {
            final JSONObject a = rows.optJSONObject(i);
            // GeoJSON point, legacy location, or latitude and longitude columns.
            final double[] at = Socrata.latLon(a, point);
            if (at == null)
                continue;
            final double lat = at[0], lon = at[1];
            if (!area.contains(lat, lon))
                continue;
            final long t = parse(a.optString(time, ""), fmt);
            if (t <= 0 || now - t > window)
                continue;
            out.add(new Call(t, text(a, f.optString("type")), text(a, f.optString("address")),
                    text(a, f.optString("place", "")), lat, lon));
        }
        return out;
    }

    /** An ArcGIS layer: the area's outline, newest first, the window checked here. */
    private static List<Call> arcgis(JSONObject f, Area area, long now, long window) throws Exception {
        final String time = f.optString("time");
        final String where = f.optString("where", "").isEmpty() ? "1=1" : f.optString("where");
        final String form = "where=" + Esri.enc(where) + "&f=json&outFields=*&returnGeometry=true&outSR=4326"
                + "&orderByFields=" + Esri.enc(field(time) + " DESC") + "&resultRecordCount=500"
                + area.esriQueryForm();
        final JSONObject o = new JSONObject(Http.post(f.optString("url") + "/query", form));
        if (o.has("error"))
            throw new java.io.IOException("service error: " + o.optJSONObject("error"));
        final JSONArray feats = o.optJSONArray("features");
        if (feats == null)
            throw new java.io.IOException("no features in the answer");
        final List<Call> out = new ArrayList<>();
        for (int i = 0; i < feats.length(); i++) {
            final JSONObject ft = feats.optJSONObject(i);
            final JSONObject a = ft == null ? null : ft.optJSONObject("attributes");
            final JSONObject g = ft == null ? null : ft.optJSONObject("geometry");
            if (a == null || g == null)
                continue;
            final double lon = g.optDouble("x"), lat = g.optDouble("y");
            if (!area.contains(lat, lon))
                continue;
            long t = a.optLong(time, 0);
            if ("epoch_date_hhmm".equals(f.optString("time_kind"))) {
                // A local date at midnight plus "HHMM" (Fairfax).
                final String hhmm = a.optString(f.optString("time_hhmm"), "");
                if (t > 0 && hhmm.matches("\\d{4}"))
                    t += (Integer.parseInt(hhmm.substring(0, 2)) * 60L + Integer.parseInt(hhmm.substring(2))) * 60_000L;
                else
                    t = 0;
            }
            if (t <= 0 || now - t > window)
                continue;
            out.add(new Call(t, text(a, f.optString("type")), text(a, f.optString("address")),
                    text(a, f.optString("place", "")), lat, lon));
        }
        return out;
    }

    // ------------------------------------------------------------------ helpers

    /** A row's time window: {@code window_hours} (1 to 72), else the live 2 hours. */
    static long windowMs(JSONObject f) {
        final int h = f.optInt("window_hours", 0);
        return h <= 0 ? WINDOW_MS : Math.min(h, MAX_WINDOW_HOURS) * 3_600_000L;
    }

    /** A catalog field name, letters, digits and underscores only, so it can sit in a query. */
    private static String field(String name) throws java.io.IOException {
        if (name == null || !name.matches("[A-Za-z_][A-Za-z0-9_]{0,63}"))
            throw new java.io.IOException("bad field name in the call catalog");
        return name;
    }

    private static long parse(String s, SimpleDateFormat fmt) {
        try {
            return fmt.parse(s.length() > 19 ? s.substring(0, 19) : s).getTime();
        } catch (Exception e) {
            return 0;
        }
    }

    /** A field's text, cleaned of markup and cut short. */
    private static String text(JSONObject a, String key) {
        if (key == null || key.isEmpty() || a.isNull(key))
            return "";
        String s = a.optString(key, "").replaceAll("<[^>]*>", " ").replace("&#160;", " ")
                .replaceAll("\\s+", " ").trim();
        if (s.length() > 80)
            s = s.substring(0, 80);
        return s;
    }

    static final class Call {
        final long time;
        final String type, address, place;
        final double lat, lon;

        Call(long time, String type, String address, String place, double lat, double lon) {
            this.time = time;
            this.type = type.isEmpty() ? "Call" : type;
            this.address = PRIVATE.matcher(type).find() ? block(address) : address;
            this.place = place;
            this.lat = lat;
            this.lon = lon;
        }

        /** {@code 13 min ago: Medic Response, 900 block N 143rd St}. */
        String describe(long now) {
            final long min = Math.max(0, (now - time) / 60_000L);
            final String ago = min < 60 ? min + " min ago" : (min / 60) + " h " + (min % 60) + " min ago";
            final StringBuilder b = new StringBuilder(ago).append(": ").append(type);
            if (!address.isEmpty())
                b.append(", ").append(address);
            if (!place.isEmpty())
                b.append(", ").append(place);
            return b.toString();
        }

        /** For the map and the pane: the "calls" place category, with agency and time. */
        JSONObject point(String agency) throws org.json.JSONException {
            final SimpleDateFormat hm = new SimpleDateFormat("MMM d HH:mm", Locale.US);
            final JSONArray i = new JSONArray().put(agency + ".").put("Received " + hm.format(new Date(time)) + ".");
            final String where = address.isEmpty() ? place : (place.isEmpty() ? address : address + ", " + place);
            if (!where.isEmpty())
                i.put(where);
            return new JSONObject().put("c", "calls").put("n", type).put("lat", lat).put("lon", lon)
                    .put("ag", agency).put("t", time).put("i", i);
        }

        /** "916 N 143rd St" to "900 block N 143rd St"; anything else as it is. */
        private static String block(String address) {
            final Matcher m = HOUSE_NUMBER.matcher(address);
            if (!m.matches())
                return address;
            try {
                final long n = Long.parseLong(m.group(1));
                return (n / 100 * 100) + " block " + m.group(2);
            } catch (NumberFormatException e) {
                return m.group(2);
            }
        }
    }
}
