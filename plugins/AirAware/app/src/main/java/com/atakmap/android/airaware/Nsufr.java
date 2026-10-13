package com.atakmap.android.airaware;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * One National Security UAS Flight Restriction: a place no drone may fly at all.
 *
 * <p>Under 14 CFR 99.7 the FAA prohibits UAS flight over certain military bases,
 * Coast Guard stations, national laboratories, dams and nuclear sites, from the surface
 * to 400 ft above the ground. Unlike a TFR these do not expire, and unlike a UAS
 * Facility Map ceiling they are not a number to fly under: there is no authorization
 * to file for. The record carries what the FAA publishes with each one, including the
 * point of contact for it.
 *
 * <p>Three kinds, by {@link #status}: {@code full-time}, the standing ones; {@code
 * part-time}, active on the proponent's word (the FAA says how); and {@code pending},
 * announced and not yet in force.
 *
 * <p>No ATAK or Android types here on purpose, like {@link Airspace}: this can be run
 * against the pack on a desktop JVM without a device.
 */
public class Nsufr {

    public static final String FULL_TIME = "full-time";
    public static final String PART_TIME = "part-time";
    public static final String PENDING = "pending";

    /** The pack's id: the status prefix and the layer's OBJECTID, unique across the three. */
    public String id = "";
    public String status = FULL_TIME;
    public String proponent = "";
    public String branch = "";
    public String base = "";
    public String facility = "";
    public String airspace = "";
    public String reason = "";
    public String state = "";
    public String faaId = "";
    /** The point of contact the FAA publishes with the restriction, as published. */
    public String poc = "";
    public String floor = "";
    public String ceiling = "";
    public String county = "";
    /** Part-time only: how it is activated, and the FAA's advice on it. */
    public String alertType = "";
    public String adviseInstructions = "";
    public String adviseAuthority = "";
    public String adviseNote = "";

    /** The boundary, one entry per disjoint part, the same shape as an airspace shelf. */
    public final List<Airspace.Part> parts = new ArrayList<>();

    /** Bounding box, {south, west, north, east}, for picking what is in view. */
    public double south = Double.NaN, west = Double.NaN, north = Double.NaN,
            east = Double.NaN;

    public boolean drawable() {
        for (Airspace.Part p : parts)
            if (p.outer.size() >= 3)
                return true;
        return false;
    }

    /** What the operator is told this is: the facility, or the base when it has no name. */
    public String title() {
        if (!facility.isEmpty())
            return facility;
        if (!base.isEmpty())
            return base;
        return "National security restriction";
    }

    /** "Full-time", "Part-time", "Pending", for the row and the details page. */
    public String statusName() {
        return statusName(status);
    }

    public static String statusName(String status) {
        if (PART_TIME.equals(status))
            return "Part-time";
        if (PENDING.equals(status))
            return "Pending";
        return "Full-time";
    }

    /**
     * The one line that says the vertical extent, in the FAA's own words. Every one
     * published so far reads "Surface" to "400' AGL"; this prints what the row says so
     * a different one is not silently normalized.
     */
    public String heights() {
        final String lo = floor.isEmpty() ? "surface" : floor;
        final String hi = ceiling.isEmpty() ? "400' AGL" : ceiling;
        return lo + " to " + hi;
    }

    /**
     * The published ceiling in feet, or 400 when none is readable. Every one published
     * so far is "400' AGL" except 57 at "2,000' MSL" and a handful at 2,000 to 8,000
     * AGL, so the number is read rather than assumed.
     */
    public int ceilingFeet() {
        final StringBuilder digits = new StringBuilder();
        for (int i = 0; i < ceiling.length(); i++) {
            final char c = ceiling.charAt(i);
            if (Character.isDigit(c))
                digits.append(c);
            else if (c != ',' && digits.length() > 0)
                break;
        }
        if (digits.length() == 0 || digits.length() > 6)
            return 400;
        return Integer.parseInt(digits.toString());
    }

    /** Whether the ceiling is measured above the ground. "MSL" anywhere in it says no. */
    public boolean ceilingAgl() {
        return !ceiling.toUpperCase(Locale.US).contains("MSL");
    }

