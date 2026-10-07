package com.atakmap.android.uasflightplan.obstacles;

import java.util.Locale;

/** One record of the FAA Digital Obstacle File, as the plugin uses it. */
public final class Obstacle {

    /** FAA's number for it, e.g. "06-012345". */
    public final String oas;
    /** FAA's type code, trimmed: "TOWER", "T-L TWR", "CATENARY". */
    public final String typeCode;
    public final double lat;
    public final double lon;
    /** Height above the ground, feet. */
    public final double aglFt;
    /** Height of the top above sea level, feet. */
    public final double amslFt;
    /** FAA lighting code. */
    public final String lighting;
    /** "O" verified, "U" unverified. */
    public final String verified;
    public final int quantity;
    public final String city;
    public final String state;
    /** Distance from the launch point, meters. */
    public final double distanceM;

    public Obstacle(String oas, String typeCode, double lat, double lon, double aglFt,
            double amslFt, String lighting, String verified, int quantity, String city,
            String state, double distanceM) {
        this.oas = oas;
        this.typeCode = typeCode;
        this.lat = lat;
        this.lon = lon;
        this.aglFt = aglFt;
        this.amslFt = amslFt;
        this.lighting = lighting;
        this.verified = verified;
        this.quantity = quantity;
        this.city = city;
        this.state = state;
        this.distanceM = distanceM;
    }

    /** True when the top is at or above the ceiling: it sticks up through it. */
    public boolean aboveCeiling(double ceilingMslFt) {
        return amslFt >= ceilingMslFt;
    }

    /** The kind, in words a pilot uses, from FAA's code. */
    public String kind() {
        switch (typeCode) {
            case "TOWER":
                return "Tower";
            case "T-L TWR":
                return "Transmission tower";
            case "CATENARY":
                return "Wire span";
            case "POLE":
                return "Pole";
            case "UTILITY POLE":
                return "Utility pole";
            case "WINDMILL":
                return "Wind turbine";
            case "BLDG":
                return "Building";
            case "STACK":
                return "Stack";
            case "TANK":
                return "Tank";
            case "FENCE":
                return "Fence";
            case "SIGN":
                return "Sign";
            case "SOLAR PANELS":
                return "Solar panels";
            case "NAVAID":
                return "Navaid";
            case "GEN UTIL":
                return "Utility structure";
            case "ELEC SYS":
                return "Electrical structure";
            case "CRANE":
                return "Crane";
            case "BRIDGE":
                return "Bridge";
            case "DAM":
                return "Dam";
            case "POWER PLANT":
                return "Power plant";
            case "REFINERY":
                return "Refinery";
            case "BLDG-TWR":
                return "Building with tower";
            case "CTRL TWR":
                return "Control tower";
            case "MONUMENT":
                return "Monument";
            case "SPIRE":
                return "Spire";
            case "DOME":
                return "Dome";
            case "RIG":
                return "Rig";
            case "VERTICAL STRUCTURE":
                return "Vertical structure";
            default:
                return titleCase(typeCode);
        }
    }

    /** "lit", "unlit" or "lighting unknown", from FAA's lighting code. */
    public String lightingWords() {
        if (lighting == null || lighting.isEmpty() || "U".equals(lighting))
            return "lighting unknown";
        if ("N".equals(lighting))
            return "unlit";
        return "lit";
    }

    public boolean isVerified() {
        return "O".equals(verified);
    }

    private static String titleCase(String s) {
        if (s == null || s.isEmpty())
            return "Obstacle";
        final String lower = s.toLowerCase(Locale.US);
        return Character.toUpperCase(lower.charAt(0)) + lower.substring(1);
    }
}
