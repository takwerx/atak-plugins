package com.atakmap.android.sitrep;

import android.content.SharedPreferences;
import android.preference.PreferenceManager;

import com.atakmap.android.maps.MapView;
import com.atakmap.coremap.conversions.Span;
import com.atakmap.coremap.conversions.SpanUtilities;

/**
 * Distances in whatever units the operator has already told ATAK they want.
 *
 * <p>IPAWS's {@code Units}, carried forward (there is no shared module).
 *
 * <p>ATAK keeps the choice in {@code rab_rng_units_pref}, and the stored value is the
 * {@link Span} constant itself: "0" is {@link Span#ENGLISH}, "1" {@link Span#METRIC},
 * "2" {@link Span#NM}. Note that 0 is English, not metric -- assuming the obvious
 * ordering gets it exactly backwards. Read on each call, because it can change in
 * ATAK's settings while the pane is open.
 */
public final class Units {

    private Units() {
    }

    /** @return one of {@link Span#ENGLISH}, {@link Span#METRIC}, {@link Span#NM} */
    public static int type() {
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

    /** The large unit the operator thinks in: miles, kilometers or nautical miles. */
    public static Span bigSpan() {
        switch (type()) {
            case Span.METRIC:
                return Span.KILOMETER;
            case Span.NM:
                return Span.NAUTICALMILE;
            default:
                return Span.MILE;
        }
    }

    public static String bigLabel() {
        switch (type()) {
            case Span.METRIC:
                return "km";
            case Span.NM:
                return "NM";
            default:
                return "mi";
        }
    }

    /** Convert a count of {@link #bigSpan()} units into meters. */
    public static double bigToMeters(double n) {
        try {
            return SpanUtilities.convert(n, bigSpan(), Span.METER);
        } catch (RuntimeException e) {
            return n * 1609.344;
        }
    }

    /** A distance the way ATAK writes it, in the operator's units. */
    public static String distance(double meters) {
        try {
            return SpanUtilities.formatType(type(), meters, Span.METER);
        } catch (RuntimeException e) {
            return String.format(java.util.Locale.US, "%.1f mi", meters / 1609.344);
        }
    }

    /** A ground area: acres or square miles, hectares or square kilometers. */
    public static String area(double squareMeters) {
        if (type() == Span.METRIC) {
            final double ha = squareMeters / 10_000d;
            return ha < 100 ? String.format(java.util.Locale.US, "%,.0f ha", ha)
                    : String.format(java.util.Locale.US, "%,.1f sq km", squareMeters / 1e6);
        }
        final double acres = squareMeters / 4046.8564224;
        return acres < 640 ? String.format(java.util.Locale.US, "%,.0f acres", acres)
                : String.format(java.util.Locale.US, "%,.1f sq mi", squareMeters / 2_589_988.11);
    }
}
