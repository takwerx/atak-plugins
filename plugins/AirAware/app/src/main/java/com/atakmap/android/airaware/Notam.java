package com.atakmap.android.airaware;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TimeZone;

/**
 * One NOTAM as the relay publishes it: the FAA's record trimmed to what a crew on the
 * ground needs, with the area already decoded out of the text.
 *
 * <p>The relay ({@code tools/notam_relay.py} in the notes repo) holds takwerx's one FAA
 * key and the whole national picture, and cuts it into the two-degree tiles this reads.
 * The phone never calls the FAA: the NOTAM Management Service allows one delta pull
 * every three minutes under one key, and that is one relay's budget, not a fleet's.
 *
 * <p>A NOTAM's area is mostly not in its geometry. The FAA's GeoJSON is a single point
 * for nineteen in twenty, and the circle or polygon of a UAS or parachute NOTAM is in
 * its prose -- "3.4NM RADIUS OF 293012N0951234W". The relay decodes that and this
 * carries the result: a radius in meters, or rings of lon/lat, or neither.
 */
public final class Notam {

    /** The kinds the relay sorts NOTAMs into, from the FAA's own feature keyword. */
    public static final String[] KINDS = { "airspace", "obstacle", "airfield",
            "navigation", "other" };

    /** On by default: what is in the air or sticking up into it, and the airfield. */
    public static final Set<String> DEFAULT_ON = new HashSet<>(
            Arrays.asList("airspace", "obstacle", "airfield"));

    public final String id;
    public final String number;
    public final String kind;
    /** DOM, FDC, MIL or INTL: the FAA's classification, for the details page. */
    public final String classification;
    public final String location;
    public final String text;
    public final String lower;
    public final String upper;
    public final String updated;
    public final long startMs;
    /** {@link Long#MAX_VALUE} for a permanent NOTAM or one with no end. */
    public final long endMs;
    public final boolean estimatedEnd;
    public final double lat;
    public final double lon;
    /** A circle's radius in meters, or 0 when the NOTAM has no circle. */
    public final double radiusM;
    /** Polygon rings as lon/lat pairs; empty when the NOTAM has no polygon. */
    public final List<double[][]> rings;

    /** Scratch for the nearest-first sort; meters from the middle of the view. */
    public double distanceM;

    private Notam(String id, String number, String kind, String classification,
            String location, String text, String lower, String upper, String updated,
            long startMs, long endMs, boolean estimatedEnd, double lat, double lon,
            double radiusM, List<double[][]> rings) {
        this.id = id;
        this.number = number;
        this.kind = kind;
        this.classification = classification;
        this.location = location;
        this.text = text;
        this.lower = lower;
        this.upper = upper;
        this.updated = updated;
        this.startMs = startMs;
        this.endMs = endMs;
        this.estimatedEnd = estimatedEnd;
        this.lat = lat;
        this.lon = lon;
        this.radiusM = radiusM;
        this.rings = rings;
    }

    /** One record out of a tile, or null when it cannot be placed. */
    static Notam from(JSONObject o) {
        if (o == null)
            return null;
        final String id = o.optString("id", "");
        if (id.isEmpty() || !o.has("lat") || !o.has("lon"))
            return null;
        final double lat = o.optDouble("lat", Double.NaN);
        final double lon = o.optDouble("lon", Double.NaN);
        if (Double.isNaN(lat) || Double.isNaN(lon))
            return null;
        final List<double[][]> rings = new ArrayList<>();
        final JSONArray p = o.optJSONArray("p");
        if (p != null) {
            for (int i = 0; i < p.length(); i++) {
                final JSONArray ring = p.optJSONArray(i);
                if (ring == null || ring.length() < 4)
                    continue;
                final double[][] pts = new double[ring.length()][];
                boolean ok = true;
                for (int j = 0; j < ring.length(); j++) {
                    final JSONArray c = ring.optJSONArray(j);
                    if (c == null || c.length() < 2) {
                        ok = false;
                        break;
                    }
                    pts[j] = new double[] { c.optDouble(0), c.optDouble(1) };
                }
                if (ok)
                    rings.add(pts);
            }
        }
        String kind = o.optString("k", "other");
        if (!Arrays.asList(KINDS).contains(kind))
            kind = "other";
        final String end = o.optString("e", "");
        return new Notam(id, o.optString("n", ""), kind, o.optString("c", ""),
                o.optString("loc", ""), o.optString("t", ""), o.optString("lo", ""),
                o.optString("up", ""), o.optString("u", ""),
                parseTime(o.optString("s", "")), end.isEmpty() ? Long.MAX_VALUE : parseTime(end),
                "true".equalsIgnoreCase(o.optString("est", "")), lat, lon,
                Math.max(0d, o.optDouble("r", 0d)), rings);
    }

