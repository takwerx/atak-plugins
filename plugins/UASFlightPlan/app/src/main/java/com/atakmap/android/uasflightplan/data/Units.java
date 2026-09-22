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
