package com.atakmap.android.atmosphere.units;

/**
 * What a reading measures. A source definition names the quantity so the plugin
 * knows how to convert and how to label it — the API's own field name is not
 * something we can infer meaning from.
 */
public enum Quantity {
    TEMPERATURE,
    SPEED,
    LENGTH,
    PRECIPITATION,
    PRESSURE,
    ANGLE,
    PERCENT,
    /** No unit and no conversion (weather codes, indices, counts). */
    SCALAR;

    public static Quantity fromName(String name) {
        if (name == null)
            return SCALAR;
        String n = name.trim().toUpperCase();
        if (n.equals("PRECIP"))
            n = "PRECIPITATION";
        if (n.equals("DIRECTION") || n.equals("BEARING"))
            n = "ANGLE";
        for (Quantity q : values()) {
            if (q.name().equals(n))
                return q;
        }
        return SCALAR;
    }
}
