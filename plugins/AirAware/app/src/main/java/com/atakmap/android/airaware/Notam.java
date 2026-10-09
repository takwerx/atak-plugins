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
        // Finite and on the globe, or the record is dropped: a NaN or an infinity
        // handed to the native feature store is a crash, not a bad drawing.
        if (!finite(lat, 90d) || !finite(lon, 180d))
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
                    final double x = c.optDouble(0, Double.NaN);
                    final double y = c.optDouble(1, Double.NaN);
                    if (!finite(x, 180d) || !finite(y, 90d)) {
                        ok = false;
                        break;
                    }
                    pts[j] = new double[] { x, y };
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
                radius(o.optDouble("r", 0d)), rings);
    }

    private static boolean finite(double v, double limit) {
        return !Double.isNaN(v) && !Double.isInfinite(v) && Math.abs(v) <= limit;
    }

    /** A circle's radius, or 0: never negative, never infinite, never wider than a state. */
    private static double radius(double r) {
        if (Double.isNaN(r) || Double.isInfinite(r) || r <= 0d)
            return 0d;
        return Math.min(r, 500_000d);
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
        // An FDC NOTAM leads with its state, "CA..ROUTE ZLA.": the keyword is after
        // the dots. Identifiers stay the way the FAA writes them -- ZLA, FUL, RWY --
        // and only ordinary words are given a capital and lower case.
        final String[] w = text.trim().replaceFirst("^[A-Z]{2}\\.\\.", "")
                .replace("\n", " ").split("\\s+");
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
            else if (sub.isEmpty())
                out = w[0];
            else
                out = w.length > 2 ? sub + " " + w[2] : sub;
        } else if ("obstacle".equals(kind)) {
            // "OBST TOWER LGT (ASR 1049866) 401716N0745222W (2.8NM WNW TTN) 362.2FT
            // (303.1FT AGL) U/S" is the common one, eleven in thirteen: the thing, what
            // is wrong with it, and how tall it is. The pill says those three.
            out = obstacleThing(w.length > 1 && "OBST".equals(w[0]) ? w[1] : w[0]);
            final String body = text.toUpperCase(Locale.US);
            if (body.contains("NOT LGTD") || body.contains("UNLIT"))
                out += " unlit";
            else if (body.matches("(?s).*\\bLGTS?\\b.*\\b(U/S|OTS|UNSERVICEABLE|OUT OF SERVICE)\\b.*"))
                out += " lights out";
            else if (body.contains("LGTD"))
                out += " lit";
            final java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("\\((\\d+(?:\\.\\d+)?)\\s?FT AGL\\)").matcher(body);
            if (m.find()) {
                try {
                    out += " " + Math.round(Double.parseDouble(m.group(1))) + "'";
                } catch (NumberFormatException ignored) {
                    // The height stays off the pill; it is on the page.
                }
            }
        } else {
            final StringBuilder b = new StringBuilder();
            for (int i = 0; i < Math.min(3, w.length); i++) {
                if (b.length() > 0)
                    b.append(' ');
                b.append(w[i]);
            }
            out = b.toString();
        }
        out = out.replaceAll("[.,;]+$", "");
        return out.length() > 26 ? out.substring(0, 25) + "…" : out;
    }

    /** The obstacle in a word, from the FAA's keyword after OBST. */
    private static String obstacleThing(String word) {
        switch (word.toUpperCase(Locale.US).replaceAll("[^A-Z]", "")) {
            case "TOWER":
                return "Tower";
            case "CRANE":
            case "MOBILE":
                return "Crane";
            case "WIND":
                return "Wind turbines";
            case "POWER":
                return "Power line";
            case "RIG":
                return "Rig";
            case "POLE":
                return "Pole";
            case "STACK":
                return "Stack";
            case "BLDG":
                return "Building";
            case "TREE":
                return "Tree";
            default:
                return cap(word);
        }
    }

    private static String cap(String s) {
        if (s == null || s.isEmpty())
            return "";
        final String lower = s.toLowerCase(Locale.US);
        return Character.toUpperCase(lower.charAt(0)) + lower.substring(1);
    }
}