    /** An FAA time ("2026-10-09T13:59:00Z", with or without millis), PERM, or nothing. */
    public static long parseTime(String s) {
        if (s == null || s.length() < 19)
            return s != null && s.startsWith("PERM") ? Long.MAX_VALUE : 0L;
        try {
            final SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US);
            f.setTimeZone(TimeZone.getTimeZone("UTC"));
            return f.parse(s.substring(0, 19)).getTime();
        } catch (ParseException e) {
            return 0L;
        }
    }

    public static String kindName(String kind) {
        switch (kind) {
            case "airspace":
                return "Airspace";
            case "obstacle":
                return "Obstacles";
            case "airfield":
                return "Airfields";
            case "navigation":
                return "Navigation";
            default:
                return "Other";
        }
    }

    public boolean hasArea() {
        return radiusM > 0 || !rings.isEmpty();
    }

    /** In effect now: started, and not ended. A missing start counts as started. */
    public boolean isActive(long now) {
        return startMs <= now && endMs > now;
    }

    public boolean isFuture(long now) {
        return startMs > now;
    }

    public boolean isExpired(long now) {
        return endMs <= now;
    }

    /** The details page title: "Airspace NOTAM 12/145" */
    public String title() {
        return kindName(kind).replace("Obstacles", "Obstacle").replace("Airfields", "Airfield")
                + " NOTAM" + (number.isEmpty() ? "" : " " + number);
    }

    /**
     * What the pill on the map says: the thing, in a word or three.
     *
     * <p>"UAS", "Tower 456'", "RWY 08L/26R CLSD". The FAA's text leads with its subject
     * and that is nearly always the right label; the few abbreviations a crew would not
     * know are spelled out.
     */
    public String label() {
        final String[] w = text.trim().replace("\n", " ").split("\\s+");
        if (w.length == 0 || w[0].isEmpty())
            return number;
        String out;
        if ("airspace".equals(kind)) {
            final String body = text.toUpperCase(Locale.US);
            final String sub = w.length > 1 ? w[1] : "";
            if (body.contains("UAS"))
                out = "UAS";
            else if (sub.startsWith("PJE") || body.contains("PARACHUTE"))
                out = "Parachute";
            else if (body.contains("ROCKET"))
                out = "Rocket";
            else if (body.contains("PYROTECHNIC") || body.contains("FIREWORKS"))
                out = "Fireworks";
            else if (body.contains("AEROBATIC"))
                out = "Aerobatics";
            else if (body.contains("AIRDROP"))
                out = "Airdrop";
            else if (body.contains("BLASTING"))
                out = "Blasting";
            else if (body.contains("LASER"))
                out = "Laser";
            else if (body.contains("BALLOON"))
                out = "Balloon";
            else if (body.contains("GLD") || body.contains("GLIDER"))
                out = "Gliders";
            else if (body.contains("FLTCK") || body.contains("FLIGHT CHECK"))
                out = "Flight check";
            else if (w[0].startsWith("!SUA") || body.contains("SUA "))
                out = "Special use";
            else
                out = cap(sub.isEmpty() ? w[0] : sub);
        } else if ("obstacle".equals(kind)) {
            final String what = w.length > 1 && "OBST".equals(w[0]) ? w[1] : w[0];
            final java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("\\((\\d+)\\s?FT AGL\\)").matcher(text);
            out = cap(what) + (m.find() ? " " + m.group(1) + "'" : "");
        } else {
            final StringBuilder b = new StringBuilder();
            for (int i = 0; i < Math.min(3, w.length); i++) {
                if (b.length() > 0)
                    b.append(' ');
                b.append(w[i]);
            }
            out = b.toString();
        }
        return out.length() > 22 ? out.substring(0, 21) + "…" : out;
    }

    private static String cap(String s) {
        if (s == null || s.isEmpty())
            return "";
        final String lower = s.toLowerCase(Locale.US);
        return Character.toUpperCase(lower.charAt(0)) + lower.substring(1);
    }
}
