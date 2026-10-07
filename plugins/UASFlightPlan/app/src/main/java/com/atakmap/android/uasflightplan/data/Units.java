package com.atakmap.android.uasflightplan.data;

import android.content.SharedPreferences;
import android.preference.PreferenceManager;

import com.atakmap.android.maps.MapView;
import com.atakmap.android.preference.UnitPreferences;
import com.atakmap.coremap.conversions.Span;
import com.atakmap.coremap.conversions.SpanUtilities;

import java.util.Locale;

/**
 * Distances and altitudes in whatever units the operator has already told ATAK
 * they want. Never a hardcoded unit.
 *
 * <p>Distances follow {@code rab_rng_units_pref}, whose stored value <em>is</em> the
 * {@link Span} type constant: {@code "0"} is {@link Span#ENGLISH}, {@code "1"} is
 * {@link Span#METRIC}, {@code "2"} is {@link Span#NM}. Zero is English, not metric;
 * assuming the obvious ordering gets it exactly backwards.
 *
 * <p>Altitudes follow ATAK's own altitude unit preference through
 * {@link UnitPreferences#getAltitudeUnits()}, feet or meters. The reference is
 * always MSL and is always written out beside the number, whatever ATAK's altitude
 * display preference says: a pilot reading an HAE number as MSL is off by the geoid
 * height, about 100 ft in California, and the aircraft does not know the difference.
 *
 * <p>Read on each call rather than cached: either preference can change in ATAK's
 * settings while the pane is open.
 */
public final class Units {

    private Units() {
    }

    /** @return one of {@link Span#ENGLISH}, {@link Span#METRIC}, {@link Span#NM} */
    public static int rangeType() {
        try {
            final MapView mv = MapView.getMapView();
            if (mv != null) {
                final SharedPreferences p = PreferenceManager
                        .getDefaultSharedPreferences(mv.getContext());
                return Integer.parseInt(p.getString("rab_rng_units_pref",
                        String.valueOf(Span.ENGLISH)));
            }
        } catch (RuntimeException e) {
            // A malformed preference must not stop the pane drawing.
        }
        return Span.ENGLISH;
    }

    /** Format a distance in meters the way ATAK would, e.g. "1.0 mi" or "1.6 km". */
    public static String distance(double meters) {
        try {
            return SpanUtilities.formatType(rangeType(), meters, Span.METER);
        } catch (RuntimeException e) {
            return Math.round(meters) + " m";
        }
    }

    /** True when ATAK shows altitudes in feet, which is its default. */
    public static boolean altitudeInFeet() {
        try {
            final MapView mv = MapView.getMapView();
            if (mv != null) {
                final Span s = new UnitPreferences(mv.getContext()).getAltitudeUnits();
                // ATAK offers feet and meters only.
                return s != Span.METER;
            }
        } catch (RuntimeException e) {
            // Fall through to the default.
        }
        return true;
    }

    public static String altitudeUnit() {
        return altitudeInFeet() ? "ft" : "m";
    }

    public static double toAltitudeUnit(double meters) {
        return altitudeInFeet()
                ? SpanUtilities.convert(meters, Span.METER, Span.FOOT)
                : meters;
    }

    public static double feetToMeters(double feet) {
        return SpanUtilities.convert(feet, Span.FOOT, Span.METER);
    }

    public static double metersToFeet(double meters) {
        return SpanUtilities.convert(meters, Span.METER, Span.FOOT);
    }

    /**
     * A preset distance in the operator's large unit, pinned: "0.5 mi", never
     * "2640 ft". ATAK's own formatter switches to the small unit below a threshold,
     * which puts one preset in feet beside the others in miles.
     */
    public static String largeUnit(double meters) {
        final int t = rangeType();
        final double v;
        final String unit;
        if (t == Span.METRIC) {
            v = meters / 1000d;
            unit = "km";
        } else if (t == Span.NM) {
            v = meters / 1852d;
            unit = "NM";
        } else {
            v = meters / 1609.344d;
            unit = "mi";
        }
        final String n = Math.abs(v - Math.rint(v)) < 0.01d
                ? String.format(Locale.US, "%.0f", v)
                : String.format(Locale.US, "%.1f", v);
        return n + " " + unit;
    }

    /** The presets for the circle, in the operator's large unit, as meters. */
    public static double[] circlePresetsM() {
        final int t = rangeType();
        if (t == Span.METRIC)
            return new double[] { 1000d, 2000d, 3000d, 5000d };
        if (t == Span.NM)
            return new double[] { 0.5d * 1852d, 1852d, 2d * 1852d, 3d * 1852d };
        return new double[] { 0.5d * 1609.344d, 1609.344d, 2d * 1609.344d, 3d * 1609.344d };
    }

    /** An altitude: "1,043 ft MSL". The reference is part of the number. */
    public static String altitudeMsl(double meters) {
        return String.format(Locale.US, "%,.0f %s MSL", toAltitudeUnit(meters),
                altitudeUnit());
    }

    /** A height difference: "1,000 ft". No reference, because it has none. */
    public static String height(double meters) {
        return String.format(Locale.US, "%,.0f %s", toAltitudeUnit(meters),
                altitudeUnit());
    }
}