    /** The proponent spelled out where the FAA abbreviates it. */
    public String branchName() {
        final String b = branch.trim().toUpperCase(Locale.US);
        switch (b) {
            case "USA":
                return "U.S. Army";
            case "USN":
                return "U.S. Navy";
            case "USAF":
                return "U.S. Air Force";
            case "AFGSC":
                return "U.S. Air Force Global Strike Command";
            case "USMC":
                return "U.S. Marine Corps";
            case "USCG":
                return "U.S. Coast Guard";
            case "USSF":
                return "U.S. Space Force";
            case "USACE":
                return "U.S. Army Corps of Engineers";
            case "CBP":
                return "U.S. Customs and Border Protection";
            case "BUREAU OF PRISONS":
                return "Federal Bureau of Prisons";
            default:
                return branch;
        }
    }

    /** Ray casting against the outer rings, with holes taken back out. */
    public boolean contains(double lat, double lon) {
        if (lat < south || lat > north || lon < west || lon > east)
            return false;
        for (Airspace.Part p : parts) {
            if (!TfrVertical.inside(p.outer, lat, lon))
                continue;
            boolean inHole = false;
            for (List<double[]> h : p.holes)
                if (TfrVertical.inside(h, lat, lon)) {
                    inHole = true;
                    break;
                }
            if (!inHole)
                return true;
        }
        return false;
    }

    /** Every restriction in the pack, in the pack's order. */
    public static List<Nsufr> parse(JSONObject root) {
        final List<Nsufr> out = new ArrayList<>();
        final JSONArray features = root == null ? null : root.optJSONArray("features");
        if (features == null)
            return out;
        for (int i = 0; i < features.length(); i++) {
            final Nsufr n = one(features.optJSONObject(i));
            if (n != null && n.drawable())
                out.add(n);
        }
        return out;
    }

    private static Nsufr one(JSONObject f) {
        if (f == null)
            return null;
        final JSONObject at = f.optJSONObject("attributes");
        final JSONObject geom = f.optJSONObject("geometry");
        if (at == null || geom == null)
            return null;
        final Nsufr n = new Nsufr();
        n.id = f.optString("id", "");
        final String status = f.optString("status", FULL_TIME);
        n.status = PART_TIME.equals(status) || PENDING.equals(status) ? status : FULL_TIME;
        if (n.id.isEmpty())
            n.id = n.status.substring(0, 2) + ":" + at.optLong("OBJECTID", 0L);
        n.proponent = text(at, "Proponent");
        n.branch = text(at, "Branch");
        n.base = text(at, "Base");
        n.facility = text(at, "Facility");
        n.airspace = text(at, "Airspace");
        n.reason = text(at, "Reason");
        n.state = text(at, "State");
        n.faaId = text(at, "FAA_ID");
        n.poc = text(at, "POC");
        n.floor = text(at, "Floor");
        n.ceiling = text(at, "Ceiling");
        n.county = text(at, "County");
        n.alertType = text(at, "ALERTYPE");
        n.adviseInstructions = text(at, "ADVISEINST");
        n.adviseAuthority = text(at, "ADVISEAUTH");
        n.adviseNote = text(at, "ADVISENOTE");

        final JSONArray rings = geom.optJSONArray("rings");
        if (rings == null)
            return null;
        AirspaceFeed.readRings(n.parts, rings);
        n.box();
        return n;
    }

    private void box() {
        double s = Double.MAX_VALUE, w = Double.MAX_VALUE;
        double nn = -Double.MAX_VALUE, e = -Double.MAX_VALUE;
        for (Airspace.Part p : parts) {
            for (double[] pt : p.outer) {
                s = Math.min(s, pt[0]);
                nn = Math.max(nn, pt[0]);
                w = Math.min(w, pt[1]);
                e = Math.max(e, pt[1]);
            }
        }
        if (s <= nn) {
            south = s;
            north = nn;
            west = w;
            east = e;
        }
    }

    private static String text(JSONObject at, String key) {
        if (at.isNull(key))
            return "";
        return at.optString(key, "").trim();
    }
}
