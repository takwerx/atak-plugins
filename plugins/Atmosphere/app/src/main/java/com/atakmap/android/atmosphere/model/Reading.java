package com.atakmap.android.atmosphere.model;

import com.atakmap.android.atmosphere.units.Quantity;
import com.atakmap.android.atmosphere.units.UnitSystem;
import com.atakmap.android.atmosphere.units.Units;

/** One variable at one time, stored in its canonical unit (see {@link Units}). */
public final class Reading {

    public final String key;
    public final String label;
    public final Quantity quantity;
    /** Canonical value, or {@link Double#NaN} when the provider had nothing. */
    public final double value;

    public Reading(String key, String label, Quantity quantity, double value) {
        this.key = key;
        this.label = label;
        this.quantity = quantity;
        this.value = value;
    }

    public boolean valid() {
        return !Double.isNaN(value);
    }

    /** Formatted for display, or an em dash when the provider had no value. */
    public String format(UnitSystem system) {
        if (!valid())
            return "—";
        if (quantity == Quantity.ANGLE)
            return Units.format(quantity, value, system) + " "
                    + Units.degreesToCompass(value);
        return Units.format(quantity, value, system);
    }
}
