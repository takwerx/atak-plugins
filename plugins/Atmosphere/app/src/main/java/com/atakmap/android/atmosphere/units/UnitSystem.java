package com.atakmap.android.atmosphere.units;

/**
 * How readings are shown to the operator. Values are always stored canonically
 * (see {@link Units}); the unit system is a display decision only, so switching
 * it never triggers a refetch and never loses precision.
 */
public enum UnitSystem {
    METRIC("Metric"),
    IMPERIAL("Imperial"),
    /** Imperial with aviation conventions: knots for wind, inHg for pressure. */
    AVIATION("Aviation");

    private final String label;

    UnitSystem(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    public static UnitSystem fromName(String name, UnitSystem fallback) {
        if (name == null)
            return fallback;
        for (UnitSystem u : values()) {
            if (u.name().equalsIgnoreCase(name))
                return u;
        }
        return fallback;
    }
}
